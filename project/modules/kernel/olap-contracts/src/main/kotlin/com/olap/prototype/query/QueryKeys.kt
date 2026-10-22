package com.olap.prototype.query

import com.olap.prototype.query.QueryStageAnalysis.Analysis
import com.olap.prototype.query.QueryStageAnalysis.Key
import com.olap.prototype.query.QueryStageAnalysis.Symbol
import com.olap.prototype.query.QueryStageAnalysis.SymbolId
import com.olap.prototype.query.QueryStageAnalysis.UniqueKey
import com.olap.prototype.query.QueryStageAnalysis.UnknownKey

/** 真实关系键在关联和保留列上的推导，不持有词法表或阶段状态。 */
internal object QueryKeys {
    fun joinedKey(left: Key, right: Analysis, join: PostOperationContract.JoinInput, leftNames: Set<String>, imported: List<Symbol>): Key {
        if (join.joinType !in setOf("INNER", "LEFT") || left !is UniqueKey || right.key !is UniqueKey) return UnknownKey.INSTANCE
        if (rightSingleMatch(right, join, leftNames)) return left
        val rightKey = right.key
        val combined = left.columns.toMutableSet()
        val importedByName = imported.associateBy { it.name }
        val retainedRight = linkedSetOf<SymbolId>()
        right.outputs.filter { it.id in rightKey.columns }.forEach { column ->
            retainedRight.add(column.id)
            combined.add(requireNotNull(importedByName[ScopedInputContract.metricSymbol(join.occurrenceId, column.name)]) { "joined right identity is not imported" }.id)
        }
        require(retainedRight == rightKey.columns) { "joined right identity is not retained in the completed output" }
        return UniqueKey(combined)
    }
    fun rightSingleMatch(right: Analysis, join: PostOperationContract.JoinInput, leftNames: Set<String>): Boolean {
        if (join.joinType !in setOf("INNER", "LEFT")) return false
        val unique = right.key as? UniqueKey ?: return false
        val rightKeys = right.outputs.filter { it.id in unique.columns }.associate { ScopedInputContract.metricSymbol(join.occurrenceId, it.name) to it.id }
        val matched = hashSetOf<SymbolId>()
        requiredJoinEqualities(join.condition, leftNames, rightKeys, matched)
        return matched.containsAll(unique.columns)
    }
    fun leftSingleMatch(left: Key, symbols: Map<String, Symbol>, right: Analysis, join: PostOperationContract.JoinInput): Boolean {
        if (join.joinType != "INNER" || left !is UniqueKey) return false
        val leftKeys = symbols.filterValues { it.id in left.columns }.mapValues { it.value.id }
        val rightNames = right.outputs.map { ScopedInputContract.metricSymbol(join.occurrenceId, it.name) }.toSet()
        val matched = hashSetOf<SymbolId>()
        requiredJoinEqualities(join.condition, rightNames, leftKeys, matched)
        return matched.containsAll(left.columns)
    }
    fun requiredJoinEqualities(expression: ResultExpression, leftNames: Set<String>, rightKeys: Map<String, SymbolId>, matched: MutableSet<SymbolId>) {
        if (expression !is ResultExpression.ResultOperation) return
        if (expression.operator == ResultExpression.ResultOperator.AND) expression.operands.forEach { requiredJoinEqualities(it, leftNames, rightKeys, matched) }
        else if (expression.operator == ResultExpression.ResultOperator.EQ) {
            val first = expression.operands[0] as? ResultExpression.ResultSymbol
            val second = expression.operands[1] as? ResultExpression.ResultSymbol
            if (first != null && second != null) {
                if (first.symbol in leftNames) rightKeys[second.symbol]?.let { matched.add(it) }
                if (second.symbol in leftNames) rightKeys[first.symbol]?.let { matched.add(it) }
            }
        }
    }
    fun totalOrder(key: Key, symbols: Map<String, Symbol>, partition: List<String>, order: List<String>): Boolean =
        key is UniqueKey && (partition + order).map { require(symbols, it).id }.toSet().containsAll(key.columns)
    fun singleObservation(key: Key, symbols: Map<String, Symbol>, retained: List<String>): Boolean = totalOrder(key, symbols, retained, emptyList())
    fun key(names: List<String>, symbols: Map<String, Symbol>): Key {
        val result = names.map { require(symbols, it).id }
        require(result.distinct().size == result.size) { "key has duplicate symbols" }
        return UniqueKey(result.toSet())
    }
    private fun require(symbols: Map<String, Symbol>, name: String): Symbol = requireNotNull(symbols[name]) { "symbol is outside the current lexical scope: $name" }
}
