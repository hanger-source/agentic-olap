package com.olap.prototype.runtime

import com.olap.prototype.protocol.*
import com.olap.prototype.util.OlapJsonUtils
import java.io.BufferedInputStream
import java.nio.file.Path

/** 独立来源工作进程入口；主服务拥有进程，领域方法只在本进程执行。 */
fun main(args: Array<String>) {
    // 协议独占原始stdout；宿主和插件的普通输出、默认日志都进入stderr。
    val protocolOutput = System.out
    System.setOut(System.err)
    require(args.contentEquals(arrayOf("--mode", "worker"))) { "runtime --mode worker" }
    val input = BufferedInputStream(System.`in`)
    val initial = ProtocolPeer.readFrame(input, 64 * 1024 * 1024) ?: error("worker bootstrap is required")
    require(initial.bodyCase == Frame.BodyCase.BOOTSTRAP && initial.bootstrap.protocol == "agentic-olap-runtime/v5") {
        "unsupported runtime protocol bootstrap"
    }
    val bootstrap = initial.bootstrap
    val mapper = OlapJsonUtils.mapper()
    val budgets = RuntimeBudgets(mapper.readTree(bootstrap.limitsJson.toByteArray()))
    ProtocolPeer(input, protocolOutput, budgets.frameBytes, Path.of(bootstrap.transferDirectory)).use { peer ->
        budgets.requireWorkerHeap()
        WorkerApplication(bootstrap, budgets).use { worker ->
            peer.send(Frame.newBuilder().setResponse(Response.newBuilder().setRequestId("bootstrap").setSuccess(true)).build())
            peer.serve { request -> worker.response(request) { evidence ->
                peer.send(com.olap.prototype.protocol.Frame.newBuilder().setSourceReadCompleted(
                    com.olap.prototype.protocol.SourceReadCompleted.newBuilder().setRequestId(request.requestId)
                        .setEvidence(com.olap.prototype.codec.QuerySourceProto.toProto(evidence))).build())
            } }
        }
    }
}
