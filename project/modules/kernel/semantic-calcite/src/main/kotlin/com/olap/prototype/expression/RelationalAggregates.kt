package com.olap.prototype.expression

import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.query.GroupingLevels
import com.olap.prototype.query.ResultAggregation
import org.apache.calcite.sql.SqlAggFunction
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.tools.RelBuilder

object RelationalAggregates {
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
