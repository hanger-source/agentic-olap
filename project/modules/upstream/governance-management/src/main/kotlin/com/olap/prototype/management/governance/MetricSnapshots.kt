package com.olap.prototype.management.governance

import com.fasterxml.jackson.databind.node.ObjectNode
import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.governance.contract.MetricRecord
import com.olap.prototype.management.governance.GovernanceSnapshotJson.optionalText
import com.olap.prototype.management.governance.GovernanceSnapshotJson.requireMatchingId
import com.olap.prototype.management.governance.GovernanceSnapshotJson.requiredText
import com.olap.prototype.metric.MetricBusinessMeaning
import com.olap.prototype.util.OlapJsonUtils

internal class MetricSnapshots(private val reads: GovernanceSnapshotReader) {
    fun metric(id: String, version: Long): MetricRecord {
        val snapshot = reads.versionSnapshot("metric_version", "metric_id", id, version)
        val content = snapshot.content; val field = "metric_version.content_json"
        val identity = requiredText(content, "metricId", field); requireMatchingId(id, identity, "metric")
        val definition = try { OlapJsonUtils.mapper().treeToValue(content.required("definition"), MetricDefinition::class.java) }
            catch (failure: Exception) { throw ProducerContractException("metric_version.definition is invalid: $id@$version", failure) }
        if (definition is MetricDefinition.FactMeasure) reads.event(definition.event.eventId, definition.event.eventVersion)
        return MetricRecord(identity, requiredText(content, "code", field), requiredText(content, "displayName", field), definition, optionalText(content, "description"), requiredText(content, "owner", field), snapshot.versionStatus, version, snapshot.createdBy, snapshot.createdAt, snapshot.activatedBy, snapshot.activatedAt)
    }
    fun metricBusinessMeaning(id: String, version: Long): MetricBusinessMeaning {
        val content = reads.versionSnapshot("metric_version", "metric_id", id, version).content
        requireMatchingId(id, requiredText(content, "metricId", "metric_version.content_json"), "metric")
        val meaning = content.get("businessMeaning")
        if (meaning == null || !meaning.isObject) throw ProducerContractException("metric_version.content_json.businessMeaning is required: $id@$version")
        return try { OlapJsonUtils.mapper().treeToValue(meaning, MetricBusinessMeaning::class.java) }
            catch (failure: Exception) { throw ProducerContractException("metric_version.content_json.businessMeaning invalid: $id@$version", failure) }
    }
    fun metricExecutionDefinition(id: String, version: Long): ObjectNode = OlapJsonUtils.mapper().valueToTree(metric(id, version).definition)
    companion object {
        fun metricVersionContent(metric: MetricRecord, meaning: MetricBusinessMeaning): Map<String, Any?> = linkedMapOf("metricId" to metric.id, "code" to metric.code, "displayName" to metric.displayName, "definition" to OlapJsonUtils.mapper().valueToTree<ObjectNode>(metric.definition), "description" to metric.description, "businessMeaning" to meaning, "owner" to metric.owner)
    }
}
