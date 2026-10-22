package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.governance.contract.*

internal class DimensionSnapshots(private val reads: GovernanceSnapshotReader) {
    fun dimension(id: String, version: Long): DimensionRecord {
        val snapshot = reads.versionSnapshot("dimension_version", "dimension_id", id, version)
        val record = snapshot.content<GovernanceDefinition.Dimension>().record
        snapshot.requireId(id, record.id)
        return record.copy(status = snapshot.versionStatus, currentVersion = version, createdBy = snapshot.createdBy,
            createdAt = snapshot.createdAt, updatedBy = snapshot.activatedBy, updatedAt = snapshot.activatedAt)
    }
    fun dimensionEventBinding(id: String, version: Long): DimensionEventBindingRecord {
        val snapshot = reads.versionSnapshot("dimension_event_binding_version", "binding_id", id, version)
        val record = snapshot.content<GovernanceDefinition.DimensionBinding>().record
        snapshot.requireId(id, record.id)
        dimension(record.dimensionId, record.dimensionVersion)
        val event = reads.event(record.eventId, record.eventVersion)
        reads.topology(record.topologyId, record.topologyVersion)
        reads.logicUnit(requireNotNull(record.logicUnitId), requireNotNull(record.logicUnitVersion))
        if (event.topologyId != record.topologyId || event.topologyVersion != record.topologyVersion)
            throw ProducerContractException("dimension binding topology differs from its exact event")
        requireNotNull(record.expression) { "dimension binding requires its exact semantic expression" }
        return record.copy(status = snapshot.versionStatus, currentVersion = version, createdBy = snapshot.createdBy,
            createdAt = snapshot.createdAt, updatedBy = snapshot.activatedBy, updatedAt = snapshot.activatedAt)
    }
    fun dimensionBindingRoles(id: String, version: Long): Map<String, RelationAccessPath> =
        reads.versionSnapshot("dimension_event_binding_version", "binding_id", id, version).content<GovernanceDefinition.DimensionBinding>().roles
    fun dimensionBindingAccessPath(id: String, version: Long): RelationAccessPath =
        reads.versionSnapshot("dimension_event_binding_version", "binding_id", id, version).content<GovernanceDefinition.DimensionBinding>().accessPath
    companion object {
        fun dimensionVersionContent(record: DimensionRecord): GovernanceDefinition.Dimension = GovernanceDefinition.Dimension(record)
        fun dimensionEventBindingVersionContent(record: DimensionEventBindingRecord, accessPath: RelationAccessPath, roles: Map<String, RelationAccessPath>): GovernanceDefinition.DimensionBinding =
            GovernanceDefinition.DimensionBinding(record, accessPath, roles)
    }
}
