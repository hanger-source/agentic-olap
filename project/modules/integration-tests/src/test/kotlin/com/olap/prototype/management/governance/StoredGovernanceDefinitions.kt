package com.olap.prototype.management.governance

import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.governance.contract.*
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.warehouse.contract.*
import java.sql.Types
import java.time.Instant

/** 存储边界试验的明确作者数据；不复刻治理或发布规则。 */
internal class StoredGovernanceDefinitions(private val database: PlatformDatabase) {
    val now: Instant = Instant.parse("2026-07-21T00:00:00Z")

    fun businessAndSubject() {
        execute("INSERT INTO business_line (id,code,created_by,created_at) VALUES ('travel','travel',?,?)", "tester", now)
        version("business_line_version", "business_line_id", "travel", 1, "ACTIVE", GovernanceDefinition.BusinessLine(
            BusinessLineRecord("travel", "travel", "旅游", "旅游业务", "platform", "ACTIVE", 1, "tester", now, null, null)))
        execute("INSERT INTO subject_area (id,code,business_line_id,created_by,created_at) VALUES ('trade','trade','travel',?,?)", "tester", now)
        version("subject_area_version", "subject_area_id", "trade", 1, "ACTIVE", GovernanceDefinition.SubjectArea(
            SubjectAreaRecord("trade", "trade", "交易", "travel", 1, "交易主题域", "data", "ACTIVE", 1, "tester", now, null, null)))
    }

    fun logicalTable(id: String, role: String, version: Long = 1, status: String = "ACTIVE", displayName: String = id) {
        if (version == 1L) execute("INSERT INTO logical_table (id,code,created_by,created_at) VALUES (?,?,?,?)", id, id, "tester", now)
        version("logical_table_version", "logical_table_id", id, version, status, GovernanceDefinition.LogicalTable(
            LogicalTableRecord(id, id, displayName, role, "事务边界测试", "data", status, version, "tester", now, null, null), "pt_orders", 1, 0))
    }

    fun logicUnit(table: String, id: String, code: String, type: String, time: Boolean = false, primaryKey: Boolean = false,
        aggregatable: Boolean = false, version: Long = 1, status: String = "ACTIVE", displayName: String = code, expression: SemanticExpression? = null) {
        if (version == 1L) execute("INSERT INTO logic_unit (id,logical_table_id,code,created_by,created_at) VALUES (?,?,?,?,?)", id, table, code, "tester", now)
        version("logic_unit_version", "logic_unit_id", id, version, status, GovernanceDefinition.LogicUnit(
            LogicUnitRecord(id, table, code, displayName, if (expression == null) "COLUMN" else "EXPRESSION", expression, type, null,
                time, primaryKey, false, !aggregatable, aggregatable, status, version, "tester", now, null, null), emptyList()))
    }

    fun physicalTable(version: Long, displayName: String, tableName: String, columnId: String, columnVersion: Long,
        columnName: String, columnType: JdbcColumnType, status: String = "ACTIVE") {
        if (version == 1L) execute("INSERT INTO physical_table (id,code,created_by,created_at) VALUES (?,?,?,?)", "pt_orders", "ods.orders", "tester", now)
        val contract = PhysicalDataContract(PhysicalTableRef("pt_orders", version), "duckdb", JdbcTableConstraints.Table(null, "main", tableName),
            mapOf(columnName to PhysicalDataContract.Column("$columnId@$columnVersion", columnType)),
            JdbcTableConstraints(emptyList(), emptyList(), emptyList(), emptyList()), emptyList(), emptyList(), "declared-storage-fixture")
        version("physical_table_version", "physical_table_id", "pt_orders", version, status, GovernanceDefinition.PhysicalTable(
            PhysicalTableRecord("pt_orders", "ods.orders", displayName, "duckdb", null, "main", tableName, "snapshot", "data", "LOADED", 100,
                status, version, "tester", now, null, null), listOf(GovernanceDefinition.VersionRef(columnId, columnVersion)), contract, null, null, null))
    }

    fun physicalColumn(id: String, name: String, position: Int = 1, version: Long = 1, status: String = "ACTIVE", displayName: String = name, dataType: String = "varchar") {
        if (version == 1L) execute("INSERT INTO physical_column (id,physical_table_id,created_by,created_at) VALUES (?,?,?,?)", id, "pt_orders", "tester", now)
        val type = when (dataType) { "decimal" -> JdbcColumnType(Types.DECIMAL, "DECIMAL", 18, 2, false); "varchar" -> JdbcColumnType(Types.VARCHAR, "VARCHAR", 128, null, false); else -> error("fixture type not declared: $dataType") }
        version("physical_column_version", "physical_column_id", id, version, status, GovernanceDefinition.PhysicalColumn(
            PhysicalColumnRecord(id, "pt_orders", name, displayName, dataType, "snapshot", false, position, true, status, version, "tester", now, null, type), null))
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

    private fun version(table: String, identity: String, id: String, version: Long, status: String, content: GovernanceDefinition) {
        execute("INSERT INTO $table (id,$identity,version,version_status,content_proto,diagnostic_json,source_version,created_by,created_at,activated_by,activated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            "$id:v$version", id, version, status, ProtoBytes.storage(GovernanceProto.definition(content)), "{}", if (version == 1L) null else version - 1,
            "tester", now, if (status == "DRAFT") null else "tester", if (status == "DRAFT") null else now)
    }
}
