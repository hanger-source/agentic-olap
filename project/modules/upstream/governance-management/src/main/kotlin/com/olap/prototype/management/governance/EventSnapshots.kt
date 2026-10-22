package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.governance.contract.*
import com.olap.prototype.management.governance.GovernanceSnapshotJson.optionalText
import com.olap.prototype.management.governance.GovernanceSnapshotJson.requireMatchingId
import com.olap.prototype.management.governance.GovernanceSnapshotJson.requiredLong
import com.olap.prototype.management.governance.GovernanceSnapshotJson.requiredText
import com.olap.prototype.management.governance.GovernanceSnapshotJson.rolePaths
import com.olap.prototype.util.OlapJsonUtils

internal class EventSnapshots(private val reads: GovernanceSnapshotReader) {
    fun event(id: String, version: Long): EventRecord = try {
        val snapshot = reads.versionSnapshot("event_version", "event_id", id, version)
        val content = snapshot.content; val field = "event_version.content_json"
        val identity = requiredText(content, "eventId", field); requireMatchingId(id, identity, "event")
        val topology = requiredText(content, "topologyId", field); val topologyVersion = requiredLong(content, "topologyVersion", field)
        reads.topology(topology, topologyVersion)
        if (!content.has("eventTimeLogicUnitId") || !content.has("eventTimeLogicUnitVersion")) throw ProducerContractException("event snapshot must explicitly declare its time role or null pair")
        val timeId = if (content.get("eventTimeLogicUnitId").isNull) null else requiredText(content, "eventTimeLogicUnitId", field)
        val timeVersion = if (content.get("eventTimeLogicUnitVersion").isNull) null else requiredLong(content, "eventTimeLogicUnitVersion", field)
        ProducerContracts.requireVersionedRefPair(timeId, timeVersion, "event snapshot time role")
        val timeRefs = reads.versionRefs.listEventLogicUnitRefs(id, version).filter { it.refRole == "EVENT_TIME" }
        if (timeRefs.size != if (timeId == null) 0 else 1) throw ProducerContractException("event snapshot EVENT_TIME role count conflicts with its declared time reference: $id@$version matches=${timeRefs.size}")
        if (timeId != null) {
            val ref = timeRefs.single()
            if (timeId != ref.logicUnitId || timeVersion != ref.logicUnitVersion) throw ProducerContractException("event_version.content_json event time conflicts with event_logic_unit_ref")
            reads.logicUnit(timeId, requireNotNull(timeVersion))
        }
        EventRecord(identity, requiredText(content, "code", field), requiredText(content, "displayName", field), topology, topologyVersion, timeId, timeVersion, optionalText(content, "description"), requiredText(content, "owner", field), snapshot.versionStatus, version, snapshot.createdBy, snapshot.createdAt, snapshot.activatedBy, snapshot.activatedAt)
    } catch (failure: ProducerContractException) { throw failure }
    catch (failure: Exception) { throw IllegalStateException("exact aggregate snapshot decoding failed", failure) }

    fun eventIdentity(id: String, version: Long): EventIdentity {
        val content = reads.versionSnapshot("event_version", "event_id", id, version).content
        val identity = try { OlapJsonUtils.mapper().treeToValue(content.required("identity"), EventIdentity::class.java) }
            catch (failure: Exception) { throw ProducerContractException("event identity is invalid: $id@$version", failure) }
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
    fun eventRoles(id: String, version: Long): Map<String, RelationAccessPath> = rolePaths(reads.versionSnapshot("event_version", "event_id", id, version).content)
    companion object {
        fun versionContent(event: EventRecord, identity: EventIdentity, roles: Map<String, RelationAccessPath>, filterableRefs: List<EventCreateRequest.LogicUnitRefInput>, measureRefs: List<EventCreateRequest.LogicUnitRefInput>): Map<String, Any?> = linkedMapOf("eventId" to event.id, "code" to event.code, "displayName" to event.displayName, "topologyId" to event.topologyId, "topologyVersion" to event.topologyVersion, "eventTimeLogicUnitId" to event.eventTimeLogicUnitId, "eventTimeLogicUnitVersion" to event.eventTimeLogicUnitVersion, "identity" to identity, "roles" to roles, "filterableLogicUnitRefs" to filterableRefs, "measureLogicUnitRefs" to measureRefs, "description" to event.description, "owner" to event.owner)
    }
}
