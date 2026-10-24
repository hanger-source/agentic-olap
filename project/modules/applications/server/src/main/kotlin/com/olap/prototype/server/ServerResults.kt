package com.olap.prototype.server

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.dsl.*
import com.olap.prototype.dsl.transport.CapabilityRegistry
import com.olap.prototype.server.results.ResultFiles
import com.olap.prototype.server.results.ResultPages
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

/** 实际运行、结果授权与持久交付；文件操作完成后记录真实结果，不设置中间确认协议。 */
internal class ServerResults(private val usage: QueryUsageRepository,
    private val publications: JdbcPublishedQueryEntryProvider, private val authorization: ServerAuthorization,
    private val retentionDays: Long, private val files: ResultFiles) : AutoCloseable {
    private val mapper = OlapJsonUtils.mapper()
    private val pages = ResultPages(files)
    fun retainHistory() { usage.purgeHistoryBefore(Instant.now().minus(Duration.ofDays(retentionDays))) }
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

    fun complete(request: ServerRequest, prepared: QueryResultArtifact.Prepared, executed: QueryExecutionReceipt): QueryExecutionReceipt {
        val run = authorizedRun(prepared.runId, request.principal, "execute-query")
        require(executed.runId == run.runId) { "execution and result artifact differ" }
        val reference = try {
            if (run.saveRequested) request.work.timed("SERVICE_SAVE_RESULT") { files.save(prepared) } else null
        } catch (failure: Exception) {
            usage.resultFailed(run.runId, "result persistence failed after execution")
            throw ResultPersistenceFailure(executed, failure)
        }
        val receipt = executed.copy(saved = reference != null, resultReference = reference,
            result = if (reference == null) executed.result else null,
            timingNanos = executed.timingNanos + request.work.timingNanos)
        val manifest = reference?.let { QueryUsageRepository.ResultManifest(run.runId, it.sha256,
            prepared.content.size.toLong(), prepared.rows, Instant.now()) }
        usage.completeDelivery(if (reference == null) QueryUsageRepository.Delivery.DIRECT else QueryUsageRepository.Delivery.SAVED,
            receipt.copy(result = null), manifest, QueryServiceObservation("SERVER_BEFORE_DELIVERY_CONFIRMATION",
                request.work.timingNanos.toMap(), request.work.workers.toList()))
        return receipt
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
            "read-query-result" -> (input as ResultReadRequest).also { authorize(request, it.resultReference, operation) }.let(pages::read)
            "read-query-result-metadata" -> (input as ResultMetadataRequest).also { authorize(request, it.resultReference, operation) }.let(pages::metadata)
            "read-query-result-value" -> (input as ResultValueRequest).also { authorize(request, it.resultReference, operation) }.let(pages::value)
            "profile-query-result" -> (input as ResultProfileRequest).also { authorize(request, it.resultReference, operation) }.let(pages::profile)
            else -> throw ProducerContractException("unregistered result operation: $operation")
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
    override fun close() = Unit
}

class ResultPersistenceFailure(val receipt: QueryExecutionReceipt, cause: Throwable) : RuntimeException("result persistence failed after execution", cause)
