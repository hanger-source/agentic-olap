package com.olap.prototype.runtime

import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.codec.PublicationProto
import com.olap.prototype.codec.QueryProto
import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.contract.InputContractException
import com.olap.prototype.connector.loader.DeployedSources
import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.engine.OlapEngine
import com.olap.prototype.engine.compiler.CompiledQuery
import com.olap.prototype.engine.compiler.OlapSemanticCompiler
import com.olap.prototype.engine.executable.*
import com.olap.prototype.engine.catalog.ExecutableCatalogManifest
import com.olap.prototype.query.QueryStageAnalysis
import com.olap.prototype.query.QueryExecutionMode
import com.olap.prototype.query.contract.*
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.contract.SourceReadEvidence
import java.security.MessageDigest
import java.util.HexFormat

/** 精确发布的编译与实际执行；不拥有进程、元数据库或凭据选择。 */
internal class EngineHandler(private val connectors: ConnectorRegistry, private val budgets: RuntimeBudgets,
    private val configured: List<SourceConfiguration>) {
    private data class Loaded(val publication: PublishedExecutableCatalog, val artifactHash: String,
        val contentHash: String, val bytes: Long)
    private val catalogs = linkedMapOf<String, Loaded>()
    private var catalogBytes = 0L

    fun execute(command: EngineWorkerCommand, control: WarehouseExecutionControl, sourceCompleted: (SourceReadEvidence) -> Unit): EngineWorkerResult = when (command) {
        is EngineWorkerCommand.Lookup -> {
            control.checkCancellation()
            val loaded = catalogs[command.catalogId]
            require(loaded == null || loaded.contentHash == command.contentHash) { "catalog ID already loaded with different immutable content" }
            EngineWorkerResult.Present(loaded != null)
        }
        is EngineWorkerCommand.Load -> load(command.publication, control)
        is EngineWorkerCommand.Unload -> {
            val id = command.catalogId
            val current = catalogs[id] ?: error("exact catalog is not loaded")
            require(current.contentHash == command.contentHash) { "unload must name exact immutable content" }
            catalogs.remove(id); catalogBytes -= current.bytes
            EngineWorkerResult.Unloaded(id, current.contentHash)
        }
        is EngineWorkerCommand.Inspect -> EngineWorkerResult.Resolved(resolve(command, control))
        is EngineWorkerCommand.Execute -> {
            require(command.bindings == configured.map { com.olap.prototype.warehouse.contract.SourceBindingRef(it.dataSourceId, it.sourceIdentityHash()) }) {
                "execution sources differ from the supervisor's exact worker capability"
            }
            EngineWorkerResult.Executed(run(command, control, sourceCompleted))
        }
    }

    private fun load(publication: PublishedExecutableCatalog, control: WarehouseExecutionControl): EngineWorkerResult.Loaded {
        val wire = GovernanceProto.toProto(publication)
        if (wire.serializedSize > budgets.catalogBytes) throw RuntimeResourceLimitException("CATALOG_BYTES_EXCEEDED")
        val encoded = ProtoBytes.encode(wire, budgets.catalogBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        require(publication.formatVersion == PublishedExecutableCatalog.FORMAT_VERSION) { "unsupported published executable catalog" }
        ExecutableCatalogManifest.requireSupportedSchema(publication.catalog.manifest.schemaVersion)
        val id = publication.catalog.manifest.catalogId
        val artifactHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded))
        catalogs[id]?.let {
            check(it.artifactHash == artifactHash) { "catalog ID already loaded with different immutable content" }
            control.checkCancellation()
            return EngineWorkerResult.Loaded(id, it.contentHash)
        }
        publication.validate()
        val contentHash = PublicationProto.contentHash(publication)
        control.checkCancellation()
        while (catalogs.isNotEmpty() && (catalogs.size >= budgets.catalogEntries || encoded.size > budgets.catalogBytes - catalogBytes)) {
            val oldest = catalogs.entries.first(); catalogBytes -= oldest.value.bytes; catalogs.remove(oldest.key)
        }
        catalogs[id] = Loaded(publication, artifactHash, contentHash, encoded.size.toLong())
        catalogBytes += encoded.size
        return EngineWorkerResult.Loaded(id, contentHash)
    }

    private fun authorize(publication: PublishedExecutableCatalog, id: String, requestId: String, intent: QueryIntent,
        executionMode: QueryExecutionMode): ExecutableQueryRequest = try {
        require(requestId.isNotBlank()) { "query request identity is required" }
        require(intent.publication == publication.coordinate) { "query publication differs from the frozen catalog" }
        PublishedQueryContract.authorize(intent, publication.queryCapability)
        ExecutableQueryRequest(ExecutableQueryRequest.FORMAT_VERSION, requestId, id, intent.computation, executionMode)
    } catch (failure: InputContractException) {
        throw failure
    } catch (failure: IllegalArgumentException) {
        throw EngineExecutionException("AUTHORIZE_INPUT", "EXECUTABLE_QUERY_NOT_PUBLISHED",
            "Query is outside the frozen publication: ${failure.message}", failure, sourceExecutionStarted = false)
    }

    private fun resolve(command: EngineWorkerCommand.Inspect, control: WarehouseExecutionControl): EngineInputResolution {
        val id = command.catalogId
        val loaded = catalogs[id] ?: error("exact catalog is not loaded")
        val request = authorize(loaded.publication, id, command.requestId, command.intent, command.executionMode)
        control.checkCancellation()
        val context = com.olap.prototype.engine.context.OlapQueryContext(request.requestId)
        val compiler = OlapSemanticCompiler(CalciteRelCompiler(connectors))
        val compiled = if (command.mode == InspectionMode.RESOLVE) null else compiler.compile(loaded.publication.catalog, request, context, observer(control))
        val (dataSourceId, sourceIdentityHash, connectorRef) = if (compiled == null) {
            val source = compiler.resolveSource(loaded.publication.catalog, request, context, observer(control))
            Triple(source.dataSourceId, source.adoption.binding.sourceIdentityHash, source.connectorRef)
        } else {
            val lowered = requireNotNull(compiled.executableLoweringResult) { "successful compilation must return its SQL contract" }
            Triple(lowered.dataSourceId, com.olap.prototype.contract.JdbcSourceRequirement.requireSingleBinding(lowered.sourceRequirements).sourceIdentityHash, lowered.connectorRef)
        }
        control.checkCancellation()
        return EngineInputResolution(loaded.publication.coordinate, request.requestId, id, loaded.contentHash,
            QueryProto.digest(command.intent), com.olap.prototype.warehouse.contract.SourceBindingRef(dataSourceId, sourceIdentityHash), connectorRef,
            context.stageTimes, if (command.mode == InspectionMode.EXPLAIN) QueryExplanation.describe(requireNotNull(compiled)) else null)
    }

    private fun observer(control: WarehouseExecutionControl, sourceCompleted: (SourceReadEvidence) -> Unit = {}): ExecutableExecutionObserver = object : ExecutableExecutionObserver {
        override fun bindingCompleted(input: ExecutableInputScope) = control.checkCancellation()
        override fun stageAnalysisCompleted(analyses: List<QueryStageAnalysis.Analysis>) = control.checkCancellation()
        override fun sourceResolutionCompleted(evidence: List<CompiledQuery.SourceEvidence>) = control.checkCancellation()
        override fun sourceCompleted(evidence: SourceReadEvidence) = sourceCompleted.invoke(evidence)
        override fun loweringCompleted(lowered: ExecutableLoweringResult) {
            control.checkCancellation()
            val sqlBytes = lowered.sql.toByteArray(Charsets.UTF_8).size.toLong() + lowered.relationChecks.sumOf { it.sql.toByteArray(Charsets.UTF_8).size.toLong() }
            val parameters = lowered.parameters.size.toLong() + lowered.relationChecks.sumOf { it.parameters.size.toLong() }
            if (sqlBytes > budgets.sqlBytes) throw RuntimeResourceLimitException("SQL_BYTES_EXCEEDED")
            if (parameters > budgets.parameters) throw RuntimeResourceLimitException("SQL_PARAMETERS_EXCEEDED")
        }
    }

    private fun run(command: EngineWorkerCommand.Execute, control: WarehouseExecutionControl, sourceCompleted: (SourceReadEvidence) -> Unit): EngineExecutionPayload {
        val id = command.catalogId
        val started = System.nanoTime()
        val stages = linkedMapOf<String, Long>()
        val loaded = catalogs[id] ?: throw IllegalArgumentException("catalog is not loaded: $id")
        val publication = loaded.publication
        var completedReadEvidence: SourceReadEvidence? = null
        var sourceExecutionStarted: Boolean? = false
        try {
            control.checkCancellation()
            val authorizationStart = System.nanoTime()
            val definition = try { authorize(publication, id, command.requestId, command.intent, command.executionMode) }
            finally { stages["COMPILE_AUTHORIZE"] = System.nanoTime() - authorizationStart }
            val sources = DeployedSources(configured, connectors)
            val engine = OlapEngine(OlapSemanticCompiler(CalciteRelCompiler(connectors)),
                SourceExecutableSqlExecutor(sources::requireProvider, budgets.materialization, budgets.nativeMemoryBytes, command.readBasis), budgets.materialization)
            sourceExecutionStarted = null
            val computation = engine.execute(publication.catalog, definition, observer(control) { evidence ->
                completedReadEvidence = evidence
                sourceExecutionStarted = true
                sourceCompleted(evidence)
            }, control)
            completedReadEvidence = computation.sourceReadEvidence
            sourceExecutionStarted = true
            stages.putAll(computation.result.stageNanos); stages["ENGINE_TOTAL"] = System.nanoTime() - started
            return EngineExecutionPayload(publication.coordinate, command.requestId, computation.businessResult, computation.sourceReadEvidence,
                stages.toMap(), EngineExecutionDiagnostics(computation.result.sql, computation.result.aliasMapping, computation.result.auditReport),
                QueryExplanation.describe(computation.compiledQuery))
        } catch (failure: EngineExecutionException) {
            val evidence = failure.evidenceContext.toMutableMap()
            stages.putAll(failure.stageNanos)
            stages["ENGINE_TOTAL"] = System.nanoTime() - started
            evidence["publication"] = publication.coordinate; evidence["catalogId"] = id
            evidence["requestId"] = command.requestId; evidence["stageNanos"] = stages.toMap()
            throw EngineExecutionException(failure.stage, failure.code, failure.message, failure.cause, evidence, failure.compiledQuery,
                failure.sourceFailure, completedReadEvidence ?: failure.sourceReadEvidence, failure.sourceExecutionStarted ?: sourceExecutionStarted, stages)
        } catch (failure: RuntimeResourceLimitException) {
            stages["ENGINE_TOTAL"] = System.nanoTime() - started
            throw EngineExecutionException("EXECUTION_RESOURCE", failure.code, "Execution resource budget exceeded", failure,
                mapOf("publication" to publication.coordinate, "catalogId" to id, "requestId" to command.requestId, "stageNanos" to stages.toMap()),
                sourceReadEvidence = completedReadEvidence, sourceExecutionStarted = sourceExecutionStarted, stageNanos = stages)
        } catch (failure: Exception) {
            val evidence = completedReadEvidence ?: throw failure
            stages["ENGINE_TOTAL"] = System.nanoTime() - started
            throw EngineExecutionException("PREPARE_RESULT", "EXECUTABLE_RESULT_PREPARATION_FAILED", "source execution succeeded but result preparation failed", failure,
                mapOf("publication" to publication.coordinate, "catalogId" to id, "requestId" to command.requestId, "stageNanos" to stages.toMap()),
                sourceReadEvidence = evidence, sourceExecutionStarted = true, stageNanos = stages)
        }
    }
}
