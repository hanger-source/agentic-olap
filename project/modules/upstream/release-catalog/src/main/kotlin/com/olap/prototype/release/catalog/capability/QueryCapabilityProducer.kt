package com.olap.prototype.release.catalog.capability

import com.olap.prototype.calculation.MetricSource
import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.engine.catalog.ExecutableCatalog
import com.olap.prototype.event.EventRef
import com.olap.prototype.query.PostOperationContract
import com.olap.prototype.query.contract.*
import com.olap.prototype.release.catalog.PublishedValueTypes
import com.olap.prototype.release.catalog.ReleaseObjectIndex
import com.olap.prototype.release.contract.*

class QueryCapabilityProducer(private val executionCapabilities: QueryExecutionCapabilityPolicy, private val connectors: ConnectorRegistry) : ReleaseQueryCapabilityCompiler {
    override fun produce(release: ReleaseCatalogView, scenario: ReleaseScenarioSnapshot, assets: ExecutableCatalog): QueryCapabilityView {
        val frozen = FrozenScenarioCapabilities(release, scenario)
        require(assets.manifest.catalogId == "${release.releaseId}@${release.releaseVersion}#${release.scenarioId}@${release.scenarioVersion}") { "capability compiler requires the exact release assets identity" }
        val sources = PublishedQuerySources(ReleaseObjectIndex(release.objectVersionRefs), assets)
        val metrics = scenario.metrics.map(sources::metric)
        val dimensions = frozen.findScenarioAnalysisDimensionCandidates()
        val dimensionKeys = dimensions.map { "${it.analysisDimensionId}@${it.analysisDimensionVersion}" }.toSet()
        val members = frozen.findScenarioMemberReferences().map { sources.member(it, dimensionKeys) }
        val times = timeCandidates(frozen.findScenarioTimeAnalysisDimensionCandidates(), metrics)
        val nativeTypes = PublishedNativeTypes(release.dimensionMappings, assets, connectors)
        val executionTypes = nativeTypes.produce(metrics, members, dimensions)
        val valueTypes = PublishedValueTypes(assets)
        val filters = frozen.findScenarioFilterReferences().map { filterCandidate(it, valueTypes, release.dimensionMappings, executionTypes) }
        val stages = frozen.findScenarioPostProcessCapabilities().map { applyEnginePostOperationBoundary(it, assets) }
        return QueryCapabilityHash.attach(QueryCapabilityView(frozen.findScenarioRef(), frozen.findSubjectAreaCapabilityContext(), release.releaseId,
            release.releaseVersion, release.releaseDisplayName, metrics, members, dimensions, times, filters,
            sources.relationFilters(release.scenarioId, release.scenarioVersion), sources.clocks(), stages,
            frozen.findReleaseMetricAnalysisDimensionMatrix(), frozen.findReleaseDisabledReasons(), frozen.findReleaseDiagnostics(), executionTypes, nativeTypes.definitions(), null))
    }
    /** 只开放被选指标实际消费的事件锚点。 */
    private fun timeCandidates(times: List<TimeAnalysisDimensionCandidate>, metrics: List<MetricCandidate>): List<TimeAnalysisDimensionCandidate> {
        val events = metrics.flatMap { it.sources }.filter { it.origin is MetricSource.Fact }.map { it.event }.toSet()
        return times.mapNotNull { time ->
            val anchors = time.eventAnchors.filter { EventRef(it.eventId, it.eventVersion) in events }
            if (anchors.isEmpty()) null else TimeAnalysisDimensionCandidate(time.analysisDimensionId, time.analysisDimensionVersion, time.analysisDimensionDisplayName,
                time.outputAlias, time.scenarioAnalysisDimensionRefId, time.releaseObjectRefId, time.defaultLevelCode, time.defaultLevelDisplayName,
                anchors.map { it.levelCode }.distinct().sorted(), anchors, time.metricEventCapabilities, time.requiredByDefault, time.sortOrder)
        }
    }
    private fun filterCandidate(reference: ScenarioFilterReference, valueTypes: PublishedValueTypes, mappings: List<ReleaseDimensionMapping>, executionTypes: QueryExecutionTypes): FilterCandidate {
        if (reference.filterTargetType != "ANALYSIS_DIMENSION") throw ProducerContractException("unsupported filter target: ${reference.filterTargetType}")
        val operators = FilterLeafOperatorPolicy.requireOperatorSet(reference.operatorPolicyJson, "query_capability.filterCandidates.operators@${reference.filterRefId}").toList()
        val cardinalities = operators.map { when (it) { "IS_NULL", "IS_NOT_NULL" -> "NONE"; "IN" -> "MULTI"; "BETWEEN" -> "RANGE"; else -> "SINGLE" } }.toSet()
        val cardinality = cardinalities.singleOrNull() ?: "OPERATOR_DEPENDENT"
        val types = valueTypes.dimensions(mappings.filter { it.analysisDimensionId == reference.filterTargetObjectId && it.analysisDimensionVersion == reference.filterTargetObjectVersion }, executionTypes)
        return FilterCandidate(reference.filterRefId, reference.filterTargetType, "分析维度", reference.queryFieldKind, reference.filterTargetObjectId,
            reference.filterTargetObjectVersion, reference.filterTargetDisplayName, reference.releaseObjectRefId, operators, types, "TEXT_INPUT", cardinality,
            reference.valueProviderId, reference.valueProviderType, reference.valueProviderLogicUnitId, reference.valueProviderLogicUnitVersion,
            reference.eventMappings, reference.requiredByDefault, reference.sortOrder)
    }
    private fun applyEnginePostOperationBoundary(capability: PostProcessCapability, assets: ExecutableCatalog): PostProcessCapability {
        if (!capability.available) return capability
        val implemented = executionCapabilities.supportsPostProcess(capability.ruleType)
        val required = when (capability.ruleType) {
            PostOperationContract.EXPAND_COLLECTION -> CompilationSupport.Capability.COLLECTION_EXPANSION
            PostOperationContract.ANCESTOR_CLOSURE -> CompilationSupport.Capability.RECURSIVE_ANCESTORS
            else -> null
        }
        // 只声明是否存在可用上下文，实际调用仍由精确来源核验。
        val available = required == null || assets.physicalExecutionBindings.values.map { it.connectorRef }.distinct().any { connectors.require(it).compilation.supports(required) }
        if (implemented && available) return capability
        return PostProcessCapability(capability.ruleId, capability.ruleType, capability.ruleDisplayName, capability.ruleConfigJson, capability.sortOrder,
            if (implemented) "CONNECTOR_POST_OPERATION_NOT_EXECUTABLE" else "ENGINE_POST_OPERATION_NOT_EXECUTABLE",
            if (implemented) "当前发布没有支持该运算的原生执行上下文: ${capability.ruleType}" else "当前 Engine 发布能力未实现该后处理类型: ${capability.ruleType}",
            if (implemented) "CONNECTOR_CAPABILITY" else "ENGINE_CAPABILITY", capability.targetRef ?: "scenarioPipelineRule:${capability.ruleId}",
            capability.targetVersionRef ?: "scenarioPipelineRule:${capability.ruleId}", false, true, true, true)
    }
}
