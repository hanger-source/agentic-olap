package com.olap.prototype.warehouse.jdbc

import java.sql.ResultSet
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Local date/wall time use JDBC 4.2; offset timestamps normalize the native instant to UTC. */
object JdbcScalarValues {
    fun read(rows: ResultSet, index: Int): Any? = when (rows.metaData.getColumnType(index)) {
        Types.DATE -> rows.getObject(index, LocalDate::class.java)
        Types.TIMESTAMP -> rows.getObject(index, LocalDateTime::class.java)
        Types.TIMESTAMP_WITH_TIMEZONE -> rows.getTimestamp(index)?.toInstant()?.atOffset(ZoneOffset.UTC)
        else -> rows.getObject(index)
    }
}
