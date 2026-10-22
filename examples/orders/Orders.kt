import com.olap.prototype.connector.*
import com.olap.prototype.connector.loader.ConnectorPlugins
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.contract.JdbcConnectionSpec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.Driver
import java.util.Properties

/** 准备未经业务计算的真实来源数据；建模、发布和查询由同目录 main.olap.kts 完成。 */
object Orders {
    private const val SOURCE = "example-orders"

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

    private fun write(path: Path, value: Any) {
        Files.write(path, OlapJsonUtils.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(value), StandardOpenOption.CREATE_NEW)
    }
}
