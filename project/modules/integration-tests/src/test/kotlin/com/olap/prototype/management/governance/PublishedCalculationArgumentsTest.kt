package com.olap.prototype.management.governance

import com.olap.fixtures.http.HttpSqlService
import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.codec.QueryProto
import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.connector.JdbcAccess
import com.olap.prototype.connector.HttpSourceAccessTest
import com.olap.prototype.connector.loader.ConnectorPlugins
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.calculation.*
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.event.EventRef
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.engine.executable.ExecutableBusinessResult
import com.olap.prototype.governance.contract.*
import com.olap.prototype.member.*
import com.olap.prototype.metric.MetricBusinessMeaning
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.*
import com.olap.prototype.query.PostOperationContract.Derive
import com.olap.prototype.query.PostOperationContract.JoinInput
import com.olap.prototype.query.PostOperationContract.Project
import com.olap.prototype.query.PostOperationContract.Regroup
import com.olap.prototype.query.PostOperationContract.ResultAggregate
import com.olap.prototype.query.PostOperationContract.ResultGrouping
import com.olap.prototype.query.PostOperationContract.PostFilter
import com.olap.prototype.query.ResultExpression.*
import com.olap.prototype.query.contract.MetricAdoption
import com.olap.prototype.query.contract.StageAuthorization
import com.olap.prototype.query.contract.ExpressionAuthorization
import com.olap.prototype.query.contract.FilterLeafOperator
import com.olap.prototype.query.contract.MemberQueryPermission
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.warehouse.contract.JdbcConnectionSpec
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.contract.SourceAdoptionRef
import com.olap.prototype.warehouse.inspection.JdbcWarehouseInspector
import com.olap.prototype.warehouse.jdbc.JdbcSourceInspection
import com.olap.prototype.warehouse.onboarding.WarehouseDiscoveryCommand
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.nio.file.Files

class PublishedCalculationArgumentsTest {
    @Test
    fun filtersAPublishedCompletedCountInCoreWithoutDroppingZeroGroups(@TempDir directory: Path) {
        val metadata = directory.resolve("completed-count-platform")
        val code = "positive_customer_count"
        val count = GovernanceOrderFixture.records()
        var sourceAdoptions: List<SourceAdoptionRef> = emptyList()
        PlatformDatabase.openSingleInstance(metadata).use { database ->
            val f = GovernanceOrderFixture(database, directory, listOf("""
                INSERT INTO main.orders VALUES
                ('a1',100,'A','2026-07-21','x'),('a2',100,'A','2026-07-21','x'),
                ('a3',100,'A','2026-07-21',NULL),('a4',NULL,'A','2026-07-21','z'),
                ('b1',0,'B','2026-07-21','y'),('b2',-1,'B','2026-07-21','y'),('b3',NULL,'B','2026-07-21',NULL),
                ('c1',10,'C','2026-07-21','p'),('c2',20,'C','2026-07-21','q'),
                ('d1',NULL,'D','2026-07-21','k'),('e1',2,'E','2026-07-21',''),('e2',2,'E','2026-07-21','')
            """.trimIndent()))
            f.seed()
            f.activateSubjectGraph(includeSubject = false)
            val binding = f.dimensions.listDimensionEventBindings("global.destination", 1).single()
            val local = RelationAccessPath(emptyList())
            val rows = f.adoptions.createMemberSource(MemberSourceCreateRequest(GovernanceOrderFixture.SUBJECT, 1,
                "count_rows", "条件计数原始袋", MemberSourceDefinition.Projection(EventRef(GovernanceOrderFixture.EVENT, 1),
                    MemberResult("PaymentObservation", "payment_record", MemberShape.RAW_BAG, emptyList(), linkedMapOf(
                        "destination" to MemberSourceDefinition.DimensionOutput("目的地", "destination", ScalarValueType.TEXT,
                            "global.destination", 1, binding.id, 1, null),
                        "customer" to MemberSourceDefinition.PropertyOutput("客户", "customer", ScalarValueType.TEXT, "order_fact.customer_id", 1, local, null),
                        "amount" to MemberSourceDefinition.PropertyOutput("金额", "amount", ScalarValueType.NUMBER, "order_fact.pay_amount", 1, local, GovernanceOrderFixture.money())),
                        emptyMap(), null, emptyMap(), "保留重复客户、NULL与无满足记录的目的地")), CalculationBindings.empty(), emptyMap(), "golden"))
            val outputs = linkedMapOf<String, Calculation.MemberOutput>("destination" to Calculation.MemberDimension("destination", null),
                "customer" to Calculation.MemberProperty("customer", ScalarValueType.TEXT, null),
                "amount" to Calculation.MemberProperty("amount", ScalarValueType.NUMBER, GovernanceOrderFixture.money()))
            val positive = ResultOperation(ResultOperator.GT, listOf(ResultSymbol("amount"), ResultQuantityLiteral(BigDecimal.ZERO, GovernanceOrderFixture.money())))
            val body = Calculation.Scope(Calculation.Members("rows", Calculation.MemberParameter("rows"),
                outputs.keys.map { Calculation.MemberProjection(it, it, null) }, null, emptyMap(), MemberCondition.Unrestricted(), emptyMap(), emptyMap(), emptyMap()),
                emptyMap(), listOf(
                    Derive(ResultCaseWhen(listOf(ResultCaseBranch(positive, ResultSymbol("customer"))), ResultLiteral(null)), "eligible_customer", "正金额客户，NULL不参与"),
                    Regroup(ResultGrouping(listOf("destination"), listOf(ResultAggregate("eligible_customer",
                        ResultAggregation.Count(true, ResultAggregation.NullPolicy.IGNORE), "value", "去重有效客户")), null)),
                    Project(listOf("destination", "value"))))
            val definition = MetricCalculation(Calculation(listOf(Calculation.DimensionSlot("destination", "global.destination", 1, "destination")),
                listOf(Calculation.MemberSlot("rows", "PaymentObservation", "payment_record", MemberShape.RAW_BAG, outputs, null)),
                emptyList(), emptyList(), emptyMap(), body), "value", listOf("destination"), count)
            val metric = f.metrics.createMetricCalculation(MetricCalculationCreateRequest(code, "正金额去重客户数", "trade", definition,
                "完整保存CASE后条件COUNT，保留有输入但计数为0的组", MetricBusinessMeaning("正金额记录中的不同非NULL客户", "个", "有订单输入的目的地", emptyList()), "golden")).metric
            f.activate("METRIC", metric.id)
            f.adopt(metric.id, bindings = CalculationBindings(mapOf("destination" to CalculationBindings.Dimension(destination, "destination",
                f.analysis.listAnalysisDimensionEventMappings(destination.id, 1).map { it.id })),
                mapOf("rows" to CalculationBindings.SourceCall(MemberRef(rows.id, 1), emptyMap(), outputs.keys.associateWith { it }, emptyMap(), emptyMap())),
                emptyMap(), emptyMap(), emptyMap()))
            f.grantMetric(metric.id, order = 2)
            f.scenarios.createScenarioPipelineRule(ScenarioPipelineRuleCreateRequest(GovernanceOrderFixture.SCENARIO, 1,
                "POST_FILTER", 1, StageAuthorization.current("POST_FILTER")))
            f.activate("SUBJECT_AREA", GovernanceOrderFixture.SUBJECT)
            sourceAdoptions = f.sourceAdoptions
        }
        PlatformDatabase.openSingleInstance(metadata).use { database ->
            val f = GovernanceOrderFixture(database, directory)
            f.sourceAdoptions = sourceAdoptions
            f.build("completed_count_filter")
            val publication = f.publication("completed_count_filter")
            val root = Calculation.Measures(listOf(invocation("selected_count", instance(f, code), "selected_count")),
                listOf(Calculation.PublishedDimension(destination)), emptyList(), null, emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()),
                DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(listOf("selected_count")), null)
            val query = Calculation.Scope(root, emptyMap(), listOf(PostFilter(ResultOperation(ResultOperator.LT,
                listOf(ResultSymbol("selected_count"), ResultQuantityLiteral(BigDecimal("2"), count))))))
            val expected = mapOf("A" to 1, "B" to 0, "D" to 0, "E" to 1)
            for (mode in listOf(QueryExecutionMode.CORE_FINALIZATION, QueryExecutionMode.SOURCE)) {
                val result = f.execute(publication, query, mode).businessResult
                assertEquals(expected.size, result.rows.size, "$mode: 每个保留的目的地只输出一行")
                val destinationIndex = result.columns.indexOfFirst { it.symbol.name == "destination" }
                val countIndex = result.columns.indexOfFirst { it.symbol.name == "selected_count" }
                val actual = result.rows.associate { row ->
                    (row[destinationIndex] as ExecutableBusinessResult.ScalarValue).value to
                        BigDecimal((row[countIndex] as ExecutableBusinessResult.ScalarValue).value).intValueExact()
                }
                assertEquals(expected, actual, "$mode: 重复与NULL不增加票数，FALSE/UNKNOWN组保留真实的0")
            }
        }
    }

    @Test
    fun publishesAndReusesPersistedNativeScalarCalculationsThroughTheActualHttpPlugin(@TempDir directory: Path) {
        val spec = JdbcConnectionSpec("duckdb", TestConnectors.ref("duckdb"), "duckdb",
            "jdbc:duckdb:${directory.resolve("warehouse.duckdb")}", null, null, "org.duckdb.DuckDBDriver")
        val jdbc = (TestConnectors.registry.require(spec.connectorRef).access as JdbcAccess).jdbc
        HttpSqlService(jdbc, spec).use { server ->
            val pluginRoot = Files.createDirectory(directory.resolve("plugin"))
            ConnectorPlugins.load(listOf(HttpSourceAccessTest.plugin(pluginRoot))).use { loaded ->
                val ref = loaded.registry.references().single()
                val registry = ConnectorRegistry(TestConnectors.registry.references().map(TestConnectors.registry::require) + loaded.registry.require(ref))
                val source = SourceConfiguration("duckdb", ref, "duckdb", mapOf("endpoint" to server.endpoint, "consistentChecks" to true),
                    mapOf("timeoutMillis" to 30000), mapOf("authorization" to "OLAP_TEST_HTTP_AUTH"))
                val metadata = directory.resolve("platform")
                val nativeQuantity = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.NativeFloating(),
                    MetricValueContract.BinaryFloat(64), emptyMap(), MetricValueContract.Overflow.FAIL)
                val relation = Calculation.RelationSlot("completed", listOf(SetOutputContract("quantity", SetOutputContract.Quantity(GovernanceOrderFixture.records()))), emptyList())
                val definition = MetricCalculation(Calculation(emptyList(), emptyList(), emptyList(), listOf(relation), emptyMap(),
                    Calculation.Scope(Calculation.Relation("completed"), emptyMap(), listOf(PostOperationContract.Derive(
                        ResultExpression.ResultNativeCall(ref, "native_float", listOf(ResultExpression.ResultSymbol("quantity"))), "value", "来源原生数量表示"),
                        PostOperationContract.Project(listOf("value"))))), "value", emptyList(), nativeQuantity)
                val nativeOrders = listOf("INSERT INTO main.orders VALUES ('1',100,'A','2026-07-21','c1'),('2',40,'B','2026-07-21','c2'),('3',100,'A','2026-07-22','c3'),('4',20,'B','2026-08-01','c1')")
                var selectedAdoptions: List<SourceAdoptionRef> = emptyList()
                PlatformDatabase.openSingleInstance(metadata).use { database ->
                    val f = GovernanceOrderFixture(database, directory, nativeOrders, connectors = registry, deployedSource = source)
                    f.seed()
                    for ((code, distinct) in listOf("native_amount_records" to false, "native_amount_values" to true)) {
                        val display = if (distinct) "去重金额值数" else "非空金额记录数"
                        val count = f.metrics.createMetric(MetricCreateRequest(code, display, "trade", MetricDefinition.FactMeasure(
                            com.olap.prototype.event.EventRef(GovernanceOrderFixture.EVENT, 1), GovernanceOrderFixture.ref("order_fact.pay_amount"), MetricAggregation.COUNT,
                            distinct, null, emptyMap(), null, GovernanceOrderFixture.records()),
                            display, MetricBusinessMeaning(display, "个", if (distinct) "来源金额值" else "含金额的来源记录", emptyList()), "golden")).metric
                        f.activate("METRIC", count.id); f.adopt(count.id); f.grantMetric(count.id, order = if (distinct) 3 else 2)
                    }
                    val request = MetricCalculationCreateRequest("native_completed_number", "完成数量的原生表示", "trade", definition,
                        "复用完成输入的精确连接器标量变换", MetricBusinessMeaning("完成数量沿用已声明原生浮点表示", "个", "调用选择的人口", emptyList()), "golden")
                    val encoded = ProtoBytes.encode(GovernanceProto.toProto(request))
                    val decoded = GovernanceProto.fromProto(ProtoBytes.decode(encoded, com.olap.prototype.wire.governance.MetricCalculationCreateRequest.getDefaultInstance()))
                    val calculation = f.metrics.createMetricCalculation(decoded).metric
                    f.activate("METRIC", calculation.id)
                    f.adopt(calculation.id, bindings = CalculationBindings(emptyMap(), emptyMap(), emptyMap(), emptyMap(), mapOf("completed" to CalculationBindings.RelationParameter())))
                    f.grantMetric(calculation.id, order = 4)
                    val selected = StageAuthorization("DERIVE", emptySet(), emptySet(), ExpressionAuthorization(
                        setOf(ExpressionAuthorization.Kind.NUMERIC, ExpressionAuthorization.Kind.OPERATION, ExpressionAuthorization.Kind.SYMBOL),
                        setOf(ResultExpression.ResultOperator.ADD), emptySet(), emptySet(), emptySet()))
                    f.scenarios.createScenarioPipelineRule(ScenarioPipelineRuleCreateRequest(GovernanceOrderFixture.SCENARIO, 1, "DERIVE", 1, selected))
                    val restricted = f.scenarios.createScenario(ScenarioCreateRequest(GovernanceOrderFixture.SUBJECT, 1, "native_restricted", "不授权查询内变换", "analyst", "仅允许采用后的计数", "golden")).scenario
                    val count = f.metrics.listMetrics().first { it.code == "native_amount_records" }
                    f.grantMetric(count.id, scenario = restricted.id)
                    f.activateSubjectGraph()
                    selectedAdoptions = f.sourceAdoptions
                }
                // 重开持久根后由正式发布读取定义；不能依赖创建响应中残留的原生节点。
                PlatformDatabase.openSingleInstance(metadata).use { database ->
                    val f = GovernanceOrderFixture(database, directory, connectors = registry, deployedSource = source)
                    f.sourceAdoptions = selectedAdoptions
                    f.build("published_native_scalar")
                    val publication = f.publication("published_native_scalar")
                    fun complete(code: String) = measures(listOf(invocation("count", instance(f, code), "quantity")))
                    fun transform(id: String, input: String) = Calculation.Call(id, Calculation.Instance(instance(f, "native_completed_number")), id, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()), emptyMap(), mapOf("completed" to CalculationBindings.LocalRelation(input, mapOf("quantity" to "quantity"))))
                    val query = Calculation.Scope(measures(listOf(transform("records", "records"), transform("distinct_amounts", "amounts"))).root, mapOf("records" to Calculation.InputScope("ROOT", complete("native_amount_records")),
                            "amounts" to Calculation.InputScope("ROOT", complete("native_amount_values"))), emptyList())
                    val encoded = ProtoBytes.encode(QueryProto.toProto(f.intent(publication, query)))
                    val decoded = QueryProto.fromProto(ProtoBytes.decode(encoded, com.olap.prototype.wire.query.QueryIntent.getDefaultInstance()))
                    val row = f.execute(publication, decoded.computation).result.resultFrame.rows.single()
                    assertEquals(4.0, (row["records"] as Number).toDouble())
                    assertEquals(3.0, (row["distinct_amounts"] as Number).toDouble())
                    val completed = complete("native_amount_records")
                    fun publicArithmetic(operator: ResultExpression.ResultOperator) = Calculation.Scope(completed.root, completed.inputs, listOf(PostOperationContract.Derive(ResultExpression.ResultNumeric(ResultExpression.ResultOperation(operator,
                            listOf(ResultExpression.ResultSymbol("quantity"), ResultExpression.ResultSymbol("quantity"))),
                            GovernanceOrderFixture.records(), null), "selected_arithmetic", "明确批准的数量运算")))
                    number("8", f.execute(publication, publicArithmetic(ResultExpression.ResultOperator.ADD)).result.resultFrame.rows.single()["selected_arithmetic"])
                    val beforeUnselected = server.queries
                    val unselected = assertThrows(EngineExecutionException::class.java) {
                        f.execute(publication, publicArithmetic(ResultExpression.ResultOperator.SUBTRACT))
                    }
                    assertEquals("AUTHORIZE_INPUT", unselected.stage)
                    assertEquals("EXECUTABLE_QUERY_NOT_PUBLISHED", unselected.code)
                    assertEquals(beforeUnselected, server.queries, "同一发布未选SUBTRACT，应在调用实际HTTP来源前拒绝")
                    val restricted = f.publication("published_native_scalar", scenario = "${GovernanceOrderFixture.SUBJECT}.native_restricted")
                    val input = complete("native_amount_records")
                    val denied = Calculation.Scope(input.root, input.inputs, listOf(PostOperationContract.Derive(
                        ResultExpression.ResultNativeCall(ref, "native_float", listOf(ResultExpression.ResultSymbol("quantity"))), "unapproved", "未授权查询内变换")))
                    val requests = server.queries
                    val failure = assertThrows(EngineExecutionException::class.java) { f.execute(restricted, denied) }
                    assertEquals("AUTHORIZE_INPUT", failure.stage)
                    assertEquals(requests, server.queries, "发布未授权的阶段应在来源调用前拒绝")
                }
            }
        }
    }

    @Test
    fun publishesPersistedRelationParametersAndComputesFromCompletedFactInputs(@TempDir directory: Path) {
        val metadata = directory.resolve("platform")
        var selectedAdoptions: List<SourceAdoptionRef> = emptyList()
        PlatformDatabase.openSingleInstance(metadata).use { database ->
            val f = GovernanceOrderFixture(database, directory, orders)
            f.seed()
            val count = f.metrics.createMetric(MetricCreateRequest(
                code = "order_count",
                displayName = "支付订单数",
                owner = "trade",
                definition = MetricDefinition.FactMeasure(
                    event = com.olap.prototype.event.EventRef(GovernanceOrderFixture.EVENT, 1),
                    input = GovernanceOrderFixture.ref("order_fact.pay_amount"),
                    aggregation = MetricAggregation.COUNT,
                    distinct = false,
                    privatePredicate = null,
                    roles = emptyMap(),
                    snapshotRule = null,
                    valueContract = GovernanceOrderFixture.records(),
                ),
                description = "有支付金额的订单数",
                businessMeaning = MetricBusinessMeaning("有支付金额的成功订单数量", "单", "支付订单", emptyList()),
                createdBy = "golden"
            )).metric
            val average = createCalculation(f, "completed_order_mean", relationMean())
            f.activate("METRIC", count.id)
            f.adopt(count.id)
            f.grantMetric(count.id, order = 2)
            f.adopt(average.id, bindings = CalculationBindings(emptyMap(), emptyMap(), emptyMap(), emptyMap(),
                mapOf("completed" to CalculationBindings.RelationParameter())))
            f.grantMetric(average.id, order = 3)
            f.scenarios.createScenarioPipelineRule(ScenarioPipelineRuleCreateRequest(GovernanceOrderFixture.SCENARIO, 1, "DERIVE", 1, StageAuthorization.current("DERIVE")))
            f.activateSubjectGraph()
            selectedAdoptions = f.sourceAdoptions
        }

        // 发布在重开后从采用表读取关系参数，不能依赖创建响应里的内存绑定。
        PlatformDatabase.openSingleInstance(metadata).use { database ->
            val f = GovernanceOrderFixture(database, directory)
            f.sourceAdoptions = selectedAdoptions
            f.build("completed_relation_arguments")
            val publication = f.publication("completed_relation_arguments")
            val calls = listOf(
                invocation("amount", instance(f, "pay_amount"), "amount"),
                invocation("orders", instance(f, "order_count"), "orders"))
            val measured = measures(calls)
            val completed = Calculation.Scope(measured.root, measured.inputs, listOf(PostOperationContract.Derive(
                ResultExpression.ResultTypedLiteral(ScalarValueType.TEXT, "completed"), "label", "完成结果说明")))
            val average = Calculation.Call("average", Calculation.Instance(instance(f, "completed_order_mean")), "mean", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()), emptyMap(), mapOf("completed" to CalculationBindings.LocalRelation("paid", mapOf("amount" to "amount", "orders" to "orders", "label" to "label"))))
            val query = Calculation.Scope(measures(listOf(average)).root, mapOf("paid" to Calculation.InputScope("ROOT", completed)), emptyList())
            val rows = f.execute(publication, query).result.resultFrame.rows
            assertEquals(1, rows.size)
            number("55", rows.single()["mean"])
        }
    }

    @Test
    fun publishesSixGuardedRatiosOverAnIndependentDenominatorAndReportsTheActualLeftBag(@TempDir directory: Path) {
        val metadata = directory.resolve("population-platform")
        val count = GovernanceOrderFixture.records()
        fun sourceNumber(name: String) = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.UnitIdentity("example", name, 1)),
            MetricValueContract.NativeFloating(), MetricValueContract.BinaryFloat(64), emptyMap(), MetricValueContract.Overflow.FAIL)
        val amounts = linkedMapOf("base_a" to sourceNumber("base_a"), "base_b" to sourceNumber("base_b"))
        val contributions = listOf("raw_a", "raw_b", "raw_c", "raw_d", "component_e", "component_f").associateWith(::sourceNumber)
        data class Ratio(val code: String, val numerator: String, val denominator: String, val flag: String, val counter: String, val positiveOnly: Boolean)
        val ratios = listOf(Ratio("ratio_a", "raw_a", "base_a", "flag_a", "count_a", false),
            Ratio("ratio_b", "raw_b", "base_a", "flag_a", "count_a", false),
            Ratio("ratio_c", "raw_c", "base_b", "flag_a", "count_b", false),
            Ratio("ratio_d", "raw_d", "base_b", "flag_a", "count_b", false),
            Ratio("ratio_e", "component_e", "base_a", "flag_b", "count_a", true),
            Ratio("ratio_f", "component_f", "component_e", "flag_b", "count_b", true))
        val contracts = amounts + contributions
        val accountConcept = "example.account"
        val axis = ScopedInputContract.DimensionRef("${GovernanceOrderFixture.SUBJECT}.account", "1", "account")
        fun sym(name: String): ResultExpression = ResultSymbol(name)
        fun op(operator: ResultOperator, vararg operands: ResultExpression) = ResultOperation(operator, operands.toList())
        fun quantity(value: Int, contract: MetricValueContract) = ResultQuantityLiteral(BigDecimal.valueOf(value.toLong()), contract)
        fun choose(condition: ResultExpression, yes: ResultExpression, no: ResultExpression) = ResultCaseWhen(listOf(ResultCaseBranch(condition, yes)), no)
        fun sum(input: String, output: String, contract: MetricValueContract) = ResultAggregate(input, ResultAggregation.Sum(contract, ResultAggregation.NullPolicy.IGNORE), output, output)
        fun definition(spec: Ratio): MetricCalculation {
            val n = contracts.getValue(spec.numerator)
            val d = contracts.getValue(spec.denominator)
            val output = sourceNumber(spec.code)
            val equation = MetricValueContract.FixedBinary(MetricValueContract.BinaryOperator.DIVIDE,
                (n.unit as MetricValueContract.Fixed).identity, (d.unit as MetricValueContract.Fixed).identity, (output.unit as MetricValueContract.Fixed).identity)
            val result = MetricValueContract(output.unit, output.arithmetic, output.result, mapOf("quotient" to equation), output.overflow)
            val guard = if (spec.positiveOnly) op(ResultOperator.GT, sym("denominator"), quantity(0, d)) else op(ResultOperator.AND,
                op(ResultOperator.IS_NOT_NULL, sym("denominator")), op(ResultOperator.NE, sym("denominator"), quantity(0, d)))
            val invalid = if (spec.positiveOnly) quantity(0, output) else ResultLiteral(null)
            val quotient = ResultNumeric(op(ResultOperator.DIVIDE, sym("numerator"), sym("denominator")), result, "quotient")
            return MetricCalculation(Calculation(listOf(Calculation.DimensionSlot("account", accountConcept, 1, "account")), emptyList(), emptyList(),
                listOf(Calculation.RelationSlot("basis", listOf(SetOutputContract("account", SetOutputContract.DimensionSlot("account")),
                    SetOutputContract("numerator", SetOutputContract.Quantity(n)), SetOutputContract("denominator", SetOutputContract.Quantity(d))), listOf("account"))), emptyMap(),
                Calculation.Scope(Calculation.Relation("basis"), emptyMap(), listOf(Derive(choose(guard, quotient, invalid), "value", "按完整分母政策计算"),
                    Project(listOf("account", "value"))))), "value", listOf("account"), result)
        }
        val ledgerTypes = linkedMapOf("account" to ScalarValueType.TEXT, "record_reference" to ScalarValueType.TEXT,
            "count_a" to ScalarValueType.NUMBER, "count_b" to ScalarValueType.NUMBER, "base_a" to ScalarValueType.NUMBER, "base_b" to ScalarValueType.NUMBER)
        val attributeTypes = linkedMapOf("partition_key" to ScalarValueType.TEXT, "record_reference" to ScalarValueType.TEXT,
            "flag_a" to ScalarValueType.TEXT, "flag_b" to ScalarValueType.TEXT) + contributions.keys.associateWith { ScalarValueType.NUMBER }
        val inputSql = listOf(
            "CREATE TABLE main.population_ledger(account VARCHAR, record_reference VARCHAR, count_a BIGINT, count_b BIGINT, base_a DOUBLE, base_b DOUBLE)",
            "INSERT INTO main.population_ledger VALUES ('A','rA',1,1,100,50),('B','rB',1,1,NULL,NULL),('C','rC',1,1,-10,-5),('D','rD',0,0,0,0),('E','rE',1,1,20,10)",
            "CREATE TABLE main.population_attributes(partition_key VARCHAR, record_reference VARCHAR, flag_a VARCHAR, flag_b VARCHAR, raw_a DOUBLE, raw_b DOUBLE, raw_c DOUBLE, raw_d DOUBLE, component_e DOUBLE, component_f DOUBLE)",
            """INSERT INTO main.population_attributes VALUES
                ('20000101','rA','Y','Y',10,NULL,3,4,5,6),('20000102','rA','Y','Y',20,NULL,7,8,9,10),
                ('20000101','rB','Y','Y',NULL,NULL,NULL,NULL,NULL,NULL),
                ('20000101','rC','Y','Y',5,1,2,3,4,5),
                ('20000101','rD',NULL,NULL,9,9,9,9,9,9)
            """.trimIndent())
        var selectedAdoptions: List<SourceAdoptionRef> = emptyList()
        lateinit var ledgerMember: MemberRef
        lateinit var attributeMember: MemberRef
        lateinit var accountMapping: String
        PlatformDatabase.openSingleInstance(metadata).use { database ->
            val f = GovernanceOrderFixture(database, directory, inputSql)
            f.seed()
            val catalog = TestConnectors.open("duckdb", f.spec.jdbcUrl).use { it.catalog }
            fun register(table: String, types: Map<String, ScalarValueType>): EventRef {
                val discovered = f.onboarding.discover(WarehouseDiscoveryCommand("duckdb", f.spec.sourceIdentityHash(), catalog, "main", table,
                    emptyList(), 10, emptyMap(), emptyMap(), "golden"), RuntimeDeploymentLimits.CONTROL,
                    JdbcSourceInspection(JdbcWarehouseInspector(f.connectors), f.connectors))
                assertEquals("SUCCEEDED", discovered.snapshot.status, discovered.snapshot.errorMessage)
                val sourceTable = discovered.tables.single()
                val physical = f.physical.createPhysicalTable(PhysicalTableCreateRequest("example.$table", table, table, "合成袋来源", "example", "data", "internal",
                    "example", "duckdb", sourceTable.catalogName, sourceTable.schemaName, PhysicalTableCreateRequest.SourceAdoptionInput(sourceTable.id, emptyList()), "golden",
                    discovered.columns.map { PhysicalTableCreateRequest.ColumnInput(it.columnName, it.columnName, it.typeName, "internal", requireNotNull(it.nullable), "实际来源列",
                        com.olap.prototype.contract.JdbcColumnType(it.jdbcType, it.typeName, it.precision, it.scale, it.nullable)) }))
                f.sourceAdoptions += physical.sourceAdoptions.references
                f.activate("PHYSICAL_TABLE", physical.physicalTable.id)
                val columns = physical.physicalColumns.associate { it.columnName to it.id }
                f.logical.createLogicalTable(LogicalTableCreateRequest(physical.physicalTable.id, 1, table, table, LogicalTableRole.FACT, "未声明记录唯一性的袋", "data", "golden",
                    types.map { (name, type) -> LogicalTableCreateRequest.LogicUnitInput(name, name, LogicUnitSourceKind.COLUMN, null,
                        if (type == ScalarValueType.TEXT) LogicType.STRING else LogicType.NUMBER, null, false, false, false, true, type == ScalarValueType.NUMBER,
                        listOf(LogicalTableCreateRequest.SourceRefInput(columns.getValue(name), 1, LogicUnitSourceRefRole.DIRECT_SOURCE))) }))
                val topology = f.topologies.createTopology(TopologyCreateRequest(table, table, "example.population", "合成来源", table, 1, "data", "独立测量来源", "golden")).topology
                val event = f.events.createEvent(EventCreateRequest(topology.id, 1, "rows", table, "data", null, null, EventIdentity(emptyList(), emptyMap()), emptyMap(),
                    types.filterValues { it == ScalarValueType.TEXT }.keys.map { EventCreateRequest.LogicUnitRefInput("$table.$it", 1) },
                    types.filterValues { it == ScalarValueType.NUMBER }.keys.map { EventCreateRequest.LogicUnitRefInput("$table.$it", 1) }, "无跨分区唯一性推断", "golden")).event
                f.activate("LOGICAL_TABLE", table); f.activate("TOPOLOGY", topology.id); f.activate("EVENT", event.id)
                return EventRef(event.id, 1)
            }
            val ledgerEvent = register("population_ledger", ledgerTypes)
            val attributeEvent = register("population_attributes", attributeTypes)
            f.dimensions.createDimension(DimensionCreateRequest(accountConcept, "合成对象", "账户", "data", "正式TEXT账户身份", "golden"))
            val binding = f.dimensions.createDimensionEventBinding(DimensionEventBindingCreateRequest(accountConcept, 1, ledgerEvent.eventId, 1,
                "population_ledger.account", 1, null, RelationAccessPath(emptyList()), emptyMap(), "golden")).binding
            val analysis = f.analysis.createAnalysisDimension(AnalysisDimensionCreateRequest(GovernanceOrderFixture.SUBJECT, 1, "account", "账户", "账户", "account", "完成账户人口", "golden")).analysisDimension
            AnalysisDimensionGovernanceRepository(database).saveAnalysisDimensionValueAlignment(AnalysisDimensionValueAlignmentRecord("population-account-alignment", analysis.id, 1,
                "account", "PROVEN_SAME_KEY", f.mapper.writeValueAsString(mapOf("logicUnitId" to "population_ledger.account")), "golden", f.now, "PROVEN", f.now))
            accountMapping = f.analysis.createAnalysisDimensionEventMapping(AnalysisDimensionEventMappingCreateRequest(analysis.id, 1, "account", binding.id, 1,
                "PROVEN_SAME_KEY", "population-account-alignment")).mapping.id
            f.activate("DIMENSION", accountConcept); f.activate("DIMENSION_EVENT_BINDING", binding.id)
            fun member(table: String, event: EventRef, types: Map<String, ScalarValueType>): MemberRef {
                val outputs = types.mapValues { (name, type) ->
                    if (name == "account") MemberSourceDefinition.DimensionOutput("账户", "account", type, accountConcept, 1, binding.id, 1, null)
                    else MemberSourceDefinition.PropertyOutput(name, name, type, "$table.$name", 1, RelationAccessPath(emptyList()),
                        if (name.startsWith("count_")) count else contracts[name])
                }
                val adopted = f.adoptions.createMemberSource(MemberSourceCreateRequest(GovernanceOrderFixture.SUBJECT, 1, table, table,
                    MemberSourceDefinition.Projection(event, MemberResult(table, "observations", MemberShape.RAW_BAG, emptyList(), outputs, emptyMap(), null, emptyMap(), null)),
                    CalculationBindings.empty(), emptyMap(), "golden"))
                f.scenarios.createScenarioMemberSourceRef(ScenarioMemberSourceRefCreateRequest(GovernanceOrderFixture.SCENARIO, 1, adopted.id, 1,
                    MemberQueryPermission(types.keys.toList(), emptyList(), emptyMap(), false, emptyList())))
                return MemberRef(adopted.id, 1)
            }
            ledgerMember = member("population_ledger", ledgerEvent, ledgerTypes)
            attributeMember = member("population_attributes", attributeEvent, attributeTypes)
            val dimensionBindings = mapOf("account" to CalculationBindings.Dimension(axis, "account", listOf(accountMapping)))
            ratios.forEachIndexed { index, spec ->
                val created = f.metrics.createMetricCalculation(MetricCalculationCreateRequest(spec.code, spec.code, "data", definition(spec),
                    "同一账户完成关系上的已批准guard", MetricBusinessMeaning("保留原生数量、负分母和NULL政策的商值", "来源商值", "调用明确提供的账户人口", emptyList()), "golden")).metric
                f.activate("METRIC", created.id)
                f.adopt(created.id, bindings = CalculationBindings(dimensionBindings, emptyMap(), emptyMap(), emptyMap(), mapOf("basis" to CalculationBindings.RelationParameter())))
                f.grantMetric(created.id, order = index + 2)
            }
            f.scenarios.createScenarioAnalysisDimensionRef(ScenarioAnalysisDimensionRefCreateRequest(GovernanceOrderFixture.SCENARIO, 1, analysis.id, 1, "account", true, 3))
            listOf("POST_FILTER", "DERIVE", "REGROUP", "JOIN_INPUT", "PROJECT").forEachIndexed { index, operation ->
                f.scenarios.createScenarioPipelineRule(ScenarioPipelineRuleCreateRequest(GovernanceOrderFixture.SCENARIO, 1, operation, index + 1, StageAuthorization.current(operation)))
            }
            f.activateSubjectGraph()
            selectedAdoptions = f.sourceAdoptions
        }

        // 真正重开持久元数据，算法与采用均从已保存内容读取；来源采用仍由作者显式选定。
        PlatformDatabase.openSingleInstance(metadata).use { database ->
            val f = GovernanceOrderFixture(database, directory)
            f.sourceAdoptions = selectedAdoptions
            f.build("population_guarded_ratios")
            val publication = f.publication("population_guarded_ratios")
            val dimensionBindings = mapOf("account" to CalculationBindings.Dimension(axis, "account", listOf(accountMapping)))
            val sourceCount = "source_record_count"
            val joinedCount = "joined_bag_rows"
            val knownCount = "eligible_known_amounts"
            val nullCount = "eligible_null_amounts"
            val eligibilityCounts = listOf("eligible_true", "eligible_false", "eligible_unknown")
            val unmatchedCount = "unmatched_bag_rows"
            fun rows(reference: MemberRef, types: Map<String, ScalarValueType>) = Calculation.Scope(Calculation.Members("rows", Calculation.MemberSource(reference), types.keys.map {
                if (it == "account") Calculation.MemberProjection(it, it, Calculation.PublishedDimension(axis)) else Calculation.MemberProjection(it, it, null)
            }, null, emptyMap(), MemberCondition.Unrestricted(), emptyMap(), emptyMap(), emptyMap()), emptyMap(), emptyList())
            val rawLedger = rows(ledgerMember, ledgerTypes)
            val rawAttributes = rows(attributeMember, attributeTypes)
            val completedAmountSymbols = amounts.keys.associateWith { "completed_$it" }
            fun query(empty: Boolean): Calculation.Scope {
                val ledger = Calculation.Scope(rawLedger.root, rawLedger.inputs, listOf(PostFilter(if (empty) ResultLiteral(false) else op(ResultOperator.IS_NOT_NULL, sym("account")))))
                val completedAmounts = Calculation.Scope(Calculation.Input("ledger"), mapOf("ledger" to Calculation.InputScope("ROOT", ledger)), listOf(
                    Regroup(ResultGrouping(listOf("account"), amounts.map { (name, contract) -> sum(name, completedAmountSymbols.getValue(name), contract) } +
                        ResultAggregate("account", ResultAggregation.Count(false, ResultAggregation.NullPolicy.IGNORE), sourceCount, "关联前来源观察行"), null))))
                val bagStages = mutableListOf<PostOperationContract.ValidatedOperation>(
                    JoinInput("attributes", "attr", "LEFT", op(ResultOperator.EQ, sym("record_reference"), sym("attr__record_reference"))),
                    Derive(quantity(1, count), "one_row", "每条LEFT输出贡献"),
                    Derive(choose(op(ResultOperator.IS_NULL, sym("attr__record_reference")), quantity(1, count), quantity(0, count)), "unmatched_row", "实际LEFT未匹配"))
                val aggregateBag = mutableListOf(sum("one_row", joinedCount, count), sum("unmatched_row", unmatchedCount, count))
                ratios.forEach { spec ->
                    val eligible = op(ResultOperator.AND, op(ResultOperator.EQ, sym("attr__${spec.flag}"), ResultTypedLiteral(ScalarValueType.TEXT, "Y")),
                        op(ResultOperator.EQ, sym(spec.counter), quantity(1, count)))
                    bagStages += Derive(choose(eligible, sym("attr__${spec.numerator}"), quantity(0, contracts.getValue(spec.numerator))), "row_${spec.numerator}", "完整CASE保TRUE金额NULL")
                    aggregateBag += sum("row_${spec.numerator}", spec.numerator, contracts.getValue(spec.numerator))
                    if (spec.code == "ratio_a") {
                        eligibilityCounts.zip(listOf(eligible, op(ResultOperator.NOT, eligible), op(ResultOperator.IS_NULL, eligible))).forEach { (name, condition) ->
                            bagStages += Derive(choose(condition, quantity(1, count), quantity(0, count)), "row_$name", "完整AND资格的三值状态")
                            aggregateBag += sum("row_$name", name, count)
                        }
                        bagStages += Derive(choose(op(ResultOperator.AND, eligible, op(ResultOperator.IS_NOT_NULL, sym("attr__raw_a"))), quantity(1, count), quantity(0, count)), "known_row", "资格TRUE且金额已知")
                        bagStages += Derive(choose(op(ResultOperator.AND, eligible, op(ResultOperator.IS_NULL, sym("attr__raw_a"))), quantity(1, count), quantity(0, count)), "null_row", "资格TRUE但金额NULL")
                        aggregateBag += sum("known_row", knownCount, count)
                        aggregateBag += sum("null_row", nullCount, count)
                    }
                }
                bagStages += Regroup(ResultGrouping(listOf("account"), aggregateBag, null))
                val completedBag = Calculation.Scope(Calculation.Input("ledger"), mapOf("ledger" to Calculation.InputScope("ROOT", ledger),
                    "attributes" to Calculation.InputScope("RELATION", rawAttributes)), bagStages)
                val amountContracts = amounts + (sourceCount to count)
                val bagContracts = contributions + mapOf(joinedCount to count, knownCount to count, nullCount to count, unmatchedCount to count) +
                    eligibilityCounts.associateWith { count }
                fun outputs(values: Map<String, MetricValueContract>) = listOf(SetOutputContract("account", SetOutputContract.DimensionSlot("account"))) +
                    values.map { (name, contract) -> SetOutputContract(name, SetOutputContract.Quantity(contract)) }
                fun aligned(name: String, values: Map<String, MetricValueContract>) = Calculation.AlignedInput(Calculation.Scope(Calculation.Relation(name), emptyMap(), emptyList()),
                    mapOf("account" to "account"), values.keys.map { Calculation.AlignedValue(it, it, Calculation.NoMatch.PRESERVE_NULL) })
                val alignment = Calculation(listOf(Calculation.DimensionSlot("account", accountConcept, 1, "account")), emptyList(), emptyList(), listOf(
                    Calculation.RelationSlot("amounts", outputs(amountContracts), listOf("account")), Calculation.RelationSlot("bag", outputs(bagContracts), listOf("account"))), emptyMap(),
                    Calculation.Scope(Calculation.AlignMeasurements(linkedMapOf("amounts" to aligned("amounts", amountContracts), "bag" to aligned("bag", bagContracts)), listOf("account"),
                        Calculation.AlignmentPopulation(Calculation.PopulationKind.BASE, listOf("amounts"))), emptyMap(), emptyList()))
                val basis = Calculation.Scope(Calculation.Compute(alignment, Calculation.ExplicitBindings(CalculationBindings(dimensionBindings, emptyMap(), emptyMap(), emptyMap(), mapOf("amounts" to CalculationBindings.RelationParameter(), "bag" to CalculationBindings.RelationParameter())), emptyMap(), mapOf("amounts" to CalculationBindings.LocalRelation("amounts", completedAmountSymbols + mapOf("account" to "account", sourceCount to sourceCount)),
                        "bag" to CalculationBindings.LocalRelation("bag", (bagContracts.keys + "account").associateWith { it }))), emptyMap()), mapOf("amounts" to Calculation.InputScope("ROOT", completedAmounts), "bag" to Calculation.InputScope("ROOT", completedBag)), emptyList())
                val calls = ratios.map { spec -> Calculation.Call(spec.code, Calculation.Instance(instance(f, spec.code)), spec.code, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()), emptyMap(), mapOf("basis" to CalculationBindings.LocalRelation("completed", mapOf("account" to "account", "numerator" to spec.numerator, "denominator" to spec.denominator)))) }
                val measured = Calculation.Scope(Calculation.Measures(calls, (listOf(axis)).map { Calculation.PublishedDimension(it) }, emptyList(), null, emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()), DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(calls.map { it.callId }), null), mapOf("completed" to Calculation.InputScope("ROOT", basis)), emptyList())
                val detailNames = listOf("account", "base_a", "base_b") + contributions.keys + listOf(sourceCount, joinedCount, knownCount, nullCount) + ratios.map { "metric__${it.code}" }
                val stages = mutableListOf<PostOperationContract.ValidatedOperation>(
                    JoinInput("metrics", "metric", "LEFT", op(ResultOperator.EQ, sym("account"), sym("metric__account"))),
                    Derive(ResultRow(detailNames.map(::sym)), "account_detail", "实际完成分量、独立分母及六个保存公式"),
                    Derive(choose(op(ResultOperator.IS_NULL, sym("base_a")), quantity(1, count), quantity(0, count)), "null_denominator", "NULL分母账户"),
                    Derive(choose(op(ResultOperator.LT, sym("base_a"), quantity(0, amounts.getValue("base_a"))), quantity(1, count), quantity(0, count)), "negative_denominator", "负分母账户"),
                    Derive(choose(op(ResultOperator.EQ, sym("base_a"), quantity(0, amounts.getValue("base_a"))), quantity(1, count), quantity(0, count)), "zero_denominator", "零分母账户"),
                    Regroup(ResultGrouping(emptyList(), listOf(ResultAggregate("account", ResultAggregation.Count(false, ResultAggregation.NullPolicy.IGNORE), "accounts", "完成账户数"),
                        sum(sourceCount, "source_records", count), sum(joinedCount, "joined_rows", count), sum(knownCount, "known_amount_rows", count), sum(nullCount, "null_amount_rows", count),
                        sum(unmatchedCount, "unmatched_rows", count),
                        sum("eligible_true", "true_rows", count), sum("eligible_false", "false_rows", count), sum("eligible_unknown", "unknown_rows", count),
                        sum("null_denominator", "null_denominator_accounts", count), sum("negative_denominator", "negative_denominator_accounts", count), sum("zero_denominator", "zero_denominator_accounts", count),
                        ResultAggregate("account_detail", ResultAggregation.Collect(false), "details", "全局报告保留完整账户结果")), null)))
                return Calculation.Scope(Calculation.Input("basis"), mapOf("basis" to Calculation.InputScope("ROOT", basis), "metrics" to Calculation.InputScope("RELATION", measured)), stages)
            }
            // Query也经过正式Protobuf往返，保存算法由重开的发布提供；全局报告与明细在同一次执行内完成。
            val encoded = ProtoBytes.encode(QueryProto.toProto(f.intent(publication, query(false))))
            val decoded = QueryProto.fromProto(ProtoBytes.decode(encoded, com.olap.prototype.wire.query.QueryIntent.getDefaultInstance()))
            val result = f.execute(publication, decoded.computation).businessResult
            val report = result.columns.map { it.symbol.name }.zip(result.rows.single()).toMap()
            fun scalar(value: ExecutableBusinessResult.Value): String? = when (value) {
                is ExecutableBusinessResult.NullValue -> null
                is ExecutableBusinessResult.ScalarValue -> value.value
                else -> error("expected scalar")
            }
            listOf("accounts" to "5", "source_records" to "5", "joined_rows" to "6", "known_amount_rows" to "3", "null_amount_rows" to "1",
                "unmatched_rows" to "1", "true_rows" to "4", "false_rows" to "1", "unknown_rows" to "1",
                "null_denominator_accounts" to "1", "negative_denominator_accounts" to "1", "zero_denominator_accounts" to "1").forEach { (name, expected) ->
                number(expected, scalar(report.getValue(name)))
            }
            val detail = (report.getValue("details") as ExecutableBusinessResult.ArrayValue).value.associate { value ->
                val fields = (value as ExecutableBusinessResult.RowValue).value
                requireNotNull(scalar(fields.first())) to fields.drop(1).map(::scalar)
            }
            val expected = mapOf(
                "A" to listOf("100", "50", "30", null, "10", "12", "14", "16", "1", "2", "2", "0", "0.3", null, "0.2", "0.24", "0.14", (16.0 / 14.0).toString()),
                "B" to listOf(null, null, null, null, null, null, null, null, "1", "1", "0", "1", null, null, null, null, "0", "0"),
                "C" to listOf("-10", "-5", "5", "1", "2", "3", "4", "5", "1", "1", "1", "0", "-0.5", "-0.1", "-0.4", "-0.6", "0", "1.25"),
                "D" to listOf("0", "0", "0", "0", "0", "0", "0", "0", "1", "1", "0", "0", null, null, null, null, "0", "0"),
                "E" to listOf("20", "10", "0", "0", "0", "0", "0", "0", "1", "1", "0", "0", "0", "0", "0", "0", "0", "0"))
            assertEquals(expected.keys, detail.keys)
            expected.forEach { (account, values) ->
                assertEquals(values.size, detail.getValue(account).size)
                values.zip(detail.getValue(account)).forEachIndexed { index, (wanted, actual) ->
                    if (wanted == null) assertNull(actual, "$account field $index")
                    else { assertNotNull(actual, "$account field $index"); assertEquals(wanted.toDouble(), actual!!.toDouble(), "$account field $index") }
                }
            }
            val empty = f.execute(publication, query(true)).businessResult
            val emptyReport = empty.columns.map { it.symbol.name }.zip(empty.rows.single()).toMap()
            number("0", scalar(emptyReport.getValue("accounts")))
            emptyReport.filterKeys { it != "accounts" }.forEach { (name, value) ->
                assertTrue(value is ExecutableBusinessResult.NullValue, "$name：空总体的SUM/集合保NULL，不造账户或补零")
            }
        }
    }

    @Test
    fun consumesEachInvocationConditionAtItsPrivateExactFactWithoutInheritingTheRoot(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val f = GovernanceOrderFixture(database, directory, orders)
            f.seed()
            val amountDependency = privateFact(f, 1, "private_payment")
            f.events.reviseEvent(GovernanceRevisionRequest(GovernanceOrderFixture.EVENT, 1, EventCreateRequest(
                GovernanceOrderFixture.TOPOLOGY, 1, "paid", "另一个精确支付版本", "trade", null, null,
                EventIdentity(listOf(EventIdentity.LogicUnitRef("order_fact.order_id", 1)),
                    mapOf("order" to listOf(EventIdentity.LogicUnitRef("order_fact.order_id", 1)))), emptyMap(),
                listOf(EventCreateRequest.LogicUnitRefInput("order_fact.destination", 1)),
                listOf(EventCreateRequest.LogicUnitRefInput("order_fact.pay_amount", 1)), "不采用原发布的目的地映射", "golden")))
            f.activate("EVENT", GovernanceOrderFixture.EVENT, 2)
            val otherFact = privateFact(f, 2, "private_payment_v2")
            val selected = createCalculation(f, "selected_amount", conditionalAmount(amountDependency.id))
            val otherEvent = createCalculation(f, "other_event_amount", conditionalAmount(otherFact.id))
            val filter = f.scenarios.createScenarioFilterRef(ScenarioFilterRefCreateRequest(GovernanceOrderFixture.SCENARIO, 1,
                "ANALYSIS_DIMENSION", destination.id, 1, null, listOf(FilterLeafOperator.EQ), false, 1)).ref
            fun condition(value: String, filterId: String = filter.id) = DimensionCondition.Predicate(filterId, destination,
                MemberCondition.Operator.EQ, listOf(Calculation.Scalar(ScalarValueType.TEXT, value)))
            assertThrows(ProducerContractException::class.java) {
                f.adopt(selected.id, code = "fixed_publication_filter", arguments = mapOf("population" to Calculation.Condition(condition("A"))))
            }
            f.adopt(selected.id)
            f.grantMetric(selected.id, order = 2)
            f.adopt(otherEvent.id)
            f.grantMetric(otherEvent.id, order = 3)
            f.activateSubjectGraph()
            f.build("explicit_condition_arguments")
            val publication = f.publication("explicit_condition_arguments")
            val selectedInstance = instance(f, selected.code)
            fun selectedCall(id: String, instance: MetricInstanceRef, condition: DimensionCondition) = invocation(id, instance, id,
                mapOf("population" to Calculation.Condition(condition)))
            val query = measures(listOf(
                invocation("outer_b", instance(f, "pay_amount"), "outer_b"),
                selectedCall("inner_a", selectedInstance, condition("A")),
                selectedCall("inner_b", selectedInstance, condition("B"))), condition("B"))
            val rows = f.execute(publication, query).result.resultFrame.rows
            assertEquals(1, rows.size)
            number("60", rows.single()["outer_b"])
            number("160", rows.single()["inner_a"])
            number("60", rows.single()["inner_b"])

            listOf(
                selectedCall("unapproved", selectedInstance, condition("A", "not_granted")),
                selectedCall("wrong_event", instance(f, otherEvent.code), condition("A")),
            ).forEach { denied ->
                val failure = assertThrows(EngineExecutionException::class.java) { f.execute(publication, measures(listOf(denied))) }
                assertEquals("AUTHORIZE_INPUT", failure.stage)
                assertEquals("EXECUTABLE_QUERY_NOT_PUBLISHED", failure.code)
            }
        }
    }

    private fun relationMean(): MetricCalculation {
        // DuckDB 的除法返回 DOUBLE；输入仍要求精确金额和计数，结果保留原生浮点保证。
        val result = GovernanceOrderFixture.money().let { money -> MetricValueContract(money.unit, MetricValueContract.NativeFloating(), MetricValueContract.BinaryFloat(64),
            mapOf("per_order" to MetricValueContract.PreserveUnit(MetricValueContract.BinaryOperator.DIVIDE, MetricValueContract.Operand.LEFT)), money.overflow) }
        val relation = Calculation.RelationSlot("completed", listOf(
            SetOutputContract("amount", SetOutputContract.Quantity(GovernanceOrderFixture.money())),
            SetOutputContract("orders", SetOutputContract.Quantity(GovernanceOrderFixture.records())),
            SetOutputContract("label", SetOutputContract.Scalar(SetOutputContract.ScalarType.TEXT))), emptyList())
        val calculation = Calculation(emptyList(), emptyList(), emptyList(), listOf(relation), emptyMap(),
            Calculation.Scope(Calculation.Relation("completed"), emptyMap(), listOf(
                PostOperationContract.Derive(ResultExpression.ResultNumeric(ResultExpression.ResultOperation(ResultExpression.ResultOperator.DIVIDE,
                    listOf(ResultExpression.ResultSymbol("amount"), ResultExpression.ResultSymbol("orders"))), result, "per_order"), "mean", "客单价"),
                PostOperationContract.Project(listOf("mean")))))
        return MetricCalculation(calculation, "mean", emptyList(), result)
    }

    private fun conditionalAmount(factId: String): MetricCalculation {
        val parameters = mapOf("population" to Calculation.Parameter(Calculation.ParameterKind.CONDITION, "SOURCE_CONDITION", emptyList(), null, null))
        val call = Calculation.Call("private_amount", Calculation.Fixed(MetricRef(factId, "1")), "value", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Argument("population"), emptyMap(), emptyMap())
        val calculation = Calculation(emptyList(), emptyList(), emptyList(), emptyList(), parameters,
            Calculation.Scope(Calculation.Measures(listOf(call), emptyList(), emptyList(), null, emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()), DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(listOf(call.callId)), null), emptyMap(), emptyList()))
        return MetricCalculation(calculation, "value", emptyList(), GovernanceOrderFixture.money())
    }

    private fun createCalculation(f: GovernanceOrderFixture, code: String, definition: MetricCalculation) =
        f.metrics.createMetricCalculation(MetricCalculationCreateRequest(code, code, "trade", definition, "显式计算实参",
            MetricBusinessMeaning("按显式输入人口计算支付结果", "元", "调用选择的支付订单", emptyList()), "golden")).metric.also {
            f.activate("METRIC", it.id)
        }

    private fun privateFact(f: GovernanceOrderFixture, eventVersion: Long, code: String) = f.metrics.createMetric(MetricCreateRequest(
        code = code,
        displayName = code,
        owner = "trade",
        definition = MetricDefinition.FactMeasure(
            event = com.olap.prototype.event.EventRef(GovernanceOrderFixture.EVENT, eventVersion),
            input = GovernanceOrderFixture.ref("order_fact.pay_amount"),
            aggregation = MetricAggregation.SUM,
            distinct = false,
            privatePredicate = null,
            roles = emptyMap(),
            snapshotRule = null,
            valueContract = GovernanceOrderFixture.money(),
        ),
        description = "不公开采用的计算依赖",
        businessMeaning = GovernanceOrderFixture.amountMeaning(),
        createdBy = "golden"
    )).metric.also { f.activate("METRIC", it.id) }

    private fun instance(f: GovernanceOrderFixture, code: String) = f.adoptions.metricAdoption(MetricAdoption.identity(GovernanceOrderFixture.SUBJECT, code), 1).instance
    private fun invocation(id: String, instance: MetricInstanceRef, alias: String, arguments: Map<String, Calculation.Value> = emptyMap()) =
        Calculation.Call(id, Calculation.Instance(instance), alias, emptyMap(), emptyMap(), emptyMap(), emptyMap(), arguments, Calculation.Condition(DimensionCondition.Unrestricted()), emptyMap(), emptyMap())
    private fun measures(calls: List<Calculation.Call>, sourceCondition: DimensionCondition = DimensionCondition.Unrestricted()) =
        Calculation.Scope(Calculation.Measures(calls, emptyList(), emptyList(), null, emptyMap(), Calculation.Condition(sourceCondition), DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(calls.map { it.callId }), null), emptyMap(), emptyList())
    private fun number(expected: String, actual: Any?) { assertNotNull(actual); assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(actual.toString()))) }

    companion object {
        private val destination = ScopedInputContract.DimensionRef("${GovernanceOrderFixture.SUBJECT}.destination", "1", "destination")
        private val orders = listOf("INSERT INTO main.orders VALUES ('1',100,'A','2026-07-21','c1'),('2',40,'B','2026-07-21','c2'),('3',60,'A','2026-07-22','c3'),('4',20,'B','2026-08-01','c1')")
    }
}
