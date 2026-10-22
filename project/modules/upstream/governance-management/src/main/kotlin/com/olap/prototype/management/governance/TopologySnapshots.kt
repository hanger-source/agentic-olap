package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.governance.contract.*
import com.olap.prototype.management.governance.GovernanceSnapshotJson.optionalText
import com.olap.prototype.management.governance.GovernanceSnapshotJson.requireMatchingId
import com.olap.prototype.management.governance.GovernanceSnapshotJson.requireOptionalMatchingLong
import com.olap.prototype.management.governance.GovernanceSnapshotJson.requireOptionalMatchingText
import com.olap.prototype.management.governance.GovernanceSnapshotJson.requiredLong
import com.olap.prototype.management.governance.GovernanceSnapshotJson.requiredText

internal class TopologySnapshots(private val reads: GovernanceSnapshotReader) {
    fun topology(id: String, version: Long): TopologyRecord = try {
        val snapshot = reads.versionSnapshot("topology_version", "topology_id", id, version)
        val content = snapshot.content; val field = "topology_version.content_json"
        val identity = requiredText(content, "topologyId", field); requireMatchingId(id, identity, "topology")
        val backbones = reads.versionRefs.listTopologyLogicalTableRefs(id, version).filter { it.usageRole == "BACKBONE_FACT" }
        if (backbones.size != 1) throw ProducerContractException("topology snapshot requires exactly one BACKBONE_FACT: $id@$version matches=${backbones.size}")
        val backbone = backbones.single(); reads.logicalTable(backbone.logicalTableId, backbone.logicalTableVersion)
        requireOptionalMatchingText(content, "backboneLogicalTableId", backbone.logicalTableId, field)
        requireOptionalMatchingLong(content, "backboneLogicalTableVersion", backbone.logicalTableVersion, field)
        TopologyRecord(identity, requiredText(content, "code", field), requiredText(content, "displayName", field), requiredText(content, "topologyImplementationNamespaceCode", field), requiredText(content, "topologyImplementationNamespaceDisplayName", field), backbone.logicalTableId, optionalText(content, "description"), requiredText(content, "owner", field), snapshot.versionStatus, version, snapshot.createdBy, snapshot.createdAt, snapshot.activatedBy, snapshot.activatedAt)
    } catch (failure: ProducerContractException) { throw failure }
    catch (failure: Exception) { throw IllegalStateException("exact aggregate snapshot decoding failed", failure) }
    fun joinPath(id: String, version: Long): JoinPathRecord {
        val snapshot = reads.versionSnapshot("join_path_version", "join_path_id", id, version)
        val content = snapshot.content; val field = "join_path_version.content_json"
        val identity = requiredText(content, "joinPathId", field); requireMatchingId(id, identity, "join_path")
        val topology = requiredText(content, "topologyId", field); val topologyVersion = requiredLong(content, "topologyVersion", field)
        val source = requiredText(content, "sourceLogicalTableId", field); val sourceVersion = requiredLong(content, "sourceLogicalTableVersion", field)
        val target = requiredText(content, "targetLogicalTableId", field); val targetVersion = requiredLong(content, "targetLogicalTableVersion", field)
        this.topology(topology, topologyVersion); reads.logicalTable(source, sourceVersion); reads.logicalTable(target, targetVersion)
        return JoinPathRecord(identity, topology, topologyVersion, requiredText(content, "code", field), source, sourceVersion, target, targetVersion, requiredText(content, "joinType", field), requiredText(content, "cardinality", field), optionalText(content, "temporalConfigJson"), optionalText(content, "nullPolicy"), snapshot.versionStatus, version, snapshot.createdBy, snapshot.createdAt)
    }
    companion object {
        fun topologyVersionContent(record: TopologyRecord): Map<String, Any?> = linkedMapOf("topologyId" to record.id, "code" to record.code, "displayName" to record.displayName, "topologyImplementationNamespaceCode" to record.topologyImplementationNamespaceCode, "topologyImplementationNamespaceDisplayName" to record.topologyImplementationNamespaceDisplayName, "description" to record.description, "owner" to record.owner)
        fun joinPathVersionContent(record: JoinPathRecord): Map<String, Any?> = linkedMapOf("joinPathId" to record.id, "topologyId" to record.topologyId, "topologyVersion" to record.topologyVersion, "code" to record.code, "sourceLogicalTableId" to record.sourceLogicalTableId, "sourceLogicalTableVersion" to record.sourceLogicalTableVersion, "targetLogicalTableId" to record.targetLogicalTableId, "targetLogicalTableVersion" to record.targetLogicalTableVersion, "joinType" to record.joinType, "cardinality" to record.cardinality, "temporalConfigJson" to record.temporalConfigJson, "nullPolicy" to record.nullPolicy, "status" to record.status)
    }
}
