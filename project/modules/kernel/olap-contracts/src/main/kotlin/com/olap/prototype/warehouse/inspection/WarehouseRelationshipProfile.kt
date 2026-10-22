package com.olap.prototype.warehouse.inspection

import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.SemanticExpressionInspector
import com.olap.prototype.warehouse.contract.SourceConfiguration
import java.time.Instant

/** 精确来源上的全表表达式复合键调查；不接受SQL、执行目录或治理状态。 */
data class WarehouseRelationshipProfileRequest(
    val connection: SourceConfiguration,
    val sourceInput: Input,
    val targetInput: Input,
    val timeoutSeconds: Int?,
) {
    init {
        require(sourceInput.keyExpressions.size == targetInput.keyExpressions.size) { "两侧 keyExpressions 必须逐项配对" }
        require(timeoutSeconds == null || timeoutSeconds in 1..120) { "timeoutSeconds 必须在 1 到 120 之间" }
    }
    fun timeoutOrDefault(): Int = timeoutSeconds ?: 30

    class Input(val table: WarehouseTableRef, columns: List<Column>, keyExpressions: List<SemanticExpression>) {
        val columns: List<Column> = java.util.List.copyOf(columns)
        val keyExpressions: List<SemanticExpression> = java.util.List.copyOf(keyExpressions)
        init {
            require(this.columns.size <= 64 && this.keyExpressions.size in 1..16) { "表达式调查需要精确表、最多64个来源列及1到16个键表达式" }
            val names = hashSetOf<String>()
            val bindings = hashSetOf<String>()
            this.columns.forEach { column ->
                require(names.add(column.column) && bindings.add("${column.physicalColumnId}@${column.physicalColumnVersion}")) { "调查列名和精确物理引用必须各自唯一" }
            }
            val references = hashSetOf<String>()
            this.keyExpressions.forEach { expression ->
                SemanticExpressionInspector.references(expression).forEach { reference ->
                    require(reference.referenceKind == SemanticExpression.ReferenceKind.PHYSICAL_COLUMN) { "调查表达式只能引用已显式绑定的精确物理列，不能读取 LogicUnit 或治理状态" }
                    references.add("${reference.id}@${reference.version}")
                }
            }
            require(references == bindings) { "表达式物理来源与 columns 必须精确一致，不猜列名或忽略多余来源" }
        }
        override fun equals(other: Any?): Boolean = other is Input && table == other.table && columns == other.columns && keyExpressions == other.keyExpressions
        override fun hashCode(): Int = listOf(table, columns, keyExpressions).hashCode()
    }
    data class Column(val physicalColumnId: String, val physicalColumnVersion: Long, val column: String) {
        init { require(physicalColumnId.isNotBlank() && physicalColumnVersion > 0 && column.isNotBlank()) { "调查列需要精确身份、正整数版本和真实列名" } }
    }
}

/** 一条统计语句的实际全表观察，不证明业务外键、历史完整性或持续唯一。 */
data class WarehouseRelationshipProfile(
    val source: WarehouseSourceMetadata,
    val sourceInput: WarehouseRelationshipProfileRequest.Input,
    val targetInput: WarehouseRelationshipProfileRequest.Input,
    val startedAt: Instant,
    val completedAt: Instant,
    val equalitySemantics: String,
    val sourceToTarget: Direction,
    val targetToSource: Direction,
) {
    /** NULL键计入未匹配，重复键排除含NULL的键；计数保留无损十进制整数文本。 */
    data class Direction(
        val rowCount: String,
        val nullKeyRowCount: String,
        val nonNullKeyGroupCount: String,
        val duplicateNonNullKeyGroupCount: String,
        val rowsWithDuplicatedNonNullKeys: String,
        val unmatchedRowCount: String,
        val singleMatchRowCount: String,
        val multipleMatchRowCount: String,
        val matchedPairCount: String,
        val maximumMatchesPerRow: String,
    )
}
