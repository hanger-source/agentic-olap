package com.olap.prototype.management.governance

import com.olap.prototype.calculation.*
import com.olap.prototype.contract.InputContractException
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.dsl.Computations
import com.olap.prototype.governance.contract.*
import com.olap.prototype.member.*
import com.olap.prototype.query.*
import com.olap.prototype.query.contract.*
import com.olap.prototype.release.catalog.JdbcPublishedQueryEntryProvider
import com.olap.prototype.storage.PlatformDatabase
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
                val scope = Computations.scope {
                    val rows = if (local) member("formed", Computations.scope(c.formed(mapOf("window" to window))), OrderMemberDefinitions.slots)
                        else c.source(c.publicRows, window)
                    val calls = c.metrics.map { (code, adoption) -> Computations.invoke(adoption.instance, code,
                        members = if (code in setOf("sum", "average")) mapOf("rows" to rows) else emptyMap()) }
                    Computations.measure(calls, emptyList(), RowPopulationContract.calls(calls.map { it.callId }))
                }
                val frame = c.execute(scope).result.resultFrame; assertEquals(1, frame.rows.size)
                val row = frame.rows.single()
                number(if (window == c.july) 90.0 else 100.0, row["sum"]); number(if (window == c.july) 45.0 else 100.0, row["average"])
                number(115.0, row["private_sum"]); number(115.0, row["internal_sum"])
            }
            for (member in listOf(c.sharedV1, c.sharedV2)) {
                val scope = Calculation.Scope(Calculation.Members("shared", Calculation.MemberSource(MemberRef(member.id, 1)), listOf(Calculation.MemberProjection("order", "order", null), Calculation.MemberProjection("amount", "amount", null)), null, emptyMap(), MemberCondition.Unrestricted(), mapOf("window" to c.july), emptyMap(), emptyMap()), emptyMap(), emptyList())
                val rows = c.f.execute(c.sharedPublication(), scope).result.resultFrame.rows
                assertEquals(if (member == c.sharedV1) setOf("1", "2", "3", "5") else setOf("2", "3"), rows.map { it["order"] }.toSet())
            }
            val approved = c.formed(mapOf("window" to c.july), condition = MemberCondition.Predicate("amount", MemberCondition.Operator.GTE, listOf(Calculation.Scalar(ScalarValueType.NUMBER, "50"))))
            val rows = c.execute(Calculation.Scope(approved, emptyMap(), emptyList())).result.resultFrame.rows
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
        val body = Calculation(measurementSlots = listOf(Calculation.MeasurementSlot("total", emptyMap(), emptyList(), emptyList(), GovernanceOrderFixture.money())), computation = Calculation.Scope(Calculation.Measures(listOf(Calculation.MeasurementCall("total", "total", outputAlias = "amount")), emptyList(), emptyList(), null, emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()), DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(listOf("total")), null), emptyMap(), emptyList()))
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
            assertThrows(ProducerContractException::class.java) { c.freeze(Calculation.Scope(c.formed(arguments), emptyMap(), emptyList())) }
        }
        val rejected = listOf(
            c.formed(mapOf("window" to c.july), listOf(Calculation.MemberProjection("value", "candidate_amount", null))),
            c.formed(mapOf("window" to c.july), listOf(Calculation.MemberProjection("paid", "paid", null))),
            Calculation.Members("private", Calculation.MemberSource(MemberRef(c.inner.id, 1)), listOf(Calculation.MemberProjection("order", "order", null)), null, emptyMap(), MemberCondition.Unrestricted(), mapOf("window" to c.july), emptyMap(), emptyMap()),
            Calculation.Members("projection", Calculation.MemberSource(MemberRef(c.projection.id, 1)), listOf(Calculation.MemberProjection("order", "order", null)), null, emptyMap(), MemberCondition.Unrestricted(), emptyMap(), emptyMap(), emptyMap()),
            c.formed(mapOf("window" to c.july), condition = MemberCondition.Predicate("candidate_amount", MemberCondition.Operator.GTE, listOf(Calculation.Scalar(ScalarValueType.NUMBER, "50")))))
        rejected.forEach { root -> assertThrows(ProducerContractException::class.java) { c.freeze(Calculation.Scope(root, emptyMap(), emptyList())) } }
        for (binding in listOf(c.source(c.inner, c.july), CalculationBindings.SourceCall(MemberRef(c.publicRows.id, 1), mapOf("window" to c.july), mapOf("order" to "order", "amount" to "candidate_amount"), emptyMap(), emptyMap()))) {
            assertThrows(ProducerContractException::class.java) { c.freeze(Calculation.Scope(measures(listOf(Calculation.InstanceCall("sum", c.metrics.getValue("sum").instance, members = mapOf("rows" to binding)))), emptyMap(), emptyList())) }
        }
    }

    private fun exerciseInlineQualification(c: ComputedOrderFixture) {
        val rows = c.sumDefinition.calculation.memberSlots.single()
        val eligible = Calculation.MemberSlot("eligible", rows.entity, rows.role, rows.shape, rows.outputs, null)
        val measurement = Calculation.Scope(Calculation.Measures(listOf(Calculation.MeasurementCall("sum", "sum", outputAlias = "qualified_sum", memberSlots = mapOf("rows" to "rows"))), emptyList(), emptyList(), null, emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()), DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(listOf("sum")), null), emptyMap(), emptyList())
        val objects = Calculation.Scope(Calculation.Members("qualified", Calculation.MemberParameter("eligible"), listOf(Calculation.MemberProjection("order", "order", null), Calculation.MemberProjection("amount", "amount", null)), null, emptyMap(), MemberCondition.Predicate("amount", MemberCondition.Operator.GTE, listOf(Calculation.Scalar(ScalarValueType.NUMBER, "50"))), emptyMap(), emptyMap(), emptyMap()), emptyMap(), emptyList())
        val inline = Calculation(emptyList(), listOf(rows, eligible), listOf(Calculation.MeasurementSlot("sum", emptyMap(), emptyList(), emptyList(), GovernanceOrderFixture.money())), emptyList(), emptyMap(), Calculation.Scope(Calculation.ObjectMeasure(measurement, objects, listOf(Calculation.ObjectRole(listOf("order"), Calculation.RecordOutputs("rows", listOf("order"))))), emptyMap(), emptyList()))
        val measurements = mapOf("sum" to c.metrics.getValue("sum").instance)
        for (window in listOf(c.july, c.august)) for (local in listOf(false, true)) {
            val source = c.source(c.publicRows, window)
            val scope = Computations.scope {
                val actualRows = if (local) member("formed", Computations.scope(c.formed(mapOf("window" to window))), OrderMemberDefinitions.slots) else source
                Computations.compute(inline, CalculationBindings(members = mapOf("rows" to actualRows, "eligible" to source), measurements = measurements))
            }
            number(if (window == c.july) 70.0 else 100.0, c.execute(scope).result.resultFrame.rows.single()["qualified_sum"])
        }
        // 期间仅由实际来源调用消费；默认值与显式替换均属于被调用算法的参数。
        val parameterized = Calculation(memberSlots = inline.memberSlots, measurementSlots = inline.measurementSlots,
            parameters = mapOf("period" to Calculation.Parameter(Calculation.ParameterKind.RANGE, "DATE", emptyList(), c.july, null)),
            computation = inline.computation)
        val parameterSource = CalculationBindings.SourceCall(MemberRef(c.publicRows.id, 1),
            mapOf("window" to Calculation.Argument("period")), OrderMemberDefinitions.slots, emptyMap(), emptyMap())
        val actual = CalculationBindings(members = mapOf("rows" to parameterSource, "eligible" to parameterSource), measurements = measurements)
        for ((arguments, expected) in listOf(emptyMap<String, Calculation.Binding>() to 70.0,
            mapOf("period" to c.august) to 100.0)) {
            val scope = Computations.compute(parameterized, actual, arguments)
            number(expected, c.execute(scope).result.resultFrame.rows.single()["qualified_sum"])
        }
        val unused = Calculation(memberSlots = inline.memberSlots, measurementSlots = inline.measurementSlots,
            parameters = parameterized.parameters + ("unused" to Calculation.Parameter(Calculation.ParameterKind.SCALAR,
                "TEXT", emptyList(), Calculation.Scalar(ScalarValueType.TEXT, "unread"), null)), computation = inline.computation)
        assertThrows(IllegalArgumentException::class.java) { c.freeze(Computations.compute(unused, actual)) }
        val july = c.source(c.publicRows, c.july)
        val privateDefinition = Calculation(memberSlots = CustomerCalculationDefinitions.amount("SUM", MemberShape.RAW_BAG).calculation.memberSlots, computation = Calculation.Scope(Calculation.Measures(listOf(Calculation.DefinitionCall("private", MetricRef(c.rawMetric.id, "1"), outputAlias = "value", memberSlots = mapOf("rows" to "rows"))), emptyList(), emptyList(), null, emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()), DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(listOf("private")), null), emptyMap(), emptyList()))
        assertThrows(ProducerContractException::class.java) { c.freeze(Calculation.Scope(Calculation.Compute(privateDefinition, Calculation.ExplicitBindings(CalculationBindings(emptyMap(), mapOf("rows" to CalculationBindings.SourceCall(MemberRef(c.raw.id, 1), emptyMap(), mapOf("amount" to "amount"), emptyMap(), emptyMap())), emptyMap(), emptyMap(), emptyMap())), emptyMap()), emptyMap(), emptyList())) }
        val hidden = Calculation(listOf(Calculation.DimensionSlot("hidden", "global.destination", 1, "destination")), inline.memberSlots, inline.measurementSlots, inline.relationSlots, inline.parameters, inline.computation)
        val hiddenDimension = CalculationBindings.Dimension(ScopedInputContract.DimensionRef("travel.trade.destination", "1", "destination"), "destination", c.f.analysis.listAnalysisDimensionEventMappings("travel.trade.destination", 1).map { it.id })
        assertThrows(ProducerContractException::class.java) { c.freeze(Calculation.Scope(Calculation.Compute(hidden, Calculation.ExplicitBindings(CalculationBindings(dimensions = mapOf("hidden" to hiddenDimension), members = mapOf("rows" to july, "eligible" to july), measurements = measurements)), emptyMap()), emptyMap(), emptyList())) }
        for (rejected in listOf(CalculationBindings.SourceCall(MemberRef(c.publicRows.id, 1), mapOf("window" to c.july), mapOf("order" to "order", "amount" to "candidate_amount"), emptyMap(), emptyMap()),
            CalculationBindings.SourceCall(MemberRef(c.publicRows.id, 1), mapOf("window" to c.july, "lower" to Calculation.Scalar(ScalarValueType.NUMBER, "0")), OrderMemberDefinitions.slots, emptyMap(), emptyMap()))) {
            assertThrows(ProducerContractException::class.java) { c.freeze(Calculation.Scope(Calculation.Compute(inline, Calculation.ExplicitBindings(CalculationBindings(members = mapOf("rows" to rejected, "eligible" to july), measurements = measurements)), emptyMap()), emptyMap(), emptyList())) }
        }
        val accepted = Computations.compute(inline, CalculationBindings(members = mapOf("rows" to july, "eligible" to july), measurements = measurements))
        number(70.0, c.execute(accepted).result.resultFrame.rows.single()["qualified_sum"])
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

    private fun measures(calls: List<Calculation.Call>) = Calculation.Measures(calls, emptyList(), emptyList(), null, emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()), DimensionCondition.Unrestricted(), emptyList(), RowPopulationContract.calls(calls.map { it.callId }), null)
    private fun number(expected: Double, value: Any?) { assertNotNull(value); assertEquals(expected, (value as Number).toDouble(), 1e-9) }
}
