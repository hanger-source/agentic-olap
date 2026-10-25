package com.olap.prototype.server.process

import com.olap.prototype.protocol.*
import com.olap.prototype.runtime.ProtocolFiles
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one protocol pipe and its OS process; dispatch is correlated before completing callers. */
internal class ManagedJvm(private val process: DarwinProcess, private val transfer: Path, private val maximum: Int,
    private val events: Events?) : AutoCloseable {
    interface Events {
        fun capability(request: CapabilityRequest)
        fun output(output: ScriptOutput)
        fun emission(emission: ScriptEmission)
        fun unavailable(failure: Throwable)
    }
    private val log = org.slf4j.LoggerFactory.getLogger(ManagedJvm::class.java)
    private val files = ProtocolFiles(transfer)
    private val input = BufferedInputStream(process.output)
    private val output = BufferedOutputStream(process.input)
    private val pending = ConcurrentHashMap<String, CompletableFuture<Response>>()
    private val sourceCompletions = ConcurrentHashMap<String, (com.olap.prototype.wire.query.SourceReadEvidence) -> Unit>()
    private val scripts = ConcurrentHashMap<String, CompletableFuture<ScriptResponse>>()
    private val stopping = AtomicBoolean()
    private val failed = CompletableFuture<Throwable>()
    private val readDone = CompletableFuture<Unit>()
    private val logDone = CompletableFuture<Unit>()
    private val closeDone = CompletableFuture<Unit>()
    @Volatile private var logReader: Thread? = null
    private val writeLock = Any()
    private val writer = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true)
        .name("jvm-writer-${process.pid}").factory())
    @Volatile private var nativeLogLimit = Long.MAX_VALUE
    private var nativeLogBytes = 0L
    private val reader = Thread.ofPlatform().daemon(true).name("jvm-protocol-${process.pid}").unstarted {
        try {
            while (true) {
                val frame = files.read(input, maximum) ?: throw EOFException("JVM protocol ended: pid=${process.pid}")
                when (frame.bodyCase) {
                    Frame.BodyCase.RESPONSE -> pending.remove(frame.response.requestId)?.complete(frame.response)
                        ?: error("JVM response has no matching request: ${frame.response.requestId}")
                    Frame.BodyCase.SCRIPT_RESPONSE -> scripts.remove(frame.scriptResponse.requestId)?.complete(frame.scriptResponse)
                        ?: error("script response has no matching request: ${frame.scriptResponse.requestId}")
                    Frame.BodyCase.CAPABILITY_REQUEST -> requireNotNull(events) { "worker cannot invoke application capabilities" }.capability(frame.capabilityRequest)
                    Frame.BodyCase.SCRIPT_OUTPUT -> requireNotNull(events) { "worker cannot emit script output" }.output(frame.scriptOutput)
                    Frame.BodyCase.SCRIPT_EMISSION -> requireNotNull(events) { "worker cannot emit script values" }.emission(frame.scriptEmission)
                    Frame.BodyCase.SCRIPT_WORKER_FAULT -> error("script worker fault: requestId=${frame.scriptWorkerFault.requestId} code=${frame.scriptWorkerFault.code}")
                    Frame.BodyCase.SOURCE_READ_COMPLETED -> {
                        val event = frame.sourceReadCompleted
                        require(event.hasEvidence()) { "source completion requires actual read evidence" }
                        requireNotNull(sourceCompletions.remove(event.requestId)) {
                            "source completion has no matching execution: ${event.requestId}"
                        }.invoke(event.evidence)
                    }
                    else -> error("unexpected JVM frame: ${frame.bodyCase}")
                }
            }
        } catch (failure: Throwable) {
            fail(failure)
            if (!stopping.get()) Thread.ofVirtual().name("jvm-retire-${process.pid}").start { close() }
        } finally { readDone.complete(Unit) }
    }
    val alive get() = !failed.isDone && !process.exit.isDone && !stopping.get()
    fun beginLogs(maximumBytes: Long) { require(maximumBytes > 0); synchronized(this) { nativeLogBytes = 0; nativeLogLimit = maximumBytes } }
    fun idleLogs() { synchronized(this) { nativeLogLimit = Long.MAX_VALUE } }
    fun start(bootstrap: Bootstrap, remainingMillis: Long) {
        val expiry = expiry(remainingMillis)
        val future = CompletableFuture<Response>()
        pending["bootstrap"] = future
        reader.start()
        logReader = Thread.ofPlatform().daemon(true).name("jvm-stderr-${process.pid}").unstarted {
            try {
                val buffer = ByteArray(8192)
                while (true) {
                    val count = process.error.read(buffer)
                    if (count < 0) break
                    synchronized(this) {
                        nativeLogBytes = Math.addExact(nativeLogBytes, count.toLong())
                        check(nativeLogBytes <= nativeLogLimit) { "SCRIPT_NATIVE_LOG_BUDGET_EXCEEDED" }
                    }
                    // This is private service diagnostics, never the framed protocol or public user output.
                    System.err.write(buffer, 0, count)
                }
            } catch (failure: Throwable) {
                if (!stopping.get()) { fail(failure); close() }
            } finally { logDone.complete(Unit) }
        }
        logReader!!.start()
        val began = System.nanoTime()
        try {
            log.info("JVM bootstrap started: pid={} mode={} pipeBytes={}", process.pid,
                if (bootstrap.hasScripting()) "script" else bootstrap.workerKind, maximum)
            send(Frame.newBuilder().setBootstrap(bootstrap.toBuilder().setTransferDirectory(transfer.toString())).build())
            val response = future.get(remainingMillis, TimeUnit.MILLISECONDS)
            check(response.success) { "JVM bootstrap failed: ${response.errorJson.toStringUtf8()}" }
            log.info("JVM bootstrap completed: pid={} elapsedNanos={}", process.pid, System.nanoTime() - began)
        } catch (failure: Throwable) {
            log.warn("JVM bootstrap did not complete: pid={} elapsedNanos={}", process.pid, System.nanoTime() - began, failure)
            closeAfterFailure(failure)
            throw failure
        }
        finally { expiry.interrupt() }
    }
    fun exchange(request: Request, sourceCompleted: ((com.olap.prototype.wire.query.SourceReadEvidence) -> Unit)? = null): Response {
        val expiry = expiry(request.remainingMillis)
        val response = CompletableFuture<Response>()
        check(pending.putIfAbsent(request.requestId, response) == null) { "duplicate worker request identity" }
        sourceCompleted?.let { check(sourceCompletions.putIfAbsent(request.requestId, it) == null) { "duplicate source completion listener" } }
        try {
            send(Frame.newBuilder().setRequest(request).build())
            return response.get(request.remainingMillis, TimeUnit.MILLISECONDS)
        } catch (failure: Throwable) { closeAfterFailure(failure); throw failure }
        finally { expiry.interrupt(); pending.remove(request.requestId, response); sourceCompletions.remove(request.requestId) }
    }
    fun script(request: ScriptRequest): ScriptResponse {
        // Also bounds a producer blocked in native pipe write. The caller first has a cooperative grace.
        val expiry = expiry(Math.addExact(request.remainingMillis, 1000))
        val response = CompletableFuture<ScriptResponse>()
        check(scripts.putIfAbsent(request.requestId, response) == null) { "duplicate script request identity" }
        try {
            send(Frame.newBuilder().setScriptRequest(request).build())
            return response.get(request.remainingMillis, TimeUnit.MILLISECONDS)
        } catch (cancelled: InterruptedException) {
            // Cooperative cancellation may preserve language state; arbitrary non-cooperation requires termination.
            try {
                send(Frame.newBuilder().setCancel(Cancel.newBuilder().setRequestId(request.requestId)).build())
                return response.get(1, TimeUnit.SECONDS)
            } catch (failure: Throwable) { cancelled.addSuppressed(failure); closeAfterFailure(cancelled); throw cancelled }
        } catch (expired: java.util.concurrent.TimeoutException) {
            try {
                send(Frame.newBuilder().setCancel(Cancel.newBuilder().setRequestId(request.requestId)).build())
                return response.get(1, TimeUnit.SECONDS)
            } catch (failure: Throwable) { expired.addSuppressed(failure); closeAfterFailure(expired); throw expired }
        } catch (failure: Throwable) { closeAfterFailure(failure); throw failure }
        finally { expiry.interrupt(); scripts.remove(request.requestId, response) }
    }
    fun send(frame: Frame) {
        check(!stopping.get()) { "JVM process is stopping" }
        val sent = writer.submit {
            synchronized(writeLock) {
                check(!stopping.get()) { "JVM process is stopping" }
                val outgoing = files.outbound(frame, maximum)
                require(!frame.hasBootstrap() || !outgoing.hasFile()) { "bootstrap must fit the initial inline handshake" }
                require(outgoing.serializedSize <= maximum) { "protocol file reference exceeds pipe budget" }
                outgoing.writeDelimitedTo(output)
                output.flush()
            }
        }
        try { sent.get() } catch (failure: ExecutionException) { throw requireNotNull(failure.cause) }
    }
    private fun fail(failure: Throwable) {
        if (failed.complete(failure)) {
            pending.values.forEach { it.completeExceptionally(failure) }
            scripts.values.forEach { it.completeExceptionally(failure) }
            events?.unavailable(failure)
            log.warn("JVM protocol stopped: pid={}", process.pid, failure)
        }
    }
    private fun expiry(millis: Long): Thread = Thread.ofVirtual().name("jvm-deadline-${process.pid}").start {
        try { Thread.sleep(millis); close() }
        catch (_: InterruptedException) { }
        catch (failure: Throwable) { log.error("JVM deadline retirement failed: pid={}", process.pid, failure) }
    }
    private fun closeAfterFailure(primary: Throwable) {
        try { close() } catch (cleanup: Throwable) { if (cleanup !== primary) primary.addSuppressed(cleanup) }
    }
    override fun close() {
        if (stopping.compareAndSet(false, true)) {
            try {
                // Termination unblocks native pipe writes; holding writeLock first could deadlock on a full pipe.
                process.terminate()
                if (Thread.currentThread() !== reader && reader.state != Thread.State.NEW) readDone.join()
                if (Thread.currentThread() !== logReader && logReader != null) logDone.join()
                process.closePipes()
                writer.shutdown()
                writer.close()
                synchronized(writeLock) {
                    files.close()
                    PrivateDirectories.remove(transfer)
                }
                fail(IllegalStateException("JVM process stopped by owner"))
                closeDone.complete(Unit)
            } catch (failure: Throwable) {
                closeDone.completeExceptionally(failure)
                throw failure
            }
        } else if (Thread.currentThread() !== reader && Thread.currentThread() !== logReader) closeDone.join()
    }
    companion object {
        fun launch(java: Path, classpath: List<Path>, main: String, mode: String, heapMiB: Int,
            directory: Path, transferParent: Path, environment: Map<String, String>, bootstrap: Bootstrap, maximum: Int,
            startupMillis: Long, sandbox: ScriptSandbox? = null, events: Events? = null, logBytes: Long? = null): ManagedJvm {
            val transfer = Files.createTempDirectory(transferParent, "protocol-transfer-").toRealPath()
            var owned: DarwinProcess? = null
            var managed: ManagedJvm? = null
            try {
                val arguments = listOf("-Xmx${heapMiB}m", "-cp", classpath.joinToString(File.pathSeparator), main, "--mode", mode)
                val executable = if (sandbox == null) java else Path.of("/usr/bin/sandbox-exec")
                val process = DarwinProcess.spawn(executable, sandbox?.arguments(arguments, transfer) ?: arguments,
                    sandbox?.environment ?: environment, sandbox?.directory ?: directory)
                owned = process
                return ManagedJvm(process, transfer, maximum, events).also {
                    managed = it
                    if (logBytes != null) it.beginLogs(logBytes)
                    it.start(bootstrap, startupMillis)
                }
            } catch (failure: Throwable) {
                var exitAndIOConfirmed = owned == null
                try {
                    val channel = managed
                    if (channel != null) channel.close() else owned?.close()
                    exitAndIOConfirmed = true
                } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                if (exitAndIOConfirmed) {
                    try { PrivateDirectories.remove(transfer) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                }
                throw failure
            }
        }
    }
}
