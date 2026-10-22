package com.olap.prototype.calculation

import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.ScopedInputContract

/** 共同计算体与精确度量、键和数量结果合同。 */
class MetricCalculation(val calculation: Calculation, val measureOutput: String, keyOutputs: List<String>, override val valueContract: MetricValueContract) : MetricDefinition {
    val keyOutputs: List<String> = java.util.List.copyOf(keyOutputs)
    init {
        require(valueContract.unit !is MetricValueContract.EventDimension && valueContract.unit !is MetricValueContract.AnalysisDimension) { "algorithm value units must use semantic dimension slots" }
        val unit = valueContract.unit
        if (unit is MetricValueContract.SlotDimension) {
            val slot = requireNotNull(calculation.dimensionSlots.firstOrNull { it.name == unit.slot }) { "unit dimension slot is not declared" }
            require(slot.dimensionId == unit.axis.dimensionId && slot.dimensionVersion == unit.axis.dimensionVersion && slot.role == unit.axis.role) { "unit axis differs from its declared semantic dimension slot" }
        }
        MetricDefinition.text(measureOutput, "measureOutput")
        require(measureOutput !in this.keyOutputs && this.keyOutputs.distinct().size == this.keyOutputs.size) { "metric measure and key outputs must be distinct" }
        this.keyOutputs.forEach { output ->
            MetricDefinition.text(output, "metric key output")
            Calculation.groupingFamily(output)?.let { name -> require(calculation.parameters[name]?.kind == Calculation.ParameterKind.GROUPING) { "argument kind differs from consumption: $name" } }
        }
    }
    fun levelArguments(arguments: Map<String, Calculation.Value>, selected: Collection<ScopedInputContract.DimensionRef>): Map<String, Calculation.Value> =
        java.util.Collections.unmodifiableMap(LinkedHashMap(arguments).apply {
            keyOutputs.mapNotNull { Calculation.groupingFamily(it) }.distinct().forEach { name ->
                val supplied = arguments[name] as Calculation.Grouping
                put(name, Calculation.Grouping(supplied.dimensions.filter { it in selected }))
            }
        })
    override fun equals(other: Any?): Boolean = other is MetricCalculation && calculation == other.calculation && measureOutput == other.measureOutput && keyOutputs == other.keyOutputs && valueContract == other.valueContract
    override fun hashCode(): Int = listOf(calculation, measureOutput, keyOutputs, valueContract).hashCode()
}
