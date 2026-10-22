package com.olap.prototype.expression

import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.ResultExpression
import com.olap.prototype.query.ScalarValueType
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.type.SqlTypeUtil
import org.apache.calcite.tools.RelBuilder
import java.math.BigDecimal

/** 完成关系的递归表达式发射，数量、容器和标量各持自己的规则。 */
internal class CompletedExpressionCompiler private constructor(private val b: RelBuilder, private val parameters: MutableList<JdbcParameter>) {
    companion object {
        fun compile(builder: RelBuilder, expression: ResultExpression, parameters: MutableList<JdbcParameter>): RexNode = CompletedExpressionCompiler(builder, parameters).emit(expression)
    }
    private fun emit(expression: ResultExpression): RexNode = when (expression) {
        is ResultExpression.ResultCall -> {
            val temporal = expression.function.temporal
            val unit = if (temporal) (expression.arguments.first() as ResultExpression.ResultTypedLiteral).value as String else null
            SemanticCalciteOperators.rexCall(b, expression.function,
                (if (temporal) expression.arguments.drop(1) else expression.arguments).map(::emit), unit)
        }
        is ResultExpression.ResultUniformAllocation -> {
            val value = emit(expression.value)
            val count = b.aggregateCall(Ops.COUNT, emptyList()).over().partitionBy(expression.sourceKeys.map(b::field))
                .rowsBetween(b.unboundedPreceding(), b.unboundedFollowing()).toRex()
            SemanticNumericRex.output(b, safeDivide(value, count), expression.valueContract)
        }
        is ResultExpression.ResultWeightedContribution -> weightedContribution(expression)
        is ResultExpression.ResultNumeric -> SemanticNumericRex.output(b, emit(expression.expression), expression.valueContract)
        is ResultExpression.ResultQuantityLiteral -> quantityLiteral(expression)
        is ResultExpression.ResultCast -> SemanticCalciteRexCompiler(b, parameters) { _, _ -> throw IllegalArgumentException("completed result expressions cannot access native source functions") }
            .cast(emit(expression.expression), expression.targetType, expression.precision, expression.scale, expression.rounding)
        is ResultExpression.ResultRound -> SemanticNumericRex.round(b, emit(expression.expression), expression.scale, expression.rounding)
        is ResultExpression.ResultRowField -> rowField(expression)
        is ResultExpression.ResultCollectionSort -> collectionSort(expression)
        is ResultExpression.ResultCollectionText -> collectionText(expression)
        is ResultExpression.ResultRow -> b.call(Ops.ROW, expression.operands.map(::emit))
        is ResultExpression.ResultLiteral -> literal(expression)
        is ResultExpression.ResultTypedLiteral -> typedLiteral(expression)
        is ResultExpression.ResultSymbol -> b.field(expression.symbol)
        is ResultExpression.ResultArgument -> error("metric scalar argument reached Rel compilation without binding")
        is ResultExpression.ResultCaseWhen -> conditional(expression)
        is ResultExpression.ResultOperation -> operation(expression)
    }

    private fun weightedContribution(weighted: ResultExpression.ResultWeightedContribution): RexNode {
        val value = emit(weighted.value)
        val weight = emit(weighted.weight)
        val numeric = ResultExpression.ResultNumeric(if (weighted.component == ResultExpression.WeightedComponent.VALUE)
            ResultExpression.ResultOperation(ResultExpression.ResultOperator.MULTIPLY, listOf(weighted.value, weighted.weight)) else weighted.weight,
            weighted.valueContract, weighted.unitEquation)
        val contribution = emit(numeric)
        return b.call(Ops.CASE, b.and(b.isNotNull(value), b.isNotNull(weight)), contribution, b.rexBuilder.makeNullLiteral(contribution.type))
    }

    private fun quantityLiteral(literal: ResultExpression.ResultQuantityLiteral): RexNode {
        val floating = literal.valueContract.result as? MetricValueContract.BinaryFloat
        if (floating != null) {
            val value: Any = if (floating.bits == 32) literal.value.toFloat() else literal.value.toDouble()
            return parameter(value, b.typeFactory.createSqlType(if (floating.bits == 32) SqlTypeName.REAL else SqlTypeName.DOUBLE))
        }
        val type = b.rexBuilder.makeExactLiteral(literal.value).type
        return SemanticNumericRex.output(b, parameter(literal.value, type), literal.valueContract)
    }

    private fun rowField(field: ResultExpression.ResultRowField): RexNode {
        val input = emit(field.expression)
        require(input.type.isStruct && field.index < input.type.fieldCount) { "ROW_FIELD requires an existing field of a typed ROW" }
        val result = b.rexBuilder.makeFieldAccess(input, field.index)
        return if (input.type.isNullable && !result.type.isNullable) b.rexBuilder.makeCast(b.typeFactory.createTypeWithNullability(result.type, true), result) else result
    }

    private fun collectionSort(sort: ResultExpression.ResultCollectionSort): RexNode {
        val input = emit(sort.expression)
        val system = b.typeFactory.typeSystem as SemanticCalciteTypeSystem
        require(system.supports(CompilationSupport.Capability.COLLECTION_SORT)) { "connector does not support explicit collection ordering" }
        val element = input.type.componentType
        require(input.type.sqlTypeName == SqlTypeName.ARRAY && element != null && !element.isStruct && element.componentType == null &&
            element.sqlTypeName !in setOf(SqlTypeName.ANY, SqlTypeName.OTHER, SqlTypeName.NULL)) { "collection ordering requires determined comparable scalar array elements" }
        return system.nativeSupport.sortCollection(b, input, sort.direction == "DESC", sort.nulls == "FIRST")
    }

    private fun collectionText(text: ResultExpression.ResultCollectionText): RexNode {
        val input = emit(text.expression)
        val system = b.typeFactory.typeSystem as SemanticCalciteTypeSystem
        require(system.supports(CompilationSupport.Capability.COLLECTION_TEXT)) { "connector does not support collection text" }
        require(input.type.sqlTypeName == SqlTypeName.ARRAY && input.type.componentType?.let(SqlTypeUtil::isCharacter) == true) { "collection text requires a typed text array" }
        return system.nativeSupport.collectionText(b, input, text.delimiter, text.nullText)
    }

    private fun literal(literal: ResultExpression.ResultLiteral): RexNode {
        val value = literal.value ?: return b.literal(null)
        val type = if (value is BigDecimal) b.rexBuilder.makeExactLiteral(value).type else b.literal(value).type
        return parameter(value, type)
    }

    private fun typedLiteral(literal: ResultExpression.ResultTypedLiteral): RexNode {
        val value = literal.valueType.parameter(literal.value)
        val type = when (literal.valueType) {
            ScalarValueType.TEXT -> b.typeFactory.createSqlType(SqlTypeName.VARCHAR, maxOf(1, (value as String).length))
            ScalarValueType.NUMBER -> b.rexBuilder.makeExactLiteral(value as BigDecimal).type
            ScalarValueType.DATE -> b.typeFactory.createSqlType(SqlTypeName.DATE)
            ScalarValueType.LOCAL_TIMESTAMP -> b.typeFactory.createSqlType(SqlTypeName.TIMESTAMP, 9)
            ScalarValueType.OFFSET_TIMESTAMP -> b.typeFactory.createSqlType(SqlTypeName.TIMESTAMP_TZ, b.typeFactory.typeSystem.getMaxPrecision(SqlTypeName.TIMESTAMP_TZ))
            else -> throw IllegalArgumentException("unsupported typed result literal: ${literal.valueType}")
        }
        return parameter(value, type)
    }

    private fun conditional(conditional: ResultExpression.ResultCaseWhen): RexNode {
        val operands = mutableListOf<RexNode>()
        val resultTypes = conditional.branches.map {
            val predicate = emit(it.`when`)
            require(predicate.type.sqlTypeName == SqlTypeName.BOOLEAN) { "result CASE WHEN requires a Calcite BOOLEAN expression" }
            val value = emit(it.then)
            operands.add(predicate); operands.add(value)
            value.type
        }.toMutableList()
        val otherwise = emit(conditional.otherwise)
        operands.add(otherwise); resultTypes.add(otherwise.type)
        require(b.typeFactory.leastRestrictive(resultTypes) != null) { "result CASE branches have no common Calcite type: $resultTypes" }
        return b.call(Ops.CASE, operands)
    }

    private fun operation(operation: ResultExpression.ResultOperation): RexNode {
        fun unit() = (operation.operands.first() as ResultExpression.ResultTypedLiteral).value as String
        return when (operation.operator) {
            ResultExpression.ResultOperator.DATE_TRUNC -> SemanticCalciteOperators.dateTrunc(b, unit(), emit(operation.operands[1]))
            ResultExpression.ResultOperator.TIMESTAMPDIFF -> SemanticCalciteOperators.timestampDifference(b, unit(), emit(operation.operands[1]), emit(operation.operands[2]))
            ResultExpression.ResultOperator.TIMESTAMPADD -> SemanticCalciteOperators.timestampAdd(b, unit(), emit(operation.operands[1]), emit(operation.operands[2]))
            else -> scalarOperation(operation.operator, operation.operands.map(::emit))
        }
    }
    private fun scalarOperation(operator: ResultExpression.ResultOperator, args: List<RexNode>): RexNode {
        if (operator in setOf(ResultExpression.ResultOperator.EQ, ResultExpression.ResultOperator.NE)) {
            val equal = SemanticCalciteOperators.codeEquals(b, args[0], args[1])
            return if (operator == ResultExpression.ResultOperator.EQ) equal else b.not(equal)
        }
        if (operator == ResultExpression.ResultOperator.SAFE_DIVIDE) return safeDivide(args[0], args[1])
        val native = when (operator) {
            ResultExpression.ResultOperator.ADD -> Ops.PLUS
            ResultExpression.ResultOperator.SUBTRACT -> Ops.MINUS
            ResultExpression.ResultOperator.MULTIPLY -> Ops.MULTIPLY
            ResultExpression.ResultOperator.DIVIDE -> SemanticCalciteOperators.division(b)
            ResultExpression.ResultOperator.NEGATE -> Ops.UNARY_MINUS
            ResultExpression.ResultOperator.ABS -> Ops.ABS
            ResultExpression.ResultOperator.GT -> Ops.GREATER_THAN
            ResultExpression.ResultOperator.GTE -> Ops.GREATER_THAN_OR_EQUAL
            ResultExpression.ResultOperator.LT -> Ops.LESS_THAN
            ResultExpression.ResultOperator.LTE -> Ops.LESS_THAN_OR_EQUAL
            ResultExpression.ResultOperator.IS_NULL -> Ops.IS_NULL
            ResultExpression.ResultOperator.IS_NOT_NULL -> Ops.IS_NOT_NULL
            ResultExpression.ResultOperator.AND -> Ops.AND
            ResultExpression.ResultOperator.OR -> Ops.OR
            ResultExpression.ResultOperator.NOT -> Ops.NOT
            else -> error("result operator handled before dispatch")
        }
        return b.call(native, args)
    }
    private fun safeDivide(value: RexNode, divisor: RexNode): RexNode = b.call(SemanticCalciteOperators.division(b), value, b.call(Ops.NULLIF, divisor, b.literal(0)))
    private fun parameter(value: Any, type: org.apache.calcite.rel.type.RelDataType): RexNode = SemanticCalciteRexCompiler.parameter(b, parameters, type, value)
}
