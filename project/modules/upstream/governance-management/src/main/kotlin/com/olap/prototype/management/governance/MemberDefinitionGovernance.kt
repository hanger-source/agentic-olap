package com.olap.prototype.management.governance

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.calculation.MetricCalculation
import com.olap.prototype.calculation.MetricRef
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.governance.contract.*
import com.olap.prototype.member.MemberSourceDefinition
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.util.OlapJsonUtils
import java.time.Instant
import java.util.UUID

class MemberDefinitionRepository(private val database: PlatformDatabase) {
    internal fun save(record: MemberDefinitionRecord, sourceVersion: Long?) {
        try {
            if (sourceVersion == null) GovernanceJdbcSupport.insert(database, "member_definition", mapOf("id" to record.id))
            GovernanceJdbcSupport.insert(database, "member_definition_version", linkedMapOf("id" to "${record.id}:v${record.currentVersion}", "member_definition_id" to record.id, "version" to record.currentVersion, "version_status" to "DRAFT", "content_json" to OlapJsonUtils.mapper().writeValueAsString(record), "source_version" to sourceVersion, "created_by" to record.createdBy, "created_at" to record.createdAt))
        } catch (failure: Exception) { throw IllegalStateException("cannot save exact member definition", failure) }
    }
    fun read(id: String, version: Long): MemberDefinitionRecord {
        ProducerContracts.requireText(id, "member definition id"); ProducerContracts.requireVersion(version, "member definition version")
        return try {
            GovernanceJdbcSupport.query(database, "SELECT content_json,version_status FROM member_definition_version WHERE member_definition_id=? AND version=?", listOf(id, version)) { rows ->
                val content = OlapJsonUtils.mapper().readValue(rows.getString(1), MemberDefinitionRecord::class.java)
                if (content.id != id || content.currentVersion != version) throw ProducerContractException("member definition snapshot coordinate mismatch")
                content.copy(status = rows.getString(2), currentVersion = version)
            }.singleOrNull() ?: throw ProducerContractException("exact member definition is missing: $id@$version")
        } catch (failure: ProducerContractException) { throw failure }
        catch (failure: Exception) { throw IllegalStateException("cannot read exact member definition", failure) }
    }
    fun list(): List<MemberDefinitionRecord> = GovernanceWorkingVersionResolver(database).list("member_definition_version", "member_definition_id").map { read(it.objectId, it.version) }.sortedBy { it.code }
}

/** 共享定义的生命周期与上下文无关性；采用绑定由主题域拥有。 */
class MemberDefinitionGovernanceService(private val database: PlatformDatabase, private val metrics: MetricGovernanceService, private val events: EventGovernanceService, private val dimensions: DimensionGovernanceService, private val logicalModel: LogicalModelGovernanceService) {
    private val repository = MemberDefinitionRepository(database)
    fun create(request: MemberDefinitionCreateRequest): MemberDefinitionRecord = database.inTransaction { write(request, null) }
    fun revise(request: GovernanceRevisionRequest<MemberDefinitionCreateRequest>): MemberDefinitionRecord = database.inTransaction { write(request.definition, request) }
    private fun write(request: MemberDefinitionCreateRequest, revision: GovernanceRevisionRequest<MemberDefinitionCreateRequest>?): MemberDefinitionRecord {
        val actor = ProducerContracts.requireText(request.createdBy, "member definition createdBy")
        val id = revision?.objectId ?: "member-definition-${UUID.randomUUID()}"
        val draft = GovernanceDraftVersions(database).start(GovernanceDraftVersions.Asset.MEMBER_DEFINITION, id, revision, actor)
        validate(request.definition, false)
        val record = MemberDefinitionRecord(id, ProducerContracts.requireText(request.code, "member definition code"), ProducerContracts.requireText(request.displayName, "member definition displayName"), request.definition, request.description, ProducerContracts.requireText(request.owner, "member definition owner"), "DRAFT", draft.version, actor, Instant.now())
        repository.save(record, draft.sourceVersion)
        return record
    }
    fun read(id: String, version: Long): MemberDefinitionRecord = repository.read(id, version)
    fun list(): List<MemberDefinitionRecord> = repository.list()
    fun validate(definition: MemberSourceDefinition, requireApproved: Boolean) {
        try {
            when (definition) {
                is MemberSourceDefinition.Projection -> validateProjection(definition, requireApproved)
                is MemberSourceDefinition.Computed -> CalculationValidation(requireApproved).visit(definition.calculation)
            }
        } catch (failure: ProducerContractException) { throw failure }
        catch (failure: IllegalArgumentException) { throw ProducerContractException(requireNotNull(failure.message), failure) }
    }
    /** 缓存的是已证明子树深度，不是当前父路径深度，复用时仍核预算。 */
    private inner class CalculationValidation(private val approved: Boolean) {
        private val active = linkedSetOf<MetricRef>()
        private val completedDepths = hashMapOf<MetricRef, Int>()
        fun visit(calculation: Calculation): Int {
            if (calculation.memberSlots.any { it.fixedSource != null }) throw ProducerContractException("shared member definitions use member slots; subject-area member sources are bound by each adoption")
            calculation.parameters.values.forEach { contextFree(it.defaultValue) }
            calculation.measurementSlots.forEach { slot -> slot.parameters.values.forEach { contextFree(it.defaultValue) } }
            calculation.declaredDimensions().values.forEach { slot ->
                val dimension = dimensions.dimensionSnapshot(slot.dimensionId, slot.dimensionVersion)
                if (approved) GovernanceContract.requireReferenceableVersion(dimension.status, "DIMENSION", dimension.id, slot.dimensionVersion)
            }
            var height = 0
            metrics.validateCalculation(calculation, null).values.forEach { call ->
                call.arguments.values.forEach(::contextFree)
                contextFree(call.sourceCondition)
                val fixed = call.target as? Calculation.Fixed ?: return@forEach
                val ref = fixed.metric
                if (ref in active) throw ProducerContractException("shared member definition has a fixed metric dependency cycle: $active -> $ref")
                val completed = completedDepths[ref]
                if (completed != null) { depth(active.size + completed); height = maxOf(height, completed); return@forEach }
                val metric = metrics.metricSnapshot(ref.id, ref.version.toLong())
                if (approved) GovernanceContract.requireReferenceableVersion(metric.status, "METRIC", ref.id, ref.version.toLong())
                var childDepth = 1
                val definition = metric.definition
                if (definition is MetricCalculation) {
                    depth(active.size + 1); active.add(ref)
                    try { childDepth += visit(definition.calculation) } finally { active.remove(ref) }
                }
                depth(active.size + childDepth); completedDepths[ref] = childDepth; height = maxOf(height, childDepth)
            }
            return height
        }
        private fun depth(value: Int) { if (value > ScopedInputContract.MAX_DEPTH) throw ProducerContractException("shared member definition exceeds the fixed dependency depth budget") }
    }
    private fun contextFree(value: Calculation.Binding?) {
        if (value is Calculation.Grouping && value.dimensions.isNotEmpty()) throw ProducerContractException("shared member definitions bind analysis dimensions through grouping slots or adoption arguments, not contextual dimension references")
        if (value is Calculation.Condition && value.condition !is com.olap.prototype.query.DimensionCondition.Unrestricted)
            throw ProducerContractException("shared definitions express lasting population rules through semantic inputs; release filter conditions belong to invocation arguments")
    }
    fun validateProjection(projection: MemberSourceDefinition.Projection, requireApprovedProjection: Boolean) {
        val event = events.eventSnapshot(projection.event.eventId, projection.event.eventVersion)
        if (requireApprovedProjection) GovernanceContract.requireReferenceableVersion(event.status, "EVENT", event.id, projection.event.eventVersion)
        projection.result.outputs.values.forEach { output ->
            if (output is MemberSourceDefinition.DimensionOutput) {
                val binding = dimensions.dimensionEventBindingSnapshot(output.bindingId, output.bindingVersion)
                if (binding.dimensionId != output.dimensionId || binding.dimensionVersion != output.dimensionVersion || binding.eventId != event.id || binding.eventVersion != projection.event.eventVersion) throw ProducerContractException("member dimension output differs from its exact event or semantic binding")
                if (requireApprovedProjection) GovernanceContract.requireReferenceableVersion(binding.status, "DIMENSION_EVENT_BINDING", binding.id, output.bindingVersion)
                val semantic = dimensions.dimensionSnapshot(output.dimensionId, output.dimensionVersion)
                if (requireApprovedProjection) GovernanceContract.requireReferenceableVersion(semantic.status, "DIMENSION", semantic.id, output.dimensionVersion)
            } else {
                val ref = MemberSourceDefinition.propertyReference(output); val path = MemberSourceDefinition.propertyPath(output)
                events.requirePositionedLogicUnit(event.id, projection.event.eventVersion, path, ref.id, ref.version, "member output position")
                events.requireLogicUnitRef(event.id, projection.event.eventVersion, ref.id, ref.version, "member output")
                val unit = logicalModel.logicUnitSnapshot(ref.id, ref.version)
                if (requireApprovedProjection) GovernanceContract.requireReferenceableVersion(unit.status, "LOGIC_UNIT", unit.id, ref.version)
                if (output is MemberSourceDefinition.PropertyOutput) {
                    if (unit.valueShape != null) throw ProducerContractException("scalar member output cannot hide a LogicUnit container")
                    output.requireValueType(ScalarValueType.fromLogicType(requireNotNull(unit.logicType)))
                } else if (MemberSourceDefinition.containerShape(output) != unit.valueShape) throw ProducerContractException("member container shape differs from its exact LogicUnit")
            }
        }
    }
}
