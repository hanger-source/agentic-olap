package com.olap.prototype.warehouse.jdbc

import com.olap.prototype.connector.JdbcSupport
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.warehouse.SourceGuaranteeException
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.contract.JdbcConnectionSpec
import com.olap.prototype.warehouse.contract.JdbcTableConstraints
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.sql.Types

/** Adoption checks full temporal rectangles; execution later compares their immutable anchor. */
object JdbcTemporalVerifier {
    private val log = LoggerFactory.getLogger(JdbcTemporalVerifier::class.java)
    fun verify(connection: Connection, spec: JdbcConnectionSpec, table: JdbcTableConstraints.Table, columns: Map<String, JdbcColumnType>, rules: List<JdbcTableConstraints.TemporalRule>, control: WarehouseExecutionControl, jdbc: JdbcSupport): List<JdbcTableConstraints.TemporalKey> {
        val anchor = jdbc.sourceAnchor(connection, spec, control::checkCancellation)
        require("__olap_temporal_ordinal" !in columns) { "source column conflicts with temporal verification ordinal" }
        try {
            val metadata = connection.metaData
            val qualified = JdbcIdentifiers.qualified(metadata, table)
            val result = rules.map { rule ->
                control.checkCancellation()
                require(columns.keys.containsAll(rule.columns())) { "temporal rule references an unknown source column" }
                val invalid = mutableListOf<String>()
                val overlap = rule.equalityColumns.mapTo(mutableListOf()) { JdbcIdentifiers.quote(metadata, it).let { column -> "a.$column=b.$column" } }
                rule.intervals.forEach { interval ->
                    val from = requireNotNull(columns[interval.validFromColumn])
                    val to = requireNotNull(columns[interval.validToColumn])
                    require(from.jdbcType in setOf(Types.DATE, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE) && from == JdbcColumnType(to.jdbcType, to.typeName, to.precision, to.scale, from.nullable)) { "temporal endpoints must have the same exact temporal type" }
                    val low = JdbcIdentifiers.quote(metadata, interval.validFromColumn)
                    val high = JdbcIdentifiers.quote(metadata, interval.validToColumn)
                    invalid += "$low IS NULL"
                    if (!interval.openEndedValidTo) invalid += "$high IS NULL"
                    val inclusive = interval.validFromInclusive && interval.validToInclusive
                    invalid += low + (if (inclusive) ">" else ">=") + high
                    val comparison = if (inclusive) "<=" else "<"
                    var left = "a.$low${comparison}b.$high"
                    var right = "b.$low${comparison}a.$high"
                    if (interval.openEndedValidTo) { left = "(b.$high IS NULL OR $left)"; right = "(a.$high IS NULL OR $right)" }
                    overlap += left; overlap += right
                }
                reject(connection, "SELECT 1 FROM $qualified WHERE ${invalid.joinToString(" OR ")}", "SOURCE_TEMPORAL_INTERVAL_INVALID", control)
                val ordinal = JdbcIdentifiers.quote(metadata, "__olap_temporal_ordinal")
                reject(connection, "WITH q AS (SELECT ROW_NUMBER() OVER () AS $ordinal, * FROM $qualified) SELECT 1 FROM q a JOIN q b ON a.$ordinal<b.$ordinal AND ${overlap.joinToString(" AND ")}", "SOURCE_TEMPORAL_INTERVAL_OVERLAP", control)
                log.info("已完整验证冻结源时态约束：dataSourceId={}, table={}, constraint={}, dataSha256={}", spec.dataSourceId, table.table, rule.name, anchor.sha256)
                JdbcTableConstraints.TemporalKey(rule, anchor)
            }
            if (anchor != jdbc.sourceAnchor(connection, spec, control::checkCancellation)) throw SourceGuaranteeException("IMMUTABLE_SOURCE_CHANGED_DURING_VALIDATION")
            return java.util.List.copyOf(result)
        } catch (failure: SQLException) {
            control.checkCancellation()
            log.debug("冻结源时态验证失败：dataSourceId={}", spec.dataSourceId, failure)
            throw SourceGuaranteeException("SOURCE_TEMPORAL_VALIDATION_FAILED", failure.sqlState, failure.javaClass.name)
        }
    }
    private fun reject(connection: Connection, sql: String, code: String, control: WarehouseExecutionControl) {
        connection.prepareStatement(sql).use { statement -> control.registerCancellationAction(statement::cancel).use {
            statement.maxRows = 1
            statement.executeQuery().use { rows -> control.checkCancellation(); if (rows.next()) throw SourceGuaranteeException(code) }
        } }
    }
}
