package com.olap.prototype.runtime

import com.olap.prototype.codec.*
import com.olap.prototype.wire.query.CandidateWorkerCommand as WCommand
import com.olap.prototype.wire.query.CandidateWorkerResult as WResult
import com.olap.prototype.wire.query.CandidatePublication as WPublication
import com.olap.prototype.wire.query.WorkerPublicationIdentity as WIdentity

object CandidateWorkerProto {
    fun toProto(v: CandidatePublicationIdentity): WIdentity = WIdentity.newBuilder().setCoordinate(QueryProto.toProto(v.coordinate)).setCatalogHash(v.catalogHash).setCapabilityHash(v.capabilityHash).setSemanticCatalogHash(v.semanticCatalogHash).build()
    fun fromProto(v: WIdentity): CandidatePublicationIdentity { require(v.hasCoordinate()); return CandidatePublicationIdentity(QueryProto.fromProto(v.coordinate),v.catalogHash,v.capabilityHash,v.semanticCatalogHash) }
    fun toProto(v: CandidateWorkerCommand): WCommand = WCommand.newBuilder().apply { when(v) {
        is CandidateWorkerCommand.Lookup -> setLookup(toProto(v.identity))
        is CandidateWorkerCommand.Load -> setLoad(WPublication.newBuilder().setEntry(GovernanceProto.toProto(v.entry)).setExecutable(GovernanceProto.toProto(v.executable)))
        is CandidateWorkerCommand.Unload -> setUnload(toProto(v.identity))
        is CandidateWorkerCommand.Resolve -> setResolve(CandidateProto.toProto(v.request))
        is CandidateWorkerCommand.Values -> setValues(CandidateProto.toProto(v.request))
    } }.build()
    fun fromProto(v: WCommand): CandidateWorkerCommand = when(v.kindCase) {
        WCommand.KindCase.LOOKUP -> CandidateWorkerCommand.Lookup(fromProto(v.lookup))
        WCommand.KindCase.LOAD -> v.load.let { require(it.hasEntry() && it.hasExecutable()); CandidateWorkerCommand.Load(GovernanceProto.fromProto(it.entry),GovernanceProto.fromProto(it.executable)) }
        WCommand.KindCase.UNLOAD -> CandidateWorkerCommand.Unload(fromProto(v.unload))
        WCommand.KindCase.RESOLVE -> CandidateWorkerCommand.Resolve(CandidateProto.fromProto(v.resolve))
        WCommand.KindCase.VALUES -> CandidateWorkerCommand.Values(CandidateProto.fromProto(v.values))
        else -> error("candidate worker command is required")
    }
    fun toProto(v: CandidateWorkerResult): WResult = WResult.newBuilder().apply { when(v) {
        is CandidateWorkerResult.Present -> setPresent(v.value)
        is CandidateWorkerResult.Loaded -> setLoaded(toProto(v.identity))
        is CandidateWorkerResult.Unloaded -> setUnloaded(toProto(v.identity))
        is CandidateWorkerResult.Resolved -> setResolved(CandidateProto.toProto(v.value))
        is CandidateWorkerResult.Values -> setValues(CandidateProto.toProto(v.value))
    } }.build()
    fun fromProto(v: WResult): CandidateWorkerResult = when(v.kindCase) {
        WResult.KindCase.PRESENT -> CandidateWorkerResult.Present(v.present)
        WResult.KindCase.LOADED -> CandidateWorkerResult.Loaded(fromProto(v.loaded))
        WResult.KindCase.UNLOADED -> CandidateWorkerResult.Unloaded(fromProto(v.unloaded))
        WResult.KindCase.RESOLVED -> CandidateWorkerResult.Resolved(CandidateProto.fromProto(v.resolved))
        WResult.KindCase.VALUES -> CandidateWorkerResult.Values(CandidateProto.fromProto(v.values))
        else -> error("candidate worker result is required")
    }
}
