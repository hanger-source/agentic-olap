package com.olap.prototype.management.governance

import com.olap.prototype.calculation.*
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.engine.executable.ExecutableSemanticBinder
import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.governance.contract.*
import com.olap.prototype.metric.*
import com.olap.prototype.query.*
import com.olap.prototype.query.contract.MetricAdoption
import com.olap.prototype.query.contract.QueryCapabilityRequest
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.time.TemporalWindow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

class PublishedFactAndBudgetTest {
    @Test
    fun publishesDistinctAndRowExpressionsBeforeAggregation(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val f = GovernanceOrderFixture(database, directory, listOf("INSERT INTO main.orders(order_id,pay_amount,destination,pay_time) VALUES ('1',10,'杭州','2026-07-21'),('2',10,'杭州','2026-07-21'),('3',10,'上海','2026-07-21'),('4',20,'上海','2026-07-21'),('5',30,'杭州','2026-08-01')"))
            f.seed()
            val meaning = MetricBusinessMeaning("不同支付金额的数量", "种", "成功支付事实", listOf("相同金额跨目的地仍只计一次，不可加总分组计数"))
            fun distinct(code: String) = MetricCreateRequest(
                code = code,
                displayName = "不同支付金额数",
                owner = "trade",
                definition = MetricDefinition.FactMeasure(
                    event = com.olap.prototype.event.EventRef(GovernanceOrderFixture.EVENT, 1),
                    input = GovernanceOrderFixture.ref("order_fact.pay_amount"),
                    aggregation = MetricAggregation.COUNT,
                    distinct = true,
                    privatePredicate = null,
                    roles = emptyMap(),
                    snapshotRule = null,
                    valueContract = GovernanceOrderFixture.records(),
                ),
                description = "",
                businessMeaning = meaning,
                createdBy = "golden"
            )
            val count = f.metrics.createMetric(distinct("distinct_amount_count")).metric
            val amount = GovernanceOrderFixture.ref("order_fact.pay_amount")
            val squared = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.UnitIdentity("test", "CNY_SQUARED", 1)), MetricValueContract.Exact(),
                MetricValueContract.Decimal(38, 4, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
            val square = f.metrics.createMetric(MetricCreateRequest(
                code = "amount_square_sum",
                displayName = "逐事实平方和",
                owner = "trade",
                definition = MetricDefinition.FactMeasure(
                    event = com.olap.prototype.event.EventRef(GovernanceOrderFixture.EVENT, 1),
                    input = SemanticExpression.Operation(SemanticExpression.Operator.MULTIPLY, listOf(amount, amount)),
                    aggregation = MetricAggregation.SUM,
                    distinct = false,
                    privatePredicate = null,
                    roles = emptyMap(),
                    snapshotRule = null,
                    valueContract = squared,
                ),
                description = "逐事实计算而非聚合后计算",
                businessMeaning = MetricBusinessMeaning("逐事实支付金额平方的和", "元平方", "支付事实", listOf("不等于支付金额总和的平方")),
                createdBy = "golden"
            )).metric
            f.activateSubjectGraph(false)
            listOf(count, square).forEachIndexed { index, metric -> f.activate("METRIC", metric.id); f.adopt(metric.id); f.grantMetric(metric.id, order = index + 2) }
            f.activate("SUBJECT_AREA", GovernanceOrderFixture.SUBJECT); f.build("distinct_release")
            val publication = f.publication("distinct_release")
            listOf(false, true).forEach { rowExpression -> listOf(false, true).forEach { grouped ->
                val metric = if (rowExpression) square else count
                val instance = f.adoptions.metricAdoption(MetricAdoption.identity(GovernanceOrderFixture.SUBJECT, metric.code), 1).instance
                val scope = GovernanceOrderFixture.measurementScope(instance, if (grouped) listOf(destination()) else emptyList(), timeRange = period())
                val rows = f.execute(publication, scope).result.resultFrame.rows
                val observed = rows.associate { row -> (if (grouped) row.cells.first().value.toString() else "total") to BigDecimal(row.cells.last().value.toString()).toLong() }
                val expected = if (rowExpression) { if (grouped) mapOf("杭州" to 200L, "上海" to 500L) else mapOf("total" to 700L) }
                    else { if (grouped) mapOf("杭州" to 1L, "上海" to 2L) else mapOf("total" to 2L) }
                assertEquals(expected, observed)
            } }
        }
    }

    @Test
    fun publishesGrowingCatalogAndExecutesCompositeWithinTheSingleQueryBudget(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val f = GovernanceOrderFixture(database, directory, listOf("INSERT INTO main.orders(order_id,pay_amount,destination,pay_time) VALUES ('1',120.50,'杭州','2026-07-21'),('2',999,'杭州','2026-08-01')"))
            f.seed()
            val tax = f.metrics.createMetric(MetricCreateRequest(
                code = "tax_amount",
                displayName = "税额",
                owner = "trade",
                definition = MetricDefinition.FactMeasure(
                    event = com.olap.prototype.event.EventRef(GovernanceOrderFixture.EVENT, 1),
                    input = GovernanceOrderFixture.ref("order_fact.pay_amount"),
                    aggregation = MetricAggregation.SUM,
                    distinct = false,
                    privatePredicate = null,
                    roles = emptyMap(),
                    snapshotRule = null,
                    valueContract = GovernanceOrderFixture.money(),
                ),
                description = "税额",
                businessMeaning = MetricBusinessMeaning("支付成功订单的税额", "元", "支付成功订单", listOf("税额独立于支付本金")),
                createdBy = "golden"
            )).metric
            val gross = f.metrics.createMetricCalculation(MetricCalculationCreateRequest("gross_amount", "交易毛额", "trade",
                GovernanceOrderFixture.combinedAmount(f.paymentId, tax.id, ResultExpression.ResultOperator.ADD), "支付金额与税额之和",
                MetricBusinessMeaning("支付本金加税额", "元", "支付成功订单", listOf("组成金额必须使用同一币种和范围")), "golden")).metric
            f.activateSubjectGraph(false); f.activate("METRIC", tax.id); f.activate("METRIC", gross.id)
            f.adopt(gross.id, bindings = f.amountAdoption()); f.grantMetric(gross.id, order = 2)
            repeat(96) { index -> f.adopt(f.paymentId, code = "payment_population_$index"); f.grantMetric(f.paymentId, code = "payment_population_$index", order = index + 3) }
            f.activate("SUBJECT_AREA", GovernanceOrderFixture.SUBJECT)
            val result = f.build("release_composite")
            assertEquals("BUILT", result.buildStatus); assertEquals(3, result.metricsFrozen)
            val capability = f.capabilities.load(QueryCapabilityRequest(GovernanceOrderFixture.SCENARIO, 1, "release_composite", "1"))
            assertTrue(capability.metricCandidates.any { it.definition.id == gross.id })
            val instance = f.adoptions.metricAdoption(MetricAdoption.identity(GovernanceOrderFixture.SUBJECT, gross.code), 1).instance
            assertEquals(2, capability.metricAnalysisDimensionMatrix.count { it.instance == instance })
            val publication = f.publication("release_composite")
            val args = mapOf("window" to (period().window as Calculation.Range))
            val scope = GovernanceOrderFixture.measurementScope(instance, listOf(destination()), args)
            val rows = f.execute(publication, scope).result.resultFrame.rows
            assertEquals(listOf(listOf("杭州", BigDecimal("241.00"))), rows.map { row -> row.cells.map { it.value } })
            val calls = (0 until 96).map { Calculation.InstanceCall("measure_$it", instance, outputAlias = "gross_$it", arguments = args) }
            val oversized = Calculation.Scope(Calculation.Measures(calls, (listOf(destination())).map { Calculation.PublishedDimension(it) }, emptyList(), null, emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()), DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(calls.map { it.callId }), null), emptyMap(), emptyList())
            val failure = assertThrows(EngineExecutionException::class.java) {
                ExecutableSemanticBinder(f.connectors).bind(publication.catalog, f.kernelRequest(publication, oversized))
            }
            assertEquals("AUTHORIZE_INPUT", failure.stage)
            assertEquals("EXECUTABLE_QUERY_NOT_PUBLISHED", failure.code)
            val cause = assertInstanceOf(ProducerContractException::class.java, failure.cause)
            assertTrue(cause.message.orEmpty().contains("scope/instance/occurrence budget"), cause.message)
            // 根到叶和叶到根均依据同一真实子树高度判深度。
            listOf(8, 20).forEach { length ->
                val definitions = linkedMapOf<MetricRef, MetricDefinition>()
                val instances = linkedMapOf<MetricInstanceRef, MetricInstance>()
                var previous = f.paymentId
                repeat(length) { index ->
                    val ref = MetricRef("nested_$index", "1")
                    definitions[ref] = GovernanceOrderFixture.combinedAmount(previous, previous, ResultExpression.ResultOperator.ADD)
                    instances[MetricInstanceRef("adopted_$index")] = MetricInstance(ref, CalculationBindings.empty(), emptyMap(), "嵌套定义 $index")
                    previous = ref.id
                }
                listOf(false, true).forEach { reverse ->
                    val roots = instances.keys.toList().let { if (reverse) it.reversed() else it }
                    val close = org.junit.jupiter.api.function.Executable {
                        MetricSource.closure(roots, emptyList(), { definitions[it] ?: publication.catalog.requireMetric(it).definition }, { requireNotNull(instances[it]) }, { error("This definition chain has no member source: $it") })
                    }
                    if (length == 8) assertDoesNotThrow(close) else assertThrows(IllegalArgumentException::class.java, close)
                }
            }
        }
    }

    private fun destination() = ScopedInputContract.DimensionRef("travel.trade.destination", "1", "destination")
    private fun period() = Calculation.TimeRange(Calculation.PublishedDimension(ScopedInputContract.DimensionRef("travel.trade.business_date", "1", "day")), Calculation.Range(TemporalWindow(TemporalWindow.ValueType.DATE, TemporalWindow.BOUNDARY, "2026-07-01", "2026-08-01")))
}
