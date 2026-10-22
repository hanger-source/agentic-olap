package com.olap.prototype.runtime

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
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
import com.olap.prototype.query.contract.*
import com.olap.prototype.server.ServerInputs.fields
import com.olap.prototype.server.ServerInputs.text
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.contract.SourceReadEvidence
import java.security.MessageDigest
import java.util.HexFormat

/** 精确发布的编译与实际执行；不拥有进程、元数据库或凭据选择。 */
internal class EngineHandler(private val connectors: ConnectorRegistry, private val budgets: RuntimeBudgets,
    private val configured: List<SourceConfiguration>) {
    private val mapper = OlapJsonUtils.mapper()
    private data class Loaded(val publication: PublishedExecutableCatalog, val artifactHash: String,
        val contentHash: String, val bytes: Long)
    private val catalogs = linkedMapOf<String, Loaded>()
    private var catalogBytes = 0L

    fun execute(command: ObjectNode, control: WarehouseExecutionControl, sourceCompleted: (SourceReadEvidence) -> Unit): Any = when (text(command, "operation")) {
        "load-catalog" -> {
            fields(command, setOf("operation", "catalog")); load(command.required("catalog"), control)
        }
        "unload-catalog" -> {
            fields(command, setOf("operation", "catalogId", "contentHash"))
            val id = text(command, "catalogId")
            val current = catalogs[id] ?: error("exact catalog is not loaded")
            require(current.contentHash == text(command, "contentHash")) { "unload must name exact immutable content" }
            catalogs.remove(id); catalogBytes -= current.bytes
            mapOf("unloaded" to true, "catalogId" to id, "contentHash" to current.contentHash)
        }
        "resolve-input", "validate" -> {
            fields(command, setOf("operation", "catalogId", "queryDefinition"))
            resolve(text(command, "catalogId"), command.required("queryDefinition"), control, text(command, "operation"))
        }
        "explain" -> {
            fields(command, setOf("operation", "catalogId", "queryDefinition"))
            resolve(text(command, "catalogId"), command.required("queryDefinition"), control, "explain")
        }
        "execute" -> {
            fields(command, setOf("operation", "catalogId", "queryDefinition", "dataSources", "readBasis"))
            require(command.required("dataSources").isArray) { "dataSources must be an array" }
            val requested = mapper.treeToValue(command.required("dataSources"), Array<SourceConfiguration>::class.java).toList()
            require(requested == configured) { "execution sources differ from the supervisor's exact worker capability" }
            val readBasis = command.get("readBasis")?.takeUnless { it.isNull }?.let {
                fields(it, setOf("kind", "authority", "version", "partitions", "pinning"))
                require(it.required("kind").textValue() == "VERSIONED") { "pinned execution requires a versioned read basis" }
                mapper.treeToValue((it.deepCopy<JsonNode>() as ObjectNode).apply { remove("kind") },
                    com.olap.prototype.warehouse.contract.SourceReadBasis.Versioned::class.java)
            }
            run(text(command, "catalogId"), command.required("queryDefinition"), control, readBasis, sourceCompleted)
        }
        else -> throw IllegalArgumentException("unsupported engine operation")
    }

    private fun load(payload: JsonNode, control: WarehouseExecutionControl): Any {
        val encoded = mapper.writeValueAsBytes(payload)
        if (encoded.size > budgets.catalogBytes) throw RuntimeResourceLimitException("CATALOG_BYTES_EXCEEDED")
        require(payload.path("formatVersion").textValue() == PublishedExecutableCatalog.FORMAT_VERSION) { "unsupported published executable catalog" }
        ExecutableCatalogManifest.requireSupportedSchema(payload.path("catalog").path("manifest").path("schemaVersion").textValue())
        val id = text(payload.path("catalog").path("manifest"), "catalogId")
        val artifactHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded))
        catalogs[id]?.let {
            check(it.artifactHash == artifactHash) { "catalog ID already loaded with different immutable content" }
            control.checkCancellation()
            return mapOf("catalogId" to id, "contentHash" to it.contentHash)
        }
        val publication = mapper.treeToValue(payload, PublishedExecutableCatalog::class.java)
        publication.validate()
        val contentHash = PublicationContentHash.compute(payload)
        control.checkCancellation()
        while (catalogs.isNotEmpty() && (catalogs.size >= budgets.catalogEntries || encoded.size > budgets.catalogBytes - catalogBytes)) {
            val oldest = catalogs.entries.first(); catalogBytes -= oldest.value.bytes; catalogs.remove(oldest.key)
        }
        catalogs[id] = Loaded(publication, artifactHash, contentHash, encoded.size.toLong())
        catalogBytes += encoded.size
        return mapOf("catalogId" to id, "contentHash" to contentHash)
    }

    private fun authorize(publication: PublishedExecutableCatalog, id: String, definition: JsonNode): ExecutableQueryRequest = try {
        require(definition.isObject) { "queryDefinition must be an object" }
        fields(definition, setOf("requestId", "formatVersion", "scenarioId", "scenarioVersion", "releaseId", "releaseVersion", "computation"))
        val intent = (definition as ObjectNode).deepCopy()
        val requestId = text(intent, "requestId"); intent.remove("requestId")
        val computation = PublishedQueryContract.parseIntent(intent, publication.queryCapability)
        PublishedQueryContract.authorize(intent.path("formatVersion").textValue(), publication.coordinate, computation, publication.queryCapability)
        ExecutableQueryRequest(ExecutableQueryRequest.FORMAT_VERSION, requestId, id, computation)
    } catch (failure: InputContractException) {
        throw failure
    } catch (failure: IllegalArgumentException) {
        throw EngineExecutionException("AUTHORIZE_INPUT", "EXECUTABLE_QUERY_NOT_PUBLISHED",
            "Query is outside the frozen publication: ${failure.message}", failure)
    }

    private fun resolve(id: String, definition: JsonNode, control: WarehouseExecutionControl, operation: String): Any {
        val loaded = catalogs[id] ?: error("exact catalog is not loaded")
        val request = authorize(loaded.publication, id, definition)
        control.checkCancellation()
        val context = com.olap.prototype.engine.context.OlapQueryContext(request.requestId)
        val compiler = OlapSemanticCompiler(CalciteRelCompiler(connectors))
        val compiled = if (operation == "resolve-input") null else compiler.compile(loaded.publication.catalog, request, context, observer(control))
        val (dataSourceId, sourceIdentityHash, connectorRef) = if (compiled == null) {
            val source = compiler.resolveSource(loaded.publication.catalog, request, context, observer(control))
            Triple(source.dataSourceId, source.sourceConstraints.sourceIdentityHash, source.connectorRef)
        } else {
            val lowered = requireNotNull(compiled.executableLoweringResult) { "successful compilation must return its SQL contract" }
            Triple(lowered.dataSourceId, com.olap.prototype.contract.JdbcSourceRequirement.requireSingleBinding(lowered.sourceRequirements).sourceIdentityHash, lowered.connectorRef)
        }
        control.checkCancellation()
        return mapOf("formatVersion" to "olap-input-resolution/v2", "publication" to loaded.publication.coordinate,
            "catalogId" to id, "catalogHash" to loaded.contentHash, "intentHash" to PublicationContentHash.compute(definition),
            "dataSourceId" to dataSourceId, "sourceIdentityHash" to sourceIdentityHash, "connectorRef" to connectorRef,
            "explanation" to if (operation == "explain") QueryExplanation.describe(requireNotNull(compiled)) else null,
            "timingNanos" to context.stageTimes)
    }

    private fun observer(control: WarehouseExecutionControl): ExecutableExecutionObserver = object : ExecutableExecutionObserver {
        override fun bindingCompleted(input: ExecutableInputScope) = control.checkCancellation()
        override fun stageAnalysisCompleted(analyses: List<QueryStageAnalysis.Analysis>) = control.checkCancellation()
        override fun sourceResolutionCompleted(evidence: List<CompiledQuery.SourceEvidence>) = control.checkCancellation()
        override fun loweringCompleted(lowered: ExecutableLoweringResult) {
            control.checkCancellation()
            val sqlBytes = lowered.sql.toByteArray(Charsets.UTF_8).size.toLong() + lowered.relationChecks.sumOf { it.sql.toByteArray(Charsets.UTF_8).size.toLong() }
            val parameters = lowered.parameters.size.toLong() + lowered.relationChecks.sumOf { it.parameters.size.toLong() }
            if (sqlBytes > budgets.sqlBytes) throw RuntimeResourceLimitException("SQL_BYTES_EXCEEDED")
            if (parameters > budgets.parameters) throw RuntimeResourceLimitException("SQL_PARAMETERS_EXCEEDED")
        }
    }

    private fun run(id: String, intent: JsonNode, control: WarehouseExecutionControl,
        readBasis: com.olap.prototype.warehouse.contract.SourceReadBasis.Versioned?, sourceCompleted: (SourceReadEvidence) -> Unit): Any {
        val started = System.nanoTime()
        val stages = linkedMapOf<String, Long>()
        val loaded = catalogs[id] ?: throw IllegalArgumentException("catalog is not loaded: $id")
        val publication = loaded.publication
        var completedReadEvidence: SourceReadEvidence? = null
        try {
            control.checkCancellation()
            val authorizationStart = System.nanoTime()
            val definition = try { authorize(publication, id, intent) }
            finally { stages["COMPILE_AUTHORIZE"] = System.nanoTime() - authorizationStart }
            val sources = DeployedSources(configured, connectors)
            val engine = OlapEngine(OlapSemanticCompiler(CalciteRelCompiler(connectors)),
                SourceExecutableSqlExecutor(sources::requireProvider, budgets.materialization, budgets.nativeMemoryBytes, readBasis))
            val computation = engine.execute(publication.catalog, definition, observer(control), control)
            completedReadEvidence = computation.sourceReadEvidence
            sourceCompleted(computation.sourceReadEvidence)
            stages.putAll(computation.result.stageNanos); stages["ENGINE_TOTAL"] = System.nanoTime() - started
            return mapOf("formatVersion" to "olap-published-execution-result/v2", "publication" to publication.coordinate,
                "computation" to computation, "stageNanos" to stages.toMap(),
                "explanation" to QueryExplanation.describe(computation.compiledQuery))
        } catch (failure: EngineExecutionException) {
            val evidence = failure.evidenceContext.toMutableMap()
            val kernel = evidence["stageNanos"]
            if (kernel is Map<*, *>) kernel.forEach { (stage, nanos) -> stages[stage as String] = nanos as Long }
            stages["ENGINE_TOTAL"] = System.nanoTime() - started
            evidence["publication"] = publication.coordinate; evidence["catalogId"] = id
            evidence["requestId"] = text(intent, "requestId"); evidence["stageNanos"] = stages.toMap()
            throw EngineExecutionException(failure.stage, failure.code, failure.message, failure.cause, evidence, failure.compiledQuery,
                failure.sourceFailure, completedReadEvidence ?: failure.sourceReadEvidence)
        } catch (failure: RuntimeResourceLimitException) {
            stages["ENGINE_TOTAL"] = System.nanoTime() - started
            throw EngineExecutionException("EXECUTION_RESOURCE", failure.code, "Execution resource budget exceeded", failure,
                mapOf("publication" to publication.coordinate, "catalogId" to id, "requestId" to text(intent, "requestId"), "stageNanos" to stages.toMap()),
                sourceReadEvidence = completedReadEvidence)
        } catch (failure: Exception) {
            val evidence = completedReadEvidence ?: throw failure
            stages["ENGINE_TOTAL"] = System.nanoTime() - started
            throw EngineExecutionException("PREPARE_RESULT", "EXECUTABLE_RESULT_PREPARATION_FAILED", "source execution succeeded but result preparation failed", failure,
                mapOf("publication" to publication.coordinate, "catalogId" to id, "requestId" to text(intent, "requestId"), "stageNanos" to stages.toMap()),
                sourceReadEvidence = evidence)
        }
    }
}
