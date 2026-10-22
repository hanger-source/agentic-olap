package com.olap.prototype.management.governance

import com.olap.prototype.governance.contract.*
import com.olap.prototype.management.governance.GovernanceSnapshotJson.optionalText
import com.olap.prototype.management.governance.GovernanceSnapshotJson.requireMatchingId
import com.olap.prototype.management.governance.GovernanceSnapshotJson.requiredLong
import com.olap.prototype.management.governance.GovernanceSnapshotJson.requiredText

internal class BusinessLineSnapshots(private val reads: GovernanceSnapshotReader) {
    fun businessLine(id: String, version: Long): BusinessLineRecord {
        val snapshot = reads.versionSnapshot("business_line_version", "business_line_id", id, version)
        val content = snapshot.content; val field = "business_line_version.content_json"
        val identity = requiredText(content, "businessLineId", field); requireMatchingId(id, identity, "business_line")
        return BusinessLineRecord(identity, requiredText(content, "code", field), requiredText(content, "displayName", field), optionalText(content, "description"), requiredText(content, "owner", field), snapshot.versionStatus, version, snapshot.createdBy, snapshot.createdAt, snapshot.activatedBy, snapshot.activatedAt)
    }
    companion object {
        fun versionContent(record: BusinessLineRecord): Map<String, Any?> = linkedMapOf("businessLineId" to record.id, "code" to record.code, "displayName" to record.displayName, "description" to record.description, "owner" to record.owner, "status" to record.status)
    }
}
internal class SubjectAreaSnapshots(private val reads: GovernanceSnapshotReader) {
    fun subjectArea(id: String, version: Long): SubjectAreaRecord {
        val snapshot = reads.versionSnapshot("subject_area_version", "subject_area_id", id, version)
        val content = snapshot.content; val field = "subject_area_version.content_json"
        val identity = requiredText(content, "subjectAreaId", field); requireMatchingId(id, identity, "subject_area")
        val business = requiredText(content, "businessLineId", field); val businessVersion = requiredLong(content, "businessLineVersion", field)
        reads.businessLine(business, businessVersion)
        return SubjectAreaRecord(identity, requiredText(content, "code", field), requiredText(content, "displayName", field), business, businessVersion, optionalText(content, "description"), requiredText(content, "owner", field), snapshot.versionStatus, version, snapshot.createdBy, snapshot.createdAt, snapshot.activatedBy, snapshot.activatedAt)
    }
    companion object {
        fun subjectAreaVersionContent(record: SubjectAreaRecord): Map<String, Any?> = linkedMapOf("subjectAreaId" to record.id, "code" to record.code, "displayName" to record.displayName, "businessLineId" to record.businessLineId, "businessLineVersion" to record.businessLineVersion, "description" to record.description, "owner" to record.owner, "status" to record.status)
    }
}
internal class AnalysisDimensionSnapshots(private val reads: GovernanceSnapshotReader) {
    fun analysisDimension(id: String, version: Long): AnalysisDimensionRecord {
        val snapshot = reads.versionSnapshot("analysis_dimension_version", "analysis_dimension_id", id, version)
        val content = snapshot.content; val field = "analysis_dimension_version.content_json"
        val identity = requiredText(content, "analysisDimensionId", field); requireMatchingId(id, identity, "analysis_dimension")
        val owner = requiredText(content, "subjectAreaId", field); val ownerVersion = requiredLong(content, "subjectAreaVersion", field)
        reads.subjectArea(owner, ownerVersion)
        return AnalysisDimensionRecord(identity, owner, ownerVersion, requiredText(content, "code", field), requiredText(content, "displayName", field), optionalText(content, "description"), snapshot.versionStatus, version, snapshot.createdBy, snapshot.createdAt, snapshot.activatedBy, snapshot.activatedAt)
    }
    companion object {
        fun analysisDimensionVersionContent(record: AnalysisDimensionRecord): Map<String, Any?> = linkedMapOf("analysisDimensionId" to record.id, "subjectAreaId" to record.subjectAreaId, "subjectAreaVersion" to record.subjectAreaVersion, "code" to record.code, "displayName" to record.displayName, "description" to record.description, "status" to record.status)
    }
}
internal class ScenarioSnapshots(private val reads: GovernanceSnapshotReader) {
    fun scenario(id: String, version: Long): ScenarioRecord {
        val snapshot = reads.versionSnapshot("scenario_version", "scenario_id", id, version)
        val content = snapshot.content; val field = "scenario_version.content_json"
        val identity = requiredText(content, "scenarioId", field); requireMatchingId(id, identity, "scenario")
        val owner = requiredText(content, "subjectAreaId", field); val ownerVersion = requiredLong(content, "subjectAreaVersion", field)
        reads.subjectArea(owner, ownerVersion)
        return ScenarioRecord(identity, owner, ownerVersion, requiredText(content, "code", field), requiredText(content, "displayName", field), optionalText(content, "description"), requiredText(content, "owner", field), snapshot.versionStatus, version, snapshot.createdBy, snapshot.createdAt, snapshot.activatedBy, snapshot.activatedAt)
    }
    companion object {
        fun scenarioVersionContent(record: ScenarioRecord): Map<String, Any?> = linkedMapOf("scenarioId" to record.id, "subjectAreaId" to record.subjectAreaId, "subjectAreaVersion" to record.subjectAreaVersion, "code" to record.code, "displayName" to record.displayName, "description" to record.description, "owner" to record.owner, "status" to record.status)
    }
}
