package com.olap.prototype.calculation

import com.olap.prototype.query.ScopedInputContract

/** 精确定义的一次绑定，固定口径与调用实参各只有一个位置。 */
class MetricInstance(val definition: MetricRef, val bindings: CalculationBindings, fixedArguments: Map<String, Calculation.Value>, val displayName: String) {
    val fixedArguments: Map<String, Calculation.Value> = java.util.Map.copyOf(fixedArguments)
    init { MetricDefinition.text(displayName, "metric instance displayName") }
    fun remainingParameters(definition: MetricDefinition): Map<String, Calculation.Parameter> = when (definition) {
        is MetricCalculation -> CalculationInstantiation.remainingParameters(definition.calculation, bindings, fixedArguments)
        is MetricDefinition.FactMeasure -> { CalculationInstantiation.requireFixedArguments(emptyMap(), fixedArguments, bindings); emptyMap() }
    }
    fun remainingGroupingCandidates(definition: MetricDefinition): Map<String, List<ScopedInputContract.DimensionRef>> = when (definition) {
        is MetricCalculation -> CalculationInstantiation.remainingGroupingCandidates(definition.calculation, bindings, fixedArguments)
        is MetricDefinition.FactMeasure -> { remainingParameters(definition); emptyMap() }
    }
    fun remainingMemberParameters(definition: MetricDefinition): Map<String, Calculation.MemberSlot> = when (definition) {
        is MetricCalculation -> CalculationInstantiation.remainingMemberParameters(definition.calculation, bindings)
        is MetricDefinition.FactMeasure -> emptyMap()
    }
    fun remainingRelationParameters(definition: MetricDefinition): Map<String, Calculation.RelationSlot> = when (definition) {
        is MetricCalculation -> CalculationInstantiation.remainingRelationParameters(definition.calculation, bindings)
        is MetricDefinition.FactMeasure -> emptyMap()
    }
    override fun equals(other: Any?): Boolean = other is MetricInstance && definition == other.definition && bindings == other.bindings && fixedArguments == other.fixedArguments && displayName == other.displayName
    override fun hashCode(): Int = listOf(definition, bindings, fixedArguments, displayName).hashCode()

    companion object {
        /** 只沿实际绑定测量展开，不占用本次执行调用预算；缓存保留子树高度。 */
        fun dependencyClosure(roots: Collection<MetricInstanceRef>, lookup: (MetricInstanceRef) -> MetricInstance): Map<String, MetricInstance> {
            val result = linkedMapOf<String, MetricInstance>()
            val active = linkedSetOf<MetricInstanceRef>()
            val heights = hashMapOf<MetricInstanceRef, Int>()
            fun collect(reference: MetricInstanceRef): Int {
                require(reference !in active) { "measurement instance dependency cycle: $active -> $reference" }
                heights[reference]?.let { height ->
                    require(active.size + height <= ScopedInputContract.MAX_DEPTH) { "measurement instance closure exceeds the dependency depth budget" }
                    return height
                }
                require(active.size < ScopedInputContract.MAX_DEPTH) { "measurement instance closure exceeds the dependency depth budget" }
                val instance = lookup(reference)
                active.add(reference)
                try {
                    val height = 1 + (instance.bindings.measurements.values.maxOfOrNull { collect(it) } ?: 0)
                    result[reference.id] = instance
                    heights[reference] = height
                    return height
                } finally { active.remove(reference) }
            }
            roots.forEach { collect(it) }
            return java.util.Collections.unmodifiableMap(result)
        }
    }
}
