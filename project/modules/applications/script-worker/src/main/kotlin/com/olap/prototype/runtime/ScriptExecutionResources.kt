package com.olap.prototype.runtime

import java.lang.management.ManagementFactory
import java.lang.management.ThreadMXBean
import java.nio.file.Files
import java.nio.file.Path

/** 线程快照只提供观察；会话只因本类实际拥有的求值上下文无法恢复而失去可继续性。 */
internal class ScriptExecutionResources(directory: Path) : AutoCloseable {
    private val thread = Thread.currentThread()
    private val previousLoader = thread.contextClassLoader
    private val previousInput = System.`in`
    private val previousOutput = System.out
    private val previousError = System.err
    private var threadBean: ThreadMXBean? = null
    private var threadsBeforeEvaluation: Set<Long>? = null
    private var prepared = false
    private val issues = mutableListOf<ScriptResourceIssue>()
    val resourceIssues: List<ScriptResourceIssue> get() = java.util.List.copyOf(issues)
    var evaluationContextRestored = true
        private set

    init {
        require(Files.isDirectory(directory)) { "script execution requires its actual temporary directory" }
    }

    fun prepareEvaluation() {
        check(!prepared) { "script evaluation resources were already used" }
        prepared = true
        // 已存在的编译环境线程进入基线；差集只说明新增观察，不证明由脚本创建。
        try {
            val bean = ManagementFactory.getThreadMXBean()
            threadsBeforeEvaluation = bean.allThreadIds.toSet()
            threadBean = bean
        }
        catch (failure: Exception) {
            inspectionFailure("platform thread baseline could not be inspected", failure)
        }
    }

    override fun close() {
        val changedStreams = listOf(System.`in` !== previousInput, System.out !== previousOutput, System.err !== previousError).count { it }
        if (changedStreams != 0) {
            issues += ScriptResourceIssue("STREAMS_CHANGED", "script worker standard streams changed during evaluation", changedStreams)
            try {
                System.setIn(previousInput)
                System.setOut(previousOutput)
                System.setErr(previousError)
                check(System.`in` === previousInput && System.out === previousOutput && System.err === previousError) {
                    "script worker standard streams differ after restoration"
                }
            } catch (failure: Exception) {
                evaluationContextRestored = false
                issues += ScriptResourceIssue("STANDARD_STREAM_RESTORE_FAILED",
                    "script worker standard streams could not be restored (${failure.javaClass.name.take(MAXIMUM_TEXT)})")
            }
        }
        try { threadsBeforeEvaluation?.let(::inspectThreads) }
        catch (failure: Exception) { inspectionFailure("platform thread state could not be inspected", failure) }
        try {
            thread.contextClassLoader = previousLoader
            check(thread.contextClassLoader === previousLoader) { "script evaluation context classloader differs after restoration" }
        } catch (failure: Exception) {
            evaluationContextRestored = false
            issues += ScriptResourceIssue("CONTEXT_CLASSLOADER_RESTORE_FAILED",
                "script evaluation context classloader could not be restored (${failure.javaClass.name.take(MAXIMUM_TEXT)})")
        }
    }

    private fun inspectThreads(before: Set<Long>) {
        val bean = checkNotNull(threadBean)
        val observed = bean.allThreadIds.filter { it != thread.threadId() && it !in before }.sorted()
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
            val information = bean.getThreadInfo(selected.toLongArray(), MAXIMUM_STACK_FRAMES + 1)
            information.forEach { info ->
                // allThreadIds 返回时线程可能已结束；正式 ThreadInfo=null 确认该平台线程已不再存活。
                // 未取样或读取失败只保留观察的不确定性，不决定会话生命周期。
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
        val relation = if (loader === previousLoader) "PREVIOUS_CONTEXT" else "OTHER_CONTEXT"
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

/** 有界私有事实，不保留 Thread/ClassLoader 引用，也不推断资源创建者。 */
internal data class ScriptThreadObservation(val id: Long, val name: String, val state: String, val daemon: Boolean,
    val contextClassLoader: String, val stack: List<String>)

internal data class ScriptResourceIssue(val code: String, val message: String, val observedCount: Int = 0,
    val threads: List<ScriptThreadObservation> = emptyList(), val truncated: Boolean = false)
