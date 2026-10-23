package com.olap.connectors.h2

import com.olap.prototype.contract.JdbcSourceRequirement
import com.olap.prototype.connector.SourceCapabilities
import com.olap.prototype.warehouse.SourceGuaranteeException
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.contract.*
import com.olap.prototype.warehouse.jdbc.JdbcIdentifiers
import com.olap.prototype.warehouse.jdbc.StandardJdbcConstraints
import com.olap.prototype.warehouse.jdbc.StandardJdbcSupport
import java.sql.Connection
import java.sql.DriverManager

internal class H2JdbcSupport : StandardJdbcSupport("org.h2.Driver") {
    override fun capabilities(source: SourceConfiguration): SourceCapabilities = SourceCapabilities(setOf(
        SourceCapabilities.Capability.PARAMETER_BINDING, SourceCapabilities.Capability.CANCELLATION,
        SourceCapabilities.Capability.TRANSACTION_SNAPSHOT, SourceCapabilities.Capability.CONSISTENT_READ,
        SourceCapabilities.Capability.STRUCTURE_INSPECTION, SourceCapabilities.Capability.DATA_PREVIEW,
        SourceCapabilities.Capability.DATA_PROFILING, SourceCapabilities.Capability.RELATIONSHIP_PROFILING,
        // 同读基础的数据见证可以核验 NOT_NULL；catalog 的 PK/UK 等仍不作快照保证。
        SourceCapabilities.Capability.CONSTRAINT_VERIFICATION))
    override fun close() {
        markClosed()
        val drivers = DriverManager.getDrivers()
        while (drivers.hasMoreElements()) drivers.nextElement().let { if (it.javaClass.classLoader == javaClass.classLoader) DriverManager.deregisterDriver(it) }
    }
    override fun supportsColumnInspection(evidence: PhysicalSourceConstraints): Boolean = evidence.databaseProductName == "H2" &&
        evidence.databaseProductVersion == "2.2.224 (2023-09-17)" && evidence.driverName == "H2 JDBC Driver" && evidence.driverVersion == "2.2.224 (2023-09-17)"
    override fun supportsConstraintSnapshot(evidence: PhysicalSourceConstraints): Boolean = false
    override fun constraints(connection: Connection, table: JdbcTableConstraints.Table, control: WarehouseExecutionControl): JdbcTableConstraints =
        StandardJdbcConstraints.read(connection, StandardJdbcConstraints.uniqueKeys(connection.metaData, table, control), table, control)
    override fun configureMemory(connection: Connection, bytes: Long, control: WarehouseExecutionControl) {
        require(bytes > 0) { "explicit positive native memory budget is required" }
        control.checkCancellation()
    }
    override fun beginSnapshot(connection: Connection, sources: List<JdbcSourceRequirement>, control: WarehouseExecutionControl) {
        connection.transactionIsolation = Connection.TRANSACTION_SERIALIZABLE
        connection.autoCommit = false
        connection.prepareStatement("SELECT 1 FROM ${JdbcIdentifiers.qualified(connection.metaData, sources.first().table)}").use { statement ->
            control.registerCancellationAction(statement::cancel).use {
                statement.maxRows = 1
                statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) }
            }
        }
    }
    override fun sourceAnchor(connection: Connection, spec: JdbcConnectionSpec, checkCancellation: () -> Unit): JdbcTableConstraints.DataAnchor =
        throw SourceGuaranteeException("IMMUTABLE_SOURCE_UNSUPPORTED")
}
