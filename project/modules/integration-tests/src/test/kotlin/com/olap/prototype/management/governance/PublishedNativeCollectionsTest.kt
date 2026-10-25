package com.olap.prototype.management.governance

import com.olap.prototype.calculation.*
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.event.EventRef
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.governance.contract.*
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.member.*
import com.olap.prototype.query.*
import com.olap.prototype.query.PostOperationContract.Derive
import com.olap.prototype.query.PostOperationContract.ExpandCollection
import com.olap.prototype.query.PostOperationContract.Project
import com.olap.prototype.query.PostOperationContract.ValidatedOperation
import com.olap.prototype.query.ResultExpression.*
import com.olap.prototype.query.contract.MemberCandidate
import com.olap.prototype.query.contract.MemberQueryPermission
import com.olap.prototype.query.contract.QueryCapabilityRequest
import com.olap.prototype.query.contract.StageAuthorization
import com.olap.prototype.release.catalog.JdbcPublishedQueryEntryProvider
import com.olap.prototype.release.contract.ReleaseCatalogRequest
import com.olap.prototype.storage.PlatformDatabase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

class PublishedNativeCollectionsTest {
    @Test
    fun publishesNativeArrayAndRecordPropertiesThroughReusableMembersAndReadsChangingRows(@TempDir directory: Path) {
        val initial = listOf("ALTER TABLE main.orders ADD COLUMN labels VARCHAR[]", "ALTER TABLE main.orders ADD COLUMN lines STRUCT(code VARCHAR, value DECIMAL(18,2))[]",
            "INSERT INTO main.orders VALUES ('1',100,'A','2026-07-21','c1',['A',NULL,'A'],[{'code':'A','value':10},{'code':'A','value':10},NULL,{'code':'B','value':NULL}]),('2',40,'A','2026-07-21','c2',[],[]),('3',10,'B','2026-07-21','c3',NULL,NULL)")
        PlatformDatabase.inMemory().use { database ->
            val f = GovernanceOrderFixture(database, directory, initial)
            f.seed()
            val labels = SetOutputContract.Array(SetOutputContract.Property("label", ScalarValueType.TEXT))
            val lines = SetOutputContract.Array(SetOutputContract.Row(listOf(SetOutputContract.Property("code", ScalarValueType.TEXT), SetOutputContract.Quantity(GovernanceOrderFixture.money()))))
            val physicalColumns = f.physical.physicalTableColumnSnapshots(f.physicalId, 1).associateBy { it.columnName }
            fun container(name: String, shape: SetOutputContract.Container) = LogicalTableCreateRequest.LogicUnitInput(name, name, LogicUnitSourceKind.COLUMN, null, null, shape,
                false, false, false, false, false, listOf(LogicalTableCreateRequest.SourceRefInput(physicalColumns.getValue(name).id, 1, LogicUnitSourceRefRole.DIRECT_SOURCE)))
            val order = LogicalTableCreateRequest.LogicUnitInput("order", "订单", LogicUnitSourceKind.COLUMN, null, LogicType.STRING, null, false, true, false, false, false,
                listOf(LogicalTableCreateRequest.SourceRefInput(physicalColumns.getValue("order_id").id, 1, LogicUnitSourceRefRole.DIRECT_SOURCE)))
            f.logical.createLogicalTable(LogicalTableCreateRequest(f.physicalId, 1, "array_orders", "订单多值属性", LogicalTableRole.FACT, "原生数组与金额记录", "trade", "golden", listOf(order, container("labels", labels), container("lines", lines))))
            f.activate("LOGICAL_TABLE", "array_orders")
            f.topologies.createTopology(TopologyCreateRequest("orders", "数组订单", "trade.arrays", "数组属性", "array_orders", 1, "trade", "真实原生来源", "golden"))
            f.activate("TOPOLOGY", "trade.arrays.orders")
            val event = f.events.createEvent(EventCreateRequest("trade.arrays.orders", 1, "rows", "订单记录", "trade", null, null,
                EventIdentity(listOf(EventIdentity.LogicUnitRef("array_orders.order", 1)), emptyMap()), emptyMap(), emptyList(),
                listOf(EventCreateRequest.LogicUnitRefInput("array_orders.labels", 1), EventCreateRequest.LogicUnitRefInput("array_orders.lines", 1)), "保留重复、NULL和空数组", "golden")).event
            f.activate("EVENT", event.id)
            f.subjects.createSubjectArea(SubjectAreaCreateRequest("travel", 1, "arrays", "多值订单", "trade", "实际原生属性", "golden"))
            val local = RelationAccessPath(emptyList())
            val projection = MemberResult("订单", "order", MemberShape.RECORDS, listOf("order"), mapOf(
                "order" to MemberSourceDefinition.PropertyOutput("订单", "order", ScalarValueType.TEXT, "array_orders.order", 1, local, null),
                "labels" to MemberSourceDefinition.ContainerPropertyOutput("标签", "labels", labels, "array_orders.labels", 1, local),
                "lines" to MemberSourceDefinition.ContainerPropertyOutput("明细金额", "lines", lines, "array_orders.lines", 1, local)), emptyMap(), null, emptyMap(), "原生容器投影")
            val projected = f.adoptions.createMemberSource(MemberSourceCreateRequest("travel.arrays", 1, "source", "实际订单容器", MemberSourceDefinition.Projection(EventRef(event.id, 1), projection), CalculationBindings.empty(), emptyMap(), "golden"))
            val body = Calculation(emptyList(), listOf(Calculation.MemberSlot("rows", "订单", "order", MemberShape.RECORDS, mapOf(
                "order" to Calculation.MemberProperty("order", ScalarValueType.TEXT, null), "labels" to Calculation.MemberContainerProperty("labels", labels),
                "lines" to Calculation.MemberContainerProperty("lines", lines)), null)), emptyList(), emptyList(), emptyMap(), Calculation.Scope(Calculation.Members("rows", "rows",
            listOf("order", "labels", "lines").map { Calculation.MemberProjection(it, it) }, null, emptyMap(), MemberCondition.Unrestricted()), emptyMap(), emptyList()))
            val computed = MemberResult("订单", "order", MemberShape.RECORDS, listOf("order"), mapOf(
                "order" to MemberSourceDefinition.ComputedPropertyOutput("订单", "order", ScalarValueType.TEXT, "order", null),
                "labels" to MemberSourceDefinition.ComputedContainerPropertyOutput("标签", "labels", labels, "labels"),
                "lines" to MemberSourceDefinition.ComputedContainerPropertyOutput("明细金额", "lines", lines, "lines")), emptyMap(), null, emptyMap(), "可复用容器定义")
            val shared = f.members.create(MemberDefinitionCreateRequest("order_collections", "订单多值来源", MemberSourceDefinition.Computed(body, computed), "保持完整数组，不先展开", "trade", "golden"))
            f.activate("MEMBER_DEFINITION", shared.id)
            val adopted = f.adoptions.adoptMemberDefinition(MemberDefinitionAdoptRequest("travel.arrays", 1, "reusable", "复用订单多值定义", MemberDefinitionRef(shared.id, 1),
                CalculationBindings(emptyMap(), mapOf("rows" to CalculationBindings.SourceCall(MemberRef(projected.id, 1), emptyMap(), mapOf("order" to "order", "labels" to "labels", "lines" to "lines"), emptyMap(), emptyMap())), emptyMap(), emptyMap(), emptyMap()), emptyMap(), "golden"))
            val scenario = "travel.arrays.collections"
            f.scenarios.createScenario(ScenarioCreateRequest("travel.arrays", 1, "collections", "订单容器查询", "analyst", "按需展开", "golden"))
            listOf("EXPAND_COLLECTION", "DERIVE", "PROJECT").forEachIndexed { index, operation -> f.scenarios.createScenarioPipelineRule(ScenarioPipelineRuleCreateRequest(scenario, 1, operation, index + 1, StageAuthorization.current(operation))) }
            assertThrows(ProducerContractException::class.java) { f.scenarios.createScenarioMemberSourceRef(ScenarioMemberSourceRefCreateRequest(scenario, 1, adopted.id, 1,
                MemberQueryPermission(listOf("order", "labels", "lines"), listOf("labels"), emptyMap(), false, emptyList()))) }
            f.scenarios.createScenarioMemberSourceRef(ScenarioMemberSourceRefCreateRequest(scenario, 1, adopted.id, 1, MemberQueryPermission(listOf("order", "labels", "lines"), emptyList(), emptyMap(), false, emptyList())))
            f.activate("SUBJECT_AREA", "travel.arrays")
            f.builder().build(SubjectAreaReleaseBuildRequest("native_collections", "1", "实际源容器发布", "travel.arrays", 1, "golden", f.sourceAdoptions))
            val coordinate = QueryCapabilityRequest(scenario, 1, "native_collections", "1")
            val online = JdbcPublishedQueryEntryProvider(database).require(coordinate)
            assertTrue(online.capability.memberCandidates.first().outputs["labels"] is MemberCandidate.ContainerProperty)
            System.getProperty("olap.test.queryInputExportDirectory")?.let { f.mapper.writeValue(Path.of(it).resolve("native-collections-semantic-catalog.json").toFile(), online.semanticCatalog) }
            val publication = f.artifacts.loadExecutableCatalog(ReleaseCatalogRequest(scenario, 1, "native_collections", "1"))
            fun execute(slot: String, mode: String): List<String> {
                val stages = mutableListOf<ValidatedOperation>(ExpandCollection("items", "element", "ordinal", mode))
                if (slot == "lines") {
                    stages += Derive(ResultRowField(ResultSymbol("element"), 0), "code", "编码")
                    stages += Derive(ResultRowField(ResultSymbol("element"), 1), "amount", "金额")
                    stages += Project(listOf("order", "code", "amount", "ordinal"))
                } else stages += Project(listOf("order", "element", "ordinal"))
                val root = QueryComputation.Members("orders", MemberRef(adopted.id, 1), emptyMap(), listOf(QueryComputation.Slot("order", "order"), QueryComputation.Slot("items", slot)), null, emptyMap(), MemberCondition.Unrestricted(), emptyMap(), emptyMap())
                return f.execute(publication, QueryComputation.Scope(root, emptyMap(), stages)).result.resultFrame.rows.map { row -> row.cells.joinToString("|") {
                    val value = it.value
                    if (value is Number) BigDecimal(value.toString()).stripTrailingZeros().toPlainString() else value.toString()
                } }.sorted()
            }
            assertEquals(listOf("1|A|1", "1|A|3", "1|null|2").sorted(), execute("labels", "INNER"))
            assertEquals(listOf("1|A|1", "1|A|3", "1|null|2", "2|null|null", "3|null|null").sorted(), execute("labels", "OUTER"))
            assertEquals(listOf("1|A|10|1", "1|A|10|2", "1|B|null|4", "1|null|null|3").sorted(), execute("lines", "INNER"))
            assertEquals(listOf("1|A|10|1", "1|A|10|2", "1|B|null|4", "1|null|null|3", "2|null|null|null", "3|null|null|null").sorted(), execute("lines", "OUTER"))
            TestConnectors.open("duckdb", f.spec.jdbcUrl).use { connection -> connection.createStatement().use { it.execute("INSERT INTO main.orders VALUES ('4',5,'C','2026-07-22','c4',['Z'],[{'code':'C','value':5}])") } }
            assertEquals(listOf("1|A|1", "1|A|3", "1|null|2", "2|null|null", "3|null|null", "4|Z|1").sorted(), execute("labels", "OUTER"))
        }
    }
}
