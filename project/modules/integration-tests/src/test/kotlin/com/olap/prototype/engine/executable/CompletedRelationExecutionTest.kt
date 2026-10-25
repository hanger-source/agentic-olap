package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.*
import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.engine.catalog.*
import com.olap.prototype.engine.compiler.CompiledQuery
import com.olap.prototype.engine.compiler.OlapSemanticCompiler
import com.olap.prototype.engine.context.OlapQueryContext
import com.olap.prototype.event.*
import com.olap.prototype.expression.*
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.member.MemberCondition
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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.math.sqrt

class CompletedRelationExecutionTest {
    private val unrestricted = DimensionCondition.Unrestricted()
    private val money = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.UnitIdentity("trial", "money", 1)),
        MetricValueContract.Exact(), MetricValueContract.Decimal(38, 2, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
    private val code = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(),
        MetricValueContract.Integer(64), emptyMap(), MetricValueContract.Overflow.FAIL)
    private fun warehouse(directory: Path, dialect: String, rows: String) = NativeWarehouse(directory, dialect, listOf(
        "CREATE TABLE orders(id INTEGER PRIMARY KEY, region VARCHAR, event_hour INTEGER, customer INTEGER, amount DECIMAL(18,2))",
        "INSERT INTO orders VALUES $rows"))
    private fun dimensionRef(name: String) = ScopedInputContract.DimensionRef(name, "1", "value")
    private fun catalog(source: NativeWarehouse): ExecutableCatalog {
        val names = linkedMapOf("id" to "id", "region" to "region", "hour" to "event_hour", "customer" to "customer", "amount" to "amount")
        val physical = source.adopt("orders", names.mapKeys { "orders.${it.key}" })
        val topology = ExecutableTopology("orders", "1", "orders", null, "orders", "1")
        val event = ExecutableEvent("orders", "1", "orders", "1", null,
            EventIdentity(listOf(EventIdentity.LogicUnitRef("orders.id", 1)), emptyMap()), emptyMap(), null, null, null)
        val dimensions = listOf("region", "hour", "customer")
        val bindings = dimensions.associate { id -> "$id@1" to ExecutableDimensionBinding(id, "1", id, "1", "orders", "1", "orders", "1",
            "orders.$id", "1", SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "orders.$id", 1), RelationAccessPath(emptyList()), emptyMap(), emptyList(), "orders", "1") }
        return ExecutableCatalog(ExecutableCatalogManifest("completed_relation", ExecutableCatalogManifest.SCHEMA_VERSION),
            metrics = mapOf(MetricRef("amount", "1").key() to ExecutableMetric(MetricRef("amount", "1"), MetricDefinition.FactMeasure(EventRef("orders", 1),
                SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "orders.amount", 1), MetricAggregation.SUM, false, null, emptyMap(), null, money, MetricValueContract.Rounding.NONE))),
            physicalExecutionBindings = mapOf("orders@1" to physical), topologies = mapOf("orders@1" to topology), events = mapOf("orders@1" to event),
            logicUnits = names.keys.associate { id -> "orders.$id@1" to ExecutableLogicUnit("orders.$id", "1", if (id == "region") ScalarValueType.TEXT else ScalarValueType.NUMBER,
                null, null, null, id, null, "orders", "1") }, dimensionBindings = bindings,
            dimensions = dimensions.associate { "$it@1" to ExecutableDimension(it, "1", null, "trial", null, null) },
            analysisDimensions = dimensions.associate { id -> "$id@1" to ExecutableAnalysisDimension(id, "1", null, id, id, "value",
                listOf(ExecutableAnalysisDimensionLevel("value", id, null)), listOf(ExecutableAnalysisDimensionEventMapping("${id}_mapping", id, "1", "value", "orders", "1", id, "1", id, "1", "orders.$id", "1", "same_$id", "PROVEN_SAME_KEY"))) })
    }
    private fun measures(catalog: ExecutableCatalog, instance: String, columns: List<String>): ExecutableInputScope {
        val event = catalog.events.getValue("orders@1"); val topology = catalog.topologies.getValue("orders@1")
        val physical = catalog.physicalExecutionBindings.getValue("orders@1")
        val dimensions = columns.map { id ->
            val binding = catalog.dimensionBindings.getValue("$id@1")
            ExecutableBoundAnalysisDimension(catalog.analysisDimensions.getValue("$id@1"), id, "value", event,
                catalog.dimensions.getValue("$id@1"), binding, catalog.logicUnits.getValue("orders.$id@1"),
                if (id == "region") ScalarValueType.TEXT else ScalarValueType.NUMBER, emptyList(), physical, false, "same_$id", "PROVEN_SAME_KEY")
        }
        val metric = catalog.metrics.getValue(MetricRef("amount", "1").key())
        val invocation = ExecutableMetricCall.Invocation(QueryStageAnalysis.ScopeId.root(), instance, CalculationOwner.Metric(MetricInstanceRef(instance)),
            "value", "value", metric, emptyMap(), CalculationBindings.empty(), null, emptyMap(), emptyMap())
        val selection = ExecutableMeasureSelection(columns.associateWith(::dimensionRef), emptyMap(), null, null, emptyMap())
        val input = ExecutableMetricInput(invocation, selection, event, topology, null, null, dimensions, unrestricted, emptyMap(), emptyList())
        return ExecutableInputScope("ROOT", ExecutableInputRoot.Measures(listOf(ExecutableMetricCall.Fact(input)), selection,
            RowPopulationContract.calls(listOf(instance)), unrestricted), emptyList(), emptyMap(), emptyList())
    }
    private fun stages(input: ExecutableInputScope, operations: List<PostOperationContract.ValidatedOperation>) =
        ExecutableInputScope(input.inputMode, input.root, input.objectRestrictions, input.additionalInputs, operations)
    private fun compile(catalog: ExecutableCatalog, input: ExecutableInputScope): ExecutableLoweringResult {
        val graph = ExecutableInputGraph.index(input)
        return CalciteRelCompiler(TestConnectors.registry).compile(graph, ExecutableStageAnalysis.analyze(graph, TestConnectors.registry), ExecutableSourceResolver().resolve(catalog, graph), OlapQueryContext("completed_relation")).lowering
    }
    private fun symbol(name: String): ResultExpression = ResultSymbol(name)
    private fun comparison(operator: String, left: ResultExpression, right: ResultExpression): ResultExpression = ResultOperation(ResultOperator.valueOf(operator), listOf(left, right))
    private fun quantityLiteral(value: String): ResultExpression = ResultQuantityLiteral(BigDecimal(value), money)
    private fun numeric(expression: ResultExpression, contract: MetricValueContract): ResultExpression = ResultNumeric(expression, contract, null)
    private fun order(symbol: String, direction: String) = OrderKey(symbol, direction, "LAST")
    private fun join(input: String, occurrence: String, type: String, condition: ResultExpression) = JoinInput(input, occurrence, type, condition)

    @Test fun bindsCustomerGroupingAndRejectsAnotherNumericDimensionOrRole(@TempDir directory: Path) {
        warehouse(directory, "duckdb", "(1,'A',1,1,10),(2,'B',2,1,10),(3,'A',3,2,NULL),(4,'B',4,3,7)").use { warehouse ->
            val base = catalog(warehouse)
            val calculation = MetricCalculation(Calculation(listOf(Calculation.DimensionSlot("customer", "customer", 1, "customer")), emptyList(), emptyList(), emptyList(), emptyMap(), Calculation.Scope(Calculation.Measures(listOf(Calculation.Call("amount", Calculation.Fixed(MetricRef("amount", "1")), "amount", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()))),
                listOf("customer"), emptyList(), null, null, emptyMap(), Calculation.Population(listOf("amount")), null), emptyMap(), emptyList())), "amount", listOf("customer"), money)
            val metric = ExecutableMetric(MetricRef("by_customer", "1"), calculation)
            fun adopted(binding: CalculationBindings.Dimension) = ExecutableCatalog(base.manifest, base.metrics + (metric.reference.key() to metric),
                mapOf("by_customer" to MetricInstance(metric.reference, CalculationBindings(mapOf("customer" to binding), emptyMap(), emptyMap(), emptyMap(), emptyMap()), emptyMap(), "by_customer@1")),
                analysisDimensions = base.analysisDimensions, events = base.events, topologies = base.topologies, dimensions = base.dimensions,
                dimensionBindings = base.dimensionBindings, logicUnits = base.logicUnits, physicalExecutionBindings = base.physicalExecutionBindings)
            val request = ExecutableQueryRequest(ExecutableQueryRequest.FORMAT_VERSION, "customer_grouping", base.manifest.catalogId,
                QueryComputation.Scope(QueryComputation.Measures(listOf(QueryComputation.Invocation("customers", MetricInstanceRef("by_customer"), "amount", emptyMap(), emptyMap(), emptyMap(), DimensionCondition.Unrestricted())),
                    listOf(dimensionRef("customer")), null, emptyMap(), unrestricted, unrestricted, emptyList(), RowPopulationContract.calls(listOf("customers")), null), emptyMap(), emptyList()))
            val observer = object : ExecutableExecutionObserver {
                override fun bindingCompleted(input: ExecutableInputScope) {}
                override fun stageAnalysisCompleted(analyses: List<QueryStageAnalysis.Analysis>) {}
                override fun sourceResolutionCompleted(sources: List<CompiledQuery.SourceEvidence>) {}
                override fun loweringCompleted(loweringResult: ExecutableLoweringResult) {}
            }
            val compiler = OlapSemanticCompiler(CalciteRelCompiler(TestConnectors.registry))
            warehouse.finishSetup()
            val valid = adopted(CalculationBindings.Dimension(dimensionRef("customer"), "customer", listOf("customer_mapping")))
            val result = warehouse.execute(requireNotNull(compiler.compile(valid, request, OlapQueryContext(request.requestId), observer).executableLoweringResult))
                .map { listOf((it[0] as Number).toInt(), it[1]?.let { value -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString() }) }.sortedBy { it[0] as Int }
            assertEquals(listOf(listOf(1, "20"), listOf(2, null), listOf(3, "7")), result)
            listOf(CalculationBindings.Dimension(dimensionRef("hour"), "hour", listOf("hour_mapping")),
                CalculationBindings.Dimension(dimensionRef("customer"), "hour", listOf("customer_mapping"))).forEach { binding ->
                assertEquals("BIND_INPUT", assertThrows(EngineExecutionException::class.java) {
                    compiler.compile(adopted(binding), request, OlapQueryContext(request.requestId), observer)
                }.stage)
            }
        }
    }
    @Test fun measuresSampleDispersionOverObservationBagsPartitionsAndGrowingFrames(@TempDir directory: Path) {
        val deviation = MetricValueContract(money.unit, MetricValueContract.Approximate("0", "1E13", "1E-9", "1E-12"), MetricValueContract.BinaryFloat(64), emptyMap(), MetricValueContract.Overflow.FAIL)
        for (dialect in listOf("h2", "duckdb")) warehouse(directory, dialect,
            "(1,'A',1,1,0),(2,'A',2,2,2),(3,'A',3,3,2),(4,'A',4,4,NULL),(5,'B',1,5,1000000000001),(6,'B',2,6,1000000000002),(7,'B',3,7,1000000000003),(8,'C',1,8,7),(9,'C',2,9,7),(10,'D',1,10,9),(11,'D',2,11,NULL),(12,'E',1,12,NULL)").use { warehouse ->
            val catalog = catalog(warehouse); warehouse.finishSetup()
            val aggregation = ResultAggregation.SampleDeviation(deviation, ResultAggregation.NullPolicy.IGNORE)
            val partition = PartitionAggregate("value", listOf("region"), "partition_sd", "分区样本标准差", aggregation)
            val window = WindowAggregate("value", listOf("region"), "rolling_sd", "累计样本标准差", listOf(order("hour", "ASC")), aggregation,
                WindowFrameContract.Frame("ROWS", WindowFrameContract.Bound("UNBOUNDED_PRECEDING", null, null), WindowFrameContract.Bound("CURRENT_ROW", null, null)))
            val root = stages(measures(catalog, "observations", listOf("region", "hour")), listOf(partition, window,
                Sort(listOf(order("region", "ASC"), order("hour", "ASC"))), Project(listOf("region", "hour", "partition_sd", "rolling_sd"))))
            val rows = warehouse.execute(compile(catalog, root)); assertEquals(12, rows.size)
            val bagDeviation = sqrt(4.0 / 3.0)
            rows.forEach { row ->
                val group = row[0] as String; val hour = (row[1] as Number).toInt()
                if (group in setOf("D", "E")) { assertNull(row[2]); assertNull(row[3]) }
                else {
                    assertEquals(when (group) { "A" -> bagDeviation; "B" -> 1.0; else -> 0.0 }, (row[2] as Number).toDouble(), 1E-9, "$dialect/$group")
                    if (hour == 1) assertNull(row[3]) else assertEquals(when (group) { "A" -> if (hour == 2) sqrt(2.0) else bagDeviation; "B" -> if (hour == 2) sqrt(0.5) else 1.0; else -> 0.0 },
                        (row[3] as Number).toDouble(), 1E-9, "$dialect/$group/$hour")
                }
            }
            for (nullPolicy in listOf("IGNORE", "PROPAGATE")) {
                val aggregate = ResultAggregate("value", ResultAggregation.SampleDeviation(deviation, ResultAggregation.NullPolicy.valueOf(nullPolicy)), "sd", "组内样本标准差")
                val regroup = Regroup(ResultGrouping(listOf("region"), listOf(aggregate), null))
                val grouped = warehouse.execute(compile(catalog, stages(measures(catalog, "observations", listOf("region", "hour")), listOf(regroup))))
                assertEquals(5, grouped.size)
                grouped.forEach { row -> val group = row[0] as String
                    if (group in setOf("D", "E") || nullPolicy == "PROPAGATE" && group == "A") assertNull(row[1])
                    else assertEquals(when (group) { "A" -> bagDeviation; "B" -> 1.0; else -> 0.0 }, (row[1] as Number).toDouble(), 1E-9)
                }
                val empty = warehouse.execute(compile(catalog, stages(measures(catalog, "empty_observations", listOf("region", "hour")), listOf(
                    PostFilter(ResultLiteral(false)), Regroup(ResultGrouping(emptyList(), listOf(aggregate), null))))))
                assertEquals(1, empty.size); assertNull(empty.single().single())
            }
        }
    }
    private fun scope(input: ExecutableInputScope, mode: String? = input.inputMode,
        inputs: Map<String, ExecutableInputScope> = input.additionalInputs, operations: List<PostOperationContract.ValidatedOperation> = input.postOps) =
        ExecutableInputScope(mode, input.root, input.objectRestrictions, inputs, operations)
    private fun sum(input: String, output: String) = ResultAggregate(input, ResultAggregation.Sum(money, ResultAggregation.NullPolicy.IGNORE), output, output)
    @Test fun regroupsProjectedBagBeforeScalarAlignment(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) warehouse(directory, dialect, "(1,'A',1,1,10),(2,'A',2,2,10),(3,'B',1,3,7)").use { warehouse ->
            val catalog = catalog(warehouse); warehouse.finishSetup()
            val projected = Project(listOf("value"))
            val child = scope(measures(catalog, "cohort", listOf("region", "hour")), "KEY_ALIGNMENT", operations = listOf(projected))
            val root = scope(measures(catalog, "primary", listOf("region", "hour")), inputs = mapOf("cohort" to child))
            assertThrows(IllegalArgumentException::class.java) { compile(catalog, root) }
            val regroup = Regroup(ResultGrouping(emptyList(), listOf(sum("value", "total")), null))
            val rows = warehouse.execute(compile(catalog, scope(root, inputs = mapOf("cohort" to stages(child, listOf(projected, regroup))))))
            assertEquals(3, rows.size)
            rows.forEach { assertEquals(0, BigDecimal("27").compareTo(it[3] as BigDecimal)); assertEquals(true, it[4]) }
            assertEquals(2, rows.count { BigDecimal("10").compareTo(it[2] as BigDecimal) == 0 })
        }
    }
    @Test fun alignsProjectedManyToOneResultsWithoutChangingObservationAmounts(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) warehouse(directory, dialect, "(1,'A',1,1,10),(2,'A',2,2,20),(3,'B',1,3,7),(4,NULL,1,4,3)").use { warehouse ->
            val catalog = catalog(warehouse); warehouse.finishSetup()
            for (kind in listOf("INNER", "LEFT", "SCALAR")) {
                val right = scope(measures(catalog, "lookup", if (kind == "SCALAR") emptyList() else listOf("region", "hour")), "RELATION")
                val condition = if (kind == "SCALAR") ResultLiteral(true) else comparison("AND",
                    comparison("EQ", symbol("region"), symbol("matched__region")), comparison("AND",
                        comparison("EQ", symbol("matched__hour"), symbol("hour")), comparison("OR",
                            comparison("GT", symbol("matched__value"), quantityLiteral("0")), comparison("EQ", symbol("matched__value"), quantityLiteral("0")))))
                val projected = scope(measures(catalog, "observations", listOf("region", "hour")), "KEY_ALIGNMENT", mapOf("lookup" to right), listOf(
                    join("lookup", "matched", if (kind == "SCALAR") "INNER" else kind, condition), Project(listOf("region", "hour", "matched__value"))))
                val root = scope(measures(catalog, "primary", listOf("region", "hour")), inputs = mapOf("cohort" to projected), operations = listOf(
                    Project(listOf("region", "hour", "value", "cohort__matched__value", "cohort__present"))))
                val rows = warehouse.execute(compile(catalog, root)); assertEquals(4, rows.size, "$dialect/$kind")
                rows.forEach { row ->
                    val expected = if (row[0] == null) "3" else when ("${row[0]}/${(row[1] as Number).toInt()}") {
                        "A/1" -> "10"; "A/2" -> "20"; "B/1" -> "7"; else -> error("unexpected observation: $row")
                    }
                    assertEquals(0, BigDecimal(expected).compareTo(row[2] as BigDecimal))
                    if (kind == "SCALAR") { assertEquals(0, BigDecimal("40").compareTo(row[3] as BigDecimal)); assertEquals(true, row[4]) }
                    else if (row[0] == null) { assertNull(row[3]); if (kind == "LEFT") assertEquals(true, row[4]) else assertNull(row[4]) }
                    else { assertEquals(0, BigDecimal(expected).compareTo(row[3] as BigDecimal)); assertEquals(true, row[4]) }
                }
            }
        }
    }
    @Test fun alignsManyToOneCollectionsRegroupedFromANonLeadingKeyWithoutDuplicatingAmounts(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) warehouse(directory, dialect, "(1,'A',1,1,10),(2,'A',1,2,10),(3,'A',2,3,20),(4,'B',1,4,7),(5,NULL,1,5,3)").use { warehouse ->
            val catalog = catalog(warehouse); warehouse.finishSetup()
            val aggregate = ResultAggregate("native_row", ResultAggregation.Collect(false), "detail_rows", "原生明细bag")
            val details = scope(measures(catalog, "detail", listOf("customer", "region", "hour")), "RELATION", operations = listOf(
                Derive(ResultRow(listOf(symbol("hour"), symbol("value"))), "native_row", "小时与原金额"),
                Regroup(ResultGrouping(listOf("region"), listOf(aggregate), null))))
            val projected = scope(measures(catalog, "observations", listOf("region")), "KEY_ALIGNMENT", mapOf("details" to details), listOf(
                join("details", "matched", "LEFT", comparison("EQ", symbol("region"), symbol("matched__region"))),
                Project(listOf("region", "value", "matched__detail_rows"))))
            val root = scope(measures(catalog, "primary", listOf("region")), inputs = mapOf("cohort" to projected), operations = listOf(
                Project(listOf("region", "value", "cohort__value", "cohort__matched__detail_rows", "cohort__present"))))
            val rows = warehouse.execute(compile(catalog, root)); assertEquals(3, rows.size, dialect); assertEquals(1, rows.count { it[0] == null }, dialect)
            rows.forEach { row ->
                val region = row[0] as String?; val amount = when (region) { null -> "3"; "A" -> "40"; else -> "7" }
                assertEquals(0, BigDecimal(amount).compareTo(row[1] as BigDecimal), dialect); assertEquals(0, BigDecimal(amount).compareTo(row[2] as BigDecimal), dialect); assertEquals(true, row[4], dialect)
                if (region == null) assertNull(row[3], dialect)
                else {
                    val tuples = (row[3] as com.olap.prototype.api.SqlResultValues.ArrayValue).elements.map { value ->
                        val fields = (value as com.olap.prototype.api.SqlResultValues.RowValue).fields
                        "${(fields[0] as Number).toInt()}/${(fields[1] as BigDecimal).stripTrailingZeros().toPlainString()}"
                    }.sorted()
                    assertEquals(if (region == "A") listOf("1/10", "1/10", "2/20") else listOf("1/7"), tuples, dialect)
                }
            }
        }
    }
    @Test fun keepsOneToManyMatchesAsBagsAndRefusesToAlignTheirIncompleteCompositeKeys(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) warehouse(directory, dialect, "(1,'A',1,1,10),(2,'A',2,2,20),(3,'B',1,3,7),(4,NULL,1,4,3)").use { warehouse ->
            val catalog = catalog(warehouse); warehouse.finishSetup()
            val region = comparison("EQ", symbol("region"), symbol("matched__region"))
            val exact = comparison("AND", region, comparison("EQ", symbol("hour"), symbol("matched__hour")))
            for (condition in listOf(region, comparison("AND", region, region), comparison("OR", exact, region))) {
                val right = scope(measures(catalog, "lookup", listOf("region", "hour")), "RELATION")
                val bag = scope(measures(catalog, "observations", listOf("region", "hour")), inputs = mapOf("lookup" to right), operations = listOf(
                    join("lookup", "matched", "INNER", condition), Project(listOf("region", "hour", "matched__value"))))
                val rows = warehouse.execute(compile(catalog, bag)); assertEquals(5, rows.size, dialect)
                assertEquals(setOf("A/1/10", "A/1/20", "A/2/10", "A/2/20", "B/1/7"), rows.map {
                    "${it[0]}/${(it[1] as Number).toInt()}/${(it[2] as BigDecimal).stripTrailingZeros().toPlainString()}"
                }.toSet())
                val root = scope(measures(catalog, "primary", listOf("region", "hour")), inputs = mapOf("cohort" to scope(bag, "KEY_ALIGNMENT")))
                val failure = assertThrows(IllegalArgumentException::class.java) { compile(catalog, root) }
                assertTrue(requireNotNull(failure.message).contains("no proven final key"), failure.message)
            }
        }
    }
    @Test fun reusesCompletedRelationAtIndependentOccurrencesWithoutCollapsingBags(@TempDir directory: Path) {
        warehouse(directory, "duckdb", "(1,'A',1,1,10),(2,'A',1,2,20),(3,'B',1,3,7),(4,NULL,1,4,3)").use { warehouse ->
            val catalog = catalog(warehouse); warehouse.finishSetup()
            val relation = scope(measures(catalog, "detail", listOf("region", "customer")), "RELATION", operations = listOf(Project(listOf("region", "value"))))
            for (kind in listOf("INNER", "LEFT", "SEMI", "ANTI")) {
                val input = scope(measures(catalog, "primary", listOf("region")), inputs = mapOf("detail" to relation), operations = listOf(
                    join("detail", "matched", kind, comparison("EQ", symbol("region"), symbol("matched__region"))), Project(listOf("region", "value"))))
                val joined = warehouse.execute(compile(catalog, input))
                assertEquals(when (kind) { "INNER" -> 3; "LEFT" -> 4; "SEMI" -> 2; else -> 1 }, joined.size, kind)
                assertEquals(if (kind == "LEFT" || kind == "ANTI") 1 else 0, joined.count { it[0] == null }, kind)
            }
            val root = scope(measures(catalog, "primary", listOf("region")), inputs = mapOf("detail" to relation), operations = listOf(
                join("detail", "first", "INNER", comparison("EQ", symbol("region"), symbol("first__region"))),
                join("detail", "second", "INNER", comparison("EQ", symbol("region"), symbol("second__region"))),
                Project(listOf("region", "value", "first__value", "second__value"))))
            val rows = warehouse.execute(compile(catalog, root)); assertEquals(5, rows.size); assertEquals(4, rows.count { it[0] == "A" })
            assertEquals(setOf("10.00/10.00", "10.00/20.00", "20.00/10.00", "20.00/20.00"), rows.filter { it[0] == "A" }.map { "${it[2]}/${it[3]}" }.toSet())
            assertFalse(rows.any { it[0] == null })
        }
    }
    private fun populationExample(catalog: ExecutableCatalog, operations: List<PostOperationContract.ValidatedOperation>): ExecutableInputScope {
        val root = measures(catalog, "primary", listOf("region")); val fact = root.metrics.single()
        val filtered = ExecutableMetricInput(fact.invocation, fact.selection, fact.event, fact.topology, fact.timeAnchor, fact.rangeDimension, fact.analysisDimensions,
            DimensionCondition.Predicate("region_filter", dimensionRef("region"), MemberCondition.Operator.EQ, listOf(Calculation.Scalar(ScalarValueType.TEXT, "A"))), fact.relationConditions, fact.objectRestrictions)
        val child = scope(measures(catalog, "other", listOf("region")), "KEY_ALIGNMENT", operations = operations)
        return ExecutableInputScope(root.inputMode, ExecutableInputRoot.Measures(listOf(ExecutableMetricCall.Fact(filtered)), fact.selection,
            RowPopulationContract.Selection("SCOPED_UNION", listOf("primary"), listOf("other")), unrestricted), emptyList(), mapOf("other" to child), emptyList())
    }
    @Test fun takesPopulationFromCompletedInputsAndKeepsNullGroupsDistinctFromMissingGroups(@TempDir directory: Path) {
        warehouse(directory, "duckdb", "(1,'A',1,1,10),(2,'A',2,2,20),(3,'B',1,3,7),(4,NULL,1,4,NULL)").use { warehouse ->
            val catalog = catalog(warehouse); warehouse.finishSetup()
            val limited = populationExample(catalog, listOf(PostFilter(comparison("GT", symbol("value"), quantityLiteral("20")))))
            assertEquals(1, warehouse.execute(compile(catalog, limited)).size)
            val rows = warehouse.execute(compile(catalog, populationExample(catalog, emptyList()))); assertEquals(3, rows.size)
            val nullGroup = rows.single { it[0] == null }; assertNull(nullGroup[1]); assertNull(nullGroup[2]); assertEquals(true, nullGroup[3])
            val foreign = rows.single { it[0] == "B" }; assertNull(foreign[1]); assertEquals(0, BigDecimal("7").compareTo(foreign[2] as BigDecimal))
        }
    }
    @Test fun appliesWarmupWindowBeforeDisplayQualificationRankingAndStableSelection(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) warehouse(directory, dialect, "(1,'A',1,1,10),(2,'A',3,2,NULL),(3,'A',4,3,30),(4,'B',1,4,7),(5,'B',4,5,2)").use { warehouse ->
            val catalog = catalog(warehouse); warehouse.finishSetup()
            val root = stages(measures(catalog, "primary", listOf("region", "hour")), listOf(
                Cumulative("value", "hour", "ASC", listOf("region"), "cumulative", "累计金额", ResultAggregation.Sum(money, ResultAggregation.NullPolicy.IGNORE)),
                PostFilter(comparison("GTE", symbol("hour"), ResultTypedLiteral(ScalarValueType.NUMBER, BigDecimal("3")))),
                Rank("cumulative", "DESC", listOf("region"), "rank", "排名"),
                TopN(listOf(order("rank", "ASC"), order("region", "ASC"), order("hour", "DESC")), 2),
                Project(listOf("region", "hour", "cumulative", "rank"))))
            val rows = warehouse.execute(compile(catalog, root)); assertEquals(listOf("A", "B"), rows.map { it[0] })
            assertEquals(listOf(BigDecimal("40.00"), BigDecimal("9.00")), rows.map { it[2] })
            assertTrue(rows.all { (it[1] as Number).toInt() == 4 && (it[3] as Number).toLong() == 1L })
        }
    }

    @Test fun selectsEmptyAndLargeOrderedPopulationsWithoutChangingAmounts(@TempDir directory: Path) {
        val records = (1..10007).joinToString(",") { "($it,'A',$it,$it,$it)" }
        for (dialect in listOf("h2", "duckdb")) warehouse(directory, dialect, records).use { warehouse ->
            val catalog = catalog(warehouse); warehouse.finishSetup()
            val measured = measures(catalog, "population", listOf("hour"))
            val empty = warehouse.business(compile(catalog, stages(measured, listOf(TopN(listOf(order("value", "DESC")), 0)))))
            assertTrue(empty.rows.isEmpty(), dialect)
            assertEquals(listOf("hour", "value"), empty.columns.map { it.symbol.name })
            val selected = warehouse.execute(compile(catalog, stages(measured, listOf(TopN(listOf(order("value", "DESC")), 10003)))))
            assertEquals(10003, selected.size, dialect)
            assertEquals((10007 downTo 5).toList(), selected.map { (it[0] as Number).toInt() }, dialect)
            selected.forEach { row -> assertEquals(0, BigDecimal(row[0].toString()).compareTo(BigDecimal(row[1].toString())), dialect) }
        }
    }

    @Test fun preservesAlternativeQuantitiesAndAppliesOnlyExplicitOutputQuantization(@TempDir directory: Path) {
        val small = MetricValueContract(money.unit, MetricValueContract.Exact(), MetricValueContract.Decimal(6, 2, MetricValueContract.Rounding.NONE), emptyMap(), money.overflow)
        val large = MetricValueContract(money.unit, MetricValueContract.Exact(), MetricValueContract.Decimal(24, 4, MetricValueContract.Rounding.NONE), emptyMap(), money.overflow)
        val rounded = MetricValueContract(money.unit, MetricValueContract.Exact(), MetricValueContract.Decimal(22, 2, MetricValueContract.Rounding.HALF_UP), emptyMap(), money.overflow)
        fun literal(value: String, contract: MetricValueContract) = ResultQuantityLiteral(BigDecimal(value), contract)
        fun choose(first: ResultExpression, second: ResultExpression) = ResultCaseWhen(listOf(ResultCaseBranch(ResultLiteral(true), first)), second)
        val selected = choose(literal("12345678901234567890.1250", large), literal("12.34", small))
        val coalesced = ResultCall(ScalarFunction.COALESCE, listOf(ResultLiteral(null), literal("12345678901234567890.1250", large), literal("12.34", small)))
        for (dialect in listOf("h2", "duckdb")) NativeWarehouse(directory, dialect, listOf(
            "CREATE TABLE orders(id INTEGER PRIMARY KEY, region CHAR(2), event_hour INTEGER, customer INTEGER, amount DECIMAL(18,2))",
            "INSERT INTO orders VALUES (1,'AB',1,1,10)"
        )).use { warehouse ->
            val catalog = catalog(warehouse); warehouse.finishSetup()
            val measured = measures(catalog, "alternatives", listOf("region"))
            val values = warehouse.execute(compile(catalog, stages(measured, listOf(
                Derive(selected, "selected", "选择大容量金额"),
                Derive(coalesced, "coalesced", "忽略NULL后选择大容量金额"),
                Derive(numeric(ResultRound(symbol("selected"), 2, MetricValueContract.Rounding.HALF_UP), rounded), "rounded", "明确量化两位小数"),
                Derive(choose(ResultTypedLiteral(ScalarValueType.TEXT, "longer"), symbol("region")), "text_case", "字符与文本分支"),
                Derive(ResultCall(ScalarFunction.COALESCE, listOf(symbol("region"), ResultTypedLiteral(ScalarValueType.TEXT, "longer"))), "text_coalesce", "保留原字符值"),
                Project(listOf("selected", "coalesced", "rounded", "text_case", "text_coalesce"))
            )))).single()
            assertEquals(listOf("12345678901234567890.125", "12345678901234567890.125", "12345678901234567890.13"),
                values.take(3).map { BigDecimal(it.toString()).stripTrailingZeros().toPlainString() }, dialect)
            assertEquals(listOf("longer", "AB"), values.drop(3), dialect)

            val otherUnit = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.UnitIdentity("trial", "distance", 1)),
                MetricValueContract.Exact(), large.result, emptyMap(), money.overflow)
            val nativeFloat = MetricValueContract(money.unit, MetricValueContract.NativeFloating(), MetricValueContract.BinaryFloat(64), emptyMap(), money.overflow)
            val floatingChoice = choose(literal("0.1", nativeFloat), literal("0.2", nativeFloat))
            for (invalid in listOf(choose(literal("1", large), literal("1", otherUnit)),
                    choose(literal("1", large), literal("0.1", nativeFloat)), numeric(floatingChoice, large))) {
                assertThrows(IllegalArgumentException::class.java, {
                    compile(catalog, stages(measured, listOf(Derive(invalid, "invalid", "不允许改变单位或算术保证"))))
                }, dialect)
            }
            if (dialect == "duckdb") {
                val whole = MetricValueContract(money.unit, MetricValueContract.Exact(), MetricValueContract.Decimal(38, 0, MetricValueContract.Rounding.NONE), emptyMap(), money.overflow)
                val fraction = MetricValueContract(money.unit, MetricValueContract.Exact(), MetricValueContract.Decimal(38, 18, MetricValueContract.Rounding.NONE), emptyMap(), money.overflow)
                val incompatible = choose(literal("1", whole), literal("0.1", fraction))
                assertThrows(IllegalArgumentException::class.java) {
                    compile(catalog, stages(measured, listOf(Derive(incompatible, "invalid", "后端不能容纳共同容量"))))
                }
            }
        }
    }
    @Test fun composesDependentWindowsAndDerivedQuantitiesWithoutExplicitRelationBoundaries(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) warehouse(directory, dialect,
            "(1,'A',1,1,10),(2,'A',2,2,NULL),(3,'A',3,3,30),(4,'B',1,4,7),(5,'B',2,5,2)").use { warehouse ->
            val catalog = catalog(warehouse)
            warehouse.finishSetup()
            val root = stages(measures(catalog, "primary", listOf("region", "hour")), listOf(
                Cumulative("value", "hour", "ASC", listOf("region"), "cumulative", "累计金额", ResultAggregation.Sum(money, ResultAggregation.NullPolicy.IGNORE)),
                OffsetValue("cumulative", listOf("region"), "previous", "上期累计", listOf(order("hour", "ASC")), "LAG", 1),
                Derive(numeric(comparison("SUBTRACT", symbol("cumulative"), symbol("previous")), money), "increase", "本期增加"),
                OrderedRank(PostOperationContract.ROW_NUMBER, listOf("region"), "position", "增加排名", listOf(order("increase", "DESC"), order("hour", "ASC"))),
                Sort(listOf(order("region", "ASC"), order("hour", "ASC"))),
                Project(listOf("region", "hour", "cumulative", "previous", "increase", "position"))))
            fun amount(value: Any?): String? = value?.let { BigDecimal(it.toString()).stripTrailingZeros().toPlainString() }
            val rows = warehouse.execute(compile(catalog, root)).map { row ->
                listOf(row[0], (row[1] as Number).toInt(), amount(row[2]), amount(row[3]), amount(row[4]), (row[5] as Number).toLong())
            }
            assertEquals(listOf(
                listOf("A", 1, "10", null, null, 3L),
                listOf("A", 2, "10", "10", "0", 2L),
                listOf("A", 3, "40", "10", "30", 1L),
                listOf("B", 1, "7", null, null, 2L),
                listOf("B", 2, "9", "7", "2", 1L)), rows, dialect)
        }
    }

    @Test fun shiftsNumericMemberCodesOnlyThroughAnExplicitDimensionlessInterpretation(@TempDir directory: Path) {
        warehouse(directory, "duckdb", "(1,'A',1,1,10),(2,'A',54,2,20)").use { warehouse ->
            val catalog = catalog(warehouse); warehouse.finishSetup()
            val later = scope(measures(catalog, "later", listOf("hour")), "RELATION")
            val shifted = numeric(comparison("ADD", numeric(symbol("hour"), code), ResultLiteral(BigDecimal("53"))), code)
            val root = scope(measures(catalog, "primary", listOf("hour")), inputs = mapOf("later" to later), operations = listOf(
                join("later", "following", "INNER", comparison("EQ", shifted, numeric(symbol("following__hour"), code))),
                Project(listOf("hour", "value", "following__value"))))
            assertEquals(listOf(listOf(1, BigDecimal("10.00"), BigDecimal("20.00"))), warehouse.execute(compile(catalog, root)))
            val invalid = scope(root, inputs = emptyMap(), operations = listOf(Derive(numeric(symbol("hour"), money), "invented_money", "错误单位")))
            assertThrows(IllegalArgumentException::class.java) { compile(catalog, invalid) }
        }
    }
}
