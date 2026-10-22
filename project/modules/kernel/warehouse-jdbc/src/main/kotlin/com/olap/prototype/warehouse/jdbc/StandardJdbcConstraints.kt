package com.olap.prototype.warehouse.jdbc

import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.contract.JdbcTableConstraints
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.SQLException
import java.util.TreeMap

/** JDBC metadata facts shared by inspection and execution; no business cardinality inference. */
object StandardJdbcConstraints {
    fun read(connection: Connection, unique: List<JdbcTableConstraints.UniqueKey>, table: JdbcTableConstraints.Table, control: WarehouseExecutionControl): JdbcTableConstraints {
        control.checkCancellation()
        val metadata = connection.metaData
        val primary = TreeMap<Int, String>()
        metadata.getPrimaryKeys(table.catalog, table.schema, table.table).use { rows -> while (rows.next()) {
            control.checkCancellation()
            if (primary.put(rows.getInt("KEY_SEQ"), rows.getString("COLUMN_NAME")) != null) throw SQLException("duplicate primary key sequence")
        } }
        val foreign = mutableListOf<JdbcTableConstraints.ForeignKeyColumn>()
        metadata.getImportedKeys(table.catalog, table.schema, table.table).use { rows -> while (rows.next()) {
            control.checkCancellation()
            foreign += JdbcTableConstraints.ForeignKeyColumn(rows.getString("FK_NAME"), rows.getString("FKCOLUMN_NAME"), JdbcTableConstraints.Table(rows.getString("PKTABLE_CAT"), rows.getString("PKTABLE_SCHEM"), rows.getString("PKTABLE_NAME")),
                rows.getString("PKCOLUMN_NAME"), rows.getShort("UPDATE_RULE"), rows.getShort("DELETE_RULE"), rows.getInt("KEY_SEQ"))
        } }
        foreign.sortWith(compareBy({ it.name.orEmpty() }, { it.referencedTable.toString() }, { it.keySequence }, { it.column }))
        return JdbcTableConstraints(primary.values.toList(), unique, foreign, emptyList())
    }
    fun uniqueKeys(metadata: DatabaseMetaData, table: JdbcTableConstraints.Table, control: WarehouseExecutionControl): List<JdbcTableConstraints.UniqueKey> {
        val indexes = TreeMap<String, TreeMap<Int, String>>()
        val filters = linkedMapOf<String, String?>()
        metadata.getIndexInfo(table.catalog, table.schema, table.table, true, false).use { rows -> while (rows.next()) {
            control.checkCancellation()
            if (rows.getShort("TYPE").toInt() == DatabaseMetaData.tableIndexStatistic.toInt() || rows.getBoolean("NON_UNIQUE")) continue
            val name = rows.getString("INDEX_NAME") ?: throw SQLException("unique index lacks exact name or column metadata")
            val column = rows.getString("COLUMN_NAME") ?: throw SQLException("unique index lacks exact name or column metadata")
            if (indexes.getOrPut(name) { TreeMap() }.put(rows.getInt("ORDINAL_POSITION"), column) != null) throw SQLException("duplicate unique index sequence")
            val filter = rows.getString("FILTER_CONDITION")
            if (filters.containsKey(name) && filters[name] != filter) throw SQLException("inconsistent unique index filter")
            filters[name] = filter
        } }
        return indexes.map { (name, columns) -> JdbcTableConstraints.UniqueKey(name, columns.values.toList(), filters[name], null) }
    }
}
