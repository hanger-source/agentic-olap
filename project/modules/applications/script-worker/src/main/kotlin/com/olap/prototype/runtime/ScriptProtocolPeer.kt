package com.olap.prototype.runtime

import com.google.protobuf.ByteString
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.dsl.transport.CapabilityFailure
import com.olap.prototype.dsl.transport.CapabilityFailureException
import com.olap.prototype.dsl.transport.CapabilityRegistry
import com.olap.prototype.dsl.transport.CapabilityTransport
import com.olap.prototype.protocol.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 脚本专用通道；不提供 worker 租用、元数据库或来源配置访问。 */
internal class ScriptProtocolPeer(
    private val input: InputStream,
    private val output: OutputStream,
    private val maximumFrameBytes: Int,
    private val maximumRequestBytes: Int,
    private val maximumDepth: Int,
    transferRoot: Path,
    private val privateDiagnostics: PrintStream,
) : AutoCloseable {
    private val files = ProtocolFiles(transferRoot)
    private sealed interface Inbound {
        data class Call(val request: ScriptRequest, val running: Running) : Inbound
        data class End(val failure: Throwable?) : Inbound
    }
    private class Running(val requestId: String, remainingMillis: Long) {
        val deadline = System.nanoTime() + Math.multiplyExact(remainingMillis, 1_000_000L)
        private var thread: Thread? = null
        @Volatile var cancelled = false
        @Volatile var evaluating = false

        @Synchronized fun bindThread() {
            check(thread == null) { "script request already owns an evaluation thread" }
            thread = Thread.currentThread()
            if (cancelled) thread?.interrupt()
        }

        @Synchronized fun ownsCurrentThread(): Boolean = thread === Thread.currentThread()

        @Synchronized fun cancel() { cancelled = true; thread?.interrupt() }

        @Synchronized fun releaseThread() {
            check(thread == null || thread === Thread.currentThread()) { "script request must release its own evaluation thread" }
            thread = null
            evaluating = false
            // 与cancel处于同一临界区，旧请求不能在这里清理后再中断长期会话线程。
            Thread.interrupted()
        }

        fun remainingMillis(): Long = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).also {
            if (it <= 0 || cancelled) throw java.util.concurrent.CancellationException("script request deadline ended or request was cancelled")
        }
    }
    private data class CallIdentity(val requestId: String, val callId: String)
    private class Pending(val identity: CallIdentity, val operationId: String) {
        val response = CompletableFuture<CapabilityResponse>()
    }
    private class OutputScope(val requestId: String) { var open = true }
    private val inbound = LinkedBlockingQueue<Inbound>(1)
    private val active = AtomicReference<Running?>()
    private val callsLock = Any()
    private var pending: Pending? = null
    private val abandoned = HashSet<CallIdentity>()
    private val closed = AtomicBoolean()
    private val retiring = AtomicBoolean()
    private val writeLock = Any()
    private val outputScope = InheritableThreadLocal<OutputScope?>()
    private var reader: Thread? = null
    private var sessionId: String? = null

    /** 稳定端口每次解析当前执行；历史函数不会捕获旧请求或旧期限。 */
    val transport: CapabilityTransport = CapabilityTransport { operation, bytes -> call(operation, bytes) }

    fun beginEvaluation() {
        val running = checkNotNull(active.get()) { "session has no active execution" }
        check(running.ownsCurrentThread() && !running.evaluating) { "evaluation must begin on its active execution thread" }
        running.remainingMillis()
        running.evaluating = true
    }

    fun finishEvaluation() { active.get()?.evaluating = false }

    fun requireEvaluation(): String = evaluation().requestId

    fun emit(value: com.google.protobuf.Any) {
        val running = evaluation()
        send(Frame.newBuilder().setScriptEmission(ScriptEmission.newBuilder().setRequestId(running.requestId).setOutput(value)).build())
    }

    private fun evaluation(): Running {
        val running = active.get()
        if (running == null || !running.evaluating || !running.ownsCurrentThread()) {
            rejected(outputScope.get()?.requestId.orEmpty(), "SCRIPT_CAPABILITY_OUTSIDE_ACTIVE_EXECUTION")
            throw IllegalStateException("formal calls and explicit output require the active session evaluation thread")
        }
        running.remainingMillis()
        return running
    }

    /** 普通流按请求归属；本次产生的子线程保留原scope，不能借用后续请求的日志预算。 */
    fun redirectStandardOutput(): AutoCloseable {
        val previousOutput = System.out
        val previousError = System.err
        System.setOut(outputStream(ScriptOutput.Stream.STDOUT))
        System.setErr(outputStream(ScriptOutput.Stream.STDERR))
        return AutoCloseable {
            System.setOut(previousOutput)
            System.setErr(previousError)
        }
    }

    private fun outputStream(stream: ScriptOutput.Stream): PrintStream = object : PrintStream(object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(value: ByteArray, offset: Int, length: Int) = writeOutput(stream, value, offset, length)
    }, true, Charsets.UTF_8) {
        override fun close() { rejected(outputScope.get()?.requestId.orEmpty(), "SCRIPT_STANDARD_STREAM_CLOSE_REJECTED") }
    }

    private fun writeOutput(stream: ScriptOutput.Stream, value: ByteArray, offset: Int, length: Int) {
        java.util.Objects.checkFromIndexSize(offset, length, value.size)
        if (length == 0) return
        val scope = outputScope.get()
        synchronized(writeLock) {
            if (scope == null || !scope.open || active.get()?.requestId != scope.requestId) {
                rejected(scope?.requestId.orEmpty(), "SCRIPT_OUTPUT_OUTSIDE_ACTIVE_REQUEST")
                return
            }
            val output = ScriptOutput.newBuilder().setRequestId(scope.requestId).setStream(stream)
            val fixedBytes = Frame.newBuilder().setScriptOutput(output).build().serializedSize
            // 预留内外两层长度前缀的最大增量；每帧仍由send检查真实大小。
            val chunkBytes = minOf(64 * 1024, maximumFrameBytes - fixedBytes - 10)
            require(chunkBytes > 0) { "script output identity exceeds its frame budget" }
            var position = offset
            val end = offset + length
            while (position < end) {
                val count = minOf(chunkBytes, end - position)
                output.setContent(ByteString.copyFrom(value, position, count))
                send(Frame.newBuilder().setScriptOutput(output).build())
                position += count
            }
        }
    }

    fun send(frame: Frame) {
        check(!closed.get()) { "script protocol is closed" }
        synchronized(writeLock) {
            val outgoing = files.outbound(frame, maximumFrameBytes)
            require(outgoing.serializedSize <= maximumFrameBytes) { "protocol transfer reference exceeds pipe frame budget" }
            try {
                outgoing.writeDelimitedTo(output)
                output.flush()
            } catch (failure: IOException) {
                retiring.set(true)
                throw failure
            }
        }
    }

    fun serve(handler: (ScriptRequest, () -> Unit) -> ScriptResponse) {
        check(reader == null) { "script protocol can only serve once" }
        // 每个串行 worker 只有一个长期阻塞的协议读取器，由进程生命周期直接拥有。
        reader = Thread.ofPlatform().daemon(true).name("script-protocol-reader").start { readLoop() }
        while (!closed.get()) {
            when (val next = inbound.take()) {
                is Inbound.End -> { next.failure?.let { throw it }; return }
                is Inbound.Call -> execute(next, handler)
            }
        }
    }

    private fun execute(next: Inbound.Call, handler: (ScriptRequest, () -> Unit) -> ScriptResponse) {
        val running = next.running
        running.bindThread()
        val scope = OutputScope(running.requestId)
        outputScope.set(scope)
        try {
            val response = try {
                handler(next.request) { running.remainingMillis(); Unit }
            } finally {
                running.evaluating = false
                synchronized(writeLock) { scope.open = false }
                outputScope.remove()
            }
            running.releaseThread()
            active.compareAndSet(running, null)
            if (!response.sessionUsable) retiring.set(true)
            send(Frame.newBuilder().setScriptResponse(response.toBuilder().setSessionUsable(!retiring.get())).build())
        } finally {
            synchronized(writeLock) { scope.open = false }
            outputScope.remove()
            running.releaseThread()
            active.compareAndSet(running, null)
        }
    }

    private fun call(operation: String, bytes: ByteArray): ByteArray {
        val running = evaluation()
        CapabilityRegistry.require(operation)
        require(bytes.size <= maximumRequestBytes) { "capability input exceeds request byte budget" }
        val model = ProtoBytes.decode(bytes, com.google.protobuf.Any.getDefaultInstance(), maximumRequestBytes, maximumDepth)
        val call = Pending(CallIdentity(running.requestId, "call-${UUID.randomUUID()}"), operation)
        synchronized(callsLock) {
            check(pending == null) { "only one synchronous capability call may be pending" }
            pending = call
        }
        var submitted = false
        try {
            send(Frame.newBuilder().setCapabilityRequest(CapabilityRequest.newBuilder()
                .setRequestId(running.requestId).setCallId(call.identity.callId)
                .setInvocation(CapabilityInvocation.newBuilder().setOperationId(operation).setInput(model))).build())
            submitted = true
            val response = call.response.get(running.remainingMillis(), TimeUnit.MILLISECONDS)
            if (!response.success) {
                val failure = response.failure
                throw CapabilityFailureException(CapabilityFailure(operation, call.identity.callId, failure.code,
                    failure.stage, failure.message, failure.fieldPathList.toList(), failure.diagnosticId.takeIf(String::isNotBlank), failure.detailsJson.toByteArray()))
            }
            return ProtoBytes.encode(response.result)
        } finally {
            synchronized(callsLock) {
                if (pending === call) {
                    // 等待被中断不代表语言或通道损坏；精确登记已提交、尚未收到回复的调用。
                    // 与读线程使用同一锁交接，回复不会落入 pending/abandoned 之间的空隙。
                    if (submitted && !call.response.isDone) abandoned.add(call.identity)
                    pending = null
                }
            }
        }
    }

    private fun readLoop() {
        var failure: Throwable? = null
        try {
            while (!closed.get()) {
                val frame = files.read(input, maximumFrameBytes) ?: break
                when (frame.bodyCase) {
                    Frame.BodyCase.SCRIPT_REQUEST -> {
                        val request = frame.scriptRequest
                        require(request.requestId.isNotBlank() && request.sessionId.isNotBlank() && request.remainingMillis > 0 &&
                            request.remainingMillis < Long.MAX_VALUE / 4 / 1_000_000L) { "valid script request identity and deadline are required" }
                        require(request.hasSource() && !request.parametersJson.isEmpty) { "complete script source and parameters are required" }
                        require(sessionId == null || sessionId == request.sessionId) { "session process cannot change its exact session identity" }
                        sessionId = request.sessionId
                        if (retiring.get()) { workerFault(request.requestId, "SCRIPT_WORKER_NOT_REUSABLE"); continue }
                        synchronized(callsLock) {
                            // 宿主收齐旧能力任务，单一 writer 将已提交回复排在下个请求之前；写失败关闭管道。
                            // 因此读到下个请求时，旧回执必须已经交付或被精确消化。
                            check(pending == null && abandoned.isEmpty()) {
                                "supervisor accepted another script before completing its previous capability responses"
                            }
                        }
                        val running = Running(request.requestId, request.remainingMillis)
                        check(active.compareAndSet(null, running)) { "script process already has an active request" }
                        check(inbound.offer(Inbound.Call(request, running))) { "script request queue is full" }
                    }
                    Frame.BodyCase.CAPABILITY_RESPONSE -> {
                        receive(frame.capabilityResponse)
                    }
                    Frame.BodyCase.CANCEL -> active.get()?.takeIf { it.requestId == frame.cancel.requestId }?.cancel()
                    Frame.BodyCase.SHUTDOWN -> break
                    else -> error("script process received a frame outside its protocol role")
                }
            }
        } catch (error: Throwable) { failure = error }
        finally {
            retiring.set(true)
            active.get()?.cancel()
            synchronized(callsLock) {
                pending?.response?.completeExceptionally(failure ?: IllegalStateException("script supervisor channel closed"))
            }
            inbound.clear()
            inbound.offer(Inbound.End(failure))
        }
    }

    private fun receive(response: CapabilityResponse) {
        val identity = CallIdentity(response.requestId, response.callId)
        synchronized(callsLock) {
            if (abandoned.remove(identity)) {
                privateDiagnostics.println("script capability response completed after local wait: sessionId=$sessionId requestId=${identity.requestId} callId=${identity.callId}")
                return
            }
            val call = checkNotNull(pending) { "capability response has no matching invocation" }
            require(identity == call.identity) { "capability response identity differs" }
            if (response.success) {
                require(response.hasResult() && !response.hasFailure()) { "successful capability response requires exactly one result" }
            } else {
                require(response.hasFailure() && !response.hasResult()) { "failed capability response requires exactly one failure contract" }
                require(response.failure.operationId == call.operationId && response.failure.callId == identity.callId) {
                    "capability failure identity differs from its invocation"
                }
            }
            check(call.response.complete(response)) { "capability response was repeated" }
        }
    }

    private fun rejected(requestId: String, code: String) {
        privateDiagnostics.println("script action rejected: sessionId=$sessionId requestId=$requestId code=$code")
    }

    private fun workerFault(requestId: String, code: String) {
        retiring.set(true)
        send(Frame.newBuilder().setScriptWorkerFault(ScriptWorkerFault.newBuilder().setRequestId(requestId).setCode(code)).build())
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active.get()?.cancel()
        synchronized(callsLock) {
            pending?.response?.completeExceptionally(IllegalStateException("script protocol closed"))
            pending = null
            abandoned.clear()
        }
        reader?.interrupt()
        input.close()
        if (reader !== Thread.currentThread()) reader?.join()
        synchronized(writeLock) { files.close() }
    }

    companion object {
        fun readFrame(input: InputStream, maximum: Int): Frame? {
            return ProtocolFiles.readPipeFrame(input, maximum)
        }
    }
}

