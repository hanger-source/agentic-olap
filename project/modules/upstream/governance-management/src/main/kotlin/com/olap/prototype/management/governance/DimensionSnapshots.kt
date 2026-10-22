package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.governance.contract.*
import com.olap.prototype.management.governance.GovernanceSnapshotJson.optionalText
import com.olap.prototype.management.governance.GovernanceSnapshotJson.requireMatchingId
import com.olap.prototype.management.governance.GovernanceSnapshotJson.requiredLong
import com.olap.prototype.management.governance.GovernanceSnapshotJson.requiredText
import com.olap.prototype.management.governance.GovernanceSnapshotJson.rolePaths
import com.olap.prototype.util.OlapJsonUtils

internal class DimensionSnapshots(private val reads: GovernanceSnapshotReader) {
    fun dimension(id: String, version: Long): DimensionRecord {
        val snapshot = reads.versionSnapshot("dimension_version", "dimension_id", id, version)
        val content = snapshot.content; val field = "dimension_version.content_json"
        val identity = requiredText(content, "dimensionId", field); requireMatchingId(id, identity, "dimension")
        return DimensionRecord(identity, requiredText(content, "code", field), requiredText(content, "dimensionCodeNamespace", field), requiredText(content, "dimensionCodeNamespaceDisplayName", field), requiredText(content, "localCode", field), requiredText(content, "displayName", field), optionalText(content, "description"), requiredText(content, "owner", field), snapshot.versionStatus, version, snapshot.createdBy, snapshot.createdAt, snapshot.activatedBy, snapshot.activatedAt)
    }
    fun dimensionEventBinding(id: String, version: Long): DimensionEventBindingRecord {
        val snapshot = reads.versionSnapshot("dimension_event_binding_version", "binding_id", id, version)
        val content = snapshot.content; val field = "dimension_event_binding_version.content_json"
        val identity = requiredText(content, "bindingId", field); requireMatchingId(id, identity, "dimension_event_binding")
        val dimension = requiredText(content, "dimensionId", field); val dimensionVersion = requiredLong(content, "dimensionVersion", field)
        val event = requiredText(content, "eventId", field); val eventVersion = requiredLong(content, "eventVersion", field)
        val topology = requiredText(content, "topologyId", field); val topologyVersion = requiredLong(content, "topologyVersion", field)
        val unit = requiredText(content, "logicUnitId", field); val unitVersion = requiredLong(content, "logicUnitVersion", field)
        this.dimension(dimension, dimensionVersion)
        val eventRecord = reads.event(event, eventVersion)
        reads.topology(topology, topologyVersion); reads.logicUnit(unit, unitVersion)
        if (eventRecord.topologyId != topology || eventRecord.topologyVersion != topologyVersion) throw ProducerContractException("dimension_event_binding snapshot topology conflicts with event snapshot: $id@$version")
        return DimensionEventBindingRecord(identity, dimension, dimensionVersion, event, eventVersion, topology, topologyVersion, unit, unitVersion, requiredText(content, "expressionJson", field), snapshot.versionStatus, version, snapshot.createdBy, snapshot.createdAt, snapshot.activatedBy, snapshot.activatedAt)
    }
    fun dimensionBindingRoles(id: String, version: Long): Map<String, RelationAccessPath> = rolePaths(reads.versionSnapshot("dimension_event_binding_version", "binding_id", id, version).content)
    fun dimensionBindingAccessPath(id: String, version: Long): RelationAccessPath = try {
        OlapJsonUtils.mapper().treeToValue(reads.versionSnapshot("dimension_event_binding_version", "binding_id", id, version).content.required("accessPath"), RelationAccessPath::class.java)
    } catch (failure: Exception) { throw ProducerContractException("dimension binding must declare an exact access path", failure) }
    companion object {
        fun dimensionVersionContent(record: DimensionRecord): Map<String, Any?> = linkedMapOf("dimensionId" to record.id, "code" to record.code, "dimensionCodeNamespace" to record.dimensionCodeNamespace, "dimensionCodeNamespaceDisplayName" to record.dimensionCodeNamespaceDisplayName, "localCode" to record.localCode, "displayName" to record.displayName, "description" to record.description, "owner" to record.owner, "status" to record.status)
        fun dimensionEventBindingVersionContent(record: DimensionEventBindingRecord, accessPath: RelationAccessPath, roles: Map<String, RelationAccessPath>): Map<String, Any?> = linkedMapOf("bindingId" to record.id, "dimensionId" to record.dimensionId, "dimensionVersion" to record.dimensionVersion, "eventId" to record.eventId, "eventVersion" to record.eventVersion, "topologyId" to record.topologyId, "topologyVersion" to record.topologyVersion, "logicUnitId" to record.logicUnitId, "logicUnitVersion" to record.logicUnitVersion, "expressionJson" to record.expressionJson, "accessPath" to accessPath, "roles" to roles, "status" to record.status)
    }
}
