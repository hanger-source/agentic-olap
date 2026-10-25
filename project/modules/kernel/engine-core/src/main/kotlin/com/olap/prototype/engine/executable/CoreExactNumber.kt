package com.olap.prototype.engine.executable

import com.olap.prototype.metric.MetricValueContract
import java.math.BigDecimal
import java.math.BigInteger

/** 核心标量中间值；只有正式量化节点才把有理数变成有限十进制。 */
internal class CoreExactNumber private constructor(val numerator: BigInteger, val denominator: BigInteger) : Comparable<CoreExactNumber> {
    val isZero: Boolean get() = numerator.signum() == 0
    operator fun unaryMinus(): CoreExactNumber = of(numerator.negate(), denominator)
    fun abs(): CoreExactNumber = if (numerator.signum() < 0) -this else this
    operator fun plus(other: CoreExactNumber): CoreExactNumber {
        val common = denominator.gcd(other.denominator)
        val left = other.denominator.divide(common)
        val right = denominator.divide(common)
        return of(numerator.multiply(left).add(other.numerator.multiply(right)), denominator.multiply(left))
    }
    operator fun minus(other: CoreExactNumber): CoreExactNumber = this + -other
    operator fun times(other: CoreExactNumber): CoreExactNumber {
        val first = numerator.gcd(other.denominator)
        val second = other.numerator.gcd(denominator)
        return of(numerator.divide(first).multiply(other.numerator.divide(second)),
            denominator.divide(second).multiply(other.denominator.divide(first)))
    }
    operator fun div(other: CoreExactNumber): CoreExactNumber {
        if (other.isZero) throw CoreArithmeticFailure("CORE_FINALIZATION_DIVISION_BY_ZERO", "DIVIDE has a zero denominator")
        return this * of(other.denominator, other.numerator)
    }
    override fun compareTo(other: CoreExactNumber): Int = numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator))

    fun rounded(scale: Int, rounding: MetricValueContract.Rounding): CoreExactNumber {
        val scaledNumerator = if (scale >= 0) numerator.multiply(BigInteger.TEN.pow(scale)) else numerator
        val scaledDenominator = if (scale < 0) denominator.multiply(BigInteger.TEN.pow(Math.negateExact(scale))) else denominator
        val division = scaledNumerator.divideAndRemainder(scaledDenominator)
        val remainder = division[1].abs()
        val half = remainder.shiftLeft(1).compareTo(scaledDenominator)
        val increment = when (rounding) {
            MetricValueContract.Rounding.NONE -> {
                if (remainder.signum() != 0) throw CoreArithmeticFailure("CORE_FINALIZATION_ROUNDING_REQUIRED", "value cannot be represented at the declared scale without rounding")
                false
            }
            MetricValueContract.Rounding.TRUNCATE -> false
            MetricValueContract.Rounding.HALF_UP -> half >= 0
            MetricValueContract.Rounding.HALF_DOWN -> half > 0
            MetricValueContract.Rounding.HALF_EVEN -> half > 0 || half == 0 && division[0].testBit(0)
        }
        val coefficient = if (increment) division[0].add(BigInteger.valueOf(numerator.signum().toLong())) else division[0]
        return decimal(BigDecimal(coefficient, scale))
    }

    fun integer(): BigInteger {
        if (denominator != BigInteger.ONE) throw CoreArithmeticFailure("CORE_FINALIZATION_ROUNDING_REQUIRED", "integer capacity requires an integral value; quantize explicitly before conversion")
        return numerator
    }
    fun decimal(): BigDecimal = try { BigDecimal(numerator).divide(BigDecimal(denominator)) }
        catch (failure: ArithmeticException) {
            throw CoreArithmeticFailure("CORE_FINALIZATION_EXACT_RESULT_NOT_REPRESENTABLE", "an unquantized rational has no finite decimal representation", failure)
        }

    companion object {
        fun decimal(value: BigDecimal): CoreExactNumber = if (value.scale() >= 0)
            of(value.unscaledValue(), BigInteger.TEN.pow(value.scale()))
        else of(value.unscaledValue().multiply(BigInteger.TEN.pow(Math.negateExact(value.scale()))), BigInteger.ONE)
        private fun of(numerator: BigInteger, denominator: BigInteger): CoreExactNumber {
            if (denominator.signum() == 0) throw CoreArithmeticFailure("CORE_FINALIZATION_DIVISION_BY_ZERO", "DIVIDE has a zero denominator")
            val divisor = numerator.gcd(denominator)
            val sign = if (denominator.signum() < 0) divisor.negate() else divisor
            return CoreExactNumber(numerator.divide(sign), denominator.divide(sign))
        }
    }
}

internal class CoreArithmeticFailure(val code: String, message: String, cause: Throwable? = null) : ArithmeticException(message) {
    init { if (cause != null) initCause(cause) }
}
