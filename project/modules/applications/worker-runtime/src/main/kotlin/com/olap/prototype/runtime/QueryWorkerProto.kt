package com.olap.prototype.runtime

import com.olap.prototype.codec.*
import com.olap.prototype.wire.query.EngineWorkerCommand as WEngineCommand
import com.olap.prototype.wire.query.EngineWorkerResult as WEngineResult
import com.olap.prototype.wire.query.QueryWorkerCommand as WQueryCommand
import com.olap.prototype.wire.query.QueryWorkerResult as WQueryResult
import com.olap.prototype.wire.query.EngineCatalogReference
import com.olap.prototype.wire.query.EngineInspect
import com.olap.prototype.wire.query.EngineExecute
import com.olap.prototype.wire.query.EngineCatalogReceipt
import com.olap.prototype.wire.query.EngineInputResolution as WResolution
import com.olap.prototype.wire.query.EngineExecutionPayload as WPayload
import com.olap.prototype.wire.query.EngineExecutionDiagnostics as WDiagnostics
import com.olap.prototype.wire.query.QueryUnload
import com.olap.prototype.wire.query.QueryCapabilityReceipt

/** 具体worker消息与实际请求/回执的一一转换；没有通用命令名或AST JSON。 */
object QueryWorkerProto {
    fun toProto(v: EngineWorkerCommand): WEngineCommand = WEngineCommand.newBuilder().apply { when (v) {
        is EngineWorkerCommand.Lookup -> lookup = EngineCatalogReference.newBuilder().setCatalogId(v.catalogId).setContentHash(v.contentHash).build()
        is EngineWorkerCommand.Load -> load = GovernanceProto.toProto(v.publication)
        is EngineWorkerCommand.Unload -> unload = EngineCatalogReference.newBuilder().setCatalogId(v.catalogId).setContentHash(v.contentHash).build()
        is EngineWorkerCommand.Inspect -> inspect = EngineInspect.newBuilder().setMode(v.mode.name).setCatalogId(v.catalogId).setRequestId(v.requestId).setIntent(QueryProto.toProto(v.intent)).setExecutionMode(QueryProto.toProto(v.executionMode)).build()
        is EngineWorkerCommand.Execute -> execute = EngineExecute.newBuilder().setCatalogId(v.catalogId).setRequestId(v.requestId).setIntent(QueryProto.toProto(v.intent))
            .addAllBindings(v.bindings.map(QuerySourceProto::toProto)).setExecutionMode(QueryProto.toProto(v.executionMode)).apply { v.readBasis?.let { setReadBasis(QuerySourceProto.toProto(it)) } }.build()
    } }.build()
    fun fromProto(v: WEngineCommand): EngineWorkerCommand = when (v.kindCase) {
        WEngineCommand.KindCase.LOOKUP -> EngineWorkerCommand.Lookup(v.lookup.catalogId, v.lookup.contentHash)
        WEngineCommand.KindCase.LOAD -> EngineWorkerCommand.Load(GovernanceProto.fromProto(v.load))
        WEngineCommand.KindCase.UNLOAD -> EngineWorkerCommand.Unload(v.unload.catalogId, v.unload.contentHash)
        WEngineCommand.KindCase.INSPECT -> v.inspect.let { require(it.hasIntent()); EngineWorkerCommand.Inspect(InspectionMode.valueOf(it.mode), it.catalogId, it.requestId, QueryProto.fromProto(it.intent), QueryProto.fromProto(it.executionMode)) }
        WEngineCommand.KindCase.EXECUTE -> v.execute.let { require(it.hasIntent()); EngineWorkerCommand.Execute(it.catalogId, it.requestId, QueryProto.fromProto(it.intent), it.bindingsList.map(QuerySourceProto::fromProto), if (it.hasReadBasis()) QuerySourceProto.fromProto(it.readBasis) else null, QueryProto.fromProto(it.executionMode)) }
        else -> error("engine worker command requires one explicit variant")
    }
    fun toProto(v: EngineInputResolution): WResolution = WResolution.newBuilder().setPublication(QueryProto.toProto(v.publication)).setRequestId(v.requestId).setCatalogId(v.catalogId)
        .setCatalogHash(v.catalogHash).setIntentHash(v.intentHash).setBinding(QuerySourceProto.toProto(v.binding)).setConnectorRef(SemanticProto.toProto(v.connectorRef))
        .putAllTimingNanos(v.timingNanos).apply { v.explanation?.let { setExplanation(QueryExplanationProto.toProto(it)) } }.build()
    fun fromProto(v: WResolution): EngineInputResolution { require(v.hasPublication() && v.hasBinding() && v.hasConnectorRef()); return EngineInputResolution(QueryProto.fromProto(v.publication), v.requestId,
        v.catalogId, v.catalogHash, v.intentHash, QuerySourceProto.fromProto(v.binding), SemanticProto.fromProto(v.connectorRef), v.timingNanosMap.toMap(), if (v.hasExplanation()) QueryExplanationProto.fromProto(v.explanation) else null) }
    fun toProto(v: EngineExecutionDiagnostics): WDiagnostics = WDiagnostics.newBuilder().setSql(v.sql).putAllAliasMapping(v.aliasMapping).apply { v.auditReport?.let(::setAuditReport) }.build()
    fun fromProto(v: WDiagnostics) = EngineExecutionDiagnostics(v.sql, v.aliasMappingMap.toMap(), if (v.hasAuditReport()) v.auditReport else null)
    fun toProto(v: EngineExecutionPayload): WPayload = WPayload.newBuilder().setPublication(QueryProto.toProto(v.publication)).setRequestId(v.requestId).setResult(QueryResultProto.toProto(v.result))
        .setSourceReadEvidence(QuerySourceProto.toProto(v.sourceReadEvidence)).putAllStageNanos(v.stageNanos).setDiagnostics(toProto(v.diagnostics)).setExplanation(QueryExplanationProto.toProto(v.explanation)).build()
    fun fromProto(v: WPayload): EngineExecutionPayload { require(v.hasPublication() && v.hasResult() && v.hasSourceReadEvidence() && v.hasDiagnostics() && v.hasExplanation());
        return EngineExecutionPayload(QueryProto.fromProto(v.publication), v.requestId, QueryResultProto.fromProto(v.result), QuerySourceProto.fromProto(v.sourceReadEvidence), v.stageNanosMap.toMap(), fromProto(v.diagnostics), QueryExplanationProto.fromProto(v.explanation)) }
    fun toProto(v: EngineWorkerResult): WEngineResult = WEngineResult.newBuilder().apply { when (v) {
        is EngineWorkerResult.Present -> present = v.value
        is EngineWorkerResult.Loaded -> loaded = EngineCatalogReceipt.newBuilder().setCatalogId(v.catalogId).setContentHash(v.contentHash).build()
        is EngineWorkerResult.Unloaded -> unloaded = EngineCatalogReceipt.newBuilder().setCatalogId(v.catalogId).setContentHash(v.contentHash).build()
        is EngineWorkerResult.Resolved -> resolved = toProto(v.value)
        is EngineWorkerResult.Executed -> executed = toProto(v.value)
    } }.build()
    fun fromProto(v: WEngineResult): EngineWorkerResult = when (v.kindCase) {
        WEngineResult.KindCase.PRESENT -> EngineWorkerResult.Present(v.present)
        WEngineResult.KindCase.LOADED -> EngineWorkerResult.Loaded(v.loaded.catalogId, v.loaded.contentHash)
        WEngineResult.KindCase.UNLOADED -> EngineWorkerResult.Unloaded(v.unloaded.catalogId, v.unloaded.contentHash)
        WEngineResult.KindCase.RESOLVED -> EngineWorkerResult.Resolved(fromProto(v.resolved))
        WEngineResult.KindCase.EXECUTED -> EngineWorkerResult.Executed(fromProto(v.executed))
        else -> error("engine worker result requires one explicit variant")
    }
    fun toProto(v: QueryWorkerCommand): WQueryCommand = WQueryCommand.newBuilder().apply { when (v) {
        is QueryWorkerCommand.Lookup -> lookup = com.olap.prototype.wire.query.WorkerPublicationIdentity.newBuilder()
            .setCoordinate(QueryProto.toProto(v.coordinate)).setCatalogHash(v.catalogHash).setCapabilityHash(v.capabilityHash).setSemanticCatalogHash(v.semanticCatalogHash).build()
        is QueryWorkerCommand.Load -> load = GovernanceProto.toProto(v.entry)
        is QueryWorkerCommand.Unload -> unload = QueryUnload.newBuilder().setCoordinate(QueryProto.toProto(v.coordinate)).setCatalogHash(v.catalogHash).setCapabilityHash(v.capabilityHash).build()
        is QueryWorkerCommand.Validate -> validate = QueryProto.toProto(v.intent)
    } }.build()
    fun fromProto(v: WQueryCommand): QueryWorkerCommand = when (v.kindCase) {
        WQueryCommand.KindCase.LOOKUP -> v.lookup.let { require(it.hasCoordinate()); QueryWorkerCommand.Lookup(QueryProto.fromProto(it.coordinate), it.catalogHash, it.capabilityHash, it.semanticCatalogHash) }
        WQueryCommand.KindCase.LOAD -> QueryWorkerCommand.Load(GovernanceProto.fromProto(v.load))
        WQueryCommand.KindCase.UNLOAD -> v.unload.let { require(it.hasCoordinate()); QueryWorkerCommand.Unload(QueryProto.fromProto(it.coordinate), it.catalogHash, it.capabilityHash) }
        WQueryCommand.KindCase.VALIDATE -> QueryWorkerCommand.Validate(QueryProto.fromProto(v.validate))
        else -> error("query worker command requires one explicit variant")
    }
    fun toProto(v: QueryWorkerResult): WQueryResult = WQueryResult.newBuilder().apply { when (v) {
        is QueryWorkerResult.Present -> present = v.value
        is QueryWorkerResult.Loaded -> loaded = QueryCapabilityReceipt.newBuilder().setCoordinate(QueryProto.toProto(v.coordinate)).setCatalogHash(v.catalogHash).setCapabilityHash(v.capabilityHash).build()
        is QueryWorkerResult.Unloaded -> unloaded = QueryProto.toProto(v.coordinate)
        is QueryWorkerResult.Validated -> validatedCapabilityHash = v.capabilityHash
    } }.build()
    fun fromProto(v: WQueryResult): QueryWorkerResult = when (v.kindCase) {
        WQueryResult.KindCase.PRESENT -> QueryWorkerResult.Present(v.present)
        WQueryResult.KindCase.LOADED -> v.loaded.let { require(it.hasCoordinate()); QueryWorkerResult.Loaded(QueryProto.fromProto(it.coordinate), it.catalogHash, it.capabilityHash) }
        WQueryResult.KindCase.UNLOADED -> QueryWorkerResult.Unloaded(QueryProto.fromProto(v.unloaded))
        WQueryResult.KindCase.VALIDATED_CAPABILITY_HASH -> QueryWorkerResult.Validated(v.validatedCapabilityHash)
        else -> error("query worker result requires one explicit variant")
    }
}
