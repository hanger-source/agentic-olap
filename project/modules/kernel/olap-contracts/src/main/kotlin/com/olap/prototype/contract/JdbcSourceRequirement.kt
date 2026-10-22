package com.olap.prototype.contract

import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.warehouse.SourceGuaranteeException
import com.olap.prototype.warehouse.contract.*

/** 编译采用的精确来源事实，执行者按本次用途核验一致数据状态。 */
class JdbcSourceRequirement(
    val dataSourceId: String,
    val connectorRef: ConnectorRef,
    val dialect: String,
    val physicalTableId: String,
    val physicalTableVersion: String,
    val table: JdbcTableConstraints.Table,
    val evidence: PhysicalSourceConstraints,
    columns: Map<String, JdbcColumnType>,
    uses: Set<Use>,
    snapshotUniqueKeys: List<SnapshotUniqueKey>,
    constraintUses: Set<ConstraintUse>,
) {
    val columns: Map<String, JdbcColumnType> = java.util.Map.copyOf(columns)
    val uses: Set<Use> = java.util.Set.copyOf(uses)
    val snapshotUniqueKeys: List<SnapshotUniqueKey> = java.util.List.copyOf(snapshotUniqueKeys)
    val constraintUses: Set<ConstraintUse> = java.util.Set.copyOf(constraintUses)

    enum class Use { ROWS, NATIVE_TABLE }
    enum class ConstraintKind { PRIMARY_KEY, UNIQUE_KEY, FOREIGN_KEY, TEMPORAL_KEY, SNAPSHOT_UNIQUE_KEY }
    data class ConstraintUse(val kind: ConstraintKind, val name: String?) {
        init { require(if (kind == ConstraintKind.PRIMARY_KEY) name == null else !name.isNullOrBlank()) { "constraint reference must identify its exact frozen constraint" } }
    }

    init {
        require(connectorRef == evidence.connectorRef) { "source connector differs from its frozen evidence" }
        require(this.uses.isNotEmpty() && (Use.ROWS in this.uses || this.columns.isEmpty() && this.snapshotUniqueKeys.isEmpty() && this.constraintUses.isEmpty())) {
            "source use must explicitly distinguish row reads from native table arguments"
        }
        require(this.snapshotUniqueKeys.map { it.rule.name }.distinct().size == this.snapshotUniqueKeys.size) { "source snapshot key names must be distinct" }
        this.snapshotUniqueKeys.forEach { require(evidence.columnVersionRefs.keys.containsAll(it.rule.columns)) { "source snapshot key is outside adopted source columns" } }
        evidence.sourceCollections.forEach { collection ->
            val declaredTable = collection.collection.table
            require(collection.dataSourceId == dataSourceId && collection.sourceIdentityHash == evidence.sourceIdentityHash &&
                table.catalog?.takeIf { it.isNotEmpty() } == declaredTable.catalog && table.schema == declaredTable.schema && table.table == declaredTable.table) {
                "source collection differs from exact adopted physical source"
            }
        }
        this.constraintUses.forEach { use ->
            val constraints = evidence.constraints
            val exists = when (use.kind) {
                ConstraintKind.PRIMARY_KEY -> constraints.primaryKeyColumns.isNotEmpty()
                ConstraintKind.UNIQUE_KEY -> constraints.uniqueKeys.any { it.name == use.name }
                ConstraintKind.FOREIGN_KEY -> constraints.importedKeys.any { it.name == use.name }
                ConstraintKind.TEMPORAL_KEY -> constraints.temporalKeys.any { it.rule.name == use.name }
                ConstraintKind.SNAPSHOT_UNIQUE_KEY -> this.snapshotUniqueKeys.any { it.rule.name == use.name }
            }
            require(exists) { "adopted constraint is outside frozen evidence" }
        }
        // 空列不代表用途；ROWS 的 COUNT 等也可以没有字段引用。
        require(evidence.columnVersionRefs.keys.containsAll(this.columns.keys)) { "source requirement must use columns from the adopted physical version" }
    }

    fun bindingRef(): SourceBindingRef = SourceBindingRef(dataSourceId, evidence.sourceIdentityHash)
    fun readsRows(): Boolean = Use.ROWS in uses
    fun withUse(use: Use): JdbcSourceRequirement = JdbcSourceRequirement(dataSourceId, connectorRef, dialect, physicalTableId, physicalTableVersion, table, evidence, columns, uses + use, snapshotUniqueKeys, constraintUses)
    fun requireDeployment(source: SourceConfiguration) {
        if (bindingRef() != source.bindingRef() || connectorRef != source.connectorRef || dialect != source.dialectId) throw SourceGuaranteeException("SOURCE_BINDING_DRIFT")
    }

    override fun equals(other: Any?): Boolean = other is JdbcSourceRequirement && dataSourceId == other.dataSourceId && connectorRef == other.connectorRef && dialect == other.dialect && physicalTableId == other.physicalTableId && physicalTableVersion == other.physicalTableVersion && table == other.table && evidence == other.evidence && columns == other.columns && uses == other.uses && snapshotUniqueKeys == other.snapshotUniqueKeys && constraintUses == other.constraintUses
    override fun hashCode(): Int = listOf(dataSourceId, connectorRef, dialect, physicalTableId, physicalTableVersion, table, evidence, columns, uses, snapshotUniqueKeys, constraintUses).hashCode()

    companion object {
        fun requireSingleBinding(requirements: Collection<JdbcSourceRequirement>): SourceBindingRef {
            val bindings = requirements.map { it.bindingRef() }.distinct()
            require(bindings.size == 1) { "one SQL request requires one exact source binding" }
            return bindings.single()
        }
    }
}
