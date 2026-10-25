import com.olap.prototype.calculation.*
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.dsl.*
import com.olap.prototype.dsl.transport.CapabilityFailureException
import com.olap.prototype.engine.executable.ExecutableBusinessResult
import com.olap.prototype.event.*
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.governance.contract.*
import com.olap.prototype.metric.*
import com.olap.prototype.query.*
import com.olap.prototype.query.ResultExpression.*
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.definition.QueryDefinitionCommand
import com.olap.prototype.source.contract.*
import com.olap.prototype.warehouse.contract.*
import com.olap.prototype.warehouse.inspection.*
import com.olap.prototype.warehouse.onboarding.*
import java.math.BigDecimal

fun fields(vararg values: Pair<String, ScriptValue>) = ScriptValue.ObjectValue(mapOf(*values))
fun text(value: String) = ScriptValue.Text(value)
fun reference(name: String): QueryResultReference = QueryResultReference(parameters.text(name + "RunId"), parameters.text(name + "Sha256"))
fun scalar(value: ResultValueView): ExecutableBusinessResult.ScalarValue =
    (value as ResultValueView.Complete).value as ExecutableBusinessResult.ScalarValue
fun verify(reference: QueryResultReference): ResultPage {
    val page = olap.results.read(ResultReadRequest(reference, 0, 10, null))
    check(page.totalRows == 1 && page.rows.size == 1 && page.nextOffset == null)
    check(page.columns.map { it.symbol.name } == listOf("sales_amount", "order_count"))
    val (amount, count) = page.rows.single().map(::scalar)
    // 独立参照来自消费脚本写入的实际订单，不由计算定义推导。
    check(amount.type == ExecutableBusinessResult.ValueKind.DECIMAL && BigDecimal(amount.value).compareTo(BigDecimal("175")) == 0)
    check(count.type == ExecutableBusinessResult.ValueKind.INTEGER && count.value == "4")
    return page
}
fun execute(intent: QueryIntent, question: String): QueryExecutionReceipt =
    olap.queries.execute(QueryExecutionRequest(QuerySelection.Inline(intent), true, question, null)).also {
        check(it.status == QueryExecutionStatus.SUCCEEDED && it.saved) { it.error.toString() }
        verify(requireNotNull(it.resultReference))
    }
fun computation(entry: PublishedQueryEntry, unauthorized: Boolean = false,
    codes: List<String> = listOf("sales_amount", "order_count")): QueryComputation.Scope {
    val calls = codes.map { code ->
        val instance = entry.capability.metricCandidates.single { it.code == code }.instance
        QueryComputation.Invocation(code, if (unauthorized && code == "sales_amount") MetricInstanceRef("metric-instance-" + "0".repeat(64)) else instance,
            code, emptyMap(), emptyMap(), emptyMap(), DimensionCondition.Unrestricted())
    }
    return scope(QueryComputation.Measures(calls, emptyList(), null, emptyMap(), DimensionCondition.Unrestricted(),
        DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(calls.map { it.callId }), null))
}

val actor = olap.caller.subject
fun activate(type: GovernanceActivationObjectType, id: String, version: Long = 1) {
    olap.governance.activate(GovernanceVersionActivationRequest(type, id, version, actor, "安装验收明确激活精确版本"))
}
olap.output(when (parameters.text("phase")) {
    "create" -> {
        check(olap.sources.list(EmptyRequest).items.isEmpty())
        val asset = olap.governance.createBusinessLine(BusinessLineCreateRequest(
            "installed_product", "安装后的实际治理资产", actor, "npm包真实创建与重启读取", actor)).businessLine
        fields("businessLineId" to text(asset.id))
    }
    "structure" -> {
        val source = olap.sources.list(EmptyRequest).items.single { it.binding.dataSourceId == "business" }
        val discovery = olap.sources.inspect(SourceInspection.Discover(source.binding, null, "PUBLIC", null, emptyList(), 20))
            as SourceInspectionResult.Discovery
        check(discovery.value.tables.any { it.ref.table == "SALES_ORDERS" })
        val columns = listOf(
            WarehouseColumnMetadata("ORDER_ID", -5, "BIGINT", 1, false, 19, 0, null, null, null, null),
            WarehouseColumnMetadata("AMOUNT", 3, "DECIMAL", 2, true, 18, 2, null, null, null, null),
            WarehouseColumnMetadata("CALENDAR_DAYS", 4, "INTEGER", 3, true, 10, 0, null, null, null, null)
        )
        val table = SourceStructureRegistrationRequest.Table(
            WarehouseTableSummary(WarehouseTableRef(null, "PUBLIC", "SALES_ORDERS"), "TABLE", null), columns,
            JdbcTableConstraints(listOf("ORDER_ID"), emptyList(), emptyList(), emptyList()))
        val stored = olap.governance.registerSourceStructure(SourceStructureRegistrationRequest(
            source.binding.dataSourceId, source.binding.sourceIdentityHash, null, "PUBLIC", "installed-product-configuration",
            "sales-schema-v1", listOf(table), actor))
        val snapshot = olap.semantics.sourceDiscovery(GovernanceReadRequests.SnapshotInput(stored.snapshotId))
        check(requireNotNull(snapshot.structureProvenance).kind == StructureProvenance.Kind.DECLARED && snapshot.driverName == null)
        val businessLine = olap.semantics.businessLines(GovernanceReadRequests.PageInput(0, 20)).items.single { it.id == parameters.text("businessLineId") }
        fields("snapshotId" to text(stored.snapshotId), "sourceIdentityHash" to text(source.binding.sourceIdentityHash),
            "businessLineId" to text(businessLine.id))
    }
    "build" -> {
        val snapshotId = parameters.text("snapshotId")
        val snapshot = olap.semantics.sourceDiscovery(GovernanceReadRequests.SnapshotInput(snapshotId))
        check(requireNotNull(snapshot.structureProvenance).kind == StructureProvenance.Kind.DECLARED)
        val observed = olap.semantics.discoveredTables(GovernanceReadRequests.SnapshotPageInput(snapshotId, 0, 20)).items.single()
        val columns = olap.semantics.discoveredColumns(GovernanceReadRequests.DiscoveredTablePageInput(observed.id, 0, 20)).items
        check(columns.size == 3 && columns.all { it.autoIncrement == null && it.generated == null })
        val physical = olap.governance.createPhysicalTable(PhysicalTableCreateRequest(
            "sales_orders", "SALES_ORDERS", "销售订单", "安装后真实H2订单", "sales", actor, "PUBLIC", "installed-sales", "business",
            observed.catalogName, observed.schemaName,
            PhysicalTableCreateRequest.SourceAdoptionInput(observed.id, emptyList()), actor,
            columns.map { PhysicalTableCreateRequest.ColumnInput(it.columnName, it.columnName, it.typeName, "PUBLIC", requireNotNull(it.nullable),
                "订单原始字段", JdbcColumnType(it.jdbcType, it.typeName, it.precision, it.scale, it.nullable)) }))
        val sourceAdoptions = physical.sourceAdoptions.references
        activate(GovernanceActivationObjectType.PHYSICAL_TABLE, physical.physicalTable.id)
        val logical = olap.governance.createLogicalTable(LogicalTableCreateRequest(physical.physicalTable.id, 1, "sales_orders", "销售订单",
            LogicalTableRole.FACT, "一条记录是一笔订单", actor, actor,
            physical.physicalColumns.map { column -> LogicalTableCreateRequest.LogicUnitInput(column.columnName, column.columnName,
                LogicUnitSourceKind.COLUMN, null, LogicType.NUMBER, null, false, column.columnName == "ORDER_ID", false, false, true,
                listOf(LogicalTableCreateRequest.SourceRefInput(column.id, 1, LogicUnitSourceRefRole.DIRECT_SOURCE))) }))
        activate(GovernanceActivationObjectType.LOGICAL_TABLE, logical.logicalTable.id)
        val units = logical.logicUnits.associateBy { it.code }
        val topology = olap.governance.createTopology(TopologyCreateRequest("orders", "销售订单事实", "installed.sales", "安装验收销售事实",
            logical.logicalTable.id, 1, actor, "单表订单事实", actor)).topology
        activate(GovernanceActivationObjectType.TOPOLOGY, topology.id)
        val event = olap.governance.createEvent(EventCreateRequest(topology.id, 1, "registered", "已登记订单", actor, null, null,
            EventIdentity(listOf(EventIdentity.LogicUnitRef(units.getValue("ORDER_ID").id, 1)), emptyMap()), emptyMap(), emptyList(),
            units.values.map { EventCreateRequest.LogicUnitRefInput(it.id, 1) }, "按原订单记录汇总，不声明未知完成状态", actor)).event
        activate(GovernanceActivationObjectType.EVENT, event.id)
        val amount = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.UnitIdentity("iso4217", "CNY", 1)),
            MetricValueContract.Exact(), MetricValueContract.Decimal(38, 2, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
        val count = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(),
            MetricValueContract.Integer(64), emptyMap(), MetricValueContract.Overflow.FAIL)
        val day = MetricValueContract.UnitIdentity("example", "calendar-day", 1)
        val days = MetricValueContract(MetricValueContract.Fixed(day), MetricValueContract.Exact(),
            MetricValueContract.Integer(64), emptyMap(), MetricValueContract.Overflow.FAIL)
        val specifications = listOf(
            Triple("sales_amount", MetricDefinition.FactMeasure(EventRef(event.id, 1), SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT,
                units.getValue("AMOUNT").id, 1), MetricAggregation.SUM, false, null, emptyMap(), null, amount),
                MetricBusinessMeaning("所有已登记订单的金额之和", "CNY", "订单", listOf("不含退款及状态限定"))),
            Triple("order_count", MetricDefinition.FactMeasure(EventRef(event.id, 1), SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT,
                units.getValue("ORDER_ID").id, 1), MetricAggregation.COUNT, false, null, emptyMap(), null, count),
                MetricBusinessMeaning("已登记订单记录数", "单", "订单", listOf("不作客户去重"))),
            Triple("day_sum", MetricDefinition.FactMeasure(EventRef(event.id, 1), SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT,
                units.getValue("CALENDAR_DAYS").id, 1), MetricAggregation.SUM, false, null, emptyMap(), null, days),
                MetricBusinessMeaning("订单记录日历天数之和", "日历天", "订单", emptyList())),
            Triple("valid_count", MetricDefinition.FactMeasure(EventRef(event.id, 1), SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT,
                units.getValue("CALENDAR_DAYS").id, 1), MetricAggregation.COUNT, false, null, emptyMap(), null, count),
                MetricBusinessMeaning("有非空日历天数的订单数", "单", "订单", emptyList()))
        )
        val metrics = specifications.map { (code, definition, meaning) ->
            olap.governance.createMetric(MetricCreateRequest(code, code, actor, definition, meaning.definition, meaning, actor)).metric.also {
                activate(GovernanceActivationObjectType.METRIC, it.id)
            }
        }
        val businessLineId = parameters.text("businessLineId")
        activate(GovernanceActivationObjectType.BUSINESS_LINE, businessLineId)
        val subjectDefinition = SubjectAreaCreateRequest(businessLineId, 1, "sales", "销售经营", actor, "安装后订单分析", actor)
        val subject = olap.governance.createSubjectArea(subjectDefinition).subjectArea
        val adoptions = metrics.zip(specifications).map { (metric, specification) ->
            olap.governance.createSubjectAreaMetricRef(SubjectAreaMetricRefCreateRequest(subject.id, 1, metric.code, metric.displayName,
                specification.third, metric.id, 1, CalculationBindings.empty(), emptyMap(), actor)).subjectAreaMetricRef
        }
        val scenario = olap.governance.createScenario(ScenarioCreateRequest(subject.id, 1, "orders", "订单经营分析", actor, "公开销售额和订单数", actor)).scenario
        adoptions.forEachIndexed { index, adoption ->
            olap.governance.createScenarioMetricRef(ScenarioMetricRefCreateRequest(scenario.id, 1, adoption.id, 1, true, index + 1))
        }
        olap.governance.createScenarioPipelineRule(ScenarioPipelineRuleCreateRequest(scenario.id, 1, "DERIVE", 1, StageAuthorization.current("DERIVE")))
        activate(GovernanceActivationObjectType.SUBJECT_AREA, subject.id)
        olap.governance.buildRelease(SubjectAreaReleaseBuildRequest("installed-sales", "1", "安装后销售口径", subject.id, 1, actor, sourceAdoptions))
        val coordinate = QueryCapabilityRequest(scenario.id, 1, "installed-sales", "1")
        val publication = olap.semantics.publication(coordinate)
        val semanticMatches = olap.semantics.search(com.olap.prototype.query.contract.catalog.SemanticCatalogReads.SearchRequest(
            coordinate, "", null, 0, 1000))
        val maximumMatches = olap.semantics.search(com.olap.prototype.query.contract.catalog.SemanticCatalogReads.SearchRequest(
            coordinate, "", null, 0, Int.MAX_VALUE))
        check(semanticMatches.items == maximumMatches.items && maximumMatches.total == maximumMatches.items.size && maximumMatches.nextOffset == null)
        check(maximumMatches.items.isNotEmpty())
        val pastMatches = olap.semantics.search(com.olap.prototype.query.contract.catalog.SemanticCatalogReads.SearchRequest(
            coordinate, "", null, Int.MAX_VALUE, Int.MAX_VALUE))
        check(pastMatches.items.isEmpty() && pastMatches.total == maximumMatches.total && pastMatches.nextOffset == null)
        val intent = QueryIntent(coordinate, computation(publication))
        val rejected = runCatching { olap.queries.validate(QueryValidationRequest(QuerySelection.Inline(QueryIntent(coordinate, computation(publication, true))))) }.exceptionOrNull()
        check(rejected is CapabilityFailureException && rejected.failure.code == "EXECUTABLE_QUERY_NOT_PUBLISHED") { "未发布实例必须被正式授权拒绝：$rejected" }
        check(olap.queries.validate(QueryValidationRequest(QuerySelection.Inline(intent))).valid)
        val explanation = olap.queries.explain(QueryExplanationRequest(QuerySelection.Inline(intent),
            QueryExplanationSection.EXECUTION_REQUIREMENTS, 0, 20))
        val requirements = requireNotNull((explanation.content as QueryExplanationContent.ExecutionRequirements).value)
        val adoptedSource = requirements.sourceConstraints.single { it.physicalTableId == physical.physicalTable.id }
        val orderColumnVersion = physical.physicalColumns.single { it.columnName == "ORDER_ID" }.id + "@1"
        check(adoptedSource.physicalTableVersion == "1" && adoptedSource.binding.dataSourceId == "business")
        check(adoptedSource.schema == "PUBLIC" && adoptedSource.table == "SALES_ORDERS")
        val notNull = adoptedSource.constraints.single { it.kind == com.olap.prototype.contract.JdbcSourceRequirement.ConstraintKind.NOT_NULL }
        check(notNull.name == "ORDER_ID" && notNull.columns == mapOf("ORDER_ID" to orderColumnVersion))
        check(notNull.adoptedFor == "NON_NULL_NATIVE_COLUMN_TYPE" && !requirements.sourceCapabilitiesChecked)
        val summary = olap.queries.explain(QueryExplanationRequest(QuerySelection.Inline(intent),
            QueryExplanationSection.SUMMARY, 0, 1000)).content as QueryExplanationContent.Summary
        val allScopes = olap.queries.explain(QueryExplanationRequest(QuerySelection.Inline(intent),
            QueryExplanationSection.SCOPES, 0, Int.MAX_VALUE)).content as QueryExplanationContent.Scopes
        check(allScopes.items.size == summary.value.scopeCount && allScopes.totalItems == allScopes.items.size && allScopes.nextOffset == null)
        val firstScopes = olap.queries.explain(QueryExplanationRequest(QuerySelection.Inline(intent),
            QueryExplanationSection.SCOPES, 0, 1)).content as QueryExplanationContent.Scopes
        check(firstScopes.items == allScopes.items.take(1))
        val continuedScopes = firstScopes.nextOffset?.let { next ->
            olap.queries.explain(QueryExplanationRequest(QuerySelection.Inline(intent),
                QueryExplanationSection.SCOPES, next, Int.MAX_VALUE)).content as QueryExplanationContent.Scopes
        }
        check(firstScopes.items + continuedScopes?.items.orEmpty() == allScopes.items && continuedScopes?.nextOffset == null)
        val pastScopes = olap.queries.explain(QueryExplanationRequest(QuerySelection.Inline(intent),
            QueryExplanationSection.SCOPES, Int.MAX_VALUE, Int.MAX_VALUE)).content as QueryExplanationContent.Scopes
        check(pastScopes.items.isEmpty() && pastScopes.totalItems == allScopes.totalItems && pastScopes.nextOffset == null)
        val saved = olap.queries.save(QueryDefinitionCommand("installed-sales-summary", null, requireNotNull(publication.capability.capabilityHash),
            "销售额与订单数", intent, null))
        val execution = execute(intent, "全部已登记订单销售额和订单数是多少？")
        val savedScopePage = olap.results.metadata(ResultMetadataRequest(requireNotNull(execution.resultReference),
            ResultMetadataSection.EXPLANATION, null, QueryExplanationSection.SCOPES, 0, Int.MAX_VALUE))
        val savedScopes = (savedScopePage.content as ResultMetadataContent.Explanation).value as QueryExplanationContent.Scopes
        check(savedScopes.items == allScopes.items && savedScopePage.totalItems == allScopes.totalItems && savedScopePage.nextOffset == null)
        val savedColumnPage = olap.results.metadata(ResultMetadataRequest(requireNotNull(execution.resultReference),
            ResultMetadataSection.COLUMNS, null, null, 0, Int.MAX_VALUE))
        check((savedColumnPage.content as ResultMetadataContent.Columns).values.map { it.symbol.name } == listOf("sales_amount", "order_count"))
        check(savedColumnPage.totalItems == 2 && savedColumnPage.nextOffset == null)
        val observedRun = olap.results.run(QueryRunRequest(execution.runId, 0, 1000)).run
        check(observedRun.receipt?.resultReference == execution.resultReference)
        check(requireNotNull(observedRun.serviceObservation).workers.isNotEmpty())
        val mean = MetricValueContract(MetricValueContract.Fixed(day), MetricValueContract.Exact(),
            MetricValueContract.Decimal(28, 18, MetricValueContract.Rounding.HALF_UP), mapOf("days_per_valid_contribution" to
                MetricValueContract.FixedBinary(MetricValueContract.BinaryOperator.DIVIDE, day, MetricValueContract.ONE, day)), MetricValueContract.Overflow.FAIL)
        val division = ResultOperation(ResultOperator.DIVIDE, listOf(ResultSymbol("day_sum"), ResultSymbol("valid_count")))
        val declaredDivision = ResultNumeric(division, mean, "days_per_valid_contribution")
        val zero = ResultQuantityLiteral(BigDecimal.ZERO, count)
        val positiveCount = ResultOperation(ResultOperator.GT, listOf(ResultSymbol("valid_count"), zero))
        fun guarded(value: ResultExpression) = ResultCaseWhen(listOf(ResultCaseBranch(positiveCount, value)), ResultLiteral(null))
        val dayRoot = computation(publication, codes = listOf("day_sum", "valid_count")).root
        for ((bad, code, tail) in listOf(
            Triple(guarded(ResultNumeric(ResultRound(division, 18, MetricValueContract.Rounding.HALF_UP), mean, "days_per_valid_contribution")),
                "RESULT_UNIT_EQUATION_REQUIRED", listOf("branches", "0", "then", "expression", "expression")),
            Triple(guarded(ResultRound(declaredDivision, 18, MetricValueContract.Rounding.HALF_UP)),
                "RESULT_NUMERIC_CONTRACT_REQUIRED", listOf("branches", "0", "then")))) {
            val invalid = QueryIntent(coordinate, scope(dayRoot) { stage(PostOperationContract.Derive(bad, "mean_days", "平均日历天数")) })
            val failure = runCatching { olap.queries.validate(QueryValidationRequest(QuerySelection.Inline(invalid))) }.exceptionOrNull()
            check(failure is CapabilityFailureException && failure.failure.code == code) { "应交付可定位的作者错误：$failure" }
            check(failure.failure.stage == "ANALYZE_STAGES" && "postOps" in failure.failure.fieldPath && failure.failure.fieldPath.takeLast(tail.size) == tail) {
                "作者错误定位不完整：code=$code stage=${failure.failure.stage} fieldPath=${failure.failure.fieldPath} expectedTail=$tail"
            }
        }
        val ratio = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(),
            MetricValueContract.Decimal(28, 18, MetricValueContract.Rounding.HALF_UP), mapOf("ratio" to
                MetricValueContract.FixedBinary(MetricValueContract.BinaryOperator.DIVIDE, MetricValueContract.ONE, MetricValueContract.ONE, MetricValueContract.ONE)),
            MetricValueContract.Overflow.FAIL)
        val numericIntent = QueryIntent(coordinate, scope(dayRoot) {
            stage(PostOperationContract.Derive(guarded(ResultNumeric(ResultRound(declaredDivision, 18, MetricValueContract.Rounding.HALF_UP), mean, null)), "mean_days", "平均日历天数"))
            stage(PostOperationContract.Derive(ResultNumeric(ResultOperation(ResultOperator.DIVIDE, listOf(
                ResultQuantityLiteral(BigDecimal("-9223372036854775808"), count), ResultQuantityLiteral(BigDecimal("1000000000000000000"), count))), ratio, "ratio"), "wide_ratio", "完整整数域的商"))
            stage(PostOperationContract.Derive(ResultNumeric(ResultOperation(ResultOperator.SAFE_DIVIDE, listOf(ResultSymbol("day_sum"), zero)), mean,
                "days_per_valid_contribution"), "zero_ratio", "零分母保留NULL"))
        })
        val numericResult = olap.queries.execute(QueryExecutionRequest(QuerySelection.Inline(numericIntent), true, "同请求整数SUM/COUNT、完整BIGINT域与零分母", null))
        check(numericResult.status == QueryExecutionStatus.SUCCEEDED && numericResult.saved) { numericResult.error.toString() }
        val numericRun = olap.results.run(QueryRunRequest(numericResult.runId, 0, 20)).run
        val numericObservation = requireNotNull(numericRun.serviceObservation)
        check(numericObservation.workers.isNotEmpty() && numericObservation.workers.all { it.outcome == "REUSED" }) {
            "三槽交替解析和执行应复用已有精确配置进程：${numericObservation.workers}"
        }
        check(listOf("RESULT_VERIFY", "RESULT_CREATE", "RESULT_WRITE", "RESULT_SYNC", "RESULT_COMMIT", "RESULT_DIRECTORY_SYNC", "RESULT_CLEANUP")
            .all { numericObservation.timingNanos[it]?.let { nanos -> nanos >= 0 } == true })
        val coreIntent = QueryIntent(coordinate, QueryComputation.Scope(numericIntent.computation.root,
            numericIntent.computation.inputs, numericIntent.computation.stages + PostOperationContract.Derive(
                ResultNumeric(ResultOperation(ResultOperator.DIVIDE, listOf(ResultSymbol("day_sum"),
                    ResultQuantityLiteral(BigDecimal("3"), count))), mean, "days_per_valid_contribution"),
                "third_of_days", "来源完成天数和的三分之一，核心量化")))
        check(olap.queries.validate(QueryValidationRequest(QuerySelection.Inline(coreIntent), QueryExecutionMode.CORE_FINALIZATION)).valid)
        val coreExplanation = olap.queries.explain(QueryExplanationRequest(QuerySelection.Inline(coreIntent),
            QueryExplanationSection.SCOPES, 0, 100, QueryExecutionMode.CORE_FINALIZATION)).content as QueryExplanationContent.Scopes
        check(coreExplanation.items.flatMap { it.stages }.all { it.executionLocation == QueryExplanationView.ExecutionLocation.CORE })
        val coreResult = olap.queries.execute(QueryExecutionRequest(QuerySelection.Inline(coreIntent), true,
            "同源完成数量经核心终算并保存无损结果", null, QueryExecutionMode.CORE_FINALIZATION))
        check(coreResult.status == QueryExecutionStatus.SUCCEEDED && coreResult.saved &&
            coreResult.executionMode == QueryExecutionMode.CORE_FINALIZATION && coreResult.sourceState == SourceExecutionState.SUCCEEDED) { coreResult.error.toString() }
        val corePage = olap.results.read(ResultReadRequest(requireNotNull(coreResult.resultReference), 0, 10, null))
        check(corePage.totalRows == 1 && corePage.rows.size == 1 && corePage.nextOffset == null)
        val coreValues = corePage.columns.map { it.symbol.name }.zip(corePage.rows.single()).toMap()
        check(scalar(coreValues.getValue("mean_days")).value == "30.250000000000000000")
        check(scalar(coreValues.getValue("third_of_days")).value == "40.333333333333333333")
        check((coreValues.getValue("zero_ratio") as ResultValueView.Complete).value is ExecutableBusinessResult.NullValue)
        val coreRun = olap.results.run(QueryRunRequest(coreResult.runId, 0, 20)).run
        check(coreRun.receipt?.executionMode == QueryExecutionMode.CORE_FINALIZATION && coreRun.receipt?.resultReference == coreResult.resultReference)
        check(requireNotNull(coreRun.serviceObservation).workers.let { it.isNotEmpty() && it.all { worker -> worker.outcome == "REUSED" } })
        val carry = olap.semantics.contextSelection(SubjectContextSelectionRequest(subject.id, 1)).selection
        olap.governance.reviseSubjectArea(SubjectContextRevisionRequest(subject.id, 1, subjectDefinition, carry))
        activate(GovernanceActivationObjectType.SUBJECT_AREA, subject.id, 2)
        olap.governance.buildRelease(SubjectAreaReleaseBuildRequest("installed-sales", "2", "延续订单分析", subject.id, 2, actor, sourceAdoptions))
        val nextCoordinate = QueryCapabilityRequest(scenario.id, 2, "installed-sales", "2")
        val nextPublication = olap.semantics.publication(nextCoordinate)
        val continued = execute(QueryIntent(nextCoordinate, computation(nextPublication)), "修订上下文后保持订单口径")
        fields("query" to ScriptValue.QueryReferenceValue(saved.reference),
            "result" to ScriptValue.ResultReferenceValue(requireNotNull(execution.resultReference)),
            "continuedResult" to ScriptValue.ResultReferenceValue(requireNotNull(continued.resultReference)), "scenarioId" to text(scenario.id),
            "subjectAreaId" to text(subject.id), "orderColumnVersion" to text(orderColumnVersion),
            "logicalTableId" to text(logical.logicalTable.id), "orderUnitId" to text(units.getValue("ORDER_ID").id),
            "amountUnitId" to text(units.getValue("AMOUNT").id), "daysUnitId" to text(units.getValue("CALENDAR_DAYS").id),
            "numericResult" to ScriptValue.ResultReferenceValue(requireNotNull(numericResult.resultReference)),
            "coreResult" to ScriptValue.ResultReferenceValue(requireNotNull(coreResult.resultReference)))
    }
    "reopen" -> {
        val query = QueryReference(parameters.text("definitionId"), parameters.text("definitionVersion").toLong(), parameters.text("querySha256"))
        val original = reference("result")
        verify(original)
        check(olap.queries.read(QueryVersionRequest(query)).reference == query)
        val repeated = olap.queries.execute(QueryExecutionRequest(QuerySelection.Saved(query), true, "重启后按同一发布重新计算销售额与订单数", null))
        check(repeated.status == QueryExecutionStatus.SUCCEEDED && repeated.saved && repeated.runId != original.runId)
        verify(requireNotNull(repeated.resultReference))
        fields("query" to ScriptValue.QueryReferenceValue(query), "result" to ScriptValue.ResultReferenceValue(requireNotNull(repeated.resultReference)))
    }
    else -> error("未知安装验收阶段")
})
