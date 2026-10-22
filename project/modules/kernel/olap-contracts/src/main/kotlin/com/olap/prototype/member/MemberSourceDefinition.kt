package com.olap.prototype.member

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.olap.prototype.calculation.Calculation
import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.event.EventRef
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.query.SetOutputContract

/** 实际事件投影与完成计算来源，各持有自己的位置。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes(JsonSubTypes.Type(value = MemberSourceDefinition.Projection::class, name = "PROJECTION"), JsonSubTypes.Type(value = MemberSourceDefinition.Computed::class, name = "COMPUTED"))
sealed interface MemberSourceDefinition {
    val result: MemberResult
    data class Projection(val event: EventRef, override val result: MemberResult) : MemberSourceDefinition {
        init { require(result.outputs.values.all { it is DimensionOutput || it is PropertyOutput || it is ContainerPropertyOutput }) { "event projections require actual dimension bindings or LogicUnit positions" } }
    }
    data class Computed(val calculation: Calculation, override val result: MemberResult) : MemberSourceDefinition {
        init {
            require(result.clockSlots.isEmpty()) { "computed source clocks belong to explicit calculation parameters" }
            val dimensions = calculation.dimensionSlots.associateBy { it.name }
            result.outputs.values.forEach { output ->
                when (output) {
                    is ComputedDimensionOutput -> {
                        val declared = requireNotNull(dimensions[output.dimensionSlot]) { "computed dimension requires a declared semantic slot" }
                        require(declared.role == output.role) { "computed dimension role differs from its declared semantic slot" }
                    }
                    is ComputedPropertyOutput, is ComputedContainerPropertyOutput -> kotlin.Unit
                    else -> throw IllegalArgumentException("computed member outputs require completed calculation symbols")
                }
            }
            result.outputs.values.forEach { output ->
                val unit = (output as? ScalarOutput)?.valueContract?.unit as? MetricValueContract.SlotDimension
                if (unit != null) {
                    val dimension = result.outputs[unit.slot] as ComputedDimensionOutput
                    val declared = requireNotNull(dimensions[dimension.dimensionSlot])
                    require(declared.dimensionId == unit.axis.dimensionId && declared.dimensionVersion == unit.axis.dimensionVersion && declared.role == unit.axis.role) { "computed member unit axis differs from its semantic slot" }
                }
            }
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = DimensionOutput::class, name = "DIMENSION"), JsonSubTypes.Type(value = PropertyOutput::class, name = "PROPERTY"), JsonSubTypes.Type(value = ComputedDimensionOutput::class, name = "COMPUTED_DIMENSION"), JsonSubTypes.Type(value = ComputedPropertyOutput::class, name = "COMPUTED_PROPERTY"), JsonSubTypes.Type(value = ContainerPropertyOutput::class, name = "CONTAINER_PROPERTY"), JsonSubTypes.Type(value = ComputedContainerPropertyOutput::class, name = "COMPUTED_CONTAINER_PROPERTY"))
    sealed interface Output { val displayName: String; val role: String }
    sealed interface ScalarOutput : Output { val valueType: ScalarValueType; val valueContract: MetricValueContract? }

    data class ContainerPropertyOutput(override val displayName: String, override val role: String, val shape: SetOutputContract.Shape,
        val logicUnitId: String, val logicUnitVersion: Long, val accessPath: RelationAccessPath) : Output {
        init { meaning(displayName, role); SetOutputContract.requireSourceContainer(shape); MetricDefinition.text(logicUnitId, "logicUnitId"); MetricDefinition.version(logicUnitVersion) }
    }
    data class ComputedContainerPropertyOutput(override val displayName: String, override val role: String, val shape: SetOutputContract.Shape, val symbol: String) : Output {
        init { meaning(displayName, role); SetOutputContract.requireSourceContainer(shape); MetricDefinition.text(symbol, "completed output symbol") }
    }
    data class DimensionOutput(override val displayName: String, override val role: String, override val valueType: ScalarValueType,
        val dimensionId: String, val dimensionVersion: Long, val bindingId: String, val bindingVersion: Long,
        @param:JsonProperty(value = "valueContract", required = true) override val valueContract: MetricValueContract?) : ScalarOutput {
        init { meaning(displayName, role); MetricDefinition.text(dimensionId, "dimensionId"); MetricDefinition.version(dimensionVersion); MetricDefinition.text(bindingId, "bindingId"); MetricDefinition.version(bindingVersion) }
    }
    data class PropertyOutput(override val displayName: String, override val role: String, override val valueType: ScalarValueType,
        val logicUnitId: String, val logicUnitVersion: Long, val accessPath: RelationAccessPath,
        @param:JsonProperty(value = "valueContract", required = true) override val valueContract: MetricValueContract?) : ScalarOutput {
        init { meaning(displayName, role); MetricDefinition.text(logicUnitId, "logicUnitId"); MetricDefinition.version(logicUnitVersion) }
        fun requireValueType(actual: ScalarValueType) { require(valueType == actual) { "member property type differs from its exact LogicUnit: $logicUnitId@$logicUnitVersion" } }
    }
    data class ComputedDimensionOutput(override val displayName: String, override val role: String, override val valueType: ScalarValueType,
        val dimensionSlot: String, val symbol: String,
        @param:JsonProperty(value = "valueContract", required = true) override val valueContract: MetricValueContract?) : ScalarOutput {
        init { meaning(displayName, role); ScopedInputContract.requireId(dimensionSlot); MetricDefinition.text(symbol, "completed output symbol") }
    }
    data class ComputedPropertyOutput(override val displayName: String, override val role: String, override val valueType: ScalarValueType, val symbol: String,
        @param:JsonProperty(value = "valueContract", required = true) override val valueContract: MetricValueContract?) : ScalarOutput {
        init { meaning(displayName, role); MetricDefinition.text(symbol, "completed output symbol") }
    }

    companion object {
        private fun meaning(displayName: String, role: String) { MetricDefinition.text(displayName, "displayName"); MetricDefinition.text(role, "role") }
        fun scalar(output: Output): ScalarOutput = output as? ScalarOutput ?: throw IllegalArgumentException("operation requires a scalar member output")
        fun rootValueContract(output: Output): MetricValueContract? = (output as? ScalarOutput)?.valueContract
        fun containerShape(output: Output): SetOutputContract.Shape = when (output) {
            is ContainerPropertyOutput -> output.shape
            is ComputedContainerPropertyOutput -> output.shape
            else -> throw IllegalArgumentException("member output is scalar")
        }
        fun completedSymbol(output: Output): String = when (output) {
            is ComputedDimensionOutput -> output.symbol
            is ComputedPropertyOutput -> output.symbol
            is ComputedContainerPropertyOutput -> output.symbol
            else -> throw IllegalArgumentException("member output is not a completed calculation symbol")
        }
        fun propertyReference(output: Output): SemanticExpression.Reference = when (output) {
            is PropertyOutput -> SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, output.logicUnitId, output.logicUnitVersion)
            is ContainerPropertyOutput -> SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, output.logicUnitId, output.logicUnitVersion)
            else -> throw IllegalArgumentException("member output has no projected property position")
        }
        fun propertyPath(output: Output): RelationAccessPath = when (output) {
            is PropertyOutput -> output.accessPath
            is ContainerPropertyOutput -> output.accessPath
            else -> throw IllegalArgumentException("member output has no projected property position")
        }
    }
}
