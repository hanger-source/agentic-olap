package com.olap.prototype.storage.schema

import java.sql.Connection
import java.sql.SQLException

/** 从实际 H2 元数据读取结构，排除环境库名、匿名对象名和可变执行计数。 */
internal class PlatformSchemaStructure private constructor(private val objects: Map<String, List<Definition>>) {
    private data class Definition(val attributes: Map<String, String?>, val columns: List<Map<String, String?>> = emptyList(), val referencedColumns: List<Map<String, String?>> = emptyList())

    fun sameStructure(other: PlatformSchemaStructure): Boolean = objects == other.objects

    fun requireSameStructure(actual: PlatformSchemaStructure) {
        for ((kind, expected) in objects) {
            val observed = actual.objects[kind] ?: throw SQLException("Platform storage structure is missing $kind")
            if (expected != observed) throw SQLException("Platform storage structure differs in $kind; expected ${difference(expected, observed)}; actual ${difference(observed, expected)}")
        }
    }

    companion object {
        private const val USER_SCHEMA = " <> 'INFORMATION_SCHEMA'"
        private val anonymousConstraint = Regex("CONSTRAINT_[0-9A-F]+(?:_[0-9]+)?")

        fun read(connection: Connection): PlatformSchemaStructure {
            val objects = linkedMapOf<String, List<Definition>>()
            fun capture(kind: String, sql: String) { objects[kind] = ordered(rows(connection, sql).map { Definition(it) }) }
            capture("schemas", "SELECT * FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME$USER_SCHEMA")
            capture("tables", """
                SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE, IS_INSERTABLE_INTO, COMMIT_ACTION
                FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA <> 'INFORMATION_SCHEMA'
            """.trimIndent())
            capture("columns", """
                SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, COLUMN_DEFAULT,
                       IS_NULLABLE, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, CHARACTER_OCTET_LENGTH,
                       NUMERIC_PRECISION, NUMERIC_PRECISION_RADIX, NUMERIC_SCALE, DATETIME_PRECISION,
                       INTERVAL_TYPE, INTERVAL_PRECISION, CHARACTER_SET_SCHEMA, CHARACTER_SET_NAME,
                       COLLATION_SCHEMA, COLLATION_NAME, DOMAIN_SCHEMA, DOMAIN_NAME, MAXIMUM_CARDINALITY,
                       DTD_IDENTIFIER, IS_IDENTITY, IDENTITY_GENERATION, IDENTITY_START, IDENTITY_INCREMENT,
                       IDENTITY_MAXIMUM, IDENTITY_MINIMUM, IDENTITY_CYCLE, IDENTITY_CACHE,
                       IS_GENERATED, GENERATION_EXPRESSION, DECLARED_DATA_TYPE,
                       DECLARED_NUMERIC_PRECISION, DECLARED_NUMERIC_SCALE, GEOMETRY_TYPE, GEOMETRY_SRID,
                       COLUMN_ON_UPDATE, IS_VISIBLE, DEFAULT_ON_NULL
                FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA <> 'INFORMATION_SCHEMA'
            """.trimIndent())
            capture("sequences", """
                SELECT SEQUENCE_SCHEMA, SEQUENCE_NAME, DATA_TYPE, NUMERIC_PRECISION,
                       NUMERIC_PRECISION_RADIX, NUMERIC_SCALE, START_VALUE, MINIMUM_VALUE,
                       MAXIMUM_VALUE, INCREMENT, CYCLE_OPTION, DECLARED_DATA_TYPE,
                       DECLARED_NUMERIC_PRECISION, DECLARED_NUMERIC_SCALE, CACHE
                FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_SCHEMA <> 'INFORMATION_SCHEMA'
            """.trimIndent())
            objects["constraints"] = constraints(connection)
            objects["indexes"] = indexes(connection)
            for ((table, schemaColumn) in mapOf("DOMAINS" to "DOMAIN_SCHEMA", "DOMAIN_CONSTRAINTS" to "DOMAIN_SCHEMA",
                "ELEMENT_TYPES" to "OBJECT_SCHEMA", "FIELDS" to "OBJECT_SCHEMA", "VIEWS" to "TABLE_SCHEMA",
                "TRIGGERS" to "TRIGGER_SCHEMA", "ROUTINES" to "ROUTINE_SCHEMA", "CONSTANTS" to "CONSTANT_SCHEMA", "SYNONYMS" to "SYNONYM_SCHEMA"))
                capture(table, "SELECT * FROM INFORMATION_SCHEMA.$table WHERE $schemaColumn$USER_SCHEMA")
            return PlatformSchemaStructure(objects.toMap())
        }

        private fun constraints(connection: Connection): List<Definition> {
            val columns = groupedColumns(connection, """
                SELECT CONSTRAINT_SCHEMA, CONSTRAINT_NAME, COLUMN_NAME, ORDINAL_POSITION, POSITION_IN_UNIQUE_CONSTRAINT
                FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE WHERE CONSTRAINT_SCHEMA <> 'INFORMATION_SCHEMA' ORDER BY ORDINAL_POSITION
            """.trimIndent(), "CONSTRAINT_SCHEMA", "CONSTRAINT_NAME")
            return ordered(rows(connection, """
                SELECT t.CONSTRAINT_SCHEMA, t.CONSTRAINT_NAME, t.CONSTRAINT_TYPE,
                       t.TABLE_SCHEMA, t.TABLE_NAME, t.IS_DEFERRABLE, t.INITIALLY_DEFERRED,
                       t.ENFORCED, t.NULLS_DISTINCT, c.CHECK_CLAUSE,
                       r.MATCH_OPTION, r.UPDATE_RULE, r.DELETE_RULE,
                       u.CONSTRAINT_SCHEMA AS REFERENCED_CONSTRAINT_SCHEMA,
                       u.CONSTRAINT_NAME AS REFERENCED_CONSTRAINT_NAME,
                       u.TABLE_SCHEMA AS REFERENCED_TABLE_SCHEMA, u.TABLE_NAME AS REFERENCED_TABLE_NAME
                FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS t
                LEFT JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS c
                  ON c.CONSTRAINT_CATALOG=t.CONSTRAINT_CATALOG
                 AND c.CONSTRAINT_SCHEMA=t.CONSTRAINT_SCHEMA AND c.CONSTRAINT_NAME=t.CONSTRAINT_NAME
                LEFT JOIN INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS r
                  ON r.CONSTRAINT_CATALOG=t.CONSTRAINT_CATALOG
                 AND r.CONSTRAINT_SCHEMA=t.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME=t.CONSTRAINT_NAME
                LEFT JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS u
                  ON u.CONSTRAINT_CATALOG=r.UNIQUE_CONSTRAINT_CATALOG
                 AND u.CONSTRAINT_SCHEMA=r.UNIQUE_CONSTRAINT_SCHEMA AND u.CONSTRAINT_NAME=r.UNIQUE_CONSTRAINT_NAME
                WHERE t.TABLE_SCHEMA <> 'INFORMATION_SCHEMA'
            """.trimIndent()).map { row ->
                val attributes = row.toSortedMap()
                for (name in listOf("CONSTRAINT_NAME", "REFERENCED_CONSTRAINT_NAME")) attributes[name]?.let {
                    if (anonymousConstraint.matches(it)) attributes[name] = "<anonymous>"
                }
                Definition(attributes, columns[key(row, "CONSTRAINT_SCHEMA", "CONSTRAINT_NAME")].orEmpty(),
                    columns[key(row, "REFERENCED_CONSTRAINT_SCHEMA", "REFERENCED_CONSTRAINT_NAME")].orEmpty())
            })
        }

        private fun indexes(connection: Connection): List<Definition> {
            val columns = groupedColumns(connection, """
                SELECT INDEX_SCHEMA, INDEX_NAME, COLUMN_NAME, ORDINAL_POSITION, ORDERING_SPECIFICATION, NULL_ORDERING, IS_UNIQUE
                FROM INFORMATION_SCHEMA.INDEX_COLUMNS WHERE TABLE_SCHEMA <> 'INFORMATION_SCHEMA' ORDER BY ORDINAL_POSITION
            """.trimIndent(), "INDEX_SCHEMA", "INDEX_NAME")
            return ordered(rows(connection, """
                SELECT INDEX_SCHEMA, INDEX_NAME, TABLE_SCHEMA, TABLE_NAME, INDEX_TYPE_NAME, NULLS_DISTINCT, IS_GENERATED
                FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_SCHEMA <> 'INFORMATION_SCHEMA'
            """.trimIndent()).map { row ->
                val attributes = row.toSortedMap()
                if (attributes["IS_GENERATED"].toBoolean()) attributes["INDEX_NAME"] = "<anonymous>"
                Definition(attributes, columns[key(row, "INDEX_SCHEMA", "INDEX_NAME")].orEmpty())
            })
        }

        private fun groupedColumns(connection: Connection, sql: String, schema: String, name: String): Map<Pair<String?, String?>, List<Map<String, String?>>> =
            rows(connection, sql).groupBy { key(it, schema, name) }.mapValues { (_, values) -> values.map { row -> row.filterKeys { it != schema && it != name }.toSortedMap() } }

        private fun key(row: Map<String, String?>, schema: String, name: String): Pair<String?, String?> = row[schema] to row[name]

        private fun rows(connection: Connection, sql: String): List<Map<String, String?>> = connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                val metadata = result.metaData
                buildList {
                    while (result.next()) add(buildMap {
                        for (column in 1..metadata.columnCount) {
                            val name = metadata.getColumnLabel(column)
                            if (!name.endsWith("_CATALOG") && name != "CATALOG_NAME" && name != "REMARKS") put(name, result.getString(column))
                        }
                    }.toSortedMap())
                }
            }
        }

        private fun ordered(definitions: List<Definition>): List<Definition> = definitions.sortedBy { it.toString() }
        private fun difference(left: List<Definition>, right: List<Definition>): Any = left.firstOrNull { it !in right } ?: "object count ${left.size}"
    }
}
