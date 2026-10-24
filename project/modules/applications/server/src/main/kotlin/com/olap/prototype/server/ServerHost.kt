package com.olap.prototype.server

import com.fasterxml.jackson.databind.JsonNode
import com.google.protobuf.ByteString
import com.olap.prototype.codec.CapabilityProtos
import com.olap.prototype.protocol.CapabilityFailure
import com.olap.prototype.protocol.CapabilityInvocation
import com.olap.prototype.protocol.CapabilityResponse
import com.olap.prototype.runtime.RuntimeBudgets
import com.olap.prototype.server.config.Deployment
import com.olap.prototype.server.http.ExternalJson
import com.olap.prototype.server.process.ManagedProcesses
import com.olap.prototype.server.scripting.ScriptReport
import com.olap.prototype.server.scripting.ScriptSessions
import com.olap.prototype.server.scripting.ScriptSubmission
import com.olap.prototype.warehouse.DeadlineExecutionControl
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 一份部署直接拥有正式应用与受管进程；传输会话不拥有业务或语言状态。 */
class ServerHost(private val deployment: Deployment, distribution: Path) : ApplicationEndpoint, AutoCloseable {
    private val log = org.slf4j.LoggerFactory.getLogger(ServerHost::class.java)
    private val configuration = deployment.json()
    private val budgets = RuntimeBudgets(configuration.required("limits"))
    private val processes = ManagedProcesses(configuration, distribution)
    private val scripts = ScriptSessions(processes)
    private val workers = WorkerPool(processes)
    private val application = try { ServerApplication.open(configuration, budgets.io, workers) }
        catch (failure: Throwable) { processes.close(); throw failure }
    private val codecs = CapabilityProtos(budgets.io.requestBytes, budgets.io.inputDepth)
    private val admitted = Semaphore(deployment.limits.maxInFlight, true)
    private val closed = AtomicBoolean()
    private val lifecycle = Object()
    private val active = hashSetOf<String>()
    override val authorizationPolicyHash: String get() = application.authorizationPolicyHash
    override fun operations(principal: ServerAuthorization.Principal) = application.descriptions(principal)

    override fun call(principal: ServerAuthorization.Principal, operation: String, payload: JsonNode,
        remainingMillis: Long): ApplicationResponse = run(principal, remainingMillis, operation) { request ->
        require(payload.isObject) { "operation payload must be one JSON object" }
        when (operation) {
            "execute-script" -> {
                application.authorize(principal, operation)
                val submission = ExternalJson.mapper.treeToValue(payload, ScriptSubmission::class.java)
                scripts.submit(principal, submission) { invocation, deadline ->
                    capability(principal, invocation, deadline)
                }
            }
            "wait-script" -> {
                application.authorize(principal, operation)
                ServerInputs.fields(payload, setOf("executionId", "waitMillis"))
                scripts.wait(principal, ServerInputs.text(payload, "executionId"), waitMillis(payload))
            }
            "cancel-script" -> {
                application.authorize(principal, operation)
                ServerInputs.fields(payload, setOf("executionId"))
                scripts.cancel(principal, ServerInputs.text(payload, "executionId"))
            }
            "read-script-session", "reset-script-session", "close-script-session" -> {
                application.authorize(principal, operation)
                ServerInputs.fields(payload, setOf("sessionId"))
                val session = ServerInputs.text(payload, "sessionId")
                when (operation) {
                    "read-script-session" -> scripts.readSession(principal, session)
                    "reset-script-session" -> scripts.resetSession(principal, session)
                    else -> scripts.closeSession(principal, session)
                }
            }
            else -> application.dispatch(operation, request, payload)
        }
    }

    private fun capability(principal: ServerAuthorization.Principal, invocation: CapabilityInvocation,
        deadlineNanos: Long): CapabilityResponse {
        val remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
        val response = run(principal, remaining, invocation.operationId) { request -> application.invoke(request, invocation) }
        return CapabilityResponse.newBuilder().setOperationRequestId(response.requestId).setSuccess(response.success)
            .addAllEffects(response.effects).apply {
                if (response.success) {
                    try { setResult(codecs.encodeResult(invocation.operationId, requireNotNull(response.value))) }
                    catch (failure: Exception) {
                        log.error("capability result encoding failed after application: requestId={} operation={}", response.requestId, invocation.operationId, failure)
                        setSuccess(false)
                        setFailure(CapabilityFailure.newBuilder().setStage("ENCODE_RESULT").setCode("CAPABILITY_RESULT_ENCODING_FAILED")
                            .setMessage("operation completed; result encoding failed; inspect recorded effects without replaying")
                            .setOperationId(invocation.operationId))
                    }
                } else response.error?.let { error ->
                    setFailure(CapabilityFailure.newBuilder().setStage(error.stage).setCode(error.code).setMessage(error.message)
                        .setOperationId(invocation.operationId).setDetailsJson(ByteString.copyFrom(ExternalJson.mapper.writeValueAsBytes(error.details)))
                        .apply { (error.details["fieldPath"] as? List<*>)?.map(Any?::toString)?.let(::addAllFieldPath) })
                }
            }.build()
    }

    private fun run(principal: ServerAuthorization.Principal, remainingMillis: Long, operation: String,
        action: (ServerRequest) -> Any): ApplicationResponse {
        val id = "request-${UUID.randomUUID()}"
        val effects = arrayListOf<com.olap.prototype.protocol.CapabilityEffect>()
        var work: WorkerPool.Request? = null
        var permit = false
        var started = false
        try {
            synchronized(lifecycle) {
                check(!closed.get()) { "service is closing" }
                check(active.add(id))
                started = true
            }
            require(remainingMillis > 0) { "positive remaining request duration is required" }
            val deadline = ManagedProcesses.deadlineAfter(remainingMillis)
            if (operation !in controlOperations) {
                if (!admitted.tryAcquire(remainingMillis, TimeUnit.MILLISECONDS))
                    throw com.olap.prototype.runtime.RuntimeResourceLimitException("REQUEST_DEADLINE_EXCEEDED")
                permit = true
            }
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            DeadlineExecutionControl(remaining).use { control ->
                val current = WorkerPool.Request(id, control, deadline)
                work = current
                val value = action(ServerRequest(principal, current, effects))
                val report = value as? ScriptReport
                val success = report == null || report.state == "RUNNING" || report.success
                return ApplicationResponse(report?.requestId ?: id, success, value,
                    if (success) null else ApplicationFailure("SCRIPT", "SCRIPT_NOT_COMPLETED",
                        "代码执行未成功结束；读取实际输出、诊断及正式调用记录", mapOf("sessionId" to report!!.sessionId,
                            "executionId" to report.executionId, "state" to report.state)),
                    effects.toList(), current.timingNanos.toMap(), current.workers.toList())
            }
        } catch (failure: Exception) {
            log.warn("application operation failed: requestId={} operation={}", id, operation, failure)
            val error = ServerFailures.project(failure, "APPLICATION", "OPERATION_FAILED")
            return ApplicationResponse(id, false, error = ApplicationFailure(error.stage, error.code,
                error.message, error.details), effects = effects.toList(), timingNanos = work?.timingNanos?.toMap().orEmpty(),
                workers = work?.workers?.toList().orEmpty())
        } finally {
            if (permit) admitted.release()
            if (started) synchronized(lifecycle) { active.remove(id); lifecycle.notifyAll() }
        }
    }

    private fun waitMillis(payload: JsonNode): Long = payload.get("waitMillis")?.let {
        require(it.isIntegralNumber && it.canConvertToLong() && it.longValue() >= 0) { "waitMillis must be a nonnegative integer" }
        it.longValue()
    } ?: 0L

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // 脚本与来源进程先终止并确认，之后才释放持久应用与配置。
        try { scripts.close() } finally {
            try { processes.close() } finally {
                synchronized(lifecycle) { while (active.isNotEmpty()) lifecycle.wait() }
                application.close()
            }
        }
    }

    private companion object {
        val controlOperations = setOf("describe-script-api", "describe-operation", "execute-script", "wait-script", "cancel-script",
            "read-script-session", "reset-script-session", "close-script-session")
    }
}
