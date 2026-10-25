package com.olap.prototype.codec

import com.olap.prototype.query.contract.*
import com.olap.prototype.wire.query.SavedQueryResultRecord as WRecord
import com.olap.prototype.wire.query.SavedQueryResultArtifact as WArtifact
import com.olap.prototype.wire.query.QueryExecutionDiagnostics as WDiagnostics
import java.time.Instant

/** 官方Protobuf结果资产：外封套保护精确记录字节；不会重新运行查询。 */
object QueryArtifactProto {
    const val FORMAT = "olap-query-result-artifact/v5"
    fun toProto(v: QueryExecutionDiagnostics): WDiagnostics = WDiagnostics.newBuilder().setSql(v.sql).putAllAliasMapping(v.aliasMapping).apply { v.auditReport?.let(::setAuditReport) }.build()
    fun fromProto(v: WDiagnostics) = QueryExecutionDiagnostics(v.sql, v.aliasMappingMap.toMap(), if (v.hasAuditReport()) v.auditReport else null)
    fun toProto(v: SavedQueryResultRecord): WRecord = WRecord.newBuilder().setFormatVersion(v.formatVersion).setRunId(v.runId).setPublication(QueryProto.toProto(v.publication))
        .setCatalogHash(v.catalogHash).setCapabilityHash(v.capabilityHash).setIntentHash(v.intentHash).setIntent(QueryProto.toProto(v.intent)).setStartedAt(v.startedAt.toString()).setCompletedAt(v.completedAt.toString())
        .setReceipt(QueryProto.toProto(v.receipt)).apply { v.queryReference?.let { setQueryReference(QueryProto.toProto(it)) }; v.explanation?.let { setExplanation(QueryExplanationProto.toProto(it)) }; v.diagnostics?.let { setDiagnostics(toProto(it)) } }.build()
    fun fromProto(v: WRecord): SavedQueryResultRecord {
        if (v.formatVersion != SavedQueryResultRecord.FORMAT)
            throw QueryResultFormatException(QueryResultFormatException.Component.RECORD, v.formatVersion, SavedQueryResultRecord.FORMAT)
        require(v.hasPublication() && v.hasIntent() && v.hasReceipt())
        return SavedQueryResultRecord(v.formatVersion, v.runId,
            QueryProto.fromProto(v.publication), v.catalogHash, v.capabilityHash, QueryProto.fromProto(v.intent), if (v.hasQueryReference()) QueryProto.fromProto(v.queryReference) else null,
            Instant.parse(v.startedAt), Instant.parse(v.completedAt), QueryProto.fromProto(v.receipt), if (v.hasExplanation()) QueryExplanationProto.fromProto(v.explanation) else null,
            if (v.hasDiagnostics()) fromProto(v.diagnostics) else null, v.intentHash)
    }
    fun encode(record: SavedQueryResultRecord): ByteArray {
        val bytes = ProtoBytes.encode(toProto(record))
        return ProtoBytes.encode(WArtifact.newBuilder().setFormatVersion(FORMAT).setSha256(ProtoBytes.digest(bytes)).setRecordBytes(com.google.protobuf.ByteString.copyFrom(bytes)).build())
    }
    fun decode(bytes: ByteArray, maximumBytes: Int, maximumDepth: Int = 128): SavedQueryResultRecord {
        val artifact = ProtoBytes.decode(bytes, WArtifact.getDefaultInstance(), maximumBytes, maximumDepth)
        if (artifact.formatVersion != FORMAT)
            throw QueryResultFormatException(QueryResultFormatException.Component.ARTIFACT, artifact.formatVersion, FORMAT)
        val record = artifact.recordBytes.toByteArray()
        require(artifact.sha256 == ProtoBytes.digest(record)) { "saved result record differs from its precise digest" }
        return fromProto(ProtoBytes.decode(record, WRecord.getDefaultInstance(), maximumBytes, maximumDepth))
    }
}
