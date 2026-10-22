package com.olap.prototype.management.governance

import com.olap.prototype.calculation.*
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.expression.SemanticExpressionCodec
import com.olap.prototype.governance.contract.*
import com.olap.prototype.metric.MetricBusinessMeaning
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.*
import com.olap.prototype.query.contract.MetricAdoption
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.time.TemporalWindow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

class PublishedCrossEventCalculationTest {
    @Test
    fun freezesEachLeafEventAndExecutesTheCompositeInOnePublishedQuery(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val f = GovernanceOrderFixture(database, directory, listOf("INSERT INTO orders VALUES ('1',120.50,'杭州','2026-07-21','c1'),('2',999,'杭州','2026-08-01','c2')")); f.seed()
            val topology = f.topologies.createTopology(TopologyCreateRequest("payment", "退款支付拓扑", "trade.refund", "交易退款实现空间", "order_fact", 1, "modeling", "退款事实", "golden")).topology
            val event = f.events.createEvent(EventCreateRequest(topology.id, 1, "paid", "退款成功", "trade", "order_fact.pay_time", 1,
                EventIdentity(listOf(EventIdentity.LogicUnitRef("order_fact.order_id", 1)), mapOf("order" to listOf(EventIdentity.LogicUnitRef("order_fact.order_id", 1)))), emptyMap(),
                listOf(EventCreateRequest.LogicUnitRefInput("order_fact.destination", 1)), listOf(EventCreateRequest.LogicUnitRefInput("order_fact.pay_amount", 1)), "退款事件", "golden")).event
            val refund = f.metrics.createMetric(MetricCreateRequest(event.id, 1, "refund_amount", "退款金额", "trade", SemanticExpressionCodec.write(GovernanceOrderFixture.ref("order_fact.pay_amount")),
                "SUM", false, "BASE", "ADDITIVE", null, emptyMap(), "退款金额", MetricBusinessMeaning("成功退款金额", "元", "退款", emptyList()), null,
                GovernanceOrderFixture.money(), MetricValueContract.Rounding.NONE, "golden")).metric
            val net = f.metrics.createMetricCalculation(MetricCalculationCreateRequest("net_amount", "净交易额", "trade",
                GovernanceOrderFixture.combinedAmount(f.paymentId, refund.id, ResultExpression.ResultOperator.SUBTRACT), "支付减退款", GovernanceOrderFixture.amountMeaning(), "golden")).metric
            f.activateSubjectGraph(false); f.activate("TOPOLOGY", topology.id); f.activate("EVENT", event.id); f.activate("METRIC", refund.id)
            for ((dimension, unit, code, level) in listOf(listOf("global.destination", "order_fact.destination", "destination", "destination"), listOf("common.business_date", "order_fact.pay_time", "business_date", "day"))) {
                val binding = f.dimensions.createDimensionEventBinding(DimensionEventBindingCreateRequest(dimension, 1, event.id, 1, unit, 1, null, RelationAccessPath(emptyList()), emptyMap(), "golden")).binding
                f.analysis.createAnalysisDimensionEventMapping(AnalysisDimensionEventMappingCreateRequest("travel.trade.$code", 1, level, binding.id, 1, "PROVEN_SAME_KEY", "alignment:$code"))
                f.activate("DIMENSION_EVENT_BINDING", binding.id)
            }
            f.activate("METRIC", net.id); f.adopt(net.id, bindings = f.amountAdoption()); f.grantMetric(net.id, order = 2)
            f.activate("SUBJECT_AREA", GovernanceOrderFixture.SUBJECT); f.build("release_cross_event")
            val publication = f.publication("release_cross_event")
            val instance = f.adoptions.metricAdoption(MetricAdoption.identity(GovernanceOrderFixture.SUBJECT, net.code), 1).instance
            val matrix = publication.queryCapability.metricAnalysisDimensionMatrix.filter { it.instance == instance }
            assertEquals(4, matrix.size); assertEquals(setOf(GovernanceOrderFixture.EVENT, event.id), matrix.map { it.eventId }.toSet()); assertTrue(matrix.all { it.available })
            val city = ScopedInputContract.DimensionRef("travel.trade.destination", "1", "destination")
            val window = Calculation.Range(TemporalWindow(TemporalWindow.ValueType.DATE, TemporalWindow.BOUNDARY, "2026-07-01", "2026-08-01"))
            val scope = GovernanceOrderFixture.measurementScope(instance, listOf(city), mapOf("window" to window))
            val rows = f.execute(publication, scope).result.resultFrame.rows
            assertEquals(1, rows.size); assertEquals("杭州", rows.single()["destination"]); assertEquals(0, BigDecimal.ZERO.compareTo(BigDecimal(rows.single()["value"].toString())))
        }
    }
}
