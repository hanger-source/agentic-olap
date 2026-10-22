package com.olap.prototype.member

import com.fasterxml.jackson.annotation.JsonInclude
import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.ScopedInputContract

/** 记录或对象的身份、形态和字段含义；不推断来源唯一性。 */
class MemberResult(val entity: String, val role: String, val shape: MemberShape, identitySlots: List<String>,
    outputs: Map<String, MemberSourceDefinition.Output>, filterOperators: Map<String, List<MemberCondition.Operator>>,
    val timeSlot: String?, clockSlots: Map<String, String>, @get:JsonInclude(JsonInclude.Include.NON_NULL) val description: String?) {
    val identitySlots: List<String> = java.util.List.copyOf(identitySlots)
    val outputs: Map<String, MemberSourceDefinition.Output> = java.util.Map.copyOf(outputs)
    val filterOperators: Map<String, List<MemberCondition.Operator>> = MemberCondition.operators(filterOperators)
    val clockSlots: Map<String, String> = java.util.Map.copyOf(clockSlots)
    init {
        MetricDefinition.text(entity, "member entity"); MetricDefinition.text(role, "member role")
        description?.let { MetricDefinition.text(it, "member source description") }
        this.clockSlots.keys.forEach { ScopedInputContract.requireId(it) }
        require(this.clockSlots.values.all { it.matches(Regex("clock_[0-9a-f]{64}")) } && this.clockSlots.values.distinct().size == this.clockSlots.size) { "member clock names must identify distinct exact relationship positions" }
        require((shape == MemberShape.RAW_BAG) == this.identitySlots.isEmpty()) { "raw bags declare no identity; identified results require identity slots" }
        require(this.identitySlots.distinct().size == this.identitySlots.size && this.outputs.keys.containsAll(this.identitySlots) && this.outputs.keys.containsAll(this.filterOperators.keys)) { "member identity and filters require distinct declared output slots" }
        (this.identitySlots + this.filterOperators.keys).forEach { MemberSourceDefinition.scalar(requireNotNull(this.outputs[it])) }
        if (timeSlot != null) require(this.outputs[timeSlot]?.let { MemberSourceDefinition.scalar(it).valueType } in setOf(ScalarValueType.DATE, ScalarValueType.LOCAL_TIMESTAMP, ScalarValueType.OFFSET_TIMESTAMP)) { "member time slot must be an explicitly approved temporal output" }
        this.outputs.values.forEach { output ->
            val value = MemberSourceDefinition.rootValueContract(output)
            if (value != null) {
                require(MemberSourceDefinition.scalar(output).valueType == ScalarValueType.NUMBER && value.unitEquations.isEmpty()) { "member quantities declare numeric facts, not calculation unit equations" }
                when (val unit = value.unit) {
                    is MetricValueContract.Fixed -> kotlin.Unit
                    is MetricValueContract.SlotDimension -> {
                        val categorical = this.outputs[unit.slot]
                        require(categorical != null && MemberSourceDefinition.rootValueContract(categorical) == null && MemberSourceDefinition.scalar(categorical).valueType == unit.axis.memberType && categorical.role == unit.axis.role) { "member quantity requires its exact categorical output slot" }
                        when (categorical) {
                            is MemberSourceDefinition.DimensionOutput -> require(categorical.dimensionId == unit.axis.dimensionId && categorical.dimensionVersion == unit.axis.dimensionVersion) { "member unit differs from its projected semantic dimension" }
                            is MemberSourceDefinition.ComputedDimensionOutput -> kotlin.Unit
                            else -> throw IllegalArgumentException("member unit slot must be a semantic dimension output")
                        }
                    }
                    else -> throw IllegalArgumentException("member units belong to fixed identities or local semantic output slots")
                }
            }
        }
    }
    override fun equals(other: Any?): Boolean = other is MemberResult && entity == other.entity && role == other.role && shape == other.shape && identitySlots == other.identitySlots && outputs == other.outputs && filterOperators == other.filterOperators && timeSlot == other.timeSlot && clockSlots == other.clockSlots && description == other.description
    override fun hashCode(): Int = listOf(entity, role, shape, identitySlots, outputs, filterOperators, timeSlot, clockSlots, description).hashCode()
}
