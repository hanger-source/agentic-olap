package com.olap.prototype.management.governance

import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.calculation.CalculationBindings
import com.olap.prototype.connector.SourceExecutionException
import com.olap.prototype.connector.SourceProvider
import com.olap.prototype.engine.context.OlapQueryContext
import com.olap.prototype.engine.executable.*
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.governance.contract.*
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.management.warehouse.SourceRegistrationService
import com.olap.prototype.management.warehouse.WarehouseRegistryService
import com.olap.prototype.metric.MetricBusinessMeaning
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.*
import com.olap.prototype.query.contract.PublishedExecutableCatalog
import com.olap.prototype.release.contract.ReleaseCatalogRequest
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.warehouse.contract.JdbcConnectionSpec
import com.olap.prototype.warehouse.inspection.JdbcWarehouseInspector
import com.olap.prototype.warehouse.jdbc.JdbcSourceInspection
import com.olap.prototype.warehouse.onboarding.WarehouseDiscoveryCommand
import com.olap.prototype.warehouse.onboarding.WarehouseOnboardingService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

class PublishedSourceBindingTest {
    @Test
    fun publishesBothSourceBindingsThroughOnePhysicalIdentity(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val f = GovernanceOrderFixture(database, directory, listOf("INSERT INTO main.orders VALUES ('1',100,'A','2026-07-21','c1'),('2',40,'A','2026-07-21','c2'),('3',10,'B','2026-07-21','c3')"))
            f.seedDefinitions()
            f.activate("BUSINESS_LINE", "travel")
            val upgraded = JdbcConnectionSpec("duckdb", TestConnectors.ref("duckdb"), "duckdb", "jdbc:duckdb:${directory.resolve("upgraded.duckdb")}", null, null, "org.duckdb.DuckDBDriver")
            TestConnectors.open("duckdb", upgraded.jdbcUrl).use { connection -> connection.createStatement().use { sql ->
                sql.execute("CREATE TABLE main.orders (order_id VARCHAR PRIMARY KEY,pay_amount DECIMAL(18,2),destination VARCHAR NOT NULL,pay_time DATE NOT NULL,customer_id VARCHAR)")
                sql.execute("INSERT INTO main.orders VALUES ('1',900,'A','2026-07-21','c1'),('2',70,'A','2026-07-21','c2')")
            } }
            val originalColumns = f.physical.physicalTableColumnSnapshots(f.physicalId, 1)
            val publications = mutableListOf<PublishedExecutableCatalog>()
            val computations = mutableListOf<QueryComputation.Scope>()
            var metricId: String? = null
            var oldArtifact: Any? = null
            (1L..2L).forEach { version ->
                if (version == 2L) {
                    SourceRegistrationService(database, f.connectors).initializeAuthorizedSources(listOf(upgraded.source()))
                    val authorized = listOf(f.spec.source(), upgraded.source()).associateBy { it.bindingRef() }
                    val onboarding = WarehouseOnboardingService(f.physical, WarehouseRegistryService(database, f.warehouse, f.physical), authorized::get)
                    val discovery = onboarding.discover(WarehouseDiscoveryCommand("duckdb", upgraded.sourceIdentityHash(), null, "main", "orders", emptyList(), 10, emptyMap(), emptyMap(), "golden"),
                        RuntimeDeploymentLimits.CONTROL, JdbcSourceInspection(JdbcWarehouseInspector(f.connectors), f.connectors))
                    assertEquals("SUCCEEDED", discovery.snapshot.status, discovery.snapshot.errorMessage)
                    val observed = discovery.tables.single()
                    val definition = PhysicalTableCreateRequest("warehouse.orders", observed.tableName, "交易订单物理表", "新绑定明确采用", "交易", "data", "internal", "orders", "duckdb", "duckdb",
                        observed.catalogName, observed.schemaName, requireNotNull(observed.schemaHash), PhysicalTableCreateRequest.SourceConstraintsInput(observed.id, emptyList()), "golden", discovery.columns.map { column ->
                            PhysicalTableCreateRequest.ColumnInput(column.columnName, column.columnName, originalColumns.single { it.columnName == column.columnName }.dataType, "internal", requireNotNull(column.nullable), "真实调查字段",
                                com.olap.prototype.contract.JdbcColumnType(column.jdbcType, column.typeName, column.precision, column.scale, column.nullable))
                        })
                    val revised = f.physical.revisePhysicalTable(GovernanceRevisionRequest(f.physicalId, 1, definition))
                    assertEquals(f.physicalId, revised.physicalTable.id)
                    assertEquals(originalColumns.map { it.id }.toSet(), revised.physicalColumns.map { it.id }.toSet())
                    assertEquals(discovery.snapshot.id, f.physical.physicalTableSourceConstraints(f.physicalId, 2).discoverySnapshotId)
                    assertEquals(f.spec.sourceIdentityHash(), f.physical.physicalTableSourceConstraints(f.physicalId, 1).sourceIdentityHash)
                    f.activate("PHYSICAL_TABLE", f.physicalId, version)
                }
                val columns = f.physical.physicalTableColumnSnapshots(f.physicalId, version)
                val units = listOf("order_id", "pay_amount").map { name ->
                    val column = columns.single { it.columnName == name }
                    LogicalTableCreateRequest.LogicUnitInput(name, name, LogicUnitSourceKind.COLUMN, null, if (name == "order_id") LogicType.STRING else LogicType.NUMBER,
                        null, false, name == "order_id", false, false, name == "pay_amount", listOf(LogicalTableCreateRequest.SourceRefInput(column.id, column.currentVersion, LogicUnitSourceRefRole.DIRECT_SOURCE)))
                }
                val logical = LogicalTableCreateRequest(f.physicalId, version, "binding_orders", "绑定订单", LogicalTableRole.FACT, "精确采用来源", "data", "golden", units)
                if (version == 1L) f.logical.createLogicalTable(logical) else f.logical.reviseLogicalTable(GovernanceRevisionRequest("binding_orders", 1, logical))
                f.activate("LOGICAL_TABLE", "binding_orders", version)
                val topology = TopologyCreateRequest("orders", "绑定订单", "trade.binding", "来源采用", "binding_orders", version, "data", "精确绑定", "golden")
                if (version == 1L) f.topologies.createTopology(topology) else f.topologies.reviseTopology(GovernanceRevisionRequest("trade.binding.orders", 1, topology))
                f.activate("TOPOLOGY", "trade.binding.orders", version)
                val event = EventCreateRequest("trade.binding.orders", version, "observed", "订单", "trade", null, null,
                    EventIdentity(listOf(EventIdentity.LogicUnitRef("binding_orders.order_id", version)), emptyMap()), emptyMap(), emptyList(),
                    listOf(EventCreateRequest.LogicUnitRefInput("binding_orders.pay_amount", version)), "调查订单", "golden")
                if (version == 1L) f.events.createEvent(event) else f.events.reviseEvent(GovernanceRevisionRequest("trade.binding.orders.observed", 1, event))
                f.activate("EVENT", "trade.binding.orders.observed", version)
                val metric = MetricCreateRequest(
                    code = "amount",
                    displayName = "金额",
                    owner = "trade",
                    definition = MetricDefinition.FactMeasure(
                        event = com.olap.prototype.event.EventRef("trade.binding.orders.observed", version),
                        input = GovernanceOrderFixture.ref("binding_orders.pay_amount", version),
                        aggregation = MetricAggregation.SUM,
                        distinct = false,
                        privatePredicate = null,
                        roles = emptyMap(),
                        snapshotRule = null,
                        valueContract = GovernanceOrderFixture.money(),
                        aggregationRounding = MetricValueContract.Rounding.NONE
                    ),
                    description = "实收金额",
                    businessMeaning = MetricBusinessMeaning("来源订单金额", "元", "订单", emptyList()),
                    createdBy = "golden"
                )
                if (version == 1L) metricId = f.metrics.createMetric(metric).metric.id else f.metrics.reviseMetric(GovernanceRevisionRequest(requireNotNull(metricId), 1, metric))
                f.activate("METRIC", requireNotNull(metricId), version)
                val subject = SubjectAreaCreateRequest("travel", 1, "bindings", "绑定经营", "trade", "精确绑定", "golden")
                if (version == 1L) f.subjects.createSubjectArea(subject) else f.subjects.reviseSubjectArea(GovernanceRevisionRequest("travel.bindings", 1, subject))
                val adoption = f.adoptions.createSubjectAreaMetricRef(SubjectAreaMetricRefCreateRequest("travel.bindings", version, "amount", "金额",
                    f.metrics.metricBusinessMeaning(requireNotNull(metricId), version), requireNotNull(metricId), version, CalculationBindings.empty(), emptyMap(), "golden")).subjectAreaMetricRef
                val scenario = ScenarioCreateRequest("travel.bindings", version, "amount", "来源金额", "analyst", "明确采用", "golden")
                if (version == 1L) f.scenarios.createScenario(scenario) else f.scenarios.reviseScenario(GovernanceRevisionRequest("travel.bindings.amount", 1, scenario))
                f.scenarios.createScenarioMetricRef(ScenarioMetricRefCreateRequest("travel.bindings.amount", version, adoption.id, version, true, 1))
                f.activate("SUBJECT_AREA", "travel.bindings", version)
                f.builder().build(SubjectAreaReleaseBuildRequest("binding_upgrade", version.toString(), "来源采用", "travel.bindings", version, "golden"))
                publications += f.artifacts.loadExecutableCatalog(ReleaseCatalogRequest("travel.bindings.amount", version, "binding_upgrade", version.toString()))
                computations += QueryComputation.Scope(QueryComputation.Measures(listOf(QueryComputation.Invocation("measure", adoption.instance, "value", emptyMap(), emptyMap(), emptyMap(), DimensionCondition.Unrestricted())), emptyList(), null,
                    emptyMap(), DimensionCondition.Unrestricted(), DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(listOf("measure")), null), emptyMap(), emptyList())
                if (version == 1L) oldArtifact = f.artifacts.loadArtifact("binding_upgrade", "1")
            }
            assertEquals(oldArtifact, f.artifacts.loadArtifact("binding_upgrade", "1"))
            val providers = listOf(f.spec.source(), upgraded.source()).associate { it.bindingRef() to SourceProvider(it, f.connectors) }
            publications.indices.forEach { index ->
                val publication = f.artifacts.loadExecutableCatalog(ReleaseCatalogRequest("travel.bindings.amount", index + 1L, "binding_upgrade", (index + 1).toString()))
                val plan = compile(publication, computations[index])
                val frame = SourceExecutableSqlExecutor(providers::get, RuntimeDeploymentLimits.result, RuntimeDeploymentLimits.nativeMemoryBytes, null).execute(plan, OlapQueryContext("binding-query-$index"), RuntimeDeploymentLimits.CONTROL).frame
                assertEquals(0, BigDecimal(if (index == 0) "150" else "970").compareTo(BigDecimal(frame.rows.first().cells.first().value.toString())))
            }
            val missing = assertThrows(SourceExecutionException::class.java) {
                SourceExecutableSqlExecutor({ reference -> providers[reference]?.takeIf { it.bindingRef == upgraded.source().bindingRef() } },
                    RuntimeDeploymentLimits.result, RuntimeDeploymentLimits.nativeMemoryBytes, null).execute(compile(publications.first(), computations.first()), OlapQueryContext("binding-missing"), RuntimeDeploymentLimits.CONTROL)
            }
            assertEquals(f.spec.source().bindingRef(), missing.failure.binding)
        }
    }

    private fun compile(publication: PublishedExecutableCatalog, scope: QueryComputation.Scope): ExecutableLoweringResult {
        val bound = ExecutableSemanticBinder(TestConnectors.registry).bind(publication.catalog, ExecutableQueryRequest(ExecutableQueryRequest.FORMAT_VERSION, "binding", publication.catalog.manifest.catalogId, scope))
        val graph = ExecutableInputGraph.index(bound)
        return CalciteRelCompiler(TestConnectors.registry).compile(graph, ExecutableStageAnalysis.analyze(graph), ExecutableSourceResolver().resolve(publication.catalog, graph), OlapQueryContext("binding")).lowering
    }
}
