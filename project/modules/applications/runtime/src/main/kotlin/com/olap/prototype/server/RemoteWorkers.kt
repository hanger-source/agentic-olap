package com.olap.prototype.server

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.protocol.*
import com.olap.prototype.runtime.ProtocolPeer
import com.olap.prototype.runtime.RuntimeResourceLimitException
import com.olap.prototype.runtime.EngineWorkerCommand
import com.olap.prototype.runtime.EngineWorkerResult
import com.olap.prototype.runtime.QueryWorkerCommand
import com.olap.prototype.runtime.QueryWorkerResult
import com.olap.prototype.runtime.QueryWorkerProto
import com.olap.prototype.runtime.SourceWorkerCommand
import com.olap.prototype.runtime.SourceWorkerResult
import com.olap.prototype.runtime.SourceWorkerProto
import com.olap.prototype.runtime.CandidateWorkerCommand
import com.olap.prototype.runtime.CandidateWorkerResult
import com.olap.prototype.runtime.CandidateWorkerProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.codec.QuerySourceProto
import com.olap.prototype.source.contract.SourceInspection
import com.olap.prototype.source.contract.SourceInspectionResult
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.contract.SourceConfiguration
import java.util.concurrent.TimeUnit

/** 显式监督者工作端口；没有进程句柄、池、凭据读取或运行配置生成。 */
class RemoteWorkers(private val peer: ProtocolPeer, private val maximumModelBytes: Int, private val maximumDepth: Int) {
    private val log = org.slf4j.LoggerFactory.getLogger(RemoteWorkers::class.java)
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

        fun engine(command: EngineWorkerCommand): EngineWorkerResult {
            require(kind == Kind.ENGINE) { "engine command requires an engine worker lease" }
            val input = request.timed("${kind.name}_WORKER_ENCODE") { QueryWorkerProto.toProto(command) }
            val response = exchangeModel(input, input.kindCase.name)
            return request.timed("${kind.name}_WORKER_DECODE") { QueryWorkerProto.fromProto(ProtoBytes.unpack(response,
                com.olap.prototype.wire.query.EngineWorkerResult.getDefaultInstance(), maximumModelBytes, maximumDepth)) }
        }
        fun query(command: QueryWorkerCommand): QueryWorkerResult {
            require(kind == Kind.QUERY) { "query command requires a query worker lease" }
            val input = request.timed("${kind.name}_WORKER_ENCODE") { QueryWorkerProto.toProto(command) }
            val response = exchangeModel(input, input.kindCase.name)
            return request.timed("${kind.name}_WORKER_DECODE") { QueryWorkerProto.fromProto(ProtoBytes.unpack(response,
                com.olap.prototype.wire.query.QueryWorkerResult.getDefaultInstance(), maximumModelBytes, maximumDepth)) }
        }
        fun inspect(command: SourceInspection): SourceInspectionResult {
            require(kind == Kind.INSPECTION) { "inspection requires its own worker lease" }
            val input = request.timed("${kind.name}_WORKER_ENCODE") { GovernanceProto.toProto(command) }
            val response = exchangeModel(input, "INSPECT")
            return request.timed("${kind.name}_WORKER_DECODE") { GovernanceProto.fromProto(ProtoBytes.unpack(response,
                com.olap.prototype.wire.governance.SourceInspectionResult.getDefaultInstance(), maximumModelBytes, maximumDepth)) }
        }
        fun source(command: SourceWorkerCommand): SourceWorkerResult {
            require(kind == Kind.SOURCE_INSPECTION) { "source investigation requires its own worker lease" }
            val input = request.timed("${kind.name}_WORKER_ENCODE") { SourceWorkerProto.toProto(command) }
            val response = exchangeModel(input, input.kindCase.name)
            return request.timed("${kind.name}_WORKER_DECODE") { SourceWorkerProto.fromProto(ProtoBytes.unpack(response,
                com.olap.prototype.wire.query.SourceWorkerResult.getDefaultInstance(), maximumModelBytes, maximumDepth)) }
        }
        fun candidate(command: CandidateWorkerCommand): CandidateWorkerResult {
            require(kind == Kind.CANDIDATE_VALUES) { "candidate command requires its own worker lease" }
            val input = request.timed("${kind.name}_WORKER_ENCODE") { CandidateWorkerProto.toProto(command) }
            val response = exchangeModel(input, input.kindCase.name)
            return request.timed("${kind.name}_WORKER_DECODE") { CandidateWorkerProto.fromProto(ProtoBytes.unpack(response,
                com.olap.prototype.wire.query.CandidateWorkerResult.getDefaultInstance(), maximumModelBytes, maximumDepth)) }
        }
        private fun exchangeModel(command: com.google.protobuf.Message, action: String): com.google.protobuf.Any {
            check(!released) { "worker lease is closed" }
            val packed = request.timed("${kind.name}_WORKER_PACK") { ProtoBytes.pack(command, maximumModelBytes) }
            val reply = request.timed("${kind.name}_WORKER_$action") { peer.work(request.requestId, request.remainingMillis(),
                WorkRequest.newBuilder().setExchange(ExchangeWorker.newBuilder().setLeaseId(id).setCommandModel(packed))) }
            reply.timingNanosMap.forEach { (stage, nanos) -> request.timingNanos.merge("${kind.name}_WORKER_${action}_$stage", nanos, Long::plus) }
            log.debug("worker exchange: requestId={} kind={} action={} commandBytes={} resultBytes={} workerNanos={}",
                request.requestId, kind, action, packed.value.size(), if (reply.hasModel()) reply.model.value.size() else 0, reply.timingNanosMap)
            requireSuccess(reply, kind)
            require(reply.hasModel()) { "semantic worker response requires a concrete model" }
            return reply.model
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

    class WorkerFailure(val kind: Kind, val error: JsonNode, facts: com.olap.prototype.wire.query.WorkerFailureFacts?) : RuntimeException("worker operation failed") {
        val sourceReadEvidence = facts?.takeIf { it.hasSourceReadEvidence() }?.let { QuerySourceProto.fromProto(it.sourceReadEvidence) }
        val sourceFailure = facts?.takeIf { it.hasSourceFailure() }?.let { QuerySourceProto.fromProto(it.sourceFailure) }
        val sourceExecutionStarted = facts?.takeIf { it.hasSourceExecutionStarted() }?.sourceExecutionStarted
        val inspectionFailure = facts?.takeIf { it.hasInspectionFailure() }?.inspectionFailure
        val stageNanos: Map<String, Long> = facts?.stageNanosMap?.toMap().orEmpty()
    }

    private fun requireSuccess(response: WorkResponse, kind: Kind) {
        if (!response.success) {
            val error = OlapJsonUtils.mapper().readTree(response.errorJson.toByteArray())
            if (response.hasWorkerFailure()) throw WorkerFailure(kind, error, response.workerFailure)
            when (error.path("code").asText()) {
                "CANCELLED" -> throw java.util.concurrent.CancellationException("supervised work was cancelled")
                "WORKER_DEADLINE_EXCEEDED" -> throw RuntimeResourceLimitException("WORKER_DEADLINE_EXCEEDED")
            }
            if (response.leaseInvalidated) throw IllegalStateException("supervisor worker transport failed: ${error.path("code").asText()}")
            throw WorkerFailure(kind, error, null)
        }
    }
}
