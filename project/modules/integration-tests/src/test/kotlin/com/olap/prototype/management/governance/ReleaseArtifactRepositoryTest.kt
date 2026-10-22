package com.olap.prototype.management.governance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.olap.prototype.connector.JdbcAccess
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.event.EventRef
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.governance.contract.*
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.member.*
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.contract.*
import com.olap.prototype.release.catalog.*
import com.olap.prototype.release.catalog.capability.PublishedNativeTypes
import com.olap.prototype.release.catalog.capability.ReleaseQueryCapabilityRepository
import com.olap.prototype.release.contract.*
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.contract.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DatabaseMetaData
import java.time.Instant

class ReleaseArtifactRepositoryTest {
    private val connectors = TestConnectors.registry
    private val now = Instant.parse("2026-07-21T00:00:00Z")
    private val mapper = OlapJsonUtils.mapper()

    @Test
    fun reloadsFrozenCatalogAndCapabilitiesAndRejectsTamperedPublication(@TempDir directory: Path) {
        val member = memberSource()
        val catalog = releaseCatalog(directory, member)
        PlatformDatabase.openSingleInstance(directory.resolve("metadata")).use { database ->
            saveReleaseRoot(database)
            val candidate = MemberCandidate(member.source.id, 1, member.displayName, member.source.definition.result.entity,
                member.source.definition.result.role, member.source.definition.result.shape, member.source.definition.result.identitySlots,
                mapOf("id" to MemberCandidate.Property("订单记录", "record", ScalarValueType.NUMBER, null)),
                MemberQueryPermission(listOf("id"), emptyList(), emptyMap(), false, emptyList()), emptyMap(), emptyMap(), emptyMap(), null,
                emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
            val assembler = ReleaseExecutableCatalogAssembler(connectors)
            val assets = assembler.compileAssets(catalog, emptyList())
            val nativeTypes = PublishedNativeTypes(catalog.dimensionMappings, assets, connectors)
            val capability = QueryCapabilityHash.attach(QueryCapabilityView(
                QueryScenarioRef("trade_daily", 1, "交易日报", "rel_trade", "1", "交易发布"),
                SubjectAreaCapabilityContext("trade", 1, "trade:scenario:daily", "ACTIVE"), "rel_trade", "1", "交易发布",
                emptyList(), listOf(candidate), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                nativeTypes.produce(emptyList(), listOf(candidate), emptyList()), nativeTypes.definitions(), null))
            val artifacts = ReleaseArtifactRepository(database)
            artifacts.saveCatalog(catalog, assembler.assemble(catalog, assets, capability), now)
            saveFrozenObjects(database, catalog.objectVersionRefs)
            artifacts.finalizeArtifact("rel_trade", "1", "trade", 1, "tester", now)
            ReleaseQueryCapabilityRepository(database).save(capability, now)
        }
        PlatformDatabase.openSingleInstance(directory.resolve("metadata")).use { database ->
            val artifacts = ReleaseArtifactRepository(database)
            val request = ReleaseCatalogRequest("trade_daily", 1, "rel_trade", "1")
            val loaded = artifacts.loadCatalog(request)
            val capability = ReleaseQueryCapabilityRepository(database).load(QueryCapabilityRequest("trade_daily", 1, "rel_trade", "1"))
            assertEquals("trade_daily", loaded.scenarioId)
            assertEquals("trade", capability.subjectAreaCapabilityContext.subjectAreaId)
            val executable = artifacts.loadExecutableCatalog(request)
            assertEquals("rel_trade", executable.coordinate.releaseId)
            assertEquals("trade_daily", executable.coordinate.scenarioId)
            val different = mapper.valueToTree<ObjectNode>(loaded).put("releaseId", "other_release")
            val differentJson = mapper.writeValueAsString(different)
            assertThrows(ProducerContractException::class.java) {
                artifacts.saveCatalog(mapper.readValue(differentJson, ReleaseCatalogView::class.java), executable, now)
            }

            // 修改实际存储且同步伪造producer摘要；坐标核验必须独立发现，不只比较摘要。
            database.inTransaction {
                database.connection().use { connection ->
                    val savepoint = connection.setSavepoint()
                    try {
                        connection.prepareStatement("""
                            UPDATE release_executable_catalog SET producer_catalog_json=?, producer_catalog_hash=?
                            WHERE release_id=? AND release_version=? AND scenario_id=? AND scenario_version=?
                        """.trimIndent()).use { statement ->
                            statement.setString(1, differentJson)
                            statement.setString(2, artifacts.contentHash(differentJson))
                            statement.setString(3, request.releaseId)
                            statement.setString(4, request.releaseVersion)
                            statement.setString(5, request.scenarioId)
                            statement.setLong(6, request.scenarioVersion)
                            assertEquals(1, statement.executeUpdate())
                        }
                        connection.prepareStatement("""
                            UPDATE release_executable_catalog SET executable_catalog_json='{}'
                            WHERE release_id=? AND release_version=? AND scenario_id=? AND scenario_version=?
                        """.trimIndent()).use { statement ->
                            statement.setString(1, request.releaseId)
                            statement.setString(2, request.releaseVersion)
                            statement.setString(3, request.scenarioId)
                            statement.setLong(4, request.scenarioVersion)
                            assertEquals(1, statement.executeUpdate())
                        }
                        assertThrows(ProducerContractException::class.java) { artifacts.loadCatalog(request) }
                        assertThrows(ProducerContractException::class.java) { artifacts.loadExecutableCatalog(request) }
                        assertThrows(ProducerContractException::class.java) { artifacts.catalogHash(request) }
                    } finally { connection.rollback(savepoint) }
                }
            }
            artifacts.loadCatalog(request)
            artifacts.loadExecutableCatalog(request)
        }
    }

    /** 产物仓储试验只需要精确根记录；绕过治理应用以独立观察存储重启/完整性边界。 */
    private fun saveReleaseRoot(database: PlatformDatabase) {
        val businessLines = BusinessLineGovernanceRepository(database)
        businessLines.saveBusinessLine(BusinessLineRecord("travel", "travel", "旅游", "旅游", "platform", "ACTIVE", 1, "tester", now, "tester", now))
        businessLines.saveBusinessLineVersion(BusinessLineVersionRecord("travel:v1", "travel", 1, "ACTIVE", mapper.writeValueAsString(mapOf(
            "businessLineId" to "travel", "code" to "travel", "displayName" to "旅游", "description" to "旅游", "owner" to "platform", "status" to "ACTIVE")),
            "{}", null, "tester", now, "tester", now, null, null, null, null))
        val subjects = SubjectAreaGovernanceRepository(database)
        subjects.saveSubjectArea(SubjectAreaRecord("trade", "trade", "交易", "travel", 1, "交易", "data", "ACTIVE", 1, "tester", now, "tester", now))
        subjects.saveSubjectAreaVersion(SubjectAreaVersionRecord("trade:v1", "trade", 1, "ACTIVE", mapper.writeValueAsString(mapOf(
            "subjectAreaId" to "trade", "code" to "trade", "displayName" to "交易", "businessLineId" to "travel", "businessLineVersion" to 1,
            "description" to "交易", "owner" to "data", "status" to "ACTIVE")), "{}", null, "tester", now, "tester", now, null, null, null, null))
        val scenarios = ScenarioGovernanceRepository(database)
        scenarios.saveScenario(ScenarioRecord("trade_daily", "trade", 1, "daily", "交易日报", "交易日报", "analyst", "ACTIVE", 1, "tester", now, "tester", now))
        scenarios.saveScenarioVersion(ScenarioVersionRecord("trade_daily:v1", "trade_daily", 1, "ACTIVE", mapper.writeValueAsString(mapOf(
            "scenarioId" to "trade_daily", "subjectAreaId" to "trade", "subjectAreaVersion" to 1, "code" to "daily", "displayName" to "交易日报",
            "description" to "交易日报", "owner" to "analyst", "status" to "ACTIVE")), "{}", null, "tester", now, "tester", now, null, null, null, null))
        CanonicalReleaseRepository(database).saveRelease(CanonicalReleaseRepository.SubjectAreaReleaseRecord("rel_trade:1", "rel_trade", "交易发布", "trade", "1", 1, "BUILT", "tester", now))
    }

    private fun saveFrozenObjects(database: PlatformDatabase, references: List<ReleaseObjectRef>) {
        database.connection().use { connection -> connection.prepareStatement("""
            INSERT INTO release_object_ref
              (id,release_id,release_version,object_type,object_id,object_version,object_code,object_display_name,ref_role,snapshot_summary_json,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """.trimIndent()).use { statement ->
            references.forEach { reference ->
                statement.setString(1, reference.id); statement.setString(2, reference.releaseId); statement.setString(3, reference.releaseVersion)
                statement.setString(4, reference.objectType); statement.setString(5, reference.objectId); statement.setLong(6, reference.objectVersion)
                statement.setString(7, reference.objectCode); statement.setString(8, reference.objectDisplayName); statement.setString(9, reference.refRole)
                statement.setString(10, reference.snapshotSummaryJson); statement.setObject(11, reference.createdAt); statement.addBatch()
            }
            statement.executeBatch()
        } }
    }

    private fun memberSource(): PublishedMemberSource {
        val output = MemberSourceDefinition.PropertyOutput("订单记录", "record", ScalarValueType.NUMBER, "orders.id", 1, RelationAccessPath(emptyList()), null)
        val definition = MemberSourceDefinition.Projection(EventRef("orders", 1), MemberResult("订单记录", "record", MemberShape.RECORDS,
            listOf("id"), mapOf("id" to output), emptyMap(), null, emptyMap(), null))
        return PublishedMemberSource(MemberSource.Projection("order_records", 1, definition,
            mapOf("id" to SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, "orders.id", 1))), "trade", "order_records", "订单记录")
    }

    private fun releaseCatalog(directory: Path, member: PublishedMemberSource): ReleaseCatalogView {
        val spec = JdbcConnectionSpec("source", TestConnectors.ref("h2"), "h2", "jdbc:h2:file:${directory.resolve("source")};DATABASE_TO_UPPER=false", "sa", null, "org.h2.Driver")
        val unit = TestConnectors.open("h2", spec.jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { sql -> sql.execute("CREATE TABLE orders(id INTEGER PRIMARY KEY)"); sql.execute("INSERT INTO orders VALUES (1)") }
            val metadata = connection.metaData
            val coordinate = JdbcTableConstraints.Table(connection.catalog, connection.schema, "orders")
            val constraints = (connectors.require(spec.connectorRef).access as JdbcAccess).jdbc.constraints(connection, coordinate, RuntimeDeploymentLimits.CONTROL)
            val type = metadata.getColumns(coordinate.catalog, coordinate.schema, "orders", "id").use { columns ->
                check(columns.next()) { "actual order identifier is missing" }
                JdbcColumnType(columns.getInt("DATA_TYPE"), columns.getString("TYPE_NAME"), columns.getInt("COLUMN_SIZE"), columns.getInt("DECIMAL_DIGITS"),
                    columns.getInt("NULLABLE") == DatabaseMetaData.columnNullable)
            }
            val evidence = PhysicalSourceConstraints("restart-source-description", PublicationContentHash.compute(mapper.valueToTree<JsonNode>(mapOf("type" to type, "constraints" to constraints))),
                spec.sourceIdentityHash(), spec.connectorRef, metadata.databaseProductName, metadata.databaseProductVersion, metadata.driverName, metadata.driverVersion,
                constraints, mapOf("id" to "orders.id.physical@1"), emptyList(), StructureProvenance(StructureProvenance.Kind.DATABASE_CATALOG, "integration-test-catalog", "fixture-v1"))
            LogicUnitExecutionSnapshot(LogicUnitExecutionSnapshot.FORMAT_VERSION, "orders_binding", "1", "orders.id", 1, "NUMBER", null,
                "orders", 1, "orders_physical", 1, "source", coordinate.catalog, coordinate.schema, "orders", "h2",
                mapOf("orders.id@1" to mapper.valueToTree<JsonNode>(SemanticExpression.Reference(SemanticExpression.ReferenceKind.PHYSICAL_COLUMN, "orders.id.physical", 1))),
                mapOf("orders.id.physical@1" to "id"), mapOf("orders.id.physical@1" to type), emptyMap(), evidence)
        }
        val event = EventExecutionSnapshot("orders", 1, null, null, EventIdentity(listOf(EventIdentity.LogicUnitRef("orders.id", 1)), emptyMap()), emptyMap(), null)
        val topology = TopologyExecutionSnapshot("orders", 1, "orders", "订单记录", "orders", 1, emptyList())
        return ReleaseCatalogView("trade_daily", 1, "交易日报", "rel_trade", "1", "交易发布", listOf(
            reference("LOGIC_UNIT", "orders.id", unit), reference("EVENT", "orders", event), reference("TOPOLOGY", "orders", topology),
            reference("MEMBER_SOURCE", member.source.id, member)), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }

    private fun reference(kind: String, id: String, content: Any) = ReleaseObjectRef("$kind:$id", "rel_trade", "1", kind, id, 1, id, id,
        ReleaseObjectKind.require(kind).refRole, mapper.writeValueAsString(content), now)
}
