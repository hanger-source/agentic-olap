package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.governance.contract.*

internal class EventSnapshots(private val reads: GovernanceSnapshotReader) {
    fun event(id: String, version: Long): EventRecord {
        val snapshot = reads.versionSnapshot("event_version", "event_id", id, version)
        val event = snapshot.content<GovernanceDefinition.Event>().record
        snapshot.requireId(id, event.id)
        reads.topology(event.topologyId, event.topologyVersion)
        ProducerContracts.requireVersionedRefPair(event.eventTimeLogicUnitId, event.eventTimeLogicUnitVersion, "event snapshot time role")
        val timeRefs = reads.versionRefs.listEventLogicUnitRefs(id, version).filter { it.refRole == "EVENT_TIME" }
        require(timeRefs.size == if (event.eventTimeLogicUnitId == null) 0 else 1) { "event snapshot EVENT_TIME role count conflicts with its declared time reference" }
        event.eventTimeLogicUnitId?.let { timeId ->
            val ref = timeRefs.single()
            require(timeId == ref.logicUnitId && event.eventTimeLogicUnitVersion == ref.logicUnitVersion) { "event time differs from its exact dependency index" }
            reads.logicUnit(timeId, requireNotNull(event.eventTimeLogicUnitVersion))
        }
        return event.copy(status = snapshot.versionStatus, currentVersion = version, createdBy = snapshot.createdBy,
            createdAt = snapshot.createdAt, updatedBy = snapshot.activatedBy, updatedAt = snapshot.activatedAt)
    }
    fun eventIdentity(id: String, version: Long): EventIdentity {
        val identity = reads.versionSnapshot("event_version", "event_id", id, version).content<GovernanceDefinition.Event>().identity
        val indexed = linkedMapOf<String, MutableSet<EventIdentity.LogicUnitRef>>()
        reads.versionRefs.listEventLogicUnitRefs(id, version).forEach { ref ->
            if (ref.refRole == "RECORD_KEY" || ref.refRole.startsWith("ENTITY:"))
                indexed.getOrPut(ref.refRole) { linkedSetOf() }.add(EventIdentity.LogicUnitRef(ref.logicUnitId, ref.logicUnitVersion))
        }
        val expected = linkedMapOf<String, Set<EventIdentity.LogicUnitRef>>()
        if (identity.recordKey.isNotEmpty()) expected["RECORD_KEY"] = identity.recordKey.toSet()
        identity.entityRoles.forEach { (role, refs) -> expected["ENTITY:$role"] = refs.toSet() }
        if (indexed != expected) throw ProducerContractException("event identity differs from its exact dependency index: $id@$version")
        return identity
    }
    fun eventRoles(id: String, version: Long): Map<String, RelationAccessPath> =
        reads.versionSnapshot("event_version", "event_id", id, version).content<GovernanceDefinition.Event>().roles
    companion object {
        fun versionContent(event: EventRecord, identity: EventIdentity, roles: Map<String, RelationAccessPath>,
            filterableRefs: List<EventCreateRequest.LogicUnitRefInput>, measureRefs: List<EventCreateRequest.LogicUnitRefInput>): GovernanceDefinition.Event =
            GovernanceDefinition.Event(event, identity, roles, filterableRefs, measureRefs)
    }
}
