package com.olap.prototype.server

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.protocol.*
import com.olap.prototype.server.process.ManagedProcesses
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

/** 应用使用正式工作合同；仅跨工作进程时编码，不经过元数据监督者。 */
class WorkerPool(private val processes: ManagedProcesses) {
    private val log = org.slf4j.LoggerFactory.getLogger(WorkerPool::class.java)
    enum class Kind { QUERY, ENGINE, INSPECTION, SOURCE_INSPECTION, CANDIDATE_VALUES }

    class Request(val requestId: String, val control: WarehouseExecutionControl, val deadline: Long) {
        val timingNanos = linkedMapOf<String, Long>()
        val workers = arrayListOf<com.olap.prototype.query.contract.QueryWorkerAcquisition>()
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
        val lease = request.timed("${kind.name}_WORKER_ACQUIRE") {
            processes.acquire(WorkerKind.valueOf(kind.name), sources, request.requestId, request.remainingMillis())
        }
        request.workers += lease.observation
        return Lease(lease, kind, request)
    }

    inner class Lease(private val process: ManagedProcesses.Lease, private val kind: Kind, private val request: Request) : AutoCloseable {
        private var released = false

        fun engine(command: EngineWorkerCommand): EngineWorkerResult {
            require(kind == Kind.ENGINE) { "engine command requires an engine worker lease" }
            val input = request.timed("${kind.name}_WORKER_ENCODE") { QueryWorkerProto.toProto(command) }
            val response = exchangeModel(input, input.kindCase.name)
            return request.timed("${kind.name}_WORKER_DECODE") { QueryWorkerProto.fromProto(ProtoBytes.unpackResponse(response,
                com.olap.prototype.wire.query.EngineWorkerResult.getDefaultInstance())) }
        }
        fun query(command: QueryWorkerCommand): QueryWorkerResult {
            require(kind == Kind.QUERY) { "query command requires a query worker lease" }
            val input = request.timed("${kind.name}_WORKER_ENCODE") { QueryWorkerProto.toProto(command) }
            val response = exchangeModel(input, input.kindCase.name)
            return request.timed("${kind.name}_WORKER_DECODE") { QueryWorkerProto.fromProto(ProtoBytes.unpackResponse(response,
                com.olap.prototype.wire.query.QueryWorkerResult.getDefaultInstance())) }
        }
        fun inspect(command: SourceInspection): SourceInspectionResult {
            require(kind == Kind.INSPECTION) { "inspection requires its own worker lease" }
            val input = request.timed("${kind.name}_WORKER_ENCODE") { GovernanceProto.toProto(command) }
            val response = exchangeModel(input, "INSPECT")
            return request.timed("${kind.name}_WORKER_DECODE") { GovernanceProto.fromProto(ProtoBytes.unpackResponse(response,
                com.olap.prototype.wire.governance.SourceInspectionResult.getDefaultInstance())) }
        }
        fun source(command: SourceWorkerCommand): SourceWorkerResult {
            require(kind == Kind.SOURCE_INSPECTION) { "source investigation requires its own worker lease" }
            val input = request.timed("${kind.name}_WORKER_ENCODE") { SourceWorkerProto.toProto(command) }
            val response = exchangeModel(input, input.kindCase.name)
            return request.timed("${kind.name}_WORKER_DECODE") { SourceWorkerProto.fromProto(ProtoBytes.unpackResponse(response,
                com.olap.prototype.wire.query.SourceWorkerResult.getDefaultInstance())) }
        }
        fun candidate(command: CandidateWorkerCommand): CandidateWorkerResult {
            require(kind == Kind.CANDIDATE_VALUES) { "candidate command requires its own worker lease" }
            val input = request.timed("${kind.name}_WORKER_ENCODE") { CandidateWorkerProto.toProto(command) }
            val response = exchangeModel(input, input.kindCase.name)
            return request.timed("${kind.name}_WORKER_DECODE") { CandidateWorkerProto.fromProto(ProtoBytes.unpackResponse(response,
                com.olap.prototype.wire.query.CandidateWorkerResult.getDefaultInstance())) }
        }
        private fun exchangeModel(command: com.google.protobuf.Message, action: String): com.google.protobuf.Any {
            check(!released) { "worker lease is closed" }
            val packed = request.timed("${kind.name}_WORKER_PACK") { ProtoBytes.pack(command) }
            val reply = request.timed("${kind.name}_WORKER_$action") {
                process.exchange(com.olap.prototype.protocol.Request.newBuilder().setRequestId(request.requestId)
                    .setRemainingMillis(request.remainingMillis()).setOperation("worker-command").setWorkerModel(packed).build())
            }
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
            process.close()
        }
    }

    class WorkerFailure(val kind: Kind, val error: JsonNode, facts: com.olap.prototype.wire.query.WorkerFailureFacts?) : RuntimeException("worker operation failed") {
        val sourceReadEvidence = facts?.takeIf { it.hasSourceReadEvidence() }?.let { QuerySourceProto.fromProto(it.sourceReadEvidence) }
        val sourceFailure = facts?.takeIf { it.hasSourceFailure() }?.let { QuerySourceProto.fromProto(it.sourceFailure) }
        val sourceExecutionStarted = facts?.takeIf { it.hasSourceExecutionStarted() }?.sourceExecutionStarted
        val inspectionFailure = facts?.takeIf { it.hasInspectionFailure() }?.inspectionFailure
        val stageNanos: Map<String, Long> = facts?.stageNanosMap?.toMap().orEmpty()
    }

    private fun requireSuccess(response: Response, kind: Kind) {
        if (!response.success) {
            val error = OlapJsonUtils.mapper().readTree(response.errorJson.toByteArray())
            if (response.hasWorkerFailure()) throw WorkerFailure(kind, error, response.workerFailure)
            when (error.path("code").asText()) {
                "CANCELLED" -> throw java.util.concurrent.CancellationException("worker operation was cancelled")
                "WORKER_DEADLINE_EXCEEDED" -> throw RuntimeResourceLimitException("WORKER_DEADLINE_EXCEEDED")
            }
            throw WorkerFailure(kind, error, null)
        }
    }
}
