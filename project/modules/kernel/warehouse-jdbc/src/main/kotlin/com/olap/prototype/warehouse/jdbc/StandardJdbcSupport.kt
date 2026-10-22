package com.olap.prototype.warehouse.jdbc

import com.olap.prototype.connector.JdbcSupport
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.warehouse.contract.JdbcConnectionSpec
import com.olap.prototype.warehouse.contract.SourceReadBasis
import com.olap.prototype.warehouse.SourceGuaranteeException
import com.olap.prototype.warehouse.WarehouseExecutionControl
import java.sql.Connection
import java.sql.Driver
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.Properties

/** Standard JDBC mechanics; exact backend owns constraints, snapshot, capacity and native evidence. */
abstract class StandardJdbcSupport(protected val driverClass: String) : JdbcSupport {
    @Volatile private var closed = false
    protected fun markClosed() { closed = true }
    protected fun requireOpen() { check(!closed) { "connector has been closed" } }
    init {
        try {
            val type = Class.forName(driverClass, false, javaClass.classLoader)
            require(Driver::class.java.isAssignableFrom(type) && type.classLoader == javaClass.classLoader) { "driver must be provided by the exact plugin artifact group" }
            type.getConstructor()
        } catch (failure: ReflectiveOperationException) { throw IllegalArgumentException("plugin driver dependency is unavailable", failure) }
    }
    override fun openReadOnly(spec: JdbcConnectionSpec, credentials: Properties): Connection {
        requireOpen()
        require(spec.driverClassName == null || spec.driverClassName == driverClass) { "driver differs from registered connector" }
        val driver = Class.forName(driverClass, true, javaClass.classLoader).getConstructor().newInstance() as Driver
        val connection = driver.connect(spec.jdbcUrl, credentials) ?: throw IllegalArgumentException("connector driver rejects source URL")
        try { connection.isReadOnly = true; return connection }
        catch (failure: Exception) { try { connection.close() } catch (close: java.sql.SQLException) { failure.addSuppressed(close) }; throw failure }
    }
    override fun bind(statement: PreparedStatement, index: Int, value: JdbcParameter) = value.bind(statement, index)
    override fun scalar(rows: ResultSet, index: Int): Any? = JdbcScalarValues.read(rows, index)
    override fun readBasis(connection: Connection, spec: JdbcConnectionSpec, expected: SourceReadBasis.Versioned?, control: WarehouseExecutionControl): SourceReadBasis {
        if (expected != null) throw SourceGuaranteeException("SOURCE_PINNING_UNSUPPORTED")
        return SourceReadBasis.Unknown("the source did not return a stable version or partition receipt for this execution")
    }
    override fun arrayElement(array: java.sql.Array, elements: ResultSet): Any? = if (elements.metaData.getColumnType(2) == Types.ARRAY) elements.getArray(2) else scalar(elements, 2)
}
