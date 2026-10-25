package com.olap.prototype.management.governance

import com.olap.prototype.calculation.*
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.governance.contract.*
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.*
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.definition.QueryDefinitionCommand
import com.olap.prototype.release.contract.*
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.engine.EngineExecutionException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PublishedNestedCustomerTest {
    @Test
    fun nestedAdoptionsKeepRangesGroupingAndMemberPopulationsIndependent(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val n = NestedCustomerFixture(database, directory)
            rejectIncompatibleAdoptions(n)
            n.publish()
            val f = n.f
            val historical = DefinitionImpactService(database).read(DefinitionImpactRequest("METRIC", n.child.id, 1))
            assertTrue(historical.dependents.any { it.reference.id == n.parent.id && it.direct })
            assertEquals(setOf("travel.trade.release", "travel.finance.release"), historical.dependents.filter { it.reference.assetType == "RELEASE" }.map { it.reference.id }.toSet())
            assertThrows(ProducerContractException::class.java) { DefinitionImpactService(database).read(DefinitionImpactRequest("METRIC", n.child.id, 2)) }
            for (subject in NestedCustomerFixture.SUBJECTS) {
                val publication = n.publication(subject)
                assertEquals(setOf(n.parent.id, n.measured.id), publication.queryCapability.metricCandidates.map { it.definition.id }.toSet())
                val grouping = ScopedInputContract.DimensionRef("$subject.destination", "1", "destination")
                val calls = listOf(NestedCustomerFixture.invocation("july", n.primary.getValue(subject).instance, listOf(grouping), "2026-07-01", "2026-08-01", "0"),
                    NestedCustomerFixture.invocation("qualified", n.primary.getValue(subject).instance, listOf(grouping), "2026-07-01", "2026-08-01", "40"),
                    NestedCustomerFixture.invocation("august", n.primary.getValue(subject).instance, listOf(grouping), "2026-08-01", "2026-09-01", "0"))
                val saved = n.save(publication, "${subject}_average", NestedCustomerFixture.scope(calls, listOf(grouping)))
                val observed = n.execute(publication, saved)
                val actual = observed.result.resultFrame.rows.associateBy { it["destination"] }
                listOf("july", "qualified", "august").forEach { name ->
                    val contract = requireNotNull(observed.businessResult.columns.single { it.symbol.name == name }.symbol.quantity).declaration
                    assertTrue(contract.arithmetic is MetricValueContract.NativeFloating, name)
                    assertEquals(MetricValueContract.BinaryFloat(64), contract.result, name)
                }
                assertEquals(setOf("A", "B"), actual.keys)
                number(50.0, actual.getValue("A")["july"]); number(70.0, actual.getValue("A")["qualified"]); assertNull(actual.getValue("A")["august"])
                number(20.0, actual.getValue("B")["july"]); assertNull(actual.getValue("B")["qualified"]); number(100.0, actual.getValue("B")["august"])
                val fixed = GovernanceOrderFixture.measurementScope(n.fixed.getValue(subject).instance, listOf(grouping), mapOf("groups" to Calculation.Grouping(listOf(grouping))))
                val fixedSaved = n.save(publication, "${subject}_fixed", fixed)
                val fixedRows = n.execute(publication, fixedSaved).result.resultFrame.rows
                assertEquals(1, fixedRows.size); assertEquals("A", fixedRows.single()["destination"]); number(50.0, fixedRows.single()["value"])
                val overall = n.save(publication, "${subject}_overall", GovernanceOrderFixture.measurementScope(n.fixed.getValue(subject).instance, emptyList(), mapOf("groups" to Calculation.Grouping(emptyList()))))
                number(50.0, n.execute(publication, overall).result.resultFrame.rows.single()["value"])
                val forbidden = listOf(
                    Calculation.Call("fixed", Calculation.Instance(n.fixed.getValue(subject).instance), "value", emptyMap(), emptyMap(), emptyMap(), emptyMap(), mapOf("groups" to Calculation.Grouping(listOf(grouping)), "minimum" to Calculation.Scalar(ScalarValueType.NUMBER, "0")), Calculation.Condition(DimensionCondition.Unrestricted()), emptyMap(), emptyMap()),
                    Calculation.Call("fixed", Calculation.Instance(n.fixed.getValue(subject).instance), "value", emptyMap(), emptyMap(), emptyMap(), emptyMap(), mapOf("groups" to Calculation.Grouping(listOf(grouping)), "window" to NestedCustomerFixture.range("2026-08-01", "2026-09-01")), Calculation.Condition(DimensionCondition.Unrestricted()), emptyMap(), emptyMap()),
                    Calculation.Call("fixed", Calculation.Instance(n.fixed.getValue(subject).instance), "value", emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), Calculation.Condition(DimensionCondition.Unrestricted()), emptyMap(), emptyMap()),
                    Calculation.Call("fixed", Calculation.Instance(n.internal.getValue(subject).instance), "value", emptyMap(), emptyMap(), emptyMap(), emptyMap(), mapOf("groups" to Calculation.Grouping(listOf(grouping)), "window" to n.july), Calculation.Condition(DimensionCondition.Unrestricted()), emptyMap(), emptyMap()))
                forbidden.forEachIndexed { index, call -> assertThrows(ProducerContractException::class.java) { n.save(publication, "${subject}_forbidden_$index", NestedCustomerFixture.scope(listOf(call), listOf(grouping))) } }
                number(50.0, n.execute(publication, fixedSaved).result.resultFrame.rows.single()["value"])
                if (subject == "travel.trade") independentMembers(n, publication, grouping)
                val layered = NestedCustomerFixture.scope(calls, listOf(grouping), GroupingLevels("level", listOf(GroupingLevels.Level("by_group", listOf("destination")), GroupingLevels.Level("overall", emptyList()))))
                val revised = n.queries.update(saved.definitionId, QueryDefinitionCommand(saved.definitionId, saved.definitionVersion, requireNotNull(publication.queryCapability.capabilityHash), "经营组和全体平均", f.intent(publication, layered), null), "golden")
                val identity = f.artifacts.loadArtifact("$subject.release", "1")
                val bytes = f.mapper.writeValueAsString(publication)
                f.lifecycle.deprecate(GovernanceVersionDeprecationRequest(GovernanceActivationObjectType.SUBJECT_AREA, subject, 1, "governance-owner", "停止未来发布"))
                assertEquals("DEPRECATED", f.subjects.subjectAreaSnapshot(subject, 1).status)
                assertEquals("DEPRECATED", f.scenarios.scenarioSnapshot("$subject.customers", 1).status)
                assertEquals("DEPRECATED", f.analysis.analysisDimensionSnapshot("$subject.destination", 1).status)
                assertThrows(ProducerContractException::class.java) { f.lifecycle.deprecate(GovernanceVersionDeprecationRequest(GovernanceActivationObjectType.SUBJECT_AREA, subject, 1, "governance-owner", "重复停用")) }
                assertThrows(ProducerContractException::class.java) { f.builder().build(SubjectAreaReleaseBuildRequest("$subject.release", "3", "停用后拒绝", subject, 1, "golden", f.sourceAdoptions)) }
                assertThrows(ProducerContractException::class.java) { f.artifacts.loadArtifact("$subject.release", "3") }
                assertEquals(identity, f.artifacts.loadArtifact("$subject.release", "1")); assertEquals(bytes, f.mapper.writeValueAsString(n.publication(subject)))
                val layers = n.execute(n.publication(subject), revised).result.resultFrame.rows
                assertEquals(3, layers.size)
                val total = layers.single { it["destination"] == null }
                number(40.0, total["july"]); number(70.0, total["qualified"]); number(100.0, total["august"])
            }
            TestConnectors.open("duckdb", f.spec.jdbcUrl).use { connection -> connection.createStatement().use { it.execute("INSERT INTO orders VALUES ('7',5,'A','2026-07-04','c5')") } }
            val old = n.publication("travel.trade")
            val oldScope = GovernanceOrderFixture.measurementScope(n.fixed.getValue("travel.trade").instance, emptyList(), mapOf("groups" to Calculation.Grouping(emptyList())))
            val drift = assertThrows(EngineExecutionException::class.java) { f.execute(old, oldScope) }
            assertEquals("VERIFY_SOURCE", drift.stage)
            assertEquals("SOURCE_COVERAGE_ANCHOR_DRIFT", drift.code)
        }
    }

    private fun independentMembers(n: NestedCustomerFixture, publication: PublishedExecutableCatalog, grouping: ScopedInputContract.DimensionRef) {
        val f = n.f
        val a = f.adoptions.metricAdoption(MetricAdoption.identity("travel.trade", "a_customers_average"), 1).instance
        for (pass in 0..2) {
            val groups = if (pass == 2) emptyList() else listOf(grouping)
            val allCall = NestedCustomerFixture.invocation("all_customers", n.primary.getValue("travel.trade").instance, groups, "2026-07-01", "2026-08-01", "0")
            val aCall = NestedCustomerFixture.invocation("a_customers", a, groups, "2026-07-01", "2026-08-01", "0")
            val calls = when (pass) { 0 -> listOf(allCall, aCall); 1 -> listOf(aCall, allCall); else -> listOf(aCall) }
            val result = n.execute(publication, n.save(publication, "independent_$pass", NestedCustomerFixture.scope(calls, groups))).result.resultFrame.rows
            if (pass == 2) { assertEquals(1, result.size); number(50.0, result.single()["a_customers"]) }
            else {
                val rows = result.associateBy { it["destination"] }; assertEquals(setOf("A", "B"), rows.keys)
                number(50.0, rows.getValue("A")["all_customers"]); number(50.0, rows.getValue("A")["a_customers"])
                number(20.0, rows.getValue("B")["all_customers"]); assertNull(rows.getValue("B")["a_customers"])
            }
        }
        for (foreign in listOf(MetricInstanceRef(n.parent.id), n.primary.getValue("travel.finance").instance)) {
            val invalid = NestedCustomerFixture.invocation("foreign", foreign, listOf(grouping), "2026-07-01", "2026-08-01", "0")
            assertThrows(ProducerContractException::class.java) { n.save(publication, "foreign_${foreign.id}", NestedCustomerFixture.scope(listOf(invalid), listOf(grouping))) }
        }
    }

    private fun rejectIncompatibleAdoptions(n: NestedCustomerFixture) {
        val subject = "travel.trade"
        val rootBindings = CalculationBindings(emptyMap(), emptyMap(), n.bindings.getValue(subject).groupingCandidates, mapOf("qualified" to n.internal.getValue(subject).instance), emptyMap())
        val wrongParameters = n.createMetric("wrong_measurement_parameters", CustomerCalculationDefinitions.measurement(n.definition.calculation.parameters))
        val wrongQuantity = n.createMetric("wrong_measurement_quantity", CustomerCalculationDefinitions.measurement(n.definition.calculation.parameters.filterKeys { it != "minimum" }, GovernanceOrderFixture.records()))
        for (metric in listOf(wrongParameters, wrongQuantity)) assertThrows(ProducerContractException::class.java) { n.adopt(subject, metric.code, metric, rootBindings) }
        val cycleA = n.adopt(subject, "cycle_a", n.child, n.bindings.getValue(subject), mapOf("minimum" to Calculation.Scalar(ScalarValueType.NUMBER, "30")))
        val cycleB = n.adopt(subject, "cycle_b", n.measured, CalculationBindings(emptyMap(), emptyMap(), rootBindings.groupingCandidates, mapOf("qualified" to cycleA.instance), emptyMap()))
        assertThrows(ProducerContractException::class.java) { n.adopt(subject, "cycle_a", n.measured, CalculationBindings(emptyMap(), emptyMap(), rootBindings.groupingCandidates, mapOf("qualified" to cycleB.instance), emptyMap())) }
        val request = MetricCalculationCreateRequest("unapproved_customer_average", "待批准客户平均", "trade", n.definition, "未批准版本", n.meaning, "golden")
        val draft = n.f.metrics.createMetricCalculation(request).metric
        repeat(2) { index ->
            if (index == 1) n.f.metrics.reviseMetricCalculation(GovernanceRevisionRequest(draft.id, 1, request))
            assertThrows(ProducerContractException::class.java) { n.adopt(subject, "unapproved", draft, n.bindings.getValue(subject), mapOf("minimum" to Calculation.Scalar(ScalarValueType.NUMBER, "30"))) }
        }
    }

    private fun number(expected: Double, value: Any?) { assertNotNull(value); assertEquals(expected, (value as Number).toDouble(), 1e-9) }
}
