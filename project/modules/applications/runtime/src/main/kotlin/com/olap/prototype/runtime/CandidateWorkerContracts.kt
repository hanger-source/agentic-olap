package com.olap.prototype.runtime

import com.olap.prototype.query.contract.*

sealed interface CandidateWorkerCommand {
    data class Load(val entry: PublishedQueryEntry, val executable: PublishedExecutableCatalog) : CandidateWorkerCommand
    data class Unload(val identity: CandidatePublicationIdentity) : CandidateWorkerCommand
    data class Resolve(val request: CandidateRequest) : CandidateWorkerCommand
    data class Values(val request: CandidateRequest) : CandidateWorkerCommand
}
data class CandidatePublicationIdentity(val coordinate: QueryCapabilityRequest, val catalogHash: String, val capabilityHash: String, val semanticCatalogHash: String)
sealed interface CandidateWorkerResult {
    data class Loaded(val identity: CandidatePublicationIdentity) : CandidateWorkerResult
    data class Unloaded(val identity: CandidatePublicationIdentity) : CandidateWorkerResult
    data class Resolved(val value: CandidateResolution) : CandidateWorkerResult
    data class Values(val value: CandidateValues) : CandidateWorkerResult
}
