package com.olap.prototype.warehouse.inspection

import java.time.Instant

/** 保留技术编码类型；十进制数与跨语言大整数不经二进制浮点。 */
data class WarehouseScalar(val kind: Kind, val value: String?, val truncated: Boolean) {
    enum class Kind { NULL, BOOLEAN, INTEGER, DECIMAL, TEXT, DATE, TIME, TIMESTAMP, BINARY, OTHER }
    companion object { fun nullValue(): WarehouseScalar = WarehouseScalar(Kind.NULL, null, false) }
}

data class WarehouseCell(val columnName: String, val jdbcType: Int, val typeName: String?, val value: WarehouseScalar)

class WarehouseRow(cells: List<WarehouseCell>) {
    val cells: List<WarehouseCell> = java.util.List.copyOf(cells)
    override fun equals(other: Any?): Boolean = other is WarehouseRow && cells == other.cells
    override fun hashCode(): Int = cells.hashCode()
}

class WarehousePreview(val source: WarehouseSourceMetadata, val table: WarehouseTableSummary, columns: List<WarehouseColumnMetadata>, val rowLimit: Int, rows: List<WarehouseRow>) {
    val columns: List<WarehouseColumnMetadata> = java.util.List.copyOf(columns)
    val rows: List<WarehouseRow> = java.util.List.copyOf(rows)
    override fun equals(other: Any?): Boolean = other is WarehousePreview && source == other.source && table == other.table && columns == other.columns && rowLimit == other.rowLimit && rows == other.rows
    override fun hashCode(): Int = listOf(source, table, columns, rowLimit, rows).hashCode()
}

/** 计数以十进制文本交付，不代表历史时点快照。 */
class WarehouseProfile(val source: WarehouseSourceMetadata, val table: WarehouseTableSummary, val startedAt: Instant, val completedAt: Instant, val rowCount: String, columns: List<ColumnCount>) {
    val columns: List<ColumnCount> = java.util.List.copyOf(columns)
    data class ColumnCount(val column: String, val nonNullCount: String, val nullCount: String)
    override fun equals(other: Any?): Boolean = other is WarehouseProfile && source == other.source && table == other.table && startedAt == other.startedAt && completedAt == other.completedAt && rowCount == other.rowCount && columns == other.columns
    override fun hashCode(): Int = listOf(source, table, startedAt, completedAt, rowCount, columns).hashCode()
}

/** 不携带地址、凭据或底层异常消息的安全失败。 */
class WarehouseInspectionException(val code: String, val dataSourceId: String, val sqlState: String?, val failureType: String?, safeDetail: String? = null) :
    IllegalStateException("$code: dataSourceId=$dataSourceId${sqlState?.let { ", sqlState=$it" }.orEmpty()}, failureType=$failureType${safeDetail?.let { ", $it" }.orEmpty()}")
