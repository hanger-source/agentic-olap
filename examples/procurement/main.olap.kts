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
fun money() = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.UnitIdentity("iso4217", "CNY", 1)),
    MetricValueContract.Exact(), MetricValueContract.Decimal(38, 2, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)


fun sum(keys: List<String>, input: String) = Regroup(ResultGrouping(keys,
    listOf(ResultAggregate(input, ResultAggregation.Sum(money(), ResultAggregation.NullPolicy.IGNORE), "value", "记录金额合计")), null))

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
        stages.forEachIndexed { index, type -> governance.createScenarioPipelineRule(ScenarioPipelineRuleCreateRequest(scenario.id, 1, type, index + 1, StageAuthorization.current(type))) }
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

fun receiptDefinition(po: Calculation.DimensionSlot, latestFirst: Boolean): MemberSourceDefinition.Computed {
    val outputs = linkedMapOf<String, Calculation.MemberOutput>(
        "id" to Calculation.MemberProperty("delivery_identity", ScalarValueType.TEXT, null),
        "po" to Calculation.MemberDimension("po", null),
        "receipt" to Calculation.MemberProperty("record_identity", ScalarValueType.TEXT, null),
        "revision" to Calculation.MemberProperty("version", ScalarValueType.NUMBER, null),
        "status" to Calculation.MemberProperty("status", ScalarValueType.TEXT, null),
        "amount" to Calculation.MemberProperty("amount", ScalarValueType.NUMBER, money()),
        "delivered" to Calculation.MemberProperty("delivery_time", ScalarValueType.LOCAL_TIMESTAMP, null),
    )
    val versions = SelectVersions(listOf("receipt"), listOf(OrderKey("revision", "DESC", "LAST"),
        OrderKey("delivered", "DESC", "LAST"), OrderKey("id", "ASC", "LAST")), VersionTies.FAIL, null)
    val accepted = PostFilter(ResultOperation(ResultOperator.EQ,
        listOf(ResultSymbol("status"), ResultArgument("accepted", ScalarValueType.TEXT))))
    val stages: List<ValidatedOperation> = if (latestFirst) listOf(versions, accepted) else listOf(accepted, versions)
    val calculation = Calculation(listOf(po), listOf(Calculation.MemberSlot("deliveries", "收货投递", "delivery",
        MemberShape.RECORDS, outputs, null)), emptyList(), emptyList(), mapOf("accepted" to Calculation.Parameter(
    Calculation.ParameterKind.SCALAR, "TEXT", emptyList(), null, null)), Calculation.Scope(
    Calculation.Members("deliveries", "deliveries", outputs.keys.map { Calculation.MemberProjection(it, it) },
        null, emptyMap(), MemberCondition.Unrestricted()), emptyMap(),
    stages + Project(listOf("receipt", "po", "amount"))))
    return MemberSourceDefinition.Computed(calculation, MemberResult("业务金额记录", "amount_record", MemberShape.RECORDS,
        listOf("id"), mapOf(
            "id" to MemberSourceDefinition.ComputedPropertyOutput("收货身份", "record_identity", ScalarValueType.TEXT, "receipt", null),
            "po" to MemberSourceDefinition.ComputedDimensionOutput("采购单", "purchase_order", ScalarValueType.TEXT, "po", "po", null),
            "amount" to MemberSourceDefinition.ComputedPropertyOutput("收货金额", "amount", ScalarValueType.NUMBER, "amount", money()),
        ), emptyMap(), null, emptyMap(),
        if (latestFirst) "先选最新状态再判有效，取消的收货不保留" else "先选成功投递再选最新成功，后续取消不抹除历史成功"))
}

fun sumDefinition(po: Calculation.DimensionSlot): MetricCalculation {
    val rows = Calculation.MemberSlot("rows", "业务金额记录", "amount_record", MemberShape.RECORDS, mapOf(
        "id" to Calculation.MemberProperty("record_identity", ScalarValueType.TEXT, null),
        "po" to Calculation.MemberDimension("po", null),
        "amount" to Calculation.MemberProperty("amount", ScalarValueType.NUMBER, money()),
    ), null)
    val body = Calculation(listOf(po), listOf(rows), emptyList(), emptyList(), emptyMap(), Calculation.Scope(
        Calculation.Members("records", "rows", listOf("id", "po", "amount").map { Calculation.MemberProjection(it, it) },
            null, emptyMap(), MemberCondition.Unrestricted()), emptyMap(), listOf(sum(listOf("po"), "amount"))))
    return MetricCalculation(body, "value", listOf("po"), money())
}

fun totalDefinition(po: Calculation.DimensionSlot) = MetricCalculation(
    Calculation(listOf(po), emptyList(), listOf(Calculation.MeasurementSlot("amount", emptyMap(), listOf(po), emptyList(), money())), emptyList(), emptyMap(), Calculation.Scope(Calculation.Measures(listOf(Calculation.Call("amount", Calculation.Measurement("amount"), "amount", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()))), listOf("po"), emptyList(), null, null,
        emptyMap(), Calculation.Population(listOf("amount")), null), emptyMap(), listOf(sum(emptyList(), "amount")))),
    "value", emptyList(), money())

fun verifyProcurement(page: ResultPage) = expect(page, mapOf(
    "P-001" to mapOf("purchase" to "100", "latest_effective" to "80", "latest_successful" to "80"),
    "P-002" to mapOf("purchase" to "150", "latest_effective" to "50", "latest_successful" to "120")))

fun buildProcurement(): BusinessExample.Saved {
    val example = BusinessExample(olap, parameters, "procurement")
    val governance = example.governance
    val subject = example.context()
    val purchase = example.table("purchase_order", linkedMapOf("po_id" to LogicType.STRING, "amount" to LogicType.NUMBER), "po_id", null)
    val delivery = example.table("receipt_delivery", linkedMapOf("delivery_id" to LogicType.STRING, "receipt_id" to LogicType.STRING,
        "po_id" to LogicType.STRING, "revision" to LogicType.NUMBER, "status" to LogicType.STRING, "amount" to LogicType.NUMBER,
        "delivered_at" to LogicType.LOCAL_TIMESTAMP), "delivery_id", "delivered_at")
    val tables = mapOf("purchase" to purchase, "delivery" to delivery)
    val events = tables.mapValues { (name, table) -> example.event(table, example.topology(table), if (name == "purchase") "po_id" else "delivery_id",
        "purchase_order", "po_id", if (name == "purchase") null else "delivered_at") }
    val dimension = governance.createDimension(DimensionCreateRequest("example.purchase_order", "采购共同语义", "采购单", example.actor,
        "两来源使用同一采购系统完整键", example.actor)).dimension.also { example.activate("DIMENSION", it.id) }
    val bindings = tables.mapValues { (name, table) -> example.binding(dimension, events.getValue(name), table.units.getValue("po_id")) }
    val axis = example.axis(subject, "po", "purchase_order", bindings.values.toList())
    fun projection(name: String): MemberSourceDefinition.Projection {
        val table = tables.getValue(name)
        fun property(column: String, role: String, type: ScalarValueType, contract: MetricValueContract? = null) =
            MemberSourceDefinition.PropertyOutput(column, role, type, table.units.getValue(column).id, 1, RelationAccessPath(emptyList()), contract)
        val outputs = linkedMapOf<String, MemberSourceDefinition.Output>(
            "id" to property(if (name == "purchase") "po_id" else "delivery_id", if (name == "purchase") "record_identity" else "delivery_identity", ScalarValueType.TEXT),
            "po" to MemberSourceDefinition.DimensionOutput("采购单", "purchase_order", ScalarValueType.TEXT, dimension.id, 1, bindings.getValue(name).id, 1, null),
            "amount" to property("amount", "amount", ScalarValueType.NUMBER, money()))
        if (name == "delivery") {
            outputs["receipt"] = property("receipt_id", "record_identity", ScalarValueType.TEXT)
            outputs["revision"] = property("revision", "version", ScalarValueType.NUMBER)
            outputs["status"] = property("status", "status", ScalarValueType.TEXT)
            outputs["delivered"] = property("delivered_at", "delivery_time", ScalarValueType.LOCAL_TIMESTAMP)
        }
        return MemberSourceDefinition.Projection(EventRef(events.getValue(name).id, 1), MemberResult(
            if (name == "purchase") "业务金额记录" else "收货投递", if (name == "purchase") "amount_record" else "delivery", MemberShape.RECORDS,
            listOf("id"), outputs, emptyMap(), null, emptyMap(), "每次真实投递一行，保留重复、版本与取消历史"))
    }
    val purchases = example.member(subject, "purchase_rows", projection("purchase"))
    val deliveries = example.member(subject, "delivery_history", projection("delivery"))
    val po = Calculation.DimensionSlot("po", dimension.id, 1, "purchase_order")
    val formed = linkedMapOf("purchase" to purchases)
    listOf("latest_effective", "latest_successful").forEach { code ->
        formed[code] = example.member(subject, code, receiptDefinition(po, code == "latest_effective"),
            CalculationBindings(mapOf("po" to axis.bound), mapOf("deliveries" to example.sourceCall(deliveries, "id", "po", "receipt", "revision", "status", "amount", "delivered")),
                emptyMap(), emptyMap(), emptyMap()), mapOf("accepted" to Calculation.Scalar(ScalarValueType.TEXT, "SUCCESS")))
    }
    val sum = example.calculation("record_amount_by_po", sumDefinition(po))
    val total = example.calculation("measurement_total", totalDefinition(po))
    val metrics = linkedMapOf<String, SubjectAreaMetricRefRecord>()
    formed.forEach { (code, member) ->
        val measured = example.adopt(subject, code, sum, CalculationBindings(mapOf("po" to axis.bound),
            mapOf("rows" to example.sourceCall(member, "id", "po", "amount")), emptyMap(), emptyMap(), emptyMap()))
        metrics[code] = measured
        metrics["${code}_total"] = example.adopt(subject, "${code}_total", total, CalculationBindings(mapOf("po" to axis.bound), emptyMap(), emptyMap(),
            mapOf("amount" to measured.instance), emptyMap()))
    }
    val scenario = governance.createScenario(ScenarioCreateRequest(subject.id, 1, "receipt_comparison", "采购与两种收货口径", example.actor,
        "独立完成采购和收货，按采购人口对齐", example.actor)).scenario
    example.grant(scenario, axis)
    val entry = example.publish(subject, scenario, metrics.values.toList(), emptyList())
    val totals = example.execute(entry, "procurement_totals", example.scope(listOf("purchase", "latest_effective", "latest_successful").map {
        example.call(metrics.getValue("${it}_total"), it) }, emptyList(), listOf("purchase")))
    expect(totals.page, mapOf("total" to mapOf("purchase" to "250", "latest_effective" to "130", "latest_successful" to "200")))
    return example.execute(entry, "procurement_by_po", example.scope(listOf("purchase", "latest_effective", "latest_successful").map {
        example.call(metrics.getValue(it)) }, listOf(axis.bound.reference), listOf("purchase"))).also { verifyProcurement(it.page) }
}

olap.output(if (parameters.text("phase") == "build") references(buildProcurement()) else resume(olap, parameters, ::verifyProcurement))
