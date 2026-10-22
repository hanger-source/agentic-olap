package com.olap.prototype.server

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.dsl.*
import com.olap.prototype.dsl.transport.CapabilityRegistry
import com.olap.prototype.protocol.DeliveryConfirmation
import com.olap.prototype.protocol.DeliveryStatus
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.usage.QueryResultArtifact
import com.olap.prototype.query.usage.QueryUsageRepository
import com.olap.prototype.release.catalog.JdbcPublishedQueryEntryProvider
import com.olap.prototype.runtime.RuntimeResourceLimitException
import com.olap.prototype.server.ServerInputs.fields
import com.olap.prototype.server.ServerInputs.integer
import com.olap.prototype.server.ServerInputs.optionalText
import com.olap.prototype.server.ServerInputs.text
import com.olap.prototype.util.OlapJsonUtils
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** 持久运行与结果权限的owner；只确认Go实际交付，不读写文件。 */
internal class ServerResults(private val usage: QueryUsageRepository,
    private val publications: JdbcPublishedQueryEntryProvider, private val authorization: ServerAuthorization,
    private val retentionDays: Long, private val maximumPending: Int) : AutoCloseable {
    private val mapper = OlapJsonUtils.mapper()
    private data class Pending(val subject: String, val sha256: String, val bytes: Long, val rows: Long,
        val expires: Long, val receipt: QueryExecutionReceipt)
    private val pending = linkedMapOf<String, Pending>()
    @Synchronized fun remember(request: ServerRequest, prepared: QueryResultArtifact.Prepared, receipt: QueryExecutionReceipt) {
        expirePending()
        if (pending.size >= maximumPending) throw RuntimeResourceLimitException("RESULT_DELIVERY_ADMISSION_EXCEEDED")
        check(pending.putIfAbsent(prepared.runId, Pending(request.principal.subject, prepared.sha256, prepared.content.size.toLong(),
            prepared.rows, request.work.deadline + TimeUnit.SECONDS.toNanos(5), receipt)) == null) { "run already has a pending result delivery" }
    }
    @Synchronized private fun expirePending() {
        pending.filterValues { System.nanoTime() >= it.expires }.keys.toList().forEach { id ->
            usage.resultFailed(id, "result delivery confirmation expired"); pending.remove(id)
        }
    }
    fun retainHistory() { expirePending(); usage.purgeHistoryBefore(Instant.now().minus(Duration.ofDays(retentionDays))) }
    private fun authorizedRun(id: String, principal: ServerAuthorization.Principal, operation: String): QueryUsageRepository.Run {
        val run = usage.require(id)
        if (run.subject != principal.subject) throw SecurityException("run is not authorized for this subject")
        authorization.publication(principal, operation, run.subjectAreaId, run.publication.releaseId)
        return run
    }
    fun authorize(request: ServerRequest, reference: QueryResultReference, operation: String): QueryResultReference {
        val permission = CapabilityRegistry.require(operation).permission
        authorization.operation(request.principal, permission)
        val run = authorizedRun(reference.runId, request.principal, permission)
        val saved = run.result
        if (saved == null || saved.sha256 != reference.sha256) throw ProducerContractException("saved result reference is unavailable")
        return QueryResultReference(run.runId, saved.sha256)
    }

    @Synchronized fun completeDelivery(request: ServerRequest, confirmation: DeliveryConfirmation,
        supplied: QueryExecutionReceipt): QueryExecutionReceipt {
        val id = confirmation.runId
        val run = authorizedRun(id, request.principal, "execute-query")
        val expected = pending[id] ?: throw ProducerContractException("prepared result delivery is unavailable")
        require(expected.subject == request.principal.subject && supplied == expected.receipt.copy(result = null)) {
            "delivery receipt differs from the exact prepared execution"
        }
        require(confirmation.sha256 == expected.sha256 && confirmation.bytes == expected.bytes && confirmation.rows == expected.rows) {
            "delivery facts differ from the exact prepared artifact"
        }
        val saved = when (confirmation.status) {
            DeliveryStatus.SAVED -> { require(run.saveRequested); usage.resultSaved(id, expected.sha256, expected.bytes, expected.rows); true }
            DeliveryStatus.DIRECT -> { require(!run.saveRequested); usage.directProduced(id); false }
            DeliveryStatus.FAILED -> { usage.resultFailed(id, "result delivery failed"); false }
            else -> throw ProducerContractException("actual result delivery status is required")
        }
        pending.remove(id)
        usage.recordServiceObservation(id, QueryServiceObservation("SERVER_BEFORE_DELIVERY_CONFIRMATION",
            confirmation.timingNanosMap.toMap(), confirmation.workersList.map {
                QueryWorkerAcquisition(it.kind,it.outcome,it.queueNanos,it.startupNanos)
            }))
        return expected.receipt.copy(saved = saved, resultReference = if (saved) QueryResultReference(id, expected.sha256) else null,
            result = if (confirmation.status == DeliveryStatus.DIRECT) expected.receipt.result else null,
            timingNanos = expected.receipt.timingNanos + confirmation.timingNanosMap)
    }

    fun invoke(operation: String, request: ServerRequest, input: Any): Any {
        retainHistory()
        return when (operation) {
            "read-query-usage" -> {
                val r = input as QueryRunRequest
                val run = authorizedRun(r.runId, request.principal, operation)
                val notes = usage.notes(run.runId, r.offset, r.limit + 1)
                QueryUsageView(view(run), notes.take(r.limit).map(::note), if (notes.size > r.limit) r.offset + r.limit else null,
                    usage.references(run.runId).map { QuerySemanticReference(it.assetType,it.id,it.version) })
            }
            "search-query-usage" -> {
                val r = input as QueryRunListRequest
                authorization.publication(request.principal, operation, publications.require(r.coordinate))
                val p = usage.search(request.principal.subject,r.coordinate,r.text,r.offset,r.limit)
                QueryUsagePage(p.items.map(::view),p.nextOffset)
            }
            "append-query-usage-note" -> {
                val r = input as QueryUsageNoteRequest
                val run = authorizedRun(r.runId,request.principal,operation)
                note(usage.appendNote(run.runId, request.principal.subject,r.text))
            }
            else -> throw ProducerContractException("result reading belongs to the Go result reader")
        }
    }
    private fun note(n: QueryUsageRepository.Note) = QueryUsageNote(n.id,n.runId,n.subject,n.text,n.createdAt)
    private fun view(r: QueryUsageRepository.Run) = QueryRunView(r.runId,r.subject,r.publication,r.subjectAreaId,r.subjectAreaVersion,
        r.catalogHash,r.capabilityHash,r.intentHash,QueryRunExecution.valueOf(r.execution.name),QueryRunDelivery.valueOf(r.delivery.name),
        r.acceptedAt,r.completedAt,r.historyEnabled,r.saveRequested,r.intent,r.definition,r.question,r.executionError,r.deliveryError,
        r.result?.let { QueryResultManifest(it.runId,it.sha256,it.bytes,it.rows,it.savedAt) },r.receipt,r.serviceObservation)

    fun search(request: ServerRequest, payload: JsonNode): Any {
        fields(payload,setOf("coordinate","text","offset","limit"))
        val coordinate = mapper.treeToValue(payload.required("coordinate"),QueryCapabilityRequest::class.java)
        return invoke("search-query-usage",request,QueryRunListRequest(coordinate,optionalText(payload,"text"),
            integer(payload,"offset",0,0,Int.MAX_VALUE),integer(payload,"limit",50,1,200)))
    }
    fun read(request: ServerRequest, payload: JsonNode): Any {
        fields(payload,setOf("runId","offset","limit"))
        return invoke("read-query-usage",request,QueryRunRequest(text(payload,"runId"),integer(payload,"offset",0,0,Int.MAX_VALUE),integer(payload,"limit",50,1,200)))
    }
    fun appendNote(request: ServerRequest, payload: JsonNode): Any {
        fields(payload,setOf("runId","text")); return invoke("append-query-usage-note",request,QueryUsageNoteRequest(text(payload,"runId"),text(payload,"text")))
    }
    @Synchronized override fun close() { pending.clear() }
}
