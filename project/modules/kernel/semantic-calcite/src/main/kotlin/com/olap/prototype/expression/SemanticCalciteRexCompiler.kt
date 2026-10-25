package com.olap.prototype.expression

import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.metric.MetricValueContract
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.type.SqlTypeUtil
import org.apache.calcite.tools.RelBuilder
import java.math.BigDecimal
import java.math.BigInteger

/** 规范表达式到Rex；精确引用的授权与归属由调用边界决定。 */
class SemanticCalciteRexCompiler(private val builder: RelBuilder, private val parameters: MutableList<JdbcParameter>,
    private val nativeCalls: NativeCallCompiler) {
    fun interface NativeCallCompiler { fun compile(call: SemanticExpression.NativeCall, operands: (SemanticExpression) -> RexNode): RexNode }

    fun compile(expression: SemanticExpression, references: (SemanticExpression.Reference) -> RexNode,
        quantifiers: (SemanticExpression.Exists) -> RexNode = { throw IllegalArgumentException("this expression owner does not provide relationship quantifiers") }): RexNode {
        fun recurse(value: SemanticExpression): RexNode = compile(value, references, quantifiers)
        return when (expression) {
            is SemanticExpression.Reference -> references(expression)
            is SemanticExpression.Exists -> quantifiers(expression)
            is SemanticExpression.Literal -> {
                val type = when (val value = expression.value) {
                    is String -> builder.typeFactory.createSqlType(SqlTypeName.CHAR, value.length)
                    is BigDecimal -> builder.rexBuilder.makeExactLiteral(value).type
                    else -> builder.literal(value).type
                }
                parameter(builder, parameters, type, expression.value)
            }
            is SemanticExpression.NativeCall -> nativeCalls.compile(expression, ::recurse)
            is SemanticExpression.Call -> SemanticCalciteOperators.rexCall(builder, expression, ::recurse)
            is SemanticExpression.Operation -> operation(expression.operator, expression.operands.map(::recurse))
            is SemanticExpression.CaseWhen -> {
                val args = expression.branches.flatMap { listOf(recurse(it.`when`), recurse(it.then)) }.toMutableList()
                args.add(expression.otherwise?.let(::recurse) ?: builder.rexBuilder.makeNullLiteral(args[1].type))
                val values = args.filterIndexed { index, _ -> index % 2 == 1 || index == args.lastIndex }
                builder.rexBuilder.makeCall(SemanticNativeTypes.commonAlternativeType(builder.typeFactory, values.map { it.type }, "source CASE"), Ops.CASE, args)
            }
            is SemanticExpression.Round -> SemanticNumericRex.round(builder, recurse(expression.expression), expression.scale, expression.rounding)
            is SemanticExpression.Cast -> cast(recurse(expression.expression), expression.targetType, expression.precision, expression.scale, expression.rounding)
        }
    }

    fun cast(input: RexNode, targetType: SemanticExpression.ValueType, precision: Int?, scale: Int?, rounding: MetricValueContract.Rounding): RexNode {
        SemanticExpression.Cast.validateTarget(targetType, precision, scale)
        val type = when (targetType) {
            SemanticExpression.ValueType.STRING -> SqlTypeName.VARCHAR
            SemanticExpression.ValueType.INTEGER -> SqlTypeName.INTEGER
            SemanticExpression.ValueType.BIGINT -> SqlTypeName.BIGINT
            SemanticExpression.ValueType.DECIMAL -> SqlTypeName.DECIMAL
            SemanticExpression.ValueType.DOUBLE -> SqlTypeName.DOUBLE
            SemanticExpression.ValueType.BOOLEAN -> SqlTypeName.BOOLEAN
            SemanticExpression.ValueType.DATE -> SqlTypeName.DATE
            SemanticExpression.ValueType.TIMESTAMP -> SqlTypeName.TIMESTAMP
        }
        var value = input
        val numeric = type in setOf(SqlTypeName.DECIMAL, SqlTypeName.INTEGER, SqlTypeName.BIGINT)
        require(numeric || rounding == MetricValueContract.Rounding.NONE) { "non-quantizing cast must use NONE rounding" }
        if (numeric && SqlTypeUtil.isNumeric(input.type)) {
            val targetScale = if (type == SqlTypeName.DECIMAL) requireNotNull(scale) else 0
            if (rounding == MetricValueContract.Rounding.NONE) {
                require(!SqlTypeUtil.isApproximateNumeric(input.type) && !(input.type.sqlTypeName == SqlTypeName.DECIMAL && input.type.scale > targetScale)) {
                    "numeric cast would discard digits without an explicit rounding policy"
                }
            } else value = SemanticNumericRex.round(builder, input, targetScale, rounding)
        }
        val factory = builder.typeFactory
        val target = when {
            type == SqlTypeName.DECIMAL -> {
                val system = factory.typeSystem as? SemanticCalciteTypeSystem ?: error("decimal cast requires a bound semantic type system")
                check(requireNotNull(precision) <= system.maximumDeclaredDecimalPrecision && requireNotNull(scale) <= system.getMaxScale(type)) { "cast precision/scale exceeds the Calcite type system" }
                factory.createSqlType(type, precision, scale)
            }
            precision != null -> factory.createSqlType(type, precision)
            type == SqlTypeName.TIMESTAMP -> when {
                value.type.sqlTypeName == SqlTypeName.DATE -> factory.createSqlType(type, 0)
                value.type.sqlTypeName == SqlTypeName.TIMESTAMP && value.type.precision >= 0 -> factory.createSqlType(type, value.type.precision)
                else -> error("TIMESTAMP cast requires explicit precision for this input type")
            }
            else -> factory.createSqlType(type)
        }
        (factory.typeSystem as? SemanticCalciteTypeSystem)?.takeUnless { it.semantic }?.nativeSupport?.requireNativeCast(target)
        return builder.rexBuilder.makeCast(factory.createTypeWithNullability(target, value.type.isNullable), value)
    }

    private fun operation(operation: SemanticExpression.Operator, args: List<RexNode>): RexNode {
        when (operation) {
            SemanticExpression.Operator.IN -> {
                check(args.size >= 2) { "IN requires values" }
                return builder.or(args.drop(1).map { builder.equals(args[0], it) })
            }
            SemanticExpression.Operator.BETWEEN -> {
                requireArity(args, 3)
                return builder.and(builder.call(Ops.GREATER_THAN_OR_EQUAL, args[0], args[1]), builder.call(Ops.LESS_THAN_OR_EQUAL, args[0], args[2]))
            }
            SemanticExpression.Operator.AND, SemanticExpression.Operator.OR -> {
                check(args.size >= 2) { "boolean combination requires at least two operands" }
                return if (operation == SemanticExpression.Operator.AND) builder.and(args) else builder.or(args)
            }
            else -> Unit
        }
        requireArity(args, if (operation in setOf(SemanticExpression.Operator.IS_NULL, SemanticExpression.Operator.IS_NOT_NULL, SemanticExpression.Operator.NOT, SemanticExpression.Operator.NEGATE)) 1 else 2)
        return when (operation) {
            SemanticExpression.Operator.DIVIDE -> SemanticCalciteOperators.divide(builder, args[0], args[1])
            SemanticExpression.Operator.SAFE_DIVIDE -> SemanticCalciteOperators.divide(builder, args[0], builder.call(Ops.NULLIF, args[1], builder.literal(0)))
            else -> builder.call(SemanticCalciteOperators.operation(operation), args)
        }
    }

    companion object {
        private fun requireArity(args: List<RexNode>, count: Int) { check(args.size == count) { "semantic operator requires $count operands" } }

        fun parameter(builder: RelBuilder, parameters: MutableList<JdbcParameter>, type: RelDataType, value: Any?): RexNode {
            val system = builder.typeFactory.typeSystem as? SemanticCalciteTypeSystem ?: error("SQL parameter requires its bound numeric type system")
            var actual = value
            if (SqlTypeUtil.isExactNumeric(type)) {
                actual = when {
                    type.sqlTypeName in setOf(SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER, SqlTypeName.BIGINT) && value is BigDecimal -> value.toBigIntegerExact()
                    type.sqlTypeName == SqlTypeName.DECIMAL && (value is BigInteger || value is Long || value is Int || value is Short || value is Byte) -> BigDecimal(value.toString())
                    else -> value
                }
                require(actual !is Float && actual !is Double) { "exact SQL parameters cannot be recovered from floating input values" }
            }
            require(type.sqlTypeName != SqlTypeName.DECIMAL ||
                system.supports(CompilationSupport.Capability.WIDE_INTEGER) && SemanticCalciteTypeSystem.wideInteger(type) ||
                type.precision <= system.maximumDeclaredDecimalPrecision) { "SQL decimal parameter exceeds native declared capacity" }
            val index = parameters.size
            parameters.add(system.nativeSupport.parameter(type, actual))
            return builder.rexBuilder.makeDynamicParam(type, index)
        }
    }
}
