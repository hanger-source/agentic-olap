package com.olap.prototype.query.contract

import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.calculation.MetricInstanceRef
import com.olap.prototype.time.TemporalWindow

data class QueryScenarioRef(val scenarioId: String, val scenarioVersion: Long, val scenarioDisplayName: String,
    val releaseId: String, val releaseVersion: String, val releaseDisplayName: String) {
    init {
        ProducerContracts.requireText(scenarioId, "scenarioId"); ProducerContracts.requireVersion(scenarioVersion, "scenarioVersion")
        ProducerContracts.requireText(scenarioDisplayName, "scenarioDisplayName"); ProducerContracts.requireText(releaseId, "releaseId")
        ProducerContracts.requireText(releaseVersion, "releaseVersion"); ProducerContracts.requireText(releaseDisplayName, "releaseDisplayName")
    }
}
data class SubjectAreaCapabilityContext(val subjectAreaId: String, val subjectAreaVersion: Long,
    val subjectAreaScenarioRefId: String, val subjectAreaScenarioRefStatus: String) {
    init {
        ProducerContracts.requireText(subjectAreaId, "subjectAreaId"); ProducerContracts.requireVersion(subjectAreaVersion, "subjectAreaVersion")
        ProducerContracts.requireText(subjectAreaScenarioRefId, "subjectAreaScenarioRefId"); ProducerContracts.requireText(subjectAreaScenarioRefStatus, "subjectAreaScenarioRefStatus")
    }
}
data class TemporalClockCandidate(val clockRef: String, val valueType: TemporalWindow.ValueType, val description: String) {
    init {
        require(clockRef.matches(Regex("clock_[0-9a-f]{64}"))) { "exact temporal clock reference is required" }
        ProducerContracts.requireText(description, "clock description")
    }
}
data class MetricAnalysisDimensionCapability(val instance: MetricInstanceRef, val eventId: String, val eventVersion: Long,
    val analysisDimensionId: String, val analysisDimensionVersion: Long, val levelCode: String?, val available: Boolean,
    val reasonCode: String?, val reasonMessage: String?, val producerStage: String?, val targetRef: String?, val targetVersionRef: String?,
    val blocking: Boolean, val pageBlocking: Boolean, val executableBlocking: Boolean) {
    init {
        ProducerContracts.requireText(eventId, "eventId"); ProducerContracts.requireVersion(eventVersion, "eventVersion")
        ProducerContracts.requireText(analysisDimensionId, "analysisDimensionId"); ProducerContracts.requireVersion(analysisDimensionVersion, "analysisDimensionVersion")
        if (!available) {
            ProducerContracts.requireText(reasonCode, "reasonCode"); ProducerContracts.requireText(reasonMessage, "reasonMessage")
            ProducerContracts.requireText(producerStage, "producerStage"); ProducerContracts.requireText(targetRef, "targetRef"); ProducerContracts.requireText(targetVersionRef, "targetVersionRef")
        }
    }
}
data class PublishedObjectVersionRef(val objectType: String?, val objectId: String?, val objectVersion: String?,
    val objectCode: String?, val objectDisplayName: String?, val refRole: String?)

data class DiagnosticIssueRef(val diagnosticRunId: String, val issueId: String, val severity: String,
    val reasonCode: String, val reasonMessage: String, val producerStage: String, val targetRef: String, val targetVersionRef: String,
    val blocking: Boolean, val pageBlocking: Boolean, val executableBlocking: Boolean) {
    init {
        ProducerContracts.requireText(diagnosticRunId, "diagnosticRunId"); ProducerContracts.requireText(issueId, "issueId"); ProducerContracts.requireText(severity, "severity")
        ProducerContracts.requireText(reasonCode, "reasonCode"); ProducerContracts.requireText(reasonMessage, "reasonMessage")
        ProducerContracts.requireText(producerStage, "producerStage"); ProducerContracts.requireText(targetRef, "targetRef"); ProducerContracts.requireText(targetVersionRef, "targetVersionRef")
    }
}
data class DisabledReason(val id: String, val releaseId: String, val releaseVersion: String, val scopeType: String,
    val scenarioId: String?, val scenarioVersion: Long?, val metricId: String?, val metricVersion: Long?,
    val analysisDimensionId: String?, val analysisDimensionVersion: Long?, val levelCode: String?,
    val reasonCode: String, val reasonMessage: String, val producerStage: String, val targetRef: String, val targetVersionRef: String,
    val blocking: Boolean, val pageBlocking: Boolean, val executableBlocking: Boolean) {
    init {
        ProducerContracts.requireText(id, "id"); ProducerContracts.requireText(releaseId, "releaseId"); ProducerContracts.requireText(releaseVersion, "releaseVersion"); ProducerContracts.requireText(scopeType, "scopeType")
        ProducerContracts.requireVersionedRefPair(scenarioId, scenarioVersion, "scenario"); ProducerContracts.requireVersionedRefPair(metricId, metricVersion, "metric")
        ProducerContracts.requireVersionedRefPair(analysisDimensionId, analysisDimensionVersion, "analysisDimension")
        ProducerContracts.requireText(reasonCode, "reasonCode"); ProducerContracts.requireText(reasonMessage, "reasonMessage")
        ProducerContracts.requireText(producerStage, "producerStage"); ProducerContracts.requireText(targetRef, "targetRef"); ProducerContracts.requireText(targetVersionRef, "targetVersionRef")
    }
}
