package com.olap.prototype.expression

import org.apache.calcite.plan.RelOptCluster
import org.apache.calcite.plan.RelOptPredicateList
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.Aggregate
import org.apache.calcite.rel.core.Calc
import org.apache.calcite.rel.core.Join
import org.apache.calcite.rel.core.JoinRelType
import org.apache.calcite.rel.core.Project
import org.apache.calcite.rel.logical.LogicalFilter
import org.apache.calcite.rel.logical.LogicalProject
import org.apache.calcite.rel.metadata.JaninoRelMetadataProvider
import org.apache.calcite.rel.metadata.RelMetadataQuery
import org.apache.calcite.rex.RexCall
import org.apache.calcite.rex.RexLiteral
import org.apache.calcite.rex.RexNode
import org.apache.calcite.rex.RexOver
import org.apache.calcite.rex.RexSubQuery
import org.apache.calcite.rex.RexUtil
import org.apache.calcite.sql.SqlKind
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
        if (rel is Aggregate && dependentGroupKey(rel)?.let(columns::contains) == true) return true
        // 空键唯一表示实际关系至多一行。两侧都满足时，INNER/LEFT/RIGHT JOIN
        // 仍至多一行；Calcite 的普通 JOIN 推导只处理含列的键，遗漏这一标量情形。
        // FULL JOIN 则可能保留两侧各一条未匹配记录，不能作此推导。
        if (columns.isEmpty && rel is Join &&
            rel.joinType in setOf(JoinRelType.INNER, JoinRelType.LEFT, JoinRelType.RIGHT) &&
            areColumnsUnique(rel.left, ImmutableBitSet.of(), false) == true &&
            areColumnsUnique(rel.right, ImmutableBitSet.of(), false) == true) return true
        if (rel !is CollectionExpansionRel) return super.areColumnsUnique(rel, columns, ignoreNulls)
        val ordinal = rel.rowType.fieldCount - 1
        if (!columns[ordinal]) return null
        val inputColumns = columns.intersect(ImmutableBitSet.range(rel.input.rowType.fieldCount))
        return if (areColumnsUnique(rel.input, inputColumns, ignoreNulls) == true) true else null
    }

    override fun getUniqueKeys(rel: RelNode, ignoreNulls: Boolean): Set<ImmutableBitSet>? {
        if (rel is CollectionExpansionRel) return getUniqueKeys(rel.input, ignoreNulls)?.mapTo(linkedSetOf()) {
            it.union(ImmutableBitSet.of(rel.rowType.fieldCount - 1))
        }
        val keys = super.getUniqueKeys(rel, ignoreNulls)
        val dependent = (rel as? Aggregate)?.let(::dependentGroupKey) ?: return keys
        return keys.orEmpty().toMutableSet().apply { add(dependent) }
    }

    /** 只移除由同一实际 groupSet 中其余键确定的表达式，不从作者声明借入函数依赖。 */
    private fun dependentGroupKey(aggregate: Aggregate): ImmutableBitSet? {
        val expressions = when (val input = aggregate.input) {
            is Project -> input.projects
            is Calc -> input.program.projectList.map(input.program::expandLocalRef)
            else -> return null
        }
        val inputKeys = aggregate.groupSet.asList()
        val retained = inputKeys.indices.toMutableList()
        inputKeys.indices.reversed().forEach { output ->
            val input = inputKeys[output]
            if (aggregate.groupSets.all { group -> !group[input] || determinedBy(expressions[input],
                    retained.filter { it != output && group[inputKeys[it]] }.map { expressions[inputKeys[it]] }) }) retained.remove(output)
        }
        if (retained.size == inputKeys.size) return null
        val key = ImmutableBitSet.of(retained)
        if (Aggregate.isSimple(aggregate)) return key
        // 不同层级可能把真实 NULL 与汇总补出的 NULL 投成相同原键。只有实际 GROUPING
        // 调用的联合掩码能区分每个 groupSet 时，才连同其输出证明跨层级唯一性。
        val grouping = aggregate.aggCallList.withIndex().filter { it.value.aggregation.kind == SqlKind.GROUPING }
        val masks = aggregate.groupSets.map { group -> grouping.flatMap { (_, call) -> call.argList.map { group[it] } } }
        if (masks.distinct().size != aggregate.groupSets.size) return null
        return key.union(ImmutableBitSet.of(grouping.map { aggregate.groupCount + it.index }))
    }

    private fun determinedBy(expression: RexNode, keys: List<RexNode>): Boolean {
        if (!RexUtil.isDeterministic(expression)) return false
        if (expression in keys || expression is RexLiteral) return true
        return expression is RexCall && expression !is RexOver && expression !is RexSubQuery &&
            expression.operands.all { determinedBy(it, keys) }
    }
}
