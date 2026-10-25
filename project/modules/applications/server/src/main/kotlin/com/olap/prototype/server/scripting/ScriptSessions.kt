package com.olap.prototype.server.scripting

import com.google.protobuf.ByteString
import com.olap.prototype.protocol.*
import com.olap.prototype.server.ServerAuthorization
import com.olap.prototype.server.process.ManagedProcesses
import com.olap.prototype.server.process.ManagedProcesses.Companion.positive
import com.olap.prototype.server.process.ManagedProcesses.Companion.remaining
import com.olap.prototype.server.process.ManagedJvm
import com.olap.prototype.server.process.ScriptSandbox
import com.olap.prototype.server.process.PrivateDirectories
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The service owns sessions and executions; the dedicated JVM alone owns persistent language history. */
class ScriptSessions(private val processes: ManagedProcesses) : AutoCloseable {
    private val log = org.slf4j.LoggerFactory.getLogger(ScriptSessions::class.java)
    private val lock = Any()
    private val sessions = LinkedHashMap<String, Session>()
    private val executions = LinkedHashMap<Pair<Owner, String>, Execution>()
    private val controls = LinkedHashMap<String, CompletableFuture<Unit>>()
    private val slots = Array<Session?>(positive(processes.scripting, "workerCount")) { null }
    private val closed = AtomicBoolean()
    private val maximumCalls = positive(processes.scripting, "maxCalls")
    private data class Owner(val subject: String, val scopes: List<String>)
    private class Session(val id: String, val owner: Owner, var slot: Int) {
        var state = "STARTING"
        var worker: Worker? = null
        var execution: Execution? = null
        fun snapshot() = ScriptSession(id, state, state == "READY", worker?.id, execution?.executionId)
    }
    private class Execution(val id: String, val owner: Owner, submission: ScriptSubmission, val session: Session,
        val deadline: Long, invoke: (CapabilityInvocation, Long) -> CapabilityResponse, val fingerprint: ByteString) {
        val executionId = submission.executionId
        var submission: ScriptSubmission? = submission
        var invoke: ((CapabilityInvocation, Long) -> CapabilityResponse)? = invoke
        val done = CompletableFuture<Unit>()
        val calls = LinkedHashMap<String, ScriptCallReceipt>()
        // 调用回执决定业务调用是否仍在运行；任务还要完成响应发送，才能退出执行生命周期。
        val callTasks = ConcurrentHashMap<String, CompletableFuture<Unit>>()
        val callThreads = ConcurrentHashMap<String, Thread>()
        val outputs = ArrayList<ScriptOutput>()
        val diagnostics = ArrayList<ScriptDiagnostic>()
        val timings = LinkedHashMap<String, Long>()
        var thread: Thread? = null
        var cancelled = false
        var state = "RUNNING"
        var success = false
        var workerId: String? = null
        var workerOutcome: String? = null
        var disposition: String? = null
        val reasons = linkedSetOf<String>()
        @Synchronized fun report() = ScriptReport(session.id, executionId, state, disposition == "RETAINED", id,
            workerId, workerOutcome, disposition, reasons.toList(), success,
            outputs.map { it.copy(content = it.content?.clone()) }, diagnostics.toList(), calls.values.toList(), timings.toMap())
        @Synchronized fun cancel() { cancelled = true; thread?.interrupt(); callThreads.values.forEach(Thread::interrupt) }
    }
    private inner class Worker(val id: String, val directory: Path, val session: Session) : ManagedJvm.Events, AutoCloseable {
        lateinit var process: ManagedJvm
        @Volatile var current: Execution? = null
        private var stopped = false
        override fun capability(request: CapabilityRequest) {
            val execution = current
            if (execution == null || request.requestId != execution.id) { invalid("SCRIPT_EXPIRED_CAPABILITY_CALL"); return }
            if (!request.hasInvocation() || request.invocation.operationId.isBlank() || !request.invocation.hasInput() ||
                !request.invocation.input.typeUrl.startsWith("type.googleapis.com/agentic.olap.")) {
                process.send(Frame.newBuilder().setCapabilityResponse(capabilityFailure(request, "CAPABILITY_REQUEST_INVALID")).build())
                return
            }
            val marker = CompletableFuture<Unit>()
            synchronized(execution) {
                val runningCall = execution.callTasks.keys.firstNotNullOfOrNull { callId ->
                    execution.calls[callId]?.takeIf { it.state == "STARTED" }
                }
                if (execution.calls.containsKey(request.callId) || request.callId.isBlank() ||
                    execution.calls.size >= maximumCalls || runningCall != null) {
                    val code = when {
                        execution.calls.size >= maximumCalls -> "SCRIPT_CAPABILITY_BUDGET_EXCEEDED"
                        runningCall != null -> "SCRIPT_CAPABILITY_CONCURRENCY_REJECTED"
                        else -> "SCRIPT_CAPABILITY_REQUEST_INVALID"
                    }
                    log.warn("script capability rejected: sessionId={} executionId={} requestId={} callId={} operation={} code={} runningCallId={}",
                        execution.session.id, execution.executionId, request.requestId, request.callId,
                        request.invocation.operationId, code, runningCall?.callId)
                    process.send(Frame.newBuilder().setCapabilityResponse(capabilityFailure(request, code)).build())
                    return
                }
                execution.callTasks[request.callId] = marker
                execution.calls[request.callId] = ScriptCallReceipt(request.callId, request.invocation.operationId, "", "STARTED",
                    false, null, null, 0, emptyList())
            }
            Thread.ofVirtual().name("script-capability-${execution.id}-${request.callId}").start {
                val began = System.nanoTime()
                try {
                    execution.callThreads[request.callId] = Thread.currentThread()
                    val reply = try {
                        remaining(execution.deadline)
                        if (execution.cancelled) throw java.util.concurrent.CancellationException("script execution cancelled")
                        // Only this captured invocation knows the authenticated caller. The worker supplies no identity or scopes.
                        requireNotNull(execution.invoke)(request.invocation, execution.deadline).toBuilder()
                            .setRequestId(request.requestId).setCallId(request.callId).apply {
                                if (hasFailure()) setFailure(failure.toBuilder().setCallId(request.callId))
                            }.build()
                    } catch (failure: Throwable) {
                        log.warn("script capability did not complete: sessionId={} executionId={} callId={}", execution.session.id,
                            execution.executionId, request.callId, failure)
                        capabilityFailure(request, "CAPABILITY_DELIVERY_FAILED")
                    }
                    synchronized(execution) {
                        // 同步调用收到响应即可继续下一次调用，不能等发送线程的 finally 才解除业务并发占用。
                        // 先记录终态与实际 effects；发送任务仍由 callTasks 跟踪，供取消和会话退出完整等待。
                        execution.calls[request.callId] = ScriptCallReceipt(request.callId, request.invocation.operationId,
                            reply.operationRequestId, if (reply.hasFailure() && reply.failure.code == "CAPABILITY_DELIVERY_FAILED") "UNKNOWN" else "COMPLETED",
                            reply.success, reply.failure.takeIf { reply.hasFailure() }?.stage,
                            reply.failure.takeIf { reply.hasFailure() }?.code, System.nanoTime() - began, reply.effectsList.toList())
                    }
                    if (process.alive) process.send(Frame.newBuilder().setCapabilityResponse(reply).build())
                } catch (failure: Throwable) { log.warn("script capability reply failed: requestId={} callId={}", request.requestId, request.callId, failure) }
                finally { execution.callThreads.remove(request.callId); execution.callTasks.remove(request.callId); marker.complete(Unit) }
            }
        }
        override fun output(output: com.olap.prototype.protocol.ScriptOutput) {
            val execution = current
            if (execution == null || output.requestId != execution.id || output.stream !in listOf(
                    com.olap.prototype.protocol.ScriptOutput.Stream.STDOUT, com.olap.prototype.protocol.ScriptOutput.Stream.STDERR)) {
                invalid("SCRIPT_OUTPUT_SCOPE_VIOLATION"); return
            }
            synchronized(execution) {
                execution.outputs.add(ScriptOutput(execution.outputs.size + 1L, output.stream.name, output.content.toByteArray()))
            }
        }
        override fun emission(emission: ScriptEmission) {
            val execution = current
            if (execution == null || emission.requestId != execution.id || !emission.hasOutput() ||
                !emission.output.typeUrl.startsWith("type.googleapis.com/agentic.olap.")) {
                invalid("SCRIPT_EMISSION_CONTRACT_INVALID"); return
            }
            synchronized(execution) { execution.outputs.add(ScriptOutput(execution.outputs.size + 1L, "VALUE", value = emission.output)) }
        }
        private fun invalid(code: String) {
            log.warn("script protocol scope rejected: workerId={} code={}", id, code)
            current?.let { synchronized(it) { it.reasons.add(code) }; it.cancel() }
            throw IllegalStateException(code)
        }
        override fun unavailable(failure: Throwable) {
            val idle = synchronized(lock) {
                val owns = session.worker === this
                if (owns && session.state in setOf("READY", "BUSY")) session.state = "LOST"
                owns && session.execution == null
            }
            if (idle) Thread.ofVirtual().start {
                try {
                    close()
                    synchronized(lock) { if (session.worker === this && session.state == "LOST") release(session) }
                } catch (cleanup: Throwable) { log.error("lost script worker cleanup failed: workerId={}", id, cleanup) }
            }
        }
        @Synchronized override fun close() {
            if (stopped) return
            process.close()
            PrivateDirectories.remove(directory)
            stopped = true
            log.info("script worker retired: workerId={} processExitState=EXIT_CONFIRMED", id)
        }
    }
    init { processes.onShutdown(::close) }

    fun submit(principal: ServerAuthorization.Principal, submission: ScriptSubmission,
        invoke: (CapabilityInvocation, Long) -> CapabilityResponse): ScriptReport {
        validate(submission)
        val owner = owner(principal)
        val fingerprint = ByteString.copyFrom(java.security.MessageDigest.getInstance("SHA-256")
            .digest(processes.mapper.writeValueAsBytes(submission.copy(waitMillis = 0))))
        val execution: Execution
        synchronized(lock) {
            checkOpen()
            val key = owner to submission.executionId
            val existing = executions[key]
            if (existing != null) {
                if (existing.fingerprint != fingerprint)
                    throw ScriptException("SCRIPT_EXECUTION_CONFLICT", "executionId already identifies a different request", existing.session.id, submission.executionId)
                execution = existing
            } else {
            val session = if (submission.sessionId != null) session(owner, submission.sessionId).also {
                if (it.state != "READY") throw ScriptException("SCRIPT_SESSION_${it.state}", "session cannot accept an execution in its current state", it.id, submission.executionId, it.state)
            } else {
                val slot = slots.indexOfFirst { it == null }
                if (slot < 0) throw ScriptException("SCRIPT_SESSION_CAPACITY_EXHAUSTED", "all script slots belong to sessions; close a session before creating another")
                Session("session-${UUID.randomUUID()}", owner, slot).also { slots[slot] = it; sessions[it.id] = it }
            }
            execution = Execution("script-${processes.ids.incrementAndGet()}", owner, submission.copy(parameters = submission.parameters.deepCopy()), session,
                ManagedProcesses.deadlineAfter(processes.requestMillis), invoke, fingerprint)
            session.execution = execution
            session.state = if (session.worker == null) "STARTING" else "BUSY"
            executions[key] = execution
            execution.thread = Thread.ofVirtual().name(execution.id).unstarted { run(execution, principal) }
            execution.thread!!.start()
            }
        }
        return waitExecution(execution, submission.waitMillis)
    }
    fun wait(principal: ServerAuthorization.Principal, executionId: String, waitMillis: Long): ScriptReport = waitExecution(execution(owner(principal), executionId), waitMillis)
    fun cancel(principal: ServerAuthorization.Principal, executionId: String): ScriptReport {
        val execution = execution(owner(principal), executionId)
        execution.cancel()
        execution.done.join()
        return execution.report()
    }
    fun readSession(principal: ServerAuthorization.Principal, sessionId: String): ScriptSession = synchronized(lock) { session(owner(principal), sessionId).snapshot() }
    fun resetSession(principal: ServerAuthorization.Principal, sessionId: String): ScriptSession = control(principal, sessionId, true)
    fun closeSession(principal: ServerAuthorization.Principal, sessionId: String): ScriptSession = control(principal, sessionId, false)

    private fun run(execution: Execution, principal: ServerAuthorization.Principal) {
        val began = System.nanoTime()
        val session = execution.session
        val submission = requireNotNull(execution.submission)
        var directory: Path? = null
        var worker: Worker? = null
        try {
            synchronized(execution) { if (execution.cancelled) throw java.util.concurrent.CancellationException("script cancelled before start") }
            worker = synchronized(lock) { session.worker }
            if (worker == null) {
                val startup = System.nanoTime()
                worker = startWorker(session)
                synchronized(lock) { session.worker = worker; if (session.state == "STARTING") session.state = "BUSY" }
                synchronized(execution) {
                    execution.workerOutcome = "STARTED"
                    execution.timings["SCRIPT_WORKER_STARTUP"] = System.nanoTime() - startup
                }
            } else synchronized(execution) { execution.workerOutcome = "SESSION" }
            val active = requireNotNull(worker)
            synchronized(execution) { execution.workerId = active.id }
            active.current = execution
            active.process.beginLogs(processes.scripting.required("nativeLogBytes").longValue())
            directory = Files.createTempDirectory(active.directory, "execution-")
            log.info("script started: sessionId={} executionId={} requestId={} workerId={}", session.id, execution.executionId, execution.id, active.id)
            val response = active.process.script(ScriptRequest.newBuilder().setRequestId(execution.id).setSessionId(session.id)
                .setRemainingMillis(remaining(execution.deadline)).setCallerSubject(principal.subject).setExecutionDirectory(directory.toString())
                .setSource(ScriptSource.newBuilder().setSource(submission.source).setSourceName(submission.sourceName)
                    .setDslApiVersion(submission.dslApiVersion))
                .setParametersJson(ByteString.copyFrom(processes.mapper.writeValueAsBytes(submission.parameters))).build())
            require(response.state in setOf("COMPLETED", "COMPILE_FAILED", "RUNTIME_FAILED", "CANCELLED")) { "invalid script completion state" }
            response.resourceIssuesList.forEach { issue -> log.warn("script resource observation: sessionId={} executionId={} workerId={} resourceIssue={}",
                session.id, execution.executionId, active.id, issue) }
            synchronized(execution) {
                execution.state = response.state
                execution.success = response.state == "COMPLETED" && response.success
                execution.diagnostics.addAll(response.diagnosticsList)
                execution.timings.putAll(response.timingNanosMap)
                if (!response.sessionUsable) {
                    // 资源观察不是退役原因；可继续性由语言、通道和实际上下文恢复结果决定。
                    execution.reasons.add("SCRIPT_SESSION_UNUSABLE")
                }
            }
        } catch (failure: Throwable) {
            Thread.interrupted()
            synchronized(execution) {
                execution.state = if (execution.cancelled || failure is InterruptedException || failure is java.util.concurrent.TimeoutException ||
                    failure is java.util.concurrent.CancellationException) "CANCELLED" else "LOST"
                execution.success = false
                execution.reasons.add("SCRIPT_PROCESS_UNAVAILABLE")
                execution.diagnostics.add(ScriptDiagnostic.newBuilder().setStage("RUNTIME").setSeverity("ERROR")
                    .setCode(if (execution.state == "CANCELLED") "SCRIPT_CANCELLED" else "SCRIPT_RUNTIME_LOST")
                    .setMessage("script did not complete; recorded outputs, calls and effects remain available").build())
            }
            log.warn("script execution did not complete: sessionId={} executionId={} requestId={}", session.id, execution.executionId, execution.id, failure)
        } finally {
            // 先收齐旧调用任务，再解除可信闭包；受管管道的单一 writer 保证已提交回复先于下个请求。
            // 发送等待即使被取消，实际写帧仍由该 writer 完成；写失败由管道 owner 结束进程。
            // 即使语言 worker 退出，已经完成的业务调用也保留实际 effects。
            while (execution.callTasks.isNotEmpty()) execution.callTasks.values.toList().forEach { it.join() }
            worker?.current = null
            if (directory != null) try { PrivateDirectories.remove(directory) } catch (failure: Throwable) {
                synchronized(execution) { execution.reasons.add("SCRIPT_EXECUTION_DIRECTORY_CLEANUP_FAILED") }
                log.warn("script execution directory cleanup failed: requestId={}", execution.id, failure)
            }
            val retire = synchronized(lock) { synchronized(execution) {
                if (closed.get() || session.state in setOf("CLOSING", "RESETTING")) execution.reasons.add("SCRIPT_SESSION_CONTROL")
                if (worker?.process?.alive != true) execution.reasons.add("SCRIPT_PROCESS_UNAVAILABLE")
                execution.reasons.isNotEmpty()
            } }
            var cleaned = true
            if (retire) try { worker?.close() } catch (failure: Throwable) {
                cleaned = false
                log.error("script worker cleanup failed: sessionId={} workerId={}", session.id, worker?.id, failure)
                synchronized(execution) { execution.reasons.add("SCRIPT_DIRECTORY_CLEANUP_FAILED") }
            } else worker?.process?.idleLogs()
            synchronized(lock) { synchronized(execution) {
                execution.disposition = if (retire) "RETIRED" else "RETAINED"
                execution.timings["SCRIPT_TOTAL"] = System.nanoTime() - began
                execution.thread = null
                execution.invoke = null
                execution.submission = null
                session.execution = null
                if (session.state !in setOf("CLOSING", "RESETTING")) session.state = if (retire) "LOST" else "READY"
                if (retire && cleaned && session.state !in setOf("CLOSING", "RESETTING")) release(session)
                execution.done.complete(Unit)
            } }
            log.info("script completed: sessionId={} executionId={} state={} disposition={}", session.id, execution.executionId, execution.state, execution.disposition)
        }
    }

    private fun startWorker(session: Session): Worker {
        val root = processes.directory.resolve("scripting").also { Files.createDirectories(it, PRIVATE) }
        val directory = Files.createTempDirectory(root, "session-", PRIVATE).toRealPath()
        val worker = Worker("script-worker-${processes.ids.incrementAndGet()}", directory, session)
        try {
            val installed = processes.installed
            val configuration = ScriptConfiguration.newBuilder().setDslApiVersion(installed.dslVersion)
                .addAllCompilationClasspath(installed.dsl.map(Path::toString))
                .setMaximumSourceBytes(positive(processes.limits, "requestBytes").toLong())
                .setMaximumRequestBytes(positive(processes.limits, "requestBytes").toLong())
                .setMaximumFrameBytes(processes.maximumFrame.toLong())
                .setMaximumInputDepth(positive(processes.limits, "inputDepth"))
                .setMaximumInputNodes(positive(processes.limits, "inputNodes").toLong())
            worker.process = ManagedJvm.launch(installed.java, installed.script, installed.scriptMain, "script",
                positive(processes.scripting, "jvmHeapMiB"), directory, processes.transferParent, emptyMap(),
                Bootstrap.newBuilder().setProtocol(ManagedProcesses.PROTOCOL).setScripting(configuration).build(), processes.maximumFrame,
                processes.startupMillis, ScriptSandbox(directory, installed.java, installed.script + installed.dsl), worker,
                processes.scripting.required("nativeLogBytes").longValue())
            worker.process.idleLogs()
            return worker
        } catch (failure: Throwable) {
            try { PrivateDirectories.remove(directory) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
    private fun control(principal: ServerAuthorization.Principal, id: String, reset: Boolean): ScriptSession {
        val session: Session
        val current: Execution?
        val control = CompletableFuture<Unit>()
        synchronized(lock) {
            checkOpen(); session = session(owner(principal), id)
            if (session.state in setOf("RESETTING", "CLOSING")) throw ScriptException("SCRIPT_SESSION_CONTROL_IN_PROGRESS", "session control is already active", id)
            if (session.state == "CLOSED") {
                if (reset) throw ScriptException("SCRIPT_SESSION_CLOSED", "closed session cannot be reset", id)
                return session.snapshot()
            }
            if (reset && session.slot < 0) {
                val slot = slots.indexOfFirst { it == null }
                if (slot < 0) throw ScriptException("SCRIPT_SESSION_CAPACITY_EXHAUSTED", "no session slot is available", id)
                slots[slot] = session; session.slot = slot
            }
            session.state = if (reset) "RESETTING" else "CLOSING"
            current = session.execution
            controls[id] = control
        }
        try {
            current?.cancel(); current?.done?.join()
            session.worker?.close()
            synchronized(lock) { session.worker = null }
            if (!reset || closed.get()) return synchronized(lock) {
                release(session); session.state = "CLOSED"; session.snapshot()
            }
            val worker = startWorker(session)
            if (closed.get()) { worker.close(); synchronized(lock) { release(session); session.state = "CLOSED" }; return session.snapshot() }
            return synchronized(lock) { session.worker = worker; session.state = "READY"; session.snapshot() }
        } catch (failure: Throwable) {
            synchronized(lock) { session.state = "LOST"; if (session.worker == null) release(session) }
            throw failure
        } finally {
            synchronized(lock) { controls.remove(id); control.complete(Unit) }
        }
    }
    private fun release(session: Session) {
        if (session.slot >= 0 && slots[session.slot] === session) slots[session.slot] = null
        session.slot = -1; session.worker = null
    }
    private fun execution(owner: Owner, id: String) = synchronized(lock) { executions[owner to id] }
        ?: throw ScriptException("SCRIPT_EXECUTION_UNAVAILABLE", "execution is unavailable to this identity", executionId = id)
    private fun session(owner: Owner, id: String) = sessions[id]?.takeIf { it.owner == owner }
        ?: throw ScriptException("SCRIPT_SESSION_UNAVAILABLE", "session is unavailable to this identity", id)
    private fun owner(principal: ServerAuthorization.Principal) = Owner(principal.subject, principal.scopes.distinct().sorted())
    private fun checkOpen() { if (closed.get() || processes.closed.get()) throw ScriptException("SCRIPT_RUNTIME_CLOSED", "script runtime is closed") }
    private fun validate(submission: ScriptSubmission) {
        require(submission.waitMillis >= 0) { "waitMillis must be nonnegative" }
        if (submission.executionId.isBlank() || submission.source.isBlank() || submission.sourceName.isBlank() ||
            submission.dslApiVersion != processes.installed.dslVersion || !submission.parameters.isObject ||
            submission.source.toByteArray(Charsets.UTF_8).size > positive(processes.limits, "requestBytes") ||
            processes.mapper.writeValueAsBytes(submission.parameters).size > positive(processes.limits, "requestBytes"))
            throw ScriptException("SCRIPT_INPUT_INVALID", "source, exact DSL API, executionId and explicit parameters within the input budget are required", submission.sessionId, submission.executionId)
    }
    private fun waitExecution(execution: Execution, millis: Long): ScriptReport {
        require(millis >= 0) { "waitMillis must be nonnegative" }
        if (millis > 0) try { execution.done.get(millis, TimeUnit.MILLISECONDS) } catch (_: java.util.concurrent.TimeoutException) { }
        return execution.report()
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val running = synchronized(lock) { sessions.values.mapNotNull { it.execution } }
        running.forEach { it.cancel() }
        running.forEach { it.done.join() }
        synchronized(lock) { controls.values.toList() }.forEach { it.join() }
        val owned = synchronized(lock) { sessions.values.toList() }
        val failures = ArrayList<Throwable>()
        owned.forEach { session ->
            try {
                session.worker?.close()
                synchronized(lock) { session.state = "CLOSED"; release(session) }
            } catch (failure: Throwable) {
                synchronized(lock) { session.state = "LOST" }
                failures.add(failure)
            }
        }
        if (failures.isNotEmpty()) throw IllegalStateException("script process shutdown failed", failures.first()).also { aggregate ->
            failures.drop(1).forEach(aggregate::addSuppressed)
        }
    }
    companion object {
        private val PRIVATE = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        private fun capabilityFailure(request: CapabilityRequest, code: String) = CapabilityResponse.newBuilder()
            .setRequestId(request.requestId).setCallId(request.callId).setFailure(CapabilityFailure.newBuilder()
                .setStage("APPLICATION").setCode(code).setMessage("capability call did not complete within the active script contract")
                .setCallId(request.callId).setOperationId(request.invocation.operationId)).build()
    }
}
