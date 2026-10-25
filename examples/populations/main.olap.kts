import com.olap.prototype.dsl.*
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
fun calc(root: Calculation.Root, stages: List<ValidatedOperation> = emptyList()) = Calculation.Scope(root, emptyMap(), stages)
fun root(input: QueryComputation.Scope) = QueryComputation.Input("ROOT", input)
fun relation(input: QueryComputation.Scope) = QueryComputation.Input("RELATION", input)
fun stageAuthorization(stage: String): StageAuthorization {
    val expression = ExpressionAuthorization(setOf(ExpressionAuthorization.Kind.ARGUMENT, ExpressionAuthorization.Kind.SYMBOL, ExpressionAuthorization.Kind.LITERAL,
        ExpressionAuthorization.Kind.QUANTITY_LITERAL, ExpressionAuthorization.Kind.NUMERIC, ExpressionAuthorization.Kind.TYPED_LITERAL, ExpressionAuthorization.Kind.OPERATION, ExpressionAuthorization.Kind.CASE),
        setOf(ResultOperator.EQ, ResultOperator.NE, ResultOperator.LT, ResultOperator.GT, ResultOperator.NOT, ResultOperator.AND, ResultOperator.IS_NULL, ResultOperator.IS_NOT_NULL, ResultOperator.DIVIDE, ResultOperator.SUBTRACT) +
            if (stage == "DERIVE") setOf(ResultOperator.MULTIPLY) else emptySet(), emptySet(), emptySet(), setOf(ScalarValueType.TEXT, ScalarValueType.NUMBER))
    return StageAuthorization(stage, if (stage == "JOIN_INPUT") setOf(StageAuthorization.JoinType.LEFT) else emptySet(),
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
    fun install(name: String, types: Map<String, ScalarValueType>, quantities: Map<String, MetricValueContract> = emptyMap(), castAdjustment: Boolean = false, captionPredicate: Boolean = false) {
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
        val logical = governance.createLogicalTable(LogicalTableCreateRequest(physical.physicalTable.id, 1, name, name, LogicalTableRole.FACT, "保原袋与NULL，不造PK", actor, actor, units))
        activate("LOGICAL_TABLE", logical.logicalTable.id)
        val topology = governance.createTopology(TopologyCreateRequest(name, name, "example.populations", "独立来源时钟", logical.logicalTable.id, 1, actor, "完整记录边界", actor)).topology
        activate("TOPOLOGY", topology.id)
        val event = governance.createEvent(EventCreateRequest(topology.id, 1, "records", name, actor, null, null, EventIdentity(emptyList(), emptyMap()), emptyMap(),
            logical.logicUnits.map { EventCreateRequest.LogicUnitRefInput(it.id, 1) }, logical.logicUnits.filter { it.aggregatable }.map { EventCreateRequest.LogicUnitRefInput(it.id, 1) }, "RAW_BAG无记录身份", actor)).event
        activate("EVENT", event.id)
        val bound = if ("subject" in types) governance.createDimensionEventBinding(DimensionEventBindingCreateRequest(dimension.id, 1, event.id, 1, logical.logicUnits.single { it.code == "subject" }.id, 1, null, RelationAccessPath(emptyList()), emptyMap(), actor)).binding.also { activate("DIMENSION_EVENT_BINDING", it.id); bindings += it } else null
        val effective = types + (if (castAdjustment) mapOf("adjustment" to ScalarValueType.NUMBER) else emptyMap()) +
            (if (captionPredicate) mapOf("caption_accepted" to ScalarValueType.BOOLEAN) else emptyMap())
        val declared: Map<String, MemberSourceDefinition.Output> = effective.mapValues { (code, type) -> if (code == "subject") MemberSourceDefinition.DimensionOutput(code, "subject", type, dimension.id, 1, requireNotNull(bound).id, 1, null)
            else MemberSourceDefinition.PropertyOutput(code, code, type, logical.logicUnits.single { it.code == code }.id, 1, RelationAccessPath(emptyList()), quantities[code]) }
        val allowed = effective.mapValues { (_, type) -> if (type == ScalarValueType.TEXT) listOf(MemberCondition.Operator.EQ, MemberCondition.Operator.BETWEEN) else listOf(MemberCondition.Operator.EQ) }
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
    fun raw(name: String, columns: List<String> = outputs.getValue(name).keys.toList(), condition: MemberCondition = MemberCondition.Unrestricted()) = QueryComputation.Scope(QueryComputation.Members(name, MemberRef(members.getValue(name).id, members.getValue(name).subjectAreaVersion), emptyMap(),
        columns.map { if (it == "subject") QueryComputation.MappedDimension(it, it, axis.reference) else QueryComputation.Slot(it, it) }, null, emptyMap(), condition, emptyMap(), emptyMap()), emptyMap(), if ("subject" in columns) listOf(PostFilter(nonnull("subject"))) else emptyList())
    fun complete(input: QueryComputation.Scope, vararg stages: ValidatedOperation) = QueryComputation.Scope(input.root, input.inputs, input.stages + stages)
    fun align(inputs: Map<String, Pair<QueryComputation.Scope, Map<String, SetOutputContract.Shape>>>, population: Calculation.PopulationKind, selected: List<String>): QueryComputation.Scope {
        val relations = inputs.map { (name, value) -> Calculation.RelationSlot(name, listOf(SetOutputContract("subject", SetOutputContract.DimensionSlot("subject"))) + value.second.map { (column, shape) -> SetOutputContract(column, shape) }, listOf("subject")) }
        val aligned = inputs.mapValues { (name, value) -> Calculation.AlignedInput(calc(Calculation.Relation(name)), mapOf("subject" to "subject"), value.second.keys.map { Calculation.AlignedValue(it, it, Calculation.NoMatch.PRESERVE_NULL) }) }
        val calculation = Calculation(listOf(slot), emptyList(), emptyList(), relations, emptyMap(), calc(Calculation.AlignMeasurements(aligned, listOf("subject"), Calculation.AlignmentPopulation(population, selected))))
        val bindings = CalculationBindings(mapOf("subject" to axis), emptyMap(), emptyMap(), emptyMap(), inputs.keys.associateWith { CalculationBindings.RelationParameter() })
        return QueryComputation.Scope(QueryComputation.Compute(calculation, bindings, emptyMap(), emptyMap(), inputs.mapValues { (name, value) -> CalculationBindings.LocalRelation(name, (listOf("subject") + value.second.keys).associateWith { it }) }), inputs.mapValues { root(it.value.first) }, emptyList())
    }
    data class Saved(val query: QueryReference, val result: QueryResultReference, val rows: List<Map<String, String?>>)
    fun execute(entry: PublishedQueryEntry, name: String, input: QueryComputation.Scope, mode: QueryExecutionMode = QueryExecutionMode.SOURCE): Saved {
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

fun cohortQuery(e: PopulationExample): QueryComputation.Scope {
    fun paid(day: String) = e.complete(e.raw("activity", listOf("subject", "actor"), MemberCondition.All(listOf(
        condition("day", ScalarValueType.TEXT, MemberCondition.Operator.EQ, day), condition("first_flag", ScalarValueType.BOOLEAN, MemberCondition.Operator.EQ, "true")))),
        regroup(listOf("subject"), counted("actor", "actors", true)), PostFilter(op(ResultOperator.GT, sym("actors"), number("0"))), Project(listOf("subject")))
    val directory = e.raw("catalog", listOf("subject", "item"), condition("current_partition", ScalarValueType.BOOLEAN, MemberCondition.Operator.EQ, "true"))
    val visits = e.raw("visits", listOf("item", "actor"), condition("day", ScalarValueType.TEXT, MemberCondition.Operator.BETWEEN, "20000101", "20000102"))
    val visited = QueryComputation.Scope(visits.root, mapOf("directory" to relation(directory)), listOf(
        JoinInput("directory", "d", "LEFT", op(ResultOperator.EQ, sym("item"), sym("d__item"))), PostFilter(nonnull("d__subject")),
        regroup(listOf("d__subject"), counted("actor", "actors", true)), PostFilter(op(ResultOperator.GT, sym("actors"), number("0"))), Project(listOf("d__subject"))))
    val names = listOf("previous", "visited", "current")
    val identity = listOf(SetOutputContract("subject", SetOutputContract.DimensionSlot("subject")))
    fun set(kind: Calculation.SetKind, left: Calculation.Scope, right: Calculation.Scope) = Calculation.Scope(Calculation.SetCombination(kind, false,
        listOf(Calculation.SetProjection("l", listOf("subject")), Calculation.SetProjection("r", listOf("subject"))), identity),
        mapOf("l" to Calculation.InputScope("ROOT", left), "r" to Calculation.InputScope("ROOT", right)), emptyList())
    val population = set(Calculation.SetKind.EXCEPT, set(Calculation.SetKind.INTERSECT, calc(Calculation.Relation("previous")), calc(Calculation.Relation("visited"))), calc(Calculation.Relation("current")))
    fun formal(name: String) = Calculation.MemberSlot(name, name, "records", MemberShape.RAW_BAG, e.outputs.getValue(name).mapValues { (_, value) -> when (value) {
        is MemberSourceDefinition.DimensionOutput -> Calculation.MemberDimension("subject", null)
        is MemberSourceDefinition.PropertyOutput -> Calculation.MemberProperty(value.role, value.valueType, value.valueContract)
        else -> error("本例来源只有标量")
    } }, null)
    fun qualified(name: String, condition: MemberCondition): Calculation.Scope {
        val records = calc(Calculation.Members(name, name, e.outputs.getValue(name).keys.map { Calculation.MemberProjection(it, it) }, null, emptyMap(), condition))
        return calc(Calculation.ObjectMeasure(records, population, listOf(Calculation.ObjectRole(listOf("subject"), Calculation.RecordOutputs(name, listOf("subject"))))))
    }
    val catalog = qualified("catalog", condition("current_partition", ScalarValueType.BOOLEAN, MemberCondition.Operator.EQ, "true"))
    val completedCatalog = Calculation.Scope(catalog.root, catalog.inputs, listOf(Derive(choose(sym("eligible"), sym("item")), "qualified_item", "显式目录资格"),
        regroup(listOf("subject"), counted("item", "items", true), counted("qualified_item", "qualified_items", true))))
    val records = qualified("activity", condition("day", ScalarValueType.TEXT, MemberCondition.Operator.BETWEEN, "20000101", "20000102"))
    val completedAmount = Calculation.Scope(records.root, records.inputs, listOf(regroup(listOf("subject"), sum("amount", "measured_amount", floating("activity-value")), counted("subject", "records"), counted("amount", "known"))))
    val key = mapOf("subject" to "subject")
    val aligned = Calculation.AlignMeasurements(linkedMapOf("population" to Calculation.AlignedInput(population, key, emptyList()),
        "catalog" to Calculation.AlignedInput(completedCatalog, key, listOf("items", "qualified_items").map { Calculation.AlignedValue(it, it, Calculation.NoMatch.PRESERVE_NULL) }),
        "amount" to Calculation.AlignedInput(completedAmount, key, listOf(Calculation.AlignedValue("measured_amount", "amount", Calculation.NoMatch.PRESERVE_NULL)) +
            listOf("records", "known").map { Calculation.AlignedValue(it, it, Calculation.NoMatch.PRESERVE_NULL) })), listOf("subject"), Calculation.AlignmentPopulation(Calculation.PopulationKind.BASE, listOf("population")))
    val definition = Calculation(listOf(e.slot), listOf(formal("activity"), formal("catalog")), emptyList(), names.map { Calculation.RelationSlot(it, identity, listOf("subject")) }, emptyMap(), calc(aligned))
    val bindings = CalculationBindings(mapOf("subject" to e.axis), listOf("activity", "catalog").associateWith { name -> CalculationBindings.SourceCall(MemberRef(e.members.getValue(name).id, 1), emptyMap(), e.outputs.getValue(name).keys.associateWith { it }, emptyMap(), emptyMap()) }, emptyMap(), emptyMap(), names.associateWith { CalculationBindings.RelationParameter() })
    return QueryComputation.Scope(QueryComputation.Compute(definition, bindings, emptyMap(), emptyMap(), names.associateWith { CalculationBindings.LocalRelation(it, mapOf("subject" to if (it == "visited") "d__subject" else "subject")) }),
        mapOf("previous" to root(paid("19991231")), "visited" to root(visited), "current" to root(paid("20000101"))), emptyList())
}

fun independentQuery(e: PopulationExample): QueryComputation.Scope {
    fun selected(name: String) = e.raw(name, condition = MemberCondition.All(listOf(condition("current_partition", ScalarValueType.BOOLEAN, MemberCondition.Operator.EQ, "true"), condition("clock", ScalarValueType.TEXT, MemberCondition.Operator.BETWEEN, "20000101", "20000102"))))
    val a = e.complete(selected("fact_a"), Derive(choose(sym("eligible"), sym("amount"), number("0", floating("a-value"))), "conditional", "资格TRUE保NULL，FALSE/UNKNOWN零"),
        regroup(listOf("subject"), sum("amount", "a", floating("a-value")), sum("conditional", "conditional_a", floating("a-value")), counted("subject", "a_rows")), Derive(number("1"), "a_present", "有组标志"))
    val b = e.complete(selected("fact_b"), Derive(ResultNumeric(op(ResultOperator.SUBTRACT, sym("amount"), sym("deduction")), floating("b-value"), null), "net", "先逐行相减，任一分量NULL则本行NULL"),
        regroup(listOf("subject"), sum("net", "b", floating("b-value")), counted("subject", "b_rows"), counted("net", "known_b")), Derive(number("1"), "b_present", "全NULL组仍存在"))
    return e.align(linkedMapOf("a" to (a to linkedMapOf("a" to SetOutputContract.Quantity(floating("a-value")), "conditional_a" to SetOutputContract.Quantity(floating("a-value")), "a_rows" to SetOutputContract.Quantity(count), "a_present" to SetOutputContract.Quantity(count))),
        "b" to (b to linkedMapOf("b" to SetOutputContract.Quantity(floating("b-value")), "b_rows" to SetOutputContract.Quantity(count), "known_b" to SetOutputContract.Quantity(count), "b_present" to SetOutputContract.Quantity(count)))), Calculation.PopulationKind.UNION, listOf("a", "b"))
}
val independentExpected = linkedMapOf(
    "A" to mapOf("a" to "9", "conditional_a" to "0", "b" to null, "a_present" to "1", "b_present" to "1", "known_b" to "0"),
    "B" to mapOf("a" to "5", "conditional_a" to "5", "b" to null, "a_present" to "1", "b_present" to null, "b_rows" to null),
    "C" to mapOf("a" to null, "conditional_a" to null, "b" to "5", "a_present" to null, "b_present" to "1", "b_rows" to "2"))

fun captionQuery(e: PopulationExample): QueryComputation.Scope {
    val selected = e.raw("catalog", listOf("subject", "item", "caption", "caption_accepted"), condition("current_partition", ScalarValueType.BOOLEAN, MemberCondition.Operator.EQ, "true"))
    val counts = e.complete(selected,
        Derive(choose(sym("caption_accepted"), number("1", count)), "accepted_row", "仅完整谓词TRUE贡献"),
        Derive(choose(op(ResultOperator.NOT, sym("caption_accepted")), number("1", count)), "rejected_row", "完整谓词FALSE贡献"),
        Derive(choose(op(ResultOperator.IS_NULL, sym("caption_accepted")), number("1", count)), "unknown_row", "NULL保UNKNOWN"),
        regroup(listOf("subject"), counted("subject", "catalog_rows"), counted("accepted_row", "true_rows"), counted("rejected_row", "false_rows"), counted("unknown_row", "unknown_rows")))
    // 完成关系每subject一个键；回连原目录袋保文本、NULL与重复，不把item声明成唯一键。
    return QueryComputation.Scope(selected.root, selected.inputs + ("counts" to relation(counts)), selected.stages + listOf(
        JoinInput("counts", "counts", "LEFT", op(ResultOperator.EQ, sym("subject"), sym("counts__subject"))),
        Derive(op(ResultOperator.EQ, sym("caption"), text("山川🦊")), "caption_is_mountain_fox", "完整TEXT与非BMP typed literal精确比较"),
        Project(listOf("subject", "item", "caption", "caption_accepted", "caption_is_mountain_fox", "counts__catalog_rows", "counts__true_rows", "counts__false_rows", "counts__unknown_rows"))))
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

fun dailyBasis(e: PopulationExample): QueryComputation.Scope {
    val input = e.raw("participation")
    val nonempty = op(ResultOperator.NE, sym("actor"), text(""))
    val period = e.complete(input, Derive(choose(nonempty, sym("actor")), "nonempty", "仅本测量排空串"), regroup(listOf("subject"), counted("actor", "period", true), counted("nonempty", "period_nonempty", true)))
    val daily = e.complete(input, Derive(choose(nonempty, sym("actor")), "nonempty", "保有日组的零计数"), regroup(listOf("subject", "day"), counted("nonempty", "daily", true)), regroup(listOf("subject"), sum("daily", "days_total", count)))
    // 三条互相独立的资格分支，先完成完整日/标识元组，不影响日总体测量。
    val predicates = linkedMapOf("eligible_total" to sym("eligible"), "ineligible_total" to op(ResultOperator.NOT, sym("eligible")), "unknown_total" to op(ResultOperator.IS_NULL, sym("eligible")))
    val qualified = predicates.mapValues { (_, predicate) -> e.complete(input, PostFilter(op(ResultOperator.AND, predicate, nonempty)), regroup(listOf("subject", "day", "actor")), regroup(listOf("subject"), counted("actor", "quantity"))) }
    val aligned = e.align(linkedMapOf("period" to (period to linkedMapOf("period" to SetOutputContract.Quantity(count), "period_nonempty" to SetOutputContract.Quantity(count))),
        "daily" to (daily to mapOf("days_total" to SetOutputContract.Quantity(count)))), Calculation.PopulationKind.BASE, listOf("period"))
    val stages = mutableListOf<ValidatedOperation>()
    qualified.forEach { (name, _) -> stages += JoinInput(name, name, "LEFT", op(ResultOperator.EQ, sym("subject"), sym("${name}__subject")))
        stages += Derive(choose(op(ResultOperator.IS_NULL, sym("${name}__subject")), number("0"), sym("${name}__quantity")), name, "只为缺资格组给零") }
    stages += Project(listOf("subject", "period", "period_nonempty", "days_total") + predicates.keys)
    return QueryComputation.Scope(aligned.root, aligned.inputs + qualified.mapValues { relation(it.value) }, aligned.stages + stages)
}
val daysContract = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(), MetricValueContract.Decimal(20, 1, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
val savedMeanContract = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(), MetricValueContract.Decimal(25, 6, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
fun dailyAlgorithm(e: PopulationExample): SubjectAreaMetricRefRecord {
    val days = Calculation.Parameter(Calculation.ParameterKind.SCALAR, "NUMBER", emptyList(), null, daysContract)
    return e.savedAlgorithm("completed_per_days", MetricCalculation(Calculation(listOf(e.slot), emptyList(), emptyList(), listOf(Calculation.RelationSlot("completed", listOf(
        SetOutputContract("subject", SetOutputContract.DimensionSlot("subject")), SetOutputContract("quantity", SetOutputContract.Quantity(count))), listOf("subject"))), mapOf("days" to days),
        calc(Calculation.Relation("completed"), listOf(Derive(ResultNumeric(op(ResultOperator.DIVIDE, sym("quantity"), ResultArgument("days", ScalarValueType.NUMBER)), savedMeanContract, null), "value", "完成数量除明确请求天数"), Project(listOf("subject", "value"))))), "value", listOf("subject"), savedMeanContract))
}
fun savedDailyQuery(e: PopulationExample, metric: SubjectAreaMetricRefRecord, basis: QueryComputation.Scope): QueryComputation.Scope {
    val calls = listOf("days_total", "eligible_total", "ineligible_total", "unknown_total").map { column -> QueryComputation.Invocation(column, metric.instance, "${column}_average", mapOf("days" to Calculation.Scalar(ScalarValueType.NUMBER, "2")), emptyMap(),
        mapOf("completed" to CalculationBindings.LocalRelation("basis", mapOf("subject" to "subject", "quantity" to column))), DimensionCondition.Unrestricted()) }
    return QueryComputation.Scope(QueryComputation.Measures(calls, listOf(e.axis.reference), null, emptyMap(), DimensionCondition.Unrestricted(), DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(calls.map { it.callId }), null), mapOf("basis" to root(basis)), emptyList())
}
fun exactDailyQuery(basis: QueryComputation.Scope) = QueryComputation.Scope(basis.root, basis.inputs, basis.stages + listOf(
    Derive(ResultNumeric(op(ResultOperator.DIVIDE, sym("days_total"), number("19", daysContract)), exact(18, MetricValueContract.Rounding.HALF_UP), null), "mean", "显式十九日，不用活跃日期数"),
    Derive(ResultNumeric(op(ResultOperator.SUBTRACT, sym("days_total"), sym("period_nonempty")), count, null), "repeat_days", "跨日重复贡献"), Project(listOf("subject", "days_total", "period", "period_nonempty", "mean", "repeat_days"))))

fun scaledQuery(e: PopulationExample): QueryComputation.Scope {
    val hundred = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(), MetricValueContract.Decimal(4, 1, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
    fun scaled(input: String, source: String, target: String) = ResultNumeric(op(ResultOperator.DIVIDE, sym(input), number("100.0", hundred)), floating(target, mapOf("scale" to MetricValueContract.Scale(MetricValueContract.BinaryOperator.DIVIDE, unit(source), unit(target), "100.0"))), "scale")
    val ratio = floating("gross-per-actor", mapOf("divide" to MetricValueContract.FixedBinary(MetricValueContract.BinaryOperator.DIVIDE, unit("gross-report"), MetricValueContract.ONE, unit("gross-per-actor"))))
    return e.complete(e.raw("scaled"), Derive(scaled("gross", "gross-native", "gross-report"), "row_gross", "逐行原生浮点尺度转换"),
        Derive(scaled("adjustment", "adjustment-native", "adjustment-report"), "row_adjustment", "正式逻辑DOUBLE出口再逐行尺度转换"),
        regroup(listOf("subject"), sum("row_gross", "gross_total", floating("gross-report")), sum("row_adjustment", "adjustment_total", floating("adjustment-report")),
            sum("quantity", "quantity_total", floating("quantity")), counted("actor", "actors", true), counted("record_ref", "references", true),
            ResultAggregate("label", ResultAggregation.Maximum(ResultAggregation.NullPolicy.IGNORE), "maximum_label", "TEXT最大值不是时间赢家")),
        Derive(sym("maximum_label"), "label", "完成TEXT最大值"),
        Derive(ResultNumeric(op(ResultOperator.DIVIDE, sym("gross_total"), sym("actors")), ratio, "divide"), "ratio", "完成金额除完成去重数，保无guard政策"),
        Project(listOf("subject", "gross_total", "adjustment_total", "quantity_total", "references", "actors", "ratio", "label")))
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
        calc(Calculation.Relation("basis"), listOf(Derive(value, "value", "保存算法拥有guard与除法"), Project(listOf("subject", "value"))))), "value", listOf("subject"), spec.resultContract))
}
data class AttributeBasis(val input: QueryComputation.Scope, val quantities: Map<String, MetricValueContract>)
fun inlineAttributeQuery(e: PopulationExample): Pair<QueryComputation.Scope, Map<String, MetricValueContract>> {
    data class Contribution(val field: String, val eligibility: String, val negate: Boolean = false) { val output get() = "amount_$field" }
    val contributions = listOf(Contribution("a", "flag_b_count_a"), Contribution("b", "flag_b_count_b"), Contribution("c", "flag_b_count_b"),
        Contribution("d", "flag_b_count_b"), Contribution("e", "state_c_y"), Contribution("f", "state_c_y"), Contribution("g", "count_b_one", true),
        Contribution("h", "flag_a_count_a"), Contribution("i", "flag_a_count_a"), Contribution("j", "flag_a_count_a"),
        Contribution("k", "flag_a_count_b"), Contribution("l", "flag_a_count_b"), Contribution("m", "flag_a_count_b"))
    fun declared(member: String, field: String) = requireNotNull(MemberSourceDefinition.rootValueContract(e.outputs.getValue(member).getValue(field)))
    val baseQuantities = linkedMapOf("base_a" to declared("bases", "base_a"), "base_b" to declared("bases", "base_b"), "source_rows" to count)
    val original = e.raw("bases")
    val bases = e.complete(original, regroup(listOf("subject"), sum("base_a", "sum_base_a", baseQuantities.getValue("base_a")),
        sum("base_b", "sum_base_b", baseQuantities.getValue("base_b")), counted("subject", "source_rows")),
        Derive(sym("sum_base_a"), "base_a", "未关联属性的独立基数"), Derive(sym("sum_base_b"), "base_b", "未关联属性的独立基数"), Project(listOf("subject", "base_a", "base_b", "source_rows")))
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
    val bag = QueryComputation.Scope(original.root, mapOf("attributes" to relation(e.raw("attributes"))), original.stages + stages)
    val aligned = e.align(linkedMapOf("bases" to (bases to baseQuantities.mapValues { SetOutputContract.Quantity(it.value) }),
        "contributions" to (bag to bagQuantities.mapValues { SetOutputContract.Quantity(it.value) })), Calculation.PopulationKind.BASE, listOf("bases"))
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
    return QueryComputation.Scope(aligned.root, aligned.inputs, aligned.stages + finalStages) to outputs.associateWith { quantities.getValue(it) }
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
    val amounts = e.complete(original, regroup(listOf("subject"), sum("base_a", "sum_base_a", floating("base-a")), sum("base_b", "sum_base_b", floating("base-b")), counted("subject", "source_rows")),
        Derive(sym("sum_base_a"), "base_a", "未关联属性的独立基数"), Derive(sym("sum_base_b"), "base_b", "未关联属性的独立基数"), Project(listOf("subject", "base_a", "base_b", "source_rows")))
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
    val contributions = QueryComputation.Scope(original.root, mapOf("attributes" to relation(e.raw("attributes"))), original.stages + stages)
    val amountQuantities = linkedMapOf("base_a" to floating("base-a"), "base_b" to floating("base-b"), "source_rows" to count)
    val basis = e.align(linkedMapOf("amounts" to (amounts to amountQuantities.mapValues { SetOutputContract.Quantity(it.value) }), "contributions" to (contributions to quantities.mapValues { SetOutputContract.Quantity(it.value) })), Calculation.PopulationKind.BASE, listOf("amounts"))
    return AttributeBasis(basis, amountQuantities + quantities)
}
fun attributeDetails(e: PopulationExample, basis: QueryComputation.Scope, metrics: List<SubjectAreaMetricRefRecord>): QueryComputation.Scope {
    val calls = ratios.zip(metrics).map { (spec, metric) -> QueryComputation.Invocation(spec.name, metric.instance, "ratio_${spec.name}", emptyMap(), emptyMap(),
        mapOf("basis" to CalculationBindings.LocalRelation("basis", mapOf("subject" to "subject", "numerator" to spec.name, "denominator" to spec.denominator))), DimensionCondition.Unrestricted()) }
    val values = QueryComputation.Scope(QueryComputation.Measures(calls, listOf(e.axis.reference), null, emptyMap(), DimensionCondition.Unrestricted(), DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(calls.map { it.callId }), null), mapOf("basis" to root(basis)), emptyList())
    return QueryComputation.Scope(basis.root, basis.inputs + ("ratios" to relation(values)), basis.stages + listOf(JoinInput("ratios", "r", "LEFT", op(ResultOperator.EQ, sym("subject"), sym("r__subject")))))
}
fun attributeReport(details: QueryComputation.Scope): QueryComputation.Scope {
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
    return QueryComputation.Scope(details.root, details.inputs, details.stages + stages)
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
fun reuseMembers(name: String, columns: List<String>, condition: MemberCondition) = calc(Calculation.Members(name, name, columns.map { Calculation.MemberProjection(it, it) }, null, emptyMap(), condition))
fun reuseResult(calculation: Calculation, quantities: Map<String, Pair<String, MetricValueContract>>, subjectSymbol: String, shape: MemberShape, description: String): MemberSourceDefinition.Computed {
    val outputs = linkedMapOf<String, MemberSourceDefinition.Output>("subject" to MemberSourceDefinition.ComputedDimensionOutput("对象", "subject", ScalarValueType.TEXT, "subject", subjectSymbol, null))
    quantities.forEach { (name, value) -> outputs[name] = MemberSourceDefinition.ComputedPropertyOutput(name, name, ScalarValueType.NUMBER, value.first, value.second) }
    return MemberSourceDefinition.Computed(calculation, MemberResult("population_subject", "subject", shape, listOf("subject"), outputs, emptyMap(), null, emptyMap(), description))
}
fun paidDefinition(e: PopulationExample): MemberSourceDefinition.Computed {
    val columns = listOf("subject", "day", "first_flag", "actor")
    val rows = reuseMembers("activity", columns, MemberCondition.All(listOf(reusePeriodCondition(), condition("first_flag", ScalarValueType.BOOLEAN, MemberCondition.Operator.EQ, "true"))))
    val body = Calculation.Scope(rows.root, rows.inputs, listOf(PostFilter(nonnull("subject")), regroup(listOf("subject"), counted("actor", "paid_actors", true)),
        PostFilter(op(ResultOperator.GT, sym("paid_actors"), number("0", count))), Project(listOf("subject"))))
    return reuseResult(Calculation(listOf(e.slot), listOf(reuseSlot(e, "activity", "activity", columns)), emptyList(), emptyList(), reusePeriod, body),
        emptyMap(), "subject", MemberShape.UNIQUE_MEMBERS, "明确期间内first_flag为TRUE且至少一个非NULL actor；不新增actor空串政策")
}
fun visitedDefinition(e: PopulationExample): MemberSourceDefinition.Computed {
    val visits = listOf("item", "actor", "day"); val catalog = listOf("subject", "item", "current_partition")
    val rows = reuseMembers("visits", visits, reusePeriodCondition())
    val directory = reuseMembers("catalog", catalog, condition("current_partition", ScalarValueType.BOOLEAN, MemberCondition.Operator.EQ, "true"))
    val body = Calculation.Scope(rows.root, mapOf("directory" to Calculation.InputScope("RELATION", directory)), listOf(
        JoinInput("directory", "d", "LEFT", op(ResultOperator.EQ, sym("item"), sym("d__item"))), PostFilter(nonnull("d__subject")),
        regroup(listOf("d__subject"), counted("actor", "visit_actors", true)), PostFilter(op(ResultOperator.GT, sym("visit_actors"), number("0", count))), Project(listOf("d__subject"))))
    return reuseResult(Calculation(listOf(e.slot), listOf(reuseSlot(e, "visits", "visits", visits), reuseSlot(e, "catalog", "catalog", catalog)), emptyList(), emptyList(), reusePeriod, body),
        emptyMap(), "d__subject", MemberShape.UNIQUE_MEMBERS, "期间访问按当前目录归属，有非NULL actor；目录eligible不改变访问人口，不声称历史归属")
}
fun activityDefinition(e: PopulationExample): MemberSourceDefinition.Computed {
    val columns = listOf("subject", "day", "amount")
    val rows = reuseMembers("activity", columns, reusePeriodCondition())
    val amount = requireNotNull(MemberSourceDefinition.rootValueContract(e.outputs.getValue("activity").getValue("amount")))
    val body = Calculation.Scope(rows.root, rows.inputs, listOf(PostFilter(nonnull("subject")), regroup(listOf("subject"), sum("amount", "amount_total", amount), counted("subject", "records"), counted("amount", "known"))))
    return reuseResult(Calculation(listOf(e.slot), listOf(reuseSlot(e, "activity", "activity", columns)), emptyList(), emptyList(), reusePeriod, body),
        linkedMapOf("amount" to ("amount_total" to amount), "records" to ("records" to count), "known" to ("known" to count)), "subject", MemberShape.RECORDS,
        "期间活动原袋按完整subject完成金额/来源行/金额非NULL行，真实组全NULL与无组分别保留")
}
fun catalogDefinition(e: PopulationExample, requireCaption: Boolean): MemberSourceDefinition.Computed {
    val columns = listOf("subject", "item", "current_partition", "eligible", "caption_accepted")
    val rows = reuseMembers("catalog", columns, condition("current_partition", ScalarValueType.BOOLEAN, MemberCondition.Operator.EQ, "true"))
    val policy = if (requireCaption) op(ResultOperator.AND, sym("eligible"), sym("caption_accepted")) else sym("eligible")
    val body = Calculation.Scope(rows.root, rows.inputs, listOf(PostFilter(nonnull("subject")), Derive(choose(policy, sym("item")), "qualified_item", "目录资格只取完整谓词TRUE"),
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
        val member = e.members.getValue("period_activity").definition.result
        val slot = Calculation.MemberSlot("completed", member.entity, member.role, member.shape,
            mapOf("subject" to Calculation.MemberDimension("subject", null), "quantity" to Calculation.MemberProperty(member.outputs.getValue(field).role, ScalarValueType.NUMBER, count)), null)
        val rows = reuseMembers("completed", listOf("subject", "quantity"), MemberCondition.Unrestricted())
        val definition = MetricCalculation(Calculation(listOf(e.slot), listOf(slot), emptyList(), emptyList(), reusePeriod,
            Calculation.Scope(rows.root, rows.inputs, listOf(Derive(sym("quantity"), "value", displayName), Project(listOf("subject", "value"))))), "value", listOf("subject"), count)
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
    e.createAxis()
}
fun references(query: QueryReference, result: QueryResultReference, captionQuery: QueryReference, captionResult: QueryResultReference) = ScriptValue.ObjectValue(mapOf(
    "query" to ScriptValue.QueryReferenceValue(query), "result" to ScriptValue.ResultReferenceValue(result),
    "captionQuery" to ScriptValue.QueryReferenceValue(captionQuery), "captionResult" to ScriptValue.ResultReferenceValue(captionResult)))
fun build(): ScriptValue.ObjectValue {
    val e = PopulationExample(); installInputs(e)
    val inline = e.publication("inline")
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
    e.savedContext()
    val daily = dailyBasis(e)
    val mean = dailyAlgorithm(e)
    val ratioMetrics = ratios.map { ratioAlgorithm(e, it) }
    val saved = e.publication("saved", listOf(mean) + ratioMetrics)
    expect(e.execute(saved, "每日完成后四次复用同一保存算法", savedDailyQuery(e, mean, daily), QueryExecutionMode.CORE_FINALIZATION).rows, mapOf("A" to mapOf("days_total_average" to "1.5", "eligible_total_average" to "1", "ineligible_total_average" to "0.5", "unknown_total_average" to "0"), "B" to mapOf("days_total_average" to "0", "eligible_total_average" to "0", "ineligible_total_average" to "0", "unknown_total_average" to "0")))
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
    return ScriptValue.ObjectValue(references(primary.query, primary.result, captions.query, captions.result).values + reusePublications(e))
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
