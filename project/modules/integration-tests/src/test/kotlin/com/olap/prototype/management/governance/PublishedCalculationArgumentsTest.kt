package com.olap.prototype.management.governance

import com.olap.prototype.calculation.*
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.expression.SemanticExpressionCodec
import com.olap.prototype.governance.contract.*
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.metric.MetricBusinessMeaning
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.*
import com.olap.prototype.query.contract.MetricAdoption
import com.olap.prototype.query.contract.FilterLeafOperator
import com.olap.prototype.storage.PlatformDatabase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

class PublishedCalculationArgumentsTest {
    @Test
    fun publishesPersistedRelationParametersAndComputesFromCompletedFactInputs(@TempDir directory: Path) {
        val metadata = directory.resolve("platform")
        PlatformDatabase.openSingleInstance(metadata).use { database ->
            val f = GovernanceOrderFixture(database, directory, orders)
            f.seed()
            val count = f.metrics.createMetric(MetricCreateRequest(GovernanceOrderFixture.EVENT, 1, "order_count", "支付订单数", "trade",
                SemanticExpressionCodec.write(GovernanceOrderFixture.ref("order_fact.pay_amount")), "COUNT", false, "BASE", "ADDITIVE", null,
                emptyMap(), "有支付金额的订单数", MetricBusinessMeaning("有支付金额的成功订单数量", "单", "支付订单", emptyList()), null,
                GovernanceOrderFixture.records(), MetricValueContract.Rounding.NONE, "golden")).metric
            val average = createCalculation(f, "completed_order_mean", relationMean())
            f.activate("METRIC", count.id)
            f.adopt(count.id)
            f.grantMetric(count.id, order = 2)
            f.adopt(average.id, bindings = CalculationBindings(emptyMap(), emptyMap(), emptyMap(), emptyMap(),
                mapOf("completed" to CalculationBindings.RelationParameter())))
            f.grantMetric(average.id, order = 3)
            f.scenarios.createScenarioPipelineRule(ScenarioPipelineRuleCreateRequest(GovernanceOrderFixture.SCENARIO, 1, "DERIVE", 1))
            f.activateSubjectGraph()
        }

        // 发布在重开后从采用表读取关系参数，不能依赖创建响应里的内存绑定。
        PlatformDatabase.openSingleInstance(metadata).use { database ->
            val f = GovernanceOrderFixture(database, directory)
            f.build("completed_relation_arguments")
            val publication = f.publication("completed_relation_arguments")
            val calls = listOf(
                invocation("amount", instance(f, "pay_amount"), "amount"),
                invocation("orders", instance(f, "order_count"), "orders"))
            val measured = measures(calls)
            val completed = QueryComputation.Scope(measured.root, measured.inputs, listOf(PostOperationContract.Derive(
                ResultExpression.ResultTypedLiteral(ScalarValueType.TEXT, "completed"), "label", "完成结果说明")))
            val average = QueryComputation.Invocation("average", instance(f, "completed_order_mean"), "mean", emptyMap(), emptyMap(),
                mapOf("completed" to CalculationBindings.LocalRelation("paid", mapOf("amount" to "amount", "orders" to "orders", "label" to "label"))),
                DimensionCondition.Unrestricted())
            val query = QueryComputation.Scope(measures(listOf(average)).root,
                mapOf("paid" to QueryComputation.Input("ROOT", completed)), emptyList())
            val rows = f.execute(publication, query).result.resultFrame.rows
            assertEquals(1, rows.size)
            number("55", rows.single()["mean"])
        }
    }

    @Test
    fun consumesEachInvocationConditionAtItsPrivateExactFactWithoutInheritingTheRoot(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val f = GovernanceOrderFixture(database, directory, orders)
            f.seed()
            val amountDependency = privateFact(f, 1, "private_payment")
            f.events.reviseEvent(GovernanceRevisionRequest(GovernanceOrderFixture.EVENT, 1, EventCreateRequest(
                GovernanceOrderFixture.TOPOLOGY, 1, "paid", "另一个精确支付版本", "trade", null, null,
                EventIdentity(listOf(EventIdentity.LogicUnitRef("order_fact.order_id", 1)),
                    mapOf("order" to listOf(EventIdentity.LogicUnitRef("order_fact.order_id", 1)))), emptyMap(),
                listOf(EventCreateRequest.LogicUnitRefInput("order_fact.destination", 1)),
                listOf(EventCreateRequest.LogicUnitRefInput("order_fact.pay_amount", 1)), "不采用原发布的目的地映射", "golden")))
            f.activate("EVENT", GovernanceOrderFixture.EVENT, 2)
            val otherFact = privateFact(f, 2, "private_payment_v2")
            val selected = createCalculation(f, "selected_amount", conditionalAmount(amountDependency.id))
            val otherEvent = createCalculation(f, "other_event_amount", conditionalAmount(otherFact.id))
            val filter = f.scenarios.createScenarioFilterRef(ScenarioFilterRefCreateRequest(GovernanceOrderFixture.SCENARIO, 1,
                "ANALYSIS_DIMENSION", destination.id, 1, null, listOf(FilterLeafOperator.EQ), false, 1)).ref
            fun condition(value: String, filterId: String = filter.id) = DimensionCondition.Predicate(filterId, destination,
                MemberCondition.Operator.EQ, listOf(Calculation.Scalar(ScalarValueType.TEXT, value)))
            assertThrows(ProducerContractException::class.java) {
                f.adopt(selected.id, code = "fixed_publication_filter", arguments = mapOf("population" to Calculation.Condition(condition("A"))))
            }
            f.adopt(selected.id)
            f.grantMetric(selected.id, order = 2)
            f.adopt(otherEvent.id)
            f.grantMetric(otherEvent.id, order = 3)
            f.activateSubjectGraph()
            f.build("explicit_condition_arguments")
            val publication = f.publication("explicit_condition_arguments")
            val selectedInstance = instance(f, selected.code)
            fun selectedCall(id: String, instance: MetricInstanceRef, condition: DimensionCondition) = invocation(id, instance, id,
                mapOf("population" to Calculation.Condition(condition)))
            val query = measures(listOf(
                invocation("outer_b", instance(f, "pay_amount"), "outer_b"),
                selectedCall("inner_a", selectedInstance, condition("A")),
                selectedCall("inner_b", selectedInstance, condition("B"))), condition("B"))
            val rows = f.execute(publication, query).result.resultFrame.rows
            assertEquals(1, rows.size)
            number("60", rows.single()["outer_b"])
            number("160", rows.single()["inner_a"])
            number("60", rows.single()["inner_b"])

            listOf(
                selectedCall("unapproved", selectedInstance, condition("A", "not_granted")),
                selectedCall("wrong_event", instance(f, otherEvent.code), condition("A")),
            ).forEach { denied ->
                val failure = assertThrows(EngineExecutionException::class.java) { f.execute(publication, measures(listOf(denied))) }
                assertEquals("AUTHORIZE_INPUT", failure.stage)
                assertEquals("EXECUTABLE_QUERY_NOT_PUBLISHED", failure.code)
            }
        }
    }

    private fun relationMean(): MetricCalculation {
        // DuckDB 的除法返回 DOUBLE；输入仍要求精确金额和计数，结果保留原生浮点保证。
        val result = GovernanceOrderFixture.money().let { money -> MetricValueContract(money.unit, MetricValueContract.NativeFloating(), MetricValueContract.BinaryFloat(64),
            mapOf("per_order" to MetricValueContract.PreserveUnit(MetricValueContract.BinaryOperator.DIVIDE, MetricValueContract.Operand.LEFT)), money.overflow) }
        val relation = Calculation.RelationSlot("completed", listOf(
            SetOutputContract("amount", SetOutputContract.Quantity(GovernanceOrderFixture.money())),
            SetOutputContract("orders", SetOutputContract.Quantity(GovernanceOrderFixture.records())),
            SetOutputContract("label", SetOutputContract.Scalar(SetOutputContract.ScalarType.TEXT))), emptyList())
        val calculation = Calculation(emptyList(), emptyList(), emptyList(), listOf(relation), emptyMap(),
            Calculation.Scope(Calculation.Relation("completed"), emptyMap(), listOf(
                PostOperationContract.Derive(ResultExpression.ResultNumeric(ResultExpression.ResultOperation(ResultExpression.ResultOperator.DIVIDE,
                    listOf(ResultExpression.ResultSymbol("amount"), ResultExpression.ResultSymbol("orders"))), result, "per_order"), "mean", "客单价"),
                PostOperationContract.Project(listOf("mean")))))
        return MetricCalculation(calculation, "mean", emptyList(), result)
    }

    private fun conditionalAmount(factId: String): MetricCalculation {
        val parameters = mapOf("population" to Calculation.Parameter(Calculation.ParameterKind.CONDITION, "SOURCE_CONDITION", emptyList(), null, null))
        val call = Calculation.Call("private_amount", Calculation.Fixed(MetricRef(factId, "1")), "value", emptyMap(), emptyMap(), emptyMap(), emptyMap(),
            emptyMap(), Calculation.Argument("population"))
        val calculation = Calculation(emptyList(), emptyList(), emptyList(), emptyList(), parameters,
            Calculation.Scope(Calculation.Measures(listOf(call), emptyList(), emptyList(), null, null, emptyMap(),
                Calculation.Population(listOf(call.callId)), null), emptyMap(), emptyList()))
        return MetricCalculation(calculation, "value", emptyList(), GovernanceOrderFixture.money())
    }

    private fun createCalculation(f: GovernanceOrderFixture, code: String, definition: MetricCalculation) =
        f.metrics.createMetricCalculation(MetricCalculationCreateRequest(code, code, "trade", definition, "显式计算实参",
            MetricBusinessMeaning("按显式输入人口计算支付结果", "元", "调用选择的支付订单", emptyList()), "golden")).metric.also {
            f.activate("METRIC", it.id)
        }

    private fun privateFact(f: GovernanceOrderFixture, eventVersion: Long, code: String) = f.metrics.createMetric(MetricCreateRequest(
        GovernanceOrderFixture.EVENT, eventVersion, code, code, "trade", SemanticExpressionCodec.write(GovernanceOrderFixture.ref("order_fact.pay_amount")),
        "SUM", false, "BASE", "ADDITIVE", null, emptyMap(), "不公开采用的计算依赖", GovernanceOrderFixture.amountMeaning(), null,
        GovernanceOrderFixture.money(), MetricValueContract.Rounding.NONE, "golden")).metric.also { f.activate("METRIC", it.id) }

    private fun instance(f: GovernanceOrderFixture, code: String) = f.adoptions.metricAdoption(MetricAdoption.identity(GovernanceOrderFixture.SUBJECT, code), 1).instance
    private fun invocation(id: String, instance: MetricInstanceRef, alias: String, arguments: Map<String, Calculation.Value> = emptyMap()) =
        QueryComputation.Invocation(id, instance, alias, arguments, emptyMap(), emptyMap(), DimensionCondition.Unrestricted())
    private fun measures(calls: List<QueryComputation.Invocation>, sourceCondition: DimensionCondition = DimensionCondition.Unrestricted()) =
        QueryComputation.Scope(QueryComputation.Measures(calls, emptyList(), null, emptyMap(), sourceCondition, DimensionCondition.Unrestricted(), emptyList(),
            RowPopulationContract.calls(calls.map { it.callId }), null), emptyMap(), emptyList())
    private fun number(expected: String, actual: Any?) { assertNotNull(actual); assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(actual.toString()))) }

    companion object {
        private val destination = ScopedInputContract.DimensionRef("${GovernanceOrderFixture.SUBJECT}.destination", "1", "destination")
        private val orders = listOf("INSERT INTO main.orders VALUES ('1',100,'A','2026-07-21','c1'),('2',40,'B','2026-07-21','c2'),('3',60,'A','2026-07-22','c3'),('4',20,'B','2026-08-01','c1')")
    }
}
