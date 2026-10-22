package com.olap.prototype.query

/** 列、记录字段或数组元素模板的业务坐标；数组元素各有独立局部值域。 */
class ValuePosition(val owner: QueryStageAnalysis.SymbolId, path: List<Int>) {
    val path: List<Int> = java.util.List.copyOf(path)
    init { require(this.path.size <= 64 && this.path.all { it >= -1 }) { "value path requires bounded field ordinals and -1 array-element markers" } }
    fun field(index: Int): ValuePosition { require(index >= 0) { "negative row field ordinal" }; return ValuePosition(owner, path + index) }
    fun element(): ValuePosition = ValuePosition(owner, path + -1)
    fun remap(owners: Map<QueryStageAnalysis.SymbolId, QueryStageAnalysis.SymbolId>): ValuePosition = owners[owner]?.let { ValuePosition(it, path) } ?: this
    fun scalar(): Boolean = -1 !in path
    override fun equals(other: Any?): Boolean = other is ValuePosition && owner == other.owner && path == other.path
    override fun hashCode(): Int = listOf(owner, path).hashCode()
    override fun toString(): String = "ValuePosition(owner=$owner, path=$path)"
    companion object { fun column(owner: QueryStageAnalysis.SymbolId): ValuePosition = ValuePosition(owner, emptyList()) }
}
