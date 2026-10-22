package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts.requireText
import com.olap.prototype.contract.ProducerContracts.requireVersion
import com.olap.prototype.contract.ProducerContracts.requireVersionedRefPair
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.event.EventRef
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.SemanticExpressionCodec
import com.olap.prototype.expression.SemanticExpressionInspector
import com.olap.prototype.expression.SemanticExpressionValueType
import com.olap.prototype.governance.contract.*
import com.olap.prototype.query.RelationClockDiscovery
import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.TemporalJoinContract
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.util.OlapJsonUtils
import java.time.Instant

/** Owns event versions, explicit record/entity identity, positioned roles and slice predicates. */
class EventGovernanceService(private val database: PlatformDatabase, private val topologies: TopologyReferenceResolver, private val nativeExpressions: NativeExpressionBindings) {
    private val repository = GovernanceRepository(database)
    private val snapshots = GovernanceSnapshotReader(database)
    fun createEvent(request: EventCreateRequest): EventCreateResult = database.inTransaction { create(request, null) }
    fun reviseEvent(request: GovernanceRevisionRequest<EventCreateRequest>): EventCreateResult = database.inTransaction { create(request.definition, request) }
    private fun create(request: EventCreateRequest, revision: GovernanceRevisionRequest<EventCreateRequest>?): EventCreateResult {
        val topologyId = requireText(request.topologyId, "event.topology_id").trim()
        val topologyVersion = requireVersion(request.topologyVersion, "event.topology_version")
        val topology = topologies.topologySnapshot(topologyId, topologyVersion)
        val code = requireText(request.code, "event.code").trim()
        if (!code.matches(Regex("[a-z][a-z0-9_]*"))) throw ProducerContractException("event.code must match [a-z][a-z0-9_]*")
        val id = revision?.objectId ?: "$topologyId.$code"
        val draft = GovernanceDraftVersions(database).start(GovernanceDraftVersions.Asset.EVENT, id, revision, request.createdBy)
        requireVersionedRefPair(request.eventTimeLogicUnitId, request.eventTimeLogicUnitVersion, "event.event_time_logic_unit")
        val timeId = request.eventTimeLogicUnitId?.let { requireText(it, "event.event_time_logic_unit_id").trim() }
        val actor = requireText(request.createdBy, "event.created_by").trim()
        val identity = request.identity
        val roles = java.util.Map.copyOf(request.roles)
        roles.forEach { (role, path) -> ScopedInputContract.requireId(role); topologies.requireAccessPath(topologyId, topologyVersion, path, "event.roles.$role") }
        identity.recordKey.forEach { key ->
            if (snapshots.logicUnit(key.logicUnitId, key.logicUnitVersion).logicalTableId != topology.backboneLogicalTableId)
                throw ProducerContractException("event record key must belong to the exact backbone")
        }
        val now = Instant.now()
        val event = EventRecord(id, code, requireText(request.displayName, "event.display_name").trim(), topologyId, topologyVersion, timeId,
            request.eventTimeLogicUnitVersion, request.description, requireText(request.owner, "event.owner").trim(), "DRAFT", draft.version, actor, now, actor, now)
        val version = EventVersionRecord("$id:v${draft.version}", id, draft.version, "DRAFT", json(EventSnapshots.versionContent(event, identity, roles, request.filterableLogicUnitRefs, request.measureLogicUnitRefs)),
            "{}", draft.sourceVersion, actor, now, null, null, null, null, null, null)
        val refs = buildList {
            if (timeId != null) add(logicRef(event, timeId, requireNotNull(request.eventTimeLogicUnitVersion), "EVENT_TIME", now))
            identity.recordKey.forEach { add(logicRef(event, it.logicUnitId, it.logicUnitVersion, "RECORD_KEY", now)) }
            identity.entityRoles.forEach { (role, keys) -> keys.forEach { add(logicRef(event, it.logicUnitId, it.logicUnitVersion, "ENTITY:$role", now)) } }
            fun addAuthored(inputs: List<EventCreateRequest.LogicUnitRefInput>, role: String) {
                inputs.forEach { add(logicRef(event, requireText(it.logicUnitId, "event_logic_unit_ref.logic_unit_id").trim(), requireVersion(it.logicUnitVersion, "event_logic_unit_ref.logic_unit_version"), role, now)) }
            }
            addAuthored(request.filterableLogicUnitRefs, "FILTERABLE")
            addAuthored(request.measureLogicUnitRefs, "MEASURE")
        }
        saveEvent(event); saveEventVersion(version); refs.forEach(repository::saveEventLogicUnitRef)
        return EventCreateResult(event, version, refs)
    }
    private fun logicRef(event: EventRecord, id: String, version: Long, role: String, now: Instant): EventLogicUnitRefRecord {
        val eventVersion = requireVersion(event.currentVersion, "event.current_version")
        topologies.requireReachableLogicUnit(event.topologyId, event.topologyVersion, id, version, "event_logic_unit_ref.logic_unit")
        return EventLogicUnitRefRecord("${event.id}:v$eventVersion:$role:$id:v$version", event.id, eventVersion, id, version, role, now)
    }
    internal fun saveEvent(record: EventRecord) {
        topologies.topologySnapshot(record.topologyId, record.topologyVersion)
        val timeId = record.eventTimeLogicUnitId
        if (!timeId.isNullOrBlank()) topologies.requireReachableLogicUnit(record.topologyId, record.topologyVersion, timeId, record.eventTimeLogicUnitVersion, "event.event_time_logic_unit")
        repository.saveEvent(record)
    }
    internal fun saveEventVersion(record: EventVersionRecord) {
        if (!repository.eventIdentityExists(record.eventId)) throw ProducerContractException("event_version.event_id not found: ${record.eventId}")
        repository.saveEventVersion(record)
    }
    fun findEventVersion(id: String, version: Long): EventVersionRecord? = repository.findEventVersion(id, version)
    fun listEventVersions(id: String): List<EventVersionRecord> = repository.listEventVersions(id)
    fun findEvent(id: String): EventRecord? = repository.findEvent(id)
    fun eventSnapshot(id: String, version: Long): EventRecord = snapshots.event(id, version)
    fun eventRoles(id: String, version: Long): Map<String, RelationAccessPath> = snapshots.eventRoles(id, version)
    fun eventIdentity(id: String, version: Long): EventIdentity = snapshots.eventIdentity(id, version)
    fun listEvents(): List<EventRecord> = repository.listEvents()
    fun listEventsByTopology(id: String, version: Long): List<EventRecord> = repository.listEventsByTopology(id, version)
    fun listEventLogicUnitRefs(id: String, version: Long): List<EventLogicUnitRefRecord> = repository.listEventLogicUnitRefs(id, version)
    fun listEventSliceFilters(id: String, version: Long): List<EventSliceFilterRecord> = repository.listEventSliceFilters(id, version)
    fun requireEvent(id: String, version: Long?, field: String): EventRecord = eventSnapshot(requireText(id, "$field.event_id").trim(), requireVersion(version, field))
    fun requireEvent(id: String, version: Long?): EventRecord = eventSnapshot(requireText(id, "event.id").trim(), requireVersion(version, "event.version"))

    fun requirePositionedLogicUnit(id: String, version: Long, path: RelationAccessPath, logicUnitId: String, logicUnitVersion: Long, field: String) {
        val event = eventSnapshot(id, version)
        topologies.requirePositionedLogicUnit(event.topologyId, event.topologyVersion, path, logicUnitId, logicUnitVersion, field)
    }
    fun requireRelatedEventRole(id: String, version: Long, role: String, targetId: String, targetVersion: Long) {
        val parent = eventSnapshot(id, version)
        val target = eventSnapshot(targetId, targetVersion)
        val path = requireNotNull(eventRoles(id, version)[role]) { "relationship role is not declared by the parent event" }
        if (path.joinPathRefs.isEmpty()) throw ProducerContractException("relation filter requires an actual association path")
        topologies.requireRelatedTopology(parent.topologyId, parent.topologyVersion, path, target.topologyId, target.topologyVersion, "relation filter role")
    }
    fun relationClocks(relationId: String, parent: EventRef, relationshipRole: String): Map<String, TemporalJoinContract.QueryClock> {
        val discovery = RelationClockDiscovery { topologies.temporalConstraint(it.joinPathId, it.joinPathVersion) }
        discovery.path("relation:$relationId", emptyList(), requireNotNull(eventRoles(parent.eventId, parent.eventVersion)[relationshipRole]) { "relation role is missing" })
        return discovery.slots()
    }
    fun baseClocks(id: String, version: Long): Map<String, TemporalJoinContract.QueryClock> {
        val discovery = RelationClockDiscovery { topologies.temporalConstraint(it.joinPathId, it.joinPathVersion) }
        val predicates = listEventSliceFilters(id, version).map { SemanticExpressionCodec.parse(it.filterJson, "event source predicate") }
        discovery.expression("event", RelationAccessPath(emptyList()), eventRoles(id, version), predicates)
        return discovery.slots()
    }

    fun requirePositionedExpression(id: String, version: Long, local: RelationAccessPath, roles: Map<String, RelationAccessPath>, expression: SemanticExpression, directRole: String?, field: String) {
        val event = eventSnapshot(id, version)
        SemanticExpressionInspector.nativeCalls(expression).forEach { nativeExpressions.function(it) }
        SemanticExpressionInspector.quantifiers(expression).forEach { quantifier ->
            val scopes = quantifier.parents + quantifier.expression
            topologies.requireAccessPath(event.topologyId, event.topologyVersion, SemanticExpressionInspector.localPath(scopes, local, roles), "$field.exists")
            val type = SemanticExpressionValueType.resolve(quantifier.expression.predicate, { ref -> ScalarValueType.fromLogicType(requireNotNull(snapshots.logicUnit(ref.id, ref.version).logicType)) }, nativeExpressions::function)
            if (type != ScalarValueType.BOOLEAN) throw ProducerContractException("$field quantified predicate must be BOOLEAN")
        }
        SemanticExpressionInspector.accesses(expression).forEach { access ->
            val ref = access.reference
            if (ref.referenceKind == SemanticExpression.ReferenceKind.PHYSICAL_COLUMN) throw ProducerContractException("$field cannot bypass semantic LogicUnits")
            topologies.requirePositionedLogicUnit(event.topologyId, event.topologyVersion, SemanticExpressionInspector.path(access, local, roles), ref.id, ref.version, field)
            val requiredRole = if (access.scopes.isEmpty()) directRole else "FILTERABLE"
            if (requiredRole == null) requireLogicUnitRef(id, version, ref.id, ref.version, field)
            else requireLogicUnitRole(id, version, ref.id, ref.version, requiredRole, field)
        }
    }
    fun requireLogicUnitRole(eventId: String, eventVersion: Long?, logicUnitId: String, logicUnitVersion: Long?, requiredRole: String, field: String) {
        if (listEventLogicUnitRefs(eventId, requireVersion(eventVersion, "event.version")).none { it.refRole == requiredRole && it.logicUnitId == logicUnitId && it.logicUnitVersion == logicUnitVersion })
            throw ProducerContractException("$field must be registered by event version with role $requiredRole: $eventId#$eventVersion -> $logicUnitId#$logicUnitVersion")
    }
    fun requireLogicUnitRef(eventId: String, eventVersion: Long?, logicUnitId: String, logicUnitVersion: Long?, field: String) {
        if (listEventLogicUnitRefs(eventId, requireVersion(eventVersion, "event.version")).none { it.logicUnitId == logicUnitId && it.logicUnitVersion == logicUnitVersion })
            throw ProducerContractException("$field must be registered by event version: $eventId#$eventVersion -> $logicUnitId#$logicUnitVersion")
    }
    internal fun saveEventLogicUnitRef(record: EventLogicUnitRefRecord) {
        val event = requireEvent(record.eventId, record.eventVersion)
        topologies.requireReachableLogicUnit(event.topologyId, event.topologyVersion, record.logicUnitId, record.logicUnitVersion, "event_logic_unit_ref.logic_unit")
        repository.saveEventLogicUnitRef(record)
    }
    internal fun saveEventSliceFilter(record: EventSliceFilterRecord) {
        val event = requireEvent(record.eventId, record.eventVersion)
        val expression = try {
            SemanticExpressionCodec.parse(record.filterJson, "event_slice_filter.filter_json").also {
                requirePositionedExpression(record.eventId, record.eventVersion, RelationAccessPath(emptyList()), eventRoles(record.eventId, record.eventVersion), it, "FILTERABLE", "event predicate")
            }
        } catch (failure: ProducerContractException) { throw failure }
          catch (failure: IllegalArgumentException) { throw ProducerContractException(requireNotNull(failure.message)) }
        val refs = SemanticExpressionInspector.references(expression).map { it.id to it.version }.distinct()
        if (refs.isEmpty() && SemanticExpressionInspector.quantifiers(expression).isEmpty()) throw ProducerContractException("event_slice_filter.filter_json 必须引用至少一个 LogicUnit: ${record.id}")
        refs.forEach { (id, version) ->
            topologies.requireReachableLogicUnit(event.topologyId, event.topologyVersion, id, version, "event_slice_filter.logic_unit")
            requireLogicUnitRole(record.eventId, record.eventVersion, id, version, "FILTERABLE", "event_slice_filter.logic_unit")
        }
        repository.saveEventSliceFilter(record)
    }
    private fun json(value: Any): String = try { OlapJsonUtils.mapper().writeValueAsString(value) } catch (failure: Exception) { throw ProducerContractException("event content_json invalid", failure) }
}
