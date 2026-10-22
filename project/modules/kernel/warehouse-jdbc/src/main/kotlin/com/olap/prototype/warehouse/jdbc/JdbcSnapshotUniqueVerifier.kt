package com.olap.prototype.warehouse.jdbc

import com.olap.prototype.connector.JdbcSupport
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.warehouse.SourceGuaranteeException
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.contract.*
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException

/** Full grouping verifies frozen candidate keys, including uniqueness of NULL tuples. */
object JdbcSnapshotUniqueVerifier {
    private val log = LoggerFactory.getLogger(JdbcSnapshotUniqueVerifier::class.java)
    fun verify(connection: Connection, spec: JdbcConnectionSpec, table: JdbcTableConstraints.Table, columns: Map<String, JdbcColumnType>, rules: List<SnapshotUniqueKey.Rule>, control: WarehouseExecutionControl, jdbc: JdbcSupport): List<SnapshotUniqueKey> {
        val anchor = jdbc.sourceAnchor(connection, spec, control::checkCancellation)
        val metadata = try { connection.metaData } catch (failure: SQLException) { control.checkCancellation(); throw guaranteeFailure(failure) }
        val qualified = try { JdbcIdentifiers.qualified(metadata, table) } catch (failure: SQLException) { control.checkCancellation(); throw guaranteeFailure(failure) }
        val verified = rules.map { rule ->
            control.checkCancellation()
            require(columns.keys.containsAll(rule.columns)) { "unique rule references an unknown source column" }
            try {
                val identifiers = rule.columns.map { JdbcIdentifiers.quote(metadata, it) }
                connection.prepareStatement("SELECT 1 FROM $qualified GROUP BY ${identifiers.joinToString(",")} HAVING COUNT(*)>1").use { statement ->
                    control.registerCancellationAction(statement::cancel).use {
                        statement.maxRows = 1
                        statement.executeQuery().use { rows -> control.checkCancellation(); if (rows.next()) throw SourceGuaranteeException("SOURCE_SNAPSHOT_KEY_DUPLICATE") }
                    }
                }
            } catch (failure: SQLException) {
                control.checkCancellation()
                log.debug("冻结源唯一键核验失败：dataSourceId={}, key={}", spec.dataSourceId, rule.name, failure)
                throw guaranteeFailure(failure)
            }
            log.info("已完整验证冻结源唯一键：dataSourceId={}, table={}, key={}, dataSha256={}", spec.dataSourceId, table.table, rule.name, anchor.sha256)
            SnapshotUniqueKey(rule, anchor)
        }
        if (anchor != jdbc.sourceAnchor(connection, spec, control::checkCancellation)) throw SourceGuaranteeException("IMMUTABLE_SOURCE_CHANGED_DURING_VALIDATION")
        return java.util.List.copyOf(verified)
    }
    private fun guaranteeFailure(cause: SQLException) = SourceGuaranteeException("SOURCE_SNAPSHOT_KEY_VALIDATION_FAILED", cause.sqlState, cause.javaClass.name)
}
