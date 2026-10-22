package com.olap.prototype.runtime

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.protobuf.ByteString
import com.olap.prototype.connector.loader.ConnectorPlugins
import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.protocol.Bootstrap
import com.olap.prototype.protocol.Request
import com.olap.prototype.protocol.Response
import com.olap.prototype.protocol.WorkerKind
import com.olap.prototype.server.ServerInputs.fields
import com.olap.prototype.server.ServerInputs.text
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
        return try {
            require(request.operation == "worker-command" && !request.hasPrincipal()) { "worker accepts only supervisor commands" }
            require(request.remainingMillis in 1..budgets.requestMillis) { "request exceeds deployed deadline" }
            val command = BoundedJson.decode(request.payloadJson.toByteArray(), budgets.io)
            require(command is ObjectNode) { "worker command must be an object" }
            DeadlineExecutionControl(request.remainingMillis).use { control ->
                val result = when (val actual = role) {
                    is Role.Engine -> actual.handler.execute(command, control) { completedReadEvidence = it }
                    else -> {
                        fields(command, setOf("operation", "payload"))
                        val operation = text(command, "operation")
                        val payload = command.required("payload")
                        when (actual) {
                            is Role.Query -> actual.handler.execute(operation, payload, control)
                            is Role.Inspection -> actual.handler.execute(operation, payload, control)
                            is Role.SourceInspection -> actual.handler.execute(operation, payload, control)
                            is Role.Values -> actual.handler.execute(operation, payload, control)
                            is Role.Engine -> error("engine dispatch scope differs")
                        }
                    }
                }
                val encoded = try { BoundedJson.encode(result, budgets.io.responseBytes) }
                catch (failure: Exception) {
                    val evidence = completedReadEvidence ?: throw failure
                    throw EngineExecutionException("ENCODE_RESULT",
                        if (failure is RuntimeResourceLimitException) failure.code else "WORKER_RESULT_ENCODING_FAILED",
                        "source execution succeeded but worker result encoding failed", failure, sourceReadEvidence = evidence)
                }
                Response.newBuilder().setRequestId(request.requestId).setSuccess(true)
                    .setResultJson(ByteString.copyFrom(encoded)).build()
            }
        } catch (failure: Exception) {
            org.slf4j.LoggerFactory.getLogger("runtime.worker").warn("worker operation failed: kind={}, requestId={}", kind, request.requestId, failure)
            Response.newBuilder().setRequestId(request.requestId).setSuccess(false)
                .setErrorJson(ByteString.copyFrom(mapper.writeValueAsBytes(WorkerFailures.project(kind, failure)))).build()
        }
    }

    override fun close() = plugins.close()
}
