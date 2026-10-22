import com.olap.prototype.calculation.*
import com.olap.prototype.connector.*
import com.olap.prototype.connector.loader.ConnectorPlugins
import com.olap.prototype.member.*
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.*
import com.olap.prototype.query.PostOperationContract.CalendarAxis
import com.olap.prototype.query.PostOperationContract.Derive
import com.olap.prototype.query.PostOperationContract.JoinInput
import com.olap.prototype.query.PostOperationContract.ObserveWindow
import com.olap.prototype.query.PostOperationContract.ObservationDomain
import com.olap.prototype.query.PostOperationContract.OrderKey
import com.olap.prototype.query.PostOperationContract.PostFilter
import com.olap.prototype.query.PostOperationContract.Project
import com.olap.prototype.query.PostOperationContract.Regroup
import com.olap.prototype.query.PostOperationContract.ResultAggregate
import com.olap.prototype.query.PostOperationContract.ResultGrouping
import com.olap.prototype.query.PostOperationContract.SelectVersions
import com.olap.prototype.query.PostOperationContract.SnapshotSelection
import com.olap.prototype.query.PostOperationContract.VersionTies
import com.olap.prototype.query.ResultExpression.*
import com.olap.prototype.time.TemporalWindow
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.contract.JdbcConnectionSpec
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Driver
import java.util.Properties

/** 原始订阅数据与 typed 作者输入；业务资产操作由外部 MCP 客户端承担。 */
object Subscriptions {
    private val YUAN = MetricValueContract.UnitIdentity("iso4217", "CNY", 1)
    private val CENTS = MetricValueContract.UnitIdentity("example-subscriptions", "CNY-cent", 1)

    @JvmStatic
    fun main(arguments: Array<String>) {
        val args = arguments.toMutableList()
        val option = args.indexOf("--connectors")
        require(option >= 0 && option + 1 < args.size && args.count { it == "--connectors" } == 1) {
            "用法：Subscriptions --connectors <配置> prepare <新输入目录>"
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
            require(args.size == 2 && args[0] == "prepare") { "用法：Subscriptions --connectors <配置> prepare <新输入目录>" }
            val output = Path.of(args[1]).toAbsolutePath().normalize()
            Files.createDirectories(output.parent)
            Files.createDirectory(output)
            Files.createDirectories(output.resolve("temporary"))
            System.setProperty("java.io.tmpdir", output.resolve("temporary").toString())
            prepareSource(output, deployment.registry.require(selected.single()))
            authorDefinitions(output)
        }
    }

    private fun prepareSource(output: Path, connector: ResolvedConnector) {
        val source = JdbcConnectionSpec("example-subscriptions", connector.ref, "duckdb",
            "jdbc:duckdb:${output.resolve("source.duckdb")}", null, null, "org.duckdb.DuckDBDriver")
        val jdbc = (connector.access as JdbcAccess).jdbc
        val driver = Class.forName("org.duckdb.DuckDBDriver", true, jdbc.javaClass.classLoader)
            .getDeclaredConstructor().newInstance() as Driver
        driver.connect(source.jdbcUrl, Properties()).use { connection ->
            connection.createStatement().use { sql ->
                // 只建立未经归约的业务输入，不预先计算迁移存量、窗口或目的地分摊。
                sql.execute("CREATE TABLE legacy_stock(id INTEGER PRIMARY KEY, subscription VARCHAR NOT NULL, observed DATE NOT NULL, amount DECIMAL(18,2))")
                sql.execute("INSERT INTO legacy_stock VALUES (1,'S1','2026-03-31',10000),(2,'S2','2026-03-31',5000),(3,'S1','2026-04-30',14000)")
                sql.execute("CREATE TABLE current_stock(id INTEGER PRIMARY KEY, subscription VARCHAR NOT NULL, observed DATE NOT NULL, amount DECIMAL(18,2))")
                sql.execute("INSERT INTO current_stock VALUES (11,'S1','2026-04-30',150),(12,'S2','2026-04-30',50)")
                sql.execute("CREATE TABLE region_history(id INTEGER PRIMARY KEY, subscription VARCHAR NOT NULL, observed DATE NOT NULL, region VARCHAR NOT NULL)")
                sql.execute("INSERT INTO region_history VALUES (21,'S1','2026-03-01','东'),(22,'S1','2026-04-01','南'),(23,'S2','2026-03-01','东')")
                sql.execute("CREATE TABLE usage_events(id INTEGER PRIMARY KEY, observed DATE NOT NULL, tenant VARCHAR NOT NULL, person VARCHAR NOT NULL)")
                sql.execute("INSERT INTO usage_events VALUES (31,'2026-03-01','T1','D'),(32,'2026-03-02','T1','A'),(33,'2026-03-02','T1','B'),(34,'2026-03-08','T1','A'),(35,'2026-03-08','T1','C')")
                sql.execute("CREATE TABLE calendar_days(id INTEGER PRIMARY KEY, observed DATE UNIQUE NOT NULL)")
                sql.execute("INSERT INTO calendar_days VALUES (1,'2026-03-01'),(2,'2026-03-02'),(3,'2026-03-03'),(4,'2026-03-04'),(5,'2026-03-05'),(6,'2026-03-06'),(7,'2026-03-07'),(8,'2026-03-08')")
                sql.execute("CREATE TABLE subscription_destinations(id INTEGER PRIMARY KEY, subscription VARCHAR NOT NULL, destination VARCHAR NOT NULL, UNIQUE(subscription,destination))")
                sql.execute("INSERT INTO subscription_destinations VALUES (41,'S1','东'),(42,'S1','西'),(43,'S2','东')")
                sql.execute("CREATE TABLE subscription_charges(id INTEGER PRIMARY KEY, subscription VARCHAR NOT NULL, observed DATE NOT NULL, amount DECIMAL(18,2) NOT NULL)")
                sql.execute("INSERT INTO subscription_charges VALUES (51,'S1','2026-04-30',150),(52,'S2','2026-04-30',50)")
            }
        }
        write(output.resolve("data-sources.json"), listOf(source.source()))
    }

    private fun migratedStock(axes: Map<String, Calculation.DimensionSlot>, legacy: MemberResult, current: MemberResult): MemberSourceDefinition.Computed {
        val conversion = MetricValueContract(MetricValueContract.Fixed(YUAN), MetricValueContract.Exact(), balance().result,
            mapOf("cents_to_yuan" to MetricValueContract.Scale(MetricValueContract.BinaryOperator.MULTIPLY, CENTS, YUAN, "0.01")),
            MetricValueContract.Overflow.FAIL)
        val factor = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(),
            MetricValueContract.Decimal(3, 2, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
        val old = Calculation.Scope(members("legacy", legacy), emptyMap(), listOf(
            PostFilter(op(ResultOperator.LT, symbol("day"), literalDate("2026-04-01"))),
            Derive(ResultNumeric(op(ResultOperator.MULTIPLY, symbol("amount"), ResultQuantityLiteral(BigDecimal("0.01"), factor)),
                conversion, "cents_to_yuan"), "value", "明确从分换为元"),
        ))
        val fresh = Calculation.Scope(members("current", current), emptyMap(), listOf(
            PostFilter(op(ResultOperator.GTE, symbol("day"), literalDate("2026-04-01"))), Derive(symbol("amount"), "value", "新系统元存量"),
        ))
        val body = Calculation(listOf(axes.getValue("subscription"), axes.getValue("day")), listOf(slot("legacy", legacy), slot("current", current)), emptyList(), emptyList(), asOfParameters(), Calculation.Scope(Calculation.SetCombination(Calculation.SetKind.UNION, true,
            listOf(Calculation.SetProjection("legacy", listOf("subscription", "day", "value")), Calculation.SetProjection("current", listOf("subscription", "day", "value"))),
            listOf(SetOutputContract("subscription", SetOutputContract.DimensionSlot("subscription")), SetOutputContract("day", SetOutputContract.DimensionSlot("day")),
                SetOutputContract("value", SetOutputContract.Quantity(balance())))),
            linkedMapOf("legacy" to Calculation.InputScope("ROOT", old), "current" to Calculation.InputScope("ROOT", fresh)),
            listOf(SelectVersions(listOf("subscription"), listOf(OrderKey("day", "DESC", "LAST")), VersionTies.FAIL,
                SnapshotSelection("day", Calculation.Argument("as_of"))))))
        return computed(body, mapOf("subscription" to "subscription", "day" to "day"), mapOf("value" to balance()), listOf("subscription"),
            "先按2026-04-01来源权威切换并换算单位，再按订阅选择截至as_of的最后版本；同日并列硬拒")
    }

    private fun regionAt(axes: Map<String, Calculation.DimensionSlot>, regions: MemberResult): MemberSourceDefinition.Computed {
        val body = Calculation(listOf(axes.getValue("subscription"), axes.getValue("day"), axes.getValue("region")), listOf(slot("regions", regions)), emptyList(), emptyList(), asOfParameters(), Calculation.Scope(members("regions", regions), emptyMap(), listOf(
            PostFilter(op(ResultOperator.LTE, symbol("day"), ResultArgument("as_of", ScalarValueType.DATE))),
            SelectVersions(listOf("subscription"), listOf(OrderKey("day", "DESC", "LAST")), VersionTies.FAIL, null),
        )))
        return computed(body, mapOf("subscription" to "subscription", "day" to "day", "region" to "region"), emptyMap(), listOf("subscription"),
            "历史归属独立在同一as_of选择；区域不是当前静态属性，也不先筛区域")
    }

    private fun historicalStock(axes: Map<String, Calculation.DimensionSlot>, stock: MemberResult, regions: MemberResult): MemberSourceDefinition.Computed {
        val body = Calculation(listOf(axes.getValue("subscription"), axes.getValue("day"), axes.getValue("region")), listOf(slot("stock", stock), slot("regions", regions)), emptyList(), emptyList(), asOfParameters(), Calculation.Scope(members("stock", stock),
            mapOf("history" to Calculation.InputScope("RELATION", Calculation.Scope(members("regions", regions), emptyMap(), emptyList()))),
            listOf(JoinInput("history", "history", "INNER", op(ResultOperator.EQ, symbol("subscription"), symbol("history__subscription"))),
                Project(listOf("subscription", "day", "value", "history__region")))))
        return computed(body, mapOf("subscription" to "subscription", "day" to "day", "region" to "history__region"), mapOf("value" to balance()),
            listOf("subscription"), "真实存量先选版本，区域随后按同历史时点关联；缺少归属不推断、不拿旧区域冒充")
    }

    private fun stockMetric(axes: Map<String, Calculation.DimensionSlot>, source: MemberResult): MetricCalculation {
        val parameters = asOfParameters() + ("groups" to Calculation.Parameter(Calculation.ParameterKind.GROUPING, "SEMANTIC_DIMENSIONS",
            listOf(axes.getValue("region"), axes.getValue("subscription")), null, null))
        val body = Calculation(listOf(axes.getValue("subscription"), axes.getValue("day"), axes.getValue("region")), listOf(slot("records", source)), emptyList(), emptyList(), parameters, Calculation.Scope(members("records", source), emptyMap(), listOf(Regroup(ResultGrouping(listOf("\$group:groups"),
            listOf(ResultAggregate("value", ResultAggregation.Sum(balance(), ResultAggregation.NullPolicy.IGNORE), "balance", "历史时点存量（元）")), null)))))
        return MetricCalculation(body, "balance", listOf("\$group:groups"), balance())
    }

    private fun usageMetric(axes: Map<String, Calculation.DimensionSlot>, usage: MemberResult, calendar: MemberResult): MetricCalculation {
        val body = Calculation(listOf(axes.getValue("day")), listOf(slot("calendar", calendar), slot("usage", usage)), emptyList(), emptyList(), emptyMap(), Calculation.Scope(Calculation.Input("calendar"), calendarInputs(usage, calendar), listOf(
            JoinInput("usage", "usage", "LEFT", op(ResultOperator.EQ, symbol("day"), symbol("usage__day"))),
            ObserveWindow(listOf("usage__tenant", "usage__person"), emptyList(), "rolling", "七日完整对象去重", listOf(OrderKey("day", "ASC", "LAST")),
                ResultAggregation.Count(true, ResultAggregation.NullPolicy.IGNORE), sevenDays(), ObservationDomain.CALENDAR, "day",
                dateRange("2026-03-02", "2026-03-09"), dateRange("2026-03-08", "2026-03-09"), CalendarAxis("calendar", "day")),
            Regroup(ResultGrouping(listOf("day"), listOf(ResultAggregate("rolling", ResultAggregation.Maximum(ResultAggregation.NullPolicy.IGNORE), "people", "七日使用对象数")), null)),
        )))
        return MetricCalculation(body, "people", listOf("day"), count())
    }

    private fun dailyMeanMetric(axes: Map<String, Calculation.DimensionSlot>, usage: MemberResult, calendar: MemberResult): MetricCalculation {
        val mean = average(MetricValueContract.ONE)
        val body = Calculation(listOf(axes.getValue("day")), listOf(slot("calendar", calendar), slot("usage", usage)), emptyList(), emptyList(), emptyMap(), Calculation.Scope(Calculation.Input("calendar"), calendarInputs(usage, calendar), listOf(
            JoinInput("usage", "usage", "LEFT", op(ResultOperator.EQ, symbol("day"), symbol("usage__day"))),
            Regroup(ResultGrouping(listOf("day"), listOf(ResultAggregate("usage__id", ResultAggregation.Count(false, ResultAggregation.NullPolicy.IGNORE), "daily_events", "当日实际使用事件数，未匹配事件为零")), null)),
            ObserveWindow(listOf("daily_events"), emptyList(), "daily_mean", "完整七个公历日的使用事件日均", listOf(OrderKey("day", "ASC", "LAST")),
                ResultAggregation.Average(mean, ResultAggregation.NullPolicy.IGNORE, MetricValueContract.Rounding.NONE), sevenDays(), ObservationDomain.CALENDAR, "day",
                dateRange("2026-03-02", "2026-03-09"), dateRange("2026-03-08", "2026-03-09"), CalendarAxis("calendar", "day")),
            Project(listOf("day", "daily_mean")),
        )))
        return MetricCalculation(body, "daily_mean", listOf("day"), mean)
    }

    private fun calendarInputs(usage: MemberResult, calendar: MemberResult) = linkedMapOf(
        "calendar" to Calculation.InputScope("ROOT", Calculation.Scope(members("calendar", calendar), emptyMap(), emptyList())),
        "usage" to Calculation.InputScope("RELATION", Calculation.Scope(members("usage", usage), emptyMap(), emptyList())),
    )

    private fun sevenDays() = WindowFrameContract.Frame("RANGE", WindowFrameContract.Bound("PRECEDING", 6, "DAY"),
        WindowFrameContract.Bound("CURRENT_ROW", null, null))
    private fun dateRange(start: String, end: String) = Calculation.Range(TemporalWindow(TemporalWindow.ValueType.DATE, TemporalWindow.BOUNDARY, start, end))

    private fun allocationMetric(axes: Map<String, Calculation.DimensionSlot>, destinations: MemberResult): MetricCalculation {
        val allocated = average(YUAN)
        val body = Calculation(listOf(axes.getValue("subscription"), axes.getValue("destination")), listOf(slot("destinations", destinations)), listOf(Calculation.MeasurementSlot("charges", emptyMap(), listOf(axes.getValue("subscription")), emptyList(), balance())), emptyList(), emptyMap(), Calculation.Scope(Calculation.Measures(listOf(Calculation.Call("charges", Calculation.Measurement("charges"), "value", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()))),
            listOf("subscription"), emptyList(), null, null, emptyMap(), Calculation.Population(listOf("charges")), null),
            mapOf("destinations" to Calculation.InputScope("RELATION", Calculation.Scope(members("destinations", destinations), emptyMap(), emptyList()))),
            listOf(JoinInput("destinations", "place", "INNER", op(ResultOperator.EQ, symbol("subscription"), symbol("place__subscription"))),
                Derive(ResultUniformAllocation(symbol("value"), listOf("subscription"), listOf("place__subscription", "place__destination"), allocated, "divide"),
                    "allocated", "每个订阅确认账款在其实际完整目的地总体内均摊"),
                Regroup(ResultGrouping(listOf("place__destination"), listOf(ResultAggregate("allocated", ResultAggregation.Sum(allocated, ResultAggregation.NullPolicy.IGNORE), "allocated_total", "目的地分摊账款")), null)))))
        return MetricCalculation(body, "allocated_total", listOf("place__destination"), allocated)
    }

    private fun chargesBySubscription(axes: Map<String, Calculation.DimensionSlot>) = MetricCalculation(
        Calculation(listOf(axes.getValue("subscription")), emptyList(), emptyList(), emptyList(), emptyMap(), Calculation.Scope(
            Calculation.Measures(listOf(Calculation.Call("charges", Calculation.Fixed(MetricRef("authored.charges", "1")), "value", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()))),
                listOf("subscription"), emptyList(), null, null, emptyMap(), Calculation.Population(listOf("charges")), null), emptyMap(), emptyList())),
        "value", listOf("subscription"), balance())

    private fun slot(name: String, source: MemberResult): Calculation.MemberSlot {
        val outputs = source.outputs.mapValues { (key, output) ->
            require(output is MemberSourceDefinition.ScalarOutput) { "本例作者声明的成员槽位只使用标量输出" }
            when (output) {
                is MemberSourceDefinition.DimensionOutput, is MemberSourceDefinition.ComputedDimensionOutput -> Calculation.MemberDimension(key, output.valueContract)
                else -> Calculation.MemberProperty(output.role, output.valueType, output.valueContract)
            }
        }
        return Calculation.MemberSlot(name, source.entity, source.role, source.shape, outputs, null)
    }

    private fun members(name: String, source: MemberResult) = Calculation.Members(name, name,
        source.outputs.keys.sorted().map { Calculation.MemberProjection(it, it) }, null, emptyMap(), MemberCondition.Unrestricted())
    private fun asOfParameters() = mapOf("as_of" to Calculation.Parameter(Calculation.ParameterKind.SCALAR, "DATE", emptyList(), null, null))
    private fun symbol(value: String) = ResultSymbol(value)
    private fun literalDate(value: String) = ResultTypedLiteral(ScalarValueType.DATE, value)
    private fun op(operator: ResultOperator, vararg values: ResultExpression) = ResultOperation(operator, values.toList())

    private fun amount(unit: MetricValueContract.UnitIdentity) = MetricValueContract(MetricValueContract.Fixed(unit), MetricValueContract.Exact(),
        MetricValueContract.Decimal(18, 2, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
    private fun balance() = MetricValueContract(MetricValueContract.Fixed(YUAN), MetricValueContract.Exact(),
        MetricValueContract.Decimal(38, 4, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
    private fun count() = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(),
        MetricValueContract.Integer(64), emptyMap(), MetricValueContract.Overflow.FAIL)
    private fun average(unit: MetricValueContract.UnitIdentity) = MetricValueContract(MetricValueContract.Fixed(unit),
        MetricValueContract.Approximate("-1000000", "1000000", "0.000000001", "0.000000001"), MetricValueContract.BinaryFloat(64),
        mapOf("divide" to MetricValueContract.PreserveUnit(MetricValueContract.BinaryOperator.DIVIDE, MetricValueContract.Operand.LEFT)), MetricValueContract.Overflow.FAIL)

    private fun computed(body: Calculation, axes: Map<String, String>, values: Map<String, MetricValueContract>, identity: List<String>, description: String): MemberSourceDefinition.Computed {
        val outputs = linkedMapOf<String, MemberSourceDefinition.Output>()
        axes.forEach { (name, symbol) -> outputs[name] = MemberSourceDefinition.ComputedDimensionOutput(name, name,
            if (name == "day") ScalarValueType.DATE else ScalarValueType.TEXT, name, symbol, null) }
        values.forEach { (name, contract) -> outputs[name] = MemberSourceDefinition.ComputedPropertyOutput(name, "amount", ScalarValueType.NUMBER, name, contract) }
        return MemberSourceDefinition.Computed(body, MemberResult("subscription", "subscription", MemberShape.RECORDS, identity,
            outputs, emptyMap(), "day", emptyMap(), description))
    }

    private fun row(name: String, axes: List<String>, properties: Map<String, ScalarValueType>, values: Map<String, MetricValueContract>, identity: List<String>): MemberResult {
        val outputs = linkedMapOf<String, MemberSourceDefinition.Output>()
        axes.forEach { axis -> outputs[axis] = MemberSourceDefinition.ComputedDimensionOutput(axis, axis,
            if (axis == "day") ScalarValueType.DATE else ScalarValueType.TEXT, axis, axis, null) }
        properties.forEach { (property, type) -> outputs[property] = MemberSourceDefinition.ComputedPropertyOutput(property, property, type, property, values[property]) }
        return MemberResult(name, name, MemberShape.RECORDS, identity, outputs, emptyMap(), if ("day" in axes) "day" else null, emptyMap(),
            "作者声明的记录合同；实际来源由远程MCP采用绑定")
    }

    private fun authorDefinitions(output: Path) {
        val axes = listOf("subscription", "day", "region", "destination").associateWith { Calculation.DimensionSlot(it, "subscriptions.$it", 1, it) }
        val numeric = mapOf("id" to ScalarValueType.NUMBER, "amount" to ScalarValueType.NUMBER)
        val legacy = row("legacy_records", listOf("subscription", "day"), numeric, mapOf("amount" to amount(CENTS)), listOf("id"))
        val current = row("current_records", listOf("subscription", "day"), numeric, mapOf("amount" to amount(YUAN)), listOf("id"))
        val regions = row("region_records", listOf("subscription", "day", "region"), mapOf("id" to ScalarValueType.NUMBER), emptyMap(), listOf("id"))
        val usage = row("usage_records", listOf("day"), mapOf("id" to ScalarValueType.NUMBER, "tenant" to ScalarValueType.TEXT, "person" to ScalarValueType.TEXT), emptyMap(), listOf("id"))
        val calendar = row("calendar", listOf("day"), emptyMap(), emptyMap(), listOf("day"))
        val destinations = row("destinations", listOf("subscription", "destination"), mapOf("id" to ScalarValueType.NUMBER), emptyMap(), listOf("subscription", "destination"))
        val migrated = migratedStock(axes, legacy, current)
        val region = regionAt(axes, regions)
        val formed = historicalStock(axes, migrated.result, region.result)
        val mapper = OlapJsonUtils.mapper()
        fun metric(value: MetricDefinition) = mapper.readTree(mapper.writerFor(MetricDefinition::class.java).writeValueAsBytes(value))
        val members = linkedMapOf("selected_stock" to migrated, "historical_region" to region, "historical_stock" to formed).mapValues { (_, value) ->
            mapper.readTree(mapper.writerFor(MemberSourceDefinition::class.java).writeValueAsBytes(value))
        }
        write(output.resolve("authored-definitions.json"), linkedMapOf(
            "rowContracts" to mapOf("legacy" to legacy, "current" to current, "regions" to regions, "usage" to usage, "calendar" to calendar, "destinations" to destinations),
            "members" to members,
            "definitions" to mapOf("historical_balance" to metric(stockMetric(axes, formed.result)), "window_people" to metric(usageMetric(axes, usage, calendar)),
                "calendar_daily_mean" to metric(dailyMeanMetric(axes, usage, calendar)), "destination_allocation" to metric(allocationMetric(axes, destinations)),
                "charges_by_subscription" to metric(chargesBySubscription(axes))),
            "amountCents" to amount(CENTS), "amountYuan" to amount(YUAN), "balance" to balance(), "count" to count(),
        ))
    }

    private fun write(path: Path, value: Any) {
        Files.write(path, OlapJsonUtils.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(value))
    }
}
