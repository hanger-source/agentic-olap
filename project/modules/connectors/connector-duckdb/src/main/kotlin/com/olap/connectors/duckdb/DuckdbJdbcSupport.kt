package com.olap.connectors.duckdb

import com.olap.prototype.contract.JdbcSourceRequirement
import com.olap.prototype.connector.SourceCapabilities
import com.olap.prototype.warehouse.SourceGuaranteeException
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.contract.*
import com.olap.prototype.warehouse.jdbc.StandardJdbcConstraints
import com.olap.prototype.warehouse.jdbc.StandardJdbcSupport
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.HexFormat
import java.util.Properties

internal class DuckdbJdbcSupport : StandardJdbcSupport("org.duckdb.DuckDBDriver") {
    override fun capabilities(source: SourceConfiguration): SourceCapabilities = SourceCapabilities(setOf(
        SourceCapabilities.Capability.PARAMETER_BINDING, SourceCapabilities.Capability.CANCELLATION, SourceCapabilities.Capability.CONSISTENT_READ,
        SourceCapabilities.Capability.TRANSACTION_SNAPSHOT, SourceCapabilities.Capability.STRUCTURE_INSPECTION, SourceCapabilities.Capability.DATA_PROFILING,
        SourceCapabilities.Capability.RELATIONSHIP_PROFILING, SourceCapabilities.Capability.DATA_PREVIEW, SourceCapabilities.Capability.CONSTRAINT_VERIFICATION,
        SourceCapabilities.Capability.SEALED_DATASET_VERIFICATION, SourceCapabilities.Capability.PINNED_READ))
    override fun close() {
        markClosed()
        val drivers = DriverManager.getDrivers()
        while (drivers.hasMoreElements()) drivers.nextElement().let { if (it.javaClass.classLoader == javaClass.classLoader) DriverManager.deregisterDriver(it) }
    }
    override fun arrayElement(array: java.sql.Array, elements: ResultSet): Any? = elements.getObject(2)
    override fun openReadOnly(spec: JdbcConnectionSpec, credentials: Properties): Connection {
        requireOpen()
        credentials.setProperty("duckdb.read_only", "true")
        val temporaryRoot = System.getProperty("java.io.tmpdir")
        require(!temporaryRoot.isNullOrBlank() && Path.of(temporaryRoot).isAbsolute) { "absolute JVM temporary directory is required for DuckDB" }
        val key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(spec.jdbcUrl.toByteArray(StandardCharsets.UTF_8)))
        val directory = Path.of(temporaryRoot).toRealPath().resolve("olap-duckdb-${ProcessHandle.current().pid()}-$key")
        Files.createDirectories(directory)
        credentials.setProperty("temp_directory", directory.toString())
        return super.openReadOnly(spec, credentials)
    }
    override fun supportsColumnInspection(evidence: PhysicalSourceEvidence): Boolean = exactDriver(evidence)
    override fun supportsConstraintSnapshot(evidence: PhysicalSourceEvidence): Boolean = exactDriver(evidence)
    private fun exactDriver(evidence: PhysicalSourceEvidence): Boolean = evidence.databaseProductName == "DuckDB" && evidence.databaseProductVersion == "v1.5.6" && evidence.driverName == "DuckDBJ" && evidence.driverVersion == "1.0"
    override fun constraints(connection: Connection, table: JdbcTableConstraints.Table, control: WarehouseExecutionControl): JdbcTableConstraints =
        StandardJdbcConstraints.read(connection, DuckdbConstraints.read(connection, table, control), table, control)
    override fun configureMemory(connection: Connection, bytes: Long, control: WarehouseExecutionControl) {
        require(bytes > 0) { "explicit positive native memory budget is required" }
        control.checkCancellation()
        connection.prepareStatement("SET memory_limit = '${bytes}B'").use { statement ->
            control.registerCancellationAction(statement::cancel).use { statement.execute(); control.checkCancellation() }
        }
    }
    override fun beginSnapshot(connection: Connection, sources: List<JdbcSourceRequirement>, control: WarehouseExecutionControl) {
        connection.autoCommit = false
        if (connection.transactionIsolation != Connection.TRANSACTION_REPEATABLE_READ) throw SourceGuaranteeException("SOURCE_CONSISTENCY_MISMATCH")
    }
    override fun sourceAnchor(connection: Connection, spec: JdbcConnectionSpec, checkCancellation: () -> Unit): JdbcTableConstraints.DataAnchor = DuckdbImmutableSource.anchor(connection, spec, checkCancellation)
    override fun readBasis(connection: Connection, spec: JdbcConnectionSpec, expected: SourceReadBasis.Versioned?, control: WarehouseExecutionControl): SourceReadBasis {
        if (expected == null) return SourceReadBasis.Unknown("fresh DuckDB execution has no declared immutable data anchor")
        if (connection.autoCommit) beginSnapshot(connection, emptyList(), control)
        val anchor = sourceAnchor(connection, spec, control::checkCancellation)
        return SourceReadBasis.Versioned("sha256", anchor.sha256, emptyMap(), SourcePinning.EXACT_VERSION)
    }
}
