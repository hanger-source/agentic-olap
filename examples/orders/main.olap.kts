import com.olap.prototype.dsl.*
import com.olap.prototype.calculation.*
import com.olap.prototype.event.*
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.governance.contract.*
import com.olap.prototype.member.*
import com.olap.prototype.metric.*
import com.olap.prototype.query.*
import com.olap.prototype.query.PostOperationContract.OrderKey
import com.olap.prototype.query.PostOperationContract.PostFilter
import com.olap.prototype.query.PostOperationContract.Project
import com.olap.prototype.query.PostOperationContract.Regroup
import com.olap.prototype.query.PostOperationContract.ResultAggregate
import com.olap.prototype.query.PostOperationContract.ResultGrouping
import com.olap.prototype.query.PostOperationContract.SelectVersions
import com.olap.prototype.query.PostOperationContract.ValidatedOperation
import com.olap.prototype.query.PostOperationContract.VersionTies
import com.olap.prototype.query.PostOperationContract.JoinInput
import com.olap.prototype.query.PostOperationContract.Derive
import com.olap.prototype.query.PostOperationContract.CalendarAxis
import com.olap.prototype.query.PostOperationContract.ObserveWindow
import com.olap.prototype.query.PostOperationContract.ObservationDomain
import com.olap.prototype.query.PostOperationContract.SnapshotSelection
import com.olap.prototype.query.ResultExpression.*
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.definition.QueryDefinitionCommand
import com.olap.prototype.source.contract.*
import com.olap.prototype.time.TemporalWindow
import com.olap.prototype.warehouse.contract.*
import com.olap.prototype.warehouse.onboarding.*
import com.olap.prototype.warehouse.inspection.*
import com.olap.prototype.engine.executable.ExecutableBusinessResult
import java.math.BigDecimal

// 本例只通过已部署服务的正式能力建模和执行；数据源凭据由服务配置持有。
val CUSTOMER_SLOT = Calculation.DimensionSlot("customer", "example.customer", 1, "customer")
val TIME_SLOT = Calculation.DimensionSlot("time", "example.business_date", 1, "business_time")
val PERIOD = mapOf("window" to Calculation.Parameter(Calculation.ParameterKind.RANGE, "DATE", emptyList(), null, null))


fun ScriptValue.objectValues(): Map<String, ScriptValue> = (this as ScriptValue.ObjectValue).values
fun ScriptValue.arrayValues(): List<ScriptValue> = (this as ScriptValue.ArrayValue).values
fun Map<String, ScriptValue>.text(name: String): String = (getValue(name) as ScriptValue.Text).value
fun Map<String, ScriptValue>.optionalText(name: String): String? = when (val value = getValue(name)) {
    ScriptValue.Null -> null
    is ScriptValue.Text -> value.value
    else -> error("外部配置字段 $name 必须为文本或 NULL")
}
fun Map<String, ScriptValue>.integer(name: String): Int? = when (val value = getValue(name)) {
    ScriptValue.Null -> null
    is ScriptValue.DecimalValue -> value.value.intValueExact()
    else -> error("外部配置字段 $name 必须为整数或 NULL")
}
fun Map<String, ScriptValue>.optionalBoolean(name: String): Boolean? = when (val value = getValue(name)) {
    ScriptValue.Null -> null
    is ScriptValue.BooleanValue -> value.value
    else -> error("外部配置字段 $name 必须为布尔或 NULL")
}
fun scalar(value: ResultValueView): String? = when (val result = (value as ResultValueView.Complete).value) {
    is ExecutableBusinessResult.NullValue -> null
    is ExecutableBusinessResult.ScalarValue -> result.value
    else -> error("此结果位置应为标量")
}
fun expect(page: ResultPage, expected: Map<String, Map<String, String>>) {
    val names = page.columns.map { it.symbol.name }
    val valueNames = expected.values.first().keys
    val keys = names.filter { it !in valueNames }
    val actual = page.rows.associate { row ->
        val values = names.zip(row).associate { (name, value) -> name to scalar(value) }
        val key = if (keys.isEmpty()) "total" else keys.joinToString("/") { requireNotNull(values[it]) }
        key to values
    }
    check(actual.keys == expected.keys) { "实际人口 ${actual.keys} 不同于明确业务人口 ${expected.keys}" }
    expected.forEach { (key, values) -> values.forEach { (name, number) ->
        check(BigDecimal(requireNotNull(actual.getValue(key)[name])).compareTo(BigDecimal(number)) == 0) { "$key/$name 结果不符合业务参照" }
    } }
}
fun period(start: String, end: String) = Calculation.Range(TemporalWindow(TemporalWindow.ValueType.DATE, TemporalWindow.BOUNDARY, start, end))
fun amountContract() = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.UnitIdentity("iso4217", "CNY", 1)),
    MetricValueContract.Exact(), MetricValueContract.Decimal(38, 2, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
fun countContract() = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(),
    MetricValueContract.Integer(64), emptyMap(), MetricValueContract.Overflow.FAIL)


fun averageContract(): MetricValueContract {
    val money = (amountContract().unit as MetricValueContract.Fixed).identity
    return MetricValueContract(MetricValueContract.Fixed(money), MetricValueContract.Approximate("-1000000", "1000000", "0.000000001", "0.000000001"),
        MetricValueContract.BinaryFloat(64), mapOf(
            "multiply" to MetricValueContract.FixedBinary(MetricValueContract.BinaryOperator.MULTIPLY, money, MetricValueContract.ONE, money),
            "divide" to MetricValueContract.FixedBinary(MetricValueContract.BinaryOperator.DIVIDE, money, MetricValueContract.ONE, money)), MetricValueContract.Overflow.FAIL)
}

fun ratioContract() = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE),
    MetricValueContract.Approximate("-1000000", "1000000", "0.000000001", "0.000000001"), MetricValueContract.BinaryFloat(64),
    mapOf("ratio" to MetricValueContract.SameUnitRatio()), MetricValueContract.Overflow.FAIL)

class BusinessExample(val client: OlapClient, val parameters: ScriptArguments, val domain: String) {
    val actor = client.caller.subject
    val governance = client.governance
    val source by lazy { client.sources.list(EmptyRequest).items.single { it.binding.dataSourceId == parameters.text("sourceId") } }
    data class Table(val logical: LogicalTableRecord, val units: Map<String, LogicUnitRecord>)
    fun activate(type: String, id: String, version: Long = 1) {
        governance.activate(GovernanceVersionActivationRequest(GovernanceActivationObjectType.valueOf(type), id, version, actor, "示例明确激活精确版本"))
    }
    fun table(name: String, types: Map<String, LogicType>, key: String, time: String?): Table {
        // 结构供应方式由调用参数明确选择；HTTP 来源不假称拥有数据库 catalog 权限。
        val discovery = if ("declaredStructure" in parameters.values) {
            val declared = parameters["declaredStructure"].objectValues().getValue(name).objectValues()
            val summary = declared.getValue("table").objectValues()
            val reference = summary.getValue("ref").objectValues()
            val declaredColumns = declared.getValue("columns").arrayValues().map { value ->
                val column = value.objectValues()
                WarehouseColumnMetadata(column.text("name"), column.integer("jdbcType"), column.optionalText("typeName"),
                    column.integer("ordinalPosition"), column.optionalBoolean("nullable"), column.integer("precision"), column.integer("scale"),
                    column.optionalBoolean("autoIncrement"), column.optionalBoolean("generated"), column.optionalText("defaultValue"), column.optionalText("remarks"))
            }
            val constraints = declared.getValue("constraints").objectValues()
            check(constraints.getValue("uniqueKeys").arrayValues().isEmpty() && constraints.getValue("importedKeys").arrayValues().isEmpty()
                && constraints.getValue("temporalKeys").arrayValues().isEmpty()) { "本例结构只声明实际主键，不接受未实现的约束解释" }
            val table = SourceStructureRegistrationRequest.Table(
                WarehouseTableSummary(WarehouseTableRef(reference.optionalText("catalog"), reference.optionalText("schema"), reference.text("table")),
                    summary.optionalText("tableType"), summary.optionalText("remarks")), declaredColumns,
                JdbcTableConstraints(constraints.getValue("primaryKeyColumns").arrayValues().map { (it as ScriptValue.Text).value }, emptyList(), emptyList(), emptyList()))
            governance.registerSourceStructure(SourceStructureRegistrationRequest(source.binding.dataSourceId, source.binding.sourceIdentityHash,
                reference.optionalText("catalog"), reference.text("schema"), "example-fixture", "1", listOf(table), actor))
        } else {
            governance.discoverSource(WarehouseDiscoveryCommand(source.binding.dataSourceId, source.binding.sourceIdentityHash,
                null, "main", name, listOf("TABLE"), 10, emptyMap(), emptyMap(), actor))
        }
        check(discovery.status == "SUCCEEDED" && !discovery.truncated)
        val observed = client.semantics.discoveredTables(GovernanceReadRequests.SnapshotPageInput(discovery.snapshotId, 0, 10)).items.single()
        val columns = client.semantics.discoveredColumns(GovernanceReadRequests.DiscoveredTablePageInput(observed.id, 0, 200)).items
        check(columns.map { it.columnName }.toSet() == types.keys)
        val physical = governance.createPhysicalTable(PhysicalTableCreateRequest("$domain.$name", name, name, "保留原始记录、NULL与历史版本", domain,
            actor, "PUBLIC", domain, source.binding.dataSourceId, source.dialectId, observed.catalogName, observed.schemaName,
            requireNotNull(observed.schemaHash), PhysicalTableCreateRequest.SourceConstraintsInput(observed.id, emptyList()), actor,
            columns.map { PhysicalTableCreateRequest.ColumnInput(it.columnName, it.columnName, it.typeName, "PUBLIC", requireNotNull(it.nullable), null,
                com.olap.prototype.contract.JdbcColumnType(it.jdbcType, it.typeName, it.precision, it.scale, it.nullable)) }))
        activate("PHYSICAL_TABLE", physical.physicalTable.id)
        val logical = governance.createLogicalTable(LogicalTableCreateRequest(physical.physicalTable.id, 1, name, name, LogicalTableRole.FACT,
            "来源保持真实记录粒度", actor, actor, physical.physicalColumns.map { column ->
                LogicalTableCreateRequest.LogicUnitInput(column.columnName, column.displayName, LogicUnitSourceKind.COLUMN, null,
                    types.getValue(column.columnName), null, column.columnName == time, column.columnName == key, false, true,
                    types.getValue(column.columnName) == LogicType.NUMBER,
                    listOf(LogicalTableCreateRequest.SourceRefInput(column.id, 1, LogicUnitSourceRefRole.DIRECT_SOURCE)))
            }))
        activate("LOGICAL_TABLE", logical.logicalTable.id)
        return Table(logical.logicalTable, logical.logicUnits.associateBy { it.code })
    }
    fun topology(table: Table): TopologyRecord = governance.createTopology(TopologyCreateRequest(table.logical.code, table.logical.displayName,
        "example.$domain", "$domain 实现空间", table.logical.id, 1, actor, "独立事实", actor)).topology
    fun event(table: Table, topology: TopologyRecord, key: String, entityRole: String, entity: String, time: String?, roles: Map<String, RelationAccessPath> = emptyMap()): EventRecord {
        activate("TOPOLOGY", topology.id)
        val unit = { column: String -> EventCreateRequest.LogicUnitRefInput(table.units.getValue(column).id, 1) }
        val created = governance.createEvent(EventCreateRequest(topology.id, 1, "registered", table.logical.displayName, actor,
            time?.let { table.units.getValue(it).id }, time?.let { 1L }, EventIdentity(listOf(EventIdentity.LogicUnitRef(table.units.getValue(key).id, 1)),
                mapOf(entityRole to listOf(EventIdentity.LogicUnitRef(table.units.getValue(entity).id, 1)))), roles,
            table.units.keys.map(unit), table.units.filterValues { it.aggregatable }.keys.map(unit), "记录键与业务对象身份分别声明", actor)).event
        activate("EVENT", created.id)
        return created
    }
    fun context(): SubjectAreaRecord {
        val line = governance.createBusinessLine(BusinessLineCreateRequest(domain, domain, "完整语义计算示例", actor, actor)).businessLine
        activate("BUSINESS_LINE", line.id)
        return governance.createSubjectArea(SubjectAreaCreateRequest(line.id, 1, domain, domain, actor, "通过正式能力建设并发布", actor)).subjectArea
    }
    fun meaning(text: String) = MetricBusinessMeaning(text, "数量合同确定", "显式观察对象", emptyList())
    fun calculation(code: String, definition: MetricCalculation): MetricRecord = governance.createMetricCalculation(MetricCalculationCreateRequest(code, code, actor,
        definition, "可替换输入并继续组合的共同定义", meaning(code), actor)).metric.also { activate("METRIC", it.id) }
    fun adopt(subject: SubjectAreaRecord, code: String, metric: MetricRecord, bindings: CalculationBindings, arguments: Map<String, Calculation.Value> = emptyMap()): SubjectAreaMetricRefRecord =
        governance.createSubjectAreaMetricRef(SubjectAreaMetricRefCreateRequest(subject.id, 1, code, code, meaning(code), metric.id, metric.currentVersion, bindings, arguments, actor)).subjectAreaMetricRef
    fun publish(subject: SubjectAreaRecord, scenario: ScenarioRecord, metrics: List<SubjectAreaMetricRefRecord>, stages: List<String>): PublishedQueryEntry {
        metrics.forEachIndexed { index, metric -> governance.createScenarioMetricRef(ScenarioMetricRefCreateRequest(scenario.id, 1, metric.id, 1, true, index + 1)) }
        stages.forEachIndexed { index, type -> governance.createScenarioPipelineRule(ScenarioPipelineRuleCreateRequest(scenario.id, 1, type, index + 1)) }
        activate("SUBJECT_AREA", subject.id)
        governance.buildRelease(SubjectAreaReleaseBuildRequest("$domain-release", "1", domain, subject.id, 1, actor))
        return client.semantics.publication(QueryCapabilityRequest(scenario.id, 1, "$domain-release", "1"))
    }
    data class Saved(val query: QueryReference, val result: QueryResultReference, val page: ResultPage)
    fun execute(entry: PublishedQueryEntry, name: String, computation: QueryComputation.Scope): Saved {
        val intent = QueryIntent(PublishedQueryEntry.key(entry.capability), computation)
        val saved = client.queries.save(QueryDefinitionCommand(null, null, requireNotNull(entry.capability.capabilityHash), name, intent, null))
        val reference = saved.reference
        val receipt = client.queries.execute(QueryExecutionRequest(QuerySelection.Saved(reference), true, name, null))
        check(receipt.status == QueryExecutionStatus.SUCCEEDED && receipt.saved) { receipt.error.toString() }
        val restored = client.results.read(ResultReadRequest(requireNotNull(receipt.resultReference), 0, 200, null))
        check(restored.totalRows.toLong() == receipt.rows)
        return Saved(reference, requireNotNull(receipt.resultReference), restored)
    }
    fun sourceCall(source: MemberSourceRecord, vararg outputs: String) = CalculationBindings.SourceCall(MemberRef(source.id, source.subjectAreaVersion),
        emptyMap(), outputs.associateWith { it }, emptyMap(), emptyMap())
    fun member(subject: SubjectAreaRecord, code: String, definition: MemberSourceDefinition, bindings: CalculationBindings = CalculationBindings.empty(),
        fixed: Map<String, Calculation.Value> = emptyMap()): MemberSourceRecord = governance.createMemberSource(MemberSourceCreateRequest(subject.id, 1, code, code, definition, bindings, fixed, actor))
    fun binding(dimension: DimensionRecord, event: EventRecord, unit: LogicUnitRecord, path: RelationAccessPath = RelationAccessPath(emptyList())): DimensionEventBindingRecord =
        governance.createDimensionEventBinding(DimensionEventBindingCreateRequest(dimension.id, 1, event.id, 1, unit.id, 1, null, path, emptyMap(), actor)).binding.also {
            activate("DIMENSION_EVENT_BINDING", it.id)
        }
    data class Axis(val analysis: AnalysisDimensionRecord, val bound: CalculationBindings.Dimension)
    fun axis(subject: SubjectAreaRecord, code: String, role: String, bindings: List<DimensionEventBindingRecord>): Axis {
        val analysis = governance.createAnalysisDimension(AnalysisDimensionCreateRequest(subject.id, 1, code, code, "明确共同身份及观察粒度", code, code, actor)).analysisDimension
        val alignment = if (bindings.size > 1) governance.createAnalysisDimensionValueAlignment(AnalysisDimensionValueAlignmentCreateRequest(
            analysis.id, 1, code, "PROVEN_SAME_KEY", "{\"basis\":\"示例来源共享同一系统、同一完整业务身份\"}", actor)) else null
        val mappings = bindings.map { binding -> governance.createAnalysisDimensionEventMapping(AnalysisDimensionEventMappingCreateRequest(analysis.id, 1, code,
            binding.id, 1, if (alignment == null) "SINGLE_EVENT_ONLY" else "PROVEN_SAME_KEY", alignment?.id)).mapping }
        return Axis(analysis, CalculationBindings.Dimension(ScopedInputContract.DimensionRef(analysis.id, "1", code), role, mappings.map { it.id }))
    }
    fun grant(scenario: ScenarioRecord, axis: Axis) {
        governance.createScenarioAnalysisDimensionRef(ScenarioAnalysisDimensionRefCreateRequest(scenario.id, 1, axis.analysis.id, 1, axis.bound.reference.levelCode, true, 1))
    }
    fun call(adoption: SubjectAreaMetricRefRecord, alias: String = adoption.code, arguments: Map<String, Calculation.Value> = emptyMap()) =
        QueryComputation.Invocation(alias, adoption.instance, alias, arguments, emptyMap(), emptyMap(), DimensionCondition.Unrestricted())
    fun scope(calls: List<QueryComputation.Invocation>, grouping: List<ScopedInputContract.DimensionRef>, population: List<String> = calls.map { it.callId }) =
        QueryComputation.Scope(QueryComputation.Measures(calls, grouping, null, emptyMap(), DimensionCondition.Unrestricted(), DimensionCondition.Unrestricted(), emptyList(),
            RowPopulationContract.calls(population), null), emptyMap(), emptyList())
}

fun resume(client: OlapClient, parameters: ScriptArguments, verify: (ResultPage) -> Unit): ScriptValue {
    val queryValues = parameters["query"].objectValues()
    val resultValues = parameters["result"].objectValues()
    val query = QueryReference(queryValues.text("definitionId"), queryValues.text("definitionVersion").toLong(), queryValues.text("sha256"))
    val result = QueryResultReference(resultValues.text("runId"), resultValues.text("sha256"))
    verify(client.results.read(ResultReadRequest(result, 0, 200, null)))
    if (parameters.text("phase") == "reopen") {
        check(client.queries.read(QueryVersionRequest(query)).reference == query)
        val receipt = client.queries.execute(QueryExecutionRequest(QuerySelection.Saved(query), true, "重启后复用同一查询", null))
        check(receipt.status == QueryExecutionStatus.SUCCEEDED && receipt.saved) { receipt.error.toString() }
        verify(client.results.read(ResultReadRequest(requireNotNull(receipt.resultReference), 0, 200, null)))
    } else check(parameters.text("phase") == "read-result")
    return ScriptValue.ObjectValue(mapOf("query" to ScriptValue.QueryReferenceValue(query), "result" to ScriptValue.ResultReferenceValue(result)))
}
fun references(saved: BusinessExample.Saved): ScriptValue = ScriptValue.ObjectValue(mapOf(
    "query" to ScriptValue.QueryReferenceValue(saved.query), "result" to ScriptValue.ResultReferenceValue(saved.result)))

fun scope(root: Calculation.Root, vararg stages: ValidatedOperation) = Calculation.Scope(root, emptyMap(), stages.toList())

fun measure(slot: String, alias: String, grouped: Boolean, period: Boolean) = scope(Calculation.Measures(
    listOf(Calculation.Call(slot, Calculation.Measurement(slot), alias, emptyMap(), emptyMap(), emptyMap(), emptyMap(), if (period) mapOf("window" to Calculation.Argument("window")) else emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()))),
    if (grouped) listOf("customer") else emptyList(), emptyList(), null, null, emptyMap(), Calculation.Population(listOf(slot)), null))

fun slot(name: String, contract: MetricValueContract, grouped: Boolean, period: Boolean) = Calculation.MeasurementSlot(
    name, if (period) PERIOD else emptyMap(), if (grouped) listOf(CUSTOMER_SLOT) else emptyList(), emptyList(), contract)

fun aggregateDefinition(fact: MetricRef, contract: MetricValueContract) = MetricCalculation(
    Calculation(listOf(CUSTOMER_SLOT, TIME_SLOT), emptyList(), emptyList(), emptyList(), PERIOD, scope(Calculation.Measures(
        listOf(Calculation.Call("fact", Calculation.Fixed(fact), "value", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()))),
        listOf("customer"), emptyList(), "time", Calculation.Argument("window"), emptyMap(), Calculation.Population(listOf("fact")), null))),
    "value", listOf("customer"), contract)

fun aligned(input: Calculation.Scope, symbol: String, alias: String, grouped: Boolean) = Calculation.AlignedInput(
    input, if (grouped) mapOf("customer" to "customer") else emptyMap(),
    listOf(Calculation.AlignedValue(symbol, alias, Calculation.NoMatch.PRESERVE_NULL)))

fun align(inputs: Map<String, Calculation.AlignedInput>, grouped: Boolean, base: String) = Calculation.AlignMeasurements(
    inputs, if (grouped) listOf("customer") else emptyList(), Calculation.AlignmentPopulation(Calculation.PopulationKind.BASE, listOf(base)))

fun symbol(name: String): ResultExpression = ResultSymbol(name)
fun operation(operator: ResultOperator, left: ResultExpression, right: ResultExpression): ResultExpression = ResultOperation(operator, listOf(left, right))
fun numeric(alias: String, expression: ResultExpression, contract: MetricValueContract, equation: String?) =
    Derive(ResultNumeric(expression, contract, equation), alias, alias)

fun reduction(input: String, aggregation: ResultAggregation, alias: String) =
    ResultAggregate(input, aggregation, alias, alias)

fun eligibleSlot(name: String) = Calculation.MemberSlot(name, "客户", "customer", MemberShape.UNIQUE_MEMBERS,
    mapOf("customer" to Calculation.MemberDimension("customer", null)), null)

fun people(slot: String) = scope(Calculation.Members(slot, slot, listOf(Calculation.MemberProjection("customer", "customer")),
    null, emptyMap(), MemberCondition.Unrestricted()))

fun floating(value: ResultExpression): ResultExpression = ResultCast(value, SemanticExpression.ValueType.DOUBLE, null, null, MetricValueContract.Rounding.NONE)

fun netAt(window: Calculation.Range) = scope(Calculation.Measures(
    listOf(Calculation.Call("net", Calculation.Measurement("net"), "measured", emptyMap(), emptyMap(), emptyMap(), emptyMap(), mapOf("window" to window), Calculation.Condition(DimensionCondition.Unrestricted()))),
    listOf("customer"), emptyList(), null, null, emptyMap(), Calculation.Population(listOf("net")), null))

fun netDefinition(fees: Boolean): MetricCalculation {
    val inputs = linkedMapOf(
        "gross" to aligned(measure("gross", "value", true, true), "value", "gross", true),
        "refund" to aligned(measure("refund", "value", true, true), "value", "refunded", true),
    )
    val slots = mutableListOf(slot("gross", amountContract(), true, true), slot("refund", amountContract(), true, true))
    var expression = operation(ResultOperator.SUBTRACT, symbol("gross"), symbol("refunded"))
    if (fees) {
        inputs["fee"] = aligned(measure("fee", "value", true, true), "value", "fee", true)
        slots += slot("fee", amountContract(), true, true)
        expression = operation(ResultOperator.SUBTRACT, expression, symbol("fee"))
    }
    return MetricCalculation(Calculation(listOf(CUSTOMER_SLOT), emptyList(), slots, emptyList(), PERIOD, scope(align(inputs, true, "gross"), numeric("value", expression, amountContract(), null), Project(listOf("customer", "value")))),
        "value", listOf("customer"), amountContract())
}

fun totalDefinition(measurement: String, contract: MetricValueContract) = MetricCalculation(
    Calculation(listOf(CUSTOMER_SLOT), emptyList(), listOf(slot(measurement, contract, true, true)), emptyList(), PERIOD, scope(Calculation.ObservationStatistics(measure(measurement, "measured", true, true), Calculation.ObjectsBy(listOf("customer")),
        emptyList(), listOf(Calculation.Reduction(reduction("measured", ResultAggregation.Sum(contract, ResultAggregation.NullPolicy.IGNORE), "value")))))), "value", emptyList(), contract)

fun qualification(): Calculation {
    val inputs = linkedMapOf(
        "net" to aligned(measure("net", "value", true, true), "value", "net", true),
        "orders" to aligned(measure("orders", "value", true, true), "value", "orders", true),
    )
    val condition = operation(ResultOperator.AND,
        operation(ResultOperator.GTE, symbol("orders"), ResultQuantityLiteral(BigDecimal("2"), countContract())),
        operation(ResultOperator.GTE, symbol("net"), ResultQuantityLiteral(BigDecimal("100"), amountContract())))
    return Calculation(listOf(CUSTOMER_SLOT), emptyList(), listOf(slot("net", amountContract(), true, true), slot("orders", countContract(), true, true)), emptyList(), PERIOD, scope(align(inputs, true, "net"), PostFilter(condition), Project(listOf("customer"))))
}

fun qualifiedTotal(measured: Calculation.Scope): Calculation.Scope {
    val qualified = Calculation.Scope(Calculation.Input("net_rows"), linkedMapOf(
        "net_rows" to Calculation.InputScope("ROOT", measured), "eligible" to Calculation.InputScope("RELATION", people("eligible"))),
        listOf(JoinInput("eligible", "chosen", "SEMI", operation(ResultOperator.EQ, symbol("customer"), symbol(ScopedInputContract.metricSymbol("chosen", "customer"))))))
    return scope(Calculation.ObservationStatistics(qualified, Calculation.ObjectsBy(listOf("customer")), emptyList(),
        listOf(Calculation.Reduction(reduction("measured", ResultAggregation.Sum(amountContract(), ResultAggregation.NullPolicy.IGNORE), "value")))))
}

fun qualifiedDefinition() = MetricCalculation(Calculation(listOf(CUSTOMER_SLOT), listOf(eligibleSlot("eligible")), listOf(slot("net", amountContract(), true, true)), emptyList(), PERIOD, qualifiedTotal(measure("net", "measured", true, true))), "value", emptyList(), amountContract())

fun growthDefinition(): MetricCalculation {
    val inputs = linkedMapOf(
        "current" to aligned(qualifiedTotal(netAt(period("2026-01-01", "2026-02-01"))), "value", "current", false),
        "prior" to aligned(qualifiedTotal(netAt(period("2025-12-01", "2026-01-01"))), "value", "prior", false),
    )
    val ratio = operation(ResultOperator.DIVIDE, floating(operation(ResultOperator.SUBTRACT, symbol("current"), symbol("prior"))), floating(symbol("prior")))
    return MetricCalculation(Calculation(listOf(CUSTOMER_SLOT), listOf(eligibleSlot("eligible_source"), eligibleSlot("eligible")), listOf(slot("net", amountContract(), true, true)), emptyList(), emptyMap(), Calculation.Scope(align(inputs, false, "current"),
        mapOf("fixed_people" to Calculation.InputScope("ROOT", people("eligible_source"))),
        listOf(numeric("value", ratio, ratioContract(), "ratio"), Project(listOf("value"))))), "value", emptyList(), ratioContract())
}

fun statisticsDefinition(outputName: String): Calculation {
    val inputs = linkedMapOf(
        "gross" to aligned(measure("gross", "value", true, true), "value", "gross", true),
        "orders" to aligned(measure("orders", "value", true, true), "value", "orders", true),
    )
    val observations = scope(align(inputs, true, "gross"), numeric("price", operation(ResultOperator.DIVIDE,
        floating(symbol("gross")), floating(symbol("orders"))), averageContract(), "divide"))
    val stats = Calculation.ObservationStatistics(observations, Calculation.ObjectsBy(listOf("customer")), emptyList(), listOf(
        Calculation.Reduction(reduction("gross", ResultAggregation.Sum(amountContract(), ResultAggregation.NullPolicy.IGNORE), "gross_total")),
        Calculation.Reduction(reduction("orders", ResultAggregation.Sum(countContract(), ResultAggregation.NullPolicy.IGNORE), "order_total")),
        Calculation.Reduction(reduction("gross", ResultAggregation.Average(averageContract(), ResultAggregation.NullPolicy.IGNORE, MetricValueContract.Rounding.NONE), "customer_mean")),
        Calculation.Reduction(reduction("price", ResultAggregation.Average(averageContract(), ResultAggregation.NullPolicy.IGNORE, MetricValueContract.Rounding.NONE), "unweighted")),
        Calculation.WeightedMean("price", "orders", "weighted", "按真实订单数加权客户均价", averageContract(), countContract(),
            averageContract(), "multiply", "divide", "IGNORE_PAIR"),
    ))
    return Calculation(listOf(CUSTOMER_SLOT), emptyList(), listOf(slot("gross", amountContract(), true, true), slot("orders", countContract(), true, true)), emptyList(), PERIOD, scope(stats, numeric("order_mean", operation(ResultOperator.DIVIDE, floating(symbol("gross_total")), floating(symbol("order_total"))),
        averageContract(), "divide"), Project(listOf(outputName))))
}

fun verifyOrders(page: ResultPage) = expect(page, mapOf("total" to mapOf("value" to "175")))

fun buildOrders(): BusinessExample.Saved {
    val example = BusinessExample(olap, parameters, "orders")
    val governance = example.governance
    val subject = example.context()
    val names = listOf("orders", "payments", "refunds", "fees")
    val dates = mapOf("orders" to "ordered_date", "payments" to "paid_date", "refunds" to "refunded_date", "fees" to "charged_date")
    val keys = mapOf("orders" to "order_id", "payments" to "payment_id", "refunds" to "refund_id", "fees" to "fee_id")
    val tables = names.associateWith { name ->
        val types = linkedMapOf(keys.getValue(name) to LogicType.STRING, dates.getValue(name) to LogicType.DATE)
        if (name == "orders") types["customer"] = LogicType.STRING else { types["order_id"] = LogicType.STRING; types["amount"] = LogicType.NUMBER }
        example.table(name, types, keys.getValue(name), dates.getValue(name))
    }
    val original = tables.getValue("orders")
    val paths = linkedMapOf<String, RelationAccessPath>()
    val events = names.associateWith { name ->
        val table = tables.getValue(name)
        val topology = example.topology(table)
        val path = if (name == "orders") RelationAccessPath(emptyList()) else {
            governance.createTopologyTableRef(TopologyLogicalTableRefCreateRequest(topology.id, 1, original.logical.id, 1, TopologyNodeRole.BRIDGE_NODE))
            val join = governance.createJoinPath(JoinPathCreateRequest(topology.id, 1, "original_order", table.logical.id, 1, original.logical.id, 1,
                "INNER", "MANY_TO_ONE", null, "SQL_EQUALS_NULL_NEVER_MATCHES", example.actor, listOf(JoinPathCreateRequest.KeyRefInput(
                    table.units.getValue("order_id").id, 1, original.units.getValue("order_id").id, 1)))).joinPath
            RelationAccessPath(listOf(RelationAccessPath.JoinRef(join.id, 1)))
        }
        paths[name] = path
        example.activate("TOPOLOGY", topology.id)
        fun ref(unit: LogicUnitRecord) = EventCreateRequest.LogicUnitRefInput(unit.id, 1)
        val event = governance.createEvent(EventCreateRequest(topology.id, 1, "registered", name, example.actor, table.units.getValue(dates.getValue(name)).id, 1,
            EventIdentity(listOf(EventIdentity.LogicUnitRef(table.units.getValue(keys.getValue(name)).id, 1)), mapOf(
                "customer" to listOf(EventIdentity.LogicUnitRef(original.units.getValue("customer").id, 1)),
                "order" to listOf(EventIdentity.LogicUnitRef(original.units.getValue("order_id").id, 1)))),
            mapOf("customer" to path, "order" to path), listOf(ref(original.units.getValue("customer")), ref(original.units.getValue("ordered_date"))),
            if (name == "orders") listOf(ref(table.units.getValue("order_id"))) else listOf(ref(table.units.getValue("amount"))),
            "事实记录键独立，客户与原单日期经真实多对一关系访问", example.actor)).event
        example.activate("EVENT", event.id)
        event
    }
    val customerDimension = governance.createDimension(DimensionCreateRequest("example.customer", "订单共同语义", "客户", example.actor, "保留真实客户身份", example.actor)).dimension
    val timeDimension = governance.createDimension(DimensionCreateRequest("example.business_date", "订单共同语义", "公历业务日期", example.actor, "日期角色由采用明确选择", example.actor)).dimension
    listOf(customerDimension, timeDimension).forEach { example.activate("DIMENSION", it.id) }
    val customer = example.axis(subject, "customer", "customer", names.map { example.binding(customerDimension, events.getValue(it), original.units.getValue("customer"), paths.getValue(it)) })
    val originalTimeBindings = names.associateWith { name ->
        example.binding(timeDimension, events.getValue(name), original.units.getValue("ordered_date"), paths.getValue(name))
    }
    val originalTime = example.axis(subject, "original", "business_time", originalTimeBindings.values.toList())
    val occurrenceTimeBindings = names.associateWith { name ->
        if (name == "orders") originalTimeBindings.getValue(name)
        else example.binding(timeDimension, events.getValue(name), tables.getValue(name).units.getValue(dates.getValue(name)))
    }
    val occurrenceTime = example.axis(subject, "occurrence", "business_time", occurrenceTimeBindings.values.toList())
    val factTables = mapOf("gross" to "payments", "refund" to "refunds", "orders" to "orders", "fee" to "fees")
    val aggregates = factTables.mapValues { (code, tableName) ->
        val unit = tables.getValue(tableName).units.getValue(if (code == "orders") "order_id" else "amount")
        val contract = if (code == "orders") countContract() else amountContract()
        val fact = governance.createMetric(MetricCreateRequest("${code}_fact", code, example.actor,
            MetricDefinition.FactMeasure(EventRef(events.getValue(tableName).id, 1), SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, unit.id, 1),
                if (code == "orders") MetricAggregation.COUNT else MetricAggregation.SUM, false, null, emptyMap(), null, contract, MetricValueContract.Rounding.NONE),
            "分别完成各事实测量，不把支付与退款明细相乘", example.meaning(code), example.actor)).metric
        example.activate("METRIC", fact.id)
        example.calculation("${code}_by_customer", aggregateDefinition(MetricRef(fact.id, "1"), contract))
    }
    val children = linkedMapOf<String, SubjectAreaMetricRefRecord>()
    aggregates.forEach { (code, definition) ->
        children[code] = example.adopt(subject, "${code}_original", definition, CalculationBindings(mapOf("customer" to customer.bound, "time" to originalTime.bound), emptyMap(), emptyMap(), emptyMap(), emptyMap()))
        if (code in listOf("gross", "refund")) children["${code}_occurrence"] = example.adopt(subject, "${code}_occurrence", definition,
            CalculationBindings(mapOf("customer" to customer.bound, "time" to occurrenceTime.bound), emptyMap(), emptyMap(), emptyMap(), emptyMap()))
    }
    val netDefinition = example.calculation("net", netDefinition(false))
    fun net(code: String, suffix: String) = example.adopt(subject, code, netDefinition, CalculationBindings(mapOf("customer" to customer.bound), emptyMap(), emptyMap(),
        mapOf("gross" to children.getValue("gross$suffix").instance, "refund" to children.getValue("refund$suffix").instance), emptyMap()))
    val net = net("original_net", "")
    val occurrenceNet = net("occurrence_net", "_occurrence")
    val totalDefinition = example.calculation("net_total", totalDefinition("net", amountContract()))
    fun total(code: String, input: SubjectAreaMetricRefRecord) = example.adopt(subject, code, totalDefinition,
        CalculationBindings(mapOf("customer" to customer.bound), emptyMap(), emptyMap(), mapOf("net" to input.instance), emptyMap()))
    val metrics = linkedMapOf("original_net" to net, "original_total" to total("original_total", net), "occurrence_total" to total("occurrence_total", occurrenceNet),
        "gross_total" to total("gross_total", children.getValue("gross")))
    val qualification = qualification()
    val qualifiedDefinition = MemberSourceDefinition.Computed(qualification, MemberResult("客户", "customer", MemberShape.UNIQUE_MEMBERS, listOf("customer"),
        mapOf("customer" to MemberSourceDefinition.ComputedDimensionOutput("客户", "customer", ScalarValueType.TEXT, "customer", "customer", null)),
        emptyMap(), null, emptyMap(), "明确期间内订单至少2单且净额至少100元"))
    val shared = governance.createMemberDefinition(MemberDefinitionCreateRequest("qualified_customers", "合格客户", qualifiedDefinition, "资格成为可继续引用的定义", example.actor, example.actor))
    example.activate("MEMBER_DEFINITION", shared.id)
    val qualified = governance.adoptMemberDefinition(MemberDefinitionAdoptRequest(subject.id, 1, "qualified", "本期合格客户", shared.reference,
        CalculationBindings(mapOf("customer" to customer.bound), emptyMap(), emptyMap(), mapOf("net" to net.instance, "orders" to children.getValue("orders").instance), emptyMap()), emptyMap(), example.actor))
    fun eligible(binding: Calculation.Binding) = CalculationBindings.SourceCall(MemberRef(qualified.id, 1), mapOf("window" to binding), mapOf("customer" to "customer"), emptyMap(), emptyMap())
    val qualifiedTotalDefinition = example.calculation("qualified_total", qualifiedDefinition())
    metrics["fixed_cohort"] = example.adopt(subject, "fixed_cohort", qualifiedTotalDefinition, CalculationBindings(mapOf("customer" to customer.bound),
        mapOf("eligible" to eligible(period("2026-01-01", "2026-02-01"))), emptyMap(), mapOf("net" to net.instance), emptyMap()))
    metrics["separate_cohort"] = example.adopt(subject, "separate_cohort", qualifiedTotalDefinition, CalculationBindings(mapOf("customer" to customer.bound),
        mapOf("eligible" to eligible(Calculation.Argument("window"))), emptyMap(), mapOf("net" to net.instance), emptyMap()))
    val growth = growthDefinition()
    val growthBindings = CalculationBindings(mapOf("customer" to customer.bound), mapOf("eligible_source" to eligible(period("2026-01-01", "2026-02-01")),
        "eligible" to CalculationBindings.LocalRef("fixed_people", mapOf("customer" to "customer"))), emptyMap(), mapOf("net" to net.instance), emptyMap())
    metrics["fixed_growth"] = example.adopt(subject, "fixed_growth", example.calculation("cohort_growth", growth), growthBindings)
    listOf("order_mean", "customer_mean", "unweighted", "weighted").forEach { code -> metrics[code] = example.adopt(subject, code,
        example.calculation(code, MetricCalculation(statisticsDefinition(code), code, emptyList(), averageContract())), CalculationBindings(mapOf("customer" to customer.bound),
            emptyMap(), emptyMap(), mapOf("gross" to children.getValue("gross").instance, "orders" to children.getValue("orders").instance), emptyMap())) }
    val scenario = governance.createScenario(ScenarioCreateRequest(subject.id, 1, "business", "订单经营", example.actor, "独立事实、业务资格和共同计算", example.actor)).scenario
    listOf(customer, originalTime, occurrenceTime).forEach { example.grant(scenario, it) }
    governance.createScenarioMemberSourceRef(ScenarioMemberSourceRefCreateRequest(scenario.id, 1, qualified.id, 1,
        MemberQueryPermission(listOf("customer"), listOf("customer"), emptyMap(), false, emptyList())))
    val entry = example.publish(subject, scenario, metrics.values.toList(), listOf("JOIN_INPUT", "DERIVE", "PROJECT", "REGROUP"))
    val current = period("2026-01-01", "2026-02-01")
    val prior = period("2025-12-01", "2026-01-01")
    val checks = listOf(Triple("occurrence_total", current, "210"), Triple("gross_total", current, "240"), Triple("fixed_cohort", current, "115"),
        Triple("fixed_cohort", prior, "80"), Triple("separate_cohort", prior, "150"), Triple("original_total", prior, "230"),
        Triple("order_mean", current, "80"), Triple("customer_mean", current, "120"), Triple("unweighted", current, "82.5"), Triple("weighted", current, "80"))
    checks.forEachIndexed { index, (code, window, expected) ->
        val saved = example.execute(entry, "orders_${code}_$index", example.scope(listOf(example.call(metrics.getValue(code), "value", mapOf("window" to window))), emptyList()))
        expect(saved.page, mapOf("total" to mapOf("value" to expected)))
    }
    val savedGrowth = example.execute(entry, "fixed_growth", example.scope(listOf(example.call(metrics.getValue("fixed_growth"), "value")), emptyList()))
    expect(savedGrowth.page, mapOf("total" to mapOf("value" to "0.4375")))
    val inline = QueryComputation.Scope(QueryComputation.Compute(growth.calculation, growthBindings, emptyMap(), emptyMap(), emptyMap()), emptyMap(), emptyList())
    val inlineSaved = example.execute(entry, "inline_fixed_growth", inline)
    expect(inlineSaved.page, mapOf("total" to mapOf("value" to "0.4375")))
    return example.execute(entry, "original_net", example.scope(listOf(example.call(metrics.getValue("original_total"), "value", mapOf("window" to current))), emptyList())).also { verifyOrders(it.page) }
}

olap.output(when (parameters.text("phase")) {
    "build" -> references(buildOrders())
    "refresh-source" -> {
        val queryValues = parameters["query"].objectValues()
        val resultValues = parameters["result"].objectValues()
        val query = QueryReference(queryValues.text("definitionId"), queryValues.text("definitionVersion").toLong(), queryValues.text("sha256"))
        val result = QueryResultReference(resultValues.text("runId"), resultValues.text("sha256"))
        verifyOrders(olap.results.read(ResultReadRequest(result, 0, 200, null)))
        val fresh = olap.queries.execute(QueryExecutionRequest(QuerySelection.Saved(query), true, "来源新增10元支付后复用精确定义", null))
        check(fresh.status == QueryExecutionStatus.SUCCEEDED && fresh.saved) { fresh.error.toString() }
        expect(olap.results.read(ResultReadRequest(requireNotNull(fresh.resultReference), 0, 200, null)), mapOf("total" to mapOf("value" to "185")))
        ScriptValue.ObjectValue(mapOf("query" to ScriptValue.QueryReferenceValue(query), "result" to ScriptValue.ResultReferenceValue(result),
            "freshResult" to ScriptValue.ResultReferenceValue(requireNotNull(fresh.resultReference))))
    }
    else -> resume(olap, parameters, ::verifyOrders)
})
