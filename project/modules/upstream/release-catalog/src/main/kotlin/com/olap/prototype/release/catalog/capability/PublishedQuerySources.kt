package com.olap.prototype.release.catalog.capability

import com.fasterxml.jackson.core.JsonProcessingException
import com.olap.prototype.calculation.CalculationInstantiation
import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.calculation.MetricRef
import com.olap.prototype.calculation.MetricSource
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.engine.catalog.ExecutableCatalog
import com.olap.prototype.event.PublishedDimensionBinding
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.expression.SemanticExpressionCodec
import com.olap.prototype.member.MemberRef
import com.olap.prototype.member.MemberResult
import com.olap.prototype.member.MemberSource
import com.olap.prototype.member.MemberSourceDefinition
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.RelationClockDiscovery
import com.olap.prototype.query.TemporalJoinContract
import com.olap.prototype.query.contract.*
import com.olap.prototype.release.catalog.ReleaseObjectIndex
import com.olap.prototype.release.contract.*

/** 同次精确发布中的来源和位置时钟，不读取治理数据库。 */
class PublishedQuerySources(private val snapshots: ReleaseObjectIndex, private val assets: ExecutableCatalog) {
    private val objects = snapshots.objects.sortedBy { "${it.objectType}:${it.objectId}@${it.objectVersion}" }

    fun metric(reference: ScenarioMetricReference): MetricCandidate {
        val adoption = snapshots.scenarioAdoption(reference)
        val definition = snapshots.metric(adoption.definition).definition
        val computation = adoption.computation
        val fact = definition as? MetricDefinition.FactMeasure
        return MetricCandidate(adoption.adoptionId, adoption.subjectAreaVersion, adoption.subjectAreaId, adoption.subjectAreaVersion, adoption.code,
            adoption.displayName, adoption.businessMeaning, adoption.definition, adoption.instance, definition.valueContract.adopted(adoption.bindings), fact?.snapshotRule?.meaning(),
            reference.adoptionStatus, reference.scenarioMetricRefId, reference.releaseObjectRefId,
            MetricSource.resolve(adoption.definition, adoption.bindings, { snapshots.metric(it).definition }, { snapshots.instance(it).computation }, ::frozenMember),
            if (fact != null) "FACT_MEASURE" else "CALCULATION", adoption.fixedArguments, computation.remainingParameters(definition),
            computation.remainingGroupingCandidates(definition), computation.remainingMemberParameters(definition), computation.remainingRelationParameters(definition), reference.requiredByDefault, reference.sortOrder)
    }

    fun member(reference: ScenarioMemberReference, dimensionKeys: Set<String>): MemberCandidate {
        val published = snapshots.member(MemberRef(reference.sourceId, reference.sourceVersion))
        val source = published.source
        val result = source.definition.result
        val permission = reference.permission
        val outputs = linkedMapOf<String, MemberCandidate.Output>()
        (permission.outputs + permission.values).distinct().forEach { slot ->
            val output = requireNotNull(result.outputs[slot]) { "approved member output is absent from the frozen source" }
            outputs[slot] = when (output) {
                is MemberSourceDefinition.DimensionOutput -> MemberCandidate.Dimension(output.displayName, output.role, output.valueType,
                    output.dimensionId, output.dimensionVersion, assets.memberDimensionMappings(source, slot).filter { "${it.id}@${it.version}" in dimensionKeys }, output.valueContract)
                is MemberSourceDefinition.ComputedDimensionOutput -> {
                    val computed = source as MemberSource.Computed
                    val dimension = computed.definition.calculation.dimensionSlots.first { it.name == output.dimensionSlot }
                    MemberCandidate.Dimension(output.displayName, output.role, output.valueType, dimension.dimensionId, dimension.dimensionVersion,
                        assets.memberDimensionMappings(source, slot).filter { "${it.id}@${it.version}" in dimensionKeys }, output.valueContract)
                }
                is MemberSourceDefinition.ScalarOutput -> MemberCandidate.Property(output.displayName, output.role, output.valueType, output.valueContract)
                else -> MemberCandidate.ContainerProperty(output.displayName, output.role, MemberSourceDefinition.containerShape(output))
            }
        }
        val clocks = permission.clocks.associateWith { requireNotNull(result.clockSlots[it]) { "approved member clock is absent" } }
        val filterTypes = permission.filters.keys.associateWith { MemberSourceDefinition.scalar(result.outputs.getValue(it)).valueType }
        val filterValues = permission.filters.keys.associateWith { MemberSourceDefinition.rootValueContract(result.outputs.getValue(it)) }
        val contracts = filterValues.values + outputs.values.map(MemberCandidate::rootValueContract)
        val units = contracts.mapNotNull { (it?.unit as? MetricValueContract.SlotDimension)?.slot }.toSortedSet()
        val unitDimensions = units.associateWith { slot -> assets.memberDimensionMappings(source, slot).filter { "${it.id}@${it.version}" in dimensionKeys } }
        val computed = source as? MemberSource.Computed
        return MemberCandidate(source.id, source.version, published.displayName, result.entity, result.role, result.shape, result.identitySlots, outputs,
            permission, filterTypes, filterValues, unitDimensions, range(result, permission.timeRange), clocks,
            computed?.remainingParameters() ?: emptyMap(), computed?.fixedArguments ?: emptyMap(), computed?.remainingGroupingCandidates() ?: emptyMap(),
            computed?.remainingMemberParameters() ?: emptyMap(), computed?.remainingRelationParameters() ?: emptyMap())
    }

    fun clocks(): List<TemporalClockCandidate> {
        val discovery = clockDiscovery()
        objects.forEach { reference -> when (reference.objectType) {
            "EVENT" -> {
                val event = readFrozen(reference, EventExecutionSnapshot::class.java, "frozen event")
                val expressions = event.baseFilterJson?.let { listOf(SemanticExpressionCodec.parse(it, "frozen event predicate")) } ?: emptyList()
                discovery.expression("event", RelationAccessPath(emptyList()), event.roles, expressions)
            }
            "METRIC" -> {
                val fact = snapshots.metric(MetricRef(reference.objectId, reference.objectVersion.toString())).definition as? MetricDefinition.FactMeasure
                if (fact != null) discovery.expression("metric:${reference.objectId}@${reference.objectVersion}", RelationAccessPath(emptyList()), fact.roles,
                    listOf(fact.input) + listOfNotNull(fact.privatePredicate) + (fact.snapshotRule?.expressions() ?: emptyList()))
            }
            "DIMENSION_EVENT_BINDING" -> {
                val binding = readFrozen(reference, PublishedDimensionBinding::class.java, "frozen dimension binding")
                discovery.expression("dimension:${binding.id}@${binding.version}", binding.accessPath, binding.roles, listOf(binding.expression))
            }
            "MEMBER_SOURCE" -> {
                val source = frozenMember(reference.objectId, reference.objectVersion) as? MemberSource.Projection
                source?.definition?.result?.outputs?.forEach { (slot, output) ->
                    if (output is MemberSourceDefinition.PropertyOutput || output is MemberSourceDefinition.ContainerPropertyOutput)
                        discovery.expression("member:${source.id}@${source.version}:$slot", MemberSourceDefinition.propertyPath(output), emptyMap(), listOf(source.outputExpressions.getValue(slot)))
                }
            }
            "RELATION_FILTER" -> {
                val grant = readFrozen(reference, PublishedRelationFilter::class.java, "frozen relation filter")
                val roles = frozenRelationParent(grant).roles
                discovery.path("relation:${grant.relation.id}", emptyList(), requireNotNull(roles[grant.relation.relationshipRole]) { "frozen relation role is missing" })
            }
            else -> kotlin.Unit
        } }
        return discovery.slots().map { (reference, clock) -> TemporalClockCandidate(reference, clock.valueType, clock.description) }
    }

    fun relationFilters(scenarioId: String, scenarioVersion: Long): List<RelationFilterCandidate> = objects.filter { it.objectType == "RELATION_FILTER" }.mapNotNull { reference ->
        val grant = readFrozen(reference, PublishedRelationFilter::class.java, "frozen relation filter")
        if (grant.scenarioId != scenarioId || grant.relation.version != scenarioVersion) return@mapNotNull null
        val source = frozenMember(grant.relation.memberSourceId, grant.relation.memberSourceVersion)
        if (source !is MemberSource.Projection) throw ProducerContractException("event-role relation requires a projection member source")
        val result = source.definition.result
        val types = grant.permission.filters.keys.associateWith { MemberSourceDefinition.scalar(requireNotNull(result.outputs[it]) { "frozen relation slot is absent" }).valueType }
        val roles = frozenRelationParent(grant).roles
        val discovery = clockDiscovery()
        discovery.path("relation:${grant.relation.id}", emptyList(), requireNotNull(roles[grant.relation.relationshipRole]) { "frozen relation role is missing" })
        val available = RelationClockDiscovery.relationSlots(result.clockSlots, discovery.slots().keys)
        val clocks = grant.permission.clocks.associateWith { requireNotNull(available[it]) { "frozen relation clock is absent" } }
        val values = grant.permission.filters.keys.associateWith { MemberSourceDefinition.rootValueContract(result.outputs.getValue(it)) }
        RelationFilterCandidate(grant, result.entity, result.role, types, values, range(result, grant.permission.timeRange), clocks)
    }.sortedBy { it.definition.sortOrder }

    private fun range(result: MemberResult, allowed: Boolean): MemberCandidate.TimeRangeCapability? {
        if (!allowed) return null
        val slot = requireNotNull(result.timeSlot)
        return MemberCandidate.TimeRangeCapability(slot, MemberSourceDefinition.scalar(result.outputs.getValue(slot)).valueType)
    }
    private fun frozenRelationParent(grant: PublishedRelationFilter): EventExecutionSnapshot = readFrozen(snapshots.require("EVENT", grant.relation.parent.eventId,
        grant.relation.parent.eventVersion), EventExecutionSnapshot::class.java, "frozen relation parent")
    private fun frozenMember(id: String, version: Long): MemberSource = snapshots.member(MemberRef(id, version)).source
    private fun clockDiscovery(): RelationClockDiscovery = RelationClockDiscovery { ref ->
        val path = readFrozen(snapshots.require("JOIN_PATH", ref.joinPathId, ref.joinPathVersion), ReleaseJoinPathSnapshot::class.java, "frozen positioned clock path")
        if (path.joinPathId != ref.joinPathId || path.joinPathVersion != ref.joinPathVersion) throw ProducerContractException("frozen clock path differs from its exact version reference")
        TemporalJoinContract.parse(requireNotNull(path.temporalConfigJson), "frozen positioned clock")
    }
    private fun <T : Any> readFrozen(reference: ReleaseObjectRef, type: Class<T>, field: String): T = try { snapshots.read(reference, type) }
    catch (failure: JsonProcessingException) { throw ProducerContractException("$field is invalid", failure) }
}
