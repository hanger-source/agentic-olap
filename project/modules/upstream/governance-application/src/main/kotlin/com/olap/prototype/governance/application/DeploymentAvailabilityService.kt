package com.olap.prototype.governance.application

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.connector.OlapConnector
import com.olap.prototype.contract.JdbcSourceRequirement
import com.olap.prototype.engine.catalog.ExecutableCatalogManifest
import com.olap.prototype.engine.compiler.OlapSemanticCompiler
import com.olap.prototype.engine.context.OlapQueryContext
import com.olap.prototype.engine.executable.*
import com.olap.prototype.governance.contract.DeploymentAvailability
import com.olap.prototype.governance.contract.DeploymentAvailability.*
import com.olap.prototype.governance.contract.DeploymentAvailabilityRequest
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.definition.JdbcQueryDefinitionRepository
import com.olap.prototype.query.definition.QueryDefinitionArtifact
import com.olap.prototype.query.usage.QueryUsageRepository
import com.olap.prototype.release.catalog.JdbcPublishedQueryEntryProvider
import com.olap.prototype.release.catalog.ReleaseArtifactRepository
import com.olap.prototype.runtime.BoundedJson
import com.olap.prototype.runtime.JsonResourceLimits
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.storage.schema.PlatformSchemaInitializer
import com.olap.prototype.warehouse.contract.SourceBindingRef
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.WarehouseExecutionControl

/** 核验本部署能否读取和编译所选精确资产；不连接业务源，不执行、迁移或重放。 */
class DeploymentAvailabilityService(private val database: PlatformDatabase, private val connectors: ConnectorRegistry, private val limits: JsonResourceLimits) {
    private val log = org.slf4j.LoggerFactory.getLogger(DeploymentAvailabilityService::class.java)
    private val definitions = JdbcQueryDefinitionRepository(database, limits)
    private val publications = JdbcPublishedQueryEntryProvider(database)
    private val artifacts = ReleaseArtifactRepository(database)
    private val runs = QueryUsageRepository(database, limits)

    fun read(request: DeploymentAvailabilityRequest, deployedBindings: Map<SourceBindingRef, SourceConfiguration>, control: WarehouseExecutionControl,
        authorizeSource: (String) -> Unit): DeploymentAvailability = database.inReadSnapshot {
        control.checkCancellation()
        val checks = mutableListOf<Check>()
        val supported = mapOf("storageFormat" to PlatformSchemaInitializer.supportedFormatVersion.toString(),
            "catalogSchema" to ExecutableCatalogManifest.SCHEMA_VERSION, "publicationFormat" to PublishedExecutableCatalog.FORMAT_VERSION,
            "connectorApi" to OlapConnector.API_VERSION, "definitionFormat" to QueryDefinitionArtifact.FORMAT_VERSION, "intentFormat" to PublishedQueryContract.FORMAT_VERSION)
        checks.add(check(Kind.METADATA, mapOf("asset" to "platform-storage")) {
            val actual = query("SELECT format_version FROM platform_storage_format WHERE singleton_id=1") { it.getInt(1) }.single()
            format("storageFormat", actual.toString(), supported.getValue("storageFormat"))
        })
        request.publications.forEach { coordinate ->
            control.checkCancellation()
            val reference = publicationReference(coordinate)
            var publication: JdbcPublishedQueryEntryProvider.Publication? = null
            val stored = check(Kind.PUBLICATION, reference) { publication = publication(coordinate) }
            checks.add(stored)
            checks.add(publication?.let { available -> check(Kind.QUERY_EXECUTION, reference + ("basis" to "EXACT_FROZEN_DEPLOYMENT_REQUIREMENTS")) {
                requireDeployed(available.executable, deployedBindings, authorizeSource)
            } } ?: blocked(Kind.QUERY_EXECUTION, reference, stored))
            control.checkCancellation()
        }
        request.queries.forEach { reference ->
            control.checkCancellation()
            val coordinate = mapOf<String, Any>("definitionId" to reference.definitionId, "definitionVersion" to reference.definitionVersion, "sha256" to reference.sha256)
            var definition: QueryDefinitionView? = null
            val stored = check(Kind.DEFINITION, coordinate) {
                val raw = textRow("SELECT artifact_json FROM query_definition_version WHERE definition_id=? AND version=?", listOf(reference.definitionId, reference.definitionVersion), "SAVED_QUERY_MISSING")
                val envelope = json(raw)
                format("definitionFormat", envelope.path("formatVersion").asText(), QueryDefinitionArtifact.FORMAT_VERSION)
                val view = requireNotNull(definitions.findByIdAndVersion(reference.definitionId, reference.definitionVersion)) { "saved query disappeared from read snapshot" }
                val exact = definitions.requireReference(reference.definitionId, reference.definitionVersion)
                if (exact.sha256 != reference.sha256) problem(Status.INVALID, "SAVED_QUERY_HASH_MISMATCH", "saved query reference differs from its stored artifact")
                definition = view
            }
            checks.add(stored)
            checks.add(definition?.let { view -> check(Kind.QUERY_EXECUTION, coordinate + ("basis" to "AUTHORIZED_COMPILE_WITHOUT_SOURCE_EXECUTION")) {
                format("intentFormat", view.intent.path("formatVersion").asText(), PublishedQueryContract.FORMAT_VERSION)
                val selected = publication(QueryCapabilityRequest(view.scenarioId, view.scenarioVersion, view.releaseId, view.releaseVersion))
                require(view.catalogHash == selected.entry.catalogHash && view.capabilityHash == selected.entry.capability.capabilityHash) { "saved query differs from its exact frozen publication" }
                PublishedQueryContract.authorize(view.intent, selected.entry.capability)
                val computation = PublishedQueryContract.parseIntent(view.intent, selected.entry.capability)
                val queryId = "availability:${view.definitionId}@${view.definitionVersion}"
                control.checkCancellation()
                val compiled = OlapSemanticCompiler(CalciteRelCompiler(connectors)).compile(selected.executable.catalog,
                    ExecutableQueryRequest(ExecutableQueryRequest.FORMAT_VERSION, queryId, selected.executable.catalog.manifest.catalogId, computation),
                    OlapQueryContext(queryId), object : ExecutableExecutionObserver {
                        override fun bindingCompleted(input: ExecutableInputScope) = control.checkCancellation()
                        override fun stageAnalysisCompleted(analyses: List<com.olap.prototype.query.QueryStageAnalysis.Analysis>) = control.checkCancellation()
                        override fun sourceResolutionCompleted(sources: List<com.olap.prototype.engine.compiler.CompiledQuery.SourceEvidence>) = control.checkCancellation()
                        override fun loweringCompleted(loweringResult: ExecutableLoweringResult) = control.checkCancellation()
                    })
                control.checkCancellation()
                val lowering = requireNotNull(compiled.executableLoweringResult)
                lowering.sourceRequirements.forEach { requirement ->
                    requireDeployed(requirement.bindingRef(), requirement.connectorRef, requirement.dialect, deployedBindings, authorizeSource)
                }
                val binding = JdbcSourceRequirement.requireSingleBinding(lowering.sourceRequirements)
                val source = deployedBindings.getValue(binding)
                control.checkCancellation()
                val capabilities = connectors.require(lowering.connectorRef).access.capabilities(source)
                lowering.toSourceRequest("execute-query", null).requireCapabilities(source.dataSourceId, capabilities)
                control.checkCancellation()
            } } ?: blocked(Kind.QUERY_EXECUTION, coordinate, stored))
            control.checkCancellation()
        }
        request.results.forEach { reference ->
            control.checkCancellation()
            val coordinate = mapOf<String, Any>("runId" to reference.runId, "sha256" to reference.sha256)
            val manifest = check(Kind.RESULT_READ, coordinate) {
                textRow("SELECT run_id FROM query_run WHERE run_id=?", listOf(reference.runId), "RESULT_RUN_MISSING")
                val run = runs.require(reference.runId)
                val result = run.result ?: problem(Status.MISSING, "RESULT_MANIFEST_MISSING", "run has no saved result manifest")
                if (result.sha256 != reference.sha256) problem(Status.INVALID, "RESULT_REFERENCE_MISMATCH", "result hash differs from the committed manifest")
            }
            checks.add(if (manifest.status == Status.AVAILABLE) manifest.copy(status = Status.NOT_CHECKED,
                issues = listOf(Issue("RESULT_FILE_CHECK_REQUIRED", "stored manifest verified; result bytes and format require the result store check"))) else manifest)
            control.checkCancellation()
        }
        DeploymentAvailability(supported, checks.toList())
    }

    private fun publication(coordinate: QueryCapabilityRequest): JdbcPublishedQueryEntryProvider.Publication {
        val raw = textRow("SELECT executable_catalog_json FROM release_executable_catalog WHERE scenario_id=? AND scenario_version=? AND release_id=? AND release_version=?",
            listOf(coordinate.scenarioId, coordinate.scenarioVersion, coordinate.releaseId, coordinate.releaseVersion), "PUBLICATION_MISSING")
        val envelope = json(raw)
        format("publicationFormat", envelope.path("formatVersion").asText(), PublishedExecutableCatalog.FORMAT_VERSION)
        format("catalogSchema", envelope.path("catalog").path("manifest").path("schemaVersion").asText(), ExecutableCatalogManifest.SCHEMA_VERSION)
        format("executionTypesFormat", envelope.path("queryCapability").path("executionTypes").path("formatVersion").asText(), QueryExecutionTypes.FORMAT_VERSION)
        artifacts.loadArtifact(coordinate.releaseId, coordinate.releaseVersion)
        return publications.readPublication(coordinate).also { it.executable.validate() }
    }
    private fun requireDeployed(publication: PublishedExecutableCatalog, deployed: Map<SourceBindingRef, SourceConfiguration>, authorizeSource: (String) -> Unit) {
        publication.catalog.physicalExecutionBindings.values.forEach { binding ->
            requireDeployed(SourceBindingRef(binding.dataSourceId, binding.sourceConstraints.sourceIdentityHash), binding.connectorRef, binding.dialect, deployed, authorizeSource)
        }
    }
    private fun requireDeployed(binding: SourceBindingRef, connector: com.olap.prototype.connector.ConnectorRef, dialect: String, deployed: Map<SourceBindingRef, SourceConfiguration>, authorizeSource: (String) -> Unit) {
        authorizeSource(binding.dataSourceId)
        if (connector !in connectors.references()) problem(Status.UNSUPPORTED, "EXACT_CONNECTOR_NOT_DEPLOYED", "exact frozen connector is not deployed for the current connector API",
            mapOf("connectorRef" to connector, "requiredApi" to OlapConnector.API_VERSION))
        val source = deployed[binding] ?: problem(Status.MISSING, "EXACT_SOURCE_BINDING_NOT_DEPLOYED", "exact frozen source binding is not deployed", mapOf("binding" to binding))
        require(source.bindingRef() == binding && source.connectorRef == connector && source.dialectId == dialect) { "deployed source differs from its exact frozen execution requirements" }
    }
    private fun publicationReference(coordinate: QueryCapabilityRequest): Map<String, Any> = mapOf("scenarioId" to coordinate.scenarioId, "scenarioVersion" to coordinate.scenarioVersion,
        "releaseId" to coordinate.releaseId, "releaseVersion" to coordinate.releaseVersion)
    private fun blocked(kind: Kind, reference: Map<String, Any>, prerequisite: Check): Check = Check(kind, reference, Status.NOT_CHECKED,
        listOf(Issue("PREREQUISITE_UNAVAILABLE", "${prerequisite.kind} is ${prerequisite.status}")))
    private fun check(kind: Kind, reference: Map<String, Any>, action: () -> Unit): Check = try {
        action(); Check(kind, reference, Status.AVAILABLE)
    } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
    catch (denied: SecurityException) { throw denied }
    catch (failure: AvailabilityProblem) { Check(kind, reference, failure.status, listOf(failure.issue)) }
    catch (failure: com.olap.prototype.runtime.RuntimeResourceLimitException) { Check(kind, reference, Status.UNSUPPORTED,
        listOf(Issue(failure.code, "selected asset exceeds this deployment's inspection budget"))) }
    catch (failure: com.olap.prototype.connector.SourceCapabilityException) { Check(kind, reference, Status.UNSUPPORTED,
        listOf(Issue(failure.code, failure.message, mapOf("dataSourceId" to failure.dataSourceId, "operation" to failure.operation, "capability" to failure.capability.name)))) }
    catch (failure: com.olap.prototype.connector.ConnectorUnavailableException) { Check(kind, reference, Status.UNSUPPORTED,
        listOf(Issue("EXACT_CONNECTOR_NOT_DEPLOYED", "exact frozen connector is not deployed for the current connector API", mapOf("connectorRef" to failure.connectorRef, "requiredApi" to OlapConnector.API_VERSION)))) }
    catch (failure: com.olap.prototype.engine.EngineExecutionException) {
        log.warn("availability compilation failed: kind={} reference={}", kind, reference, failure)
        compilationFailure(kind, reference, failure)
    }
    catch (failure: Exception) {
        log.warn("availability validation failed: kind={} reference={}", kind, reference, failure)
        Check(kind, reference, Status.INVALID, listOf(Issue("ASSET_INVALID", "selected exact asset failed validation; consult the authorized service diagnostics")))
    }
    private fun compilationFailure(kind: Kind, reference: Map<String, Any>, failure: com.olap.prototype.engine.EngineExecutionException): Check {
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = failure
        while (cause != null && visited.add(cause)) {
            when (cause) {
                is com.olap.prototype.connector.NativeAggregateMappingException -> return Check(kind, reference, Status.UNSUPPORTED,
                    listOf(Issue(cause.code, "native aggregate mapping is unavailable for the declared input type", mapOf("aggregate" to cause.aggregate.name, "inputType" to cause.inputType))))
                is com.olap.prototype.connector.ConnectorUnavailableException -> return Check(kind, reference, Status.UNSUPPORTED,
                    listOf(Issue("EXACT_CONNECTOR_NOT_DEPLOYED", "exact frozen connector is not deployed for the current connector API",
                        mapOf("connectorRef" to cause.connectorRef, "requiredApi" to OlapConnector.API_VERSION))))
                is com.olap.prototype.connector.SourceCapabilityException -> return Check(kind, reference, Status.UNSUPPORTED,
                    listOf(Issue(cause.code, "source operation requires an unsupported capability",
                        mapOf("dataSourceId" to cause.dataSourceId, "operation" to cause.operation, "capability" to cause.capability.name))))
                is com.olap.prototype.connector.SourceBindingUnavailableException -> return Check(kind, reference, Status.MISSING,
                    listOf(Issue("EXACT_SOURCE_BINDING_NOT_DEPLOYED", "exact frozen source binding is not deployed", mapOf("binding" to cause.binding))))
                is com.olap.prototype.engine.catalog.SourceBindingConflictException -> return Check(kind, reference, Status.INVALID,
                    listOf(Issue(cause.code, "one query requires one exact source binding", mapOf("inputs" to cause.inputs))))
                is com.olap.prototype.runtime.RuntimeResourceLimitException -> return Check(kind, reference, Status.UNSUPPORTED,
                    listOf(Issue(cause.code, "selected asset exceeds this deployment's inspection budget")))
            }
            cause = cause.cause
        }
        return Check(kind, reference, Status.INVALID, listOf(Issue(failure.code, "selected query cannot be compiled by this deployment; consult the authorized service diagnostics",
            mapOf("stage" to failure.stage))))
    }
    private fun textRow(sql: String, values: List<Any>, missing: String): String = query(sql, values) { it.getString(1) }.singleOrNull()
        ?: problem(Status.MISSING, missing, "selected exact asset does not exist")
    private fun <T> query(sql: String, values: List<Any> = emptyList(), read: (java.sql.ResultSet) -> T): List<T> = database.connection().use { connection ->
        connection.prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
        }
    }
    private fun json(value: String): JsonNode = BoundedJson.decode(value.toByteArray(Charsets.UTF_8), limits)
    private fun format(name: String, actual: String, supported: String) {
        if (actual != supported) problem(Status.UNSUPPORTED, "UNSUPPORTED_ASSET_FORMAT", "$name is unsupported; no migration or compatibility reader is applied", mapOf("format" to name, "actual" to actual, "supported" to supported))
    }
    private class AvailabilityProblem(val status: Status, val issue: Issue) : RuntimeException(issue.message)
    private fun problem(status: Status, code: String, message: String, details: Map<String, Any?> = emptyMap()): Nothing = throw AvailabilityProblem(status, Issue(code, message, details))
}
