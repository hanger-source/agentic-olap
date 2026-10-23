package com.olap.prototype.scripting

import com.olap.prototype.dsl.ScriptValue
import com.olap.prototype.dsl.transport.CapabilityFailure
import com.olap.prototype.contract.InputContractException
import java.security.MessageDigest

/** 编译、加载与求值的限制分别检查；进程期限和强制终止由 Go 负责。 */
data class ScriptLimits(
    val maximumSourceBytes: Int = 262_144,
    val maximumDiagnostics: Int = 100,
) {
    init {
        require(maximumSourceBytes > 0 && maximumDiagnostics > 0)
    }
}

data class ScriptSource(val source: String, val sourceName: String = "analysis.olap.kts") {
    init {
        require(sourceName.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,119}\\.kts"))) {
            "script sourceName must be a local .kts filename"
        }
    }
}

enum class ScriptPhase { INPUT, COMPILE, EVALUATE, OUTPUT }
enum class ScriptSeverity { INFO, WARNING, ERROR }

data class ScriptDiagnostic(
    val code: String,
    val phase: ScriptPhase,
    val severity: ScriptSeverity,
    val message: String,
    val sourceName: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val endLine: Int? = null,
    val endColumn: Int? = null,
    val capabilityFailure: CapabilityFailure? = null,
    val inputRule: InputContractException.Rule? = null,
    val fieldPath: List<String> = emptyList(),
    val expectedKind: String? = null,
    val actualKind: String? = null,
)

/** 有界私有现场，只记录平台线程事实；不持有线程或类加载器，也不据此认定资源创建者。 */
data class ScriptThreadObservation(
    val id: Long,
    val name: String,
    val state: String,
    val daemon: Boolean,
    val contextClassLoader: String,
    val stack: List<String>,
)

/** observedCount 是观察时的总量；threads 仅为有限样本，truncated 表示细节不完整。 */
data class ScriptResourceIssue(
    val code: String,
    val message: String,
    val observedCount: Int = 0,
    val threads: List<ScriptThreadObservation> = emptyList(),
    val truncated: Boolean = false,
)

data class ScriptRunResult(
    val successful: Boolean,
    val value: ScriptValue?,
    val diagnostics: List<ScriptDiagnostic>,
    val compilationNanos: Long,
    val evaluationNanos: Long,
    val sourceSha256: String,
    val resourceIssues: List<ScriptResourceIssue> = emptyList(),
) {
    val recycleWorker: Boolean get() = resourceIssues.isNotEmpty()
    val recycleReasons: List<String> get() = resourceIssues.map { it.code }.distinct()
}

internal fun digest(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
