package com.olap.prototype.management.governance

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.query.QueryComputation
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.contract.QueryCapabilityRequest
import com.olap.prototype.query.definition.*
import com.olap.prototype.query.usage.QueryUsageRepository
import com.olap.prototype.release.catalog.JdbcPublishedQueryEntryProvider
import com.olap.prototype.runtime.BoundedJson
import com.olap.prototype.runtime.RuntimeResourceLimitException
import com.olap.prototype.storage.PlatformDatabase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class PublishedQueryHistoryTest {
    @Test
    fun persistsExactQueriesAndExecutionFactsIndependentlyOfDeliveryAndOptionalHistory(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val c = ComputedOrderFixture(database, directory); c.publish()
            val publication = c.publication()
            val approved = QueryComputation.Scope(c.formed(mapOf("window" to c.july), condition = MemberCondition.Predicate("amount", MemberCondition.Operator.GTE,
                listOf(Calculation.Scalar(ScalarValueType.NUMBER, "50")))), emptyMap(), emptyList())
            val original = c.freeze(approved)
            val repository = JdbcQueryDefinitionRepository(database, RuntimeDeploymentLimits.io)
            val reference = repository.requireReference(original.definitionId, original.definitionVersion)
            val update = QueryDefinitionCommand(original.definitionId, 1, requireNotNull(publication.queryCapability.capabilityHash), "实际合格订单明确修订",
                "travel.trade.formed", 1, "formed_orders", "1", original.intent)
            assertEquals(2L, c.queries.update(original.definitionId, update, "analyst").definitionVersion)
            assertThrows(IllegalArgumentException::class.java) { c.queries.update(original.definitionId, update, "analyst") }
            val reopened = JdbcQueryDefinitionRepository(database, RuntimeDeploymentLimits.io)
            assertEquals(original, reopened.findByIdAndVersion(original.definitionId, 1)); assertEquals(reference, reopened.requireReference(original.definitionId, 1))
            val provider = JdbcPublishedQueryEntryProvider(database)
            val coordinate = QueryCapabilityRequest("travel.trade.formed", 1, "formed_orders", "1")
            val usage = QueryUsageRepository(database, RuntimeDeploymentLimits.io)
            val run = "run-${UUID.randomUUID().toString().replace("-", "")}"
            usage.accept(run, "analyst", provider.require(coordinate), original.intent, reference, true, true, "七月合格订单有哪些？")
            usage.dispatched(run)
            val actual = c.f.execute(publication, QueryComputation.parse(original.intent.required("computation"))).result.resultFrame
            assertEquals(1, actual.rows.size); assertEquals("3", actual.rows.single()["order"])
            usage.complete(run, QueryUsageRepository.Execution.SUCCEEDED, null, com.olap.prototype.util.OlapJsonUtils.mapper().createObjectNode())
            usage.resultFailed(run, "结果存储不可用，执行成功事实保留")
            for (produceDirect in listOf(true, false)) {
                val direct = "run-${UUID.randomUUID().toString().replace("-", "")}"
                usage.accept(direct, "analyst", provider.require(coordinate), original.intent, reference, false, false, null)
                assertFalse(usage.require(direct).saveRequested); assertEquals(QueryUsageRepository.Delivery.PENDING, usage.require(direct).delivery)
                usage.dispatched(direct)
                val result = c.f.execute(publication, QueryComputation.parse(original.intent.required("computation"))).result.resultFrame
                assertEquals(1, result.rows.size)
                usage.complete(direct, QueryUsageRepository.Execution.SUCCEEDED, null, com.olap.prototype.util.OlapJsonUtils.mapper().createObjectNode())
                if (produceDirect) usage.directProduced(direct)
                else {
                    val failure = assertThrows(RuntimeResourceLimitException::class.java) { BoundedJson.encode(result, 1) }
                    usage.resultFailed(direct, failure.code)
                }
                val persisted = QueryUsageRepository(database, RuntimeDeploymentLimits.io).require(direct)
                assertEquals(QueryUsageRepository.Execution.SUCCEEDED, persisted.execution)
                assertEquals(if (produceDirect) QueryUsageRepository.Delivery.DIRECT else QueryUsageRepository.Delivery.FAILED, persisted.delivery)
                assertFalse(persisted.historyEnabled); assertNull(persisted.intent)
            }
            val note = usage.appendNote(run, "analyst", "本次资格按原始支付日期和金额门槛确认")
            val reopenedUsage = QueryUsageRepository(database, RuntimeDeploymentLimits.io)
            assertEquals(QueryUsageRepository.Execution.SUCCEEDED, reopenedUsage.require(run).execution)
            assertEquals(QueryUsageRepository.Delivery.FAILED, reopenedUsage.require(run).delivery)
            assertEquals(listOf(note), reopenedUsage.notes(run, 0, 10))
            assertEquals(run, reopenedUsage.search("analyst", coordinate, "支付日期", 0, 10).items.single().runId)
            assertTrue(reopenedUsage.search("another-subject", coordinate, null, 0, 10).items.isEmpty())
            assertFalse(reopenedUsage.references(run).isEmpty())
            reopenedUsage.purgeHistoryBefore(Instant.now().plusSeconds(1))
            assertTrue(reopenedUsage.search("analyst", coordinate, null, 0, 10).items.isEmpty()); assertNull(reopenedUsage.require(run).intent)
            assertEquals(reference, reopened.requireReference(original.definitionId, 1))
        }
    }
}
