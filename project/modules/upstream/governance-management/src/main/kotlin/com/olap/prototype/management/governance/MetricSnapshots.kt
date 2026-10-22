package com.olap.prototype.management.governance

import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.governance.contract.*
import com.olap.prototype.metric.MetricBusinessMeaning

internal class MetricSnapshots(private val reads: GovernanceSnapshotReader) {
    fun metric(id: String, version: Long): MetricRecord {
        val snapshot = reads.versionSnapshot("metric_version", "metric_id", id, version)
        val record = snapshot.content<GovernanceDefinition.Metric>().record
        snapshot.requireId(id, record.id)
        val definition = record.definition
        if (definition is MetricDefinition.FactMeasure) reads.event(definition.event.eventId, definition.event.eventVersion)
        return record.copy(status = snapshot.versionStatus, currentVersion = version, createdBy = snapshot.createdBy,
            createdAt = snapshot.createdAt, updatedBy = snapshot.activatedBy, updatedAt = snapshot.activatedAt)
    }
    fun metricBusinessMeaning(id: String, version: Long): MetricBusinessMeaning =
        reads.versionSnapshot("metric_version", "metric_id", id, version).content<GovernanceDefinition.Metric>().businessMeaning
    fun metricExecutionDefinition(id: String, version: Long): MetricDefinition = metric(id, version).definition
    companion object {
        fun metricVersionContent(metric: MetricRecord, meaning: MetricBusinessMeaning): GovernanceDefinition.Metric = GovernanceDefinition.Metric(metric, meaning)
    }
}
