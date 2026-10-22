package com.olap.prototype.connector

import com.olap.prototype.api.SqlResultValues
import com.olap.prototype.connector.loader.ConnectorPlugins
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.contract.NativeValueType
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.DeadlineExecutionControl
import com.olap.prototype.warehouse.ResultMaterializationBudget
import com.olap.prototype.warehouse.ResultMaterializationLimits
import com.olap.prototype.warehouse.contract.JdbcConnectionSpec
import com.olap.prototype.warehouse.jdbc.JdbcResultValues
import org.apache.calcite.jdbc.JavaTypeFactoryImpl
import org.apache.calcite.sql.parser.SqlParser
import org.apache.calcite.sql.type.SqlTypeName
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Driver
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Properties
import java.util.ServiceConfigurationError
import java.util.UUID

class ConnectorPluginExecutionTest {
    @Test fun materializesTemporalContainerLeavesThroughTheExactConnector() {
        listOf("h2", "duckdb").forEach { backend ->
            val jdbc = (TestConnectors.registry.require(TestConnectors.ref(backend)).access as JdbcAccess).jdbc
            val url = if (backend == "h2") "jdbc:h2:mem:containers_${UUID.randomUUID()}" else "jdbc:duckdb:"
            val instant = "TIMESTAMP WITH TIME ZONE '2026-01-02 03:04:05.123456+08:00'"
            val sql = "SELECT ARRAY[DATE '2026-01-02',NULL], ARRAY[$instant], ROW(DATE '2026-01-02',$instant)"
            TestConnectors.open(backend, url).use { connection -> connection.createStatement().use { statement -> statement.executeQuery(sql).use { rows -> DeadlineExecutionControl(10000).use { control ->
                assertTrue(rows.next())
                val budget = ResultMaterializationBudget(ResultMaterializationLimits(1, 20, 4096, 8))
                val dates = JdbcResultValues.read(rows, 1, control, budget, jdbc) as SqlResultValues.ArrayValue
                assertEquals(listOf(LocalDate.parse("2026-01-02"), null), dates.elements)
                val expected = OffsetDateTime.parse("2026-01-01T19:04:05.123456Z")
                val instants = JdbcResultValues.read(rows, 2, control, budget, jdbc) as SqlResultValues.ArrayValue
                assertEquals(listOf(expected), instants.elements)
                val row = JdbcResultValues.read(rows, 3, control, budget, jdbc) as SqlResultValues.RowValue
                assertEquals(listOf(LocalDate.parse("2026-01-02"), expected), row.fields)
            } } } }
        }
    }
    @Test fun executesNativeNumericMappingUsingItsExactIsolatedDriver(@TempDir directory: Path) {
        val implementations = mutableListOf<Pair<JdbcSupport, JdbcConnectionSpec>>()
        val manifests = OlapJsonUtils.mapper().readValue(Files.readAllBytes(TestConnectors.configuration), Array<String>::class.java).map(Path::of)
        ConnectorPlugins.load(manifests).use { plugins -> listOf("h2", "duckdb").forEach { backend ->
            val connector = plugins.registry.require(TestConnectors.ref(backend))
            val jdbc = (connector.access as JdbcAccess).jdbc
            val driverName = if (backend == "h2") "org.h2.Driver" else "org.duckdb.DuckDBDriver"
            val driverType = Class.forName(driverName, true, jdbc.javaClass.classLoader)
            assertSame(jdbc.javaClass.classLoader, driverType.classLoader)
            assertNotSame(OlapConnector::class.java.classLoader, driverType.classLoader)
            val driver = driverType.getConstructor().newInstance() as Driver
            val url = if (backend == "h2") "jdbc:h2:${directory.resolve("amounts-h2")}" else "jdbc:duckdb:${directory.resolve("amounts.duckdb")}"
            driver.connect(url, Properties()).use { writer -> writer.createStatement().use { statement -> statement.execute("CREATE TABLE amounts(amount DECIMAL(18,2))"); statement.execute("INSERT INTO amounts VALUES (1.25),(2.75),(NULL)") } }
            val spec = JdbcConnectionSpec("amounts", connector.ref, backend, url, null, null, driverName)
            implementations += jdbc to spec
            val compilation = connector.compilation
            val factory = JavaTypeFactoryImpl(compilation.typeSystem)
            val expected = compilation.typeSystem.deriveAvgAggType(factory, factory.createSqlType(SqlTypeName.DECIMAL, 18, 2))
            val sql = SqlParser.create("SELECT AVG(AMOUNT) FROM AMOUNTS").parseQuery().toSqlString(compilation.dialect).sql
            jdbc.openReadOnly(spec, Properties()).use { connection -> connection.prepareStatement(sql).use { statement -> statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                assertEquals(0, BigDecimal(jdbc.scalar(rows, 1).toString()).compareTo(BigDecimal("2")))
                val metadata = rows.metaData
                val observed = JdbcColumnType(metadata.getColumnType(1), metadata.getColumnTypeName(1), metadata.getPrecision(1), metadata.getScale(1), true)
                val actual = compilation.jdbcType(factory, observed)
                assertTrue(compilation.matchesNativeResult(NativeValueType(expected.sqlTypeName.name, expected.precision.takeIf { it >= 0 }, expected.scale.takeIf { it >= 0 }, expected.isNullable), observed))
                assertEquals(expected.sqlTypeName, actual.sqlTypeName)
                if (expected.sqlTypeName == SqlTypeName.DECIMAL) { assertEquals(expected.precision, actual.precision); assertEquals(expected.scale, actual.scale) }
                assertFalse(rows.next())
            } } }
        } }
        implementations.forEach { (jdbc, spec) -> assertThrows(IllegalStateException::class.java) { jdbc.openReadOnly(spec, Properties()) } }
    }
    @Test fun rejectsDuplicateArtifactIdentityAndMissingIsolatedDriver() {
        val mapper = OlapJsonUtils.mapper()
        val manifests = mapper.readValue(Files.readAllBytes(TestConnectors.configuration), Array<String>::class.java)
        val path = Path.of(manifests[0])
        assertThrows(IllegalArgumentException::class.java) { ConnectorPlugins.load(listOf(path, path)) }
        val manifest = mapper.readValue(Files.readAllBytes(path), ConnectorPlugins.Manifest::class.java)
        val pluginOnly = manifest.jars.take(1)
        val exact = ConnectorRef(manifest.connectorRef.id, manifest.connectorRef.version, ConnectorPlugins.groupHash(pluginOnly.associateTo(linkedMapOf()) { it.file to it.sha256 }))
        val incomplete = Files.createTempFile(path.parent, "missing-driver-", ".json")
        mapper.writeValue(incomplete.toFile(), ConnectorPlugins.Manifest(exact, manifest.apiVersion, pluginOnly))
        assertThrows(ServiceConfigurationError::class.java) { ConnectorPlugins.load(listOf(incomplete)) }
    }
}
