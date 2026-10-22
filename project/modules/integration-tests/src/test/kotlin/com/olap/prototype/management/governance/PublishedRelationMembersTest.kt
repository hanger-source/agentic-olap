package com.olap.prototype.management.governance

import com.olap.prototype.calculation.*
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.event.EventRef
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.expression.SemanticExpressionCodec
import com.olap.prototype.governance.contract.*
import com.olap.prototype.member.*
import com.olap.prototype.query.*
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.definition.*
import com.olap.prototype.release.catalog.JdbcPublishedQueryEntryProvider
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.integration.RuntimeDeploymentLimits
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

class PublishedRelationMembersTest {
    @Test
    fun relatedExistenceFiltersPreserveTheAmountPopulation(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val f = GovernanceOrderFixture(database, directory, listOf("INSERT INTO orders VALUES ('1',10,'A','2026-07-01','c1'),('2',20,'A','2026-07-02','c1'),('3',70,'A','2026-07-03','c2'),('4',5,'B','2026-07-01','c3'),('5',15,'B','2026-07-02','c3'),('6',100,'B','2026-08-01','c4')"))
            f.seedDefinitions()
            val columns = f.physical.physicalTableColumnSnapshots(f.physicalId, 1).associate { it.columnName to it.id }
            fun unit(code: String, type: LogicType, primary: Boolean = false) = LogicalTableCreateRequest.LogicUnitInput(code, code, LogicUnitSourceKind.COLUMN,
                null, type, null, false, primary, false, true, code == "pay_amount",
                listOf(LogicalTableCreateRequest.SourceRefInput(columns.getValue(code), 1, LogicUnitSourceRefRole.DIRECT_SOURCE)))
            f.logical.createLogicalTable(LogicalTableCreateRequest(f.physicalId, 1, "registered_orders", "登记订单关系", LogicalTableRole.FACT,
                "独立关联记录位置", "data", "golden", listOf(unit("order_id", LogicType.STRING, true), unit("customer_id", LogicType.STRING), unit("pay_amount", LogicType.NUMBER))))
            f.topologies.createTopologyLogicalTableRef(TopologyLogicalTableRefCreateRequest(GovernanceOrderFixture.TOPOLOGY, 1, "registered_orders", 1, TopologyNodeRole.BRIDGE_NODE))
            val path = f.topologies.createJoinPath(JoinPathCreateRequest(GovernanceOrderFixture.TOPOLOGY, 1, "same_customer", "order_fact", 1,
                "registered_orders", 1, "INNER", "MANY_TO_MANY", "{}", "SQL_EQUALS_NULL_NEVER_MATCHES", "golden",
                listOf(JoinPathCreateRequest.KeyRefInput("order_fact.customer_id", 1, "registered_orders.customer_id", 1)))).joinPath
            val topology = f.topologies.createTopology(TopologyCreateRequest("registered", "登记关系", "trade.registered", "登记订单实现空间",
                "registered_orders", 1, "data", "关联源主干", "golden")).topology
            fun identity(unit: String) = EventIdentity(listOf(EventIdentity.LogicUnitRef(unit, 1)), emptyMap())
            val registered = f.events.createEvent(EventCreateRequest(topology.id, 1, "orders", "登记订单", "data", null, null,
                identity("registered_orders.order_id"), emptyMap(), listOf(EventCreateRequest.LogicUnitRefInput("registered_orders.pay_amount", 1)),
                emptyList(), "关联记录", "golden")).event
            val parent = f.events.createEvent(EventCreateRequest(GovernanceOrderFixture.TOPOLOGY, 1, "related_orders", "可筛选的订单金额", "data", null, null,
                identity("order_fact.order_id"), mapOf("customer_orders" to RelationAccessPath(listOf(RelationAccessPath.JoinRef(path.id, 1)))), emptyList(),
                listOf(EventCreateRequest.LogicUnitRefInput("order_fact.pay_amount", 1)), "关联存在性筛选", "golden")).event
            val metric = f.metrics.createMetric(MetricCreateRequest(parent.id, 1, "related_amount", "订单金额", "data", SemanticExpressionCodec.write(GovernanceOrderFixture.ref("order_fact.pay_amount")),
                "SUM", false, "BASE", "ADDITIVE", null, emptyMap(), "关联记录数量不放大订单金额", GovernanceOrderFixture.amountMeaning(), null,
                GovernanceOrderFixture.money(), com.olap.prototype.metric.MetricValueContract.Rounding.NONE, "golden")).metric
            f.activate("LOGICAL_TABLE", "registered_orders"); f.activate("TOPOLOGY", topology.id); f.activate("EVENT", registered.id)
            f.approveDefinitions(); f.seedSubjectDraft(); f.activateSubjectGraph(false)
            f.activate("EVENT", parent.id); f.activate("METRIC", metric.id); f.adopt(metric.id)
            val local = RelationAccessPath(emptyList())
            val member = f.adoptions.createMemberSource(MemberSourceCreateRequest(GovernanceOrderFixture.SUBJECT, 1, "registered_orders", "关联登记订单",
                MemberSourceDefinition.Projection(EventRef(registered.id, 1), MemberResult("订单", "order", MemberShape.RECORDS, listOf("order"), mapOf(
                    "order" to MemberSourceDefinition.PropertyOutput("订单", "order", ScalarValueType.TEXT, "registered_orders.order_id", 1, local, null),
                    "amount" to MemberSourceDefinition.PropertyOutput("金额", "amount", ScalarValueType.NUMBER, "registered_orders.pay_amount", 1, local, GovernanceOrderFixture.money())),
                    mapOf("amount" to listOf(MemberCondition.Operator.GT)), null, emptyMap(), null)), CalculationBindings.empty(), emptyMap(), "golden"))
            val scenario = f.scenarios.createScenario(ScenarioCreateRequest(GovernanceOrderFixture.SUBJECT, 1, "related", "关联订单筛选", "analyst", "同一发布改变阈值", "golden")).scenario
            f.grantMetric(metric.id, scenario.id)
            f.scenarios.createScenarioMemberSourceRef(ScenarioMemberSourceRefCreateRequest(scenario.id, 1, member.id, 1,
                MemberQueryPermission(listOf("order"), emptyList(), mapOf("amount" to listOf(MemberCondition.Operator.GT)), false, emptyList())))
            val filter = f.scenarios.createScenarioRelationFilter(ScenarioRelationFilterCreateRequest(scenario.id, 1, "customer_orders", "客户关联订单", parent.id, 1,
                "customer_orders", member.id, 1, RelationFilterPermission(mapOf("amount" to listOf(MemberCondition.Operator.GT)), false, emptyList()), false, 1)).definition
            f.activate("SUBJECT_AREA", GovernanceOrderFixture.SUBJECT); f.build("related_release")
            val publication = f.publication("related_release", scenario = scenario.id)
            val definitions = QueryDefinitionService(JdbcQueryDefinitionRepository(database, RuntimeDeploymentLimits.io), JdbcPublishedQueryEntryProvider(database),
                NativeValidator { intent, capability -> QueryNativeValidation.validate(intent, capability, f.connectors) })
            for ((threshold, expected) in linkedMapOf("0" to "220", "15" to "200", "40" to "170", "90" to "100")) {
                val condition = DimensionCondition.Exists("eligible_order", filter.relation.id,
                    MemberCondition.Predicate("amount", MemberCondition.Operator.GT, listOf(Calculation.Scalar(ScalarValueType.NUMBER, threshold))), null, emptyMap())
                val scope = QueryComputation.Scope(QueryComputation.Measures(listOf(QueryComputation.Invocation("amount", f.adoptions.metricAdoption(MetricAdoption.identity(GovernanceOrderFixture.SUBJECT, metric.code), 1).instance, "total", emptyMap(), emptyMap(), emptyMap(), DimensionCondition.Unrestricted())),
                    emptyList(), null, emptyMap(), condition, DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(listOf("amount")), null), emptyMap(), emptyList())
                val saved = definitions.save(QueryDefinitionCommand("related_$threshold", null, requireNotNull(publication.queryCapability.capabilityHash), "关联订单阈值",
                    scenario.id, 1, "related_release", "1", f.intent(publication, scope)), "golden")
                val reloaded = QueryComputation.parse(definitions.requireByIdAndVersion(saved.definitionId, saved.definitionVersion).intent.required("computation"))
                val rows = f.execute(publication, reloaded).result.resultFrame.rows
                assertEquals(1, rows.size)
                assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(rows.single().cells.single().value.toString())), "threshold=$threshold")
            }
        }
    }
}
