package com.olap.prototype.scripting

import com.olap.prototype.dsl.OlapClient
import com.olap.prototype.dsl.ScriptArguments
import com.olap.prototype.dsl.ScriptValue
import com.olap.prototype.dsl.ScriptValueException
import com.olap.prototype.dsl.transport.CapabilityFailureException
import com.olap.prototype.contract.InputContractException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.*
import kotlin.script.experimental.api.*
import kotlin.script.experimental.api.ScriptDiagnostic as KotlinDiagnostic
import kotlin.script.experimental.jvm.BasicJvmScriptEvaluator
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.loadDependencies
import kotlin.script.experimental.host.StringScriptSource
import kotlin.script.experimental.jvmhost.JvmScriptCompiler

/** 固定编译环境可复用；每次程序的实例、参数、回调和求值类加载器属于本次请求。 */
class ScriptRuntime(private val environment: ScriptEnvironment) {
    private val running = AtomicBoolean()
    private val compiler = JvmScriptCompiler()
    private val compilationConfiguration = scriptConfiguration(environment)

    fun run(source: ScriptSource, olap: OlapClient, parameters: ScriptArguments, checkCancellation: () -> Unit): ScriptRunResult {
        check(running.compareAndSet(false, true)) { "one script worker evaluates one program at a time" }
        val resources = try { ScriptExecutionResources(olap.temporaryDirectory) }
        catch (failure: Exception) { running.set(false); throw failure }
        var result: ScriptRunResult
        try { result = program(source, olap, parameters, resources, checkCancellation) }
        finally { resources.close(); running.set(false) }
        val resourceIssues = resources.resourceIssues
        return result.copy(resourceIssues = resourceIssues,
            diagnostics = result.diagnostics + resourceIssues.map { ScriptDiagnostic(it.code, ScriptPhase.OUTPUT,
                ScriptSeverity.WARNING, it.message, source.sourceName) })
    }

    private fun program(source: ScriptSource, olap: OlapClient, parameters: ScriptArguments, resources: ScriptExecutionResources, checkCancellation: () -> Unit): ScriptRunResult {
        checkCancellation()
        val sourceBytes = source.source.toByteArray(Charsets.UTF_8)
        val sourceHash = digest(sourceBytes)
        if (sourceBytes.size > environment.limits.maximumSourceBytes) return result(null,
            listOf(failure("SCRIPT_SOURCE_LIMIT", ScriptPhase.INPUT, "script source exceeds its UTF-8 byte budget", source.sourceName)), 0, 0, sourceHash)
        val compilationStarted = System.nanoTime()
        val compiled = try {
            runScriptSuspending { compiler(StringScriptSource(source.source, source.sourceName), compilationConfiguration) }
        } catch (error: Exception) {
            return result(null, listOf(exceptionDiagnostic("SCRIPT_COMPILER_FAILED", ScriptPhase.COMPILE, error, source.sourceName)),
                System.nanoTime() - compilationStarted, 0, sourceHash)
        }
        val compilationNanos = System.nanoTime() - compilationStarted
        checkCancellation()
        val reports = diagnostics(compiled.reports, ScriptPhase.COMPILE, source.sourceName)
        if (compiled is ResultWithDiagnostics.Failure) return result(null, reports, compilationNanos, 0, sourceHash)
        val script = (compiled as ResultWithDiagnostics.Success<CompiledScript>).value
        val evaluationStarted = System.nanoTime()
        val evaluated = try {
            resources.prepareEvaluation()
            val configuration = ScriptEvaluationConfiguration {
                providedProperties("olap" to olap, "parameters" to parameters)
                jvm { baseClassLoader(resources.classLoader); loadDependencies(false) }
            }
            runScriptSuspending { BasicJvmScriptEvaluator()(script, configuration) }
        } catch (error: Exception) {
            return result(null, reports + exceptionDiagnostic("SCRIPT_EVALUATION_FAILED", ScriptPhase.EVALUATE, error, source.sourceName),
                compilationNanos, System.nanoTime() - evaluationStarted, sourceHash)
        }
        val evaluationNanos = System.nanoTime() - evaluationStarted
        val allReports = reports + diagnostics(evaluated.reports, ScriptPhase.EVALUATE, source.sourceName)
        if (evaluated is ResultWithDiagnostics.Failure) return result(null, allReports, compilationNanos, evaluationNanos, sourceHash)
        return when (val returned = (evaluated as ResultWithDiagnostics.Success<EvaluationResult>).value.returnValue) {
            is ResultValue.Unit -> result(ScriptValue.Null, allReports, compilationNanos, evaluationNanos, sourceHash)
            is ResultValue.Value -> when (val value = returned.value) {
                is ScriptValue -> result(value, allReports, compilationNanos, evaluationNanos, sourceHash)
                Unit -> result(ScriptValue.Null, allReports, compilationNanos, evaluationNanos, sourceHash)
                else -> result(null, allReports + failure("SCRIPT_RESULT_TYPE", ScriptPhase.OUTPUT,
                    "script must return ScriptValue or Unit; actual type: ${returned.type}", source.sourceName), compilationNanos, evaluationNanos, sourceHash)
            }
            is ResultValue.Error -> result(null, allReports + exceptionDiagnostic("SCRIPT_EXECUTION_FAILED", ScriptPhase.EVALUATE,
                returned.error, source.sourceName), compilationNanos, evaluationNanos, sourceHash)
            ResultValue.NotEvaluated -> result(null, allReports + failure("SCRIPT_NOT_EVALUATED", ScriptPhase.EVALUATE,
                "script was not evaluated", source.sourceName), compilationNanos, evaluationNanos, sourceHash)
        }
    }

    private fun result(value: ScriptValue?, diagnostics: List<ScriptDiagnostic>, compilation: Long, evaluation: Long, sourceHash: String) =
        ScriptRunResult(value != null, value, java.util.List.copyOf(diagnostics), compilation, evaluation, sourceHash)

    private fun diagnostics(reports: List<KotlinDiagnostic>, phase: ScriptPhase, sourceName: String): List<ScriptDiagnostic> =
        reports.asSequence().filter { it.severity != KotlinDiagnostic.Severity.DEBUG }.take(environment.limits.maximumDiagnostics).map { report ->
            ScriptDiagnostic("KOTLIN_${report.code}", phase, when (report.severity) {
                KotlinDiagnostic.Severity.ERROR, KotlinDiagnostic.Severity.FATAL -> ScriptSeverity.ERROR
                KotlinDiagnostic.Severity.WARNING -> ScriptSeverity.WARNING
                else -> ScriptSeverity.INFO
            }, report.message.take(4_096), sourceName, report.location?.start?.line, report.location?.start?.col,
                report.location?.end?.line, report.location?.end?.col)
        }.toList()

    private fun exceptionDiagnostic(code: String, phase: ScriptPhase, error: Throwable, sourceName: String): ScriptDiagnostic {
        val causes = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (current != null && causes.size < 16 && causes.none { it === current }) { causes += current; current = current.cause }
        val capability = causes.filterIsInstance<CapabilityFailureException>().firstOrNull()?.failure
        val input = if (capability == null) causes.filterIsInstance<InputContractException>().firstOrNull() else null
        val value = if (capability == null && input == null) causes.filterIsInstance<ScriptValueException>().firstOrNull() else null
        val location = causes.asSequence().flatMap { it.stackTrace.asSequence() }.firstOrNull { it.fileName == sourceName }
        return ScriptDiagnostic(capability?.code ?: input?.rule?.code ?: value?.code ?: code, phase, ScriptSeverity.ERROR,
            (capability?.message ?: input?.message ?: value?.message ?: error.message ?: error.javaClass.simpleName).take(4_096), sourceName,
            location?.lineNumber?.takeIf { it > 0 }, capabilityFailure = capability, inputRule = input?.rule,
            fieldPath = (input?.details?.get("fieldPath") as? List<*>)?.map { it.toString() } ?: value?.fieldPath?.map(Any::toString) ?: emptyList(),
            expectedKind = input?.details?.get("expectedKind") as? String ?: value?.expected,
            actualKind = input?.details?.get("actualKind") as? String ?: value?.actual)
    }
    private fun failure(code: String, phase: ScriptPhase, message: String, sourceName: String) =
        ScriptDiagnostic(code, phase, ScriptSeverity.ERROR, message, sourceName)
}

private fun <T> runScriptSuspending(block: suspend () -> T): T {
    val completed = CountDownLatch(1)
    val result = AtomicReference<Result<T>>()
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(value: Result<T>) { result.set(value); completed.countDown() }
    })
    completed.await()
    return result.get().getOrThrow()
}
