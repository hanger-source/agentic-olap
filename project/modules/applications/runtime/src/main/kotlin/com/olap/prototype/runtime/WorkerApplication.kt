package com.olap.prototype.runtime

import com.google.protobuf.ByteString
import com.olap.prototype.connector.loader.ConnectorPlugins
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.protocol.Bootstrap
import com.olap.prototype.protocol.Request
import com.olap.prototype.protocol.Response
import com.olap.prototype.protocol.WorkerKind
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.DeadlineExecutionControl
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.contract.SourceReadEvidence
import java.nio.file.Path

/** 单角色worker的实际状态；角色/精确来源变更由Go销毁进程重建。 */
internal class WorkerApplication(bootstrap: Bootstrap, private val budgets: RuntimeBudgets) : AutoCloseable {
    private val mapper = OlapJsonUtils.mapper()
    private val plugins = ConnectorPlugins.load(bootstrap.connectorManifestsList.map(Path::of))
    private val kind = bootstrap.workerKind
    private val configured = BoundedJson.decode(bootstrap.sourceConfigurationsJson.toByteArray(), budgets.io).let { sources ->
        require(sources.isArray && sources.size() <= budgets.io.inputFiles) { "worker sources exceed deployment input budget" }
        mapper.treeToValue(sources, Array<SourceConfiguration>::class.java).toList()
    }

    private sealed interface Role {
        class Engine(val handler: EngineHandler) : Role
        class Query(val handler: QueryHandler) : Role
        class Inspection(val handler: InspectionHandler) : Role
        class SourceInspection(val handler: SourceInspectionHandler) : Role
        class Values(val handler: CandidateHandler) : Role
    }

    private val role: Role = when (kind) {
        WorkerKind.ENGINE -> Role.Engine(EngineHandler(plugins.registry, budgets, configured))
        WorkerKind.QUERY -> {
            require(configured.isEmpty()) { "query validation worker cannot receive source access" }
            Role.Query(QueryHandler(plugins.registry, budgets))
        }
        WorkerKind.INSPECTION -> Role.Inspection(InspectionHandler(plugins.registry, configured))
        WorkerKind.SOURCE_INSPECTION -> {
            require(configured.size == 1) { "technical source inspection requires one exact source" }
            Role.SourceInspection(SourceInspectionHandler(plugins.registry, configured.single()))
        }
        WorkerKind.CANDIDATE_VALUES -> Role.Values(CandidateHandler(plugins.registry, budgets, configured))
        else -> throw IllegalArgumentException("explicit worker kind is required")
    }

    @Synchronized
    fun response(request: Request): Response {
        var completedReadEvidence: SourceReadEvidence? = null
        var sourceExecutionStarted: Boolean? = false
        val timings = linkedMapOf<String, Long>()
        fun <T> timed(stage: String, action: () -> T): T {
            val started = System.nanoTime()
            return try { action() } finally { timings[stage] = System.nanoTime() - started }
        }
        fun <C, R> invoke(decode: () -> C, handle: (C) -> R, encode: (R) -> com.google.protobuf.Message): com.google.protobuf.Message {
            val command = timed("DECODE", decode)
            val result = timed("HANDLE") { handle(command) }
            return timed("ENCODE") { encode(result) }
        }
        return try {
            require(request.operation == "worker-command" && !request.hasPrincipal()) { "worker accepts only supervisor commands" }
            require(request.remainingMillis in 1..budgets.requestMillis) { "request exceeds deployed deadline" }
            DeadlineExecutionControl(request.remainingMillis).use { control ->
                    require(request.hasWorkerModel() && request.payloadJson.isEmpty) { "workers require their concrete Protobuf command" }
                    val output = when (val actual = role) {
                        is Role.Engine -> invoke({ QueryWorkerProto.fromProto(ProtoBytes.unpack(request.workerModel,
                            com.olap.prototype.wire.query.EngineWorkerCommand.getDefaultInstance(), budgets.frameBytes, budgets.io.inputDepth)) }, { command ->
                            if (command is EngineWorkerCommand.Execute) sourceExecutionStarted = null
                            actual.handler.execute(command, control) { completedReadEvidence = it; sourceExecutionStarted = true }
                        }, QueryWorkerProto::toProto)
                        is Role.Query -> invoke({ QueryWorkerProto.fromProto(ProtoBytes.unpack(request.workerModel,
                            com.olap.prototype.wire.query.QueryWorkerCommand.getDefaultInstance(), budgets.frameBytes, budgets.io.inputDepth)) },
                            { actual.handler.execute(it, control) }, QueryWorkerProto::toProto)
                        is Role.Inspection -> invoke({ com.olap.prototype.codec.GovernanceProto.fromProto(ProtoBytes.unpack(request.workerModel,
                            com.olap.prototype.wire.governance.SourceInspection.getDefaultInstance(), budgets.frameBytes, budgets.io.inputDepth)) }, { command ->
                            if (command != com.olap.prototype.source.contract.SourceInspection.Capabilities) sourceExecutionStarted = null
                            actual.handler.execute(command, control)
                        }, com.olap.prototype.codec.GovernanceProto::toProto)
                        is Role.SourceInspection -> invoke({ SourceWorkerProto.fromProto(ProtoBytes.unpack(request.workerModel,
                            com.olap.prototype.wire.query.SourceWorkerCommand.getDefaultInstance(), budgets.frameBytes, budgets.io.inputDepth)) }, { command ->
                            sourceExecutionStarted = null
                            actual.handler.execute(command, control)
                        }, SourceWorkerProto::toProto)
                        is Role.Values -> invoke({ CandidateWorkerProto.fromProto(ProtoBytes.unpack(request.workerModel,
                            com.olap.prototype.wire.query.CandidateWorkerCommand.getDefaultInstance(), budgets.frameBytes, budgets.io.inputDepth)) }, { command ->
                            if (command is CandidateWorkerCommand.Values) sourceExecutionStarted = null
                            actual.handler.execute(command, control)
                        }, CandidateWorkerProto::toProto)
                    }
                    val encoded = try { timed("PACK") { ProtoBytes.pack(output, budgets.io.responseBytes) } }
                    catch (failure: Exception) {
                        val evidence = completedReadEvidence ?: throw failure
                        throw EngineExecutionException("ENCODE_RESULT", "WORKER_RESULT_ENCODING_FAILED",
                            "source execution succeeded but worker result encoding failed", failure, sourceReadEvidence = evidence, sourceExecutionStarted = true)
                    }
                Response.newBuilder().setRequestId(request.requestId).setSuccess(true)
                    .setModel(encoded).putAllTimingNanos(timings).build()
            }
        } catch (failure: Exception) {
            val logger = org.slf4j.LoggerFactory.getLogger("runtime.worker")
            logger.warn("worker operation failed: kind={}, requestId={}", kind, request.requestId, failure)
            WorkerFailures.recordLocal(logger, kind, request.requestId, failure)
            Response.newBuilder().setRequestId(request.requestId).setSuccess(false)
                .putAllTimingNanos(timings)
                .setWorkerFailure(WorkerFailures.facts(failure, sourceExecutionStarted))
                .setErrorJson(ByteString.copyFrom(mapper.writeValueAsBytes(WorkerFailures.project(kind, failure)))).build()
        }
    }

    override fun close() = plugins.close()
}
