package com.olap.prototype.management.governance

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.calculation.CalculationBindings
import com.olap.prototype.connector.SourceExecutionException
import com.olap.prototype.connector.SourceProvider
import com.olap.prototype.engine.context.OlapQueryContext
import com.olap.prototype.engine.executable.*
import com.olap.prototype.governance.contract.*
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.management.warehouse.SourceRegistrationService
import com.olap.prototype.management.warehouse.WarehouseRegistryService
import com.olap.prototype.management.warehouse.PhysicalSourceAdoptionService
import com.olap.prototype.query.*
import com.olap.prototype.query.contract.PublishedExecutableCatalog
import com.olap.prototype.release.contract.ReleaseCatalogRequest
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.warehouse.contract.*
import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.warehouse.inspection.JdbcWarehouseInspector
import com.olap.prototype.warehouse.jdbc.JdbcSourceInspection
import com.olap.prototype.warehouse.onboarding.WarehouseDiscoveryCommand
import com.olap.prototype.warehouse.onboarding.WarehouseOnboardingService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

class PublishedSourceBindingTest {
    @Test
    fun publishesBothSourceBindingsThroughOnePhysicalIdentity(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val f = GovernanceOrderFixture(database, directory, listOf("INSERT INTO main.orders VALUES ('1',100,'A','2026-07-21','c1'),('2',40,'A','2026-07-21','c2'),('3',10,'B','2026-07-21','c3')"))
            f.seedDefinitions()
            f.approveDefinitions()
            val physical = PhysicalTableRef(f.physicalId, 1)
            val originalContract = f.physical.readPhysicalDataContract(physical)
            val initialAdoptions = f.sourceAdoptions
            f.subjects.createSubjectArea(SubjectAreaCreateRequest("travel", 1, "bindings", "绑定经营", "trade", "同一业务定义明确选择执行来源", "golden"))
            val adoption = f.adoptions.createSubjectAreaMetricRef(SubjectAreaMetricRefCreateRequest("travel.bindings", 1, "amount", "金额",
                f.metrics.metricBusinessMeaning(f.paymentId, 1), f.paymentId, 1, CalculationBindings.empty(), emptyMap(), "golden")).subjectAreaMetricRef
            f.scenarios.createScenario(ScenarioCreateRequest("travel.bindings", 1, "amount", "来源金额", "analyst", "明确采用", "golden"))
            f.scenarios.createScenarioMetricRef(ScenarioMetricRefCreateRequest("travel.bindings.amount", 1, adoption.id, 1, true, 1))
            f.activate("SUBJECT_AREA", "travel.bindings")
            fun build(version: String, sources: List<SourceAdoptionRef>) = f.builder().build(
                SubjectAreaReleaseBuildRequest("binding_upgrade", version, "来源采用", "travel.bindings", 1, "golden", sources))
            fun publication(version: String) = f.artifacts.loadExecutableCatalog(ReleaseCatalogRequest("travel.bindings.amount", 1, "binding_upgrade", version))
            val computation = Calculation.Scope(Calculation.Measures(listOf(
                Calculation.Call("measure", Calculation.Instance(adoption.instance), "value", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()), emptyMap(), emptyMap())), emptyList(), emptyList(), null, emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()), DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(listOf("measure")), null), emptyMap(), emptyList())
            build("1", initialAdoptions)
            val originalArtifact = f.artifacts.loadArtifact("binding_upgrade", "1")
            val originalPublishedBytes = ProtoBytes.encode(GovernanceProto.toProto(publication("1")))

            // 独立部署使用相同 catalog/table 坐标，仍须兑现同一份 P。
            val alternateRoot = Files.createDirectory(directory.resolve("alternate"))
            val alternate = JdbcConnectionSpec("duckdb", TestConnectors.ref("duckdb"), "duckdb", "jdbc:duckdb:${alternateRoot.resolve("warehouse.duckdb")}", null, null, "org.duckdb.DuckDBDriver")
            TestConnectors.open("duckdb", alternate.jdbcUrl).use { connection -> connection.createStatement().use { sql ->
                sql.execute("CREATE TABLE main.orders (order_id VARCHAR PRIMARY KEY,pay_amount DECIMAL(18,2),destination VARCHAR NOT NULL,pay_time DATE NOT NULL,customer_id VARCHAR,implementation_note VARCHAR)")
                sql.execute("INSERT INTO main.orders VALUES ('1',900,'A','2026-07-21','c1','B'),('2',70,'A','2026-07-21','c2','B')")
            } }
            SourceRegistrationService(database, f.connectors).initializeAuthorizedSources(listOf(alternate.source()))
            val authorized = listOf(f.spec.source(), alternate.source()).associateBy { it.bindingRef() }
            val onboarding = WarehouseOnboardingService(f.physical, WarehouseRegistryService(database, f.warehouse, f.physical), authorized::get,
                PhysicalSourceAdoptionService(f.warehouse, f.physical))
            val discovery = onboarding.discover(WarehouseDiscoveryCommand("duckdb", alternate.sourceIdentityHash(), null, "main", "orders", emptyList(), 10, emptyMap(), emptyMap(), "golden"),
                RuntimeDeploymentLimits.CONTROL, JdbcSourceInspection(JdbcWarehouseInspector(f.connectors), f.connectors))
            assertEquals("SUCCEEDED", discovery.snapshot.status, discovery.snapshot.errorMessage)
            val alternateAdoptions = onboarding.registerSourceAdoptions(SourceAdoptionRegistrationRequest(listOf(physical), discovery.snapshot.id, "golden"))
            assertEquals(alternateAdoptions.references, onboarding.readSourceAdoptions(SourceAdoptionReadRequest(discovery.snapshot.id)).references)
            build("2", alternateAdoptions.references)

            val missing = assertThrows(ReleaseClosureConflictException::class.java) { build("3", emptyList()) }
            assertEquals("SOURCE_ADOPTION_MISSING", missing.code)
            assertEquals(f.physicalId, missing.coordinate["physicalTableId"])
            val conflicting = assertThrows(ReleaseClosureConflictException::class.java) { build("4", initialAdoptions + alternateAdoptions.references) }
            assertEquals("SOURCE_ADOPTION_AMBIGUOUS", conflicting.code)
            assertEquals((initialAdoptions + alternateAdoptions.references).map { it.id }.toSet(),
                (conflicting.coordinate["sourceAdoptions"] as List<*>).toSet())
            listOf("3", "4").forEach { version -> assertThrows(ProducerContractException::class.java) { f.artifacts.loadArtifact("binding_upgrade", version) } }

            assertEquals(originalContract, f.physical.readPhysicalDataContract(physical))
            assertEquals(originalArtifact, f.artifacts.loadArtifact("binding_upgrade", "1"))
            assertArrayEquals(originalPublishedBytes, ProtoBytes.encode(GovernanceProto.toProto(publication("1"))))
            val providers = authorized.mapValues { SourceProvider(it.value, f.connectors) }
            listOf("150", "970").forEachIndexed { index, expected ->
                val plan = compile(publication((index + 1).toString()), computation)
                val frame = SourceExecutableSqlExecutor(providers::get, RuntimeDeploymentLimits.result, RuntimeDeploymentLimits.nativeMemoryBytes, null)
                    .execute(plan, OlapQueryContext("binding-query-$index"), RuntimeDeploymentLimits.CONTROL).frame
                assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(frame.rows.single().cells.single().value.toString())))
            }
            val unavailable = assertThrows(SourceExecutionException::class.java) {
                SourceExecutableSqlExecutor({ reference -> providers[reference]?.takeIf { it.bindingRef == alternate.source().bindingRef() } },
                    RuntimeDeploymentLimits.result, RuntimeDeploymentLimits.nativeMemoryBytes, null)
                    .execute(compile(publication("1"), computation), OlapQueryContext("binding-missing"), RuntimeDeploymentLimits.CONTROL)
            }
            assertEquals("SOURCE_BINDING_NOT_DEPLOYED", unavailable.failure.code)
            assertEquals(f.spec.source().bindingRef(), unavailable.failure.binding)
            assertEquals(SourceExecutionState.NOT_SUBMITTED, unavailable.failure.request.execution)
        }
    }

    private fun compile(publication: PublishedExecutableCatalog, scope: Calculation.Scope): ExecutableLoweringResult {
        val bound = ExecutableSemanticBinder(TestConnectors.registry).bind(publication.catalog, ExecutableQueryRequest(ExecutableQueryRequest.FORMAT_VERSION, "binding", publication.catalog.manifest.catalogId, scope))
        val graph = ExecutableInputGraph.index(bound)
        return CalciteRelCompiler(TestConnectors.registry).compile(graph, ExecutableStageAnalysis.analyze(graph, TestConnectors.registry), ExecutableSourceResolver().resolve(publication.catalog, graph), OlapQueryContext("binding")).lowering
    }
}
