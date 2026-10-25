package com.olap.prototype.engine.executable

import com.olap.prototype.engine.catalog.ExecutableRelationPosition.Expression
import com.olap.prototype.expression.RelationalAggregates
import com.olap.prototype.expression.SemanticCalciteTypeSystem
import com.olap.prototype.expression.SemanticNumericRex
import com.olap.prototype.expression.NativeAggregateTypes
import com.olap.prototype.metric.MetricSnapshotRule
import com.olap.prototype.metric.MetricAggregation
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.tools.RelBuilder

/** 唯一快照、FIRST/LAST/MEAN和选行后资格，不决定最终指标人口。 */
internal object FactSnapshotCompiler {
    fun compile(b: RelBuilder, compiler: SourceRelationCompiler.Rows, position: Expression, rule: MetricSnapshotRule,
        projection: FactAtomCompiler.Projection, projectedValues: List<RexNode>): FactAtomCompiler.Projection {
        val original = projection.names()
        val names = original.toMutableList()
        val values = projectedValues.toMutableList()
        val keys = rule.keys().mapIndexed { index, key ->
            val alias = "__snapshot_key_$index"
            check(alias !in names) { "snapshot key alias conflicts with an output" }
            values.add(compiler.compile(key.expression(), position)); names.add(alias); alias
        }
        projection.columns.forEach { column -> column.predicate?.let { predicate ->
            val at = original.indexOf(predicate); values[at] = b.call(Ops.CASE, values[at], b.literal(1), b.literal(0))
        } }
        compiler.project(values, names)
        b.filter(b.isNotNull(b.field(keys[rule.entityKeys.size])))
        val singles = projection.columns.flatMap { column -> listOfNotNull(column.value, column.predicate).map { b.aggregateCall(Ops.SINGLE_VALUE, listOf(b.field(it))).`as`(it) } }
        b.aggregate(b.groupKey((projection.dimensions + keys).map(b::field)), singles)
        b.project(names.map(b::field), names, true)
        val entityGrain = projection.dimensions + keys.take(rule.entityKeys.size)
        if (rule.reduction == MetricSnapshotRule.Reduction.MEAN) meanSnapshots(b, rule, projection, entityGrain)
        else selectSnapshot(b, rule, keys, entityGrain)
        val restored = projection.dimensions.map(b::field).toMutableList<RexNode>()
        projection.columns.forEach { column -> restored.add(b.field(column.value)); column.predicate?.let { restored.add(b.equals(b.field(it), b.literal(1))) } }
        b.project(restored, projection.names(), true)
        return if (rule.reduction == MetricSnapshotRule.Reduction.MEAN) projection.copy(columns = projection.columns.map { column ->
            column.copy(inputGuarantees = listOf(requireNotNull(rule.meanValueContract).arithmetic))
        }) else projection
    }
    private fun selectSnapshot(b: RelBuilder, rule: MetricSnapshotRule, keys: List<String>, entityGrain: List<String>) {
        val order = keys.drop(rule.entityKeys.size).map { if (rule.reduction == MetricSnapshotRule.Reduction.LAST) b.desc(b.field(it)) else b.field(it) }.map(b::nullsLast)
        val rank = b.aggregateCall(Ops.ROW_NUMBER, emptyList()).over().partitionBy(entityGrain.map(b::field)).orderBy(order).toRex()
        val names = b.peek().rowType.fieldNames.toList()
        check("__snapshot_position" !in names) { "duplicate result alias: __snapshot_position" }
        b.project(b.fields() + rank, names + "__snapshot_position", true).filter(b.equals(b.field("__snapshot_position"), b.literal(1)))
    }
    private fun meanSnapshots(b: RelBuilder, rule: MetricSnapshotRule, projection: FactAtomCompiler.Projection, entityGrain: List<String>) {
        val valueContract = requireNotNull(rule.meanValueContract)
        val averages = mutableListOf<RelBuilder.AggCall>()
        projection.columns.forEach { column ->
            val name = column.value
            val predicate = column.predicate?.let { b.equals(b.field(it), b.literal(1)) }
            val input = SemanticNumericRex.factAggregate(b, MetricAggregation.AVG, b.field(name), valueContract)
            val function = RelationalAggregates.factAggregateFunction(MetricAggregation.AVG, (b.typeFactory.typeSystem as SemanticCalciteTypeSystem).nativeSupport)
            averages.add(b.aggregateCall(function, false, predicate, name, input))
            averages.add(b.aggregateCall(Ops.COUNT, false, predicate, "${name}__known", b.field(name)))
            averages.add(b.aggregateCall(Ops.COUNT, false, predicate, "${name}__population", emptyList()))
        }
        b.aggregate(b.groupKey(entityGrain.map(b::field)), averages)
        projection.columns.forEach { column -> NativeAggregateTypes.requireAverage(b, column.value, requireNotNull(rule.meanTarget),
            valueContract, column.inputGuarantees) }
        val reduced = projection.dimensions.map(b::field).toMutableList<RexNode>()
        projection.columns.forEach { column ->
            val name = column.value
            var value: RexNode = b.field(name)
            if (rule.nullPolicy == MetricSnapshotRule.NullPolicy.PROPAGATE) value = b.call(Ops.CASE,
                b.equals(b.field("${name}__known"), b.field("${name}__population")), value, b.rexBuilder.makeNullLiteral(value.type))
            reduced.add(SemanticNumericRex.output(b, value, valueContract))
            if (column.predicate != null) reduced.add(b.call(Ops.CASE, b.greaterThan(b.field("${name}__population"), b.literal(0)), b.literal(1), b.literal(0)))
        }
        b.project(reduced, projection.names(), true)
    }
}
