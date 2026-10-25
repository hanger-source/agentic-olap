package com.olap.prototype.query.contract

import com.olap.prototype.expression.ScalarFunction
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.query.*

/** 冻结的是获准操作及表达式集合，不把语言说明文档当作权限数据。 */
class StageAuthorization(val operationType: String, joinTypes: Set<JoinType>, aggregations: Set<ResultAggregation.Kind>,
    val expressions: ExpressionAuthorization) {
    enum class JoinType { INNER, LEFT, RIGHT, FULL, SEMI, ANTI }
    val joinTypes: Set<JoinType> = java.util.Set.copyOf(joinTypes)
    val aggregations: Set<ResultAggregation.Kind> = java.util.Set.copyOf(aggregations)
    init { PostOperationContract.requireType(operationType, "stage authorization operation") }
    /** 仅报告超出支持域的显式选择；未选择的能力不构成错误，也不自动补齐。 */
    fun unsupportedBy(supported: StageAuthorization): Map<String, List<String>> = buildMap {
        if (operationType != supported.operationType) put("operationType", listOf(operationType))
        fun <T : Enum<T>> selected(field: String, actual: Set<T>, available: Set<T>) {
            val unsupported = (actual - available).map { it.name }.sorted()
            if (unsupported.isNotEmpty()) put(field, unsupported)
        }
        selected("joinTypes", joinTypes, supported.joinTypes)
        selected("aggregations", aggregations, supported.aggregations)
        selected("expressions.kinds", expressions.kinds, supported.expressions.kinds)
        selected("expressions.operators", expressions.operators, supported.expressions.operators)
        selected("expressions.functions", expressions.functions, supported.expressions.functions)
        selected("expressions.castTypes", expressions.castTypes, supported.expressions.castTypes)
        selected("expressions.literalTypes", expressions.literalTypes, supported.expressions.literalTypes)
    }
    override fun equals(other: Any?): Boolean = other is StageAuthorization && operationType == other.operationType && joinTypes == other.joinTypes && aggregations == other.aggregations && expressions == other.expressions
    override fun hashCode(): Int = listOf(operationType, joinTypes, aggregations, expressions).hashCode()
    companion object {
        fun contract(): Map<String, Any> = mapOf(
            "operationType" to PostOperationContract.operationTypes(),
            "collections" to "EXPLICIT_IMMUTABLE_SETS_NO_IMPLICIT_EXPANSION",
            "equality" to "OPERATION_TYPE_AND_ALL_JOIN_AGGREGATION_EXPRESSION_SETS_ORDER_INDEPENDENT",
            "currentFactory" to "DEPLOYED_LANGUAGE_CANDIDATE_NOT_AUTOMATIC_AUTHORIZATION",
            "currentJoinTypes" to "ALL_JOIN_TYPES_FOR_JOIN_INPUT_OTHERWISE_EMPTY",
            "currentAggregations" to "ALL_RESULT_AGGREGATION_KINDS_FOR_PARTITION_AGGREGATE_REGROUP_WINDOW_AGGREGATE_OBSERVE_WINDOW_OTHERWISE_EMPTY",
            "currentExpressions" to "ExpressionAuthorization.current()",
            "scenarioSelection" to "CREATE_AND_REFRESH_REQUIRE_EXPLICIT_SAME_OPERATION_SELECTION_WITHIN_CURRENT_SUPPORTED_SETS",
            "reduction" to "REFRESH_MAY_REMOVE_PREVIOUSLY_APPROVED_CAPABILITIES_WITHOUT_CHANGING_EXISTING_RELEASES",
            "dependencies" to "NO_IMPLICIT_EXPRESSION_CLOSURE_EACH_ACTUALLY_USED_NODE_AND_ARGUMENT_MUST_BE_SELECTED",
            "publishedAuthorization" to "EXACT_FROZEN_SETS_CHECK_EACH_NODE_AND_ARGUMENT_RECURSIVELY_NO_AUTOMATIC_UPGRADE",
        )
        fun current(operationType: String): StageAuthorization = StageAuthorization(operationType,
            if (operationType == PostOperationContract.JOIN_INPUT) JoinType.entries.toSet() else emptySet(),
            if (operationType in setOf(PostOperationContract.PARTITION_AGGREGATE, PostOperationContract.REGROUP,
                    PostOperationContract.WINDOW_AGGREGATE, PostOperationContract.OBSERVE_WINDOW)) ResultAggregation.Kind.entries.toSet() else emptySet(),
            ExpressionAuthorization.current())
    }
}

class ExpressionAuthorization(kinds: Set<Kind>, operators: Set<ResultExpression.ResultOperator>, functions: Set<ScalarFunction>,
    castTypes: Set<SemanticExpression.ValueType>, literalTypes: Set<ScalarValueType>) {
    enum class Kind { ARGUMENT, SYMBOL, LITERAL, EXACT_NUMBER, QUANTITY_LITERAL, NUMERIC, WEIGHTED_CONTRIBUTION, UNIFORM_ALLOCATION,
        TYPED_LITERAL, CALL, NATIVE_CALL, OPERATION, CASE, ROW, ROW_FIELD, COLLECTION_SORT, COLLECTION_TEXT, CAST, ROUND }
    val kinds: Set<Kind> = java.util.Set.copyOf(kinds)
    val operators: Set<ResultExpression.ResultOperator> = java.util.Set.copyOf(operators)
    val functions: Set<ScalarFunction> = java.util.Set.copyOf(functions)
    val castTypes: Set<SemanticExpression.ValueType> = java.util.Set.copyOf(castTypes)
    val literalTypes: Set<ScalarValueType> = java.util.Set.copyOf(literalTypes)
    override fun equals(other: Any?): Boolean = other is ExpressionAuthorization && kinds == other.kinds && operators == other.operators && functions == other.functions && castTypes == other.castTypes && literalTypes == other.literalTypes
    override fun hashCode(): Int = listOf(kinds, operators, functions, castTypes, literalTypes).hashCode()
    companion object {
        fun contract(): Map<String, Any> = mapOf(
            "collections" to "EXPLICIT_IMMUTABLE_SETS",
            "equality" to "VALUE_EQUALITY_COMPARES_ALL_FIVE_SETS_ORDER_INDEPENDENT",
            "currentKinds" to Kind.entries.map { it.name },
            "currentOperators" to ResultExpression.ResultOperator.entries.map { it.name },
            "currentFunctions" to ScalarFunction.entries.map { it.name },
            "currentCastTypes" to SemanticExpression.ValueType.entries.map { it.name },
            "currentLiteralTypes" to current().literalTypes.map { it.name }.sorted(),
            "approval" to "CREATE_AND_REFRESH_ACCEPT_EXPLICIT_SUBSETS_OF_THE_SUPPORTED_SETS",
            "currentFactory" to "OPTIONAL_AUTHOR_FACTORY_SELECTING_ALL_CURRENT_EXPRESSION_CAPABILITIES",
            "selection" to "NO_IMPLICIT_CLOSURE_OR_EXPANSION_FROM_THE_QUERY_OR_PREVIOUS_AUTHORIZATION",
        )
        fun current(): ExpressionAuthorization = ExpressionAuthorization(Kind.entries.toSet(), ResultExpression.ResultOperator.entries.toSet(),
            ScalarFunction.entries.toSet(), SemanticExpression.ValueType.entries.toSet(),
            setOf(ScalarValueType.TEXT, ScalarValueType.NUMBER, ScalarValueType.DATE, ScalarValueType.LOCAL_TIMESTAMP, ScalarValueType.OFFSET_TIMESTAMP))
    }
}
