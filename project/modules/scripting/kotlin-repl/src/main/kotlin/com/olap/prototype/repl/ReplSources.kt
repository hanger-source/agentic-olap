package com.olap.prototype.repl

import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import kotlin.script.experimental.api.ScriptDiagnostic

/** 只保存定位元数据；源码与对象的语言历史由 Kotlin 自身管理。 */
internal class ReplSources {
    private val identity = UUID.randomUUID().toString().replace("-", "")
    private var sequence = 0L
    private val snippets = mutableMapOf<String, ReplLocation>()

    fun register(source: ReplSource): ReplLocation {
        val snippetId = "snippet_${identity}_${++sequence}.kts"
        return ReplLocation(snippetId, source.sourceName).also { snippets[snippetId] = it }
    }

    fun diagnostic(report: ScriptDiagnostic, phase: ReplPhase, current: ReplLocation): ReplDiagnostic {
        val source = report.sourcePath?.let(::find) ?: current
        val location = report.location?.let { range ->
            source.copy(line = range.start.line, column = range.start.col,
                endLine = range.end?.line, endColumn = range.end?.col)
        } ?: report.exception?.let { exceptionLocation(it) } ?: source
        return ReplDiagnostic("KOTLIN_${report.code}", phase, ReplSeverity.valueOf(report.severity.name),
            report.message, location, report.exception)
    }

    fun failure(code: String, phase: ReplPhase, error: Throwable, current: ReplLocation): ReplDiagnostic =
        ReplDiagnostic(code, phase, ReplSeverity.ERROR, error.message ?: error.javaClass.name,
            exceptionLocation(error) ?: current, error)

    fun clear() = snippets.clear()

    private fun find(path: String): ReplLocation? = snippets[path.substringAfterLast('/').substringAfterLast('\\')]

    private fun exceptionLocation(failure: Throwable): ReplLocation? {
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = failure
        while (current != null && visited.add(current)) {
            current.stackTrace.forEach { frame ->
                frame.fileName?.let(::find)?.let { source ->
                    return source.copy(line = frame.lineNumber.takeIf { it > 0 })
                }
            }
            current = current.cause
        }
        return null
    }
}
