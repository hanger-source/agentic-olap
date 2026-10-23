package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.*
import com.olap.prototype.codec.SemanticProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.engine.OlapEngine
import com.olap.prototype.engine.catalog.*
import com.olap.prototype.engine.compiler.CompiledQuery
import com.olap.prototype.engine.compiler.OlapSemanticCompiler
import com.olap.prototype.engine.context.OlapQueryContext
import com.olap.prototype.event.*
import com.olap.prototype.expression.*
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.member.*
import com.olap.prototype.metric.*
import com.olap.prototype.query.*
import com.olap.prototype.query.PostOperationContract.Project
import com.olap.prototype.query.PostOperationContract.Sort
import com.olap.prototype.query.PostOperationContract.TopN
import com.olap.prototype.query.PostOperationContract.PostFilter
import com.olap.prototype.query.PostOperationContract.Derive
import com.olap.prototype.query.PostOperationContract.Cumulative
import com.olap.prototype.query.PostOperationContract.Rank
import com.olap.prototype.query.PostOperationContract.OrderedRank
import com.olap.prototype.query.PostOperationContract.OffsetValue
import com.olap.prototype.query.PostOperationContract.PartitionAggregate
import com.olap.prototype.query.PostOperationContract.WindowAggregate
import com.olap.prototype.query.PostOperationContract.Regroup
import com.olap.prototype.query.PostOperationContract.ResultGrouping
import com.olap.prototype.query.PostOperationContract.ResultAggregate
import com.olap.prototype.query.PostOperationContract.JoinInput
import com.olap.prototype.query.PostOperationContract.OrderKey
import com.olap.prototype.query.PostOperationContract.AncestorClosure
import com.olap.prototype.query.ResultExpression.*
import com.olap.prototype.connector.SourceExecutionException
import com.olap.prototype.warehouse.contract.SourceCancellationEvidence
import com.olap.prototype.warehouse.contract.SourceExecutionState
import com.olap.prototype.warehouse.contract.SourceFailureCategory
import com.olap.prototype.warehouse.contract.SourceRequestAcceptance
import com.olap.prototype.warehouse.contract.SourceResponseState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

class MemberRelationExecutionTest {
    private val local = RelationAccessPath(emptyList())
    private val money = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.UnitIdentity("trial", "money", 1)), MetricValueContract.Exact(),
        MetricValueContract.Decimal(38, 2, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
    private fun property(title: String, role: String, type: ScalarValueType, unit: String, path: RelationAccessPath = local, contract: MetricValueContract? = null) =
        MemberSourceDefinition.PropertyOutput(title, role, type, unit, 1, path, contract)
    private fun orderUnits() = mapOf("orders.id" to "id", "orders.customer" to "customer", "orders.region" to "region", "orders.hour" to "event_hour", "orders.amount" to "amount")
    private fun orderOutputs(): Map<String, MemberSourceDefinition.Output> = linkedMapOf(
        "id" to property("记录", "record", ScalarValueType.NUMBER, "orders.id"),
        "customer" to property("客户", "customer", ScalarValueType.NUMBER, "orders.customer"),
        "region" to property("地区", "region", ScalarValueType.TEXT, "orders.region"),
        "hour" to property("时段", "hour", ScalarValueType.NUMBER, "orders.hour"),
        "amount" to property("金额", "amount", ScalarValueType.NUMBER, "orders.amount", contract = money))
    private fun orders(directory: Path, dialect: String) = NativeWarehouse(directory, dialect, listOf(
        "CREATE TABLE orders(id INTEGER PRIMARY KEY, customer INTEGER, region VARCHAR, event_hour INTEGER, amount DECIMAL(18,2))",
        "INSERT INTO orders VALUES (1,1,'A',1,10),(2,1,'B',2,10),(3,2,'A',3,NULL),(4,3,'B',4,7)"))
    private fun catalog(warehouse: NativeWarehouse, table: String, units: Map<String, String>, joins: List<ExecutableJoinPath>,
        recordKey: String = "$table.id"): ExecutableCatalog {
        val topology = ExecutableTopology(table, "1", table, null, table, "1", joinPathRefs = joins.map { ExecutableJoinPathRef(it.joinPathId, it.joinPathVersion) })
        val event = ExecutableEvent(table, "1", table, "1", null, EventIdentity(listOf(EventIdentity.LogicUnitRef(recordKey, 1)), emptyMap()), emptyMap(), null, null, null)
        return ExecutableCatalog(ExecutableCatalogManifest("member_relation", ExecutableCatalogManifest.SCHEMA_VERSION),
            physicalExecutionBindings = mapOf("$table@1" to warehouse.adopt(table, units)), joinPaths = joins.associateBy { "${it.joinPathId}@${it.joinPathVersion}" },
            events = mapOf("$table@1" to event), topologies = mapOf("$table@1" to topology))
    }
    private fun members(catalog: ExecutableCatalog, id: String, shape: MemberShape, identity: List<String>,
        outputs: Map<String, MemberSourceDefinition.Output>, projection: Map<String, String>): ExecutableInputScope {
        val event = catalog.events.values.first()
        val expressions = outputs.mapValues { (_, output) -> when (output) {
            is MemberSourceDefinition.PropertyOutput -> SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, output.logicUnitId, 1)
            is MemberSourceDefinition.DimensionOutput -> catalog.dimensionBindings.getValue("${output.bindingId}@${output.bindingVersion}").expression
            else -> error("source fixture requires an explicit projected output")
        } }
        val definition = MemberSourceDefinition.Projection(EventRef(event.eventId, 1), MemberResult(if (shape == MemberShape.UNIQUE_MEMBERS) "客户" else "事实记录",
            if (shape == MemberShape.UNIQUE_MEMBERS) "customer" else "observations", shape, identity, outputs, emptyMap(), null, emptyMap(), null))
        val source = MemberSource.Projection(id, 1, definition, expressions)
        val meanings = projection.mapValues { (_, slot) -> when (val output = outputs.getValue(slot)) {
            is MemberSourceDefinition.DimensionOutput -> QueryStageAnalysis.MemberDimension(output.dimensionId, output.dimensionVersion.toString(), output.role)
            else -> QueryStageAnalysis.MemberProperty(MemberSourceDefinition.scalar(output).valueType, output.role)
        } }
        val contracts = projection.mapValues { MemberSourceDefinition.rootValueContract(outputs.getValue(it.value)) }
        val selection = MemberInvocationBinding.Bound(projection, meanings, contracts, emptyMap(), (identity + projection.values).toSet(),
            MemberCondition.Unrestricted(), null, emptyMap(), emptyMap())
        return ExecutableInputScope("ROOT", ExecutableInputRoot.Members(ExecutableMemberInput.Projection(id, QueryStageAnalysis.ScopeId.root(), source, event,
            catalog.topologies.getValue("${event.topologyId}@1"), selection)), emptyList(), emptyMap(), emptyList())
    }
    private fun compile(catalog: ExecutableCatalog, input: ExecutableInputScope): ExecutableLoweringResult {
        val graph = ExecutableInputGraph.index(input)
        return CalciteRelCompiler(TestConnectors.registry).compile(graph, ExecutableStageAnalysis.analyze(graph, TestConnectors.registry), ExecutableSourceResolver().resolve(catalog, graph), OlapQueryContext("member_relation")).lowering
    }
    private fun stages(input: ExecutableInputScope, operations: List<PostOperationContract.ValidatedOperation>) =
        ExecutableInputScope(input.inputMode, input.root, input.objectRestrictions, input.additionalInputs, operations)
    private fun symbol(name: String): ResultExpression = ResultSymbol(name)
    private fun order(name: String) = OrderKey(name, "ASC", "LAST")
    private fun aggregate(input: String, aggregation: ResultAggregation, alias: String) = ResultAggregate(input, aggregation, alias, alias)
    private val observer = object : ExecutableExecutionObserver {
        override fun bindingCompleted(input: ExecutableInputScope) {}
        override fun stageAnalysisCompleted(analyses: List<QueryStageAnalysis.Analysis>) {}
        override fun sourceResolutionCompleted(sources: List<CompiledQuery.SourceEvidence>) {}
        override fun loweringCompleted(loweringResult: ExecutableLoweringResult) {}
    }
    @Test fun compilesNumericMemberValuesAndRejectsATextDeclarationForTheSameSource(@TempDir directory: Path) {
        orders(directory, "duckdb").use { warehouse ->
            val base = catalog(warehouse, "orders", mapOf("orders.id" to "id", "orders.customer" to "customer"), emptyList())
            val units = listOf("id", "customer").associate { "orders.$it@1" to ExecutableLogicUnit("orders.$it", "1", ScalarValueType.NUMBER, null, null, null, it, null, "orders", "1") }
            val request = ExecutableQueryRequest(ExecutableQueryRequest.FORMAT_VERSION, "numeric_members", base.manifest.catalogId,
                QueryComputation.Scope(QueryComputation.Members("members", MemberRef("records", 1), emptyMap(), listOf(QueryComputation.Slot("customer", "customer")), null, emptyMap(), MemberCondition.Unrestricted(), emptyMap(), emptyMap()), emptyMap(), emptyList()))
            val compiler = OlapSemanticCompiler(CalciteRelCompiler(TestConnectors.registry)); warehouse.finishSetup()
            for (type in listOf(ScalarValueType.NUMBER, ScalarValueType.TEXT)) {
                val outputs = mapOf("id" to property("记录", "record", ScalarValueType.NUMBER, "orders.id"), "customer" to property("客户", "customer", type, "orders.customer"))
                val input = members(base, "records", MemberShape.RECORDS, listOf("id"), outputs, mapOf("customer" to "customer"))
                val source = ((input.root as ExecutableInputRoot.Members).input as ExecutableMemberInput.Projection).memberSource
                val catalog = ExecutableCatalog(base.manifest, memberSources = mapOf("records@1" to source), logicUnits = units,
                    physicalExecutionBindings = base.physicalExecutionBindings, events = base.events, topologies = base.topologies)
                if (type == ScalarValueType.NUMBER) assertEquals(listOf(1, 1, 2, 3), warehouse.execute(requireNotNull(compiler.compile(catalog, request, OlapQueryContext(request.requestId), observer).executableLoweringResult)).map { (it.first() as Number).toInt() }.sorted())
                else assertEquals("BIND_INPUT", assertThrows(EngineExecutionException::class.java) { compiler.compile(catalog, request, OlapQueryContext(request.requestId), observer) }.stage)
            }
        }
    }
    @Test fun preservesRecordMultiplicityAndOnlyDeduplicatesAnExplicitMemberIdentity(@TempDir directory: Path) {
        orders(directory, "duckdb").use { warehouse ->
            val catalog = catalog(warehouse, "orders", orderUnits(), emptyList()); warehouse.finishSetup(); val outputs = orderOutputs()
            val records = members(catalog, "records", MemberShape.RECORDS, listOf("id"), outputs, mapOf("customer" to "customer"))
            assertEquals(listOf(1, 1, 2, 3), warehouse.execute(compile(catalog, records)).map { (it.first() as Number).toInt() }.sorted())
            val unique = members(catalog, "customers", MemberShape.UNIQUE_MEMBERS, listOf("customer"), mapOf("customer" to outputs.getValue("customer")), mapOf("customer" to "customer"))
            assertEquals(listOf(1, 2, 3), warehouse.execute(compile(catalog, unique)).map { (it.first() as Number).toInt() }.sorted())
            val contradictory = members(catalog, "attributes", MemberShape.UNIQUE_MEMBERS, listOf("customer"), outputs, mapOf("customer" to "customer", "region" to "region"))
            assertThrows(IllegalArgumentException::class.java) { compile(catalog, contradictory) }
            warehouse.connection.createStatement().use { statement -> statement.executeQuery("SELECT COUNT(DISTINCT region) FROM orders WHERE customer=1").use { rows -> assertTrue(rows.next()); assertEquals(2, rows.getInt(1)) } }
        }
    }
    @Test fun countsDistinctMembersInsideAFrameAndPreservesTypedTupleCollections(@TempDir directory: Path) {
        orders(directory, "duckdb").use { warehouse ->
            warehouse.connection.createStatement().use { it.execute("UPDATE orders SET customer=NULL WHERE id=4") }
            val catalog = catalog(warehouse, "orders", orderUnits(), emptyList()); warehouse.finishSetup()
            val projected = linkedMapOf("id" to "id", "hour" to "hour", "customer" to "customer", "amount" to "amount")
            val source = members(catalog, "records", MemberShape.RECORDS, listOf("id"), orderOutputs(), projected)
            val window = WindowAggregate("customer", emptyList(), "people", "people", listOf(order("hour"), order("id")), ResultAggregation.Count(true, ResultAggregation.NullPolicy.IGNORE),
                WindowFrameContract.Frame("ROWS", WindowFrameContract.Bound("UNBOUNDED_PRECEDING", null, null), WindowFrameContract.Bound("CURRENT_ROW", null, null)))
            val counted = stages(source, listOf(window, Sort(listOf(order("hour"), order("id"))), Project(listOf("customer", "people"))))
            assertEquals(listOf(1L, 1L, 2L, 2L), warehouse.execute(compile(catalog, counted)).map { (it[1] as Number).toLong() })
            for (aggregation in listOf("ARRAY_AGG", "ARRAY_AGG_DISTINCT")) {
                val collection = stages(source, listOf(Derive(ResultRow(listOf(symbol("customer"), symbol("amount"))), "observation", "客户与金额"),
                    Regroup(ResultGrouping(emptyList(), listOf(aggregate("observation", ResultAggregation.Collect(aggregation == "ARRAY_AGG_DISTINCT"), "observations")), null))))
                val result = warehouse.execute(compile(catalog, collection)).single().single() as com.olap.prototype.api.SqlResultValues.ArrayValue
                val tuples = result.elements.map { (it as com.olap.prototype.api.SqlResultValues.RowValue).fields }
                assertEquals(if (aggregation == "ARRAY_AGG") 4 else 3, tuples.size)
                assertTrue(tuples.any { it[0] != null && (it[0] as Number).toInt() == 2 && it[1] == null })
                assertTrue(tuples.any { it[0] == null && (it[1] as BigDecimal).compareTo(BigDecimal("7")) == 0 })
                assertEquals(if (aggregation == "ARRAY_AGG") 2 else 1, tuples.count { it[0] != null && (it[0] as Number).toInt() == 1 && (it[1] as BigDecimal).compareTo(BigDecimal("10")) == 0 })
            }
        }
    }
    @Test fun resolvesFiniteParentAndGrandparentRolesOnTheSamePhysicalTable(@TempDir directory: Path) {
        NativeWarehouse(directory, "duckdb", listOf("CREATE TABLE employees(id INTEGER PRIMARY KEY, manager INTEGER, name VARCHAR)",
            "INSERT INTO employees VALUES (1,2,'员工'),(2,3,'经理'),(3,NULL,'负责人'),(4,2,'同组员工'),(5,NULL,'独立成员')")).use { warehouse ->
            val join = ExecutableJoinPath("manager", "1", "employees", "1", "employees", "1", "employees", "1", "LEFT", "MANY_TO_ONE", null,
                "SQL_EQUALS_NULL_NEVER_MATCHES", listOf(ExecutableJoinKey(1, "employees.manager", "1", "employees.id", "1")))
            val catalog = catalog(warehouse, "employees", mapOf("employees.id" to "id", "employees.manager" to "manager", "employees.name" to "name"), listOf(join))
            warehouse.finishSetup()
            val parent = RelationAccessPath(listOf(RelationAccessPath.JoinRef("manager", 1)))
            val grandparent = RelationAccessPath(listOf(RelationAccessPath.JoinRef("manager", 1), RelationAccessPath.JoinRef("manager", 1)))
            val outputs = mapOf("id" to property("记录", "record", ScalarValueType.NUMBER, "employees.id"), "employee" to property("员工", "employee", ScalarValueType.TEXT, "employees.name"),
                "parent" to property("上级", "parent", ScalarValueType.TEXT, "employees.name", parent), "grandparent" to property("上上级", "grandparent", ScalarValueType.TEXT, "employees.name", grandparent))
            val root = members(catalog, "organization", MemberShape.RECORDS, listOf("id"), outputs, linkedMapOf("employee" to "employee", "parent" to "parent", "grandparent" to "grandparent"))
            val rows = warehouse.execute(compile(catalog, root)); assertEquals(5, rows.size)
            assertTrue(rows.contains(listOf("员工", "经理", "负责人"))); assertTrue(rows.contains(listOf("同组员工", "经理", "负责人")))
            assertTrue(rows.contains(listOf("经理", "负责人", null))); assertTrue(rows.contains(listOf("独立成员", null, null)))
        }
    }
    private fun graphCatalog(warehouse: NativeWarehouse) = catalog(warehouse, "graph", mapOf("graph.record_no" to "record_no", "graph.tenant" to "tenant", "graph.id" to "id", "graph.parent_tenant" to "parent_tenant", "graph.parent_id" to "parent_id"), emptyList(), "graph.record_no")
    private fun ancestors(catalog: ExecutableCatalog, self: Boolean): ExecutableInputScope {
        val projection = listOf("tenant", "id", "parent_tenant", "parent_id").associateWith { it }
        val outputs = projection.keys.associateWith { property(it, it, ScalarValueType.NUMBER, "graph.$it") } +
            ("record_no" to property("边记录", "edge_record", ScalarValueType.NUMBER, "graph.record_no"))
        return stages(members(catalog, "graph_records", MemberShape.RECORDS, listOf("record_no"), outputs, projection), listOf(AncestorClosure(
            listOf("tenant", "id"), listOf("parent_tenant", "parent_id"), listOf("d_tenant", "d_id"), listOf("a_tenant", "a_id"), self)))
    }
    @Test fun closesCompoundAncestorsAcrossDeepChainsDiamondsRootsAndUnknownTargets(@TempDir directory: Path) {
        val statements = mutableListOf("CREATE SEQUENCE edge_no", "CREATE TABLE graph(record_no BIGINT DEFAULT nextval('edge_no') PRIMARY KEY,tenant INTEGER,id INTEGER,parent_tenant INTEGER,parent_id INTEGER)",
            "INSERT INTO graph(tenant,id,parent_tenant,parent_id) VALUES (1,1,1,2),(1,1,1,2),(1,1,1,3),(1,2,1,4),(1,3,1,4),(1,4,NULL,NULL),(1,8,1,99),(2,1,2,2),(2,2,NULL,NULL)")
        (1..65).forEach { id -> statements += "INSERT INTO graph(tenant,id,parent_tenant,parent_id) VALUES (3,$id,${if (id == 65) "NULL,NULL" else "3,${id + 1}"})" }
        NativeWarehouse(directory, "duckdb", statements).use { warehouse ->
            val catalog = graphCatalog(warehouse); warehouse.finishSetup()
            val expected = mutableSetOf(listOf(1, 1, 1, 2), listOf(1, 1, 1, 3), listOf(1, 1, 1, 4), listOf(1, 2, 1, 4), listOf(1, 3, 1, 4), listOf(1, 8, 1, 99), listOf(2, 1, 2, 2))
            for (child in 1..65) for (parent in child + 1..65) expected += listOf(3, child, 3, parent)
            for (self in listOf(false, true)) {
                val actual = warehouse.execute(compile(catalog, ancestors(catalog, self))).map { row -> row.map { (it as Number).toInt() } }
                val answer = expected.toMutableSet()
                if (self) {
                    listOf(1, 2, 3, 4, 8, 99).forEach { answer += listOf(1, it, 1, it) }
                    listOf(1, 2).forEach { answer += listOf(2, it, 2, it) }
                    (1..65).forEach { answer += listOf(3, it, 3, it) }
                }
                assertEquals(answer, actual.toSet()); assertEquals(answer.size, actual.size, "重复边和菱形路径不重复输出端点对")
            }
            val initial = ancestors(catalog, false)
            val projected = stages(initial, initial.postOps + Project(listOf("d_tenant", "d_id")))
            val counts = expected.map { it.take(2) }.groupingBy { it }.eachCount()
            val actual = warehouse.execute(compile(catalog, projected)).map { row -> row.map { (it as Number).toInt() } }.groupingBy { it }.eachCount()
            assertEquals(counts, actual, "端点投影保留不同祖先贡献的行")
        }
    }
    @Test fun rejectsInvalidAncestorInputsEvenWhenTheFinalBusinessFilterIsEmpty(@TempDir directory: Path) {
        val failures = mapOf("(1,NULL,1,2)" to "ANCESTOR_SOURCE_KEY_NULL", "(1,1,1,NULL)" to "ANCESTOR_PARENT_KEY_PARTIAL_NULL", "(1,1,1,2),(1,2,1,1)" to "ANCESTOR_CYCLE")
        failures.forEach { (input, expected) ->
            NativeWarehouse(directory, "duckdb", listOf("CREATE SEQUENCE edge_no", "CREATE TABLE graph(record_no BIGINT DEFAULT nextval('edge_no') PRIMARY KEY,tenant INTEGER,id INTEGER,parent_tenant INTEGER,parent_id INTEGER)",
                "INSERT INTO graph(tenant,id,parent_tenant,parent_id) VALUES $input")).use { warehouse ->
                val catalog = graphCatalog(warehouse); warehouse.finishSetup()
                val root = ancestors(catalog, false)
                val filtered = stages(root, root.postOps + PostFilter(ResultLiteral(false)))
                val compiled = compile(catalog, filtered)
                val failure = assertThrows(SourceExecutionException::class.java) { warehouse.execute(compiled) }
                assertEquals(expected, failure.failure.code)
                assertEquals(SourceFailureCategory.CONTRACT, failure.failure.category)
                assertEquals(warehouse.spec.source().bindingRef(), failure.failure.binding)
                assertEquals("execute-query", failure.failure.operation)
                assertEquals("JDBC", failure.failure.request.protocol)
                assertEquals(SourceRequestAcceptance.UNKNOWN, failure.failure.request.acceptance)
                assertEquals(SourceResponseState.UNKNOWN, failure.failure.request.response)
                assertEquals(SourceExecutionState.UNKNOWN, failure.failure.request.execution)
                assertEquals(SourceCancellationEvidence.NotRequested, failure.failure.cancellation)
                assertNull(failure.readEvidence)
            }
        }
    }
    /** 仅持本次作者输入；每次执行都重新构造真正不可变目录。 */
    private class Definitions(base: ExecutableCatalog) {
        val manifest = base.manifest
        val metrics = base.metrics.toMutableMap()
        val instances = base.metricInstances.toMutableMap()
        val members = base.memberSources.toMutableMap()
        val relations = base.relationFilters.toMutableMap()
        val analyses = base.analysisDimensions.toMutableMap()
        val events = base.events.toMutableMap()
        val topologies = base.topologies.toMutableMap()
        val dimensions = base.dimensions.toMutableMap()
        val bindings = base.dimensionBindings.toMutableMap()
        val units = base.logicUnits.toMutableMap()
        val anchors = base.timeAnchors.toMutableMap()
        val joins = base.joinPaths.toMutableMap()
        val physical = base.physicalExecutionBindings.toMutableMap()
        fun catalog() = ExecutableCatalog(manifest, metrics, instances, members, relations, analyses, events, topologies, dimensions, bindings, units, anchors, joins, physical)
    }
    private fun declareUnits(definitions: Definitions, table: String, types: Map<String, ScalarValueType>) {
        types.forEach { (name, type) -> definitions.units["$table.$name@1"] = ExecutableLogicUnit("$table.$name", "1", type, null, null, null, name, null, table, "1") }
    }
    private fun observationCatalog(warehouse: NativeWarehouse): Definitions {
        val names = listOf("id", "customer", "period", "kind", "amount")
        val definitions = Definitions(catalog(warehouse, "orders", names.associate { "orders.$it" to it }, emptyList()))
        declareUnits(definitions, "orders", names.associateWith { ScalarValueType.NUMBER })
        val event = definitions.events.getValue("orders@1")
        definitions.events["orders@1"] = event.copy(identity = EventIdentity(listOf(EventIdentity.LogicUnitRef("orders.id", 1)),
            mapOf("customer" to listOf(EventIdentity.LogicUnitRef("orders.customer", 1)), "order" to listOf(EventIdentity.LogicUnitRef("orders.id", 1)))), roles = mapOf("customer" to local, "order" to local))
        definitions.dimensions["customer@1"] = ExecutableDimension("customer", "1", "customer", "observation", "customer", "客户")
        definitions.analyses["customers@1"] = ExecutableAnalysisDimension("customers", "1", "客户", "customer", "customer", "customer", listOf(ExecutableAnalysisDimensionLevel("customer", "客户", null)))
        observationCustomerMapping(definitions, "orders")
        val outputs = names.associateWith { slot -> property(slot, if (slot == "id") "order" else slot, ScalarValueType.NUMBER, "orders.$slot", contract = if (slot == "amount") money else null) }
        definitions.members["orders@1"] = MemberSource.Projection("orders", 1, MemberSourceDefinition.Projection(EventRef("orders", 1),
            MemberResult("记录", "observations", MemberShape.RECORDS, listOf("id"), outputs,
                mapOf("customer" to listOf(MemberCondition.Operator.EQ, MemberCondition.Operator.NE), "period" to listOf(MemberCondition.Operator.EQ), "id" to listOf(MemberCondition.Operator.EQ)), null, emptyMap(), null)),
            names.associateWith { SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "orders.$it", 1) })
        return definitions
    }
    private fun observationCustomerMapping(definitions: Definitions, event: String) {
        val id = "customer_$event"
        definitions.bindings["$id@1"] = ExecutableDimensionBinding(id, "1", "customer", "1", event, "1", "orders", "1", "orders.customer", "1", SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "orders.customer", 1),
            local, emptyMap(), emptyList(), "orders", "1")
        val analysis = definitions.analyses.getValue("customers@1")
        definitions.analyses["customers@1"] = analysis.copy(eventMappings = analysis.eventMappings + ExecutableAnalysisDimensionEventMapping(id, "customers", "1", "customer", event, "1", "customer", "1", id, "1",
            "orders.customer", "1", "orders.customer@1", ValueAlignmentContract.PROVEN_SAME_KEY))
    }
    private fun installObservationFact(definitions: Definitions, id: String, condition: SemanticExpression?): MetricInstanceRef {
        definitions.events["$id@1"] = definitions.events.getValue("orders@1").copy(eventId = id, baseFilter = condition)
        observationCustomerMapping(definitions, id)
        val reference = MetricRef(id, "1")
        definitions.metrics[reference.key()] = ExecutableMetric(reference, MetricDefinition.FactMeasure(EventRef(id, 1), SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "orders.amount", 1),
            MetricAggregation.SUM, false, null, emptyMap(), null, money, MetricValueContract.Rounding.NONE))
        definitions.instances[id] = MetricInstance(reference, CalculationBindings.empty(), emptyMap(), id)
        return MetricInstanceRef(id)
    }
    private fun customerObservation() = Calculation.DimensionSlot("customer", "customer", 1, "customer")
    private fun observationMeasurement(name: String, grouped: Boolean) = Calculation.MeasurementSlot(name, emptyMap(), if (grouped) listOf(customerObservation()) else emptyList(), emptyList(), money)
    private fun observationBindings(definitions: Definitions, members: Map<String, CalculationBindings.Member>, measurements: Map<String, MetricInstanceRef>) =
        CalculationBindings(mapOf("customer" to CalculationBindings.Dimension(ScopedInputContract.DimensionRef("customers", "1", "customer"), "customer",
            definitions.analyses.getValue("customers@1").eventMappings.map { it.mappingId })), members, emptyMap(), measurements, emptyMap())
    private fun observationCompute(scope: Calculation.Scope, members: List<Calculation.MemberSlot>, measurements: List<Calculation.MeasurementSlot>, bindings: CalculationBindings) =
        QueryComputation.Scope(QueryComputation.Compute(Calculation(listOf(customerObservation()), members, measurements, emptyList(), emptyMap(), scope), bindings, emptyMap(), emptyMap(), emptyMap()), emptyMap(), emptyList())
    private fun observationMeasure(slot: String, grouped: Boolean) = Calculation.Scope(Calculation.Measures(listOf(Calculation.Call("value", Calculation.Measurement(slot), "value", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()))),
        if (grouped) listOf("customer") else emptyList(), emptyList(), null, null, emptyMap(), Calculation.Population(listOf("value")), null), emptyMap(), emptyList())
    private fun topCustomer(slot: String) = Calculation.Scope(observationMeasure(slot, true).root, emptyMap(), listOf(PostOperationContract.TopN(
        listOf(PostOperationContract.OrderKey("value", "DESC", "LAST"), PostOperationContract.OrderKey("customer", "ASC", "LAST")), 1)))
    private fun orderFormal(name: String, fixed: MemberRef?) = Calculation.MemberSlot(name, "记录", "observations", MemberShape.RECORDS,
        mapOf("id" to Calculation.MemberProperty("order", ScalarValueType.NUMBER, null), "customer" to Calculation.MemberProperty("customer", ScalarValueType.NUMBER, null),
            "period" to Calculation.MemberProperty("period", ScalarValueType.NUMBER, null), "kind" to Calculation.MemberProperty("kind", ScalarValueType.NUMBER, null), "amount" to Calculation.MemberProperty("amount", ScalarValueType.NUMBER, money)), fixed)
    private fun orderOutputSlots() = listOf("id", "customer", "period", "kind", "amount").associateWith { it }
    private fun orderSource() = CalculationBindings.SourceCall(MemberRef("orders", 1), emptyMap(), orderOutputSlots(), emptyMap(), emptyMap())
    private fun orderRows(slot: String, condition: MemberCondition, operations: List<PostOperationContract.ValidatedOperation>) =
        Calculation.Scope(Calculation.Members("records", slot, orderOutputSlots().keys.map { Calculation.MemberProjection(it, it) }, null, emptyMap(), condition), emptyMap(), operations)
    private fun memberEquals(slot: String, value: Int) = MemberCondition.Predicate(slot, MemberCondition.Operator.EQ, listOf(Calculation.Scalar(ScalarValueType.NUMBER, value.toString())))
    private fun semanticEquals(column: String, value: Int) = SemanticExpression.Operation(SemanticExpression.Operator.EQ, listOf(SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "orders.$column", 1), SemanticExpression.Literal(BigDecimal.valueOf(value.toLong()))))
    private fun observationMean() = MetricValueContract(money.unit, MetricValueContract.Approximate("0", "1000000", "1E-9", "1E-9"), MetricValueContract.BinaryFloat(64), emptyMap(), money.overflow)
    private fun reduction(input: String, function: ResultAggregation, output: String) = aggregate(input, function, output)
    private fun regroup(keys: List<String>, aggregates: List<ResultAggregate>) = Regroup(ResultGrouping(keys, aggregates, null))
    private fun engine(warehouse: NativeWarehouse): OlapEngine {
        val source = com.olap.prototype.connector.SourceProvider(warehouse.spec.source(), TestConnectors.registry)
        return OlapEngine(OlapSemanticCompiler(CalciteRelCompiler(TestConnectors.registry)), SourceExecutableSqlExecutor({ if (it == source.bindingRef) source else null }, RuntimeDeploymentLimits.result, RuntimeDeploymentLimits.nativeMemoryBytes, null))
    }
    private fun execute(engine: OlapEngine, definitions: Definitions, computation: QueryComputation.Scope) = execute(engine, definitions.catalog(), computation)
    private fun execute(engine: OlapEngine, catalog: ExecutableCatalog, computation: QueryComputation.Scope): ExecutableBusinessResult = engine.execute(catalog,
        ExecutableQueryRequest(ExecutableQueryRequest.FORMAT_VERSION, "member_behavior", catalog.manifest.catalogId, computation), observer, RuntimeDeploymentLimits.CONTROL).businessResult
    private fun decimal(value: String) = BigDecimal(value).stripTrailingZeros()
    private fun numericRows(result: ExecutableBusinessResult): List<List<BigDecimal?>> = result.rows.map { row -> row.map { value ->
        if (value is ExecutableBusinessResult.NullValue) null else decimal((value as ExecutableBusinessResult.ScalarValue).value)
    } }.sortedWith(compareBy(nullsFirst()) { it.first() })
    private fun measures(calls: List<QueryComputation.Invocation>, inputs: Map<String, QueryComputation.Input>) = QueryComputation.Scope(QueryComputation.Measures(calls, emptyList(), null, emptyMap(),
        DimensionCondition.Unrestricted(), DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(calls.map { it.callId }), null), inputs, emptyList())
    @Test fun qualifiesObjectsBeforeMeasurementWithoutChangingCohortsOrCompletedLocalInputs(@TempDir directory: Path) {
        NativeWarehouse(directory, "duckdb", listOf("CREATE TABLE orders(id INTEGER PRIMARY KEY, customer INTEGER, period INTEGER, kind INTEGER, amount DECIMAL(18,2))",
            "INSERT INTO orders VALUES (1,1,1,1,60),(2,1,1,1,40),(3,2,1,1,50),(4,1,2,1,10),(5,2,2,1,120),(6,3,2,1,5)")).use { warehouse ->
            val definitions = observationCatalog(warehouse)
            val early = installObservationFact(definitions, "early", semanticEquals("period", 1)); val later = installObservationFact(definitions, "later", semanticEquals("period", 2))
            val measurements = mapOf("early_total" to early, "early_customers" to early, "later_total" to later, "later_customers" to later)
            val slots = listOf(observationMeasurement("early_total", false), observationMeasurement("early_customers", true), observationMeasurement("later_total", false), observationMeasurement("later_customers", true))
            val members = listOf(orderFormal("rows", MemberRef("orders", 1)), orderFormal("formed", null))
            val bindings = observationBindings(definitions, mapOf("rows" to orderSource(), "formed" to CalculationBindings.LocalRef("original", orderOutputSlots())), measurements)
            val original = orderRows("rows", memberEquals("period", 1), emptyList())
            val originalInputs = mapOf("original" to Calculation.InputScope("ROOT", original))
            val duplicateA = orderRows("rows", MemberCondition.All(listOf(memberEquals("period", 1), memberEquals("customer", 1))), listOf(PostOperationContract.Project(listOf("customer"))))
            val onlyB = orderRows("rows", memberEquals("customer", 2), listOf(PostOperationContract.Project(listOf("customer"))))
            val earlyTop = topCustomer("early_customers"); val laterTop = topCustomer("later_customers")
            val generic = MetricRef("generic_total", "1")
            val genericBody = Calculation(listOf(customerObservation()), emptyList(), listOf(observationMeasurement("amount", false)), emptyList(), emptyMap(), observationMeasure("amount", false))
            definitions.metrics[generic.key()] = ExecutableMetric(generic, MetricCalculation(genericBody, "value", emptyList(), money))
            val earlyMeasurement = MetricInstanceRef("early_measurement")
            definitions.instances[earlyMeasurement.id] = MetricInstance(generic, observationBindings(definitions, emptyMap(), mapOf("amount" to early)), emptyMap(), "首期总额")
            warehouse.finishSetup(); val engine = engine(warehouse)
            fun qualified(measurement: Calculation.Scope, objects: Calculation.Scope, target: Calculation.ObjectTarget = Calculation.EventRole("customer")) =
                Calculation.Scope(Calculation.ObjectMeasure(measurement, objects, listOf(Calculation.ObjectRole(listOf("customer"), target))), emptyMap(), emptyList())
            fun actual(body: Calculation.Scope, declaredMembers: List<Calculation.MemberSlot> = members.take(1), declaredMeasurements: List<Calculation.MeasurementSlot> = slots,
                adopted: CalculationBindings = observationBindings(definitions, mapOf("rows" to orderSource()), measurements)) =
                numericRows(execute(engine, definitions, observationCompute(body, declaredMembers, declaredMeasurements, adopted)))
            assertEquals(listOf(listOf(decimal("100"))), actual(qualified(observationMeasure("early_total", false), duplicateA)))
            assertEquals(listOf(listOf(decimal("10"))), actual(qualified(observationMeasure("later_total", false), earlyTop), emptyList(), adopted = observationBindings(definitions, emptyMap(), measurements)))
            assertEquals(listOf(listOf(decimal("120"))), actual(qualified(observationMeasure("later_total", false), laterTop), emptyList(), adopted = observationBindings(definitions, emptyMap(), measurements)))
            val nested = qualified(qualified(observationMeasure("early_total", false), earlyTop), onlyB)
            assertEquals(listOf(listOf<BigDecimal?>(null)), actual(nested))
            val completedLocal = Calculation.Scope(Calculation.Members("records", "formed", listOf(Calculation.MemberProjection("id", "id"), Calculation.MemberProjection("amount", "amount")), null, emptyMap(), MemberCondition.Unrestricted()),
                emptyMap(), listOf(regroup(emptyList(), listOf(reduction("amount", ResultAggregation.Sum(money, ResultAggregation.NullPolicy.IGNORE), "total")))))
            val qualifiedLocal = qualified(completedLocal, duplicateA, Calculation.RecordOutputs("formed", listOf("customer")))
            val localTotals = Calculation.Scope(Calculation.AlignMeasurements(linkedMapOf(
                "original_total" to Calculation.AlignedInput(orderRows("formed", MemberCondition.Unrestricted(), listOf(regroup(emptyList(), listOf(reduction("amount", ResultAggregation.Sum(money, ResultAggregation.NullPolicy.IGNORE), "total"))))), emptyMap(), listOf(Calculation.AlignedValue("total", "original_amount", Calculation.NoMatch.PRESERVE_NULL))),
                "qualified_total" to Calculation.AlignedInput(qualifiedLocal, emptyMap(), listOf(Calculation.AlignedValue("total", "qualified_amount", Calculation.NoMatch.PRESERVE_NULL)))), emptyList(), Calculation.AlignmentPopulation(Calculation.PopulationKind.BASE, listOf("original_total"))),
                originalInputs, listOf(PostOperationContract.Project(listOf("original_amount", "qualified_amount"))))
            assertEquals(listOf(listOf(decimal("150"), decimal("100"))), actual(localTotals, members, emptyList(), observationBindings(definitions, bindings.members, emptyMap())))
            val completed = Calculation.Scope(Calculation.Input("original"), mapOf("original" to Calculation.InputScope("ROOT", observationMeasure("early_total", false))), emptyList())
            assertEquals("BIND_INPUT", assertThrows(EngineExecutionException::class.java) { actual(qualified(completed, duplicateA)) }.stage)
            assertEquals(listOf(listOf(decimal("150"))), actual(observationMeasure("early_total", false), emptyList(), adopted = observationBindings(definitions, emptyMap(), measurements)))
            assertEquals(listOf(listOf(decimal("150"))), numericRows(execute(engine, definitions, measures(listOf(QueryComputation.Invocation("reused", earlyMeasurement, "reused_total", emptyMap(), emptyMap(), emptyMap(), DimensionCondition.Unrestricted())), emptyMap()))))
        }
    }
    private fun recordCount() = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(), MetricValueContract.Integer(64), emptyMap(), MetricValueContract.Overflow.FAIL)
    private fun installAllocationInputs(warehouse: NativeWarehouse, definitions: Definitions) {
        definitions.dimensions["order@1"] = ExecutableDimension("order", "1", "order", "observation", "order", "交易")
        definitions.bindings["order_sales@1"] = ExecutableDimensionBinding("order_sales", "1", "order", "1", "sales", "1", "orders", "1", "orders.id", "1",
            SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "orders.id", 1), local, emptyMap(), emptyList(), "orders", "1")
        definitions.analyses["transactions@1"] = ExecutableAnalysisDimension("transactions", "1", "交易", "order", "transaction", "order", listOf(ExecutableAnalysisDimensionLevel("order", "交易", null)),
            listOf(ExecutableAnalysisDimensionEventMapping("order_sales", "transactions", "1", "order", "sales", "1", "order", "1", "order_sales", "1", "orders.id", "1", null, ValueAlignmentContract.SINGLE_EVENT_ONLY)))
        definitions.physical["associations@1"] = warehouse.adopt("associations", mapOf("associations.order_id" to "order_id", "associations.region" to "region"))
        declareUnits(definitions, "associations", mapOf("order_id" to ScalarValueType.NUMBER, "region" to ScalarValueType.NUMBER))
        definitions.topologies["associations@1"] = ExecutableTopology("associations", "1", "associations", null, "associations", "1")
        definitions.events["associations@1"] = ExecutableEvent("associations", "1", "associations", "1", null,
            EventIdentity(listOf(EventIdentity.LogicUnitRef("associations.order_id", 1), EventIdentity.LogicUnitRef("associations.region", 1)), emptyMap()), emptyMap(), null, null, null)
        definitions.members["places@1"] = MemberSource.Projection("places", 1, MemberSourceDefinition.Projection(EventRef("associations", 1),
            MemberResult("交易地区关系", "places", MemberShape.RECORDS, listOf("order", "region"), mapOf("order" to property("交易", "order", ScalarValueType.NUMBER, "associations.order_id"),
                "region" to property("地区", "region", ScalarValueType.NUMBER, "associations.region")), emptyMap(), null, emptyMap(), null)),
            mapOf("order" to SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "associations.order_id", 1), "region" to SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "associations.region", 1)))
    }
    private fun allocationCompute(scope: Calculation.Scope): QueryComputation.Scope {
        val transaction = Calculation.DimensionSlot("transaction", "order", 1, "order")
        val places = Calculation.MemberSlot("places", "交易地区关系", "places", MemberShape.RECORDS,
            mapOf("order" to Calculation.MemberProperty("order", ScalarValueType.NUMBER, null), "region" to Calculation.MemberProperty("region", ScalarValueType.NUMBER, null)), MemberRef("places", 1))
        val calculation = Calculation(listOf(transaction), listOf(places, orderFormal("eligible", MemberRef("orders", 1))), listOf(Calculation.MeasurementSlot("amount", emptyMap(), listOf(transaction), emptyList(), money)), emptyList(), emptyMap(), scope)
        val bindings = CalculationBindings(mapOf("transaction" to CalculationBindings.Dimension(ScopedInputContract.DimensionRef("transactions", "1", "order"), "order", listOf("order_sales"))), mapOf("eligible" to orderSource(), "places" to CalculationBindings.SourceCall(MemberRef("places", 1), emptyMap(), mapOf("order" to "order", "region" to "region"), emptyMap(), emptyMap())), emptyMap(), mapOf("amount" to MetricInstanceRef("sales")), emptyMap())
        return QueryComputation.Scope(QueryComputation.Compute(calculation, bindings, emptyMap(), emptyMap(), emptyMap()), emptyMap(), emptyList())
    }
    @Test fun alignsIndependentFactPopulationsAndZerosOnlyGenuinelyAbsentMeasurements(@TempDir directory: Path) {
        NativeWarehouse(directory, "duckdb", listOf("CREATE TABLE orders(id INTEGER PRIMARY KEY, customer INTEGER, period INTEGER, kind INTEGER, amount DECIMAL(18,2))",
            "INSERT INTO orders VALUES (1,1,1,1,10),(2,2,1,1,NULL),(3,3,1,1,20),(4,1,1,2,2),(5,2,1,2,NULL),(6,4,1,2,7)")).use { warehouse ->
            val definitions = observationCatalog(warehouse)
            val purchases = installObservationFact(definitions, "purchases", semanticEquals("kind", 1)); val refunds = installObservationFact(definitions, "refunds", semanticEquals("kind", 2))
            val slots = listOf(observationMeasurement("purchases", true), observationMeasurement("refunds", true))
            val bindings = observationBindings(definitions, emptyMap(), mapOf("purchases" to purchases, "refunds" to refunds))
            val inputs = linkedMapOf("purchases" to Calculation.AlignedInput(observationMeasure("purchases", true), mapOf("customer" to "customer"), listOf(Calculation.AlignedValue("value", "purchased", Calculation.NoMatch.ZERO_WHEN_ABSENT))),
                "refunds" to Calculation.AlignedInput(observationMeasure("refunds", true), mapOf("customer" to "customer"), listOf(Calculation.AlignedValue("value", "refunded", Calculation.NoMatch.ZERO_WHEN_ABSENT))))
            warehouse.finishSetup(); val engine = engine(warehouse)
            val baseline = listOf(listOf(decimal("1"), decimal("10"), decimal("2")), listOf(decimal("2"), null, null), listOf(decimal("3"), decimal("20"), decimal("0")))
            for (population in listOf(Calculation.PopulationKind.BASE, Calculation.PopulationKind.UNION, Calculation.PopulationKind.INTERSECTION)) {
                val selected = if (population == Calculation.PopulationKind.BASE) listOf("purchases") else listOf("purchases", "refunds")
                val body = Calculation.Scope(Calculation.AlignMeasurements(inputs, listOf("customer"), Calculation.AlignmentPopulation(population, selected)), emptyMap(),
                    listOf(PostOperationContract.Project(listOf("customer", "purchased", "refunded"))))
                val expected = when (population) { Calculation.PopulationKind.UNION -> baseline + listOf(listOf(decimal("4"), decimal("0"), decimal("7"))); Calculation.PopulationKind.INTERSECTION -> baseline.dropLast(1); else -> baseline }
                assertEquals(expected, numericRows(execute(engine, definitions, observationCompute(body, emptyList(), slots, bindings))))
            }
        }
        NativeWarehouse(directory, "duckdb", listOf("CREATE TABLE orders(id INTEGER PRIMARY KEY, customer INTEGER, period INTEGER, kind INTEGER, amount DECIMAL(18,2))",
            "INSERT INTO orders VALUES (1,1,1,1,1000),(2,2,1,1,2000)", "CREATE TABLE associations(order_id INTEGER, region INTEGER, PRIMARY KEY(order_id,region))", "INSERT INTO associations VALUES (1,1),(1,2),(2,1),(2,2)")).use { warehouse ->
            val definitions = observationCatalog(warehouse); installObservationFact(definitions, "sales", null); installAllocationInputs(warehouse, definitions)
            val output = observationMean()
            val allocation = MetricValueContract(output.unit, output.arithmetic, output.result, mapOf("divide" to MetricValueContract.PreserveUnit(MetricValueContract.BinaryOperator.DIVIDE, MetricValueContract.Operand.LEFT)), output.overflow)
            val order = ScopedInputContract.metricSymbol("place", "order"); val region = ScopedInputContract.metricSymbol("place", "region")
            val joined = PostOperationContract.JoinInput("places", "place", "INNER", ResultExpression.ResultOperation(ResultExpression.ResultOperator.EQ, listOf(ResultExpression.ResultSymbol("transaction"), ResultExpression.ResultSymbol(order))))
            val allocationStage = PostOperationContract.Derive(ResultExpression.ResultUniformAllocation(ResultExpression.ResultSymbol("value"), listOf("transaction"), listOf(order, region), allocation, "divide"), "allocated", "完整关联总体内均匀分配金额")
            val source = Calculation.Scope(Calculation.Measures(listOf(Calculation.Call("value", Calculation.Measurement("amount"), "value", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()))), listOf("transaction"), emptyList(), null, null, emptyMap(), Calculation.Population(listOf("value")), null),
                mapOf("places" to Calculation.InputScope("RELATION", Calculation.Scope(Calculation.Members("places", "places", listOf(Calculation.MemberProjection("order", "order"), Calculation.MemberProjection("region", "region")), null, emptyMap(), MemberCondition.Unrestricted()), emptyMap(), emptyList()))), listOf(joined, allocationStage))
            val qualified = Calculation.ObjectMeasure(source, orderRows("eligible", memberEquals("id", 1), listOf(PostOperationContract.Project(listOf("id")))), listOf(Calculation.ObjectRole(listOf("id"), Calculation.EventRole("order"))))
            warehouse.finishSetup(); val engine = engine(warehouse)
            fun actual(operations: List<PostOperationContract.ValidatedOperation>) = numericRows(execute(engine, definitions, allocationCompute(Calculation.Scope(qualified, emptyMap(), operations))))
            assertEquals(listOf(listOf(decimal("1"), decimal("500")), listOf(decimal("2"), decimal("500"))), actual(listOf(PostOperationContract.Project(listOf(region, "allocated")))))
            assertEquals(listOf(listOf(decimal("1"), decimal("1000")), listOf(decimal("2"), decimal("1000"))), actual(listOf(PostOperationContract.Project(listOf(region, "value")))))
            assertEquals(listOf(listOf(decimal("1000"))), actual(listOf(regroup(emptyList(), listOf(reduction("allocated", ResultAggregation.Sum(output, ResultAggregation.NullPolicy.IGNORE), "total"))))))
            val filter = PostOperationContract.PostFilter(ResultExpression.ResultOperation(ResultExpression.ResultOperator.EQ, listOf(ResultExpression.ResultSymbol(region), ResultExpression.ResultLiteral(BigDecimal.ONE))))
            assertEquals(listOf(listOf(decimal("1"), decimal("500"))), actual(listOf(filter, PostOperationContract.Project(listOf(region, "allocated")))))
            assertEquals("ANALYZE_STAGES", assertThrows(EngineExecutionException::class.java) { actual(listOf(regroup(emptyList(), listOf(reduction("value", ResultAggregation.Sum(money, ResultAggregation.NullPolicy.IGNORE), "total"))))) }.stage)
            val split = Calculation.Scope(source.root, source.inputs, listOf(joined, PostOperationContract.Derive(ResultExpression.ResultUniformAllocation(ResultExpression.ResultSymbol("value"), listOf("transaction", region), listOf(order), allocation, "divide"), "allocated", "错误地区身份")))
            val invalid = allocationCompute(Calculation.Scope(Calculation.ObjectMeasure(split, qualified.objects, qualified.roles), emptyMap(), listOf(regroup(emptyList(), listOf(reduction("allocated", ResultAggregation.Sum(output, ResultAggregation.NullPolicy.IGNORE), "total"))))))
            assertEquals("ANALYZE_STAGES", assertThrows(EngineExecutionException::class.java) { execute(engine, definitions, invalid) }.stage)
            val childKeys = listOf("transaction", order, region)
            val childStages = listOf(joined, regroup(childKeys, listOf(reduction("value", ResultAggregation.Sum(money, ResultAggregation.NullPolicy.IGNORE), "copied_value"))))
            assertEquals(listOf(listOf(decimal("1"), decimal("1"), decimal("1"), decimal("1000")), listOf(decimal("1"), decimal("1"), decimal("2"), decimal("1000")),
                listOf(decimal("2"), decimal("2"), decimal("1"), decimal("2000")), listOf(decimal("2"), decimal("2"), decimal("2"), decimal("2000"))),
                numericRows(execute(engine, definitions, allocationCompute(Calculation.Scope(source.root, source.inputs, childStages)))))
            val launder = PostOperationContract.Derive(ResultExpression.ResultUniformAllocation(ResultExpression.ResultSymbol("copied_value"), childKeys, listOf(region), allocation, "divide"), "laundered", "子行SUM不赋予可加性")
            val failure = assertThrows(EngineExecutionException::class.java) { execute(engine, definitions, allocationCompute(Calculation.Scope(source.root, source.inputs, childStages + launder))) }
            assertEquals("ANALYZE_STAGES", failure.stage); assertTrue(requireNotNull(failure.message).contains("uniform allocation requires"), failure.message)
        }
    }
    @Test fun provesFilteredAlignmentIdentitiesWithoutTrustingNullableRawPopulation(@TempDir directory: Path) {
        for (identityType in listOf(ScalarValueType.NUMBER, ScalarValueType.TEXT)) NativeWarehouse(directory, "duckdb", listOf(
            "CREATE TABLE orders(id INTEGER, customer ${if (identityType == ScalarValueType.NUMBER) "INTEGER" else "VARCHAR"}, period INTEGER, kind INTEGER, amount DECIMAL(18,2))",
            "INSERT INTO orders VALUES (1,1,1,1,10),(2,1,2,1,20),(3,NULL,1,1,5),(4,2,2,1,NULL)"
        )).use { warehouse ->
            val definitions = observationCatalog(warehouse)
            declareUnits(definitions, "orders", mapOf("customer" to identityType))
            val projected = definitions.members.getValue("orders@1") as MemberSource.Projection
            val result = projected.definition.result
            val memberOutputs = result.outputs + ("customer" to property("客户", "customer", identityType, "orders.customer"))
            definitions.members["orders@1"] = MemberSource.Projection(projected.id, projected.version,
                projected.definition.copy(result = MemberResult(result.entity, result.role, MemberShape.RAW_BAG, emptyList(),
                    memberOutputs, result.filterOperators, result.timeSlot, result.clockSlots, result.description)), projected.outputExpressions)
            val formal = orderFormal("rows", MemberRef("orders", 1))
            val members = listOf(Calculation.MemberSlot(formal.name, formal.entity, formal.role, MemberShape.RAW_BAG,
                formal.outputs + ("customer" to Calculation.MemberProperty("customer", identityType, null)), formal.fixedSource))
            val bindings = observationBindings(definitions, mapOf("rows" to orderSource()), emptyMap())
            fun query(restrictIdentity: Boolean): QueryComputation.Scope {
                fun period(value: Int) = orderRows("rows", if (restrictIdentity)
                    MemberCondition.All(listOf(MemberCondition.Predicate("customer", MemberCondition.Operator.EQ,
                        listOf(Calculation.Scalar(identityType, "1"))), memberEquals("period", value)))
                else memberEquals("period", value), listOf(
                    regroup(listOf("customer"), listOf(reduction("amount", ResultAggregation.Sum(money, ResultAggregation.NullPolicy.IGNORE), "total")))
                ))
                val inputs = linkedMapOf(
                    "before" to Calculation.AlignedInput(period(1), mapOf("customer" to "customer"), listOf(Calculation.AlignedValue("total", "before", Calculation.NoMatch.PRESERVE_NULL))),
                    "after" to Calculation.AlignedInput(period(2), mapOf("customer" to "customer"), listOf(Calculation.AlignedValue("total", "after", Calculation.NoMatch.ZERO_WHEN_ABSENT)))
                )
                return observationCompute(Calculation.Scope(Calculation.AlignMeasurements(inputs, listOf("customer"),
                    Calculation.AlignmentPopulation(Calculation.PopulationKind.BASE, listOf("before"))), emptyMap(), emptyList()), members, emptyList(), bindings)
            }
            warehouse.finishSetup()
            val catalog = definitions.catalog()
            val compiled = OlapSemanticCompiler(CalciteRelCompiler(TestConnectors.registry)).compile(catalog,
                ExecutableQueryRequest(ExecutableQueryRequest.FORMAT_VERSION, "filtered-alignment", catalog.manifest.catalogId, query(true)),
                OlapQueryContext("filtered-alignment"), observer)
            val lowered = requireNotNull(compiled.executableLoweringResult)
            assertTrue(lowered.relationChecks.isEmpty(), "过滤与归约已经证明非 NULL，不应再提交独立关系检查：${lowered.relationChecks.joinToString { it.sql }}")
            assertFalse(com.olap.prototype.connector.SourceCapabilities.Capability.CONSISTENT_READ in lowered.toSourceRequest("execute-query", null).requiredCapabilities)
            assertEquals(listOf(listOf(decimal("1"), decimal("10"), decimal("20"))), warehouse.execute(lowered).map { row -> row.map { it.toString().toBigDecimal().stripTrailingZeros() } })

            val unknown = OlapSemanticCompiler(CalciteRelCompiler(TestConnectors.registry)).compile(catalog,
                ExecutableQueryRequest(ExecutableQueryRequest.FORMAT_VERSION, "unknown-alignment", catalog.manifest.catalogId, query(false)),
                OlapQueryContext("unknown-alignment"), observer)
            val guarded = requireNotNull(unknown.executableLoweringResult)
            val check = guarded.relationChecks.first { it.code == "OBSERVATION_OBJECT_IDENTITY_NULL" }
            assertEquals(listOf("customer"), requireNotNull(check.origin).keys)
            assertNotNull(check.origin?.scope)
            assertTrue(com.olap.prototype.connector.SourceCapabilities.Capability.CONSISTENT_READ in guarded.toSourceRequest("execute-query", null).requiredCapabilities)
            val failure = assertThrows(EngineExecutionException::class.java) { execute(engine(warehouse), definitions, query(false)) }
            assertEquals("OBSERVATION_OBJECT_IDENTITY_NULL", failure.code)
        }
    }
    @Test fun distinguishesWeightedObjectsRawOccurrencesAndAnIndependentReferencePopulation(@TempDir directory: Path) {
        NativeWarehouse(directory, "duckdb", listOf("CREATE TABLE orders(id INTEGER PRIMARY KEY, customer INTEGER, period INTEGER, kind INTEGER, amount DECIMAL(18,2))",
            "INSERT INTO orders VALUES (1,1,1,1,10),(2,2,1,1,100),(3,2,1,1,100),(4,2,1,1,100),(5,3,1,1,30),(6,4,1,1,NULL),(7,NULL,1,1,NULL)")).use { warehouse ->
            val definitions = observationCatalog(warehouse); val average = observationMean(); val identity = (money.unit as MetricValueContract.Fixed).identity
            val product = MetricValueContract(average.unit, average.arithmetic, average.result, mapOf("product" to MetricValueContract.FixedBinary(MetricValueContract.BinaryOperator.MULTIPLY, identity, MetricValueContract.ONE, identity)), average.overflow)
            val ratio = MetricValueContract(average.unit, average.arithmetic, average.result, mapOf("quotient" to MetricValueContract.FixedBinary(MetricValueContract.BinaryOperator.DIVIDE, identity, MetricValueContract.ONE, identity)), average.overflow)
            val selected = MemberCondition.Predicate("customer", MemberCondition.Operator.NE, listOf(Calculation.Scalar(ScalarValueType.NUMBER, "3")))
            val customers = orderRows("rows", selected, listOf(regroup(listOf("customer"), listOf(reduction("amount", ResultAggregation.Average(average, ResultAggregation.NullPolicy.IGNORE, MetricValueContract.Rounding.NONE), "mean"), reduction("id", ResultAggregation.Count(false, ResultAggregation.NullPolicy.IGNORE), "weight")))))
            fun reduction(input: String, aggregation: ResultAggregation, alias: String) = Calculation.Reduction(PostOperationContract.ResultAggregate(input, aggregation, alias, alias))
            val objects = Calculation.Scope(Calculation.ObservationStatistics(customers, Calculation.ObjectsBy(listOf("customer")), emptyList(), listOf(
                Calculation.WeightedMean("mean", "weight", "weighted", "按真实记录数加权的客户均额", product, recordCount(), ratio, "product", "quotient", "IGNORE_PAIR"),
                reduction("mean", ResultAggregation.Average(average, ResultAggregation.NullPolicy.IGNORE, MetricValueContract.Rounding.NONE), "unweighted"),
                reduction("customer", ResultAggregation.Count(false, ResultAggregation.NullPolicy.IGNORE), "objects"))), emptyMap(), emptyList())
            val reference = Calculation.Scope(Calculation.ObservationStatistics(orderRows("rows", MemberCondition.Unrestricted(), emptyList()), Calculation.RawRows(), emptyList(),
                listOf(reduction("amount", ResultAggregation.Average(average, ResultAggregation.NullPolicy.IGNORE, MetricValueContract.Rounding.NONE), "reference"))), emptyMap(), emptyList())
            val raw = Calculation.Scope(Calculation.ObservationStatistics(orderRows("rows", selected, emptyList()), Calculation.RawRows(), emptyList(), listOf(reduction("id", ResultAggregation.Count(false, ResultAggregation.NullPolicy.IGNORE), "raw_count"))), emptyMap(), emptyList())
            val inputs = linkedMapOf("objects" to Calculation.AlignedInput(objects, emptyMap(), listOf(Calculation.AlignedValue("weighted", "weighted", Calculation.NoMatch.PRESERVE_NULL),
                Calculation.AlignedValue("unweighted", "unweighted", Calculation.NoMatch.PRESERVE_NULL), Calculation.AlignedValue("objects", "object_count", Calculation.NoMatch.PRESERVE_NULL))),
                "reference" to Calculation.AlignedInput(reference, emptyMap(), listOf(Calculation.AlignedValue("reference", "reference_mean", Calculation.NoMatch.PRESERVE_NULL))),
                "raw" to Calculation.AlignedInput(raw, emptyMap(), listOf(Calculation.AlignedValue("raw_count", "raw_count", Calculation.NoMatch.PRESERVE_NULL))))
            val bindings = observationBindings(definitions, mapOf("rows" to orderSource()), emptyMap()); val members = listOf(orderFormal("rows", MemberRef("orders", 1)))
            warehouse.finishSetup(); val engine = engine(warehouse)
            val body = Calculation.Scope(Calculation.AlignMeasurements(inputs, emptyList(), Calculation.AlignmentPopulation(Calculation.PopulationKind.BASE, listOf("objects"))), emptyMap(),
                listOf(PostOperationContract.Project(listOf("weighted", "unweighted", "object_count", "reference_mean", "raw_count"))))
            val authored = observationCompute(body, members, emptyList(), bindings)
            val restored = SemanticProto.fromProto(ProtoBytes.decode(ProtoBytes.encode(SemanticProto.toProto(authored)), com.olap.prototype.wire.semantic.QueryScope.getDefaultInstance()))
            assertEquals(listOf(listOf(decimal("77.5"), decimal("55"), decimal("3"), decimal("68"), decimal("5"))),
                numericRows(execute(engine, definitions, restored)))
            val falseObjects = orderRows("rows", MemberCondition.Unrestricted(), listOf(
                PostOperationContract.Derive(ResultExpression.ResultQuantityLiteral(BigDecimal.ONE, recordCount()), "weight", "每行权重"),
                PostOperationContract.Derive(ResultExpression.ResultWeightedContribution(ResultExpression.ResultSymbol("amount"), ResultExpression.ResultSymbol("weight"), Calculation.ObjectsBy(listOf("customer")), ResultExpression.WeightedComponent.VALUE, product, "product"), "contribution", "伪客户观察")))
            assertEquals("ANALYZE_STAGES", assertThrows(EngineExecutionException::class.java) { execute(engine, definitions, observationCompute(falseObjects, members, emptyList(), bindings)) }.stage)
            val customerRows = orderRows("rows", MemberCondition.Unrestricted(), listOf(regroup(listOf("customer"), listOf(this.reduction("id", ResultAggregation.Count(false, ResultAggregation.NullPolicy.IGNORE), "records")))))
            val unknownObjects = Calculation.Scope(Calculation.ObservationStatistics(customerRows, Calculation.ObjectsBy(listOf("customer")), emptyList(), listOf(reduction("customer", ResultAggregation.Count(false, ResultAggregation.NullPolicy.IGNORE), "objects"))), emptyMap(), emptyList())
            val failure = assertThrows(EngineExecutionException::class.java) { execute(engine, definitions, observationCompute(unknownObjects, members, emptyList(), bindings)) }
            assertEquals("VERIFY_SOURCE", failure.stage); assertEquals("OBSERVATION_OBJECT_IDENTITY_NULL", failure.code)
        }
    }
    private fun installProjection(definitions: Definitions, id: String, shape: MemberShape, identity: List<String>, outputs: Map<String, MemberSourceDefinition.Output>) {
        val input = members(definitions.catalog(), id, shape, identity, outputs, outputs.keys.associateWith { it })
        definitions.members["$id@1"] = ((input.root as ExecutableInputRoot.Members).input as ExecutableMemberInput.Projection).memberSource
    }
    private fun computedProperty(name: String, role: String, symbol: String, quantity: MetricValueContract?) = MemberSourceDefinition.ComputedPropertyOutput(name, role, ScalarValueType.NUMBER, symbol, quantity)
    private fun identityOutputs(result: MemberResult) = result.outputs.keys.associateWith { it }
    private fun installMemberReduction(definitions: Definitions, id: String, source: MemberResult, field: String, aggregation: ResultAggregation, output: MetricValueContract): MetricInstanceRef {
        val formal = source.outputs.mapValues { (_, meaning) -> Calculation.MemberProperty(meaning.role, MemberSourceDefinition.scalar(meaning).valueType, MemberSourceDefinition.rootValueContract(meaning)) }
        val body = Calculation(emptyList(), listOf(Calculation.MemberSlot("members", source.entity, source.role, source.shape, formal, null)), emptyList(), emptyList(), emptyMap(), Calculation.Scope(Calculation.Members("objects", "members", formal.keys.map { Calculation.MemberProjection(it, it) }, null, emptyMap(), MemberCondition.Unrestricted()), emptyMap(),
            listOf(regroup(emptyList(), listOf(reduction(field, aggregation, "value"))))))
        val ref = MetricRef(id, "1"); definitions.metrics[ref.key()] = ExecutableMetric(ref, MetricCalculation(body, "value", emptyList(), output))
        val instance = MetricInstanceRef("${id}_instance")
        definitions.instances[instance.id] = MetricInstance(ref, CalculationBindings(emptyMap(), mapOf("members" to CalculationBindings.InputParameter()), emptyMap(), emptyMap(), emptyMap()), emptyMap(), id)
        return instance
    }
    private fun memberScope(source: String, outputs: List<String>, operations: List<PostOperationContract.ValidatedOperation>) = QueryComputation.Scope(
        QueryComputation.Members("members", MemberRef(source, 1), emptyMap(), outputs.map { QueryComputation.Slot(it, it) }, null, emptyMap(), MemberCondition.Unrestricted(), emptyMap(), emptyMap()), emptyMap(), operations)

    private val floatingMoney = MetricValueContract(money.unit, MetricValueContract.NativeFloating(), MetricValueContract.BinaryFloat(64), emptyMap(), money.overflow)
    private fun transactionBags(directory: Path) = NativeWarehouse(directory, "duckdb", listOf(
        "CREATE TABLE transactions(order_id INTEGER, amount DOUBLE)",
        "INSERT INTO transactions VALUES (1,100),(2,50),(3,30),(4,NULL),(5,20)",
        "CREATE TABLE attributes(order_id INTEGER, tag VARCHAR, partition_no INTEGER, label VARCHAR)",
        "INSERT INTO attributes VALUES (1,'x',1,'A'),(1,'x',2,'a'),(2,'y',1,'A '),(4,'x',1,'é'),(5,'y',1,'e\u0301')"))

    private fun transactionBagDefinitions(warehouse: NativeWarehouse): Definitions {
        val transactions = catalog(warehouse, "transactions", mapOf("transactions.order_id" to "order_id", "transactions.amount" to "amount"), emptyList(), "transactions.order_id")
        val attributes = catalog(warehouse, "attributes", listOf("order_id", "tag", "partition_no", "label").associate { "attributes.$it" to it }, emptyList(), "attributes.order_id")
        val definitions = Definitions(transactions)
        definitions.physical.putAll(attributes.physicalExecutionBindings)
        definitions.topologies.putAll(attributes.topologies)
        (transactions.events + attributes.events).forEach { (id, event) -> definitions.events[id] = event.copy(identity = EventIdentity(emptyList(), emptyMap())) }
        declareUnits(definitions, "transactions", mapOf("order_id" to ScalarValueType.NUMBER, "amount" to ScalarValueType.NUMBER))
        declareUnits(definitions, "attributes", mapOf("order_id" to ScalarValueType.NUMBER, "tag" to ScalarValueType.TEXT, "partition_no" to ScalarValueType.NUMBER, "label" to ScalarValueType.TEXT))
        fun install(catalog: ExecutableCatalog, id: String, outputs: Map<String, MemberSourceDefinition.Output>) {
            val input = members(catalog, id, MemberShape.RAW_BAG, emptyList(), outputs, outputs.keys.associateWith { it })
            definitions.members["$id@1"] = ((input.root as ExecutableInputRoot.Members).input as ExecutableMemberInput.Projection).memberSource
        }
        install(transactions, "transaction_rows", linkedMapOf(
            "order_id" to property("交易", "order", ScalarValueType.NUMBER, "transactions.order_id"),
            "amount" to property("金额", "amount", ScalarValueType.NUMBER, "transactions.amount", contract = floatingMoney)))
        install(attributes, "attribute_rows", linkedMapOf(
            "order_id" to property("交易", "order", ScalarValueType.NUMBER, "attributes.order_id"),
            "tag" to property("条件", "tag", ScalarValueType.TEXT, "attributes.tag"),
            "partition_no" to property("分区", "partition", ScalarValueType.NUMBER, "attributes.partition_no"),
            "label" to property("文本身份", "label", ScalarValueType.TEXT, "attributes.label")))
        return definitions
    }

    private fun joinedTransactionBag(operations: List<PostOperationContract.ValidatedOperation>): QueryComputation.Scope {
        val transactions = memberScope("transaction_rows", listOf("order_id", "amount"), emptyList())
        val attributes = memberScope("attribute_rows", listOf("order_id", "tag", "partition_no", "label"), emptyList())
        val join = PostOperationContract.JoinInput("attributes", "attribute", "LEFT", ResultExpression.ResultOperation(ResultExpression.ResultOperator.EQ,
            listOf(ResultExpression.ResultSymbol("order_id"), ResultExpression.ResultSymbol("attribute__order_id"))))
        return QueryComputation.Scope(transactions.root, mapOf("attributes" to QueryComputation.Input("RELATION", attributes)), listOf(join) + operations)
    }

    private fun compile(catalog: ExecutableCatalog, computation: QueryComputation.Scope): CompiledQuery =
        OlapSemanticCompiler(CalciteRelCompiler(TestConnectors.registry)).compile(catalog,
            ExecutableQueryRequest(ExecutableQueryRequest.FORMAT_VERSION, "transaction_bag", catalog.manifest.catalogId, computation), OlapQueryContext("transaction_bag"), observer)

    @Test fun sharesConditionalAmountsAndDenominatorsWithoutEvaluatingUnselectedOverflowOrDeduplicatingBags(@TempDir directory: Path) {
        transactionBags(directory).use { warehouse ->
            val catalog = transactionBagDefinitions(warehouse).catalog()
            val workingMoney = MetricValueContract(floatingMoney.unit, floatingMoney.arithmetic, floatingMoney.result, mapOf(
                "scale" to MetricValueContract.PreserveUnit(MetricValueContract.BinaryOperator.MULTIPLY, MetricValueContract.Operand.LEFT),
                "divide" to MetricValueContract.PreserveUnit(MetricValueContract.BinaryOperator.DIVIDE, MetricValueContract.Operand.LEFT)), floatingMoney.overflow)
            val number = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), floatingMoney.arithmetic, floatingMoney.result, emptyMap(), floatingMoney.overflow)
            val ratio = MetricValueContract(number.unit, number.arithmetic, number.result, mapOf("ratio" to MetricValueContract.SameUnitRatio()), number.overflow)
            fun ref(name: String) = ResultExpression.ResultSymbol(name)
            fun operation(operator: ResultExpression.ResultOperator, vararg operands: ResultExpression) = ResultExpression.ResultOperation(operator, operands.toList())
            fun tagged(tag: String) = operation(ResultExpression.ResultOperator.EQ, ref("attribute__tag"), ResultExpression.ResultTypedLiteral(ScalarValueType.TEXT, tag))
            fun selected(condition: ResultExpression, value: ResultExpression) = ResultExpression.ResultCaseWhen(listOf(ResultExpression.ResultCaseBranch(condition, value)), ResultExpression.ResultLiteral(null))
            fun derive(name: String, value: ResultExpression) = PostOperationContract.Derive(value, name, name)
            fun quantity(value: String) = ResultExpression.ResultQuantityLiteral(BigDecimal(value), number)
            fun computation(depth: Int, overflowTag: String = "never"): QueryComputation.Scope {
                val stages = mutableListOf<PostOperationContract.ValidatedOperation>(
                    derive("x_amount", selected(tagged("x"), ref("amount"))), derive("y_amount", selected(tagged("y"), ref("amount"))),
                    derive("missing_amount", selected(operation(ResultExpression.ResultOperator.IS_NULL, ref("attribute__order_id")), ref("amount"))),
                    derive("matched_amount", selected(operation(ResultExpression.ResultOperator.IS_NOT_NULL, ref("attribute__order_id")), ref("amount"))),
                    derive("guarded_amount", selected(tagged(overflowTag), ResultExpression.ResultNumeric(operation(ResultExpression.ResultOperator.MULTIPLY,
                        ref("amount"), quantity("1E308")), workingMoney, "scale"))),
                    regroup(emptyList(), listOf(reduction("amount", ResultAggregation.Sum(workingMoney, ResultAggregation.NullPolicy.IGNORE), "total"), reduction("x_amount", ResultAggregation.Sum(workingMoney, ResultAggregation.NullPolicy.IGNORE), "x"),
                        reduction("y_amount", ResultAggregation.Sum(workingMoney, ResultAggregation.NullPolicy.IGNORE), "y"), reduction("missing_amount", ResultAggregation.Sum(workingMoney, ResultAggregation.NullPolicy.IGNORE), "missing"),
                        reduction("matched_amount", ResultAggregation.Sum(workingMoney, ResultAggregation.NullPolicy.IGNORE), "matched"), reduction("guarded_amount", ResultAggregation.Sum(workingMoney, ResultAggregation.NullPolicy.IGNORE), "guarded"),
                        reduction("order_id", ResultAggregation.Count(false, ResultAggregation.NullPolicy.IGNORE), "occurrences"), reduction("amount", ResultAggregation.Count(false, ResultAggregation.NullPolicy.IGNORE), "known_amounts"))))
                var previous = "total"
                repeat(depth) { index ->
                    val (operator, operand, equation) = when (index % 4) {
                        0 -> Triple(ResultExpression.ResultOperator.ADD, ref("x"), null)
                        1 -> Triple(ResultExpression.ResultOperator.SUBTRACT, ref("y"), null)
                        2 -> Triple(ResultExpression.ResultOperator.DIVIDE, quantity("2"), "divide")
                        else -> Triple(ResultExpression.ResultOperator.ADD, ref("missing"), null)
                    }
                    val next = "amount_${index + 1}"
                    stages.add(derive(next, ResultExpression.ResultNumeric(operation(operator, ref(previous), operand), workingMoney, equation)))
                    previous = next
                }
                listOf("x" to "total", "y" to "total", "missing" to "total", "x" to "matched", "y" to "matched", "matched" to "matched").forEachIndexed { index, (value, denominator) ->
                    stages.add(derive("ratio_$index", ResultExpression.ResultNumeric(operation(ResultExpression.ResultOperator.SAFE_DIVIDE, ref(value), ref(denominator)), ratio, "ratio")))
                }
                stages.add(PostOperationContract.Project(listOf("total", "x", "y", "missing", "matched", "guarded", "occurrences", "known_amounts", previous) + (0..5).map { "ratio_$it" }))
                return joinedTransactionBag(stages)
            }
            warehouse.finishSetup()
            val bag = warehouse.execute(requireNotNull(compile(catalog, joinedTransactionBag(listOf(PostOperationContract.Project(listOf("order_id", "amount", "attribute__partition_no"))))).executableLoweringResult))
                .map { row -> Triple((row[0] as Number).toInt(), (row[1] as? Number)?.toDouble(), (row[2] as? Number)?.toInt()) }.groupingBy { it }.eachCount()
            assertEquals(listOf(Triple(1, 100.0, 1), Triple(1, 100.0, 2), Triple(2, 50.0, 1), Triple(3, 30.0, null), Triple(4, null, 1), Triple(5, 20.0, 1)).groupingBy { it }.eachCount(), bag)
            val scales = listOf(4 to 245.0, 8 to 217.5).map { (depth, expected) ->
                val compiled = compile(catalog, computation(depth))
                val row = warehouse.execute(requireNotNull(compiled.executableLoweringResult)).single()
                listOf(300.0, 200.0, 70.0, 30.0, 270.0).forEachIndexed { index, value -> assertEquals(value, (row[index] as Number).toDouble(), 1E-12) }
                assertNull(row[5]); assertEquals(6L, (row[6] as Number).toLong()); assertEquals(5L, (row[7] as Number).toLong()); assertEquals(expected, (row[8] as Number).toDouble(), 1E-12)
                listOf(2.0 / 3, 7.0 / 30, 1.0 / 10, 20.0 / 27, 7.0 / 27, 1.0).forEachIndexed { index, value -> assertEquals(value, (row[index + 9] as Number).toDouble(), 1E-12) }
                requireNotNull(compiled.compilationScale)
            }
            org.slf4j.LoggerFactory.getLogger(javaClass).info("条件金额SQL规模：4层={} bytes/{} checks，8层={} bytes/{} checks", scales[0].sqlBytes, scales[0].sqlNumericChecks, scales[1].sqlBytes, scales[1].sqlNumericChecks)
            assertTrue(scales[1].sqlBytes < scales[0].sqlBytes * 3L, "加倍金额计算深度的SQL应受控增长：${scales.map { it.sqlBytes }}")
            val failure = assertThrows(com.olap.prototype.connector.SourceExecutionException::class.java) { warehouse.execute(requireNotNull(compile(catalog, computation(4, "x")).executableLoweringResult)) }
            assertEquals(com.olap.prototype.warehouse.contract.SourceFailureCategory.SQL, failure.failure.category)
        }
    }

    @Test fun concatenatesCompletedTextBeforeDistinctCountingWithoutChangingEncodedIdentities(@TempDir directory: Path) {
        transactionBags(directory).use { warehouse ->
            val catalog = transactionBagDefinitions(warehouse).catalog()
            val concat = Derive(ResultCall(ScalarFunction.CONCAT, listOf(symbol("attribute__label"), ResultTypedLiteral(ScalarValueType.TEXT, ""))), "identity_text", "完成关系文本身份")
            warehouse.finishSetup()
            val values = warehouse.execute(requireNotNull(compile(catalog, joinedTransactionBag(listOf(concat, PostOperationContract.Project(listOf("identity_text"))))).executableLoweringResult)).map { it.single() }
            assertEquals(listOf("A", "a", "A ", "é", "e\u0301", null).groupingBy { it }.eachCount(), values.groupingBy { it }.eachCount())
            val counted = compile(catalog, joinedTransactionBag(listOf(concat, regroup(emptyList(), listOf(aggregate("attribute__label", ResultAggregation.Count(true, ResultAggregation.NullPolicy.IGNORE), "original"),
                aggregate("identity_text", ResultAggregation.Count(true, ResultAggregation.NullPolicy.IGNORE), "concatenated"), aggregate("identity_text", ResultAggregation.Count(false, ResultAggregation.NullPolicy.IGNORE), "known_texts"), aggregate("order_id", ResultAggregation.Count(false, ResultAggregation.NullPolicy.IGNORE), "occurrences"))))))
            assertEquals(listOf(5L, 5L, 5L, 6L), warehouse.execute(requireNotNull(counted.executableLoweringResult)).single().map { (it as Number).toLong() })
            val exact = PostFilter(ResultOperation(ResultOperator.EQ, listOf(symbol("identity_text"),
                ResultTypedLiteral(ScalarValueType.TEXT, "A"))))
            val selected = compile(catalog, joinedTransactionBag(listOf(concat, exact, regroup(emptyList(), listOf(aggregate("order_id", ResultAggregation.Count(false, ResultAggregation.NullPolicy.IGNORE), "identities"))))))
            assertEquals(1L, (warehouse.execute(requireNotNull(selected.executableLoweringResult)).single().single() as Number).toLong())
        }
    }
    @Test fun executesUnidentifiedBagsAndReusesComputedQuantitiesWithoutMakingThemAdditive(@TempDir directory: Path) {
        NativeWarehouse(directory, "duckdb", listOf("CREATE TABLE observations(customer INTEGER, amount DECIMAL(18,2))", "INSERT INTO observations VALUES (1,20),(1,20),(2,NULL),(3,10)")).use { warehouse ->
            val definitions = Definitions(catalog(warehouse, "observations", mapOf("observations.customer" to "customer", "observations.amount" to "amount"), emptyList()))
            definitions.events["observations@1"] = definitions.events.getValue("observations@1").copy(identity = EventIdentity(emptyList(), emptyMap()))
            declareUnits(definitions, "observations", mapOf("customer" to ScalarValueType.NUMBER, "amount" to ScalarValueType.NUMBER))
            val rawOutputs = mapOf("customer" to property("客户", "customer", ScalarValueType.NUMBER, "observations.customer"), "amount" to property("金额", "amount", ScalarValueType.NUMBER, "observations.amount", contract = money))
            installProjection(definitions, "observations", MemberShape.RAW_BAG, emptyList(), rawOutputs)
            val mean = observationMean(); val count = recordCount(); val identity = (money.unit as MetricValueContract.Fixed).identity
            val ratio = MetricValueContract(mean.unit, mean.arithmetic, mean.result, mapOf("division" to MetricValueContract.FixedBinary(MetricValueContract.BinaryOperator.DIVIDE, identity, MetricValueContract.ONE, identity)), mean.overflow)
            val formal = Calculation.MemberSlot("rows", "事实记录", "observations", MemberShape.RAW_BAG, mapOf("customer" to Calculation.MemberProperty("customer", ScalarValueType.NUMBER, null), "amount" to Calculation.MemberProperty("amount", ScalarValueType.NUMBER, money)), MemberRef("observations", 1))
            val body = Calculation(emptyList(), listOf(formal), emptyList(), emptyList(), emptyMap(), Calculation.Scope(
                Calculation.Members("observed_rows", "rows", listOf(Calculation.MemberProjection("customer", "customer"), Calculation.MemberProjection("amount", "amount")), null, emptyMap(), MemberCondition.Unrestricted()), emptyMap(), listOf(
                    regroup(listOf("customer"), listOf(reduction("amount", ResultAggregation.Sum(money, ResultAggregation.NullPolicy.IGNORE), "total"), reduction("amount", ResultAggregation.Average(mean, ResultAggregation.NullPolicy.IGNORE, MetricValueContract.Rounding.NONE), "average"), reduction("amount", ResultAggregation.Count(false, ResultAggregation.NullPolicy.IGNORE), "observations"), reduction("amount", ResultAggregation.Count(true, ResultAggregation.NullPolicy.IGNORE), "distinct_amounts"))),
                    Derive(ResultNumeric(ResultOperation(ResultOperator.SAFE_DIVIDE, listOf(symbol("total"), symbol("observations"))), ratio, "division"), "ratio", "每次观测金额"))))
            val result = MemberResult("客户", "customers", MemberShape.UNIQUE_MEMBERS, listOf("customer"), mapOf(
                "customer" to computedProperty("客户", "customer", "customer", null), "total" to computedProperty("客户金额", "amount", "total", money),
                "average" to computedProperty("客户均额", "mean_amount", "average", mean), "observations" to computedProperty("有效观测次数", "count", "observations", count),
                "distinct_amounts" to computedProperty("金额种类", "distinct_count", "distinct_amounts", count), "ratio" to computedProperty("每次观测金额", "ratio", "ratio", mean)), emptyMap(), null, emptyMap(), null)
            definitions.members["customers@1"] = MemberSource.Computed("customers", 1, MemberSourceDefinition.Computed(body, result), CalculationBindings(emptyMap(), mapOf("rows" to CalculationBindings.SourceCall(MemberRef("observations", 1), emptyMap(), mapOf("customer" to "customer", "amount" to "amount"), emptyMap(), emptyMap())), emptyMap(), emptyMap(), emptyMap()), emptyMap())
            val totalInstance = installMemberReduction(definitions, "customer_total", result, "total", ResultAggregation.Sum(money, ResultAggregation.NullPolicy.IGNORE), money)
            val invalid = listOf("average", "ratio", "distinct_amounts").associateWith { field -> installMemberReduction(definitions, "invalid_$field", result, field, ResultAggregation.Sum(requireNotNull(MemberSourceDefinition.rootValueContract(result.outputs.getValue(field))), ResultAggregation.NullPolicy.IGNORE), requireNotNull(MemberSourceDefinition.rootValueContract(result.outputs.getValue(field)))) }
            warehouse.finishSetup(); val engine = engine(warehouse)
            assertEquals(listOf(listOf(decimal("1")), listOf(decimal("1")), listOf(decimal("2")), listOf(decimal("3"))), numericRows(execute(engine, definitions, memberScope("observations", listOf("customer"), emptyList()))))
            assertEquals(listOf(listOf(decimal("1"), decimal("2")), listOf(decimal("2"), decimal("1")), listOf(decimal("3"), decimal("1"))), numericRows(execute(engine, definitions,
                memberScope("observations", listOf("customer"), listOf(regroup(listOf("customer"), listOf(reduction("customer", ResultAggregation.Count(false, ResultAggregation.NullPolicy.IGNORE), "occurrences"))))))))
            assertEquals(listOf(listOf(decimal("1"), decimal("40"), decimal("20"), decimal("2"), decimal("1"), decimal("20")), listOf(decimal("2"), null, null, decimal("0"), decimal("0"), null),
                listOf(decimal("3"), decimal("10"), decimal("10"), decimal("1"), decimal("1"), decimal("10"))), numericRows(execute(engine, definitions,
                memberScope("customers", listOf("customer", "total", "average", "observations", "distinct_amounts", "ratio"), emptyList()))))
            val local = CalculationBindings.LocalRef("customers", identityOutputs(result))
            val shared = measures(listOf(QueryComputation.Invocation("left", totalInstance, "left_total", emptyMap(), mapOf("members" to local), emptyMap(), DimensionCondition.Unrestricted()), QueryComputation.Invocation("right", totalInstance, "right_total", emptyMap(), mapOf("members" to local), emptyMap(), DimensionCondition.Unrestricted())),
                mapOf("customers" to QueryComputation.Input("ROOT", memberScope("customers", result.outputs.keys.toList(), emptyList()))))
            assertEquals(listOf(listOf(decimal("50"), decimal("50"))), numericRows(execute(engine, definitions, shared)))
            val overall = numericRows(execute(engine, definitions, memberScope("observations", listOf("amount"), listOf(regroup(emptyList(),
                listOf(reduction("amount", ResultAggregation.Average(mean, ResultAggregation.NullPolicy.IGNORE, MetricValueContract.Rounding.NONE), "overall_average"), reduction("amount", ResultAggregation.Count(true, ResultAggregation.NullPolicy.IGNORE), "overall_distinct")))))))
            assertEquals(1, overall.size); assertEquals(50.0 / 3.0, requireNotNull(overall.single()[0]).toDouble(), 1E-9); assertEquals(decimal("2"), overall.single()[1])
            invalid.forEach { (field, instance) ->
                val source = CalculationBindings.SourceCall(MemberRef("customers", 1), emptyMap(), identityOutputs(result), emptyMap(), emptyMap())
                assertEquals("ANALYZE_STAGES", assertThrows(EngineExecutionException::class.java) { execute(engine, definitions,
                    measures(listOf(QueryComputation.Invocation("invalid", instance, "invalid_total", emptyMap(), mapOf("members" to source), emptyMap(), DimensionCondition.Unrestricted())), emptyMap())) }.stage, field)
            }
            assertEquals(listOf(listOf(decimal("50"), decimal("50"))), numericRows(execute(engine, definitions, shared)))
        }
    }
    @Test fun requiresLocalObjectProjectionsToRetainIdentityOrExplicitlyRegroupCustomers(@TempDir directory: Path) {
        orders(directory, "duckdb").use { warehouse ->
            val definitions = Definitions(catalog(warehouse, "orders", orderUnits(), emptyList()))
            declareUnits(definitions, "orders", mapOf("id" to ScalarValueType.NUMBER, "customer" to ScalarValueType.NUMBER, "region" to ScalarValueType.TEXT, "hour" to ScalarValueType.NUMBER, "amount" to ScalarValueType.NUMBER))
            installProjection(definitions, "orders", MemberShape.RECORDS, listOf("id"), orderOutputs())
            val result = MemberResult("客户", "customers", MemberShape.UNIQUE_MEMBERS, listOf("customer"), mapOf("customer" to computedProperty("客户", "customer", "customer", null)), emptyMap(), null, emptyMap(), null)
            val instance = installMemberReduction(definitions, "customer_count", result, "customer", ResultAggregation.Count(false, ResultAggregation.NullPolicy.IGNORE), recordCount())
            warehouse.finishSetup(); val engine = engine(warehouse)
            val local = CalculationBindings.LocalRef("customers", mapOf("customer" to "customer"))
            val calls = listOf(QueryComputation.Invocation("first", instance, "first_count", emptyMap(), mapOf("members" to local), emptyMap(), DimensionCondition.Unrestricted()), QueryComputation.Invocation("second", instance, "second_count", emptyMap(), mapOf("members" to local), emptyMap(), DimensionCondition.Unrestricted()))
            val projected = memberScope("orders", listOf("customer"), emptyList())
            assertEquals(listOf(listOf(decimal("1")), listOf(decimal("1")), listOf(decimal("2")), listOf(decimal("3"))), numericRows(execute(engine, definitions, projected)))
            assertEquals("ANALYZE_STAGES", assertThrows(EngineExecutionException::class.java) { execute(engine, definitions, measures(calls, mapOf("customers" to QueryComputation.Input("ROOT", projected)))) }.stage)
            val customers = memberScope("orders", listOf("id", "customer"), listOf(regroup(listOf("customer"), listOf(reduction("id", ResultAggregation.Count(false, ResultAggregation.NullPolicy.IGNORE), "orders"))), Project(listOf("customer"))))
            val explicit = measures(calls, mapOf("customers" to QueryComputation.Input("ROOT", customers)))
            repeat(2) { assertEquals(listOf(listOf(decimal("3"), decimal("3"))), numericRows(execute(engine, definitions, explicit))) }
        }
    }
    @Test fun combinesCompletedRecordBagsWithNullsUsingDistinctAndAllSetSemantics(@TempDir directory: Path) {
        NativeWarehouse(directory, "duckdb", listOf("CREATE TABLE orders(id INTEGER PRIMARY KEY,customer INTEGER,region VARCHAR,event_hour INTEGER,amount DECIMAL(18,2))",
            "INSERT INTO orders VALUES (1,NULL,'A',1,0),(2,NULL,'A',1,0),(3,1,'A',1,0),(4,1,'A',1,0),(5,2,'A',1,0),(6,NULL,'B',2,0),(7,1,'B',2,0),(8,3,'B',2,0)")).use { warehouse ->
            val catalog = catalog(warehouse, "orders", orderUnits(), emptyList()); warehouse.finishSetup()
            for (kind in Calculation.SetKind.entries) for (all in listOf(false, true)) {
                val inputs = listOf("left", "right").associateWith { side ->
                    val records = members(catalog, "records", MemberShape.RECORDS, listOf("id"), orderOutputs(), mapOf("customer" to "customer", "hour" to "hour"))
                    stages(records, listOf(PostFilter(ResultOperation(ResultOperator.EQ, listOf(symbol("hour"),
                        ResultTypedLiteral(ScalarValueType.NUMBER, BigDecimal(if (side == "left") "1" else "2"))))), Project(listOf("customer"))))
                }
                val root = ExecutableInputScope("ROOT", ExecutableInputRoot.SetInputs(kind, all, listOf(Calculation.SetProjection("left", listOf("customer")), Calculation.SetProjection("right", listOf("customer"))),
                    listOf(SetOutputContract("customer", SetOutputContract.Property("customer", ScalarValueType.NUMBER)))), emptyList(), inputs, emptyList())
                val counts = warehouse.execute(compile(catalog, root)).map { it.first()?.toString() ?: "NULL" }.groupingBy { it }.eachCount()
                val expected = when (kind) {
                    Calculation.SetKind.UNION -> if (all) mapOf("NULL" to 3, "1" to 3, "2" to 1, "3" to 1) else mapOf("NULL" to 1, "1" to 1, "2" to 1, "3" to 1)
                    Calculation.SetKind.INTERSECT -> mapOf("NULL" to 1, "1" to 1)
                    Calculation.SetKind.EXCEPT -> if (all) mapOf("NULL" to 1, "1" to 1, "2" to 1) else mapOf("2" to 1)
                }
                assertEquals(expected, counts, "$kind all=$all")
            }
        }
    }
    @Test fun appliesNumericFailureAndSafeDivisionThroughCompletedMemberResults(@TempDir directory: Path) {
        for (operator in listOf("OVERFLOW", "DIVIDE", "SAFE_DIVIDE")) NativeWarehouse(directory, "duckdb", listOf("CREATE TABLE numbers(id INTEGER PRIMARY KEY,n DOUBLE)",
            if (operator == "OVERFLOW") "INSERT INTO numbers VALUES (1,1E308)" else "INSERT INTO numbers VALUES (1,0),(2,NULL),(3,2)")).use { warehouse ->
            val catalog = catalog(warehouse, "numbers", mapOf("numbers.id" to "id", "numbers.n" to "n"), emptyList()); warehouse.finishSetup()
            val arithmetic = MetricValueContract.Approximate("-1E308", "1E308", "1E-320", "1E-12")
            val plain = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), arithmetic, MetricValueContract.BinaryFloat(64), emptyMap(), MetricValueContract.Overflow.FAIL)
            val outputs = mapOf("id" to property("记录", "record", ScalarValueType.NUMBER, "numbers.id"), "n" to property("数值", "number", ScalarValueType.NUMBER, "numbers.n", contract = plain))
            val product = MetricValueContract(plain.unit, arithmetic, plain.result, mapOf("operation" to MetricValueContract.FixedBinary(MetricValueContract.BinaryOperator.MULTIPLY, MetricValueContract.ONE, MetricValueContract.ONE, MetricValueContract.ONE)), plain.overflow)
            val ratio = MetricValueContract(plain.unit, arithmetic, plain.result, mapOf("operation" to MetricValueContract.SameUnitRatio()), plain.overflow)
            val doubled = ResultNumeric(ResultOperation(ResultOperator.MULTIPLY, listOf(symbol("n"), ResultLiteral(BigDecimal("2")))), product, "operation")
            val root = members(catalog, "numbers", MemberShape.RECORDS, listOf("id"), outputs, mapOf("n" to "n"))
            val computed = stages(root, listOf(Derive(ResultNumeric(ResultOperation(ResultOperator.valueOf(if (operator == "OVERFLOW") "DIVIDE" else operator), listOf(ResultLiteral(BigDecimal("1")), if (operator == "OVERFLOW") doubled else symbol("n"))), ratio, "operation"), "result", "倒数"), Project(listOf("result"))))
            val compiled = compile(catalog, computed)
            if (operator != "SAFE_DIVIDE") { val failure = assertThrows(RuntimeException::class.java) { warehouse.execute(compiled) }; assertInstanceOf(java.sql.SQLException::class.java, failure.cause, failure.toString()) }
            else { val rows = warehouse.execute(compiled); assertEquals(3, rows.size); assertEquals(2, rows.count { it.first() == null }); assertEquals(0.5, (rows.mapNotNull { it.first() }.single() as Number).toDouble()) }
        }
    }
    @Test fun keepsMemberQuantityBindingsInsideTheirBooleanUnitScope() {
        val usd = MetricValueContract.UnitIdentity("iso4217", "USD", 1)
        val cny = MetricValueContract.UnitIdentity("iso4217", "CNY", 1)
        fun text(value: String) = Calculation.Scalar(ScalarValueType.TEXT, value)
        val axis = MetricValueContract.UnitAxis("currency", 1, "currency", ScalarValueType.TEXT,
            listOf(MetricValueContract.UnitMember(text("USD"), usd), MetricValueContract.UnitMember(text("CNY"), cny)))
        val sourceAmount = MetricValueContract(MetricValueContract.SlotDimension("currency", axis), MetricValueContract.Exact(),
            MetricValueContract.Decimal(38, 2, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
        val minimumAmount = MetricValueContract(MetricValueContract.Fixed(usd), sourceAmount.arithmetic, sourceAmount.result, emptyMap(), sourceAmount.overflow)
        val arguments = mapOf("minimum" to Calculation.Scalar(ScalarValueType.NUMBER, "5"))
        val contracts = mapOf("currency" to null, "amount" to sourceAmount)
        val operators = mapOf("currency" to listOf(MemberCondition.Operator.EQ), "amount" to listOf(MemberCondition.Operator.GTE, MemberCondition.Operator.IS_NOT_NULL))
        fun currency(value: String) = MemberCondition.Predicate("currency", MemberCondition.Operator.EQ, listOf(text(value)))
        val minimum = MemberCondition.Predicate("amount", MemberCondition.Operator.GTE, listOf(Calculation.Argument("minimum")))
        val known = MemberCondition.Predicate("amount", MemberCondition.Operator.IS_NOT_NULL, emptyList())
        fun bind(original: MemberCondition) = MemberCondition.bind(original, operators,
            { if (it == "currency") ScalarValueType.TEXT else ScalarValueType.NUMBER }, { it },
            { CalculationInstantiation.resolve(it, CalculationBindings.empty(), arguments) }, linkedSetOf())
        fun parameters(original: MemberCondition, bound: MemberCondition) = MemberCondition.requireParameterUnits(original, bound,
            contracts::get, { it }, contracts::get, { if (it == "minimum") minimumAmount else null }, emptyMap())

        val accepted = listOf(
            MemberCondition.All(listOf(currency("USD"), MemberCondition.All(listOf(known, minimum)))),
            MemberCondition.All(listOf(currency("USD"), MemberCondition.Not(MemberCondition.All(listOf(currency("CNY"), minimum)))))
        )
        accepted.forEach { original ->
            val bound = bind(original)
            parameters(original, bound)
            MemberCondition.requireQuantityUnits(bound, contracts)
        }
        val rejected = listOf(
            MemberCondition.Any(listOf(currency("USD"), minimum)),
            MemberCondition.Not(MemberCondition.All(listOf(currency("USD"), minimum))),
            MemberCondition.All(listOf(currency("USD"), currency("CNY"), currency("USD"), minimum))
        )
        rejected.forEach { original ->
            val bound = bind(original)
            assertThrows(IllegalArgumentException::class.java) { parameters(original, bound) }
            assertThrows(IllegalArgumentException::class.java) { MemberCondition.requireQuantityUnits(bound, contracts) }
        }
        val wrongCurrency = MemberCondition.All(listOf(currency("CNY"), minimum))
        assertThrows(IllegalArgumentException::class.java) { parameters(wrongCurrency, bind(wrongCurrency)) }
    }

    @Test fun preservesCurrencyPerTupleElementAndRejectsMixedUnitTotalsAndUnknownMembers(@TempDir directory: Path) {
        val axis = MetricValueContract.UnitAxis("currency", 1, "currency", ScalarValueType.TEXT, listOf(
            MetricValueContract.UnitMember(Calculation.Scalar(ScalarValueType.TEXT, "USD"), MetricValueContract.UnitIdentity("iso4217", "USD", 1)),
            MetricValueContract.UnitMember(Calculation.Scalar(ScalarValueType.TEXT, "CNY"), MetricValueContract.UnitIdentity("iso4217", "CNY", 1))))
        val contract = MetricValueContract(MetricValueContract.SlotDimension("currency", axis), MetricValueContract.Exact(), MetricValueContract.Decimal(38, 2, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
        for (unknown in listOf(false, true)) NativeWarehouse(directory, "duckdb", listOf("CREATE TABLE payments(id INTEGER PRIMARY KEY,currency VARCHAR(3) NOT NULL,amount DECIMAL(18,2))",
            if (unknown) "INSERT INTO payments VALUES (1,'EUR',7)" else "INSERT INTO payments VALUES (1,'USD',10),(2,'USD',10),(3,'CNY',70),(4,'USD',NULL)")).use { warehouse ->
            val definitions = Definitions(catalog(warehouse, "payments", mapOf("payments.id" to "id", "payments.currency" to "currency", "payments.amount" to "amount"), emptyList()))
            definitions.bindings["currency@1"] = ExecutableDimensionBinding("currency", "1", "currency", "1", "payments", "1", "payments", "1", "payments.currency", "1",
                SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "payments.currency", 1), local, emptyMap(), emptyList(), "payments", "1")
            val catalog = definitions.catalog(); warehouse.finishSetup()
            val outputs = mapOf("id" to property("记录", "record", ScalarValueType.NUMBER, "payments.id"),
                "currency" to MemberSourceDefinition.DimensionOutput("币种", "currency", ScalarValueType.TEXT, "currency", 1, "currency", 1, null),
                "amount" to property("金额", "amount", ScalarValueType.NUMBER, "payments.amount", contract = contract))
            val projection = linkedMapOf("id" to "id", "currency" to "currency", "amount" to "amount")
            val original = members(catalog, "currency_records", MemberShape.RECORDS, listOf("id"), outputs, projection)
            val collection = stages(original, listOf(Derive(ResultRow(listOf(symbol("currency"), symbol("amount"))), "observation", "带币种金额"),
                Regroup(ResultGrouping(emptyList(), listOf(aggregate("observation", ResultAggregation.Collect(false), "observations")), null))))
            val compiled = compile(catalog, collection)
            if (unknown) assertThrows(IllegalStateException::class.java) { warehouse.business(compiled) }
            else {
                val business = warehouse.business(compiled)
                val tuples = (business.rows.single().single() as ExecutableBusinessResult.ArrayValue).value
                assertEquals(4, tuples.size)
                var repeated = 0; var yuan = false; var absent = false
                tuples.forEach { tuple ->
                    val row = (tuple as ExecutableBusinessResult.RowValue).value
                    val currency = (row[0] as ExecutableBusinessResult.ScalarValue).value
                    if (row[1] is ExecutableBusinessResult.NullValue) absent = absent || currency == "USD"
                    else {
                        val amount = BigDecimal((row[1] as ExecutableBusinessResult.ScalarValue).value)
                        if (currency == "USD" && amount.compareTo(BigDecimal("10")) == 0) repeated++
                        yuan = yuan || currency == "CNY" && amount.compareTo(BigDecimal("70")) == 0
                    }
                }
                assertEquals(2, repeated); assertTrue(yuan); assertTrue(absent)
                System.getProperty("olap.test.currencyResultExportDirectory")?.let { directory ->
                    com.olap.prototype.util.OlapJsonUtils.mapper().writeValue(Path.of(directory).resolve("currency-result.json").toFile(), business)
                }
                for (aggregation in listOf("SUM", "STDDEV_SAMP")) {
                    val output = if (aggregation == "SUM") contract else MetricValueContract(contract.unit, MetricValueContract.Approximate("0", "10000", "1E-9", "1E-12"), MetricValueContract.BinaryFloat(64), emptyMap(), MetricValueContract.Overflow.FAIL)
                    val rule = if (aggregation == "SUM") ResultAggregation.Sum(output, ResultAggregation.NullPolicy.IGNORE)
                        else ResultAggregation.SampleDeviation(output, ResultAggregation.NullPolicy.IGNORE)
                    val total = aggregate("amount", rule, "total")
                    val invalid = stages(original, listOf(Regroup(ResultGrouping(emptyList(), listOf(total), null))))
                    assertThrows(IllegalArgumentException::class.java, { compile(catalog, invalid) }, aggregation)
                }
            }
        }
    }
}
