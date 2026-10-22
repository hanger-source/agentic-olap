package com.olap.prototype.warehouse.inspection

import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.connector.SourceCapabilityException
import com.olap.prototype.runtime.RuntimeResourceLimitException
import com.olap.prototype.warehouse.SourceGuaranteeException
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.contract.JdbcTableConstraints
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.contract.StructureProvenance
import com.olap.prototype.warehouse.jdbc.JdbcConnectionFactory
import com.olap.prototype.warehouse.jdbc.JdbcIdentifiers
import com.olap.prototype.warehouse.jdbc.WarehouseConnectionException
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.CancellationException

/** Read-only catalog observation and bounded preview; one resource boundary owns typed safe failures. */
class JdbcWarehouseInspector(connectors: ConnectorRegistry, environmentLookup: (String) -> String? = System::getenv) {
    private val log = LoggerFactory.getLogger(JdbcWarehouseInspector::class.java)
    private val connections = JdbcConnectionFactory(connectors, environmentLookup)
    fun discover(request: WarehouseDiscoveryRequest, control: WarehouseExecutionControl): WarehouseDiscoveryResult = inspect("WAREHOUSE_DISCOVERY_FAILED", request.connection, control) { connection ->
        val metadata = connection.metaData
        val discovered = JdbcMetadataReader(metadata, control).discover(request.catalog, request.schemaPattern, request.tablePattern, request.tableTypes, request.limitOrDefault())
        WarehouseDiscoveryResult(sourceMetadata(request.connection, connection, metadata), discovered.tables.size, discovered.truncated, discovered.tables)
    }
    fun describe(request: WarehouseTableInspectionRequest, control: WarehouseExecutionControl): WarehouseTableMetadata = inspect("WAREHOUSE_TABLE_DESCRIPTION_FAILED", request.connection, control) { connection ->
        val metadata = connection.metaData
        readTable(request.connection, connection, metadata, JdbcMetadataReader(metadata, control).exactTable(request.table), control)
    }
    fun snapshot(request: WarehouseSchemaSnapshotRequest, control: WarehouseExecutionControl): WarehouseSchemaSnapshot = inspect("WAREHOUSE_SCHEMA_SNAPSHOT_FAILED", request.connection, control) { connection ->
        val metadata = connection.metaData
        val discovered = JdbcMetadataReader(metadata, control).discover(request.catalog, JdbcIdentifiers.literalPattern(metadata, request.schemaName), request.tablePattern, request.tableTypes, request.limitOrDefault())
        val tables = discovered.tables.map { readTable(request.connection, connection, metadata, it, control) }
        WarehouseSchemaSnapshot(sourceMetadata(request.connection, connection, metadata), discovered.truncated, tables)
    }
    fun preview(request: WarehousePreviewRequest, control: WarehouseExecutionControl): WarehousePreview = inspect("WAREHOUSE_TABLE_PREVIEW_FAILED", request.connection, control) { connection ->
        val metadata = connection.metaData
        val reader = JdbcMetadataReader(metadata, control)
        val table = reader.exactTable(request.table)
        val columns = reader.columns(table.ref)
        val sql = "SELECT * FROM ${JdbcIdentifiers.qualified(metadata, coordinate(table.ref))}"
        val rows = mutableListOf<WarehouseRow>()
        connection.createStatement().use { statement -> control.registerCancellationAction(statement::cancel).use {
            statement.maxRows = request.limitOrDefault(); statement.fetchSize = request.limitOrDefault()
            statement.executeQuery(sql).use { result ->
                val resultMetadata = result.metaData
                while (rows.size < request.limitOrDefault() && result.next()) {
                    control.checkCancellation()
                    rows += WarehouseRow((1..resultMetadata.columnCount).map { index -> WarehouseCell(resultMetadata.getColumnLabel(index), resultMetadata.getColumnType(index), resultMetadata.getColumnTypeName(index), JdbcInspectionValues.scalar(connections.jdbc(request.connection).scalar(result, index))) })
                }
            }
        } }
        WarehousePreview(sourceMetadata(request.connection, connection, metadata), table, columns, request.limitOrDefault(), rows)
    }
    fun profile(request: WarehouseProfileRequest, control: WarehouseExecutionControl): WarehouseProfile {
        val started = Instant.now()
        return inspect("WAREHOUSE_TABLE_PROFILE_FAILED", request.connection, control) { connection ->
            val metadata = connection.metaData
            val reader = JdbcMetadataReader(metadata, control)
            val table = reader.exactTable(request.table)
            val actualColumns = reader.columns(table.ref).map { it.name }
            request.columns.forEach { require(it in actualColumns) { "数据源中不存在精确列引用: $it" } }
            val sql = "SELECT COUNT(*)" + request.columns.joinToString("") { ", COUNT(${JdbcIdentifiers.quote(metadata, it)})" } + " FROM ${JdbcIdentifiers.qualified(metadata, coordinate(table.ref))}"
            connection.createStatement().use { statement -> control.registerCancellationAction(statement::cancel).use {
                statement.queryTimeout = request.timeoutOrDefault()
                statement.executeQuery(sql).use { result ->
                    control.checkCancellation()
                    if (!result.next()) throw SQLException("统计未返回结果")
                    val rows = result.getBigDecimal(1).toBigIntegerExact()
                    val counts = request.columns.mapIndexed { index, column ->
                        val nonNull = result.getBigDecimal(index + 2).toBigIntegerExact()
                        if (nonNull.signum() < 0 || nonNull > rows) throw SQLException("统计计数不一致")
                        WarehouseProfile.ColumnCount(column, nonNull.toString(), (rows - nonNull).toString())
                    }
                    WarehouseProfile(sourceMetadata(request.connection, connection, metadata), table, started, Instant.now(), rows.toString(), counts)
                }
            } }
        }
    }
    fun profileRelationship(request: WarehouseRelationshipProfileRequest, control: WarehouseExecutionControl): WarehouseRelationshipProfile {
        val started = Instant.now()
        return inspect("WAREHOUSE_RELATIONSHIP_PROFILE_FAILED", request.connection, control) { connection ->
            val metadata = connection.metaData
            val reader = JdbcMetadataReader(metadata, control)
            val source = reader.exactTable(request.sourceInput.table)
            val target = reader.exactTable(request.targetInput.table)
            val compiled = CalciteRelationshipProfiler.compile(request, reader.columns(source.ref), reader.columns(target.ref), connections.connector(request.connection).compilation)
            val directions = arrayOfNulls<WarehouseRelationshipProfile.Direction>(2)
            connection.prepareStatement(compiled.sql).use { statement -> control.registerCancellationAction(statement::cancel).use {
                statement.queryTimeout = request.timeoutOrDefault()
                compiled.parameters.forEachIndexed { index, parameter -> connections.jdbc(request.connection).bind(statement, index + 1, parameter) }
                statement.executeQuery().use { result -> while (result.next()) {
                    control.checkCancellation()
                    val direction = result.getInt(1)
                    if (direction !in 0..1 || directions[direction] != null) throw SQLException("关系统计方向重复或无效")
                    val values = (2..11).map { index ->
                        val count = result.getBigDecimal(index)
                        if (count == null || count.signum() < 0) throw SQLException("关系计数无效")
                        count.toBigIntegerExact().toString()
                    }
                    directions[direction] = WarehouseRelationshipProfile.Direction(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8], values[9])
                } }
            } }
            val forward = directions[0]; val backward = directions[1]
            if (forward == null || backward == null || forward.matchedPairCount != backward.matchedPairCount) throw SQLException("关系统计未返回完整且一致的双向结果")
            WarehouseRelationshipProfile(sourceMetadata(request.connection, connection, metadata), request.sourceInput, request.targetInput, started, Instant.now(), "SQL_EQUALS_NULL_NEVER_MATCHES", forward, backward)
        }
    }
    private fun readTable(source: SourceConfiguration, connection: Connection, metadata: DatabaseMetaData, table: WarehouseTableSummary, control: WarehouseExecutionControl): WarehouseTableMetadata {
        control.checkCancellation()
        val columns = JdbcMetadataReader(metadata, control).columns(table.ref)
        control.checkCancellation()
        return WarehouseTableMetadata(sourceMetadata(source, connection, metadata), table, columns, connections.jdbc(source).constraints(connection, coordinate(table.ref), control))
    }
    private fun coordinate(ref: WarehouseTableRef): JdbcTableConstraints.Table = JdbcTableConstraints.Table(ref.catalog, ref.schema, ref.table)
    private fun sourceMetadata(source: SourceConfiguration, connection: Connection, metadata: DatabaseMetaData): WarehouseSourceMetadata =
        WarehouseSourceMetadata(source.dataSourceId, source.dialectId, source.sourceIdentityHash(), metadata.databaseProductName, metadata.databaseProductVersion,
            metadata.driverName, metadata.driverVersion, metadata.userName, connection.isReadOnly, StructureProvenance(StructureProvenance.Kind.DATABASE_CATALOG, metadata.databaseProductName, source.sourceIdentityHash()))

    private inline fun <T> inspect(code: String, source: SourceConfiguration, control: WarehouseExecutionControl, action: (Connection) -> T): T {
        control.checkCancellation()
        try {
            connections.openReadOnly(source).use { connection -> control.registerCancellationAction { connection.abort { it.run() } }.use {
                control.checkCancellation()
                return action(connection)
            } }
        } catch (failure: WarehouseInspectionException) { throw failure }
          catch (failure: WarehouseConnectionException) { control.checkCancellation(); throw WarehouseInspectionException(failure.code, failure.dataSourceId, failure.sqlState, failure.failureType, failure.safeDetail) }
          catch (failure: Exception) {
            control.checkCancellation()
            when (failure) {
                is CancellationException, is SourceCapabilityException, is RuntimeResourceLimitException, is SourceGuaranteeException -> throw failure
            }
            log.error("warehouse inspection failed: code={}, dataSourceId={}", code, source.dataSourceId, failure)
            throw WarehouseInspectionException(code, source.dataSourceId, (failure as? SQLException)?.sqlState, failure.javaClass.name)
        }
    }
}
