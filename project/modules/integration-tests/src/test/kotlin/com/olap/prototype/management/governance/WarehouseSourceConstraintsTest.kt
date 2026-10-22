package com.olap.prototype.management.governance

import com.olap.prototype.connector.SourceProvider
import com.olap.prototype.connector.SourceSqlRequest
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.engine.calcite.CalciteQueryCompiler
import com.olap.prototype.engine.calcite.FrozenCatalogSchema
import com.olap.prototype.engine.catalog.ExecutablePhysicalExecutionBinding
import com.olap.prototype.expression.CalciteSqlRendering
import com.olap.prototype.expression.SemanticNativeTypes
import com.olap.prototype.governance.contract.*
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.management.warehouse.*
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.connector.SourceExecutionException
import com.olap.prototype.warehouse.contract.*
import com.olap.prototype.warehouse.inspection.JdbcWarehouseInspector
import com.olap.prototype.warehouse.jdbc.JdbcSourceInspection
import com.olap.prototype.warehouse.onboarding.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.HexFormat
import java.util.UUID

class WarehouseSourceConstraintsTest {
    private val connectors = TestConnectors.registry
    private val inspection = JdbcSourceInspection(JdbcWarehouseInspector(connectors), connectors)
    private val control = RuntimeDeploymentLimits.CONTROL

    @Test
    fun adoptsCompleteNullableKeysAndSealedCollectionsAndRejectsChangedData(@TempDir directory: Path) {
        val spec = JdbcConnectionSpec("source", TestConnectors.ref("duckdb"), "duckdb", "jdbc:duckdb:${directory.resolve("sealed.duckdb")}", null, null, "org.duckdb.DuckDBDriver")
        TestConnectors.open("duckdb", spec.jdbcUrl).use { connection -> connection.createStatement().use { sql ->
            sql.execute("CREATE TABLE facts(tenant INTEGER,id INTEGER,label VARCHAR)")
            sql.execute("INSERT INTO facts VALUES (1,1,'A'),(2,1,'B'),(3,NULL,'C')")
        } }
        PlatformDatabase.inMemory().use { database ->
            val source = spec.source()
            register(database, source)
            val registry = WarehouseRegistryRepository(database)
            val assets = PhysicalAssetGovernanceService(database, registry)
            val onboarding = onboarding(database, registry, assets, source)
            val command = WarehouseDiscoveryCommand("source", spec.sourceIdentityHash(), null, "main", "facts", emptyList(), 10,
                emptyMap(), mapOf("facts" to listOf(SnapshotUniqueKey.Rule("registered_identity", listOf("tenant", "id")))), "deployment")
            val discovery = onboarding.discover(command, control, inspection)
            assertEquals("SUCCEEDED", discovery.snapshot.status, discovery.snapshot.errorMessage)
            val table = discovery.tables.first()
            val adopted = onboarding.register(registration(table.id, discovery.columns))
            val id = adopted.physicalTable.physicalTable.id
            val evidence = assets.physicalTableSourceConstraints(id, 1)
            val proof = assets.physicalTableUniqueKeys(id, 1).first()
            val originalBinding = binding(id, "1", "duckdb", table, discovery.columns, evidence, listOf(proof.key))
            val original = identityPlan(originalBinding)
            val provider = SourceProvider(source, connectors)
            fun execute(plan: SourceSqlRequest) = provider.execute(plan, control, RuntimeDeploymentLimits.result,
                RuntimeDeploymentLimits.nativeMemoryBytes) { _, _ -> }.frame
            assertEquals(3, execute(original).rows.size)

            val declaration = SealedSourceDataset(SealedSourceDataset.FORMAT_VERSION, "test-source-owner", "b".repeat(64), proof.key.anchor,
                SealedSourceDataset.Scope("Asia/Shanghai", LocalDate.parse("2026-01-01"), LocalDate.parse("2027-01-01"), LocalDateTime.parse("2026-12-31T23:59:59")),
                listOf(SealedSourceDataset.Collection("registered.facts", JdbcTableConstraints.Table(table.catalogName, table.schemaName, table.tableName),
                    listOf("all-registered-source-rows"))))
            val dataset = SourceDatasetRegistrationRequest("source", spec.sourceIdentityHash(), declaration)
            val collections = onboarding.registerSourceDataset(dataset, control, inspection)
            assertEquals(collections, onboarding.registerSourceDataset(dataset, control, inspection))
            assets.revisePhysicalTable(GovernanceRevisionRequest(id, 1, definition("duckdb", table, discovery.columns, collections.map { it.id })))
            val coveredEvidence = assets.physicalTableSourceConstraints(id, 2)
            assertTrue(evidence.sourceCollections.isEmpty())
            assertEquals(collections, coveredEvidence.sourceCollections)
            val covered = identityPlan(binding(id, "2", "duckdb", table, discovery.columns, coveredEvidence, assets.physicalTableUniqueKeys(id, 2).map { it.key }))
            assertEquals(3, execute(covered).rows.size)
            assets.updatePhysicalTableMetadata(PhysicalTableMetadataUpdateRequest(id, 2, "登记总体", "同一源", "data", "agent"))
            assertEquals(assets.physicalTableUniqueKeys(id, 1), assets.physicalTableUniqueKeys(id, 2))
            assertEquals(coveredEvidence, assets.physicalTableSourceConstraints(id, 3))

            TestConnectors.open("duckdb", spec.jdbcUrl).use { connection -> connection.createStatement().use {
                it.execute("INSERT INTO facts VALUES (3,NULL,'duplicate-null')")
            } }
            assertEquals("SOURCE_DATA_ANCHOR_DRIFT", assertThrows(SourceExecutionException::class.java) { execute(original) }.failure.code)
            assertEquals("SOURCE_COVERAGE_ANCHOR_DRIFT", assertThrows(SourceExecutionException::class.java) { execute(covered) }.failure.code)
            val rejected = onboarding.discover(command, control, inspection)
            assertEquals("FAILED", rejected.snapshot.status)
            assertTrue(registry.listDiscoveredTables(rejected.snapshot.id).isEmpty())
            assertEquals(proof, assets.physicalTableUniqueKeys(id, 1).first())
        }
    }

    @Test
    fun adoptsCompositeSourceFactsAndPreservesExactHistoryAcrossRevision(@TempDir directory: Path) {
        listOf("h2", "duckdb").forEach { dialect ->
            val spec = JdbcConnectionSpec("source", TestConnectors.ref(dialect), dialect,
                if (dialect == "h2") "jdbc:h2:mem:source_${UUID.randomUUID()};DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1" else "jdbc:duckdb:${directory.resolve("constraints.duckdb")}",
                if (dialect == "h2") "sa" else null, null, if (dialect == "h2") "org.h2.Driver" else "org.duckdb.DuckDBDriver")
            val source = spec.source()
            TestConnectors.open(dialect, spec.jdbcUrl, spec.username, "").use { connection -> connection.createStatement().use { sql ->
                sql.execute("CREATE SCHEMA source_test")
                sql.execute("CREATE TABLE source_test.parents (parent_no BIGINT, tenant_id BIGINT, PRIMARY KEY(parent_no, tenant_id))")
                sql.execute("CREATE TABLE source_test.facts (tenant_id BIGINT, record_id BIGINT, external_id BIGINT, parent_no BIGINT, amount DECIMAL(22,7), " +
                    "PRIMARY KEY(tenant_id, record_id), CONSTRAINT unique_external UNIQUE(external_id, tenant_id), " +
                    "CONSTRAINT parent_link FOREIGN KEY(parent_no, tenant_id) REFERENCES source_test.parents(parent_no, tenant_id))")
            } }
            PlatformDatabase.inMemory().use { database ->
                register(database, source)
                val registry = WarehouseRegistryRepository(database)
                val assets = PhysicalAssetGovernanceService(database, registry)
                val onboarding = onboarding(database, registry, assets, source)
                val command = WarehouseDiscoveryCommand("source", source.sourceIdentityHash(), null, "source_test", "facts", emptyList(), 10, emptyMap(), emptyMap(), "agent")
                val discovery = onboarding.discover(command, control, inspection)
                assertEquals("SUCCEEDED", discovery.snapshot.status, discovery.snapshot.errorMessage)
                val table = requireNotNull(registry.findDiscoveredTable(discovery.tables.first().id))
                assertNull(table.rowCount)
                assertEquals(listOf("tenant_id", "record_id"), table.constraints.primaryKeyColumns)
                assertEquals(listOf("parent_no", "tenant_id"), table.constraints.importedKeys.map { it.column })
                assertEquals(listOf(1, 2), table.constraints.importedKeys.map { it.keySequence })
                assertTrue(table.constraints.uniqueKeys.any { it.columns == listOf("external_id", "tenant_id") }, "$dialect: ${table.constraints}")
                val columns = registry.listDiscoveredColumns(table.id)
                // 来源证据是正式必填类型；这里核实际不存在的精确调查引用仍不能写入资产。
                val missing = definition(dialect, table, columns, emptyList(), discoveredTableId = "not-adopted")
                assertThrows(ProducerContractException::class.java) { assets.createPhysicalTable(missing) }
                assertTrue(assets.listPhysicalTables().isEmpty())
                val registered = onboarding.register(registration(table.id, columns))
                val id = registered.physicalTable.physicalTable.id
                assertEquals(1L, registered.physicalTable.physicalTable.currentVersion)
                val frozen = assets.physicalTableSourceConstraints(id, 1)
                assertEquals(table.constraints, frozen.constraints)
                assertEquals(discovery.snapshot.id, frozen.discoverySnapshotId)
                assertEquals(table.schemaHash, frozen.schemaHash)
                assertEquals(5, frozen.columnVersionRefs.size)
                assertTrue(frozen.columnVersionRefs.values.all { it.endsWith("@1") })
                val revised = assets.updatePhysicalTableMetadata(PhysicalTableMetadataUpdateRequest(id, 1, "同一源的业务名称", "完整定义", "data", "agent"))
                assertEquals(2L, revised.physicalTable.currentVersion)
                assertEquals(frozen, assets.physicalTableSourceConstraints(id, 1))
                assertEquals(frozen, assets.physicalTableSourceConstraints(id, 2))
                TestConnectors.open(dialect, spec.jdbcUrl, spec.username, "").use { connection -> connection.createStatement().use {
                    it.execute("ALTER TABLE source_test.facts ADD COLUMN added_value INTEGER")
                } }
                val later = onboarding.discover(command, control, inspection)
                assertEquals("SUCCEEDED", later.snapshot.status, later.snapshot.errorMessage)
                assertNotEquals(table.schemaHash, later.tables.first().schemaHash)
                assertEquals(registered.discoverySource, registry.findDiscoveredTable(table.id))
                assertEquals(frozen, assets.physicalTableSourceConstraints(id, 1))
                val invalid = definition(dialect, table, columns, emptyList(), schemaHash = "wrong-hash")
                assertThrows(RuntimeException::class.java) { assets.revisePhysicalTable(GovernanceRevisionRequest(id, 2, invalid)) }
                assertEquals(2L, requireNotNull(assets.findPhysicalTable(id)).currentVersion)
                assertEquals("DRAFT", assets.physicalTableSnapshot(id, 2).status)
                assertEquals(frozen, assets.physicalTableSourceConstraints(id, 2))
                assertThrows(RuntimeException::class.java) { assets.physicalTableSnapshot(id, 3) }
            }
        }
    }

    private fun register(database: PlatformDatabase, source: SourceConfiguration) {
        SourceRegistrationService(database, connectors).register(WarehouseDataSourceCreateRequest(source.dataSourceId, "调查源", source.connectorRef,
            source.dialectId, source.identityParameters, emptyMap(), source.credentialEnvironmentVariables, "agent"))
    }

    private fun onboarding(database: PlatformDatabase, registry: WarehouseRegistryRepository, assets: PhysicalAssetGovernanceService, source: SourceConfiguration) =
        WarehouseOnboardingService(assets, WarehouseRegistryService(database, registry, assets)) { reference -> source.takeIf { it.bindingRef() == reference } }

    private fun registration(tableId: String, columns: List<WarehouseDiscoveredColumnRecord>) = DiscoveredPhysicalTableRegistrationRequest(tableId,
        "facts", "事实记录", "完整源记录", "业务", "data", "internal", "source", "agent",
        columns.map { DiscoveredPhysicalTableRegistrationRequest.ColumnGovernance(it.columnName, it.columnName, "internal", "调查字段") })

    private fun definition(dialect: String, table: WarehouseDiscoveredTableRecord, columns: List<WarehouseDiscoveredColumnRecord>, collections: List<String>,
        discoveredTableId: String = table.id, schemaHash: String = requireNotNull(table.schemaHash)) = PhysicalTableCreateRequest("facts", table.tableName, "登记记录", "完整源记录",
        "业务", "data", "internal", "source", "source", dialect, table.catalogName, table.schemaName, schemaHash,
        PhysicalTableCreateRequest.SourceConstraintsInput(discoveredTableId, collections), "agent", columns.map {
            PhysicalTableCreateRequest.ColumnInput(it.columnName, it.columnName, it.typeName, "internal", requireNotNull(it.nullable), "源字段", columnType(it))
        })

    private fun columnType(column: WarehouseDiscoveredColumnRecord) = JdbcColumnType(column.jdbcType, column.typeName, column.precision, column.scale, column.nullable)

    private fun binding(id: String, version: String, dialect: String, table: WarehouseDiscoveredTableRecord, columns: List<WarehouseDiscoveredColumnRecord>,
        evidence: PhysicalSourceConstraints, uniqueKeys: List<SnapshotUniqueKey>): ExecutablePhysicalExecutionBinding {
        val names = columns.associate { evidence.columnVersionRefs.getValue(it.columnName) to it.columnName }
        val types = columns.associate { evidence.columnVersionRefs.getValue(it.columnName) to columnType(it) }
        return ExecutablePhysicalExecutionBinding("facts", "1", null, null, null, null, "facts", "1", id, version, "source", evidence.connectorRef,
            connectors.require(evidence.connectorRef).compilation.tablePath(table.catalogName, table.schemaName, table.tableName), dialect,
            table.catalogName, table.schemaName, table.tableName, evidence, uniqueKeys, emptyMap(), emptyMap(), names, types)
    }

    private fun identityPlan(binding: ExecutablePhysicalExecutionBinding): SourceSqlRequest {
        val inputs = CalciteQueryCompiler.inputs(listOf(binding), binding.physicalColumnBindings.keys, emptySet(), connectors)
        val builder = inputs.builder
        builder.scan(FrozenCatalogSchema.path(binding))
        builder.project(listOf(builder.field("tenant"), builder.field("id")), listOf("tenant", "id"), true).distinct()
        val types = builder.peek().rowType.fieldList.associate { it.name to SemanticNativeTypes.resultType(it.type) }
        val sql = CalciteSqlRendering.render(CalciteQueryCompiler.optimize(builder.build(), emptyList()), connectors.require(binding.connectorRef).compilation)
        return SourceSqlRequest("execute-query", sql.sql, emptyList(), mapOf("tenant" to "tenant", "id" to "id"), mapOf("tenant" to "租户", "id" to "身份"),
            types, inputs.sourceRequirements(), emptyList(), null)
    }
}
