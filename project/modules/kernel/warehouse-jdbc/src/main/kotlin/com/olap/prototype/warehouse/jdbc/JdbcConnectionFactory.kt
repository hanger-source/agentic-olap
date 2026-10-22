package com.olap.prototype.warehouse.jdbc

import com.olap.prototype.connector.*
import com.olap.prototype.runtime.RuntimeResourceLimitException
import com.olap.prototype.warehouse.SourceGuaranteeException
import com.olap.prototype.warehouse.contract.JdbcConnectionSpec
import com.olap.prototype.warehouse.contract.SourceConfiguration
import java.sql.Connection
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.CancellationException

class JdbcConnectionFactory(private val connectors: ConnectorRegistry, private val environmentLookup: (String) -> String? = System::getenv) {
    fun connector(spec: JdbcConnectionSpec): ResolvedConnector = connectors.require(spec.connectorRef)
    fun connector(source: SourceConfiguration): ResolvedConnector = connectors.require(source.connectorRef)
    fun jdbc(spec: JdbcConnectionSpec): JdbcSupport = jdbc(spec.source())
    fun jdbc(source: SourceConfiguration): JdbcSupport = (connector(source).access as? JdbcAccess)?.jdbc ?: throw IllegalArgumentException("source does not provide JDBC access: ${source.dataSourceId}")
    fun openReadOnly(source: SourceConfiguration): Connection = openReadOnly(JdbcConnectionSpec.from(source))
    fun openReadOnly(spec: JdbcConnectionSpec): Connection {
        try {
            val credentials = Properties()
            spec.username?.let { credentials.setProperty("user", it) }
            spec.passwordEnvironmentVariable?.let { variable ->
                val password = environmentLookup(variable) ?: throw WarehouseConnectionException("WAREHOUSE_PASSWORD_ENVIRONMENT_VARIABLE_MISSING", spec.dataSourceId, null, "missing-environment-variable", "environmentVariable=$variable")
                credentials.setProperty("password", password)
            }
            return jdbc(spec).openReadOnly(spec, credentials)
        } catch (failure: WarehouseConnectionException) { throw failure }
          catch (failure: IllegalArgumentException) { throw failure }
          catch (failure: SourceCapabilityException) { throw failure }
          catch (failure: SourceGuaranteeException) { throw failure }
          catch (failure: RuntimeResourceLimitException) { throw failure }
          catch (failure: CancellationException) { throw failure }
          catch (failure: Exception) { throw WarehouseConnectionException("WAREHOUSE_CONNECTION_FAILED", spec.dataSourceId, (failure as? SQLException)?.sqlState, failure.javaClass.simpleName) }
    }
}
