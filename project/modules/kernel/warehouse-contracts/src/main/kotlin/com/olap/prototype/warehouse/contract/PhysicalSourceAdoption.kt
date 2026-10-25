package com.olap.prototype.warehouse.contract

import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.contract.JdbcColumnType
import java.time.Instant

/** 不可变兑现采用的内容地址；观察改变会产生另一份采用。 */
data class SourceAdoptionRef(val id: String) {
    init { require(id.matches(Regex("source-adoption:[0-9a-f]{64}"))) { "source adoption requires its exact content identity" } }
}

data class SnapshotUniqueKeyProof(val id: String, val key: SnapshotUniqueKey) {
    init { require(id.isNotBlank()) { "source key proof requires its exact observation identity" } }
}

class PhysicalSourceEvidence(
    val discoverySnapshotId: String,
    val discoveredTableId: String,
    val schemaHash: String,
    val structureProvenance: StructureProvenance,
    val databaseProductName: String?,
    val databaseProductVersion: String?,
    val driverName: String?,
    val driverVersion: String?,
    columns: Map<String, JdbcColumnType>,
    val constraints: JdbcTableConstraints,
    snapshotUniqueKeys: List<SnapshotUniqueKeyProof>,
    sourceCollections: List<SourceCollectionRecord>,
) {
    val columns: Map<String, JdbcColumnType> = java.util.Collections.unmodifiableMap(LinkedHashMap(columns))
    val snapshotUniqueKeys: List<SnapshotUniqueKeyProof> = java.util.List.copyOf(snapshotUniqueKeys)
    val sourceCollections: List<SourceCollectionRecord> = java.util.List.copyOf(sourceCollections)
    init {
        require(listOf(discoverySnapshotId, discoveredTableId, schemaHash).none(String::isBlank)) { "source evidence requires an exact structure observation" }
        if (structureProvenance.kind == StructureProvenance.Kind.DATABASE_CATALOG)
            require(listOf(databaseProductName, databaseProductVersion, driverName, driverVersion).all { !it.isNullOrBlank() }) { "catalog evidence requires exact observed database and driver technology" }
        require(this.columns.keys.none(String::isBlank)) { "source evidence requires explicit observed column names" }
        require(this.snapshotUniqueKeys.map { it.id }.distinct().size == this.snapshotUniqueKeys.size &&
            this.snapshotUniqueKeys.map { it.key.rule.name }.distinct().size == this.snapshotUniqueKeys.size) { "source key observations must be distinct" }
        require(this.sourceCollections.map { it.id }.distinct().size == this.sourceCollections.size) { "source collection observations must be distinct" }
    }
    override fun equals(other: Any?): Boolean = other is PhysicalSourceEvidence && discoverySnapshotId == other.discoverySnapshotId &&
        discoveredTableId == other.discoveredTableId && schemaHash == other.schemaHash && structureProvenance == other.structureProvenance &&
        databaseProductName == other.databaseProductName && databaseProductVersion == other.databaseProductVersion && driverName == other.driverName &&
        driverVersion == other.driverVersion && columns == other.columns && constraints == other.constraints &&
        snapshotUniqueKeys == other.snapshotUniqueKeys && sourceCollections == other.sourceCollections
    override fun hashCode(): Int = listOf(discoverySnapshotId, discoveredTableId, schemaHash, structureProvenance, databaseProductName,
        databaseProductVersion, driverName, driverVersion, columns, constraints, snapshotUniqueKeys, sourceCollections).hashCode()
}

data class PhysicalSourceAdoption(
    val reference: SourceAdoptionRef,
    val physicalTable: PhysicalTableRef,
    val binding: SourceBindingRef,
    val connectorRef: ConnectorRef,
    val dialect: String,
    val table: JdbcTableConstraints.Table,
    val evidence: PhysicalSourceEvidence,
    val adoptedBy: String,
    val adoptedAt: Instant,
) {
    init {
        require(dialect.isNotBlank() && adoptedBy.isNotBlank()) { "source adoption requires an exact dialect and actor" }
        require(evidence.sourceCollections.all { it.dataSourceId == binding.dataSourceId && it.sourceIdentityHash == binding.sourceIdentityHash && it.collection.table == table }) {
            "source collection evidence differs from the exact adopted binding or table"
        }
    }

    /** 核对冻结证据能兑现P；不把登记观察当作本次执行已经核验。 */
    fun requireContract(contract: PhysicalDataContract) {
        require(physicalTable == contract.reference && binding.dataSourceId == contract.dataSourceId && table == contract.table) { "source adoption differs from its exact physical data contract" }
        contract.columns.forEach { (name, column) ->
            require(evidence.columns[name] == column.type) { "source adoption does not fulfill physical column: ${contract.reference.key()}.$name" }
        }
        val expected = contract.constraints
        val actual = evidence.constraints
        require(expected.primaryKeyColumns.isEmpty() || expected.primaryKeyColumns == actual.primaryKeyColumns) { "source adoption does not fulfill the physical primary key" }
        require(actual.uniqueKeys.containsAll(expected.uniqueKeys)) { "source adoption does not fulfill physical unique constraints" }
        require(expected.importedKeys.groupBy { it.name }.all { (name, keys) -> actual.importedKeys.filter { it.name == name } == keys }) { "source adoption does not fulfill physical foreign constraints" }
        require(actual.temporalKeys.containsAll(expected.temporalKeys)) { "source adoption does not fulfill physical temporal constraints and anchors" }
        require(evidence.snapshotUniqueKeys.map { it.key }.containsAll(contract.snapshotUniqueKeys)) { "source adoption does not fulfill physical snapshot keys and anchors" }
        require(evidence.sourceCollections.map { it.declaration() }.containsAll(contract.sourceCollections)) { "source adoption does not fulfill the exact physical collection declarations" }
    }
}

class SourceAdoptions(items: List<PhysicalSourceAdoption>) {
    val items: List<PhysicalSourceAdoption> = java.util.List.copyOf(items)
    val references: List<SourceAdoptionRef> get() = java.util.List.copyOf(items.map { it.reference })
    companion object {
        fun contract(): Map<String, Any> = mapOf(
            "items" to "IMMUTABLE_EXPLICIT_ADOPTIONS_NOT_A_LATEST_SELECTION",
            "references" to "DERIVED_FROM_ITEMS_PASS_DIRECTLY_TO_BUILD_RELEASE",
            "samePhysicalContract" to "MULTIPLE_ADOPTIONS_MAY_EXIST_FOR_DIFFERENT_EXACT_OBSERVATIONS_OR_BINDINGS",
        )
    }
    override fun equals(other: Any?): Boolean = other is SourceAdoptions && items == other.items
    override fun hashCode(): Int = items.hashCode()
}

class SourceAdoptionRegistrationRequest(physicalTables: List<PhysicalTableRef>, val discoverySnapshotId: String, val adoptedBy: String) {
    val physicalTables: List<PhysicalTableRef> = java.util.List.copyOf(physicalTables)
    init {
        require(this.physicalTables.isNotEmpty() && this.physicalTables.distinct().size == this.physicalTables.size) { "source adoption requires distinct exact physical contracts" }
        require(discoverySnapshotId.isNotBlank() && adoptedBy.isNotBlank()) { "source adoption requires an exact structure snapshot and actor" }
    }
    companion object {
        fun contract(): Map<String, Any> = mapOf(
            "physicalTables" to "NONEMPTY_DISTINCT_EXACT_PHYSICAL_DATA_CONTRACT_REFERENCES",
            "discoverySnapshotId" to "EXACT_SUCCEEDED_STRUCTURE_OBSERVATION_AND_ITS_REGISTERED_AUTHORIZED_BINDING",
            "adoptedBy" to "AUTHENTICATED_CALLER_SUBJECT",
            "validation" to "EACH_DECLARED_COLUMN_TYPE_NULLABILITY_KEY_ANCHOR_AND_COLLECTION_MUST_BE_FULFILLED",
            "additionalObservedColumns" to "PERMITTED_WITHOUT_CHANGING_THE_PHYSICAL_DATA_CONTRACT",
            "result" to "IMMUTABLE_CONTENT_ADDRESSED_ADOPTIONS_WITH_AUDIT_INFORMATION",
            "execution" to "REGISTRATION_IS_NOT_A_SOURCE_QUERY_OR_A_DATA_SNAPSHOT_GUARANTEE",
        )
    }
}

data class SourceAdoptionReadRequest(val discoverySnapshotId: String) {
    init { require(discoverySnapshotId.isNotBlank()) { "source adoption read requires an exact structure snapshot" } }
}
