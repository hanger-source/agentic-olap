package com.olap.prototype.api

import com.olap.prototype.contract.JdbcColumnType

data class ResultColumn(val name: String, val displayName: String?, val physicalName: String, val type: String, val jdbcType: JdbcColumnType?)
data class ResultCell(val column: String, val value: Any?)

class ResultRow(val index: Int, cells: List<ResultCell>) {
    val cells: List<ResultCell> = java.util.List.copyOf(cells)
    operator fun get(column: String): Any? = cells.firstOrNull { it.column == column }?.value
    override fun equals(other: Any?): Boolean = other is ResultRow && index == other.index && cells == other.cells
    override fun hashCode(): Int = listOf(index, cells).hashCode()
}

class ResultFrame(columns: List<ResultColumn>, rows: List<ResultRow>) {
    val columns: List<ResultColumn> = java.util.List.copyOf(columns)
    val rows: List<ResultRow> = java.util.List.copyOf(rows)
    override fun equals(other: Any?): Boolean = other is ResultFrame && columns == other.columns && rows == other.rows
    override fun hashCode(): Int = listOf(columns, rows).hashCode()
}
