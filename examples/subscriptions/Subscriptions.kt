import com.olap.prototype.connector.*
import com.olap.prototype.connector.loader.ConnectorPlugins
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.contract.JdbcConnectionSpec
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Driver
import java.util.Properties

/** 准备未经业务计算的真实来源数据；建模、发布和查询由同目录 main.olap.kts 完成。 */
object Subscriptions {

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

    private fun write(path: Path, value: Any) {
        Files.write(path, OlapJsonUtils.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(value))
    }
}
