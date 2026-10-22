package com.olap.prototype.query

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.olap.prototype.calculation.Calculation
import com.olap.prototype.contract.ProducerContracts.requireExactFields
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.PostOperationContract as Stage
import com.olap.prototype.query.PostOperationContract.ValidatedOperation
import com.olap.prototype.util.OlapJsonUtils

/** 阶段的唯一 JSON 边界；语法校验由正式模型拥有，不在服务侧再造一份。 */
object PostOperationCodec {
    /** 只描述正式平铺 JSON；运行时聚合由 ResultAggregation 拥有。 */
    data class AggregateArguments(
        @param:JsonProperty(required = true) val input: String,
        @param:JsonProperty(required = true) val aggregation: String,
        @param:JsonProperty(required = true) val nullPolicy: String,
        @param:JsonProperty(required = true) val outputAlias: String,
        @param:JsonProperty(required = true) val outputDisplayName: String,
        @param:JsonProperty(required = true) val valueContract: MetricValueContract?,
        @param:JsonProperty(required = true) val aggregationRounding: MetricValueContract.Rounding
    )

    internal val aggregationTypes: Map<String, Class<out ResultAggregation>> = mapOf(
        "SUM" to ResultAggregation.Sum::class.java,
        "AVG" to ResultAggregation.Average::class.java,
        "STDDEV_SAMP" to ResultAggregation.SampleDeviation::class.java,
        "MIN" to ResultAggregation.Minimum::class.java,
        "MAX" to ResultAggregation.Maximum::class.java,
        "COUNT" to ResultAggregation.Count::class.java,
        "COUNT_DISTINCT" to ResultAggregation.Count::class.java,
        "ARRAY_AGG" to ResultAggregation.Collect::class.java,
        "ARRAY_AGG_DISTINCT" to ResultAggregation.Collect::class.java
    )

    class AggregateReader : JsonDeserializer<Stage.ResultAggregate>() {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): Stage.ResultAggregate {
            val node: JsonNode = parser.codec.readTree(parser)
            val raw = parser.codec.treeToValue(node, Map::class.java)
            return readAggregate(raw, "aggregate")
        }
    }

    class AggregateWriter : JsonSerializer<Stage.ResultAggregate>() {
        override fun serialize(value: Stage.ResultAggregate, output: JsonGenerator, provider: SerializerProvider) {
            provider.defaultSerializeValue(aggregateArguments(value), output)
        }
    }

    class Reader : JsonDeserializer<ValidatedOperation>() {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): ValidatedOperation {
            val node: JsonNode = parser.codec.readTree(parser)
            require(node.isObject && node.fieldNames().asSequence().toSet() == setOf("opType", "arguments") && node["opType"].isTextual && node["arguments"].isObject) {
                "stage requires exactly opType and arguments"
            }
            @Suppress("UNCHECKED_CAST")
            val arguments = parser.codec.treeToValue(node["arguments"], Map::class.java) as Map<String, Any?>
            return parse(node["opType"].textValue(), arguments)
        }
    }
    class Writer : JsonSerializer<ValidatedOperation>() {
        override fun serialize(value: ValidatedOperation, output: JsonGenerator, provider: SerializerProvider) {
            output.writeStartObject()
            output.writeStringField("opType", value.operationType)
            output.writeFieldName("arguments")
            writeValue(output, arguments(value))
            output.writeEndObject()
        }
        private fun writeValue(output: JsonGenerator, value: Any?) {
            when (value) {
                is Calculation.Binding -> OlapJsonUtils.mapper().writerFor(Calculation.Binding::class.java).writeValue(output, value)
                is Calculation.ObservationUnit -> OlapJsonUtils.mapper().writerFor(Calculation.ObservationUnit::class.java).writeValue(output, value)
                is Map<*, *> -> {
                    output.writeStartObject()
                    value.keys.map { it as String }.sorted().forEach { output.writeFieldName(it); writeValue(output, value[it]) }
                    output.writeEndObject()
                }
                is List<*> -> { output.writeStartArray(); value.forEach { writeValue(output, it) }; output.writeEndArray() }
                else -> output.writeObject(value)
            }
        }
    }
    private fun arguments(value: ValidatedOperation): Map<String, Any?> = when (value) {
        is Stage.SelectVersions -> mapOf("partitionBy" to value.partitionBy, "orderKeys" to value.orderKeys, "ties" to value.ties,
            "snapshot" to value.snapshot?.let { mapOf("timeSymbol" to it.timeSymbol, "asOf" to it.asOf) })
        is Stage.ObserveWindow -> mapOf("inputKeys" to value.inputKeys, "partitionBy" to value.partitionBy, "outputAlias" to value.outputAlias,
            "outputDisplayName" to value.outputDisplayName, "orderKeys" to value.orderKeys, "frame" to frame(value.windowFrame),
            "domain" to value.domain, "timeSymbol" to value.timeSymbol, "observationRange" to value.observationRange, "displayRange" to value.displayRange, "calendar" to value.calendar) + aggregationArguments(value.aggregation)
        is Stage.AncestorClosure -> mapOf("sourceKeys" to value.sourceKeys, "parentKeys" to value.parentKeys, "descendantAliases" to value.descendantAliases,
            "ancestorAliases" to value.ancestorAliases, "includeSelf" to value.includeSelf)
        is Stage.JoinInput -> mapOf("inputId" to value.inputId, "occurrenceId" to value.occurrenceId, "joinType" to value.joinType, "condition" to ResultExpressionCodec.write(value.condition))
        is Stage.Project -> mapOf("symbols" to value.symbols)
        is Stage.ExpandCollection -> mapOf("input" to value.input, "elementAlias" to value.elementAlias, "ordinalAlias" to value.ordinalAlias, "mode" to value.mode)
        is Stage.Derive -> mapOf("expression" to ResultExpressionCodec.write(value.expression), "outputAlias" to value.outputAlias, "outputDisplayName" to value.outputDisplayName)
        is Stage.PostFilter -> mapOf("expression" to ResultExpressionCodec.write(value.expression))
        is Stage.Sort -> mapOf("orderKeys" to value.orderKeys)
        is Stage.TopN -> mapOf("orderKeys" to value.orderKeys, "limit" to value.limit)
        is Stage.Rank -> mapOf("orderBy" to value.orderBy, "direction" to value.direction, "partitionBy" to value.partitionBy,
            "outputAlias" to value.outputAlias, "outputDisplayName" to value.outputDisplayName)
        is Stage.OrderedRank -> mapOf("orderKeys" to value.orderKeys, "partitionBy" to value.partitionBy, "outputAlias" to value.outputAlias, "outputDisplayName" to value.outputDisplayName)
        is Stage.Cumulative -> mapOf("input" to value.input, "orderBy" to value.orderBy, "direction" to value.direction, "partitionBy" to value.partitionBy,
            "outputAlias" to value.outputAlias, "outputDisplayName" to value.outputDisplayName,
            "valueContract" to value.aggregation.valueContract, "aggregationRounding" to MetricValueContract.Rounding.NONE)
        is Stage.PartitionAggregate -> mapOf("input" to value.input,
            "partitionBy" to value.partitionBy, "outputAlias" to value.outputAlias, "outputDisplayName" to value.outputDisplayName) + aggregationArguments(value.aggregation)
        is Stage.WindowAggregate -> mapOf("input" to value.input,
            "partitionBy" to value.partitionBy, "orderKeys" to value.orderKeys, "frame" to frame(value.windowFrame), "outputAlias" to value.outputAlias,
            "outputDisplayName" to value.outputDisplayName) + aggregationArguments(value.aggregation)
        is Stage.OffsetValue -> mapOf("input" to value.input, "function" to value.function, "offset" to value.distance, "partitionBy" to value.partitionBy,
            "orderKeys" to value.orderKeys, "outputAlias" to value.outputAlias, "outputDisplayName" to value.outputDisplayName)
        is Stage.Regroup -> mapOf("groupBy" to value.grouping.groupBy, "aggregates" to value.grouping.aggregates.map(::aggregateArguments), "groupingLevels" to levels(value.grouping.groupingLevels))
    }

    private fun wireNullPolicy(aggregation: ResultAggregation): String = when (aggregation) {
        is ResultAggregation.Scalar -> aggregation.nullPolicy.name
        is ResultAggregation.Collect -> "RESPECT"
    }
    private fun wireRounding(aggregation: ResultAggregation): MetricValueContract.Rounding =
        if (aggregation is ResultAggregation.Average) aggregation.rounding else MetricValueContract.Rounding.NONE

    private fun aggregationArguments(aggregation: ResultAggregation): Map<String, Any?> = mapOf(
        "aggregation" to aggregation.code,
        "nullPolicy" to wireNullPolicy(aggregation),
        "valueContract" to (aggregation as? ResultAggregation.Numeric)?.valueContract,
        "aggregationRounding" to wireRounding(aggregation)
    )
    private fun aggregateArguments(value: Stage.ResultAggregate): AggregateArguments = AggregateArguments(
        value.input, value.aggregation.code, wireNullPolicy(value.aggregation), value.outputAlias, value.outputDisplayName,
        (value.aggregation as? ResultAggregation.Numeric)?.valueContract, wireRounding(value.aggregation)
    )

    private val aggregateFields = setOf("input", "aggregation", "nullPolicy", "outputAlias", "outputDisplayName", "valueContract", "aggregationRounding")
    private fun readAggregate(raw: Any?, field: String): Stage.ResultAggregate {
        val values = objectFields(raw, aggregateFields, field)
        val wire = AggregateArguments(ScopedInputContract.requireInputSymbol(values["input"], "$field.input"),
            Stage.requireTextValue(values["aggregation"], "$field.aggregation"), Stage.requireTextValue(values["nullPolicy"], "$field.nullPolicy"),
            Stage.requireAlias(Stage.requireTextValue(values["outputAlias"], "$field.outputAlias")),
            Stage.requireTextValue(values["outputDisplayName"], "$field.outputDisplayName"),
            values["valueContract"]?.let(ResultExpressionCodec::requireValueContract),
            MetricValueContract.Rounding.valueOf(Stage.requireTextValue(values["aggregationRounding"], "$field.aggregationRounding")))
        return Stage.ResultAggregate(wire.input, readAggregation(wire.aggregation, wire.nullPolicy, wire.valueContract, wire.aggregationRounding, field),
            wire.outputAlias, wire.outputDisplayName)
    }

    private fun readAggregation(values: Map<*, *>, field: String): ResultAggregation = readAggregation(
        Stage.requireTextValue(values["aggregation"], "$field.aggregation"), Stage.requireTextValue(values["nullPolicy"], "$field.nullPolicy"),
        values["valueContract"]?.let(ResultExpressionCodec::requireValueContract),
        MetricValueContract.Rounding.valueOf(Stage.requireTextValue(values["aggregationRounding"], "$field.aggregationRounding")), field
    )

    private fun readAggregation(code: String, nullPolicy: String, contract: MetricValueContract?, rounding: MetricValueContract.Rounding, field: String): ResultAggregation {
        val type = requireNotNull(aggregationTypes[code]) { "$field.aggregation is not supported" }
        require(ResultAggregation.Numeric::class.java.isAssignableFrom(type) == (contract != null)) {
            "numeric aggregation requires an explicit value contract; other aggregations require null"
        }
        require(type == ResultAggregation.Average::class.java || rounding == MetricValueContract.Rounding.NONE) { "only AVG declares a native aggregate rounding policy" }
        if (type == ResultAggregation.Collect::class.java) {
            require(nullPolicy == "RESPECT") { "$field.nullPolicy must be RESPECT for collection aggregation" }
            return ResultAggregation.Collect(code == "ARRAY_AGG_DISTINCT")
        }
        val policy = ResultAggregation.NullPolicy.valueOf(nullPolicy)
        return when (type) {
            ResultAggregation.Sum::class.java -> ResultAggregation.Sum(requireNotNull(contract), policy)
            ResultAggregation.Average::class.java -> ResultAggregation.Average(requireNotNull(contract), policy, rounding)
            ResultAggregation.SampleDeviation::class.java -> ResultAggregation.SampleDeviation(requireNotNull(contract), policy)
            ResultAggregation.Minimum::class.java -> ResultAggregation.Minimum(policy)
            ResultAggregation.Maximum::class.java -> ResultAggregation.Maximum(policy)
            ResultAggregation.Count::class.java -> ResultAggregation.Count(code == "COUNT_DISTINCT", policy)
            else -> error("collection aggregation is decoded before scalar policies")
        }
    }
    internal fun levels(bound: GroupingLevels.Bound?): GroupingLevels? = bound?.let { GroupingLevels(it.outputAlias, it.levels.map { level -> GroupingLevels.Level(level.id, level.groupBy) }) }
    private fun frame(frame: WindowFrameContract.Frame): Map<String, Any?> = mapOf("mode" to frame.mode, "lower" to bound(frame.lower), "upper" to bound(frame.upper))
    private fun bound(bound: WindowFrameContract.Bound): Map<String, Any?> = if (bound.offset == null) mapOf("kind" to bound.kind)
        else mapOf("kind" to bound.kind, "offset" to bound.offset, "unit" to bound.unit)

    fun parse(operationType: String, arguments: Map<String, Any?>, field: String = "stage"): ValidatedOperation = Decoder(arguments, field).parse(operationType)

    /** 每次解码的原始字段和错误位置只归本次调用；没有共享可变的解析状态。 */
    private class Decoder(private val args: Map<String, Any?>, private val field: String) {
        fun parse(type: String): ValidatedOperation = when (Stage.requireText(type, "$field.opType")) {
            Stage.EXPAND_COLLECTION -> {
                exact("input", "elementAlias", "ordinalAlias", "mode")
                Stage.ExpandCollection(text("input"), alias("elementAlias"), alias("ordinalAlias"), text("mode"))
            }
            Stage.ANCESTOR_CLOSURE -> {
                exact("sourceKeys", "parentKeys", "descendantAliases", "ancestorAliases", "includeSelf")
                Stage.AncestorClosure(symbols("sourceKeys"), symbols("parentKeys"), aliases("descendantAliases"), aliases("ancestorAliases"),
                    args["includeSelf"] as? Boolean ?: throw IllegalArgumentException("$field.includeSelf must be explicit boolean"))
            }
            Stage.JOIN_INPUT -> {
                exact("inputId", "occurrenceId", "joinType", "condition")
                Stage.JoinInput(text("inputId"), text("occurrenceId"), text("joinType"), expression("condition"))
            }
            Stage.PROJECT -> { exact("symbols"); Stage.Project(symbols("symbols")) }
            Stage.DERIVE -> { exact("expression", "outputAlias", "outputDisplayName"); Stage.Derive(expression("expression"), alias(), text("outputDisplayName")) }
            Stage.TOP_N -> { exact("orderKeys", "limit"); Stage.TopN(orderKeys(), WindowFrameContract.positiveInteger(args["limit"], "$field.arguments.limit")) }
            Stage.RANK -> {
                exact("orderBy", "direction", "partitionBy", "outputAlias", "outputDisplayName")
                Stage.Rank(symbol("orderBy"), text("direction"), symbols("partitionBy"), alias(), text("outputDisplayName"))
            }
            Stage.CUMULATIVE -> {
                exact("input", "orderBy", "direction", "partitionBy", "outputAlias", "outputDisplayName", "valueContract", "aggregationRounding")
                Stage.Cumulative(symbol("input"), symbol("orderBy"), text("direction"), symbols("partitionBy"), alias(), text("outputDisplayName"),
                    ResultAggregation.Sum(requireNotNull(contract()) { "$field.arguments.valueContract is required" }, ResultAggregation.NullPolicy.IGNORE)).also {
                    require(rounding() == MetricValueContract.Rounding.NONE) { "only AVG declares a native aggregate rounding policy" }
                }
            }
            Stage.POST_FILTER -> { exact("expression"); Stage.PostFilter(expression("expression")) }
            Stage.SORT -> { exact("orderKeys"); Stage.Sort(orderKeys()) }
            Stage.ROW_NUMBER, Stage.DENSE_RANK -> {
                exact("orderKeys", "partitionBy", "outputAlias", "outputDisplayName")
                Stage.OrderedRank(type, symbols("partitionBy"), alias(), text("outputDisplayName"), orderKeys())
            }
            Stage.PARTITION_AGGREGATE -> {
                exact("input", "aggregation", "nullPolicy", "partitionBy", "outputAlias", "outputDisplayName", "valueContract", "aggregationRounding")
                Stage.PartitionAggregate(symbol("input"), symbols("partitionBy"), alias(), text("outputDisplayName"), readAggregation(args, "$field.arguments"))
            }
            Stage.REGROUP -> regroup()
            Stage.WINDOW_AGGREGATE -> {
                exact("input", "aggregation", "nullPolicy", "partitionBy", "orderKeys", "frame", "outputAlias", "outputDisplayName", "valueContract", "aggregationRounding")
                Stage.WindowAggregate(symbol("input"), symbols("partitionBy"), alias(), text("outputDisplayName"), orderKeys(), readAggregation(args, "$field.arguments"),
                    WindowFrameContract.require(args["frame"], "$field.arguments.frame"))
            }
            Stage.OFFSET_VALUE -> {
                exact("input", "function", "offset", "partitionBy", "orderKeys", "outputAlias", "outputDisplayName")
                Stage.OffsetValue(symbol("input"), symbols("partitionBy"), alias(), text("outputDisplayName"), orderKeys(), text("function"),
                    WindowFrameContract.positiveInteger(args["offset"], "$field.arguments.offset"))
            }
            Stage.SELECT_VERSIONS -> versions()
            Stage.OBSERVE_WINDOW -> observations()
            else -> throw IllegalArgumentException("$field.opType is not executable: $type")
        }
        private fun versions(): ValidatedOperation {
            exact("partitionBy", "orderKeys", "ties", "snapshot")
            val snapshot = args["snapshot"]?.let { raw ->
                val values = objectFields(raw, setOf("timeSymbol", "asOf"), "$field.snapshot")
                Stage.SnapshotSelection(ScopedInputContract.requireInputSymbol(values["timeSymbol"], "$field.snapshot.timeSymbol"), timeBinding(values["asOf"]))
            }
            return Stage.SelectVersions(symbols("partitionBy"), orderKeys(), Stage.VersionTies.valueOf(text("ties")), snapshot)
        }
        private fun observations(): ValidatedOperation {
            exact("inputKeys", "partitionBy", "outputAlias", "outputDisplayName", "orderKeys", "aggregation", "nullPolicy", "frame", "valueContract",
                "aggregationRounding", "domain", "timeSymbol", "observationRange", "displayRange", "calendar")
            val calendar = args["calendar"]?.let { raw ->
                val values = objectFields(raw, setOf("inputId", "dateSymbol"), "$field.calendar")
                Stage.CalendarAxis(Stage.requireTextValue(values["inputId"], "$field.calendar.inputId"),
                    ScopedInputContract.requireInputSymbol(values["dateSymbol"], "$field.calendar.dateSymbol"))
            }
            return Stage.ObserveWindow(symbols("inputKeys"), symbols("partitionBy"), alias(), text("outputDisplayName"), orderKeys(), readAggregation(args, "$field.arguments"),
                WindowFrameContract.require(args["frame"], "$field.frame"), Stage.ObservationDomain.valueOf(text("domain")),
                args["timeSymbol"]?.let { ScopedInputContract.requireInputSymbol(it, "$field.timeSymbol") }, timeBinding(args["observationRange"]), timeBinding(args["displayRange"]), calendar)
        }
        private fun regroup(): ValidatedOperation {
            exact("groupBy", "aggregates", "groupingLevels")
            val keys = symbols("groupBy")
            val raw = args["aggregates"]
            require(raw is List<*> && raw.isNotEmpty()) { "$field.arguments.aggregates must be a non-empty array" }
            val aggregates = raw.mapIndexed { index, item ->
                val location = "$field.arguments.aggregates[$index]"
                readAggregate(item, location)
            }
            val levels = args["groupingLevels"]?.let {
                (if (it is GroupingLevels) it else OlapJsonUtils.mapper().convertValue(it, GroupingLevels::class.java)).bind(keys)
            }
            return Stage.Regroup(Stage.ResultGrouping(keys, aggregates, levels))
        }
        private fun orderKeys(): List<Stage.OrderKey> {
            val raw = args["orderKeys"]
            require(raw is List<*> && raw.isNotEmpty()) { "$field.arguments.orderKeys must be a non-empty array" }
            return raw.mapIndexed { index, item ->
                val location = "$field.arguments.orderKeys[$index]"
                val value = objectFields(item, setOf("symbol", "direction", "nulls"), location)
                Stage.OrderKey(ScopedInputContract.requireInputSymbol(value["symbol"], "$location.symbol"), Stage.requireTextValue(value["direction"], "$location.direction"),
                    Stage.requireTextValue(value["nulls"], "$location.nulls"))
            }
        }
        private fun aliases(name: String): List<String> {
            val values = args[name]
            require(values is List<*>) { "$field.$name requires alias arrays" }
            return values.map { Stage.requireAlias(Stage.requireTextValue(it, "$field.$name.alias")) }
        }
        private fun exact(vararg fields: String) = requireExactFields(args, fields.toSet(), field)
        private fun text(name: String): String = Stage.requireTextValue(args[name], "$field.arguments.$name")
        private fun alias(name: String = "outputAlias"): String = Stage.requireAlias(text(name))
        private fun symbol(name: String): String = ScopedInputContract.requireInputSymbol(args[name], "$field.arguments.$name")
        private fun symbols(name: String): List<String> = ScopedInputContract.requireInputSymbols(args[name], "$field.arguments.$name")
        private fun expression(name: String): ResultExpression = ResultExpressionCodec.read(args[name], "$field.arguments.$name")
        private fun contract(): MetricValueContract? = args["valueContract"]?.let(ResultExpressionCodec::requireValueContract)
        private fun rounding(): MetricValueContract.Rounding = MetricValueContract.Rounding.valueOf(text("aggregationRounding"))
    }
    private fun objectFields(raw: Any?, fields: Set<String>, field: String): Map<*, *> {
        require(raw is Map<*, *> && raw.keys == fields) { "$field requires exactly $fields" }
        return raw
    }
    private fun timeBinding(raw: Any?): Calculation.Binding? = raw?.let { OlapJsonUtils.mapper().convertValue(it, Calculation.Binding::class.java) }
}
