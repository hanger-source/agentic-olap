package com.olap.prototype.release.contract

import com.olap.prototype.contract.ProducerContracts
import java.time.Instant

data class ReleaseTimeAnchorRule(val id: String, val releaseId: String, val releaseVersion: String, val subjectAreaId: String, val scenarioId: String?, val scenarioVersion: Long?, val eventId: String, val eventVersion: Long, val topologyId: String, val topologyVersion: Long, val topologyImplementationNamespaceCode: String, val topologyImplementationNamespaceDisplayName: String, val effectiveTimeLogicUnitId: String, val effectiveTimeLogicUnitVersion: Long, val effectiveTimeLogicUnitDisplayName: String, val timeRole: String, val analysisDimensionId: String, val analysisDimensionVersion: Long, val analysisDimensionDisplayName: String, val levelCode: String, val aliasSymbolsJson: String?, val partitionLogicUnitId: String?, val partitionLogicUnitVersion: Long?, val diagnosticJson: String?, val createdAt: Instant) {
    init {
        ProducerContracts.requireText(id, "id"); ProducerContracts.requireText(releaseId, "releaseId"); ProducerContracts.requireText(releaseVersion, "releaseVersion"); ProducerContracts.requireText(subjectAreaId, "subjectAreaId")
        ProducerContracts.requireVersionedRefPair(scenarioId, scenarioVersion, "scenario")
        ProducerContracts.requireText(eventId, "eventId"); ProducerContracts.requireVersion(eventVersion, "eventVersion")
        ProducerContracts.requireText(topologyId, "topologyId"); ProducerContracts.requireVersion(topologyVersion, "topologyVersion")
        ProducerContracts.requireText(topologyImplementationNamespaceCode, "topologyImplementationNamespaceCode"); ProducerContracts.requireText(topologyImplementationNamespaceDisplayName, "topologyImplementationNamespaceDisplayName")
        ProducerContracts.requireText(effectiveTimeLogicUnitId, "effectiveTimeLogicUnitId"); ProducerContracts.requireVersion(effectiveTimeLogicUnitVersion, "effectiveTimeLogicUnitVersion"); ProducerContracts.requireText(effectiveTimeLogicUnitDisplayName, "effectiveTimeLogicUnitDisplayName")
        ProducerContracts.requireText(timeRole, "timeRole"); ProducerContracts.requireVersionedRefPair(analysisDimensionId, analysisDimensionVersion, "analysisDimension")
        ProducerContracts.requireText(analysisDimensionId, "analysisDimensionId"); ProducerContracts.requireVersion(analysisDimensionVersion, "analysisDimensionVersion"); ProducerContracts.requireText(analysisDimensionDisplayName, "analysisDimensionDisplayName"); ProducerContracts.requireText(levelCode, "levelCode")
        ProducerContracts.requireVersionedRefPair(partitionLogicUnitId, partitionLogicUnitVersion, "partitionLogicUnit")
    }
}
