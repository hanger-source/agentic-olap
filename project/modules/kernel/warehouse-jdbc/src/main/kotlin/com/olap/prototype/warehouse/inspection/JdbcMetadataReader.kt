package com.olap.prototype.warehouse.inspection

import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.jdbc.JdbcIdentifiers
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException

/** A connection-local catalog reader; pattern discovery never substitutes for exact adoption. */
class JdbcMetadataReader(private val metadata: DatabaseMetaData, private val control: WarehouseExecutionControl) {
    class Tables(tables: List<WarehouseTableSummary>, val truncated: Boolean) {
        val tables: List<WarehouseTableSummary> = java.util.List.copyOf(tables)
        override fun equals(other: Any?): Boolean = other is Tables && tables == other.tables && truncated == other.truncated
        override fun hashCode(): Int = 31 * tables.hashCode() + truncated.hashCode()
    }
    fun discover(catalog: String?, schemaPattern: String?, tablePattern: String?, tableTypes: List<String>, limit: Int): Tables {
        control.checkCancellation()
        val tables = mutableListOf<WarehouseTableSummary>()
        var truncated = false
        metadata.getTables(catalog, schemaPattern, tablePattern, tableTypes.toTypedArray()).use { rows -> while (rows.next()) {
            control.checkCancellation()
            if (tables.size == limit) { truncated = true; break }
            tables += summary(rows)
        } }
        return Tables(tables.sortedWith(compareBy({ it.ref.catalog.orEmpty() }, { it.ref.schema.orEmpty() }, { it.ref.table })), truncated)
    }
    fun exactTable(requested: WarehouseTableRef): WarehouseTableSummary {
        control.checkCancellation()
        val matches = mutableListOf<WarehouseTableSummary>()
        metadata.getTables(requested.catalog, JdbcIdentifiers.literalPattern(metadata, requested.schema), JdbcIdentifiers.literalPattern(metadata, requested.table), null).use { rows ->
            while (rows.next()) { control.checkCancellation(); val candidate = summary(rows); if (candidate.ref == requested) matches += candidate }
        }
        require(matches.size == 1) { "数据源必须返回唯一精确表引用: $requested" }
        return matches.single()
    }
    fun columns(table: WarehouseTableRef): List<WarehouseColumnMetadata> {
        control.checkCancellation()
        val columns = mutableListOf<WarehouseColumnMetadata>()
        metadata.getColumns(table.catalog, JdbcIdentifiers.literalPattern(metadata, table.schema), JdbcIdentifiers.literalPattern(metadata, table.table), null).use { rows -> while (rows.next()) {
            control.checkCancellation()
            if (rows.getString("TABLE_CAT") != table.catalog || rows.getString("TABLE_SCHEM") != table.schema || rows.getString("TABLE_NAME") != table.table) continue
            val nullable = nullability(rows) ?: throw IllegalArgumentException("数据源未提供精确字段可空性: $table")
            columns += WarehouseColumnMetadata(rows.getString("COLUMN_NAME"), rows.getInt("DATA_TYPE"), rows.getString("TYPE_NAME"), rows.getInt("ORDINAL_POSITION"), nullable,
                nullableInt(rows, "COLUMN_SIZE"), nullableInt(rows, "DECIMAL_DIGITS"), yesNo(rows, "IS_AUTOINCREMENT"), yesNo(rows, "IS_GENERATEDCOLUMN"), rows.getString("COLUMN_DEF"), rows.getString("REMARKS"))
        } }
        require(columns.isNotEmpty()) { "表没有可发现的字段: $table" }
        return java.util.List.copyOf(columns.sortedBy { it.ordinalPosition })
    }
    private fun summary(row: ResultSet): WarehouseTableSummary = WarehouseTableSummary(WarehouseTableRef(row.getString("TABLE_CAT"), row.getString("TABLE_SCHEM"), row.getString("TABLE_NAME")), row.getString("TABLE_TYPE"), row.getString("REMARKS"))
    companion object {
        fun nullability(row: ResultSet): Boolean? {
            val value = row.getInt("NULLABLE")
            if (row.wasNull() || value == DatabaseMetaData.columnNullableUnknown) return null
            return when (value) { DatabaseMetaData.columnNullable -> true; DatabaseMetaData.columnNoNulls -> false; else -> throw SQLException("invalid JDBC column nullability metadata") }
        }
        fun nullableInt(row: ResultSet, field: String): Int? = row.getInt(field).let { if (row.wasNull()) null else it }
        private fun yesNo(row: ResultSet, field: String): Boolean? {
            val metadata = try { row.metaData } catch (unsupported: SQLFeatureNotSupportedException) { return null }
            if ((1..metadata.columnCount).none { metadata.getColumnLabel(it).equals(field, ignoreCase = true) }) return null
            val value = try { row.getString(field) } catch (unsupported: SQLFeatureNotSupportedException) { return null }
            if (value.isNullOrBlank()) return null
            return when { value.equals("YES", ignoreCase = true) -> true; value.equals("NO", ignoreCase = true) -> false; else -> throw SQLException("invalid JDBC yes/no metadata: $field") }
        }
    }
}
