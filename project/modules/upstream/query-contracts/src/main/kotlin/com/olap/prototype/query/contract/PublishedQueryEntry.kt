package com.olap.prototype.query.contract

import com.fasterxml.jackson.annotation.JsonProperty
import com.olap.prototype.calculation.CalculationInstantiation
import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.calculation.MetricInstanceRef
import com.olap.prototype.calculation.MetricSource
import com.olap.prototype.engine.catalog.ExecutableCatalog
import com.olap.prototype.member.MemberRef
import com.olap.prototype.member.MemberSource
import com.olap.prototype.member.MemberSourceDefinition
import com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog
import com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.*
import com.olap.prototype.util.OlapJsonUtils

/** 可查询批准、可执行目录身份和可解释语义闭包必须来自同一精确发布。 */
data class PublishedQueryEntry(
    @param:JsonProperty(required = true) val capability: QueryCapabilityView,
    @param:JsonProperty(required = true) val catalogHash: String,
    @param:JsonProperty(required = true) val semanticCatalogHash: String,
    @param:JsonProperty(required = true) val semanticCatalog: PublishedSemanticCatalog,
) {
    init {
        QueryCapabilityHash.requireValid(capability)
        require(catalogHash.matches(Regex("[a-f0-9]{64}")) && semanticCatalogHash.matches(Regex("[a-f0-9]{64}")) && semanticCatalogHash == semanticCatalog.sha256) { "capability, executable and semantic catalog hashes are required" }
        requireSemanticDefinitions(semanticCatalog.catalog)
    }
    fun metricDefinition(instance: MetricInstanceRef): Metric = semanticCatalog.catalog.metric(instance)
    fun memberDefinition(id: String, version: Long): MemberDefinition = semanticCatalog.catalog.memberDefinition(MemberRef(id, version))
    fun relationFilterDefinition(id: String, version: Long): RelationFilter = semanticCatalog.catalog.relation(id, version)
    fun dimensionSourceDefinition(id: String, version: Long): DimensionDefinition = semanticCatalog.catalog.dimension(id, version).sourceDefinition
    fun timeDimensionSourceDefinition(id: String, version: Long): DimensionDefinition = semanticCatalog.catalog.timeDimension(id, version).sourceDefinition

    private fun requireSemanticDefinitions(catalog: Catalog) {
        require(key(capability) == catalog.coordinate.request()) { "frozen semantic definition coordinate mismatch" }
        equalValue(catalog.displayName, capability.scenario.scenarioDisplayName)
        equalValue(catalog.sourceJoinContract, SourceJoinMeaning.current())
        requireSemanticDependencies(catalog)
        require(catalog.metrics.size == capability.metricCandidates.size) { "frozen semantic metric set differs from capability" }
        capability.metricCandidates.forEach { candidate ->
            val metric = catalog.metric(candidate.instance)
            equalValue(metric.adoptionId, candidate.adoptionId); equalValue(metric.adoptionVersion, candidate.adoptionVersion)
            equalValue(metric.subjectAreaId, candidate.subjectAreaId); equalValue(metric.subjectAreaVersion, candidate.subjectAreaVersion)
            equalValue(metric.code, candidate.code); equalValue(metric.definition, candidate.definition)
            equalValue(metric.displayName, candidate.displayName); equalValue(metric.businessMeaning, candidate.businessMeaning)
            equalValue(metric.valueContract, candidate.valueContract); equalValue(metric.snapshotMeaning, candidate.snapshotMeaning)
            equalValue(metric.sources, candidate.sources); equalValue(metric.definitionKind.name, candidate.definitionKind)
            equalValue(metric.parameters, candidate.parameters); equalValue(metric.fixedArguments, candidate.fixedArguments)
            equalValue(metric.groupingCandidates, candidate.groupingCandidates); equalValue(metric.memberParameters, candidate.memberParameters)
        }
        require(catalog.memberSources.size == capability.memberCandidates.size) { "frozen member entry set differs from capability" }
        capability.memberCandidates.forEach { equalValue(catalog.member(it.sourceId, it.sourceVersion).candidate(), it) }
        require(catalog.relationFilters.size == capability.relationFilterCandidates.size) { "frozen relation filter set differs from capability" }
        capability.relationFilterCandidates.forEach { candidate ->
            val grant = candidate.definition
            val row = catalog.relation(grant.relation.id, grant.relation.version)
            equalValue(row.displayName, grant.displayName); equalValue(row.entity, candidate.entity); equalValue(row.role, candidate.role)
            equalValue(row.requiredByDefault, grant.requiredByDefault); equalValue(row.parentEvent, grant.relation.parent)
            equalValue(row.permission, grant.permission); equalValue(row.filterTypes, candidate.filterTypes)
            equalValue(row.filterValueContracts, candidate.filterValueContracts); equalValue(row.timeRange, candidate.timeRange); equalValue(row.clockSlots, candidate.clockSlots)
        }
        require(catalog.dimensions.size == capability.analysisDimensionCandidates.size && catalog.timeDimensions.size == capability.timeAnalysisDimensionCandidates.size) { "frozen dimension set differs from capability" }
        capability.analysisDimensionCandidates.forEach { candidate ->
            val dimension = catalog.dimension(candidate.analysisDimensionId, candidate.analysisDimensionVersion)
            equalValue(dimension.displayName, candidate.analysisDimensionDisplayName); equalValue(dimension.levels, candidate.availableLevelCodes)
            equalValue(dimension.eventMappings, candidate.eventMappings.map { EventMapping(it.mappingId, it.levelCode, it.levelDisplayName, it.eventId, it.eventVersion, it.eventDisplayName, it.valueAlignmentKey, it.valueAlignmentStatus) })
        }
        capability.timeAnalysisDimensionCandidates.forEach { candidate ->
            val dimension = catalog.timeDimension(candidate.analysisDimensionId, candidate.analysisDimensionVersion)
            equalValue(dimension.displayName, candidate.analysisDimensionDisplayName); equalValue(dimension.levels, candidate.availableLevelCodes)
            equalValue(dimension.anchors, candidate.eventAnchors.map { Anchor(it.mappingId, it.levelCode, it.eventId, it.eventVersion, it.eventDisplayName, it.timeRole) })
        }
        equalValue(catalog.combinations, capability.metricAnalysisDimensionMatrix.map { Combination(it.instance, it.analysisDimensionId, it.analysisDimensionVersion, requireNotNull(it.levelCode), it.available, it.reasonCode, it.reasonMessage) })
        require(catalog.filters.size == capability.filterCandidates.size) { "frozen filter set differs from capability" }
        capability.filterCandidates.forEach { candidate ->
            val rows = catalog.filters.filter { it.id == candidate.filterRefId && it.version == capability.scenario.scenarioVersion }
            require(rows.size == 1) { "exact published filter is required" }
            val filter = rows.single()
            equalValue(filter.displayName, candidate.filterTargetDisplayName)
            equalValue(filter.dimensionRef, AssetReference(candidate.filterTargetObjectId, candidate.filterTargetObjectVersion))
            equalValue(filter.operators, candidate.operators); equalValue(filter.valueTypes, candidate.filterValueTypes)
            equalValue(filter.requiredByDefault, candidate.requiredByDefault)
            equalValue(filter.eventMappings, candidate.eventMappings.map { FilterMapping(it.levelCode, it.eventId, it.eventVersion) })
        }
    }

    private fun requireSemanticDependencies(semantic: Catalog) {
        val native = try { OlapJsonUtils.mapper().treeToValue(capability.nativeDefinitions.catalog, ExecutableCatalog::class.java) }
        catch (failure: java.io.IOException) { throw IllegalArgumentException("frozen semantic dependency catalog is invalid", failure) }
        val closure = PublishedInstanceContract.require(native, capability)
        val actual = linkedSetOf<MetricSource.Dependency>()
        semantic.semanticDependencies.forEach { dependency ->
            when (dependency) {
                is CalculationDependency -> {
                    actual.add(MetricSource.MetricDependency(dependency.reference))
                    equalValue(dependency.definition, native.requireMetric(dependency.reference).definition)
                }
                is FactDependency -> {
                    actual.add(MetricSource.MetricDependency(dependency.reference))
                    val fact = native.requireMetric(dependency.reference).definition as? MetricDefinition.FactMeasure ?: throw IllegalArgumentException("semantic fact dependency is not a frozen fact")
                    equalValue(dependency.event, fact.event); equalValue(dependency.valueContract, fact.valueContract)
                    equalValue(dependency.aggregation.function, fact.aggregation); equalValue(dependency.aggregation.distinct, fact.distinct)
                    equalValue(dependency.aggregation.rounding, fact.aggregationRounding)
                }
                is InstanceDependency -> {
                    actual.add(MetricSource.InstanceDependency(dependency.reference))
                    equalValue(dependency.instance, native.requireMetricInstance(dependency.reference))
                }
                is MemberDependency -> {
                    actual.add(MetricSource.MemberDependency(dependency.reference))
                    requireMemberDependency(dependency, native.requireMemberSource(dependency.reference))
                }
            }
        }
        require(actual == closure.dependencies.toSet()) { "semantic dependencies differ from the exact frozen mixed closure" }
    }

    private fun requireMemberDependency(dependency: MemberDependency, source: MemberSource) {
        val result = source.definition.result
        equalValue(dependency.entity, result.entity); equalValue(dependency.role, result.role)
        equalValue(dependency.shape, result.shape); equalValue(dependency.identitySlots, result.identitySlots)
        require(dependency.definition.slots.keys == result.outputs.keys) { "semantic member outputs differ from its frozen source" }
        result.outputs.forEach { (slot, output) ->
            val meaning = dependency.definition.slots.getValue(slot)
            equalValue(meaning.displayName, output.displayName); equalValue(meaning.role, output.role)
            when (output) {
                is MemberSourceDefinition.ScalarOutput -> {
                    val scalar = PublishedSemanticCatalog.scalar(meaning)
                    equalValue(scalar.valueType, output.valueType); equalValue(scalar.valueContract, output.valueContract)
                }
                is MemberSourceDefinition.ComputedContainerPropertyOutput -> {
                    require(meaning is ComputedContainerSlotMeaning) { "computed member container is missing" }
                    equalValue(meaning.shape, MemberSourceDefinition.containerShape(output)); equalValue(meaning.symbol, output.symbol)
                }
                else -> {
                    require(meaning is ContainerSlotMeaning) { "projected member container is missing" }
                    equalValue(meaning.shape, MemberSourceDefinition.containerShape(output))
                }
            }
            when (output) {
                is MemberSourceDefinition.ComputedDimensionOutput -> {
                    require(meaning is ComputedSlotMeaning) { "computed dimension has no exact semantic output" }
                    equalValue(meaning.symbol, output.symbol); equalValue(meaning.dimensionSlot, output.dimensionSlot)
                }
                is MemberSourceDefinition.ComputedPropertyOutput -> {
                    require(meaning is ComputedSlotMeaning) { "computed property has no exact semantic output" }
                    equalValue(meaning.symbol, output.symbol)
                    require(meaning.dimensionSlot == null) { "computed property cannot acquire a dimension slot" }
                }
                else -> kotlin.Unit
            }
        }
        when (source) {
            is MemberSource.Computed -> {
                val meaning = dependency.definition as? ComputedMeaning ?: throw IllegalArgumentException("semantic computed member is not its frozen kind")
                equalValue(meaning.calculation, source.definition.calculation); equalValue(meaning.bindings, source.bindings); equalValue(meaning.fixedArguments, source.fixedArguments)
                equalValue(meaning.parameters, CalculationInstantiation.remainingParameters(source.definition.calculation, source.bindings, source.fixedArguments))
                equalValue(meaning.groupingCandidates, CalculationInstantiation.remainingGroupingCandidates(source.definition.calculation, source.bindings, source.fixedArguments))
                equalValue(meaning.memberParameters, CalculationInstantiation.remainingMemberParameters(source.definition.calculation, source.bindings))
            }
            is MemberSource.Projection -> {
                val meaning = dependency.definition as? ProjectionMeaning ?: throw IllegalArgumentException("semantic projected member is not its frozen kind")
                equalValue(meaning.event, source.definition.event)
            }
        }
    }
    companion object {
        private fun equalValue(value: Any?, expected: Any?) {
            require(QueryIntentHash.compute(OlapJsonUtils.mapper().valueToTree(value)) == QueryIntentHash.compute(OlapJsonUtils.mapper().valueToTree(expected))) { "frozen semantic value differs from capability" }
        }
        fun key(capability: QueryCapabilityView): QueryCapabilityRequest = QueryCapabilityRequest(capability.scenario.scenarioId, capability.scenario.scenarioVersion, capability.releaseId, capability.releaseVersion)
    }
}
