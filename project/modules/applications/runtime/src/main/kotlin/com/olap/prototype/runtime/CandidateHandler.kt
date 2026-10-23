package com.olap.prototype.runtime

import com.olap.prototype.codec.CandidateProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.codec.PublicationProto
import com.olap.prototype.calculation.Calculation
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
    private val sources = DeployedSources(configured, connectors)
    private data class Loaded(val entry: PublishedQueryEntry, val executable: PublishedExecutableCatalog, val bytes: Long)
    private val publications = linkedMapOf<QueryCapabilityRequest, Loaded>()
    private var publicationBytes = 0L

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
    private data class CandidatePage(val values: List<Any>, val scope: String)

    fun execute(command: CandidateWorkerCommand, control: WarehouseExecutionControl): CandidateWorkerResult = when (command) {
        is CandidateWorkerCommand.Lookup -> {
            control.checkCancellation()
            val id = command.identity
            val loaded = publications[id.coordinate]
            loaded?.let { requireIdentity(it, id.catalogHash, id.capabilityHash, id.semanticCatalogHash) }
            CandidateWorkerResult.Present(loaded != null)
        }
        is CandidateWorkerCommand.Load -> load(command, control)
        is CandidateWorkerCommand.Unload -> unload(command.identity)
        is CandidateWorkerCommand.Resolve -> {
            val request = command.request
            val selected = select(request)
            val plan = plan(request, selected, control)
            CandidateWorkerResult.Resolved(resolution(request, selected, plan))
        }
        is CandidateWorkerCommand.Values -> {
            val request = command.request
            val selected = select(request)
            CandidateWorkerResult.Values(page(request, selected, plan(request, selected, control), control))
        }
    }

    private fun select(request: CandidateRequest): Selected {
        val coordinate = request.publication
        val loaded = publications[coordinate] ?: error("exact candidate publication is not loaded")
        require(request.capabilityHash == loaded.entry.capability.capabilityHash) { "candidate capability hash mismatch" }
        val offset = request.offset; val limit = request.limit
        require(offset in 0..1_000_000 && limit in 1..200) { "invalid candidate page bounds" }
        return Selected(loaded, offset, limit)
    }

    private fun plan(request: CandidateRequest, selected: Selected, control: WarehouseExecutionControl): Planned {
        control.checkCancellation()
        return when (val target = request.target) {
            is CandidateTarget.DimensionProvider -> dimensionPlan(target, selected, control)
            is CandidateTarget.MemberOutput -> memberPlan(target, selected, control)
        }
    }

    private fun dimensionPlan(target: CandidateTarget.DimensionProvider, selected: Selected, control: WarehouseExecutionControl): Planned {
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

    private fun memberPlan(target: CandidateTarget.MemberOutput, selected: Selected, control: WarehouseExecutionControl): Planned {
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

    private fun resolution(request: CandidateRequest, selected: Selected, plan: Planned): CandidateResolution {
        val required = plan.sourceRequirements.map { CandidateSource(SourceBindingRef(it.dataSourceId, it.evidence.sourceIdentityHash), it.connectorRef) }
            .distinct().sortedWith(compareBy<CandidateSource> { it.binding.dataSourceId }.thenBy { it.binding.sourceIdentityHash }
                .thenBy { it.connectorRef.id }.thenBy { it.connectorRef.version }.thenBy { it.connectorRef.sha256 })
        return CandidateResolution(selected.publication.executable.coordinate, selected.publication.entry.catalogHash,
            request.capabilityHash, queryHash(request), required, plan.valueType)
    }

    private fun page(request: CandidateRequest, selected: Selected, plan: Planned, control: WarehouseExecutionControl): CandidateValues {
        val actual = when (plan) {
            is DimensionPlan -> executeDimension(plan, selected.limit, control)
            is MemberPlan -> executeMember(plan, selected.limit, control)
        }
        val more = actual.values.size > selected.limit
        return CandidateValues(selected.publication.executable.coordinate, selected.publication.entry.catalogHash,
            request.capabilityHash, queryHash(request), request.target, plan.valueType,
            actual.values.take(selected.limit).map { Calculation.Scalar(plan.valueType, plan.valueType.candidate(it).toString()) }, selected.offset,
            if (more) selected.offset + selected.limit else null, Instant.now(), actual.scope)
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

    private fun queryHash(request: CandidateRequest): String = CandidateProto.digest(request)

    private fun load(command: CandidateWorkerCommand.Load, control: WarehouseExecutionControl): CandidateWorkerResult.Loaded {
        val entry = command.entry
        val executable = command.executable
        executable.validate()
        val coordinate = PublishedQueryEntry.key(entry.capability)
        require(coordinate == executable.coordinate && entry.capability == executable.queryCapability &&
            entry.catalogHash == PublicationProto.contentHash(executable)) { "candidate publication binding mismatch" }
        executable.catalog.physicalExecutionBindings.values.forEach { connectors.require(it.connectorRef) }
        publications[coordinate]?.let { requireIdentity(it, entry.catalogHash,
            requireNotNull(entry.capability.capabilityHash) { "candidate publication requires its exact capability hash" }, entry.semanticCatalogHash)
            return CandidateWorkerResult.Loaded(identity(coordinate, entry)) }
        val bytes = CandidateWorkerProto.toProto(command).serializedSize.toLong()
        if (bytes > budgets.catalogBytes) throw RuntimeResourceLimitException("CANDIDATE_PUBLICATION_BUDGET_EXCEEDED")
        while (publications.isNotEmpty() && (publications.size >= budgets.catalogEntries || bytes > budgets.catalogBytes - publicationBytes)) {
            val oldest = publications.entries.first(); publicationBytes -= oldest.value.bytes; publications.remove(oldest.key)
        }
        control.checkCancellation()
        publications[coordinate] = Loaded(entry, executable, bytes); publicationBytes += bytes
        return CandidateWorkerResult.Loaded(identity(coordinate, entry))
    }

    private fun unload(identity: CandidatePublicationIdentity): CandidateWorkerResult.Unloaded {
        val coordinate = identity.coordinate
        val previous = publications[coordinate] ?: error("exact candidate publication is not loaded")
        requireIdentity(previous, identity.catalogHash, identity.capabilityHash, identity.semanticCatalogHash)
        publications.remove(coordinate); publicationBytes -= previous.bytes
        return CandidateWorkerResult.Unloaded(identity(coordinate, previous.entry))
    }

    private fun requireIdentity(previous: Loaded, catalog: String, capability: String, semantic: String) {
        require(previous.entry.catalogHash == catalog && previous.entry.capability.capabilityHash == capability && previous.entry.semanticCatalogHash == semantic) {
            "publication operation must identify exact loaded content"
        }
    }
    private fun identity(coordinate: QueryCapabilityRequest, entry: PublishedQueryEntry) = CandidatePublicationIdentity(
        coordinate, entry.catalogHash, requireNotNull(entry.capability.capabilityHash), entry.semanticCatalogHash)
}
