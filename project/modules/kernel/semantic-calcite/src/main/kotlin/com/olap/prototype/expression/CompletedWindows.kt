package com.olap.prototype.expression

import com.olap.prototype.query.PostOperationContract
import com.olap.prototype.query.ResultAggregation
import com.olap.prototype.query.WindowFrameContract
import org.apache.calcite.avatica.util.TimeUnit
import org.apache.calcite.rex.*
import org.apache.calcite.sql.SqlIntervalQualifier
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.type.SqlTypeUtil
import org.apache.calcite.tools.RelBuilder
import java.math.BigDecimal

/** 普通窗口与观察窗口共用的帧、排序、聚合及NULL规则。 */
internal object CompletedWindows {
    fun partitionAggregate(b: RelBuilder, op: PostOperationContract.PartitionAggregate): RexNode {
        val aggregation = op.aggregation
        val input = SemanticNumericRex.aggregate(b, aggregation, b.field(op.input))
        val function = RelationalAggregates.aggregateFunction(op.aggregation, (b.typeFactory.typeSystem as SemanticCalciteTypeSystem).nativeSupport)
        val partitions = op.partitionBy.map(b::field)
        val value = b.aggregateCall(function, listOf(input)).distinct(RelationalAggregates.distinct(op.aggregation))
            .over().partitionBy(partitions).rowsBetween(b.unboundedPreceding(), b.unboundedFollowing()).toRex()
        if (aggregation !is ResultAggregation.Scalar || aggregation.nullPolicy != ResultAggregation.NullPolicy.PROPAGATE) return value
        val known = b.aggregateCall(Ops.COUNT, listOf(b.field(op.input))).over().partitionBy(partitions)
            .rowsBetween(b.unboundedPreceding(), b.unboundedFollowing()).toRex()
        val total = b.aggregateCall(Ops.COUNT, emptyList()).over().partitionBy(partitions)
            .rowsBetween(b.unboundedPreceding(), b.unboundedFollowing()).toRex()
        return b.call(Ops.CASE, b.equals(known, total), value, b.rexBuilder.makeNullLiteral(value.type))
    }

    fun windowAggregate(b: RelBuilder, op: PostOperationContract.WindowAggregate): RexNode {
        val aggregation = op.aggregation
        val input = SemanticNumericRex.aggregate(b, aggregation, b.field(op.input))
        val frame = op.windowFrame
        if (frame.mode == "RANGE") {
            val orderType = b.field(op.orderKeys.first().symbol).type
            require(SqlTypeUtil.isNumeric(orderType) || SqlTypeUtil.isDatetime(orderType)) { "RANGE requires a numeric or temporal ordered result" }
            for (bound in listOf(frame.lower, frame.upper)) {
                if (bound.offset == null) continue
                require(if (bound.unit == "NUMBER") SqlTypeUtil.isNumeric(orderType) else SqlTypeUtil.isDatetime(orderType)) { "RANGE offset unit does not match the ordered result type" }
                require(orderType.sqlTypeName != SqlTypeName.DATE || bound.unit in setOf("DAY", "NUMBER")) { "DATE RANGE requires DAY offsets; sub-day ranges require an explicit timestamp" }
            }
        }
        val value = framedWindow(b, b.aggregateCall(RelationalAggregates.aggregateFunction(op.aggregation,
            (b.typeFactory.typeSystem as SemanticCalciteTypeSystem).nativeSupport), listOf(input)).distinct(RelationalAggregates.distinct(op.aggregation)), op)
        if (aggregation is ResultAggregation.Collect) {
            val total = framedWindow(b, b.count(), op)
            return b.call(Ops.CASE, b.equals(total, b.literal(0)), b.rexBuilder.makeNullLiteral(value.type), value)
        }
        if (aggregation !is ResultAggregation.Scalar || aggregation.nullPolicy != ResultAggregation.NullPolicy.PROPAGATE) return value
        val known = framedWindow(b, b.count(b.field(op.input)), op)
        val total = framedWindow(b, b.count(), op)
        return b.call(Ops.CASE, b.equals(known, total), value, b.rexBuilder.makeNullLiteral(value.type))
    }

    fun framedWindow(b: RelBuilder, aggregate: RelBuilder.AggCall, op: PostOperationContract.WindowAggregate): RexNode =
        framedWindow(b, aggregate, op.partitionBy, op.orderKeys, op.windowFrame)

    fun framedWindow(b: RelBuilder, aggregate: RelBuilder.AggCall, partitions: List<String>, order: List<PostOperationContract.OrderKey>, frame: WindowFrameContract.Frame): RexNode {
        val window = aggregate.over().partitionBy(partitions.map(b::field)).orderBy(orderKeys(b, order))
        val lower = windowBound(b, frame.lower)
        val upper = windowBound(b, frame.upper)
        return (if (frame.mode == "ROWS") window.rowsBetween(lower, upper) else window.rangeBetween(lower, upper)).toRex()
    }

    private fun windowBound(b: RelBuilder, bound: WindowFrameContract.Bound): RexWindowBound = when (bound.kind) {
        "UNBOUNDED_PRECEDING" -> b.unboundedPreceding()
        "UNBOUNDED_FOLLOWING" -> b.unboundedFollowing()
        "CURRENT_ROW" -> b.currentRow()
        "PRECEDING", "FOLLOWING" -> {
            val offset = if (bound.unit in setOf("ROW", "NUMBER")) b.literal(bound.offset) else {
                val unit = when (bound.unit) {
                    "DAY" -> TimeUnit.DAY
                    "HOUR" -> TimeUnit.HOUR
                    "MINUTE" -> TimeUnit.MINUTE
                    "SECOND", "MILLISECOND", "MICROSECOND" -> TimeUnit.SECOND
                    else -> throw IllegalArgumentException("unsupported temporal window unit")
                }
                val qualifier = SqlIntervalQualifier(unit, 10, null, 6, SqlParserPos.ZERO)
                if (bound.unit == "MICROSECOND") b.call(Ops.MULTIPLY,
                    b.rexBuilder.makeExactLiteral(WindowFrameContract.normalizedOffset(bound).divide(BigDecimal.valueOf(1000))),
                    b.rexBuilder.makeIntervalLiteral(BigDecimal.valueOf(1000), qualifier))
                else b.rexBuilder.makeIntervalLiteral(WindowFrameContract.normalizedOffset(bound), qualifier)
            }
            if (bound.kind == "PRECEDING") b.preceding(offset) else b.following(offset)
        }
        else -> throw IllegalArgumentException("unsupported window bound")
    }

    fun orderKeys(b: RelBuilder, keys: List<PostOperationContract.OrderKey>): List<RexNode> = keys.map {
        val value = b.field(it.symbol).let { field -> if (it.direction == "DESC") b.desc(field) else field }
        if (it.nulls == "FIRST") b.nullsFirst(value) else b.nullsLast(value)
    }
}
