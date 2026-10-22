package com.olap.prototype.warehouse.jdbc

import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.warehouse.SourceGuaranteeException
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.WarehouseSourceInspection
import com.olap.prototype.warehouse.contract.*
import com.olap.prototype.warehouse.inspection.*
import java.sql.SQLException

/** Complete frozen-source proofs complement structure observation; samples never become constraints. */
class JdbcSourceInspection(private val inspector: JdbcWarehouseInspector, connectors: ConnectorRegistry) : WarehouseSourceInspection {
    private val connections = JdbcConnectionFactory(connectors)
    override fun discover(request: WarehouseSourceInspection.DiscoveryRequest, control: WarehouseExecutionControl): WarehouseSourceInspection.Discovery {
        val snapshot = inspector.snapshot(request.inspection, control)
        if (request.temporalKeys.isEmpty() && request.snapshotUniqueKeys.isEmpty()) return WarehouseSourceInspection.Discovery(snapshot, emptyMap())
        val names = snapshot.tables.mapTo(hashSetOf()) { it.table.ref.table }
        require(!snapshot.truncated && names.containsAll(request.temporalKeys.keys) && names.containsAll(request.snapshotUniqueKeys.keys)) { "key verification requires complete discovery of every exact requested table" }
        val source = request.inspection.connection
        val spec = JdbcConnectionSpec.from(source)
        var failureCode = if (request.temporalKeys.isEmpty()) "SOURCE_UNIQUE_CONNECTION_FAILED" else "SOURCE_TEMPORAL_CONNECTION_FAILED"
        control.checkCancellation()
        try {
            connections.openReadOnly(source).use { connection -> control.registerCancellationAction { connection.abort { it.run() } }.use {
                control.checkCancellation()
                val jdbc = connections.jdbc(spec)
                val unique = linkedMapOf<String, List<SnapshotUniqueKey>>()
                val tables = snapshot.tables.map { original ->
                    control.checkCancellation()
                    val ref = original.table.ref
                    val coordinate = JdbcTableConstraints.Table(ref.catalog, ref.schema, ref.table)
                    val columns = original.columns.associateTo(linkedMapOf()) { it.name to JdbcColumnType(requireNotNull(it.jdbcType) { "source column JDBC type is unknown" }, requireNotNull(it.typeName) { "source column native type is unknown" }, it.precision, it.scale, it.nullable) }
                    val temporal = request.temporalKeys[ref.table]
                    val table = if (temporal == null) original else {
                        failureCode = "SOURCE_TEMPORAL_CONNECTION_FAILED"
                        val verified = JdbcTemporalVerifier.verify(connection, spec, coordinate, columns, temporal, control, jdbc)
                        WarehouseTableMetadata(original.source, original.table, original.columns, JdbcTableConstraints(original.constraints.primaryKeyColumns, original.constraints.uniqueKeys, original.constraints.importedKeys, verified))
                    }
                    request.snapshotUniqueKeys[ref.table]?.let { rules ->
                        failureCode = "SOURCE_UNIQUE_CONNECTION_FAILED"
                        unique[ref.table] = JdbcSnapshotUniqueVerifier.verify(connection, spec, coordinate, columns, rules, control, jdbc)
                    }
                    table
                }
                control.checkCancellation()
                return WarehouseSourceInspection.Discovery(WarehouseSchemaSnapshot(snapshot.source, snapshot.truncated, tables), unique)
            } }
        } catch (failure: SQLException) { control.checkCancellation(); throw SourceGuaranteeException(failureCode, failure.sqlState, failure.javaClass.name) }
    }
    override fun preview(request: WarehousePreviewRequest, control: WarehouseExecutionControl): WarehousePreview = inspector.preview(request, control)
    override fun verifySealedDataset(connection: SourceConfiguration, declaration: SealedSourceDataset, control: WarehouseExecutionControl) {
        control.checkCancellation()
        try {
            connections.openReadOnly(connection).use { physical -> control.registerCancellationAction { physical.abort { it.run() } }.use {
                control.checkCancellation()
                val spec = JdbcConnectionSpec.from(connection)
                val jdbc = connections.jdbc(spec)
                if (declaration.anchor != jdbc.sourceAnchor(physical, spec, control::checkCancellation)) throw ProducerContractException("source declaration data anchor differs from deployed source")
                val metadata = physical.metaData
                declaration.collections.forEach { collection ->
                    control.checkCancellation()
                    val table = collection.table
                    var found = false
                    metadata.getTables(table.catalog, JdbcIdentifiers.literalPattern(metadata, table.schema), JdbcIdentifiers.literalPattern(metadata, table.table), arrayOf("TABLE")).use { rows -> while (rows.next()) {
                        control.checkCancellation()
                        if (table.catalog == rows.getString("TABLE_CAT") && table.schema == rows.getString("TABLE_SCHEM") && table.table == rows.getString("TABLE_NAME")) {
                            if (found) throw ProducerContractException("sealed source collection has duplicate exact base tables")
                            found = true
                        }
                    } }
                    if (!found) throw ProducerContractException("sealed source collection must identify an exact deployed base table")
                }
                if (declaration.anchor != jdbc.sourceAnchor(physical, spec, control::checkCancellation)) throw ProducerContractException("source changed while adopting its declaration")
                control.checkCancellation()
            } }
        } catch (failure: SQLException) { control.checkCancellation(); throw ProducerContractException("cannot verify sealed source deployment", failure) }
    }
}
