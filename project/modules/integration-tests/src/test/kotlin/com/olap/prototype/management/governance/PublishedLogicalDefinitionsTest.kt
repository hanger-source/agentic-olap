package com.olap.prototype.management.governance

import com.olap.prototype.calculation.CalculationBindings
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.SemanticExpressionCodec
import com.olap.prototype.governance.contract.*
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.metric.MetricBusinessMeaning
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.release.contract.ReleaseCatalogRequest
import com.olap.prototype.storage.PlatformDatabase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

class PublishedLogicalDefinitionsTest {
    @Test
    fun composesExactLogicalDefinitionsAcrossRevisionsAndExecutesTheirFrozenClosure(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val f = GovernanceOrderFixture(database, directory, listOf("INSERT INTO main.orders VALUES ('1',100,'A','2026-07-21','c1'),('2',40,'A','2026-07-21','c2'),('3',10,'B','2026-07-21','c3')"))
            f.seed()
            fun expression(code: String, body: SemanticExpression) = LogicalTableCreateRequest.LogicUnitInput(code, code, LogicUnitSourceKind.EXPRESSION,
                SemanticExpressionCodec.write(body), LogicType.NUMBER, null, false, false, false, false, true, emptyList())
            fun definition(units: List<LogicalTableCreateRequest.LogicUnitInput>, physicalVersion: Long = 1) = LogicalTableCreateRequest(f.physicalId, physicalVersion,
                "recognized", "确认口径", LogicalTableRole.FACT, "逐层定义", "trade", "golden", units)
            val a = GovernanceOrderFixture.ref("recognized.a")
            val b = GovernanceOrderFixture.ref("recognized.b")
            assertThrows(ProducerContractException::class.java) { f.logical.createLogicalTable(definition(listOf(expression("a", b), expression("b", a)))) }
            assertThrows(ProducerContractException::class.java) { f.logical.createLogicalTable(definition(listOf(expression("a", a)))) }
            assertThrows(ProducerContractException::class.java) { f.logical.createLogicalTable(definition(listOf(expression("a", GovernanceOrderFixture.ref("order_fact.pay_amount"))))) }
            val columns = f.physical.physicalTableColumnSnapshots(f.physicalId, 1).associateBy { it.columnName }
            val raw = SemanticExpression.Reference(SemanticExpression.ReferenceKind.PHYSICAL_COLUMN, columns.getValue("pay_amount").id, 1)
            assertThrows(ProducerContractException::class.java) { f.logical.createLogicalTable(definition(listOf(expression("a",
                SemanticExpression.Operation(SemanticExpression.Operator.GTE, listOf(raw, SemanticExpression.Literal(0))))))) }
            fun net(fee: Int) = SemanticExpression.Operation(SemanticExpression.Operator.SUBTRACT, listOf(raw, SemanticExpression.Literal(fee)))
            val netV1 = GovernanceOrderFixture.ref("recognized.net")
            val qualifiedV1 = SemanticExpression.CaseWhen(listOf(SemanticExpression.Branch(SemanticExpression.Operation(SemanticExpression.Operator.GTE,
                listOf(netV1, SemanticExpression.Literal(50))), netV1)), SemanticExpression.Literal(0))
            val order = LogicalTableCreateRequest.LogicUnitInput("order", "订单", LogicUnitSourceKind.COLUMN, null, LogicType.STRING, null, false, true, false, false, false,
                listOf(LogicalTableCreateRequest.SourceRefInput(columns.getValue("order_id").id, 1, LogicUnitSourceRefRole.DIRECT_SOURCE)))
            // 上层排在下层之前；精确定义不依赖输入顺序。
            f.logical.createLogicalTable(definition(listOf(order, expression("qualified", qualifiedV1), expression("net", net(10)))))
            f.activate("LOGICAL_TABLE", "recognized")
            val qualifiedV2 = SemanticExpression.Operation(SemanticExpression.Operator.ADD, listOf(GovernanceOrderFixture.ref("recognized.qualified"), GovernanceOrderFixture.ref("recognized.net", 2)))
            f.logical.reviseLogicalTable(GovernanceRevisionRequest("recognized", 1, definition(listOf(order, expression("qualified", qualifiedV2), expression("net", net(20))))))
            f.activate("LOGICAL_TABLE", "recognized", 2)
            assertEquals(SemanticExpressionCodec.write(qualifiedV2), f.logical.logicUnitSnapshot("recognized.qualified", 2).expressionJson)
            assertEquals(1, f.logical.listLogicUnitSourceRefs("recognized.qualified", 2).size)
            f.topologies.createTopology(TopologyCreateRequest("recognized", "确认订单", "trade.rules", "规则", "recognized", 2, "trade", "确定来源", "golden"))
            f.activate("TOPOLOGY", "trade.rules.recognized")
            val event = f.events.createEvent(EventCreateRequest("trade.rules.recognized", 1, "sold", "确认订单", "trade", null, null,
                EventIdentity(listOf(EventIdentity.LogicUnitRef("recognized.order", 2)), emptyMap()), emptyMap(), emptyList(), listOf(EventCreateRequest.LogicUnitRefInput("recognized.qualified", 2)), "逐层确认口径", "golden")).event
            f.activate("EVENT", event.id)
            val metric = f.metrics.createMetric(MetricCreateRequest(event.id, 1, "recognized_amount", "确认净额", "trade", SemanticExpressionCodec.write(GovernanceOrderFixture.ref("recognized.qualified", 2)),
                "SUM", false, "BASE", "ADDITIVE", null, emptyMap(), "确认口径", MetricBusinessMeaning("原资格口径加本期净额", "元", "订单", emptyList()), null,
                GovernanceOrderFixture.money(), MetricValueContract.Rounding.NONE, "golden")).metric
            f.activate("METRIC", metric.id)
            f.subjects.createSubjectArea(SubjectAreaCreateRequest("travel", 1, "rules", "口径采用", "trade", "精确引用", "golden"))
            val adoption = f.adoptions.createSubjectAreaMetricRef(SubjectAreaMetricRefCreateRequest("travel.rules", 1, metric.code, metric.displayName,
                f.metrics.metricBusinessMeaning(metric.id, 1), metric.id, 1, CalculationBindings.empty(), emptyMap(), "golden")).subjectAreaMetricRef
            f.scenarios.createScenario(ScenarioCreateRequest("travel.rules", 1, "amount", "确认净额", "analyst", "组合定义", "golden"))
            f.scenarios.createScenarioMetricRef(ScenarioMetricRefCreateRequest("travel.rules.amount", 1, adoption.id, 1, true, 1))
            f.activate("SUBJECT_AREA", "travel.rules")
            f.builder().build(SubjectAreaReleaseBuildRequest("logical_rules", "1", "口径发布", "travel.rules", 1, "golden"))
            val publication = f.artifacts.loadExecutableCatalog(ReleaseCatalogRequest("travel.rules.amount", 1, "logical_rules", "1"))
            f.physical.updatePhysicalTableMetadata(PhysicalTableMetadataUpdateRequest(f.physicalId, 1, "订单采用修订", "同名源的新采用版本", "trade", "golden"))
            assertThrows(ProducerContractException::class.java) { f.logical.reviseLogicalTable(GovernanceRevisionRequest("recognized", 2,
                definition(listOf(order, expression("qualified", qualifiedV2), expression("net", net(20))), 2))) }
            assertEquals(2L, requireNotNull(f.logical.findLogicalTable("recognized")).currentVersion)
            assertTrue(publication.catalog.logicUnits.keys.containsAll(setOf("recognized.net@1", "recognized.net@2", "recognized.qualified@1", "recognized.qualified@2")))
            val unit = publication.catalog.logicUnits.getValue("recognized.qualified@2")
            val binding = publication.catalog.physicalExecutionBindings.getValue("${unit.physicalExecutionBindingId}@${unit.physicalExecutionBindingVersion}")
            assertEquals("2", binding.logicalTableVersion)
            assertTrue(binding.logicUnitBindings.keys.containsAll(setOf("recognized.net@1", "recognized.net@2", "recognized.qualified@1")))
            val impact = DefinitionImpactService(database).read(DefinitionImpactRequest("LOGIC_UNIT", "recognized.net", 1))
            assertTrue(impact.dependents.any { it.reference.assetType == "LOGIC_UNIT" && it.reference.id == "recognized.qualified" && it.reference.version == "2" && !it.direct })
            assertTrue(impact.dependents.any { it.reference.assetType == "METRIC" && it.reference.id == metric.id })
            assertTrue(impact.dependents.any { it.reference.assetType == "RELEASE" && it.reference.id == "logical_rules" })
            val expected = TestConnectors.open("duckdb", f.spec.jdbcUrl).use { connection -> connection.createStatement().use { sql ->
                sql.executeQuery("SELECT SUM(CASE WHEN pay_amount-10>=50 THEN pay_amount-10 ELSE 0 END + pay_amount-20) FROM main.orders").use { assertTrue(it.next()); it.getBigDecimal(1) }
            } }
            assertEquals(0, BigDecimal("180").compareTo(expected))
            repeat(2) { execution ->
                if (execution == 1) f.logical.reviseLogicalTable(GovernanceRevisionRequest("recognized", 2, definition(listOf(order, expression("qualified", qualifiedV2), expression("net", net(90))))))
                val frame = f.execute(publication, GovernanceOrderFixture.measurementScope(adoption.instance)).result.resultFrame
                assertEquals(1, frame.rows.size)
                assertEquals(0, expected.compareTo(BigDecimal(frame.rows.first().cells.first().value.toString())))
            }
        }
    }
}
