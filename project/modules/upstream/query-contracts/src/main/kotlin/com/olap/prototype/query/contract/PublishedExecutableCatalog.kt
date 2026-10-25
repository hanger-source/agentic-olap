package com.olap.prototype.query.contract

import com.olap.prototype.calculation.MetricCalculation
import com.olap.prototype.calculation.MetricInstance
import com.olap.prototype.calculation.MetricSource
import com.olap.prototype.engine.catalog.ExecutableCatalog
import com.olap.prototype.member.MemberRef
import com.olap.prototype.member.MemberResult
import com.olap.prototype.member.MemberSource
import com.olap.prototype.member.MemberSourceDefinition
import com.olap.prototype.query.RelationClockDiscovery
import java.time.Instant
import java.util.Collections

/** 上游不可变发布封套；批准与业务所属不进入内核目录。 */
class PublishedExecutableCatalog(
     val formatVersion: String,
     val coordinate: QueryCapabilityRequest,
     val queryCapability: QueryCapabilityView,
     val catalog: ExecutableCatalog,
     metricAdoptions: Map<String, MetricAdoption>,
     objectVersionRefs: List<PublishedObjectVersionRef>,
     val generatedAt: Instant?,
     val diagnosticRunId: String?,
) {
    val metricAdoptions: Map<String, MetricAdoption> = Collections.unmodifiableMap(LinkedHashMap(metricAdoptions))
    val objectVersionRefs: List<PublishedObjectVersionRef> = java.util.List.copyOf(objectVersionRefs)
    init { require(formatVersion == FORMAT_VERSION) { "unsupported published executable catalog" } }

    fun validate(): MetricSource.Closure {
        catalog.validate()
        requireEnvelopeIdentity()
        val closure = PublishedInstanceContract.require(catalog, queryCapability)
        requireAdoptions(); requireMembers(); requireRelations(); requireDimensions()
        return closure
    }
    private fun requireEnvelopeIdentity() {
        require((queryCapability.capabilityHash?.matches(Regex("[a-f0-9]{64}")) == true)) { "exact capability hash is required" }
        require(coordinate == PublishedQueryEntry.key(queryCapability)) { "published capability coordinate differs from its envelope" }
        require(catalog.manifest.catalogId == queryCapability.nativeDefinitions.catalog.manifest.catalogId) { "native compilation catalog identity differs from execution catalog" }
    }
    private fun requireAdoptions() {
        val adopted = linkedMapOf<String, MetricInstance>()
        metricAdoptions.forEach { (id, adoption) ->
            require(id == adoption.adoptionId) { "adoption key differs from its identity" }
            require(adopted.putIfAbsent(adoption.instance.id, adoption.computation) == null) { "duplicate exact metric adoption instance" }
        }
        require(adopted == catalog.metricInstances) { "kernel instances differ from the exact reachable frozen adoptions" }
        queryCapability.metricCandidates.forEach { candidate ->
            val adoption = requireNotNull(metricAdoptions[candidate.adoptionId]) { "published metric adoption is missing" }
            require(adoption.subjectAreaVersion == candidate.adoptionVersion && adoption.subjectAreaId == candidate.subjectAreaId &&
                adoption.subjectAreaVersion == candidate.subjectAreaVersion && adoption.definition == candidate.definition && adoption.instance == candidate.instance &&
                adoption.code == candidate.code && adoption.displayName == candidate.displayName && adoption.businessMeaning == candidate.businessMeaning) { "published metric adoption differs from its frozen version" }
            val metric = catalog.requireMetric(candidate.definition)
            val sources = MetricSource.resolve(adoption.definition, adoption.bindings, { catalog.requireMetric(it).definition },
                { requireNotNull(catalog.metricInstances[it.id]) { "source measurement instance is not frozen" } },
                { id, version -> requireNotNull(catalog.memberSources["$id@$version"]) { "source member is not frozen" } })
            require(sources == candidate.sources) { "published adopted source closure differs from the execution catalog" }
            require(candidate.definitionKind == if (metric.definition is MetricCalculation) "CALCULATION" else "FACT_MEASURE") { "published metric definition kind differs from execution definition" }
            require(candidate.valueContract == metric.definition.valueContract.adopted(adoption.bindings)) { "published metric value contract differs from its frozen definition and adoption" }
        }
    }

    private fun requireMembers() {
        val approved = queryCapability.analysisDimensionCandidates.map { "${it.analysisDimensionId}@${it.analysisDimensionVersion}" }.toSet()
        queryCapability.memberCandidates.forEach { candidate ->
            val source = catalog.requireMemberSource(MemberRef(candidate.sourceId, candidate.sourceVersion))
            requireMemberInvocation(candidate, source)
            requireMemberPermission(candidate, source.definition.result)
            candidate.unitDimensions.forEach { (slot, references) ->
                val expected = catalog.memberDimensionMappings(source, slot).filter { "${it.id}@${it.version}" in approved }.toSet()
                require(expected == references.toSet()) { "published member unit mappings differ from their exact frozen binding" }
            }
            requireMemberOutputs(candidate, source)
        }
    }
    private fun requireMemberInvocation(candidate: MemberCandidate, member: MemberSource) {
        val context = queryCapability.executionTypes.members["${candidate.sourceId}@${candidate.sourceVersion}"]
        when (member) {
            is MemberSource.Computed -> require(candidate.parameters == member.remainingParameters() && candidate.fixedArguments == member.fixedArguments &&
                candidate.groupingCandidates == member.remainingGroupingCandidates() && candidate.memberParameters == member.remainingMemberParameters() && candidate.relationParameters == member.remainingRelationParameters() &&
                context is QueryExecutionTypes.ComputedMember) { "published computed member differs from its frozen instantiation" }
            is MemberSource.Projection -> require(candidate.parameters.isEmpty() && candidate.fixedArguments.isEmpty() && candidate.groupingCandidates.isEmpty() &&
                candidate.memberParameters.isEmpty() && candidate.relationParameters.isEmpty() && context is QueryExecutionTypes.ProjectionMember) { "published projection member carries a computed call contract" }
        }
    }
    private fun requireMemberPermission(candidate: MemberCandidate, definition: MemberResult) {
        require(candidate.entity == definition.entity && candidate.role == definition.role && candidate.shape == definition.shape && candidate.identitySlots == definition.identitySlots) { "published member identity differs from frozen source" }
        val permission = candidate.permission
        require(definition.outputs.keys.containsAll(permission.outputs) && definition.outputs.keys.containsAll(permission.values) && definition.filterOperators.keys.containsAll(permission.filters.keys) && candidate.clockSlots.keys == permission.clocks.toSet()) { "member permission is outside frozen source capabilities" }
        permission.filters.forEach { (slot, operators) ->
            require(definition.filterOperators.getValue(slot).containsAll(operators) && candidate.filterTypes[slot] == MemberSourceDefinition.scalar(definition.outputs.getValue(slot)).valueType) { "published member filter differs from approved source slot" }
        }
        candidate.clockSlots.forEach { (slot, type) -> require(type == definition.clockSlots[slot]) { "published member clock differs from frozen source" } }
        if (permission.timeRange) {
            val range = candidate.timeRange
            require(range != null && range.slot == definition.timeSlot && range.valueType == MemberSourceDefinition.scalar(definition.outputs.getValue(requireNotNull(definition.timeSlot))).valueType) { "published member time range differs from frozen source" }
        }
        candidate.filterValueContracts.forEach { (slot, value) ->
            require(value == MemberSourceDefinition.rootValueContract(definition.outputs.getValue(slot))) { "member filter numeric declaration differs from its frozen source" }
        }
    }
    private fun requireMemberOutputs(candidate: MemberCandidate, member: MemberSource) {
        val definition = member.definition.result
        candidate.outputs.forEach { (slot, output) ->
            val original = requireNotNull(definition.outputs[slot]) { "approved member output is missing" }
            if (output is MemberCandidate.ContainerProperty) {
                require(original !is MemberSourceDefinition.ScalarOutput && output.shape == MemberSourceDefinition.containerShape(original) && original.role == output.role) { "published member container differs from its frozen source" }
            } else {
                require(MemberCandidate.rootValueContract(output) == MemberSourceDefinition.rootValueContract(original)) { "member output value declaration differs from the frozen source" }
                require(original.role == output.role && MemberSourceDefinition.scalar(original).valueType == MemberCandidate.scalar(output).valueType) { "published member output meaning differs from frozen source" }
                if (output is MemberCandidate.Dimension) {
                    requireDimensionIdentity(member, original, output)
                    require(catalog.memberDimensionMappings(member, slot).toSet().containsAll(output.mappings)) { "published member mapping is not its exact source expression binding" }
                } else require(original is MemberSourceDefinition.PropertyOutput || original is MemberSourceDefinition.ComputedPropertyOutput) { "published member output changed dimension into property" }
            }
        }
    }

    private fun requireRelations() {
        queryCapability.relationFilterCandidates.forEach { candidate ->
            val grant = candidate.definition
            val relation = grant.relation
            require(relation == catalog.relationFilters["${relation.id}@${relation.version}"]) { "published relation permission differs from its frozen owner" }
            val member = requireNotNull(catalog.memberSources["${relation.memberSourceId}@${relation.memberSourceVersion}"]) { "related source is not frozen" }.definition.result
            require(candidate.entity == member.entity && candidate.role == member.role) { "relation candidate differs from its related source meaning" }
            grant.permission.filters.forEach { (slot, operators) ->
                val output = requireNotNull(member.outputs[slot]) { "related source slot is missing" }
                require(candidate.filterTypes[slot] == MemberSourceDefinition.scalar(output).valueType && member.filterOperators.getValue(slot).containsAll(operators)) { "published relation filter differs from its frozen slot type or operators" }
                require(candidate.filterValueContracts[slot] == MemberSourceDefinition.rootValueContract(output)) { "relation predicate numeric declaration differs from its frozen source" }
            }
            val parent = requireNotNull(catalog.events["${relation.parent.eventId}@${relation.parent.eventVersion}"]) { "relation parent event is not frozen" }
            val discovery = RelationClockDiscovery { ref -> requireNotNull(catalog.joinPaths["${ref.joinPathId}@${ref.joinPathVersion}"]) { "relation path is not frozen" }.temporalConstraint }
            discovery.path("relation:${relation.id}", emptyList(), requireNotNull(parent.roles[relation.relationshipRole]) { "relation role is not frozen" })
            val clocks = RelationClockDiscovery.relationSlots(member.clockSlots, discovery.slots().keys)
            grant.permission.clocks.forEach { require(clocks[it] == candidate.clockSlots[it]) { "published relation clock differs from its frozen position" } }
            if (grant.permission.timeRange) {
                val range = candidate.timeRange
                require(range != null && range.slot == member.timeSlot && range.valueType == MemberSourceDefinition.scalar(member.outputs.getValue(requireNotNull(member.timeSlot))).valueType) { "relation range differs from its frozen source time slot" }
            }
        }
    }
    private fun requireDimensions() {
        queryCapability.analysisDimensionCandidates.forEach { candidate ->
            val dimension = requireNotNull(catalog.analysisDimensions["${candidate.analysisDimensionId}@${candidate.analysisDimensionVersion}"]) { "published dimension is missing from execution catalog" }
            require(dimension.outputAlias == candidate.outputAlias) { "published dimension output differs from execution catalog" }
        }
        queryCapability.timeAnalysisDimensionCandidates.forEach { candidate ->
            val dimension = requireNotNull(catalog.analysisDimensions["${candidate.analysisDimensionId}@${candidate.analysisDimensionVersion}"]) { "published time dimension is missing from execution catalog" }
            require(dimension.outputAlias == candidate.outputAlias) { "published time dimension output differs from execution catalog" }
        }
    }
    override fun equals(other: Any?): Boolean = other is PublishedExecutableCatalog && formatVersion == other.formatVersion && coordinate == other.coordinate && queryCapability == other.queryCapability && catalog == other.catalog && metricAdoptions == other.metricAdoptions && objectVersionRefs == other.objectVersionRefs && generatedAt == other.generatedAt && diagnosticRunId == other.diagnosticRunId
    override fun hashCode(): Int = listOf(formatVersion, coordinate, queryCapability, catalog, metricAdoptions, objectVersionRefs, generatedAt, diagnosticRunId).hashCode()
    companion object {
        const val FORMAT_VERSION = "olap-published-executable-catalog/v7"
        private fun requireDimensionIdentity(member: MemberSource, output: MemberSourceDefinition.Output, published: MemberCandidate.Dimension) {
            val identity = when (output) {
                is MemberSourceDefinition.DimensionOutput -> output.dimensionId to output.dimensionVersion
                is MemberSourceDefinition.ComputedDimensionOutput -> {
                    val computed = member as? MemberSource.Computed ?: throw IllegalArgumentException("published member dimension is not a semantic dimension output")
                    val slot = computed.definition.calculation.dimensionSlots.firstOrNull { it.name == output.dimensionSlot } ?: throw IllegalArgumentException("computed semantic dimension slot is missing")
                    slot.dimensionId to slot.dimensionVersion
                }
                else -> throw IllegalArgumentException("published member dimension is not a semantic dimension output")
            }
            require(published.dimensionId == identity.first && published.dimensionVersion == identity.second) { "published member dimension is not a semantic dimension output" }
        }
    }
}
