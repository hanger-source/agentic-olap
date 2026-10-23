package com.olap.prototype.scripting

import com.olap.prototype.dsl.OlapClient
import java.lang.management.ManagementFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

/** 只清理由本运行器实际取得的资源；未知平台线程仍阻止复用，快照不覆盖虚拟线程。 */
internal class ScriptExecutionResources(directory: Path) : AutoCloseable {
    private val thread = Thread.currentThread()
    private val previousLoader = thread.contextClassLoader
    private val previousInput = System.`in`
    private val previousOutput = System.out
    private val previousError = System.err
    private val threadBean = ManagementFactory.getThreadMXBean()
    val classLoader = URLClassLoader(emptyArray(), OlapClient::class.java.classLoader)
    private var threadsBeforeEvaluation: Set<Long>? = null
    private val issues = mutableListOf<ScriptResourceIssue>()
    val resourceIssues: List<ScriptResourceIssue> get() = java.util.List.copyOf(issues)

    init {
        require(Files.isDirectory(directory)) { "script execution requires its actual temporary directory" }
    }

    fun prepareEvaluation() {
        check(threadsBeforeEvaluation == null) { "script evaluation resources were already used" }
        // 已存在的编译环境线程进入基线；差集只说明新增观察，不证明由脚本创建。
        threadsBeforeEvaluation = try { threadBean.allThreadIds.toSet() }
        catch (failure: Exception) {
            inspectionFailure("platform thread baseline could not be inspected", failure)
            throw failure
        }
        thread.contextClassLoader = classLoader
    }

    override fun close() {
        val changedStreams = listOf(System.`in` !== previousInput, System.out !== previousOutput, System.err !== previousError).count { it }
        if (changedStreams != 0) issues += ScriptResourceIssue("STREAMS_CHANGED", "script worker standard streams changed during evaluation", changedStreams)
        try { threadsBeforeEvaluation?.let(::inspectThreads) }
        catch (failure: Exception) { inspectionFailure("platform thread state could not be inspected", failure) }
        try { thread.contextClassLoader = previousLoader }
        catch (failure: Exception) { issues += ScriptResourceIssue("CONTEXT_CLASSLOADER_RESTORE_FAILED",
            "script evaluation context classloader could not be restored (${failure.javaClass.name.take(MAXIMUM_TEXT)})") }
        try { classLoader.close() }
        catch (failure: Exception) { issues += ScriptResourceIssue("CLASSLOADER_CLOSE_FAILED",
            "script evaluation classloader could not be closed (${failure.javaClass.name.take(MAXIMUM_TEXT)})") }
    }

    private fun inspectThreads(before: Set<Long>) {
        val observed = threadBean.allThreadIds.filter { it != thread.threadId() && it !in before }.sorted()
        if (observed.isEmpty()) return
        var remainingCount = observed.size
        var truncated = observed.size > MAXIMUM_THREADS
        val selected = observed.take(MAXIMUM_THREADS)
        val observations = mutableListOf<ScriptThreadObservation>()
        fun text(value: String): String {
            if (value.length > MAXIMUM_TEXT) truncated = true
            return value.take(MAXIMUM_TEXT)
        }
        try {
            // ThreadInfo 可限栈深度；ThreadGroup 枚举仅用于补充它未提供的 TCCL。
            val references = platformThreads(selected.toSet())
            val information = threadBean.getThreadInfo(selected.toLongArray(), MAXIMUM_STACK_FRAMES + 1)
            information.forEach { info ->
                // allThreadIds 返回时线程可能已结束；正式 ThreadInfo=null 确认该平台线程已不再存活。
                // 未取样或读取失败的候选没有这项证明，仍留在 remainingCount 中。
                if (info == null) { remainingCount--; return@forEach }
                val candidate = references[info.threadId]
                val loader = if (candidate == null) {
                    truncated = true
                    "UNAVAILABLE: platform thread was not present in the bounded reference snapshot"
                } else try { describeLoader(candidate.contextClassLoader) }
                catch (failure: Exception) {
                    truncated = true
                    "UNAVAILABLE: ${failure.javaClass.name.take(MAXIMUM_TEXT / 2)}"
                }
                if (info.stackTrace.size > MAXIMUM_STACK_FRAMES) truncated = true
                observations += ScriptThreadObservation(info.threadId, text(info.threadName), info.threadState.name,
                    info.isDaemon, text(loader), java.util.List.copyOf(info.stackTrace.take(MAXIMUM_STACK_FRAMES).map { text(it.toString()) }))
            }
        } catch (failure: Exception) {
            truncated = true
            inspectionFailure("bounded platform thread details could not be inspected", failure)
        }
        if (remainingCount == 0) return
        issues += ScriptResourceIssue("SCRIPT_NEW_PLATFORM_THREADS",
            "observed $remainingCount platform threads absent from the evaluation baseline without confirmed termination; ownership and continued lifetime are unknown; virtual threads are not included",
            remainingCount, java.util.List.copyOf(observations), truncated)
    }

    private fun platformThreads(selected: Set<Long>): Map<Long, Thread> {
        var group = checkNotNull(thread.threadGroup)
        while (true) { group = group.parent ?: break }
        val references = arrayOfNulls<Thread>(MAXIMUM_THREAD_REFERENCES)
        val count = group.enumerate(references, true)
        return references.take(count).filterNotNull().filter { it.threadId() in selected }.associateBy { it.threadId() }
    }

    private fun describeLoader(loader: ClassLoader?): String = if (loader == null) "BOOTSTRAP" else {
        val relation = when { loader === classLoader -> "EVALUATION_CONTEXT"; loader === previousLoader -> "PREVIOUS_CONTEXT"; else -> "OTHER_CONTEXT" }
        "${loader.javaClass.name.take(MAXIMUM_TEXT / 2)}@${Integer.toHexString(System.identityHashCode(loader))}:$relation"
    }

    private fun inspectionFailure(message: String, failure: Exception) {
        issues += ScriptResourceIssue("RESOURCE_INSPECTION_FAILED", "$message (${failure.javaClass.name.take(MAXIMUM_TEXT / 2)})", truncated = true)
    }

    private companion object {
        const val MAXIMUM_THREADS = 16
        const val MAXIMUM_STACK_FRAMES = 16
        const val MAXIMUM_TEXT = 512
        const val MAXIMUM_THREAD_REFERENCES = 4_096
    }
}
