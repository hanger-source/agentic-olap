package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.governance.contract.*

internal class TopologySnapshots(private val reads: GovernanceSnapshotReader) {
    fun topology(id: String, version: Long): TopologyRecord {
        val snapshot = reads.versionSnapshot("topology_version", "topology_id", id, version)
        val record = snapshot.content<GovernanceDefinition.Topology>().record
        snapshot.requireId(id, record.id)
        val backbones = reads.versionRefs.listTopologyLogicalTableRefs(id, version).filter { it.usageRole == "BACKBONE_FACT" }
        if (backbones.size != 1) throw ProducerContractException("topology requires exactly one exact backbone")
        val backbone = backbones.single()
        reads.logicalTable(backbone.logicalTableId, backbone.logicalTableVersion)
        require(record.backboneLogicalTableId == backbone.logicalTableId) { "topology backbone differs from its exact dependency index" }
        return record.copy(status = snapshot.versionStatus, currentVersion = version, createdBy = snapshot.createdBy,
            createdAt = snapshot.createdAt, updatedBy = snapshot.activatedBy, updatedAt = snapshot.activatedAt)
    }
    fun joinPath(id: String, version: Long): JoinPathRecord {
        val snapshot = reads.versionSnapshot("join_path_version", "join_path_id", id, version)
        val record = snapshot.content<GovernanceDefinition.JoinPath>().record
        snapshot.requireId(id, record.id)
        topology(record.topologyId, record.topologyVersion)
        reads.logicalTable(record.sourceLogicalTableId, record.sourceLogicalTableVersion)
        reads.logicalTable(record.targetLogicalTableId, record.targetLogicalTableVersion)
        return record.copy(status = snapshot.versionStatus, currentVersion = version, createdBy = snapshot.createdBy, createdAt = snapshot.createdAt)
    }
    companion object {
        fun topologyVersionContent(record: TopologyRecord): GovernanceDefinition.Topology = GovernanceDefinition.Topology(record)
        fun joinPathVersionContent(record: JoinPathRecord): GovernanceDefinition.JoinPath = GovernanceDefinition.JoinPath(record)
    }
}
