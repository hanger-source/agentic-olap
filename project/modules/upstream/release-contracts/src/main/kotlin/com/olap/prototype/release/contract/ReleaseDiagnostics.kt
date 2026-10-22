package com.olap.prototype.release.contract

import com.olap.prototype.contract.ProducerContracts
import java.time.Instant

data class ReleaseDiagnosticIssue(val diagnosticRunId: String, val issueId: String, val severity: String, val reasonCode: String, val reasonMessage: String, val producerStage: String, val targetRef: String, val targetVersionRef: String, val blocking: Boolean, val pageBlocking: Boolean, val executableBlocking: Boolean) {
    init {
        ProducerContracts.requireText(diagnosticRunId, "diagnosticRunId"); ProducerContracts.requireText(issueId, "issueId"); ProducerContracts.requireText(severity, "severity")
        ProducerContracts.requireText(reasonCode, "reasonCode"); ProducerContracts.requireText(reasonMessage, "reasonMessage"); ProducerContracts.requireText(producerStage, "producerStage")
        ProducerContracts.requireText(targetRef, "targetRef"); ProducerContracts.requireText(targetVersionRef, "targetVersionRef")
    }
}
data class ReleaseUnavailableReason(val id: String, val releaseId: String, val releaseVersion: String, val scopeType: String, val scenarioId: String?, val scenarioVersion: Long?, val metricId: String?, val metricVersion: Long?, val analysisDimensionId: String?, val analysisDimensionVersion: Long?, val levelCode: String?, val reasonCode: String, val reasonMessage: String, val producerStage: String, val targetRef: String, val targetVersionRef: String, val blocking: Boolean, val pageBlocking: Boolean, val executableBlocking: Boolean, val createdAt: Instant) {
    init {
        ProducerContracts.requireText(id, "id"); ProducerContracts.requireText(releaseId, "releaseId"); ProducerContracts.requireText(releaseVersion, "releaseVersion"); ProducerContracts.requireText(scopeType, "scopeType")
        ProducerContracts.requireVersionedRefPair(scenarioId, scenarioVersion, "scenario"); ProducerContracts.requireVersionedRefPair(metricId, metricVersion, "metric")
        ProducerContracts.requireVersionedRefPair(analysisDimensionId, analysisDimensionVersion, "analysisDimension")
        ProducerContracts.requireText(reasonCode, "reasonCode"); ProducerContracts.requireText(reasonMessage, "reasonMessage"); ProducerContracts.requireText(producerStage, "producerStage")
        ProducerContracts.requireText(targetRef, "targetRef"); ProducerContracts.requireText(targetVersionRef, "targetVersionRef")
    }
}
data class ReleaseMetricDimensionCapability(val id: String, val releaseId: String, val releaseVersion: String, val subjectAreaId: String, val scenarioId: String?, val scenarioVersion: Long?, val adoptionId: String, val adoptionVersion: Long, val metricId: String, val metricVersion: Long, val metricDisplayName: String, val eventId: String, val eventVersion: Long, val analysisDimensionId: String, val analysisDimensionVersion: Long, val analysisDimensionDisplayName: String, val levelCode: String?, val levelDisplayName: String?, val available: Boolean, val reasonCode: String?, val reasonMessage: String?, val producerStage: String?, val targetRef: String?, val targetVersionRef: String?, val blocking: Boolean, val pageBlocking: Boolean, val executableBlocking: Boolean, val createdAt: Instant) {
    init {
        ProducerContracts.requireText(id, "id"); ProducerContracts.requireText(releaseId, "releaseId"); ProducerContracts.requireText(releaseVersion, "releaseVersion"); ProducerContracts.requireText(subjectAreaId, "subjectAreaId")
        ProducerContracts.requireVersionedRefPair(scenarioId, scenarioVersion, "scenario")
        ProducerContracts.requireText(adoptionId, "adoptionId"); ProducerContracts.requireVersion(adoptionVersion, "adoptionVersion")
        ProducerContracts.requireText(metricId, "metricId"); ProducerContracts.requireVersion(metricVersion, "metricVersion"); ProducerContracts.requireText(metricDisplayName, "metricDisplayName")
        ProducerContracts.requireText(eventId, "eventId"); ProducerContracts.requireVersion(eventVersion, "eventVersion")
        ProducerContracts.requireText(analysisDimensionId, "analysisDimensionId"); ProducerContracts.requireVersion(analysisDimensionVersion, "analysisDimensionVersion"); ProducerContracts.requireText(analysisDimensionDisplayName, "analysisDimensionDisplayName")
        if (!available) {
            ProducerContracts.requireText(reasonCode, "reasonCode"); ProducerContracts.requireText(reasonMessage, "reasonMessage"); ProducerContracts.requireText(producerStage, "producerStage")
            ProducerContracts.requireText(targetRef, "targetRef"); ProducerContracts.requireText(targetVersionRef, "targetVersionRef")
        }
    }
}
