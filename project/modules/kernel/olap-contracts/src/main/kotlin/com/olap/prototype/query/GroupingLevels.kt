package com.olap.prototype.query

/** 有限分组层级；原生GROUPING代码与业务层级身份共同进入输出合同。 */
class GroupingLevels(val outputAlias: String, levels: List<Level>) {
    val levels: List<Level> = java.util.List.copyOf(levels)
    init {
        PostOperationContract.requireAlias(outputAlias)
        require(this.levels.size in 1..32 && this.levels.map { it.id }.distinct().size == this.levels.size && this.levels.map { it.groupBy.toSet() }.distinct().size == this.levels.size) { "grouping levels require 1..32 distinct identities and grouping sets" }
    }
    class Level(val id: String, groupBy: List<String>) {
        val groupBy: List<String> = java.util.List.copyOf(groupBy)
        init { ScopedInputContract.requireId(id); require(this.groupBy.distinct().size == this.groupBy.size) { "grouping level repeats a symbol" } }
        override fun equals(other: Any?): Boolean = other is Level && id == other.id && groupBy == other.groupBy
        override fun hashCode(): Int = listOf(id, groupBy).hashCode()
    }
    class BoundLevel(val id: String, val code: Long, groupBy: List<String>) {
        val groupBy: List<String> = java.util.List.copyOf(groupBy)
        init { ScopedInputContract.requireId(id); require(code >= 0) { "negative grouping code" } }
        override fun equals(other: Any?): Boolean = other is BoundLevel && id == other.id && code == other.code && groupBy == other.groupBy
        override fun hashCode(): Int = listOf(id, code, groupBy).hashCode()
    }
    class Bound(val outputAlias: String, levels: List<BoundLevel>) {
        val levels: List<BoundLevel> = java.util.List.copyOf(levels)
        init { PostOperationContract.requireAlias(outputAlias) }
        override fun equals(other: Any?): Boolean = other is Bound && outputAlias == other.outputAlias && levels == other.levels
        override fun hashCode(): Int = listOf(outputAlias, levels).hashCode()
    }
    fun expand(symbols: (List<String>) -> List<String>): GroupingLevels = GroupingLevels(outputAlias, levels.map { Level(it.id, symbols(it.groupBy)) })
    fun bind(dimensions: List<String>): Bound {
        require(dimensions.size <= 63 && outputAlias !in dimensions) { "GROUPING requires at most 63 dimensions and a distinct level output" }
        val available = java.util.Set.copyOf(dimensions)
        val used = hashSetOf<String>()
        val result = levels.map { level ->
            require(available.containsAll(level.groupBy)) { "grouping level references an unavailable dimension or result key" }
            used.addAll(level.groupBy)
            val code = dimensions.fold(0L) { code, dimension -> (code shl 1) or if (dimension in level.groupBy) 0L else 1L }
            BoundLevel(level.id, code, level.groupBy)
        }
        require(used == available) { "declared dimensions must occur in at least one grouping level" }
        return Bound(outputAlias, result)
    }
    override fun equals(other: Any?): Boolean = other is GroupingLevels && outputAlias == other.outputAlias && levels == other.levels
    override fun hashCode(): Int = listOf(outputAlias, levels).hashCode()
}
