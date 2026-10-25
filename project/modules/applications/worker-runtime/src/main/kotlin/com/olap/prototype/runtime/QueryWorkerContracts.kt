package com.olap.prototype.runtime

import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.engine.executable.ExecutableBusinessResult
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.QueryExplanationView
import com.olap.prototype.query.QueryExecutionMode
import com.olap.prototype.warehouse.contract.*

/** 只描述跨进程实际工作，不复制语义AST或来源选择规则。 */
sealed interface EngineWorkerCommand {
    data class Lookup(val catalogId: String, val contentHash: String) : EngineWorkerCommand
    data class Load(val publication: PublishedExecutableCatalog) : EngineWorkerCommand
    data class Unload(val catalogId: String, val contentHash: String) : EngineWorkerCommand
    data class Inspect(val mode: InspectionMode, val catalogId: String, val requestId: String, val intent: QueryIntent,
        val executionMode: QueryExecutionMode = QueryExecutionMode.SOURCE) : EngineWorkerCommand
    data class Execute(val catalogId: String, val requestId: String, val intent: QueryIntent,
        val bindings: List<SourceBindingRef>, val readBasis: SourceReadBasis.Versioned?,
        val executionMode: QueryExecutionMode = QueryExecutionMode.SOURCE) : EngineWorkerCommand
}
enum class InspectionMode { RESOLVE, VALIDATE, EXPLAIN }
sealed interface EngineWorkerResult {
    data class Present(val value: Boolean) : EngineWorkerResult
    data class Loaded(val catalogId: String, val contentHash: String) : EngineWorkerResult
    data class Unloaded(val catalogId: String, val contentHash: String) : EngineWorkerResult
    data class Resolved(val value: EngineInputResolution) : EngineWorkerResult
    data class Executed(val value: EngineExecutionPayload) : EngineWorkerResult
}
data class EngineInputResolution(val publication: QueryCapabilityRequest, val requestId: String, val catalogId: String,
    val catalogHash: String, val intentHash: String, val binding: SourceBindingRef, val connectorRef: ConnectorRef,
    val timingNanos: Map<String, Long>, val explanation: QueryExplanationView?)
data class EngineExecutionDiagnostics(val sql: String, val aliasMapping: Map<String, String>, val auditReport: String?)
data class EngineExecutionPayload(val publication: QueryCapabilityRequest, val requestId: String, val result: ExecutableBusinessResult,
    val sourceReadEvidence: SourceReadEvidence, val stageNanos: Map<String, Long>, val diagnostics: EngineExecutionDiagnostics,
    val explanation: QueryExplanationView)

sealed interface QueryWorkerCommand {
    data class Lookup(val coordinate: QueryCapabilityRequest, val catalogHash: String, val capabilityHash: String, val semanticCatalogHash: String) : QueryWorkerCommand
    data class Load(val entry: PublishedQueryEntry) : QueryWorkerCommand
    data class Unload(val coordinate: QueryCapabilityRequest, val catalogHash: String, val capabilityHash: String) : QueryWorkerCommand
    data class Validate(val intent: QueryIntent) : QueryWorkerCommand
}
sealed interface QueryWorkerResult {
    data class Present(val value: Boolean) : QueryWorkerResult
    data class Loaded(val coordinate: QueryCapabilityRequest, val catalogHash: String, val capabilityHash: String) : QueryWorkerResult
    data class Unloaded(val coordinate: QueryCapabilityRequest) : QueryWorkerResult
    data class Validated(val capabilityHash: String) : QueryWorkerResult
}
