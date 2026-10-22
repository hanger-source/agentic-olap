package com.olap.prototype.warehouse.contract

/** 原生技术约束与冻结数据的完整验证证据；业务声明和抽样不产生这些保证。 */
class JdbcTableConstraints(
    primaryKeyColumns: List<String>,
    uniqueKeys: List<UniqueKey>,
    importedKeys: List<ForeignKeyColumn>,
    temporalKeys: List<TemporalKey>,
) {
    val primaryKeyColumns: List<String> = java.util.List.copyOf(primaryKeyColumns)
    val uniqueKeys: List<UniqueKey> = java.util.List.copyOf(uniqueKeys)
    val importedKeys: List<ForeignKeyColumn> = java.util.List.copyOf(importedKeys)
    val temporalKeys: List<TemporalKey> = java.util.List.copyOf(temporalKeys)

    init {
        require(this.temporalKeys.map { it.rule.name }.distinct().size == this.temporalKeys.size) { "temporal constraint names must be distinct" }
        requireColumns(this.primaryKeyColumns)
    }

    data class DataAnchor(val sha256: String) {
        init { require(sha256.matches(Regex("[0-9a-f]{64}"))) { "immutable data anchor requires SHA-256" } }
    }

    data class TemporalInterval(
        val validFromColumn: String,
        val validToColumn: String,
        val validFromInclusive: Boolean,
        val validToInclusive: Boolean,
        val openEndedValidTo: Boolean,
    ) {
        init { requireColumns(listOf(validFromColumn, validToColumn)) }
    }

    class TemporalRule(val name: String, equalityColumns: List<String>, intervals: List<TemporalInterval>) {
        val equalityColumns: List<String> = java.util.List.copyOf(equalityColumns)
        val intervals: List<TemporalInterval> = java.util.List.copyOf(intervals)
        init {
            require(name.isNotBlank()) { "temporal rule requires an exact name" }
            requireColumns(this.equalityColumns)
            require(this.equalityColumns.size in 1..16 && this.intervals.size in 1..8) { "temporal rule requires bounded keys and intervals" }
            require(this.intervals.distinct().size == this.intervals.size) { "temporal intervals must be distinct" }
        }
        fun columns(): Set<String> = java.util.Set.copyOf(linkedSetOf<String>().apply {
            addAll(equalityColumns)
            intervals.forEach { add(it.validFromColumn); add(it.validToColumn) }
        })
        override fun equals(other: Any?): Boolean = other is TemporalRule && name == other.name && equalityColumns == other.equalityColumns && intervals == other.intervals
        override fun hashCode(): Int = listOf(name, equalityColumns, intervals).hashCode()
    }

    data class TemporalKey(val rule: TemporalRule, val anchor: DataAnchor)

    class UniqueKey(val name: String, columns: List<String>, val filterCondition: String?, val expressions: String?) {
        val columns: List<String> = java.util.List.copyOf(columns)
        init {
            require(this.columns.isNotEmpty() || !expressions.isNullOrBlank()) { "unique index requires columns or observed expressions" }
            requireColumns(this.columns)
        }
        override fun equals(other: Any?): Boolean = other is UniqueKey && name == other.name && columns == other.columns && filterCondition == other.filterCondition && expressions == other.expressions
        override fun hashCode(): Int = listOf(name, columns, filterCondition, expressions).hashCode()
    }

    /** 每个复合外键保留 JDBC 名字与 KEY_SEQ，不按列名重新配对。 */
    data class ForeignKeyColumn(
        val name: String?,
        val column: String,
        val referencedTable: Table,
        val referencedColumn: String,
        val updateRule: Short,
        val deleteRule: Short,
        val keySequence: Int,
    ) {
        init { require(column.isNotBlank() && referencedColumn.isNotBlank() && keySequence >= 1) { "foreign key requires columns and positive KEY_SEQ" } }
    }

    data class Table(val catalog: String?, val schema: String?, val table: String) {
        init { require(table.isNotBlank()) { "table is required" } }
    }

    override fun equals(other: Any?): Boolean = other is JdbcTableConstraints && primaryKeyColumns == other.primaryKeyColumns && uniqueKeys == other.uniqueKeys && importedKeys == other.importedKeys && temporalKeys == other.temporalKeys
    override fun hashCode(): Int = listOf(primaryKeyColumns, uniqueKeys, importedKeys, temporalKeys).hashCode()

    companion object {
        private fun requireColumns(columns: List<String>) {
            require(columns.all { it.isNotBlank() } && columns.distinct().size == columns.size) { "constraint columns must be distinct nonempty names" }
        }
    }
}
