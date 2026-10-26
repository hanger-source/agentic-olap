import com.olap.prototype.dsl.*
import com.olap.prototype.dsl.transport.CapabilityFailureException
import com.olap.prototype.calculation.*
import com.olap.prototype.event.*
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.governance.contract.*
import com.olap.prototype.member.*
import com.olap.prototype.metric.*
import com.olap.prototype.query.*
import com.olap.prototype.query.PostOperationContract.Derive
import com.olap.prototype.query.PostOperationContract.JoinInput
import com.olap.prototype.query.PostOperationContract.PostFilter
import com.olap.prototype.query.PostOperationContract.Project
import com.olap.prototype.query.PostOperationContract.Regroup
import com.olap.prototype.query.PostOperationContract.ResultAggregate
import com.olap.prototype.query.PostOperationContract.ResultGrouping
import com.olap.prototype.query.PostOperationContract.ValidatedOperation
import com.olap.prototype.query.ResultExpression.*
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.definition.QueryDefinitionCommand
import com.olap.prototype.warehouse.contract.*
import com.olap.prototype.warehouse.onboarding.*
import com.olap.prototype.engine.executable.ExecutableBusinessResult
import java.math.BigDecimal

fun ScriptValue.fields() = (this as ScriptValue.ObjectValue).values
fun Map<String, ScriptValue>.text(name: String) = (getValue(name) as ScriptValue.Text).value
fun unit(name: String) = MetricValueContract.UnitIdentity("example.populations", name, 1)
val count = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(), MetricValueContract.Integer(64), emptyMap(), MetricValueContract.Overflow.FAIL)
fun floating(name: String, equations: Map<String, MetricValueContract.UnitEquation> = emptyMap()) = MetricValueContract(MetricValueContract.Fixed(unit(name)), MetricValueContract.NativeFloating(), MetricValueContract.BinaryFloat(64), equations, MetricValueContract.Overflow.FAIL)
fun exact(scale: Int, rounding: MetricValueContract.Rounding) = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(), MetricValueContract.Decimal(38, scale, rounding), emptyMap(), MetricValueContract.Overflow.FAIL)
fun sym(name: String): ResultExpression = ResultSymbol(name)
fun op(operator: ResultOperator, vararg operands: ResultExpression): ResultExpression = ResultOperation(operator, operands.toList())
fun text(value: String): ResultExpression = ResultTypedLiteral(ScalarValueType.TEXT, value)
fun number(value: String, contract: MetricValueContract = count): ResultExpression = ResultQuantityLiteral(BigDecimal(value), contract)
fun choose(condition: ResultExpression, value: ResultExpression, otherwise: ResultExpression = ResultLiteral(null)): ResultExpression = ResultCaseWhen(listOf(ResultCaseBranch(condition, value)), otherwise)
fun nonnull(name: String) = op(ResultOperator.NOT, op(ResultOperator.IS_NULL, sym(name)))
fun regroup(keys: List<String>, vararg values: ResultAggregate) = Regroup(ResultGrouping(keys, values.toList(), null))
fun sum(input: String, output: String, contract: MetricValueContract) = ResultAggregate(input, ResultAggregation.Sum(contract, ResultAggregation.NullPolicy.IGNORE), output, output)
fun counted(input: String, output: String, distinct: Boolean = false) = ResultAggregate(input, ResultAggregation.Count(distinct, ResultAggregation.NullPolicy.IGNORE), output, output)
fun condition(name: String, type: ScalarValueType, operator: MemberCondition.Operator, vararg values: String) = MemberCondition.Predicate(name, operator, values.map { Calculation.Scalar(type, it) })
fun stageAuthorization(stage: String): StageAuthorization {
    val expression = ExpressionAuthorization(setOf(ExpressionAuthorization.Kind.ARGUMENT, ExpressionAuthorization.Kind.SYMBOL, ExpressionAuthorization.Kind.LITERAL,
        ExpressionAuthorization.Kind.QUANTITY_LITERAL, ExpressionAuthorization.Kind.NUMERIC, ExpressionAuthorization.Kind.TYPED_LITERAL, ExpressionAuthorization.Kind.OPERATION, ExpressionAuthorization.Kind.CASE),
        setOf(ResultOperator.EQ, ResultOperator.NE, ResultOperator.LT, ResultOperator.GT, ResultOperator.NOT, ResultOperator.AND, ResultOperator.IS_NULL, ResultOperator.IS_NOT_NULL, ResultOperator.DIVIDE, ResultOperator.SUBTRACT) +
            if (stage == "DERIVE") setOf(ResultOperator.MULTIPLY) else emptySet(), emptySet(), emptySet(), setOf(ScalarValueType.TEXT, ScalarValueType.NUMBER))
    return StageAuthorization(stage, if (stage == "JOIN_INPUT") setOf(StageAuthorization.JoinType.LEFT, StageAuthorization.JoinType.INNER) else emptySet(),
        if (stage == "REGROUP") setOf(ResultAggregation.Kind.SUM, ResultAggregation.Kind.COUNT, ResultAggregation.Kind.COUNT_DISTINCT, ResultAggregation.Kind.MAX) else emptySet(), expression)
}

fun readAll(reference: QueryResultReference): List<Map<String, String?>> {
    val rows = mutableListOf<Map<String, String?>>()
    var offset = 0
    var total: Int? = null
    while (true) {
        // 每页一行，人口、重复和空串都在完整正式分页后检查。
        val page = olap.results.read(ResultReadRequest(reference, offset, 1, null))
        check(page.resultReference == reference && page.offset == offset)
        if (total == null) total = page.totalRows else check(total == page.totalRows)
        page.rows.forEach { row -> rows += page.columns.map { it.symbol.name }.zip(row).associate { (name, cell) ->
            val value = (cell as ResultValueView.Complete).value
            name to when (value) { is ExecutableBusinessResult.NullValue -> null; is ExecutableBusinessResult.ScalarValue -> value.value; else -> error("本例读取标量") }
        } }
        offset += page.rows.size
        check(page.rows.isNotEmpty() || offset.toLong() == page.totalRows.toLong())
        if (page.nextOffset == null) { check(offset == page.totalRows); break }
        check(page.nextOffset == offset)
    }
    return rows
}
fun expect(rows: List<Map<String, String?>>, expected: Map<String, Map<String, String?>>, key: String = "subject") {
    val actual = rows.associateBy { if (key == "") "total" else requireNotNull(it[key]) }
    check(rows.size == actual.size && actual.keys == expected.keys) { "人口不符：$rows；预期$expected" }
    expected.forEach { (id, fields) -> fields.forEach { (name, expectedValue) ->
        val value = actual.getValue(id).getValue(name)
        check(if (expectedValue == null) value == null else value != null &&
            (expectedValue.toBigDecimalOrNull()?.let { it.compareTo(value.toBigDecimal()) == 0 } ?: (value == expectedValue))) { "$id/$name：实际$value，预期$expectedValue" }
    } }
}
fun expectSavedQuantities(reference: QueryResultReference, expected: Map<String, MetricValueContract>) {
    val page = olap.results.read(ResultReadRequest(reference, 0, 1, null))
    check(page.resultReference == reference && page.columns.map { it.symbol.name } == listOf("subject") + expected.keys)
    page.columns.drop(1).forEach { column ->
        val contract = expected.getValue(column.symbol.name)
        val actual = requireNotNull(column.symbol.quantity) { "保存列丢失数量合同：${column.symbol.name}" }
        check(actual.declaration.unit == contract.unit && actual.unit == QueryQuantity.Fixed((contract.unit as MetricValueContract.Fixed).identity) &&
            actual.declaration.arithmetic == contract.arithmetic && actual.declaration.result == contract.result && actual.declaration.overflow == contract.overflow) {
            "保存列数量合同不符：${column.symbol.name}；实际${actual.declaration}，预期$contract"
        }
    }
}

class PopulationExample {
    val actor = olap.caller.subject
    val governance = olap.governance
    val source = olap.sources.list(EmptyRequest).items.single { it.binding.dataSourceId == parameters.text("sourceId") }
    val adopted = mutableListOf<SourceAdoptionRef>()
    val members = linkedMapOf<String, MemberSourceRecord>()
    val outputs = linkedMapOf<String, Map<String, MemberSourceDefinition.Output>>()
    val filters = linkedMapOf<String, Map<String, List<MemberCondition.Operator>>>()
    val bindings = mutableListOf<DimensionEventBindingRecord>()
    val dimension = governance.createDimension(DimensionCreateRequest("example.population_subject", "人口组合", "对象", actor, "同一受控来源中的完整TEXT身份，空串合法", actor)).dimension.also { activate("DIMENSION", it.id) }
    val line = governance.createBusinessLine(BusinessLineCreateRequest(code = "populations", displayName = "组合人口", owner = actor,
        description = "受控来源上的完整语义计算", createdBy = actor)).businessLine.also { activate("BUSINESS_LINE", it.id) }
    val subjectDefinition = SubjectAreaCreateRequest(line.id, 1, "observations", "观察组合", actor, "对象、数量及来源边界", actor)
    var subject = governance.createSubjectArea(subjectDefinition).subjectArea
    lateinit var axis: CalculationBindings.Dimension
    val slot = Calculation.DimensionSlot("subject", dimension.id, 1, "subject")
    fun activate(type: String, id: String, version: Long = 1) { governance.activate(GovernanceVersionActivationRequest(GovernanceActivationObjectType.valueOf(type), id, version, actor, "示例采用精确版本")) }
    fun install(name: String, types: Map<String, ScalarValueType>, quantities: Map<String, MetricValueContract> = emptyMap(), castAdjustment: Boolean = false, captionPredicate: Boolean = false,
        castReference: Boolean = false, filterOperators: Map<String, List<MemberCondition.Operator>> = emptyMap()) {
        val discovered = governance.discoverSource(WarehouseDiscoveryCommand(source.binding.dataSourceId, source.binding.sourceIdentityHash, null, "main", name, listOf("TABLE"), 10, emptyMap(), emptyMap(), actor))
        check(discovered.status == "SUCCEEDED" && !discovered.truncated)
        val table = olap.semantics.discoveredTables(GovernanceReadRequests.SnapshotPageInput(discovered.snapshotId, 0, 10)).items.single()
        val columns = olap.semantics.discoveredColumns(GovernanceReadRequests.DiscoveredTablePageInput(table.id, 0, 200))
        check(columns.items.size.toLong() == columns.total.toLong() && columns.items.map { it.columnName }.toSet() == types.keys)
        val physical = governance.createPhysicalTable(PhysicalTableCreateRequest("populations.$name", name, name, "无记录键的nullable来源袋", "example", actor, "PUBLIC", "example", source.binding.dataSourceId, table.catalogName, table.schemaName,
            PhysicalTableCreateRequest.SourceAdoptionInput(table.id, emptyList()), actor, columns.items.map { column -> PhysicalTableCreateRequest.ColumnInput(column.columnName, column.columnName, column.typeName, "PUBLIC", requireNotNull(column.nullable), null,
                com.olap.prototype.contract.JdbcColumnType(column.jdbcType, column.typeName, column.precision, column.scale, column.nullable)) }))
        adopted += physical.sourceAdoptions.references
        val recovered = olap.semantics.sourceAdoptions(SourceAdoptionReadRequest(discovered.snapshotId))
        check(recovered.references == physical.sourceAdoptions.references)
        activate("PHYSICAL_TABLE", physical.physicalTable.id)
        val units = physical.physicalColumns.map { column -> LogicalTableCreateRequest.LogicUnitInput(column.columnName, column.columnName, LogicUnitSourceKind.COLUMN, null,
            when (types.getValue(column.columnName)) { ScalarValueType.NUMBER -> LogicType.NUMBER; ScalarValueType.BOOLEAN -> LogicType.BOOLEAN; else -> LogicType.STRING }, null,
            false, false, false, true, types.getValue(column.columnName) == ScalarValueType.NUMBER,
            listOf(LogicalTableCreateRequest.SourceRefInput(column.id, 1, LogicUnitSourceRefRole.DIRECT_SOURCE))) }.toMutableList()
        if (castAdjustment) {
            val column = physical.physicalColumns.single { it.columnName == "adjustment_text" }
            units += LogicalTableCreateRequest.LogicUnitInput("adjustment", "正式来源DOUBLE转换", LogicUnitSourceKind.EXPRESSION,
                SemanticExpression.Cast(SemanticExpression.Reference(SemanticExpression.ReferenceKind.PHYSICAL_COLUMN, column.id, 1), SemanticExpression.ValueType.DOUBLE, null, null, MetricValueContract.Rounding.NONE), LogicType.NUMBER, null, false, false, false, true, true, emptyList())
        }
        if (captionPredicate) {
            val caption = physical.physicalColumns.single { it.columnName == "caption" }
            units += LogicalTableCreateRequest.LogicUnitInput("caption_accepted", "Unicode模式NOT LIKE的三值资格", LogicUnitSourceKind.EXPRESSION,
                SemanticExpression.Operation(SemanticExpression.Operator.NOT, listOf(SemanticExpression.Operation(SemanticExpression.Operator.LIKE, listOf(
                    SemanticExpression.Reference(SemanticExpression.ReferenceKind.PHYSICAL_COLUMN, caption.id, caption.currentVersion, null),
                    SemanticExpression.Literal("%样本%"))))), LogicType.BOOLEAN, null, false, false, false, true, false, emptyList())
        }
        if (castReference) {
            val column = physical.physicalColumns.single { it.columnName == "reference_number" }
            units += LogicalTableCreateRequest.LogicUnitInput("reference_text", "账本原I64引用的TEXT出口", LogicUnitSourceKind.EXPRESSION,
                SemanticExpression.Cast(SemanticExpression.Reference(SemanticExpression.ReferenceKind.PHYSICAL_COLUMN, column.id, column.currentVersion), SemanticExpression.ValueType.STRING, null, null, MetricValueContract.Rounding.NONE),
                LogicType.STRING, null, false, false, false, true, false, emptyList())
        }
        val logical = governance.createLogicalTable(LogicalTableCreateRequest(physical.physicalTable.id, 1, name, name, LogicalTableRole.FACT, "保原袋与NULL，不造PK", actor, actor, units))
        activate("LOGICAL_TABLE", logical.logicalTable.id)
        val topology = governance.createTopology(TopologyCreateRequest(name, name, "example.populations", "独立来源时钟", logical.logicalTable.id, 1, actor, "完整记录边界", actor)).topology
        activate("TOPOLOGY", topology.id)
        val event = governance.createEvent(EventCreateRequest(topology.id, 1, "records", name, actor, null, null, EventIdentity(emptyList(), emptyMap()), emptyMap(),
            logical.logicUnits.map { EventCreateRequest.LogicUnitRefInput(it.id, 1) }, logical.logicUnits.filter { it.aggregatable }.map { EventCreateRequest.LogicUnitRefInput(it.id, 1) }, "RAW_BAG无记录身份", actor)).event
        activate("EVENT", event.id)
        val bound = if ("subject" in types) governance.createDimensionEventBinding(DimensionEventBindingCreateRequest(dimension.id, 1, event.id, 1, logical.logicUnits.single { it.code == "subject" }.id, 1, null, RelationAccessPath(emptyList()), emptyMap(), actor)).binding.also { activate("DIMENSION_EVENT_BINDING", it.id); bindings += it } else null
        val effective = types + (if (castAdjustment) mapOf("adjustment" to ScalarValueType.NUMBER) else emptyMap()) +
            (if (captionPredicate) mapOf("caption_accepted" to ScalarValueType.BOOLEAN) else emptyMap()) +
            (if (castReference) mapOf("reference_text" to ScalarValueType.TEXT) else emptyMap())
        val declared: Map<String, MemberSourceDefinition.Output> = effective.mapValues { (code, type) -> if (code == "subject") MemberSourceDefinition.DimensionOutput(code, "subject", type, dimension.id, 1, requireNotNull(bound).id, 1, null)
            else MemberSourceDefinition.PropertyOutput(code, code, type, logical.logicUnits.single { it.code == code }.id, 1, RelationAccessPath(emptyList()), quantities[code]) }
        require(effective.keys.containsAll(filterOperators.keys))
        val allowed = effective.mapValues { (_, type) -> if (type == ScalarValueType.TEXT) listOf(MemberCondition.Operator.EQ, MemberCondition.Operator.BETWEEN) else listOf(MemberCondition.Operator.EQ) } + filterOperators
        outputs[name] = declared; filters[name] = allowed
        val definition = MemberSourceDefinition.Projection(EventRef(event.id, 1), MemberResult(name, "records", MemberShape.RAW_BAG, emptyList(), declared, allowed, null, emptyMap(), null))
        members[name] = governance.createMemberSource(MemberSourceCreateRequest(subject.id, 1, name, name, definition, CalculationBindings.empty(), emptyMap(), actor))
    }
    fun savedContext(): SubjectContextRevisionResult {
        // 显式延续同一主题的成员、轴、场景；旧发布仍保持版本1，不重建P/S或业务采用。
        val carry = olap.semantics.contextSelection(SubjectContextSelectionRequest(subject.id, subject.currentVersion)).selection
        val revised = governance.reviseSubjectArea(SubjectContextRevisionRequest(subject.id, subject.currentVersion, subjectDefinition, carry))
        subject = revised.subjectArea.subjectArea
        val current = olap.semantics.memberSources(GovernanceReadRequests.VersionPageInput(subject.id, subject.currentVersion, 0, 100))
        check(current.items.size.toLong() == current.total.toLong() && current.items.map { it.code }.toSet() == members.keys)
        members.clear(); current.items.forEach { members[it.code] = it }
        val dimension = revised.carried.single { it.assetType == "ANALYSIS_DIMENSION" && it.id == axis.reference.id }
        val mappings = olap.semantics.dimensionMappings(GovernanceReadRequests.VersionPageInput(dimension.id, dimension.targetVersion, 0, 100))
        check(mappings.items.size.toLong() == mappings.total.toLong() && mappings.items.size == bindings.size)
        axis = CalculationBindings.Dimension(ScopedInputContract.DimensionRef(dimension.id, dimension.targetVersion.toString(), "subject"), "subject", mappings.items.map { it.id })
        return revised
    }
    fun createAxis() {
        val analysis = governance.createAnalysisDimension(AnalysisDimensionCreateRequest(subject.id, 1, "subject", "对象", "同一来源声明的完整TEXT身份", "subject", "subject", actor)).analysisDimension
        val alignment = governance.createAnalysisDimensionValueAlignment(AnalysisDimensionValueAlignmentCreateRequest(analysis.id, 1, "subject", "PROVEN_SAME_KEY", "{\"basis\":\"本例所有subject来自同一个受控身份空间\"}", actor))
        val mappings = bindings.map { binding -> governance.createAnalysisDimensionEventMapping(AnalysisDimensionEventMappingCreateRequest(analysis.id, 1, "subject", binding.id, 1, "PROVEN_SAME_KEY", alignment.id)).mapping }
        axis = CalculationBindings.Dimension(ScopedInputContract.DimensionRef(analysis.id, "1", "subject"), "subject", mappings.map { it.id })
    }
    fun publication(version: String, metrics: List<SubjectAreaMetricRefRecord> = emptyList(), memberNames: List<String> = members.keys.toList(), displayName: String = "人口组合$version"): PublishedQueryEntry {
        val scenario = governance.createScenario(ScenarioCreateRequest(subject.id, subject.currentVersion, "scenario_${version.replace('-', '_')}", displayName, actor, "正式成员与实际阶段授权", actor)).scenario
        memberNames.forEach { name -> val member = members.getValue(name)
            governance.createScenarioMemberSourceRef(ScenarioMemberSourceRefCreateRequest(scenario.id, 1, member.id, member.subjectAreaVersion, MemberQueryPermission(outputs.getValue(name).keys.toList(), emptyList(), filters.getValue(name), false, emptyList()))) }
        governance.createScenarioAnalysisDimensionRef(ScenarioAnalysisDimensionRefCreateRequest(scenario.id, 1, axis.reference.id, axis.reference.version.toLong(), "subject", true, 1))
        metrics.forEachIndexed { index, metric -> governance.createScenarioMetricRef(ScenarioMetricRefCreateRequest(scenario.id, 1, metric.id, metric.subjectAreaVersion, true, index + 1)) }
        listOf("POST_FILTER", "DERIVE", "REGROUP", "JOIN_INPUT", "PROJECT").forEachIndexed { index, stage -> governance.createScenarioPipelineRule(ScenarioPipelineRuleCreateRequest(scenario.id, 1, stage, index + 1, stageAuthorization(stage))) }
        activate("SUBJECT_AREA", subject.id, subject.currentVersion)
        val built = governance.buildRelease(SubjectAreaReleaseBuildRequest("populations-release", version, "完整人口与数量组合", subject.id, subject.currentVersion, actor, adopted))
        if (metrics.isEmpty()) check(built.metricsFrozen == 0)
        return olap.semantics.publication(QueryCapabilityRequest(scenario.id, 1, "populations-release", version))
    }
    fun raw(name: String, columns: List<String> = outputs.getValue(name).keys.toList(), condition: MemberCondition = MemberCondition.Unrestricted()): Calculation.Scope {
        val rows = Computations.members(name, MemberRef(members.getValue(name).id, members.getValue(name).subjectAreaVersion), emptyMap(),
            columns.map { Calculation.MemberProjection(it, it, if (it == "subject") Calculation.PublishedDimension(axis.reference) else null) }, condition = condition)
        return if ("subject" in columns) Computations.stages(rows, listOf(PostFilter(nonnull("subject")))) else rows
    }
    data class Saved(val query: QueryReference, val result: QueryResultReference, val rows: List<Map<String, String?>>)
    fun execute(entry: PublishedQueryEntry, name: String, input: Calculation.Scope, mode: QueryExecutionMode = QueryExecutionMode.SOURCE): Saved {
        val saved = olap.queries.save(QueryDefinitionCommand(null, null, requireNotNull(entry.capability.capabilityHash), name,
            QueryIntent(PublishedQueryEntry.key(entry.capability), input), null, executionMode = mode))
        val receipt = olap.queries.execute(QueryExecutionRequest(QuerySelection.Saved(saved.reference), true, name, null, executionMode = mode))
        check(receipt.status == QueryExecutionStatus.SUCCEEDED && receipt.saved) { receipt.error.toString() }
        check(receipt.executionMode == mode && receipt.sourceState == SourceExecutionState.SUCCEEDED && receipt.sourceReadEvidence?.basis is SourceReadBasis.Unknown)
        val result = requireNotNull(receipt.resultReference)
        return Saved(saved.reference, result, readAll(result)).also { check(it.rows.size.toLong() == receipt.rows) }
    }
    fun savedAlgorithm(code: String, definition: MetricCalculation): SubjectAreaMetricRefRecord {
        val meaning = MetricBusinessMeaning(code, "显式数量合同", "完整subject键完成关系", listOf("资格由调用方形成，不访问或重读来源"))
        val metric = governance.createMetricCalculation(MetricCalculationCreateRequest(code, code, actor, definition, "真实保存算法", meaning, actor)).metric
        activate("METRIC", metric.id)
        return governance.createSubjectAreaMetricRef(SubjectAreaMetricRefCreateRequest(subject.id, subject.currentVersion, code, code, meaning, metric.id, 1,
            CalculationBindings(mapOf("subject" to axis), emptyMap(), emptyMap(), emptyMap(), definition.calculation.relationSlots.associate { it.name to CalculationBindings.RelationParameter() }), emptyMap(), actor)).subjectAreaMetricRef
    }
}

fun cohortQuery(e: PopulationExample): Calculation.Scope {
    fun paid(day: String) = Computations.stages(e.raw("activity", listOf("subject", "actor"), MemberCondition.All(listOf(
        condition("day", ScalarValueType.TEXT, MemberCondition.Operator.EQ, day), condition("first_flag", ScalarValueType.BOOLEAN, MemberCondition.Operator.EQ, "true")))), listOf(regroup(listOf("subject"), counted("actor", "actors", true)), PostFilter(op(ResultOperator.GT, sym("actors"), number("0"))), Project(listOf("subject"))))
    val directory = e.raw("catalog", listOf("subject", "item"), condition("current_partition", ScalarValueType.BOOLEAN, MemberCondition.Operator.EQ, "true"))
    val visits = e.raw("visits", listOf("item", "actor"), condition("day", ScalarValueType.TEXT, MemberCondition.Operator.BETWEEN, "20000101", "20000102"))
    val visited = Computations.scope {
        input("directory", directory, InputMode.RELATION)
        Computations.stages(visits, listOf(
            JoinInput("directory", "d", "LEFT", op(ResultOperator.EQ, sym("item"), sym("d__item"))), PostFilter(nonnull("d__subject")),
            regroup(listOf("d__subject"), counted("actor", "actors", true)), PostFilter(op(ResultOperator.GT, sym("actors"), number("0"))), Project(listOf("d__subject"))))
    }
    val identity = listOf(SetOutputContract("subject", SetOutputContract.Dimension(e.axis.reference)))
    val priorVisits = Computations.set(Calculation.SetKind.INTERSECT, false, linkedMapOf(
        "previous" to Computations.ProjectedInput(paid("19991231"), listOf("subject")),
        "visited" to Computations.ProjectedInput(visited, listOf("d__subject"))), identity)
    val population = Computations.set(Calculation.SetKind.EXCEPT, false, linkedMapOf(
        "prior_visits" to Computations.ProjectedInput(priorVisits, listOf("subject")),
        "current" to Computations.ProjectedInput(paid("20000101"), listOf("subject"))), identity)
    fun qualified(name: String, condition: MemberCondition): Calculation.Scope {
        val records = e.raw(name, condition = condition)
        return Computations.objectMeasure(records, population, listOf(Calculation.ObjectRole(listOf("subject"),
            Calculation.RecordCallOutputs(emptyList(), name, listOf("subject")))))
    }
    val catalog = qualified("catalog", condition("current_partition", ScalarValueType.BOOLEAN, MemberCondition.Operator.EQ, "true"))
    val completedCatalog = Computations.stages(catalog, listOf(Derive(choose(sym("eligible"), sym("item")), "qualified_item", "显式目录资格"),
        regroup(listOf("subject"), counted("item", "items", true), counted("qualified_item", "qualified_items", true))))
    val records = qualified("activity", condition("day", ScalarValueType.TEXT, MemberCondition.Operator.BETWEEN, "20000101", "20000102"))
    val completedAmount = Computations.stages(records, listOf(regroup(listOf("subject"), sum("amount", "measured_amount", floating("activity-value")), counted("subject", "records"), counted("amount", "known"))))
    val key = mapOf("subject" to "subject")
    return Computations.align(linkedMapOf("population" to Computations.aligned(population, key, emptyList()),
        "catalog" to Computations.aligned(completedCatalog, key, listOf("items", "qualified_items").map { Calculation.AlignedValue(it, it, Calculation.NoMatch.PRESERVE_NULL) }),
        "amount" to Computations.aligned(completedAmount, key, listOf(Calculation.AlignedValue("measured_amount", "amount", Calculation.NoMatch.PRESERVE_NULL)) +
            listOf("records", "known").map { Calculation.AlignedValue(it, it, Calculation.NoMatch.PRESERVE_NULL) })), listOf("subject"), Calculation.AlignmentPopulation(Calculation.PopulationKind.BASE, listOf("population")))
}

// 在各来源自己的完整partition_key关系上取MAX，不把fixture布尔值或其他来源日期当最新分区。
fun latestPartitionRows(e: PopulationExample, source: String, columns: List<String>, condition: MemberCondition = MemberCondition.Unrestricted()): Calculation.Scope {
    val maximum = Computations.stages(e.raw(source, listOf("partition_key")), listOf(regroup(emptyList(),
        ResultAggregate("partition_key", ResultAggregation.Maximum(ResultAggregation.NullPolicy.IGNORE), "latest_partition", "本来源执行时最大分区"))))
    val rows = e.raw(source, (columns + "partition_key").distinct(), condition)
    return Computations.scope {
        input("latest", maximum, InputMode.RELATION)
        Computations.stages(rows, listOf(
            JoinInput("latest", "partition", "LEFT", op(ResultOperator.EQ, sym("partition_key"), sym("partition__latest_partition"))),
            PostFilter(nonnull("partition__latest_partition")), Project(columns)))
    }
}

val labelRelationColumns = listOf("subject", "label_kind", "label_id", "label_display", "has_label_match")
val labelRelationExpected: List<List<String?>> = listOf(
    listOf("A", "CATEGORY_A", "label-1", "Same", "true"),
    listOf("A", "CATEGORY_A", "label-2", "Same", "true"),
    listOf("A", "CATEGORY_B", "label-3", "Other", "true"),
    listOf("A", null, null, null, "false"),
    listOf("B", "CATEGORY_A", "label-4", "Alpha", "true"),
    listOf("C", null, null, null, "false"))
fun labelRelationsQuery(e: PopulationExample): Calculation.Scope {
    val facts = Computations.stages(e.raw("label_facts", listOf("subject", "object_ref"), MemberCondition.All(listOf(
        condition("partition_key", ScalarValueType.TEXT, MemberCondition.Operator.GTE, "20000101"),
        condition("activity_day", ScalarValueType.TEXT, MemberCondition.Operator.BETWEEN, "20010101", "20010107")))),
        listOf(regroup(listOf("subject", "object_ref"))))
    val labels = Computations.stages(latestPartitionRows(e, "labels", listOf("object_ref", "label_kind", "label_id", "label_display"),
        condition("label_kind", ScalarValueType.TEXT, MemberCondition.Operator.IN, "CATEGORY_A", "CATEGORY_B")),
        listOf(regroup(listOf("object_ref", "label_kind", "label_id", "label_display"))))
    // 两类别共用同一标签关系，只LEFT一次；显示文本不替代ID，NULL对象键按普通EQ不匹配。
    return Computations.scope {
        input("labels", labels, InputMode.RELATION)
        Computations.stages(facts, listOf(
            JoinInput("labels", "label", "LEFT", op(ResultOperator.EQ, sym("object_ref"), sym("label__object_ref"))),
            Derive(sym("label__label_kind"), "label_kind", "标签原类别"),
            Derive(sym("label__label_id"), "label_id", "标签原ID，不按显示名合并"),
            Derive(sym("label__label_display"), "label_display", "标签原显示文本"),
            Derive(nonnull("label__label_kind"), "has_label_match", "是否存在本次两类别的当前标签匹配"),
            Project(labelRelationColumns), regroup(labelRelationColumns)))
    }
}

val ledgerOwnershipColumns = listOf("raw_activity_rows", "account_count", "activity_reference_is_null", "activity_reference_is_empty", "activity_coordinate_groups")
val ledgerIntersectionColumns = ledgerOwnershipColumns.dropLast(1) + listOf("fee_ledger_is_missing", "adjustment_ledger_is_missing", "fee_ledger_raw_rows", "adjustment_ledger_raw_rows", "activity_coordinate_groups")
val ledgerOwnershipExpected: List<List<String?>> = listOf(
    listOf("2", "1", "false", "false", "1"),
    listOf("2", "2", "false", "false", "1"),
    listOf("1", "1", "false", "false", "3"))
val ledgerIntersectionExpected: List<List<String?>> = listOf(
    listOf("2", "1", "false", "false", "false", "false", "3", "2", "1"),
    listOf("2", "2", "false", "false", "false", "false", "1", "1", "1"),
    listOf("1", "1", "false", "false", "true", "true", null, null, "1"),
    listOf("1", "1", "false", "false", "false", "true", "1", null, "1"),
    listOf("1", "1", "false", "false", "true", "false", null, "2", "1"))
fun ledgerAttributionQuery(e: PopulationExample, question: String): Calculation.Scope {
    require(question in setOf("OWNERSHIP", "LEDGER_INTERSECTION"))
    val intersection = question == "LEDGER_INTERSECTION"
    val activity = Computations.stages(e.raw("ledger_activity", listOf("subject", "reference_key", "activity_day"), MemberCondition.All(listOf(
        condition("partition_key", ScalarValueType.TEXT, MemberCondition.Operator.GTE, "20000101"),
        condition("activity_day", ScalarValueType.TEXT, MemberCondition.Operator.BETWEEN, "20010101", "20010107")))), listOf(
        Derive(number("1"), "activity_row_marker", "每条原活动袋行贡献一次"),
        regroup(listOf("reference_key", "activity_day"), counted("activity_row_marker", "raw_activity_rows"), counted("subject", "account_count", true))))
    fun ledger(source: String): Calculation.Scope {
        val reference = if (source == "fee_ledger") "reference_text" else "reference_key"
        val rows = latestPartitionRows(e, source, listOf(reference, "ledger_day"),
            condition("ledger_day", ScalarValueType.TEXT, MemberCondition.Operator.BETWEEN, "20010101", "20010107"))
        val stages = mutableListOf<ValidatedOperation>()
        if (reference != "reference_key") stages += Derive(sym(reference), "reference_key", "沿原I64含义形成的TEXT引用")
        stages += Derive(sym("ledger_day"), "activity_day", "本账本业务日，不继承活动分区")
        stages += Derive(number("1"), "ledger_row_marker", "每条原账本袋行贡献一次")
        stages += regroup(listOf("reference_key", "activity_day"), counted("ledger_row_marker", "raw_rows"))
        return Computations.stages(rows, stages)
    }
    val inputs = linkedMapOf<String, Calculation.Scope>()
    val stages = mutableListOf<ValidatedOperation>()
    if (intersection) listOf("fee_ledger", "adjustment_ledger").forEach { source ->
        inputs[source] = ledger(source)
        stages += JoinInput(source, source, "LEFT", op(ResultOperator.AND,
            op(ResultOperator.EQ, sym("reference_key"), sym("${source}__reference_key")),
            op(ResultOperator.EQ, sym("activity_day"), sym("${source}__activity_day"))))
        stages += Derive(sym("${source}__raw_rows"), "${source}_raw_rows", "匹配本侧完成袋计数，缺组保NULL")
        stages += Derive(op(ResultOperator.IS_NULL, sym("${source}__raw_rows")), "${source}_is_missing", "仅以完成组的正COUNT判缺侧")
    }
    stages += Derive(op(ResultOperator.IS_NULL, sym("reference_key")), "activity_reference_is_null", "活动引用是否NULL")
    stages += Derive(op(ResultOperator.EQ, sym("reference_key"), text("")), "activity_reference_is_empty", "精确空串，不清洗或把NULL补成空串")
    stages += Derive(number("1"), "coordinate_marker", "每个实际引用日调查组一次")
    val columns = if (intersection) ledgerIntersectionColumns else ledgerOwnershipColumns
    stages += regroup(columns.dropLast(1), counted("coordinate_marker", "activity_coordinate_groups"))
    stages += Project(columns)
    return Computations.scope {
        inputs.forEach { (id, rows) -> input(id, rows, InputMode.RELATION) }
        Computations.stages(activity, stages)
    }
}

fun sourceRelationCases(e: PopulationExample, entry: PublishedQueryEntry): Map<String, ScriptValue> {
    fun expectRows(rows: List<Map<String, String?>>, columns: List<String>, expected: List<List<String?>>) {
        val actual = rows.map { row -> check(row.keys.toList() == columns) { "完整关系列不符：${row.keys}" }; columns.map { row.getValue(it) } }
        check(actual.size == expected.size && actual.toSet() == expected.toSet()) { "完整关系行不符：实际$actual；预期$expected" }
    }
    val labels = e.execute(entry, "多值标签按完整身份形成五列集合", labelRelationsQuery(e), QueryExecutionMode.SOURCE)
    expectRows(labels.rows, labelRelationColumns, labelRelationExpected)
    val ownership = e.execute(entry, "OWNERSHIP：引用日活动原袋行与不同账户归属", ledgerAttributionQuery(e, "OWNERSHIP"), QueryExecutionMode.SOURCE)
    expectRows(ownership.rows, ledgerOwnershipColumns, ledgerOwnershipExpected)
    val intersection = e.execute(entry, "LEDGER_INTERSECTION：两账本先完成再双LEFT", ledgerAttributionQuery(e, "LEDGER_INTERSECTION"), QueryExecutionMode.SOURCE)
    expectRows(intersection.rows, ledgerIntersectionColumns, ledgerIntersectionExpected)
    listOf(ownership to setOf("raw_activity_rows", "account_count", "activity_coordinate_groups"),
        intersection to setOf("raw_activity_rows", "account_count", "fee_ledger_raw_rows", "adjustment_ledger_raw_rows", "activity_coordinate_groups")).forEach { (saved, quantityNames) ->
        val quantityColumns = olap.results.read(ResultReadRequest(saved.result, 0, 1, null)).columns.filter { it.symbol.name in quantityNames }
        check(quantityColumns.map { it.symbol.name }.toSet() == quantityNames)
        quantityColumns.forEach { column ->
            val quantity = requireNotNull(column.symbol.quantity)
            check(quantity.unit == QueryQuantity.Fixed(MetricValueContract.ONE) && quantity.declaration.unit == count.unit &&
                quantity.declaration.arithmetic == count.arithmetic && quantity.declaration.result == count.result && quantity.declaration.overflow == count.overflow) {
                "保存的袋计数丢失ONE/Exact/I64/FAIL合同：${column.symbol.name}"
            }
        }
    }
    return mapOf("labelRelationsQuery" to ScriptValue.QueryReferenceValue(labels.query), "labelRelationsResult" to ScriptValue.ResultReferenceValue(labels.result),
        "ledgerOwnershipQuery" to ScriptValue.QueryReferenceValue(ownership.query), "ledgerOwnershipResult" to ScriptValue.ResultReferenceValue(ownership.result),
        "ledgerIntersectionQuery" to ScriptValue.QueryReferenceValue(intersection.query), "ledgerIntersectionResult" to ScriptValue.ResultReferenceValue(intersection.result))
}

fun ledgerBagColumns(families: List<String>) = listOf("subject", "activity_tuples", "activity_present") +
    families.flatMap { listOf("${it}_raw", "${it}_raw_present", "${it}_tuples", "${it}_tuple_present") }
val ledgerBagBothExpected = linkedMapOf(
    "A" to listOf("2", "1", "3", "1", "2", "1", "5", "1", "4", "1"),
    "B" to listOf("1", "1", "2", "1", "1", "1", "3", "1", "2", "1"),
    "C" to listOf("1", "1", null, null, null, null, null, null, null, null),
    "D" to listOf("1", "1", null, null, null, null, null, null, null, null),
    "" to listOf("1", "1", null, null, null, null, "1", "1", "1", "1"))

/** 账户归属先形成完整tuple；每账本原袋与其投影tuple分别完成，绝不相乘两份原袋。 */
fun ledgerBagQuery(e: PopulationExample, families: List<String>, accounts: List<String>, day: String): Calculation.Scope {
    require(families.isNotEmpty() && families.distinct() == families && families.all { it in setOf("r", "s") })
    require(accounts.isNotEmpty() && accounts.distinct() == accounts && day.matches(Regex("[0-9]{8}")))
    val activity = Computations.stages(e.raw("attributed_activity", condition = MemberCondition.All(listOf(
        condition("business_day", ScalarValueType.TEXT, MemberCondition.Operator.EQ, day),
        MemberCondition.Any(accounts.map { condition("subject", ScalarValueType.TEXT, MemberCondition.Operator.EQ, it) })))),
        listOf(regroup(listOf("subject", "document_ref", "business_day"))))
    val activityCounts = Computations.stages(activity, listOf(
        Derive(number("1"), "ownership_row", "每个完整账户/引用/业务日归属一次"),
        regroup(listOf("subject"), counted("ownership_row", "activity_tuples")),
        Derive(number("1"), "activity_present", "交易归属组真实存在")))
    val aligned = linkedMapOf("activity" to Computations.aligned(activityCounts, mapOf("subject" to "subject"),
        listOf("activity_tuples", "activity_present").map { Calculation.AlignedValue(it, it, Calculation.NoMatch.PRESERVE_NULL) }))
    families.forEach { family ->
        val tuple = if (family == "r") listOf("document_ref", "settlement_day", "flag", "amount_r", "amount_s")
            else listOf("document_ref", "settlement_day", "text_amount")
        val rows = e.raw("book_$family", tuple, MemberCondition.All(listOf(
            condition("is_current", ScalarValueType.BOOLEAN, MemberCondition.Operator.EQ, "true"),
            condition("settlement_day", ScalarValueType.TEXT, MemberCondition.Operator.EQ, day))))
        val attributed = Computations.scope {
            input("ownership", activity, InputMode.RELATION)
            Computations.stages(rows, listOf(JoinInput("ownership", "owned", "INNER", op(ResultOperator.AND,
                op(ResultOperator.EQ, sym("document_ref"), sym("owned__document_ref")),
                op(ResultOperator.EQ, sym("settlement_day"), sym("owned__business_day"))))))
        }
        val raw = Computations.stages(attributed, listOf(
            Derive(number("1"), "ledger_row", "金额或文本NULL不漏原袋行"),
            regroup(listOf("owned__subject"), counted("ledger_row", "${family}_raw")),
            Derive(number("1"), "${family}_raw_present", "本账本原袋有组")))
        val tuples = Computations.stages(attributed, listOf(
            regroup(listOf("owned__subject") + tuple),
            Derive(number("1"), "tuple_row", "完整投影tuple一次，数值保持数值"),
            regroup(listOf("owned__subject"), counted("tuple_row", "${family}_tuples")),
            Derive(number("1"), "${family}_tuple_present", "本账本tuple有组")))
        aligned["${family}_raw"] = Computations.aligned(raw, mapOf("subject" to "owned__subject"),
            listOf("${family}_raw", "${family}_raw_present").map { Calculation.AlignedValue(it, it, Calculation.NoMatch.PRESERVE_NULL) })
        aligned["${family}_tuples"] = Computations.aligned(tuples, mapOf("subject" to "owned__subject"),
            listOf("${family}_tuples", "${family}_tuple_present").map { Calculation.AlignedValue(it, it, Calculation.NoMatch.PRESERVE_NULL) })
    }
    return Computations.stages(Computations.align(aligned, listOf("subject"),
        Calculation.AlignmentPopulation(Calculation.PopulationKind.BASE, listOf("activity"))), listOf(Project(ledgerBagColumns(families))))
}

fun ledgerBagCases(e: PopulationExample, entry: PublishedQueryEntry): Map<String, ScriptValue> {
    val output = linkedMapOf<String, ScriptValue>()
    val completeColumns = ledgerBagColumns(listOf("r", "s")).drop(1)
    val expected = ledgerBagBothExpected.mapValues { (_, values) -> completeColumns.zip(values).toMap() }
    listOf("R" to listOf("r"), "S" to listOf("s"), "Both" to listOf("r", "s")).forEach { (name, families) ->
        val columns = ledgerBagColumns(families)
        val saved = e.execute(entry, "账户归属后账本${families.joinToString("+")}原袋与完整tuple", ledgerBagQuery(e, families, listOf("A", "B", "C", "D", "", "absent"), "20000102"), QueryExecutionMode.SOURCE)
        check(saved.rows.all { it.keys.toList() == columns })
        expect(saved.rows, expected.mapValues { (_, values) -> columns.drop(1).associateWith(values::getValue) })
        expectSavedQuantities(saved.result, columns.drop(1).associateWith { count })
        output["ledgerBag${name}Query"] = ScriptValue.QueryReferenceValue(saved.query)
        output["ledgerBag${name}Result"] = ScriptValue.ResultReferenceValue(saved.result)
    }
    return output
}

val completedPopulationColumns = listOf("subject", "period_uv", "period_nonempty", "daily_total", "observed_days", "payment_uv",
    "period_present", "daily_present", "payment_present", "daily_mean", "repeat_visits", "payment_per_visitor")
val completedPopulationExpected = linkedMapOf(
    "A" to listOf("2", "1", "2", "2", "3", "1", "1", "1", "0.285714285714285714", "1", "1.500000000000000000"),
    "B" to listOf("1", "0", "0", "1", "0", "1", "1", "1", "0.000000000000000000", "0", "0.000000000000000000"),
    "C" to listOf(null, null, null, null, "1", null, null, "1", null, null, null),
    "D" to listOf("0", "0", "0", "1", "1", "1", "1", "1", "0.000000000000000000", "0", null),
    "E" to listOf("1", "1", "1", "1", null, "1", "1", null, "0.142857142857142857", "0", null))

/** 三个独立完成出口按UNION人口汇合，CORE只计算最后的精确商与差。 */
fun completedPopulationQuery(e: PopulationExample, from: String, to: String, requestedDays: Int): Calculation.Scope {
    require(from.matches(Regex("[0-9]{8}")) && to.matches(Regex("[0-9]{8}")) && from <= to && requestedDays > 0)
    val directorySource = e.members.getValue("observer_directory")
    // 目录原袋不先排NULL账户；LEFT后按实际归属排除，未将entry_ref声明为唯一。
    val directory = Computations.members("observer_directory", MemberRef(directorySource.id, directorySource.subjectAreaVersion), emptyMap(),
        listOf(Calculation.MemberProjection("entry_ref", "entry_ref", null), Calculation.MemberProjection("subject", "subject", Calculation.PublishedDimension(e.axis.reference))),
        condition = condition("is_current", ScalarValueType.BOOLEAN, MemberCondition.Operator.EQ, "true"))
    val visits = e.raw("observer_visits", listOf("entry_ref", "visitor_ref", "day"), MemberCondition.All(listOf(
        condition("day", ScalarValueType.TEXT, MemberCondition.Operator.BETWEEN, from, to),
        condition("eligible", ScalarValueType.BOOLEAN, MemberCondition.Operator.EQ, "true"))))
    val attributed = Computations.scope {
        input("directory", directory, InputMode.RELATION)
        Computations.stages(visits, listOf(JoinInput("directory", "owned", "LEFT", op(ResultOperator.EQ, sym("entry_ref"), sym("owned__entry_ref"))),
            PostFilter(nonnull("owned__subject")),
            Derive(choose(op(ResultOperator.NE, sym("visitor_ref"), text("")), sym("visitor_ref")), "nonempty_visitor", "仅每日/非空期间量排空串，保NULL日期组")))
    }
    val period = Computations.stages(attributed, listOf(regroup(listOf("owned__subject"), counted("visitor_ref", "period_uv", true),
        counted("nonempty_visitor", "period_nonempty", true)), Derive(number("1"), "period_present", "期间访问组存在")))
    val daily = Computations.stages(attributed, listOf(regroup(listOf("owned__subject", "day"), counted("nonempty_visitor", "daily_uv", true)),
        regroup(listOf("owned__subject"), sum("daily_uv", "daily_total", count), counted("day", "observed_days")),
        Derive(number("1"), "daily_present", "有访问记录的日期组存在，不补日历")))
    val paymentRows = e.raw("observer_payments", condition = condition("day", ScalarValueType.TEXT, MemberCondition.Operator.BETWEEN, from, to))
    val payment = Computations.stages(paymentRows, listOf(
        Derive(choose(op(ResultOperator.EQ, sym("initial_flag"), text("Y")), sym("buyer_ref")), "selected_buyer", "只选择Y，不筛掉原交易组"),
        regroup(listOf("subject"), counted("selected_buyer", "payment_uv", true)), Derive(number("1"), "payment_present", "原交易组存在，计数可为零")))
    val completed = Computations.align(linkedMapOf(
        "period" to Computations.aligned(period, mapOf("subject" to "owned__subject"),
            listOf("period_uv", "period_nonempty", "period_present").map { Calculation.AlignedValue(it, it, Calculation.NoMatch.PRESERVE_NULL) }),
        "daily" to Computations.aligned(daily, mapOf("subject" to "owned__subject"),
            listOf("daily_total", "observed_days", "daily_present").map { Calculation.AlignedValue(it, it, Calculation.NoMatch.PRESERVE_NULL) }),
        "payment" to Computations.aligned(payment, mapOf("subject" to "subject"),
            listOf("payment_uv", "payment_present").map { Calculation.AlignedValue(it, it, Calculation.NoMatch.PRESERVE_NULL) })),
        listOf("subject"), Calculation.AlignmentPopulation(Calculation.PopulationKind.UNION, listOf("period", "daily", "payment")))
    val finalContract = exact(18, MetricValueContract.Rounding.HALF_UP)
    return Computations.stages(completed, listOf(
        Derive(ResultNumeric(op(ResultOperator.DIVIDE, sym("daily_total"), number(requestedDays.toString(), daysContract)), finalContract, null), "daily_mean", "只在最终商按明确请求日数量化"),
        Derive(ResultNumeric(op(ResultOperator.SUBTRACT, sym("daily_total"), sym("period_nonempty")), count, null), "repeat_visits", "跨日重复贡献"),
        Derive(ResultNumeric(choose(op(ResultOperator.AND, nonnull("period_uv"), op(ResultOperator.NE, sym("period_uv"), number("0"))),
            op(ResultOperator.DIVIDE, sym("payment_uv"), sym("period_uv"))), finalContract, null), "payment_per_visitor", "不同标识量之比允许大于1，零/缺分母为NULL"),
        Project(completedPopulationColumns)))
}

fun completedPopulationCase(e: PopulationExample, entry: PublishedQueryEntry): Map<String, ScriptValue> {
    val saved = e.execute(entry, "三域完整人口与明确七日精确终算", completedPopulationQuery(e, "20000101", "20000107", 7), QueryExecutionMode.CORE_FINALIZATION)
    check(saved.rows.all { it.keys.toList() == completedPopulationColumns })
    expect(saved.rows, completedPopulationExpected.mapValues { (_, values) -> completedPopulationColumns.drop(1).zip(values).toMap() })
    expectSavedQuantities(saved.result, completedPopulationColumns.drop(1).associateWith { name ->
        if (name in setOf("daily_mean", "payment_per_visitor")) exact(18, MetricValueContract.Rounding.HALF_UP) else count
    })
    return mapOf("completedPopulationQuery" to ScriptValue.QueryReferenceValue(saved.query), "completedPopulationResult" to ScriptValue.ResultReferenceValue(saved.result))
}

fun independentQuery(e: PopulationExample): Calculation.Scope {
    fun selected(name: String) = e.raw(name, condition = MemberCondition.All(listOf(condition("current_partition", ScalarValueType.BOOLEAN, MemberCondition.Operator.EQ, "true"), condition("clock", ScalarValueType.TEXT, MemberCondition.Operator.BETWEEN, "20000101", "20000102"))))
    val a = Computations.stages(selected("fact_a"), listOf(Derive(choose(sym("eligible"), sym("amount"), number("0", floating("a-value"))), "conditional", "资格TRUE保NULL，FALSE/UNKNOWN零"), regroup(listOf("subject"), sum("amount", "a", floating("a-value")), sum("conditional", "conditional_a", floating("a-value")), counted("subject", "a_rows")), Derive(number("1"), "a_present", "有组标志")))
    val b = Computations.stages(selected("fact_b"), listOf(Derive(ResultNumeric(op(ResultOperator.SUBTRACT, sym("amount"), sym("deduction")), floating("b-value"), null), "net", "先逐行相减，任一分量NULL则本行NULL"), regroup(listOf("subject"), sum("net", "b", floating("b-value")), counted("subject", "b_rows"), counted("net", "known_b")), Derive(number("1"), "b_present", "全NULL组仍存在")))
    return Computations.align(linkedMapOf(
        "a" to Computations.aligned(a, mapOf("subject" to "subject"), listOf("a", "conditional_a", "a_rows", "a_present").map { Calculation.AlignedValue(it, it, Calculation.NoMatch.PRESERVE_NULL) }),
        "b" to Computations.aligned(b, mapOf("subject" to "subject"), listOf("b", "b_rows", "known_b", "b_present").map { Calculation.AlignedValue(it, it, Calculation.NoMatch.PRESERVE_NULL) })),
        listOf("subject"), Calculation.AlignmentPopulation(Calculation.PopulationKind.UNION, listOf("a", "b")))
}
val independentExpected = linkedMapOf(
    "A" to mapOf("a" to "9", "conditional_a" to "0", "b" to null, "a_present" to "1", "b_present" to "1", "known_b" to "0"),
    "B" to mapOf("a" to "5", "conditional_a" to "5", "b" to null, "a_present" to "1", "b_present" to null, "b_rows" to null),
    "C" to mapOf("a" to null, "conditional_a" to null, "b" to "5", "a_present" to null, "b_present" to "1", "b_rows" to "2"))

fun captionQuery(e: PopulationExample): Calculation.Scope {
    val selected = e.raw("catalog", listOf("subject", "item", "caption", "caption_accepted"), condition("current_partition", ScalarValueType.BOOLEAN, MemberCondition.Operator.EQ, "true"))
    val counts = Computations.stages(selected, listOf(Derive(choose(sym("caption_accepted"), number("1", count)), "accepted_row", "仅完整谓词TRUE贡献"), Derive(choose(op(ResultOperator.NOT, sym("caption_accepted")), number("1", count)), "rejected_row", "完整谓词FALSE贡献"), Derive(choose(op(ResultOperator.IS_NULL, sym("caption_accepted")), number("1", count)), "unknown_row", "NULL保UNKNOWN"), regroup(listOf("subject"), counted("subject", "catalog_rows"), counted("accepted_row", "true_rows"), counted("rejected_row", "false_rows"), counted("unknown_row", "unknown_rows"))))
    // 完成关系每subject一个键；回连原目录袋保文本、NULL与重复，不把item声明成唯一键。
    return Computations.scope {
        input("counts", counts, InputMode.RELATION)
        Computations.stages(selected, listOf(
            JoinInput("counts", "counts", "LEFT", op(ResultOperator.EQ, sym("subject"), sym("counts__subject"))),
            Derive(op(ResultOperator.EQ, sym("caption"), text("山川🦊")), "caption_is_mountain_fox", "完整TEXT与非BMP typed literal精确比较"),
            Project(listOf("subject", "item", "caption", "caption_accepted", "caption_is_mountain_fox", "counts__catalog_rows", "counts__true_rows", "counts__false_rows", "counts__unknown_rows"))))
    }
}
fun expectCaptions(rows: List<Map<String, String?>>, changed: Boolean) {
    fun row(subject: String, item: String, caption: String?, accepted: String?, mountainFox: String?, total: String, yes: String, no: String, unknown: String) = mapOf(
        "subject" to subject, "item" to item, "caption" to caption, "caption_accepted" to accepted, "caption_is_mountain_fox" to mountainFox,
        "counts__catalog_rows" to total, "counts__true_rows" to yes, "counts__false_rows" to no, "counts__unknown_rows" to unknown)
    val expected = if (changed) listOf(
        row("A", "a1", "山川样本🦊", "false", "false", "2", "0", "1", "1"), row("A", "a2", null, null, null, "2", "0", "1", "1"),
        row("B", "b1", "plain", "true", "false", "2", "2", "0", "0"), row("B", "b1", "plain", "true", "false", "2", "2", "0", "0"),
        row("C", "c1", "plain", "true", "false", "1", "1", "0", "0"), row("D", "d1", "plain", "true", "false", "1", "1", "0", "0"), row("", "empty", "山川", "true", "false", "1", "1", "0", "0")) else listOf(
        row("A", "a1", "山川🦊", "true", "true", "2", "1", "1", "0"), row("A", "a2", "示例样本", "false", "false", "2", "1", "1", "0"),
        row("B", "b1", null, null, null, "2", "0", "0", "2"), row("B", "b1", null, null, null, "2", "0", "0", "2"),
        row("C", "c1", "plain", "true", "false", "1", "1", "0", "0"), row("D", "d1", "plain", "true", "false", "1", "1", "0", "0"), row("", "empty", "山川", "true", "false", "1", "1", "0", "0"))
    check(rows.groupingBy { it }.eachCount() == expected.groupingBy { it }.eachCount()) { "目录文本、BOOLEAN三值或原始重复袋不符：$rows；预期$expected" }
}

fun dailyBasis(e: PopulationExample): Calculation.Scope {
    val input = e.raw("participation")
    val nonempty = op(ResultOperator.NE, sym("actor"), text(""))
    val period = Computations.stages(input, listOf(Derive(choose(nonempty, sym("actor")), "nonempty", "仅本测量排空串"), regroup(listOf("subject"), counted("actor", "period", true), counted("nonempty", "period_nonempty", true))))
    val daily = Computations.stages(input, listOf(Derive(choose(nonempty, sym("actor")), "nonempty", "保有日组的零计数"), regroup(listOf("subject", "day"), counted("nonempty", "daily", true)), regroup(listOf("subject"), sum("daily", "days_total", count))))
    // 三条互相独立的资格分支，先完成完整日/标识元组，不影响日总体测量。
    val predicates = linkedMapOf("eligible_total" to sym("eligible"), "ineligible_total" to op(ResultOperator.NOT, sym("eligible")), "unknown_total" to op(ResultOperator.IS_NULL, sym("eligible")))
    val qualified = predicates.mapValues { (_, predicate) -> Computations.stages(input, listOf(PostFilter(op(ResultOperator.AND, predicate, nonempty)), regroup(listOf("subject", "day", "actor")), regroup(listOf("subject"), counted("actor", "quantity")))) }
    val aligned = Computations.align(linkedMapOf(
        "period" to Computations.aligned(period, mapOf("subject" to "subject"), listOf("period", "period_nonempty").map { Calculation.AlignedValue(it, it, Calculation.NoMatch.PRESERVE_NULL) }),
        "daily" to Computations.aligned(daily, mapOf("subject" to "subject"), listOf(Calculation.AlignedValue("days_total", "days_total", Calculation.NoMatch.PRESERVE_NULL)))),
        listOf("subject"), Calculation.AlignmentPopulation(Calculation.PopulationKind.BASE, listOf("period")))
    val stages = mutableListOf<ValidatedOperation>()
    qualified.forEach { (name, _) -> stages += JoinInput(name, name, "LEFT", op(ResultOperator.EQ, sym("subject"), sym("${name}__subject")))
        stages += Derive(choose(op(ResultOperator.IS_NULL, sym("${name}__subject")), number("0"), sym("${name}__quantity")), name, "只为缺资格组给零") }
    stages += Project(listOf("subject", "period", "period_nonempty", "days_total") + predicates.keys)
    return Computations.scope {
        qualified.forEach { (name, rows) -> input(name, rows, InputMode.RELATION) }
        Computations.stages(aligned, stages)
    }
}
val daysContract = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(), MetricValueContract.Decimal(20, 1, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
val savedMeanContract = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(), MetricValueContract.Decimal(25, 6, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
fun dailyTemplate(subject: Calculation.DimensionSlot): MetricCalculation {
    val days = Calculation.Parameter(Calculation.ParameterKind.SCALAR, "NUMBER", emptyList(), null, daysContract)
    return MetricCalculation(Calculation(listOf(subject), emptyList(), emptyList(), listOf(Calculation.RelationSlot("completed", listOf(
        SetOutputContract("subject", SetOutputContract.DimensionSlot("subject")), SetOutputContract("quantity", SetOutputContract.Quantity(count))), listOf("subject"))), mapOf("days" to days),
        Computations.stages(Computations.scope(Calculation.Relation("completed")), listOf(Derive(ResultNumeric(op(ResultOperator.DIVIDE, sym("quantity"), ResultArgument("days", ScalarValueType.NUMBER)), savedMeanContract, null), "value", "完成数量除明确请求天数"), Project(listOf("subject", "value"))))), "value", listOf("subject"), savedMeanContract)
}
val dailyComparisons = listOf("days_total", "eligible_total", "ineligible_total", "unknown_total")
val dailyComparisonExpected = mapOf(
    "A" to mapOf("days_total_average" to "1.5", "eligible_total_average" to "1", "ineligible_total_average" to "0.5", "unknown_total_average" to "0"),
    "B" to mapOf("days_total_average" to "0", "eligible_total_average" to "0", "ineligible_total_average" to "0", "unknown_total_average" to "0"))
fun dailyComparisonTemplate(subject: Calculation.DimensionSlot, mean: Calculation): Calculation {
    val completed = mean.relationSlots.single { it.name == "completed" }
    val branches = dailyComparisons.associateWith { name ->
        val average = Computations.compute(mean, Calculation.ParentSlots(mapOf("subject" to "subject"), emptyMap(), emptyMap(), mapOf("completed" to name)),
            mapOf("days" to Calculation.Argument("days")))
        Computations.aligned(average, mapOf("subject" to "subject"), listOf(Calculation.AlignedValue("value", "${name}_average", Calculation.NoMatch.PRESERVE_NULL)))
    }
    return Calculation(listOf(subject), emptyList(), emptyList(), dailyComparisons.map { Calculation.RelationSlot(it, completed.outputs, completed.keys) }, mean.parameters,
        Computations.align(branches, listOf("subject"), Calculation.AlignmentPopulation(Calculation.PopulationKind.BASE, listOf("days_total"))))
}
fun dailyTemplateQuery(e: PopulationExample, template: Calculation, basis: Calculation.Scope, quantities: Map<String, String>): Calculation.Scope {
    val bindings = CalculationBindings(mapOf("subject" to e.axis), emptyMap(), emptyMap(), emptyMap(),
        template.relationSlots.associate { it.name to CalculationBindings.RelationParameter() })
    return Computations.scope {
        Computations.compute(template, bindings, mapOf("days" to Calculation.Scalar(ScalarValueType.NUMBER, "2")),
            relations = quantities.mapValues { (_, column) -> relation("basis", basis, mapOf("subject" to "subject", "quantity" to column)) })
    }
}
fun saveDailyComparison(e: PopulationExample, template: Calculation): MemberSourceRecord {
    val columns = linkedMapOf<String, MemberSourceDefinition.Output>("subject" to MemberSourceDefinition.ComputedDimensionOutput("对象", "subject", ScalarValueType.TEXT, "subject", "subject", null))
    dailyComparisons.forEach { name -> columns["${name}_average"] = MemberSourceDefinition.ComputedPropertyOutput(name, name, ScalarValueType.NUMBER, "${name}_average", savedMeanContract) }
    val definition = MemberSourceDefinition.Computed(template, MemberResult("daily_comparison", "daily_comparison", MemberShape.RECORDS, listOf("subject"), columns,
        emptyMap(), null, emptyMap(), "按完整对象比较总体与TRUE/FALSE/UNKNOWN资格日均；各分支接收独立完成计数"))
    val shared = e.governance.createMemberDefinition(MemberDefinitionCreateRequest("daily_comparison", "每日资格比较", definition, definition.result.description, e.actor, e.actor))
    e.activate("MEMBER_DEFINITION", shared.id, shared.currentVersion)
    val adopted = e.governance.adoptMemberDefinition(MemberDefinitionAdoptRequest(e.subject.id, e.subject.currentVersion, "daily_comparison", "每日资格比较", shared.reference,
        CalculationBindings(mapOf("subject" to e.axis), emptyMap(), emptyMap(), emptyMap(), dailyComparisons.associateWith { CalculationBindings.RelationParameter() }), emptyMap(), e.actor))
    e.members["daily_comparison"] = adopted; e.outputs["daily_comparison"] = columns; e.filters["daily_comparison"] = emptyMap()
    return adopted
}
fun publishedDailyComparison(e: PopulationExample, member: MemberSourceRecord, basis: Calculation.Scope): Calculation.Scope = Computations.scope {
    Computations.members("daily_comparison", MemberRef(member.id, member.subjectAreaVersion), mapOf("days" to Calculation.Scalar(ScalarValueType.NUMBER, "2")),
        listOf(Calculation.MemberProjection("subject", "subject", Calculation.PublishedDimension(e.axis.reference))) +
            dailyComparisons.map { Calculation.MemberProjection("${it}_average", "${it}_average", null) },
        relations = dailyComparisons.associateWith { column -> relation("basis", basis, mapOf("subject" to "subject", "quantity" to column)) })
}
fun savedDailyQuery(e: PopulationExample, metric: SubjectAreaMetricRefRecord, basis: Calculation.Scope): Calculation.Scope = Computations.scope {
    val calls = listOf("days_total", "eligible_total", "ineligible_total", "unknown_total").map { column ->
        Computations.invoke(metric.instance, column, outputAlias = "${column}_average", arguments = mapOf("days" to Calculation.Scalar(ScalarValueType.NUMBER, "2")),
            relations = mapOf("completed" to relation("basis", basis, mapOf("subject" to "subject", "quantity" to column)))) }
    Computations.measure(calls, listOf(Calculation.PublishedDimension(e.axis.reference)), RowPopulationContract.calls(calls.map { it.callId }))
}
fun exactDailyQuery(basis: Calculation.Scope) = Computations.stages(basis, listOf(
    Derive(ResultNumeric(op(ResultOperator.DIVIDE, sym("days_total"), number("19", daysContract)), exact(18, MetricValueContract.Rounding.HALF_UP), null), "mean", "显式十九日，不用活跃日期数"),
    Derive(ResultNumeric(op(ResultOperator.SUBTRACT, sym("days_total"), sym("period_nonempty")), count, null), "repeat_days", "跨日重复贡献"), Project(listOf("subject", "days_total", "period", "period_nonempty", "mean", "repeat_days"))))

fun scaledQuery(e: PopulationExample): Calculation.Scope {
    val hundred = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(), MetricValueContract.Decimal(4, 1, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
    fun scaled(input: String, source: String, target: String) = ResultNumeric(op(ResultOperator.DIVIDE, sym(input), number("100.0", hundred)), floating(target, mapOf("scale" to MetricValueContract.Scale(MetricValueContract.BinaryOperator.DIVIDE, unit(source), unit(target), "100.0"))), "scale")
    val ratio = floating("gross-per-actor", mapOf("divide" to MetricValueContract.FixedBinary(MetricValueContract.BinaryOperator.DIVIDE, unit("gross-report"), MetricValueContract.ONE, unit("gross-per-actor"))))
    return Computations.stages(e.raw("scaled"), listOf(Derive(scaled("gross", "gross-native", "gross-report"), "row_gross", "逐行原生浮点尺度转换"), Derive(scaled("adjustment", "adjustment-native", "adjustment-report"), "row_adjustment", "正式逻辑DOUBLE出口再逐行尺度转换"), regroup(listOf("subject"), sum("row_gross", "gross_total", floating("gross-report")), sum("row_adjustment", "adjustment_total", floating("adjustment-report")),
            sum("quantity", "quantity_total", floating("quantity")), counted("actor", "actors", true), counted("record_ref", "references", true),
            ResultAggregate("label", ResultAggregation.Maximum(ResultAggregation.NullPolicy.IGNORE), "maximum_label", "TEXT最大值不是时间赢家")), Derive(sym("maximum_label"), "label", "完成TEXT最大值"), Derive(ResultNumeric(op(ResultOperator.DIVIDE, sym("gross_total"), sym("actors")), ratio, "divide"), "ratio", "完成金额除完成去重数，保无guard政策"), Project(listOf("subject", "gross_total", "adjustment_total", "quantity_total", "references", "actors", "ratio", "label"))))
}

data class RatioSpec(val name: String, val denominator: String, val positiveOnly: Boolean) {
    val numeratorContract get() = floating("attribute-$name")
    val denominatorContract get() = if (denominator == "e") floating("attribute-e") else floating(denominator.replace('_', '-'))
    val resultContract get() = floating("ratio-$name", mapOf("divide" to MetricValueContract.FixedBinary(MetricValueContract.BinaryOperator.DIVIDE,
        (numeratorContract.unit as MetricValueContract.Fixed).identity, (denominatorContract.unit as MetricValueContract.Fixed).identity, unit("ratio-$name"))))
}
val ratios = listOf(RatioSpec("a", "base_a", false), RatioSpec("b", "base_a", false), RatioSpec("c", "base_b", false), RatioSpec("d", "base_b", false), RatioSpec("e", "base_a", true), RatioSpec("f", "e", true))
fun ratioAlgorithm(e: PopulationExample, spec: RatioSpec): SubjectAreaMetricRefRecord {
    val guard = if (spec.positiveOnly) op(ResultOperator.GT, sym("denominator"), number("0", spec.denominatorContract)) else op(ResultOperator.AND, nonnull("denominator"), op(ResultOperator.NE, sym("denominator"), number("0", spec.denominatorContract)))
    val value = choose(guard, ResultNumeric(op(ResultOperator.DIVIDE, sym("numerator"), sym("denominator")), spec.resultContract, "divide"), if (spec.positiveOnly) number("0", spec.resultContract) else ResultLiteral(null))
    return e.savedAlgorithm("ratio_${spec.name}", MetricCalculation(Calculation(listOf(e.slot), emptyList(), emptyList(), listOf(Calculation.RelationSlot("basis", listOf(
        SetOutputContract("subject", SetOutputContract.DimensionSlot("subject")), SetOutputContract("numerator", SetOutputContract.Quantity(spec.numeratorContract)), SetOutputContract("denominator", SetOutputContract.Quantity(spec.denominatorContract))), listOf("subject"))), emptyMap(),
        Computations.stages(Computations.scope(Calculation.Relation("basis")), listOf(Derive(value, "value", "保存算法拥有guard与除法"), Project(listOf("subject", "value"))))), "value", listOf("subject"), spec.resultContract))
}
data class AttributeBasis(val input: Calculation.Scope, val quantities: Map<String, MetricValueContract>)
fun inlineAttributeQuery(e: PopulationExample): Pair<Calculation.Scope, Map<String, MetricValueContract>> {
    data class Contribution(val field: String, val eligibility: String, val negate: Boolean = false) { val output get() = "amount_$field" }
    val contributions = listOf(Contribution("a", "flag_b_count_a"), Contribution("b", "flag_b_count_b"), Contribution("c", "flag_b_count_b"),
        Contribution("d", "flag_b_count_b"), Contribution("e", "state_c_y"), Contribution("f", "state_c_y"), Contribution("g", "count_b_one", true),
        Contribution("h", "flag_a_count_a"), Contribution("i", "flag_a_count_a"), Contribution("j", "flag_a_count_a"),
        Contribution("k", "flag_a_count_b"), Contribution("l", "flag_a_count_b"), Contribution("m", "flag_a_count_b"))
    fun declared(member: String, field: String) = requireNotNull(MemberSourceDefinition.rootValueContract(e.outputs.getValue(member).getValue(field)))
    val baseQuantities = linkedMapOf("base_a" to declared("bases", "base_a"), "base_b" to declared("bases", "base_b"), "source_rows" to count)
    val original = e.raw("bases")
    val bases = Computations.stages(original, listOf(regroup(listOf("subject"), sum("base_a", "sum_base_a", baseQuantities.getValue("base_a")),
        sum("base_b", "sum_base_b", baseQuantities.getValue("base_b")), counted("subject", "source_rows")), Derive(sym("sum_base_a"), "base_a", "未关联属性的独立基数"), Derive(sym("sum_base_b"), "base_b", "未关联属性的独立基数"), Project(listOf("subject", "base_a", "base_b", "source_rows"))))
    val countA = op(ResultOperator.EQ, sym("count_a"), number("1", count))
    val countB = op(ResultOperator.EQ, sym("count_b"), number("1", count))
    val predicates = linkedMapOf("flag_b_count_a" to op(ResultOperator.AND, sym("at__flag_b"), countA),
        "flag_b_count_b" to op(ResultOperator.AND, sym("at__flag_b"), countB), "state_c_y" to op(ResultOperator.EQ, sym("state_c"), text("Y")),
        "count_b_one" to countB, "flag_a_count_a" to op(ResultOperator.AND, sym("at__flag_a"), countA),
        "flag_a_count_b" to op(ResultOperator.AND, sym("at__flag_a"), countB))
    val stages = mutableListOf<ValidatedOperation>(JoinInput("attributes", "at", "LEFT", op(ResultOperator.EQ, sym("record_ref"), sym("at__record_ref"))))
    val aggregates = mutableListOf(counted("subject", "bag_rows"), counted("at__record_ref", "matched_rows"), counted("at__partition_day", "attribute_partitions", true))
    val bagQuantities = linkedMapOf("bag_rows" to count, "matched_rows" to count, "attribute_partitions" to count)
    predicates.forEach { (name, predicate) ->
        stages += Derive(predicate, "eligible_$name", "完整三值资格只决定本项CASE")
        listOf("true" to sym("eligible_$name"), "false" to op(ResultOperator.NOT, sym("eligible_$name")),
            "unknown" to op(ResultOperator.IS_NULL, sym("eligible_$name"))).forEach { (state, expression) ->
            val output = "${name}_${state}_rows"
            stages += Derive(choose(expression, number("1", count)), "row_$output", "LEFT袋中完整谓词的状态")
            aggregates += counted("row_$output", output); bagQuantities[output] = count
        }
    }
    val oneFloat = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.NativeFloating(), MetricValueContract.BinaryFloat(64), emptyMap(), MetricValueContract.Overflow.FAIL)
    contributions.forEach { spec ->
        val contract = declared("attributes", spec.field)
        val raw = sym("at__${spec.field}")
        val value = if (spec.negate) {
            val identity = (contract.unit as MetricValueContract.Fixed).identity
            val signed = MetricValueContract(contract.unit, contract.arithmetic, contract.result,
                mapOf("sign" to MetricValueContract.FixedBinary(MetricValueContract.BinaryOperator.MULTIPLY, identity, MetricValueContract.ONE, identity)), contract.overflow)
            ResultNumeric(op(ResultOperator.MULTIPLY, raw, number("-1.0", oneFloat)), signed, "sign")
        } else raw
        stages += Derive(choose(sym("eligible_${spec.eligibility}"), value, number("0.0", contract)), "row_${spec.output}", "TRUE取原值含NULL；FALSE或UNKNOWN取同单位0")
        aggregates += sum("row_${spec.output}", spec.output, contract); bagQuantities[spec.output] = contract
        val nullOutput = "true_null_${spec.output}"
        stages += Derive(choose(op(ResultOperator.AND, sym("eligible_${spec.eligibility}"), op(ResultOperator.IS_NULL, raw)), number("1", count)), "row_$nullOutput", "本项TRUE且原值NULL")
        aggregates += counted("row_$nullOutput", nullOutput); bagQuantities[nullOutput] = count
    }
    stages += Regroup(ResultGrouping(listOf("subject"), aggregates, null))
    val bag = Computations.scope {
        input("attributes", e.raw("attributes"), InputMode.RELATION)
        Computations.stages(original, stages)
    }
    val aligned = Computations.align(linkedMapOf(
        "bases" to Computations.aligned(bases, mapOf("subject" to "subject"), baseQuantities.keys.map { Calculation.AlignedValue(it, it, Calculation.NoMatch.PRESERVE_NULL) }),
        "contributions" to Computations.aligned(bag, mapOf("subject" to "subject"), bagQuantities.keys.map { Calculation.AlignedValue(it, it, Calculation.NoMatch.PRESERVE_NULL) })),
        listOf("subject"), Calculation.AlignmentPopulation(Calculation.PopulationKind.BASE, listOf("bases")))
    data class Ratio(val name: String, val numerator: String, val denominator: String, val positiveOnly: Boolean)
    val ratios = listOf(Ratio("ratio_a", "amount_h", "base_a", false), Ratio("ratio_b", "amount_i", "base_a", false),
        Ratio("ratio_c", "amount_k", "base_b", false), Ratio("ratio_d", "amount_l", "base_b", false),
        Ratio("ratio_e", "amount_a", "base_a", true), Ratio("ratio_f", "amount_c", "amount_a", true))
    val quantities = (baseQuantities + bagQuantities).toMutableMap()
    val finalStages = mutableListOf<ValidatedOperation>()
    ratios.forEach { spec ->
        val numerator = quantities.getValue(spec.numerator); val denominator = quantities.getValue(spec.denominator)
        val result = floating("inline-${spec.name}", mapOf("divide" to MetricValueContract.FixedBinary(MetricValueContract.BinaryOperator.DIVIDE,
            (numerator.unit as MetricValueContract.Fixed).identity, (denominator.unit as MetricValueContract.Fixed).identity, unit("inline-${spec.name}"))))
        val guard = if (spec.positiveOnly) op(ResultOperator.GT, sym(spec.denominator), number("0.0", denominator)) else
            op(ResultOperator.AND, nonnull(spec.denominator), op(ResultOperator.NE, sym(spec.denominator), number("0.0", denominator)))
        finalStages += Derive(choose(guard, ResultNumeric(op(ResultOperator.DIVIDE, sym(spec.numerator), sym(spec.denominator)), result, "divide"),
            if (spec.positiveOnly) number("0.0", result) else ResultLiteral(null)), spec.name, "本Query拥有原生商和guard，合法分母下保NULL分子")
        quantities[spec.name] = result
    }
    finalStages += Derive(ResultNumeric(op(ResultOperator.SUBTRACT, sym("bag_rows"), sym("matched_rows")), count, null), "unmatched_rows", "LEFT未匹配行")
    finalStages += Derive(ResultNumeric(op(ResultOperator.SUBTRACT, sym("bag_rows"), sym("source_rows")), count, null), "extra_rows", "关联净扩行，不是记录身份或版本数")
    quantities["unmatched_rows"] = count; quantities["extra_rows"] = count
    val outputs = contributions.map { it.output } + ratios.map { it.name } + listOf("base_a", "base_b", "source_rows", "bag_rows", "matched_rows", "unmatched_rows", "extra_rows", "attribute_partitions") +
        predicates.keys.flatMap { name -> listOf("${name}_true_rows", "${name}_false_rows", "${name}_unknown_rows") } + contributions.map { "true_null_${it.output}" }
    finalStages += Project(listOf("subject") + outputs)
    return Computations.stages(aligned, finalStages) to outputs.associateWith { quantities.getValue(it) }
}

fun inlineAttributeExpected(): Map<String, Map<String, String?>> {
    // 五条独立主记录、六条LEFT行的手算结果；不调用作者表达式生成预期。
    val amountNames = listOf("amount_a", "amount_b", "amount_c", "amount_d", "amount_e", "amount_f", "amount_g", "amount_h", "amount_i", "amount_j", "amount_k", "amount_l", "amount_m")
    val ratioNames = listOf("ratio_a", "ratio_b", "ratio_c", "ratio_d", "ratio_e", "ratio_f")
    val observationNames = listOf("base_a", "base_b", "source_rows", "bag_rows", "matched_rows", "unmatched_rows", "extra_rows", "attribute_partitions")
    val amounts = linkedMapOf("A" to listOf("10", "0", "3", "4", "14", "16", "-5", "30", null, "5", "10", "12", "14"),
        "B" to listOf("8", null, null, null, null, null, null, "5", null, null, null, null, null),
        "C" to listOf("5", "1", "2", "3", "0", "0", "-4", "2", "3", "4", "1", "2", "3"),
        "D" to listOf("0", "0", "0", "0", "0", "0", "-5", "0", "0", "0", "0", "0", "0"),
        "E" to listOf("0", "0", "0", "0", null, null, null, "0", "0", "0", "0", "0", "0"))
    val ratios = mapOf("A" to listOf("0.3", null, "0.2", "0.24", "0.1", "0.3"), "B" to listOf("0.1", null, null, null, "0.16", null),
        "C" to listOf("-0.2", "-0.3", "-0.2", "-0.4", "0", "0.4"), "D" to listOf(null, null, null, null, "0", "0"), "E" to listOf("0", "0", "0", "0", "0", "0"))
    val observations = mapOf("A" to listOf("100", "50", "1", "2", "2", "0", "1", "2"), "B" to listOf("50", "25", "1", "1", "1", "0", "0", "1"),
        "C" to listOf("-10", "-5", "1", "1", "1", "0", "0", "1"), "D" to listOf("0", "0", "1", "1", "1", "0", "0", "1"), "E" to listOf("20", "10", "1", "1", "0", "1", "0", "0"))
    val predicateNames = listOf("flag_b_count_a", "flag_b_count_b", "state_c_y", "count_b_one", "flag_a_count_a", "flag_a_count_b")
    val states = mapOf("A" to listOf(1,1,0, 1,1,0, 2,0,0, 2,0,0, 2,0,0, 2,0,0), "B" to listOf(1,0,0, 1,0,0, 1,0,0, 1,0,0, 1,0,0, 1,0,0),
        "C" to listOf(1,0,0, 1,0,0, 0,1,0, 1,0,0, 1,0,0, 1,0,0), "D" to listOf(0,1,0, 0,1,0, 0,0,1, 1,0,0, 0,0,1, 0,0,1),
        "E" to listOf(0,0,1, 0,0,1, 1,0,0, 1,0,0, 0,0,1, 0,0,1))
    val nullAmounts = mapOf("A" to listOf(0,1,0,0,0,0,0,0,2,0,0,0,0), "B" to listOf(0,1,1,1,1,1,1,0,1,1,1,1,1),
        "C" to List(13) { 0 }, "D" to List(13) { 0 }, "E" to listOf(0,0,0,0,1,1,1,0,0,0,0,0,0))
    val stateNames = predicateNames.flatMap { name -> listOf("${name}_true_rows", "${name}_false_rows", "${name}_unknown_rows") }
    return amounts.mapValues { (subject, values) -> amountNames.zip(values).toMap() + ratioNames.zip(ratios.getValue(subject)) + observationNames.zip(observations.getValue(subject)) +
        stateNames.zip(states.getValue(subject).map(Int::toString)) + amountNames.map { "true_null_$it" }.zip(nullAmounts.getValue(subject).map(Int::toString)) }
}

val contributionCounts = listOf("true_rows", "false_rows", "unknown_rows", "known_rows", "null_rows")
fun attributeBasis(e: PopulationExample): AttributeBasis {
    val original = e.raw("bases")
    val amounts = Computations.stages(original, listOf(regroup(listOf("subject"), sum("base_a", "sum_base_a", floating("base-a")), sum("base_b", "sum_base_b", floating("base-b")), counted("subject", "source_rows")), Derive(sym("sum_base_a"), "base_a", "未关联属性的独立基数"), Derive(sym("sum_base_b"), "base_b", "未关联属性的独立基数"), Project(listOf("subject", "base_a", "base_b", "source_rows"))))
    val stages = mutableListOf<ValidatedOperation>(JoinInput("attributes", "at", "LEFT", op(ResultOperator.EQ, sym("record_ref"), sym("at__record_ref"))),
        Derive(number("1"), "row", "一条LEFT袋记录"), Derive(choose(nonnull("at__record_ref"), number("1"), number("0")), "matched", "实际匹配"))
    val aggregates = mutableListOf(sum("row", "bag_rows", count), sum("matched", "matched_rows", count))
    val quantities = linkedMapOf("bag_rows" to count, "matched_rows" to count)
    ratios.forEach { spec ->
        val eligible = op(ResultOperator.AND, sym(if (spec.name in listOf("e", "f")) "at__flag_b" else "at__flag_a"),
            op(ResultOperator.EQ, sym(if (spec.name in listOf("c", "d", "f")) "count_b" else "count_a"), number("1")))
        stages += Derive(choose(eligible, sym("at__${spec.name}"), number("0", spec.numeratorContract)), "row_${spec.name}", "TRUE取金额含NULL，FALSE/UNKNOWN零")
        aggregates += sum("row_${spec.name}", spec.name, spec.numeratorContract); quantities[spec.name] = spec.numeratorContract
        val predicates = listOf(eligible, op(ResultOperator.NOT, eligible), op(ResultOperator.IS_NULL, eligible), op(ResultOperator.AND, eligible, nonnull("at__${spec.name}")), op(ResultOperator.AND, eligible, op(ResultOperator.IS_NULL, sym("at__${spec.name}"))))
        contributionCounts.zip(predicates).forEach { (suffix, predicate) ->
            val name = "${spec.name}_$suffix"
            stages += Derive(choose(predicate, number("1"), number("0")), "row_$name", "完整三值谓词的袋贡献数")
            aggregates += sum("row_$name", name, count); quantities[name] = count
        }
    }
    stages += Regroup(ResultGrouping(listOf("subject"), aggregates, null))
    val contributions = Computations.scope {
        input("attributes", e.raw("attributes"), InputMode.RELATION)
        Computations.stages(original, stages)
    }
    val amountQuantities = linkedMapOf("base_a" to floating("base-a"), "base_b" to floating("base-b"), "source_rows" to count)
    val basis = Computations.align(linkedMapOf(
        "amounts" to Computations.aligned(amounts, mapOf("subject" to "subject"), amountQuantities.keys.map { Calculation.AlignedValue(it, it, Calculation.NoMatch.PRESERVE_NULL) }),
        "contributions" to Computations.aligned(contributions, mapOf("subject" to "subject"), quantities.keys.map { Calculation.AlignedValue(it, it, Calculation.NoMatch.PRESERVE_NULL) })),
        listOf("subject"), Calculation.AlignmentPopulation(Calculation.PopulationKind.BASE, listOf("amounts")))
    return AttributeBasis(basis, amountQuantities + quantities)
}
fun attributeDetails(e: PopulationExample, basis: Calculation.Scope, metrics: List<SubjectAreaMetricRefRecord>): Calculation.Scope {
    val values = Computations.scope {
        val calls = ratios.zip(metrics).map { (spec, metric) -> Computations.invoke(metric.instance, spec.name, outputAlias = "ratio_${spec.name}",
            relations = mapOf("basis" to relation("basis", basis, mapOf("subject" to "subject", "numerator" to spec.name, "denominator" to spec.denominator)))) }
        Computations.measure(calls, listOf(Calculation.PublishedDimension(e.axis.reference)), RowPopulationContract.calls(calls.map { it.callId }))
    }
    return Computations.scope {
        input("ratios", values, InputMode.RELATION)
        Computations.stages(basis, listOf(JoinInput("ratios", "r", "LEFT", op(ResultOperator.EQ, sym("subject"), sym("r__subject")))))
    }
}
fun attributeReport(details: Calculation.Scope): Calculation.Scope {
    val stages = mutableListOf<ValidatedOperation>(Derive(ResultNumeric(op(ResultOperator.SUBTRACT, sym("bag_rows"), sym("source_rows")), count, null), "extra_rows", "关联额外行"),
        Derive(ResultNumeric(op(ResultOperator.SUBTRACT, sym("bag_rows"), sym("matched_rows")), count, null), "unmatched_rows", "LEFT未匹配行"))
    val totals = mutableListOf(counted("subject", "subjects"), counted("subject", "distinct_subjects", true), sum("source_rows", "total_source_rows", count), sum("bag_rows", "total_bag_rows", count),
        sum("matched_rows", "total_matched_rows", count), sum("unmatched_rows", "total_unmatched_rows", count), sum("extra_rows", "total_extra_rows", count))
    ratios.forEach { spec ->
        contributionCounts.forEach { suffix -> totals += sum("${spec.name}_$suffix", "${spec.name}_total_$suffix", count) }
        val predicates = linkedMapOf("denominator_null" to op(ResultOperator.IS_NULL, sym(spec.denominator)), "denominator_zero" to op(ResultOperator.EQ, sym(spec.denominator), number("0", spec.denominatorContract)),
            "denominator_negative" to op(ResultOperator.LT, sym(spec.denominator), number("0", spec.denominatorContract)), "numerator_null" to op(ResultOperator.IS_NULL, sym(spec.name)),
            "ratio_null" to op(ResultOperator.IS_NULL, sym("r__ratio_${spec.name}")), "ratio_zero" to op(ResultOperator.EQ, sym("r__ratio_${spec.name}"), number("0", spec.resultContract)),
            "true_null_amount" to op(ResultOperator.GT, sym("${spec.name}_null_rows"), number("0")))
        predicates.forEach { (suffix, predicate) -> val marker = "${spec.name}_$suffix"; stages += Derive(choose(predicate, number("1")), marker, "每完成对象计数一次"); totals += counted(marker, "${marker}_subjects") }
    }
    check(totals.size == 79)
    stages += Regroup(ResultGrouping(emptyList(), totals, null))
    return Computations.stages(details, stages)
}

val reusePeriod = linkedMapOf("from" to Calculation.Parameter(Calculation.ParameterKind.SCALAR, "TEXT", emptyList(), null, null),
    "to" to Calculation.Parameter(Calculation.ParameterKind.SCALAR, "TEXT", emptyList(), null, null))
fun reusePeriodCondition() = MemberCondition.Predicate("day", MemberCondition.Operator.BETWEEN, listOf(Calculation.Argument("from"), Calculation.Argument("to")))
fun reuseSlot(e: PopulationExample, name: String, source: String, columns: List<String>): Calculation.MemberSlot {
    val result = e.members.getValue(source).definition.result
    return Calculation.MemberSlot(name, result.entity, result.role, result.shape, columns.associateWith { column ->
        when (val output = result.outputs.getValue(column)) {
            is MemberSourceDefinition.DimensionOutput, is MemberSourceDefinition.ComputedDimensionOutput -> Calculation.MemberDimension("subject", MemberSourceDefinition.rootValueContract(output))
            is MemberSourceDefinition.ScalarOutput -> Calculation.MemberProperty(output.role, output.valueType, output.valueContract)
            else -> error("本例复用定义只接收正式标量出口")
        }
    }, null)
}
fun reuseSource(e: PopulationExample, source: String, columns: List<String>, arguments: Map<String, Calculation.Binding> = emptyMap()): CalculationBindings.SourceCall {
    val member = e.members.getValue(source)
    return CalculationBindings.SourceCall(MemberRef(member.id, member.subjectAreaVersion), arguments, columns.associateWith { it }, emptyMap(), emptyMap())
}
fun reuseMembers(name: String, columns: List<String>, condition: MemberCondition) = Computations.members(name, Calculation.MemberParameter(name),
    columns.map { Calculation.MemberProjection(it, it, null) }, condition = condition)
fun reuseResult(calculation: Calculation, quantities: Map<String, Pair<String, MetricValueContract>>, subjectSymbol: String, shape: MemberShape, description: String): MemberSourceDefinition.Computed {
    val outputs = linkedMapOf<String, MemberSourceDefinition.Output>("subject" to MemberSourceDefinition.ComputedDimensionOutput("对象", "subject", ScalarValueType.TEXT, "subject", subjectSymbol, null))
    quantities.forEach { (name, value) -> outputs[name] = MemberSourceDefinition.ComputedPropertyOutput(name, name, ScalarValueType.NUMBER, value.first, value.second) }
    return MemberSourceDefinition.Computed(calculation, MemberResult("population_subject", "subject", shape, listOf("subject"), outputs, emptyMap(), null, emptyMap(), description))
}
fun paidDefinition(e: PopulationExample): MemberSourceDefinition.Computed {
    val columns = listOf("subject", "day", "first_flag", "actor")
    val rows = reuseMembers("activity", columns, MemberCondition.All(listOf(reusePeriodCondition(), condition("first_flag", ScalarValueType.BOOLEAN, MemberCondition.Operator.EQ, "true"))))
    val body = Computations.stages(rows, listOf(PostFilter(nonnull("subject")), regroup(listOf("subject"), counted("actor", "paid_actors", true)),
        PostFilter(op(ResultOperator.GT, sym("paid_actors"), number("0", count))), Project(listOf("subject"))))
    return reuseResult(Calculation(listOf(e.slot), listOf(reuseSlot(e, "activity", "activity", columns)), emptyList(), emptyList(), reusePeriod, body),
        emptyMap(), "subject", MemberShape.UNIQUE_MEMBERS, "明确期间内first_flag为TRUE且至少一个非NULL actor；不新增actor空串政策")
}
fun visitedDefinition(e: PopulationExample): MemberSourceDefinition.Computed {
    val visits = listOf("item", "actor", "day"); val catalog = listOf("subject", "item", "current_partition")
    val rows = reuseMembers("visits", visits, reusePeriodCondition())
    val directory = reuseMembers("catalog", catalog, condition("current_partition", ScalarValueType.BOOLEAN, MemberCondition.Operator.EQ, "true"))
    val body = Computations.scope {
        input("directory", directory, InputMode.RELATION)
        Computations.stages(rows, listOf(
            JoinInput("directory", "d", "LEFT", op(ResultOperator.EQ, sym("item"), sym("d__item"))), PostFilter(nonnull("d__subject")),
            regroup(listOf("d__subject"), counted("actor", "visit_actors", true)), PostFilter(op(ResultOperator.GT, sym("visit_actors"), number("0", count))), Project(listOf("d__subject"))))
    }
    return reuseResult(Calculation(listOf(e.slot), listOf(reuseSlot(e, "visits", "visits", visits), reuseSlot(e, "catalog", "catalog", catalog)), emptyList(), emptyList(), reusePeriod, body),
        emptyMap(), "d__subject", MemberShape.UNIQUE_MEMBERS, "期间访问按当前目录归属，有非NULL actor；目录eligible不改变访问人口，不声称历史归属")
}
fun activityDefinition(e: PopulationExample): MemberSourceDefinition.Computed {
    val columns = listOf("subject", "day", "amount")
    val rows = reuseMembers("activity", columns, reusePeriodCondition())
    val amount = requireNotNull(MemberSourceDefinition.rootValueContract(e.outputs.getValue("activity").getValue("amount")))
    val body = Computations.stages(rows, listOf(PostFilter(nonnull("subject")), regroup(listOf("subject"), sum("amount", "amount_total", amount), counted("subject", "records"), counted("amount", "known"))))
    return reuseResult(Calculation(listOf(e.slot), listOf(reuseSlot(e, "activity", "activity", columns)), emptyList(), emptyList(), reusePeriod, body),
        linkedMapOf("amount" to ("amount_total" to amount), "records" to ("records" to count), "known" to ("known" to count)), "subject", MemberShape.RECORDS,
        "期间活动原袋按完整subject完成金额/来源行/金额非NULL行，真实组全NULL与无组分别保留")
}
fun catalogDefinition(e: PopulationExample, requireCaption: Boolean): MemberSourceDefinition.Computed {
    val columns = listOf("subject", "item", "current_partition", "eligible", "caption_accepted")
    val rows = reuseMembers("catalog", columns, condition("current_partition", ScalarValueType.BOOLEAN, MemberCondition.Operator.EQ, "true"))
    val policy = if (requireCaption) op(ResultOperator.AND, sym("eligible"), sym("caption_accepted")) else sym("eligible")
    val body = Computations.stages(rows, listOf(PostFilter(nonnull("subject")), Derive(choose(policy, sym("item")), "qualified_item", "目录资格只取完整谓词TRUE"),
        regroup(listOf("subject"), counted("item", "items", true), counted("qualified_item", "qualified_items", true))))
    return reuseResult(Calculation(listOf(e.slot), listOf(reuseSlot(e, "catalog", "catalog", columns)), emptyList(), emptyList(), emptyMap(), body),
        linkedMapOf("items" to ("items" to count), "qualified_items" to ("qualified_items" to count)), "subject", MemberShape.RECORDS,
        if (requireCaption) "当前目录item去重；资格为eligible AND caption_accepted，仅TRUE参与" else "当前目录item去重；资格为eligible，仅TRUE参与")
}
fun reusePublications(e: PopulationExample): Map<String, ScriptValue> {
    // v1 inline和v2 saved均已冻结；正式复用资产在carry后的v3 DRAFT首次建设。
    e.savedContext()
    val definitions = linkedMapOf<String, MemberDefinitionRecord>()
    fun adopt(code: String, displayName: String, definition: MemberSourceDefinition.Computed, sources: Map<String, CalculationBindings.SourceCall>) {
        val created = e.governance.createMemberDefinition(MemberDefinitionCreateRequest("population_$code", displayName, definition, definition.result.description, e.actor, e.actor))
        e.activate("MEMBER_DEFINITION", created.id, created.currentVersion)
        val adopted = e.governance.adoptMemberDefinition(MemberDefinitionAdoptRequest(e.subject.id, e.subject.currentVersion, code, displayName, created.reference,
            CalculationBindings(mapOf("subject" to e.axis), sources, emptyMap(), emptyMap(), emptyMap()), emptyMap(), e.actor))
        definitions[code] = created; e.members[code] = adopted; e.outputs[code] = definition.result.outputs; e.filters[code] = definition.result.filterOperators
    }
    adopt("paid_subjects", "复用付费标记对象", paidDefinition(e), mapOf("activity" to reuseSource(e, "activity", listOf("subject", "day", "first_flag", "actor"))))
    adopt("visited_subjects", "复用当前目录访问对象", visitedDefinition(e), mapOf("visits" to reuseSource(e, "visits", listOf("item", "actor", "day")),
        "catalog" to reuseSource(e, "catalog", listOf("subject", "item", "current_partition"))))
    adopt("period_activity", "复用期间活动完成量", activityDefinition(e), mapOf("activity" to reuseSource(e, "activity", listOf("subject", "day", "amount"))))
    adopt("current_catalog", "复用当前目录完成量", catalogDefinition(e, false), mapOf("catalog" to reuseSource(e, "catalog", listOf("subject", "item", "current_partition", "eligible", "caption_accepted"))))
    fun countMetric(code: String, field: String, displayName: String): SubjectAreaMetricRefRecord {
        val slot = Calculation.MemberSlot("completed", "population_subject", "subject", MemberShape.RECORDS,
            mapOf("subject" to Calculation.MemberDimension("subject", null), "quantity" to Calculation.MemberProperty(field, ScalarValueType.NUMBER, count)), null)
        val rows = reuseMembers("completed", listOf("subject", "quantity"), MemberCondition.Unrestricted())
        val definition = MetricCalculation(Calculation(listOf(e.slot), listOf(slot), emptyList(), emptyList(), reusePeriod,
            Computations.stages(rows, listOf(Derive(sym("quantity"), "value", displayName), Project(listOf("subject", "value"))))), "value", listOf("subject"), count)
        val meaning = MetricBusinessMeaning(displayName, "ONE/Exact/I64", "完整subject键的期间活动", listOf("调用正式period_activity闭包，不继承本次集合资格"))
        val metric = e.governance.createMetricCalculation(MetricCalculationCreateRequest("population_$code", displayName, e.actor, definition, "来源和归约由正式Computed定义拥有", meaning, e.actor)).metric
        e.activate("METRIC", metric.id)
        val source = e.members.getValue("period_activity")
        val binding = CalculationBindings.SourceCall(MemberRef(source.id, source.subjectAreaVersion), mapOf("from" to Calculation.Argument("from"), "to" to Calculation.Argument("to")),
            mapOf("subject" to "subject", "quantity" to field), emptyMap(), emptyMap())
        return e.governance.createSubjectAreaMetricRef(SubjectAreaMetricRefCreateRequest(e.subject.id, e.subject.currentVersion, code, displayName, meaning, metric.id, 1,
            CalculationBindings(mapOf("subject" to e.axis), mapOf("completed" to binding), emptyMap(), emptyMap(), emptyMap()), emptyMap(), e.actor)).subjectAreaMetricRef
    }
    val metrics = listOf(countMetric("source_record_count", "records", "期间来源记录数"), countMetric("known_amount_count", "known", "期间金额非NULL数"))
    val names = definitions.keys.toList()
    val original = e.publication("reuse-original", metrics, names, "对象人口复用")
    // 规则修订先完成并发布，再由独立外部阶段改变来源；旧发布仍引用原定义。
    val revisedContext = e.savedContext()
    val oldDefinition = definitions.getValue("current_catalog")
    val revised = e.governance.reviseMemberDefinition(GovernanceRevisionRequest(oldDefinition.id, oldDefinition.currentVersion,
        MemberDefinitionCreateRequest(oldDefinition.code, oldDefinition.displayName, catalogDefinition(e, true), "目录资格明确增加caption_accepted", e.actor, e.actor)))
    e.activate("MEMBER_DEFINITION", revised.id, revised.currentVersion)
    val previous = e.members.getValue("current_catalog")
    val current = e.governance.reviseMemberAdoption(MemberAdoptionRevisionRequest(MemberRef(previous.id, previous.subjectAreaVersion), oldDefinition.reference, revised.reference,
        previous.displayName, previous.bindings, previous.fixedArguments, e.actor))
    e.members["current_catalog"] = current; e.outputs["current_catalog"] = current.definition.result.outputs
    val carried = olap.semantics.metricAdoptions(GovernanceReadRequests.VersionPageInput(e.subject.id, e.subject.currentVersion, 0, 100))
    check(carried.items.size.toLong() == carried.total.toLong())
    val selected = carried.items.filter { it.code in setOf("source_record_count", "known_amount_count") }
    check(selected.map { it.code }.toSet() == setOf("source_record_count", "known_amount_count") && selected.size == 2)
    val scenario = revisedContext.carried.single { it.assetType == "SCENARIO" && it.id == original.capability.scenario.scenarioId }
    e.activate("SUBJECT_AREA", e.subject.id, e.subject.currentVersion)
    e.governance.buildRelease(SubjectAreaReleaseBuildRequest("populations-release", "reuse-revised", "目录资格显式修订的复用发布", e.subject.id, e.subject.currentVersion, e.actor, e.adopted))
    val updated = olap.semantics.publication(QueryCapabilityRequest(scenario.id, scenario.targetVersion, "populations-release", "reuse-revised"))
    fun coordinate(entry: PublishedQueryEntry) = ScriptValue.ObjectValue(mapOf("releaseId" to ScriptValue.Text(entry.capability.releaseId), "releaseVersion" to ScriptValue.Text(entry.capability.releaseVersion),
        "scenarioId" to ScriptValue.Text(entry.capability.scenario.scenarioId), "scenarioVersion" to ScriptValue.DecimalValue(BigDecimal.valueOf(entry.capability.scenario.scenarioVersion)),
        "scenarioDisplayName" to ScriptValue.Text(entry.capability.scenario.scenarioDisplayName)))
    return mapOf("reuseOriginalPublication" to coordinate(original), "reuseRevisedPublication" to coordinate(updated))
}

fun installInputs(e: PopulationExample) {
    fun texts(vararg names: String) = names.associateWith { ScalarValueType.TEXT }
    fun numbers(vararg names: String) = names.associateWith { ScalarValueType.NUMBER }
    fun booleans(vararg names: String) = names.associateWith { ScalarValueType.BOOLEAN }
    e.install("activity", texts("subject", "day", "actor") + numbers("amount") + booleans("first_flag"), mapOf("amount" to floating("activity-value")))
    e.install("catalog", texts("subject", "item", "caption") + booleans("current_partition", "eligible"), captionPredicate = true)
    e.install("visits", texts("item", "actor", "day"))
    e.install("participation", texts("subject", "day", "actor") + booleans("eligible"))
    e.install("fact_a", texts("subject", "clock") + numbers("amount") + booleans("current_partition", "eligible"), mapOf("amount" to floating("a-value")))
    e.install("fact_b", texts("subject", "clock") + numbers("amount", "deduction") + booleans("current_partition"), listOf("amount", "deduction").associateWith { floating("b-value") })
    e.install("scaled", texts("subject", "record_ref", "actor", "adjustment_text", "label") + numbers("gross", "quantity"), mapOf("gross" to floating("gross-native"), "adjustment" to floating("adjustment-native"), "quantity" to floating("quantity")), true)
    e.install("bases", texts("subject", "record_ref", "state_c") + numbers("count_a", "count_b", "base_a", "base_b"), mapOf("count_a" to count, "count_b" to count, "base_a" to floating("base-a"), "base_b" to floating("base-b")))
    val attributeQuantities = ('a'..'m').associate { it.toString() to floating("attribute-$it") }
    e.install("attributes", texts("record_ref", "partition_day") + booleans("flag_a", "flag_b") + attributeQuantities.keys.associateWith { ScalarValueType.NUMBER }, attributeQuantities)
    val factFilters = mapOf("partition_key" to listOf(MemberCondition.Operator.GTE), "activity_day" to listOf(MemberCondition.Operator.BETWEEN))
    e.install("label_facts", texts("subject", "object_ref", "partition_key", "activity_day", "record_id"), filterOperators = factFilters)
    e.install("labels", texts("object_ref", "label_kind", "label_id", "label_display", "partition_key"),
        filterOperators = mapOf("label_kind" to listOf(MemberCondition.Operator.IN)))
    e.install("ledger_activity", texts("subject", "reference_key", "activity_day", "partition_key"), filterOperators = factFilters)
    e.install("fee_ledger", numbers("reference_number") + texts("ledger_day", "partition_key"), castReference = true)
    e.install("adjustment_ledger", texts("reference_key", "ledger_day", "partition_key"))
    e.install("attributed_activity", texts("subject", "document_ref", "business_day"))
    e.install("book_r", texts("document_ref", "settlement_day", "flag") + numbers("amount_r", "amount_s") + booleans("is_current"),
        mapOf("amount_r" to floating("book-r-unit"), "amount_s" to floating("book-s-unit")))
    e.install("book_s", texts("document_ref", "settlement_day", "text_amount") + booleans("is_current"))
    e.install("observer_directory", texts("entry_ref", "subject") + booleans("is_current"))
    e.install("observer_visits", texts("entry_ref", "visitor_ref", "day") + booleans("eligible"))
    e.install("observer_payments", texts("subject", "day", "initial_flag", "buyer_ref"))
    e.createAxis()
}
fun references(query: QueryReference, result: QueryResultReference, captionQuery: QueryReference, captionResult: QueryResultReference) = ScriptValue.ObjectValue(mapOf(
    "query" to ScriptValue.QueryReferenceValue(query), "result" to ScriptValue.ResultReferenceValue(result),
    "captionQuery" to ScriptValue.QueryReferenceValue(captionQuery), "captionResult" to ScriptValue.ResultReferenceValue(captionResult)))
fun build(): ScriptValue.ObjectValue {
    val e = PopulationExample(); installInputs(e)
    val meanTemplate = dailyTemplate(e.slot)
    val comparisonTemplate = dailyComparisonTemplate(e.slot, meanTemplate.calculation)
    val inline = e.publication("inline")
    val sourceRelations = sourceRelationCases(e, inline)
    val ledgerBags = ledgerBagCases(e, inline)
    val completedPopulation = completedPopulationCase(e, inline)
    val cohort = e.execute(inline, "对象集合后分别测量原始袋", cohortQuery(e))
    expect(cohort.rows, linkedMapOf("" to mapOf("items" to "1", "qualified_items" to "1", "amount" to null, "records" to "1", "known" to "0"),
        "A" to mapOf("items" to "2", "qualified_items" to "2", "amount" to null, "records" to "2", "known" to "0"),
        "B" to mapOf("items" to "1", "qualified_items" to "1", "amount" to "7", "records" to "1", "known" to "1"),
        "C" to mapOf("items" to "1", "qualified_items" to "0", "amount" to null, "records" to null, "known" to null)))
    val scale = e.execute(inline, "零Metric双尺度与完成关系商", scaledQuery(e))
    expect(scale.rows, mapOf("A" to mapOf("gross_total" to "1", "adjustment_total" to "2", "quantity_total" to "3", "ratio" to "1", "actors" to "1", "label" to "beta"), "B" to mapOf("gross_total" to null, "adjustment_total" to null, "quantity_total" to null, "ratio" to null, "actors" to "1", "label" to "gamma")))
    val primary = e.execute(inline, "独立事实完整人口", independentQuery(e)); expect(primary.rows, independentExpected)
    val captions = e.execute(inline, "Unicode逻辑出口与目录原袋三值", captionQuery(e)); expectCaptions(captions.rows, false)
    val (inlineAttributes, inlineQuantities) = inlineAttributeQuery(e)
    val inlineResult = e.execute(inline, "零Metric十三贡献与六个内联guard", inlineAttributes)
    expect(inlineResult.rows, inlineAttributeExpected())
    check(inlineResult.rows.all { it.keys == setOf("subject") + inlineQuantities.keys })
    expectSavedQuantities(inlineResult.result, inlineQuantities)
    // 模板直接使用与嵌入高层计算均先于任何日均Metric或比较成员的持久化。
    val inlineDailyBasis = dailyBasis(e)
    val inlineDaily = e.execute(inline, "本地日均模板直接计算", dailyTemplateQuery(e, meanTemplate.calculation, inlineDailyBasis, mapOf("completed" to "days_total")), QueryExecutionMode.CORE_FINALIZATION)
    expect(inlineDaily.rows, mapOf("A" to mapOf("value" to "1.5"), "B" to mapOf("value" to "0")))
    expectSavedQuantities(inlineDaily.result, mapOf("value" to savedMeanContract))
    val nestedDaily = e.execute(inline, "本地日均模板嵌入四种资格比较", dailyTemplateQuery(e, comparisonTemplate, inlineDailyBasis, dailyComparisons.associateWith { it }), QueryExecutionMode.CORE_FINALIZATION)
    expect(nestedDaily.rows, dailyComparisonExpected)
    check(nestedDaily.rows.all { it.keys == setOf("subject") + dailyComparisons.map { "${it}_average" } })
    expectSavedQuantities(nestedDaily.result, dailyComparisons.associate { "${it}_average" to savedMeanContract })
    e.savedContext()
    val daily = dailyBasis(e)
    val mean = e.savedAlgorithm("completed_per_days", meanTemplate)
    val comparisonMember = saveDailyComparison(e, comparisonTemplate)
    val ratioMetrics = ratios.map { ratioAlgorithm(e, it) }
    val saved = e.publication("saved", listOf(mean) + ratioMetrics)
    expect(e.execute(saved, "每日完成后四次复用同一保存算法", savedDailyQuery(e, mean, daily), QueryExecutionMode.CORE_FINALIZATION).rows, dailyComparisonExpected)
    val publishedDaily = e.execute(saved, "正式采用多量比较成员并复用", publishedDailyComparison(e, comparisonMember, daily), QueryExecutionMode.CORE_FINALIZATION)
    expect(publishedDaily.rows, dailyComparisonExpected)
    check(publishedDaily.rows.all { it.keys == setOf("subject") + dailyComparisons.map { "${it}_average" } })
    expectSavedQuantities(publishedDaily.result, dailyComparisons.associate { "${it}_average" to savedMeanContract })
    // 算法的completed形参要求ONE计数；实际金额完成量不能作为其quantity输入。
    val amountInput = Computations.stages(e.raw("activity", listOf("subject", "amount")),
        listOf(regroup(listOf("subject"), sum("amount", "completed_amount", floating("activity-value")))))
    val invalid = Computations.scope {
        val invalidCall = Computations.invoke(mean.instance, "invalid_daily", arguments = mapOf("days" to Calculation.Scalar(ScalarValueType.NUMBER, "2")),
            relations = mapOf("completed" to relation("amount_input", amountInput, mapOf("subject" to "subject", "quantity" to "completed_amount"))))
        Computations.measure(listOf(invalidCall), listOf(Calculation.PublishedDimension(e.axis.reference)), RowPopulationContract.calls(listOf("invalid_daily")))
    }
    val invalidFailure = runCatching { olap.queries.validate(QueryValidationRequest(QuerySelection.Inline(
        Computations.query(PublishedQueryEntry.key(saved.capability), invalid)), QueryExecutionMode.CORE_FINALIZATION)) }.exceptionOrNull()
    check(invalidFailure is CapabilityFailureException) { "真实金额输入必须被计数形参拒绝：${invalidFailure?.javaClass?.name}: ${invalidFailure?.message}" }
    val dailyInputRejected = invalidFailure.failure
    check(dailyInputRejected.operationId == "validate-query" && dailyInputRejected.code == "RESULT_UNIT_MISMATCH" && dailyInputRejected.stage == "ANALYZE_STAGES") {
        "${dailyInputRejected.code}/${dailyInputRejected.stage}: ${dailyInputRejected.message}"
    }
    expect(e.execute(saved, "十九日精确CORE终算", exactDailyQuery(daily), QueryExecutionMode.CORE_FINALIZATION).rows, mapOf("A" to mapOf("days_total" to "3", "period" to "3", "period_nonempty" to "2", "mean" to "0.157894736842105263", "repeat_days" to "1"), "B" to mapOf("days_total" to "0", "period" to "1", "period_nonempty" to "0", "mean" to "0", "repeat_days" to "0")))
    val basis = attributeBasis(e)
    val details = attributeDetails(e, basis.input, ratioMetrics)
    expect(e.execute(saved, "独立分母与六个真实保存guard", details).rows, mapOf(
        "A" to mapOf("base_a" to "100", "base_b" to "50", "a" to "30", "b" to null, "bag_rows" to "2", "r__ratio_a" to "0.3", "r__ratio_b" to null, "r__ratio_e" to "0.05", "r__ratio_f" to "1.2"),
        "B" to mapOf("a" to "8", "b" to null, "r__ratio_a" to "0.16", "r__ratio_b" to null, "r__ratio_e" to null, "r__ratio_f" to "0"), "C" to mapOf("r__ratio_a" to "-0.5", "r__ratio_b" to "-0.1", "r__ratio_c" to "-0.4", "r__ratio_d" to "-0.6", "r__ratio_e" to "0", "r__ratio_f" to "1.25"),
        "D" to mapOf("r__ratio_a" to null, "r__ratio_d" to null, "r__ratio_e" to "0", "r__ratio_f" to "0"),
        "E" to mapOf("base_a" to "20", "base_b" to "10", "matched_rows" to "0", "bag_rows" to "1", "r__ratio_a" to "0", "r__ratio_b" to "0", "r__ratio_c" to "0", "r__ratio_d" to "0", "r__ratio_e" to "0", "r__ratio_f" to "0")))
    val report = e.execute(saved, "完成对象之后的79列人口报告", attributeReport(details))
    // 独立由五条原记录、六条LEFT记录及上方业务表推得；不由生产表达式或结果反推预期。
    val reportExpected = linkedMapOf<String, String?>("subjects" to "5", "distinct_subjects" to "5", "total_source_rows" to "5", "total_bag_rows" to "6", "total_matched_rows" to "5", "total_unmatched_rows" to "1", "total_extra_rows" to "1")
    val reportSuffixes = listOf("total_true_rows", "total_false_rows", "total_unknown_rows", "total_known_rows", "total_null_rows", "denominator_null_subjects", "denominator_zero_subjects", "denominator_negative_subjects", "numerator_null_subjects", "ratio_null_subjects", "ratio_zero_subjects", "true_null_amount_subjects")
    val independentCounts = linkedMapOf(
        "a" to listOf(4,0,2,4,0, 0,1,1,0,1,1,0),
        "b" to listOf(4,0,2,1,3, 0,1,1,2,3,1,2),
        "c" to listOf(4,0,2,3,1, 0,1,1,1,2,1,1),
        "d" to listOf(4,0,2,3,1, 0,1,1,1,2,1,1),
        "e" to listOf(3,2,1,2,1, 0,1,1,1,1,3,1),
        "f" to listOf(3,2,1,2,1, 1,2,0,1,0,3,1))
    independentCounts.forEach { (name, counts) -> reportSuffixes.zip(counts).forEach { (suffix, value) -> reportExpected["${name}_$suffix"] = value.toString() } }
    expect(report.rows, mapOf("total" to reportExpected), "")
    check(report.rows.single().size == 79)
    val rejected = ScriptValue.ObjectValue(mapOf("operation" to ScriptValue.Text(dailyInputRejected.operationId), "code" to ScriptValue.Text(dailyInputRejected.code),
        "stage" to ScriptValue.Text(dailyInputRejected.stage), "message" to ScriptValue.Text(dailyInputRejected.message)))
    val localReferences = mapOf("dailyInlineQuery" to ScriptValue.QueryReferenceValue(inlineDaily.query), "dailyInlineResult" to ScriptValue.ResultReferenceValue(inlineDaily.result),
        "dailyNestedQuery" to ScriptValue.QueryReferenceValue(nestedDaily.query), "dailyNestedResult" to ScriptValue.ResultReferenceValue(nestedDaily.result),
        "dailyPublishedQuery" to ScriptValue.QueryReferenceValue(publishedDaily.query), "dailyPublishedResult" to ScriptValue.ResultReferenceValue(publishedDaily.result))
    return ScriptValue.ObjectValue(references(primary.query, primary.result, captions.query, captions.result).values + reusePublications(e) + localReferences + sourceRelations + ledgerBags + completedPopulation + ("dailyInputRejected" to rejected))
}
olap.output(when (parameters.text("phase")) {
    "build" -> build()
    else -> {
        val q = parameters["query"].fields(); val r = parameters["result"].fields()
        val query = QueryReference(q.text("definitionId"), q.text("definitionVersion").toLong(), q.text("sha256"))
        val result = QueryResultReference(r.text("runId"), r.text("sha256"))
        val cq = parameters["captionQuery"].fields(); val cr = parameters["captionResult"].fields()
        val captionQuery = QueryReference(cq.text("definitionId"), cq.text("definitionVersion").toLong(), cq.text("sha256"))
        val captionResult = QueryResultReference(cr.text("runId"), cr.text("sha256"))
        val output = references(query, result, captionQuery, captionResult).values.toMutableMap()
        // 变化后的新来源请求和旧保存引用分别观察，不重执来源来伪造旧结果。
        expect(readAll(result), independentExpected)
        expectCaptions(readAll(captionResult), false)
        if (parameters.text("phase") in setOf("reopen", "refresh-source")) {
            check(olap.queries.read(QueryVersionRequest(query)).reference == query)
            val fresh = olap.queries.execute(QueryExecutionRequest(QuerySelection.Saved(query), true, "精确冻结Query面对当前来源", null))
            check(fresh.status == QueryExecutionStatus.SUCCEEDED && fresh.saved)
            expect(readAll(requireNotNull(fresh.resultReference)), if (parameters.text("phase") == "refresh-source") independentExpected.filterKeys { it != "C" } else independentExpected)
            expect(readAll(result), independentExpected)
            check(olap.queries.read(QueryVersionRequest(captionQuery)).reference == captionQuery)
            if (parameters.text("phase") == "refresh-source") {
                val freshCaption = olap.queries.execute(QueryExecutionRequest(QuerySelection.Saved(captionQuery), true, "原精确Unicode查询面对变化后的caption", null, executionMode = QueryExecutionMode.SOURCE))
                check(freshCaption.status == QueryExecutionStatus.SUCCEEDED && freshCaption.saved && freshCaption.executionMode == QueryExecutionMode.SOURCE && freshCaption.sourceState == SourceExecutionState.SUCCEEDED)
                check(freshCaption.sourceReadEvidence?.basis is SourceReadBasis.Unknown)
                val freshCaptionResult = requireNotNull(freshCaption.resultReference)
                check(freshCaptionResult != captionResult)
                expectCaptions(readAll(freshCaptionResult), true)
                expectCaptions(readAll(captionResult), false)
                output["freshCaptionResult"] = ScriptValue.ResultReferenceValue(freshCaptionResult)
            }
        } else if (parameters.text("phase") == "read-caption-history") {
            val fr = parameters["freshCaptionResult"].fields()
            val freshCaptionResult = QueryResultReference(fr.text("runId"), fr.text("sha256"))
            check(freshCaptionResult != captionResult)
            expectCaptions(readAll(freshCaptionResult), true)
            output["freshCaptionResult"] = ScriptValue.ResultReferenceValue(freshCaptionResult)
        } else check(parameters.text("phase") == "read-result")
        ScriptValue.ObjectValue(output)
    }
})
