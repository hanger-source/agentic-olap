package com.olap.prototype.query.contract

import com.olap.prototype.engine.executable.ExecutableBusinessResult
import com.olap.prototype.query.QueryExplanationView
import com.olap.prototype.query.QueryExplanationContent
import com.olap.prototype.query.QueryStageAnalysis
import com.olap.prototype.warehouse.contract.*
import java.time.Instant

/** 保存字节表达真实执行事实，不要求重新执行历史脚本或来源查询。 */
data class QueryExecutionDiagnostics(val sql: String, val aliasMapping: Map<String,String>, val auditReport: String?)
data class SavedQueryResultRecord(val formatVersion: String, val runId: String, val publication: QueryCapabilityRequest,
    val catalogHash: String, val capabilityHash: String, val intent: QueryIntent, val queryReference: QueryReference?,
    val startedAt: Instant, val completedAt: Instant, val receipt: QueryExecutionReceipt, val explanation: QueryExplanationView?,
    val diagnostics: QueryExecutionDiagnostics?, val intentHash: String) {
    init { require(formatVersion == FORMAT && runId == receipt.runId && publication == intent.publication && intentHash.matches(Regex("[0-9a-f]{64}"))) { "saved result identity differs from its execution" } }
    companion object { const val FORMAT = "olap-query-result-record/v4" }
}
enum class ResultLengthUnit { VALUE, MEMBERS, UNICODE_CODE_POINTS }
enum class ResultMetadataSection { RUN, SOURCE_EVIDENCE, PUBLICATION, COLUMNS, INPUT_COLUMNS, SCOPE_INPUT_COLUMNS, SOURCE_SYMBOLS, QUERY_INTENT, EXPLANATION }
data class ResultRunSummary(val runId: String, val status: QueryExecutionStatus, val saved: Boolean, val rows: Long,
    val clientOutcome: QueryClientOutcome, val startedAt: Instant, val completedAt: Instant, val timingNanos: Map<String,Long>, val error: QueryExecutionFailure?)
data class ResultPublication(val publication: QueryCapabilityRequest, val catalogHash: String, val capabilityHash: String, val intentHash: String, val queryReference: QueryReference?)
data class ResultSourceEvidence(val state: SourceExecutionState, val request: SourceRequestEvidence?, val readEvidence: SourceReadEvidence?)
data class ResultScopeColumns(val scopePath: String, val columnCount: Int)
sealed interface ResultMetadataContent {
    data class Run(val value: ResultRunSummary) : ResultMetadataContent
    data class Source(val value: ResultSourceEvidence) : ResultMetadataContent
    data class Publication(val value: ResultPublication) : ResultMetadataContent
    data class Columns(val values: List<ExecutableBusinessResult.Column>) : ResultMetadataContent
    data class ScopeIndex(val values: List<ResultScopeColumns>) : ResultMetadataContent
    data class Symbols(val values: List<QueryStageAnalysis.Symbol>) : ResultMetadataContent
    data class Intent(val value: QueryIntent) : ResultMetadataContent
    data class Explanation(val value: QueryExplanationContent) : ResultMetadataContent
}
data class ResultMetadataPage(val resultReference: QueryResultReference, val section: ResultMetadataSection, val content: ResultMetadataContent,
    val offset: Int, val totalItems: Int, val nextOffset: Int?)
data class ResultValueReference(val rowIndex: Int, val columnId: String, val path: List<Int>, val sha256: String) {
    init { require(rowIndex >= 0 && columnId.isNotBlank() && path.all { it >= 0 } && sha256.matches(Regex("[0-9a-f]{64}"))) }
}
sealed interface ResultValueView {
    data class Complete(val value: ExecutableBusinessResult.Value) : ResultValueView
    data class Reference(val reference: ResultValueReference, val type: ExecutableBusinessResult.ValueKind, val length: Int, val lengthUnit: ResultLengthUnit) : ResultValueView
}
data class ResultPage(val resultReference: QueryResultReference, val run: ResultRunSummary, val publication: ResultPublication,
    val columns: List<ExecutableBusinessResult.Column>, val rows: List<List<ResultValueView>>, val offset: Int, val totalRows: Int, val nextOffset: Int?)
sealed interface ResultValuePageContent {
    data class Text(val text: String) : ResultValuePageContent
    data class Members(val values: List<ResultValueView>) : ResultValuePageContent
}
data class ResultValuePage(val resultReference: QueryResultReference, val valueReference: ResultValueReference, val type: ExecutableBusinessResult.ValueKind,
    val lengthUnit: ResultLengthUnit, val totalLength: Int, val offset: Int, val nextOffset: Int?, val content: ResultValuePageContent)
data class ResultValueCount(val value: ResultValueView, val count: Long)
data class ResultNumericExtrema(val minimum: ResultValueView, val maximum: ResultValueView)
data class ResultColumnProfile(val column: ExecutableBusinessResult.Column, val nullCount: Long, val nonNullCount: Long,
    val distinctEncodedNonNullValueCount: Long, val valueCounts: List<ResultValueCount>?, val numericExtrema: ResultNumericExtrema?)
data class ResultProfile(val resultReference: QueryResultReference, val totalRows: Long, val publication: ResultPublication, val run: ResultRunSummary, val columns: List<ResultColumnProfile>)

enum class QueryRunExecution { ACCEPTED, RUNNING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED }
enum class QueryRunDelivery { PENDING, DIRECT, SAVED, FAILED }
data class QueryResultManifest(val runId: String, val sha256: String, val bytes: Long, val rows: Long, val savedAt: Instant)
data class QueryRunView(val runId: String, val subject: String, val publication: QueryCapabilityRequest, val subjectAreaId: String, val subjectAreaVersion: Long,
    val catalogHash: String, val capabilityHash: String, val intentHash: String, val execution: QueryRunExecution, val delivery: QueryRunDelivery,
    val acceptedAt: Instant, val completedAt: Instant?, val historyEnabled: Boolean, val saveRequested: Boolean, val intent: QueryIntent?,
    val definition: QueryReference?, val question: String?, val executionError: String?, val deliveryError: String?, val result: QueryResultManifest?,
    val receipt: QueryExecutionReceipt?, val serviceObservation: QueryServiceObservation?)
data class QueryUsageNote(val id: String, val runId: String, val subject: String, val text: String, val createdAt: Instant)
data class QuerySemanticReference(val assetType: String, val id: String, val version: String)
data class QueryUsageView(val run: QueryRunView, val notes: List<QueryUsageNote>, val nextNoteOffset: Int?, val references: List<QuerySemanticReference>)
data class QueryUsagePage(val items: List<QueryRunView>, val nextOffset: Int?)
