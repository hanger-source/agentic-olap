package com.olap.prototype.repl

import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.K2ReplCompiler
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.K2ReplEvaluator
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.ScriptDiagnosticsMessageCollector
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.JvmDependency
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.jvmTarget
import kotlin.script.experimental.jvm.loadDependencies

/** 编译及求值历史共同属于一个会话；不缓存独立脚本，也不重放历史源码。 */
internal class ReplCompilation(configuration: ReplConfiguration) : AutoCloseable {
    private val disposable: Disposable = Disposer.newDisposable("Kotlin REPL")
    private val parentClassLoader = configuration.parentClassLoader
    val compiler: K2ReplCompiler
    val evaluator: K2ReplEvaluator
    private val fixedEvaluationConfiguration: ScriptEvaluationConfiguration

    init {
        try {
            require(JvmTarget.fromString(configuration.jvmTarget) != null) {
                "unsupported Kotlin JVM target: ${configuration.jvmTarget}"
            }
            val compilationConfiguration = ScriptCompilationConfiguration {
                displayName("Kotlin REPL")
                fileExtension("kts")
                defaultImports.put(configuration.defaultImports)
                dependencies(JvmDependency(configuration.classpath.map { it.toFile() }))
                compilerOptions("-language-version", "2.2", "-api-version", "2.2", "-no-stdlib", "-no-reflect")
                jvm { jvmTarget(configuration.jvmTarget) }
            }
            // 使用官方默认的 FIR REPL history provider 与 snippet source 声明。
            compiler = K2ReplCompiler(K2ReplCompiler.createCompilationState(
                ScriptDiagnosticsMessageCollector(parentMessageCollector = null), disposable, compilationConfiguration))
            // K2 snippet 由官方 evaluator 执行其生成的单例求值入口；不使用脚本构造器。
            evaluator = K2ReplEvaluator()
            fixedEvaluationConfiguration = ScriptEvaluationConfiguration {
                jvm { baseClassLoader(configuration.parentClassLoader); loadDependencies(false) }
            }
        } catch (failure: Throwable) {
            Disposer.dispose(disposable)
            throw failure
        }
    }

    fun evaluationConfiguration(): ScriptEvaluationConfiguration {
        // 官方 KJvmCompiledScript 在 loadDependencies(false) 时仅使用 baseClassLoader。
        // 上段 loader 的父链最终仍是固定宿主，不为每段重载产品 JAR。
        val previous = evaluator.lastEvaluatedSnippet?.get()?.result
        val previousClass = previous?.scriptClass?.java ?: previous?.scriptInstance?.javaClass
        val previousLoader = previousClass?.classLoader
        return ScriptEvaluationConfiguration(fixedEvaluationConfiguration) {
            jvm { baseClassLoader(previousLoader ?: parentClassLoader); loadDependencies(false) }
        }
    }

    override fun close() {
        Disposer.dispose(disposable)
    }
}
