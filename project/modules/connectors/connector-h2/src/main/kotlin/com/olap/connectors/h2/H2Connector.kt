package com.olap.connectors.h2

import com.olap.prototype.connector.*
import com.olap.prototype.warehouse.jdbc.JdbcSourceAccess

class H2Connector : OlapConnector {
    override val id: String = "h2"
    override val version: String = "1.0.0"
    override val apiVersion: String = OlapConnector.API_VERSION
    override val compilation: CompilationSupport = H2Compilation()
    private val jdbc = H2JdbcSupport()
    override fun access(identity: ConnectorRef): SourceAccess = JdbcSourceAccess(identity, compilation, jdbc)
}
