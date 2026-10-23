package com.olap.prototype.runtime

import com.google.protobuf.ByteString
import com.olap.prototype.protocol.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 一条双工协议通道；只有监督者拥有进程，JVM只执行请求和等待显式工作回执。 */
class ProtocolPeer(
    private val input: InputStream,
    private val output: OutputStream,
    private val maximumBytes: Int,
    transferRoot: Path,
) : AutoCloseable {
    private val files = ProtocolFiles(transferRoot)
    private val callbacks = ConcurrentHashMap<String, CompletableFuture<WorkResponse>>()
    private val active = ConcurrentHashMap<String, Running>()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val closed = AtomicBoolean()
    private val writeLock = Any()

    private class Running {
        private var thread: Thread? = null
        private var cancelled = false

        @Synchronized fun begin(): Boolean {
            check(thread == null) { "request already owns an execution thread" }
            if (cancelled) return false
            thread = Thread.currentThread()
            return true
        }

        @Synchronized fun cancel() { cancelled = true; thread?.interrupt() }

        @Synchronized fun finish() {
            check(thread == null || thread === Thread.currentThread()) { "request must release its own execution thread" }
            thread = null
            Thread.interrupted()
        }
    }

    fun serve(handler: (Request) -> Response) {
        while (!closed.get()) {
            val frame = files.read(input, maximumBytes) ?: break
            when (frame.bodyCase) {
                Frame.BodyCase.REQUEST -> {
                    val request = frame.request
                    require(request.requestId.isNotBlank() && request.remainingMillis > 0) {
                        "request identity and remaining deadline are required"
                    }
                    val running = Running()
                    check(active.putIfAbsent(request.requestId, running) == null) { "duplicate request identity" }
                    executor.submit {
                        try {
                            val response = try {
                                if (running.begin()) handler(request)
                                else Response.newBuilder().setRequestId(request.requestId).setSuccess(false)
                                    .setErrorJson(ByteString.copyFromUtf8("""{"stage":"APPLICATION","code":"CANCELLED","message":"request cancelled before application handling began"}"""))
                                    .build()
                            } finally {
                                // 取消与线程摘除互斥；已取得的线程引用不能在回执交付阶段再次中断它。
                                running.finish()
                            }
                            // 不在领域提交后再次核取消并伪造未执行结果。
                            send(Frame.newBuilder().setResponse(response).build())
                        } catch (failure: Throwable) {
                            org.slf4j.LoggerFactory.getLogger(ProtocolPeer::class.java)
                                .error("runtime protocol request failed: requestId={}", request.requestId, failure)
                            input.close()
                            throw failure
                        } finally {
                            active.remove(request.requestId, running)
                        }
                    }
                }
                Frame.BodyCase.WORK_RESPONSE -> {
                    val response = frame.workResponse
                    val waiting = callbacks.remove(response.workId)
                        ?: error("supervisor response has no matching work request")
                    waiting.complete(response)
                }
                Frame.BodyCase.CANCEL -> active[frame.cancel.requestId]?.cancel()
                Frame.BodyCase.SHUTDOWN -> break
                else -> error("unexpected supervisor protocol frame")
            }
        }
    }

    fun work(requestId: String, remainingMillis: Long, action: WorkRequest.Builder): WorkResponse {
        check(!closed.get()) { "protocol is closed" }
        val id = "work-${UUID.randomUUID()}"
        val future = CompletableFuture<WorkResponse>()
        callbacks[id] = future
        try {
            send(Frame.newBuilder().setWorkRequest(action.setRequestId(requestId)
                .setWorkId(id).setRemainingMillis(remainingMillis)).build())
            return future.get(remainingMillis, TimeUnit.MILLISECONDS)
        } catch (cancelled: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.util.concurrent.CancellationException("request interrupted while waiting for supervised work")
        } catch (expired: java.util.concurrent.TimeoutException) {
            throw RuntimeResourceLimitException("WORKER_DEADLINE_EXCEEDED")
        } finally {
            // 超时或取消后的迟到回执仍由读循环消费，不能污染别的请求或关闭通道。
            if (future.isDone) callbacks.remove(id, future)
        }
    }

    fun send(frame: Frame) {
        synchronized(writeLock) {
            val outgoing = files.outbound(frame, maximumBytes)
            require(outgoing.serializedSize <= maximumBytes) { "protocol transfer reference exceeds pipe frame budget" }
            outgoing.writeDelimitedTo(output)
            output.flush()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active.values.forEach(Running::cancel)
        callbacks.values.forEach { it.completeExceptionally(IllegalStateException("supervisor channel closed")) }
        executor.shutdownNow()
        executor.close()
    }

    companion object {
        fun readFrame(input: InputStream, maximum: Int): Frame? {
            return ProtocolFiles.readPipeFrame(input, maximum)
        }
    }
}
