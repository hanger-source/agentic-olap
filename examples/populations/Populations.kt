import com.olap.prototype.connector.*
import com.olap.prototype.connector.loader.ConnectorPlugins
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.contract.JdbcConnectionSpec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.Driver
import java.util.Properties

/** 只准备中性原始袋；资格、测量、比率与人口全部交给正式作者程序。 */
object Populations {
    @JvmStatic fun main(arguments: Array<String>) {
        require(arguments.size == 4 && arguments[0] == "--connectors" && arguments[2] in setOf("prepare", "advance-source")) {
            "Populations --connectors <manifests.json> prepare|advance-source <output-directory>"
        }
        val manifests = OlapJsonUtils.mapper().readTree(Files.readAllBytes(Path.of(arguments[1])))
        ConnectorPlugins.load(manifests.map { Path.of(it.textValue()) }).use { plugins ->
            val connector = plugins.registry.require(plugins.registry.references().single { it.id == "duckdb" })
            val output = Path.of(arguments[3]).toAbsolutePath().normalize()
            val source = JdbcConnectionSpec("example-populations", connector.ref, "duckdb", "jdbc:duckdb:${output.resolve("source.duckdb")}", null, null, "org.duckdb.DuckDBDriver")
            if (arguments[2] == "prepare") Files.createDirectory(output) else require(Files.isRegularFile(output.resolve("source.duckdb")))
            val jdbc = (connector.access as JdbcAccess).jdbc
            val driver = Class.forName("org.duckdb.DuckDBDriver", true, jdbc.javaClass.classLoader).getDeclaredConstructor().newInstance() as Driver
            driver.connect(source.jdbcUrl, Properties()).use { connection -> connection.createStatement().use { sql ->
                if (arguments[2] == "advance-source") {
                    // P、声明、绑定与当前标签保持相同；来源内容变化并不重写历史结果。
                    sql.execute("DELETE FROM fact_b WHERE subject='C'")
                } else {
                    sourceStatements.forEach(sql::execute)
                }
            } }
            if (arguments[2] == "prepare") Files.write(output.resolve("data-sources.json"), OlapJsonUtils.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(listOf(source.source())), StandardOpenOption.CREATE_NEW)
        }
    }

    // 全部列可空、无 PK／UNIQUE。重复、NULL、两份来源时钟及分区标签保留为原始输入。
    private val sourceStatements = listOf(
        "CREATE TABLE activity(subject VARCHAR,day VARCHAR,first_flag BOOLEAN,actor VARCHAR,amount DOUBLE)",
        """
        INSERT INTO activity VALUES
          ('A','19991231',TRUE,'pA',1),
          ('B','19991231',TRUE,'pB',1),
          ('C','19991231',TRUE,'pC',1),
          ('D','19991231',TRUE,'pD',1),
          ('E','19991231',TRUE,'pE',1),
          ('','19991231',TRUE,'p0',1),
          (NULL,'19991231',TRUE,'pn',1),
          ('A','20000101',FALSE,NULL,NULL),
          ('A','20000102',FALSE,NULL,NULL),
          ('B','20000101',FALSE,NULL,7),
          ('D','20000101',TRUE,'now',5),
          ('','20000101',FALSE,NULL,NULL)
        """.trimIndent(),
        "CREATE TABLE catalog(item VARCHAR,subject VARCHAR,current_partition BOOLEAN,eligible BOOLEAN)",
        """
        INSERT INTO catalog VALUES
          ('a1','A',TRUE,TRUE),
          ('a2','A',TRUE,TRUE),
          ('b1','B',TRUE,TRUE),
          ('b1','B',TRUE,TRUE),
          ('c1','C',TRUE,FALSE),
          ('d1','D',TRUE,TRUE),
          ('empty','',TRUE,TRUE),
          ('old','E',FALSE,TRUE)
        """.trimIndent(),
        "CREATE TABLE visits(item VARCHAR,actor VARCHAR,day VARCHAR)",
        """
        INSERT INTO visits VALUES
          ('a1','va','20000101'),
          ('b1','vb','20000101'),
          ('c1','vc','20000101'),
          ('d1','vd','20000101'),
          ('empty','v0','20000101'),
          ('missing','vn','20000101')
        """.trimIndent(),
        "CREATE TABLE participation(subject VARCHAR,day VARCHAR,actor VARCHAR,eligible BOOLEAN)",
        """
        INSERT INTO participation VALUES
          ('A','20000101','u1',TRUE),
          ('A','20000101','u1',TRUE),
          ('A','20000101','u2',FALSE),
          ('A','20000102','u1',TRUE),
          ('A','20000102','',TRUE),
          ('A','20000102',NULL,TRUE),
          ('B','20000101','',TRUE),
          ('B','20000102',NULL,NULL)
        """.trimIndent(),
        "CREATE TABLE fact_a(subject VARCHAR,clock VARCHAR,current_partition BOOLEAN,amount DOUBLE,eligible BOOLEAN)",
        """
        INSERT INTO fact_a VALUES
          ('A','20000101',TRUE,NULL,TRUE),
          ('A','20000101',TRUE,9,FALSE),
          ('B','20000102',TRUE,5,TRUE),
          ('A','20000101',FALSE,999,TRUE)
        """.trimIndent(),
        "CREATE TABLE fact_b(subject VARCHAR,clock VARCHAR,current_partition BOOLEAN,amount DOUBLE,deduction DOUBLE)",
        """
        INSERT INTO fact_b VALUES
          ('A','20000102',TRUE,5,NULL),
          ('C','20000102',TRUE,8,3),
          ('C','20000102',TRUE,NULL,2),
          ('C','20000102',FALSE,999,0)
        """.trimIndent(),
        "CREATE TABLE scaled(subject VARCHAR,record_ref VARCHAR,actor VARCHAR,gross DOUBLE,adjustment_text VARCHAR,quantity DOUBLE,label VARCHAR)",
        """
        INSERT INTO scaled VALUES
          ('A','r1','u1',100,'200',2,'alpha'),
          ('A','r2','u1',NULL,NULL,1,'beta'),
          ('B','r3','u2',NULL,NULL,NULL,'gamma')
        """.trimIndent(),
        "CREATE TABLE bases(subject VARCHAR,record_ref VARCHAR,count_a BIGINT,count_b BIGINT,base_a DOUBLE,base_b DOUBLE,state_c VARCHAR)",
        """
        INSERT INTO bases VALUES
          ('A','rA',1,1,100,50,'Y'),
          ('B','rB',1,1,50,25,'Y'),
          ('C','rC',1,1,-10,-5,'N'),
          ('D','rD',1,1,0,0,NULL),
          ('E','rE',1,1,20,10,'Y')
        """.trimIndent(),
        "CREATE TABLE attributes(record_ref VARCHAR,partition_day VARCHAR,flag_a BOOLEAN,flag_b BOOLEAN,a DOUBLE,b DOUBLE,c DOUBLE,d DOUBLE,e DOUBLE,f DOUBLE,g DOUBLE,h DOUBLE,i DOUBLE,j DOUBLE,k DOUBLE,l DOUBLE,m DOUBLE)",
        """
        INSERT INTO attributes VALUES
          ('rA','20000101',TRUE,TRUE,10,NULL,3,4,5,6,2,10,NULL,2,4,5,6),
          ('rA','20000102',TRUE,FALSE,20,NULL,7,8,9,10,3,20,NULL,3,6,7,8),
          ('rB','20000101',TRUE,TRUE,8,NULL,NULL,NULL,NULL,NULL,NULL,5,NULL,NULL,NULL,NULL,NULL),
          ('rC','20000101',TRUE,TRUE,5,1,2,3,4,5,4,2,3,4,1,2,3),
          ('rD','20000101',NULL,FALSE,9,9,9,9,9,9,5,9,9,9,9,9,9)
        """.trimIndent()
    )
}
