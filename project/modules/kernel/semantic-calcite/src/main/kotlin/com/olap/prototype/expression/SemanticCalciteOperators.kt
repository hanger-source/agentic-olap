package com.olap.prototype.expression

import com.olap.prototype.member.MemberCondition
import com.olap.prototype.connector.NativeArithmeticMappingException
import com.olap.prototype.connector.NativeCompilationCapabilityException
import org.apache.calcite.avatica.util.TimeUnit
import org.apache.calcite.avatica.util.TimeUnitRange
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.*
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.type.SqlTypeUtil
import org.apache.calcite.tools.RelBuilder
import java.util.Locale

/** 授权语义到Calcite标准算子的共同绑定，不实现自己的类型或关系代数。 */
object SemanticCalciteOperators {
    fun predicate(builder: RelBuilder, field: RexNode, operator: MemberCondition.Operator, values: List<RexNode>): RexNode = when (operator) {
        MemberCondition.Operator.IS_NULL -> builder.isNull(field)
        MemberCondition.Operator.IS_NOT_NULL -> builder.isNotNull(field)
        MemberCondition.Operator.IN -> builder.or(values.map { codeEquals(builder, field, it) })
        MemberCondition.Operator.BETWEEN -> builder.and(builder.call(Ops.GREATER_THAN_OR_EQUAL, field, values[0]), builder.call(Ops.LESS_THAN_OR_EQUAL, field, values[1]))
        MemberCondition.Operator.EQ -> codeEquals(builder, field, values.first())
        MemberCondition.Operator.NE -> builder.not(codeEquals(builder, field, values.first()))
        MemberCondition.Operator.GT -> builder.call(Ops.GREATER_THAN, field, values.first())
        MemberCondition.Operator.GTE -> builder.call(Ops.GREATER_THAN_OR_EQUAL, field, values.first())
        MemberCondition.Operator.LT -> builder.call(Ops.LESS_THAN, field, values.first())
        MemberCondition.Operator.LTE -> builder.call(Ops.LESS_THAN_OR_EQUAL, field, values.first())
    }

    fun divide(builder: RelBuilder, left: RexNode, right: RexNode): RexNode {
        require(SqlTypeUtil.isNumeric(left.type) && SqlTypeUtil.isNumeric(right.type)) { "division requires two numeric operands" }
        val types = builder.typeFactory.typeSystem as? SemanticCalciteTypeSystem
            ?: error("numeric expression requires the bound semantic type system")
        var operands = listOf(left, right)
        return try {
            if (operands.all { SqlTypeUtil.isIntType(it.type) }) {
                operands = operands.map { decimalInteger(builder, types, it, operands) }
            }
            val result = builder.call(if (types.semantic) Ops.DIVIDE else types.nativeSupport.division, operands)
            val inputTypes = operands.map { it.type.fullTypeString }
            if (!SqlTypeUtil.isNumeric(result.type)) {
                throw NativeArithmeticMappingException.rejected("DIVIDE", inputTypes, NativeArithmeticMappingException.Reason.NON_NUMERIC_QUOTIENT)
            }
            if (SqlTypeUtil.isIntType(result.type)) {
                throw NativeArithmeticMappingException.rejected("DIVIDE", inputTypes, NativeArithmeticMappingException.Reason.INTEGRAL_QUOTIENT)
            }
            result
        } catch (failure: NativeArithmeticMappingException) {
            throw failure
        } catch (failure: NativeCompilationCapabilityException) {
            throw failure
        } catch (failure: IllegalArgumentException) {
            throw NativeArithmeticMappingException("DIVIDE", operands.map { it.type.fullTypeString }, failure)
        } catch (failure: UnsupportedOperationException) {
            throw NativeArithmeticMappingException("DIVIDE", operands.map { it.type.fullTypeString }, failure)
        }
    }

    /** 整数域的无损嵌入不使用最终输出容量，也不替连接器选择小数商的工作精度。 */
    private fun decimalInteger(builder: RelBuilder, types: SemanticCalciteTypeSystem, input: RexNode, operands: List<RexNode>): RexNode {
        val precision = when (input.type.sqlTypeName) {
            SqlTypeName.TINYINT, SqlTypeName.UTINYINT -> 3
            SqlTypeName.SMALLINT, SqlTypeName.USMALLINT -> 5
            SqlTypeName.INTEGER, SqlTypeName.UINTEGER -> 10
            SqlTypeName.BIGINT -> 19
            SqlTypeName.UBIGINT -> 20
            else -> error("integer division normalization requires an integer SQL type")
        }
        if (precision > types.getMaxPrecision(SqlTypeName.DECIMAL)) {
            throw NativeArithmeticMappingException.rejected("DIVIDE", operands.map { it.type.fullTypeString },
                NativeArithmeticMappingException.Reason.INTEGER_INPUT_CAPACITY_UNAVAILABLE)
        }
        val target = builder.typeFactory.createTypeWithNullability(
            builder.typeFactory.createSqlType(SqlTypeName.DECIMAL, precision, 0), input.type.isNullable)
        if (!types.semantic) types.nativeSupport.requireNativeCast(target)
        return builder.rexBuilder.makeCast(target, input)
    }

    fun codeEquals(builder: RelBuilder, left: RexNode, right: RexNode): RexNode {
        if (!SqlTypeUtil.inCharFamily(left.type) || !SqlTypeUtil.inCharFamily(right.type)) return builder.equals(left, right)
        val types = builder.typeFactory.typeSystem as? SemanticCalciteTypeSystem
            ?: error("member equality needs its bound execution dialect")
        return types.nativeSupport.textIdentityEquals(builder, left, right).also {
            require(it.type.sqlTypeName == SqlTypeName.BOOLEAN) { "connector text identity comparison must return BOOLEAN" }
        }
    }

    fun function(function: ScalarFunction): SqlOperator = when (function) {
        ScalarFunction.ABS -> Ops.ABS
        ScalarFunction.COALESCE -> Ops.COALESCE
        ScalarFunction.CONCAT -> Ops.CONCAT
        ScalarFunction.LOWER -> Ops.LOWER
        ScalarFunction.NULLIF -> Ops.NULLIF
        ScalarFunction.SUBSTRING -> Ops.SUBSTRING
        ScalarFunction.UPPER -> Ops.UPPER
        ScalarFunction.TIMESTAMPDIFF -> Ops.TIMESTAMP_DIFF
        ScalarFunction.DATE_TRUNC -> Ops.FLOOR
        ScalarFunction.TIMESTAMPADD -> Ops.TIMESTAMP_ADD
    }

    fun contract(): Map<String, Any> {
        val descriptions = ScalarFunction.entries.sortedBy { it.name }.map { declaration ->
            val operator = function(declaration)
            buildMap<String, Any?> {
                putAll(declaration.contract()); put("calciteOperator", operator.name); put("calciteSignatures", operator.allowedSignatures)
                if (operator in listOf<SqlOperator>(Ops.TIMESTAMP_DIFF, Ops.TIMESTAMP_ADD, Ops.FLOOR)) {
                    put("arguments", when (operator) {
                        Ops.FLOOR -> listOf("literalTimeUnit", "temporal")
                        Ops.TIMESTAMP_ADD -> listOf("literalTimeUnit", "integerQuantity", "temporal")
                        else -> listOf("literalTimeUnit", "start", "end")
                    })
                    put("literalTimeUnit", mapOf("kind" to "LITERAL", "valueType" to "STRING", "caseSensitive" to false,
                        "units" to TimeUnit.values().filter { it.multiplier != null && it != TimeUnit.EPOCH }.map { it.name }))
                }
                when (operator) {
                    Ops.FLOOR -> put("weekStart", "MONDAY")
                    Ops.TIMESTAMP_DIFF -> put("meaning", "COMPLETE_ELAPSED_UNITS_NOT_BOUNDARY_COUNT")
                    Ops.TIMESTAMP_ADD -> put("meaning", "CALENDAR_INTERVAL_SHIFT_NOT_PREVIOUS_OBSERVATION_NO_TIMEZONE_CONVERSION")
                }
            }
        }
        val types = SemanticCalciteTypeSystem()
        return mapOf("formatVersion" to "olap-semantic-expression-contract/v3", "functions" to descriptions,
            "division" to mapOf("operators" to listOf("DIVIDE", "SAFE_DIVIDE"),
                "meaning" to "ARITHMETIC_QUOTIENT_NOT_INTEGER_TRUNCATION",
                "integerOperands" to "BOTH_INTEGER_INPUTS_LOSSLESS_DECIMAL_FULL_INTEGER_DOMAIN_SCALE_ZERO",
                "nativeMapping" to "CONNECTOR_ARITHMETIC_DIVISION_NON_INTEGER_SQL_TYPE_OR_EXPLICIT_REJECTION",
                "outputQuantization" to "EXPLICIT_NUMERIC_CAST_OR_ROUND_AFTER_EXPRESSION_NOT_PUSHED_INTO_SUBEXPRESSIONS",
                "nativePrecision" to "INTEGER_NORMALIZATION_DOES_NOT_PROVE_FRACTIONAL_PRECISION_OR_SINGLE_ROUNDING",
                "safeDivideZeroDenominator" to "NULL"),
            "textIdentityEquality" to mapOf("binding" to "CODE_EQUALS_TEXT_OPERANDS_EQ_NE_IN",
                "meaning" to "EXACT_ENCODED_IDENTITY", "caseFolding" to false, "trimWhitespace" to false,
                "unicodeNormalization" to "NONE", "nativeMapping" to "CONNECTOR_TEXT_IDENTITY_EQUALS",
                "operandType" to "PRESERVE_REAL_NATIVE_TYPE_NO_BINARY_RESULT_REQUIREMENT",
                "nativeCollation" to "NATIVE_COMPARISON_ALLOWED_WITH_CONNECTOR_GUARANTEED_EXACT_IDENTITY_NOT_ASSUMED_FROM_DEFAULT_COLLATION",
                "scope" to "DOES_NOT_REDEFINE_GROUP_BY_ORDER_BY_OR_GENERAL_CHAR_SEMANTICS"),
            "nativeFunctions" to mapOf("declarations" to "read-connector-capabilities.connectors[].nativeScalarFunctions",
                "binding" to "EXACT_CONNECTOR_AND_ADOPTED_PHYSICAL_TABLE_VERSION_SAME_SOURCE_BINDING",
                "sql" to "SINGLE_SOURCE_STATEMENT_PLUGIN_AST_NO_CLIENT_PREFETCH",
                "stability" to mapOf("IMMUTABLE" to "RESULT_DEPENDS_ONLY_ON_IMMUTABLE_ARGUMENT_VALUES_NOT_CHANGING_TABLE_DATA",
                    "STATEMENT" to "STABLE_WITHIN_ONE_SOURCE_STATEMENT_NOT_ACROSS_REQUESTS", "VOLATILE" to "MAY_CHANGE_BETWEEN_CALLS_NO_CONSTANT_FOLDING"),
                "arithmetic" to "DECLARED_NATIVE_GUARANTEE_SURVIVES_CASTS_DECIMAL_RESULT_DOES_NOT_IMPLY_EXACT"),
            "construction" to "KOTLIN_DSL_TYPED_SEMANTIC_EXPRESSION",
            "maximumDecimalPrecision" to types.getMaxPrecision(SqlTypeName.DECIMAL),
            "maximumTemporalPrecision" to types.getMaxPrecision(SqlTypeName.TIMESTAMP),
            "validationBoundary" to "Registered operators require exact authorized references and Calcite operand validation; dialect execution and business meaning remain separate evidence.")
    }

    private fun unit(call: SemanticExpression.Call, arity: Int): TimeUnit {
        val literal = call.arguments.firstOrNull() as? SemanticExpression.Literal
        require(call.arguments.size == arity && literal?.value is String) {
            "${call.function} requires a literal time unit and ${arity - 1} temporal operands"
        }
        return timeUnit(literal.value as String)
    }

    private fun timeUnit(name: String): TimeUnit = TimeUnit.valueOf(name.uppercase(Locale.ROOT)).also {
        require(it.multiplier != null && it != TimeUnit.EPOCH) { "unsupported Calcite temporal unit: $name" }
    }

    fun sqlCall(call: SemanticExpression.Call, operands: (SemanticExpression) -> SqlNode): SqlNode {
        val operator = function(call.function)
        return when (operator) {
            Ops.TIMESTAMP_DIFF, Ops.TIMESTAMP_ADD -> operator.createCall(SqlParserPos.ZERO,
                SqlIntervalQualifier(unit(call, 3), null, SqlParserPos.ZERO), operands(call.arguments[1]), operands(call.arguments[2]))
            Ops.FLOOR -> operator.createCall(SqlParserPos.ZERO, operands(call.arguments[1]), SqlIntervalQualifier(unit(call, 2), null, SqlParserPos.ZERO))
            else -> sqlScalarFunction(call.function, call.arguments.map(operands))
        }
    }

    fun sqlScalarFunction(function: ScalarFunction, arguments: List<SqlNode>): SqlNode {
        val operator = SemanticCalciteOperators.function(function)
        return if (function == ScalarFunction.CONCAT) arguments.reduce { left, right -> operator.createCall(SqlParserPos.ZERO, left, right) }
        else operator.createCall(SqlParserPos.ZERO, arguments)
    }

    fun rexCall(builder: RelBuilder, call: SemanticExpression.Call, operands: (SemanticExpression) -> RexNode): RexNode = when (val operator = function(call.function)) {
        Ops.TIMESTAMP_DIFF -> timestampDifference(builder, unit(call, 3), operands(call.arguments[1]), operands(call.arguments[2]))
        Ops.TIMESTAMP_ADD -> timestampAdd(builder, unit(call, 3), operands(call.arguments[1]), operands(call.arguments[2]))
        Ops.FLOOR -> dateTrunc(builder, unit(call, 2), operands(call.arguments[1]))
        else -> rexCall(builder, call.function, call.arguments.map(operands))
    }

    fun rexCall(builder: RelBuilder, function: ScalarFunction, arguments: List<RexNode>, temporalUnit: String? = null): RexNode {
        function.requireArity(arguments.size + if (function.temporal) 1 else 0)
        if (!function.temporal) SemanticExpressionValueType.requireFunction(function, arguments.map { it.type }, builder.typeFactory)
        return when (function) {
            ScalarFunction.TIMESTAMPDIFF -> timestampDifference(builder, requireNotNull(temporalUnit), arguments[0], arguments[1])
            ScalarFunction.TIMESTAMPADD -> timestampAdd(builder, requireNotNull(temporalUnit), arguments[0], arguments[1])
            ScalarFunction.DATE_TRUNC -> dateTrunc(builder, requireNotNull(temporalUnit), arguments[0])
            ScalarFunction.CONCAT -> arguments.reduce { left, right -> builder.call(Ops.CONCAT, left, right) }
            else -> builder.call(SemanticCalciteOperators.function(function), arguments)
        }
    }

    fun dateTrunc(builder: RelBuilder, unit: String, temporal: RexNode): RexNode = dateTrunc(builder, timeUnit(unit), temporal)
    private fun dateTrunc(builder: RelBuilder, unit: TimeUnit, temporal: RexNode): RexNode {
        require(SqlTypeUtil.isDatetime(temporal.type)) { "DATE_TRUNC requires a temporal operand" }
        val date = temporal.type.sqlTypeName == SqlTypeName.DATE
        val input = if (date) builder.cast(temporal, SqlTypeName.TIMESTAMP) else temporal
        val truncated = builder.call(Ops.FLOOR, input, builder.rexBuilder.makeFlag(TimeUnitRange.of(unit, null)))
        return if (date) builder.rexBuilder.makeCast(temporal.type, truncated) else truncated
    }

    fun timestampDifference(builder: RelBuilder, unit: String, start: RexNode, end: RexNode): RexNode = timestampDifference(builder, timeUnit(unit), start, end)
    private fun timestampDifference(builder: RelBuilder, unit: TimeUnit, start: RexNode, end: RexNode): RexNode {
        require(SqlTypeUtil.isDatetime(start.type) && SqlTypeUtil.isDatetime(end.type)) { "TIMESTAMPDIFF requires temporal operands" }
        val type = builder.typeFactory.createTypeWithNullability(builder.typeFactory.createSqlType(SqlTypeName.BIGINT), start.type.isNullable || end.type.isNullable)
        return builder.rexBuilder.makeCall(type, Ops.TIMESTAMP_DIFF, listOf(builder.rexBuilder.makeFlag(unit), start, end))
    }

    fun timestampAdd(builder: RelBuilder, unit: String, quantity: RexNode, temporal: RexNode): RexNode = timestampAdd(builder, timeUnit(unit), quantity, temporal)
    private fun timestampAdd(builder: RelBuilder, unit: TimeUnit, quantity: RexNode, temporal: RexNode): RexNode {
        require(SqlTypeUtil.isIntType(quantity.type) && SqlTypeUtil.isDatetime(temporal.type)) { "TIMESTAMPADD requires an integer quantity and temporal operand" }
        val flag = builder.rexBuilder.makeFlag(unit)
        val shifted = builder.call(Ops.TIMESTAMP_ADD, flag, quantity, temporal)
        if (temporal.type.sqlTypeName == SqlTypeName.DATE && shifted.type.sqlTypeName == SqlTypeName.DATE) {
            val timestamp = builder.call(Ops.TIMESTAMP_ADD, flag, quantity, builder.cast(temporal, SqlTypeName.TIMESTAMP))
            return builder.rexBuilder.makeCast(shifted.type, timestamp)
        }
        return shifted
    }

    fun operation(operator: SemanticExpression.Operator): SqlOperator = when (operator) {
        SemanticExpression.Operator.ADD -> Ops.PLUS
        SemanticExpression.Operator.SUBTRACT -> Ops.MINUS
        SemanticExpression.Operator.MULTIPLY -> Ops.MULTIPLY
        SemanticExpression.Operator.DIVIDE, SemanticExpression.Operator.SAFE_DIVIDE -> Ops.DIVIDE
        SemanticExpression.Operator.MODULO -> Ops.MOD
        SemanticExpression.Operator.NEGATE -> Ops.UNARY_MINUS
        SemanticExpression.Operator.EQ -> Ops.EQUALS
        SemanticExpression.Operator.NE -> Ops.NOT_EQUALS
        SemanticExpression.Operator.GT -> Ops.GREATER_THAN
        SemanticExpression.Operator.GTE -> Ops.GREATER_THAN_OR_EQUAL
        SemanticExpression.Operator.LT -> Ops.LESS_THAN
        SemanticExpression.Operator.LTE -> Ops.LESS_THAN_OR_EQUAL
        SemanticExpression.Operator.LIKE -> Ops.LIKE
        SemanticExpression.Operator.IN -> Ops.IN
        SemanticExpression.Operator.BETWEEN -> Ops.BETWEEN
        SemanticExpression.Operator.IS_NULL -> Ops.IS_NULL
        SemanticExpression.Operator.IS_NOT_NULL -> Ops.IS_NOT_NULL
        SemanticExpression.Operator.AND -> Ops.AND
        SemanticExpression.Operator.OR -> Ops.OR
        SemanticExpression.Operator.NOT -> Ops.NOT
    }
}
