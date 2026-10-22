package com.olap.prototype.management.governance

import com.olap.prototype.storage.PlatformDatabase
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/** 元数据 SQL 的绑定与资源作用域；调用方仍决定领域失败和事务边界。 */
internal object GovernanceJdbcSupport {
    fun setLong(statement: PreparedStatement, index: Int, value: Long?) = statement.setObject(index, value)
    fun setInteger(statement: PreparedStatement, index: Int, value: Int?) = statement.setObject(index, value)
    fun setBoolean(statement: PreparedStatement, index: Int, value: Boolean?) = statement.setObject(index, value)
    fun timestamp(value: Instant?): Timestamp? = value?.let(Timestamp::from)
    fun nullableLong(rows: ResultSet, column: String): Long? = rows.getLong(column).let { if (rows.wasNull()) null else it }
    fun nullableBoolean(rows: ResultSet, column: String): Boolean? = rows.getBoolean(column).let { if (rows.wasNull()) null else it }
    fun nullableInteger(rows: ResultSet, column: String): Int? = rows.getInt(column).let { if (rows.wasNull()) null else it }
    fun instant(rows: ResultSet, column: String): Instant? = rows.getTimestamp(column)?.toInstant()

    private fun bind(statement: PreparedStatement, values: List<Any?>) {
        values.forEachIndexed { index, value -> statement.setObject(index + 1, if (value is Instant) timestamp(value) else value) }
    }
    fun execute(database: PlatformDatabase, sql: String, values: List<Any?> = emptyList()): Int = database.connection().use { connection ->
        connection.prepareStatement(sql).use { statement -> bind(statement, values); statement.executeUpdate() }
    }
    fun insert(database: PlatformDatabase, table: String, fields: Map<String, Any?>) {
        require(table.matches(Regex("[a-z_]+")) && fields.isNotEmpty() && fields.keys.all { it.matches(Regex("[a-z_]+")) }) { "metadata insert requires explicit SQL identifiers" }
        execute(database, "INSERT INTO $table (${fields.keys.joinToString(", ")}) VALUES (${fields.keys.joinToString(", ") { "?" }})", fields.values.toList())
    }
    fun <T> query(database: PlatformDatabase, sql: String, values: List<Any?> = emptyList(), read: (ResultSet) -> T): List<T> = database.connection().use { connection ->
        connection.prepareStatement(sql).use { statement ->
            bind(statement, values)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
        }
    }
}
