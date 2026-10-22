package com.olap.prototype.warehouse.jdbc

import com.olap.prototype.api.SqlResultValues.ArrayValue
import com.olap.prototype.api.SqlResultValues.RowValue
import com.olap.prototype.connector.JdbcSupport
import com.olap.prototype.warehouse.ResultMaterializationBudget
import com.olap.prototype.warehouse.WarehouseExecutionControl
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Struct
import java.sql.Types
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Materialize arrays and rows losslessly while JDBC still owns their resources. */
object JdbcResultValues {
    fun read(rows: ResultSet, index: Int, control: WarehouseExecutionControl, budget: ResultMaterializationBudget, jdbc: JdbcSupport): Any? = read(rows, index, control, budget, jdbc, 1)
    private fun read(rows: ResultSet, index: Int, control: WarehouseExecutionControl, budget: ResultMaterializationBudget, jdbc: JdbcSupport, depth: Int): Any? {
        control.checkCancellation(); budget.value(depth)
        val value = if (rows.metaData.getColumnType(index) == Types.ARRAY) rows.getArray(index) else jdbc.scalar(rows, index)
        return materialize(value, control, budget, jdbc, depth)
    }
    private fun materialize(value: Any?, control: WarehouseExecutionControl, budget: ResultMaterializationBudget, jdbc: JdbcSupport, depth: Int): Any? {
        control.checkCancellation()
        when (value) {
            is java.sql.Array -> try {
                val elements = mutableListOf<Any?>()
                value.resultSet.use { rows -> while (rows.next()) { control.checkCancellation(); budget.value(depth + 1); elements += materialize(jdbc.arrayElement(value, rows), control, budget, jdbc, depth + 1) } }
                return ArrayValue(elements)
            } finally { value.free() }
            is Struct -> return RowValue(value.attributes.map { budget.value(depth + 1); materialize(it, control, budget, jdbc, depth + 1) })
            is ResultSet -> value.use { row ->
                if (!row.next()) throw SQLException("JDBC ROW result requires exactly one tuple")
                val fields = (1..row.metaData.columnCount).map { read(row, it, control, budget, jdbc, depth + 1) }
                if (row.next()) throw SQLException("JDBC ROW result contains more than one tuple")
                return RowValue(fields)
            }
        }
        val scalar = when (value) {
            is java.sql.Date -> value.toLocalDate()
            is java.sql.Timestamp -> value.toLocalDateTime()
            is OffsetDateTime -> value.withOffsetSameInstant(ZoneOffset.UTC)
            else -> value
        }
        budget.scalar(scalar)
        return scalar
    }
}
