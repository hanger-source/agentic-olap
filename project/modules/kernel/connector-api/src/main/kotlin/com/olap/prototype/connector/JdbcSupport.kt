package com.olap.prototype.connector

import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.contract.JdbcSourceRequirement
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.contract.*
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.Properties

/** JDBC 实现的原生机制；仅由已授权连接调用显式传入凭据。 */
interface JdbcSupport : AutoCloseable {
    fun capabilities(source: SourceConfiguration): SourceCapabilities
    override fun close()
    fun openReadOnly(spec: JdbcConnectionSpec, credentials: Properties): Connection
    fun configureMemory(connection: Connection, bytes: Long, control: WarehouseExecutionControl)
    fun beginSnapshot(connection: Connection, sources: List<JdbcSourceRequirement>, control: WarehouseExecutionControl)
    fun supportsColumnInspection(evidence: PhysicalSourceConstraints): Boolean
    fun supportsConstraintSnapshot(evidence: PhysicalSourceConstraints): Boolean
    fun constraints(connection: Connection, table: JdbcTableConstraints.Table, control: WarehouseExecutionControl): JdbcTableConstraints
    fun sourceAnchor(connection: Connection, spec: JdbcConnectionSpec, checkCancellation: () -> Unit): JdbcTableConstraints.DataAnchor
    fun readBasis(connection: Connection, spec: JdbcConnectionSpec, expected: SourceReadBasis.Versioned?, control: WarehouseExecutionControl): SourceReadBasis
    fun bind(statement: PreparedStatement, index: Int, value: JdbcParameter)
    fun scalar(rows: ResultSet, index: Int): Any?
    /** 元素游标已定位真实元素；解码第2列，不伪造不存在的元素元数据。 */
    fun arrayElement(array: java.sql.Array, elements: ResultSet): Any?
}
