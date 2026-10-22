package com.olap.prototype.warehouse

import com.olap.prototype.runtime.RuntimeResourceLimitException
import com.olap.prototype.warehouse.contract.SourceCancellationEvidence
import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 一次请求的期限与取消；无法响应驱动取消时由所属进程终止。 */
class DeadlineExecutionControl(remainingMillis: Long) : WarehouseExecutionControl, AutoCloseable {
    private val started = System.nanoTime()
    private val durationNanos: Long
    private val actions = linkedSetOf<WarehouseExecutionControl.CancellationAction>()
    private var expired = false
    private var closed = false
    private var cancellation: SourceCancellationEvidence = SourceCancellationEvidence.NotRequested
    private val timer = Executors.newSingleThreadScheduledExecutor { work -> Thread(work, "warehouse-request-deadline").apply { isDaemon = true } }
    init {
        require(remainingMillis > 0 && remainingMillis <= Long.MAX_VALUE / 1_000_000) { "positive finite remainingMillis is required" }
        durationNanos = TimeUnit.MILLISECONDS.toNanos(remainingMillis)
        timer.schedule(::expire, remainingMillis, TimeUnit.MILLISECONDS)
    }
    override fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) {
            synchronized(actions) {
                if (cancellation is SourceCancellationEvidence.NotRequested) cancellation = SourceCancellationEvidence.Unconfirmed("request interrupted; source cancellation is unconfirmed")
            }
            throw CancellationException("request interrupted")
        }
        synchronized(actions) {
            if (expired || System.nanoTime() - started >= durationNanos) throw RuntimeResourceLimitException("REQUEST_DEADLINE_EXCEEDED")
            check(!closed) { "execution control is closed" }
        }
    }
    override fun cancellationEvidence(): SourceCancellationEvidence = synchronized(actions) { cancellation }
    override fun registerCancellationAction(action: WarehouseExecutionControl.CancellationAction): WarehouseExecutionControl.Registration {
        checkCancellation()
        synchronized(actions) {
            if (expired) throw RuntimeResourceLimitException("REQUEST_DEADLINE_EXCEEDED")
            actions.add(action)
        }
        return WarehouseExecutionControl.Registration { synchronized(actions) { actions.remove(action) }; Unit }
    }
    private fun expire() {
        val active = synchronized(actions) {
            if (closed) return
            expired = true
            cancellation = SourceCancellationEvidence.Unconfirmed("request deadline expired; cancellation actions do not confirm a source terminal state")
            actions.toList()
        }
        active.forEach { action ->
            try { action.cancel() } catch (failure: Exception) {
                synchronized(actions) { cancellation = SourceCancellationEvidence.Failed("SOURCE_CANCELLATION_ACTION_FAILED", "a source cancellation action failed without a cancellation receipt") }
                LOG.warn("JDBC deadline cancellation failed: type={}", failure.javaClass.name)
            }
        }
    }
    override fun close() {
        synchronized(actions) { closed = true; actions.clear() }
        timer.shutdownNow()
    }
    companion object { private val LOG = LoggerFactory.getLogger(DeadlineExecutionControl::class.java) }
}
