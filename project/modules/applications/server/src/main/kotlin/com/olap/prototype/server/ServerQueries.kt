package com.olap.prototype.server

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.codec.*
import com.olap.prototype.connector.loader.DeployedSources
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.dsl.*
import com.olap.prototype.protocol.CapabilityEffect
import com.olap.prototype.query.QueryExplanationContent
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.definition.*
import com.olap.prototype.query.usage.QueryResultArtifact
import com.olap.prototype.query.usage.QueryUsageRepository
import com.olap.prototype.release.catalog.JdbcPublishedQueryEntryProvider
import com.olap.prototype.runtime.*
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.contract.*
import java.time.Instant
import java.util.UUID

/** 完整模型从正式能力入口进入；来源解析、实际执行与结果交付分别记录真实事实。 */
internal class ServerQueries(private val queries: JdbcQueryDefinitionRepository,
    private val publications: JdbcPublishedQueryEntryProvider, private val authorization: ServerAuthorization,
    private val workers: WorkerPool, private val sources: DeployedSources,
    private val usage: QueryUsageRepository, private val results: ServerResults,
    private val historyEnabled: Boolean, private val limits: JsonResourceLimits) {
    private val mapper = OlapJsonUtils.mapper()
    private val log = org.slf4j.LoggerFactory.getLogger(ServerQueries::class.java)
    private data class Selected(val intent: QueryIntent, val reference: QueryReference?)
    private fun definitions(request: ServerRequest) = QueryDefinitionService(queries, publications) { intent, capability ->
        val publication = publications.readPublication(PublishedQueryEntry.key(capability))
        requireResolution(intent, inspect(request, intent, publication, InspectionMode.VALIDATE), publication)
    }
    private fun requireQuery(request: ServerRequest, reference: QueryReference): QueryDefinitionView {
        require(queries.requireReference(reference.definitionId, reference.definitionVersion) == reference) {
            "saved query reference differs from exact bytes"
        }
        return definitions(request).requireByIdAndVersion(reference.definitionId, reference.definitionVersion)
    }
    private fun select(request: ServerRequest, query: QuerySelection): Selected = when (query) {
        is QuerySelection.Inline -> Selected(query.intent, null)
        is QuerySelection.Saved -> Selected(requireQuery(request, query.reference).intent, query.reference)
    }

    fun execute(request: ServerRequest, input: QueryExecutionRequest): QueryExecutionReceipt {
        val started = System.nanoTime()
        val selected = select(request, input.query)
        val publication = request.work.timed("METADATA_LOAD_PUBLICATION") { publications.readPublication(selected.intent.publication) { stage, nanos ->
            request.work.timingNanos.merge("METADATA_$stage", nanos, Long::plus)
        } }
        request.work.timed("METADATA_AUTHORIZE_PUBLICATION") { authorization.publication(request.principal, "execute-query", publication.entry) }
        val run = usage.accept("run-${UUID.randomUUID().toString().replace("-", "")}", request.principal.subject,
            publication.entry, selected.intent, selected.reference, historyEnabled, input.saveResult, input.question)
        val executed = executeAccepted(request, selected, publication, run, input.readBasis)
        val receipt = executed.receipt.copy(timingNanos = executed.receipt.timingNanos + request.work.timingNanos +
            ("METADATA_TOTAL" to System.nanoTime() - started))
        val prepared = try { request.work.timed("METADATA_PREPARE_ARTIFACT") {
            QueryResultArtifact.prepare(SavedQueryResultRecord(SavedQueryResultRecord.FORMAT, run.runId,
                selected.intent.publication, publication.entry.catalogHash, QueryCapabilityHash.requireValid(publication.entry.capability),
                selected.intent, selected.reference, run.acceptedAt, Instant.now(), receipt, executed.payload?.explanation,
                executed.payload?.diagnostics?.let { QueryExecutionDiagnostics(it.sql, it.aliasMapping, it.auditReport) }, run.intentHash))
        } } catch (failure: Exception) { usage.resultFailed(run.runId, "result artifact preparation failed"); throw failure }
        return results.complete(request, prepared, receipt)
    }

    private data class Executed(val receipt: QueryExecutionReceipt, val payload: EngineExecutionPayload?)
    private fun executeAccepted(request: ServerRequest, selected: Selected,
        publication: JdbcPublishedQueryEntryProvider.Publication, run: QueryUsageRepository.Run,
        readBasis: SourceReadBasis.Versioned?): Executed {
        var payload: EngineExecutionPayload? = null
        var dispatched = false
        var status = QueryExecutionStatus.SUCCEEDED
        var client = QueryClientOutcome.COMPLETED
        var sourceState = SourceExecutionState.NOT_SUBMITTED
        var sourceRequest: SourceRequestEvidence? = null
        var readEvidence: SourceReadEvidence? = null
        var error: QueryExecutionFailure? = null
        var errorRecord: String? = null
        val timings = linkedMapOf<String, Long>()
        try {
            val resolution = inspect(request, selected.intent, publication, InspectionMode.RESOLVE)
            resolution.timingNanos.forEach { (stage, nanos) -> timings["RESOLVE_$stage"] = nanos }
            requireResolution(selected.intent, resolution, publication)
            authorization.source(request.principal, "execute-query", resolution.binding.dataSourceId)
            val source = sources.requireConfiguration(resolution.binding)
            require(source.connectorRef == resolution.connectorRef) { "resolved connector differs from exact deployed source" }
            payload = workers.borrow(WorkerPool.Kind.ENGINE, listOf(source), request.work).use { worker ->
                WorkerPublications.engine(worker, publication)
                usage.dispatched(run.runId)
                dispatched = true
                (worker.engine(EngineWorkerCommand.Execute(publication.executable.catalog.manifest.catalogId,
                    run.runId, selected.intent, listOf(source.bindingRef()), readBasis)) as EngineWorkerResult.Executed).value
            }
            require(payload.publication == selected.intent.publication && payload.requestId == run.runId) {
                "engine execution differs from accepted query"
            }
            readEvidence = payload.sourceReadEvidence
            sourceRequest = readEvidence.request
            sourceState = SourceExecutionState.SUCCEEDED
            timings.putAll(payload.stageNanos)
        } catch (failure: Exception) {
            log.warn("query execution failed: runId={}", run.runId, failure)
            val projected = ServerFailures.project(failure, "execution", "EXECUTION_FAILED")
            errorRecord = mapper.writeValueAsString(projected)
            error = QueryExecutionFailure(projected.stage, projected.code, projected.message,
                projected.details["diagnosticId"] as? String, (projected.details["fieldPath"] as? List<*>)?.map { it.toString() } ?: emptyList())
            val workerFailure = failure as? WorkerPool.WorkerFailure
            readEvidence = workerFailure?.sourceReadEvidence
            sourceRequest = readEvidence?.request ?: workerFailure?.sourceFailure?.request
            timings.putAll(workerFailure?.stageNanos.orEmpty())
            client = when {
                failure is java.util.concurrent.CancellationException || workerFailure?.error?.path("code")?.textValue() == "CANCELLED" -> QueryClientOutcome.CANCELLED
                failure is RuntimeResourceLimitException && failure.code in setOf("REQUEST_DEADLINE_EXCEEDED", "WORKER_DEADLINE_EXCEEDED") -> QueryClientOutcome.DEADLINE_EXCEEDED
                workerFailure?.error?.path("code")?.textValue() in setOf("REQUEST_DEADLINE_EXCEEDED", "WORKER_DEADLINE_EXCEEDED") -> QueryClientOutcome.DEADLINE_EXCEEDED
                else -> QueryClientOutcome.COMPLETED
            }
            val notStarted = workerFailure?.sourceExecutionStarted == false
            sourceState = sourceRequest?.execution ?: if (dispatched && !notStarted) SourceExecutionState.UNKNOWN else SourceExecutionState.NOT_SUBMITTED
            status = when {
                sourceState == SourceExecutionState.CANCELLED -> QueryExecutionStatus.CANCELLED
                sourceState in setOf(SourceExecutionState.UNKNOWN, SourceExecutionState.RUNNING) -> QueryExecutionStatus.OUTCOME_UNKNOWN
                !dispatched && client != QueryClientOutcome.COMPLETED -> QueryExecutionStatus.NOT_EXECUTED
                failure is WorkerPool.WorkerFailure -> QueryExecutionStatus.ENGINE_FAILED
                else -> QueryExecutionStatus.EXECUTION_FAILED
            }
        }
        val actual = payload.takeIf { status == QueryExecutionStatus.SUCCEEDED }
        val receipt = QueryExecutionReceipt(run.runId, status, null, actual?.result, false,
            actual?.result?.rows?.size?.toLong() ?: 0, client, sourceState, sourceRequest, readEvidence, timings.toMap(), error)
        val outcome = when (status) {
            QueryExecutionStatus.SUCCEEDED -> QueryUsageRepository.Execution.SUCCEEDED
            QueryExecutionStatus.OUTCOME_UNKNOWN -> QueryUsageRepository.Execution.INTERRUPTED
            QueryExecutionStatus.CANCELLED, QueryExecutionStatus.NOT_EXECUTED -> QueryUsageRepository.Execution.CANCELLED
            else -> QueryUsageRepository.Execution.FAILED
        }
        val interrupted = Thread.interrupted()
        try { request.work.timed("METADATA_COMMIT_EXECUTION") { usage.complete(run.runId, outcome, errorRecord, receipt.copy(result = null)) } }
        finally { if (interrupted) Thread.currentThread().interrupt() }
        return Executed(receipt, actual)
    }

    private fun inspect(request: ServerRequest, intent: QueryIntent,
        publication: JdbcPublishedQueryEntryProvider.Publication, mode: InspectionMode): EngineInputResolution =
        workers.borrow(WorkerPool.Kind.ENGINE, emptyList(), request.work).use { worker ->
            WorkerPublications.engine(worker, publication)
            (worker.engine(EngineWorkerCommand.Inspect(mode, publication.executable.catalog.manifest.catalogId,
                request.work.requestId, intent)) as EngineWorkerResult.Resolved).value
        }

    private fun requireResolution(intent: QueryIntent, resolution: EngineInputResolution,
        publication: JdbcPublishedQueryEntryProvider.Publication) {
        check(resolution.publication == intent.publication && resolution.catalogHash == publication.entry.catalogHash &&
            resolution.catalogId == publication.executable.catalog.manifest.catalogId && resolution.intentHash == QueryProto.digest(intent)) {
            "resolved execution input differs from exact request"
        }
    }

    fun validate(request: ServerRequest, input: QueryValidationRequest): QueryValidationResult {
        val selected = select(request, input.query)
        val publication = publications.readPublication(selected.intent.publication)
        authorization.publication(request.principal, "validate-query", publication.entry)
        requireResolution(selected.intent, inspect(request, selected.intent, publication, InspectionMode.VALIDATE), publication)
        return QueryValidationResult(true, QueryCapabilityHash.requireValid(publication.entry.capability))
    }

    fun explain(request: ServerRequest, input: QueryExplanationRequest): QueryExplanationResult {
        val selected = select(request, input.query)
        val publication = publications.readPublication(selected.intent.publication)
        authorization.publication(request.principal, "explain-query", publication.entry)
        val resolution = inspect(request, selected.intent, publication, InspectionMode.EXPLAIN)
        requireResolution(selected.intent, resolution, publication)
        authorization.source(request.principal, "explain-query", resolution.binding.dataSourceId)
        val view = requireNotNull(resolution.explanation)
        fun next(size: Int): Int? = if (input.offset.toLong() + input.limit < size) input.offset + input.limit else null
        val content = when (input.section) {
            QueryExplanationSection.SUMMARY -> { require(input.offset == 0); QueryExplanationContent.Summary(view.summary) }
            QueryExplanationSection.SCOPES -> QueryExplanationContent.Scopes(view.scopes.drop(input.offset).take(input.limit), view.scopes.size, next(view.scopes.size))
            QueryExplanationSection.SOURCES -> QueryExplanationContent.Sources(view.sources.drop(input.offset).take(input.limit), view.sources.size, next(view.sources.size))
            QueryExplanationSection.PLAN -> QueryExplanationContent.Plan(view.plan.drop(input.offset).take(input.limit), view.plan.size, next(view.plan.size))
            QueryExplanationSection.COMPILATION_SCALE -> { require(input.offset == 0); QueryExplanationContent.CompilationScale(view.compilationScale) }
            QueryExplanationSection.EXECUTION_REQUIREMENTS -> { require(input.offset == 0); QueryExplanationContent.ExecutionRequirements(view.executionRequirements) }
        }
        return QueryExplanationResult(input.section, content)
    }

    private fun definitionResult(view: QueryDefinitionView) = QueryDefinitionResult(view,
        queries.requireReference(view.definitionId, view.definitionVersion))

    fun save(operation: String, request: ServerRequest, command: QueryDefinitionCommand): QueryDefinitionResult {
        authorization.publication(request.principal, operation, publications.require(command.intent.publication))
        if (operation == "update-query") {
            val previous = definitions(request).requireByIdAndVersion(requireNotNull(command.definitionId), requireNotNull(command.sourceVersion))
            authorization.publication(request.principal, operation, publications.require(previous.intent.publication))
        }
        val result = definitionResult(if (operation == "save-query") definitions(request).save(command, request.principal.subject)
        else definitions(request).update(requireNotNull(command.definitionId), command, request.principal.subject))
        request.completedEffects.add(CapabilityEffect.newBuilder().setReadOperation("read-query")
            .setQuery(QueryProto.toProto(result.reference)).build())
        return result
    }
    fun read(request: ServerRequest, input: QueryVersionRequest): QueryDefinitionResult = definitionResult(requireQuery(request, input.queryReference).also {
        authorization.publication(request.principal, "read-query", publications.require(it.intent.publication))
    })
    fun list(request: ServerRequest, input: QueryListRequest): QueryDefinitionPage {
        authorization.publication(request.principal, "list-queries", publications.require(input.coordinate))
        val p = queries.page(input.coordinate.scenarioId, input.coordinate.scenarioVersion, input.coordinate.releaseId,
            input.coordinate.releaseVersion, input.offset, Math.addExact(input.limit, 1))
        return QueryDefinitionPage(p.take(input.limit).map(::definitionResult), if (p.size > input.limit) Math.addExact(input.offset, input.limit) else null)
    }
}
