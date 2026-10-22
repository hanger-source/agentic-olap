package com.olap.prototype.query

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.time.TemporalWindow

/** 对正式发布筛选入口的类型化条件。 */
sealed interface DimensionCondition {
    class Unrestricted : DimensionCondition { override fun equals(other: kotlin.Any?): Boolean = other is Unrestricted; override fun hashCode(): Int = 0 }
    class Exists(val instanceId: String, val filterRefId: String, val predicate: MemberCondition,
        val range: TemporalWindow?, clocks: Map<String, TemporalClockBindings.Value>) : DimensionCondition {
        val clocks: Map<String, TemporalClockBindings.Value> = java.util.Map.copyOf(clocks)
        init { ScopedInputContract.requireId(instanceId); require(filterRefId.isNotBlank()) { "relation filter reference is required" } }
        override fun equals(other: kotlin.Any?): Boolean = other is Exists && instanceId == other.instanceId && filterRefId == other.filterRefId && predicate == other.predicate && range == other.range && clocks == other.clocks
        override fun hashCode(): Int = listOf(instanceId, filterRefId, predicate, range, clocks).hashCode()
    }
    class Predicate(val filterRefId: String, val dimension: ScopedInputContract.DimensionRef, val operator: MemberCondition.Operator, values: List<Calculation.Scalar>) : DimensionCondition {
        val values: List<Calculation.Scalar> = java.util.List.copyOf(values)
        init { require(filterRefId.isNotBlank()) { "filterRefId is required" }; operator.requireValueCount(this.values.size) }
        override fun equals(other: kotlin.Any?): Boolean = other is Predicate && filterRefId == other.filterRefId && dimension == other.dimension && operator == other.operator && values == other.values
        override fun hashCode(): Int = listOf(filterRefId, dimension, operator, values).hashCode()
    }
    class All(operands: List<DimensionCondition>) : DimensionCondition {
        val operands: List<DimensionCondition> = operands(operands)
        override fun equals(other: kotlin.Any?): Boolean = other is All && operands == other.operands
        override fun hashCode(): Int = operands.hashCode()
    }
    class Any(operands: List<DimensionCondition>) : DimensionCondition {
        val operands: List<DimensionCondition> = operands(operands)
        override fun equals(other: kotlin.Any?): Boolean = other is Any && operands == other.operands
        override fun hashCode(): Int = operands.hashCode()
    }
    data class Not(val operand: DimensionCondition) : DimensionCondition {
        init { require(operand !is Unrestricted) { "TRUE is only the no-filter root" } }
    }
    companion object {
        fun and(left: DimensionCondition, right: DimensionCondition): DimensionCondition = when { left is Unrestricted -> right; right is Unrestricted -> left; else -> All(listOf(left, right)) }
        fun leaves(condition: DimensionCondition): List<Predicate> = buildList { walk(condition) { if (it is Predicate) add(it) } }
        fun existences(condition: DimensionCondition): List<Exists> = buildList { walk(condition) { if (it is Exists) add(it) } }
        private fun walk(condition: DimensionCondition, consume: (DimensionCondition) -> Unit) {
            when (condition) { is All -> condition.operands.forEach { walk(it, consume) }; is Any -> condition.operands.forEach { walk(it, consume) }; is Not -> walk(condition.operand, consume); else -> consume(condition) }
        }
        private fun operands(values: List<DimensionCondition>): List<DimensionCondition> {
            require(values.size >= 2 && values.none { it is Unrestricted }) { "compound filter requires at least two non-TRUE operands" }
            return java.util.List.copyOf(values)
        }
    }
}
