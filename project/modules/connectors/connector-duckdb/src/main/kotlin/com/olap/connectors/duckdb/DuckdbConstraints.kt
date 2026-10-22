package com.olap.connectors.duckdb

import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.contract.JdbcTableConstraints
import java.sql.Connection
import java.sql.PreparedStatement

internal object DuckdbConstraints {
    fun read(connection: Connection, table: JdbcTableConstraints.Table, control: WarehouseExecutionControl): List<JdbcTableConstraints.UniqueKey> = buildList {
        // JDBC 1.5.6.0 omits real UNIQUE in getIndexInfo. These are native metadata functions, not parsed SQL.
        connection.prepareStatement("SELECT constraint_name,constraint_column_names FROM duckdb_constraints() WHERE database_name=? AND schema_name=? AND table_name=? AND constraint_type IN ('PRIMARY KEY','UNIQUE') ORDER BY constraint_name").use { statement ->
            control.registerCancellationAction(statement::cancel).use {
                bind(statement, table)
                statement.executeQuery().use { rows -> while (rows.next()) {
                    control.checkCancellation()
                    val array = rows.getArray(2)
                    try { add(JdbcTableConstraints.UniqueKey(rows.getString(1), (array.array as Array<*>).map { it as String }, null, null)) } finally { array.free() }
                } }
            }
        }
        connection.prepareStatement("SELECT index_name,expressions FROM duckdb_indexes() WHERE database_name=? AND schema_name=? AND table_name=? AND is_unique ORDER BY index_name").use { statement ->
            control.registerCancellationAction(statement::cancel).use {
                bind(statement, table)
                statement.executeQuery().use { rows -> while (rows.next()) { control.checkCancellation(); add(JdbcTableConstraints.UniqueKey(rows.getString(1), emptyList(), null, rows.getString(2))) } }
            }
        }
    }
    private fun bind(statement: PreparedStatement, table: JdbcTableConstraints.Table) {
        statement.setString(1, requireNotNull(table.catalog) { "DuckDB constraint lookup requires exact catalog" }); statement.setString(2, table.schema); statement.setString(3, table.table)
    }
}
