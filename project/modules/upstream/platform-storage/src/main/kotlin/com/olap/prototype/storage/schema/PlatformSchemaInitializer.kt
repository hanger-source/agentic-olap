package com.olap.prototype.storage.schema

import org.h2.tools.RunScript
import java.io.StringReader
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/** 空库初始化和格式核对都依据同一规范 DDL；不升级、不猜测已有数据库结构。 */
object PlatformSchemaInitializer {
    private const val RESOURCE = "/db/schema/platform-schema.sql"
    private val schema: String by lazy {
        val stream = PlatformSchemaInitializer::class.java.getResourceAsStream(RESOURCE)
            ?: error("Platform schema resource not found: $RESOURCE")
        stream.use { it.readAllBytes().toString(Charsets.UTF_8) }
    }
    private data class Reference(val empty: PlatformSchemaStructure, val version: Int, val structure: PlatformSchemaStructure)
    private val reference: Reference by lazy {
        try {
            DriverManager.getConnection("jdbc:h2:mem:platform_schema_reference_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_UPPER=false", "sa", "").use { connection ->
                val empty = PlatformSchemaStructure.read(connection)
                executeSchema(connection)
                Reference(empty, formatVersion(connection), PlatformSchemaStructure.read(connection))
            }
        } catch (failure: SQLException) { throw IllegalStateException("Cannot derive platform schema from canonical DDL", failure) }
    }
    val supportedFormatVersion: Int get() = reference.version

    fun initializeOrValidate(connection: Connection) {
        val expected = reference
        var actual = PlatformSchemaStructure.read(connection)
        if (expected.empty.sameStructure(actual)) {
            executeSchema(connection)
            actual = PlatformSchemaStructure.read(connection)
        }
        val version = formatVersion(connection)
        if (version != expected.version) throw SQLException("Unsupported platform storage format: $version; supported format: ${expected.version}")
        expected.structure.requireSameStructure(actual)
    }

    // SQL 的引号、注释和语句边界由实际数据库的脚本读取器解释，不再维护第二份手写分割器。
    private fun executeSchema(connection: Connection) { RunScript.execute(connection, StringReader(schema)) }

    private fun formatVersion(connection: Connection): Int = try {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT singleton_id, format_version FROM platform_storage_format").use { rows ->
                if (!rows.next() || rows.getInt("singleton_id") != 1) throw SQLException("Platform storage format singleton is missing or invalid")
                val version = rows.getInt("format_version")
                if (rows.wasNull() || version < 1 || rows.next()) throw SQLException("Platform storage format must contain exactly one valid singleton")
                version
            }
        }
    } catch (failure: SQLException) {
        throw SQLException("Cannot establish platform storage format; incomplete or unversioned storage is unsupported", failure)
    }
}
