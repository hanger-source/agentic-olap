package com.olap.connectors.duckdb

import com.olap.prototype.connector.*
import com.olap.prototype.warehouse.jdbc.JdbcSourceAccess

class DuckdbConnector : OlapConnector {
    override val id: String = "duckdb"
    override val version: String = "1.0.0"
    override val apiVersion: String = OlapConnector.API_VERSION
    override val compilation: CompilationSupport = DuckdbCompilation()
    private val jdbc = DuckdbJdbcSupport()
    override fun access(identity: ConnectorRef): SourceAccess = JdbcSourceAccess(identity, compilation, jdbc)
}
