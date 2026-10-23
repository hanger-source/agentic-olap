package com.olap.prototype.management.governance

import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.calculation.*
import com.olap.prototype.connector.JdbcAccess
import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.connector.SourceProvider
import com.olap.prototype.engine.OlapEngine
import com.olap.prototype.engine.catalog.ExecutablePostOperationCapabilities
import com.olap.prototype.engine.compiler.OlapSemanticCompiler
import com.olap.prototype.engine.executable.*
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.event.EventRef
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.governance.application.ReleaseFreezer
import com.olap.prototype.governance.application.SubjectAreaReleaseBuildService
import com.olap.prototype.governance.contract.*
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.management.warehouse.*
import com.olap.prototype.metric.MetricBusinessMeaning
import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.*
import com.olap.prototype.query.contract.*
import com.olap.prototype.release.catalog.*
import com.olap.prototype.release.catalog.capability.*
import com.olap.prototype.release.contract.*
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.contract.*
import com.olap.prototype.warehouse.inspection.*
import com.olap.prototype.warehouse.jdbc.JdbcConnectionFactory
import com.olap.prototype.warehouse.jdbc.JdbcSourceInspection
import com.olap.prototype.warehouse.onboarding.*
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/** 既有治理链唯一作者fixture；不计算查询答案，不替产品冻结/授权。 */
internal class GovernanceOrderFixture(val database: PlatformDatabase, val directory: Path,
    private val initialOrders: List<String> = emptyList(), private val sourceKeys: List<SnapshotUniqueKey.Rule> = emptyList(),
    val connectors: ConnectorRegistry = TestConnectors.registry, private val deployedSource: SourceConfiguration? = null,
    private val sourceDeclaration: ((JdbcTableConstraints.DataAnchor) -> SealedSourceDataset)? = null) {
    val now: Instant = Instant.parse("2026-07-21T00:00:00Z")
    val mapper = OlapJsonUtils.mapper()
    val warehouse = WarehouseRegistryRepository(database)
    val physical = PhysicalAssetGovernanceService(database, warehouse)
    val logical = LogicalModelGovernanceService(database, physical, connectors)
    val topologies = TopologyGovernanceService(database, logical)
    val nativeExpressions = NativeExpressionBindings(physical, connectors)
    val events = EventGovernanceService(database, topologies, nativeExpressions)
    val dimensions = DimensionGovernanceService(database, topologies, events)
    val metrics = MetricGovernanceService(database, logical, topologies, events, dimensions, nativeExpressions)
    val business = BusinessLineGovernanceService(database)
    val subjects = SubjectAreaGovernanceService(database, business)
    val analysis = AnalysisDimensionGovernanceService(database, subjects, logical, events, dimensions)
    val members = MemberDefinitionGovernanceService(database, metrics, events, dimensions, logical)
    val adoptions = SubjectAreaAdoptionService(database, subjects, analysis, metrics, members, events, dimensions)
    val scenarios = ScenarioGovernanceService(database, subjects, adoptions, analysis, logical, metrics)
    val lifecycle = GovernanceVersionLifecycleService(database, business, physical, logical, topologies, events, dimensions,
        metrics, subjects, adoptions, analysis, members, scenarios, DiagnosticLifecycleRepository(database), warehouse)
    val spec = JdbcConnectionSpec("duckdb", TestConnectors.ref("duckdb"), "duckdb", "jdbc:duckdb:${directory.resolve("warehouse.duckdb")}", null, null, "org.duckdb.DuckDBDriver")
    private val source: SourceConfiguration get() = deployedSource ?: spec.source()
    val artifacts = ReleaseArtifactRepository(database)
    val capabilities = ReleaseQueryCapabilityRepository(database)
    private val inspector = JdbcWarehouseInspector(connectors)
    private val inspection = JdbcSourceInspection(inspector, connectors)
    val onboarding = WarehouseOnboardingService(physical, WarehouseRegistryService(database, warehouse, physical)) { reference -> source.takeIf { it.bindingRef() == reference } }
    lateinit var physicalId: String
        private set
    lateinit var paymentId: String
        private set
    lateinit var sourceSnapshot: WarehouseTableMetadata
        private set
    val sourceEvidence: PhysicalSourceConstraints get() = physical.physicalTableSourceConstraints(physicalId, 1)
    private var physicalColumns = emptyMap<String, String>()

    fun seedDefinitions() {
        business.createBusinessLine(BusinessLineCreateRequest("travel", "旅游业务", "旅游交易经营", "platform", "golden"))
        preparePhysical()
        val units = listOf(
            unit("order_id", "订单 ID", LogicType.STRING, primary = true), unit("pay_time", "支付时间", LogicType.DATE, time = true),
            unit("destination", "目的地", LogicType.STRING, filterable = true), unit("pay_amount", "支付金额", LogicType.NUMBER, aggregatable = true),
            unit("customer_id", "客户", LogicType.STRING, filterable = true))
        logical.createLogicalTable(LogicalTableCreateRequest(physicalId, 1, "order_fact", "交易订单", LogicalTableRole.FACT, "订单事实", "data", "golden", units))
        topologies.createTopology(TopologyCreateRequest("payment", "支付拓扑", "trade.payment", "交易支付实现空间", "order_fact", 1, "modeling", "交易事实主干", "golden"))
        events.createEvent(EventCreateRequest(TOPOLOGY, 1, "paid", "支付成功", "trade", "order_fact.pay_time", 1,
            EventIdentity(listOf(EventIdentity.LogicUnitRef("order_fact.order_id", 1)), mapOf("order" to listOf(EventIdentity.LogicUnitRef("order_fact.order_id", 1)))),
            emptyMap(), listOf(EventCreateRequest.LogicUnitRefInput("order_fact.destination", 1), EventCreateRequest.LogicUnitRefInput("order_fact.customer_id", 1)),
            listOf(EventCreateRequest.LogicUnitRefInput("order_fact.pay_amount", 1)), "支付成功事件", "golden"))
        paymentId = metrics.createMetric(MetricCreateRequest("pay_amount", "支付金额", "trade", MetricDefinition.FactMeasure(EventRef(EVENT, 1), ref("order_fact.pay_amount"),
            MetricAggregation.SUM, false, null, emptyMap(), null, money(), MetricValueContract.Rounding.NONE), "支付金额", amountMeaning(), "golden")).metric.id
    }

    private fun preparePhysical() {
        val catalog = TestConnectors.open("duckdb", spec.jdbcUrl).use { connection ->
            connection.createStatement().use { sql ->
                sql.execute("CREATE TABLE main.orders (order_id VARCHAR PRIMARY KEY, pay_amount DECIMAL(18,2), destination VARCHAR NOT NULL, pay_time DATE NOT NULL, customer_id VARCHAR)")
                initialOrders.forEach { sql.execute(it) }
            }
            connection.catalog
        }
        SourceRegistrationService(database, connectors).initializeAuthorizedSources(listOf(source))
        sourceSnapshot = inspector.describe(WarehouseTableInspectionRequest(spec.source(), WarehouseTableRef(catalog, "main", "orders")), RuntimeDeploymentLimits.CONTROL)
        val discovered = if (deployedSource == null) onboarding.discover(WarehouseDiscoveryCommand("duckdb", spec.sourceIdentityHash(), catalog, "main", "orders", emptyList(), 10,
            emptyMap(), if (sourceKeys.isEmpty()) emptyMap() else mapOf("orders" to sourceKeys), "golden"), RuntimeDeploymentLimits.CONTROL, inspection)
        else {
            require(sourceKeys.isEmpty() && sourceDeclaration == null) { "HTTP fixture uses explicit structure without sealed-source declarations" }
            onboarding.registerStructure(SourceStructureRegistrationRequest(source.dataSourceId, source.sourceIdentityHash(), sourceSnapshot.table.ref.catalog,
                requireNotNull(sourceSnapshot.table.ref.schema), "fixture-owned-database", "1", listOf(SourceStructureRegistrationRequest.Table(
                    sourceSnapshot.table, sourceSnapshot.columns, sourceSnapshot.constraints)), "golden"))
        }
        check(discovered.snapshot.status == "SUCCEEDED") { discovered.snapshot.errorMessage.orEmpty() }
        val table = discovered.tables.single()
        val collections = sourceDeclaration?.let { declaration ->
            val jdbc = (connectors.require(spec.connectorRef).access as JdbcAccess).jdbc
            val anchor = JdbcConnectionFactory(connectors).openReadOnly(spec).use { jdbc.sourceAnchor(it, spec, RuntimeDeploymentLimits.CONTROL::checkCancellation) }
            val dataset = declaration(anchor)
            onboarding.registerSourceDataset(SourceDatasetRegistrationRequest("duckdb", spec.sourceIdentityHash(), dataset), RuntimeDeploymentLimits.CONTROL, inspection)
        } ?: emptyList()
        val input = PhysicalTableCreateRequest("warehouse.orders", "orders", "交易订单物理表", "黄金场景订单", "交易", "data", "internal", "warehouse", source.dataSourceId, source.dialectId,
            table.catalogName, table.schemaName, requireNotNull(table.schemaHash), PhysicalTableCreateRequest.SourceConstraintsInput(table.id, collections.map { it.id }), "golden", discovered.columns.map {
                PhysicalTableCreateRequest.ColumnInput(it.columnName, it.columnName, it.typeName, "internal", requireNotNull(it.nullable), "真实订单字段",
                    com.olap.prototype.contract.JdbcColumnType(it.jdbcType, it.typeName, it.precision, it.scale, it.nullable))
            })
        val registered = physical.createPhysicalTable(input)
        physicalId = registered.physicalTable.id
        physicalColumns = registered.physicalColumns.associate { it.columnName to it.id }
        activate("PHYSICAL_TABLE", physicalId)
    }

    private fun unit(code: String, display: String, type: LogicType, time: Boolean = false, primary: Boolean = false,
        filterable: Boolean = false, aggregatable: Boolean = false) = LogicalTableCreateRequest.LogicUnitInput(code, display, LogicUnitSourceKind.COLUMN,
        null, type, null, time, primary, false, filterable, aggregatable,
        listOf(LogicalTableCreateRequest.SourceRefInput(physicalColumns.getValue(code), 1, LogicUnitSourceRefRole.DIRECT_SOURCE)))

    fun approveDefinitions() {
        activate("LOGICAL_TABLE", "order_fact"); activate("TOPOLOGY", TOPOLOGY); activate("EVENT", EVENT); activate("METRIC", paymentId); activate("BUSINESS_LINE", "travel")
    }

    fun seedSubjectDraft() {
        subjects.createSubjectArea(SubjectAreaCreateRequest("travel", 1, "trade", "交易主题域", "trade", "交易分析", "golden"))
        adopt(paymentId)
        analysisDimension("global.destination", "目的地", "destination", "order_fact.destination", "destination")
        analysisDimension("common.business_date", "业务日期", "day", "order_fact.pay_time", "business_date")
        scenarios.createScenario(ScenarioCreateRequest(SUBJECT, 1, "daily", "交易日报", "analyst", "按日查看交易", "golden"))
        grantMetric(paymentId)
        scenarios.createScenarioAnalysisDimensionRef(ScenarioAnalysisDimensionRefCreateRequest(SCENARIO, 1, "$SUBJECT.destination", 1, "destination", true, 1))
        scenarios.createScenarioAnalysisDimensionRef(ScenarioAnalysisDimensionRefCreateRequest(SCENARIO, 1, "$SUBJECT.business_date", 1, "day", true, 2))
    }

    fun seed() { seedDefinitions(); approveDefinitions(); seedSubjectDraft() }

    fun adopt(metricId: String, version: Long = 1, code: String = metrics.metricSnapshot(metricId, version).code,
        bindings: CalculationBindings = CalculationBindings.empty(), arguments: Map<String, Calculation.Value> = emptyMap()): SubjectAreaMetricRefCreateResult =
        adoptions.createSubjectAreaMetricRef(SubjectAreaMetricRefCreateRequest(SUBJECT, 1, code, metrics.metricSnapshot(metricId, version).displayName,
            metrics.metricBusinessMeaning(metricId, version), metricId, version, bindings, arguments, "golden"))

    fun grantMetric(metricId: String, scenario: String = SCENARIO, code: String = metrics.metricSnapshot(metricId, 1).code, order: Int = 1) =
        scenarios.createScenarioMetricRef(ScenarioMetricRefCreateRequest(scenario, 1, MetricAdoption.identity(SUBJECT, code), 1, true, order))

    fun analysisDimension(dimensionCode: String, display: String, level: String, logicUnit: String, analysisCode: String) {
        dimensions.createDimension(DimensionCreateRequest(dimensionCode, if (dimensionCode.startsWith("common.")) "公共维度" else "全局维度", display, "data", display, "golden"))
        val binding = dimensions.createDimensionEventBinding(DimensionEventBindingCreateRequest(dimensionCode, 1, EVENT, 1, logicUnit, 1, null, RelationAccessPath(emptyList()), emptyMap(), "golden")).binding
        val analysis = analysis.createAnalysisDimension(AnalysisDimensionCreateRequest(SUBJECT, 1, analysisCode, display, display, level, display, "golden")).analysisDimension
        // 精确同键证据是此作者fixture显式提供的数据，发布仍核证据类型及坐标。
        AnalysisDimensionGovernanceRepository(database).saveAnalysisDimensionValueAlignment(AnalysisDimensionValueAlignmentRecord("alignment:$analysisCode", analysis.id, 1, level,
            "PROVEN_SAME_KEY", mapper.writeValueAsString(mapOf("logicUnitId" to logicUnit)), "golden", now, "PROVEN", now))
        this.analysis.createAnalysisDimensionEventMapping(AnalysisDimensionEventMappingCreateRequest(analysis.id, 1, level, binding.id, 1, "PROVEN_SAME_KEY", "alignment:$analysisCode"))
    }

    fun activateSubjectGraph(includeSubject: Boolean = true) {
        listOf("global.destination", "common.business_date").forEach { id ->
            activate("DIMENSION", id)
            dimensions.listDimensionEventBindings(id, 1).forEach { activate("DIMENSION_EVENT_BINDING", it.id) }
        }
        if (includeSubject) activate("SUBJECT_AREA", SUBJECT)
    }

    fun activate(kind: String, id: String, version: Long = 1) = lifecycle.activate(GovernanceVersionActivationRequest(GovernanceActivationObjectType.valueOf(kind), id, version, "golden", "golden release activation"))

    fun builder(artifactWriter: ReleaseArtifactWriter = artifacts, capabilityWriter: ReleaseQueryCapabilityRepository = capabilities) = SubjectAreaReleaseBuildService(
        CanonicalReleaseRepository(database), ReleaseFreezer(physical, nativeExpressions, logical, topologies, events, dimensions, metrics, subjects, adoptions,
            analysis, members, scenarios, JdbcReleaseCatalogRepository(database::connection)), artifactWriter, ReleaseExecutableCatalogAssembler(connectors),
        PublishedSemanticCatalogProducer(), QueryCapabilityProducer(ExecutablePostOperationCapabilities::supports, connectors), capabilityWriter)

    fun build(id: String = "release_trade", version: String = "1") = builder().build(SubjectAreaReleaseBuildRequest(id, version, "交易发布", SUBJECT, 1, "golden"))

    fun amountAdoption(): CalculationBindings = CalculationBindings(mapOf(
        "destination" to CalculationBindings.Dimension(ScopedInputContract.DimensionRef("$SUBJECT.destination", "1", "destination"), "destination",
            analysis.listAnalysisDimensionEventMappings("$SUBJECT.destination", 1).map { it.id }),
        "time" to CalculationBindings.Dimension(ScopedInputContract.DimensionRef("$SUBJECT.business_date", "1", "day"), "business_time",
            analysis.listAnalysisDimensionEventMappings("$SUBJECT.business_date", 1).map { it.id })), emptyMap(), emptyMap(), emptyMap(), emptyMap())

    fun publication(id: String = "release_trade", version: String = "1", scenario: String = SCENARIO) = artifacts.loadExecutableCatalog(ReleaseCatalogRequest(scenario, 1, id, version))

    fun execute(publication: PublishedExecutableCatalog, computation: QueryComputation.Scope): ExecutableExecutionResult {
        val provider = SourceProvider(source, connectors)
        val engine = OlapEngine(OlapSemanticCompiler(CalciteRelCompiler(connectors)), SourceExecutableSqlExecutor({ reference -> provider.takeIf { it.bindingRef == reference } },
            RuntimeDeploymentLimits.result, RuntimeDeploymentLimits.nativeMemoryBytes, null))
        val observer = object : ExecutableExecutionObserver {
            override fun bindingCompleted(input: ExecutableInputScope) { }
            override fun stageAnalysisCompleted(analyses: List<QueryStageAnalysis.Analysis>) { }
            override fun sourceResolutionCompleted(sources: List<com.olap.prototype.engine.compiler.CompiledQuery.SourceEvidence>) { }
            override fun loweringCompleted(loweringResult: ExecutableLoweringResult) { }
        }
        val request = kernelRequest(publication, computation)
        return engine.execute(publication.catalog, request, observer, RuntimeDeploymentLimits.CONTROL)
    }

    fun intent(publication: PublishedExecutableCatalog, computation: QueryComputation.Scope): QueryIntent = QueryIntent(publication.coordinate, computation)

    fun kernelRequest(publication: PublishedExecutableCatalog, computation: QueryComputation.Scope): ExecutableQueryRequest {
        try {
            val intent = intent(publication, computation)
            PublishedQueryContract.authorize(intent, publication.queryCapability)
            return ExecutableQueryRequest(ExecutableQueryRequest.FORMAT_VERSION, "golden_query", publication.catalog.manifest.catalogId, intent.computation)
        } catch (failure: RuntimeException) {
            throw com.olap.prototype.engine.EngineExecutionException("AUTHORIZE_INPUT", "EXECUTABLE_QUERY_NOT_PUBLISHED", "Published query rejected: ${failure.message}", failure)
        }
    }

    companion object {
        const val SUBJECT = "travel.trade"
        const val SCENARIO = "travel.trade.daily"
        const val TOPOLOGY = "trade.payment.payment"
        const val EVENT = "trade.payment.payment.paid"
        fun ref(id: String, version: Long = 1) = SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, id, version)
        fun money() = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.UnitIdentity("iso4217", "CNY", 1)), MetricValueContract.Exact(),
            MetricValueContract.Decimal(38, 2, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
        fun records() = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(), MetricValueContract.Integer(64), emptyMap(), MetricValueContract.Overflow.FAIL)
        fun amountMeaning() = MetricBusinessMeaning("支付成功订单的支付金额", "元", "支付成功订单", listOf("不同币种不得直接合并"))
        fun combinedAmount(first: String, second: String, operator: ResultExpression.ResultOperator) = MetricCalculation(Calculation(listOf(Calculation.DimensionSlot("destination", "global.destination", 1, "destination"), Calculation.DimensionSlot("time", "common.business_date", 1, "business_time")), emptyList(), emptyList(), emptyList(), mapOf("window" to Calculation.Parameter(Calculation.ParameterKind.RANGE, "DATE", emptyList(), null, null)), Calculation.Scope(Calculation.Measures(listOf(
            Calculation.Call("first", Calculation.Fixed(MetricRef(first, "1")), "first", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted())),
            Calculation.Call("second", Calculation.Fixed(MetricRef(second, "1")), "second", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()))),
            listOf("destination"), emptyList(), "time", Calculation.Argument("window"), emptyMap(), Calculation.Population(listOf("first", "second")), null), emptyMap(), listOf(
            PostOperationContract.Derive(ResultExpression.ResultNumeric(ResultExpression.ResultOperation(operator, listOf(ResultExpression.ResultSymbol("first"), ResultExpression.ResultSymbol("second"))), money(), null), "combined", "组合金额"),
            PostOperationContract.Project(listOf("destination", "combined"))))), "combined", listOf("destination"), money())
        fun measurementScope(instance: MetricInstanceRef, grouping: List<ScopedInputContract.DimensionRef> = emptyList(), arguments: Map<String, Calculation.Value> = emptyMap(),
            timeRange: QueryComputation.TimeRange? = null) =
            QueryComputation.Scope(QueryComputation.Measures(listOf(QueryComputation.Invocation("measure", instance, "value", arguments, emptyMap(), emptyMap(), DimensionCondition.Unrestricted())), grouping, timeRange,
                emptyMap(), DimensionCondition.Unrestricted(), DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(listOf("measure")), null), emptyMap(), emptyList())
    }
}
