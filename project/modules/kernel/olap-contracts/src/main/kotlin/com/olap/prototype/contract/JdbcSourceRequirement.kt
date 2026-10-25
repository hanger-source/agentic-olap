package com.olap.prototype.contract

import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.warehouse.SourceGuaranteeException
import com.olap.prototype.warehouse.contract.*

/** 编译采用的精确来源事实，执行者按本次用途核验一致数据状态。 */
class JdbcSourceRequirement(
    val dataContract: PhysicalDataContract,
    val adoption: PhysicalSourceAdoption,
    columns: Map<String, JdbcColumnType>,
    uses: Set<Use>,
    constraintUses: Set<ConstraintUse>,
) {
    val columns: Map<String, JdbcColumnType> = java.util.Map.copyOf(columns)
    val uses: Set<Use> = java.util.Set.copyOf(uses)
    val dataSourceId: String get() = adoption.binding.dataSourceId
    val connectorRef: ConnectorRef get() = adoption.connectorRef
    val dialect: String get() = adoption.dialect
    val physicalTableId: String get() = dataContract.reference.id
    val physicalTableVersion: String get() = dataContract.reference.version.toString()
    val table: JdbcTableConstraints.Table get() = adoption.table
    val evidence: PhysicalSourceEvidence get() = adoption.evidence
    val snapshotUniqueKeys: List<SnapshotUniqueKey> get() = dataContract.snapshotUniqueKeys
    val sourceCollections: List<SourceCollectionRecord> get() = evidence.sourceCollections.filter {
        it.declaration() in dataContract.sourceCollections
    }
    val constraintUses: Set<ConstraintUse> = java.util.Set.copyOf(constraintUses)

    enum class Use { ROWS, NATIVE_TABLE }
    enum class ConstraintKind { PRIMARY_KEY, UNIQUE_KEY, FOREIGN_KEY, TEMPORAL_KEY, SNAPSHOT_UNIQUE_KEY, NOT_NULL }
    /**
     * NOT_NULL 的 name 是 columns 中的精确列名，表示编译已采用其非空原生类型；
     * PRIMARY_KEY 无名称，其余项引用精确冻结约束名。
     * 采用说明本次执行必须兑现什么，不说明来源已核验或必须使用哪种核验机制。
     */
    data class ConstraintUse(val kind: ConstraintKind, val name: String?) {
        init { require(if (kind == ConstraintKind.PRIMARY_KEY) name == null else !name.isNullOrBlank()) { "source guarantee must identify its exact frozen column or constraint" } }
    }

    init {
        adoption.requireContract(dataContract)
        require(this.uses.isNotEmpty() && (Use.ROWS in this.uses || this.columns.isEmpty() && this.constraintUses.isEmpty())) {
            "source use must explicitly distinguish row reads from native table arguments"
        }
        require(this.snapshotUniqueKeys.map { it.rule.name }.distinct().size == this.snapshotUniqueKeys.size) { "source snapshot key names must be distinct" }
        this.snapshotUniqueKeys.forEach { require(dataContract.columns.keys.containsAll(it.rule.columns)) { "source snapshot key is outside exact data contract columns" } }
        sourceCollections.forEach { collection ->
            val declaredTable = collection.collection.table
            require(collection.dataSourceId == dataSourceId && collection.sourceIdentityHash == adoption.binding.sourceIdentityHash &&
                table == declaredTable) {
                "source collection differs from exact adopted physical source"
            }
        }
        this.constraintUses.forEach { use ->
            val constraints = dataContract.constraints
            val exists = when (use.kind) {
                ConstraintKind.PRIMARY_KEY -> constraints.primaryKeyColumns.isNotEmpty()
                ConstraintKind.UNIQUE_KEY -> constraints.uniqueKeys.any { it.name == use.name }
                ConstraintKind.FOREIGN_KEY -> constraints.importedKeys.any { it.name == use.name }
                ConstraintKind.TEMPORAL_KEY -> constraints.temporalKeys.any { it.rule.name == use.name }
                ConstraintKind.SNAPSHOT_UNIQUE_KEY -> this.snapshotUniqueKeys.any { it.rule.name == use.name }
                ConstraintKind.NOT_NULL -> use.name != null && this.columns[use.name]?.nullable == false
            }
            require(exists) { "adopted constraint is outside frozen evidence" }
        }
        // 空列不代表用途；ROWS 的 COUNT 等也可以没有字段引用。
        this.columns.forEach { (name, type) -> require(dataContract.columns[name]?.type == type) {
            "source requirement must use the exact data contract column type: $name"
        } }
    }

    fun bindingRef(): SourceBindingRef = adoption.binding
    fun readsRows(): Boolean = Use.ROWS in uses
    fun withUse(use: Use): JdbcSourceRequirement = JdbcSourceRequirement(dataContract, adoption, columns, uses + use, constraintUses)
    fun requireDeployment(source: SourceConfiguration) {
        if (bindingRef() != source.bindingRef() || connectorRef != source.connectorRef || dialect != source.dialectId) throw SourceGuaranteeException("SOURCE_BINDING_DRIFT")
    }

    override fun equals(other: Any?): Boolean = other is JdbcSourceRequirement && dataContract == other.dataContract && adoption == other.adoption && columns == other.columns && uses == other.uses && constraintUses == other.constraintUses
    override fun hashCode(): Int = listOf(dataContract, adoption, columns, uses, constraintUses).hashCode()

    companion object {
        fun requireSingleBinding(requirements: Collection<JdbcSourceRequirement>): SourceBindingRef {
            val bindings = requirements.map { it.bindingRef() }.distinct()
            require(bindings.size == 1) { "one SQL request requires one exact source binding" }
            return bindings.single()
        }
    }
}
