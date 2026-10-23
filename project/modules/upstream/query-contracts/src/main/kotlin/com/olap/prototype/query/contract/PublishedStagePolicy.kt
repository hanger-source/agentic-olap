package com.olap.prototype.query.contract

import com.olap.prototype.query.PostOperationContract
import com.olap.prototype.query.ResultExpression
import com.olap.prototype.query.ResultExpression.*
import com.olap.prototype.query.ResultExpressionTree
import java.math.BigDecimal

/** 语法可构造不等于发布已授权；核对冻结的精确允许集。 */
object PublishedStagePolicy {
    fun require(operation: PostOperationContract.ValidatedOperation, authorization: StageAuthorization, field: String) {
        require(operation.operationType == authorization.operationType) { "$field has a different published stage identity" }
        when (operation) {
            is PostOperationContract.JoinInput -> require(StageAuthorization.JoinType.valueOf(operation.joinType) in authorization.joinTypes) { "$field join type is not published: ${operation.joinType}" }
            is PostOperationContract.AggregateOutput -> require(operation.aggregation.kind in authorization.aggregations) { "$field aggregation is not published: ${operation.aggregation.code}" }
            is PostOperationContract.ObserveWindow -> require(operation.aggregation.kind in authorization.aggregations) { "$field aggregation is not published: ${operation.aggregation.code}" }
            is PostOperationContract.Regroup -> operation.grouping.aggregates.forEach { require(it.aggregation.kind in authorization.aggregations) { "$field aggregation is not published: ${it.aggregation.code}" } }
            else -> Unit
        }
        val expression = when (operation) {
            is PostOperationContract.JoinInput -> operation.condition
            is PostOperationContract.Derive -> operation.expression
            is PostOperationContract.PostFilter -> operation.expression
            else -> return
        }
        ResultExpressionTree.nodes(expression).forEach { value ->
            val kind = when (value) {
                is ResultArgument -> ExpressionAuthorization.Kind.ARGUMENT
                is ResultSymbol -> ExpressionAuthorization.Kind.SYMBOL
                is ResultLiteral -> if (value.value is BigDecimal) ExpressionAuthorization.Kind.EXACT_NUMBER else ExpressionAuthorization.Kind.LITERAL
                is ResultTypedLiteral -> ExpressionAuthorization.Kind.TYPED_LITERAL
                is ResultCall -> ExpressionAuthorization.Kind.CALL
                is ResultNativeCall -> ExpressionAuthorization.Kind.NATIVE_CALL
                is ResultOperation -> ExpressionAuthorization.Kind.OPERATION
                is ResultCaseWhen -> ExpressionAuthorization.Kind.CASE
                is ResultRow -> ExpressionAuthorization.Kind.ROW
                is ResultRowField -> ExpressionAuthorization.Kind.ROW_FIELD
                is ResultCast -> ExpressionAuthorization.Kind.CAST
                is ResultRound -> ExpressionAuthorization.Kind.ROUND
                is ResultCollectionSort -> ExpressionAuthorization.Kind.COLLECTION_SORT
                is ResultCollectionText -> ExpressionAuthorization.Kind.COLLECTION_TEXT
                is ResultNumeric -> ExpressionAuthorization.Kind.NUMERIC
                is ResultQuantityLiteral -> ExpressionAuthorization.Kind.QUANTITY_LITERAL
                is ResultWeightedContribution -> ExpressionAuthorization.Kind.WEIGHTED_CONTRIBUTION
                is ResultUniformAllocation -> ExpressionAuthorization.Kind.UNIFORM_ALLOCATION
            }
            val permission = authorization.expressions
            require(kind in permission.kinds) { "$field expression kind is not published: $kind" }
            when (value) {
                is ResultCall -> require(value.function in permission.functions) { "$field function is not published: ${value.function}" }
                is ResultOperation -> require(value.operator in permission.operators) { "$field operator is not published: ${value.operator}" }
                is ResultTypedLiteral -> require(value.valueType in permission.literalTypes) { "$field literal type is not published: ${value.valueType}" }
                is ResultCast -> require(value.targetType in permission.castTypes) { "$field cast target is not published: ${value.targetType}" }
                else -> Unit
            }
        }
    }
}
