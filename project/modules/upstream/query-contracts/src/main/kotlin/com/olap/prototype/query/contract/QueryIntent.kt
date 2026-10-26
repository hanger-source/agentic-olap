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
    companion object { const val FORMAT_VERSION = "olap-query-intent/v10" }
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
data class QueryValidationRequest(val query: QuerySelection, val executionMode: QueryExecutionMode = QueryExecutionMode.SOURCE) {
    companion object {
        fun contract(): Map<String, Any> = mapOf(
            "validation" to "AUTHORIZES_AND_COMPILES_THE_EXACT_QUERY_IN_THE_REQUESTED_EXECUTION_MODE_INCLUDING_NATIVE_PLANNING_AND_WORK_REDUCTION_SELECTION",
            "sourceExecution" to "NO_SOURCE_SQL_EXECUTION_NO_SOURCE_CAPABILITY_OR_GUARANTEE_VERIFICATION",
            "selectionDetails" to "USE_EXPLAIN_QUERY_SUMMARY_WITH_THE_SAME_QUERY_AND_EXECUTION_MODE_FOR_ACTUAL_SOURCE_OUTPUT_REPRESENTATION_AND_REDUCTION_DECISION",
        )
    }
}
data class QueryValidationResult(val valid: Boolean, val capabilityHash: String) {
    companion object {
        fun contract(): Map<String, Any> = mapOf(
            "valid" to "SUCCESSFUL_AUTHORIZATION_AND_COMPILATION_NOT_PROOF_OF_SOURCE_EXECUTION_OR_NUMERIC_GUARANTEES",
            "capabilityHash" to "EXACT_PUBLICATION_CAPABILITY_HASH_NOT_A_COMPILED_PLAN_OR_SOURCE_RESULT_REFERENCE",
        )
    }
}
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
