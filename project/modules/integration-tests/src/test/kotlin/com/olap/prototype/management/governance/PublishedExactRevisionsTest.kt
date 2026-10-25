package com.olap.prototype.management.governance

import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.calculation.CalculationBindings
import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.governance.contract.*
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.member.MemberRef
import com.olap.prototype.member.MemberShape
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.QueryComputation
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.query.contract.MemberQueryPermission
import com.olap.prototype.query.contract.MetricAdoption
import com.olap.prototype.release.contract.ReleaseCatalogRequest
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.time.TemporalWindow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

class PublishedExactRevisionsTest {
    @Test
    fun reopensOnePublicationWithTwoExactEventVersionsAndTheirTimeAnchors(@TempDir directory: Path) {
        val metadata = directory.resolve("platform")
        PlatformDatabase.openSingleInstance(metadata).use { database ->
            val f = GovernanceOrderFixture(database, directory, listOf(
                "INSERT INTO main.orders VALUES ('1',100,'A','2026-07-21','c1'),('2',40,'B','2026-07-21','c2'),('3',60,'A','2026-07-22','c3'),('4',90,'B','2026-08-01','c4')"))
            f.seedDefinitions()
            f.approveDefinitions()
            f.events.reviseEvent(GovernanceRevisionRequest(GovernanceOrderFixture.EVENT, 1, EventCreateRequest(
                GovernanceOrderFixture.TOPOLOGY, 1, "paid", "A目的地支付", "trade", "order_fact.pay_time", 1,
                EventIdentity(listOf(EventIdentity.LogicUnitRef("order_fact.order_id", 1)), mapOf("order" to listOf(EventIdentity.LogicUnitRef("order_fact.order_id", 1)))),
                emptyMap(), listOf(EventCreateRequest.LogicUnitRefInput("order_fact.destination", 1), EventCreateRequest.LogicUnitRefInput("order_fact.customer_id", 1)),
                listOf(EventCreateRequest.LogicUnitRefInput("order_fact.pay_amount", 1)), "只统计A目的地", "golden")))
            // 事件分片沿既有正式持久表进入冻结；查询答案由独立订单输入决定。
            GovernanceRepository(database).saveEventSliceFilter(EventSliceFilterRecord("paid:v2:destination-a", GovernanceOrderFixture.EVENT, 2,
                SemanticExpression.Operation(SemanticExpression.Operator.EQ,
                    listOf(GovernanceOrderFixture.ref("order_fact.destination"), SemanticExpression.Literal("A"))), f.now))
            f.activate("EVENT", GovernanceOrderFixture.EVENT, 2)
            val selectedMetric = f.metrics.createMetric(MetricCreateRequest(
                code = "a_amount",
                displayName = "A目的地金额",
                owner = "trade",
                definition = MetricDefinition.FactMeasure(
                    event = com.olap.prototype.event.EventRef(GovernanceOrderFixture.EVENT, 2),
                    input = GovernanceOrderFixture.ref("order_fact.pay_amount"),
                    aggregation = MetricAggregation.SUM,
                    distinct = false,
                    privatePredicate = null,
                    roles = emptyMap(),
                    snapshotRule = null,
                    valueContract = GovernanceOrderFixture.money(),
                ),
                description = "A目的地支付金额",
                businessMeaning = GovernanceOrderFixture.amountMeaning(),
                createdBy = "golden"
            )).metric
            f.activate("METRIC", selectedMetric.id)
            f.seedSubjectDraft()
            f.activateSubjectGraph(false)
            f.adopt(selectedMetric.id)
            f.grantMetric(selectedMetric.id, order = 2)
            listOf(
                listOf("global.destination", "destination", "order_fact.destination", "destination"),
                listOf("common.business_date", "business_date", "order_fact.pay_time", "day"),
            ).forEach { (dimension, analysis, unit, level) ->
                val previous = f.dimensions.listDimensionEventBindings(dimension, 1).single()
                f.dimensions.reviseDimensionEventBinding(GovernanceRevisionRequest(previous.id, 1, DimensionEventBindingCreateRequest(
                    dimension, 1, GovernanceOrderFixture.EVENT, 2, unit, 1, null, RelationAccessPath(emptyList()), emptyMap(), "golden")))
                f.activate("DIMENSION_EVENT_BINDING", previous.id, 2)
                f.analysis.createAnalysisDimensionEventMapping(AnalysisDimensionEventMappingCreateRequest(
                    "${GovernanceOrderFixture.SUBJECT}.$analysis", 1, level, previous.id, 2, "PROVEN_SAME_KEY", "alignment:$analysis"))
            }
            f.activate("SUBJECT_AREA", GovernanceOrderFixture.SUBJECT)
            f.build("two_event_versions")
        }
        PlatformDatabase.openSingleInstance(metadata).use { database ->
            val f = GovernanceOrderFixture(database, directory)
            val publication = f.publication("two_event_versions")
            val day = QueryComputation.TimeRange(ScopedInputContract.DimensionRef("${GovernanceOrderFixture.SUBJECT}.business_date", "1", "day"),
                TemporalWindow(TemporalWindow.ValueType.DATE, TemporalWindow.BOUNDARY, "2026-07-21", "2026-07-22"))
            for ((code, expected) in mapOf("pay_amount" to "140", "a_amount" to "100")) {
                val adoption = f.adoptions.metricAdoption(MetricAdoption.identity(GovernanceOrderFixture.SUBJECT, code), 1)
                val frame = f.execute(publication, GovernanceOrderFixture.measurementScope(adoption.instance, timeRange = day)).result.resultFrame
                assertEquals(1, frame.rows.size)
                assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(frame.rows.single()["value"].toString())), code)
            }
        }
    }

    @Test
    fun scenarioRevisionChangesItsGrantWhileOldPublicationKeepsItsExactPermission(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val f = GovernanceOrderFixture(database, directory, listOf(
                "INSERT INTO main.orders VALUES ('1',100,'A','2026-07-21','c1'),('2',40,'B','2026-07-21','c2'),('3',60,'A','2026-07-22','c3')"))
            f.seedDefinitions()
            f.approveDefinitions()
            val subject = GovernanceOrderFixture.SUBJECT
            val scenario = "$subject.access"
            val subjectDefinition = SubjectAreaCreateRequest("travel", 1, "trade", "交易主题域", "trade", "成员读取授权", "golden")
            f.subjects.createSubjectArea(subjectDefinition)
            val first = f.adoptions.createMemberSource(MemberSourceCreateRequest(subject, 1, "orders", "订单", OrderMemberDefinitions.projection(MemberShape.RECORDS),
                CalculationBindings.empty(), emptyMap(), "golden"))
            f.scenarios.createScenario(ScenarioCreateRequest(subject, 1, "access", "订单读取", "analyst", "金额与订单", "golden"))
            f.scenarios.createScenarioMemberSourceRef(ScenarioMemberSourceRefCreateRequest(scenario, 1, first.id, 1,
                MemberQueryPermission(listOf("order", "amount"), emptyList(), mapOf("amount" to listOf(MemberCondition.Operator.GTE)), false, emptyList())))
            f.activate("SUBJECT_AREA", subject)
            f.builder().build(SubjectAreaReleaseBuildRequest("access_v1", "1", "原订单权限", subject, 1, "golden", f.sourceAdoptions))

            f.subjects.reviseSubjectArea(GovernanceRevisionRequest(subject, 1, subjectDefinition))
            val second = f.adoptions.createMemberSource(MemberSourceCreateRequest(subject, 2, "orders", "订单", OrderMemberDefinitions.projection(MemberShape.RECORDS),
                CalculationBindings.empty(), emptyMap(), "golden"))
            f.scenarios.reviseScenario(GovernanceRevisionRequest(scenario, 1, ScenarioCreateRequest(subject, 2, "access", "订单读取", "analyst", "只读取订单", "golden")))
            f.scenarios.createScenarioMemberSourceRef(ScenarioMemberSourceRefCreateRequest(scenario, 2, second.id, 2,
                MemberQueryPermission(listOf("order"), emptyList(), emptyMap(), false, emptyList())))
            f.activate("SUBJECT_AREA", subject, 2)
            f.builder().build(SubjectAreaReleaseBuildRequest("access_v2", "1", "收窄订单权限", subject, 2, "golden", f.sourceAdoptions))

            val oldPublication = f.artifacts.loadExecutableCatalog(ReleaseCatalogRequest(scenario, 1, "access_v1", "1"))
            val newPublication = f.artifacts.loadExecutableCatalog(ReleaseCatalogRequest(scenario, 2, "access_v2", "1"))
            val minimum = MemberCondition.Predicate("amount", MemberCondition.Operator.GTE, listOf(com.olap.prototype.calculation.Calculation.Scalar(ScalarValueType.NUMBER, "50")))
            fun query(version: Long, outputs: List<String>, condition: MemberCondition) = QueryComputation.Scope(QueryComputation.Members("orders", MemberRef(first.id, version), emptyMap(), outputs.map { QueryComputation.Slot(it, it) }, null, emptyMap(), condition, emptyMap(), emptyMap()), emptyMap(), emptyList())
            val original = f.execute(oldPublication, query(1, listOf("order", "amount"), minimum)).result.resultFrame
            assertEquals(setOf("1", "3"), original.rows.map { it["order"] }.toSet())
            assertEquals(setOf("100", "60"), original.rows.map { BigDecimal(it["amount"].toString()).stripTrailingZeros().toPlainString() }.toSet())
            val allowed = f.execute(newPublication, query(2, listOf("order"), MemberCondition.Unrestricted())).result.resultFrame
            assertEquals(setOf("1", "2", "3"), allowed.rows.map { it["order"] }.toSet())
            listOf(query(2, listOf("order"), minimum), query(2, listOf("order", "amount"), MemberCondition.Unrestricted()),
                query(1, listOf("order"), MemberCondition.Unrestricted())).forEach { denied ->
                val failure = assertThrows(EngineExecutionException::class.java) { f.execute(newPublication, denied) }
                assertEquals("EXECUTABLE_QUERY_NOT_PUBLISHED", failure.code)
            }

            val diff = DefinitionRevisionDiffService(database).read(DefinitionRevisionDiffRequest("SCENARIO", scenario, 1, 2))
            val authorization = diff.changes.single { it.classification == DefinitionRevisionDiff.Classification.AUTHORIZATION && it.path == "/authorization/members" }
            assertInstanceOf(DefinitionRevisionDiff.Value.Text::class.java, authorization.before)
            assertInstanceOf(DefinitionRevisionDiff.Value.Text::class.java, authorization.after)
            assertNotEquals(authorization.before, authorization.after)
            assertTrue(DefinitionImpact.Reference("MEMBER_SOURCE", first.id, "1") in diff.removedDependencies)
            assertTrue(DefinitionImpact.Reference("MEMBER_SOURCE", second.id, "2") in diff.addedDependencies)
            for ((version, release) in mapOf(1L to "access_v1", 2L to "access_v2")) {
                val impact = DefinitionImpactService(database).read(DefinitionImpactRequest("MEMBER_SOURCE", first.id, version))
                val granted = impact.dependents.single { it.reference == DefinitionImpact.Reference("SCENARIO", scenario, version.toString()) }
                assertTrue(granted.path.any { it.relation == DefinitionImpact.Relation.AUTHORIZATION })
                assertEquals(setOf(release), impact.dependents.filter { it.reference.assetType == "RELEASE" }.map { it.reference.id }.toSet())
            }
        }
    }
}
