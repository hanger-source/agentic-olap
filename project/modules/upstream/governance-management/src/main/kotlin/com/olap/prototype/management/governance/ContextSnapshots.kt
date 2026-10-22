package com.olap.prototype.management.governance

import com.olap.prototype.governance.contract.*

internal class BusinessLineSnapshots(private val reads: GovernanceSnapshotReader) {
    fun businessLine(id: String, version: Long): BusinessLineRecord {
        val snapshot = reads.versionSnapshot("business_line_version", "business_line_id", id, version)
        val record = snapshot.content<GovernanceDefinition.BusinessLine>().record
        snapshot.requireId(id, record.id)
        return record.copy(status = snapshot.versionStatus, currentVersion = version, createdBy = snapshot.createdBy,
            createdAt = snapshot.createdAt, updatedBy = snapshot.activatedBy, updatedAt = snapshot.activatedAt)
    }
    companion object { fun versionContent(record: BusinessLineRecord): GovernanceDefinition.BusinessLine = GovernanceDefinition.BusinessLine(record) }
}
internal class SubjectAreaSnapshots(private val reads: GovernanceSnapshotReader) {
    fun subjectArea(id: String, version: Long): SubjectAreaRecord {
        val snapshot = reads.versionSnapshot("subject_area_version", "subject_area_id", id, version)
        val record = snapshot.content<GovernanceDefinition.SubjectArea>().record
        snapshot.requireId(id, record.id)
        reads.businessLine(record.businessLineId, record.businessLineVersion)
        return record.copy(status = snapshot.versionStatus, currentVersion = version, createdBy = snapshot.createdBy,
            createdAt = snapshot.createdAt, updatedBy = snapshot.activatedBy, updatedAt = snapshot.activatedAt)
    }
    companion object { fun subjectAreaVersionContent(record: SubjectAreaRecord): GovernanceDefinition.SubjectArea = GovernanceDefinition.SubjectArea(record) }
}
internal class AnalysisDimensionSnapshots(private val reads: GovernanceSnapshotReader) {
    fun analysisDimension(id: String, version: Long): AnalysisDimensionRecord {
        val snapshot = reads.versionSnapshot("analysis_dimension_version", "analysis_dimension_id", id, version)
        val record = snapshot.content<GovernanceDefinition.AnalysisDimension>().record
        snapshot.requireId(id, record.id)
        reads.subjectArea(record.subjectAreaId, record.subjectAreaVersion)
        return record.copy(status = snapshot.versionStatus, currentVersion = version, createdBy = snapshot.createdBy,
            createdAt = snapshot.createdAt, updatedBy = snapshot.activatedBy, updatedAt = snapshot.activatedAt)
    }
    companion object { fun analysisDimensionVersionContent(record: AnalysisDimensionRecord): GovernanceDefinition.AnalysisDimension = GovernanceDefinition.AnalysisDimension(record) }
}
internal class ScenarioSnapshots(private val reads: GovernanceSnapshotReader) {
    fun scenario(id: String, version: Long): ScenarioRecord {
        val snapshot = reads.versionSnapshot("scenario_version", "scenario_id", id, version)
        val record = snapshot.content<GovernanceDefinition.Scenario>().record
        snapshot.requireId(id, record.id)
        reads.subjectArea(record.subjectAreaId, record.subjectAreaVersion)
        return record.copy(status = snapshot.versionStatus, currentVersion = version, createdBy = snapshot.createdBy,
            createdAt = snapshot.createdAt, updatedBy = snapshot.activatedBy, updatedAt = snapshot.activatedAt)
    }
    companion object { fun scenarioVersionContent(record: ScenarioRecord): GovernanceDefinition.Scenario = GovernanceDefinition.Scenario(record) }
}
