package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.*
import com.olap.prototype.codec.SemanticProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.engine.catalog.*
import com.olap.prototype.engine.compiler.CompiledQuery
import com.olap.prototype.engine.compiler.OlapSemanticCompiler
import com.olap.prototype.engine.context.OlapQueryContext
import com.olap.prototype.event.*
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.member.*
import com.olap.prototype.metric.*
import com.olap.prototype.query.*
import com.olap.prototype.util.OlapJsonUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

class RelationArgumentExecutionTest {
    private val seller = ScopedInputContract.DimensionRef("seller", "1", "value")
    private val day = ScopedInputContract.DimensionRef("day", "1", "value")
    private val unrestricted = DimensionCondition.Unrestricted()
    private val moneyUnit = MetricValueContract.Fixed(MetricValueContract.UnitIdentity("trial", "money", 1))
    private fun money(arithmetic: MetricValueContract.Arithmetic = MetricValueContract.Exact()) = MetricValueContract(moneyUnit, arithmetic,
        MetricValueContract.Decimal(38, 2, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
    private val count = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(),
        MetricValueContract.Integer(64), emptyMap(), MetricValueContract.Overflow.FAIL)
    private val average = MetricValueContract(moneyUnit, MetricValueContract.NativeFloating(),
        MetricValueContract.BinaryFloat(64), mapOf("per_buyer" to MetricValueContract.PreserveUnit(
            MetricValueContract.BinaryOperator.DIVIDE, MetricValueContract.Operand.LEFT)), MetricValueContract.Overflow.FAIL)
    private val observer = object : ExecutableExecutionObserver {
        override fun bindingCompleted(input: ExecutableInputScope) {}
        override fun stageAnalysisCompleted(analyses: List<QueryStageAnalysis.Analysis>) {}
        override fun sourceResolutionCompleted(sources: List<CompiledQuery.SourceEvidence>) {}
        override fun loweringCompleted(loweringResult: ExecutableLoweringResult) {}
    }

    private fun catalog(warehouse: NativeWarehouse, sourceMoney: MetricValueContract = money(),
        sellerShape: SetOutputContract.Shape = SetOutputContract.DimensionSlot("seller"), relationMoney: MetricValueContract = money(),
        sellerReference: ScopedInputContract.DimensionRef = seller, withText: Boolean = false, averageFilter: ResultExpression? = null): ExecutableCatalog {
        val units = listOf("id", "seller", "day", "buyer", "amount") + if (withText) listOf("quantity", "fee", "pay_text") else emptyList()
        val event = ExecutableEvent("orders", "1", "orders", "1", null,
            EventIdentity(listOf(EventIdentity.LogicUnitRef("orders.id", 1)), emptyMap()), emptyMap(), null, null, null)
        fun fact(input: String, aggregation: MetricAggregation, distinct: Boolean, contract: MetricValueContract) = MetricDefinition.FactMeasure(
            EventRef("orders", 1), SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "orders.$input", 1), aggregation, distinct, null, emptyMap(), null, contract,
            if (aggregation == MetricAggregation.AVG) MeanTarget.SOURCE_NATIVE else null)
        val dimension = Calculation.DimensionSlot("seller", sellerReference.id, sellerReference.version.toLong(), sellerReference.id)
        val outputs = listOf(SetOutputContract("seller", sellerShape), SetOutputContract("amount", SetOutputContract.Quantity(relationMoney)),
            SetOutputContract("people", SetOutputContract.Quantity(count)))
        val ratio = ResultExpression.ResultNumeric(ResultExpression.ResultOperation(ResultExpression.ResultOperator.DIVIDE,
            listOf(ResultExpression.ResultSymbol("amount"), ResultExpression.ResultSymbol("people"))), average, "per_buyer")
        val leaf = MetricCalculation(Calculation(listOf(dimension), emptyList(), emptyList(),
            listOf(Calculation.RelationSlot("rows", outputs, listOf("seller"))), emptyMap(),
            Calculation.Scope(Calculation.Relation("rows"), emptyMap(), listOfNotNull(averageFilter?.let { PostOperationContract.PostFilter(it) }) + listOf(PostOperationContract.Derive(ratio, "average", "客单价"),
                PostOperationContract.Project(listOf("seller", "average"))))), "average", listOf("seller"), average)
        val parentOutputs = outputs + SetOutputContract("records", SetOutputContract.Quantity(count))
        val parent = MetricCalculation(Calculation(listOf(dimension), emptyList(), emptyList(),
            listOf(Calculation.RelationSlot("basis", parentOutputs, listOf("seller"))), emptyMap(), Calculation.Scope(
                Calculation.Measures(listOf(Calculation.Call("price", Calculation.Fixed(MetricRef("average", "1")), "average", mapOf("seller" to "seller"), emptyMap(), emptyMap(), mapOf("rows" to "basis"), emptyMap(), Calculation.Condition(unrestricted), emptyMap(), emptyMap())), (listOf("seller")).map { Calculation.DimensionParameter(it) }, emptyList(), null, emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()), DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(listOf("price")), null), emptyMap(), emptyList())),
            "average", listOf("seller"), average)
        val amountOutput = SetOutputContract("amount", SetOutputContract.Quantity(money()))
        val total = MetricCalculation(Calculation(emptyList(), emptyList(), emptyList(),
            listOf(Calculation.RelationSlot("rows", listOf(amountOutput), null)), emptyMap(), Calculation.Scope(Calculation.Relation("rows"), emptyMap(),
                listOf(PostOperationContract.Regroup(PostOperationContract.ResultGrouping(emptyList(), listOf(PostOperationContract.ResultAggregate("amount",
                    ResultAggregation.Sum(money(), ResultAggregation.NullPolicy.IGNORE), "total", "金额合计")), null))))), "total", emptyList(), money())
        val keyedDimensions = listOf(Calculation.DimensionSlot("seller", "seller", 1, "seller"), Calculation.DimensionSlot("day", "day", 1, "day"))
        val keyedOutputs = listOf(SetOutputContract("seller", SetOutputContract.DimensionSlot("seller")), SetOutputContract("day", SetOutputContract.DimensionSlot("day")), amountOutput)
        val keyedTotal = MetricCalculation(Calculation(keyedDimensions, emptyList(), emptyList(),
            listOf(Calculation.RelationSlot("basis", keyedOutputs, listOf("seller", "day"))), emptyMap(), Calculation.Scope(
                Calculation.Measures(listOf(Calculation.Call("total", Calculation.Fixed(MetricRef("total", "1")), "total", emptyMap(), emptyMap(), emptyMap(), mapOf("rows" to "basis"), emptyMap(), Calculation.Condition(unrestricted), emptyMap(), emptyMap())), emptyList(), emptyList(), null, emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()), DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(listOf("total")), null), emptyMap(), emptyList())), "total", emptyList(), money())
        val definitions = linkedMapOf<String, MetricDefinition>("amount" to fact("amount", MetricAggregation.SUM, false, sourceMoney),
            "people" to fact("buyer", MetricAggregation.COUNT, true, count), "records" to fact("id", MetricAggregation.COUNT, false, count),
            "average" to leaf, "nested_average" to parent, "total" to total, "keyed_total" to keyedTotal)
        val adoptedDimension = CalculationBindings.Dimension(sellerReference, sellerReference.id, listOf("${sellerReference.id}_mapping"))
        val keyedAdoptions = listOf(seller, day).associate { reference -> reference.id to CalculationBindings.Dimension(reference, reference.id, listOf("${reference.id}_mapping")) }
        val instances = definitions.mapValues { (id, _) ->
            val bindings = when (id) {
                "average" -> CalculationBindings(mapOf("seller" to adoptedDimension), emptyMap(), emptyMap(), emptyMap(), mapOf("rows" to CalculationBindings.RelationParameter()))
                "nested_average" -> CalculationBindings(mapOf("seller" to adoptedDimension), emptyMap(), emptyMap(), emptyMap(), mapOf("basis" to CalculationBindings.RelationParameter()))
                "total" -> CalculationBindings(emptyMap(), emptyMap(), emptyMap(), emptyMap(), mapOf("rows" to CalculationBindings.RelationParameter()))
                "keyed_total" -> CalculationBindings(keyedAdoptions, emptyMap(), emptyMap(), emptyMap(), mapOf("basis" to CalculationBindings.RelationParameter()))
                else -> CalculationBindings.empty()
            }
            MetricInstance(MetricRef(id, "1"), bindings, emptyMap(), id)
        }
        val dimensions = listOf("seller", "day")
        return ExecutableCatalog(ExecutableCatalogManifest("relation_arguments", ExecutableCatalogManifest.SCHEMA_VERSION),
            memberSources = if (withText) mapOf("orders_rows@1" to observations(units)) else emptyMap(),
            metrics = definitions.map { (id, definition) -> MetricRef(id, "1").key() to ExecutableMetric(MetricRef(id, "1"), definition) }.toMap(), metricInstances = instances,
            physicalExecutionBindings = mapOf("orders@1" to warehouse.adopt("orders", units.associate { "orders.$it" to it })),
            topologies = mapOf("orders@1" to ExecutableTopology("orders", "1", "orders", null, "orders", "1")), events = mapOf("orders@1" to event),
            logicUnits = units.associate { "orders.$it@1" to ExecutableLogicUnit("orders.$it", "1", if (it == "pay_text") ScalarValueType.TEXT else ScalarValueType.NUMBER, null, null, null, it, null, "orders", "1") },
            dimensions = dimensions.associate { "$it@1" to ExecutableDimension(it, "1", null, "trial", null, null) },
            dimensionBindings = dimensions.associate { id -> "$id@1" to ExecutableDimensionBinding(id, "1", id, "1", "orders", "1", "orders", "1",
                "orders.$id", "1", SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "orders.$id", 1), RelationAccessPath(emptyList()), emptyMap(), emptyList(), "orders", "1") },
            analysisDimensions = dimensions.associate { id -> "$id@1" to ExecutableAnalysisDimension(id, "1", null, id, id, "value",
                listOf(ExecutableAnalysisDimensionLevel("value", id, null)), listOf(ExecutableAnalysisDimensionEventMapping("${id}_mapping", id, "1", "value", "orders", "1", id, "1", id, "1", "orders.$id", "1", "same_$id", "PROVEN_SAME_KEY"))) })
    }

    private fun observations(units: List<String>): MemberSource.Projection {
        val outputs = units.associateWith { field ->
            if (field == "seller") MemberSourceDefinition.DimensionOutput("商家", "seller", ScalarValueType.NUMBER, "seller", 1, "seller", 1, null)
            else MemberSourceDefinition.PropertyOutput(field, field, if (field == "pay_text") ScalarValueType.TEXT else ScalarValueType.NUMBER,
                "orders.$field", 1, RelationAccessPath(emptyList()), when (field) { "amount", "fee" -> money(); "quantity" -> count; else -> null })
        }
        return MemberSource.Projection("orders_rows", 1, MemberSourceDefinition.Projection(EventRef("orders", 1),
            MemberResult("支付记录", "payments", MemberShape.RAW_BAG, emptyList(), outputs, emptyMap(), null, emptyMap(), null)),
            units.associateWith { SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "orders.$it", 1) })
    }

    private fun paymentRows(): Calculation.Scope {
        val fields = listOf("id", "seller", "day", "buyer", "amount", "quantity", "fee", "pay_text")
        return Calculation.Scope(Calculation.Members("payments", Calculation.MemberSource(MemberRef("orders_rows", 1)), fields.map { if (it == "seller") Calculation.MemberProjection(it, it, Calculation.PublishedDimension(seller))
                else Calculation.MemberProjection(if (it in listOf("amount", "quantity", "fee")) "raw_$it" else it, it, null) }, null, emptyMap(), MemberCondition.Unrestricted(), emptyMap(), emptyMap(), emptyMap()), emptyMap(), emptyList())
    }

    private fun completedPayments(): Calculation.Scope {
        fun sum(input: String, contract: MetricValueContract) = PostOperationContract.ResultAggregate("raw_$input",
            ResultAggregation.Sum(contract, ResultAggregation.NullPolicy.IGNORE), input, input)
        fun distinct(input: String, alias: String) = PostOperationContract.ResultAggregate(input,
            ResultAggregation.Count(true, ResultAggregation.NullPolicy.IGNORE), alias, alias)
        val aggregates = listOf(sum("quantity", count), sum("amount", money()), distinct("id", "orders"), distinct("buyer", "people"),
            sum("fee", money()), PostOperationContract.ResultAggregate("pay_text", ResultAggregation.Maximum(ResultAggregation.NullPolicy.IGNORE), "latest_text", "最新支付文本"))
        return paymentRows().let { Calculation.Scope(it.root, it.inputs, listOf(PostOperationContract.Regroup(PostOperationContract.ResultGrouping(listOf("seller"), aggregates, null)))) }
    }

    private fun completedTextAndRatio(text: SetOutputContract.Shape): Calculation.Scope {
        val outputs = listOf(SetOutputContract("seller", SetOutputContract.DimensionSlot("seller")),
            SetOutputContract("quantity", SetOutputContract.Quantity(count)), SetOutputContract("amount", SetOutputContract.Quantity(money())),
            SetOutputContract("orders", SetOutputContract.Quantity(count)), SetOutputContract("people", SetOutputContract.Quantity(count)),
            SetOutputContract("fee", SetOutputContract.Quantity(money())), SetOutputContract("latest_text", text))
        val ratioInputs = outputs.filter { it.name in listOf("seller", "amount", "people") }
        val ratio = Calculation.Call("ratio", Calculation.Measurement("ratio"), "average", emptyMap(), emptyMap(), emptyMap(), mapOf("rows" to "ratio_basis"), emptyMap(), Calculation.Condition(unrestricted), emptyMap(), emptyMap())
        val ratioScope = Calculation.Scope(Calculation.Measures(listOf(ratio), (listOf("seller")).map { Calculation.DimensionParameter(it) }, emptyList(), null, emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()), DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(listOf("ratio")), null), emptyMap(), emptyList())
        val dimension = Calculation.DimensionSlot("seller", "seller", 1, "seller")
        val calculation = Calculation(listOf(dimension), emptyList(), listOf(Calculation.MeasurementSlot("ratio", emptyMap(), listOf(dimension), emptyList(), average)),
            listOf(Calculation.RelationSlot("completed", outputs, listOf("seller")), Calculation.RelationSlot("ratio_basis", ratioInputs, listOf("seller"))),
            emptyMap(), Calculation.Scope(Calculation.Relation("completed"), mapOf("ratio" to Calculation.InputScope("RELATION", ratioScope)), listOf(
                PostOperationContract.JoinInput("ratio", "ratio", "LEFT", ResultExpression.ResultOperation(ResultExpression.ResultOperator.EQ,
                    listOf(ResultExpression.ResultSymbol("seller"), ResultExpression.ResultSymbol("ratio__seller")))),
                PostOperationContract.Project(outputs.map { it.name } + "ratio__average"))))
        val bindings = CalculationBindings(mapOf("seller" to CalculationBindings.Dimension(seller, "seller", listOf("seller_mapping"))),
            emptyMap(), emptyMap(), mapOf("ratio" to MetricInstanceRef("average")), mapOf("completed" to CalculationBindings.RelationParameter(), "ratio_basis" to CalculationBindings.RelationParameter()))
        val relationInputs = mapOf("completed" to CalculationBindings.LocalRelation("payments", outputs.associate { it.name to it.name }),
            "ratio_basis" to CalculationBindings.LocalRelation("payments", ratioInputs.associate { it.name to it.name }))
        return Calculation.Scope(Calculation.Compute(calculation, Calculation.ExplicitBindings(bindings, emptyMap(), relationInputs), emptyMap()), mapOf("payments" to Calculation.InputScope("ROOT", completedPayments())), emptyList())
    }

    @Test fun combinesOneCompletedTextMaximumAndFiveQuantitiesWithASavedRatio(@TempDir directory: Path) {
        NativeWarehouse(directory, "duckdb", listOf(
            "CREATE TABLE orders(id INTEGER PRIMARY KEY,seller INTEGER,day INTEGER,buyer INTEGER,amount DECIMAL(18,2),quantity BIGINT,fee DECIMAL(18,2),pay_text VARCHAR)",
            "INSERT INTO orders VALUES (1,1,1,11,10,1,1,'20261001'),(2,1,2,12,20,2,2,'20261003'),(3,2,1,21,NULL,4,NULL,NULL),(4,3,1,31,5,1,0,''),(5,3,2,31,7,1,1,'')")).use { warehouse ->
            val catalog = catalog(warehouse, withText = true)
            warehouse.finishSetup()
            val query = completedTextAndRatio(SetOutputContract.Scalar(SetOutputContract.ScalarType.TEXT))
            val decoded = SemanticProto.fromProto(ProtoBytes.decode(ProtoBytes.encode(SemanticProto.toProto(query)), com.olap.prototype.wire.semantic.CalculationScope.getDefaultInstance()))
            val result = warehouse.execute(compile(catalog, decoded)).sortedBy { (it[0] as Number).toInt() }
            val expected = listOf(listOf("3", "30", "2", "2", "3", "20261003", "15"), listOf("4", null, "1", "1", null, null, null),
                listOf("2", "12", "2", "1", "1", "", "12"))
            assertEquals(expected.size, result.size)
            result.forEachIndexed { index, row ->
                assertEquals(index + 1, (row[0] as Number).toInt())
                assertEquals(expected[index], row.drop(1).map { if (it is Number) BigDecimal(it.toString()).stripTrailingZeros().toPlainString() else it })
            }
            val impersonated = assertThrows(EngineExecutionException::class.java) {
                compile(catalog, completedTextAndRatio(SetOutputContract.Property("payment_text", ScalarValueType.TEXT)))
            }
            assertEquals("RELATION_INPUT_CONTRACT_MISMATCH", impersonated.code)
            assertTrue(impersonated.message.contains("completed categorical value"), impersonated.message)

            val echo = Calculation(emptyList(), emptyList(), emptyList(), listOf(Calculation.RelationSlot("value", listOf(
                SetOutputContract("text", SetOutputContract.Scalar(SetOutputContract.ScalarType.TEXT))), null)), emptyMap(),
                Calculation.Scope(Calculation.Relation("value"), emptyMap(), emptyList()))
            val rawProperty = Calculation.Scope(Calculation.Compute(echo, Calculation.ExplicitBindings(CalculationBindings(emptyMap(), emptyMap(), emptyMap(), emptyMap(),
                mapOf("value" to CalculationBindings.RelationParameter())), emptyMap(), mapOf("value" to CalculationBindings.LocalRelation("raw", mapOf("text" to "pay_text")))), emptyMap()), mapOf("raw" to Calculation.InputScope("ROOT", paymentRows())), emptyList())
            val erasedRole = assertThrows(EngineExecutionException::class.java) { compile(catalog, rawProperty) }
            assertEquals("RELATION_INPUT_CONTRACT_MISMATCH", erasedRole.code)
            assertTrue(erasedRole.message.contains("cannot erase"), erasedRole.message)
        }
    }

    private fun basis(byDay: Boolean = false): Calculation.Scope {
        val calls = listOf("amount", "people", "records").map { Calculation.Call(it, Calculation.Instance(MetricInstanceRef(it)), it, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(unrestricted), emptyMap(), emptyMap()) }
        return Calculation.Scope(Calculation.Measures(calls, (if (byDay) listOf(seller, day) else listOf(seller)).map { Calculation.PublishedDimension(it) }, emptyList(), null, emptyMap(), Calculation.Condition(unrestricted), unrestricted, emptyList(), RowPopulationContract.calls(calls.map { it.callId }), null), emptyMap(), emptyList())
    }
    private fun invocation(instance: String, byDay: Boolean = false): Calculation.Scope {
        val slot = if (instance == "average") "rows" else "basis"
        val outputs = if (instance == "average") listOf("seller", "amount", "people") else listOf("seller", "amount", "people", "records")
        val call = Calculation.Call("price", Calculation.Instance(MetricInstanceRef(instance)), "average", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(unrestricted), emptyMap(), mapOf(slot to CalculationBindings.LocalRelation("basis", outputs.associateWith { it })))
        return Calculation.Scope(Calculation.Measures(listOf(call), (listOf(seller)).map { Calculation.PublishedDimension(it) }, emptyList(), null, emptyMap(), Calculation.Condition(unrestricted), unrestricted, emptyList(), RowPopulationContract.calls(listOf("price")), null), mapOf("basis" to Calculation.InputScope("ROOT", basis(byDay))), emptyList())
    }
    private fun compile(catalog: ExecutableCatalog, scope: Calculation.Scope): ExecutableLoweringResult {
        val request = ExecutableQueryRequest(ExecutableQueryRequest.FORMAT_VERSION, "relation_arguments", catalog.manifest.catalogId, scope)
        return requireNotNull(OlapSemanticCompiler(CalciteRelCompiler(TestConnectors.registry))
            .compile(catalog, request, OlapQueryContext(request.requestId), observer).executableLoweringResult)
    }
    private fun rows(warehouse: NativeWarehouse, compiled: ExecutableLoweringResult): List<List<Any?>> = warehouse.execute(compiled).map { row ->
        row.mapIndexed { index, value -> if (index == 0) (value as Number).toInt() else value?.let { BigDecimal(it.toString()).stripTrailingZeros().toPlainString() } }
    }.sortedBy { it.first() as Int }

    @Test fun combinesSavedProjectionsOfOneCompletedPopulationWithoutRepeatingItsAlignment(@TempDir directory: Path) {
        NativeWarehouse(directory, "duckdb", listOf("CREATE TABLE orders(id INTEGER PRIMARY KEY,seller INTEGER,day INTEGER,buyer INTEGER,amount DECIMAL(18,2))",
            "INSERT INTO orders VALUES (1,1,1,11,10),(2,1,1,12,20),(3,1,2,11,30),(4,2,1,21,NULL),(5,2,2,22,NULL),(6,3,1,31,7),(7,3,2,31,7)")).use { warehouse ->
            val valid = catalog(warehouse)
            val sellerOne = DimensionCondition.Predicate("selected_seller", seller, MemberCondition.Operator.EQ, listOf(Calculation.Scalar(ScalarValueType.NUMBER, "1")))
            val completedCalls = listOf("amount", "people", "records").map { id -> Calculation.Call(id, Calculation.Instance(MetricInstanceRef(id)), id, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(if (id == "people") sellerOne else unrestricted), emptyMap(), emptyMap()) }
            val completed = Calculation.Scope(Calculation.Measures(completedCalls, (listOf(seller)).map { Calculation.PublishedDimension(it) }, emptyList(), null, emptyMap(), Calculation.Condition(unrestricted), unrestricted, emptyList(), RowPopulationContract.calls(listOf("records")), null), emptyMap(), emptyList())
            fun query(basis: Calculation.Scope) : Calculation.Scope {
                val calls = listOf("people", "records").map { denominator -> Calculation.Call("per_$denominator", Calculation.Instance(MetricInstanceRef("average")), "per_$denominator", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(unrestricted), emptyMap(), mapOf("rows" to CalculationBindings.LocalRelation("basis", mapOf("seller" to "seller", "amount" to "amount", "people" to denominator)))) }
                return Calculation.Scope(Calculation.Measures(calls, (listOf(seller)).map { Calculation.PublishedDimension(it) }, emptyList(), null, emptyMap(), Calculation.Condition(unrestricted), unrestricted, emptyList(), RowPopulationContract.calls(calls.map { it.callId }), null), mapOf("basis" to Calculation.InputScope("ROOT", basis)), emptyList())
            }
            val filtered = catalog(warehouse, averageFilter = ResultExpression.ResultOperation(ResultExpression.ResultOperator.EQ,
                listOf(ResultExpression.ResultSymbol("seller"), ResultExpression.ResultLiteral(BigDecimal.ONE))))
            warehouse.finishSetup()
            val basis = compile(valid, completed)
            val combined = compile(valid, query(completed))
            assertEquals(listOf(listOf(1, "30", "20"), listOf(2, null, null), listOf(3, null, "7")), rows(warehouse, combined))
            fun leftJoins(sql: String) = Regex("\\bLEFT JOIN\\b").findAll(sql).count()
            assertTrue(leftJoins(basis.sql) > 0, "试验人口包含真实的独立资格与缺组对齐")
            assertEquals(leftJoins(basis.sql), leftJoins(combined.sql), combined.sql)
            assertFalse(Regex("\\bUNION\\b").containsMatchIn(combined.sql), combined.sql)

            // 即使两个调用的过滤看似相同，也不将未知的调用人口改写为共同投影。
            val independentlyFiltered = compile(filtered, query(completed))
            assertEquals(listOf(listOf(1, "30", "20")), rows(warehouse, independentlyFiltered))
            assertTrue(leftJoins(independentlyFiltered.sql) > leftJoins(basis.sql), independentlyFiltered.sql)
        }
        NativeWarehouse(directory, "duckdb", listOf("CREATE TABLE orders(id INTEGER PRIMARY KEY,seller INTEGER,day INTEGER,buyer INTEGER,amount DECIMAL(18,2))",
            "INSERT INTO orders VALUES (1,1,1,NULL,10)")).use { warehouse ->
            val valid = catalog(warehouse)
            val calls = listOf("first", "second").map { id -> Calculation.Call(id, Calculation.Instance(MetricInstanceRef("average")), id, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(unrestricted), emptyMap(), mapOf("rows" to CalculationBindings.LocalRelation("basis", listOf("seller", "amount", "people").associateWith { it }))) }
            val query = Calculation.Scope(Calculation.Measures(calls, (listOf(seller)).map { Calculation.PublishedDimension(it) }, emptyList(), null, emptyMap(), Calculation.Condition(unrestricted), unrestricted, emptyList(), RowPopulationContract.calls(calls.map { it.callId }), null), mapOf("basis" to Calculation.InputScope("ROOT", basis())), emptyList())
            warehouse.finishSetup()
            val failure = assertThrows(com.olap.prototype.connector.SourceExecutionException::class.java) { warehouse.execute(compile(valid, query)) }
            assertEquals(com.olap.prototype.warehouse.contract.SourceFailureCategory.SQL, failure.failure.category,
                "合并投影仍必须兑现原有非有限数量失败")
        }
    }

    @Test fun reusesGroupedFactsThroughNestedRelationsWithoutInventingKeysRolesOrQuantityGuarantees(@TempDir directory: Path) {
        NativeWarehouse(directory, "duckdb", listOf("CREATE TABLE orders(id INTEGER PRIMARY KEY,seller INTEGER,day INTEGER,buyer INTEGER,amount DECIMAL(18,2))",
            "INSERT INTO orders VALUES (1,1,1,11,10),(2,1,1,12,20),(3,1,2,11,30),(4,2,1,21,NULL),(5,2,2,22,NULL),(6,3,1,31,7),(7,3,2,31,7)")).use { warehouse ->
            val valid = catalog(warehouse)
            warehouse.finishSetup()
            assertEquals(listOf(listOf(1, "60", "2", "3"), listOf(2, null, "2", "2"), listOf(3, "14", "1", "2")), rows(warehouse, compile(valid, basis())))
            val expected = listOf(listOf(1, "30"), listOf(2, null), listOf(3, "14"))
            for (instance in listOf("average", "nested_average")) assertEquals(expected, rows(warehouse, compile(valid, invocation(instance))), instance)

            val projection = Calculation(emptyList(), emptyList(), emptyList(), listOf(Calculation.RelationSlot("rows",
                listOf(SetOutputContract("amount", SetOutputContract.Quantity(money()))), null)), emptyMap(),
                Calculation.Scope(Calculation.Relation("rows"), emptyMap(), emptyList()))
            val bag = Calculation.Scope(Calculation.Compute(projection, Calculation.ExplicitBindings(CalculationBindings(emptyMap(), emptyMap(), emptyMap(), emptyMap(), mapOf("rows" to CalculationBindings.RelationParameter())), emptyMap(), mapOf("rows" to CalculationBindings.LocalRelation("basis", mapOf("amount" to "amount")))), emptyMap()), mapOf("basis" to Calculation.InputScope("ROOT", basis(true))), emptyList())
            val amounts = warehouse.execute(compile(valid, bag)).map { it.single()?.let { value -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString() } }
            assertEquals(listOf("30", "30", "7", "7"), amounts.filterNotNull().sorted())
            assertEquals(2, amounts.count { it == null })

            fun rejected(invalid: ExecutableCatalog, scope: Calculation.Scope, reason: String) {
                val failure = assertThrows(EngineExecutionException::class.java) { compile(invalid, scope) }
                assertEquals("ANALYZE_STAGES", failure.stage)
                assertEquals("RELATION_INPUT_CONTRACT_MISMATCH", failure.code)
                assertTrue(failure.evidenceContext.containsKey("inputScope"))
                assertTrue(failure.evidenceContext.containsKey("requirements"))
                assertTrue(failure.evidenceContext.containsKey("actualOutputs"))
                assertTrue(failure.message.contains(reason), failure.message)
            }
            rejected(catalog(warehouse, sellerReference = day), invocation("nested_average"), "exact declared dimension/version/level")
            rejected(valid, invocation("nested_average", true), "declared complete actual unique key")
            val keyedOutputs = listOf(SetOutputContract("seller", SetOutputContract.Dimension(seller)), SetOutputContract("day", SetOutputContract.Dimension(day)),
                SetOutputContract("amount", SetOutputContract.Quantity(money())))
            val repeated = Calculation.Scope(Calculation.SetCombination(Calculation.SetKind.UNION, true, listOf("left", "right").map { Calculation.SetProjection(it, listOf("seller", "day", "amount")) }, keyedOutputs), listOf("left", "right").associateWith { Calculation.InputScope("ROOT", basis(true)) }, emptyList())
            val total = Calculation.Call("total", Calculation.Instance(MetricInstanceRef("keyed_total")), "total", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(unrestricted), emptyMap(), mapOf("basis" to CalculationBindings.LocalRelation("basis", listOf("seller", "day", "amount").associateWith { it })))
            val keyedParent = Calculation.Scope(Calculation.Measures(listOf(total), emptyList(), emptyList(), null, emptyMap(), Calculation.Condition(unrestricted), unrestricted, emptyList(), RowPopulationContract.calls(listOf("total")), null), mapOf("basis" to Calculation.InputScope("ROOT", repeated)), emptyList())
            rejected(valid, keyedParent, "declared complete actual unique key")
            rejected(catalog(warehouse, sellerShape = SetOutputContract.Property("seller", ScalarValueType.NUMBER)), invocation("nested_average"), "actual declared member or set property role")
            rejected(catalog(warehouse, sourceMoney = money(MetricValueContract.NativeFloating())), invocation("nested_average"), "native approximate input cannot become exact arithmetic")
            val weak = money(MetricValueContract.Approximate("0", "1000000", "0.001", "0.0001"))
            val strict = money(MetricValueContract.Approximate("0", "1000000", "0.000001", "0.0000001"))
            rejected(catalog(warehouse, sourceMoney = weak, relationMoney = strict), invocation("nested_average"), "quantity approximation exceeds its required domain or evaluation tolerances")
        }
    }
}
