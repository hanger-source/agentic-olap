package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.governance.contract.*
import com.olap.prototype.query.contract.MetricAdoption
import com.olap.prototype.storage.PlatformDatabase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class GovernanceLifecycleReleaseTest {
    @Test
    fun activatesUnidentifiedEventsAndRejectsInexactIdentityIndexes(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val f = GovernanceOrderFixture(database, directory)
            f.seedDefinitions()
            f.activate("LOGICAL_TABLE", "order_fact")
            f.activate("TOPOLOGY", GovernanceOrderFixture.TOPOLOGY)
            listOf(false, true).forEach { hasRole ->
                val identity = EventIdentity(emptyList(), if (hasRole) mapOf("customer" to listOf(EventIdentity.LogicUnitRef("order_fact.customer_id", 1))) else emptyMap())
                val created = f.events.createEvent(EventCreateRequest(GovernanceOrderFixture.TOPOLOGY, 1,
                    if (hasRole) "observations_by_customer" else "observations", "无声明记录身份的观测", "trade", null, null,
                    identity, emptyMap(), emptyList(), emptyList(), "重复观测不构成记录键", "golden"))
                f.activate("EVENT", created.event.id)
                assertEquals("ACTIVE", f.events.eventSnapshot(created.event.id, 1).status)
                assertEquals(identity, f.events.eventIdentity(created.event.id, 1))
            }
            database.connection().use { connection -> connection.createStatement().use { sql ->
                sql.executeUpdate("""
                    INSERT INTO logic_unit_version (id,logic_unit_id,version,version_status,content_proto,diagnostic_json,source_version,created_by,created_at,activated_by,activated_at)
                    SELECT 'order_fact.order_id:v2',logic_unit_id,2,'DRAFT',content_proto,diagnostic_json,1,created_by,created_at,NULL,NULL
                    FROM logic_unit_version WHERE logic_unit_id='order_fact.order_id' AND version=1
                """.trimIndent())
                sql.executeUpdate("UPDATE event_logic_unit_ref SET logic_unit_version=2 WHERE event_id='${GovernanceOrderFixture.EVENT}' AND ref_role='RECORD_KEY'")
            } }
            val mismatch = assertThrows(ProducerContractException::class.java) { f.activate("EVENT", GovernanceOrderFixture.EVENT) }
            assertTrue(mismatch.message.orEmpty().startsWith("event identity differs from its exact dependency index"))
            database.connection().use { connection -> connection.createStatement().use { it.executeUpdate("DELETE FROM event_logic_unit_ref WHERE event_id='${GovernanceOrderFixture.EVENT}' AND ref_role='RECORD_KEY'") } }
            val missing = assertThrows(ProducerContractException::class.java) { f.activate("EVENT", GovernanceOrderFixture.EVENT) }
            assertTrue(missing.message.orEmpty().startsWith("event identity differs from its exact dependency index"))
            assertEquals("DRAFT", f.events.eventSnapshot(GovernanceOrderFixture.EVENT, 1).status)
        }
    }

    @Test
    fun rejectsDraftReleaseClosureAndKeepsLifecycleStateUnchanged(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val f = GovernanceOrderFixture(database, directory)
            f.seedDefinitions()
            f.subjects.createSubjectArea(SubjectAreaCreateRequest("travel", 1, "trade", "交易主题域", "trade", "未批准的草稿闭包", "golden"))
            assertThrows(ProducerContractException::class.java) { f.build("release_draft") }
            assertFalse(CanonicalReleaseRepository(database).existsRelease("release_draft", "1"))
            f.activate("LOGICAL_TABLE", "order_fact")
            assertThrows(ProducerContractException::class.java) { f.activate("EVENT", GovernanceOrderFixture.EVENT) }
            assertEquals("DRAFT", f.events.eventSnapshot(GovernanceOrderFixture.EVENT, 1).status)
        }
    }

    @Test
    fun rejectsIncompleteOwnedScenarioWithoutPartialActivationAndPublishesAfterExplicitRepair(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val f = GovernanceOrderFixture(database, directory)
            f.seed()
            f.activateSubjectGraph(false)
            f.scenarios.createScenario(ScenarioCreateRequest(GovernanceOrderFixture.SUBJECT, 1, "incomplete", "待补成员场景", "analyst", "必须显式组织完整成员", "golden"))
            val failure = assertThrows(ProducerContractException::class.java) { f.activate("SUBJECT_AREA", GovernanceOrderFixture.SUBJECT) }
            assertTrue(failure.message.orEmpty().contains("SCENARIO_REAL_INPUT_REQUIRED"))
            assertEquals("DRAFT", f.subjects.subjectAreaSnapshot(GovernanceOrderFixture.SUBJECT, 1).status)
            assertEquals("DRAFT", f.scenarios.scenarioSnapshot(GovernanceOrderFixture.SCENARIO, 1).status)
            assertEquals("DRAFT", f.scenarios.scenarioSnapshot("travel.trade.incomplete", 1).status)
            assertEquals("DRAFT", f.analysis.analysisDimensionSnapshot("travel.trade.business_date", 1).status)
            f.grantMetric(f.paymentId, "travel.trade.incomplete")
            f.scenarios.createScenarioAnalysisDimensionRef(ScenarioAnalysisDimensionRefCreateRequest("travel.trade.incomplete", 1, "travel.trade.business_date", 1, "day", true, 1))
            f.activate("SUBJECT_AREA", GovernanceOrderFixture.SUBJECT)
            assertEquals("ACTIVE", f.scenarios.scenarioSnapshot(GovernanceOrderFixture.SCENARIO, 1).status)
            assertEquals("ACTIVE", f.scenarios.scenarioSnapshot("travel.trade.incomplete", 1).status)
            f.build("repaired_members")
            assertTrue(CanonicalReleaseRepository(database).existsRelease("repaired_members", "1"))
        }
    }

    @Test
    fun keepsExactActiveBindingContentWhenANewerDraftUsesAnotherStringColumn(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val f = GovernanceOrderFixture(database, directory)
            f.seedDefinitions(); f.approveDefinitions()
            f.subjects.createSubjectArea(SubjectAreaCreateRequest("travel", 1, "trade", "交易主题域", "trade", "交易分析", "golden"))
            f.dimensions.createDimension(DimensionCreateRequest("global.destination", "全局维度", "目的地", "data", "目的地", "golden"))
            val original = f.dimensions.createDimensionEventBinding(DimensionEventBindingCreateRequest("global.destination", 1, GovernanceOrderFixture.EVENT, 1,
                "order_fact.destination", 1, null, RelationAccessPath(emptyList()), emptyMap(), "golden")).binding
            f.activate("DIMENSION", "global.destination"); f.activate("DIMENSION_EVENT_BINDING", original.id)
            f.dimensions.reviseDimensionEventBinding(GovernanceRevisionRequest(original.id, 1, DimensionEventBindingCreateRequest("global.destination", 1,
                GovernanceOrderFixture.EVENT, 1, "order_fact.customer_id", 1, null, RelationAccessPath(emptyList()), emptyMap(), "golden")))
            assertEquals("ACTIVE", f.dimensions.dimensionEventBindingSnapshot(original.id, 1).status)
            assertEquals("DRAFT", f.dimensions.dimensionEventBindingSnapshot(original.id, 2).status)
            assertEquals("order_fact.customer_id", f.dimensions.dimensionEventBindingSnapshot(original.id, 2).logicUnitId)
            f.analysis.createAnalysisDimension(AnalysisDimensionCreateRequest(GovernanceOrderFixture.SUBJECT, 1, "destination", "目的地", "目的地分析", "destination", "目的地", "golden"))
            f.analysis.createAnalysisDimensionEventMapping(AnalysisDimensionEventMappingCreateRequest("travel.trade.destination", 1, "destination", original.id, 1, "SINGLE_EVENT_ONLY", null))
            val provider = f.analysis.createAnalysisDimensionValueProvider(AnalysisDimensionValueProviderCreateRequest("travel.trade.destination", 1, "destination", original.id, 1, "ON_DEMAND", "golden"))
            val candidate = f.analysis.analysisDimensionValueProviderCandidates("travel.trade.destination", 1).mappedBindings.first()
            assertEquals("order_fact.destination", candidate.logicUnitId)
            assertEquals("目的地", candidate.logicUnitDisplayName)
            assertEquals("order_fact.destination", requireNotNull(f.analysis.findAnalysisDimensionValueProvider(provider.provider.id)).sourceLogicUnitId)
        }
    }
}
