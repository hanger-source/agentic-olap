package com.olap.prototype.codec

import com.olap.prototype.query.contract.*
import com.olap.prototype.query.definition.QueryDefinitionCommand
import com.olap.prototype.dsl.*
import java.time.Instant
import com.olap.prototype.wire.query.QueryIntent as WIntent
import com.olap.prototype.wire.query.QueryCapabilityCoordinate as WCoordinate
import com.olap.prototype.wire.query.QueryReference as WReference
import com.olap.prototype.wire.query.QuerySelection as WSelection
import com.olap.prototype.wire.query.QueryResultReference as WResultReference
import com.olap.prototype.wire.query.QueryExecutionRequest as WExecutionRequest
import com.olap.prototype.wire.query.QueryExecutionMode as WExecutionMode
import com.olap.prototype.query.QueryExecutionMode
import com.olap.prototype.wire.query.QueryExecutionReceipt as WReceipt
import com.olap.prototype.wire.query.QueryExecutionFailure as WFailure
import com.olap.prototype.wire.query.QueryValidationRequest as WValidationRequest
import com.olap.prototype.wire.query.QueryValidationResult as WValidationResult
import com.olap.prototype.wire.query.QueryExplanationRequest as WExplanationRequest
import com.olap.prototype.wire.query.QueryExplanationResult as WExplanationResult
import com.olap.prototype.wire.query.QuerySource as WSource
import com.olap.prototype.wire.query.QueryDefinitionCommand as WDefinitionCommand
import com.olap.prototype.wire.query.QueryDefinitionView as WDefinitionView
import com.olap.prototype.wire.query.QueryDefinitionResult as WDefinitionResult
import com.olap.prototype.wire.query.QueryDefinitionPage as WDefinitionPage
import com.olap.prototype.wire.query.QueryVersionRequest as WVersionRequest
import com.olap.prototype.wire.query.QueryListRequest as WListRequest

/** 完整查询的正式 Protobuf 边界；只转换结构，领域构造器继续核验业务合同。 */
object QueryProto {
    fun toProto(v: QueryCapabilityRequest): WCoordinate = WCoordinate.newBuilder().setScenarioId(v.scenarioId)
        .setScenarioVersion(v.scenarioVersion).setReleaseId(v.releaseId).setReleaseVersion(v.releaseVersion).build()
    fun fromProto(v: WCoordinate) = QueryCapabilityRequest(v.scenarioId, v.scenarioVersion, v.releaseId, v.releaseVersion)
    fun toProto(v: QueryIntent): WIntent = WIntent.newBuilder().setFormatVersion(v.formatVersion).setPublication(toProto(v.publication))
        .setComputation(SemanticProto.toProto(v.computation)).build()
    fun fromProto(v: WIntent): QueryIntent {
        require(v.formatVersion == QueryIntent.FORMAT_VERSION && v.hasPublication() && v.hasComputation()) { "complete supported query intent is required" }
        return QueryIntent(fromProto(v.publication), SemanticProto.fromProto(v.computation))
    }
    fun digest(v: QueryIntent): String = toProto(v).let { ProtoBytes.digest(ProtoBytes.encode(it, it.serializedSize)) }
    fun toProto(v: QueryReference): WReference = WReference.newBuilder().setDefinitionId(v.definitionId).setDefinitionVersion(v.definitionVersion).setSha256(v.sha256).build()
    fun fromProto(v: WReference) = QueryReference(v.definitionId, v.definitionVersion, v.sha256)
    fun toProto(v: QuerySelection): WSelection = WSelection.newBuilder().apply { when (v) {
        is QuerySelection.Inline -> inline = toProto(v.intent)
        is QuerySelection.Saved -> saved = toProto(v.reference)
    } }.build()
    fun fromProto(v: WSelection): QuerySelection = when (v.kindCase) {
        WSelection.KindCase.INLINE -> QuerySelection.Inline(fromProto(v.inline))
        WSelection.KindCase.SAVED -> QuerySelection.Saved(fromProto(v.saved))
        else -> error("query selection requires one explicit variant")
    }
    fun toProto(v: QueryResultReference): WResultReference = WResultReference.newBuilder().setRunId(v.runId).setSha256(v.sha256).build()
    fun fromProto(v: WResultReference) = QueryResultReference(v.runId, v.sha256)
    fun toProto(v: QueryExecutionMode): WExecutionMode = when (v) {
        QueryExecutionMode.SOURCE -> WExecutionMode.QUERY_EXECUTION_SOURCE
        QueryExecutionMode.CORE_FINALIZATION -> WExecutionMode.QUERY_EXECUTION_CORE_FINALIZATION
    }
    fun fromProto(v: WExecutionMode): QueryExecutionMode = when (v) {
        WExecutionMode.QUERY_EXECUTION_SOURCE -> QueryExecutionMode.SOURCE
        WExecutionMode.QUERY_EXECUTION_CORE_FINALIZATION -> QueryExecutionMode.CORE_FINALIZATION
        WExecutionMode.UNRECOGNIZED -> error("unknown query execution mode")
    }
    fun toProto(v: QueryExecutionRequest): WExecutionRequest = WExecutionRequest.newBuilder().setQuery(toProto(v.query)).setSaveResult(v.saveResult).setExecutionMode(toProto(v.executionMode))
        .apply { v.question?.let(::setQuestion); v.readBasis?.let { setReadBasis(QuerySourceProto.toProto(it)) } }.build()
    fun fromProto(v: WExecutionRequest): QueryExecutionRequest {
        require(v.hasQuery()) { "query selection is required" }
        return QueryExecutionRequest(fromProto(v.query), v.saveResult, if (v.hasQuestion()) v.question else null,
            if (v.hasReadBasis()) QuerySourceProto.fromProto(v.readBasis) else null, fromProto(v.executionMode))
    }
    fun toProto(v: QueryValidationRequest): WValidationRequest = WValidationRequest.newBuilder().setQuery(toProto(v.query)).setExecutionMode(toProto(v.executionMode)).build()
    fun fromProto(v: WValidationRequest): QueryValidationRequest { require(v.hasQuery()); return QueryValidationRequest(fromProto(v.query), fromProto(v.executionMode)) }
    fun toProto(v: QueryValidationResult): WValidationResult = WValidationResult.newBuilder().setValid(v.valid).setCapabilityHash(v.capabilityHash).build()
    fun fromProto(v: WValidationResult) = QueryValidationResult(v.valid, v.capabilityHash)
    fun toProto(v: QueryExplanationRequest): WExplanationRequest = WExplanationRequest.newBuilder().setQuery(toProto(v.query)).setSection(v.section.name)
        .setOffset(v.offset).setLimit(v.limit).setExecutionMode(toProto(v.executionMode)).build()
    fun fromProto(v: WExplanationRequest): QueryExplanationRequest { require(v.hasQuery()); return QueryExplanationRequest(fromProto(v.query), QueryExplanationSection.valueOf(v.section), v.offset, v.limit, fromProto(v.executionMode)) }
    fun toProto(v: QueryExplanationResult): WExplanationResult = WExplanationResult.newBuilder().setSection(v.section.name).setContent(QueryExplanationProto.toProto(v.content)).build()
    fun fromProto(v: WExplanationResult): QueryExplanationResult { require(v.hasContent()); return QueryExplanationResult(QueryExplanationSection.valueOf(v.section), QueryExplanationProto.fromProto(v.content)) }
    fun toProto(v: QueryExecutionFailure): WFailure = WFailure.newBuilder().setStage(v.stage).setCode(v.code).setMessage(v.message)
        .addAllFieldPath(v.fieldPath).apply { v.diagnosticId?.let(::setDiagnosticId) }.build()
    fun fromProto(v: WFailure) = QueryExecutionFailure(v.stage, v.code, v.message, if (v.hasDiagnosticId()) v.diagnosticId else null, v.fieldPathList.toList())
    fun toProto(v: QueryExecutionReceipt): WReceipt = WReceipt.newBuilder().setRunId(v.runId).setStatus(v.status.name).setSaved(v.saved).setRows(v.rows)
        .setClientOutcome(v.clientOutcome.name).setSourceState(v.sourceState.name).setExecutionMode(toProto(v.executionMode)).putAllTimingNanos(v.timingNanos).apply {
            v.resultReference?.let { setResultReference(toProto(it)) }; v.result?.let { setResult(QueryResultProto.toProto(it)) }
            v.sourceRequest?.let { setSourceRequest(QuerySourceProto.toProto(it)) }; v.sourceReadEvidence?.let { setSourceReadEvidence(QuerySourceProto.toProto(it)) }
            v.error?.let { setError(toProto(it)) }
        }.build()
    fun fromProto(v: WReceipt) = QueryExecutionReceipt(v.runId, QueryExecutionStatus.valueOf(v.status),
        if (v.hasResultReference()) fromProto(v.resultReference) else null, if (v.hasResult()) QueryResultProto.fromProto(v.result) else null,
        v.saved, v.rows, QueryClientOutcome.valueOf(v.clientOutcome), com.olap.prototype.warehouse.contract.SourceExecutionState.valueOf(v.sourceState),
        if (v.hasSourceRequest()) QuerySourceProto.fromProto(v.sourceRequest) else null,
        if (v.hasSourceReadEvidence()) QuerySourceProto.fromProto(v.sourceReadEvidence) else null, v.timingNanosMap.toMap(), if (v.hasError()) fromProto(v.error) else null, fromProto(v.executionMode))
    fun toProto(v: QuerySource): WSource = WSource.newBuilder().setName(v.name).setSource(v.source).setDslApiVersion(v.dslApiVersion).setSha256(v.sha256).build()
    fun fromProto(v: WSource) = QuerySource(v.name, v.source, v.dslApiVersion, v.sha256)
    fun toProto(v: QueryDefinitionCommand): WDefinitionCommand = WDefinitionCommand.newBuilder().setCapabilityHash(v.capabilityHash).setName(v.name)
        .setIntent(toProto(v.intent)).apply { v.definitionId?.let(::setDefinitionId); v.sourceVersion?.let(::setSourceVersion); v.source?.let { setSource(toProto(it)) } }.build()
    fun fromProto(v: WDefinitionCommand): QueryDefinitionCommand { require(v.hasIntent()); return QueryDefinitionCommand(if (v.hasDefinitionId()) v.definitionId else null,
        if (v.hasSourceVersion()) v.sourceVersion else null, v.capabilityHash, v.name, fromProto(v.intent), if (v.hasSource()) fromProto(v.source) else null) }
    fun toProto(v: QueryDefinitionView): WDefinitionView = WDefinitionView.newBuilder().setDefinitionId(v.definitionId).setDefinitionVersion(v.definitionVersion)
        .setName(v.name).setScenarioId(v.scenarioId).setScenarioVersion(v.scenarioVersion).setScenarioDisplayName(v.scenarioDisplayName)
        .setReleaseId(v.releaseId).setReleaseVersion(v.releaseVersion).setReleaseDisplayName(v.releaseDisplayName).setCatalogHash(v.catalogHash)
        .setSubjectAreaId(v.subjectAreaId).setSubjectAreaVersion(v.subjectAreaVersion).setCapabilityHash(v.capabilityHash).setIntentHash(v.intentHash)
        .setIntent(toProto(v.intent)).setCreatedAt(v.createdAt.toString()).apply { v.source?.let { setSource(toProto(it)) } }.build()
    fun fromProto(v: WDefinitionView): QueryDefinitionView { require(v.hasIntent()); return QueryDefinitionView(v.definitionId, v.definitionVersion, v.name,
        v.scenarioId, v.scenarioVersion, v.scenarioDisplayName, v.releaseId, v.releaseVersion, v.releaseDisplayName, v.catalogHash,
        v.subjectAreaId, v.subjectAreaVersion, v.capabilityHash, v.intentHash, fromProto(v.intent), Instant.parse(v.createdAt), if (v.hasSource()) fromProto(v.source) else null) }
    fun toProto(v: QueryDefinitionResult): WDefinitionResult = WDefinitionResult.newBuilder().setDefinition(toProto(v.definition)).setReference(toProto(v.reference)).build()
    fun fromProto(v: WDefinitionResult): QueryDefinitionResult { require(v.hasDefinition() && v.hasReference()); return QueryDefinitionResult(fromProto(v.definition),fromProto(v.reference)) }
    fun toProto(v: QueryVersionRequest): WVersionRequest = WVersionRequest.newBuilder().setQueryReference(toProto(v.queryReference)).build()
    fun fromProto(v: WVersionRequest): QueryVersionRequest { require(v.hasQueryReference()); return QueryVersionRequest(fromProto(v.queryReference)) }
    fun toProto(v: QueryListRequest): WListRequest = WListRequest.newBuilder().setCoordinate(toProto(v.coordinate)).setOffset(v.offset).setLimit(v.limit).build()
    fun fromProto(v: WListRequest): QueryListRequest { require(v.hasCoordinate()); return QueryListRequest(fromProto(v.coordinate), v.offset, v.limit) }
    fun toProto(v: QueryDefinitionPage): WDefinitionPage = WDefinitionPage.newBuilder().addAllItems(v.items.map(::toProto)).apply { v.nextOffset?.let(::setNextOffset) }.build()
    fun fromProto(v: WDefinitionPage) = QueryDefinitionPage(v.itemsList.map(::fromProto), if (v.hasNextOffset()) v.nextOffset else null)
    fun scriptValue(v: ScriptValue) = QueryScriptProto.toProto(v)
}
