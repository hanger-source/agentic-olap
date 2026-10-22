package com.olap.prototype.warehouse.inspection

import com.olap.prototype.connector.SourceSqlRequest
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.engine.calcite.CandidateValueCompiler
import com.olap.prototype.engine.catalog.ExecutablePhysicalExecutionBinding
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.warehouse.onboarding.SourceStructureRegistrationRequest
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.JdbcProvider
import com.olap.prototype.warehouse.contract.JdbcConnectionSpec
import com.olap.prototype.warehouse.contract.PhysicalSourceConstraints
import com.olap.prototype.warehouse.contract.StructureProvenance
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.TimeZone
import java.util.UUID

class JdbcWarehouseInspectorTest {
    private val connectors = TestConnectors.registry
    private val control = RuntimeDeploymentLimits.CONTROL

    @Test fun observesCompositeRelationshipMultiplicityNullsOrphansAndEmptyTablesOnBothWarehouses(@TempDir directory: Path) {
        listOf("h2", "duckdb").forEach { dialect ->
            val url = if (dialect == "h2") "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE" else "jdbc:duckdb:${directory.resolve("relationships.duckdb")}"
            val username = if (dialect == "h2") "sa" else null
            TestConnectors.open(dialect, url, username, "").use { connection -> connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA relations")
                listOf("left source", "right source", "empty source").forEach { statement.execute("CREATE TABLE relations.\"$it\" (\"tenant id\" VARCHAR(12), \"entity key\" INTEGER)") }
                statement.execute("INSERT INTO relations.\"left source\" VALUES ('A',1),('A',1),('A',2),('B',1),(NULL,1),('A',NULL),('C',3),('A',3)")
                statement.execute("INSERT INTO relations.\"right source\" VALUES ('A',1),('A',1),('A',1),('B',1),('A',2),(NULL,1),('A',NULL),('D',9)")
            } }
            val spec = spec("relationships-$dialect", dialect, url)
            val inspector = JdbcWarehouseInspector(connectors)
            val tables = inspector.discover(WarehouseDiscoveryRequest(spec.source(), null, "relations", null, null, 10), control).tables.associate { it.ref.table to it.ref }
            val keys = listOf("tenant id", "entity key")
            fun input(name: String) = relationshipInput(tables.getValue(name), keys)
            val profile = inspector.profileRelationship(WarehouseRelationshipProfileRequest(spec.source(), input("left source"), input("right source"), 30), control)
            assertEquals("SQL_EQUALS_NULL_NEVER_MATCHES", profile.equalitySemantics)
            assertEquals(WarehouseRelationshipProfile.Direction("8", "2", "5", "1", "2", "4", "2", "2", "8", "3"), profile.sourceToTarget, dialect)
            assertEquals(WarehouseRelationshipProfile.Direction("8", "2", "4", "1", "3", "3", "2", "3", "8", "2"), profile.targetToSource, dialect)
            val self = inspector.profileRelationship(WarehouseRelationshipProfileRequest(spec.source(), input("left source"), input("left source"), 30), control)
            assertEquals(WarehouseRelationshipProfile.Direction("8", "2", "5", "1", "2", "2", "4", "2", "8", "2"), self.sourceToTarget, dialect)
            assertEquals(self.sourceToTarget, self.targetToSource, dialect)
            val empty = inspector.profileRelationship(WarehouseRelationshipProfileRequest(spec.source(), input("empty source"), input("left source"), 30), control)
            val zero = WarehouseRelationshipProfile.Direction("0", "0", "0", "0", "0", "0", "0", "0", "0", "0")
            assertEquals(zero, empty.sourceToTarget, dialect)
            assertEquals(WarehouseRelationshipProfile.Direction("8", "2", "5", "1", "2", "8", "0", "0", "0", "0"), empty.targetToSource, dialect)
            val allEmpty = inspector.profileRelationship(WarehouseRelationshipProfileRequest(spec.source(), input("empty source"), input("empty source"), 30), control)
            assertEquals(zero, allEmpty.sourceToTarget, dialect); assertEquals(zero, allEmpty.targetToSource, dialect)
            assertThrows(WarehouseInspectionException::class.java) { inspector.profileRelationship(WarehouseRelationshipProfileRequest(spec.source(), relationshipInput(tables.getValue("left source"), listOf("entity key")), relationshipInput(tables.getValue("right source"), listOf("tenant id")), 30), control) }
            assertThrows(WarehouseInspectionException::class.java) { inspector.profileRelationship(WarehouseRelationshipProfileRequest(spec.source(), relationshipInput(tables.getValue("left source"), listOf("not a column")), relationshipInput(tables.getValue("right source"), listOf("entity key")), 30), control) }
        }
    }

    private fun relationshipInput(table: WarehouseTableRef, names: List<String>): WarehouseRelationshipProfileRequest.Input {
        val columns = names.map { WarehouseRelationshipProfileRequest.Column("fixture:${table.table}:$it", 1, it) }
        val keys = columns.map { SemanticExpression.Reference(SemanticExpression.ReferenceKind.PHYSICAL_COLUMN, it.physicalColumnId, it.physicalColumnVersion) }
        return WarehouseRelationshipProfileRequest.Input(table, columns, keys)
    }

    @Test fun observesConditionalExpressionKeysWithoutDiscardingNullRowsOnBothWarehouses(@TempDir directory: Path) {
        val acceptedRole = "ACCEPTED' OR 1=1 --"
        listOf("h2", "duckdb").forEach { dialect ->
            val url = if (dialect == "h2") "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE" else "jdbc:duckdb:${directory.resolve("expression-keys.duckdb")}"
            TestConnectors.open(dialect, url, if (dialect == "h2") "sa" else null, "").use { connection -> connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA conditional")
                statement.execute("CREATE TABLE conditional.orders (id INTEGER)")
                statement.execute("CREATE TABLE conditional.events (order_id INTEGER, role VARCHAR(80))")
                statement.execute("INSERT INTO conditional.orders VALUES (1),(2),(3),(NULL)")
                statement.execute("INSERT INTO conditional.events VALUES (1,'SHIPPED'),(3,'CANCELLED'),(1,NULL)")
                connection.prepareStatement("INSERT INTO conditional.events VALUES (?,?)").use { insert -> listOf(1, 2, 2, null, 4).forEach { insert.setObject(1, it); insert.setString(2, acceptedRole); insert.executeUpdate() } }
            } }
            val spec = spec("conditional-$dialect", dialect, url)
            val inspector = JdbcWarehouseInspector(connectors)
            val tables = inspector.discover(WarehouseDiscoveryRequest(spec.source(), null, "conditional", null, null, 10), control).tables.associate { it.ref.table to it.ref }
            val source = relationshipInput(tables.getValue("orders"), listOf("id"))
            val target = relationshipInput(tables.getValue("events"), listOf("order_id", "role"))
            val sourceKey = SemanticExpression.Cast(source.keyExpressions[0], SemanticExpression.ValueType.BIGINT, null, null, MetricValueContract.Rounding.NONE)
            val condition = SemanticExpression.Operation(SemanticExpression.Operator.EQ, listOf(target.keyExpressions[1], SemanticExpression.Literal(acceptedRole)))
            val targetKey = SemanticExpression.CaseWhen(listOf(SemanticExpression.Branch(condition, SemanticExpression.Cast(target.keyExpressions[0], SemanticExpression.ValueType.BIGINT, null, null, MetricValueContract.Rounding.NONE))), null)
            val profile = inspector.profileRelationship(WarehouseRelationshipProfileRequest(spec.source(), WarehouseRelationshipProfileRequest.Input(source.table, source.columns, listOf(sourceKey)), WarehouseRelationshipProfileRequest.Input(target.table, target.columns, listOf(targetKey)), 30), control)
            assertEquals(WarehouseRelationshipProfile.Direction("4", "1", "3", "0", "0", "2", "1", "1", "3", "2"), profile.sourceToTarget, dialect)
            assertEquals(WarehouseRelationshipProfile.Direction("8", "4", "3", "1", "2", "5", "3", "0", "3", "1"), profile.targetToSource, dialect)
        }
    }

    @Test fun preservesWarehouseTimeAcrossSamplingCandidatesAndExecutionDuringDstGap() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            listOf("h2", "duckdb").forEach { dialect ->
                val url = if (dialect == "h2") "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE" else "jdbc:duckdb:${Files.createTempDirectory("jdbc-clock-").resolve("clock.duckdb")}"
                val catalog = TestConnectors.open(dialect, url, if (dialect == "h2") "sa" else null, "").use { connection -> connection.createStatement().use { statement ->
                    statement.execute("CREATE SCHEMA clock_test")
                    statement.execute("CREATE TABLE clock_test.records (business_day DATE, recorded_at TIMESTAMP(6), instant_at TIMESTAMP WITH TIME ZONE, absent_at TIMESTAMP)")
                    statement.execute("INSERT INTO clock_test.records VALUES (DATE '2026-03-08', TIMESTAMP '2026-03-08 02:30:00.123456', TIMESTAMP WITH TIME ZONE '2026-03-08 02:30:00.123456-05:00', NULL)")
                    connection.catalog
                } }
                val spec = spec("clock-$dialect", dialect, url)
                val table = WarehouseTableRef(catalog, "clock_test", "records")
                val local = LocalDateTime.parse("2026-03-08T02:30:00.123456")
                val preview = JdbcWarehouseInspector(connectors).preview(WarehousePreviewRequest(spec.source(), table, 1), control)
                assertEquals(local.toString(), preview.rows[0].cells[1].value.value)
                assertEquals(WarehouseScalar.Kind.NULL, preview.rows[0].cells[3].value.kind)
                val provider = JdbcProvider(spec, connectors)
                assertEquals(listOf(local), candidates(provider, spec, table, "recorded_at", ScalarValueType.LOCAL_TIMESTAMP))
                assertEquals(listOf(LocalDate.parse("2026-03-08")), candidates(provider, spec, table, "business_day", ScalarValueType.DATE))
                val instant = OffsetDateTime.parse("2026-03-08T02:30:00.123456-05:00").toInstant()
                assertEquals(instant, (candidates(provider, spec, table, "instant_at", ScalarValueType.OFFSET_TIMESTAMP).first() as OffsetDateTime).toInstant())
                assertThrows(IllegalArgumentException::class.java) { candidates(provider, spec, table, "recorded_at", ScalarValueType.DATE) }
                val aliases = linkedMapOf("business_day" to "day", "recorded_at" to "recorded", "instant_at" to "instant", "absent_at" to "absent")
                val sql = "SELECT * FROM clock_test.records WHERE recorded_at = ?"
                val types = TestConnectors.sqlOutputTypes(spec, sql, aliases, mapOf("day" to true, "recorded" to true, "instant" to true, "absent" to true))
                val frame = provider.executeFrame(SourceSqlRequest("execute-query", sql, listOf(JdbcParameter.of(java.sql.Types.TIMESTAMP, "TIMESTAMP", 9, 0, local)), aliases, aliases, types, emptyList(), emptyList(), null), control, RuntimeDeploymentLimits.budget(), RuntimeDeploymentLimits.nativeMemoryBytes) { _, _ -> }.frame
                assertEquals(1, frame.rows.size)
                val cells = frame.rows[0].cells
                assertEquals(LocalDate.parse("2026-03-08"), cells[0].value); assertEquals(local, cells[1].value)
                assertEquals(instant, (cells[2].value as OffsetDateTime).toInstant()); assertNull(cells[3].value)
            }
        } finally { TimeZone.setDefault(original) }
    }

    private fun candidates(provider: JdbcProvider, spec: JdbcConnectionSpec, table: WarehouseTableRef, column: String, type: ScalarValueType): List<Any?> {
        val observed = JdbcWarehouseInspector(connectors).describe(WarehouseTableInspectionRequest(spec.source(), table), control)
        val refs = observed.columns.associate { it.name to "column:${it.name}@1" }
        val names = refs.entries.associate { it.value to it.key }
        val types = observed.columns.associate { "column:${it.name}@1" to JdbcColumnType(requireNotNull(it.jdbcType), requireNotNull(it.typeName), it.precision, it.scale, requireNotNull(it.nullable)) }
        val physical = SemanticExpression.Reference(SemanticExpression.ReferenceKind.PHYSICAL_COLUMN, "column:$column", 1)
        val logical = SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "value", 1)
        val source = observed.source
        val binding = TestConnectors.physical(ExecutablePhysicalExecutionBinding("clock-source", "1", null, null, null, null, "clock-source", "1", "records", "1", spec.dataSourceId, spec.connectorRef, emptyList(), spec.dialectId, table.catalog, table.schema, table.table,
            PhysicalSourceConstraints("clock-investigation", ProtoBytes.digest(GovernanceProto.toProto(SourceStructureRegistrationRequest.Table(observed.table, observed.columns, observed.constraints))), spec.sourceIdentityHash(), spec.connectorRef, source.databaseProductName, source.databaseProductVersion, source.driverName, source.driverVersion, observed.constraints, refs, emptyList(), StructureProvenance(StructureProvenance.Kind.DATABASE_CATALOG, "integration-test-catalog", "fixture-v1")),
            emptyList(), logicUnitBindings = mapOf("value@1" to physical), physicalColumnBindings = names, physicalColumnTypes = types))
        val sql = CandidateValueCompiler.compile(binding, logical, 0, 10, mutableListOf(), connectors)
        return provider.executeFrame(sql, control, RuntimeDeploymentLimits.budget(), RuntimeDeploymentLimits.nativeMemoryBytes) { _, _ -> }.frame.rows.map { it.cells.first().value }.onEach { type.candidate(it) }
    }

    @Test fun discoversDescribesAndPreviewsARealJdbcSchema() {
        val url = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE"
        TestConnectors.open("h2", url, "sa", "").use { connection -> connection.createStatement().use { statement ->
            statement.execute("CREATE SCHEMA analytics")
            statement.execute("CREATE TABLE analytics.dim_customer (customer_id BIGINT PRIMARY KEY, customer_name VARCHAR(100) NOT NULL)")
            statement.execute("CREATE TABLE analytics.fact_order (order_id BIGINT PRIMARY KEY, customer_id BIGINT NOT NULL, paid_amount DECIMAL(18, 2) NOT NULL, paid_at TIMESTAMP NOT NULL, CONSTRAINT fk_order_customer FOREIGN KEY (customer_id) REFERENCES analytics.dim_customer(customer_id))")
            statement.execute("INSERT INTO analytics.dim_customer VALUES (7, '星海旅行')")
            statement.execute("INSERT INTO analytics.fact_order VALUES (101, 7, 1299.50, TIMESTAMP '2026-09-29 10:15:00')")
        } }
        val inspector = JdbcWarehouseInspector(connectors)
        val spec = spec("business-h2", "h2", url)
        val discovery = inspector.discover(WarehouseDiscoveryRequest(spec.source(), null, "analytics", null, null, 50), control)
        assertEquals(2, discovery.returnedTableCount); assertFalse(discovery.truncated)
        val factOrder = discovery.tables.single { it.ref.table == "fact_order" }.ref
        val metadata = inspector.describe(WarehouseTableInspectionRequest(spec.source(), factOrder), control)
        assertEquals(listOf("order_id"), metadata.constraints.primaryKeyColumns)
        assertEquals(4, metadata.columns.size)
        assertEquals("customer_id", metadata.constraints.importedKeys[0].column)
        assertEquals("dim_customer", metadata.constraints.importedKeys[0].referencedTable.table)
        val preview = inspector.preview(WarehousePreviewRequest(spec.source(), factOrder, 10), control)
        assertEquals(1, preview.rows.size); assertEquals("101", preview.rows[0].cells[0].value.value)
        assertEquals(WarehouseScalar.Kind.DECIMAL, preview.rows[0].cells[2].value.kind); assertEquals("1299.50", preview.rows[0].cells[2].value.value)
    }

    @Test fun refusesInspectionWhenNamedEnvironmentSecretIsMissing() {
        val inspector = JdbcWarehouseInspector(connectors) { null }
        val spec = JdbcConnectionSpec("secured", TestConnectors.ref("h2"), "h2", "jdbc:h2:mem:secured", "sa", "OLAP_TEST_PASSWORD", null)
        val failure = assertThrows(WarehouseInspectionException::class.java) { inspector.discover(WarehouseDiscoveryRequest(spec.source(), null, null, null, null, 10), control) }
        assertEquals("WAREHOUSE_PASSWORD_ENVIRONMENT_VARIABLE_MISSING", failure.code)
        assertTrue(failure.message!!.contains("OLAP_TEST_PASSWORD")); assertFalse(failure.message!!.contains("jdbc:h2"))
    }

    private fun spec(id: String, dialect: String, url: String) = JdbcConnectionSpec(id, TestConnectors.ref(dialect), dialect, url, if (dialect == "h2") "sa" else null, null, if (dialect == "h2") "org.h2.Driver" else "org.duckdb.DuckDBDriver")
}
