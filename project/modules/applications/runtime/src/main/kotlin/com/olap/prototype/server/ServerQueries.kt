package com.olap.prototype.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.protobuf.ByteString
import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.connector.loader.DeployedSources
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.protocol.Response
import com.olap.prototype.protocol.ResultArtifact
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.definition.*
import com.olap.prototype.query.usage.QueryResultArtifact
import com.olap.prototype.query.usage.QueryUsageRepository
import com.olap.prototype.release.catalog.JdbcPublishedQueryEntryProvider
import com.olap.prototype.server.ServerInputs.command
import com.olap.prototype.server.ServerInputs.fields
import com.olap.prototype.server.ServerInputs.integer
import com.olap.prototype.server.ServerInputs.optionalText
import com.olap.prototype.server.ServerInputs.text
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.contract.SourceBindingRef
import com.olap.prototype.warehouse.contract.SourceConfiguration
import java.time.Instant
import java.util.UUID

/** 保存/验证与一次实际执行；worker调度只经显式Go监督者端口。 */
internal class ServerQueries(private val queries: JdbcQueryDefinitionRepository,
    private val publications: JdbcPublishedQueryEntryProvider, private val authorization: ServerAuthorization,
    private val workers: RemoteWorkers, private val sources: DeployedSources,
    private val usage: QueryUsageRepository, private val results: ServerResults,
    private val historyEnabled: Boolean, private val maximumArtifactBytes: Long) {
    private val mapper = OlapJsonUtils.mapper()
    private val log = org.slf4j.LoggerFactory.getLogger(ServerQueries::class.java)
    private data class SelectedQuery(val intent: JsonNode, val reference: QueryDefinitionArtifact.Reference?)

    private fun definitions(request: ServerRequest) = QueryDefinitionService(queries, publications) { intent, capability ->
        validateNative(request, intent, capability)
    }

    private fun requireQuery(request: ServerRequest, reference: QueryDefinitionArtifact.Reference): QueryDefinitionView {
        if (queries.requireReference(reference.definitionId, reference.definitionVersion) != reference)
            throw ProducerContractException("saved query reference hash differs from exact bytes")
        return definitions(request).requireByIdAndVersion(reference.definitionId, reference.definitionVersion)
    }

    private fun coordinate(query: QueryDefinitionView) = QueryCapabilityRequest(query.scenarioId,
        query.scenarioVersion, query.releaseId, query.releaseVersion)

    private fun resolveQuery(request: ServerRequest, payload: JsonNode, extra: Set<String> = emptySet()): SelectedQuery {
        fields(payload, setOf("intent", "queryReference", "saveResult", "question", "readBasis") + extra)
        if (payload.has("intent") == payload.has("queryReference"))
            throw ProducerContractException("select exactly one complete intent or exact queryReference")
        if (payload.has("intent")) return SelectedQuery(payload.required("intent"), null)
        val reference = mapper.treeToValue(payload.required("queryReference"), QueryDefinitionArtifact.Reference::class.java)
        return SelectedQuery(requireQuery(request, reference).intent, reference)
    }

    private fun validateNative(request: ServerRequest, intent: JsonNode, capability: QueryCapabilityView) {
        val publication = publications.readPublication(PublishedQueryEntry.key(capability))
        val definition = (intent as ObjectNode).deepCopy().put("requestId", request.work.requestId)
        requireResolution(definition, resolveInput(request, definition, publication, "validate"), publication)
    }

    fun execute(request: ServerRequest, payload: JsonNode): Response {
        val started = System.nanoTime()
        val selected = resolveQuery(request, payload)
        val publication = request.work.timed("METADATA_LOAD_PUBLICATION") { publications.readPublication(PublishedQueryContract.coordinate(selected.intent)) }
        request.work.timed("METADATA_AUTHORIZE_PUBLICATION") { authorization.publication(request.principal, "execute-query", publication.entry) }
        if (payload.has("saveResult") && !payload.get("saveResult").isBoolean)
            throw ProducerContractException("saveResult must be boolean")
        val save = payload.path("saveResult").asBoolean(false)
        val run = usage.accept("run-${UUID.randomUUID().toString().replace("-", "")}", request.principal.subject,
            publication.entry, selected.intent, selected.reference, historyEnabled, save, optionalText(payload, "question"))
        val record = executeAccepted(request, selected, publication, run, payload.get("readBasis"))
        val prepared = request.work.timed("METADATA_PREPARE_ARTIFACT") { QueryResultArtifact.prepare(record, selected.intent, maximumArtifactBytes) }
        request.work.timed("METADATA_REMEMBER_RESULT") { results.remember(request, prepared) }
        val output = mapper.createObjectNode().put("runId", run.runId).put("status", record.required("status").textValue())
            .putNull("result").putNull("resultReference")
        for (field in listOf("timingNanos", "clientOutcome", "sourceOutcome", "sourceReadEvidence", "error"))
            output.set<JsonNode>(field, record.required(field))
        (output.required("timingNanos") as ObjectNode).apply {
            request.work.timingNanos.forEach { (stage, nanos) -> put(stage, nanos) }
            put("METADATA_TOTAL", System.nanoTime() - started)
        }
        return Response.newBuilder().setRequestId(request.work.requestId).setSuccess(true)
            .setResultJson(ByteString.copyFrom(mapper.writeValueAsBytes(output)))
            .setArtifact(ResultArtifact.newBuilder().setRunId(prepared.runId).setSha256(prepared.sha256)
                .setContent(ByteString.copyFrom(prepared.content)).setRows(prepared.rows).setSave(save)).build()
    }

    private fun executeAccepted(request: ServerRequest, selected: SelectedQuery,
        publication: JdbcPublishedQueryEntryProvider.Publication, run: QueryUsageRepository.Run, readBasis: JsonNode?): JsonNode {
        var result: JsonNode? = null
        var error: JsonNode? = null
        var status = "SUCCEEDED"
        var dispatched = false
        var clientOutcome = "COMPLETED"
        var sourceOutcome: JsonNode = mapper.valueToTree(mapOf("state" to "NOT_SUBMITTED"))
        var readEvidence: JsonNode = mapper.nullNode()
        val timings = mapper.createObjectNode()
        fun evidence(): JsonNode = mapper.valueToTree(mapOf("clientOutcome" to clientOutcome,
            "sourceOutcome" to sourceOutcome, "sourceReadEvidence" to readEvidence,
            "timingNanos" to timings.deepCopy().apply { request.work.timingNanos.forEach { (stage, nanos) -> put(stage, nanos) } }))
        try {
            val definition = (selected.intent as ObjectNode).deepCopy().put("requestId", run.runId)
            val resolution = resolveInput(request, definition, publication)
            resolution.required("timingNanos").fields().forEachRemaining { (stage, nanos) -> timings.set<JsonNode>("RESOLVE_$stage", nanos) }
            requireResolution(definition, resolution, publication)
            val source = authorizedSource(request, resolution)
            dispatch(request, definition, source, publication, run.runId, readBasis, { dispatched = true }) { response ->
                result = response
                readEvidence = response.path("computation").required("sourceReadEvidence")
                timings.setAll<ObjectNode>(response.required("stageNanos") as ObjectNode)
                sourceOutcome = mapper.valueToTree(mapOf("state" to "SUCCEEDED", "request" to readEvidence.required("request")))
            }
            request.work.timed("METADATA_COMMIT_EXECUTION") { usage.complete(run.runId, QueryUsageRepository.Execution.SUCCEEDED, null, evidence()) }
        } catch (failure: Exception) {
            log.warn("query execution failed: runId={}", run.runId, failure)
            if (failure is RemoteWorkers.WorkerFailure) log.warn("query worker diagnostic: {}", failure.error)
            error = mapper.valueToTree(ServerFailures.project(failure, "execution", "EXECUTION_FAILED"))
            error.path("details").get("sourceReadEvidence")?.takeUnless { it.isNull }?.let { readEvidence = it }
            if (failure is RemoteWorkers.WorkerFailure)
                (failure.error.path("evidenceContext").get("stageNanos") as? ObjectNode)?.let { timings.setAll<ObjectNode>(it) }
            clientOutcome = when {
                failure is java.util.concurrent.CancellationException -> "CANCELLED"
                failure is com.olap.prototype.runtime.RuntimeResourceLimitException &&
                    failure.code in setOf("REQUEST_DEADLINE_EXCEEDED", "WORKER_DEADLINE_EXCEEDED") -> "DEADLINE_EXCEEDED"
                else -> "COMPLETED"
            }
            val sourceFailure = error.path("details").path("sourceFailure")
            val knownState = readEvidence.path("request").path("execution").textValue()
                ?: sourceFailure.path("request").path("execution").textValue()
            val sourceNotStarted = failure is RemoteWorkers.WorkerFailure &&
                failure.error.path("evidenceContext").path("sourceExecutionStarted").let { it.isBoolean && !it.booleanValue() }
            val sourceState = knownState ?: if (dispatched && !sourceNotStarted) "UNKNOWN" else "NOT_SUBMITTED"
            sourceOutcome = mapper.valueToTree(mapOf("state" to sourceState,
                "request" to (readEvidence.get("request") ?: sourceFailure.get("request")),
                "cancellation" to sourceFailure.get("cancellation")))
            status = when {
                sourceState == "CANCELLED" -> "CANCELLED"
                sourceState == "UNKNOWN" || sourceState == "RUNNING" -> "OUTCOME_UNKNOWN"
                !dispatched && clientOutcome != "COMPLETED" -> "NOT_EXECUTED"
                failure is RemoteWorkers.WorkerFailure -> "ENGINE_FAILED"
                else -> "EXECUTION_FAILED"
            }
            val outcome = when (status) {
                "OUTCOME_UNKNOWN" -> QueryUsageRepository.Execution.INTERRUPTED
                "CANCELLED", "NOT_EXECUTED" -> QueryUsageRepository.Execution.CANCELLED
                else -> QueryUsageRepository.Execution.FAILED
            }
            // 取消等待不能阻止提交已经发生的运行事实；不会因此重试来源查询。
            val interrupted = Thread.interrupted()
            try { request.work.timed("METADATA_COMMIT_EXECUTION") { usage.complete(run.runId, outcome, error.toString(), evidence()) } }
            finally { if (interrupted) Thread.currentThread().interrupt() }
        }
        return mapper.createObjectNode().apply {
            put("requestId", run.runId); put("runId", run.runId)
            put("catalogHash", publication.entry.catalogHash); put("capabilityHash", publication.entry.capability.capabilityHash)
            put("startedAt", run.acceptedAt.toString()); put("completedAt", Instant.now().toString()); put("status", status)
            put("clientOutcome", clientOutcome); set<JsonNode>("sourceOutcome", sourceOutcome)
            set<JsonNode>("timingNanos", evidence().required("timingNanos"))
            set<JsonNode>("sourceReadEvidence", readEvidence)
            set<JsonNode>("explanation", result?.get("explanation") ?: mapper.nullNode())
            set<JsonNode>("coordinate", mapper.valueToTree(publication.executable.coordinate))
            set<JsonNode>("queryReference", mapper.valueToTree(selected.reference))
            set<JsonNode>("result", result ?: mapper.nullNode()); set<JsonNode>("error", error ?: mapper.nullNode())
        }
    }

    private fun resolveInput(request: ServerRequest, definition: JsonNode,
        publication: JdbcPublishedQueryEntryProvider.Publication, operation: String = "resolve-input"): JsonNode =
        workers.borrow(RemoteWorkers.Kind.ENGINE, emptyList(), request.work).use {
            WorkerPublications.engine(it, publication)
            it.exchange(mapper.createObjectNode().put("operation", operation)
                .put("catalogId", publication.executable.catalog.manifest.catalogId)
                .set("queryDefinition", definition))
        }

    private fun requireResolution(definition: JsonNode, resolution: JsonNode,
        publication: JdbcPublishedQueryEntryProvider.Publication) {
        check(text(resolution, "formatVersion") == "olap-input-resolution/v2" &&
            mapper.treeToValue(resolution.required("publication"), QueryCapabilityRequest::class.java) == publication.executable.coordinate &&
            text(resolution, "catalogHash") == publication.entry.catalogHash &&
            text(resolution, "intentHash") == PublicationContentHash.compute(definition)) {
            "resolved execution input differs from exact request"
        }
    }

    private fun authorizedSource(request: ServerRequest, resolution: JsonNode): SourceConfiguration {
        val id = text(resolution, "dataSourceId")
        authorization.source(request.principal, "execute-query", id)
        val source = sources.requireConfiguration(SourceBindingRef(id, text(resolution, "sourceIdentityHash")))
        if (source.connectorRef != mapper.treeToValue(resolution.required("connectorRef"), ConnectorRef::class.java))
            throw ProducerContractException("resolved query connector differs from deployed source")
        return source
    }

    private fun dispatch(request: ServerRequest, definition: JsonNode, source: SourceConfiguration,
        publication: JdbcPublishedQueryEntryProvider.Publication, runId: String, readBasis: JsonNode?,
        dispatched: () -> Unit, responded: (JsonNode) -> Unit): JsonNode =
        workers.borrow(RemoteWorkers.Kind.ENGINE, listOf(source), request.work).use {
            WorkerPublications.engine(it, publication)
            val command = mapper.createObjectNode().put("operation", "execute")
                .put("catalogId", publication.executable.catalog.manifest.catalogId)
            command.set<JsonNode>("queryDefinition", definition)
            command.set<JsonNode>("dataSources", mapper.valueToTree(listOf(source)))
            command.set<JsonNode>("readBasis", readBasis ?: mapper.nullNode())
            usage.dispatched(runId)
            dispatched()
            it.exchange(command).also(responded)
        }

    fun validate(request: ServerRequest, payload: JsonNode): Any {
        require(!payload.has("readBasis")) { "readBasis is an execution requirement; query validation does not acquire source data" }
        val query = resolveQuery(request, payload)
        val entry = publications.require(PublishedQueryContract.coordinate(query.intent))
        authorization.publication(request.principal, "validate-query", entry)
        validateNative(request, query.intent, entry.capability)
        return mapOf("valid" to true, "capabilityHash" to entry.capability.capabilityHash)
    }

    fun explain(request: ServerRequest, payload: JsonNode): Any {
        val query = resolveQuery(request, payload, setOf("section", "offset", "limit"))
        require(!payload.has("readBasis")) { "explanation does not acquire or pin source data" }
        val publication = publications.readPublication(PublishedQueryContract.coordinate(query.intent))
        authorization.publication(request.principal, "explain-query", publication.entry)
        val definition = (query.intent as ObjectNode).deepCopy().put("requestId", request.work.requestId)
        val resolved = workers.borrow(RemoteWorkers.Kind.ENGINE, emptyList(), request.work).use {
            WorkerPublications.engine(it, publication)
            it.exchange(mapper.createObjectNode().put("operation", "explain")
                .put("catalogId", publication.executable.catalog.manifest.catalogId).set("queryDefinition", definition))
        }
        requireResolution(definition, resolved, publication)
        authorization.source(request.principal, "explain-query", text(resolved, "dataSourceId"))
        val section = optionalText(payload, "section") ?: "summary"
        require(section in setOf("summary", "scopes", "sources", "plan", "compilationScale")) { "unknown explanation section" }
        val value = resolved.required("explanation").required(section)
        val offset = integer(payload, "offset", 0, 0, Int.MAX_VALUE)
        val limit = integer(payload, "limit", 25, 1, 200)
        require(value.isArray || offset == 0) { "only explanation arrays accept an offset" }
        return mapOf("formatVersion" to "olap-query-explanation-page/v1", "coordinate" to publication.executable.coordinate,
            "section" to section, "timingNanos" to resolved.required("timingNanos"),
            "value" to if (value.isArray) value.drop(offset).take(limit) else value,
            "totalItems" to if (value.isArray) value.size() else 1,
            "nextOffset" to if (value.isArray && offset.toLong() + limit < value.size()) offset + limit else null)
    }

    fun save(operation: String, request: ServerRequest, payload: JsonNode): Any {
        val command = mapper.treeToValue(payload, QueryDefinitionCommand::class.java)
        val coordinate = QueryCapabilityRequest(command.scenarioId, command.scenarioVersion, command.releaseId, command.releaseVersion)
        authorization.publication(request.principal, operation, publications.require(coordinate))
        if (operation == "update-query") {
            val previous = definitions(request).requireByIdAndVersion(
                requireNotNull(command.definitionId) { "update-query requires exact definitionId" },
                requireNotNull(command.sourceVersion) { "update-query requires exact sourceVersion" })
            authorization.publication(request.principal, operation, publications.require(coordinate(previous)))
        }
        val saved = if (operation == "save-query") definitions(request).save(command, request.principal.subject)
            else definitions(request).update(requireNotNull(command.definitionId), command, request.principal.subject)
        return mapOf("definition" to saved, "queryReference" to queries.requireReference(saved.definitionId, saved.definitionVersion))
    }

    fun read(request: ServerRequest, payload: JsonNode): Any {
        fields(payload, setOf("queryReference"))
        val reference = mapper.treeToValue(payload.required("queryReference"), QueryDefinitionArtifact.Reference::class.java)
        val saved = requireQuery(request, reference)
        authorization.publication(request.principal, "read-query", publications.require(coordinate(saved)))
        return mapOf("definition" to saved, "queryReference" to reference)
    }

    fun list(request: ServerRequest, payload: JsonNode): Any {
        fields(payload, setOf("coordinate", "offset", "limit"))
        val coordinate = mapper.treeToValue(payload.required("coordinate"), QueryCapabilityRequest::class.java)
        authorization.publication(request.principal, "list-queries", publications.require(coordinate))
        return queries.page(coordinate.scenarioId, coordinate.scenarioVersion, coordinate.releaseId, coordinate.releaseVersion,
            integer(payload, "offset", 0, 0, Int.MAX_VALUE), integer(payload, "limit", 50, 1, 200))
    }
}
