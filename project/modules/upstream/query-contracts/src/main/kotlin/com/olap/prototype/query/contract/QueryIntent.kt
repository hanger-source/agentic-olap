package com.olap.prototype.query.contract

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.query.QueryExecutionMode
import com.olap.prototype.engine.executable.ExecutableBusinessResult
import com.olap.prototype.warehouse.contract.SourceReadBasis
import com.olap.prototype.warehouse.contract.SourceReadEvidence
import com.olap.prototype.warehouse.contract.SourceRequestEvidence
import com.olap.prototype.warehouse.contract.SourceExecutionState

/** 发布身份与本次完整计算；不携带可执行闭包或来源凭据。 */
data class QueryIntent(val publication: QueryCapabilityRequest, val computation: Calculation.Scope) {
    val formatVersion: String get() = FORMAT_VERSION
    companion object { const val FORMAT_VERSION = "olap-query-intent/v9" }
}

data class QueryReference(val definitionId: String, val definitionVersion: Long, val sha256: String) {
    init { require(definitionId.isNotBlank() && definitionVersion > 0 && sha256.matches(Regex("[0-9a-f]{64}"))) { "exact saved query reference required" } }
}

sealed interface QuerySelection {
    data class Inline(val intent: QueryIntent) : QuerySelection
    data class Saved(val reference: QueryReference) : QuerySelection
}

data class QueryExecutionRequest(val query: QuerySelection, val saveResult: Boolean, val question: String?, val readBasis: SourceReadBasis.Versioned?,
    val executionMode: QueryExecutionMode = QueryExecutionMode.SOURCE)
data class QueryValidationRequest(val query: QuerySelection, val executionMode: QueryExecutionMode = QueryExecutionMode.SOURCE)
data class QueryValidationResult(val valid: Boolean, val capabilityHash: String)
data class QueryResultReference(val runId: String, val sha256: String) {
    init { require(runId.matches(Regex("run-[0-9a-f]{32}")) && sha256.matches(Regex("[0-9a-f]{64}"))) }
}
enum class QueryExecutionStatus { SUCCEEDED, ENGINE_FAILED, EXECUTION_FAILED, CANCELLED, NOT_EXECUTED, OUTCOME_UNKNOWN }
enum class QueryClientOutcome { COMPLETED, CANCELLED, DEADLINE_EXCEEDED, INTERRUPTED }
data class QueryExecutionFailure(val stage: String, val code: String, val message: String, val diagnosticId: String?, val fieldPath: List<String>)
data class QueryExecutionReceipt(val runId: String, val status: QueryExecutionStatus, val resultReference: QueryResultReference?,
    val result: ExecutableBusinessResult?,
    val saved: Boolean, val rows: Long, val clientOutcome: QueryClientOutcome, val sourceState: SourceExecutionState,
    val sourceRequest: SourceRequestEvidence?, val sourceReadEvidence: SourceReadEvidence?, val timingNanos: Map<String, Long>,
    val error: QueryExecutionFailure?, val executionMode: QueryExecutionMode = QueryExecutionMode.SOURCE)

/** 正式保存完整模型，源码仅为诊断关联，不参与查询身份。 */
data class QuerySource(val name: String, val source: String, val dslApiVersion: String, val sha256: String) {
    init { require(name.isNotBlank() && dslApiVersion.isNotBlank() && sha256.matches(Regex("[0-9a-f]{64}"))) }
}
