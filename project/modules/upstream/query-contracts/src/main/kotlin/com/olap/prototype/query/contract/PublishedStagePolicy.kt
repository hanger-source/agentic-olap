package com.olap.prototype.query.contract

import com.olap.prototype.query.PostOperationContract
import com.olap.prototype.query.ResultExpression
import com.olap.prototype.query.ResultExpression.*
import java.math.BigDecimal

/** 核对精确发布批准的能力；全局语法支持不扩大冻结授权。 */
object PublishedStagePolicy {
    fun require(operation: PostOperationContract.ValidatedOperation, publishedArguments: Map<String, Any?>, field: String) {
        when (operation) {
            is PostOperationContract.JoinInput -> requirePublishedValue(publishedArguments, "joinTypes", operation.joinType, "$field.arguments.joinType")
            is PostOperationContract.AggregateOutput -> requirePublishedValue(publishedArguments, "aggregations", operation.aggregation.code, "$field.arguments.aggregation")
            is PostOperationContract.ObserveWindow -> requirePublishedValue(publishedArguments, "aggregations", operation.aggregation.code, "$field.arguments.aggregation")
            is PostOperationContract.Regroup -> {
                val contract = publishedArguments["aggregates"] as? Map<*, *> ?: throw IllegalArgumentException("$field requires a published aggregates contract")
                operation.grouping.aggregates.forEach { requirePublishedValue(contract, "aggregations", it.aggregation.code, "$field.arguments.aggregates.aggregation") }
            }
            else -> kotlin.Unit
        }
        val expression = when (operation) {
            is PostOperationContract.JoinInput -> operation.condition
            is PostOperationContract.Derive -> operation.expression
            is PostOperationContract.PostFilter -> operation.expression
            else -> return
        }
        val contract = publishedArguments["expressionContract"] as? Map<*, *> ?: throw IllegalArgumentException("$field requires a published expressionContract")
        validateExpression(expression, contract, "$field.arguments.${if (operation is PostOperationContract.JoinInput) "condition" else "expression"}")
    }
    private fun validateExpression(expression: ResultExpression, contract: Map<*, *>, field: String) {
        val kind = when (expression) {
            is ResultCall -> "CALL"
            is ResultSymbol -> "SYMBOL"
            is ResultLiteral -> if (expression.value is BigDecimal) "EXACT_NUMBER" else "LITERAL"
            is ResultTypedLiteral -> "TYPED_LITERAL"
            is ResultOperation -> "OPERATION"
            is ResultCaseWhen -> "CASE"
            is ResultRow -> "ROW"
            is ResultRowField -> "ROW_FIELD"
            is ResultCast -> "CAST"
            is ResultRound -> "ROUND"
            is ResultCollectionSort -> "COLLECTION_SORT"
            is ResultCollectionText -> "COLLECTION_TEXT"
            is ResultNumeric -> "NUMERIC"
            is ResultQuantityLiteral -> "QUANTITY_LITERAL"
            is ResultWeightedContribution -> "WEIGHTED_CONTRIBUTION"
            is ResultUniformAllocation -> "UNIFORM_ALLOCATION"
            is ResultArgument -> throw IllegalArgumentException("$field has an unsupported expression kind")
        }
        requirePublishedValue(contract, "expressionKinds", kind, "$field.kind")
        when (expression) {
            is ResultCall -> {
                val call = contract["call"] as? Map<*, *> ?: throw IllegalArgumentException("$field requires a published call contract")
                val functions = call["functions"] as? List<*> ?: throw IllegalArgumentException("$field requires published function declarations")
                require(functions.all { it is Map<*, *> && it["function"] is String }) { "$field requires published function declarations" }
                require(functions.any { (it as Map<*, *>)["function"] == expression.function.name }) { "$field.function is not in published functions: ${expression.function.name}" }
                expression.arguments.forEachIndexed { index, value -> validateExpression(value, contract, "$field.arguments[$index]") }
            }
            is ResultTypedLiteral -> {
                val literal = contract["typedLiteral"] as? Map<*, *> ?: throw IllegalArgumentException("$field requires a published typedLiteral contract")
                requirePublishedValue(literal, "valueTypes", expression.valueType.name, "$field.valueType")
            }
            is ResultCast -> {
                val cast = contract["cast"] as? Map<*, *> ?: throw IllegalArgumentException("$field requires a published cast contract")
                requirePublishedValue(cast, "targetTypes", expression.targetType.name, "$field.targetType")
                validateExpression(expression.expression, contract, "$field.expression")
            }
            is ResultRound -> validateExpression(expression.expression, contract, "$field.expression")
            is ResultRowField -> validateExpression(expression.expression, contract, "$field.expression")
            is ResultCollectionSort -> validateExpression(expression.expression, contract, "$field.expression")
            is ResultCollectionText -> validateExpression(expression.expression, contract, "$field.expression")
            is ResultNumeric -> validateExpression(expression.expression, contract, "$field.expression")
            is ResultWeightedContribution -> { validateExpression(expression.value, contract, "$field.value"); validateExpression(expression.weight, contract, "$field.weight") }
            is ResultUniformAllocation -> validateExpression(expression.value, contract, "$field.value")
            is ResultRow -> expression.operands.forEachIndexed { index, value -> validateExpression(value, contract, "$field.operands[$index]") }
            is ResultOperation -> {
                requirePublishedValue(contract, "operators", expression.operator.name, "$field.operator")
                expression.operands.forEachIndexed { index, value -> validateExpression(value, contract, "$field.operands[$index]") }
            }
            is ResultCaseWhen -> {
                expression.branches.forEachIndexed { index, branch -> validateExpression(branch.`when`, contract, "$field.branches[$index].when"); validateExpression(branch.then, contract, "$field.branches[$index].then") }
                validateExpression(expression.otherwise, contract, "$field.otherwise")
            }
            is ResultSymbol, is ResultLiteral, is ResultQuantityLiteral, is ResultArgument -> kotlin.Unit
        }
    }
    private fun requirePublishedValue(contract: Map<*, *>, key: String, value: String, field: String) {
        val allowed = contract[key] as? List<*> ?: throw IllegalArgumentException("$field requires a published $key array")
        require(allowed.all { it is String }) { "$field requires a published $key array" }
        require(value in allowed) { "$field is not in published $key: $value" }
    }
}
