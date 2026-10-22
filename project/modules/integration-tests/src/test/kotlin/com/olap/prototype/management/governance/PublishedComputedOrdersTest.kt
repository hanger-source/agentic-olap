package com.olap.prototype.management.governance

import com.fasterxml.jackson.databind.node.ObjectNode
import com.olap.prototype.calculation.*
import com.olap.prototype.contract.InputContractException
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.governance.contract.*
import com.olap.prototype.member.*
import com.olap.prototype.query.*
import com.olap.prototype.query.contract.*
import com.olap.prototype.release.catalog.JdbcPublishedQueryEntryProvider
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.util.OlapJsonUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PublishedComputedOrdersTest {
    @Test
    fun sharedRulesAndLocalReuseKeepIndependentPermissionsAndExactVersions(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val c = ComputedOrderFixture(database, directory)
            rejectInvalidDefinitionsAndCycles(c)
            c.publish()
            assertEquals(MemberDefinitionOrigin.Shared(MemberDefinitionRef(c.sharedRule.id, 1)), c.f.adoptions.memberSource(c.sharedV1.id, 1).origin)
            assertEquals(c.sharedRule.definition, c.f.adoptions.memberSource(c.sharedV1.id, 1).definition)
            assertEquals(c.revisedRule.definition, c.f.adoptions.memberSource(c.sharedV2.id, 1).definition)
            val impact = DefinitionImpactService(database).read(DefinitionImpactRequest("MEMBER_DEFINITION", c.sharedRule.id, 1))
            assertEquals(setOf(c.inner.id, c.sharedV1.id), impact.dependents.filter { it.reference.assetType == "MEMBER_SOURCE" && it.direct }.map { it.reference.id }.toSet())
            assertEquals(setOf("formed_orders", "shared_orders"), impact.dependents.filter { it.reference.assetType == "RELEASE" }.map { it.reference.id }.toSet())
            assertFalse(DefinitionImpactService(database).read(DefinitionImpactRequest("MEMBER_DEFINITION", c.sharedRule.id, 2)).dependents.any { it.reference.id in setOf(c.inner.id, c.sharedV1.id, "formed_orders") })
            assertPublicationPages(c)
            for (window in listOf(c.july, c.august)) for (local in listOf(false, true)) {
                val rows: CalculationBindings.Member = if (local) CalculationBindings.LocalRef("formed", OrderMemberDefinitions.slots) else c.source(c.publicRows, window)
                val calls = c.metrics.map { (code, adoption) -> QueryComputation.Invocation(code, adoption.instance, code, emptyMap(), if (code in setOf("sum", "average")) mapOf("rows" to rows) else emptyMap(), emptyMap(), DimensionCondition.Unrestricted()) }
                val scope = QueryComputation.Scope(measures(calls), if (local) mapOf("formed" to QueryComputation.Input("ROOT", QueryComputation.Scope(c.formed(mapOf("window" to window)), emptyMap(), emptyList()))) else emptyMap(), emptyList())
                val frame = c.execute(scope).result.resultFrame; assertEquals(1, frame.rows.size)
                val row = frame.rows.single()
                number(if (window == c.july) 90.0 else 100.0, row["sum"]); number(if (window == c.july) 45.0 else 100.0, row["average"])
                number(115.0, row["private_sum"]); number(115.0, row["internal_sum"])
            }
            for (member in listOf(c.sharedV1, c.sharedV2)) {
                val scope = QueryComputation.Scope(QueryComputation.Members("shared", MemberRef(member.id, 1), mapOf("window" to c.july), listOf(QueryComputation.Slot("order", "order"), QueryComputation.Slot("amount", "amount")), null, emptyMap(), MemberCondition.Unrestricted(), emptyMap(), emptyMap()), emptyMap(), emptyList())
                val rows = c.f.execute(c.sharedPublication(), scope).result.resultFrame.rows
                assertEquals(if (member == c.sharedV1) setOf("1", "2", "3", "5") else setOf("2", "3"), rows.map { it["order"] }.toSet())
            }
            val approved = c.formed(mapOf("window" to c.july), condition = MemberCondition.Predicate("amount", MemberCondition.Operator.GTE, listOf(Calculation.Scalar(ScalarValueType.NUMBER, "50"))))
            val rows = c.execute(QueryComputation.Scope(approved, emptyMap(), emptyList())).result.resultFrame.rows
            assertEquals(1, rows.size); assertEquals("3", rows.single()["order"]); number(70.0, rows.single()["amount"])
            rejectUnauthorizedCalls(c)
            exerciseInlineQualification(c)
        }
    }

    private fun rejectInvalidDefinitionsAndCycles(c: ComputedOrderFixture) {
        val f = c.f
        val original = OrderMemberDefinitions.projection(MemberShape.RECORDS)
        val output = original.result.outputs.getValue("order") as MemberSourceDefinition.PropertyOutput
        val wrong = MemberSourceDefinition.Projection(original.event, MemberResult(original.result.entity, original.result.role, original.result.shape, original.result.identitySlots,
            original.result.outputs + ("order" to MemberSourceDefinition.PropertyOutput(output.displayName, output.role, ScalarValueType.NUMBER, output.logicUnitId, output.logicUnitVersion, output.accessPath, null)),
            original.result.filterOperators, original.result.timeSlot, original.result.clockSlots, original.result.description))
        assertThrows(ProducerContractException::class.java) { f.members.create(MemberDefinitionCreateRequest("wrong_order_type", "订单错误数值声明", wrong, "真实源是文本", "trade", "golden")) }
        val computed = OrderMemberDefinitions.computed("minimum", true)
        val unknown = MemberSourceDefinition.Computed(Calculation(listOf(Calculation.DimensionSlot("missing", "global.nonexistent", 1, "missing")), computed.calculation.memberSlots, computed.calculation.measurementSlots, computed.calculation.relationSlots, computed.calculation.parameters, computed.calculation.computation), computed.result)
        assertThrows(ProducerContractException::class.java) { f.members.create(MemberDefinitionCreateRequest("unknown_member_dimension", "不存在维度", unknown, "正式引用必须存在", "trade", "golden")) }
        assertThrows(ProducerContractException::class.java) { f.adoptions.adoptMemberDefinition(MemberDefinitionAdoptRequest("travel.shared", 1, "foreign_orders", "跨域来源", MemberDefinitionRef(c.sharedRule.id, 1),
            c.sourceBindings(c.projection), mapOf("minimum" to Calculation.Scalar(ScalarValueType.NUMBER, "10")), "golden")) }
        assertThrows(ProducerContractException::class.java) { f.adoptions.createSubjectAreaMetricRef(SubjectAreaMetricRefCreateRequest("travel.trade", 1, "caller_only", "非法外部Local", c.meaning,
            c.metrics.getValue("sum").metricId, 1, CalculationBindings(emptyMap(), mapOf("rows" to CalculationBindings.LocalRef("caller_only", OrderMemberDefinitions.slots)), emptyMap(), emptyMap(), emptyMap()), emptyMap(), "golden")) }
        val cycle = f.adoptions.createSubjectAreaMetricRef(SubjectAreaMetricRefCreateRequest("travel.trade", 1, "cycle_sum", "环验证总额", c.meaning, c.rawMetric.id, 1,
            CalculationBindings(emptyMap(), mapOf("rows" to CalculationBindings.SourceCall(MemberRef(c.raw.id, 1), emptyMap(), mapOf("amount" to "amount"), emptyMap(), emptyMap())), emptyMap(), emptyMap(), emptyMap()), emptyMap(), "golden")).subjectAreaMetricRef
        val body = Calculation(emptyList(), emptyList(), listOf(Calculation.MeasurementSlot("total", emptyMap(), emptyList(), emptyList(), GovernanceOrderFixture.money())), emptyList(), emptyMap(), Calculation.Scope(Calculation.Measures(listOf(Calculation.Call("total", Calculation.Measurement("total"), "amount", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()))), emptyList(), emptyList(), null, null,
            emptyMap(), Calculation.Population(listOf("total")), null), emptyMap(), emptyList()))
        val member = c.member("measured_total", MemberSourceDefinition.Computed(body, MemberResult("订单", "order", MemberShape.RAW_BAG, emptyList(),
            mapOf("amount" to MemberSourceDefinition.ComputedPropertyOutput("金额", "amount", ScalarValueType.NUMBER, "amount", GovernanceOrderFixture.money())), emptyMap(), null, emptyMap(), "测量完成结果")),
            CalculationBindings(emptyMap(), emptyMap(), emptyMap(), mapOf("total" to cycle.instance), emptyMap()))
        val failure = assertThrows(ProducerContractException::class.java) { f.adoptions.createSubjectAreaMetricRef(SubjectAreaMetricRefCreateRequest("travel.trade", 1, "cycle_sum", "环验证总额", c.meaning, c.rawMetric.id, 1,
            CalculationBindings(emptyMap(), mapOf("rows" to CalculationBindings.SourceCall(MemberRef(member.id, 1), emptyMap(), mapOf("amount" to "amount"), emptyMap(), emptyMap())), emptyMap(), emptyMap(), emptyMap()), emptyMap(), "golden")) }
        assertTrue(failure.message.orEmpty().contains("calculation dependency cycle:"))
        assertTrue(failure.message.orEmpty().contains("instance ${cycle.instance} -> metric ${MetricRef(c.rawMetric.id, "1")} -> member ${MemberRef(member.id, 1)} -> instance ${cycle.instance}"))
    }

    private fun rejectUnauthorizedCalls(c: ComputedOrderFixture) {
        for (arguments in listOf(emptyMap(), mapOf("window" to c.july, "lower" to Calculation.Scalar(ScalarValueType.NUMBER, "0")))) {
            assertThrows(ProducerContractException::class.java) { c.freeze(QueryComputation.Scope(c.formed(arguments), emptyMap(), emptyList())) }
        }
        val rejected = listOf(
            c.formed(mapOf("window" to c.july), listOf(QueryComputation.Slot("value", "candidate_amount"))),
            c.formed(mapOf("window" to c.july), listOf(QueryComputation.Slot("paid", "paid"))),
            QueryComputation.Members("private", MemberRef(c.inner.id, 1), mapOf("window" to c.july), listOf(QueryComputation.Slot("order", "order")), null, emptyMap(), MemberCondition.Unrestricted(), emptyMap(), emptyMap()),
            QueryComputation.Members("projection", MemberRef(c.projection.id, 1), emptyMap(), listOf(QueryComputation.Slot("order", "order")), null, emptyMap(), MemberCondition.Unrestricted(), emptyMap(), emptyMap()),
            c.formed(mapOf("window" to c.july), condition = MemberCondition.Predicate("candidate_amount", MemberCondition.Operator.GTE, listOf(Calculation.Scalar(ScalarValueType.NUMBER, "50")))))
        rejected.forEach { root -> assertThrows(ProducerContractException::class.java) { c.freeze(QueryComputation.Scope(root, emptyMap(), emptyList())) } }
        for (binding in listOf(c.source(c.inner, c.july), CalculationBindings.SourceCall(MemberRef(c.publicRows.id, 1), mapOf("window" to c.july), mapOf("order" to "order", "amount" to "candidate_amount"), emptyMap(), emptyMap()))) {
            assertThrows(ProducerContractException::class.java) { c.freeze(QueryComputation.Scope(measures(listOf(QueryComputation.Invocation("sum", c.metrics.getValue("sum").instance, "sum", emptyMap(), mapOf("rows" to binding), emptyMap(), DimensionCondition.Unrestricted()))), emptyMap(), emptyList())) }
        }
    }

    private fun exerciseInlineQualification(c: ComputedOrderFixture) {
        val rows = c.sumDefinition.calculation.memberSlots.single()
        val eligible = Calculation.MemberSlot("eligible", rows.entity, rows.role, rows.shape, rows.outputs, null)
        val measurement = Calculation.Scope(Calculation.Measures(listOf(Calculation.Call("sum", Calculation.Measurement("sum"), "qualified_sum", emptyMap(), mapOf("rows" to "rows"), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()))),
            emptyList(), emptyList(), null, null, emptyMap(), Calculation.Population(listOf("sum")), null), emptyMap(), emptyList())
        val objects = Calculation.Scope(Calculation.Members("qualified", "eligible", listOf(Calculation.MemberProjection("order", "order"), Calculation.MemberProjection("amount", "amount")), null, emptyMap(),
            MemberCondition.Predicate("amount", MemberCondition.Operator.GTE, listOf(Calculation.Scalar(ScalarValueType.NUMBER, "50")))), emptyMap(), emptyList())
        val inline = Calculation(emptyList(), listOf(rows, eligible), listOf(Calculation.MeasurementSlot("sum", emptyMap(), emptyList(), emptyList(), GovernanceOrderFixture.money())), emptyList(), emptyMap(), Calculation.Scope(Calculation.ObjectMeasure(measurement, objects, listOf(Calculation.ObjectRole(listOf("order"), Calculation.RecordOutputs("rows", listOf("order"))))), emptyMap(), emptyList()))
        val bindings = CalculationBindings(emptyMap(), mapOf("rows" to CalculationBindings.InputParameter(), "eligible" to CalculationBindings.InputParameter()), emptyMap(), mapOf("sum" to c.metrics.getValue("sum").instance), emptyMap())
        for (window in listOf(c.july, c.august)) for (local in listOf(false, true)) {
            val source = c.source(c.publicRows, window)
            val scope = QueryComputation.Scope(QueryComputation.Compute(inline, bindings, emptyMap(), mapOf("rows" to if (local) CalculationBindings.LocalRef("formed", OrderMemberDefinitions.slots) else source, "eligible" to source), emptyMap()),
                if (local) mapOf("formed" to QueryComputation.Input("ROOT", QueryComputation.Scope(c.formed(mapOf("window" to window)), emptyMap(), emptyList()))) else emptyMap(), emptyList())
            number(if (window == c.july) 70.0 else 100.0, c.execute(scope).result.resultFrame.rows.single()["qualified_sum"])
        }
        val july = c.source(c.publicRows, c.july)
        val privateDefinition = Calculation(emptyList(), CustomerCalculationDefinitions.amount("SUM", MemberShape.RAW_BAG).calculation.memberSlots, emptyList(), emptyList(), emptyMap(), Calculation.Scope(Calculation.Measures(listOf(Calculation.Call("private", Calculation.Fixed(MetricRef(c.rawMetric.id, "1")), "value", emptyMap(), mapOf("rows" to "rows"), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()))),
            emptyList(), emptyList(), null, null, emptyMap(), Calculation.Population(listOf("private")), null), emptyMap(), emptyList()))
        assertThrows(ProducerContractException::class.java) { c.freeze(QueryComputation.Scope(QueryComputation.Compute(privateDefinition, CalculationBindings(emptyMap(), mapOf("rows" to CalculationBindings.SourceCall(MemberRef(c.raw.id, 1), emptyMap(), mapOf("amount" to "amount"), emptyMap(), emptyMap())), emptyMap(), emptyMap(), emptyMap()), emptyMap(), emptyMap(), emptyMap()), emptyMap(), emptyList())) }
        val hidden = Calculation(listOf(Calculation.DimensionSlot("hidden", "global.destination", 1, "destination")), inline.memberSlots, inline.measurementSlots, inline.relationSlots, inline.parameters, inline.computation)
        val hiddenDimension = CalculationBindings.Dimension(ScopedInputContract.DimensionRef("travel.trade.destination", "1", "destination"), "destination", c.f.analysis.listAnalysisDimensionEventMappings("travel.trade.destination", 1).map { it.id })
        assertThrows(ProducerContractException::class.java) { c.freeze(QueryComputation.Scope(QueryComputation.Compute(hidden, CalculationBindings(mapOf("hidden" to hiddenDimension), bindings.members, emptyMap(), bindings.measurements, emptyMap()), emptyMap(), mapOf("rows" to july, "eligible" to july), emptyMap()), emptyMap(), emptyList())) }
        for (rejected in listOf(CalculationBindings.SourceCall(MemberRef(c.publicRows.id, 1), mapOf("window" to c.july), mapOf("order" to "order", "amount" to "candidate_amount"), emptyMap(), emptyMap()),
            CalculationBindings.SourceCall(MemberRef(c.publicRows.id, 1), mapOf("window" to c.july, "lower" to Calculation.Scalar(ScalarValueType.NUMBER, "0")), OrderMemberDefinitions.slots, emptyMap(), emptyMap()))) {
            assertThrows(ProducerContractException::class.java) { c.freeze(QueryComputation.Scope(QueryComputation.Compute(inline, bindings, emptyMap(), mapOf("rows" to rejected, "eligible" to july), emptyMap()), emptyMap(), emptyList())) }
        }
        val accepted = QueryComputation.Scope(QueryComputation.Compute(inline, bindings, emptyMap(), mapOf("rows" to july, "eligible" to july), emptyMap()), emptyMap(), emptyList())
        val syntax = OlapJsonUtils.mapper().valueToTree<ObjectNode>(accepted)
        for (field in listOf("dimensionSlots", "measurementSlots")) {
            val invalid = syntax.deepCopy()
            val call = invalid.at("/root/calculation/computation/root/measurement/root/calls/0") as ObjectNode
            (call.required(field) as ObjectNode).put("sum", "sum")
            val failure = assertThrows(InputContractException::class.java) { QueryComputation.parse(invalid) }
            assertEquals(InputContractException.Rule.MEASUREMENT_BINDING_OVERRIDE, failure.rule)
            assertEquals(listOf("computation", "root", "calculation", "computation", "root", "measurement", "root", "calls", 0, field), failure.details["fieldPath"])
            assertEquals("EMPTY_MAP", failure.details["expectedKind"])
            assertEquals("NONEMPTY_MAP", failure.details["actualKind"])
        }
        number(70.0, c.execute(QueryComputation.parse(syntax)).result.resultFrame.rows.single()["qualified_sum"])
    }

    private fun assertPublicationPages(c: ComputedOrderFixture) {
        val provider = JdbcPublishedQueryEntryProvider(c.database)
        val scopes = listOf(JdbcPublishedQueryEntryProvider.Scope(setOf("travel.trade"), setOf("formed_orders")), JdbcPublishedQueryEntryProvider.Scope(setOf("travel.shared"), setOf("shared_orders")))
        val first = provider.list(0, 1, scopes = scopes); assertEquals(1, first.nextOffset)
        val second = provider.list(requireNotNull(first.nextOffset), 1, scopes = scopes); assertEquals(2, second.nextOffset)
        val third = provider.list(requireNotNull(second.nextOffset), 1, scopes = scopes); assertNull(third.nextOffset)
        val all = first.items + second.items + third.items
        assertEquals(setOf("travel.trade.daily", "travel.trade.formed", "travel.shared.orders"), all.map { it.scenarioId }.toSet())
        assertEquals(all, provider.list(0, 3, scopes = scopes).items)
        assertTrue(provider.list(0, 10, scopes = listOf(JdbcPublishedQueryEntryProvider.Scope(setOf("travel.trade"), setOf("shared_orders")), JdbcPublishedQueryEntryProvider.Scope(setOf("travel.shared"), setOf("formed_orders")))).items.isEmpty())
        assertTrue(provider.list(0, 10, "travel.trade", "shared_orders", scopes).items.isEmpty()); assertTrue(provider.list(0, 10, scopes = emptyList()).items.isEmpty())
    }

    private fun measures(calls: List<QueryComputation.Invocation>) = QueryComputation.Measures(calls, emptyList(), null, emptyMap(), DimensionCondition.Unrestricted(), DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(calls.map { it.callId }), null)
    private fun number(expected: Double, value: Any?) { assertNotNull(value); assertEquals(expected, (value as Number).toDouble(), 1e-9) }
}
