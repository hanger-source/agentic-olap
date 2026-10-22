package com.olap.prototype.management.governance

import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.contract.ProducerContracts.requireText
import com.olap.prototype.contract.ProducerContracts.requireVersion
import com.olap.prototype.governance.contract.*
import com.olap.prototype.management.governance.GovernanceJdbcSupport.insert
import com.olap.prototype.management.governance.GovernanceJdbcSupport.instant
import com.olap.prototype.management.governance.GovernanceJdbcSupport.nullableLong
import com.olap.prototype.management.governance.GovernanceJdbcSupport.query
import com.olap.prototype.storage.PlatformDatabase

class MetricGovernanceRepository(private val database: PlatformDatabase) {
    private val snapshots = GovernanceSnapshotReader(database)
    private val working = GovernanceWorkingVersionResolver(database)
    private val identities = GovernanceIdentityRepository(database)
    fun saveMetric(record: MetricRecord) = identities.saveMetric(record)
    fun findMetric(id: String): MetricRecord? {
        requireText(id, "metric.id")
        return working.find("metric_version", "metric_id", id)?.let { snapshots.metric(it.objectId, it.version) }
    }
    fun listMetrics(): List<MetricRecord> = working.list("metric_version", "metric_id").map { snapshots.metric(it.objectId, it.version) }.sortedBy { it.code }
    fun listMetricsByEvent(id: String, version: Long): List<MetricRecord> {
        requireText(id, "metric.source_event_id"); requireVersion(version, "metric.source_event_version")
        return listMetrics().filter { val fact = it.definition as? MetricDefinition.FactMeasure; fact != null && fact.event.eventId == id && fact.event.eventVersion == version }
    }
    fun metricIdentityExists(id: String): Boolean = identities.exists("metric", id)
    fun metricVersionExists(id: String, version: Long): Boolean {
        requireText(id, "metric_version.metric_id"); requireVersion(version, "metric_version.version")
        return try { query(database, "SELECT 1 FROM metric_version WHERE metric_id=? AND version=?", listOf(id, version)) { true }.isNotEmpty() }
        catch (failure: Exception) { throw IllegalStateException("Failed to check metric_version: $id#$version", failure) }
    }
    fun saveMetricVersion(record: MetricVersionRecord) {
        GovernanceContract.requireVersionObject("metric_version", "metric_id", record.id, record.metricId, record.version, record.versionStatus, record.createdBy, record.createdAt)
        requireText(record.contentJson, "metric_version.content_json")
        try { insert(database, "metric_version", linkedMapOf("id" to record.id, "metric_id" to record.metricId, "version" to record.version, "version_status" to record.versionStatus,
            "content_json" to record.contentJson, "diagnostic_json" to record.diagnosticJson, "source_version" to record.sourceVersion,
            "created_by" to record.createdBy, "created_at" to record.createdAt, "activated_by" to record.activatedBy, "activated_at" to record.activatedAt,
            "deprecated_by" to record.deprecatedBy, "deprecated_at" to record.deprecatedAt, "archived_by" to record.archivedBy, "archived_at" to record.archivedAt)) }
        catch (failure: Exception) { throw IllegalStateException("Failed to save exact metric_version", failure) }
    }
    fun saveMetricDependency(record: MetricDependencyRecord) {
        requireText(record.id, "metric_expression_dependency.id"); requireText(record.metricId, "metric_expression_dependency.metric_id")
        requireVersion(record.metricVersion, "metric_expression_dependency.metric_version"); requireText(record.dependencyScope, "metric_expression_dependency.dependency_scope")
        requireText(record.dependencyType, "metric_expression_dependency.dependency_type"); requireText(record.dependencyId, "metric_expression_dependency.dependency_id")
        requireText(record.referencedSymbol, "metric_expression_dependency.referenced_symbol"); requireText(record.bindingStatus, "metric_expression_dependency.binding_status")
        GovernanceContract.requireInstant(record.createdAt, "metric_expression_dependency.created_at")
        try { insert(database, "metric_expression_dependency", linkedMapOf("id" to record.id, "metric_id" to record.metricId, "metric_version" to record.metricVersion,
            "dependency_scope" to record.dependencyScope, "dependency_type" to record.dependencyType, "dependency_id" to record.dependencyId,
            "dependency_version" to record.dependencyVersion, "referenced_symbol" to record.referencedSymbol, "binding_status" to record.bindingStatus,
            "diagnostic_code" to record.diagnosticCode, "created_at" to record.createdAt)) }
        catch (failure: Exception) { throw IllegalStateException("Failed to save metric_expression_dependency: ${record.id}", failure) }
    }
    fun listMetricDependencies(id: String, version: Long): List<MetricDependencyRecord> {
        requireText(id, "metric_expression_dependency.metric_id"); requireVersion(version, "metric_expression_dependency.metric_version")
        return try { query(database, "SELECT * FROM metric_expression_dependency WHERE metric_id=? AND metric_version=? ORDER BY dependency_scope,dependency_type,dependency_id", listOf(id, version)) {
            MetricDependencyRecord(it.getString("id"), it.getString("metric_id"), requireNotNull(nullableLong(it, "metric_version")), it.getString("dependency_scope"), it.getString("dependency_type"),
                it.getString("dependency_id"), nullableLong(it, "dependency_version"), it.getString("referenced_symbol"), it.getString("binding_status"), it.getString("diagnostic_code"), requireNotNull(instant(it, "created_at")))
        } } catch (failure: Exception) { throw IllegalStateException("Failed to list metric_expression_dependency: $id@$version", failure) }
    }
}
