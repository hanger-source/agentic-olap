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
    override fun equals(other: Any?): Boolean = other is StageAuthorization && operationType == other.operationType && joinTypes == other.joinTypes && aggregations == other.aggregations && expressions == other.expressions
    override fun hashCode(): Int = listOf(operationType, joinTypes, aggregations, expressions).hashCode()
    companion object {
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
        TYPED_LITERAL, CALL, OPERATION, CASE, ROW, ROW_FIELD, COLLECTION_SORT, COLLECTION_TEXT, CAST, ROUND }
    val kinds: Set<Kind> = java.util.Set.copyOf(kinds)
    val operators: Set<ResultExpression.ResultOperator> = java.util.Set.copyOf(operators)
    val functions: Set<ScalarFunction> = java.util.Set.copyOf(functions)
    val castTypes: Set<SemanticExpression.ValueType> = java.util.Set.copyOf(castTypes)
    val literalTypes: Set<ScalarValueType> = java.util.Set.copyOf(literalTypes)
    override fun equals(other: Any?): Boolean = other is ExpressionAuthorization && kinds == other.kinds && operators == other.operators && functions == other.functions && castTypes == other.castTypes && literalTypes == other.literalTypes
    override fun hashCode(): Int = listOf(kinds, operators, functions, castTypes, literalTypes).hashCode()
    companion object {
        fun current(): ExpressionAuthorization = ExpressionAuthorization(Kind.entries.toSet(), ResultExpression.ResultOperator.entries.toSet(),
            ScalarFunction.entries.toSet(), SemanticExpression.ValueType.entries.toSet(),
            setOf(ScalarValueType.TEXT, ScalarValueType.NUMBER, ScalarValueType.DATE, ScalarValueType.LOCAL_TIMESTAMP, ScalarValueType.OFFSET_TIMESTAMP))
    }
}
