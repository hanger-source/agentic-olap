package com.olap.prototype.engine.executable

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.olap.prototype.api.ResultValueType
import com.olap.prototype.expression.SemanticNativeTypes
import com.olap.prototype.query.QueryStageAnalysis
import com.olap.prototype.query.QueryValue
import org.apache.calcite.rel.type.RelDataType

/** 原生编译完成的输出身份、来源与类型；执行阶段只核验和物化。 */
data class ExecutableOutputContract(val columns: List<Field>, val scopeInputs: List<ScopeInput>, val sourceSymbols: List<QueryStageAnalysis.Symbol>,
    val outputScope: QueryStageAnalysis.ScopeId) {
    @get:JsonProperty("formatVersion") val formatVersion: String get() = "olap-output-contract/v9"
    data class ScopeInput(val scope: QueryStageAnalysis.ScopeId, val fields: List<Field>)
    data class Field(val symbol: QueryStageAnalysis.Symbol, @get:JsonIgnore val nativeType: RelDataType) {
        @get:JsonProperty("nativeTypeDescription") val nativeTypeDescription: String get() = nativeType.fullTypeString
        @get:JsonProperty("type") val type: ResultValueType get() = SemanticNativeTypes.resultType(nativeType)
    }
    init {
        require(scopeInputs.map { it.scope }.distinct().size == scopeInputs.size) { "compiled input scopes must be unique" }
        require(scopeInputs.any { it.scope == outputScope }) { "compiled output scope must have its actual input contract" }
        val symbols = linkedMapOf<QueryStageAnalysis.SymbolId, QueryStageAnalysis.Symbol>()
        sourceSymbols.forEach { require(symbols.putIfAbsent(it.id, it) == null) { "compiled source symbols must have unique identities" } }
        sourceSymbols.forEach { symbol -> require(symbol.dependencies.all(symbols::containsKey)) { "compiled symbol dependency is absent" }; QueryValue.requirePositions(symbol.value, symbols) }
        require(columns.all { it.symbol == symbols[it.symbol.id] }) { "compiled output symbol differs from its source table" }
    }
    companion object {
        fun compiled(analyses: Map<QueryStageAnalysis.ScopeId, QueryStageAnalysis.Analysis>, outputType: RelDataType,
            inputTypes: Map<QueryStageAnalysis.ScopeId, RelDataType>, outputScope: QueryStageAnalysis.ScopeId = QueryStageAnalysis.ScopeId.root()): ExecutableOutputContract {
            val inputs = analyses.map { (scope, analysis) -> ScopeInput(scope, fields(analysis.inputs, requireNotNull(inputTypes[scope]) { "compiled scope input type is required" })) }
            val symbols = QueryStageAnalysis.symbols(analyses.values)
            return ExecutableOutputContract(fields(analyses.getValue(outputScope).outputs, outputType), inputs, symbols.values.toList(), outputScope)
        }
        private fun fields(symbols: List<QueryStageAnalysis.Symbol>, type: RelDataType): List<Field> {
            check(symbols.map { it.name } == type.fieldNames) { "native relation output differs from analyzed symbols or order" }
            return symbols.mapIndexed { index, symbol -> Field(symbol, type.fieldList[index].type) }
        }
        internal fun column(id: String, field: Field, display: String?): ExecutableBusinessResult.Column = ExecutableBusinessResult.Column(id, field.symbol, field.type, display)
    }
}
