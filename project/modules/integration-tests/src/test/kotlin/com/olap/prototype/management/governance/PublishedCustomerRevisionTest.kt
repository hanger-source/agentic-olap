package com.olap.prototype.management.governance

import com.olap.prototype.calculation.*
import com.olap.prototype.governance.contract.*
import com.olap.prototype.member.MemberRef
import com.olap.prototype.query.*
import com.olap.prototype.release.contract.*
import com.olap.prototype.storage.PlatformDatabase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PublishedCustomerRevisionTest {
    @Test
    fun carriesSelectedContextAtomicallyAndKeepsOldPublishedQueriesExecutable(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val n = NestedCustomerFixture(database, directory)
            n.publish()
            val f = n.f
            val previous = n.publication("travel.trade")
            val oldGrouping = ScopedInputContract.DimensionRef("travel.trade.destination", "1", "destination")
            val saved = n.save(previous, "before_context_revision", GovernanceOrderFixture.measurementScope(n.fixed.getValue("travel.trade").instance,
                listOf(oldGrouping), mapOf("groups" to Calculation.Grouping(listOf(oldGrouping)))))
            val service = SubjectContextRevisionService(database, f.subjects, f.analysis, f.adoptions, f.scenarios)
            val selection = service.selection(SubjectContextSelectionRequest("travel.trade", 1)).selection
            val definition = SubjectAreaCreateRequest("travel", 1, "trade", "交易主题域", "trade", "显式延续既有业务选择", "golden")

            // 已选指标依赖客户来源；省略来源必须回滚根、子版本和已经重建的采用。
            val incomplete = SubjectContextSelection(selection.metrics, emptyList(), selection.dimensions, selection.scenarios)
            assertThrows(com.olap.prototype.contract.ProducerContractException::class.java) {
                service.revise(SubjectContextRevisionRequest("travel.trade", 1, definition, incomplete))
            }
            assertEquals(1L, f.subjects.findSubjectArea("travel.trade")!!.currentVersion)
            assertEquals("ACTIVE", f.subjects.subjectAreaSnapshot("travel.trade", 1).status)
            assertEquals(1L, f.analysis.findAnalysisDimension(oldGrouping.id)!!.currentVersion)

            val revised = service.revise(SubjectContextRevisionRequest("travel.trade", 1, definition, selection))
            assertEquals(2L, revised.subjectArea.subjectArea.currentVersion)
            val adopted = f.adoptions.metricAdoption(n.fixed.getValue("travel.trade").id, 2)
            assertEquals(n.measured.id, adopted.definition.id)
            assertNotEquals(n.fixed.getValue("travel.trade").instance, adopted.instance)
            f.activate("SUBJECT_AREA", "travel.trade", 2)
            f.builder().build(SubjectAreaReleaseBuildRequest("travel.trade.release", "2", "延续原有客户分析", "travel.trade", 2, "golden"))
            val publication = n.publication("travel.trade", "2", 2)
            val grouping = ScopedInputContract.DimensionRef(oldGrouping.id, "2", "destination")
            val current = n.save(publication, "after_context_revision", GovernanceOrderFixture.measurementScope(adopted.instance,
                listOf(grouping), mapOf("groups" to Calculation.Grouping(listOf(grouping)))))
            for ((selectedPublication, query) in listOf(previous to saved, publication to current)) {
                val rows = n.execute(selectedPublication, query).result.resultFrame.rows
                assertEquals(1, rows.size)
                assertEquals("A", rows.single()["destination"])
                assertEquals(50.0, (rows.single()["value"] as Number).toDouble(), 1e-9)
            }
            // 另一主题仍采用原定义及其原先上下文，没有随本次修订切换。
            val finance = n.publication("travel.finance")
            val financeScope = GovernanceOrderFixture.measurementScope(n.fixed.getValue("travel.finance").instance,
                listOf(ScopedInputContract.DimensionRef("travel.finance.destination", "1", "destination")),
                mapOf("groups" to Calculation.Grouping(listOf(ScopedInputContract.DimensionRef("travel.finance.destination", "1", "destination")))))
            val financeQuery = n.save(finance, "unchanged_finance_context", financeScope)
            assertEquals(50.0, (n.execute(finance, financeQuery).result.resultFrame.rows.single()["value"] as Number).toDouble(), 1e-9)
        }
    }

    @Test
    fun oneSubjectExplicitlyAdoptsTheNewRuleWhileAnotherAndOldQueriesKeepTheirExactRule(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val n = NestedCustomerFixture(database, directory); n.publish()
            val f = n.f
            val previous = n.publication("travel.trade")
            val oldGrouping = ScopedInputContract.DimensionRef("travel.trade.destination", "1", "destination")
            val saved = n.save(previous, "customer_rule_v1", GovernanceOrderFixture.measurementScope(n.fixed.getValue("travel.trade").instance,
                listOf(oldGrouping), mapOf("groups" to Calculation.Grouping(listOf(oldGrouping)))))
            val impact = DefinitionImpactService(database).read(DefinitionImpactRequest("METRIC", n.child.id, 1))
            f.metrics.reviseMetricCalculation(GovernanceRevisionRequest(n.child.id, 1,
                MetricCalculationCreateRequest(n.child.code, n.child.displayName, "trade", CustomerCalculationDefinitions.average(f.paymentId, true), "严格大于门槛", n.meaning, "golden")))
            f.activate("METRIC", n.child.id, 2)
            assertTrue(DefinitionImpactService(database).read(DefinitionImpactRequest("METRIC", n.child.id, 2)).dependents.isEmpty())
            assertEquals(impact, DefinitionImpactService(database).read(DefinitionImpactRequest("METRIC", n.child.id, 1)))
            val subject = f.subjects.reviseSubjectArea(GovernanceRevisionRequest("travel.trade", 1,
                SubjectAreaCreateRequest("travel", 1, "trade", "交易主题域", "trade", "明确改用客户测量v2", "golden"))).subjectArea
            assertEquals(2L, subject.currentVersion)
            val adopted = linkedMapOf<String, CalculationBindings.Dimension>()
            for ((slot, semanticDimension, code, level) in listOf(listOf("customer", "global.customer", "customer", "customer"), listOf("time", "common.business_date", "business_date", "day"), listOf("destination", "global.destination", "destination", "destination"))) {
                val binding = f.dimensions.listDimensionEventBindings(semanticDimension, 1).single { it.eventId == GovernanceOrderFixture.EVENT }
                val dimension = f.analysis.reviseAnalysisDimension(GovernanceRevisionRequest("travel.trade.$code", 1,
                    AnalysisDimensionCreateRequest(subject.id, 2, code, code, "明确组织本版本", level, level, "golden"))).analysisDimension
                // 修订根不复制children；显式建立本版本的证明与映射。
                assertTrue(f.analysis.listAnalysisDimensionEventMappings(dimension.id, 2).isEmpty())
                val proof = f.analysis.createAnalysisDimensionValueAlignment(AnalysisDimensionValueAlignmentCreateRequest(dimension.id, 2, level, "PROVEN_SAME_KEY",
                    f.mapper.writeValueAsString(mapOf("logicUnitId" to binding.logicUnitId, "basis" to "相同精确字段")), "golden"))
                val mapping = f.analysis.createAnalysisDimensionEventMapping(AnalysisDimensionEventMappingCreateRequest(dimension.id, 2, level, binding.id, 1, "PROVEN_SAME_KEY", proof.id)).mapping
                adopted[slot] = CalculationBindings.Dimension(ScopedInputContract.DimensionRef(dimension.id, "2", level), if (slot == "time") "business_time" else slot, listOf(mapping.id))
            }
            val member = f.adoptions.createMemberSource(MemberSourceCreateRequest(subject.id, 2, "customers", "客户总体", n.memberDefinition, CalculationBindings.empty(), emptyMap(), "golden"))
            val bindings = CalculationBindings(mapOf("customer" to adopted.getValue("customer"), "time" to adopted.getValue("time")), mapOf("customers" to CalculationBindings.SourceCall(MemberRef(member.id, 2), emptyMap(), mapOf("customer" to "customer"), emptyMap(), emptyMap())), mapOf("groups" to listOf(adopted.getValue("destination"))), emptyMap(), emptyMap())
            val internal = n.adopt(subject.id, "qualified_measurement", n.child, bindings, mapOf("minimum" to Calculation.Scalar(ScalarValueType.NUMBER, "30")), 2, 2)
            val root = n.adopt(subject.id, "fixed_july_average", n.measured, CalculationBindings(emptyMap(), emptyMap(), bindings.groupingCandidates, mapOf("qualified" to internal.instance), emptyMap()), mapOf("window" to n.july), 2)
            val scenario = f.scenarios.reviseScenario(GovernanceRevisionRequest("travel.trade.customers", 1,
                ScenarioCreateRequest(subject.id, 2, "customers", "客户平均分析", "analyst", "明确选择严格测量", "golden"))).scenario
            f.scenarios.createScenarioMetricRef(ScenarioMetricRefCreateRequest(scenario.id, 2, root.id, 2, true, 1))
            val grouping = adopted.getValue("destination").reference
            f.scenarios.createScenarioAnalysisDimensionRef(ScenarioAnalysisDimensionRefCreateRequest(scenario.id, 2, grouping.id, 2, grouping.levelCode, true, 1))
            f.activate("SUBJECT_AREA", subject.id, 2)
            f.builder().build(SubjectAreaReleaseBuildRequest("travel.trade.release", "2", "交易采用v2", subject.id, 2, "golden"))
            f.builder().build(SubjectAreaReleaseBuildRequest("travel.finance.release", "2", "财务仍采用v1", "travel.finance", 1, "golden"))
            for (coordinate in listOf(ReleaseCatalogRequest("travel.trade.customers", 2, "travel.trade.release", "2"),
                ReleaseCatalogRequest("travel.finance.customers", 1, "travel.finance.release", "2"), ReleaseCatalogRequest("travel.finance.customers", 1, "travel.finance.release", "1"))) {
                val changed = coordinate.scenarioId == "travel.trade.customers"
                val pub = f.artifacts.loadExecutableCatalog(coordinate)
                val group = if (changed) grouping else ScopedInputContract.DimensionRef("travel.finance.destination", "1", "destination")
                val instance = if (changed) root.instance else n.fixed.getValue("travel.finance").instance
                val scope = GovernanceOrderFixture.measurementScope(instance, listOf(group), mapOf("groups" to Calculation.Grouping(listOf(group))))
                val reloaded = n.save(pub, "explicit_${pub.coordinate.releaseId}_${pub.coordinate.releaseVersion}", scope)
                val rows = n.execute(pub, reloaded).result.resultFrame.rows
                assertEquals(1, rows.size); assertEquals("A", rows.single()["destination"]); assertEquals(if (changed) 70.0 else 50.0, (rows.single()["value"] as Number).toDouble(), 1e-9)
            }
            assertEquals(50.0, (n.execute(previous, saved).result.resultFrame.rows.single()["value"] as Number).toDouble(), 1e-9)
        }
    }
}
