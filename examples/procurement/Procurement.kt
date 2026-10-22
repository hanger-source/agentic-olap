import com.olap.prototype.connector.*
import com.olap.prototype.connector.loader.ConnectorPlugins
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.contract.JdbcConnectionSpec
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Driver
import java.util.Properties

/** 准备未经业务计算的真实来源数据；建模、发布和查询由同目录 main.olap.kts 完成。 */
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
    }

    private fun write(path: Path, value: Any) {
        Files.write(path, OlapJsonUtils.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(value))
    }
}
