package com.olap.prototype.warehouse.jdbc

import com.olap.prototype.connector.JdbcSupport
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.contract.JdbcSourceRequirement
import com.olap.prototype.warehouse.SourceGuaranteeException
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.contract.JdbcConnectionSpec
import com.olap.prototype.warehouse.contract.JdbcTableConstraints
import com.olap.prototype.warehouse.contract.StructureProvenance
import com.olap.prototype.warehouse.inspection.JdbcMetadataReader
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException

/** Verify this query's exact adopted facts in the same connection and transaction as execution. */
object JdbcSourceVerifier {
    private val log = LoggerFactory.getLogger(JdbcSourceVerifier::class.java)
    fun requireDeployment(spec: JdbcConnectionSpec, sources: List<JdbcSourceRequirement>) { java.util.List.copyOf(sources).forEach { it.requireDeployment(spec.source()) } }
    fun beginAndVerify(connection: Connection, spec: JdbcConnectionSpec, sources: List<JdbcSourceRequirement>, control: WarehouseExecutionControl, jdbc: JdbcSupport): JdbcTableConstraints.DataAnchor? {
        control.checkCancellation(); requireDeployment(spec, sources)
        if (sources.isEmpty()) return null
        try {
            val technology = connection.metaData
            sources.forEach { source ->
                control.checkCancellation()
                val evidence = source.evidence
                val observed = evidence.structureProvenance.kind == StructureProvenance.Kind.DATABASE_CATALOG
                if (observed && !jdbc.supportsColumnInspection(evidence) || source.constraintUses.isNotEmpty() && !jdbc.supportsConstraintSnapshot(evidence)) throw SourceGuaranteeException("SOURCE_CONSISTENCY_UNSUPPORTED")
                if (observed && (technology.databaseProductName != evidence.databaseProductName || technology.databaseProductVersion != evidence.databaseProductVersion || technology.driverName != evidence.driverName || technology.driverVersion != evidence.driverVersion)) throw SourceGuaranteeException("SOURCE_TECHNOLOGY_DRIFT")
            }
            if (!connection.autoCommit) throw SourceGuaranteeException("SOURCE_TRANSACTION_ALREADY_OPEN")
            if (sources.any { it.evidence.structureProvenance.kind == StructureProvenance.Kind.DATABASE_CATALOG || it.constraintUses.isNotEmpty() || it.readsRows() && it.evidence.sourceCollections.isNotEmpty() }) jdbc.beginSnapshot(connection, sources, control)
            val needsAnchor = sources.any { it.readsRows() && it.evidence.sourceCollections.isNotEmpty() || it.constraintUses.any { use -> use.kind in setOf(JdbcSourceRequirement.ConstraintKind.TEMPORAL_KEY, JdbcSourceRequirement.ConstraintKind.SNAPSHOT_UNIQUE_KEY) } }
            val anchor = if (needsAnchor) jdbc.sourceAnchor(connection, spec, control::checkCancellation) else null
            sources.forEach { control.checkCancellation(); verify(connection, it, anchor, control, jdbc) }
            control.checkCancellation()
            return anchor
        } catch (failure: SQLException) {
            log.debug("JDBC source contract verification failed: dataSourceId={}", spec.dataSourceId, failure)
            throw SourceGuaranteeException("SOURCE_GUARANTEE_CHECK_FAILED", failure.sqlState, failure.javaClass.name)
        }
    }
    private fun verify(connection: Connection, source: JdbcSourceRequirement, anchor: JdbcTableConstraints.DataAnchor?, control: WarehouseExecutionControl, jdbc: JdbcSupport) {
        val table = source.table
        if (source.evidence.structureProvenance.kind == StructureProvenance.Kind.DATABASE_CATALOG) {
            val metadata = connection.metaData
            val columns = linkedMapOf<String, JdbcColumnType>()
            var observed = false
            metadata.getColumns(table.catalog, JdbcIdentifiers.literalPattern(metadata, table.schema), JdbcIdentifiers.literalPattern(metadata, table.table), null).use { rows -> while (rows.next()) {
                control.checkCancellation()
                if (table.catalog != rows.getString("TABLE_CAT") || table.schema != rows.getString("TABLE_SCHEM") || table.table != rows.getString("TABLE_NAME")) continue
                val name = rows.getString("COLUMN_NAME")
                observed = true
                if (name !in source.columns) continue
                val nullable = JdbcMetadataReader.nullability(rows) ?: throw SourceGuaranteeException("SOURCE_COLUMN_NULLABILITY_UNKNOWN")
                val type = JdbcColumnType(rows.getInt("DATA_TYPE"), rows.getString("TYPE_NAME"), JdbcMetadataReader.nullableInt(rows, "COLUMN_SIZE"), JdbcMetadataReader.nullableInt(rows, "DECIMAL_DIGITS"), nullable)
                if (columns.putIfAbsent(name, type) != null) throw SourceGuaranteeException("SOURCE_COLUMN_AMBIGUOUS")
            } }
            if (!observed) throw SourceGuaranteeException("SOURCE_TABLE_MISSING")
            if (columns != source.columns) throw SourceGuaranteeException("SOURCE_COLUMN_TYPE_DRIFT")
        }
        if (source.readsRows()) source.evidence.sourceCollections.forEach { control.checkCancellation(); if (it.anchor != anchor) throw SourceGuaranteeException("SOURCE_COVERAGE_ANCHOR_DRIFT") }
        if (source.constraintUses.isEmpty()) return
        val actual = jdbc.constraints(connection, table, control)
        val expected = source.evidence.constraints
        source.constraintUses.forEach { use ->
            control.checkCancellation()
            val matches = when (use.kind) {
                JdbcSourceRequirement.ConstraintKind.PRIMARY_KEY -> expected.primaryKeyColumns == actual.primaryKeyColumns
                JdbcSourceRequirement.ConstraintKind.UNIQUE_KEY -> expected.uniqueKeys.first { it.name == use.name } in actual.uniqueKeys
                JdbcSourceRequirement.ConstraintKind.FOREIGN_KEY -> expected.importedKeys.filter { it.name == use.name } == actual.importedKeys.filter { it.name == use.name }
                JdbcSourceRequirement.ConstraintKind.TEMPORAL_KEY -> { if (expected.temporalKeys.first { it.rule.name == use.name }.anchor != anchor) throw SourceGuaranteeException("SOURCE_DATA_ANCHOR_DRIFT"); true }
                JdbcSourceRequirement.ConstraintKind.SNAPSHOT_UNIQUE_KEY -> { if (source.snapshotUniqueKeys.first { it.rule.name == use.name }.anchor != anchor) throw SourceGuaranteeException("SOURCE_DATA_ANCHOR_DRIFT"); true }
            }
            if (!matches) throw SourceGuaranteeException("SOURCE_CONSTRAINT_DRIFT")
        }
    }
}
