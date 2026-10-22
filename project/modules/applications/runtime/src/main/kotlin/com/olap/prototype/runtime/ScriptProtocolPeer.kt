package com.olap.prototype.runtime

import com.google.protobuf.ByteString
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.dsl.transport.CapabilityFailure
import com.olap.prototype.dsl.transport.CapabilityFailureException
import com.olap.prototype.dsl.transport.CapabilityRegistry
import com.olap.prototype.dsl.transport.CapabilityTransport
import com.olap.prototype.protocol.*
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
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
    private val maximumResponseBytes: Int,
    private val maximumDepth: Int,
) : AutoCloseable {
    private sealed interface Inbound {
        data class Call(val request: ScriptRequest, val running: Running) : Inbound
        data class End(val failure: Throwable?) : Inbound
    }
    private class Running(val requestId: String, remainingMillis: Long) {
        val deadline = System.nanoTime() + Math.multiplyExact(remainingMillis, 1_000_000L)
        @Volatile var thread: Thread? = null
        @Volatile var cancelled = false
        fun cancel() { cancelled = true; thread?.interrupt() }
        fun remainingMillis(): Long = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).also {
            if (it <= 0 || cancelled) throw java.util.concurrent.CancellationException("script request deadline ended or request was cancelled")
        }
    }
    private class Pending(val requestId: String, val callId: String, val operationId: String) {
        val response = CompletableFuture<CapabilityResponse>()
    }
    private data class AbandonedCall(val requestId: String, val callId: String)
    private class OutputScope(val requestId: String) { var open = true }
    private val inbound = LinkedBlockingQueue<Inbound>(1)
    private val active = AtomicReference<Running?>()
    private val pending = AtomicReference<Pending?>()
    private val abandoned = AtomicReference<AbandonedCall?>()
    private val closed = AtomicBoolean()
    private val retiring = AtomicBoolean()
    private val writeLock = Any()
    private val outputScope = InheritableThreadLocal<OutputScope?>()
    private var reader: Thread? = null

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
        override fun close() { workerFault(outputScope.get()?.requestId.orEmpty(), "SCRIPT_STANDARD_STREAM_CLOSED") }
    }

    private fun writeOutput(stream: ScriptOutput.Stream, value: ByteArray, offset: Int, length: Int) {
        java.util.Objects.checkFromIndexSize(offset, length, value.size)
        if (length == 0) return
        val scope = outputScope.get()
        synchronized(writeLock) {
            if (scope == null || !scope.open || active.get()?.requestId != scope.requestId) {
                workerFault(scope?.requestId.orEmpty(), "SCRIPT_OUTPUT_OUTSIDE_ACTIVE_REQUEST")
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
        require(frame.serializedSize <= maximumFrameBytes) { "script protocol response exceeds frame budget" }
        synchronized(writeLock) { frame.writeDelimitedTo(output); output.flush() }
    }

    fun serve(handler: (ScriptRequest, CapabilityTransport, () -> Unit) -> ScriptResponse) {
        check(reader == null) { "script protocol can only serve once" }
        reader = Thread.ofVirtual().name("script-protocol-reader").start { readLoop() }
        while (!closed.get()) {
            when (val next = inbound.take()) {
                is Inbound.End -> { next.failure?.let { throw it }; return }
                is Inbound.Call -> execute(next, handler)
            }
        }
    }

    private fun execute(next: Inbound.Call, handler: (ScriptRequest, CapabilityTransport, () -> Unit) -> ScriptResponse) {
        val running = next.running
        running.thread = Thread.currentThread()
        val scope = OutputScope(running.requestId)
        outputScope.set(scope)
        try {
            val response = try {
                handler(next.request, transport(running)) { running.remainingMillis(); Unit }
            } finally {
                synchronized(writeLock) { scope.open = false }
                outputScope.remove()
            }
            Thread.interrupted()
            running.thread = null
            active.compareAndSet(running, null)
            if (response.recycleWorker) retiring.set(true)
            send(Frame.newBuilder().setScriptResponse(response.toBuilder().setRecycleWorker(retiring.get())).build())
        } finally {
            synchronized(writeLock) { scope.open = false }
            outputScope.remove()
            running.thread = null
            active.compareAndSet(running, null)
            Thread.interrupted()
        }
    }

    private fun transport(running: Running): CapabilityTransport = CapabilityTransport { operation, bytes ->
        if (active.get() !== running || Thread.currentThread() !== running.thread) {
            workerFault(running.requestId, "SCRIPT_CAPABILITY_OUTSIDE_ACTIVE_REQUEST")
            throw IllegalStateException("capability calls must execute synchronously inside the active script request")
        }
        running.remainingMillis()
        CapabilityRegistry.require(operation)
        require(bytes.size <= maximumRequestBytes) { "capability input exceeds request byte budget" }
        val call = Pending(running.requestId, "call-${UUID.randomUUID()}", operation)
        check(pending.compareAndSet(null, call)) { "only one synchronous capability call may be pending" }
        try {
            val model = ProtoBytes.decode(bytes, com.google.protobuf.Any.getDefaultInstance(), maximumRequestBytes, maximumDepth)
            send(Frame.newBuilder().setCapabilityRequest(CapabilityRequest.newBuilder()
                .setRequestId(running.requestId).setCallId(call.callId)
                .setInvocation(CapabilityInvocation.newBuilder().setOperationId(operation).setInput(model))).build())
            val response = call.response.get(running.remainingMillis(), TimeUnit.MILLISECONDS)
            if (!response.success) {
                require(response.hasFailure()) { "failed capability response has no failure contract" }
                val failure = response.failure
                require(failure.operationId == operation && failure.callId == call.callId) {
                    "capability failure identity differs from its invocation"
                }
                throw CapabilityFailureException(CapabilityFailure(operation, call.callId, failure.code,
                    failure.stage, failure.message, failure.fieldPathList.toList(), failure.diagnosticId.takeIf(String::isNotBlank)))
            }
            require(response.hasResult() && !response.hasFailure()) { "successful capability response requires exactly one result" }
            ProtoBytes.encode(response.result, maximumResponseBytes)
        } finally {
            if (!call.response.isDone) {
                abandoned.set(AbandonedCall(call.requestId, call.callId))
                retiring.set(true)
            }
            pending.compareAndSet(call, null)
        }
    }

    private fun readLoop() {
        var failure: Throwable? = null
        try {
            while (!closed.get()) {
                val frame = readFrame(input, maximumFrameBytes) ?: break
                when (frame.bodyCase) {
                    Frame.BodyCase.SCRIPT_REQUEST -> {
                        val request = frame.scriptRequest
                        require(request.requestId.isNotBlank() && request.remainingMillis > 0 &&
                            request.remainingMillis < Long.MAX_VALUE / 4 / 1_000_000L) { "valid script request identity and deadline are required" }
                        require(request.hasSource() && !request.parametersJson.isEmpty) { "complete script source and parameters are required" }
                        if (retiring.get()) { workerFault(request.requestId, "SCRIPT_WORKER_NOT_REUSABLE"); continue }
                        val running = Running(request.requestId, request.remainingMillis)
                        check(active.compareAndSet(null, running)) { "script process already has an active request" }
                        check(inbound.offer(Inbound.Call(request, running))) { "script request queue is full" }
                    }
                    Frame.BodyCase.CAPABILITY_RESPONSE -> {
                        val response = frame.capabilityResponse
                        val call = pending.get()
                        if (call == null) {
                            val old = abandoned.get()
                            if (old != null && response.requestId == old.requestId && response.callId == old.callId) {
                                abandoned.compareAndSet(old, null)
                                continue
                            }
                            workerFault(response.requestId, "SCRIPT_UNMATCHED_CAPABILITY_RESPONSE")
                            continue
                        }
                        require(response.requestId == call.requestId && response.callId == call.callId) { "capability response identity differs" }
                        check(call.response.complete(response)) { "capability response was repeated" }
                    }
                    Frame.BodyCase.CANCEL -> active.get()?.takeIf { it.requestId == frame.cancel.requestId }?.cancel()
                    Frame.BodyCase.SHUTDOWN -> break
                    else -> error("script process received a frame outside its protocol role")
                }
            }
        } catch (error: Throwable) { failure = error }
        finally {
            active.get()?.cancel()
            pending.get()?.response?.completeExceptionally(failure ?: IllegalStateException("script supervisor channel closed"))
            inbound.clear()
            inbound.offer(Inbound.End(failure))
        }
    }

    private fun workerFault(requestId: String, code: String) {
        retiring.set(true)
        send(Frame.newBuilder().setScriptWorkerFault(ScriptWorkerFault.newBuilder().setRequestId(requestId).setCode(code)).build())
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active.get()?.cancel()
        pending.getAndSet(null)?.response?.completeExceptionally(IllegalStateException("script protocol closed"))
        reader?.interrupt()
        input.close()
    }

    companion object {
        fun readFrame(input: InputStream, maximum: Int): Frame? {
            val first = input.read()
            if (first < 0) return null
            var length = (first and 127).toLong()
            var next = first
            var shift = 7
            while (next and 128 != 0) {
                require(shift < 35) { "invalid protobuf frame length" }
                next = input.read()
                require(next >= 0) { "truncated protobuf frame length" }
                length = length or ((next and 127).toLong() shl shift)
                shift += 7
            }
            require(length in 1..maximum.toLong()) { "script protocol frame exceeds byte budget" }
            val bytes = input.readNBytes(length.toInt())
            require(bytes.size == length.toInt()) { "truncated protobuf frame" }
            return Frame.parseFrom(bytes)
        }
    }
}

