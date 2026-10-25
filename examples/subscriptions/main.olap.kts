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
val YUAN = MetricValueContract.UnitIdentity("iso4217", "CNY", 1)
val CENTS = MetricValueContract.UnitIdentity("example-subscriptions", "CNY-cent", 1)


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
fun amount(unit: MetricValueContract.UnitIdentity) = MetricValueContract(MetricValueContract.Fixed(unit), MetricValueContract.Exact(),
    MetricValueContract.Decimal(18, 2, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
fun balance() = MetricValueContract(MetricValueContract.Fixed(YUAN), MetricValueContract.Exact(),
    MetricValueContract.Decimal(38, 4, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
fun count() = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(),
    MetricValueContract.Integer(64), emptyMap(), MetricValueContract.Overflow.FAIL)
fun average(unit: MetricValueContract.UnitIdentity) = MetricValueContract(MetricValueContract.Fixed(unit),
    MetricValueContract.Approximate("-1000000", "1000000", "0.000000001", "0.000000001"), MetricValueContract.BinaryFloat(64),
    mapOf("divide" to MetricValueContract.PreserveUnit(MetricValueContract.BinaryOperator.DIVIDE, MetricValueContract.Operand.LEFT)), MetricValueContract.Overflow.FAIL)

fun slot(name: String, source: MemberResult): Calculation.MemberSlot {
    val outputs = source.outputs.mapValues { (key, output) ->
        require(output is MemberSourceDefinition.ScalarOutput) { "本例作者声明的成员槽位只使用标量输出" }
        when (output) {
            is MemberSourceDefinition.DimensionOutput, is MemberSourceDefinition.ComputedDimensionOutput -> Calculation.MemberDimension(key, output.valueContract)
            else -> Calculation.MemberProperty(output.role, output.valueType, output.valueContract)
        }
    }
    return Calculation.MemberSlot(name, source.entity, source.role, source.shape, outputs, null)
}

fun members(name: String, source: MemberResult) = Calculation.Members(name, name,
    source.outputs.keys.sorted().map { Calculation.MemberProjection(it, it) }, null, emptyMap(), MemberCondition.Unrestricted())
fun asOfParameters() = mapOf("as_of" to Calculation.Parameter(Calculation.ParameterKind.SCALAR, "DATE", emptyList(), null, null))
fun symbol(value: String) = ResultSymbol(value)
fun literalDate(value: String) = ResultTypedLiteral(ScalarValueType.DATE, value)
fun op(operator: ResultOperator, vararg values: ResultExpression) = ResultOperation(operator, values.toList())

fun computed(body: Calculation, axes: Map<String, String>, values: Map<String, MetricValueContract>, identity: List<String>, description: String): MemberSourceDefinition.Computed {
    val outputs = linkedMapOf<String, MemberSourceDefinition.Output>()
    axes.forEach { (name, symbol) -> outputs[name] = MemberSourceDefinition.ComputedDimensionOutput(name, name,
        if (name == "day") ScalarValueType.DATE else ScalarValueType.TEXT, name, symbol, null) }
    values.forEach { (name, contract) -> outputs[name] = MemberSourceDefinition.ComputedPropertyOutput(name, "amount", ScalarValueType.NUMBER, name, contract) }
    return MemberSourceDefinition.Computed(body, MemberResult("subscription", "subscription", MemberShape.RECORDS, identity,
        outputs, emptyMap(), "day", emptyMap(), description))
}

fun row(name: String, axes: List<String>, properties: Map<String, ScalarValueType>, values: Map<String, MetricValueContract>, identity: List<String>): MemberResult {
    val outputs = linkedMapOf<String, MemberSourceDefinition.Output>()
    axes.forEach { axis -> outputs[axis] = MemberSourceDefinition.ComputedDimensionOutput(axis, axis,
        if (axis == "day") ScalarValueType.DATE else ScalarValueType.TEXT, axis, axis, null) }
    properties.forEach { (property, type) -> outputs[property] = MemberSourceDefinition.ComputedPropertyOutput(property, property, type, property, values[property]) }
    return MemberResult(name, name, MemberShape.RECORDS, identity, outputs, emptyMap(), if ("day" in axes) "day" else null, emptyMap(),
        "作者声明的记录合同；实际来源由远程MCP采用绑定")
}


fun sevenDays() = WindowFrameContract.Frame("RANGE", WindowFrameContract.Bound("PRECEDING", 6, "DAY"),
    WindowFrameContract.Bound("CURRENT_ROW", null, null))
fun dateRange(start: String, end: String) = Calculation.Range(TemporalWindow(TemporalWindow.ValueType.DATE, TemporalWindow.BOUNDARY, start, end))

fun calendarInputs(usage: MemberResult, calendar: MemberResult) = linkedMapOf(
    "calendar" to Calculation.InputScope("ROOT", Calculation.Scope(members("calendar", calendar), emptyMap(), emptyList())),
    "usage" to Calculation.InputScope("RELATION", Calculation.Scope(members("usage", usage), emptyMap(), emptyList())),
)

class BusinessExample(val client: OlapClient, val parameters: ScriptArguments, val domain: String) {
    val actor = client.caller.subject
    val governance = client.governance
    val sourceAdoptions = mutableListOf<SourceAdoptionRef>()
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
            actor, "PUBLIC", domain, source.binding.dataSourceId, observed.catalogName, observed.schemaName,
            PhysicalTableCreateRequest.SourceAdoptionInput(observed.id, emptyList()), actor,
            columns.map { PhysicalTableCreateRequest.ColumnInput(it.columnName, it.columnName, it.typeName, "PUBLIC", requireNotNull(it.nullable), null,
                com.olap.prototype.contract.JdbcColumnType(it.jdbcType, it.typeName, it.precision, it.scale, it.nullable)) }))
        sourceAdoptions.addAll(physical.sourceAdoptions.references)
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
        stages.forEachIndexed { index, type -> governance.createScenarioPipelineRule(ScenarioPipelineRuleCreateRequest(scenario.id, 1, type, index + 1, StageAuthorization.current(type))) }
        activate("SUBJECT_AREA", subject.id)
        governance.buildRelease(SubjectAreaReleaseBuildRequest("$domain-release", "1", domain, subject.id, 1, actor, sourceAdoptions))
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

fun migratedStock(axes: Map<String, Calculation.DimensionSlot>, legacy: MemberResult, current: MemberResult): MemberSourceDefinition.Computed {
    val conversion = MetricValueContract(MetricValueContract.Fixed(YUAN), MetricValueContract.Exact(), balance().result,
        mapOf("cents_to_yuan" to MetricValueContract.Scale(MetricValueContract.BinaryOperator.MULTIPLY, CENTS, YUAN, "0.01")),
        MetricValueContract.Overflow.FAIL)
    val factor = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(),
        MetricValueContract.Decimal(3, 2, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
    val old = Calculation.Scope(members("legacy", legacy), emptyMap(), listOf(
        PostFilter(op(ResultOperator.LT, symbol("day"), literalDate("2026-04-01"))),
        Derive(ResultNumeric(op(ResultOperator.MULTIPLY, symbol("amount"), ResultQuantityLiteral(BigDecimal("0.01"), factor)),
            conversion, "cents_to_yuan"), "value", "明确从分换为元"),
    ))
    val fresh = Calculation.Scope(members("current", current), emptyMap(), listOf(
        PostFilter(op(ResultOperator.GTE, symbol("day"), literalDate("2026-04-01"))), Derive(symbol("amount"), "value", "新系统元存量"),
    ))
    val body = Calculation(listOf(axes.getValue("subscription"), axes.getValue("day")), listOf(slot("legacy", legacy), slot("current", current)), emptyList(), emptyList(), asOfParameters(), Calculation.Scope(Calculation.SetCombination(Calculation.SetKind.UNION, true,
        listOf(Calculation.SetProjection("legacy", listOf("subscription", "day", "value")), Calculation.SetProjection("current", listOf("subscription", "day", "value"))),
        listOf(SetOutputContract("subscription", SetOutputContract.DimensionSlot("subscription")), SetOutputContract("day", SetOutputContract.DimensionSlot("day")),
            SetOutputContract("value", SetOutputContract.Quantity(balance())))),
        linkedMapOf("legacy" to Calculation.InputScope("ROOT", old), "current" to Calculation.InputScope("ROOT", fresh)),
        listOf(SelectVersions(listOf("subscription"), listOf(OrderKey("day", "DESC", "LAST")), VersionTies.FAIL,
            SnapshotSelection("day", Calculation.Argument("as_of"))))))
    return computed(body, mapOf("subscription" to "subscription", "day" to "day"), mapOf("value" to balance()), listOf("subscription"),
        "先按2026-04-01来源权威切换并换算单位，再按订阅选择截至as_of的最后版本；同日并列硬拒")
}

fun regionAt(axes: Map<String, Calculation.DimensionSlot>, regions: MemberResult): MemberSourceDefinition.Computed {
    val body = Calculation(listOf(axes.getValue("subscription"), axes.getValue("day"), axes.getValue("region")), listOf(slot("regions", regions)), emptyList(), emptyList(), asOfParameters(), Calculation.Scope(members("regions", regions), emptyMap(), listOf(
        PostFilter(op(ResultOperator.LTE, symbol("day"), ResultArgument("as_of", ScalarValueType.DATE))),
        SelectVersions(listOf("subscription"), listOf(OrderKey("day", "DESC", "LAST")), VersionTies.FAIL, null),
    )))
    return computed(body, mapOf("subscription" to "subscription", "day" to "day", "region" to "region"), emptyMap(), listOf("subscription"),
        "历史归属独立在同一as_of选择；区域不是当前静态属性，也不先筛区域")
}

fun historicalStock(axes: Map<String, Calculation.DimensionSlot>, stock: MemberResult, regions: MemberResult): MemberSourceDefinition.Computed {
    val body = Calculation(listOf(axes.getValue("subscription"), axes.getValue("day"), axes.getValue("region")), listOf(slot("stock", stock), slot("regions", regions)), emptyList(), emptyList(), asOfParameters(), Calculation.Scope(members("stock", stock),
        mapOf("history" to Calculation.InputScope("RELATION", Calculation.Scope(members("regions", regions), emptyMap(), emptyList()))),
        listOf(JoinInput("history", "history", "INNER", op(ResultOperator.EQ, symbol("subscription"), symbol("history__subscription"))),
            Project(listOf("subscription", "day", "value", "history__region")))))
    return computed(body, mapOf("subscription" to "subscription", "day" to "day", "region" to "history__region"), mapOf("value" to balance()),
        listOf("subscription"), "真实存量先选版本，区域随后按同历史时点关联；缺少归属不推断、不拿旧区域冒充")
}

fun stockMetric(axes: Map<String, Calculation.DimensionSlot>, source: MemberResult): MetricCalculation {
    val parameters = asOfParameters() + ("groups" to Calculation.Parameter(Calculation.ParameterKind.GROUPING, "SEMANTIC_DIMENSIONS",
        listOf(axes.getValue("region"), axes.getValue("subscription")), null, null))
    val body = Calculation(listOf(axes.getValue("subscription"), axes.getValue("day"), axes.getValue("region")), listOf(slot("records", source)), emptyList(), emptyList(), parameters, Calculation.Scope(members("records", source), emptyMap(), listOf(Regroup(ResultGrouping(listOf("\$group:groups"),
        listOf(ResultAggregate("value", ResultAggregation.Sum(balance(), ResultAggregation.NullPolicy.IGNORE), "balance", "历史时点存量（元）")), null)))))
    return MetricCalculation(body, "balance", listOf("\$group:groups"), balance())
}

fun usageMetric(axes: Map<String, Calculation.DimensionSlot>, usage: MemberResult, calendar: MemberResult): MetricCalculation {
    val body = Calculation(listOf(axes.getValue("day")), listOf(slot("calendar", calendar), slot("usage", usage)), emptyList(), emptyList(), emptyMap(), Calculation.Scope(Calculation.Input("calendar"), calendarInputs(usage, calendar), listOf(
        JoinInput("usage", "usage", "LEFT", op(ResultOperator.EQ, symbol("day"), symbol("usage__day"))),
        ObserveWindow(listOf("usage__tenant", "usage__person"), emptyList(), "rolling", "七日完整对象去重", listOf(OrderKey("day", "ASC", "LAST")),
            ResultAggregation.Count(true, ResultAggregation.NullPolicy.IGNORE), sevenDays(), ObservationDomain.CALENDAR, "day",
            dateRange("2026-03-02", "2026-03-09"), dateRange("2026-03-08", "2026-03-09"), CalendarAxis("calendar", "day")),
        Regroup(ResultGrouping(listOf("day"), listOf(ResultAggregate("rolling", ResultAggregation.Maximum(ResultAggregation.NullPolicy.IGNORE), "people", "七日使用对象数")), null)),
    )))
    return MetricCalculation(body, "people", listOf("day"), count())
}

fun dailyMeanMetric(axes: Map<String, Calculation.DimensionSlot>, usage: MemberResult, calendar: MemberResult): MetricCalculation {
    val mean = average(MetricValueContract.ONE)
    val body = Calculation(listOf(axes.getValue("day")), listOf(slot("calendar", calendar), slot("usage", usage)), emptyList(), emptyList(), emptyMap(), Calculation.Scope(Calculation.Input("calendar"), calendarInputs(usage, calendar), listOf(
        JoinInput("usage", "usage", "LEFT", op(ResultOperator.EQ, symbol("day"), symbol("usage__day"))),
        Regroup(ResultGrouping(listOf("day"), listOf(ResultAggregate("usage__id", ResultAggregation.Count(false, ResultAggregation.NullPolicy.IGNORE), "daily_events", "当日实际使用事件数，未匹配事件为零")), null)),
        ObserveWindow(listOf("daily_events"), emptyList(), "daily_mean", "完整七个公历日的使用事件日均", listOf(OrderKey("day", "ASC", "LAST")),
            ResultAggregation.Average(mean, ResultAggregation.NullPolicy.IGNORE, MeanTarget.SOURCE_NATIVE), sevenDays(), ObservationDomain.CALENDAR, "day",
            dateRange("2026-03-02", "2026-03-09"), dateRange("2026-03-08", "2026-03-09"), CalendarAxis("calendar", "day")),
        Project(listOf("day", "daily_mean")),
    )))
    return MetricCalculation(body, "daily_mean", listOf("day"), mean)
}

fun allocationMetric(axes: Map<String, Calculation.DimensionSlot>, destinations: MemberResult): MetricCalculation {
    val allocated = average(YUAN)
    val body = Calculation(listOf(axes.getValue("subscription"), axes.getValue("destination")), listOf(slot("destinations", destinations)), listOf(Calculation.MeasurementSlot("charges", emptyMap(), listOf(axes.getValue("subscription")), emptyList(), balance())), emptyList(), emptyMap(), Calculation.Scope(Calculation.Measures(listOf(Calculation.Call("charges", Calculation.Measurement("charges"), "value", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()))),
        listOf("subscription"), emptyList(), null, null, emptyMap(), Calculation.Population(listOf("charges")), null),
        mapOf("destinations" to Calculation.InputScope("RELATION", Calculation.Scope(members("destinations", destinations), emptyMap(), emptyList()))),
        listOf(JoinInput("destinations", "place", "INNER", op(ResultOperator.EQ, symbol("subscription"), symbol("place__subscription"))),
            Derive(ResultUniformAllocation(symbol("value"), listOf("subscription"), listOf("place__subscription", "place__destination"), allocated, "divide"),
                "allocated", "每个订阅确认账款在其实际完整目的地总体内均摊"),
            Regroup(ResultGrouping(listOf("place__destination"), listOf(ResultAggregate("allocated", ResultAggregation.Sum(allocated, ResultAggregation.NullPolicy.IGNORE), "allocated_total", "目的地分摊账款")), null)))))
    return MetricCalculation(body, "allocated_total", listOf("place__destination"), allocated)
}

fun chargesBySubscription(axes: Map<String, Calculation.DimensionSlot>, fact: MetricRef) = MetricCalculation(
    Calculation(listOf(axes.getValue("subscription")), emptyList(), emptyList(), emptyList(), emptyMap(), Calculation.Scope(
        Calculation.Measures(listOf(Calculation.Call("charges", Calculation.Fixed(fact), "value", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()))),
            listOf("subscription"), emptyList(), null, null, emptyMap(), Calculation.Population(listOf("charges")), null), emptyMap(), emptyList())),
    "value", listOf("subscription"), balance())

fun verifySubscriptions(page: ResultPage) = expect(page, mapOf("东" to mapOf("observation" to "125"), "西" to mapOf("observation" to "75")))

fun buildSubscriptions(): BusinessExample.Saved {
    val example = BusinessExample(olap, parameters, "subscriptions")
    val governance = example.governance
    val subject = example.context()
    val names = listOf("legacy_stock", "current_stock", "region_history", "usage_events", "calendar_days", "subscription_destinations", "subscription_charges")
    val tables = names.associateWith { name ->
        val types = linkedMapOf("id" to LogicType.NUMBER)
        if (name != "subscription_destinations") types["observed"] = LogicType.DATE
        when (name) {
            "legacy_stock", "current_stock", "subscription_charges" -> { types["subscription"] = LogicType.STRING; types["amount"] = LogicType.NUMBER }
            "region_history" -> { types["subscription"] = LogicType.STRING; types["region"] = LogicType.STRING }
            "usage_events" -> { types["tenant"] = LogicType.STRING; types["person"] = LogicType.STRING }
            "subscription_destinations" -> { types["subscription"] = LogicType.STRING; types["destination"] = LogicType.STRING }
        }
        example.table(name, types, "id", if (name == "subscription_destinations") null else "observed")
    }
    val events = tables.mapValues { (name, table) -> example.event(table, example.topology(table), "id", "record", "id", if (name == "subscription_destinations") null else "observed") }
    val axisTables = linkedMapOf("subscription" to listOf("legacy_stock", "current_stock", "region_history", "subscription_destinations", "subscription_charges"),
        "day" to listOf("legacy_stock", "current_stock", "region_history", "usage_events", "calendar_days", "subscription_charges"),
        "region" to listOf("region_history"), "destination" to listOf("subscription_destinations"))
    val eventBindings = linkedMapOf<String, Map<String, DimensionEventBindingRecord>>()
    val axes = axisTables.mapValues { (axis, names) ->
        val dimension = governance.createDimension(DimensionCreateRequest("subscriptions.$axis", "订阅共同语义轴", axis, example.actor, "同一业务身份与公历日期", example.actor)).dimension
        example.activate("DIMENSION", dimension.id)
        val bindings = names.associateWith { name -> example.binding(dimension, events.getValue(name), tables.getValue(name).units.getValue(if (axis == "day") "observed" else axis)) }
        eventBindings[axis] = bindings
        example.axis(subject, axis, axis, bindings.values.toList())
    }
    val slots = axes.keys.associateWith { Calculation.DimensionSlot(it, "subscriptions.$it", 1, it) }
    val numeric = mapOf("id" to ScalarValueType.NUMBER, "amount" to ScalarValueType.NUMBER)
    val rows = linkedMapOf(
        "legacy" to row("legacy_records", listOf("subscription", "day"), numeric, mapOf("amount" to amount(CENTS)), listOf("id")),
        "current" to row("current_records", listOf("subscription", "day"), numeric, mapOf("amount" to amount(YUAN)), listOf("id")),
        "regions" to row("region_records", listOf("subscription", "day", "region"), mapOf("id" to ScalarValueType.NUMBER), emptyMap(), listOf("id")),
        "usage" to row("usage_records", listOf("day"), mapOf("id" to ScalarValueType.NUMBER, "tenant" to ScalarValueType.TEXT, "person" to ScalarValueType.TEXT), emptyMap(), listOf("id")),
        "calendar" to row("calendar", listOf("day"), emptyMap(), emptyMap(), listOf("day")),
        "destinations" to row("destinations", listOf("subscription", "destination"), mapOf("id" to ScalarValueType.NUMBER), emptyMap(), listOf("subscription", "destination")))
    val sourceTables = mapOf("legacy" to "legacy_stock", "current" to "current_stock", "regions" to "region_history", "usage" to "usage_events",
        "calendar" to "calendar_days", "destinations" to "subscription_destinations")
    val members = linkedMapOf<String, MemberSourceRecord>()
    rows.forEach { (name, row) ->
        val table = sourceTables.getValue(name)
        val outputs = row.outputs.mapValues { (slot, output) -> when (output) {
            is MemberSourceDefinition.ComputedDimensionOutput -> MemberSourceDefinition.DimensionOutput(output.displayName, output.role, output.valueType,
                "subscriptions.$slot", 1, eventBindings.getValue(slot).getValue(table).id, 1, output.valueContract)
            is MemberSourceDefinition.ComputedPropertyOutput -> MemberSourceDefinition.PropertyOutput(output.displayName, output.role, output.valueType,
                tables.getValue(table).units.getValue(slot).id, 1, RelationAccessPath(emptyList()), output.valueContract)
            else -> error("本例记录输出必须明确来自语义维度或实际属性")
        } }
        members[name] = example.member(subject, row.entity, MemberSourceDefinition.Projection(EventRef(events.getValue(table).id, 1),
            MemberResult(row.entity, row.role, row.shape, row.identitySlots, outputs, row.filterOperators, row.timeSlot, row.clockSlots, row.description)))
    }
    fun call(source: MemberSourceRecord, arguments: Map<String, Calculation.Binding> = emptyMap()) = CalculationBindings.SourceCall(MemberRef(source.id, 1), arguments,
        source.definition.result.outputs.keys.associateWith { it }, emptyMap(), emptyMap())
    val migrated = migratedStock(slots, rows.getValue("legacy"), rows.getValue("current"))
    val region = regionAt(slots, rows.getValue("regions"))
    val formed = historicalStock(slots, migrated.result, region.result)
    listOf(Triple("selected_stock", migrated, mapOf("legacy" to "legacy", "current" to "current")),
        Triple("historical_region", region, mapOf("regions" to "regions")),
        Triple("historical_stock", formed, mapOf("stock" to "selected_stock", "regions" to "historical_region"))).forEach { (code, definition, inputSources) ->
        val shared = governance.createMemberDefinition(MemberDefinitionCreateRequest(code, code, definition, definition.result.description, example.actor, example.actor))
        example.activate("MEMBER_DEFINITION", shared.id)
        members[code] = governance.adoptMemberDefinition(MemberDefinitionAdoptRequest(subject.id, 1, code, code, shared.reference,
            CalculationBindings(definition.calculation.dimensionSlots.associate { it.name to axes.getValue(it.name).bound }, inputSources.mapValues { (_, name) ->
                call(members.getValue(name), if (code == "historical_stock") mapOf("as_of" to Calculation.Argument("as_of")) else emptyMap()) },
                emptyMap(), emptyMap(), emptyMap()), emptyMap(), example.actor))
    }
    val chargesUnit = tables.getValue("subscription_charges").units.getValue("amount")
    val charges = governance.createMetric(MetricCreateRequest("confirmed_charges", "实际确认账款", example.actor,
        MetricDefinition.FactMeasure(EventRef(events.getValue("subscription_charges").id, 1), SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, chargesUnit.id, 1),
            MetricAggregation.SUM, false, null, emptyMap(), null, balance()), "确认账款独立于时点存量", example.meaning("确认账款"), example.actor)).metric
    example.activate("METRIC", charges.id)
    val definitions = linkedMapOf("historical_balance" to stockMetric(slots, formed.result), "window_people" to usageMetric(slots, rows.getValue("usage"), rows.getValue("calendar")),
        "calendar_daily_mean" to dailyMeanMetric(slots, rows.getValue("usage"), rows.getValue("calendar")), "destination_allocation" to allocationMetric(slots, rows.getValue("destinations")),
        "charges_by_subscription" to chargesBySubscription(slots, MetricRef(charges.id, "1"))).mapValues { (name, definition) -> example.calculation(name, definition) }
    val adopted = linkedMapOf<String, SubjectAreaMetricRefRecord>()
    adopted["charges_by_subscription"] = example.adopt(subject, "charges_by_subscription", definitions.getValue("charges_by_subscription"),
        CalculationBindings(mapOf("subscription" to axes.getValue("subscription").bound), emptyMap(), emptyMap(), emptyMap(), emptyMap()))
    val balanceBindings = CalculationBindings(listOf("subscription", "day", "region").associateWith { axes.getValue(it).bound },
        mapOf("records" to call(members.getValue("historical_stock"), mapOf("as_of" to Calculation.Argument("as_of")))),
        mapOf("groups" to listOf(axes.getValue("region").bound, axes.getValue("subscription").bound)), emptyMap(), emptyMap())
    listOf(Triple("march_regions", "2026-03-31", "region"), Triple("april_regions", "2026-04-30", "region"), Triple("april_total", "2026-04-30", ""),
        Triple("subscription_balances", "2026-04-30", "subscription")).forEach { (name, date, group) -> adopted[name] = example.adopt(subject, name, definitions.getValue("historical_balance"), balanceBindings,
        mapOf("as_of" to Calculation.Scalar(ScalarValueType.DATE, date), "groups" to Calculation.Grouping(if (group.isEmpty()) emptyList() else listOf(axes.getValue(group).bound.reference)))) }
    val windowBindings = CalculationBindings(mapOf("day" to axes.getValue("day").bound), mapOf("usage" to call(members.getValue("usage")), "calendar" to call(members.getValue("calendar"))), emptyMap(), emptyMap(), emptyMap())
    adopted["seven_day_people"] = example.adopt(subject, "seven_day_people", definitions.getValue("window_people"), windowBindings)
    adopted["calendar_daily_mean"] = example.adopt(subject, "calendar_daily_mean", definitions.getValue("calendar_daily_mean"), windowBindings)
    adopted["allocated_destinations"] = example.adopt(subject, "allocated_destinations", definitions.getValue("destination_allocation"),
        CalculationBindings(listOf("subscription", "destination").associateWith { axes.getValue(it).bound }, mapOf("destinations" to call(members.getValue("destinations"))),
            emptyMap(), mapOf("charges" to adopted.getValue("charges_by_subscription").instance), emptyMap()))
    val scenario = governance.createScenario(ScenarioCreateRequest(subject.id, 1, "observation", "订阅历史与完整日历", example.actor, "存量、归属、窗口与贡献分别定义", example.actor)).scenario
    axes.values.forEach { example.grant(scenario, it) }
    val entry = example.publish(subject, scenario, adopted.values.toList(), emptyList())
    val expected = linkedMapOf("march_regions" to mapOf("东" to "150"), "april_regions" to mapOf("东" to "50", "南" to "150"),
        "april_total" to mapOf("total" to "200"), "subscription_balances" to mapOf("S1" to "150", "S2" to "50"),
        "charges_by_subscription" to mapOf("S1" to "150", "S2" to "50"), "seven_day_people" to mapOf("2026-03-08" to "3"))
    expected.forEach { (name, values) ->
        val axis = when (name) { "seven_day_people" -> "day"; "subscription_balances", "charges_by_subscription" -> "subscription"; else -> "region" }
        val saved = example.execute(entry, name, example.scope(listOf(example.call(adopted.getValue(name), "observation")), if (name == "april_total") emptyList() else listOf(axes.getValue(axis).bound.reference)))
        expect(saved.page, values.mapValues { (_, number) -> mapOf("observation" to number) })
    }
    val dailyMean = example.execute(entry, "calendar_daily_mean", example.scope(listOf(example.call(adopted.getValue("calendar_daily_mean"), "observation")), listOf(axes.getValue("day").bound.reference)))
    check(dailyMean.page.rows.size == 1)
    val mean = BigDecimal(requireNotNull(scalar(dailyMean.page.rows.single().last())))
    check(mean.subtract(BigDecimal(4).divide(BigDecimal(7), java.math.MathContext.DECIMAL128)).abs() <= BigDecimal("0.000000001")) { "日均必须使用完整七个公历日" }
    return example.execute(entry, "allocated_destinations", example.scope(listOf(example.call(adopted.getValue("allocated_destinations"), "observation")),
        listOf(axes.getValue("destination").bound.reference))).also { verifySubscriptions(it.page) }
}

olap.output(if (parameters.text("phase") == "build") references(buildSubscriptions()) else resume(olap, parameters, ::verifySubscriptions))
