package com.olap.prototype.warehouse.inspection

import com.olap.prototype.warehouse.contract.SourceConfiguration

/** 技术目录限定保留为空，空白目录名不成为另一个物理地址。 */
class WarehouseTableRef(catalog: String?, schema: String?, table: String) {
    val catalog: String? = normalize(catalog)
    val schema: String? = normalize(schema)
    val table: String = table.trim().also { require(it.isNotEmpty()) { "table 不能为空" } }
    override fun equals(other: Any?): Boolean = other is WarehouseTableRef && catalog == other.catalog && schema == other.schema && table == other.table
    override fun hashCode(): Int = listOf(catalog, schema, table).hashCode()
    override fun toString(): String = "WarehouseTableRef(catalog=$catalog, schema=$schema, table=$table)"
}

class WarehouseDiscoveryRequest(
    val connection: SourceConfiguration,
    catalog: String?,
    schemaPattern: String?,
    tablePattern: String?,
    tableTypes: List<String>?,
    val limit: Int?,
) {
    val catalog: String? = normalize(catalog)
    val schemaPattern: String? = normalize(schemaPattern)
    val tablePattern: String? = normalize(tablePattern)
    val tableTypes: List<String> = inspectionTableTypes(tableTypes)
    init { require(limit == null || limit in 1..5000) { "limit 必须在 1 到 5000 之间" } }
    fun limitOrDefault(): Int = limit ?: 500
    override fun equals(other: Any?): Boolean = other is WarehouseDiscoveryRequest && connection == other.connection && catalog == other.catalog && schemaPattern == other.schemaPattern && tablePattern == other.tablePattern && tableTypes == other.tableTypes && limit == other.limit
    override fun hashCode(): Int = listOf(connection, catalog, schemaPattern, tablePattern, tableTypes, limit).hashCode()
}

/** 精确 schema 范围的完整结构快照。 */
class WarehouseSchemaSnapshotRequest(
    val connection: SourceConfiguration,
    catalog: String?,
    schemaName: String,
    tablePattern: String?,
    tableTypes: List<String>?,
    val limit: Int?,
) {
    val catalog: String? = normalize(catalog)
    val schemaName: String = schemaName.trim().also { require(it.isNotEmpty()) { "schemaName 不能为空" } }
    val tablePattern: String? = normalize(tablePattern)
    val tableTypes: List<String> = inspectionTableTypes(tableTypes)
    init { require(limit == null || limit in 1..5000) { "limit 必须在 1 到 5000 之间" } }
    fun limitOrDefault(): Int = limit ?: 500
    override fun equals(other: Any?): Boolean = other is WarehouseSchemaSnapshotRequest && connection == other.connection && catalog == other.catalog && schemaName == other.schemaName && tablePattern == other.tablePattern && tableTypes == other.tableTypes && limit == other.limit
    override fun hashCode(): Int = listOf(connection, catalog, schemaName, tablePattern, tableTypes, limit).hashCode()
}

data class WarehouseTableInspectionRequest(val connection: SourceConfiguration, val table: WarehouseTableRef)

data class WarehousePreviewRequest(val connection: SourceConfiguration, val table: WarehouseTableRef, val limit: Int?) {
    init { require(limit == null || limit in 1..200) { "limit 必须在 1 到 200 之间" } }
    fun limitOrDefault(): Int = limit ?: 20
}

/** 精确列的全表统计，不接受表达式、过滤或调用者 SQL。 */
class WarehouseProfileRequest(val connection: SourceConfiguration, val table: WarehouseTableRef, columns: List<String>, val timeoutSeconds: Int?) {
    val columns: List<String> = java.util.List.copyOf(columns)
    init {
        require(this.columns.size in 1..32 && this.columns.all { it.isNotBlank() } && this.columns.distinct().size == this.columns.size) { "columns 必须包含 1 到 32 个不重复的精确列名" }
        require(timeoutSeconds == null || timeoutSeconds in 1..120) { "timeoutSeconds 必须在 1 到 120 之间" }
    }
    fun timeoutOrDefault(): Int = timeoutSeconds ?: 30
    override fun equals(other: Any?): Boolean = other is WarehouseProfileRequest && connection == other.connection && table == other.table && columns == other.columns && timeoutSeconds == other.timeoutSeconds
    override fun hashCode(): Int = listOf(connection, table, columns, timeoutSeconds).hashCode()
}

private fun normalize(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

/** 两个结构调查入口共用同一 table type 约定；不混入业务表选择。 */
private fun inspectionTableTypes(values: List<String>?): List<String> =
    if (values.isNullOrEmpty()) listOf("TABLE", "BASE TABLE", "VIEW")
    else java.util.List.copyOf(values.map { value ->
        require(value.isNotBlank()) { "tableTypes 不能包含空值" }
        value.trim()
    })
