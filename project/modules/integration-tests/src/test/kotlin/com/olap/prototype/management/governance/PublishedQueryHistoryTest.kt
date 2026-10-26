package com.olap.prototype.management.governance

import com.google.protobuf.ByteString
import com.olap.prototype.calculation.Calculation
import com.olap.prototype.contract.InputContractException
import com.olap.prototype.governance.contract.DeploymentAvailability
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.contract.QueryCapabilityRequest
import com.olap.prototype.query.contract.QueryExecutionReceipt
import com.olap.prototype.query.contract.QueryExecutionStatus
import com.olap.prototype.query.contract.QueryClientOutcome
import com.olap.prototype.query.contract.QueryServiceObservation
import com.olap.prototype.query.contract.QueryResultFormatException
import com.olap.prototype.query.contract.QueryResultReference
import com.olap.prototype.query.contract.SavedQueryResultRecord
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.codec.QueryProto
import com.olap.prototype.engine.executable.ExecutableExecutionResult
import com.olap.prototype.warehouse.contract.SourceExecutionState
import com.olap.prototype.query.definition.*
import com.olap.prototype.query.usage.QueryResultArtifact
import com.olap.prototype.query.usage.QueryUsageRepository
import com.olap.prototype.release.catalog.JdbcPublishedQueryEntryProvider
import com.olap.prototype.server.results.ResultFiles
import com.olap.prototype.storage.PlatformDatabase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class PublishedQueryHistoryTest {
    private fun receipt(runId: String, execution: ExecutableExecutionResult) = QueryExecutionReceipt(runId, QueryExecutionStatus.SUCCEEDED,
        null, execution.businessResult, false, execution.businessResult.rows.size.toLong(), QueryClientOutcome.COMPLETED,
        SourceExecutionState.SUCCEEDED, null, execution.sourceReadEvidence, execution.result.stageNanos, null)
    @Test
    fun persistsExactQueriesAndExecutionFactsIndependentlyOfDeliveryAndOptionalHistory(@TempDir directory: Path) {
        PlatformDatabase.inMemory().use { database ->
            val c = ComputedOrderFixture(database, directory); c.publish()
            val publication = c.publication()
            val approved = Calculation.Scope(c.formed(mapOf("window" to c.july), condition = MemberCondition.Predicate("amount", MemberCondition.Operator.GTE,
                listOf(Calculation.Scalar(ScalarValueType.NUMBER, "50")))), emptyMap(), emptyList())
            val original = c.freeze(approved)
            val repository = JdbcQueryDefinitionRepository(database, RuntimeDeploymentLimits.io)
            val reference = repository.requireReference(original.definitionId, original.definitionVersion)
            val update = QueryDefinitionCommand(original.definitionId, 1, requireNotNull(publication.queryCapability.capabilityHash), "实际合格订单明确修订", original.intent, null)
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
            val execution = c.f.execute(publication, original.intent.computation)
            val actual = execution.result.resultFrame
            assertEquals(1, actual.rows.size); assertEquals("3", actual.rows.single()["order"])
            usage.complete(run, QueryUsageRepository.Execution.SUCCEEDED, null, receipt(run, execution))
            usage.resultFailed(run, "结果存储不可用，执行成功事实保留")
            for (produceDirect in listOf(true, false)) {
                val direct = "run-${UUID.randomUUID().toString().replace("-", "")}"
                usage.accept(direct, "analyst", provider.require(coordinate), original.intent, reference, false, false, null)
                assertFalse(usage.require(direct).saveRequested); assertEquals(QueryUsageRepository.Delivery.PENDING, usage.require(direct).delivery)
                usage.dispatched(direct)
                val directStarted = System.nanoTime()
                val directExecution = c.f.execute(publication, original.intent.computation)
                val directNanos = System.nanoTime() - directStarted
                assertEquals(1, directExecution.result.resultFrame.rows.size)
                val receipt = receipt(direct, directExecution)
                usage.complete(direct, QueryUsageRepository.Execution.SUCCEEDED, null, receipt)
                if (produceDirect) usage.completeDelivery(QueryUsageRepository.Delivery.DIRECT, receipt.copy(result = null), null,
                    QueryServiceObservation("SERVER_BEFORE_DELIVERY_CONFIRMATION", mapOf("DIRECT_EXECUTION" to directNanos), emptyList()))
                else {
                    val failure = assertThrows(IllegalArgumentException::class.java) { ProtoBytes.encode(QueryProto.toProto(receipt), 1) }
                    usage.resultFailed(direct, requireNotNull(failure.message))
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
            assertEquals(listOf(note), reopenedUsage.notes(run, 0, 10).items)
            assertEquals(run, reopenedUsage.search("analyst", coordinate, "支付日期", 0, 10).items.single().runId)
            assertTrue(reopenedUsage.search("another-subject", coordinate, null, 0, 10).items.isEmpty())
            assertFalse(reopenedUsage.references(run).isEmpty())

            // 从本次真实保存和执行字节构造历史格式边界；这些不是旧发行包的原始字节。
            val oldIntentFormat = "olap-query-intent/v9"
            val savedDefinition = reopened.requireArtifact(original.definitionId, original.definitionVersion)
            val definition = com.olap.prototype.wire.query.QueryDefinitionView.parseFrom(savedDefinition.definition)
            val oldIntent = definition.intent.toBuilder().setFormatVersion(oldIntentFormat).build()
            val oldDefinition = ProtoBytes.encode(definition.toBuilder().setIntent(oldIntent).build())
            val definitionFailure = assertThrows(IllegalArgumentException::class.java) {
                QueryDefinitionArtifact.decode(QueryDefinitionArtifact.Artifact("olap-query-definition-artifact/v13",
                    ProtoBytes.digest(oldDefinition), oldDefinition), RuntimeDeploymentLimits.io)
            }
            assertTrue(requireNotNull(definitionFailure.message).startsWith("unsupported query definition artifact format:"))

            val persisted = reopenedUsage.require(run)
            val record = SavedQueryResultRecord(SavedQueryResultRecord.FORMAT, run, coordinate, original.catalogHash,
                original.capabilityHash, original.intent, reference, persisted.acceptedAt, requireNotNull(persisted.completedAt),
                receipt(run, execution), null, null, original.intentHash)
            val resultFiles = ResultFiles(directory.resolve("format-boundary"))
            val currentResult = resultFiles.save(QueryResultArtifact.prepare(record))
            assertEquals(record, resultFiles.read(currentResult))
            val artifact = com.olap.prototype.wire.query.SavedQueryResultArtifact.parseFrom(Files.readAllBytes(resultFiles.location(currentResult)))
            val oldRecord = ProtoBytes.encode(com.olap.prototype.wire.query.SavedQueryResultRecord.parseFrom(artifact.recordBytes)
                .toBuilder().setFormatVersion("olap-query-result-record/v7").setIntent(oldIntent).build())
            val oldResult = QueryResultReference(run, ProtoBytes.digest(oldRecord))
            Files.write(resultFiles.location(oldResult), ProtoBytes.encode(artifact.toBuilder().setSha256(oldResult.sha256)
                .setRecordBytes(ByteString.copyFrom(oldRecord)).build()))
            val resultFailure = assertThrows(QueryResultFormatException::class.java) { resultFiles.read(oldResult) }
            assertEquals(QueryResultFormatException.Component.RECORD, resultFailure.component)
            assertEquals("olap-query-result-record/v7", resultFailure.actual)
            assertEquals(DeploymentAvailability.Status.UNSUPPORTED, resultFiles.inspect(oldResult).status)

            val access = reopenedUsage.requireResultAccess(run)
            database.connection().use { connection ->
                val retainedIntent = connection.prepareStatement("SELECT intent_proto FROM query_run WHERE run_id=?").use {
                    it.setString(1, run)
                    it.executeQuery().use { rows -> check(rows.next()); requireNotNull(rows.getString(1)) }
                }
                fun retainIntent(value: String) {
                    connection.prepareStatement("UPDATE query_run SET intent_proto=? WHERE run_id=?").use {
                        it.setString(1, value); it.setString(2, run); assertEquals(1, it.executeUpdate())
                    }
                }
                try {
                    retainIntent(ProtoBytes.storage(com.olap.prototype.wire.query.QueryIntent.parseFrom(ProtoBytes.stored(retainedIntent))
                        .toBuilder().setFormatVersion(oldIntentFormat).build()))
                    assertEquals(access, reopenedUsage.requireResultAccess(run))
                    val historyFailure = assertThrows(InputContractException::class.java) { reopenedUsage.require(run) }
                    assertEquals(InputContractException.Rule.QUERY_HISTORY_FORMAT, historyFailure.rule)
                    assertEquals(oldIntentFormat, historyFailure.details["actualKind"])
                } finally { retainIntent(retainedIntent) }
            }
            assertEquals(original.intent, reopenedUsage.require(run).intent)
            reopenedUsage.purgeHistoryBefore(Instant.now().plusSeconds(1))
            assertTrue(reopenedUsage.search("analyst", coordinate, null, 0, 10).items.isEmpty()); assertNull(reopenedUsage.require(run).intent)
            assertEquals(reference, reopened.requireReference(original.definitionId, 1))
        }
    }
}
