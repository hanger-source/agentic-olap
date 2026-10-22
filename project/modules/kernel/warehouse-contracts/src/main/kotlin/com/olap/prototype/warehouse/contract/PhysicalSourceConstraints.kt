package com.olap.prototype.warehouse.contract

import com.olap.prototype.connector.ConnectorRef

/** 物理版本采用的调查、精确列身份和有限基础表声明。 */
class PhysicalSourceConstraints(
    val discoverySnapshotId: String,
    val schemaHash: String,
    val sourceIdentityHash: String,
    val connectorRef: ConnectorRef,
    val databaseProductName: String?,
    val databaseProductVersion: String?,
    val driverName: String?,
    val driverVersion: String?,
    val constraints: JdbcTableConstraints,
    columnVersionRefs: Map<String, String>,
    sourceCollections: List<SourceCollectionRecord>,
    val structureProvenance: StructureProvenance,
) {
    val columnVersionRefs: Map<String, String> = java.util.Map.copyOf(columnVersionRefs)
    val sourceCollections: List<SourceCollectionRecord> = java.util.List.copyOf(sourceCollections)
    init {
        require(sourceIdentityHash.matches(Regex("[0-9a-f]{64}"))) { "source constraint evidence requires an exact deployment identity hash" }
        require(discoverySnapshotId.isNotBlank() && schemaHash.isNotBlank()) { "source constraint evidence requires discovery snapshot and schema hash" }
        if (structureProvenance.kind == StructureProvenance.Kind.DATABASE_CATALOG)
            require(listOf(databaseProductName, databaseProductVersion, driverName, driverVersion).all { !it.isNullOrBlank() }) { "source evidence requires exact database and driver technology" }
        require(this.sourceCollections.size <= 64 && this.sourceCollections.map { it.id }.distinct().size == this.sourceCollections.size) { "physical source collections require bounded distinct exact identities" }
        require(this.sourceCollections.all { it.sourceIdentityHash == sourceIdentityHash }) { "physical source collection differs from exact deployment identity" }
        val required = linkedSetOf<String>().apply {
            addAll(constraints.primaryKeyColumns)
            constraints.uniqueKeys.forEach { addAll(it.columns) }
            constraints.importedKeys.forEach { add(it.column) }
            constraints.temporalKeys.forEach { addAll(it.rule.columns()) }
        }
        require(this.columnVersionRefs.keys.containsAll(required)) { "source constraints refer to columns outside exact physical version" }
        require(this.columnVersionRefs.values.all { it.matches(Regex(".+@[1-9][0-9]*")) } && this.columnVersionRefs.values.distinct().size == this.columnVersionRefs.size) { "source constraints require distinct exact physical column refs" }
    }
    override fun equals(other: Any?): Boolean = other is PhysicalSourceConstraints &&
        discoverySnapshotId == other.discoverySnapshotId && schemaHash == other.schemaHash && sourceIdentityHash == other.sourceIdentityHash &&
        connectorRef == other.connectorRef && databaseProductName == other.databaseProductName && databaseProductVersion == other.databaseProductVersion &&
        driverName == other.driverName && driverVersion == other.driverVersion && constraints == other.constraints && columnVersionRefs == other.columnVersionRefs &&
        sourceCollections == other.sourceCollections && structureProvenance == other.structureProvenance
    override fun hashCode(): Int = listOf(discoverySnapshotId, schemaHash, sourceIdentityHash, connectorRef, databaseProductName, databaseProductVersion, driverName, driverVersion, constraints, columnVersionRefs, sourceCollections, structureProvenance).hashCode()
}
