package com.olap.connectors.duckdb

import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.sql.type.SqlTypeName
import java.util.Locale

/** Container element types come from DuckDB's exact declaration, never samples or JDBC OTHER. */
internal object DuckdbSourceTypes {
    private val decimal = Regex("DECIMAL\\((\\d+),\\s*(\\d+)\\)")
    fun container(name: String): Boolean = name.trim().let { it.endsWith(']') || it.startsWith("STRUCT(", ignoreCase = true) }
    fun resolve(factory: RelDataTypeFactory, declaration: String, nullable: Boolean): RelDataType {
        val type = declaration.trim()
        val result = when {
            type.endsWith(']') -> {
                val open = type.lastIndexOf('[')
                if (open < 0) throw unsupported(declaration)
                val length = type.substring(open + 1, type.length - 1)
                if (length.isNotEmpty() && !length.matches(Regex("[0-9]+"))) throw unsupported(declaration)
                factory.createArrayType(resolve(factory, type.substring(0, open), true), if (length.isEmpty()) -1 else length.toLong())
            }
            type.startsWith("STRUCT(", ignoreCase = true) && type.endsWith(')') -> factory.builder().also { builder ->
                fields(type.substring(7, type.length - 1)).forEach { field ->
                    val start = fieldTypeStart(field)
                    val name = field.substring(0, start).trim().let { if (it.startsWith('"')) it.substring(1, it.length - 1).replace("\"\"", "\"") else it }
                    builder.add(name, resolve(factory, field.substring(start), true))
                }
            }.build()
            else -> {
                val match = decimal.matchEntire(type.uppercase(Locale.ROOT))
                if (match == null) scalar(factory, type.uppercase(Locale.ROOT)) else {
                    val precision = match.groupValues[1].toInt(); val scale = match.groupValues[2].toInt()
                    if (precision !in 1..38 || scale > precision) throw unsupported(type)
                    factory.createSqlType(SqlTypeName.DECIMAL, precision, scale)
                }
            }
        }
        return factory.createTypeWithNullability(result, nullable)
    }
    private fun scalar(factory: RelDataTypeFactory, name: String): RelDataType = when (name) {
        "BOOLEAN" -> factory.createSqlType(SqlTypeName.BOOLEAN)
        "TINYINT" -> factory.createSqlType(SqlTypeName.TINYINT)
        "SMALLINT" -> factory.createSqlType(SqlTypeName.SMALLINT)
        "INTEGER" -> factory.createSqlType(SqlTypeName.INTEGER)
        "BIGINT" -> factory.createSqlType(SqlTypeName.BIGINT)
        "HUGEINT" -> factory.createSqlType(SqlTypeName.DECIMAL, 39, 0)
        "FLOAT" -> factory.createSqlType(SqlTypeName.REAL)
        "DOUBLE" -> factory.createSqlType(SqlTypeName.DOUBLE)
        "VARCHAR" -> factory.createSqlType(SqlTypeName.VARCHAR)
        "BLOB" -> factory.createSqlType(SqlTypeName.VARBINARY)
        "DATE" -> factory.createSqlType(SqlTypeName.DATE)
        "TIME" -> factory.createSqlType(SqlTypeName.TIME, 6)
        "TIMETZ", "TIME WITH TIME ZONE" -> factory.createSqlType(SqlTypeName.TIME_TZ, 6)
        "TIMESTAMP" -> factory.createSqlType(SqlTypeName.TIMESTAMP, 6)
        "TIMESTAMP_NS" -> factory.createSqlType(SqlTypeName.TIMESTAMP, 9)
        "TIMESTAMP_MS" -> factory.createSqlType(SqlTypeName.TIMESTAMP, 3)
        "TIMESTAMP_S" -> factory.createSqlType(SqlTypeName.TIMESTAMP, 0)
        "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE" -> factory.createSqlType(SqlTypeName.TIMESTAMP_TZ, 6)
        else -> throw unsupported(name)
    }
    private fun fieldTypeStart(field: String): Int {
        if (field.startsWith('"')) {
            var index = 1
            while (index < field.length) {
                if (field[index] == '"') {
                    if (index + 1 < field.length && field[index + 1] == '"') index++ else {
                        if (index + 1 >= field.length || !field[index + 1].isWhitespace()) throw unsupported(field)
                        return index + 1
                    }
                }
                index++
            }
        } else field.indices.drop(1).firstOrNull { field[it].isWhitespace() }?.let { return it }
        throw unsupported(field)
    }
    private fun fields(body: String): List<String> {
        val fields = mutableListOf<String>()
        var depth = 0; var start = 0; var quote: Char? = null; var index = 0
        while (index < body.length) {
            val value = body[index]
            if (quote != null) {
                if (value == quote) { if (index + 1 < body.length && body[index + 1] == quote) index++ else quote = null }
            } else when (value) {
                '"', '\'' -> quote = value
                '(', '[' -> depth++
                ')', ']' -> { depth--; if (depth < 0) throw unsupported(body) }
                ',' -> if (depth == 0) { fields += body.substring(start, index).trim(); start = index + 1 }
            }
            index++
        }
        if (depth != 0 || quote != null) throw unsupported(body)
        fields += body.substring(start).trim()
        return fields
    }
    private fun unsupported(type: String) = IllegalArgumentException("unregistered DuckDB source type: $type")
}
