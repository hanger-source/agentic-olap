package com.olap.prototype.expression

import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.query.GroupingLevels
import com.olap.prototype.query.ResultAggregation
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.Aggregate
import org.apache.calcite.rel.core.Calc
import org.apache.calcite.rel.core.Project
import org.apache.calcite.rel.logical.LogicalProject
import org.apache.calcite.rex.RexInputRef
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.SqlAggFunction
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.type.SqlTypeUtil
import org.apache.calcite.tools.RelBuilder
import org.apache.calcite.util.ImmutableBitSet

object RelationalAggregates {
    /** 原文本及其精确二进制身份共同分组；辅助键不进入完成关系的业务列。 */
    fun aggregate(builder: RelBuilder, dimensions: List<String>, levels: GroupingLevels.Bound?, calls: List<RelBuilder.AggCall>) {
        val existing = generateSequence(builder.peek()) { relation ->
            if (relation is Project || relation is Calc) relation.getInput(0) else null
        }.toSet()
        builder.aggregate(groupKey(builder, dimensions, levels), calls)
        val system = builder.typeFactory.typeSystem as SemanticCalciteTypeSystem
        if (system.semantic) return
        // 先由 RelBuilder 完成实参、FILTER、GROUPING 和重复调用的正式登记，再扩展实际聚合。
        // 只沿其透明输出投影进入，不触及输入中的其他聚合、过滤或连接人口。
        fun exactGroups(relation: RelNode): RelNode {
            if (relation in existing) return relation
            return when (relation) {
                is Aggregate -> exactTextGroups(relation, system.nativeSupport)
                is Project, is Calc -> relation.copy(relation.traitSet, listOf(exactGroups(relation.getInput(0))))
                else -> relation
            }
        }
        builder.push(exactGroups(builder.build()))
    }

    private fun exactTextGroups(aggregate: Aggregate, support: CompilationSupport): RelNode {
        val input = aggregate.input
        val builder = RelBuilder.proto().create(aggregate.cluster, null).apply { push(input) }
        val values = input.rowType.fieldList.map { RexInputRef.of(it.index, input.rowType) }.toMutableList<RexNode>()
        val names = input.rowType.fieldNames.toMutableList()
        val reserved = (names + aggregate.rowType.fieldNames).toMutableSet()
        val helpers = linkedMapOf<Int, Int>()
        aggregate.groupSet.forEach { index ->
            val value = values[index]
            if (SqlTypeUtil.isCharacter(value.type)) {
                val key = support.textIdentityKey(builder, value)
                if (key != null) {
                    check(key.type.sqlTypeName in setOf(SqlTypeName.BINARY, SqlTypeName.VARBINARY) && key.type.isNullable == value.type.isNullable) {
                        "exact text grouping key must retain NULL and use a binary representation"
                    }
                    helpers[index] = values.size
                    values.add(key)
                    names.add(SemanticNativeTypes.internalAlias(reserved, "__group_identity_"))
                }
            }
        }
        if (helpers.isEmpty()) return aggregate
        // 所有原输入 ordinal 保持不变，聚合实参、FILTER 与 GROUPING 的索引无需改写。
        val projected = LogicalProject.create(input, emptyList(), values, names)
        fun extend(group: ImmutableBitSet): ImmutableBitSet = group.union(ImmutableBitSet.of(helpers.filterKeys { group[it] }.values))
        val groups = extend(aggregate.groupSet)
        val expanded = aggregate.copy(aggregate.traitSet, projected, groups,
            aggregate.groupSets.map(::extend).sortedWith(ImmutableBitSet.ORDERING), aggregate.aggCallList)
        val retained = aggregate.groupSet.map { groups.indexOf(it) } +
            aggregate.aggCallList.indices.map { groups.cardinality() + it }
        // 按原输出 ordinal 和 rowType 还原，内部键名不会挤占业务聚合别名。
        return LogicalProject.create(expanded, emptyList(), retained.map { RexInputRef.of(it, expanded.rowType) }, aggregate.rowType)
    }

    fun groupKey(builder: RelBuilder, dimensions: List<String>, levels: GroupingLevels.Bound?): RelBuilder.GroupKey {
        val keys = dimensions.map(builder::field)
        return if (levels == null || dimensions.isEmpty()) builder.groupKey(keys)
        else builder.groupKey(keys, levels.levels.map { level -> level.groupBy.map(builder::field) })
    }

    fun aggregateFunction(aggregation: ResultAggregation, support: CompilationSupport): SqlAggFunction = aggregateFunction(aggregation.kind, support)

    fun aggregateFunction(aggregation: ResultAggregation.Kind, support: CompilationSupport): SqlAggFunction = support.nativeAggregate(when (aggregation) {
        ResultAggregation.Kind.SUM -> SqlKind.SUM
        ResultAggregation.Kind.AVG -> SqlKind.AVG
        ResultAggregation.Kind.STDDEV_SAMP -> SqlKind.STDDEV_SAMP
        ResultAggregation.Kind.MIN -> SqlKind.MIN
        ResultAggregation.Kind.MAX -> SqlKind.MAX
        ResultAggregation.Kind.COUNT, ResultAggregation.Kind.COUNT_DISTINCT -> SqlKind.COUNT
        ResultAggregation.Kind.ARRAY_AGG, ResultAggregation.Kind.ARRAY_AGG_DISTINCT -> SqlKind.ARRAY_AGG
    })

    fun factAggregateFunction(aggregation: MetricAggregation, support: CompilationSupport): SqlAggFunction = support.nativeAggregate(when (aggregation) {
        MetricAggregation.SUM -> SqlKind.SUM
        MetricAggregation.AVG -> SqlKind.AVG
        MetricAggregation.STDDEV_SAMP -> SqlKind.STDDEV_SAMP
        MetricAggregation.MIN -> SqlKind.MIN
        MetricAggregation.MAX -> SqlKind.MAX
        MetricAggregation.COUNT -> SqlKind.COUNT
    })

    internal fun distinct(aggregation: ResultAggregation): Boolean = when (aggregation) {
        is ResultAggregation.Count -> aggregation.distinct
        is ResultAggregation.Collect -> aggregation.distinct
        is ResultAggregation.Sum, is ResultAggregation.Average, is ResultAggregation.SampleDeviation,
        is ResultAggregation.Minimum, is ResultAggregation.Maximum -> false
    }
}
