package com.olap.prototype.management.governance

import com.olap.prototype.calculation.*
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.event.EventRef
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.governance.contract.*
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.member.*
import com.olap.prototype.query.*
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.definition.*
import com.olap.prototype.release.catalog.JdbcPublishedQueryEntryProvider
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.warehouse.inspection.WarehouseTableInspectionRequest
import com.olap.prototype.warehouse.inspection.WarehouseTableRef
import com.olap.prototype.warehouse.inspection.JdbcWarehouseInspector
import com.olap.prototype.warehouse.jdbc.JdbcSourceInspection
import com.olap.prototype.warehouse.onboarding.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PublishedAncestorRelationsTest {
    @Test
    fun authorizesAndExecutesDeduplicatedAncestorsFromRegisteredEdges(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val f = GovernanceOrderFixture(database, directory); f.seed()
            TestConnectors.open("duckdb", f.spec.jdbcUrl).use { connection -> connection.createStatement().use { sql ->
                sql.execute("CREATE TABLE staff_hierarchy(record_id INTEGER PRIMARY KEY,employee_id VARCHAR NOT NULL,manager_id VARCHAR)")
                sql.execute("INSERT INTO staff_hierarchy VALUES (1,'e1','e2'),(2,'e1','e2'),(3,'e1','e3'),(4,'e2','e4'),(5,'e3','e4'),(6,'e4',NULL),(7,'e5','e99'),(8,'e6',NULL)")
            } }
            val inspection = JdbcSourceInspection(JdbcWarehouseInspector(f.connectors), f.connectors)
            val discovery = f.onboarding.discover(WarehouseDiscoveryCommand("duckdb", f.spec.sourceIdentityHash(), null, "main", "staff_hierarchy", emptyList(), 10,
                emptyMap(), emptyMap(), "golden"), RuntimeDeploymentLimits.CONTROL, inspection)
            assertEquals("SUCCEEDED", discovery.snapshot.status, discovery.snapshot.errorMessage)
            val table = discovery.tables.single()
            val adopted = f.physical.createPhysicalTable(PhysicalTableCreateRequest("staff.hierarchy", "staff_hierarchy", "人员汇报关系", "独立关联记录", "人事", "data", "internal",
                "staff", "duckdb", table.catalogName, table.schemaName,
                PhysicalTableCreateRequest.SourceAdoptionInput(table.id, emptyList()), "golden", discovery.columns.map {
                    PhysicalTableCreateRequest.ColumnInput(it.columnName, it.columnName, it.typeName, "internal", requireNotNull(it.nullable), "源记录字段",
                        com.olap.prototype.contract.JdbcColumnType(it.jdbcType, it.typeName, it.precision, it.scale, it.nullable))
                }))
            f.sourceAdoptions += adopted.sourceAdoptions.references
            f.activate("PHYSICAL_TABLE", adopted.physicalTable.id)
            val columns = adopted.physicalColumns.associate { it.columnName to it.id }
            fun unit(code: String, column: String, type: LogicType, primary: Boolean = false) = LogicalTableCreateRequest.LogicUnitInput(code, code, LogicUnitSourceKind.COLUMN,
                null, type, null, false, primary, false, true, false,
                listOf(LogicalTableCreateRequest.SourceRefInput(columns.getValue(column), 1, LogicUnitSourceRefRole.DIRECT_SOURCE)))
            f.logical.createLogicalTable(LogicalTableCreateRequest(adopted.physicalTable.id, 1, "staff_reporting", "人员汇报登记", LogicalTableRole.FACT,
                "每行独立的汇报关联", "data", "golden", listOf(unit("record", "record_id", LogicType.NUMBER, true), unit("employee", "employee_id", LogicType.STRING), unit("manager", "manager_id", LogicType.STRING))))
            val topology = f.topologies.createTopology(TopologyCreateRequest("reporting", "人员汇报关系", "staff.reporting", "汇报关系实现空间", "staff_reporting", 1, "data", "关联记录主干", "golden")).topology
            val event = f.events.createEvent(EventCreateRequest(topology.id, 1, "registered", "汇报关联登记", "人事", null, null,
                EventIdentity(listOf(EventIdentity.LogicUnitRef("staff_reporting.record", 1)), emptyMap()), emptyMap(), listOf("record", "employee", "manager").map {
                    EventCreateRequest.LogicUnitRefInput("staff_reporting.$it", 1) }, emptyList(), "无主要发生时间", "golden")).event
            val local = RelationAccessPath(emptyList())
            val member = f.adoptions.createMemberSource(MemberSourceCreateRequest(GovernanceOrderFixture.SUBJECT, 1, "staff_reporting", "人员汇报关联",
                MemberSourceDefinition.Projection(EventRef(event.id, 1), MemberResult("汇报关联", "reporting_edge", MemberShape.RECORDS, listOf("record"),
                    mapOf("record" to MemberSourceDefinition.PropertyOutput("记录", "record", ScalarValueType.NUMBER, "staff_reporting.record", 1, local, null),
                        "employee" to MemberSourceDefinition.PropertyOutput("人员", "employee", ScalarValueType.TEXT, "staff_reporting.employee", 1, local, null),
                        "manager" to MemberSourceDefinition.PropertyOutput("上级", "manager", ScalarValueType.TEXT, "staff_reporting.manager", 1, local, null)), emptyMap(), null, emptyMap(), null)),
                CalculationBindings.empty(), emptyMap(), "golden"))
            val scenario = f.scenarios.createScenario(ScenarioCreateRequest(GovernanceOrderFixture.SUBJECT, 1, "staff_ancestors", "人员祖先关系", "analyst", "显式选择自身", "golden")).scenario
            f.scenarios.createScenarioMemberSourceRef(ScenarioMemberSourceRefCreateRequest(scenario.id, 1, member.id, 1,
                MemberQueryPermission(listOf("record", "employee", "manager"), emptyList(), emptyMap(), false, emptyList())))
            f.scenarios.createScenarioPipelineRule(ScenarioPipelineRuleCreateRequest(scenario.id, 1, PostOperationContract.ANCESTOR_CLOSURE, 1, StageAuthorization.current(PostOperationContract.ANCESTOR_CLOSURE)))
            f.activate("LOGICAL_TABLE", "staff_reporting"); f.activate("TOPOLOGY", topology.id); f.activate("EVENT", event.id)
            f.activateSubjectGraph(); f.build("staff_ancestor_release")
            val publication = f.publication("staff_ancestor_release", scenario = scenario.id)
            val definitions = QueryDefinitionService(JdbcQueryDefinitionRepository(database, RuntimeDeploymentLimits.io), JdbcPublishedQueryEntryProvider(database),
                NativeValidator { intent, capability, executionMode -> QueryNativeValidation.validate(intent, capability, f.connectors, executionMode) })
            for (includeSelf in listOf(false, true)) {
                val scope = QueryComputation.Scope(QueryComputation.Members("members", MemberRef(member.id, 1), emptyMap(), listOf(QueryComputation.Slot("employee", "employee"), QueryComputation.Slot("manager", "manager")), null, emptyMap(), MemberCondition.Unrestricted(), emptyMap(), emptyMap()),
                    emptyMap(), listOf(PostOperationContract.AncestorClosure(listOf("employee"), listOf("manager"), listOf("descendant"), listOf("ancestor"), includeSelf)))
                val saved = definitions.save(QueryDefinitionCommand(if (includeSelf) "ancestors_with_self" else "ancestors", null, requireNotNull(publication.queryCapability.capabilityHash), "可达祖先", f.intent(publication, scope), null), "golden")
                val persisted = definitions.requireByIdAndVersion(saved.definitionId, saved.definitionVersion).intent.computation
                val expected = mutableSetOf(listOf("e1", "e2"), listOf("e1", "e3"), listOf("e1", "e4"), listOf("e2", "e4"), listOf("e3", "e4"), listOf("e5", "e99"))
                if (includeSelf) listOf("e1", "e2", "e3", "e4", "e5", "e6", "e99").forEach { expected.add(listOf(it, it)) }
                val rows = f.execute(publication, persisted).result.resultFrame.rows
                assertEquals(expected, rows.map { row -> row.cells.map { it.value } }.toSet())
                assertEquals(expected.size, rows.size, "重复记录与多条路径只产生一个端点对")
            }
        }
    }
}
