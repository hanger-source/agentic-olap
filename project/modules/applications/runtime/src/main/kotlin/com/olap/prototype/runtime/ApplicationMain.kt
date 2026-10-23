package com.olap.prototype.runtime

import com.fasterxml.jackson.databind.JsonNode
import com.google.protobuf.ByteString
import com.olap.prototype.protocol.*
import com.olap.prototype.server.*
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.DeadlineExecutionControl
import java.io.BufferedInputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** 统一JVM构件，仅有metadata/worker模式；全部启动、池化及强制取消属于Go。 */
fun main(args: Array<String>) {
    // 协议独占原始stdout；宿主和插件的普通输出、默认日志都进入stderr。
    val protocolOutput = System.out
    System.setOut(System.err)
    require(args.size == 2 && args[0] == "--mode" && args[1] in setOf("metadata", "worker")) {
        "runtime --mode metadata|worker"
    }
    val input = BufferedInputStream(System.`in`)
    val initial = ProtocolPeer.readFrame(input, 64 * 1024 * 1024) ?: error("supervisor bootstrap is required")
    require(initial.bodyCase == Frame.BodyCase.BOOTSTRAP && initial.bootstrap.protocol == "agentic-olap-runtime/v2") {
        "unsupported runtime protocol bootstrap"
    }
    val bootstrap = initial.bootstrap
    val mapper = OlapJsonUtils.mapper()
    val budgets = RuntimeBudgets(mapper.readTree(bootstrap.limitsJson.toByteArray()))
    ProtocolPeer(input, protocolOutput, budgets.frameBytes, Path.of(bootstrap.transferDirectory)).use { peer ->
        when (args[1]) {
            "metadata" -> {
                val config = BoundedJson.decode(bootstrap.deploymentJson.toByteArray(), budgets.io)
                ServerApplication.open(config, budgets.io, RemoteWorkers(peer)).use { application ->
                    peer.send(Frame.newBuilder().setResponse(Response.newBuilder().setRequestId("bootstrap")
                        .setSuccess(true).setReady(application.ready())).build())
                    try { peer.serve { request -> metadataResponse(application, request, budgets) } }
                    finally { peer.close() }
                }
            }
            "worker" -> {
                budgets.requireWorkerHeap()
                WorkerApplication(bootstrap, budgets).use { worker ->
                    peer.send(Frame.newBuilder().setResponse(Response.newBuilder().setRequestId("bootstrap").setSuccess(true)).build())
                    try { peer.serve { request -> worker.response(request) } }
                    finally { peer.close() }
                }
            }
        }
    }
}

private fun metadataResponse(application: ServerApplication, wire: Request, budgets: RuntimeBudgets): Response {
    val mapper = OlapJsonUtils.mapper()
    val completedEffects = ArrayList<CapabilityEffect>(1)
    return try {
        require(wire.hasPrincipal()) { "authenticated principal is required" }
        DeadlineExecutionControl(wire.remainingMillis).use { control ->
            val request = ServerRequest(ServerAuthorization.Principal(wire.principal.subject, wire.principal.scopesList),
                RemoteWorkers.Request(wire.requestId, control, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(wire.remainingMillis)),
                completedEffects)
            require(!wire.hasWorkerModel()) { "metadata process does not accept worker commands" }
            require(listOf(wire.hasCapability(), wire.hasDelivery(), wire.hasResultAuthorization(), !wire.payloadJson.isEmpty).count { it } == 1) {
                "request must select one explicit application contract"
            }
            val result = when {
                wire.hasCapability() -> {
                    require(wire.operation == "call-capability" && wire.capability.serializedSize <= budgets.io.requestBytes) {
                        "invalid or oversized capability invocation"
                    }
                    application.invoke(request, wire.capability)
                }
                wire.hasDelivery() -> {
                    require(wire.operation == "complete-result-delivery") {
                        "invalid result confirmation"
                    }
                    application.completeDelivery(request, wire.delivery)
                }
                wire.hasResultAuthorization() -> {
                    require(wire.operation == "authorize-result" && wire.resultAuthorization.serializedSize <= budgets.io.requestBytes) {
                        "invalid result authorization request"
                    }
                    application.authorizeResult(request, wire.resultAuthorization)
                }
                else -> {
                    require(wire.payloadJson.size() <= budgets.io.requestBytes) { "request exceeds deployed JSON byte budget" }
                    application.dispatch(wire.operation, request, BoundedJson.decode(wire.payloadJson.toByteArray(), budgets.io))
                }
            }
            if (result is Response) result.toBuilder().addAllEffects(completedEffects).build()
            else Response.newBuilder().setRequestId(wire.requestId).setSuccess(true)
                .setResultJson(ByteString.copyFrom(mapper.writeValueAsBytes(result)))
                .addAllEffects(completedEffects).build()
        }
    } catch (failure: Exception) {
        org.slf4j.LoggerFactory.getLogger("runtime.metadata").warn("metadata request failed: requestId={}, operation={}", wire.requestId, wire.operation, failure)
        val error = ServerFailures.project(failure, "application", "OPERATION_FAILED")
        Response.newBuilder().setRequestId(wire.requestId).setSuccess(false)
            .setErrorJson(ByteString.copyFrom(mapper.writeValueAsBytes(error))).addAllEffects(completedEffects).build()
    }
}
