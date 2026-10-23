package com.olap.prototype.warehouse.inspection

import com.olap.prototype.api.ResultValueType
import com.olap.prototype.connector.JdbcAccess
import com.olap.prototype.connector.SourceExecutionException
import com.olap.prototype.connector.SourceSqlRequest
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.contract.JdbcSourceRequirement
import com.olap.prototype.engine.calcite.*
import com.olap.prototype.engine.catalog.ExecutablePhysicalExecutionBinding
import com.olap.prototype.expression.CalciteSqlRendering
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.SemanticNativeTypes
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.JdbcProvider
import com.olap.prototype.warehouse.SourceGuaranteeException
import com.olap.prototype.warehouse.contract.*
import com.olap.prototype.warehouse.jdbc.*
import org.apache.calcite.plan.hep.HepPlanner
import org.apache.calcite.plan.hep.HepProgramBuilder
import org.apache.calcite.rel.rules.CoreRules
import org.apache.calcite.util.ImmutableBitSet
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DatabaseMetaData
import java.util.HexFormat
import java.util.UUID

class JdbcSourceGuaranteeExecutionTest {
    private val connectors = TestConnectors.registry
    private val control = RuntimeDeploymentLimits.CONTROL
    private fun jdbc(spec: JdbcConnectionSpec) = (connectors.require(spec.connectorRef).access as JdbcAccess).jdbc
    private fun execute(provider: JdbcProvider, request: SourceSqlRequest) = provider.executeFrame(request, control, RuntimeDeploymentLimits.budget(), RuntimeDeploymentLimits.nativeMemoryBytes) { _, _ -> }.frame

    @Test fun validatesSealedTemporalHistoryAndRejectsOverlapAndLaterDataDrift(@TempDir directory: Path) {
        val source = source("duckdb", directory.resolve("temporal.duckdb"))
        val spec = source.spec
        TestConnectors.open(spec.connectorRef.id, spec.jdbcUrl).use { connection -> connection.createStatement().use { statement ->
            statement.execute("CREATE TABLE source_test.history(entity INTEGER, valid_from DATE, valid_to DATE, amount DECIMAL(18,2))")
            statement.execute("INSERT INTO source_test.history VALUES (1,DATE '2026-01-01',DATE '2026-02-01',10),(1,DATE '2026-02-01',NULL,20)")
        } }
        val inspector = JdbcWarehouseInspector(connectors)
        val table = inspector.discover(WarehouseDiscoveryRequest(spec.source(), null, "source_test", "history", null, 10), control).tables.first().ref
        val observed = inspector.describe(WarehouseTableInspectionRequest(spec.source(), table), control)
        val columns = observed.columns.associate { it.name to columnType(it) }
        val refs = observed.columns.associate { it.name to "history:${it.name}@1" }
        val interval = JdbcTableConstraints.TemporalInterval("valid_from", "valid_to", true, false, true)
        val rule = JdbcTableConstraints.TemporalRule("entity_history", listOf("entity"), listOf(interval))
        val physical = JdbcTableConstraints.Table(table.catalog, table.schema, table.table)
        val verified = JdbcConnectionFactory(connectors).openReadOnly(spec).use { connection ->
            val keys = JdbcTemporalVerifier.verify(connection, spec, physical, columns, listOf(rule), control, jdbc(spec))
            val closed = JdbcTableConstraints.TemporalRule("closed_boundary", listOf("entity"), listOf(JdbcTableConstraints.TemporalInterval("valid_from", "valid_to", true, true, true)))
            assertEquals("SOURCE_TEMPORAL_INTERVAL_OVERLAP", assertThrows(SourceGuaranteeException::class.java) { JdbcTemporalVerifier.verify(connection, spec, physical, columns, listOf(closed), control, jdbc(spec)) }.code)
            keys
        }
        val native = observed.constraints
        val constraints = JdbcTableConstraints(native.primaryKeyColumns, native.uniqueKeys, native.importedKeys, verified)
        val technology = observed.source
        val evidence = PhysicalSourceConstraints("temporal-discovery", hash(observed), spec.sourceIdentityHash(), spec.connectorRef, technology.databaseProductName, technology.databaseProductVersion, technology.driverName, technology.driverVersion, constraints, refs, emptyList(), StructureProvenance(StructureProvenance.Kind.DATABASE_CATALOG, "integration-test-catalog", "fixture-v1"))
        val authored = JdbcSourceRequirement(spec.dataSourceId, spec.connectorRef, spec.dialectId, "history", "1", physical, evidence, columns, setOf(JdbcSourceRequirement.Use.ROWS), emptyList(), setOf(JdbcSourceRequirement.ConstraintUse(JdbcSourceRequirement.ConstraintKind.TEMPORAL_KEY, rule.name)))
        val requirement = OlapJsonUtils.mapper().readValue(OlapJsonUtils.mapper().writeValueAsBytes(authored), JdbcSourceRequirement::class.java)
        val provider = JdbcProvider(spec, connectors)
        val query = "SELECT SUM(amount) total FROM source_test.history WHERE DATE '2026-02-01' >= valid_from AND (valid_to IS NULL OR DATE '2026-02-01' < valid_to)"
        val queryTypes = TestConnectors.sqlOutputTypes(spec, query, mapOf("total" to "total"), mapOf("total" to true))
        val request = SourceSqlRequest("execute-query", query, emptyList(), mapOf("total" to "total"), mapOf("total" to "金额"), queryTypes, listOf(requirement), emptyList(), null)
        assertEquals(0, BigDecimal("20").compareTo(execute(provider, request).rows.first().cells.first().value as BigDecimal))
        assertEquals("EXECUTABLE_SOURCE_RESULT_FAILED", assertThrows(SourceExecutionException::class.java) { execute(provider, SourceSqlRequest("execute-query", query, emptyList(), mapOf("different_output" to "total"), mapOf("different_output" to "金额"), queryTypes, listOf(requirement), emptyList(), null)) }.failure.code)
        TestConnectors.open(spec.connectorRef.id, spec.jdbcUrl).use { connection -> connection.createStatement().use { it.execute("INSERT INTO source_test.history VALUES (1,DATE '2026-01-15',DATE '2026-02-15',100)") } }
        assertEquals("SOURCE_DATA_ANCHOR_DRIFT", assertThrows(SourceExecutionException::class.java) { execute(provider, request) }.failure.code)
        JdbcConnectionFactory(connectors).openReadOnly(spec).use { connection -> assertEquals("SOURCE_TEMPORAL_INTERVAL_OVERLAP", assertThrows(SourceGuaranteeException::class.java) { JdbcTemporalVerifier.verify(connection, spec, physical, columns, listOf(rule), control, jdbc(spec)) }.code) }
    }

    @Test fun verifiesCompiledAndCandidateReadsAndRejectsSourceAndColumnDrift(@TempDir directory: Path) {
        listOf("h2", "duckdb").forEach { dialect ->
            val source = source(dialect, directory.resolve("read-$dialect.duckdb"))
            val binding = source.binding
            val spec = source.spec
            val inputs = CalciteQueryCompiler.inputs(listOf(binding), binding.physicalColumnBindings.keys, emptySet(), connectors)
            val builder = inputs.builder
            builder.scan(FrozenCatalogSchema.path(binding))
            builder.aggregate(builder.groupKey(), builder.sum(false, "total", builder.field("amount")))
            val outputTypes = mapOf<String, ResultValueType>("total" to SemanticNativeTypes.resultType(builder.peek().rowType.fieldList.first().type))
            val sql = CalciteSqlRendering.render(CalciteQueryCompiler.optimize(builder.build(), emptyList()), connectors.require(TestConnectors.ref(dialect)).compilation)
            val requirements = inputs.sourceRequirements()
            val provider = JdbcProvider(spec, connectors)
            TestConnectors.open(spec.connectorRef.id, spec.jdbcUrl, spec.username, "").use { connection -> connection.createStatement().use { it.execute("DROP INDEX source_test.optional_amount_index") } }
            val request = SourceSqlRequest("execute-query", sql.sql, emptyList(), mapOf("total" to "total"), mapOf("total" to "总额"), outputTypes, requirements, emptyList(), null)
            assertEquals(0, BigDecimal("110").compareTo(execute(provider, request).rows.first().cells.first().value as BigDecimal))
            val value = SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "member", 1)
            val candidate = CandidateValueCompiler.compile(binding, value, 0, 10, mutableListOf(), connectors)
            assertEquals(listOf(1L, 2L), execute(provider, candidate).rows.map { it.cells.first().value })
            val replacement = JdbcConnectionSpec(spec.dataSourceId, TestConnectors.ref(dialect), dialect, if (dialect == "h2") spec.jdbcUrl.replace("source_", "other_") else spec.jdbcUrl + ".replacement", spec.username, null, spec.driverClassName)
            assertEquals("SOURCE_BINDING_DRIFT", assertThrows(SourceExecutionException::class.java) { execute(JdbcProvider(replacement, connectors), request) }.failure.code)
            TestConnectors.open(spec.connectorRef.id, spec.jdbcUrl, spec.username, "").use { connection -> connection.createStatement().use { it.execute("ALTER TABLE source_test.records ALTER COLUMN amount TYPE DECIMAL(23,8)") } }
            assertEquals("SOURCE_COLUMN_TYPE_DRIFT", assertThrows(SourceExecutionException::class.java) { execute(provider, request) }.failure.code)
        }
    }

    @Test fun protectsNativeKeyRewritesAndLeavesUnprovenH2KeysUnknown(@TempDir directory: Path) {
        listOf("h2", "duckdb").forEach { dialect ->
            val source = source(dialect, directory.resolve("keys-$dialect.duckdb"))
            val binding = source.binding
            val spec = source.spec
            val inputs = CalciteQueryCompiler.inputs(listOf(binding), binding.physicalColumnBindings.keys, emptySet(), connectors)
            val builder = inputs.builder
            builder.scan(FrozenCatalogSchema.path(binding))
            builder.project(listOf(builder.field("tenant"), builder.field("id")), listOf("tenant", "id"))
            builder.distinct()
            val planner = HepPlanner(HepProgramBuilder().addRuleInstance(CoreRules.AGGREGATE_REMOVE).build())
            planner.setRoot(builder.build())
            val planned = planner.findBestExp()
            val outputTypes = planned.rowType.fieldList.associate { it.name to SemanticNativeTypes.resultType(it.type) }
            val sql = CalciteSqlRendering.render(planned, connectors.require(TestConnectors.ref(dialect)).compilation)
            val requirements = inputs.sourceRequirements()
            TestConnectors.open(spec.connectorRef.id, spec.jdbcUrl, spec.username, "").use { connection -> connection.createStatement().use { change ->
                change.execute("DROP TABLE source_test.records")
                change.execute("CREATE TABLE source_test.records (tenant BIGINT NOT NULL, id BIGINT NOT NULL, amount DECIMAL(22,7))")
                change.execute("INSERT INTO source_test.records VALUES (1,1,50),(1,1,50)")
            } }
            val request = SourceSqlRequest("execute-query", sql.sql, emptyList(), mapOf("tenant" to "tenant", "id" to "id"), mapOf("tenant" to "租户", "id" to "记录"), outputTypes, requirements, emptyList(), null)
            val provider = JdbcProvider(spec, connectors)
            if (dialect == "duckdb") assertEquals("SOURCE_CONSTRAINT_DRIFT", assertThrows(SourceExecutionException::class.java) { execute(provider, request) }.failure.code)
            else assertEquals(1, execute(provider, request).rows.size, "H2 未证明 catalog/data 同快照，DISTINCT 仍有实际含义")
        }
    }

    @Test fun verifiesDeclaredNonNullColumnsAgainstTheQuerySnapshotWithoutCatalogEvidence(@TempDir directory: Path) {
        for (dialect in listOf("h2", "duckdb")) {
            val observed = source(dialect, directory.resolve("declared-non-null-$dialect.duckdb"))
            val binding = observed.binding
            val frozen = requireNotNull(binding.sourceConstraints)
            val declared = PhysicalSourceConstraints("declared-not-null", "declared-columns", frozen.sourceIdentityHash,
                frozen.connectorRef, null, null, null, null, JdbcTableConstraints(emptyList(), emptyList(), emptyList(), emptyList()),
                frozen.columnVersionRefs, emptyList(), StructureProvenance(StructureProvenance.Kind.DECLARED, "source-owner", "1"))
            val adopted = binding.copy(sourceConstraints = declared,
                catalogName = if (dialect == "h2") "" else binding.catalogName,
                physicalColumnTypes = binding.physicalColumnTypes.mapValues { (_, type) -> type.copy(nullable = false) })
            val amount = binding.physicalColumnBindings.entries.single { it.value == "amount" }.key
            val inputs = CalciteQueryCompiler.inputs(listOf(adopted), setOf(amount), emptySet(), connectors)
            val builder = inputs.builder.scan(FrozenCatalogSchema.path(adopted))
            builder.aggregate(builder.groupKey(), builder.sum(false, "total", builder.field("amount")))
            val output = SemanticNativeTypes.resultType(builder.peek().rowType.fieldList.single().type)
            val rendered = CalciteSqlRendering.render(CalciteQueryCompiler.optimize(builder.build(), emptyList()),
                connectors.require(adopted.connectorRef).compilation)
            val requirements = inputs.sourceRequirements()
            assertEquals(setOf(JdbcSourceRequirement.ConstraintUse(JdbcSourceRequirement.ConstraintKind.NOT_NULL, "amount")),
                requirements.single().constraintUses, "只采用本次暴露的精确列，不依赖未选择的 tenant/id 或声明的主键")
            val request = SourceSqlRequest("execute-query", rendered.sql, emptyList(), mapOf("total" to "total"),
                mapOf("total" to "总额"), mapOf("total" to output), requirements, emptyList(), null)
            val provider = JdbcProvider(observed.spec, connectors)
            assertEquals(0, BigDecimal("110").compareTo(execute(provider, request).rows.single().cells.single().value as BigDecimal))
            TestConnectors.open(dialect, observed.spec.jdbcUrl, observed.spec.username, "").use { writer -> writer.createStatement().use {
                it.execute("UPDATE source_test.records SET amount=NULL WHERE id=2")
            } }
            assertEquals("SOURCE_NOT_NULL_VIOLATION", assertThrows(SourceExecutionException::class.java) {
                execute(provider, request)
            }.failure.code, "结果只输出总额，不能靠最终结果类型发现被聚合隐藏的脏 NULL")
        }
    }

    @Test fun queryKeepsTheVerifiedDataSnapshotDuringConcurrentInsert(@TempDir directory: Path) {
        listOf("h2", "duckdb").forEach { dialect -> listOf(false, true).forEach { empty ->
            val source = source(dialect, directory.resolve("snapshot-$dialect-$empty.duckdb"))
            val spec = source.spec
            val requirements = keyRequirements(source.binding)
            TestConnectors.open(spec.connectorRef.id, spec.jdbcUrl, spec.username, "").use { writer ->
                if (empty) writer.createStatement().use { it.execute("DELETE FROM source_test.records") }
                TestConnectors.open(spec.connectorRef.id, spec.jdbcUrl, spec.username, "").use { reader ->
                    JdbcSourceVerifier.beginAndVerify(reader, spec, requirements, control, jdbc(spec))
                    writer.createStatement().use { it.execute("INSERT INTO source_test.records VALUES(1,3,900)") }
                    reader.createStatement().use { query -> query.executeQuery("SELECT SUM(amount) FROM source_test.records").use { rows ->
                        assertTrue(rows.next())
                        if (empty) assertNull(rows.getBigDecimal(1), dialect) else assertEquals(0, BigDecimal("110").compareTo(rows.getBigDecimal(1)), dialect)
                    } }
                    reader.rollback()
                }
            }
        } }
    }

    private fun keyRequirements(binding: ExecutablePhysicalExecutionBinding): List<JdbcSourceRequirement> {
        val inputs = CalciteQueryCompiler.inputs(listOf(binding), binding.physicalColumnBindings.keys, emptySet(), connectors)
        val builder = inputs.builder
        builder.scan(FrozenCatalogSchema.path(binding))
        val scan = builder.build()
        scan.cluster.metadataQuery.areColumnsUnique(scan, ImmutableBitSet.of(0, 1))
        return inputs.sourceRequirements()
    }

    @Test fun duckDbRetainsVerifiedCatalogAndDataAcrossConcurrentDdl(@TempDir directory: Path) {
        listOf("replace", "type", "not_null").forEach { action ->
            val source = source("duckdb", directory.resolve("$action.duckdb"))
            val spec = source.spec
            val requirements = keyRequirements(source.binding)
            TestConnectors.open(spec.connectorRef.id, spec.jdbcUrl).use { writer -> TestConnectors.open(spec.connectorRef.id, spec.jdbcUrl).use { reader ->
                JdbcSourceVerifier.beginAndVerify(reader, spec, requirements, control, jdbc(spec))
                writer.createStatement().use { change ->
                    change.execute("DROP INDEX source_test.optional_amount_index")
                    when (action) {
                        "replace" -> { change.execute("DROP TABLE source_test.records"); change.execute("CREATE TABLE source_test.records (tenant BIGINT NOT NULL, id BIGINT NOT NULL, amount DECIMAL(22,7))"); change.execute("INSERT INTO source_test.records VALUES(1,1,777),(1,1,777)") }
                        "type" -> { change.execute("ALTER TABLE source_test.records ALTER COLUMN amount TYPE DECIMAL(23,8)"); change.execute("UPDATE source_test.records SET amount=888") }
                        "not_null" -> { change.execute("ALTER TABLE source_test.records ALTER COLUMN amount SET NOT NULL"); change.execute("INSERT INTO source_test.records VALUES(1,3,900)") }
                    }
                }
                assertEquals(source.binding.sourceConstraints.constraints, jdbc(spec).constraints(reader, requirements.first().table, control), action)
                reader.metaData.getColumns(requirements.first().table.catalog, "source_test", "records", "amount").use { columns ->
                    assertTrue(columns.next(), action); assertEquals(22, columns.getInt("COLUMN_SIZE"), action); assertEquals(7, columns.getInt("DECIMAL_DIGITS"), action)
                    assertEquals(DatabaseMetaData.columnNullable, columns.getInt("NULLABLE"), action)
                }
                reader.createStatement().use { query -> query.executeQuery("SELECT SUM(amount) FROM source_test.records").use { rows -> assertTrue(rows.next(), action); assertEquals(0, BigDecimal("110").compareTo(rows.getBigDecimal(1)), action); assertEquals(7, rows.metaData.getScale(1), action) } }
                reader.rollback()
            } }
        }
    }

    private data class Source(val spec: JdbcConnectionSpec, val binding: ExecutablePhysicalExecutionBinding)
    private fun source(dialect: String, file: Path): Source {
        val url = if (dialect == "h2") "jdbc:h2:mem:source_${UUID.randomUUID()};DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1" else "jdbc:duckdb:$file"
        val spec = JdbcConnectionSpec("source", TestConnectors.ref(dialect), dialect, url, if (dialect == "h2") "sa" else null, null, if (dialect == "h2") "org.h2.Driver" else "org.duckdb.DuckDBDriver")
        TestConnectors.open(spec.connectorRef.id, url, spec.username, "").use { connection -> connection.createStatement().use { statement ->
            statement.execute("CREATE SCHEMA source_test")
            statement.execute("CREATE TABLE source_test.records (tenant BIGINT NOT NULL, id BIGINT NOT NULL, amount DECIMAL(22,7), PRIMARY KEY(tenant,id))")
            statement.execute("CREATE UNIQUE INDEX optional_amount_index ON source_test.records(amount)")
            statement.execute("INSERT INTO source_test.records VALUES(1,1,50),(1,2,60)")
        } }
        val inspector = JdbcWarehouseInspector(connectors)
        val table = inspector.discover(WarehouseDiscoveryRequest(spec.source(), null, "source_test", "records", null, 10), control).tables.first().ref
        val observed = inspector.describe(WarehouseTableInspectionRequest(spec.source(), table), control)
        val refs = observed.columns.associate { it.name to "column:${it.name}@1" }
        val names = refs.entries.associate { it.value to it.key }
        val types = observed.columns.associate { "column:${it.name}@1" to columnType(it) }
        val technology = observed.source
        val evidence = PhysicalSourceConstraints("independent-jdbc-discovery", hash(observed), spec.sourceIdentityHash(), spec.connectorRef, technology.databaseProductName, technology.databaseProductVersion, technology.driverName, technology.driverVersion, observed.constraints, refs, emptyList(), StructureProvenance(StructureProvenance.Kind.DATABASE_CATALOG, "integration-test-catalog", "fixture-v1"))
        val binding = TestConnectors.physical(ExecutablePhysicalExecutionBinding("observed-source", "1", null, null, null, null, "observed-source", "1", "records", "1", "source", spec.connectorRef, emptyList(), dialect, table.catalog, table.schema, table.table, evidence, emptyList(), logicUnitBindings = mapOf("member@1" to SemanticExpression.Reference(SemanticExpression.ReferenceKind.PHYSICAL_COLUMN, "column:id", 1)), physicalColumnBindings = names, physicalColumnTypes = types))
        return Source(spec, binding)
    }
    private fun columnType(column: WarehouseColumnMetadata) = JdbcColumnType(requireNotNull(column.jdbcType), requireNotNull(column.typeName), column.precision, column.scale, requireNotNull(column.nullable))
    private fun hash(value: Any): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(OlapJsonUtils.mapper().writeValueAsBytes(value)))
}
