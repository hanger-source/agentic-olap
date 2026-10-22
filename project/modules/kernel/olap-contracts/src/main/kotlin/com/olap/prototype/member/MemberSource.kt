package com.olap.prototype.member

import com.olap.prototype.calculation.*
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.query.ScopedInputContract

/** 精确成员来源实例，投影表达式和计算采用各只有一个事实owner。 */
sealed interface MemberSource {
    val id: String
    val version: Long
    val definition: MemberSourceDefinition
    class Projection(override val id: String, override val version: Long, override val definition: MemberSourceDefinition.Projection,
        outputExpressions: Map<String, SemanticExpression>) : MemberSource {
        val outputExpressions: Map<String, SemanticExpression> = java.util.Map.copyOf(outputExpressions)
        init {
            MetricDefinition.text(id, "member sourceId"); MetricDefinition.version(version)
            require(this.outputExpressions.keys == definition.result.outputs.keys) { "member expressions must exactly cover declared output slots" }
            definition.result.outputs.forEach { (slot, output) ->
                if (output is MemberSourceDefinition.PropertyOutput || output is MemberSourceDefinition.ContainerPropertyOutput)
                    require(MemberSourceDefinition.propertyReference(output) == this.outputExpressions[slot]) { "member property differs from its actual LogicUnit: $slot" }
            }
        }
        override fun equals(other: Any?): Boolean = other is Projection && id == other.id && version == other.version && definition == other.definition && outputExpressions == other.outputExpressions
        override fun hashCode(): Int = listOf(id, version, definition, outputExpressions).hashCode()
    }
    class Computed(override val id: String, override val version: Long, override val definition: MemberSourceDefinition.Computed,
        val bindings: CalculationBindings, fixedArguments: Map<String, Calculation.Value>) : MemberSource {
        val fixedArguments: Map<String, Calculation.Value> = java.util.Map.copyOf(fixedArguments)
        init {
            MetricDefinition.text(id, "member sourceId"); MetricDefinition.version(version)
            CalculationInstantiation.requireFixedArguments(definition.calculation.parameters, this.fixedArguments, bindings)
        }
        fun remainingParameters(): Map<String, Calculation.Parameter> = CalculationInstantiation.remainingParameters(definition.calculation, bindings, fixedArguments)
        fun remainingGroupingCandidates(): Map<String, List<ScopedInputContract.DimensionRef>> = CalculationInstantiation.remainingGroupingCandidates(definition.calculation, bindings, fixedArguments)
        fun remainingMemberParameters(): Map<String, Calculation.MemberSlot> = CalculationInstantiation.remainingMemberParameters(definition.calculation, bindings)
        fun remainingRelationParameters(): Map<String, Calculation.RelationSlot> = CalculationInstantiation.remainingRelationParameters(definition.calculation, bindings)
        override fun equals(other: Any?): Boolean = other is Computed && id == other.id && version == other.version && definition == other.definition && bindings == other.bindings && fixedArguments == other.fixedArguments
        override fun hashCode(): Int = listOf(id, version, definition, bindings, fixedArguments).hashCode()
    }
}
