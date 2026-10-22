package com.olap.prototype.scripting

import com.olap.prototype.dsl.OlapClient
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

/** 只清理由本运行器实际取得的资源；未知线程意味着worker不再可复用。 */
internal class ScriptExecutionResources(directory: Path) : AutoCloseable {
    private val thread = Thread.currentThread()
    private val previousLoader = thread.contextClassLoader
    private val previousInput = System.`in`
    private val previousOutput = System.out
    private val previousError = System.err
    val classLoader = URLClassLoader(emptyArray(), OlapClient::class.java.classLoader)
    private var threadsBeforeEvaluation: Set<Thread>? = null
    val failures = mutableListOf<String>()
    val recycleWorker: Boolean get() = failures.isNotEmpty()

    init {
        require(Files.isDirectory(directory)) { "script execution requires its actual temporary directory" }
    }

    fun prepareEvaluation() {
        check(threadsBeforeEvaluation == null) { "script evaluation resources were already used" }
        // 编译器自身的稳定线程不冒充脚本启动的后台任务。
        threadsBeforeEvaluation = Thread.getAllStackTraces().keys.toSet()
        thread.contextClassLoader = classLoader
    }

    override fun close() {
        if (System.`in` !== previousInput || System.out !== previousOutput || System.err !== previousError)
            failures += "script changed worker standard streams"
        try {
            threadsBeforeEvaluation?.let { before ->
                val remaining = Thread.getAllStackTraces().keys.filter { it !== thread && it.isAlive && it !in before }
                if (remaining.isNotEmpty()) failures += "script returned with ${remaining.size} live background threads"
            }
        } catch (_: Exception) { failures += "script background thread state could not be inspected" }
        try { thread.contextClassLoader = previousLoader }
        catch (_: Exception) { failures += "script evaluation context classloader could not be restored" }
        try { classLoader.close() }
        catch (_: Exception) { failures += "script evaluation classloader could not be closed" }
    }
}
