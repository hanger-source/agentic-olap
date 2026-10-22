import com.olap.prototype.calculation.*
import com.olap.prototype.connector.*
import com.olap.prototype.connector.loader.ConnectorPlugins
import com.olap.prototype.member.*
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.*
import com.olap.prototype.query.PostOperationContract.OrderKey
import com.olap.prototype.query.PostOperationContract.PostFilter
import com.olap.prototype.query.PostOperationContract.Project
import com.olap.prototype.query.PostOperationContract.Regroup
import com.olap.prototype.query.PostOperationContract.ResultAggregate
import com.olap.prototype.query.PostOperationContract.ResultGrouping
import com.olap.prototype.query.PostOperationContract.SelectVersions
import com.olap.prototype.query.PostOperationContract.ValidatedOperation
import com.olap.prototype.query.PostOperationContract.VersionTies
import com.olap.prototype.query.ResultExpression.*
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.contract.JdbcConnectionSpec
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Driver
import java.util.Properties

/** 准备真实业务数据和作者定义；资产建设、发布和查询属于外部 MCP 客户端。 */
object Procurement {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val args = arguments.toMutableList()
        val option = args.indexOf("--connectors")
        require(option >= 0 && option + 1 < args.size && args.count { it == "--connectors" } == 1) {
            "用法：Procurement --connectors <配置> prepare <新输入目录>"
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
            require(args.size == 2 && args[0] == "prepare") { "用法：Procurement --connectors <配置> prepare <新输入目录>" }
            val output = Path.of(args[1]).toAbsolutePath().normalize()
            Files.createDirectories(output.parent)
            Files.createDirectory(output)
            Files.createDirectories(output.resolve("temporary"))
            System.setProperty("java.io.tmpdir", output.resolve("temporary").toString())
            prepare(output, deployment.registry.require(selected.single()))
        }
    }

    private fun prepare(output: Path, connector: ResolvedConnector) {
        val source = JdbcConnectionSpec("example-procurement", connector.ref, "duckdb",
            "jdbc:duckdb:${output.resolve("source.duckdb")}", null, null, "org.duckdb.DuckDBDriver")
        val jdbc = (connector.access as JdbcAccess).jdbc
        val driver = Class.forName("org.duckdb.DuckDBDriver", true, jdbc.javaClass.classLoader)
            .getDeclaredConstructor().newInstance() as Driver
        driver.connect(source.jdbcUrl, Properties()).use { connection ->
            connection.createStatement().use { sql ->
                // 保存原始投递与版本历史，不预先去重或剔除取消记录。
                sql.execute("CREATE TABLE purchase_order (po_id VARCHAR PRIMARY KEY, amount DECIMAL(18,2) NOT NULL)")
                sql.execute("INSERT INTO purchase_order VALUES ('P-001',100),('P-002',150)")
                sql.execute("""
                    CREATE TABLE receipt_delivery (
                      delivery_id VARCHAR PRIMARY KEY, receipt_id VARCHAR NOT NULL, po_id VARCHAR NOT NULL,
                      revision INTEGER NOT NULL, status VARCHAR NOT NULL,
                      amount DECIMAL(18,2) NOT NULL, delivered_at TIMESTAMP NOT NULL
                    )
                """.trimIndent())
                sql.execute("""
                    INSERT INTO receipt_delivery VALUES
                      ('D-01','R-01','P-001',1,'SUCCESS',30,TIMESTAMP '2026-07-01 09:00:00'),
                      ('D-02','R-01','P-001',1,'SUCCESS',30,TIMESTAMP '2026-07-01 09:01:00'),
                      ('D-03','R-02','P-001',1,'SUCCESS',50,TIMESTAMP '2026-07-02 09:00:00'),
                      ('D-04','R-03','P-002',1,'SUCCESS',70,TIMESTAMP '2026-07-03 09:00:00'),
                      ('D-05','R-03','P-002',2,'CANCELLED',70,TIMESTAMP '2026-07-04 09:00:00'),
                      ('D-06','R-04','P-002',1,'SUCCESS',50,TIMESTAMP '2026-07-03 10:00:00')
                """.trimIndent())
            }
        }
        write(output.resolve("data-sources.json"), listOf(source.source()))
        val po = Calculation.DimensionSlot("po", "example.purchase_order", 1, "purchase_order")
        val mapper = OlapJsonUtils.mapper()
        fun member(value: MemberSourceDefinition) = mapper.readTree(mapper.writerFor(MemberSourceDefinition::class.java).writeValueAsBytes(value))
        fun metric(value: MetricDefinition) = mapper.readTree(mapper.writerFor(MetricDefinition::class.java).writeValueAsBytes(value))
        write(output.resolve("authored-definitions.json"), linkedMapOf(
            "latest_effective" to member(receiptDefinition(po, true)),
            "latest_successful" to member(receiptDefinition(po, false)),
            "sum" to metric(sumDefinition(po)), "total" to metric(totalDefinition(po)), "money" to money(),
        ))
    }

    private fun receiptDefinition(po: Calculation.DimensionSlot, latestFirst: Boolean): MemberSourceDefinition.Computed {
        val outputs = linkedMapOf<String, Calculation.MemberOutput>(
            "id" to Calculation.MemberProperty("delivery_identity", ScalarValueType.TEXT, null),
            "po" to Calculation.MemberDimension("po", null),
            "receipt" to Calculation.MemberProperty("record_identity", ScalarValueType.TEXT, null),
            "revision" to Calculation.MemberProperty("version", ScalarValueType.NUMBER, null),
            "status" to Calculation.MemberProperty("status", ScalarValueType.TEXT, null),
            "amount" to Calculation.MemberProperty("amount", ScalarValueType.NUMBER, money()),
            "delivered" to Calculation.MemberProperty("delivery_time", ScalarValueType.LOCAL_TIMESTAMP, null),
        )
        val versions = SelectVersions(listOf("receipt"), listOf(OrderKey("revision", "DESC", "LAST"),
            OrderKey("delivered", "DESC", "LAST"), OrderKey("id", "ASC", "LAST")), VersionTies.FAIL, null)
        val accepted = PostFilter(ResultOperation(ResultOperator.EQ,
            listOf(ResultSymbol("status"), ResultArgument("accepted", ScalarValueType.TEXT))))
        val stages: List<ValidatedOperation> = if (latestFirst) listOf(versions, accepted) else listOf(accepted, versions)
        val calculation = Calculation(listOf(po), listOf(Calculation.MemberSlot("deliveries", "收货投递", "delivery",
            MemberShape.RECORDS, outputs, null)), emptyList(), emptyList(), mapOf("accepted" to Calculation.Parameter(
        Calculation.ParameterKind.SCALAR, "TEXT", emptyList(), null, null)), Calculation.Scope(
        Calculation.Members("deliveries", "deliveries", outputs.keys.map { Calculation.MemberProjection(it, it) },
            null, emptyMap(), MemberCondition.Unrestricted()), emptyMap(),
        stages + Project(listOf("receipt", "po", "amount"))))
        return MemberSourceDefinition.Computed(calculation, MemberResult("业务金额记录", "amount_record", MemberShape.RECORDS,
            listOf("id"), mapOf(
                "id" to MemberSourceDefinition.ComputedPropertyOutput("收货身份", "record_identity", ScalarValueType.TEXT, "receipt", null),
                "po" to MemberSourceDefinition.ComputedDimensionOutput("采购单", "purchase_order", ScalarValueType.TEXT, "po", "po", null),
                "amount" to MemberSourceDefinition.ComputedPropertyOutput("收货金额", "amount", ScalarValueType.NUMBER, "amount", money()),
            ), emptyMap(), null, emptyMap(),
            if (latestFirst) "先选最新状态再判有效，取消的收货不保留" else "先选成功投递再选最新成功，后续取消不抹除历史成功"))
    }

    private fun sumDefinition(po: Calculation.DimensionSlot): MetricCalculation {
        val rows = Calculation.MemberSlot("rows", "业务金额记录", "amount_record", MemberShape.RECORDS, mapOf(
            "id" to Calculation.MemberProperty("record_identity", ScalarValueType.TEXT, null),
            "po" to Calculation.MemberDimension("po", null),
            "amount" to Calculation.MemberProperty("amount", ScalarValueType.NUMBER, money()),
        ), null)
        val body = Calculation(listOf(po), listOf(rows), emptyList(), emptyList(), emptyMap(), Calculation.Scope(
            Calculation.Members("records", "rows", listOf("id", "po", "amount").map { Calculation.MemberProjection(it, it) },
                null, emptyMap(), MemberCondition.Unrestricted()), emptyMap(), listOf(sum(listOf("po"), "amount"))))
        return MetricCalculation(body, "value", listOf("po"), money())
    }

    private fun totalDefinition(po: Calculation.DimensionSlot) = MetricCalculation(
        Calculation(listOf(po), emptyList(), listOf(Calculation.MeasurementSlot("amount", emptyMap(), listOf(po), emptyList(), money())), emptyList(), emptyMap(), Calculation.Scope(Calculation.Measures(listOf(Calculation.Call("amount", Calculation.Measurement("amount"), "amount", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()))), listOf("po"), emptyList(), null, null,
            emptyMap(), Calculation.Population(listOf("amount")), null), emptyMap(), listOf(sum(emptyList(), "amount")))),
        "value", emptyList(), money())

    private fun sum(keys: List<String>, input: String) = Regroup(ResultGrouping(keys,
        listOf(ResultAggregate(input, ResultAggregation.Sum(money(), ResultAggregation.NullPolicy.IGNORE), "value", "记录金额合计")), null))

    private fun money() = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.UnitIdentity("iso4217", "CNY", 1)),
        MetricValueContract.Exact(), MetricValueContract.Decimal(38, 2, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)

    private fun write(path: Path, value: Any) {
        Files.write(path, OlapJsonUtils.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(value))
    }
}
