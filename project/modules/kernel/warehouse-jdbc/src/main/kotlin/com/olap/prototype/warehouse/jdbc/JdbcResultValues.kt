package com.olap.prototype.warehouse.jdbc

import com.olap.prototype.api.SqlResultValues.ArrayValue
import com.olap.prototype.api.SqlResultValues.RowValue
import com.olap.prototype.connector.JdbcSupport
import com.olap.prototype.warehouse.WarehouseExecutionControl
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Struct
import java.sql.Types
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Materialize arrays and rows losslessly while JDBC still owns their resources. */
object JdbcResultValues {
    fun read(rows: ResultSet, index: Int, control: WarehouseExecutionControl, jdbc: JdbcSupport): Any? {
        control.checkCancellation()
        val value = if (rows.metaData.getColumnType(index) == Types.ARRAY) rows.getArray(index) else jdbc.scalar(rows, index)
        return materialize(value, control, jdbc)
    }
    private fun materialize(value: Any?, control: WarehouseExecutionControl, jdbc: JdbcSupport): Any? {
        control.checkCancellation()
        when (value) {
            is java.sql.Array -> try {
                val elements = mutableListOf<Any?>()
                value.resultSet.use { rows -> while (rows.next()) { control.checkCancellation(); elements += materialize(jdbc.arrayElement(value, rows), control, jdbc) } }
                return ArrayValue(elements)
            } finally { value.free() }
            is Struct -> return RowValue(value.attributes.map { materialize(it, control, jdbc) })
            is ResultSet -> value.use { row ->
                if (!row.next()) throw SQLException("JDBC ROW result requires exactly one tuple")
                val fields = (1..row.metaData.columnCount).map { read(row, it, control, jdbc) }
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
        return scalar
    }
}
