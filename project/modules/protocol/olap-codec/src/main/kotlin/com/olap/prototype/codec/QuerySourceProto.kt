package com.olap.prototype.codec

import com.olap.prototype.warehouse.contract.*
import com.olap.prototype.wire.query.SourceBinding as WBinding
import com.olap.prototype.wire.query.SourceVersion as WVersion
import com.olap.prototype.wire.query.SourceReadBasis as WBasis
import com.olap.prototype.wire.query.SourceRequestEvidence as WRequest
import com.olap.prototype.wire.query.SourceReadEvidence as WEvidence

object QuerySourceProto {
    fun toProto(v: SourceCancellationEvidence): com.olap.prototype.wire.query.SourceCancellationEvidence = com.olap.prototype.wire.query.SourceCancellationEvidence.newBuilder().apply { when(v) {
        SourceCancellationEvidence.NotRequested -> setNotRequested(com.olap.prototype.wire.semantic.Empty.getDefaultInstance())
        is SourceCancellationEvidence.Unconfirmed -> setUnconfirmedReason(v.reason)
        is SourceCancellationEvidence.Confirmed -> setConfirmed(com.olap.prototype.wire.query.SourceCancellationConfirmed.newBuilder().setAuthority(v.authority).setReceipt(v.receipt))
        is SourceCancellationEvidence.Failed -> setFailed(com.olap.prototype.wire.query.SourceCancellationFailed.newBuilder().setCode(v.code).setReason(v.reason))
    } }.build()
    fun fromProto(v: com.olap.prototype.wire.query.SourceCancellationEvidence): SourceCancellationEvidence = when(v.kindCase) {
        com.olap.prototype.wire.query.SourceCancellationEvidence.KindCase.NOT_REQUESTED -> SourceCancellationEvidence.NotRequested
        com.olap.prototype.wire.query.SourceCancellationEvidence.KindCase.UNCONFIRMED_REASON -> SourceCancellationEvidence.Unconfirmed(v.unconfirmedReason)
        com.olap.prototype.wire.query.SourceCancellationEvidence.KindCase.CONFIRMED -> SourceCancellationEvidence.Confirmed(v.confirmed.authority,v.confirmed.receipt)
        com.olap.prototype.wire.query.SourceCancellationEvidence.KindCase.FAILED -> SourceCancellationEvidence.Failed(v.failed.code,v.failed.reason)
        else -> error("source cancellation evidence kind is required")
    }
    fun toProto(v: SourceFailureEvidence): com.olap.prototype.wire.query.SourceFailureEvidence = com.olap.prototype.wire.query.SourceFailureEvidence.newBuilder().setCategory(v.category.name)
        .setStage(v.stage).setCode(v.code).setSafeMessage(v.safeMessage).setBinding(toProto(v.binding)).setOperation(v.operation).setRequest(toProto(v.request)).setCancellation(toProto(v.cancellation)).apply {
            v.capability?.let(::setCapability); v.sqlState?.let(::setSqlState); v.nativeCode?.let(::setNativeCode)
        }.build()
    fun fromProto(v: com.olap.prototype.wire.query.SourceFailureEvidence): SourceFailureEvidence {
        require(v.hasBinding() && v.hasRequest() && v.hasCancellation())
        return SourceFailureEvidence(SourceFailureCategory.valueOf(v.category),v.stage,v.code,v.safeMessage,fromProto(v.binding),v.operation,
            if(v.hasCapability())v.capability else null,if(v.hasSqlState())v.sqlState else null,if(v.hasNativeCode())v.nativeCode else null,fromProto(v.request),fromProto(v.cancellation))
    }
    fun toProto(v: SourceBindingRef): WBinding = WBinding.newBuilder().setDataSourceId(v.dataSourceId).setSourceIdentityHash(v.sourceIdentityHash).build()
    fun fromProto(v: WBinding) = SourceBindingRef(v.dataSourceId, v.sourceIdentityHash)
    fun toProto(v: SourceReadBasis.Versioned): WVersion = WVersion.newBuilder().setAuthority(v.authority).setVersion(v.version)
        .putAllPartitions(v.partitions).setPinning(v.pinning.name).build()
    fun fromProto(v: WVersion) = SourceReadBasis.Versioned(v.authority, v.version, v.partitionsMap.toMap(), SourcePinning.valueOf(v.pinning))
    fun toProto(v: SourceReadBasis): WBasis = WBasis.newBuilder().apply { when (v) {
        is SourceReadBasis.Unknown -> unknownReason = v.reason
        is SourceReadBasis.Versioned -> versioned = toProto(v)
    } }.build()
    fun fromProto(v: WBasis): SourceReadBasis = when (v.kindCase) {
        WBasis.KindCase.UNKNOWN_REASON -> SourceReadBasis.Unknown(v.unknownReason)
        WBasis.KindCase.VERSIONED -> fromProto(v.versioned)
        else -> error("source read basis requires one explicit variant")
    }
    fun toProto(v: SourceRequestEvidence): WRequest = WRequest.newBuilder().setAcceptance(v.acceptance.name).setResponse(v.response.name).setExecution(v.execution.name)
        .apply { v.requestId?.let(::setRequestId); v.protocol?.let(::setProtocol); v.status?.let(::setStatus) }.build()
    fun fromProto(v: WRequest) = SourceRequestEvidence(if (v.hasRequestId()) v.requestId else null, SourceRequestAcceptance.valueOf(v.acceptance),
        SourceResponseState.valueOf(v.response), SourceExecutionState.valueOf(v.execution), if (v.hasProtocol()) v.protocol else null, if (v.hasStatus()) v.status else null)
    fun toProto(v: SourceReadEvidence): WEvidence = WEvidence.newBuilder().setBinding(toProto(v.binding)).setBasis(toProto(v.basis)).setRequest(toProto(v.request)).build()
    fun fromProto(v: WEvidence): SourceReadEvidence { require(v.hasBinding() && v.hasBasis() && v.hasRequest()); return SourceReadEvidence(fromProto(v.binding), fromProto(v.basis), fromProto(v.request)) }
}
