package com.olap.prototype.release.catalog

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.olap.prototype.calculation.*
import com.olap.prototype.connector.JdbcAccess
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.engine.catalog.ExecutableCatalog
import com.olap.prototype.engine.context.OlapQueryContext
import com.olap.prototype.engine.executable.*
import com.olap.prototype.event.*
import com.olap.prototype.expression.*
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.metric.*
import com.olap.prototype.query.*
import com.olap.prototype.query.contract.*
import com.olap.prototype.release.catalog.capability.PublishedNativeTypes
import com.olap.prototype.release.catalog.capability.PublishedQuerySources
import com.olap.prototype.release.contract.*
import com.olap.prototype.time.TemporalWindow
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.contract.*
import com.olap.prototype.warehouse.inspection.*
import com.olap.prototype.warehouse.jdbc.JdbcConnectionFactory
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/** 唯一的真实订单/目的地作者输入；冻结、绑定、SQL仍走正式实现。 */
internal class FrozenPublicationFixture(directory: Path, dialect: String, unique: Boolean, extraStatements: List<String> = emptyList()) : AutoCloseable {
    val spec = JdbcConnectionSpec("source", TestConnectors.ref(dialect), dialect,
        if (dialect == "duckdb") "jdbc:duckdb:${directory.resolve("source-${UUID.randomUUID()}.duckdb")}" else "jdbc:h2:mem:publication_${UUID.randomUUID()};DATABASE_TO_UPPER=false",
        null, null, if (dialect == "duckdb") "org.duckdb.DuckDBDriver" else "org.h2.Driver")
    val connection: Connection
    private val units = linkedMapOf("orders" to linkedMapOf("orders.id" to "id", "orders.time" to "occurred", "orders.amount" to "amount", "orders.destination" to "destination"),
        "destinations" to linkedMapOf("destination.id" to "id", "destination.name" to "name"))
    private val metadata: Map<String, WarehouseTableMetadata>

    init {
        val writer = TestConnectors.open(dialect, spec.jdbcUrl)
        try {
            writer.createStatement().use { sql ->
                sql.execute("CREATE TABLE orders(id INTEGER PRIMARY KEY, occurred DATE, amount DECIMAL(18,2), destination INTEGER)")
                sql.execute("CREATE TABLE destinations(id INTEGER${if (unique) " PRIMARY KEY" else ""}, name VARCHAR(32))")
                sql.execute("INSERT INTO orders VALUES (1,'2026-06-01',10,7),(2,'2026-06-01',20,7),(3,'2026-06-02',30,8),(4,'2026-06-03',40,9),(5,'2026-06-08',999,7)")
                sql.execute(if (unique) "INSERT INTO destinations VALUES (7,'杭州'),(8,'南京'),(9,'其他')" else "INSERT INTO destinations VALUES (7,'杭州'),(7,'杭州'),(7,'南京'),(8,'南京')")
                extraStatements.forEach { sql.execute(it) }
            }
            connection = if (dialect == "duckdb") { writer.close(); JdbcConnectionFactory(connectors).openReadOnly(spec) } else writer
            val inspector = JdbcWarehouseInspector(connectors)
            metadata = units.keys.associateWith { table ->
                val observed = inspector.discover(WarehouseDiscoveryRequest(spec.source(), null, null, table, null, 10), RuntimeDeploymentLimits.CONTROL)
                    .tables.single { it.ref.table == table }.ref
                inspector.describe(WarehouseTableInspectionRequest(spec.source(), observed), RuntimeDeploymentLimits.CONTROL)
            }
        } catch (failure: Throwable) { writer.close(); throw failure }
    }

    fun release(definitions: Map<String, MetricDefinition>, timed: Boolean, cityExpression: SemanticExpression = ref("destination.name"),
        cityPath: RelationAccessPath = DESTINATION, cityRoles: Map<String, RelationAccessPath> = emptyMap(), direction: String = "LEFT"): ReleaseCatalogView {
        val refs = mutableListOf(objectRef("ANALYSIS_DIMENSION", "city", emptyMap<String, Any>(), "目的地"), objectRef("ANALYSIS_DIMENSION", "business_date", emptyMap<String, Any>(), "业务日期"))
        units.forEach { (table, names) -> names.keys.forEach { unit -> refs += objectRef("LOGIC_UNIT", unit, logicUnit(table, unit)) } }
        refs += objectRef("EVENT", "payment", EventExecutionSnapshot("payment_topology", 1, "orders.time", 1,
            EventIdentity(listOf(EventIdentity.LogicUnitRef("orders.id", 1)), emptyMap()), emptyMap(), null))
        refs += objectRef("TOPOLOGY", "payment_topology", TopologyExecutionSnapshot("payment_topology", 1, "payment", "支付", "orders", 1,
            listOf(ReleaseJoinPathRef("destination_path", 1))))
        refs += objectRef("JOIN_PATH", "destination_path", mapOf(
            "joinPathId" to "destination_path", "joinPathVersion" to 1, "topologyId" to "payment_topology", "topologyVersion" to 1,
            "sourceLogicalTableId" to "orders", "sourceLogicalTableVersion" to 1, "targetLogicalTableId" to "destinations", "targetLogicalTableVersion" to 1,
            "joinType" to direction, "cardinality" to "MANY_TO_ONE", "nullPolicy" to "SQL_EQUALS_NULL_NEVER_MATCHES", "temporalConfigJson" to "{}",
            "keys" to listOf(mapOf("order" to 0, "sourceLogicUnitId" to "orders.destination", "sourceLogicUnitVersion" to 1, "targetLogicUnitId" to "destination.id", "targetLogicUnitVersion" to 1))))
        definitions.forEach { (code, definition) ->
            refs += objectRef("METRIC", code, MetricExecutionSnapshot(definition, meaning()))
            val adoption = MetricAdoption(MetricAdoption.identity("subject", code), "subject", 1, code, code, meaning(), MetricRef(code, "1"), CalculationBindings.empty(), emptyMap())
            refs += objectRef("METRIC_ADOPTION", adoption.adoptionId, adoption)
        }
        val logicUnit = if (cityPath == LOCAL) "orders.amount" else "destination.name"
        refs += objectRef("DIMENSION_EVENT_BINDING", "city_binding", PublishedDimensionBinding("city_binding", 1, "destination", 1,
            EVENT, "payment_topology", 1, logicUnit, 1, cityExpression, cityPath, cityRoles))
        val mappings = mutableListOf(ReleaseDimensionMapping("release_city_mapping", CITY_MAPPING_ID, "release", "1", "subject", "payment", 1,
            "city", 1, "目的地", "value", "值", "destination", 1, "global", "全局", "目的地", "city_binding", 1, "aligned_city", "PROVEN_SAME_KEY",
            "payment_topology", 1, "payment", "支付", logicUnit, 1, "目的地表达式", cityPath.joinPathRefs.map { ReleaseJoinPathRef(it.joinPathId, it.joinPathVersion) },
            SemanticExpressionCodec.write(cityExpression), NOW))
        if (timed) {
            refs += objectRef("DIMENSION_EVENT_BINDING", "time_binding", PublishedDimensionBinding("time_binding", 1, "business_time", 1,
                EVENT, "payment_topology", 1, "orders.time", 1, ref("orders.time"), LOCAL, emptyMap()))
            mappings += ReleaseDimensionMapping("release_time_mapping", TIME_MAPPING_ID, "release", "1", "subject", "payment", 1,
                "business_date", 1, "业务日期", "day", "日", "business_time", 1, "common", "公共", "业务时间", "time_binding", 1, "aligned_time", "PROVEN_SAME_KEY",
                "payment_topology", 1, "payment", "支付", "orders.time", 1, "业务时间", emptyList(), SemanticExpressionCodec.write(ref("orders.time")), NOW)
        }
        val anchor = ReleaseTimeAnchorRule("business_time", "release", "1", "subject", "scenario", 1, "payment", 1, "payment_topology", 1,
            "payment", "支付", "orders.time", 1, "支付日期", "ANALYSIS_DIMENSION:business_date:day", "business_date", 1, "业务日期", "day", "[]", null, null, "{}", NOW)
        return ReleaseCatalogView("scenario", 1, "发布试验", "release", "1", "发布试验", refs, emptyList(), mappings,
            if (timed) listOf(anchor) else emptyList(), emptyList(), emptyList())
    }

    private fun logicUnit(table: String, unit: String): LogicUnitExecutionSnapshot {
        val observed = metadata.getValue(table)
        val names = units.getValue(table)
        val logic = names.map { (id, _) -> "$id@1" to mapper.valueToTree<JsonNode>(SemanticExpression.Reference(SemanticExpression.ReferenceKind.PHYSICAL_COLUMN, "$id.physical", 1)) }.toMap()
        val columns = names.map { (id, name) -> "$id.physical@1" to name }.toMap()
        val types = names.map { (id, name) ->
            val column = observed.columns.single { it.name == name }
            "$id.physical@1" to JdbcColumnType(requireNotNull(column.jdbcType), requireNotNull(column.typeName), column.precision, column.scale, column.nullable)
        }.toMap()
        val refs = names.map { (id, name) -> name to "$id.physical@1" }.toMap()
        val source = observed.source
        val evidence = PhysicalSourceConstraints("actual_frozen_source", PublicationContentHash.compute(mapper.valueToTree(observed)), spec.sourceIdentityHash(), spec.connectorRef,
            source.databaseProductName, source.databaseProductVersion, source.driverName, source.driverVersion, observed.constraints, refs, emptyList(),
            StructureProvenance(StructureProvenance.Kind.DATABASE_CATALOG, "integration-test-catalog", "fixture-v1"))
        return LogicUnitExecutionSnapshot(LogicUnitExecutionSnapshot.FORMAT_VERSION, "${table}_binding", "1", unit, 1,
            when (unit) { "orders.time" -> "DATE"; "destination.name" -> "TEXT"; else -> "NUMBER" }, null, table, 1, "${table}_physical", 1,
            "source", observed.table.ref.catalog, observed.table.ref.schema, table, spec.dialectId, logic, columns, types, emptyMap(), evidence)
    }

    fun objectRef(kind: String, id: String, value: Any, displayName: String = id) = ReleaseObjectRef("${kind}_$id", "release", "1", kind, id, 1, id, displayName,
        ReleaseObjectKind.require(kind).refRole, mapper.writeValueAsString(value), NOW)

    override fun close() = connection.close()

    companion object {
        val connectors = TestConnectors.registry
        val mapper = OlapJsonUtils.mapper()
        const val CITY_MAPPING_ID = "city_mapping"
        const val TIME_MAPPING_ID = "time_mapping"
        val NOW: Instant = Instant.parse("2026-06-22T00:00:00Z")
        val EVENT = EventRef("payment", 1)
        val LOCAL = RelationAccessPath(emptyList())
        val DESTINATION = RelationAccessPath(listOf(RelationAccessPath.JoinRef("destination_path", 1)))
        val CITY = ScopedInputContract.DimensionRef("city", "1", "value")
        fun ref(id: String) = SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, id, 1)
        fun eq(left: SemanticExpression, right: SemanticExpression) = SemanticExpression.Operation(SemanticExpression.Operator.EQ, listOf(left, right))
        fun money() = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.UnitIdentity("iso4217", "CNY", 1)), MetricValueContract.Exact(),
            MetricValueContract.Decimal(38, 2, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
        fun records() = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(), MetricValueContract.Integer(64), emptyMap(), MetricValueContract.Overflow.FAIL)
        fun meaning() = MetricBusinessMeaning("发布试验金额或记录数", "按值合同", "支付事实", emptyList())
        fun fact(input: SemanticExpression, aggregation: MetricAggregation, distinct: Boolean = false, predicate: SemanticExpression? = null, contract: MetricValueContract = money()) =
            MetricDefinition.FactMeasure(EVENT, input, aggregation, distinct, predicate, emptyMap(), null, contract, MetricValueContract.Rounding.NONE)
        fun amount(): Map<String, MetricDefinition> = mapOf("amount" to fact(ref("orders.amount"), MetricAggregation.SUM))

        fun metricReferences(view: ReleaseCatalogView): List<ScenarioMetricReference> = view.objectVersionRefs.filter { it.objectType == "METRIC_ADOPTION" }.mapIndexed { index, reference ->
            val adoption = mapper.readValue(reference.snapshotSummaryJson, MetricAdoption::class.java)
            ScenarioMetricReference(adoption.adoptionId, adoption.subjectAreaVersion, "ACTIVE", "scenario_${adoption.code}", reference.id, false, index)
        }

        fun frozen(view: ReleaseCatalogView, stages: List<PostProcessCapability> = emptyList(), members: List<MemberCandidate> = emptyList(),
            assets: ExecutableCatalog = ReleaseExecutableCatalogAssembler(connectors).compileAssets(view, metricReferences(view))): PublishedExecutableCatalog {
            val sources = PublishedQuerySources(ReleaseObjectIndex(view.objectVersionRefs), assets)
            val candidates = metricReferences(view).map(sources::metric)
            val nativeTypes = PublishedNativeTypes(view.dimensionMappings, assets, connectors)
            val capability = QueryCapabilityHash.attach(QueryCapabilityView(QueryScenarioRef("scenario", 1, "发布试验", "release", "1", "发布试验"),
                SubjectAreaCapabilityContext("subject", 1, "subject_scenario", "ACTIVE"), "release", "1", "发布试验", candidates, members,
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), stages, emptyList(), emptyList(), emptyList(), nativeTypes.produce(candidates, members, emptyList()), nativeTypes.definitions(), null))
            val published = ReleaseExecutableCatalogAssembler(connectors).assemble(view, assets, capability)
            return mapper.readValue(mapper.writeValueAsBytes(published), PublishedExecutableCatalog::class.java)
        }

        fun request(calls: List<QueryComputation.Invocation>, grouping: List<ScopedInputContract.DimensionRef> = emptyList(),
            events: List<QueryComputation.EventCondition> = emptyList(), timed: Boolean = true): ObjectNode {
            val measures = QueryComputation.Measures(calls, grouping, if (timed) QueryComputation.TimeRange(ScopedInputContract.DimensionRef("business_date", "1", "day"),
                TemporalWindow(TemporalWindow.ValueType.DATE, TemporalWindow.BOUNDARY, "2026-06-01", "2026-06-08")) else null, emptyMap(),
                DimensionCondition.Unrestricted(), DimensionCondition.Unrestricted(), events, RowPopulationContract.calls(calls.map { it.callId }), null)
            return mapper.createObjectNode().put("formatVersion", PublishedQueryContract.FORMAT_VERSION).put("requestId", "frozen_catalog")
                .put("releaseId", "release").put("releaseVersion", "1").put("scenarioId", "scenario").put("scenarioVersion", 1L)
                .set("computation", mapper.valueToTree(QueryComputation.Scope(measures, emptyMap(), emptyList())))
        }

        fun computation(request: ObjectNode): QueryComputation.Scope = mapper.convertValue(request.get("computation"), QueryComputation.Scope::class.java)
        fun bindFrozen(publication: PublishedExecutableCatalog, request: ObjectNode): ExecutableInputScope = bind(publication.catalog, request)
        fun bind(catalog: ExecutableCatalog, request: ObjectNode): ExecutableInputScope = ExecutableSemanticBinder(connectors).bind(catalog,
            ExecutableQueryRequest(ExecutableQueryRequest.FORMAT_VERSION, request.path("requestId").asText(), catalog.manifest.catalogId, computation(request)))

        fun execute(fixture: FrozenPublicationFixture, catalog: PublishedExecutableCatalog, request: ObjectNode): List<List<Any?>> {
            val graph = ExecutableInputGraph.index(bindFrozen(catalog, request))
            val sources = ExecutableSourceResolver().resolve(catalog.catalog, graph)
            val compiled = CalciteRelCompiler(connectors).compile(graph, ExecutableStageAnalysis.analyze(graph), sources, OlapQueryContext("frozen_catalog")).lowering
            fixture.connection.prepareStatement(compiled.sql).use { query ->
                val jdbc = (connectors.require(fixture.spec.connectorRef).access as JdbcAccess).jdbc
                compiled.parameters.forEachIndexed { index, parameter -> jdbc.bind(query, index + 1, parameter) }
                query.executeQuery().use { rows -> return buildList { while (rows.next()) add((1..rows.metaData.columnCount).map { rows.getObject(it) }) } }
            }
        }

        fun adoptedInstance(catalog: PublishedExecutableCatalog, code: String): MetricInstanceRef = catalog.queryCapability.metricCandidates.single {
            it.adoptionId == MetricAdoption.identity("subject", code) && it.adoptionVersion == 1L
        }.instance
        fun call(catalog: PublishedExecutableCatalog, callId: String, code: String) = QueryComputation.Invocation(callId, adoptedInstance(catalog, code), callId, emptyMap(), emptyMap(), emptyMap(), DimensionCondition.Unrestricted())
        fun cityAmounts(rows: List<List<Any?>>): Map<String, BigDecimal> = rows.associate { it[0] as String to it[1] as BigDecimal }
    }
}
