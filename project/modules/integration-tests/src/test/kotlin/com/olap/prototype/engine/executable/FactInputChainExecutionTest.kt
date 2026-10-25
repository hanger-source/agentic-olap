package com.olap.prototype.engine.executable

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.calculation.*
import com.olap.prototype.codec.SemanticProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.connector.SourceProvider
import com.olap.prototype.connector.SourceSqlRequest
import com.olap.prototype.engine.catalog.*
import com.olap.prototype.engine.context.OlapQueryContext
import com.olap.prototype.event.*
import com.olap.prototype.expression.*
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.metric.*
import com.olap.prototype.query.*
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.DeadlineExecutionControl
import com.olap.prototype.warehouse.contract.JdbcConnectionSpec
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.SQLException

class FactInputChainExecutionTest {
    private val unrestricted = DimensionCondition.Unrestricted()
    private fun ref(id: String) = SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, id, 1)
    private fun dimensionRef(name: String) = ScopedInputContract.DimensionRef("analysis_$name", "1", "value")
    private fun quantity(arithmetic: MetricValueContract.Arithmetic, result: MetricValueContract.Result, unit: MetricValueContract.UnitIdentity) =
        MetricValueContract(MetricValueContract.Fixed(unit), arithmetic, result, emptyMap(), MetricValueContract.Overflow.FAIL)
    private fun money() = quantity(MetricValueContract.Exact(), MetricValueContract.Decimal(30, 2, MetricValueContract.Rounding.NONE), MetricValueContract.UnitIdentity("trial", "money", 1))
    private fun records() = quantity(MetricValueContract.Exact(), MetricValueContract.Integer(64), MetricValueContract.ONE)
    private fun approximateMoney() = quantity(MetricValueContract.Approximate("-1000000000000000000", "1000000000000000000", "0.000000001", "0.000000000001"),
        MetricValueContract.BinaryFloat(64), MetricValueContract.UnitIdentity("trial", "money", 1))
    private data class Store(val spec: JdbcConnectionSpec, val catalog: ExecutableCatalog, val event: ExecutableEvent, val topology: ExecutableTopology)
    private fun store(directory: Path, inserts: List<String>): Store {
        NativeWarehouse(directory, "duckdb", listOf(
            "CREATE TABLE orders(id INTEGER PRIMARY KEY, amount DECIMAL(18,2), hour INTEGER, region VARCHAR, bucket VARCHAR, customer INTEGER, observed_at TIMESTAMP)",
            "CREATE TABLE actions(id INTEGER PRIMARY KEY, order_id INTEGER, qualifying BOOLEAN)") + inserts).use { source ->
            val orders = source.adopt("orders", listOf("id", "amount", "hour", "region", "bucket", "customer", "observed_at").associate { "orders.$it" to it })
            val actions = source.adopt("actions", listOf("id", "order_id", "qualifying").associate { "actions.$it" to it })
            val topology = ExecutableTopology("orders_topology", "1", "orders", null, "orders", "1", joinPathRefs = listOf(ExecutableJoinPathRef("order_actions", "1")))
            val event = ExecutableEvent("orders_event", "1", "orders_topology", "1", null,
                EventIdentity(listOf(EventIdentity.LogicUnitRef("orders.id", 1)), mapOf("customer" to listOf(EventIdentity.LogicUnitRef("orders.customer", 1)))),
                emptyMap(), null, "orders.observed_at", "1")
            val join = ExecutableJoinPath("order_actions", "1", "orders_topology", "1", "orders", "1", "actions", "1", "LEFT", "ONE_TO_MANY", null,
                "SQL_EQUALS_NULL_NEVER_MATCHES", listOf(ExecutableJoinKey(1, "orders.id", "1", "actions.order_id", "1")))
            return Store(source.spec, ExecutableCatalog(ExecutableCatalogManifest("facts", ExecutableCatalogManifest.SCHEMA_VERSION),
                physicalExecutionBindings = mapOf("orders@1" to orders, "actions@1" to actions), events = mapOf("orders_event@1" to event),
                topologies = mapOf("orders_topology@1" to topology), joinPaths = mapOf("order_actions@1" to join)), event, topology)
        }
    }
    private fun dimension(store: Store, column: String, filterOnly: Boolean): ExecutableBoundAnalysisDimension {
        val type = if (column == "hour") ScalarValueType.NUMBER else ScalarValueType.TEXT
        val binding = ExecutableDimensionBinding("binding_$column", "1", "dimension_$column", "1", "orders_event", "1", "orders_topology", "1",
            "orders.$column", "1", ref("orders.$column"), RelationAccessPath(emptyList()), emptyMap(), emptyList(), "orders", "1")
        return ExecutableBoundAnalysisDimension(ExecutableAnalysisDimension("analysis_$column", "1", null, "analysis_$column", column, "value"),
            column, "value", store.event, ExecutableDimension("dimension_$column", "1", null, "trial", null, null), binding,
            ExecutableLogicUnit("orders.$column", "1", type, null, null, null, column, null, "orders", "1"), type, emptyList(),
            store.catalog.physicalExecutionBindings.getValue("orders@1"), filterOnly, "alignment_$column", "PROVEN_SAME_KEY")
    }
    private fun metric(store: Store, id: String, column: String, aggregation: MetricAggregation, contract: MetricValueContract,
        predicate: SemanticExpression?, snapshot: MetricSnapshotRule?, dimensions: List<ExecutableBoundAnalysisDimension>): ExecutableMetricInput {
        val metric = ExecutableMetric(MetricRef(id, "1"), MetricDefinition.FactMeasure(EventRef("orders_event", 1), ref("orders.$column"), aggregation,
            false, predicate, emptyMap(), snapshot, contract, if (aggregation == MetricAggregation.AVG) MeanTarget.SOURCE_NATIVE else null))
        val invocation = ExecutableMetricCall.Invocation(QueryStageAnalysis.ScopeId.root(), id, CalculationOwner.Metric(MetricInstanceRef(id)), "value", "value", metric,
            emptyMap(), CalculationBindings.empty(), null, emptyMap(), emptyMap())
        val selection = ExecutableMeasureSelection(dimensions.filterNot { it.filterOnly }.associate { it.outputAlias to dimensionRef(it.outputAlias) }, emptyMap(), null, null, emptyMap())
        return ExecutableMetricInput(invocation, selection, store.event, store.topology, null, null, dimensions, unrestricted, emptyMap(), emptyList())
    }
    private fun modified(input: ExecutableMetricInput, invocation: ExecutableMetricCall.Invocation = input.invocation,
        selection: ExecutableMeasureSelection = input.selection, filter: DimensionCondition = input.filterExpression,
        restrictions: List<ExecutableObjectRestriction> = input.objectRestrictions) = ExecutableMetricInput(invocation, selection, input.event, input.topology,
        input.timeAnchor, input.rangeDimension, input.analysisDimensions, filter, input.relationConditions, restrictions)
    private fun definition(input: ExecutableMetricInput, definition: MetricDefinition) = modified(input,
        invocation = input.invocation.copy(metric = input.metric.copy(definition = definition)))
    private fun measures(metrics: List<ExecutableMetricInput>, levels: GroupingLevels.Bound? = null): ExecutableInputScope {
        val selection = ExecutableMeasureSelection(metrics.first().selection.grouping, emptyMap(), levels, null, emptyMap())
        val calls = metrics.map { input ->
            val alias = if (metrics.size > 1) input.callId else input.outputAlias
            ExecutableMetricCall.Fact(modified(input, invocation = input.invocation.copy(displayName = alias, outputAlias = alias), selection = selection))
        }
        return ExecutableInputScope("ROOT", ExecutableInputRoot.Measures(calls, selection, RowPopulationContract.calls(metrics.map { it.callId }), unrestricted), emptyList(), emptyMap(), emptyList())
    }
    private fun compile(store: Store, scope: ExecutableInputScope): ExecutableLoweringResult {
        val graph = ExecutableInputGraph.index(scope)
        return CalciteRelCompiler(TestConnectors.registry).compile(graph, ExecutableStageAnalysis.analyze(graph, TestConnectors.registry),
            ExecutableSourceResolver().resolve(store.catalog, graph), OlapQueryContext("core-native-chain")).lowering
    }
    private fun execute(store: Store, scope: ExecutableInputScope) = execute(store, compile(store, scope))
    private fun execute(store: Store, compiled: ExecutableLoweringResult): List<List<Any?>> = DeadlineExecutionControl(120_000).use { control ->
        SourceProvider(store.spec.source(), TestConnectors.registry).execute(SourceSqlRequest("execute-query", compiled.sql, compiled.parameters,
            compiled.aliasMapping, compiled.aliasDisplayNames, compiled.outputContract.columns.associate { it.symbol.name to it.type }, compiled.sourceRequirements,
            compiled.relationChecks, null), control, RuntimeDeploymentLimits.result, RuntimeDeploymentLimits.nativeMemoryBytes) { _, _ -> }.frame.rows.map { row -> row.cells.map { it.value } }
    }
    private fun scannedOrderRows(store: Store, compiled: ExecutableLoweringResult): Long = TestConnectors.open("duckdb", store.spec.jdbcUrl).use { connection ->
        connection.prepareStatement("EXPLAIN (ANALYZE, FORMAT JSON) ${compiled.sql}").use { statement ->
            compiled.parameters.forEachIndexed { index, parameter -> parameter.bind(statement, index + 1) }
            statement.executeQuery().use { rows -> assertTrue(rows.next()); scannedOrderRows(OlapJsonUtils.mapper().readTree(rows.getString(2))) }
        }
    }
    private fun scannedOrderRows(node: JsonNode): Long {
        val count = if (node.path("operator_type").asText() == "TABLE_SCAN" && node.path("extra_info").path("Table").asText().endsWith("orders")) node.path("operator_rows_scanned").longValue() else 0L
        return count + node.sumOf { scannedOrderRows(it) }
    }
    private fun hour(operator: MemberCondition.Operator, value: String) = DimensionCondition.Predicate("hour_filter", dimensionRef("hour"), operator, listOf(Calculation.Scalar(ScalarValueType.NUMBER, value)))

    @Test fun executesNativeFloatingFactsAndKeepsTheirGuaranteeAfterDecimalConversion(@TempDir directory: Path) {
        val store = store(directory, listOf("ALTER TABLE orders ALTER COLUMN amount TYPE DOUBLE", "INSERT INTO orders(id,amount) VALUES (1,1.25),(2,2.75),(3,NULL)"))
        val native = quantity(MetricValueContract.NativeFloating(), MetricValueContract.BinaryFloat(64), MetricValueContract.ONE)
        val original = metric(store, "total", "amount", MetricAggregation.SUM, native, null, null, emptyList())
        val total = definition(original, SemanticProto.fromProto(ProtoBytes.decode(ProtoBytes.encode(SemanticProto.toProto(original.metric.definition)),
            com.olap.prototype.wire.semantic.MetricDefinition.getDefaultInstance())))
        val average = metric(store, "average", "amount", MetricAggregation.AVG, native, null, null, emptyList())
        val count = metric(store, "count", "amount", MetricAggregation.COUNT, records(), null, null, emptyList())
        val compiled = compile(store, measures(listOf(total, average, count)))
        val values = execute(store, compiled).single()
        assertEquals(4.0, (values[0] as Number).toDouble()); assertEquals(2.0, (values[1] as Number).toDouble()); assertEquals(2L, (values[2] as Number).toLong())
        for (arithmetic in listOf(MetricValueContract.NativeFloating(), MetricValueContract.Exact(), MetricValueContract.Approximate("-100", "100", "0.01", "0.01"))) {
            val root = measures(listOf(metric(store, "converted_total", "amount", MetricAggregation.SUM, native, null, null, emptyList())))
            val output = quantity(arithmetic, MetricValueContract.Decimal(18, 2, MetricValueContract.Rounding.HALF_EVEN), MetricValueContract.ONE)
            val numeric = ResultExpression.ResultNumeric(ResultExpression.ResultCast(ResultExpression.ResultSymbol("value"),
                SemanticExpression.ValueType.DECIMAL, 18, 2, MetricValueContract.Rounding.HALF_EVEN), output, null)
            val converted = ExecutableInputScope(root.inputMode, root.root, root.objectRestrictions, root.additionalInputs,
                listOf(PostOperationContract.Derive(numeric, "converted", "converted")))
            if (arithmetic is MetricValueContract.NativeFloating) assertEquals(0, BigDecimal("4.00").compareTo(execute(store, converted).single().last() as BigDecimal))
            else assertThrows(IllegalArgumentException::class.java) { compile(store, converted) }
        }
        TestConnectors.open("duckdb", store.spec.jdbcUrl).use { it.createStatement().use { statement -> statement.execute("UPDATE orders SET amount=NULL") } }
        val unknown = execute(store, compiled).single(); assertNull(unknown[0]); assertNull(unknown[1]); assertEquals(0L, (unknown[2] as Number).toLong())
        TestConnectors.open("duckdb", store.spec.jdbcUrl).use { it.createStatement().use { statement -> statement.execute("UPDATE orders SET amount=1E308 WHERE id IN (1,2)") } }
        assertThrows(RuntimeException::class.java) { execute(store, compiled) }
    }
    @Test fun quantifiesRelatedRowsWithoutMultiplyingOrValueDeduplicatingOrders(@TempDir directory: Path) {
        val store = store(directory, listOf("INSERT INTO orders(id,amount) VALUES (1,100),(2,100),(3,70)", "INSERT INTO actions VALUES (1,1,TRUE),(2,1,TRUE),(3,2,TRUE),(4,3,FALSE)"))
        val path = RelationAccessPath(listOf(RelationAccessPath.JoinRef("order_actions", 1)))
        val exists = SemanticExpression.Exists("eligible_action", SemanticExpression.LocalParent(), path,
            SemanticExpression.Operation(SemanticExpression.Operator.EQ, listOf(ref("actions.qualifying"), SemanticExpression.Literal(true))))
        val eligible = metric(store, "eligible_amount", "amount", MetricAggregation.SUM, money(), exists, null, emptyList())
        assertEquals(0, BigDecimal("200").compareTo(execute(store, measures(listOf(eligible))).single().single() as BigDecimal))
        TestConnectors.open("duckdb", store.spec.jdbcUrl).use { connection -> connection.createStatement().use { statement ->
            statement.executeQuery("SELECT SUM(o.amount),SUM(DISTINCT o.amount) FROM orders o JOIN actions a ON o.id=a.order_id WHERE a.qualifying").use { wrong ->
                assertTrue(wrong.next()); assertEquals(0, BigDecimal("300").compareTo(wrong.getBigDecimal(1))); assertEquals(0, BigDecimal("100").compareTo(wrong.getBigDecimal(2)))
            }
        } }
    }
    @Test fun sharesAcrossIndependentScopesWhilePreservingBagsNullGroupsAndEmptyCounts(@TempDir directory: Path) {
        val store = store(directory, listOf("INSERT INTO orders(id,amount,hour,region,customer) VALUES (1,10,1,'A',1),(2,20,1,'A',2),(3,30,2,'B',1),(4,NULL,3,'C',2),(5,40,9,'D',3)"))
        val dimensions = listOf(dimension(store, "region", false), dimension(store, "hour", true))
        for (all in listOf(true, false)) {
            val left = modified(metric(store, "left_amount", "amount", MetricAggregation.SUM, money(), null, null, dimensions), filter = hour(MemberCondition.Operator.LT, "2"))
            val right = modified(metric(store, "right_amount", "amount", MetricAggregation.SUM, money(), null, null, dimensions),
                filter = DimensionCondition.All(listOf(hour(MemberCondition.Operator.GTE, "1"), hour(MemberCondition.Operator.LT, "4"))))
            val scope = ExecutableInputScope("ROOT", ExecutableInputRoot.SetInputs(Calculation.SetKind.UNION, all,
                listOf(Calculation.SetProjection("left", listOf("region", "value")), Calculation.SetProjection("right", listOf("region", "value"))),
                listOf(SetOutputContract("region", SetOutputContract.Dimension(dimensionRef("region"))), SetOutputContract("amount", SetOutputContract.Quantity(money())))),
                emptyList(), mapOf("left" to measures(listOf(left)), "right" to measures(listOf(right))), emptyList())
            val compiled = compile(store, scope); val rows = execute(store, compiled)
            assertEquals(if (all) 4 else 3, rows.size, "rows=$rows; SQL=${compiled.sql}")
            assertEquals(if (all) 2 else 1, rows.count { it[0] == "A" && (it[1] as BigDecimal).compareTo(BigDecimal("30")) == 0 })
            assertTrue(rows.any { it[0] == "B" && (it[1] as BigDecimal).compareTo(BigDecimal("30")) == 0 })
            assertTrue(rows.any { it[0] == "C" && it[1] == null }); assertFalse(rows.any { it[0] == "D" })
            assertEquals(5L, scannedOrderRows(store, compiled))
        }
        val inputs = linkedMapOf<String, ExecutableInputScope>(); val projections = mutableListOf<Calculation.SetProjection>()
        listOf(0, 1, 2, 3, 4, 5, 6, 9).forEach { value ->
            val id = "hour_$value"
            val count = modified(metric(store, id, "id", MetricAggregation.COUNT, records(), null, null, listOf(dimension(store, "hour", true))),
                filter = hour(MemberCondition.Operator.EQ, value.toString()))
            inputs[id] = measures(listOf(count)); projections += Calculation.SetProjection(id, listOf("value"))
        }
        val union = ExecutableInputScope("ROOT", ExecutableInputRoot.SetInputs(Calculation.SetKind.UNION, true, projections,
            listOf(SetOutputContract("count", SetOutputContract.Quantity(records())))), emptyList(), inputs, emptyList())
        val compiled = compile(store, union)
        assertEquals(listOf(0L, 0L, 0L, 0L, 1L, 1L, 1L, 2L), execute(store, compiled).map { (it.single() as Number).toLong() }.sorted())
        assertEquals(5L, scannedOrderRows(store, compiled))
        val amount = metric(store, "amount", "amount", MetricAggregation.SUM, money(), null, null, emptyList())
        val populations = linkedMapOf<String, ExecutableInputScope>()
        val qualified = listOf(1, 2).map { customer ->
            val id = "customers_$customer"
            val selected = modified(metric(store, id, "amount", MetricAggregation.SUM, money(), null, null,
                listOf(dimension(store, "customer", false).copy(valueType = ScalarValueType.NUMBER))), filter = DimensionCondition.Predicate("selected_customer",
                dimensionRef("customer"), MemberCondition.Operator.EQ, listOf(Calculation.Scalar(ScalarValueType.NUMBER, customer.toString()))))
            populations[id] = measures(listOf(selected))
            modified(amount, invocation = amount.invocation.copy(callId = "customer_$customer"), restrictions = listOf(ExecutableObjectRestriction(
                QueryStageAnalysis.ScopeId.root().child(id), listOf("customer"), Calculation.EventRole("customer"), emptyList(), emptyMap())))
        }
        val selected = measures(qualified)
        val totals = execute(store, ExecutableInputScope(selected.inputMode, selected.root, selected.objectRestrictions, populations, selected.postOps))
        assertEquals(1, totals.size); assertEquals(2, totals.single().size)
        assertEquals(0, BigDecimal("40").compareTo(totals.single()[0] as BigDecimal)); assertEquals(0, BigDecimal("20").compareTo(totals.single()[1] as BigDecimal))
    }
    @Test fun recomputesMeansAndDistinctPeopleAtEachGroupingLevel(@TempDir directory: Path) {
        val inserts = mutableListOf("INSERT INTO orders(id,amount,region,bucket,customer) VALUES (1,0,'A','first',1),(11,10,NULL,'null-member',2)")
        (2..10).forEach { inserts += "INSERT INTO orders(id,amount,region,bucket,customer) VALUES ($it,100,'A','second',1)" }
        val store = store(directory, inserts)
        val dimensions = listOf(dimension(store, "region", false), dimension(store, "bucket", false))
        val levels = GroupingLevels("level", listOf(GroupingLevels.Level("leaf", listOf("region", "bucket")), GroupingLevels.Level("region", listOf("region")), GroupingLevels.Level("total", emptyList()))).bind(listOf("region", "bucket"))
        val mean = metric(store, "mean", "amount", MetricAggregation.AVG, approximateMoney(), null, null, dimensions)
        val people = definition(metric(store, "people", "customer", MetricAggregation.COUNT, records(), null, null, dimensions),
            MetricDefinition.FactMeasure(EventRef("orders_event", 1), ref("orders.customer"), MetricAggregation.COUNT, true, null, emptyMap(), null, records()))
        val rows = execute(store, measures(listOf(mean, people), levels)); assertEquals(6, rows.size)
        val region = rows.single { it[0] == "A" && (it[2] as Number).toLong() == 1L }
        assertEquals(90.0, (region[3] as Number).toDouble()); assertEquals(1L, (region[4] as Number).toLong())
        val nullRegion = rows.single { it[0] == null && (it[2] as Number).toLong() == 1L }; assertEquals(10.0, (nullRegion[3] as Number).toDouble())
        val total = rows.single { (it[2] as Number).toLong() == 3L }
        assertEquals(910.0 / 11.0, (total[3] as Number).toDouble(), 1E-12); assertEquals(2L, (total[4] as Number).toLong())
    }
    @Test fun reducesObservedMeansInsideEntitiesBeforeTheFinalSum(@TempDir directory: Path) {
        val inserts = mutableListOf("INSERT INTO orders(id,amount,customer,observed_at) VALUES (1,0,1,TIMESTAMP '2026-01-01'),(11,10,2,TIMESTAMP '2026-01-01')")
        (2..10).forEach { inserts += "INSERT INTO orders(id,amount,customer,observed_at) VALUES ($it,100,1,TIMESTAMP '2026-01-01')" }
        val store = store(directory, inserts)
        val snapshot = MetricSnapshotRule(MetricSnapshotRule.Reduction.MEAN, "customer", listOf(MetricSnapshotRule.LogicUnitKey("orders.customer", 1)),
            MetricSnapshotRule.LogicUnitKey("orders.observed_at", 1), listOf(MetricSnapshotRule.LogicUnitKey("orders.id", 1)), MetricSnapshotRule.NullPolicy.IGNORE, approximateMoney(), MeanTarget.SOURCE_NATIVE)
        val amount = metric(store, "entity_mean_sum", "amount", MetricAggregation.SUM, approximateMoney(), null, snapshot, emptyList())
        assertEquals(100.0, (execute(store, measures(listOf(amount))).single().single() as Number).toDouble())
    }
    @Test fun rejectsMaskedSourceOverflowAndEvaluatesOnlyParticipatingFactRows(@TempDir directory: Path) {
        val store = store(directory, listOf("INSERT INTO orders(id,amount) VALUES (1,1),(2,2),(3,NULL)"))
        val contract = quantity(MetricValueContract.Approximate("-1E308", "1E308", "1E-320", "1E-12"), MetricValueContract.BinaryFloat(64), MetricValueContract.ONE)
        val large = SemanticExpression.Cast(SemanticExpression.Literal("1E308"), SemanticExpression.ValueType.DOUBLE, null, null, MetricValueContract.Rounding.NONE)
        val amount = SemanticExpression.Cast(ref("orders.amount"), SemanticExpression.ValueType.DOUBLE, null, null, MetricValueContract.Rounding.NONE)
        val formula = SemanticExpression.Operation(SemanticExpression.Operator.DIVIDE, listOf(SemanticExpression.Literal(BigDecimal.ONE),
            SemanticExpression.Operation(SemanticExpression.Operator.MULTIPLY, listOf(large, amount))))
        fun input(id: String, predicate: SemanticExpression?): ExecutableMetricInput = definition(metric(store, id, "amount", MetricAggregation.SUM, contract, predicate, null, emptyList()),
            MetricDefinition.FactMeasure(EventRef("orders_event", 1), formula, MetricAggregation.SUM, false, predicate, emptyMap(), null, contract))
        val invalid = compile(store, measures(listOf(input("all", null))))
        assertInstanceOf(SQLException::class.java, assertThrows(RuntimeException::class.java) { execute(store, invalid) }.cause)
        for (consumers in listOf(1, 2)) {
            val predicate = SemanticExpression.Operation(SemanticExpression.Operator.EQ, listOf(ref("orders.amount"), SemanticExpression.Literal(BigDecimal.ONE)))
            val rows = execute(store, measures((0 until consumers).map { input("selected_$it", predicate) }))
            assertEquals(1, rows.size); rows.single().forEach { assertEquals(1E-308, (it as Number).toDouble(), 1E-320) }
        }
    }
}
