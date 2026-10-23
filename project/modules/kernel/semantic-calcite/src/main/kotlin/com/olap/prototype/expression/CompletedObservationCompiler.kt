package com.olap.prototype.expression

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.query.PostOperationContract
import com.olap.prototype.query.ResultAggregation
import com.olap.prototype.query.ResultExpression
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.time.TemporalWindow
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.type.SqlTypeUtil
import org.apache.calcite.tools.RelBuilder
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 版本、观察范围、有限日历和展示裁剪的完整边界，不拥有跨阶段列累计。 */
internal class CompletedObservationCompiler(private val b: RelBuilder, private val parameters: MutableList<JdbcParameter>,
    private val relations: Map<String, RelNode>, private val checks: MutableList<RelationalCheck>, private val native: NativeExpressionScope) {

    fun selectVersions(selection: PostOperationContract.SelectVersions) {
        val names = b.peek().rowType.fieldNames.toList()
        val snapshot = selection.snapshot
        if (snapshot != null) require(SqlTypeUtil.isDatetime(b.field(snapshot.timeSymbol).type)) { "snapshot selection requires an actual temporal axis" }
        if (snapshot?.asOf != null) {
            val point = snapshot.asOf as? Calculation.Scalar ?: throw IllegalArgumentException("snapshot point is not bound")
            val time = b.field(snapshot.timeSymbol)
            val asOf = temporalLiteral(point.scalarType, point.value)
            requireTemporalType(time, asOf)
            b.filter(b.call(Ops.LESS_THAN_OR_EQUAL, time, asOf))
        }
        val rankName = internalName(names, "__olap_version_rank")
        val rank = b.aggregateCall(Ops.DENSE_RANK, emptyList()).over().partitionBy(selection.partitionBy.map(b::field))
            .orderBy(CompletedWindows.orderKeys(b, selection.orderKeys)).toRex()
        b.project(b.fields() + rank, names + rankName, true).filter(b.equals(b.field(rankName), b.literal(1)))
        var winners = b.build()
        if (selection.ties == PostOperationContract.VersionTies.FAIL) {
            b.push(winners); checkedIdentity(selection.partitionBy, "VERSION_SELECTION_TIE"); winners = b.build()
        }
        if (snapshot != null) {
            b.push(winners).filter(b.or(listOf(b.isNull(b.field(snapshot.timeSymbol))) + selection.partitionBy.map { b.isNull(b.field(it)) }))
            b.project(listOf(b.literal(1)), listOf("violation"), true).limit(0, 1)
            checks.add(RelationalCheck("SNAPSHOT_OBSERVATION_IDENTITY_NULL", b.build()))
        }
        b.push(winners).project(names.map(b::field), names, true)
    }

    private fun checkedIdentity(keys: List<String>, code: String) {
        val input = b.build()
        val names = input.rowType.fieldNames
        val count = internalName(names, "__olap_observation_count")
        b.push(input).aggregate(b.groupKey(keys.map(b::field)), b.count().`as`(count))
        b.filter(b.call(Ops.GREATER_THAN, b.field(count), b.literal(1))).project(listOf(b.literal(1)), listOf("violation"), true).limit(0, 1)
        checks.add(RelationalCheck(code, b.build()))
        b.push(input)
        val singles = names.filterNot(keys::contains).map { b.aggregateCall(Ops.SINGLE_VALUE, listOf(b.field(it))).`as`(it) } + b.count().`as`(count)
        b.aggregate(b.groupKey(keys.map(b::field)), singles).filter(b.call(Ops.GREATER_THAN, b.field(count), b.literal(0)))
        b.project(names.map(b::field), names, true)
    }

    fun observeWindow(observation: PostOperationContract.ObserveWindow) {
        val range = boundRange(observation.observationRange)
        val displayRange = boundRange(observation.displayRange)
        if (observation.calendar != null) requireCalendar(observation, range)
        filterRange(observation.timeSymbol, range)
        if (observation.domain == PostOperationContract.ObservationDomain.ROWS) {
            val identity = observation.partitionBy.toCollection(linkedSetOf())
            observation.orderKeys.forEach { identity.add(it.symbol) }
            checkedIdentity(identity.toList(), "OBSERVATION_ROW_ORDER_TIE")
        }
        var expression = if (observation.inputKeys.size == 1) CompletedWindows.windowAggregate(b,
            PostOperationContract.WindowAggregate(observation.inputKeys.first(), observation.partitionBy, observation.outputAlias,
                observation.outputDisplayName, observation.orderKeys, observation.aggregation, observation.windowFrame))
        else distinctObjects(observation)
        expression = SemanticNumericRex.aggregateOutput(b, expression, observation.aggregation)
        val names = b.peek().rowType.fieldNames.toList() + observation.outputAlias
        b.project(b.fields() + expression, names, true)
        // 窗口已经成为关系输入，展示裁剪不能丢失预热和期前观察。
        filterRange(observation.timeSymbol, displayRange)
    }

    private fun distinctObjects(observation: PostOperationContract.ObserveWindow): RexNode {
        val aggregation = observation.aggregation
        require(aggregation is ResultAggregation.Count && aggregation.distinct) { "multiple observation keys require a distinct object count" }
        val keys = observation.inputKeys.map(b::field)
        val tuple = b.call(Ops.ROW, keys)
        val complete = b.call(Ops.CASE, b.and(keys.map(b::isNotNull)), tuple, b.rexBuilder.makeNullLiteral(tuple.type))
        fun framed(call: RelBuilder.AggCall) = CompletedWindows.framedWindow(b, call, observation.partitionBy, observation.orderKeys, observation.windowFrame)
        val expression = framed(b.aggregateCall(Ops.COUNT, listOf(complete)).distinct(true))
        if (aggregation.nullPolicy != ResultAggregation.NullPolicy.PROPAGATE) return expression
        val known = framed(b.count(complete))
        val total = framed(b.count())
        return b.call(Ops.CASE, b.equals(known, total), expression, b.rexBuilder.makeNullLiteral(expression.type))
    }

    private fun requireCalendar(observation: PostOperationContract.ObserveWindow, range: TemporalWindow?) {
        require(range != null && range.valueType == TemporalWindow.ValueType.DATE && range.start != null && range.end != null) { "calendar observation coverage requires a finite DATE range" }
        require(b.field(observation.timeSymbol).type.sqlTypeName == SqlTypeName.DATE) { "calendar observation axis must be an actual DATE" }
        val current = b.build()
        val calendarRef = requireNotNull(observation.calendar)
        val calendar = requireNotNull(relations[calendarRef.inputId]) { "declared calendar input is unavailable" }
        val date = calendarRef.dateSymbol
        b.push(calendar)
        require(b.field(date).type.sqlTypeName == SqlTypeName.DATE) { "calendar source axis must be an actual DATE" }
        b.filter(b.isNull(b.field(date))).project(listOf(b.literal(1)), listOf("violation"), true).limit(0, 1)
        checks.add(RelationalCheck("CALENDAR_DATE_NULL", b.build()))
        b.push(calendar); filterRange(date, range)
        val selected = b.build()
        val count = internalName(calendar.rowType.fieldNames, "__olap_calendar_count")
        b.push(selected).aggregate(b.groupKey(date), b.count().`as`(count))
        b.filter(b.call(Ops.GREATER_THAN, b.field(count), b.literal(1))).project(listOf(b.literal(1)), listOf("violation"), true).limit(0, 1)
        checks.add(RelationalCheck("CALENDAR_DATE_NOT_UNIQUE", b.build()))
        val expected = ChronoUnit.DAYS.between(LocalDate.parse(range.start), LocalDate.parse(range.end))
        b.push(selected).aggregate(b.groupKey(), b.count().`as`(count))
        b.filter(b.call(Ops.NOT_EQUALS, b.field(count), b.literal(expected))).project(listOf(b.literal(1)), listOf("violation"), true).limit(0, 1)
        checks.add(RelationalCheck("CALENDAR_OBSERVATION_COVERAGE_MISSING", b.build()))
        b.push(current)
    }

    private fun boundRange(binding: Calculation.Binding?): TemporalWindow? = when (binding) {
        null -> null
        is Calculation.Range -> binding.window
        else -> throw IllegalArgumentException("observation range is not bound")
    }
    private fun filterRange(symbol: String?, range: TemporalWindow?) {
        if (range == null || range.start == null && range.end == null) return
        val time = requireNotNull(symbol) { "temporal range requires its actual time axis" }
        val scalar = ScalarValueType.valueOf(range.valueType.name)
        val predicates = mutableListOf<RexNode>()
        range.start?.let { val value = temporalLiteral(scalar, it); requireTemporalType(b.field(time), value); predicates.add(b.call(Ops.GREATER_THAN_OR_EQUAL, b.field(time), value)) }
        range.end?.let { val value = temporalLiteral(scalar, it); requireTemporalType(b.field(time), value); predicates.add(b.call(Ops.LESS_THAN, b.field(time), value)) }
        b.filter(b.and(predicates))
    }
    private fun temporalLiteral(type: ScalarValueType, text: String): RexNode = CompletedExpressionCompiler.compile(b, ResultExpression.ResultTypedLiteral(type, text), parameters, native)
    private fun requireTemporalType(axis: RexNode, value: RexNode) { require(SqlTypeUtil.isDatetime(axis.type) && axis.type.sqlTypeName == value.type.sqlTypeName) { "temporal selection requires the actual axis type without implicit conversion" } }
    private fun internalName(names: Collection<String>, prefix: String): String {
        var name = prefix; var suffix = 1
        while (name in names) name = "$prefix${suffix++}"
        return name
    }
}
