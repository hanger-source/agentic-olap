package com.olap.prototype.governance.contract

import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.event.RelationAccessPath
import java.time.Instant
import java.util.Collections

class EventCreateRequest(val topologyId: String, val topologyVersion: Long, val code: String, val displayName: String, val owner: String,
     val eventTimeLogicUnitId: String?,
     val eventTimeLogicUnitVersion: Long?,
    val identity: EventIdentity, roles: Map<String, RelationAccessPath>, filterableLogicUnitRefs: List<LogicUnitRefInput>, measureLogicUnitRefs: List<LogicUnitRefInput>, val description: String?, val createdBy: String) {
    val roles: Map<String, RelationAccessPath> = Collections.unmodifiableMap(LinkedHashMap(roles))
    val filterableLogicUnitRefs: List<LogicUnitRefInput> = java.util.List.copyOf(filterableLogicUnitRefs)
    val measureLogicUnitRefs: List<LogicUnitRefInput> = java.util.List.copyOf(measureLogicUnitRefs)
    data class LogicUnitRefInput(val logicUnitId: String, val logicUnitVersion: Long)
    override fun equals(other: Any?): Boolean = other is EventCreateRequest && topologyId == other.topologyId && topologyVersion == other.topologyVersion && code == other.code && displayName == other.displayName && owner == other.owner && eventTimeLogicUnitId == other.eventTimeLogicUnitId && eventTimeLogicUnitVersion == other.eventTimeLogicUnitVersion && identity == other.identity && roles == other.roles && filterableLogicUnitRefs == other.filterableLogicUnitRefs && measureLogicUnitRefs == other.measureLogicUnitRefs && description == other.description && createdBy == other.createdBy
    override fun hashCode(): Int = listOf(topologyId, topologyVersion, code, displayName, owner, eventTimeLogicUnitId, eventTimeLogicUnitVersion, identity, roles, filterableLogicUnitRefs, measureLogicUnitRefs, description, createdBy).hashCode()
}
class EventCreateResult(val event: EventRecord, val eventVersion: EventVersionRecord, logicUnitRefs: List<EventLogicUnitRefRecord>) {
    val logicUnitRefs: List<EventLogicUnitRefRecord> = java.util.List.copyOf(logicUnitRefs)
    override fun equals(other: Any?): Boolean = other is EventCreateResult && event == other.event && eventVersion == other.eventVersion && logicUnitRefs == other.logicUnitRefs
    override fun hashCode(): Int = listOf(event, eventVersion, logicUnitRefs).hashCode()
}
data class EventRecord(val id: String, val code: String, val displayName: String, val topologyId: String, val topologyVersion: Long, val eventTimeLogicUnitId: String?, val eventTimeLogicUnitVersion: Long?, val description: String?, val owner: String, val status: String, val currentVersion: Long, val createdBy: String, val createdAt: Instant, val updatedBy: String?, val updatedAt: Instant?) {
    init {
        ProducerContracts.requireVersionedRefPair(eventTimeLogicUnitId, eventTimeLogicUnitVersion, "event time role")
        if (eventTimeLogicUnitId != null) ProducerContracts.requireText(eventTimeLogicUnitId, "event time role id")
    }
}
data class EventLogicUnitRefRecord(val id: String, val eventId: String, val eventVersion: Long, val logicUnitId: String, val logicUnitVersion: Long, val refRole: String, val createdAt: Instant)
data class EventSliceFilterRecord(val id: String, val eventId: String, val eventVersion: Long, val predicate: SemanticExpression, val createdAt: Instant)
data class EventVersionRecord(val id: String, val eventId: String, val version: Long, val versionStatus: String, val contentProto: String, val diagnosticJson: String?, val sourceVersion: Long?, val createdBy: String, val createdAt: Instant, val activatedBy: String?, val activatedAt: Instant?, val deprecatedBy: String?, val deprecatedAt: Instant?, val archivedBy: String?, val archivedAt: Instant?)
