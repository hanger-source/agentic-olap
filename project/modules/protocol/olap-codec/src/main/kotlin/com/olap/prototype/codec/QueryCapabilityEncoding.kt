package com.olap.prototype.query.contract

import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.contract.ProducerContractException

object QueryCapabilityHash {
    fun compute(capability: QueryCapabilityView): String {
        val content = GovernanceProto.toProto(capability).toBuilder().clearCapabilityHash().build()
        return ProtoBytes.digest(content)
    }
    fun attach(capability: QueryCapabilityView): QueryCapabilityView = QueryCapabilityView(capability.scenario, capability.subjectAreaCapabilityContext,
        capability.releaseId, capability.releaseVersion, capability.releaseDisplayName, capability.metricCandidates, capability.memberCandidates,
        capability.analysisDimensionCandidates, capability.timeAnalysisDimensionCandidates, capability.filterCandidates, capability.relationFilterCandidates,
        capability.temporalClockCandidates, capability.postProcessCapabilities, capability.metricAnalysisDimensionMatrix, capability.disabledReasons,
        capability.diagnostics, capability.executionTypes, capability.nativeDefinitions, compute(capability))
    fun requireValid(capability: QueryCapabilityView): String = compute(capability).also {
        if (capability.capabilityHash != it) throw ProducerContractException("query capability hash mismatch")
    }
}

