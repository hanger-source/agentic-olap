package com.olap.prototype.repl

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.StringScriptSource
import kotlin.script.experimental.jvm.KJvmEvaluatedSnippet
import kotlin.script.experimental.util.LinkedSnippet

/**
 * 单一 K2 编译器与官方 JVM REPL evaluator 的持续会话。
 * 同会话并发提交会明确拒绝；宿主拥有进程、执行期限、授权及资源隔离。
 */
class KotlinRepl(private val configuration: ReplConfiguration) : AutoCloseable {
    private val occupied = AtomicBoolean()
    private val sources = ReplSources()
    private var compilation: ReplCompilation? = ReplCompilation(configuration)
    @Volatile private var closed = false
    @Volatile var continuation: ReplContinuation = ReplContinuation.AVAILABLE
        private set

    /**
     * [beforeEvaluation] 在调用官方 K2 evaluator 前触发，编译失败不会触发。
     * 返回值不包括末表达式；它不会被当作宿主输出，也不会被转换为字符串。
     */
    fun evaluate(
        source: ReplSource,
        checkCancellation: () -> Unit = {},
        beforeEvaluation: () -> Unit = {},
    ): ReplExecutionResult {
        check(occupied.compareAndSet(false, true)) { "one Kotlin REPL session evaluates one snippet at a time" }
        try {
            check(!closed) { "Kotlin REPL session is closed" }
            val current = sources.register(source)
            if (continuation == ReplContinuation.RESET_REQUIRED) return result(current, ReplStatus.NOT_EVALUATED,
                listOf(ReplDiagnostic("KOTLIN_SESSION_RESET_REQUIRED", ReplPhase.EVALUATE, ReplSeverity.ERROR,
                    "Kotlin REPL history cannot continue; the host must reset the session", current)))
            try {
                checkCancellation()
            } catch (failure: Throwable) {
                return result(current, ReplStatus.NOT_EVALUATED,
                    listOf(sources.failure("KOTLIN_NOT_EVALUATED", ReplPhase.COMPILE, failure, current)), failure)
            }
            val started = System.nanoTime()
            val state = compilation ?: try {
                ReplCompilation(configuration).also { compilation = it }
            } catch (failure: Throwable) {
                continuation = ReplContinuation.RESET_REQUIRED
                return result(current, ReplStatus.COMPILE_FAILED,
                    listOf(sources.failure("KOTLIN_COMPILER_INITIALIZATION_FAILED", ReplPhase.COMPILE, failure, current)),
                    failure, System.nanoTime() - started)
            }
            val previousCompiled = state.compiler.lastCompiledSnippet
            val compiled = try {
                runSuspending { state.compiler.compile(StringScriptSource(source.source, current.snippetId)) }
            } catch (failure: Throwable) {
                continuation = ReplContinuation.RESET_REQUIRED
                return result(current, ReplStatus.COMPILE_FAILED,
                    listOf(sources.failure("KOTLIN_COMPILER_FAILED", ReplPhase.COMPILE, failure, current)),
                    failure, System.nanoTime() - started)
            }
            val compilationNanos = System.nanoTime() - started
            val diagnostics = compiled.reports.map { sources.diagnostic(it, ReplPhase.COMPILE, current) }
            if (compiled is ResultWithDiagnostics.Failure) {
                // 普通语言拒绝不追加 compiled snippet。内部异常、致命报告或历史头变化
                // 不能伪装成一次可安全继续的作者错误，也不尝试重放或自行回滚 FIR。
                if (state.compiler.lastCompiledSnippet !== previousCompiled || compiled.reports.any {
                        it.exception != null || it.severity == ScriptDiagnostic.Severity.FATAL
                    }) continuation = ReplContinuation.RESET_REQUIRED
                if (continuation == ReplContinuation.AVAILABLE && previousCompiled == null &&
                    state.evaluator.lastEvaluatedSnippet == null) {
                    // K2 在语义拒绝前已把首段登记到 FIR，但此时尚未生成
                    // ReplState（KT-74584）。空的成功历史不能发布这份半初始化上下文。
                    // 释放后，下次按同一配置创建官方状态；不求值空片段或重放作者源码。
                    compilation = null
                    try {
                        state.close()
                    } catch (failure: Throwable) {
                        continuation = ReplContinuation.RESET_REQUIRED
                        return result(current, ReplStatus.COMPILE_FAILED,
                            diagnostics + sources.failure("KOTLIN_COMPILER_DISPOSAL_FAILED", ReplPhase.COMPILE, failure, current),
                            failure, System.nanoTime() - started)
                    }
                }
                return result(current, ReplStatus.COMPILE_FAILED, diagnostics,
                    compiled.reports.firstNotNullOfOrNull { it.exception }, System.nanoTime() - started)
            }
            val snippet = (compiled as ResultWithDiagnostics.Success<LinkedSnippet<CompiledSnippet>>).value
            val evaluationStarted = System.nanoTime()
            val evaluated = try {
                checkCancellation()
                val evaluationConfiguration = state.evaluationConfiguration()
                beforeEvaluation()
                runSuspending { state.evaluator.eval(snippet, evaluationConfiguration) }
            } catch (failure: Throwable) {
                // 编译已经推进但求值未完整归还；没有官方可兑现的回滚可供继续。
                continuation = ReplContinuation.RESET_REQUIRED
                return result(current, ReplStatus.RUNTIME_FAILED,
                    diagnostics + sources.failure("KOTLIN_EVALUATION_FAILED", ReplPhase.EVALUATE, failure, current),
                    failure, compilationNanos, System.nanoTime() - evaluationStarted)
            }
            val evaluationNanos = System.nanoTime() - evaluationStarted
            val allDiagnostics = diagnostics + evaluated.reports.map { sources.diagnostic(it, ReplPhase.EVALUATE, current) }
            if (evaluated is ResultWithDiagnostics.Failure) {
                continuation = ReplContinuation.RESET_REQUIRED
                return result(current, ReplStatus.NOT_EVALUATED, allDiagnostics,
                    evaluated.reports.firstNotNullOfOrNull { it.exception }, compilationNanos, evaluationNanos)
            }
            val evaluatedHead = (evaluated as ResultWithDiagnostics.Success<LinkedSnippet<KJvmEvaluatedSnippet>>).value
            val evaluatedSnippet = evaluatedHead.get()
            val returned = evaluatedSnippet.result
            // K2 对普通运行异常同样追加官方求值节点，不以 Error 本身使会话失效。
            // 下段使用的类身份与官方 K2 evaluator 一致：scriptClass 或 scriptInstance 的类。
            // 只核对两端实际历史头，不维护另一份历史，也不承诺失败段所有声明已初始化。
            val evaluatedClass = returned.scriptClass?.java ?: returned.scriptInstance?.javaClass
            if (state.evaluator.lastEvaluatedSnippet !== evaluatedHead || state.compiler.lastCompiledSnippet !== snippet ||
                evaluatedSnippet.compiledSnippet !== snippet.get() || evaluatedClass == null) {
                continuation = ReplContinuation.RESET_REQUIRED
            }
            return when (returned) {
                is ResultValue.Unit, is ResultValue.Value ->
                    result(current, ReplStatus.COMPLETED, allDiagnostics, null, compilationNanos, evaluationNanos)
                is ResultValue.Error -> result(current, ReplStatus.RUNTIME_FAILED,
                    allDiagnostics + sources.failure("KOTLIN_EXECUTION_FAILED", ReplPhase.EVALUATE, returned.error, current),
                    returned.error, compilationNanos, evaluationNanos)
                ResultValue.NotEvaluated -> result(current, ReplStatus.NOT_EVALUATED,
                    allDiagnostics + ReplDiagnostic("KOTLIN_NOT_EVALUATED", ReplPhase.EVALUATE, ReplSeverity.ERROR,
                        "Kotlin evaluator did not evaluate the snippet", current), null, compilationNanos, evaluationNanos)
            }
        } finally {
            occupied.set(false)
        }
    }

    /** 释放编译状态及历史引用；不关闭装配方持有的稳定宿主 classloader。 */
    override fun close() {
        check(occupied.compareAndSet(false, true)) { "cannot close a Kotlin REPL session during evaluation" }
        try {
            if (closed) return
            closed = true
            continuation = ReplContinuation.RESET_REQUIRED
            val state = compilation
            compilation = null
            try { state?.close() } finally { sources.clear() }
        } finally {
            occupied.set(false)
        }
    }

    private fun result(
        source: ReplLocation,
        status: ReplStatus,
        diagnostics: List<ReplDiagnostic>,
        failure: Throwable? = null,
        compilationNanos: Long = 0,
        evaluationNanos: Long = 0,
    ) = ReplExecutionResult(source.snippetId, source.sourceName, status, java.util.List.copyOf(diagnostics),
        failure, compilationNanos, evaluationNanos, continuation)
}

/** 官方编译/求值 API 为 suspend；不另起求值线程，不让宿主失去本次线程归属。 */
private fun <T> runSuspending(block: suspend () -> T): T {
    val completed = CountDownLatch(1)
    val result = AtomicReference<Result<T>>()
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(value: Result<T>) {
            result.set(value)
            completed.countDown()
        }
    })
    // 官方当前实现同步完成时不再进入可中断等待；脚本自己设置中断标志
    // 不应抹掉已经取得的真实求值结局。
    if (completed.count != 0L) completed.await()
    return result.get().getOrThrow()
}
