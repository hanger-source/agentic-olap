package com.olap.prototype.codec

import com.olap.prototype.query.contract.*
import com.olap.prototype.query.ScalarValueType
import java.time.Instant
import com.olap.prototype.wire.query.CandidateTarget as WTarget
import com.olap.prototype.wire.query.CandidateRequest as WRequest
import com.olap.prototype.wire.query.CandidateSource as WSource
import com.olap.prototype.wire.query.CandidateResolution as WResolution
import com.olap.prototype.wire.query.CandidateValues as WValues

object CandidateProto {
    fun toProto(v: CandidateTarget): WTarget = WTarget.newBuilder().apply { when(v) {
        is CandidateTarget.DimensionProvider -> setDimensionProvider(com.olap.prototype.wire.query.CandidateDimensionProvider.newBuilder().setFilterRefId(v.filterRefId).setLevelCode(v.levelCode))
        is CandidateTarget.MemberOutput -> setMemberOutput(SemanticProto.toProto(v.call))
    } }.build()
    fun fromProto(v: WTarget): CandidateTarget = when(v.kindCase) {
        WTarget.KindCase.DIMENSION_PROVIDER -> CandidateTarget.DimensionProvider(v.dimensionProvider.filterRefId, v.dimensionProvider.levelCode)
        WTarget.KindCase.MEMBER_OUTPUT -> CandidateTarget.MemberOutput(SemanticProto.fromProto(v.memberOutput))
        else -> error("candidate target is required")
    }
    fun toProto(v: CandidateRequest): WRequest = WRequest.newBuilder().setPublication(QueryProto.toProto(v.publication)).setCapabilityHash(v.capabilityHash).setTarget(toProto(v.target)).setOffset(v.offset).setLimit(v.limit).build()
    fun fromProto(v: WRequest): CandidateRequest { require(v.hasPublication() && v.hasTarget()); return CandidateRequest(QueryProto.fromProto(v.publication),v.capabilityHash,fromProto(v.target),v.offset,v.limit) }
    fun digest(v: CandidateRequest): String = toProto(v).let { ProtoBytes.digest(ProtoBytes.encode(it, it.serializedSize)) }
    fun toProto(v: CandidateSource): WSource = WSource.newBuilder().setBinding(QuerySourceProto.toProto(v.binding)).setConnectorRef(SemanticProto.toProto(v.connectorRef)).build()
    fun fromProto(v: WSource): CandidateSource { require(v.hasBinding() && v.hasConnectorRef()); return CandidateSource(QuerySourceProto.fromProto(v.binding),SemanticProto.fromProto(v.connectorRef)) }
    fun toProto(v: CandidateResolution): WResolution = WResolution.newBuilder().setCoordinate(QueryProto.toProto(v.coordinate)).setCatalogHash(v.catalogHash).setCapabilityHash(v.capabilityHash)
        .setQueryHash(v.queryHash).addAllSources(v.sources.map(::toProto)).setValueType(v.valueType.name).build()
    fun fromProto(v: WResolution): CandidateResolution { require(v.hasCoordinate()); return CandidateResolution(QueryProto.fromProto(v.coordinate),v.catalogHash,v.capabilityHash,v.queryHash,v.sourcesList.map(::fromProto),ScalarValueType.valueOf(v.valueType)) }
    fun toProto(v: CandidateValues): WValues = WValues.newBuilder().setCoordinate(QueryProto.toProto(v.coordinate)).setCatalogHash(v.catalogHash).setCapabilityHash(v.capabilityHash).setQueryHash(v.queryHash)
        .setTarget(toProto(v.target)).setValueType(v.valueType.name).addAllValues(v.values.map(SemanticProto::toProto)).setOffset(v.offset).setObservedAt(v.observedAt.toString()).setScope(v.scope).apply { v.nextOffset?.let(::setNextOffset) }.build()
    fun fromProto(v: WValues): CandidateValues { require(v.hasCoordinate() && v.hasTarget()); return CandidateValues(QueryProto.fromProto(v.coordinate),v.catalogHash,v.capabilityHash,v.queryHash,fromProto(v.target),ScalarValueType.valueOf(v.valueType),v.valuesList.map(SemanticProto::fromProto),v.offset,if(v.hasNextOffset())v.nextOffset else null,Instant.parse(v.observedAt),v.scope) }
}
