package com.olap.prototype.member

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.olap.prototype.calculation.Calculation
import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.ScalarValueType

/** 对成员输出槽位的条件，值直接使用共同Calculation.Binding，不接受SQL。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes(JsonSubTypes.Type(value = MemberCondition.Unrestricted::class, name = "TRUE"), JsonSubTypes.Type(value = MemberCondition.Predicate::class, name = "LEAF"), JsonSubTypes.Type(value = MemberCondition.All::class, name = "AND"), JsonSubTypes.Type(value = MemberCondition.Any::class, name = "OR"), JsonSubTypes.Type(value = MemberCondition.Not::class, name = "NOT"))
sealed interface MemberCondition {
    class Unrestricted : MemberCondition { override fun equals(other: kotlin.Any?): Boolean = other is Unrestricted; override fun hashCode(): Int = 0 }
    enum class Operator {
        EQ, NE, GT, GTE, LT, LTE, IN, BETWEEN, IS_NULL, IS_NOT_NULL;
        val valueCount: Int get() = when (this) { IS_NULL, IS_NOT_NULL -> 0; BETWEEN -> 2; IN -> -1; else -> 1 }
        fun requireValueCount(count: Int) {
            require(if (valueCount < 0) count > 0 else count == valueCount) { "member filter value count differs from operator" }
        }
    }
    class Predicate(val slot: String, val operator: Operator, values: List<Calculation.Binding>) : MemberCondition {
        val values: List<Calculation.Binding> = java.util.List.copyOf(values)
        init { MetricDefinition.text(slot, "member filter slot"); operator.requireValueCount(this.values.size) }
        override fun equals(other: kotlin.Any?): Boolean = other is Predicate && slot == other.slot && operator == other.operator && values == other.values
        override fun hashCode(): Int = listOf(slot, operator, values).hashCode()
    }
    class All(operands: List<MemberCondition>) : MemberCondition {
        val operands: List<MemberCondition> = java.util.List.copyOf(operands)
        init { require(this.operands.size >= 2) { "AND requires at least two operands" } }
        override fun equals(other: kotlin.Any?): Boolean = other is All && operands == other.operands
        override fun hashCode(): Int = operands.hashCode()
    }
    class Any(operands: List<MemberCondition>) : MemberCondition {
        val operands: List<MemberCondition> = java.util.List.copyOf(operands)
        init { require(this.operands.size >= 2) { "OR requires at least two operands" } }
        override fun equals(other: kotlin.Any?): Boolean = other is Any && operands == other.operands
        override fun hashCode(): Int = operands.hashCode()
    }
    data class Not(val operand: MemberCondition) : MemberCondition

    companion object {
        fun operators(values: Map<String, List<Operator>>): Map<String, List<Operator>> = java.util.Collections.unmodifiableMap(
            linkedMapOf<String, List<Operator>>().apply {
                values.forEach { (slot, allowed) ->
                    MetricDefinition.text(slot, "member filter slot")
                    require(allowed.isNotEmpty() && allowed.distinct().size == allowed.size) { "member operators must be explicit and distinct" }
                    put(slot, java.util.List.copyOf(allowed))
                }
            },
        )

        fun selectMembers(condition: MemberCondition, selections: MutableMap<String, Calculation.Scalar?>) {
            when (condition) {
                is All -> condition.operands.forEach { selectMembers(it, selections) }
                is Predicate -> if (condition.operator == Operator.EQ) {
                    val scalar = condition.values.firstOrNull() as? Calculation.Scalar
                    if (scalar != null) {
                        if (condition.slot !in selections) selections[condition.slot] = scalar
                        else if (scalar != selections[condition.slot]) selections[condition.slot] = null
                    }
                }
                else -> kotlin.Unit
            }
        }

        private fun conjunctionSelections(condition: MemberCondition, outer: Map<String, Calculation.Scalar?>): Map<String, Calculation.Scalar?> =
            LinkedHashMap(outer).also { selectMembers(condition, it) }

        fun conditionUnit(contract: MetricValueContract, selections: Map<String, Calculation.Scalar?>): MetricValueContract.UnitIdentity = when (val unit = contract.unit) {
            is MetricValueContract.Fixed -> unit.identity
            is MetricValueContract.SlotDimension -> {
                val selected = requireNotNull(selections[unit.slot]) { "quantity member condition must select its exact unit member in the same conjunction" }
                unit.axis.members.firstOrNull { it.member == selected }?.unit
                    ?: throw IllegalArgumentException("quantity member condition selects an undeclared unit member")
            }
            else -> throw IllegalArgumentException("member filter quantity requires its local unit owner")
        }

        fun requireQuantityUnits(condition: MemberCondition, contracts: Map<String, MetricValueContract?>) {
            fun visit(node: MemberCondition, selections: Map<String, Calculation.Scalar?>, positive: Boolean) {
                when (node) {
                    is Predicate -> if (node.values.isNotEmpty()) contracts[node.slot]?.let { conditionUnit(it, selections) }
                    is All -> node.operands.forEach { visit(it, selections, positive) }
                    is Any -> node.operands.forEach { child ->
                        visit(child, if (positive) conjunctionSelections(child, selections) else selections, positive)
                    }
                    is Not -> visit(node.operand, selections, false)
                    is Unrestricted -> kotlin.Unit
                }
            }
            visit(condition, conjunctionSelections(condition, emptyMap()), true)
        }

        fun bind(condition: MemberCondition, operatorsBySlot: Map<String, List<Operator>>, types: (String) -> ScalarValueType?,
            slots: (String) -> String?, resolveValue: (Calculation.Binding) -> Calculation.Value, used: MutableSet<String>): MemberCondition =
            resolve(condition, slots, resolveValue, used).also { requireTypes(it, operatorsBySlot, types) }

        fun resolve(condition: MemberCondition, slots: (String) -> String?, resolveValue: (Calculation.Binding) -> Calculation.Value, used: MutableSet<String>): MemberCondition = when (condition) {
            is Unrestricted -> condition
            is Predicate -> {
                val slot = requireNotNull(slots(condition.slot)) { "member condition references an undeclared formal output" }
                used.add(slot)
                Predicate(slot, condition.operator, condition.values.map { value ->
                    resolveValue(value) as? Calculation.Scalar ?: throw IllegalArgumentException("member condition scalar type differs from its published output")
                })
            }
            is All -> All(condition.operands.map { resolve(it, slots, resolveValue, used) })
            is Any -> Any(condition.operands.map { resolve(it, slots, resolveValue, used) })
            is Not -> Not(resolve(condition.operand, slots, resolveValue, used))
        }

        fun requireTypes(condition: MemberCondition, operatorsBySlot: Map<String, List<Operator>>, types: (String) -> ScalarValueType?) {
            when (condition) {
                is Unrestricted -> kotlin.Unit
                is Predicate -> {
                    require(operatorsBySlot[condition.slot]?.contains(condition.operator) == true) { "member condition slot and operator are not approved for input filtering" }
                    val actual = requireNotNull(types(condition.slot))
                    require(condition.values.all { it is Calculation.Scalar && it.scalarType == actual }) { "member condition scalar type differs from its actual output" }
                }
                is All -> condition.operands.forEach { requireTypes(it, operatorsBySlot, types) }
                is Any -> condition.operands.forEach { requireTypes(it, operatorsBySlot, types) }
                is Not -> requireTypes(condition.operand, operatorsBySlot, types)
            }
        }

        /** 原条件与绑定条件一一对应；否定分支不能借正向选值推断单位。 */
        fun requireParameterUnits(original: MemberCondition, bound: MemberCondition, sourceContracts: (String) -> MetricValueContract?,
            slots: (String) -> String?, fields: (String) -> MetricValueContract?, parameters: (String) -> MetricValueContract?,
            outerSelections: Map<String, Calculation.Scalar?>) {
            fun visit(node: MemberCondition, actual: MemberCondition, selections: Map<String, Calculation.Scalar?>, positive: Boolean) {
                when (node) {
                    is Predicate -> {
                        val expected = fields(node.slot)
                        if (node.values.isEmpty()) return
                        val unit = if (expected == null) null else conditionUnit(requireNotNull(sourceContracts(requireNotNull(slots(node.slot)))), selections)
                        node.values.filterIsInstance<Calculation.Argument>().forEach { argument ->
                            val parameter = parameters(argument.name)
                            require((expected == null) == (parameter == null)) { "member comparison must preserve the parameter's declared quantity or categorical meaning" }
                            if (parameter != null) require((parameter.unit as? MetricValueContract.Fixed)?.identity == unit) { "member comparison parameter has a different measurement unit" }
                        }
                    }
                    is All -> node.operands.zip((actual as All).operands).forEach { (child, resolved) -> visit(child, resolved, selections, positive) }
                    is Any -> node.operands.zip((actual as Any).operands).forEach { (child, resolved) ->
                        visit(child, resolved, if (positive) conjunctionSelections(resolved, selections) else selections, positive)
                    }
                    is Not -> visit(node.operand, (actual as Not).operand, selections, false)
                    is Unrestricted -> kotlin.Unit
                }
            }
            visit(original, bound, conjunctionSelections(bound, outerSelections), true)
        }

        fun contract(): Map<String, kotlin.Any> = mapOf("values" to "DIRECT_CALCULATION_BINDINGS_NO_VALUE_WRAPPER",
            "valueCountByOperator" to Operator.entries.associate { operator -> operator.name to if (operator.valueCount < 0) mapOf("minimum" to 1) else mapOf("exact" to operator.valueCount) },
            "booleanOperands" to "AND_AND_OR_REQUIRE_AT_LEAST_TWO_OPERANDS_NOT_REQUIRES_ONE_OPERAND")
    }
}
