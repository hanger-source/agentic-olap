package com.olap.prototype.codec

import com.olap.prototype.dsl.*
import com.olap.prototype.query.contract.ResultValueReference
import com.olap.prototype.query.contract.ResultMetadataSection
import com.olap.prototype.query.contract.QueryExplanationSection
import com.olap.prototype.wire.query.ResultReadRequest as WRead
import com.olap.prototype.wire.query.ResultMetadataRequest as WMetadata
import com.olap.prototype.wire.query.ResultValueReference as WValueReference
import com.olap.prototype.wire.query.ResultValueRequest as WValue
import com.olap.prototype.wire.query.ResultProfileRequest as WProfile
import com.olap.prototype.wire.query.QueryRunRequest as WRun
import com.olap.prototype.wire.query.QueryRunListRequest as WRuns
import com.olap.prototype.wire.query.QueryUsageNoteRequest as WNote

object QueryReadProto {
    fun toProto(v: ResultReadRequest): WRead = WRead.newBuilder().setResultReference(QueryProto.toProto(v.resultReference)).setOffset(v.offset).setLimit(v.limit)
        .apply { v.columnIds?.let { setColumnIds(com.olap.prototype.wire.semantic.StringList.newBuilder().addAllValues(it)) } }.build()
    fun fromProto(v: WRead): ResultReadRequest { require(v.hasResultReference()); return ResultReadRequest(QueryProto.fromProto(v.resultReference), v.offset, v.limit, if (v.hasColumnIds()) v.columnIds.valuesList.toList() else null) }
    fun toProto(v: ResultMetadataRequest): WMetadata = WMetadata.newBuilder().setResultReference(QueryProto.toProto(v.resultReference))
        .setSection(v.section.name).setOffset(v.offset).setLimit(v.limit).apply { v.scopePath?.let(::setScopePath); v.explanationSection?.let { setExplanationSection(it.name) } }.build()
    fun fromProto(v: WMetadata): ResultMetadataRequest { require(v.hasResultReference()); return ResultMetadataRequest(QueryProto.fromProto(v.resultReference), ResultMetadataSection.valueOf(v.section),
        if (v.hasScopePath()) v.scopePath else null, if (v.hasExplanationSection()) QueryExplanationSection.valueOf(v.explanationSection) else null, v.offset, v.limit) }
    fun toProto(v: ResultValueReference): WValueReference = WValueReference.newBuilder().setRowIndex(v.rowIndex).setColumnId(v.columnId).addAllPath(v.path).setSha256(v.sha256).build()
    fun fromProto(v: WValueReference) = ResultValueReference(v.rowIndex, v.columnId, v.pathList.toList(), v.sha256)
    fun toProto(v: ResultValueRequest): WValue = WValue.newBuilder().setResultReference(QueryProto.toProto(v.resultReference)).setValueReference(toProto(v.valueReference)).setOffset(v.offset).setLimit(v.limit).build()
    fun fromProto(v: WValue): ResultValueRequest { require(v.hasResultReference() && v.hasValueReference()); return ResultValueRequest(QueryProto.fromProto(v.resultReference), fromProto(v.valueReference), v.offset, v.limit) }
    fun toProto(v: ResultProfileRequest): WProfile = WProfile.newBuilder().setResultReference(QueryProto.toProto(v.resultReference)).addAllColumnIds(v.columnIds).build()
    fun fromProto(v: WProfile): ResultProfileRequest { require(v.hasResultReference()); return ResultProfileRequest(QueryProto.fromProto(v.resultReference), v.columnIdsList.toList()) }
    fun toProto(v: QueryRunRequest): WRun = WRun.newBuilder().setRunId(v.runId).setOffset(v.offset).setLimit(v.limit).build()
    fun fromProto(v: WRun) = QueryRunRequest(v.runId, v.offset, v.limit)
    fun toProto(v: QueryRunListRequest): WRuns = WRuns.newBuilder().setCoordinate(QueryProto.toProto(v.coordinate)).setOffset(v.offset).setLimit(v.limit).apply { v.text?.let(::setText) }.build()
    fun fromProto(v: WRuns): QueryRunListRequest { require(v.hasCoordinate()); return QueryRunListRequest(QueryProto.fromProto(v.coordinate), if (v.hasText()) v.text else null, v.offset, v.limit) }
    fun toProto(v: QueryUsageNoteRequest): WNote = WNote.newBuilder().setRunId(v.runId).setText(v.text).build()
    fun fromProto(v: WNote) = QueryUsageNoteRequest(v.runId, v.text)
}
