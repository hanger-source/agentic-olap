package com.olap.prototype.runtime

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.api.ResultValueType
import com.olap.prototype.api.ResultFrame
import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.connector.SourceSqlRequest
import com.olap.prototype.connector.SourceExecutionResult
import com.olap.prototype.connector.SourceExecutionException
import com.olap.prototype.connector.SourceExecutionFailures
import com.olap.prototype.connector.SourceResultMaterializationException
import com.olap.prototype.connector.loader.DeployedSources
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.contract.JdbcSourceRequirement
import com.olap.prototype.engine.calcite.CandidateValueCompiler
import com.olap.prototype.engine.catalog.ExecutableCatalog
import com.olap.prototype.engine.compiler.CompiledQuery
import com.olap.prototype.engine.compiler.OlapSemanticCompiler
import com.olap.prototype.engine.context.OlapQueryContext
import com.olap.prototype.engine.executable.*
import com.olap.prototype.expression.SemanticNativeTypes
import com.olap.prototype.query.QueryComputation
import com.olap.prototype.query.QueryStageAnalysis
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.contract.*
import com.olap.prototype.server.ServerInputs.fields
import com.olap.prototype.server.ServerInputs.text
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.ResultMaterializationLimits
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.contract.SourceBindingRef
import com.olap.prototype.warehouse.contract.SourceReadEvidence
import java.util.concurrent.CancellationException
import java.time.Instant

/** 选择已发布候选→纯编译精确来源→实际读取；不接受调用方连接、SQL或工作目录。 */
internal class CandidateHandler(private val connectors: ConnectorRegistry, private val budgets: RuntimeBudgets,
    configured: List<SourceConfiguration>) {
    private val mapper = OlapJsonUtils.mapper()
    private val sources = DeployedSources(configured, connectors)
    private data class Loaded(val entry: PublishedQueryEntry, val executable: PublishedExecutableCatalog, val bytes: Long)
    private val publications = linkedMapOf<QueryCapabilityRequest, Loaded>()
    private var publicationBytes = 0L

    data class CandidateRequest(val scenarioId: String, val scenarioVersion: Long, val releaseId: String,
        val releaseVersion: String, val capabilityHash: String, val target: Target, val offset: Int?, val limit: Int?)
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = DimensionProvider::class, name = "DIMENSION_PROVIDER"),
        JsonSubTypes.Type(value = MemberOutput::class, name = "MEMBER_OUTPUT"))
    sealed interface Target
    data class DimensionProvider(val filterRefId: String, val levelCode: String) : Target
    data class MemberOutput(val call: QueryComputation.Members) : Target
    private data class Selected(val publication: Loaded, val offset: Int, val limit: Int)
    private sealed interface Planned {
        val valueType: ScalarValueType
        val sourceRequirements: List<JdbcSourceRequirement>
    }
    private data class DimensionPlan(override val valueType: ScalarValueType, val request: SourceSqlRequest) : Planned {
        override val sourceRequirements get() = request.sourceRequirements
    }
    private data class MemberPlan(override val valueType: ScalarValueType, val lowering: ExecutableLoweringResult,
        val requestId: String) : Planned { override val sourceRequirements get() = lowering.sourceRequirements }
    private data class RequiredSource(val dataSourceId: String, val sourceIdentityHash: String, val connectorRef: ConnectorRef)
    private data class CandidatePage(val values: List<Any>, val scope: String)

    fun execute(operation: String, payload: JsonNode, control: WarehouseExecutionControl): Any = when (operation) {
        "load-publication" -> load(payload, control)
        "unload-publication" -> unload(payload)
        "resolve-candidate-input", "candidate-values" -> {
            val request = mapper.treeToValue(payload, CandidateRequest::class.java)
            val selected = select(request)
            val plan = plan(request, selected, control)
            if (operation == "resolve-candidate-input") resolution(request, selected, plan)
            else page(request, selected, plan, control)
        }
        else -> throw IllegalArgumentException("unsupported candidate operation")
    }

    private fun select(request: CandidateRequest): Selected {
        val coordinate = QueryCapabilityRequest(request.scenarioId, request.scenarioVersion, request.releaseId, request.releaseVersion)
        val loaded = publications[coordinate] ?: error("exact candidate publication is not loaded")
        require(request.capabilityHash == loaded.entry.capability.capabilityHash) { "candidate capability hash mismatch" }
        val offset = request.offset ?: 0; val limit = request.limit ?: 50
        require(offset in 0..1_000_000 && limit in 1..200) { "invalid candidate page bounds" }
        return Selected(loaded, offset, limit)
    }

    private fun plan(request: CandidateRequest, selected: Selected, control: WarehouseExecutionControl): Planned {
        control.checkCancellation()
        return when (val target = request.target) {
            is DimensionProvider -> dimensionPlan(target, selected, control)
            is MemberOutput -> memberPlan(target, selected, control)
        }
    }

    private fun dimensionPlan(target: DimensionProvider, selected: Selected, control: WarehouseExecutionControl): Planned {
        val capability = selected.publication.entry.capability
        val catalog = selected.publication.executable.catalog
        val filter = capability.filterCandidates.firstOrNull { it.filterRefId == target.filterRefId }
            ?: throw IllegalArgumentException("filter is not published")
        require(filter.valueProviderId != null) { "published filter has no candidate value provider; predicate construction remains available" }
        val mappings = filter.eventMappings.filter { it.levelCode == target.levelCode &&
            it.logicUnitId == filter.valueProviderLogicUnitId && it.logicUnitVersion == filter.valueProviderLogicUnitVersion }
        require(mappings.isNotEmpty()) { "level has no exact published value provider" }
        val logic = catalog.logicUnits["${filter.valueProviderLogicUnitId}@${filter.valueProviderLogicUnitVersion}"]
            ?: error("published value logic unit is missing")
        val binding = catalog.physicalExecutionBindings["${logic.physicalExecutionBindingId}@${logic.physicalExecutionBindingVersion}"]
            ?: error("value execution binding is missing")
        val expressions = mappings.map { mapping ->
            (catalog.dimensionBindings["${mapping.dimensionBindingId}@${mapping.dimensionBindingVersion}"]
                ?: error("published candidate dimension binding is missing")).expression
        }.distinct()
        require(expressions.size == 1) { "candidate provider has differing published expressions" }
        val valueType = ScalarValueType.valueOf(filter.filterValueType(target.levelCode))
        val parameters = mutableListOf<JdbcParameter>()
        val request = CandidateValueCompiler.compile(binding, expressions.single(), selected.offset, selected.limit + 1, parameters, connectors)
        control.checkCancellation()
        return DimensionPlan(valueType, request)
    }

    private fun memberPlan(target: MemberOutput, selected: Selected, control: WarehouseExecutionControl): Planned {
        val call = target.call
        val capability = selected.publication.entry.capability
        val catalog = selected.publication.executable.catalog
        PublishedQueryContract.authorizeMemberValues(call, capability)
        val candidate = capability.memberCandidates.first { it.sourceId == call.source.id && it.sourceVersion == call.source.version }
        val output = candidate.outputs[call.outputs.first().slot] ?: error("published member output is missing")
        val request = ExecutableQueryRequest(ExecutableQueryRequest.FORMAT_VERSION,
            "member-candidate-${call.callId}", catalog.manifest.catalogId, QueryComputation.Scope(call, emptyMap(), emptyList()))
        val compiled = OlapSemanticCompiler(CalciteRelCompiler.memberCandidateValues(connectors, selected.offset, selected.limit + 1))
            .compile(catalog, request, OlapQueryContext(request.requestId), cancellationObserver(control))
        control.checkCancellation()
        return MemberPlan(MemberCandidate.scalar(output).valueType,
            requireNotNull(compiled.executableLoweringResult) { "successful member compilation must return its SQL contract" }, request.requestId)
    }

    private fun cancellationObserver(control: WarehouseExecutionControl) = object : ExecutableExecutionObserver {
        override fun bindingCompleted(input: ExecutableInputScope) = control.checkCancellation()
        override fun stageAnalysisCompleted(analyses: List<QueryStageAnalysis.Analysis>) = control.checkCancellation()
        override fun sourceResolutionCompleted(evidence: List<CompiledQuery.SourceEvidence>) = control.checkCancellation()
        override fun loweringCompleted(lowering: ExecutableLoweringResult) = control.checkCancellation()
    }

    private fun resolution(request: CandidateRequest, selected: Selected, plan: Planned): Any {
        val required = plan.sourceRequirements.map { RequiredSource(it.dataSourceId, it.evidence.sourceIdentityHash, it.connectorRef) }
            .distinct().sortedWith(compareBy<RequiredSource> { it.dataSourceId }.thenBy { it.sourceIdentityHash }
                .thenBy { it.connectorRef.id }.thenBy { it.connectorRef.version }.thenBy { it.connectorRef.sha256 })
        return mapOf("coordinate" to selected.publication.executable.coordinate,
            "catalogHash" to selected.publication.entry.catalogHash, "capabilityHash" to request.capabilityHash,
            "queryHash" to queryHash(request), "sources" to required, "valueType" to plan.valueType.name)
    }

    private fun page(request: CandidateRequest, selected: Selected, plan: Planned, control: WarehouseExecutionControl): Any {
        val actual = when (plan) {
            is DimensionPlan -> executeDimension(plan, selected.limit, control)
            is MemberPlan -> executeMember(plan, selected.limit, control)
        }
        val more = actual.values.size > selected.limit
        return mapOf("coordinate" to selected.publication.executable.coordinate, "capabilityHash" to request.capabilityHash,
            "queryHash" to queryHash(request), "catalogHash" to selected.publication.entry.catalogHash,
            "target" to mapper.valueToTree<JsonNode>(request).required("target"), "valueType" to plan.valueType.name,
            "values" to actual.values.take(selected.limit).map(plan.valueType::candidate), "offset" to selected.offset,
            "nextOffset" to if (more) selected.offset + selected.limit else null,
            "observedAt" to Instant.now(), "scope" to actual.scope)
    }

    private fun executeDimension(plan: DimensionPlan, limit: Int, control: WarehouseExecutionControl): CandidatePage {
        val provider = sources.requireProvider(JdbcSourceRequirement.requireSingleBinding(plan.sourceRequirements))
        val result = provider.execute(plan.request, control, ResultMaterializationLimits(201, 201, budgets.io.responseBytes.toLong(), 1),
            budgets.nativeMemoryBytes) { _, _ -> }
        return candidateResult(result, provider.bindingRef, plan.request.operation, control) { frame ->
            require(frame.columns.size == 1) { "candidate plan requires exactly one output column" }
            val expected = plan.request.outputTypes["candidate"] as? ResultValueType.Scalar
                ?: throw IllegalArgumentException("candidate expression requires a scalar output")
            val observed = requireNotNull(frame.columns.single().jdbcType) { "candidate source requires native result metadata" }
            require(connectors.require(provider.connectorRef).compilation.matchesNativeResult(SemanticNativeTypes.describe(expected), observed)) {
                "candidate result type differs from its compiled expression"
            }
            val values = frame.rows.map { row ->
                require(row.cells.size == 1) { "candidate plan requires exactly one output cell" }
                val value = requireNotNull(row.cells.single().value) { "candidate plan returned null" }
                plan.valueType.candidate(value); value
            }
            require(values.size <= limit + 1) { "candidate result exceeds compiled page limit" }
            CandidatePage(values, "PUBLISHED_VALUE_PROVIDER_DOMAIN")
        }
    }

    private fun executeMember(plan: MemberPlan, limit: Int, control: WarehouseExecutionControl): CandidatePage {
        val result = SourceExecutableSqlExecutor(sources::requireProvider,
            ResultMaterializationLimits(201, 201, budgets.io.responseBytes.toLong(), 1), budgets.nativeMemoryBytes, null)
            .execute(plan.lowering, OlapQueryContext(plan.requestId), control)
        return candidateResult(result, JdbcSourceRequirement.requireSingleBinding(plan.sourceRequirements), "execute-query", control) { frame ->
            ExecutableBusinessResults.produce(frame, plan.lowering.outputContract, plan.lowering.compilationSupport)
            val values = frame.rows.map { row ->
                require(row.cells.size == 1) { "member candidate plan requires exactly one output column" }
                val value = requireNotNull(row.cells.single().value) { "candidate plan returned null" }
                plan.valueType.candidate(value); value
            }
            require(values.size <= limit + 1) { "candidate result exceeds compiled page limit" }
            control.checkCancellation()
            CandidatePage(values, "PUBLISHED_MEMBER_OUTPUT_DOMAIN")
        }
    }

    private fun candidateResult(result: SourceExecutionResult, binding: SourceBindingRef, operation: String,
        control: WarehouseExecutionControl, materialize: (ResultFrame) -> CandidatePage): CandidatePage = try {
        materialize(result.frame)
    } catch (failure: Exception) {
        val actual = when (failure) {
            is CancellationException, is RuntimeResourceLimitException, is SourceExecutionException -> failure
            else -> SourceResultMaterializationException(failure)
        }
        throw SourceExecutionFailures.exception(binding, operation, "MATERIALIZE_RESULT", actual, control.cancellationEvidence(), result.request,
            SourceReadEvidence(binding, result.readBasis, result.request))
    }

    private fun queryHash(request: CandidateRequest): String = PublicationContentHash.compute(mapper.valueToTree(request))

    private fun load(payload: JsonNode, control: WarehouseExecutionControl): Any {
        fields(payload, setOf("entry", "executable"))
        val entry = mapper.treeToValue(payload.required("entry"), PublishedQueryEntry::class.java)
        val executable = mapper.treeToValue(payload.required("executable"), PublishedExecutableCatalog::class.java)
        executable.validate()
        val coordinate = PublishedQueryEntry.key(entry.capability)
        require(coordinate == executable.coordinate && entry.capability == executable.queryCapability &&
            entry.catalogHash == PublicationContentHash.compute(payload.required("executable"))) { "candidate publication binding mismatch" }
        executable.catalog.physicalExecutionBindings.values.forEach { connectors.require(it.connectorRef) }
        publications[coordinate]?.let { requireIdentity(it, entry.catalogHash,
            requireNotNull(entry.capability.capabilityHash) { "candidate publication requires its exact capability hash" }, entry.semanticCatalogHash)
            return acknowledgement(coordinate, entry, "alreadyLoaded") }
        val bytes = mapper.writeValueAsBytes(payload).size.toLong()
        if (bytes > budgets.catalogBytes) throw RuntimeResourceLimitException("CANDIDATE_PUBLICATION_BUDGET_EXCEEDED")
        while (publications.isNotEmpty() && (publications.size >= budgets.catalogEntries || bytes > budgets.catalogBytes - publicationBytes)) {
            val oldest = publications.entries.first(); publicationBytes -= oldest.value.bytes; publications.remove(oldest.key)
        }
        control.checkCancellation()
        publications[coordinate] = Loaded(entry, executable, bytes); publicationBytes += bytes
        return acknowledgement(coordinate, entry, "loaded")
    }

    private fun unload(payload: JsonNode): Any {
        fields(payload, setOf("coordinate", "catalogHash", "capabilityHash", "semanticCatalogHash"))
        val coordinate = mapper.treeToValue(payload.required("coordinate"), QueryCapabilityRequest::class.java)
        val previous = publications[coordinate] ?: error("exact candidate publication is not loaded")
        requireIdentity(previous, text(payload, "catalogHash"), text(payload, "capabilityHash"), text(payload, "semanticCatalogHash"))
        publications.remove(coordinate); publicationBytes -= previous.bytes
        return acknowledgement(coordinate, previous.entry, "unloaded")
    }

    private fun requireIdentity(previous: Loaded, catalog: String, capability: String, semantic: String) {
        require(previous.entry.catalogHash == catalog && previous.entry.capability.capabilityHash == capability && previous.entry.semanticCatalogHash == semantic) {
            "publication operation must identify exact loaded content"
        }
    }
    private fun acknowledgement(coordinate: QueryCapabilityRequest, entry: PublishedQueryEntry, outcome: String) = mapOf(
        "coordinate" to coordinate, "catalogHash" to entry.catalogHash, "capabilityHash" to entry.capability.capabilityHash,
        "semanticCatalogHash" to entry.semanticCatalogHash, outcome to true)
}
