package com.olap.prototype.warehouse.contract

/** 本次实际读取的事实；可 pin 不承诺来源永久保留历史版本。 */
sealed interface SourceReadBasis {
    val kind: String
    data class Unknown(val reason: String) : SourceReadBasis {
        override val kind: String get() = "UNKNOWN"
        init { require(reason.isNotBlank()) }
    }
    class Versioned(val authority: String, val version: String, partitions: Map<String, String>, val pinning: SourcePinning) : SourceReadBasis {
        override val kind: String get() = "VERSIONED"
        val partitions: Map<String, String> = java.util.Map.copyOf(partitions)
        init { require(authority.isNotBlank() && version.isNotBlank() && this.partitions.all { it.key.isNotBlank() && it.value.isNotBlank() }) }
        override fun equals(other: Any?): Boolean = other is Versioned && authority == other.authority && version == other.version && partitions == other.partitions && pinning == other.pinning
        override fun hashCode(): Int = listOf(authority, version, partitions, pinning).hashCode()
    }
}

enum class SourcePinning { UNSUPPORTED, EXACT_VERSION }
data class SourceReadEvidence(val binding: SourceBindingRef, val basis: SourceReadBasis, val request: SourceRequestEvidence)

enum class SourceRequestAcceptance { NOT_SUBMITTED, SUBMITTED, ACCEPTED, UNKNOWN }
enum class SourceResponseState { NOT_RECEIVED, RECEIVED, UNKNOWN }
enum class SourceExecutionState { NOT_SUBMITTED, RUNNING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN }

/** 来源/传输层安全请求身份；不携带 headers、地址、SQL 或请求正文。 */
data class SourceRequestEvidence(val requestId: String?, val acceptance: SourceRequestAcceptance,
    val response: SourceResponseState, val execution: SourceExecutionState, val protocol: String?, val status: String?) {
    init {
        require(requestId == null || requestId.length in 1..256 && requestId.none { it.isISOControl() })
        require(protocol == null || protocol.matches(Regex("[A-Z][A-Z0-9_-]{0,31}")))
        require(status == null || status.matches(Regex("[A-Z0-9_.-]{1,64}")))
    }
}

/** 等待者停止等待不构成来源取消确认；Confirmed 必须来自来源回执。 */
sealed interface SourceCancellationEvidence {
    val kind: String
    object NotRequested : SourceCancellationEvidence { override val kind: String get() = "NOT_REQUESTED" }
    data class Unconfirmed(val reason: String) : SourceCancellationEvidence {
        override val kind: String get() = "UNCONFIRMED"
        init { require(reason.isNotBlank()) }
    }
    data class Confirmed(val authority: String, val receipt: String) : SourceCancellationEvidence {
        override val kind: String get() = "CONFIRMED"
        init { require(authority.isNotBlank() && receipt.isNotBlank()) }
    }
    data class Failed(val code: String, val reason: String) : SourceCancellationEvidence {
        override val kind: String get() = "FAILED"
        init { require(code.isNotBlank() && reason.isNotBlank()) }
    }
}

enum class SourceFailureCategory { CONNECTION, TRANSPORT, AUTHENTICATION, PERMISSION, SQL, CONTRACT, MATERIALIZATION, RESOURCE_LIMIT, CANCELLATION, UNKNOWN }

/** 只容纳已脱敏的来源事实；原始驱动文本与连接配置留在私有 cause 和日志。 */
data class SourceFailureEvidence(
    val category: SourceFailureCategory,
    val stage: String,
    val code: String,
    val safeMessage: String,
    val binding: SourceBindingRef,
    val operation: String,
    val capability: String?,
    val sqlState: String?,
    val nativeCode: Int?,
    val request: SourceRequestEvidence,
    val cancellation: SourceCancellationEvidence,
) {
    init {
        require(code.matches(Regex("[A-Z][A-Z0-9_]*")) && safeMessage.isNotBlank() && operation.isNotBlank())
        require(stage.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))) { "source failure stage must describe its actual controlled boundary" }
        require(capability == null || capability.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))) { "source capability must be a controlled identifier" }
        require((cancellation is SourceCancellationEvidence.Confirmed) == (request.execution == SourceExecutionState.CANCELLED)) {
            "source cancellation requires its matching confirmed terminal receipt"
        }
    }
}
