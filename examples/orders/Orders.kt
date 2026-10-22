import com.olap.prototype.calculation.*
import com.olap.prototype.connector.*
import com.olap.prototype.connector.loader.ConnectorPlugins
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.member.MemberShape
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.*
import com.olap.prototype.query.PostOperationContract.Derive
import com.olap.prototype.query.PostOperationContract.JoinInput
import com.olap.prototype.query.PostOperationContract.PostFilter
import com.olap.prototype.query.PostOperationContract.Project
import com.olap.prototype.query.PostOperationContract.ResultAggregate
import com.olap.prototype.query.PostOperationContract.ValidatedOperation
import com.olap.prototype.query.ResultExpression.*
import com.olap.prototype.time.TemporalWindow
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.contract.JdbcConnectionSpec
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.Driver
import java.util.Properties

/** 准备真实订单数据和作者定义；治理、发布、查询由外部 MCP 客户端完成。 */
object Orders {
    private const val SOURCE = "example-orders"
    private val CUSTOMER_SLOT = Calculation.DimensionSlot("customer", "example.customer", 1, "customer")
    private val TIME_SLOT = Calculation.DimensionSlot("time", "example.business_date", 1, "business_time")
    private val PERIOD = mapOf("window" to Calculation.Parameter(Calculation.ParameterKind.RANGE, "DATE", emptyList(), null, null))

    @JvmStatic
    fun main(arguments: Array<String>) {
        val args = arguments.toMutableList()
        val option = args.indexOf("--connectors")
        require(option >= 0 && option + 1 < args.size && args.count { it == "--connectors" } == 1) {
            "用法：Orders --connectors <配置> prepare|advance-source <输入目录>"
        }
        val configuration = Path.of(args.removeAt(option + 1))
        args.removeAt(option)
        require(configuration.isAbsolute) { "连接器配置路径必须为绝对路径" }
        val manifestPaths = OlapJsonUtils.mapper().readTree(Files.readAllBytes(configuration))
        require(manifestPaths.isArray && manifestPaths.all { it.isTextual && Path.of(it.textValue()).isAbsolute }) {
            "连接器配置必须包含绝对 manifest 路径数组"
        }
        ConnectorPlugins.load(manifestPaths.map { Path.of(it.textValue()) }).use { deployment ->
            val selected = deployment.registry.references().filter { it.id == "duckdb" }
            require(selected.size == 1) { "本例需要明确部署唯一 DuckDB 连接器" }
            require(args.size == 2 && args[0] in setOf("prepare", "advance-source")) {
                "用法：Orders --connectors <配置> prepare|advance-source <输入目录>"
            }
            val output = Path.of(args[1]).toAbsolutePath().normalize()
            val connector = deployment.registry.require(selected.single())
            if (args[0] == "advance-source") {
                require(Files.isRegularFile(output.resolve("source.duckdb"))) { "明确提供已准备的业务输入目录" }
                openSource(output, connector).use { connection ->
                    connection.createStatement().use { it.execute("INSERT INTO payments VALUES ('P5','O2',10,DATE '2026-01-06')") }
                }
                write(output.resolve("new-payment.json"), mapOf("paymentId" to "P5", "orderId" to "O2", "amount" to "10", "expectedOriginalNet" to "185"))
            } else {
                Files.createDirectories(output.parent)
                Files.createDirectory(output)
                Files.createDirectories(output.resolve("temporary"))
                System.setProperty("java.io.tmpdir", output.resolve("temporary").toString())
                prepareSource(output, connector)
                authorDefinitions(output)
            }
        }
    }

    private fun source(output: Path, connector: ResolvedConnector) = JdbcConnectionSpec(SOURCE, connector.ref, "duckdb",
        "jdbc:duckdb:${output.resolve("source.duckdb")}", null, null, "org.duckdb.DuckDBDriver")

    private fun openSource(output: Path, connector: ResolvedConnector): java.sql.Connection {
        val jdbc = (connector.access as JdbcAccess).jdbc
        val driver = Class.forName("org.duckdb.DuckDBDriver", true, jdbc.javaClass.classLoader)
            .getDeclaredConstructor().newInstance() as Driver
        return driver.connect(source(output, connector).jdbcUrl, Properties())
    }

    private fun prepareSource(output: Path, connector: ResolvedConnector) {
        // SQL 只建立业务输入；业务查询由产品编译器生成。
        openSource(output, connector).use { connection ->
            connection.createStatement().use { sql ->
                sql.execute("CREATE TABLE orders(order_id VARCHAR PRIMARY KEY,customer VARCHAR,ordered_date DATE)")
                sql.execute("CREATE TABLE payments(payment_id VARCHAR PRIMARY KEY,order_id VARCHAR NOT NULL,amount DECIMAL(18,2),paid_date DATE)")
                sql.execute("CREATE TABLE refunds(refund_id VARCHAR PRIMARY KEY,order_id VARCHAR NOT NULL,amount DECIMAL(18,2),refunded_date DATE)")
                sql.execute("CREATE TABLE fees(fee_id VARCHAR PRIMARY KEY,order_id VARCHAR NOT NULL,amount DECIMAL(18,2),charged_date DATE)")
                sql.execute("""
                    INSERT INTO orders VALUES
                    ('P-A','A',DATE '2025-12-01'),('P-B1','B',DATE '2025-12-02'),('P-B2','B',DATE '2025-12-03'),
                    ('O1','A',DATE '2026-01-05'),('O2','A',DATE '2026-01-06'),('O3','B',DATE '2026-01-07'),
                    ('UNKNOWN',NULL,NULL)
                """.trimIndent())
                sql.execute("""
                    INSERT INTO payments VALUES
                    ('PP-A','P-A',100,DATE '2025-12-01'),('PP-B1','P-B1',100,DATE '2025-12-02'),('PP-B2','P-B2',70,DATE '2025-12-03'),
                    ('P1','O1',60,DATE '2026-01-05'),('P2','O1',40,DATE '2026-01-05'),
                    ('P3','O2',50,DATE '2026-01-06'),('P4','O3',90,DATE '2026-01-07')
                """.trimIndent())
                sql.execute("""
                    INSERT INTO refunds VALUES
                    ('PR-A','P-A',20,DATE '2025-12-20'),('PR-B1','P-B1',10,DATE '2025-12-20'),('PR-B2','P-B2',10,DATE '2025-12-21'),
                    ('R1','O1',20,DATE '2026-01-20'),('R2','O1',10,DATE '2026-02-05'),('R3','O2',5,DATE '2026-02-07'),
                    ('R4','O3',10,DATE '2026-01-21'),('R5','O3',20,DATE '2026-02-08')
                """.trimIndent())
                sql.execute("INSERT INTO fees VALUES ('F1','O1',5,DATE '2026-01-31'),('F2','O3',0,DATE '2026-01-31')")
            }
        }
        write(output.resolve("data-sources.json"), listOf(source(output, connector).source()))
    }

    private fun authorDefinitions(output: Path) {
        val definitions = linkedMapOf<String, MetricCalculation>()
        listOf("gross", "refund", "orders", "fee").forEach { key ->
            definitions[key] = aggregateDefinition(MetricRef("authored.fact", "1"), if (key == "orders") countContract() else amountContract())
        }
        definitions["net"] = netDefinition(false)
        definitions["net_v2"] = netDefinition(true)
        definitions["net_total"] = totalDefinition("net", amountContract())
        definitions["qualified_total"] = qualifiedDefinition()
        definitions["growth"] = growthDefinition()
        listOf("order_mean", "customer_mean", "unweighted", "weighted").forEach { key ->
            definitions[key] = MetricCalculation(statisticsDefinition(key), key, emptyList(), averageContract())
        }
        val mapper = OlapJsonUtils.mapper()
        val wire = definitions.mapValues { (_, definition) ->
            mapper.readTree(mapper.writerFor(MetricDefinition::class.java).writeValueAsBytes(definition))
        }
        write(output.resolve("authored-definitions.json"), mapOf("definitions" to wire, "qualification" to qualification(),
            "money" to amountContract(), "count" to countContract()))
    }

    private fun scope(root: Calculation.Root, vararg stages: ValidatedOperation) = Calculation.Scope(root, emptyMap(), stages.toList())

    private fun measure(slot: String, alias: String, grouped: Boolean, period: Boolean) = scope(Calculation.Measures(
        listOf(Calculation.Call(slot, Calculation.Measurement(slot), alias, emptyMap(), emptyMap(), emptyMap(), emptyMap(), if (period) mapOf("window" to Calculation.Argument("window")) else emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()))),
        if (grouped) listOf("customer") else emptyList(), emptyList(), null, null, emptyMap(), Calculation.Population(listOf(slot)), null))

    private fun slot(name: String, contract: MetricValueContract, grouped: Boolean, period: Boolean) = Calculation.MeasurementSlot(
        name, if (period) PERIOD else emptyMap(), if (grouped) listOf(CUSTOMER_SLOT) else emptyList(), emptyList(), contract)

    private fun aggregateDefinition(fact: MetricRef, contract: MetricValueContract) = MetricCalculation(
        Calculation(listOf(CUSTOMER_SLOT, TIME_SLOT), emptyList(), emptyList(), emptyList(), PERIOD, scope(Calculation.Measures(
            listOf(Calculation.Call("fact", Calculation.Fixed(fact), "value", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()))),
            listOf("customer"), emptyList(), "time", Calculation.Argument("window"), emptyMap(), Calculation.Population(listOf("fact")), null))),
        "value", listOf("customer"), contract)

    private fun aligned(input: Calculation.Scope, symbol: String, alias: String, grouped: Boolean) = Calculation.AlignedInput(
        input, if (grouped) mapOf("customer" to "customer") else emptyMap(),
        listOf(Calculation.AlignedValue(symbol, alias, Calculation.NoMatch.PRESERVE_NULL)))

    private fun align(inputs: Map<String, Calculation.AlignedInput>, grouped: Boolean, base: String) = Calculation.AlignMeasurements(
        inputs, if (grouped) listOf("customer") else emptyList(), Calculation.AlignmentPopulation(Calculation.PopulationKind.BASE, listOf(base)))

    private fun symbol(name: String): ResultExpression = ResultSymbol(name)
    private fun operation(operator: ResultOperator, left: ResultExpression, right: ResultExpression): ResultExpression = ResultOperation(operator, listOf(left, right))
    private fun numeric(alias: String, expression: ResultExpression, contract: MetricValueContract, equation: String?) =
        Derive(ResultNumeric(expression, contract, equation), alias, alias)

    private fun netDefinition(fees: Boolean): MetricCalculation {
        val inputs = linkedMapOf(
            "gross" to aligned(measure("gross", "value", true, true), "value", "gross", true),
            "refund" to aligned(measure("refund", "value", true, true), "value", "refunded", true),
        )
        val slots = mutableListOf(slot("gross", amountContract(), true, true), slot("refund", amountContract(), true, true))
        var expression = operation(ResultOperator.SUBTRACT, symbol("gross"), symbol("refunded"))
        if (fees) {
            inputs["fee"] = aligned(measure("fee", "value", true, true), "value", "fee", true)
            slots += slot("fee", amountContract(), true, true)
            expression = operation(ResultOperator.SUBTRACT, expression, symbol("fee"))
        }
        return MetricCalculation(Calculation(listOf(CUSTOMER_SLOT), emptyList(), slots, emptyList(), PERIOD, scope(align(inputs, true, "gross"), numeric("value", expression, amountContract(), null), Project(listOf("customer", "value")))),
            "value", listOf("customer"), amountContract())
    }

    private fun reduction(input: String, aggregation: ResultAggregation, alias: String) =
        ResultAggregate(input, aggregation, alias, alias)

    private fun totalDefinition(measurement: String, contract: MetricValueContract) = MetricCalculation(
        Calculation(listOf(CUSTOMER_SLOT), emptyList(), listOf(slot(measurement, contract, true, true)), emptyList(), PERIOD, scope(Calculation.ObservationStatistics(measure(measurement, "measured", true, true), Calculation.ObjectsBy(listOf("customer")),
            emptyList(), listOf(Calculation.Reduction(reduction("measured", ResultAggregation.Sum(contract, ResultAggregation.NullPolicy.IGNORE), "value")))))), "value", emptyList(), contract)

    private fun qualification(): Calculation {
        val inputs = linkedMapOf(
            "net" to aligned(measure("net", "value", true, true), "value", "net", true),
            "orders" to aligned(measure("orders", "value", true, true), "value", "orders", true),
        )
        val condition = operation(ResultOperator.AND,
            operation(ResultOperator.GTE, symbol("orders"), ResultQuantityLiteral(BigDecimal("2"), countContract())),
            operation(ResultOperator.GTE, symbol("net"), ResultQuantityLiteral(BigDecimal("100"), amountContract())))
        return Calculation(listOf(CUSTOMER_SLOT), emptyList(), listOf(slot("net", amountContract(), true, true), slot("orders", countContract(), true, true)), emptyList(), PERIOD, scope(align(inputs, true, "net"), PostFilter(condition), Project(listOf("customer"))))
    }

    private fun eligibleSlot(name: String) = Calculation.MemberSlot(name, "客户", "customer", MemberShape.UNIQUE_MEMBERS,
        mapOf("customer" to Calculation.MemberDimension("customer", null)), null)

    private fun people(slot: String) = scope(Calculation.Members(slot, slot, listOf(Calculation.MemberProjection("customer", "customer")),
        null, emptyMap(), MemberCondition.Unrestricted()))

    private fun qualifiedTotal(measured: Calculation.Scope): Calculation.Scope {
        val qualified = Calculation.Scope(Calculation.Input("net_rows"), linkedMapOf(
            "net_rows" to Calculation.InputScope("ROOT", measured), "eligible" to Calculation.InputScope("RELATION", people("eligible"))),
            listOf(JoinInput("eligible", "chosen", "SEMI", operation(ResultOperator.EQ, symbol("customer"), symbol(ScopedInputContract.metricSymbol("chosen", "customer"))))))
        return scope(Calculation.ObservationStatistics(qualified, Calculation.ObjectsBy(listOf("customer")), emptyList(),
            listOf(Calculation.Reduction(reduction("measured", ResultAggregation.Sum(amountContract(), ResultAggregation.NullPolicy.IGNORE), "value")))))
    }

    private fun qualifiedDefinition() = MetricCalculation(Calculation(listOf(CUSTOMER_SLOT), listOf(eligibleSlot("eligible")), listOf(slot("net", amountContract(), true, true)), emptyList(), PERIOD, qualifiedTotal(measure("net", "measured", true, true))), "value", emptyList(), amountContract())

    private fun averageContract(): MetricValueContract {
        val money = (amountContract().unit as MetricValueContract.Fixed).identity
        return MetricValueContract(MetricValueContract.Fixed(money), MetricValueContract.Approximate("-1000000", "1000000", "0.000000001", "0.000000001"),
            MetricValueContract.BinaryFloat(64), mapOf(
                "multiply" to MetricValueContract.FixedBinary(MetricValueContract.BinaryOperator.MULTIPLY, money, MetricValueContract.ONE, money),
                "divide" to MetricValueContract.FixedBinary(MetricValueContract.BinaryOperator.DIVIDE, money, MetricValueContract.ONE, money)), MetricValueContract.Overflow.FAIL)
    }

    private fun ratioContract() = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE),
        MetricValueContract.Approximate("-1000000", "1000000", "0.000000001", "0.000000001"), MetricValueContract.BinaryFloat(64),
        mapOf("ratio" to MetricValueContract.SameUnitRatio()), MetricValueContract.Overflow.FAIL)

    private fun floating(value: ResultExpression): ResultExpression = ResultCast(value, SemanticExpression.ValueType.DOUBLE, null, null, MetricValueContract.Rounding.NONE)

    private fun netAt(window: Calculation.Range) = scope(Calculation.Measures(
        listOf(Calculation.Call("net", Calculation.Measurement("net"), "measured", emptyMap(), emptyMap(), emptyMap(), emptyMap(), mapOf("window" to window), Calculation.Condition(DimensionCondition.Unrestricted()))),
        listOf("customer"), emptyList(), null, null, emptyMap(), Calculation.Population(listOf("net")), null))

    private fun growthDefinition(): MetricCalculation {
        val inputs = linkedMapOf(
            "current" to aligned(qualifiedTotal(netAt(period("2026-01-01", "2026-02-01"))), "value", "current", false),
            "prior" to aligned(qualifiedTotal(netAt(period("2025-12-01", "2026-01-01"))), "value", "prior", false),
        )
        val ratio = operation(ResultOperator.DIVIDE, floating(operation(ResultOperator.SUBTRACT, symbol("current"), symbol("prior"))), floating(symbol("prior")))
        return MetricCalculation(Calculation(listOf(CUSTOMER_SLOT), listOf(eligibleSlot("eligible_source"), eligibleSlot("eligible")), listOf(slot("net", amountContract(), true, true)), emptyList(), emptyMap(), Calculation.Scope(align(inputs, false, "current"),
            mapOf("fixed_people" to Calculation.InputScope("ROOT", people("eligible_source"))),
            listOf(numeric("value", ratio, ratioContract(), "ratio"), Project(listOf("value"))))), "value", emptyList(), ratioContract())
    }

    private fun statisticsDefinition(outputName: String): Calculation {
        val inputs = linkedMapOf(
            "gross" to aligned(measure("gross", "value", true, true), "value", "gross", true),
            "orders" to aligned(measure("orders", "value", true, true), "value", "orders", true),
        )
        val observations = scope(align(inputs, true, "gross"), numeric("price", operation(ResultOperator.DIVIDE,
            floating(symbol("gross")), floating(symbol("orders"))), averageContract(), "divide"))
        val stats = Calculation.ObservationStatistics(observations, Calculation.ObjectsBy(listOf("customer")), emptyList(), listOf(
            Calculation.Reduction(reduction("gross", ResultAggregation.Sum(amountContract(), ResultAggregation.NullPolicy.IGNORE), "gross_total")),
            Calculation.Reduction(reduction("orders", ResultAggregation.Sum(countContract(), ResultAggregation.NullPolicy.IGNORE), "order_total")),
            Calculation.Reduction(reduction("gross", ResultAggregation.Average(averageContract(), ResultAggregation.NullPolicy.IGNORE, MetricValueContract.Rounding.NONE), "customer_mean")),
            Calculation.Reduction(reduction("price", ResultAggregation.Average(averageContract(), ResultAggregation.NullPolicy.IGNORE, MetricValueContract.Rounding.NONE), "unweighted")),
            Calculation.WeightedMean("price", "orders", "weighted", "按真实订单数加权客户均价", averageContract(), countContract(),
                averageContract(), "multiply", "divide", "IGNORE_PAIR"),
        ))
        return Calculation(listOf(CUSTOMER_SLOT), emptyList(), listOf(slot("gross", amountContract(), true, true), slot("orders", countContract(), true, true)), emptyList(), PERIOD, scope(stats, numeric("order_mean", operation(ResultOperator.DIVIDE, floating(symbol("gross_total")), floating(symbol("order_total"))),
            averageContract(), "divide"), Project(listOf(outputName))))
    }

    private fun period(start: String, end: String) = Calculation.Range(TemporalWindow(TemporalWindow.ValueType.DATE, TemporalWindow.BOUNDARY, start, end))
    private fun amountContract() = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.UnitIdentity("iso4217", "CNY", 1)),
        MetricValueContract.Exact(), MetricValueContract.Decimal(38, 2, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
    private fun countContract() = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(),
        MetricValueContract.Integer(64), emptyMap(), MetricValueContract.Overflow.FAIL)

    private fun write(path: Path, value: Any) {
        Files.write(path, OlapJsonUtils.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(value), StandardOpenOption.CREATE_NEW)
    }
}
