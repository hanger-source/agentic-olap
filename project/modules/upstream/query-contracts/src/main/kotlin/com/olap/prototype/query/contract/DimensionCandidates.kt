package com.olap.prototype.query.contract

import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.query.ValueAlignmentContract

class AnalysisDimensionCandidate(val analysisDimensionId: String, val analysisDimensionVersion: Long, val analysisDimensionDisplayName: String,
    val outputAlias: String, val subjectAreaAnalysisDimensionRefId: String, val subjectAreaAnalysisDimensionRefStatus: String,
    val scenarioAnalysisDimensionRefId: String, val releaseObjectRefId: String, val defaultLevelCode: String, val defaultLevelDisplayName: String,
    availableLevelCodes: List<String>, eventMappings: List<AnalysisDimensionEventMappingCandidate>, val requiredByDefault: Boolean, val sortOrder: Int) {
    val availableLevelCodes: List<String> = java.util.List.copyOf(availableLevelCodes)
    val eventMappings: List<AnalysisDimensionEventMappingCandidate> = java.util.List.copyOf(eventMappings)
    init {
        ProducerContracts.requireText(analysisDimensionId, "analysisDimensionId"); ProducerContracts.requireVersion(analysisDimensionVersion, "analysisDimensionVersion")
        ProducerContracts.requireText(analysisDimensionDisplayName, "analysisDimensionDisplayName"); ProducerContracts.requireText(outputAlias, "outputAlias")
        ProducerContracts.requireText(subjectAreaAnalysisDimensionRefId, "subjectAreaAnalysisDimensionRefId"); ProducerContracts.requireText(subjectAreaAnalysisDimensionRefStatus, "subjectAreaAnalysisDimensionRefStatus")
        ProducerContracts.requireText(scenarioAnalysisDimensionRefId, "scenarioAnalysisDimensionRefId"); ProducerContracts.requireText(releaseObjectRefId, "releaseObjectRefId")
        ProducerContracts.requireText(defaultLevelCode, "defaultLevelCode"); ProducerContracts.requireText(defaultLevelDisplayName, "defaultLevelDisplayName")
        require(this.availableLevelCodes.isNotEmpty()) { "analysisDimensionCandidate.availableLevelCodes must not be empty" }
        require(this.eventMappings.isNotEmpty()) { "analysisDimensionCandidate.eventMappings must not be empty" }
    }
    override fun equals(other: Any?): Boolean = other is AnalysisDimensionCandidate && analysisDimensionId == other.analysisDimensionId && analysisDimensionVersion == other.analysisDimensionVersion && analysisDimensionDisplayName == other.analysisDimensionDisplayName && outputAlias == other.outputAlias && subjectAreaAnalysisDimensionRefId == other.subjectAreaAnalysisDimensionRefId && subjectAreaAnalysisDimensionRefStatus == other.subjectAreaAnalysisDimensionRefStatus && scenarioAnalysisDimensionRefId == other.scenarioAnalysisDimensionRefId && releaseObjectRefId == other.releaseObjectRefId && defaultLevelCode == other.defaultLevelCode && defaultLevelDisplayName == other.defaultLevelDisplayName && availableLevelCodes == other.availableLevelCodes && eventMappings == other.eventMappings && requiredByDefault == other.requiredByDefault && sortOrder == other.sortOrder
    override fun hashCode(): Int = listOf(analysisDimensionId, analysisDimensionVersion, analysisDimensionDisplayName, outputAlias, subjectAreaAnalysisDimensionRefId, subjectAreaAnalysisDimensionRefStatus, scenarioAnalysisDimensionRefId, releaseObjectRefId, defaultLevelCode, defaultLevelDisplayName, availableLevelCodes, eventMappings, requiredByDefault, sortOrder).hashCode()
}
data class AnalysisDimensionEventMappingCandidate(val mappingId: String, val analysisDimensionId: String, val analysisDimensionVersion: Long,
    val levelCode: String, val levelDisplayName: String, val eventId: String, val eventVersion: Long, val eventDisplayName: String,
    val dimensionId: String, val dimensionVersion: Long, val dimensionCodeNamespace: String, val dimensionCodeNamespaceDisplayName: String,
    val dimensionDisplayName: String, val dimensionBindingId: String, val dimensionBindingVersion: Long, val logicUnitId: String,
    val logicUnitVersion: Long, val logicUnitDisplayName: String, val valueAlignmentKey: String?, val valueAlignmentStatus: String) {
    init {
        ProducerContracts.requireText(mappingId, "mappingId"); ProducerContracts.requireText(analysisDimensionId, "analysisDimensionId"); ProducerContracts.requireVersion(analysisDimensionVersion, "analysisDimensionVersion")
        ProducerContracts.requireText(levelCode, "levelCode"); ProducerContracts.requireText(levelDisplayName, "levelDisplayName")
        ProducerContracts.requireText(eventId, "eventId"); ProducerContracts.requireVersion(eventVersion, "eventVersion"); ProducerContracts.requireText(eventDisplayName, "eventDisplayName")
        ProducerContracts.requireText(dimensionId, "dimensionId"); ProducerContracts.requireVersion(dimensionVersion, "dimensionVersion")
        ProducerContracts.requireText(dimensionCodeNamespace, "dimensionCodeNamespace"); ProducerContracts.requireText(dimensionCodeNamespaceDisplayName, "dimensionCodeNamespaceDisplayName")
        ProducerContracts.requireText(dimensionDisplayName, "dimensionDisplayName"); ProducerContracts.requireText(dimensionBindingId, "dimensionBindingId"); ProducerContracts.requireVersion(dimensionBindingVersion, "dimensionBindingVersion")
        ProducerContracts.requireText(logicUnitId, "logicUnitId"); ProducerContracts.requireVersion(logicUnitVersion, "logicUnitVersion"); ProducerContracts.requireText(logicUnitDisplayName, "logicUnitDisplayName")
        ValueAlignmentContract.requireStatus(valueAlignmentStatus, "valueAlignmentStatus")
    }
}
class TimeAnalysisDimensionCandidate(val analysisDimensionId: String, val analysisDimensionVersion: Long, val analysisDimensionDisplayName: String,
    val outputAlias: String, val scenarioAnalysisDimensionRefId: String, val releaseObjectRefId: String, val defaultLevelCode: String,
    val defaultLevelDisplayName: String, availableLevelCodes: List<String>, eventAnchors: List<TimeAnchorEventCandidate>,
    metricEventCapabilities: List<MetricAnalysisDimensionCapability>, val requiredByDefault: Boolean, val sortOrder: Int) {
    val availableLevelCodes: List<String> = java.util.List.copyOf(availableLevelCodes)
    val eventAnchors: List<TimeAnchorEventCandidate> = java.util.List.copyOf(eventAnchors)
    val metricEventCapabilities: List<MetricAnalysisDimensionCapability> = java.util.List.copyOf(metricEventCapabilities)
    init {
        ProducerContracts.requireText(analysisDimensionId, "analysisDimensionId"); ProducerContracts.requireVersion(analysisDimensionVersion, "analysisDimensionVersion")
        ProducerContracts.requireText(analysisDimensionDisplayName, "analysisDimensionDisplayName"); ProducerContracts.requireText(outputAlias, "outputAlias")
        ProducerContracts.requireText(scenarioAnalysisDimensionRefId, "scenarioAnalysisDimensionRefId"); ProducerContracts.requireText(releaseObjectRefId, "releaseObjectRefId")
        ProducerContracts.requireText(defaultLevelCode, "defaultLevelCode"); ProducerContracts.requireText(defaultLevelDisplayName, "defaultLevelDisplayName")
        require(this.availableLevelCodes.isNotEmpty()) { "timeAnalysisDimensionCandidate.availableLevelCodes must not be empty" }
        require(this.eventAnchors.isNotEmpty()) { "timeAnalysisDimensionCandidate.eventAnchors must not be empty" }
    }
    override fun equals(other: Any?): Boolean = other is TimeAnalysisDimensionCandidate && analysisDimensionId == other.analysisDimensionId && analysisDimensionVersion == other.analysisDimensionVersion && analysisDimensionDisplayName == other.analysisDimensionDisplayName && outputAlias == other.outputAlias && scenarioAnalysisDimensionRefId == other.scenarioAnalysisDimensionRefId && releaseObjectRefId == other.releaseObjectRefId && defaultLevelCode == other.defaultLevelCode && defaultLevelDisplayName == other.defaultLevelDisplayName && availableLevelCodes == other.availableLevelCodes && eventAnchors == other.eventAnchors && metricEventCapabilities == other.metricEventCapabilities && requiredByDefault == other.requiredByDefault && sortOrder == other.sortOrder
    override fun hashCode(): Int = listOf(analysisDimensionId, analysisDimensionVersion, analysisDimensionDisplayName, outputAlias, scenarioAnalysisDimensionRefId, releaseObjectRefId, defaultLevelCode, defaultLevelDisplayName, availableLevelCodes, eventAnchors, metricEventCapabilities, requiredByDefault, sortOrder).hashCode()
}
data class TimeAnchorEventCandidate(val mappingId: String, val analysisDimensionId: String, val analysisDimensionVersion: Long,
    val levelCode: String, val levelDisplayName: String, val eventId: String, val eventVersion: Long, val eventDisplayName: String,
    val topologyId: String, val topologyVersion: Long, val topologyImplementationNamespaceCode: String, val topologyImplementationNamespaceDisplayName: String,
    val effectiveTimeLogicUnitId: String, val effectiveTimeLogicUnitVersion: Long, val effectiveTimeLogicUnitDisplayName: String, val timeRole: String) {
    init {
        ProducerContracts.requireText(mappingId, "mappingId"); ProducerContracts.requireText(analysisDimensionId, "analysisDimensionId"); ProducerContracts.requireVersion(analysisDimensionVersion, "analysisDimensionVersion")
        ProducerContracts.requireText(levelCode, "levelCode"); ProducerContracts.requireText(levelDisplayName, "levelDisplayName")
        ProducerContracts.requireText(eventId, "eventId"); ProducerContracts.requireVersion(eventVersion, "eventVersion"); ProducerContracts.requireText(eventDisplayName, "eventDisplayName")
        ProducerContracts.requireText(topologyId, "topologyId"); ProducerContracts.requireVersion(topologyVersion, "topologyVersion")
        ProducerContracts.requireText(topologyImplementationNamespaceCode, "topologyImplementationNamespaceCode"); ProducerContracts.requireText(topologyImplementationNamespaceDisplayName, "topologyImplementationNamespaceDisplayName")
        ProducerContracts.requireText(effectiveTimeLogicUnitId, "effectiveTimeLogicUnitId"); ProducerContracts.requireVersion(effectiveTimeLogicUnitVersion, "effectiveTimeLogicUnitVersion")
        ProducerContracts.requireText(effectiveTimeLogicUnitDisplayName, "effectiveTimeLogicUnitDisplayName"); ProducerContracts.requireText(timeRole, "timeRole")
    }
}
