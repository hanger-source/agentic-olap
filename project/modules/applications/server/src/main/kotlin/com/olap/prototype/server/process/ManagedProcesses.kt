package com.olap.prototype.server.process

import com.fasterxml.jackson.databind.JsonNode
import com.google.protobuf.ByteString
import com.olap.prototype.protocol.Bootstrap
import com.olap.prototype.protocol.Request
import com.olap.prototype.protocol.Response
import com.olap.prototype.protocol.WorkerKind
import com.olap.prototype.query.contract.QueryWorkerAcquisition
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.contract.SourceConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Main-service owner of exact worker configurations, OS handles, admission and shutdown. */
class ManagedProcesses(val config: JsonNode, distribution: Path) : AutoCloseable {
    internal val installed = JvmDistribution(distribution)
    internal val limits = config.required("limits")
    internal val scripting = config.required("scripting")
    internal val mapper = OlapJsonUtils.mapper()
    internal val directory = Path.of(config.required("dataRoot").textValue()).resolve("runtime").also {
        Files.createDirectories(it, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    }.toRealPath()
    internal val transferParent = directory.resolve("protocol-transfers").also {
        Files.createDirectories(it, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    }.toRealPath()
    internal val maximumFrame = positive(limits, "pendingOutputBytes")
    internal val startupMillis = limits.required("startupSeconds").decimalValue().movePointRight(3).longValueExact().also { require(it > 0) }
    internal val requestMillis = limits.required("requestSeconds").decimalValue().movePointRight(3).longValueExact().also { require(it > 0) }
    internal val ids = AtomicLong()
    internal val closed = AtomicBoolean()
    private val registered = mapper.treeToValue(config.required("dataSources"), Array<SourceConfiguration>::class.java).toList()
        .associateBy { it.bindingRef() }.also { require(it.size == config.required("dataSources").size()) { "duplicate exact configured source" } }
    private val slots = List(positive(limits, "workerCount")) { Slot() }
    private val available = ArrayBlockingQueue<Slot>(slots.size).apply { addAll(slots) }
    private val shutdownActions = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    internal class Slot { var key: String? = null; @Volatile var process: ManagedJvm? = null }

    fun acquire(kind: WorkerKind, sources: List<SourceConfiguration>, requestId: String, remainingMillis: Long): Lease {
        require(kind != WorkerKind.WORKER_KIND_UNSPECIFIED && kind != WorkerKind.UNRECOGNIZED && requestId.isNotBlank() && remainingMillis > 0)
        val ordered = sources.sortedWith(compareBy({ it.dataSourceId }, { it.sourceIdentityHash() }))
        require(ordered.map { it.bindingRef() }.distinct().size == ordered.size && ordered.all { registered[it.bindingRef()] == it }) {
            "worker requires distinct deployed exact source configurations"
        }
        val encoded = mapper.writeValueAsBytes(ordered)
        val key = kind.name + ":" + java.util.Base64.getEncoder().encodeToString(encoded)
        val deadline = deadlineAfter(remainingMillis)
        val queued = System.nanoTime()
        check(!closed.get()) { "process runtime is closed" }
        val slot = available.poll(remainingMillis, TimeUnit.MILLISECONDS)
            ?: throw java.util.concurrent.TimeoutException("worker admission deadline exceeded")
        val queueNanos = System.nanoTime() - queued
        try {
            synchronized(slot) {
                check(!closed.get()) { "process runtime is closed" }
                var startup = 0L
                val reused = slot.key == key && slot.process?.alive == true
                if (!reused) {
                    slot.process?.close(); slot.process = null
                    val began = System.nanoTime()
                    val bootstrap = Bootstrap.newBuilder().setProtocol(PROTOCOL)
                        .setLimitsJson(ByteString.copyFrom(mapper.writeValueAsBytes(limits))).setWorkerKind(kind)
                        .setSourceConfigurationsJson(ByteString.copyFrom(encoded))
                        .addAllConnectorManifests(config.required("connectors").map { it.textValue() }).build()
                    slot.process = ManagedJvm.launch(installed.java, installed.worker, installed.workerMain, "worker",
                        positive(limits, "workerJvmHeapMiB"), directory, transferParent, environment(ordered), bootstrap, maximumFrame,
                        minOf(startupMillis, remaining(deadline)))
                    if (closed.get()) {
                        slot.process?.close(); slot.process = null
                        error("process runtime closed during worker startup")
                    }
                    slot.key = key
                    startup = System.nanoTime() - began
                }
                return Lease(slot, requestId, deadline, QueryWorkerAcquisition(kind.name,
                    if (reused) "REUSED" else "STARTED", queueNanos, startup))
            }
        } catch (failure: Throwable) { check(available.offer(slot)); throw failure }
    }
    inner class Lease internal constructor(private val slot: Slot, private val requestId: String,
        private val deadline: Long, val observation: QueryWorkerAcquisition) : AutoCloseable {
        private var released = false
        @Synchronized fun exchange(request: Request, sourceCompleted: ((com.olap.prototype.wire.query.SourceReadEvidence) -> Unit)? = null): Response {
            check(!released && !closed.get()) { "worker lease is closed" }
            require(request.requestId.isNotBlank() && request.remainingMillis > 0 && request.hasWorkerModel()) { "worker command is required" }
            val bounded = request.toBuilder().setRemainingMillis(minOf(request.remainingMillis, remaining(deadline))).build()
            return requireNotNull(slot.process).exchange(bounded, sourceCompleted)
        }
        @Synchronized override fun close() {
            if (released) return
            released = true
            if (closed.get() || Thread.currentThread().isInterrupted || slot.process?.alive != true) {
                slot.process?.close(); slot.process = null; slot.key = null
            }
            check(available.offer(slot)) { "worker slot was returned twice" }
        }
    }
    internal fun onShutdown(action: () -> Unit) { check(!closed.get()); shutdownActions.add(action) }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val failures = ArrayList<Throwable>()
        shutdownActions.forEach { try { it() } catch (failure: Throwable) { failures.add(failure) } }
        // All slots, including leased ones, remain owned here. Termination does not wait for lease locks.
        slots.mapNotNull { it.process }.distinct().forEach { try { it.close() } catch (failure: Throwable) { failures.add(failure) } }
        if (failures.isNotEmpty()) throw IllegalStateException("managed process shutdown failed", failures.first()).also {
            failures.drop(1).forEach(it::addSuppressed)
        }
    }
    private fun environment(sources: List<SourceConfiguration>): Map<String, String> = buildMap {
        listOf("PATH", "JAVA_HOME", "LANG", "LC_ALL", "TZ", "TMPDIR").forEach { name -> System.getenv(name)?.let { put(name, it) } }
        sources.flatMap { it.credentialEnvironmentVariables.values }.distinct().sorted().forEach { name ->
            require(name !in setOf("PATH", "JAVA_HOME", "LANG", "LC_ALL", "TZ", "TMPDIR", "HOME", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) {
                "source credential conflicts with process environment"
            }
            put(name, requireNotNull(System.getenv(name)?.takeIf { it.isNotEmpty() }) { "selected source credential is unavailable: $name" })
        }
    }
    companion object {
        const val PROTOCOL = "agentic-olap-runtime/v6"
        internal fun positive(node: JsonNode, name: String): Int = node.required(name).let {
            require(it.isIntegralNumber && it.canConvertToInt() && it.intValue() > 0) { "positive exact $name is required" }; it.intValue()
        }
        internal fun deadlineAfter(millis: Long): Long = Math.addExact(System.nanoTime(), Math.multiplyExact(millis, 1_000_000L))
        internal fun remaining(deadline: Long): Long = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).also {
            if (it <= 0) throw java.util.concurrent.TimeoutException("request deadline exceeded")
        }
    }
}
