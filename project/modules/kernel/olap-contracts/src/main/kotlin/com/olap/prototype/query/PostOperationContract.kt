package com.olap.prototype.query

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.query.ScopedInputContract.requireInputSymbol
import com.olap.prototype.query.ScopedInputContract.requireInputSymbols
import java.util.Locale

/** 完成关系上的阶段语言；这里只拥有语法不变量，不解析实参或编译 SQL。 */
object PostOperationContract {
    const val TOP_N = "TOP_N"
    const val RANK = "RANK"
    const val CUMULATIVE = "CUMULATIVE"
    const val DERIVE = "DERIVE"
    const val POST_FILTER = "POST_FILTER"
    const val SORT = "SORT"
    const val ROW_NUMBER = "ROW_NUMBER"
    const val DENSE_RANK = "DENSE_RANK"
    const val PARTITION_AGGREGATE = "PARTITION_AGGREGATE"
    const val REGROUP = "REGROUP"
    const val WINDOW_AGGREGATE = "WINDOW_AGGREGATE"
    const val OFFSET_VALUE = "OFFSET_VALUE"
    const val PROJECT = "PROJECT"
    const val EXPAND_COLLECTION = "EXPAND_COLLECTION"
    const val JOIN_INPUT = "JOIN_INPUT"
    const val ANCESTOR_CLOSURE = "ANCESTOR_CLOSURE"
    const val SELECT_VERSIONS = "SELECT_VERSIONS"
    const val OBSERVE_WINDOW = "OBSERVE_WINDOW"
    internal const val MAX_TOP_N_LIMIT = 10_000
    private val aliasPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val operationNames = linkedMapOf(
        DERIVE to "派生计算", CUMULATIVE to "累计", RANK to "排名", TOP_N to "TopN", POST_FILTER to "后置过滤",
        SORT to "结果排序", ROW_NUMBER to "分区有序行号", DENSE_RANK to "分区多键稠密排名",
        PARTITION_AGGREGATE to "完整分区聚合", REGROUP to "结果粒度重新分组聚合", WINDOW_AGGREGATE to "显式行范围或值范围窗口聚合",
        OFFSET_VALUE to "有序前后行取值", PROJECT to "结果列投影", EXPAND_COLLECTION to "类型化集合逐项展开",
        JOIN_INPUT to "完成关系的显式关联", ANCESTOR_CLOSURE to "祖先可达关系闭包",
        SELECT_VERSIONS to "明确顺序和并列政策的版本选择", OBSERVE_WINDOW to "明确观察范围与展示范围的窗口统计"
    )

    fun operationTypes(): List<String> = java.util.List.copyOf(operationNames.keys)
    fun requireType(value: String?, field: String): String = ProducerContracts.requireText(value, field).also {
        if (it !in operationNames) throw ProducerContractException("$field must be one of ${operationTypes().joinToString()}")
    }
    fun displayName(operationType: String): String = operationNames.getValue(requireType(operationType, "operationType"))
    fun requireAlias(value: String?): String = requireNotNull(value?.takeIf(aliasPattern::matches)) { "result alias must be an explicit identifier" }
    internal fun requireText(value: String?, field: String): String = requireNotNull(value?.takeUnless(String::isBlank)) { "$field is required" }
    internal fun requireTextValue(value: Any?, field: String): String = requireText(value as? String, field)
    internal fun requireDirection(value: Any?, field: String): String = requireTextValue(value, field).uppercase(Locale.ROOT).also {
        require(it == "ASC" || it == "DESC") { "$field must be ASC or DESC" }
    }
    private fun output(alias: String, display: String, field: String) {
        requireAlias(alias)
        requireText(display, "$field.outputDisplayName")
    }
    private fun checkedOrderKeys(keys: List<OrderKey>): List<OrderKey> = java.util.List.copyOf(keys).also {
        require(it.isNotEmpty() && it.map(OrderKey::symbol).distinct().size == it.size) { "ordering requires nonempty distinct symbol keys" }
    }

    sealed interface ValidatedOperation { val operationType: String }
    sealed interface OutputOperation : ValidatedOperation { val outputAlias: String; val outputDisplayName: String }
    sealed interface AggregateOutput : OutputOperation {
        val input: String
        val aggregation: ResultAggregation
        val partitionBy: List<String>
    }

    data class JoinInput(val inputId: String, val occurrenceId: String, val joinType: String, val condition: ResultExpression) : ValidatedOperation {
        override val operationType: String get() = JOIN_INPUT
        init {
            ScopedInputContract.requireId(inputId); ScopedInputContract.requireId(occurrenceId)
            require(joinType in setOf("INNER", "LEFT", "RIGHT", "FULL", "SEMI", "ANTI")) { "unsupported relation join type" }
            require(ResultExpression.canProduceBoolean(condition)) { "relation condition must produce BOOLEAN" }
        }
    }

    class AncestorClosure(sourceKeys: List<String>, parentKeys: List<String>, descendantAliases: List<String>, ancestorAliases: List<String>, val includeSelf: Boolean) : ValidatedOperation {
        val sourceKeys = requireInputSymbols(sourceKeys, "ancestor.sourceKeys")
        val parentKeys = requireInputSymbols(parentKeys, "ancestor.parentKeys")
        val descendantAliases: List<String> = java.util.List.copyOf(descendantAliases)
        val ancestorAliases: List<String> = java.util.List.copyOf(ancestorAliases)
        override val operationType: String get() = ANCESTOR_CLOSURE
        init {
            val size = this.sourceKeys.size
            require(size in 1..16 && this.parentKeys.size == size && this.descendantAliases.size == size && this.ancestorAliases.size == size &&
                this.sourceKeys.distinct().size == size && this.parentKeys.distinct().size == size) {
                "ancestor closure requires equally sized distinct identity tuples of 1..16 components"
            }
            val aliases = this.descendantAliases + this.ancestorAliases
            require(aliases.distinct().size == aliases.size) { "closure output aliases must be distinct" }
            aliases.forEach(::requireAlias)
        }
        override fun equals(other: Any?): Boolean = other is AncestorClosure && sourceKeys == other.sourceKeys && parentKeys == other.parentKeys && descendantAliases == other.descendantAliases && ancestorAliases == other.ancestorAliases && includeSelf == other.includeSelf
        override fun hashCode(): Int = listOf(sourceKeys, parentKeys, descendantAliases, ancestorAliases, includeSelf).hashCode()
    }
    data class ExpandCollection(val input: String, val elementAlias: String, val ordinalAlias: String, val mode: String) : ValidatedOperation {
        override val operationType: String get() = EXPAND_COLLECTION
        init {
            ScopedInputContract.requireSymbol(input, "collection input")
            requireAlias(elementAlias); requireAlias(ordinalAlias)
            require(elementAlias != ordinalAlias) { "collection outputs require distinct aliases" }
            require(mode in setOf("INNER", "OUTER")) { "collection expansion mode must be INNER or OUTER" }
        }
    }
    class Project(symbols: List<String>) : ValidatedOperation {
        val symbols = requireInputSymbols(symbols, "project.symbols")
        override val operationType: String get() = PROJECT
        init { require(this.symbols.isNotEmpty()) { "projection must retain explicit symbols" } }
        override fun equals(other: Any?): Boolean = other is Project && symbols == other.symbols
        override fun hashCode(): Int = symbols.hashCode()
    }
    data class Regroup(val grouping: ResultGrouping) : ValidatedOperation { override val operationType: String get() = REGROUP }
    data class PostFilter(val expression: ResultExpression) : ValidatedOperation {
        override val operationType: String get() = POST_FILTER
        init { require(ResultExpression.canProduceBoolean(expression)) { "post filter must produce BOOLEAN" } }
    }
    class Sort(orderKeys: List<OrderKey>) : ValidatedOperation {
        val orderKeys = checkedOrderKeys(orderKeys)
        override val operationType: String get() = SORT
        override fun equals(other: Any?): Boolean = other is Sort && orderKeys == other.orderKeys
        override fun hashCode(): Int = orderKeys.hashCode()
    }
    class TopN(orderKeys: List<OrderKey>, val limit: Int) : ValidatedOperation {
        val orderKeys = checkedOrderKeys(orderKeys)
        override val operationType: String get() = TOP_N
        init { require(limit in 1..MAX_TOP_N_LIMIT) { "top limit is outside the declared bound" } }
        override fun equals(other: Any?): Boolean = other is TopN && orderKeys == other.orderKeys && limit == other.limit
        override fun hashCode(): Int = listOf(orderKeys, limit).hashCode()
    }
    data class Derive(val expression: ResultExpression, override val outputAlias: String, override val outputDisplayName: String) : OutputOperation {
        override val operationType: String get() = DERIVE
        init { output(outputAlias, outputDisplayName, "derive") }
    }
    class Rank(orderBy: String, direction: String, partitionBy: List<String>, override val outputAlias: String, override val outputDisplayName: String) : OutputOperation {
        val orderBy = requireInputSymbol(orderBy, "rank.orderBy")
        val direction = requireDirection(direction, "rank.direction")
        val partitionBy = requireInputSymbols(partitionBy, "rank.partitionBy")
        override val operationType: String get() = RANK
        init { output(outputAlias, outputDisplayName, "rank") }
        override fun equals(other: Any?): Boolean = other is Rank && orderBy == other.orderBy && direction == other.direction && partitionBy == other.partitionBy && outputAlias == other.outputAlias && outputDisplayName == other.outputDisplayName
        override fun hashCode(): Int = listOf(orderBy, direction, partitionBy, outputAlias, outputDisplayName).hashCode()
    }
    class OrderedRank(override val operationType: String, partitionBy: List<String>, override val outputAlias: String, override val outputDisplayName: String, orderKeys: List<OrderKey>) : OutputOperation {
        val partitionBy = requireInputSymbols(partitionBy, "orderedRank.partitionBy")
        val orderKeys = checkedOrderKeys(orderKeys)
        init {
            require(operationType == ROW_NUMBER || operationType == DENSE_RANK) { "unsupported ordered rank" }
            output(outputAlias, outputDisplayName, "orderedRank")
        }
        override fun equals(other: Any?): Boolean = other is OrderedRank && operationType == other.operationType && partitionBy == other.partitionBy && outputAlias == other.outputAlias && outputDisplayName == other.outputDisplayName && orderKeys == other.orderKeys
        override fun hashCode(): Int = listOf(operationType, partitionBy, outputAlias, outputDisplayName, orderKeys).hashCode()
    }
    class Cumulative(input: String, orderBy: String, direction: String, partitionBy: List<String>, override val outputAlias: String, override val outputDisplayName: String,
        val aggregation: ResultAggregation.Sum) : OutputOperation {
        val input = requireInputSymbol(input, "cumulative.input")
        val orderBy = requireInputSymbol(orderBy, "cumulative.orderBy")
        val direction = requireDirection(direction, "cumulative.direction")
        val partitionBy = requireInputSymbols(partitionBy, "cumulative.partitionBy")
        override val operationType: String get() = CUMULATIVE
        init {
            output(outputAlias, outputDisplayName, "cumulative")
            require(aggregation.nullPolicy == ResultAggregation.NullPolicy.IGNORE) { "cumulative SUM ignores null inputs" }
        }
        override fun equals(other: Any?): Boolean = other is Cumulative && input == other.input && orderBy == other.orderBy && direction == other.direction && partitionBy == other.partitionBy && outputAlias == other.outputAlias && outputDisplayName == other.outputDisplayName && aggregation == other.aggregation
        override fun hashCode(): Int = listOf(input, orderBy, direction, partitionBy, outputAlias, outputDisplayName, aggregation).hashCode()
    }
    class PartitionAggregate(input: String, partitionBy: List<String>, override val outputAlias: String, override val outputDisplayName: String,
        override val aggregation: ResultAggregation) : AggregateOutput {
        override val input = requireInputSymbol(input, "partition.input")
        override val partitionBy = requireInputSymbols(partitionBy, "partition.partitionBy")
        override val operationType: String get() = PARTITION_AGGREGATE
        init { output(outputAlias, outputDisplayName, "partition") }
        override fun equals(other: Any?): Boolean = other is PartitionAggregate && input == other.input && partitionBy == other.partitionBy && outputAlias == other.outputAlias && outputDisplayName == other.outputDisplayName && aggregation == other.aggregation
        override fun hashCode(): Int = listOf(input, partitionBy, outputAlias, outputDisplayName, aggregation).hashCode()
    }
    class WindowAggregate(input: String, partitionBy: List<String>, override val outputAlias: String, override val outputDisplayName: String, orderKeys: List<OrderKey>,
        override val aggregation: ResultAggregation, val windowFrame: WindowFrameContract.Frame) : AggregateOutput {
        override val input = requireInputSymbol(input, "window.input")
        override val partitionBy = requireInputSymbols(partitionBy, "window.partitionBy")
        val orderKeys = checkedOrderKeys(orderKeys)
        override val operationType: String get() = WINDOW_AGGREGATE
        init {
            windowFrame.requireOrderKeys(this.orderKeys.size)
            output(outputAlias, outputDisplayName, "window")
        }
        override fun equals(other: Any?): Boolean = other is WindowAggregate && input == other.input && partitionBy == other.partitionBy && outputAlias == other.outputAlias && outputDisplayName == other.outputDisplayName && orderKeys == other.orderKeys && aggregation == other.aggregation && windowFrame == other.windowFrame
        override fun hashCode(): Int = listOf(input, partitionBy, outputAlias, outputDisplayName, orderKeys, aggregation, windowFrame).hashCode()
    }

    enum class VersionTies { FAIL, KEEP_ALL }
    data class SnapshotSelection(val timeSymbol: String, val asOf: Calculation.Binding?) {
        init {
            requireInputSymbol(timeSymbol, "snapshot.timeSymbol")
            require(asOf == null || asOf is Calculation.Argument || asOf is Calculation.Scalar && asOf.scalarType in setOf(
                ScalarValueType.DATE, ScalarValueType.LOCAL_TIMESTAMP, ScalarValueType.OFFSET_TIMESTAMP)) {
                "snapshot point requires a temporal scalar or explicit argument"
            }
        }
    }
    class SelectVersions(partitionBy: List<String>, orderKeys: List<OrderKey>, val ties: VersionTies, val snapshot: SnapshotSelection?) : ValidatedOperation {
        val partitionBy = requireInputSymbols(partitionBy, "versions.partitionBy")
        val orderKeys = checkedOrderKeys(orderKeys)
        override val operationType: String get() = SELECT_VERSIONS
        override fun equals(other: Any?): Boolean = other is SelectVersions && partitionBy == other.partitionBy && orderKeys == other.orderKeys && ties == other.ties && snapshot == other.snapshot
        override fun hashCode(): Int = listOf(partitionBy, orderKeys, ties, snapshot).hashCode()
    }
    enum class ObservationDomain { ROWS, ELAPSED, CALENDAR }
    data class CalendarAxis(val inputId: String, val dateSymbol: String) {
        init { ScopedInputContract.requireId(inputId); requireInputSymbol(dateSymbol, "calendar.dateSymbol") }
    }

    /** 观察域先裁范围，聚合后再裁展示；日历人口只能来自明确的输入。 */
    class ObserveWindow(inputKeys: List<String>, partitionBy: List<String>, override val outputAlias: String, override val outputDisplayName: String,
        orderKeys: List<OrderKey>, val aggregation: ResultAggregation, val windowFrame: WindowFrameContract.Frame, val domain: ObservationDomain,
        val timeSymbol: String?, val observationRange: Calculation.Binding?,
        val displayRange: Calculation.Binding?, val calendar: CalendarAxis?) : OutputOperation {
        val inputKeys = requireInputSymbols(inputKeys, "observations.inputKeys")
        val partitionBy = requireInputSymbols(partitionBy, "observations.partitionBy")
        val orderKeys = checkedOrderKeys(orderKeys)
        override val operationType: String get() = OBSERVE_WINDOW
        init {
            require(this.inputKeys.isNotEmpty() && (aggregation is ResultAggregation.Count && aggregation.distinct || this.inputKeys.size == 1)) {
                "window aggregation requires one value or a complete distinct identity tuple"
            }
            windowFrame.requireOrderKeys(this.orderKeys.size)
            output(outputAlias, outputDisplayName, "observations")
            timeSymbol?.let { requireInputSymbol(it, "observations.timeSymbol") }
            listOf(observationRange, displayRange).forEach {
                require(it == null || it is Calculation.Range || it is Calculation.Argument) { "observation and display ranges require explicit RANGE arguments" }
            }
            require(timeSymbol != null || observationRange == null && displayRange == null && domain == ObservationDomain.ROWS) {
                "temporal observation scopes require their actual time symbol"
            }
            require(windowFrame.mode == if (domain == ObservationDomain.ROWS) "ROWS" else "RANGE") { "observation domain and actual window frame differ" }
            require(domain == ObservationDomain.ROWS || this.orderKeys.first().symbol == timeSymbol) { "temporal window must order by its declared time axis" }
            require((domain == ObservationDomain.CALENDAR) == (calendar != null)) { "only calendar windows require a real calendar input" }
            if (calendar != null) {
                require(observationRange != null) { "calendar coverage requires an explicit finite DATE observation range" }
                require(listOf(windowFrame.lower, windowFrame.upper).all { it.offset == null || it.unit == "DAY" }) { "calendar DATE windows use DAY bounds" }
            }
        }
        override fun equals(other: Any?): Boolean = other is ObserveWindow && inputKeys == other.inputKeys && partitionBy == other.partitionBy && outputAlias == other.outputAlias && outputDisplayName == other.outputDisplayName && orderKeys == other.orderKeys && aggregation == other.aggregation && windowFrame == other.windowFrame && domain == other.domain && timeSymbol == other.timeSymbol && observationRange == other.observationRange && displayRange == other.displayRange && calendar == other.calendar
        override fun hashCode(): Int = listOf(inputKeys, partitionBy, outputAlias, outputDisplayName, orderKeys, aggregation, windowFrame, domain, timeSymbol, observationRange, displayRange, calendar).hashCode()
    }
    class OffsetValue(input: String, partitionBy: List<String>, override val outputAlias: String, override val outputDisplayName: String, orderKeys: List<OrderKey>, val function: String, val distance: Int) : OutputOperation {
        val input = requireInputSymbol(input, "offset.input")
        val partitionBy = requireInputSymbols(partitionBy, "offset.partitionBy")
        val orderKeys = checkedOrderKeys(orderKeys)
        override val operationType: String get() = OFFSET_VALUE
        init {
            output(outputAlias, outputDisplayName, "offset")
            require(function in setOf("LAG", "LEAD") && distance > 0) { "offset requires LAG or LEAD and a positive distance" }
        }
        override fun equals(other: Any?): Boolean = other is OffsetValue && input == other.input && partitionBy == other.partitionBy && outputAlias == other.outputAlias && outputDisplayName == other.outputDisplayName && orderKeys == other.orderKeys && function == other.function && distance == other.distance
        override fun hashCode(): Int = listOf(input, partitionBy, outputAlias, outputDisplayName, orderKeys, function, distance).hashCode()
    }
    class ResultGrouping(groupBy: List<String>, aggregates: List<ResultAggregate>, val groupingLevels: GroupingLevels.Bound?) {
        val groupBy = requireInputSymbols(groupBy, "regroup.groupBy")
        val aggregates: List<ResultAggregate> = java.util.List.copyOf(aggregates)
        init {
            require(this.aggregates.isNotEmpty()) { "regroup requires explicit aggregates" }
            val names = this.groupBy.toMutableSet()
            this.aggregates.forEach { require(names.add(it.outputAlias)) { "regroup output names conflict" } }
            groupingLevels?.let { levels ->
                require(names.add(levels.outputAlias)) { "grouping level output conflicts" }
                val definition = GroupingLevels(levels.outputAlias, levels.levels.map { GroupingLevels.Level(it.id, it.groupBy) })
                require(definition.bind(this.groupBy) == levels) { "grouping levels differ from their explicit dimensions" }
            }
        }
        override fun equals(other: Any?): Boolean = other is ResultGrouping && groupBy == other.groupBy && aggregates == other.aggregates && groupingLevels == other.groupingLevels
        override fun hashCode(): Int = listOf(groupBy, aggregates, groupingLevels).hashCode()
    }
    data class ResultAggregate(val input: String, val aggregation: ResultAggregation, val outputAlias: String, val outputDisplayName: String) {
        init { requireInputSymbol(input, "aggregate.input"); output(outputAlias, outputDisplayName, "aggregate") }
    }
    class OrderKey(val symbol: String, direction: String, val nulls: String) {
        val direction = requireDirection(direction, "order.direction")
        init { requireInputSymbol(symbol, "order.symbol"); require(nulls in setOf("FIRST", "LAST")) { "order null placement must be FIRST or LAST" } }
        override fun equals(other: Any?): Boolean = other is OrderKey && symbol == other.symbol && direction == other.direction && nulls == other.nulls
        override fun hashCode(): Int = listOf(symbol, direction, nulls).hashCode()
    }
}
