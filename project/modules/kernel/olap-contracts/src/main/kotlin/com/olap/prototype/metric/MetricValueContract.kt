package com.olap.prototype.metric

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.calculation.CalculationBindings
import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.ScopedInputContract
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Collections
import java.util.TreeMap

/** 作者拥有的可执行量值声明：单位、算术保证、评价范围与输出容量各自明确。 */
class MetricValueContract(val unit: Unit, val arithmetic: Arithmetic, val result: Result, unitEquations: Map<String, UnitEquation>, val overflow: Overflow) {
    val unitEquations: Map<String, UnitEquation> = Collections.unmodifiableMap(TreeMap(unitEquations))
    init {
        this.unitEquations.keys.forEach { ScopedInputContract.requireId(it) }
        require(arithmetic !is Exact || result !is BinaryFloat) { "exact decimal arithmetic cannot declare a floating result" }
    }
    enum class Overflow { FAIL }
    enum class Rounding { NONE, HALF_UP, HALF_DOWN, HALF_EVEN, TRUNCATE }

    data class UnitIdentity(val authority: String, val id: String, val version: Long) {
        init { MetricDefinition.text(authority, "unit authority"); MetricDefinition.text(id, "unit identity"); MetricDefinition.version(version) }
    }
    data class UnitMember(val member: Calculation.Scalar, val unit: UnitIdentity)
    class UnitAxis(val dimensionId: String, val dimensionVersion: Long, val role: String, val memberType: ScalarValueType, members: List<UnitMember>) {
        val members: List<UnitMember> = java.util.List.copyOf(members.map { value ->
            if (value.member.scalarType == ScalarValueType.NUMBER) UnitMember(Calculation.Scalar(ScalarValueType.NUMBER, BigDecimal(value.member.value).stripTrailingZeros().toPlainString()), value.unit)
            else value
        }.sortedBy { it.member.value })
        init {
            MetricDefinition.text(dimensionId, "unit semantic dimension")
            MetricDefinition.version(dimensionVersion)
            MetricDefinition.text(role, "unit dimension role")
            require(memberType in setOf(ScalarValueType.TEXT, ScalarValueType.ENUM, ScalarValueType.NUMBER)) { "unit identity dimensions require categorical text or exact numeric codes" }
            val identities = hashSetOf<Any>()
            this.members.forEach { value ->
                require(value.member.scalarType == memberType) { "unit member has a different semantic scalar type" }
                val key = value.member.jdbcValue().let { if (it is BigDecimal) it.stripTrailingZeros() else it }
                require(identities.add(key)) { "unit member is declared more than once" }
            }
        }
        override fun equals(other: Any?): Boolean = other is UnitAxis && dimensionId == other.dimensionId && dimensionVersion == other.dimensionVersion && role == other.role && memberType == other.memberType && members == other.members
        override fun hashCode(): Int = listOf(dimensionId, dimensionVersion, role, memberType, members).hashCode()
    }

    sealed interface Unit
    data class Fixed(val identity: UnitIdentity) : Unit
    data class EventDimension(val bindingId: String, val bindingVersion: Long, val axis: UnitAxis) : Unit {
        init { MetricDefinition.text(bindingId, "unit event binding"); MetricDefinition.version(bindingVersion) }
    }
    data class SlotDimension(val slot: String, val axis: UnitAxis) : Unit {
        init { ScopedInputContract.requireId(slot) }
    }
    data class AnalysisDimension(val dimension: ScopedInputContract.DimensionRef, val axis: UnitAxis) : Unit

    fun adopted(bindings: CalculationBindings): MetricValueContract {
        val slot = unit as? SlotDimension ?: return this
        val binding = requireNotNull(bindings.dimensions[slot.slot]) { "unit dimension slot is not adopted" }
        require(binding.role == slot.axis.role) { "adopted unit dimension has a different semantic role" }
        return MetricValueContract(AnalysisDimension(binding.reference, slot.axis), arithmetic, result, unitEquations, overflow)
    }

    fun requireScalar(scalar: Calculation.Scalar) {
        require(scalar.scalarType == ScalarValueType.NUMBER) { "quantity parameter must use an exact numeric scalar" }
        val number = BigDecimal(scalar.value)
        when (val capacity = result) {
            is Integer -> {
                val value = number.toBigIntegerExact()
                val limit = BigInteger.ONE.shiftLeft(capacity.bits - 1)
                require(value >= limit.negate() && value < limit) { "numeric scalar exceeds declared integer capacity" }
            }
            is Decimal -> require(maxOf(0, number.stripTrailingZeros().scale()) <= capacity.scale && number.abs() < BigDecimal.ONE.scaleByPowerOfTen(capacity.precision - capacity.scale)) { "numeric scalar exceeds declared decimal capacity" }
            is BinaryFloat -> require(number.toDouble().isFinite() && (capacity.bits != 32 || number.toFloat().isFinite())) { "numeric scalar exceeds declared floating capacity" }
        }
        if (arithmetic is Approximate)
            require(number >= decimal(arithmetic.minimum) && number <= decimal(arithmetic.maximum)) { "numeric scalar lies outside its declared approximation domain" }
    }

    sealed interface Arithmetic
    class Exact : Arithmetic { override fun equals(other: Any?): Boolean = other is Exact; override fun hashCode(): Int = 0 }
    /** 沿用原生浮点，不声明业务域或误差界限；仍核容量、有限值与溢出。 */
    class NativeFloating : Arithmetic { override fun equals(other: Any?): Boolean = other is NativeFloating; override fun hashCode(): Int = 0 }
    data class Approximate(val minimum: String, val maximum: String, val absoluteTolerance: String, val relativeTolerance: String) : Arithmetic {
        init { require(decimal(minimum) <= decimal(maximum) && decimal(absoluteTolerance).signum() >= 0 && decimal(relativeTolerance).signum() >= 0) { "approximation requires an ordered evaluation domain and nonnegative tolerances" } }
    }

    sealed interface Result
    data class Decimal(val precision: Int, val scale: Int, val rounding: Rounding) : Result {
        init { require(precision > 0 && scale in 0..precision) { "decimal result needs explicit precision, scale and output rounding" } }
    }
    data class Integer(val bits: Int) : Result {
        init { require(bits in setOf(8, 16, 32, 64, 128)) { "unsupported integer result capacity" } }
    }
    data class BinaryFloat(val bits: Int) : Result {
        init { require(bits == 32 || bits == 64) { "unsupported binary floating result capacity" } }
    }

    enum class BinaryOperator { MULTIPLY, DIVIDE }
    enum class Operand { LEFT, RIGHT }
    sealed interface UnitEquation

    data class PreserveUnit(val operator: BinaryOperator, val operand: Operand) : UnitEquation {
        init { require(operator != BinaryOperator.DIVIDE || operand == Operand.LEFT) { "division preserves its numerator unit only for a dimensionless denominator" } }
    }
    data class FixedBinary(val operator: BinaryOperator, val left: UnitIdentity, val right: UnitIdentity, val result: UnitIdentity) : UnitEquation {
        init {
            require(operator != BinaryOperator.DIVIDE || left != right || result == ONE) { "same-unit division produces a dimensionless ratio; declare percentage scaling separately" }
            require(operator != BinaryOperator.MULTIPLY || right != ONE || result == left) { "dimensionless multiplication preserves the left unit; declare a unit scale conversion separately" }
            require(operator != BinaryOperator.MULTIPLY || left != ONE || result == right) { "dimensionless multiplication preserves the right unit" }
        }
    }
    class SameUnitRatio : UnitEquation { override fun equals(other: Any?): Boolean = other is SameUnitRatio; override fun hashCode(): Int = 0 }
    data class Scale(val operator: BinaryOperator, val from: UnitIdentity, val to: UnitIdentity, val factor: String) : UnitEquation {
        init { require(decimal(factor).signum() != 0) { "unit scale factor cannot be zero" } }
    }
    data class PreserveUnitScale(val operator: BinaryOperator, val factor: String) : UnitEquation {
        init { require(operator != BinaryOperator.DIVIDE || decimal(factor).signum() != 0) { "division scale factor cannot be zero" } }
    }

    override fun equals(other: Any?): Boolean = other is MetricValueContract && unit == other.unit && arithmetic == other.arithmetic && result == other.result && unitEquations == other.unitEquations && overflow == other.overflow
    override fun hashCode(): Int = listOf(unit, arithmetic, result, unitEquations, overflow).hashCode()

    companion object {
        val ONE = UnitIdentity("olap", "dimensionless", 1)
        val PERCENT = UnitIdentity("olap", "percent", 1)
        private fun decimal(value: String): BigDecimal = BigDecimal(MetricDefinition.text(value, "exact decimal text"))

        fun builtinEquation(equation: UnitEquation): Boolean = when (equation) {
            is SameUnitRatio, is PreserveUnitScale, is PreserveUnit -> true
            is FixedBinary -> when (equation.operator) {
                BinaryOperator.MULTIPLY -> equation.left == ONE && equation.result == equation.right || equation.right == ONE && equation.result == equation.left
                BinaryOperator.DIVIDE -> equation.right == ONE && equation.result == equation.left || equation.left == equation.right && equation.result == ONE
            }
            is Scale -> {
                val factor = decimal(equation.factor)
                when {
                    equation.from == ONE && equation.to == PERCENT -> equation.operator == BinaryOperator.MULTIPLY && factor.compareTo(BigDecimal("100")) == 0 || equation.operator == BinaryOperator.DIVIDE && factor.compareTo(BigDecimal("0.01")) == 0
                    equation.from == PERCENT && equation.to == ONE -> equation.operator == BinaryOperator.DIVIDE && factor.compareTo(BigDecimal("100")) == 0 || equation.operator == BinaryOperator.MULTIPLY && factor.compareTo(BigDecimal("0.01")) == 0
                    else -> false
                }
            }
        }

        fun compatibleAxes(left: UnitAxis, right: UnitAxis): Boolean = left.dimensionId == right.dimensionId && left.dimensionVersion == right.dimensionVersion &&
            left.role == right.role && left.memberType == right.memberType && left.members.none { first -> right.members.any { second -> first.member.sameValue(second.member) && first.unit != second.unit } }

        fun requireMemberValue(expected: MetricValueContract?, actual: MetricValueContract?, owner: Calculation.MemberSlot, binding: CalculationBindings.SourceCall) {
            if (expected == null) return
            requireNotNull(actual) { "member quantity requirement is bound to a categorical source" }
            val first = expected.unit
            val second = actual.unit
            when {
                first is Fixed && second is Fixed -> require(first.identity == second.identity) { "adopted member has a different fixed measurement unit" }
                first is SlotDimension && second is SlotDimension -> require(compatibleAxes(first.axis, second.axis) && owner.outputs.any { (slot, output) ->
                    output is Calculation.MemberDimension && output.dimensionSlot == first.slot && binding.outputSlots[slot] == second.slot
                }) { "adopted member has no exact local unit-slot correspondence" }
                else -> throw IllegalArgumentException("adopted member changes the declared unit owner")
            }
            requireNumericValue(expected, actual)
        }

        fun requireMeasurementValue(expected: MetricValueContract, actual: MetricValueContract) {
            val first = expected.unit
            val second = actual.unit
            if (first is Fixed && second is Fixed) require(first.identity == second.identity) { "measurement has a different fixed unit" }
            else {
                val firstAxis = unitAxis(first)
                val secondAxis = unitAxis(second)
                require(firstAxis != null && secondAxis != null && compatibleAxes(firstAxis, secondAxis)) { "measurement has a different semantic unit axis" }
            }
            requireNumericValue(expected, actual)
        }
        private fun unitAxis(unit: Unit): UnitAxis? = when (unit) { is EventDimension -> unit.axis; is SlotDimension -> unit.axis; is AnalysisDimension -> unit.axis; is Fixed -> null }

        /** 输出CAST不能补出输入未声明的算术保证。 */
        fun requireArithmeticInput(expected: Arithmetic, actual: Arithmetic) {
            require(expected !is Exact || actual is Exact) { "native approximate input cannot become exact arithmetic through an output cast" }
            require(expected !is Approximate || actual !is NativeFloating) { "native floating input without error bounds cannot acquire declared approximation tolerances" }
        }

        internal fun requireNumericValue(expected: MetricValueContract, actual: MetricValueContract) {
            requireArithmeticInput(expected.arithmetic, actual.arithmetic)
            val first = expected.arithmetic
            val second = actual.arithmetic
            if (first is Approximate && second is Approximate)
                require(decimal(second.minimum) >= decimal(first.minimum) && decimal(second.maximum) <= decimal(first.maximum) &&
                    decimal(second.absoluteTolerance) <= decimal(first.absoluteTolerance) && decimal(second.relativeTolerance) <= decimal(first.relativeTolerance)) {
                    "quantity approximation exceeds its required domain or evaluation tolerances"
                }
            require(resultFits(actual.result, expected.result)) { "numeric result capacity exceeds its required quantity contract" }
        }

        fun resultFits(actual: Result, expected: Result): Boolean = when {
            actual is Integer && expected is Integer -> actual.bits <= expected.bits
            actual is BinaryFloat && expected is BinaryFloat -> actual.bits <= expected.bits
            actual is Decimal && expected is Decimal -> actual.scale <= expected.scale && actual.precision - actual.scale <= expected.precision - expected.scale
            actual is Integer && expected is Decimal -> when (actual.bits) { 8 -> 3; 16 -> 5; 32 -> 10; 64 -> 19; 128 -> 39; else -> error("invalid integer capacity") } <= expected.precision - expected.scale
            else -> false
        }
    }
}
