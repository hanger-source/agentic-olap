package com.olap.prototype.warehouse.jdbc

import com.olap.prototype.warehouse.contract.JdbcTableConstraints
import java.sql.DatabaseMetaData
import java.sql.SQLFeatureNotSupportedException

/** Literal metadata patterns and SQL identifiers have separate exact syntaxes. */
object JdbcIdentifiers {
    fun literalPattern(metadata: DatabaseMetaData, value: String?): String? {
        if (value == null) return null
        val escape = metadata.searchStringEscape
        if (escape.isNullOrEmpty()) {
            if ('_' in value || '%' in value) throw SQLFeatureNotSupportedException("JDBC metadata does not expose an escape for literal patterns")
            return value
        }
        return value.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%")
    }
    fun quote(metadata: DatabaseMetaData, value: String): String {
        val quote = metadata.identifierQuoteString
        if (quote.isNullOrBlank()) throw SQLFeatureNotSupportedException("JDBC metadata does not expose identifier quoting")
        return quote + value.replace(quote, quote + quote) + quote
    }
    fun qualified(metadata: DatabaseMetaData, table: JdbcTableConstraints.Table): String {
        var name = table.schema?.let { quote(metadata, it) + "." } .orEmpty() + quote(metadata, table.table)
        val catalog = table.catalog
        if (catalog != null) {
            if (metadata.supportsCatalogsInDataManipulation()) {
                val separator = metadata.catalogSeparator
                if (separator.isNullOrEmpty()) throw SQLFeatureNotSupportedException("JDBC metadata does not expose its catalog separator")
                name = if (metadata.isCatalogAtStart) quote(metadata, catalog) + separator + name else name + separator + quote(metadata, catalog)
            } else {
                val connection = metadata.connection
                if (connection == null || catalog != connection.catalog) throw SQLFeatureNotSupportedException("JDBC cannot qualify the requested catalog or prove it is the current catalog")
            }
        }
        return name
    }
}
