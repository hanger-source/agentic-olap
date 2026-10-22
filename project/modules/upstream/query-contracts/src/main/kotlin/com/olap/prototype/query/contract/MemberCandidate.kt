package com.olap.prototype.query.contract

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.member.MemberShape
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.query.SetOutputContract
import java.util.Collections
import java.util.TreeMap

/** 业务成员目录只公开批准槽位及其实际含义。 */
class MemberCandidate(val sourceId: String, val sourceVersion: Long, val displayName: String, val entity: String, val role: String,
    val shape: MemberShape, identitySlots: List<String>, outputs: Map<String, Output>, val permission: MemberQueryPermission,
    filterTypes: Map<String, ScalarValueType>, filterValueContracts: Map<String, MetricValueContract?>,
    unitDimensions: Map<String, List<ScopedInputContract.DimensionRef>>, val timeRange: TimeRangeCapability?, clockSlots: Map<String, String>,
    parameters: Map<String, Calculation.Parameter>, fixedArguments: Map<String, Calculation.Value>,
    groupingCandidates: Map<String, List<ScopedInputContract.DimensionRef>>, memberParameters: Map<String, Calculation.MemberSlot>, relationParameters: Map<String, Calculation.RelationSlot>) {
    val identitySlots: List<String> = java.util.List.copyOf(identitySlots)
    val outputs: Map<String, Output> = java.util.Map.copyOf(outputs)
    val filterTypes: Map<String, ScalarValueType> = java.util.Map.copyOf(filterTypes)
    val filterValueContracts: Map<String, MetricValueContract?> = Collections.unmodifiableMap(TreeMap(filterValueContracts))
    val unitDimensions: Map<String, List<ScopedInputContract.DimensionRef>> = Collections.unmodifiableMap(unitDimensions.mapValuesTo(TreeMap()) { java.util.List.copyOf(it.value) })
    val clockSlots: Map<String, String> = java.util.Map.copyOf(clockSlots)
    val parameters: Map<String, Calculation.Parameter> = java.util.Map.copyOf(parameters)
    val fixedArguments: Map<String, Calculation.Value> = java.util.Map.copyOf(fixedArguments)
    val groupingCandidates: Map<String, List<ScopedInputContract.DimensionRef>> = Collections.unmodifiableMap(groupingCandidates.mapValuesTo(TreeMap()) { java.util.List.copyOf(it.value) })
    val memberParameters: Map<String, Calculation.MemberSlot> = java.util.Map.copyOf(memberParameters)
    val relationParameters: Map<String, Calculation.RelationSlot> = java.util.Map.copyOf(relationParameters)
    init {
        MetricDefinition.text(sourceId, "member sourceId"); MetricDefinition.version(sourceVersion); MetricDefinition.text(displayName, "displayName")
        MetricDefinition.text(entity, "entity"); MetricDefinition.text(role, "role")
        require(this.parameters.keys.intersect(this.fixedArguments.keys).isEmpty()) { "fixed member arguments overlap invocation parameters" }
        require(this.outputs.keys == (permission.outputs + permission.values).toSet()) { "member candidates must exactly match approved outputs" }
        require(this.filterTypes.keys == permission.filters.keys && permission.timeRange == (timeRange != null)) { "member filter and range capabilities differ from permission" }
        require(this.filterValueContracts.keys == permission.filters.keys) { "member filter value contracts differ from their granted slots" }
        permission.values.forEach { scalar(this.outputs.getValue(it)) }
        val units = (this.filterValueContracts.values + this.outputs.values.map(::rootValueContract)).mapNotNull { (it?.unit as? MetricValueContract.SlotDimension)?.slot }.toSet()
        require(this.unitDimensions.keys == units) { "member unit dimensions must exactly cover the approved quantity slots" }
    }
    data class TimeRangeCapability(val slot: String, val valueType: ScalarValueType) { init { MetricDefinition.text(slot, "member time slot") } }


    sealed interface Output { val displayName: String; val role: String }
    sealed interface ScalarOutput : Output { val valueType: ScalarValueType; val valueContract: MetricValueContract? }
    data class ContainerProperty(override val displayName: String, override val role: String, val shape: SetOutputContract.Shape) : Output {
        init { MetricDefinition.text(displayName, "displayName"); MetricDefinition.text(role, "role"); SetOutputContract.requireSourceContainer(shape) }
    }
    class Dimension(override val displayName: String, override val role: String, override val valueType: ScalarValueType,
        val dimensionId: String, val dimensionVersion: Long, mappings: List<ScopedInputContract.DimensionRef>,
         override val valueContract: MetricValueContract?) : ScalarOutput {
        val mappings: List<ScopedInputContract.DimensionRef> = java.util.List.copyOf(mappings)
        override fun equals(other: Any?): Boolean = other is Dimension && displayName == other.displayName && role == other.role && valueType == other.valueType && dimensionId == other.dimensionId && dimensionVersion == other.dimensionVersion && mappings == other.mappings && valueContract == other.valueContract
        override fun hashCode(): Int = listOf(displayName, role, valueType, dimensionId, dimensionVersion, mappings, valueContract).hashCode()
    }
    data class Property(override val displayName: String, override val role: String, override val valueType: ScalarValueType,
         override val valueContract: MetricValueContract?) : ScalarOutput
    override fun equals(other: Any?): Boolean = other is MemberCandidate && sourceId == other.sourceId && sourceVersion == other.sourceVersion && displayName == other.displayName && entity == other.entity && role == other.role && shape == other.shape && identitySlots == other.identitySlots && outputs == other.outputs && permission == other.permission && filterTypes == other.filterTypes && filterValueContracts == other.filterValueContracts && unitDimensions == other.unitDimensions && timeRange == other.timeRange && clockSlots == other.clockSlots && parameters == other.parameters && fixedArguments == other.fixedArguments && groupingCandidates == other.groupingCandidates && memberParameters == other.memberParameters && relationParameters == other.relationParameters
    override fun hashCode(): Int = listOf(sourceId, sourceVersion, displayName, entity, role, shape, identitySlots, outputs, permission, filterTypes, filterValueContracts, unitDimensions, timeRange, clockSlots, parameters, fixedArguments, groupingCandidates, memberParameters, relationParameters).hashCode()
    companion object {
        fun scalar(output: Output): ScalarOutput = output as? ScalarOutput ?: throw IllegalArgumentException("candidate operation requires scalar member output")
        fun rootValueContract(output: Output): MetricValueContract? = (output as? ScalarOutput)?.valueContract
    }
}
