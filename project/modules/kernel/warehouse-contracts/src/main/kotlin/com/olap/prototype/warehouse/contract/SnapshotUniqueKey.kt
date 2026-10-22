package com.olap.prototype.warehouse.contract

/** 不可变锚点上全量确认的行键；NULL按GROUP BY相等。 */
data class SnapshotUniqueKey(val rule: Rule, val anchor: JdbcTableConstraints.DataAnchor) {
    class Rule(val name: String, columns: List<String>) {
        val columns: List<String> = java.util.List.copyOf(columns)
        init {
            require(name.isNotBlank()) { "snapshot unique key requires an exact name" }
            require(this.columns.size in 1..16 && this.columns.all { it.isNotBlank() } && this.columns.distinct().size == this.columns.size) {
                "snapshot unique key requires 1..16 distinct exact columns"
            }
        }
        override fun equals(other: Any?): Boolean = other is Rule && name == other.name && columns == other.columns
        override fun hashCode(): Int = listOf(name, columns).hashCode()
    }
}
