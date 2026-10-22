package com.olap.prototype.expression

import org.apache.calcite.plan.RelOptCluster
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.metadata.JaninoRelMetadataProvider
import org.apache.calcite.rel.metadata.RelMetadataQuery
import org.apache.calcite.util.ImmutableBitSet

/** 通用语义算子的关系事实；来源约束由执行层在此基础上增加。 */
open class SemanticRelMetadataQuery(cluster: RelOptCluster) : RelMetadataQuery(JaninoRelMetadataProvider.of(cluster.metadataProvider)) {
    override fun areColumnsUnique(rel: RelNode, columns: ImmutableBitSet, ignoreNulls: Boolean): Boolean? {
        if (rel !is CollectionExpansionRel) return super.areColumnsUnique(rel, columns, ignoreNulls)
        val ordinal = rel.rowType.fieldCount - 1
        if (!columns[ordinal]) return null
        val inputColumns = columns.intersect(ImmutableBitSet.range(rel.input.rowType.fieldCount))
        return if (areColumnsUnique(rel.input, inputColumns, ignoreNulls) == true) true else null
    }

    override fun getUniqueKeys(rel: RelNode, ignoreNulls: Boolean): Set<ImmutableBitSet>? =
        if (rel is CollectionExpansionRel) getUniqueKeys(rel.input, ignoreNulls)?.mapTo(linkedSetOf()) {
            it.union(ImmutableBitSet.of(rel.rowType.fieldCount - 1))
        } else super.getUniqueKeys(rel, ignoreNulls)
}
