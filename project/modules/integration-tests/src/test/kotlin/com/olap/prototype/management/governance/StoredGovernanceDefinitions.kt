package com.olap.prototype.management.governance

import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.util.OlapJsonUtils
import java.sql.Types
import java.time.Instant

/** 存储边界试验的明确作者数据；不复刻治理或发布规则。 */
internal class StoredGovernanceDefinitions(private val database: PlatformDatabase) {
    val now: Instant = Instant.parse("2026-07-21T00:00:00Z")
    private val mapper = OlapJsonUtils.mapper()

    fun businessAndSubject() {
        execute("INSERT INTO business_line (id,code,created_by,created_at) VALUES ('travel','travel',?,?)", "tester", now)
        version("business_line_version", "business_line_id", "travel", 1, "ACTIVE", mapOf("businessLineId" to "travel", "code" to "travel",
            "displayName" to "旅游", "description" to "旅游业务", "owner" to "platform", "status" to "ACTIVE"))
        execute("INSERT INTO subject_area (id,code,business_line_id,created_by,created_at) VALUES ('trade','trade','travel',?,?)", "tester", now)
        version("subject_area_version", "subject_area_id", "trade", 1, "ACTIVE", mapOf("subjectAreaId" to "trade", "code" to "trade",
            "displayName" to "交易", "businessLineId" to "travel", "businessLineVersion" to 1, "description" to "交易主题域", "owner" to "data", "status" to "ACTIVE"))
    }

    fun logicalTable(id: String, role: String, version: Long = 1, status: String = "ACTIVE", displayName: String = id) {
        if (version == 1L) execute("INSERT INTO logical_table (id,code,created_by,created_at) VALUES (?,?,?,?)", id, id, "tester", now)
        version("logical_table_version", "logical_table_id", id, version, status, mapOf(
            "logicalTableId" to id, "code" to id, "displayName" to displayName, "tableRole" to role,
            "description" to "事务边界测试", "owner" to "data", "status" to "ACTIVE"))
    }

    fun logicUnit(table: String, id: String, code: String, type: String, time: Boolean = false, primaryKey: Boolean = false,
        aggregatable: Boolean = false, version: Long = 1, status: String = "ACTIVE", displayName: String = code, expression: String? = null) {
        if (version == 1L) execute("INSERT INTO logic_unit (id,logical_table_id,code,created_by,created_at) VALUES (?,?,?,?,?)", id, table, code, "tester", now)
        version("logic_unit_version", "logic_unit_id", id, version, status, mapOf(
            "logicUnitId" to id, "logicalTableId" to table, "code" to code, "displayName" to displayName,
            "sourceKind" to if (expression == null) "COLUMN" else "EXPRESSION", "expressionJson" to expression, "logicType" to type, "valueShape" to null,
            "isTime" to time, "isPrimaryKey" to primaryKey, "isForeignKey" to false,
            "filterable" to !aggregatable, "aggregatable" to aggregatable, "status" to "ACTIVE"))
    }

    fun physicalTable(version: Long, displayName: String, tableName: String, columnId: String, columnVersion: Long, status: String = "ACTIVE") {
        if (version == 1L) execute("INSERT INTO physical_table (id,code,created_by,created_at) VALUES (?,?,?,?)", "pt_orders", "ods.orders", "tester", now)
        version("physical_table_version", "physical_table_id", "pt_orders", version, status, mapOf(
            "id" to "pt_orders", "code" to "ods.orders", "displayName" to displayName, "dataSource" to "duckdb", "dialectId" to "duckdb",
            "catalogName" to "", "schemaName" to "main", "tableName" to tableName, "description" to "snapshot", "owner" to "data", "loadStatus" to "LOADED",
            "rowCount" to 100, "schemaHash" to "schema", "status" to "ACTIVE", "sourceConstraints" to null,
            "columnVersionRefs" to listOf(mapOf("id" to columnId, "version" to columnVersion))))
    }

    fun physicalColumn(id: String, name: String, position: Int = 1, version: Long = 1, status: String = "ACTIVE", displayName: String = name, dataType: String = "varchar") {
        if (version == 1L) execute("INSERT INTO physical_column (id,physical_table_id,created_by,created_at) VALUES (?,?,?,?)", id, "pt_orders", "tester", now)
        val type = when (dataType) { "decimal" -> JdbcColumnType(Types.DECIMAL, "DECIMAL", 18, 2, false); "varchar" -> JdbcColumnType(Types.VARCHAR, "VARCHAR", 128, null, false); else -> error("fixture type not declared: $dataType") }
        version("physical_column_version", "physical_column_id", id, version, status, mapOf(
            "id" to id, "physicalTableId" to "pt_orders", "columnName" to name, "displayName" to displayName, "dataType" to dataType,
            "description" to "snapshot", "nullable" to false, "ordinalPosition" to position, "enabled" to true, "status" to "ACTIVE", "jdbcColumnType" to type))
    }

    fun execute(sql: String, vararg values: Any?) {
        database.connection().use { connection -> connection.prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        } }
    }

    fun count(table: String, column: String? = null, value: String? = null, like: Boolean = false): Long {
        val sql = "SELECT count(*) FROM $table" + if (column == null) "" else " WHERE $column ${if (like) "LIKE" else "="} ?"
        return database.connection().use { connection -> connection.prepareStatement(sql).use { statement ->
            if (column != null) statement.setString(1, value)
            statement.executeQuery().use { check(it.next()); it.getLong(1) }
        } }
    }

    private fun version(table: String, identity: String, id: String, version: Long, status: String, content: Any) {
        execute("INSERT INTO $table (id,$identity,version,version_status,content_json,diagnostic_json,source_version,created_by,created_at,activated_by,activated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            "$id:v$version", id, version, status, mapper.writeValueAsString(content), "{}", if (version == 1L) null else version - 1,
            "tester", now, if (status == "DRAFT") null else "tester", if (status == "DRAFT") null else now)
    }
}
