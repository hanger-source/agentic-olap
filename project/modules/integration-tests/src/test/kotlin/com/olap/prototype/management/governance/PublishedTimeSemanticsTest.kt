package com.olap.prototype.management.governance

import com.olap.prototype.calculation.*
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.engine.executable.CalciteRelCompiler
import com.olap.prototype.engine.executable.ExecutableInputGraph
import com.olap.prototype.engine.executable.ExecutableSemanticBinder
import com.olap.prototype.engine.executable.ExecutableSourceResolver
import com.olap.prototype.engine.executable.ExecutableStageAnalysis
import com.olap.prototype.engine.context.OlapQueryContext
import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.governance.contract.*
import com.olap.prototype.metric.*
import com.olap.prototype.query.*
import com.olap.prototype.query.contract.FilterLeafOperator
import com.olap.prototype.query.contract.QueryCapabilityRequest
import com.olap.prototype.release.contract.ReleaseCatalogRequest
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.time.TemporalWindow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

class PublishedTimeSemanticsTest {
    @Test
    fun publishesUnclockedRelationAndCombinesItWithAnIndependentlyTimedInput(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val f = GovernanceOrderFixture(database, directory, listOf("INSERT INTO main.orders(order_id,pay_amount,destination,pay_time) VALUES ('1',120.50,'杭州','2026-07-21'),('2',999,'杭州','2026-08-01')"))
            f.seed()
            val source = f.events.createEvent(event("registered_rows", null, "完整登记关系")).event
            val metric = f.metrics.createMetric(metric(source.id, "registered_amount", "完整登记金额")).metric
            f.activateSubjectGraph(false); f.activate("EVENT", source.id); f.activate("METRIC", metric.id)
            f.adopt(metric.id); f.grantMetric(metric.id, order = 2); f.activate("SUBJECT_AREA", GovernanceOrderFixture.SUBJECT)
            f.build("relation_release")
            val publication = f.publication("relation_release")
            assertNull(f.events.eventSnapshot(source.id, 1).eventTimeLogicUnitId)
            assertNull(publication.catalog.events.getValue("${source.id}@1").timeLogicUnitId)
            val registered = f.adoptions.metricAdoption(com.olap.prototype.query.contract.MetricAdoption.identity(GovernanceOrderFixture.SUBJECT, metric.code), 1).instance
            val paid = f.adoptions.metricAdoption(com.olap.prototype.query.contract.MetricAdoption.identity(GovernanceOrderFixture.SUBJECT, "pay_amount"), 1).instance
            val period = period()
            val plain = GovernanceOrderFixture.measurementScope(registered)
            val timed = GovernanceOrderFixture.measurementScope(paid, timeRange = period)
            val scope = QueryComputation.Scope(plain.root, mapOf("period" to QueryComputation.Input("KEY_ALIGNMENT", timed)), emptyList())
            val rows = f.execute(publication, scope).result.resultFrame.rows
            assertEquals(1, rows.size)
            assertEquals(BigDecimal("1119.50"), rows.first()["value"])
            assertEquals(BigDecimal("120.50"), rows.first()["period__value"])
            val wrong = GovernanceOrderFixture.measurementScope(registered, timeRange = period)
            val denied = assertThrows(EngineExecutionException::class.java) { ExecutableSemanticBinder(f.connectors).bind(publication.catalog, f.kernelRequest(publication, wrong)) }
            assertEquals("AUTHORIZE_INPUT", denied.stage)
            assertEquals("EXECUTABLE_QUERY_NOT_PUBLISHED", denied.code)
            val cause = assertInstanceOf(ProducerContractException::class.java, denied.cause)
            assertTrue(cause.message.orEmpty().contains("source time dimension does not cover all direct fact events"), cause.message)
        }
    }

    @Test
    fun sharesClockDefinitionsWithoutSharingScenarioEventAuthorization(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val f = GovernanceOrderFixture(database, directory)
            f.seed(); f.activateSubjectGraph(false)
            val refund = f.events.createEvent(event("refunded", "order_fact.pay_time", "退款成功")).event
            val refunded = f.metrics.createMetric(metric(refund.id, "refund_amount", "退款额")).metric
            val binding = f.dimensions.createDimensionEventBinding(DimensionEventBindingCreateRequest("common.business_date", 1, refund.id, 1,
                "order_fact.pay_time", 1, null, RelationAccessPath(emptyList()), emptyMap(), "golden")).binding
            f.analysis.createAnalysisDimensionEventMapping(AnalysisDimensionEventMappingCreateRequest("travel.trade.business_date", 1, "day", binding.id, 1, "PROVEN_SAME_KEY", "alignment:business_date"))
            f.activate("EVENT", refund.id); f.activate("METRIC", refunded.id); f.activate("DIMENSION_EVENT_BINDING", binding.id)
            f.adopt(refunded.id)
            f.scenarios.createScenario(ScenarioCreateRequest(GovernanceOrderFixture.SUBJECT, 1, "refunds", "退款分析", "analyst", "按退款事实分析", "golden"))
            f.grantMetric(refunded.id, "travel.trade.refunds")
            f.scenarios.createScenarioAnalysisDimensionRef(ScenarioAnalysisDimensionRefCreateRequest("travel.trade.refunds", 1, "travel.trade.business_date", 1, "day", true, 1))
            listOf(GovernanceOrderFixture.SCENARIO, "travel.trade.refunds").forEach { scenario ->
                f.scenarios.createScenarioFilterRef(ScenarioFilterRefCreateRequest(scenario, 1, "ANALYSIS_DIMENSION", "travel.trade.business_date", 1, null, listOf(FilterLeafOperator.BETWEEN), false, 1))
            }
            f.activate("SUBJECT_AREA", GovernanceOrderFixture.SUBJECT); f.build("shared_clock_release")
            mapOf(GovernanceOrderFixture.SCENARIO to GovernanceOrderFixture.EVENT, "travel.trade.refunds" to refund.id).forEach { (scenario, event) ->
                val capability = f.capabilities.load(QueryCapabilityRequest(scenario, 1, "shared_clock_release", "1"))
                val time = capability.timeAnalysisDimensionCandidates.first()
                assertEquals(setOf(event), time.eventAnchors.map { it.eventId }.toSet())
                assertEquals(listOf("day"), time.availableLevelCodes)
                assertEquals("TIME_DIMENSION", capability.filterCandidates.first().queryFieldKind)
            }
        }
    }

    @Test
    fun freezesSnapshotCalculationAcrossGovernanceRevisionAndCapabilityExport(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val f = GovernanceOrderFixture(database, directory)
            f.seed()
            val meaning = MetricBusinessMeaning("按实体选定期内最后事实再汇总", "元", "实体期末", listOf("不是每日发生额求和"))
            val rule = MetricSnapshotRule(MetricSnapshotRule.Reduction.LAST, "order", listOf(MetricSnapshotRule.LogicUnitKey("order_fact.order_id", 1)),
                MetricSnapshotRule.LogicUnitKey("order_fact.pay_time", 1), emptyList(), MetricSnapshotRule.NullPolicy.PRESERVE_SELECTED, null)
            fun definition(code: String, snapshot: MetricSnapshotRule) = MetricCreateRequest(
                code = code,
                displayName = "期末事实金额",
                owner = "trade",
                definition = MetricDefinition.FactMeasure(
                    event = com.olap.prototype.event.EventRef(GovernanceOrderFixture.EVENT, 1),
                    input = GovernanceOrderFixture.ref("order_fact.pay_amount"),
                    aggregation = MetricAggregation.SUM,
                    distinct = false,
                    privatePredicate = null,
                    roles = emptyMap(),
                    snapshotRule = snapshot,
                    valueContract = GovernanceOrderFixture.money(),
                ),
                description = "",
                businessMeaning = meaning,
                createdBy = "golden"
            )
            val metric = f.metrics.createMetric(definition("snapshot_amount", rule)).metric
            f.activateSubjectGraph(false); f.activate("METRIC", metric.id); f.adopt(metric.id); f.grantMetric(metric.id, order = 2)
            f.activate("SUBJECT_AREA", GovernanceOrderFixture.SUBJECT); f.build("snapshot_release")
            val request = ReleaseCatalogRequest(GovernanceOrderFixture.SCENARIO, 1, "snapshot_release", "1")
            val hash = f.artifacts.catalogHash(request)
            val publication = f.artifacts.loadExecutableCatalog(request)
            val instance = f.adoptions.metricAdoption(com.olap.prototype.query.contract.MetricAdoption.identity(GovernanceOrderFixture.SUBJECT, "snapshot_amount"), 1).instance
            val scope = GovernanceOrderFixture.measurementScope(instance, timeRange = period())
            val graph = ExecutableInputGraph.index(ExecutableSemanticBinder(f.connectors).bind(publication.catalog, f.kernelRequest(publication, scope)))
            CalciteRelCompiler(f.connectors).compile(graph, ExecutableStageAnalysis.analyze(graph, f.connectors), ExecutableSourceResolver().resolve(publication.catalog, graph), OlapQueryContext("snapshot-freeze"))
            val revised = MetricSnapshotRule(MetricSnapshotRule.Reduction.FIRST, "order", rule.entityKeys, rule.timeKey, rule.tieBreakKeys, rule.nullPolicy, null)
            f.metrics.reviseMetric(GovernanceRevisionRequest(metric.id, 1, definition("snapshot_amount", revised)))
            assertEquals(revised, f.metrics.metricSnapshotRule(metric.id, 2))
            assertEquals(hash, f.artifacts.catalogHash(request))
            assertEquals(rule, f.artifacts.loadExecutableCatalog(request).catalog.metrics.getValue(MetricRef(metric.id, "1").key()).factMeasure().snapshotRule)
            val candidate = f.capabilities.load(QueryCapabilityRequest(GovernanceOrderFixture.SCENARIO, 1, "snapshot_release", "1")).metricCandidates.single { it.definition.id == metric.id }
            assertEquals(rule.meaning(), candidate.snapshotMeaning)
        }
    }

    private fun event(code: String, time: String?, display: String) = EventCreateRequest(GovernanceOrderFixture.TOPOLOGY, 1, code, display, "trade", time, time?.let { 1L },
        EventIdentity(listOf(EventIdentity.LogicUnitRef("order_fact.order_id", 1)), mapOf("order" to listOf(EventIdentity.LogicUnitRef("order_fact.order_id", 1)))), emptyMap(),
        listOf(EventCreateRequest.LogicUnitRefInput("order_fact.destination", 1)), listOf(EventCreateRequest.LogicUnitRefInput("order_fact.pay_amount", 1)), "显式事实时间含义", "golden")
    private fun metric(event: String, code: String, display: String) = MetricCreateRequest(
        code = code,
        displayName = display,
        owner = "trade",
        definition = MetricDefinition.FactMeasure(
            event = com.olap.prototype.event.EventRef(event, 1),
            input = GovernanceOrderFixture.ref("order_fact.pay_amount"),
            aggregation = MetricAggregation.SUM,
            distinct = false,
            privatePredicate = null,
            roles = emptyMap(),
            snapshotRule = null,
            valueContract = GovernanceOrderFixture.money(),
        ),
        description = "完整关系金额",
        businessMeaning = MetricBusinessMeaning("完整登记关系的金额", "元", "完整登记成员", listOf("不按发生期间裁剪")),
        createdBy = "golden"
    )
    private fun period() = QueryComputation.TimeRange(ScopedInputContract.DimensionRef("travel.trade.business_date", "1", "day"), TemporalWindow(TemporalWindow.ValueType.DATE, TemporalWindow.BOUNDARY, "2026-07-01", "2026-08-01"))
}
