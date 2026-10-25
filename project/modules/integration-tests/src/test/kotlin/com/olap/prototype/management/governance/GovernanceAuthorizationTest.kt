package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.governance.application.GovernanceApplication
import com.olap.prototype.governance.application.GovernanceOperations
import com.olap.prototype.governance.contract.*
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.management.warehouse.SourceRegistrationService
import com.olap.prototype.management.warehouse.WarehouseRegistryRepository
import com.olap.prototype.management.warehouse.WarehouseRegistryService
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.WarehouseSourceInspection
import com.olap.prototype.warehouse.contract.*
import com.olap.prototype.warehouse.inspection.*
import com.olap.prototype.warehouse.onboarding.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Types
import java.util.concurrent.atomic.AtomicInteger

class GovernanceAuthorizationTest {
    private val connectors = TestConnectors.registry
    private val control = RuntimeDeploymentLimits.CONTROL

    @Test
    fun typedSourceAuthorizationRejectsBeforeStructureWritesOrSourceInspection(@TempDir directory: Path) {
        PlatformDatabase.openSingleInstance(directory).use { database ->
            val source = JdbcConnectionSpec("business", TestConnectors.ref("h2"), "h2", "jdbc:h2:mem:source-authorization;DATABASE_TO_UPPER=false", null, null, "org.h2.Driver").source()
            SourceRegistrationService(database, connectors).initializeAuthorizedSources(listOf(source))
            val registry = WarehouseRegistryRepository(database)
            val assets = PhysicalAssetGovernanceService(database, registry)
            val sources = mapOf(source.bindingRef() to source)
            val onboarding = WarehouseOnboardingService(assets, WarehouseRegistryService(database, registry, assets), sources::get, com.olap.prototype.management.warehouse.PhysicalSourceAdoptionService(registry, assets))
            val operations = GovernanceOperations(GovernanceApplication(database, connectors), sources, onboarding)
            val inspectionCalls = AtomicInteger()
            val inspection = DeniedInspection { inspectionCalls.incrementAndGet() }
            val structure = SourceStructureRegistrationRequest(source.dataSourceId, source.sourceIdentityHash(), null, "main", "business-catalog", "1", listOf(
                SourceStructureRegistrationRequest.Table(WarehouseTableSummary(WarehouseTableRef(null, "main", "orders"), "TABLE", null),
                    listOf(WarehouseColumnMetadata("id", Types.INTEGER, "INTEGER", 1, false, 10, 0, false, false, null, null)),
                    JdbcTableConstraints(listOf("id"), emptyList(), emptyList(), emptyList()))), "alice")
            val discovery = WarehouseDiscoveryCommand(source.dataSourceId, source.sourceIdentityHash(), null, "main", "orders", emptyList(), 10, emptyMap(), emptyMap(), "alice")
            val denySource: (String) -> Unit = { if (it == source.dataSourceId) throw SecurityException("来源未授权") }
            assertThrows(SecurityException::class.java) {
                operations.dispatchAuthenticated("register-source-structure", structure, control, "alice", inspection, denySource)
            }
            assertThrows(SecurityException::class.java) {
                operations.dispatchAuthenticated("discover-source", discovery, control, "alice", inspection, denySource)
            }
            assertEquals(0, inspectionCalls.get())
            listOf("warehouse_discovery_snapshot", "warehouse_discovered_table", "warehouse_discovered_column").forEach { assertEquals(0L, countAll(database, it)) }
            // 同一有效请求准入后确实写入，拒绝不能由坏输入或缺绑定冒充。
            operations.dispatchAuthenticated("register-source-structure", structure, control, "alice", inspection) { }
            listOf("warehouse_discovery_snapshot", "warehouse_discovered_table", "warehouse_discovered_column").forEach { assertEquals(1L, countAll(database, it)) }
            assertEquals(0, inspectionCalls.get())
        }
    }

    @Test
    fun authenticatedGovernanceRejectsForgedCreatorsNestedRevisionsAndLifecycleActors(@TempDir directory: Path) {
        PlatformDatabase.openSingleInstance(directory).use { database ->
            val registry = WarehouseRegistryRepository(database)
            val assets = PhysicalAssetGovernanceService(database, registry)
            val onboarding = WarehouseOnboardingService(assets, WarehouseRegistryService(database, registry, assets), { null }, com.olap.prototype.management.warehouse.PhysicalSourceAdoptionService(registry, assets))
            val operations = GovernanceOperations(GovernanceApplication(database, connectors), emptyMap(), onboarding)
            val inspection = DeniedInspection { }
            fun dispatch(name: String, value: Any, subject: String = "alice"): Any = operations.dispatchAuthenticated(name,
                value, control, subject, inspection) { }
            val create = BusinessLineCreateRequest("commerce", "商业", "经营团队", "明确口径", "alice")
            assertThrows(SecurityException::class.java) { dispatch("create-business-line", create, "bob") }
            val lines = BusinessLineGovernanceService(database)
            assertTrue(lines.listBusinessLines().isEmpty())
            dispatch("create-business-line", create)
            assertThrows(SecurityException::class.java) {
                dispatch("revise-business-line", GovernanceRevisionRequest("commerce", 1, BusinessLineCreateRequest("commerce", "商业", "经营团队", "嵌套伪造创建者", "bob")))
            }
            assertEquals(1L, lines.listBusinessLines().first().currentVersion)
            assertThrows(SecurityException::class.java) {
                dispatch("activate", GovernanceVersionActivationRequest(GovernanceActivationObjectType.BUSINESS_LINE, "commerce", 1, "bob", "伪造批准人"))
            }
            dispatch("activate", GovernanceVersionActivationRequest(GovernanceActivationObjectType.BUSINESS_LINE, "commerce", 1, "alice", "明确批准"))
            assertThrows(SecurityException::class.java) {
                dispatch("deprecate", GovernanceVersionDeprecationRequest(GovernanceActivationObjectType.BUSINESS_LINE, "commerce", 1, "bob", "伪造废弃人"))
            }
            assertEquals("ACTIVE", lines.businessLineSnapshot("commerce", 1).status)
            dispatch("create-subject-area", SubjectAreaCreateRequest("commerce", 1, "trade", "交易", "经营团队", "交易分析", "alice"))
            dispatch("create-analysis-dimension", AnalysisDimensionCreateRequest("commerce.trade", 1, "city", "城市", "城市分析", "city", "城市", "alice"))
            val alignment = AnalysisDimensionValueAlignmentCreateRequest("commerce.trade.city", 1, "city", "MANUALLY_CONFIRMED",
                "{\"authority\":\"城市编码负责人\",\"scope\":\"统一城市编码\"}", "bob")
            val dimensions = AnalysisDimensionGovernanceRepository(database)
            assertThrows(SecurityException::class.java) { dispatch("create-analysis-dimension-value-alignment", alignment) }
            assertTrue(dimensions.listAnalysisDimensionValueAlignments("commerce.trade.city", 1).isEmpty())
            dispatch("create-analysis-dimension-value-alignment", alignment.copy(confirmedBy = "alice"))
            val saved = dimensions.listAnalysisDimensionValueAlignments("commerce.trade.city", 1)
            assertEquals(1, saved.size)
            assertEquals("alice", saved.first().confirmedBy)
            val page = operations.read("list-business-lines", GovernanceReadRequests.PageInput(0, 50), control) { } as GovernanceReadRequests.Page<*>
            assertEquals(1, page.items.size)
        }
    }

    private fun countAll(database: PlatformDatabase, table: String): Long = database.connection().use { connection -> connection.createStatement().use { sql ->
        sql.executeQuery("SELECT count(*) FROM $table").use { check(it.next()); it.getLong(1) }
    } }

    /** 到达来源访问就失败，从而区分准入拒绝与执行后才拒绝。 */
    private class DeniedInspection(private val onDiscover: () -> Unit) : WarehouseSourceInspection {
        override fun discover(request: WarehouseSourceInspection.DiscoveryRequest, control: WarehouseExecutionControl): WarehouseSourceInspection.Discovery {
            onDiscover(); error("未获来源授权时不能调查来源")
        }
        override fun preview(request: WarehousePreviewRequest, control: WarehouseExecutionControl): WarehousePreview = error("结构登记或发现不应预览数据")
        override fun verifySealedDataset(connection: SourceConfiguration, declaration: SealedSourceDataset, control: WarehouseExecutionControl): Unit = error("结构登记或发现不应核验封存数据")
    }
}
