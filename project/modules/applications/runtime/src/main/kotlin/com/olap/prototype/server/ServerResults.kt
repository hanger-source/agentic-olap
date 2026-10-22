package com.olap.prototype.server

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.query.contract.QueryCapabilityRequest
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

/** 持久run和结果权限的唯一owner；只核对Go实际交付回报，不读写结果文件。 */
internal class ServerResults(private val usage: QueryUsageRepository,
    private val publications: JdbcPublishedQueryEntryProvider, private val authorization: ServerAuthorization,
    private val retentionDays: Long, private val maximumPending: Int) : AutoCloseable {
    private val mapper = OlapJsonUtils.mapper()
    private data class Pending(val subject: String, val sha256: String, val bytes: Long, val rows: Long, val expires: Long)
    private val pending = linkedMapOf<String, Pending>()

    @Synchronized
    fun remember(request: ServerRequest, prepared: QueryResultArtifact.Prepared) {
        expirePending()
        if (pending.size >= maximumPending) throw RuntimeResourceLimitException("RESULT_DELIVERY_ADMISSION_EXCEEDED")
        check(pending.putIfAbsent(prepared.runId, Pending(request.principal.subject, prepared.sha256,
            prepared.content.size.toLong(), prepared.rows, request.work.deadline + TimeUnit.SECONDS.toNanos(5))) == null) {
            "run already has a pending result delivery"
        }
    }

    @Synchronized
    private fun expirePending() {
        val expired = pending.filterValues { System.nanoTime() >= it.expires }.keys.toList()
        expired.forEach { id -> usage.resultFailed(id, "result delivery confirmation expired"); pending.remove(id) }
    }

    fun retainHistory() {
        expirePending()
        usage.purgeHistoryBefore(Instant.now().minus(Duration.ofDays(retentionDays)))
    }

    private fun authorizedRun(id: String, principal: ServerAuthorization.Principal, operation: String): QueryUsageRepository.Run {
        val run = usage.require(id)
        if (run.subject != principal.subject) throw SecurityException("run is not authorized for this subject")
        authorization.publication(principal, operation, run.subjectAreaId, run.publication.releaseId)
        return run
    }

    fun authorize(request: ServerRequest, payload: JsonNode, operation: String): Any {
        fields(payload, setOf("resultReference", "operation"))
        authorization.operation(request.principal, operation)
        val reference = payload.required("resultReference")
        fields(reference, setOf("runId", "sha256"))
        val run = authorizedRun(text(reference, "runId"), request.principal, operation)
        val saved = run.result
        if (saved == null || saved.sha256 != text(reference, "sha256"))
            throw ProducerContractException("saved result reference is unavailable")
        return mapOf("resultReference" to mapOf("runId" to run.runId, "sha256" to saved.sha256))
    }

    @Synchronized
    fun completeDelivery(request: ServerRequest, payload: JsonNode): Any {
        fields(payload, setOf("runId", "deliveryStatus", "resultReference", "error", "resultBytes", "resultRows", "serviceObservation"))
        val id = text(payload, "runId")
        val run = usage.require(id)
        if (run.subject != request.principal.subject) throw SecurityException("result delivery subject differs from accepted run")
        authorization.operation(request.principal, "execute-query")
        val observation = payload.required("serviceObservation")
        fields(observation, setOf("scope", "timingNanos", "workers"))
        require(observation.path("scope").textValue() == "SERVER_BEFORE_DELIVERY_CONFIRMATION" &&
            observation.path("timingNanos").isObject && observation.path("workers").isArray) { "invalid service observation" }
        when (text(payload, "deliveryStatus")) {
            "DIRECT" -> { usage.directProduced(id); pending.remove(id) }
            "SAVED" -> {
                val expected = pending[id] ?: throw ProducerContractException("prepared result delivery is unavailable")
                val reference = payload.required("resultReference")
                fields(reference, setOf("runId", "sha256"))
                if (expected.subject != request.principal.subject || text(reference, "runId") != id ||
                    text(reference, "sha256") != expected.sha256 || requiredLong(payload, "resultBytes") != expected.bytes ||
                    requiredLong(payload, "resultRows") != expected.rows)
                    throw ProducerContractException("saved result facts differ from the exact prepared artifact")
                usage.resultSaved(id, expected.sha256, expected.bytes, expected.rows)
                pending.remove(id)
            }
            "FAILED" -> { usage.resultFailed(id, "result delivery failed"); pending.remove(id) }
            else -> throw ProducerContractException("unsupported actual delivery status")
        }
        usage.recordServiceObservation(id, observation)
        return mapOf("runId" to id, "recorded" to true)
    }

    fun search(request: ServerRequest, payload: JsonNode): Any {
        fields(payload, setOf("coordinate", "text", "offset", "limit"))
        val coordinate = mapper.treeToValue(payload.required("coordinate"), QueryCapabilityRequest::class.java)
        authorization.publication(request.principal, "search-query-usage", publications.require(coordinate))
        return usage.search(request.principal.subject, coordinate, optionalText(payload, "text"),
            integer(payload, "offset", 0, 0, Int.MAX_VALUE), integer(payload, "limit", 50, 1, 200))
    }

    fun read(request: ServerRequest, payload: JsonNode): Any {
        fields(payload, setOf("runId", "offset", "limit"))
        val run = authorizedRun(text(payload, "runId"), request.principal, "read-query-usage")
        return mapOf("run" to run, "notes" to usage.notes(run.runId, integer(payload, "offset", 0, 0, Int.MAX_VALUE),
            integer(payload, "limit", 50, 1, 200)), "references" to usage.references(run.runId))
    }

    fun appendNote(request: ServerRequest, payload: JsonNode): Any {
        fields(payload, setOf("runId", "text"))
        val run = authorizedRun(text(payload, "runId"), request.principal, "append-query-usage-note")
        return usage.appendNote(run.runId, request.principal.subject, text(payload, "text"))
    }

    private fun requiredLong(value: JsonNode, name: String): Long {
        val number = value.required(name)
        if (!number.isIntegralNumber || !number.canConvertToLong() || number.longValue() < 0)
            throw ProducerContractException("$name must be a nonnegative exact integer")
        return number.longValue()
    }

    @Synchronized override fun close() { pending.clear() }
}
