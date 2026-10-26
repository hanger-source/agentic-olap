package com.olap.prototype.expression

import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.connector.FiniteDecimalTypes
import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.ResultAggregation
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rex.*
import org.apache.calcite.sql.*
import org.apache.calcite.sql.`fun`.SqlCase
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.sql.type.*
import org.apache.calcite.tools.RelBuilder
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** 优化前确定原生运算、容量、有限值边界与显式舍入。 */
object SemanticNumericRex {
    fun isNumericCheck(value: RexNode): Boolean = value is RexCall && (value.operator is NumericCheck || value.operator is ScaleCheck)
    fun requireExactMemberCode(input: RexNode) {
        require(SqlTypeUtil.isExactNumeric(input.type)) { "numeric unit member codes require an exact native value" }
        input.accept(object : RexVisitorImpl<Void?>(true) {
            override fun visitInputRef(ref: RexInputRef): Void? { requireExact(ref.type); return null }
            override fun visitCall(call: RexCall): Void? {
                requireNativeArithmetic(call.operator, MetricValueContract.Exact())
                requireExact(call.type)
                return super.visitCall(call)
            }
        })
    }

    fun output(builder: RelBuilder, input: RexNode, declaration: MetricValueContract?): RexNode {
        if (declaration == null) return input
        require(SqlTypeUtil.isNumeric(input.type)) { "quantity output requires a numeric native value" }
        var value = arithmetic(builder, input, declaration, false)
        when (val result = declaration.result) {
            MetricValueContract.FiniteDecimal -> {
                require(SqlTypeUtil.isExactNumeric(value.type)) { "finite decimal output needs an exact native decimal representation; floating conversion must be explicit" }
                return when (val guarantee = declaration.arithmetic) {
                    is MetricValueContract.Approximate -> domain(builder, value, BigDecimal(guarantee.minimum), BigDecimal(guarantee.maximum))
                    else -> value
                }
            }
            is MetricValueContract.Decimal -> if (result.rounding == MetricValueContract.Rounding.NONE) {
                require(!SqlTypeUtil.isApproximateNumeric(value.type) && !(value.type.sqlTypeName == SqlTypeName.DECIMAL && value.type.scale > result.scale)) {
                    "quantity output scale would discard digits without an explicit rounding policy"
                }
                value = requireScale(builder, value, result.scale)
            } else value = round(builder, value, result.scale, result.rounding)
            is MetricValueContract.Integer -> {
                require(!SqlTypeUtil.isApproximateNumeric(value.type) && !(value.type.sqlTypeName == SqlTypeName.DECIMAL && !FiniteDecimalTypes.isFinite(value.type) && value.type.scale != 0)) {
                    "integer output requires an exact integral input; declare rounding and a decimal cast before conversion"
                }
                value = requireScale(builder, value, 0)
            }
            is MetricValueContract.BinaryFloat -> Unit
        }
        if (FiniteDecimalTypes.isFinite(value.type) && !system(builder).semantic) {
            value = when (val capacity = declaration.result) {
                is MetricValueContract.Decimal -> {
                    val limit = BigDecimal.ONE.scaleByPowerOfTen(capacity.precision - capacity.scale).subtract(BigDecimal.ONE.scaleByPowerOfTen(-capacity.scale))
                    guard(builder, value, limit.negate(), limit)
                }
                is MetricValueContract.Integer -> {
                    val limit = BigDecimal(BigInteger.ONE.shiftLeft(capacity.bits - 1))
                    guard(builder, value, limit.negate(), limit.subtract(BigDecimal.ONE))
                }
                else -> value
            }
        }
        val result = builder.rexBuilder.makeCast(resultType(builder, declaration, value.type.isNullable), value, true, false)
        if (system(builder).semantic) return result
        when (val guarantee = declaration.arithmetic) {
            is MetricValueContract.Approximate -> return domain(builder, result, BigDecimal(guarantee.minimum), BigDecimal(guarantee.maximum))
            is MetricValueContract.NativeFloating -> if (SqlTypeUtil.isApproximateNumeric(result.type)) {
                val limit = floatingLimit(result.type)
                return domain(builder, result, limit.negate(), limit)
            }
            is MetricValueContract.Exact -> Unit
        }
        return result
    }

    fun resultType(builder: RelBuilder, declaration: MetricValueContract, nullable: Boolean): RelDataType {
        val factory = builder.typeFactory
        val target = when (val result = declaration.result) {
            MetricValueContract.FiniteDecimal -> FiniteDecimalTypes.create(nullable)
            is MetricValueContract.Decimal -> {
                require(result.precision <= system(builder).maximumDeclaredDecimalPrecision) { "quantity decimal output exceeds its native type capacity" }
                factory.createSqlType(SqlTypeName.DECIMAL, result.precision, result.scale)
            }
            is MetricValueContract.Integer -> when (result.bits) {
                8 -> factory.createSqlType(SqlTypeName.TINYINT)
                16 -> factory.createSqlType(SqlTypeName.SMALLINT)
                32 -> factory.createSqlType(SqlTypeName.INTEGER)
                64 -> factory.createSqlType(SqlTypeName.BIGINT)
                128 -> {
                    require(system(builder).supports(CompilationSupport.Capability.WIDE_INTEGER)) { "128-bit integer output requires native HUGEINT" }
                    factory.createSqlType(SqlTypeName.DECIMAL, 39, 0)
                }
                else -> throw IllegalArgumentException("unsupported integer output capacity")
            }
            is MetricValueContract.BinaryFloat -> factory.createSqlType(if (result.bits == 32) SqlTypeName.REAL else SqlTypeName.DOUBLE)
        }
        return factory.createTypeWithNullability(target, nullable)
    }

    fun arithmetic(builder: RelBuilder, input: RexNode, declaration: MetricValueContract): RexNode = arithmetic(builder, input, declaration, true)

    /** COUNT只计数人口，原输入不承诺输出数量的算术域。 */
    fun factInput(builder: RelBuilder, input: RexNode, aggregation: MetricAggregation, declaration: MetricValueContract): RexNode =
        if (aggregation == MetricAggregation.COUNT) input else arithmetic(builder, input, declaration)

    private fun arithmetic(builder: RelBuilder, input: RexNode, declaration: MetricValueContract, guardRoot: Boolean): RexNode {
        input.accept(object : RexVisitorImpl<Void?>(true) {
            override fun visitCall(call: RexCall): Void? {
                requireNativeArithmetic(call.operator, declaration.arithmetic)
                if (call.operator is NativeScalarOperator) return null
                return super.visitCall(call)
            }
        })
        if (declaration.arithmetic is MetricValueContract.Exact) {
            input.accept(object : RexVisitorImpl<Void?>(true) {
                override fun visitInputRef(ref: RexInputRef): Void? { requireExact(ref.type); return null }
                override fun visitCall(call: RexCall): Void? { requireExact(call.type); return if (call.operator is NativeScalarOperator) null else super.visitCall(call) }
                override fun visitDynamicParam(parameter: RexDynamicParam): Void? { requireExact(parameter.type); return null }
            })
            return input
        }
        return finiteInputs(builder, input, guardRoot)
    }

    /** AVG 输入不借最终输出声明推导其保证；保证在实际聚合形成后单独核验。 */
    fun averageInput(builder: RelBuilder, input: RexNode): RexNode {
        require(SqlTypeUtil.isNumeric(input.type)) { "AVG requires a numeric native input" }
        return finiteInputs(builder, input, true)
    }

    private fun finiteInputs(builder: RelBuilder, input: RexNode, guardRoot: Boolean): RexNode {
        if (system(builder).semantic) return input
        // 内部浮点结果先证明有限，不把最终结果域误施加给中间运算。
        return input.accept(object : RexShuttle() {
            override fun visitInputRef(ref: RexInputRef): RexNode = finite(ref, ref)
            override fun visitCall(call: RexCall): RexNode = if (call.operator is NumericCheck) call
                else finite(if (call.operator is NativeScalarOperator) call else super.visitCall(call), call)
            override fun visitOver(over: RexOver): RexNode = finite(super.visitOver(over), over)
            private fun finite(value: RexNode, original: RexNode): RexNode {
                if (!SqlTypeUtil.isApproximateNumeric(value.type) || !guardRoot && original === input) return value
                val limit = floatingLimit(value.type)
                return guard(builder, value, limit.negate(), limit)
            }
        })
    }

    fun aggregate(builder: RelBuilder, aggregation: ResultAggregation, input: RexNode): RexNode = when (aggregation) {
        is ResultAggregation.Sum -> arithmetic(builder, input, aggregation.valueContract)
        is ResultAggregation.Average -> averageInput(builder, input)
        is ResultAggregation.SampleDeviation -> sampleDeviation(builder, input, aggregation.valueContract)
        is ResultAggregation.Minimum, is ResultAggregation.Maximum, is ResultAggregation.Count, is ResultAggregation.Collect -> input
    }

    fun aggregateOutput(builder: RelBuilder, input: RexNode, aggregation: ResultAggregation): RexNode =
        if (aggregation is ResultAggregation.Numeric) output(builder, input, aggregation.valueContract) else input

    fun factAggregate(builder: RelBuilder, aggregation: MetricAggregation, input: RexNode, declaration: MetricValueContract): RexNode {
        return when (aggregation) {
            MetricAggregation.SUM -> arithmetic(builder, input, declaration)
            MetricAggregation.AVG -> averageInput(builder, input)
            MetricAggregation.STDDEV_SAMP -> sampleDeviation(builder, input, declaration)
            MetricAggregation.MIN, MetricAggregation.MAX -> if (SqlTypeUtil.isNumeric(input.type)) arithmetic(builder, input, declaration) else input
            MetricAggregation.COUNT -> input
        }
    }

    private fun sampleDeviation(builder: RelBuilder, input: RexNode, declaration: MetricValueContract): RexNode {
        require(declaration.arithmetic !is MetricValueContract.Exact) { "STDDEV_SAMP requires explicit native floating or approximate arithmetic" }
        require(SqlTypeUtil.isNumeric(input.type)) { "STDDEV_SAMP requires a numeric native input" }
        require(system(builder).semantic || system(builder).supports(CompilationSupport.Capability.SAMPLE_STDDEV)) { "STDDEV_SAMP has no verified native binding for the execution dialect" }
        return arithmetic(builder, input, declaration)
    }

    /** 在原始来源表达式仍完整时保存实际数值保证；不从后续投影的技术类型恢复声明。 */
    fun inputGuarantees(input: RexNode): List<MetricValueContract.Arithmetic> {
        require(SqlTypeUtil.isNumeric(input.type)) { "arithmetic input guarantees require an actual numeric source value" }
        fun collect(value: RexNode): List<MetricValueContract.Arithmetic> {
            if (value is RexCall) {
                (value.operator as? NativeScalarOperator)?.declaration?.arithmetic?.let { return listOf(it) }
                if (value.operator is NumericCheck) return collect(value.operands.first())
                if (value.kind == SqlKind.CAST) return if (SqlTypeUtil.isApproximateNumeric(value.type))
                    listOf(MetricValueContract.NativeFloating()) else collect(value.operands.first())
                if (value.kind == SqlKind.CASE) return value.operands.filterIndexed { index, _ -> index % 2 == 1 || index == value.operands.lastIndex }
                    .flatMap(::collect)
                if (value.kind == SqlKind.NULLIF) return collect(value.operands.first())
                if (SqlTypeUtil.isApproximateNumeric(value.type)) return listOf(MetricValueContract.NativeFloating())
                val operands = value.operands.filter { SqlTypeUtil.isNumeric(it.type) }.flatMap(::collect)
                if (operands.isNotEmpty()) return operands
            }
            return if (SqlTypeUtil.isNumeric(value.type)) listOf(if (SqlTypeUtil.isApproximateNumeric(value.type))
                MetricValueContract.NativeFloating() else MetricValueContract.Exact()) else emptyList()
        }
        return java.util.List.copyOf(collect(input).distinct().ifEmpty { listOf(MetricValueContract.Exact()) })
    }

    private fun floatingLimit(type: RelDataType): BigDecimal = if (type.sqlTypeName == SqlTypeName.REAL) BigDecimal(java.lang.Float.toString(Float.MAX_VALUE)) else BigDecimal.valueOf(Double.MAX_VALUE)

    private fun domain(builder: RelBuilder, value: RexNode, minimum: BigDecimal, maximum: BigDecimal): RexNode {
        if (FiniteDecimalTypes.isFinite(value.type)) return guard(builder, value, minimum, maximum)
        val type = value.type
        var lower = minimum
        var upper = maximum
        if (SqlTypeUtil.isApproximateNumeric(type)) {
            val limit = floatingLimit(type)
            lower = lower.max(limit.negate()); upper = upper.min(limit)
            if (lower <= upper) { lower = floatingBoundary(type, lower, true); upper = floatingBoundary(type, upper, false) }
        } else {
            val scale = if (type.sqlTypeName == SqlTypeName.DECIMAL) type.scale else 0
            if (type.sqlTypeName == SqlTypeName.DECIMAL && !(system(builder).supports(CompilationSupport.Capability.WIDE_INTEGER) && SemanticCalciteTypeSystem.wideInteger(type))) {
                val capacity = BigDecimal.ONE.scaleByPowerOfTen(type.precision - scale).subtract(BigDecimal.ONE.scaleByPowerOfTen(-scale))
                lower = lower.max(capacity.negate()); upper = upper.min(capacity)
            } else {
                val bits = when (type.sqlTypeName) {
                    SqlTypeName.TINYINT -> 8
                    SqlTypeName.SMALLINT -> 16
                    SqlTypeName.INTEGER -> 32
                    SqlTypeName.BIGINT -> 64
                    SqlTypeName.DECIMAL -> 128
                    else -> throw IllegalArgumentException("approximation result has no bound exact numeric capacity")
                }
                val capacity = BigDecimal(BigInteger.ONE.shiftLeft(bits - 1))
                lower = lower.max(capacity.negate()); upper = upper.min(capacity.subtract(BigDecimal.ONE))
            }
            lower = lower.setScale(scale, RoundingMode.CEILING); upper = upper.setScale(scale, RoundingMode.FLOOR)
        }
        require(lower <= upper) { "approximation domain contains no value in its native result type" }
        return guard(builder, value, lower, upper)
    }

    private fun floatingBoundary(type: RelDataType, boundary: BigDecimal, lower: Boolean): BigDecimal {
        if (type.sqlTypeName == SqlTypeName.REAL) {
            var value = boundary.toFloat()
            val comparison = BigDecimal(java.lang.Float.toString(value)).compareTo(boundary)
            if (lower && comparison < 0) value = Math.nextUp(value)
            if (!lower && comparison > 0) value = Math.nextDown(value)
            return BigDecimal(java.lang.Float.toString(value))
        }
        var value = boundary.toDouble()
        val comparison = BigDecimal.valueOf(value).compareTo(boundary)
        if (lower && comparison < 0) value = Math.nextUp(value)
        if (!lower && comparison > 0) value = Math.nextDown(value)
        return BigDecimal.valueOf(value)
    }

    private fun guard(builder: RelBuilder, value: RexNode, minimum: BigDecimal, maximum: BigDecimal): RexNode {
        require(system(builder).supports(CompilationSupport.Capability.NUMERIC_FAILURE)) { "numeric FAIL has no verified native binding for the execution dialect" }
        val checked = (value as? RexCall)?.operator as? NumericCheck
        if (checked != null && minimum <= checked.minimum && maximum >= checked.maximum) return value
        return builder.rexBuilder.makeCall(value.type, NumericCheck(value.type, minimum, maximum, system(builder).nativeSupport),
            listOf(value, boundary(builder, minimum, value.type), boundary(builder, maximum, value.type)))
    }

    private fun boundary(builder: RelBuilder, value: BigDecimal, type: RelDataType): RexNode {
        if (type.sqlTypeName == SqlTypeName.REAL) return builder.rexBuilder.makeApproxLiteral(BigDecimal.valueOf(value.toFloat().toDouble()), builder.typeFactory.createSqlType(SqlTypeName.DOUBLE))
        val literal = numericConstant(builder, value, type)
        return if (SqlTypeUtil.isApproximateNumeric(type)) builder.rexBuilder.makeCast(type, literal, false) else literal
    }

    internal class NumericCheck(private val type: RelDataType, val minimum: BigDecimal, val maximum: BigDecimal,
        private val support: CompilationSupport) : SqlSpecialOperator("NUMERIC_CONTRACT_CHECK", SqlKind.OTHER_FUNCTION) {
        override fun unparse(writer: SqlWriter, call: SqlCall, leftPrec: Int, rightPrec: Int) {
            expand(call, writer.dialect).unparse(writer, leftPrec, rightPrec)
        }
        fun expand(call: SqlCall, dialect: SqlDialect): SqlNode {
            val pos = call.parserPosition
            val lower = Ops.CAST.createCall(pos, call.operand<SqlNode>(1), dialect.getCastSpec(type))
            val upper = Ops.CAST.createCall(pos, call.operand<SqlNode>(2), dialect.getCastSpec(type))
            val value = call.operand<SqlNode>(0)
            val valid = Ops.IS_NOT_FALSE.createCall(pos, Ops.BETWEEN.createCall(pos, value, lower, upper))
            val failure = Ops.CAST.createCall(pos, support.numericFailure(value, type, pos), dialect.getCastSpec(type))
            return SqlCase(pos, null, SqlNodeList(listOf(valid), pos), SqlNodeList(listOf(value), pos), failure)
        }
    }

    /** NONE检查保留原值；不先量化再把舍入结果包装成通过。 */
    fun requireScale(builder: RelBuilder, input: RexNode, scale: Int): RexNode {
        if (!FiniteDecimalTypes.isFinite(input.type) || system(builder).semantic) return input
        val support = system(builder).nativeSupport
        support.requireNativeCast(input.type)
        val truncated = round(builder, input, scale, MetricValueContract.Rounding.TRUNCATE)
        return builder.rexBuilder.makeCall(input.type, ScaleCheck(input.type, support), listOf(input, truncated))
    }

    internal class ScaleCheck(private val type: RelDataType, private val support: CompilationSupport) :
        SqlSpecialOperator("NUMERIC_EXACT_SCALE_CHECK", SqlKind.OTHER_FUNCTION) {
        override fun unparse(writer: SqlWriter, call: SqlCall, leftPrec: Int, rightPrec: Int) {
            expand(call, writer.dialect).unparse(writer, leftPrec, rightPrec)
        }
        fun expand(call: SqlCall, dialect: SqlDialect): SqlNode {
            val pos = call.parserPosition
            val value = call.operand<SqlNode>(0)
            val valid = Ops.OR.createCall(pos, Ops.IS_NULL.createCall(pos, value),
                Ops.IS_TRUE.createCall(pos, Ops.EQUALS.createCall(pos, value, call.operand<SqlNode>(1))))
            val failure = Ops.CAST.createCall(pos, support.numericFailure(value, type, pos), dialect.getCastSpec(type))
            return SqlCase(pos, null, SqlNodeList(listOf(valid), pos), SqlNodeList(listOf(value), pos), failure)
        }
    }

    private fun requireExact(type: RelDataType) { require(!SqlTypeUtil.isApproximateNumeric(type)) { "floating native arithmetic cannot satisfy an exact quantity contract" } }
    private fun requireNativeArithmetic(operator: SqlOperator, expected: MetricValueContract.Arithmetic) {
        (operator as? NativeScalarOperator)?.declaration?.arithmetic?.let { MetricValueContract.requireArithmeticInput(expected, it) }
    }
    private fun system(builder: RelBuilder): SemanticCalciteTypeSystem = builder.typeFactory.typeSystem as? SemanticCalciteTypeSystem ?: error("numeric operation needs a bound execution type system")

    fun round(builder: RelBuilder, input: RexNode, scale: Int, mode: MetricValueContract.Rounding): RexNode {
        if (mode == MetricValueContract.Rounding.NONE) return input
        require(system(builder).supports(CompilationSupport.Capability.NUMERIC_ROUNDING)) { "numeric rounding is not bound for the execution dialect" }
        if (!FiniteDecimalTypes.isFinite(input.type) && SqlTypeUtil.isExactNumeric(input.type) && (if (input.type.sqlTypeName != SqlTypeName.DECIMAL) scale >= 0 else input.type.scale <= scale)) return input
        val scaleValue = builder.literal(scale)
        if (mode == MetricValueContract.Rounding.HALF_UP) return nativeRound(builder, input, scaleValue, scale, true)
        val truncation = SqlFunction("TRUNC", SqlKind.OTHER_FUNCTION, ReturnTypes.ARG0, null, OperandTypes.NUMERIC_INTEGER, SqlFunctionCategory.NUMERIC)
        val truncated = builder.rexBuilder.makeCall(roundType(builder, input.type, scale, false), truncation, listOf(input, scaleValue))
        if (mode == MetricValueContract.Rounding.TRUNCATE) return truncated
        var step = BigDecimal.ONE.scaleByPowerOfTen(-scale)
        if (step.scale() < 0) step = step.setScale(0)
        val half = numericConstant(builder, step.divide(BigDecimal.valueOf(2)), input.type)
        val difference = builder.call(Ops.ABS, builder.call(Ops.MINUS, input, truncated))
        val tie = builder.equals(difference, half)
        val rounded = nativeRound(builder, input, scaleValue, scale, true)
        if (mode == MetricValueContract.Rounding.HALF_DOWN) return builder.call(Ops.CASE, tie, truncated, rounded)
        val doubleStep = numericConstant(builder, step.multiply(BigDecimal.valueOf(2)), input.type)
        val even = builder.equals(builder.call(Ops.MOD, truncated, doubleStep), builder.rexBuilder.makeExactLiteral(BigDecimal.ZERO))
        return builder.call(Ops.CASE, builder.and(tie, even), truncated, rounded)
    }

    private fun numericConstant(builder: RelBuilder, value: BigDecimal, argument: RelDataType): RexNode {
        val constant = if (value.scale() < 0) value.setScale(0) else value
        return if (SqlTypeUtil.isApproximateNumeric(argument)) builder.rexBuilder.makeApproxLiteral(constant, argument) else builder.rexBuilder.makeExactLiteral(constant)
    }
    private fun nativeRound(builder: RelBuilder, input: RexNode, scaleValue: RexNode, scale: Int, roundUp: Boolean): RexNode =
        builder.rexBuilder.makeCall(roundType(builder, input.type, scale, roundUp), Ops.ROUND, listOf(input, scaleValue))

    private fun roundType(builder: RelBuilder, input: RelDataType, scale: Int, roundUp: Boolean): RelDataType {
        if (FiniteDecimalTypes.isFinite(input)) {
            if (!system(builder).semantic) system(builder).nativeSupport.requireNativeCast(input)
            return input
        }
        if (input.sqlTypeName != SqlTypeName.DECIMAL) return input
        val targetScale = minOf(input.scale, maxOf(0, scale))
        val precision = if (system(builder).supports(CompilationSupport.Capability.ROUND_KEEPS_INPUT_PRECISION)) input.precision
            else minOf(system(builder).maximumDeclaredDecimalPrecision, input.precision - input.scale + targetScale + if (roundUp) 1 else 0)
        return builder.typeFactory.createTypeWithNullability(builder.typeFactory.createSqlType(SqlTypeName.DECIMAL, maxOf(1, precision), targetScale), input.isNullable)
    }
}
