package com.olap.prototype.warehouse.contract

import com.olap.prototype.contract.JdbcColumnType

/** 一份已治理的精确物理数据合同，不选择其执行实现。 */
data class PhysicalTableRef(val id: String, val version: Long) {
    init { require(id.isNotBlank() && version > 0) { "physical table reference requires an exact identity and version" } }
    fun key(): String = "$id@$version"
}

class PhysicalDataContract(
    val reference: PhysicalTableRef,
    val dataSourceId: String,
    val table: JdbcTableConstraints.Table,
    columns: Map<String, Column>,
    val constraints: JdbcTableConstraints,
    snapshotUniqueKeys: List<SnapshotUniqueKey>,
    sourceCollections: List<SourceCollectionDeclaration>,
    val originDiscoveredTableId: String,
) {
    data class Column(val ref: String, val type: JdbcColumnType) {
        init {
            require(ref.matches(Regex(".+@[1-9][0-9]*"))) { "physical column contract requires an exact column version" }
            require(type.nullable != null) { "physical column contract requires explicit nullability" }
        }
    }
    val columns: Map<String, Column> = java.util.Collections.unmodifiableMap(LinkedHashMap(columns))
    val snapshotUniqueKeys: List<SnapshotUniqueKey> = java.util.List.copyOf(snapshotUniqueKeys)
    val sourceCollections: List<SourceCollectionDeclaration> = java.util.List.copyOf(sourceCollections)
    init {
        require(dataSourceId.isNotBlank() && originDiscoveredTableId.isNotBlank()) { "physical data contract requires its stable source and structure origin" }
        require(this.columns.isNotEmpty() && this.columns.keys.none(String::isBlank) &&
            this.columns.values.map { it.ref }.distinct().size == this.columns.size) { "physical data contract requires named distinct exact column versions" }
        require(this.snapshotUniqueKeys.map { it.rule.name }.distinct().size == this.snapshotUniqueKeys.size) { "physical snapshot key names must be distinct" }
        require(this.sourceCollections.map { it.key() }.distinct().size == this.sourceCollections.size) { "physical collection declarations must be distinct" }
        val required = buildSet {
            addAll(constraints.primaryKeyColumns)
            constraints.uniqueKeys.forEach { addAll(it.columns) }
            constraints.importedKeys.forEach { add(it.column) }
            constraints.temporalKeys.forEach { addAll(it.rule.columns()) }
            this@PhysicalDataContract.snapshotUniqueKeys.forEach { addAll(it.rule.columns) }
        }
        require(this.columns.keys.containsAll(required)) { "physical constraints refer to columns outside the exact data contract" }
        require(this.sourceCollections.all { it.collection.table == table }) { "physical collection declaration differs from its exact table" }
    }
    override fun equals(other: Any?): Boolean = other is PhysicalDataContract && reference == other.reference &&
        dataSourceId == other.dataSourceId && table == other.table && columns == other.columns && constraints == other.constraints &&
        snapshotUniqueKeys == other.snapshotUniqueKeys && sourceCollections == other.sourceCollections && originDiscoveredTableId == other.originDiscoveredTableId
    override fun hashCode(): Int = listOf(reference, dataSourceId, table, columns, constraints, snapshotUniqueKeys, sourceCollections, originDiscoveredTableId).hashCode()
}

/** 仓库采用只需读取合同，不依赖治理实现。 */
fun interface PhysicalDataContractReader {
    fun readPhysicalDataContract(reference: PhysicalTableRef): PhysicalDataContract
}

/** 生产者的原精确声明；不包含某次访问实现的核验结论。 */
data class SourceCollectionDeclaration(
    val declarationSha256: String,
    val sourceAuthority: String,
    val ruleDocumentSha256: String,
    val anchor: JdbcTableConstraints.DataAnchor,
    val scope: SealedSourceDataset.Scope,
    val collection: SealedSourceDataset.Collection,
) {
    init {
        require(listOf(declarationSha256, ruleDocumentSha256).all { it.matches(Regex("[0-9a-f]{64}")) }) { "source declaration requires exact SHA-256 identities" }
        require(sourceAuthority.isNotBlank()) { "source declaration authority is required" }
    }
    fun key(): String = "$declarationSha256:${collection.code}"
}
