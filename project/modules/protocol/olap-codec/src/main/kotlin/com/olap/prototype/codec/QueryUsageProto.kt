package com.olap.prototype.codec

import com.olap.prototype.query.contract.*
import java.time.Instant
import com.olap.prototype.wire.query.QueryRunView as WRun
import com.olap.prototype.wire.query.QueryResultManifest as WManifest
import com.olap.prototype.wire.query.QueryUsageNote as WNote
import com.olap.prototype.wire.query.QuerySemanticReference as WReference
import com.olap.prototype.wire.query.QueryUsageView as WView
import com.olap.prototype.wire.query.QueryUsagePage as WPage

object QueryUsageProto {
    fun toProto(v: QueryWorkerAcquisition): com.olap.prototype.wire.query.QueryWorkerAcquisition = com.olap.prototype.wire.query.QueryWorkerAcquisition.newBuilder().setKind(v.kind).setOutcome(v.outcome).setQueueNanos(v.queueNanos).setStartupNanos(v.startupNanos).build()
    fun fromProto(v: com.olap.prototype.wire.query.QueryWorkerAcquisition) = QueryWorkerAcquisition(v.kind, v.outcome, v.queueNanos, v.startupNanos)
    fun toProto(v: QueryServiceObservation): com.olap.prototype.wire.query.QueryServiceObservation = com.olap.prototype.wire.query.QueryServiceObservation.newBuilder().setScope(v.scope).putAllTimingNanos(v.timingNanos).addAllWorkers(v.workers.map(::toProto)).build()
    fun fromProto(v: com.olap.prototype.wire.query.QueryServiceObservation) = QueryServiceObservation(v.scope, v.timingNanosMap, v.workersList.map(::fromProto))
    fun toProto(v: QueryResultManifest): WManifest = WManifest.newBuilder().setRunId(v.runId).setSha256(v.sha256).setBytes(v.bytes).setRows(v.rows).setSavedAt(v.savedAt.toString()).build()
    fun fromProto(v: WManifest) = QueryResultManifest(v.runId, v.sha256, v.bytes, v.rows, Instant.parse(v.savedAt))
    fun toProto(v: QueryRunView): WRun = WRun.newBuilder().setRunId(v.runId).setSubject(v.subject).setPublication(QueryProto.toProto(v.publication))
        .setSubjectAreaId(v.subjectAreaId).setSubjectAreaVersion(v.subjectAreaVersion).setCatalogHash(v.catalogHash).setCapabilityHash(v.capabilityHash).setIntentHash(v.intentHash)
        .setExecution(v.execution.name).setDelivery(v.delivery.name).setAcceptedAt(v.acceptedAt.toString()).setHistoryEnabled(v.historyEnabled).setSaveRequested(v.saveRequested).apply {
            v.completedAt?.let { setCompletedAt(it.toString()) }; v.intent?.let { setIntent(QueryProto.toProto(it)) }; v.definition?.let { setDefinition(QueryProto.toProto(it)) }
            v.question?.let(::setQuestion); v.executionError?.let(::setExecutionError); v.deliveryError?.let(::setDeliveryError); v.result?.let { setResult(toProto(it)) }; v.receipt?.let { setReceipt(QueryProto.toProto(it)) }; v.serviceObservation?.let { setServiceObservation(toProto(it)) }
        }.build()
    fun fromProto(v: WRun): QueryRunView { require(v.hasPublication()); return QueryRunView(v.runId, v.subject, QueryProto.fromProto(v.publication), v.subjectAreaId, v.subjectAreaVersion,
        v.catalogHash, v.capabilityHash, v.intentHash, QueryRunExecution.valueOf(v.execution), QueryRunDelivery.valueOf(v.delivery), Instant.parse(v.acceptedAt), if (v.hasCompletedAt()) Instant.parse(v.completedAt) else null,
        v.historyEnabled, v.saveRequested, if (v.hasIntent()) QueryProto.fromProto(v.intent) else null, if (v.hasDefinition()) QueryProto.fromProto(v.definition) else null,
        if (v.hasQuestion()) v.question else null, if (v.hasExecutionError()) v.executionError else null, if (v.hasDeliveryError()) v.deliveryError else null,
        if (v.hasResult()) fromProto(v.result) else null, if (v.hasReceipt()) QueryProto.fromProto(v.receipt) else null, if (v.hasServiceObservation()) fromProto(v.serviceObservation) else null) }
    fun toProto(v: QueryUsageNote): WNote = WNote.newBuilder().setId(v.id).setRunId(v.runId).setSubject(v.subject).setText(v.text).setCreatedAt(v.createdAt.toString()).build()
    fun fromProto(v: WNote) = QueryUsageNote(v.id, v.runId, v.subject, v.text, Instant.parse(v.createdAt))
    fun toProto(v: QuerySemanticReference): WReference = WReference.newBuilder().setAssetType(v.assetType).setId(v.id).setVersion(v.version).build()
    fun fromProto(v: WReference) = QuerySemanticReference(v.assetType, v.id, v.version)
    fun toProto(v: QueryUsageView): WView = WView.newBuilder().setRun(toProto(v.run)).addAllNotes(v.notes.map(::toProto)).addAllReferences(v.references.map(::toProto)).apply { v.nextNoteOffset?.let(::setNextNoteOffset) }.build()
    fun fromProto(v: WView): QueryUsageView { require(v.hasRun()); return QueryUsageView(fromProto(v.run), v.notesList.map(::fromProto), if (v.hasNextNoteOffset()) v.nextNoteOffset else null, v.referencesList.map(::fromProto)) }
    fun toProto(v: QueryUsagePage): WPage = WPage.newBuilder().addAllItems(v.items.map(::toProto)).apply { v.nextOffset?.let(::setNextOffset) }.build()
    fun fromProto(v: WPage) = QueryUsagePage(v.itemsList.map(::fromProto), if (v.hasNextOffset()) v.nextOffset else null)
}
