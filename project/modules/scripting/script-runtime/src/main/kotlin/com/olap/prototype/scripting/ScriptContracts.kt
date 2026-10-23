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

data class ScriptRunResult(
    val successful: Boolean,
    val value: ScriptValue?,
    val diagnostics: List<ScriptDiagnostic>,
    val compilationNanos: Long,
    val evaluationNanos: Long,
    val sourceSha256: String,
    val recycleWorker: Boolean = false,
)

internal fun digest(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
