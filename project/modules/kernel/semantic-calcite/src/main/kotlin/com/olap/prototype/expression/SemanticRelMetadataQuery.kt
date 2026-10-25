package com.olap.prototype.expression

import org.apache.calcite.plan.RelOptCluster
import org.apache.calcite.plan.RelOptPredicateList
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.Calc
import org.apache.calcite.rel.logical.LogicalFilter
import org.apache.calcite.rel.logical.LogicalProject
import org.apache.calcite.rel.metadata.JaninoRelMetadataProvider
import org.apache.calcite.rel.metadata.RelMetadataQuery
import org.apache.calcite.rex.RexUtil
import org.apache.calcite.util.ImmutableBitSet

/** 通用语义算子的关系事实；来源约束由执行层在此基础上增加。 */
open class SemanticRelMetadataQuery(cluster: RelOptCluster) : RelMetadataQuery(JaninoRelMetadataProvider.of(cluster.metadataProvider)) {
    override fun getPulledUpPredicates(rel: RelNode): RelOptPredicateList {
        if (rel !is Calc) return super.getPulledUpPredicates(rel)
        // Calcite 的标准谓词元数据处理 Filter/Project，却不处理 Calc。这里只展开等价的
        // 元数据视图，让未改写的输入键继续携带原过滤事实；实际关系及 SQL 保持原来的 Calc。
        val split = rel.program.split()
        val filtered = if (split.right.isEmpty()) rel.input else LogicalFilter.create(
            rel.input, RexUtil.composeConjunction(rel.cluster.rexBuilder, split.right))
        val projected = LogicalProject.create(filtered, emptyList(), split.left, rel.rowType)
        return getPulledUpPredicates(projected)
    }

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
