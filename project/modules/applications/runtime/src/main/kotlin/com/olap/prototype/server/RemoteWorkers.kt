package com.olap.prototype.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.protobuf.ByteString
import com.olap.prototype.protocol.*
import com.olap.prototype.runtime.ProtocolPeer
import com.olap.prototype.runtime.RuntimeResourceLimitException
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.contract.SourceConfiguration
import java.util.concurrent.TimeUnit

/** 显式监督者工作端口；没有进程句柄、池、凭据读取或运行配置生成。 */
class RemoteWorkers(private val peer: ProtocolPeer) {
    enum class Kind { QUERY, ENGINE, INSPECTION, SOURCE_INSPECTION, CANDIDATE_VALUES }

    class Request(val requestId: String, val control: WarehouseExecutionControl, val deadline: Long) {
        val timingNanos = linkedMapOf<String, Long>()
        fun <T> timed(stage: String, action: () -> T): T {
            val started = System.nanoTime()
            return try { action() } finally { timingNanos.merge(stage, System.nanoTime() - started, Long::plus) }
        }
        fun remainingMillis(): Long {
            control.checkCancellation()
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining <= 0) throw RuntimeResourceLimitException("REQUEST_DEADLINE_EXCEEDED")
            return remaining
        }
    }

    fun borrow(kind: Kind, sources: List<SourceConfiguration>, request: Request): Lease {
        val acquire = AcquireWorker.newBuilder().setKind(WorkerKind.valueOf(kind.name))
        sources.forEach { acquire.addBindings(SourceBinding.newBuilder()
            .setDataSourceId(it.dataSourceId).setSourceIdentityHash(it.sourceIdentityHash())) }
        val response = request.timed("${kind.name}_WORKER_ACQUIRE") {
            peer.work(request.requestId, request.remainingMillis(), WorkRequest.newBuilder().setAcquire(acquire))
        }
        requireSuccess(response, kind)
        check(response.leaseId.isNotBlank()) { "supervisor omitted lease identity" }
        return Lease(response.leaseId, kind, request)
    }

    inner class Lease(private val id: String, private val kind: Kind, private val request: Request) : AutoCloseable {
        private var released = false

        fun exchange(command: ObjectNode): JsonNode {
            check(!released) { "worker lease is closed" }
            val reply = request.timed("${kind.name}_WORKER_${command.required("operation").textValue().uppercase().replace('-', '_')}") { peer.work(request.requestId, request.remainingMillis(),
                WorkRequest.newBuilder().setExchange(ExchangeWorker.newBuilder().setLeaseId(id)
                    .setCommandJson(ByteString.copyFrom(OlapJsonUtils.mapper().writeValueAsBytes(command))))) }
            requireSuccess(reply, kind)
            return OlapJsonUtils.mapper().readTree(reply.resultJson.toByteArray())
        }

        override fun close() {
            if (released) return
            released = true
            // Go also reclaims all leases at the parent request's terminal boundary.
            if (Thread.currentThread().isInterrupted) return
            val reply = peer.work(request.requestId, request.remainingMillis(),
                WorkRequest.newBuilder().setRelease(ReleaseWorker.newBuilder().setLeaseId(id)))
            requireSuccess(reply, kind)
        }
    }

    class WorkerFailure(val kind: Kind, val error: JsonNode) : RuntimeException("worker operation failed")

    private fun requireSuccess(response: WorkResponse, kind: Kind) {
        if (!response.success) {
            val error = OlapJsonUtils.mapper().readTree(response.errorJson.toByteArray())
            when (error.path("code").asText()) {
                "CANCELLED" -> throw java.util.concurrent.CancellationException("supervised work was cancelled")
                "WORKER_DEADLINE_EXCEEDED" -> throw RuntimeResourceLimitException("WORKER_DEADLINE_EXCEEDED")
            }
            if (response.leaseInvalidated) throw IllegalStateException("supervisor worker transport failed: ${error.path("code").asText()}")
            throw WorkerFailure(kind, error)
        }
    }
}
