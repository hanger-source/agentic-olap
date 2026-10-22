package com.olap.prototype.query

import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.ScalarFunction
import com.olap.prototype.query.ResultExpression.ResultOperator

/** 公开表达式语法；阶段合同引用同一描述，不另存表达式模型。 */
object ResultExpressionSchema {
    fun contract(): Map<String, Any> = mapOf(
        "expressionKinds" to listOf("ARGUMENT", "SYMBOL", "LITERAL", "EXACT_NUMBER", "QUANTITY_LITERAL", "NUMERIC", "WEIGHTED_CONTRIBUTION", "UNIFORM_ALLOCATION",
            "TYPED_LITERAL", "CALL", "OPERATION", "CASE", "ROW", "ROW_FIELD", "COLLECTION_SORT", "COLLECTION_TEXT", "CAST", "ROUND"),
        "argument" to mapOf("requiredFields" to listOf("kind", "name", "valueType"),
            "valueTypes" to ScalarValueType.entries.map { it.name },
            "scope" to "CALCULATION_BODY_REQUIRES_A_DECLARED_SCALAR_PARAMETER_OF_THE_SAME_NAME_AND_TYPE",
            "binding" to "REPLACED_WITH_THE_EXPLICIT_ARGUMENT_BEFORE_STAGE_ANALYSIS_NO_UNBOUND_QUERY_PARAMETERS",
            "quantity" to "NUMERIC_PARAMETER_VALUE_CONTRACT_IS_ADOPTED_AND_PRESERVED_BY_THE_REPLACEMENT"),
        "call" to mapOf("requiredFields" to listOf("kind", "function", "arguments"),
            "functions" to ScalarFunction.entries.map { it.contract() }, "types" to "CALCITE_OPERAND_AND_RESULT_TYPES",
            "scope" to "CURRENT_COMPLETED_SYMBOLS_ONLY_NO_SOURCE_REFERENCES_NATIVE_FUNCTIONS_OR_SQL",
            "temporalUnit" to "FIRST_ARGUMENT_TYPED_LITERAL_TEXT_NOT_JDBC_PARAMETER"),
        "quantityContract" to mapOf(
            "numericFields" to listOf("kind", "expression", "valueContract", "unitEquation"),
            "quantityLiteralFields" to listOf("kind", "value", "valueContract"),
            "weightedContributionFields" to listOf("kind", "value", "weight", "observationUnit", "component", "valueContract", "unitEquation"),
            "weightedComponents" to listOf("VALUE", "WEIGHT"),
            "uniformAllocationFields" to listOf("kind", "value", "sourceKeys", "memberKeys", "valueContract", "unitEquation"),
            "weightedObservations" to "RAW_ROWS_ACTUAL_OCCURRENCES_OR_COMPLETE_NONNULL_OBJECT_KEYS_VERIFIED_IN_CURRENT_RELATION",
            "pairedNulls" to "BOTH_COMPONENTS_REQUIRE_VALUE_AND_WEIGHT_NONNULL_NO_IMPLICIT_WEIGHT_OR_ZERO",
            "unitEquation" to "EXPLICIT_NULL_OR_DECLARED_EQUATION_NAME", "values" to "EXACT_DECIMAL_TEXT",
            "categoricalMagnitude" to "EXPLICIT_NUMERIC_DIMENSIONLESS_ONLY_NATIVE_NUMERIC_TYPE_AND_PRECISION_MUST_VALIDATE"),
        "round" to mapOf("requiredFields" to listOf("kind", "expression", "scale", "rounding"),
            "roundingModes" to listOf("HALF_UP", "HALF_DOWN", "HALF_EVEN", "TRUNCATE"), "scale" to "INTEGER_MINUS_38_TO_38"),
        "cast" to mapOf("requiredFields" to listOf("kind", "expression", "targetType", "precision", "scale", "rounding"),
            "targetTypes" to SemanticExpression.ValueType.entries.map { it.name }, "typeRules" to "SHARED_SEMANTIC_CAST_TARGET_RULES_CALCITE_TYPE_SYSTEM",
            "nulls" to "PROPAGATE_INPUT_NULLABILITY"),
        "literalType" to "LITERAL_BOOLEAN_OR_EXPLICIT_NULL_EXACT_NUMBER_DECIMAL_STRING",
        "rowContract" to mapOf("requiredFields" to listOf("kind", "operands"), "operands" to "NONEMPTY_ORDERED_ARRAY_OF_RESULT_EXPRESSIONS",
            "value" to "TYPED_ROW_FIELDS_IN_DECLARED_OPERAND_ORDER_NULL_FIELDS_PRESERVED", "meaning" to "ONE_TUPLE_NOT_PARALLEL_INDEPENDENT_ARRAYS"),
        "typedLiteral" to mapOf("requiredFields" to listOf("kind", "valueType", "value"),
            "valueTypes" to listOf("TEXT", "NUMBER", "DATE", "LOCAL_TIMESTAMP", "OFFSET_TIMESTAMP"),
            "value" to "NON_NULL_STRING_VALIDATED_BY_EXACT_DECLARED_SCALAR_TYPE", "binding" to "TYPED_JDBC_PARAMETER_NO_SQL_TEXT_OR_TIMEZONE_GUESSING"),
        "caseContract" to mapOf("requiredFields" to listOf("kind", "branches", "otherwise"),
            "branches" to "NONEMPTY_ORDERED_ARRAY_OF_EXACT_WHEN_THEN_EXPRESSIONS", "whenType" to "BOOLEAN",
            "selection" to "FIRST_TRUE_BRANCH_FALSE_AND_UNKNOWN_CONTINUE_OTHERWISE_REQUIRED", "resultType" to "CALCITE_COMMON_BRANCH_TYPE_INCOMPATIBLE_TYPES_REJECTED",
            "nullMeaning" to "EXPLICIT_EXPRESSION_NOT_IMPLICIT_MISSING_GROUP_OR_UNKNOWN_COMPLETION"),
        "operators" to ResultOperator.entries.map { it.name },
        "operatorContracts" to ResultOperator.entries.map(::operator),
        "collectionSort" to mapOf("requiredFields" to listOf("kind", "expression", "direction", "nulls"), "direction" to listOf("ASC", "DESC"),
            "nulls" to listOf("FIRST", "LAST"), "input" to "DETERMINED_COMPARABLE_SCALAR_ARRAY", "nativeRequirement" to "COLLECTION_SORT_IN_SELECTED_EXACT_EXECUTION_CONTEXT",
            "meaning" to "PRESERVE_DUPLICATES_NULL_ELEMENTS_AND_QUANTITY_NO_NEW_BUSINESS_ORDER"),
        "collectionText" to mapOf("requiredFields" to listOf("kind", "expression", "delimiter", "nulls", "nullText"), "input" to "TEXT_ARRAY_WITHOUT_QUANTITY_ELEMENTS",
            "nativeRequirement" to "COLLECTION_TEXT_IN_SELECTED_EXACT_EXECUTION_CONTEXT", "nulls" to listOf("SKIP", "REPLACE"),
            "nullText" to "SKIP_REQUIRES_NULL_REPLACE_REQUIRES_EXPLICIT_STRING", "nullArray" to "NULL", "emptyArray" to "EMPTY_STRING", "meaning" to "DISPLAY_TEXT_NOT_TYPED_COLLECTION"),
        "rowField" to mapOf("requiredFields" to listOf("kind", "expression", "index"), "index" to "ZERO_BASED_DECLARED_ROW_FIELD_POSITION",
            "meaning" to "PRESERVE_EXACT_FIELD_VALUE_AND_QUANTITY_NULL_ROW_PRODUCES_NULL")
    )
    private fun operator(operator: ResultOperator): Map<String, Any> = mapOf(
        "operator" to operator.name, "operandCount" to operator.arity,
        "operandRequirements" to when (operator) {
            ResultOperator.TIMESTAMPDIFF -> "TYPED_LITERAL_TEXT_CALCITE_TIME_UNIT_START_TEMPORAL_END_TEMPORAL"
            ResultOperator.TIMESTAMPADD -> "TYPED_LITERAL_TEXT_CALCITE_TIME_UNIT_INTEGER_QUANTITY_TEMPORAL"
            ResultOperator.DATE_TRUNC -> "TYPED_LITERAL_TEXT_CALCITE_TIME_UNIT_TEMPORAL"
            else -> "RESULT_EXPRESSIONS_VALIDATED_BY_CALCITE"
        },
        "timeMeaning" to when (operator) {
            ResultOperator.TIMESTAMPDIFF -> "COMPLETE_ELAPSED_UNITS_SIGNED_BIGINT_NOT_CALENDAR_BOUNDARY_COUNT_UNIT_IS_NOT_JDBC_PARAMETER"
            ResultOperator.TIMESTAMPADD -> "CALCITE_CALENDAR_INTERVAL_SHIFT_NOT_PREVIOUS_OBSERVATION_NO_TIMEZONE_CONVERSION_UNIT_IS_NOT_JDBC_PARAMETER"
            ResultOperator.DATE_TRUNC -> "CALCITE_FLOOR_TO_CALENDAR_BOUNDARY_WEEK_START_MONDAY_PRESERVE_TEMPORAL_TYPE_NO_TIMEZONE_CONVERSION_UNIT_IS_NOT_JDBC_PARAMETER"
            else -> "NOT_APPLICABLE"
        },
        "nullOperandResult" to when (operator) {
            ResultOperator.IS_NULL -> "TRUE"
            ResultOperator.IS_NOT_NULL -> "FALSE"
            ResultOperator.AND, ResultOperator.OR -> "SQL_THREE_VALUED_LOGIC"
            else -> "NULL"
        },
        "numericMeaning" to "NUMERIC_NODE_DECLARES_PRECISION_UNIT_EQUATION_AND_OUTPUT_CAPACITY",
        "zeroDenominator" to when (operator) {
            ResultOperator.SAFE_DIVIDE -> "NULL"
            ResultOperator.DIVIDE -> "UNGUARDED_BACKEND_ARITHMETIC"
            else -> "NOT_APPLICABLE"
        }
    )
}
