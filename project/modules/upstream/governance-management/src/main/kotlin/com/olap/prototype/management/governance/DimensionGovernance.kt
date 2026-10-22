package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts.requireText
import com.olap.prototype.contract.ProducerContracts.requireVersion
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.SemanticExpressionCodec
import com.olap.prototype.expression.SemanticExpressionInspector
import com.olap.prototype.governance.contract.*
import com.olap.prototype.management.governance.GovernanceJdbcSupport.insert
import com.olap.prototype.management.governance.GovernanceJdbcSupport.query
import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.storage.PlatformDatabase
import java.time.Instant

interface DimensionBindingReferenceResolver {
    fun dimensionEventBindingSnapshot(bindingId: String, version: Long): DimensionEventBindingRecord
}

class DimensionGovernanceRepository(private val database: PlatformDatabase) {
    private val snapshots = GovernanceSnapshotReader(database)
    private val working = GovernanceWorkingVersionResolver(database)
    private val identities = GovernanceIdentityRepository(database)
    fun saveDimension(record: DimensionRecord) = identities.saveDimension(record)
    fun findDimension(id: String): DimensionRecord? {
        requireText(id, "dimension.id")
        return working.find("dimension_version", "dimension_id", id)?.let { snapshots.dimension(it.objectId, it.version) }
    }
    fun listDimensions(): List<DimensionRecord> = working.list("dimension_version", "dimension_id")
        .map { snapshots.dimension(it.objectId, it.version) }.sortedBy { it.code }
    fun dimensionIdentityExists(id: String): Boolean = identities.exists("dimension", id)
    fun dimensionVersionExists(id: String, version: Long): Boolean = exists("dimension_version", "dimension_id", id, version)
    fun dimensionEventBindingIdentityExists(id: String): Boolean = identities.exists("dimension_event_binding", id)
    fun dimensionEventBindingVersionExists(id: String, version: Long): Boolean = exists("dimension_event_binding_version", "binding_id", id, version)
    fun saveDimensionEventBinding(record: DimensionEventBindingRecord) = identities.saveDimensionEventBinding(record)
    fun findDimensionEventBinding(id: String): DimensionEventBindingRecord? {
        requireText(id, "dimension_event_binding.id")
        return working.find("dimension_event_binding_version", "binding_id", id)?.let { snapshots.dimensionEventBinding(it.objectId, it.version) }
    }
    private fun workingBindings(): List<DimensionEventBindingRecord> = working.list("dimension_event_binding_version", "binding_id").map { snapshots.dimensionEventBinding(it.objectId, it.version) }
    fun listDimensionEventBindings(id: String, version: Long): List<DimensionEventBindingRecord> {
        requireText(id, "dimension_event_binding.dimension_id"); requireVersion(version, "dimension_event_binding.dimension_version")
        return workingBindings().filter { it.dimensionId == id && it.dimensionVersion == version }
            .sortedWith(compareBy(DimensionEventBindingRecord::eventId, DimensionEventBindingRecord::eventVersion, DimensionEventBindingRecord::logicUnitId))
    }
    fun listDimensionEventBindingsByEvent(id: String, version: Long): List<DimensionEventBindingRecord> {
        requireText(id, "dimension_event_binding.event_id"); requireVersion(version, "dimension_event_binding.event_version")
        return workingBindings().filter { it.eventId == id && it.eventVersion == version }
            .sortedWith(compareBy(DimensionEventBindingRecord::dimensionId, DimensionEventBindingRecord::dimensionVersion, DimensionEventBindingRecord::logicUnitId))
    }
    fun listDimensionEventBindingCandidates(id: String, version: Long): List<DimensionEventBindingCandidateRecord> = listDimensionEventBindings(id, version).map {
        DimensionEventBindingCandidateRecord(it.id, it.currentVersion, it.dimensionId, it.dimensionVersion, it.eventId, it.eventVersion,
            snapshots.event(it.eventId, it.eventVersion).displayName, it.topologyId, it.topologyVersion, it.logicUnitId, it.logicUnitVersion,
            snapshots.logicUnit(requireText(it.logicUnitId, "dimension binding candidate.logic_unit_id"),
                requireVersion(it.logicUnitVersion, "dimension binding candidate.logic_unit_version")).displayName, it.status)
    }.sortedWith(compareBy(DimensionEventBindingCandidateRecord::eventDisplayName, DimensionEventBindingCandidateRecord::logicUnitDisplayName, DimensionEventBindingCandidateRecord::bindingVersion))

    fun saveDimensionVersion(record: DimensionVersionRecord) {
        GovernanceContract.requireVersionObject("dimension_version", "dimension_id", record.id, record.dimensionId, record.version, record.versionStatus, record.createdBy, record.createdAt)
        requireText(record.contentProto, "dimension_version.content_proto")
        try { insert(database, "dimension_version", linkedMapOf("id" to record.id, "dimension_id" to record.dimensionId, "version" to record.version,
            "version_status" to record.versionStatus, "content_proto" to record.contentProto, "diagnostic_json" to record.diagnosticJson, "source_version" to record.sourceVersion,
            "created_by" to record.createdBy, "created_at" to record.createdAt, "activated_by" to record.activatedBy, "activated_at" to record.activatedAt,
            "deprecated_by" to record.deprecatedBy, "deprecated_at" to record.deprecatedAt, "archived_by" to record.archivedBy, "archived_at" to record.archivedAt)) }
        catch (failure: Exception) { throw IllegalStateException("Failed to save dimension_version: ${record.id}", failure) }
    }
    fun saveDimensionEventBindingVersion(record: DimensionEventBindingVersionRecord) {
        GovernanceContract.requireVersionObject("dimension_event_binding_version", "binding_id", record.id, record.bindingId, record.version, record.versionStatus, record.createdBy, record.createdAt)
        requireText(record.contentProto, "dimension_event_binding_version.content_proto")
        try { insert(database, "dimension_event_binding_version", linkedMapOf("id" to record.id, "binding_id" to record.bindingId, "version" to record.version,
            "version_status" to record.versionStatus, "content_proto" to record.contentProto, "diagnostic_json" to record.diagnosticJson, "source_version" to record.sourceVersion,
            "created_by" to record.createdBy, "created_at" to record.createdAt, "activated_by" to record.activatedBy, "activated_at" to record.activatedAt,
            "deprecated_by" to record.deprecatedBy, "deprecated_at" to record.deprecatedAt, "archived_by" to record.archivedBy, "archived_at" to record.archivedAt)) }
        catch (failure: Exception) { throw IllegalStateException("Failed to save dimension_event_binding_version: ${record.id}", failure) }
    }
    private fun exists(table: String, column: String, id: String, version: Long): Boolean {
        requireText(id, "$table.$column"); requireVersion(version, "$table.version")
        return try { query(database, "SELECT 1 FROM $table WHERE $column=? AND version=?", listOf(id, version)) { true }.isNotEmpty() }
        catch (failure: Exception) { throw IllegalStateException("Failed to check $table: $id#$version", failure) }
    }
}

class DimensionGovernanceService(
    private val database: PlatformDatabase,
    private val topologies: TopologyGovernanceService,
    private val events: EventGovernanceService
) : DimensionBindingReferenceResolver {
    private val repository = DimensionGovernanceRepository(database)
    private val snapshots = GovernanceSnapshotReader(database)
    fun dimensionSnapshot(id: String, version: Long): DimensionRecord = snapshots.dimension(id, version)
    override fun dimensionEventBindingSnapshot(bindingId: String, version: Long): DimensionEventBindingRecord = snapshots.dimensionEventBinding(bindingId, version)
    fun bindingAccessPath(id: String, version: Long): RelationAccessPath = snapshots.dimensionBindingAccessPath(id, version)
    fun bindingRoles(id: String, version: Long): Map<String, RelationAccessPath> = snapshots.dimensionBindingRoles(id, version)
    fun findDimension(id: String): DimensionRecord? = repository.findDimension(id)
    fun listDimensions(): List<DimensionRecord> = repository.listDimensions()
    fun createDimension(request: DimensionCreateRequest): DimensionCreateResult = database.inTransaction { createDimension(request, null) }
    fun reviseDimension(request: GovernanceRevisionRequest<DimensionCreateRequest>): DimensionCreateResult = database.inTransaction { createDimension(request.definition, request) }
    private fun createDimension(request: DimensionCreateRequest, revision: GovernanceRevisionRequest<DimensionCreateRequest>?): DimensionCreateResult {
        val code = text(request.code, "dimension.code")
        if (!code.matches(Regex("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*"))) throw ProducerContractException("dimension.code must have exactly two dot-separated parts namespace.local, such as global.destination")
        val draft = GovernanceDraftVersions(database).start(GovernanceDraftVersions.Asset.DIMENSION, code, revision, request.createdBy)
        val (namespace, local) = code.split('.', limit = 2)
        val actor = text(request.createdBy, "dimension.created_by")
        val now = Instant.now()
        val record = DimensionRecord(code, code, namespace, text(request.dimensionCodeNamespaceDisplayName, "dimension.dimension_code_namespace_display_name"), local,
            text(request.displayName, "dimension.display_name"), request.description, text(request.owner, "dimension.owner"), "DRAFT", draft.version, actor, now, actor, now)
        val version = DimensionVersionRecord("$code:v${draft.version}", code, draft.version, "DRAFT", encodeDefinition(DimensionSnapshots.dimensionVersionContent(record)),
            "{}", draft.sourceVersion, actor, now, null, null, null, null, null, null)
        saveDimension(record); saveDimensionVersion(version)
        return DimensionCreateResult(record, version)
    }
    internal fun saveDimension(record: DimensionRecord) = repository.saveDimension(record)
    internal fun saveDimensionVersion(record: DimensionVersionRecord) {
        if (!repository.dimensionIdentityExists(record.dimensionId)) throw ProducerContractException("dimension_version.dimension_id not found: ${record.dimensionId}")
        repository.saveDimensionVersion(record)
    }

    fun createDimensionEventBinding(request: DimensionEventBindingCreateRequest): DimensionEventBindingCreateResult = database.inTransaction { createBinding(request, null) }
    fun reviseDimensionEventBinding(request: GovernanceRevisionRequest<DimensionEventBindingCreateRequest>): DimensionEventBindingCreateResult = database.inTransaction { createBinding(request.definition, request) }
    private fun createBinding(request: DimensionEventBindingCreateRequest, revision: GovernanceRevisionRequest<DimensionEventBindingCreateRequest>?): DimensionEventBindingCreateResult {
        val dimensionId = text(request.dimensionId, "dimension_event_binding.dimension_id")
        val dimensionVersion = positive(request.dimensionVersion, "dimension_event_binding.dimension_version")
        requireDimensionVersion(dimensionId, dimensionVersion, "dimension_event_binding.dimension_version")
        val eventVersion = positive(request.eventVersion, "dimension_event_binding.event_version")
        val event = events.requireEvent(request.eventId, eventVersion)
        val unitId = text(request.logicUnitId, "dimension_event_binding.logic_unit_id")
        val unitVersion = positive(request.logicUnitVersion, "dimension_event_binding.logic_unit_version")
        val accessPath = request.accessPath ?: throw ProducerContractException("dimension_event_binding.access_path is required")
        topologies.requirePositionedLogicUnit(event.topologyId, event.topologyVersion, accessPath, unitId, unitVersion, "dimension binding accessPath")
        val roles = java.util.Map.copyOf(request.roles)
        roles.forEach { (role, path) -> ScopedInputContract.requireId(role); topologies.requireAccessPath(event.topologyId, event.topologyVersion, path, "dimension binding role $role") }
        val actor = text(request.createdBy, "dimension_event_binding.created_by")
        val now = Instant.now()
        val id = revision?.objectId ?: "$dimensionId:event:${event.id}:logic:$unitId"
        val draft = GovernanceDraftVersions(database).start(GovernanceDraftVersions.Asset.DIMENSION_EVENT_BINDING, id, revision, actor)
        val expression = bindingExpression(request.expression, unitId, unitVersion)
        val binding = DimensionEventBindingRecord(id, dimensionId, dimensionVersion, event.id, eventVersion, event.topologyId, event.topologyVersion,
            unitId, unitVersion, expression, "DRAFT", draft.version, actor, now, actor, now)
        events.requirePositionedExpression(event.id, eventVersion, accessPath, roles, expression, null, "dimension expression")
        val version = DimensionEventBindingVersionRecord("$id:v${draft.version}", id, draft.version, "DRAFT",
            encodeDefinition(DimensionSnapshots.dimensionEventBindingVersionContent(binding, accessPath, roles)), "{}", draft.sourceVersion, actor, now, null, null, null, null, null, null)
        saveDimensionEventBinding(binding); saveDimensionEventBindingVersion(version)
        return DimensionEventBindingCreateResult(binding, version)
    }
    private fun bindingExpression(authored: SemanticExpression?, id: String, version: Long): SemanticExpression {
        val expression = authored ?: SemanticExpressionCodec.logicUnit(id, version)
        if (SemanticExpressionInspector.references(expression).none {
            it.referenceKind in setOf(SemanticExpression.ReferenceKind.LOGIC_UNIT, SemanticExpression.ReferenceKind.ROLE_LOGIC_UNIT) && it.id == id && it.version == version
        }) throw ProducerContractException("dimension_event_binding.expression_proto 必须引用已绑定 LogicUnit: $id@$version")
        return expression
    }
    internal fun saveDimensionEventBinding(record: DimensionEventBindingRecord) {
        requireDimensionVersion(record.dimensionId, record.dimensionVersion, "dimension_event_binding.dimension_version")
        val event = events.requireEvent(record.eventId, record.eventVersion)
        if (record.topologyId != event.topologyId) throw ProducerContractException("dimension_event_binding.topology_id must match event.topology_id")
        if (record.topologyVersion != event.topologyVersion) throw ProducerContractException("dimension_event_binding.topology_version must match event.topology_version")
        val unitId = text(record.logicUnitId, "dimension_event_binding.logic_unit_id")
        val unitVersion = positive(record.logicUnitVersion, "dimension_event_binding.logic_unit_version")
        topologies.requireReachableLogicUnit(event.topologyId, event.topologyVersion, unitId, unitVersion, "dimension_event_binding.logic_unit")
        events.requireLogicUnitRef(record.eventId, record.eventVersion, unitId, unitVersion, "dimension_event_binding.logic_unit")
        repository.saveDimensionEventBinding(record)
    }
    internal fun saveDimensionEventBindingVersion(record: DimensionEventBindingVersionRecord) {
        if (!repository.dimensionEventBindingIdentityExists(record.bindingId)) throw ProducerContractException("dimension_event_binding_version.binding_id not found: ${record.bindingId}")
        repository.saveDimensionEventBindingVersion(record)
    }
    fun listDimensionEventBindings(id: String, version: Long): List<DimensionEventBindingRecord> = repository.listDimensionEventBindings(id, version)
    fun listDimensionEventBindingsByEvent(id: String, version: Long): List<DimensionEventBindingRecord> = repository.listDimensionEventBindingsByEvent(id, version)
    fun listDimensionEventBindingCandidates(id: String, version: Long): List<DimensionEventBindingCandidateRecord> {
        requireDimensionVersion(id, version, "dimension_event_binding.dimension_version")
        return repository.listDimensionEventBindingCandidates(id, version)
    }
    fun requireDimensionVersion(dimensionId: String, version: Long?, field: String) { dimensionSnapshot(text(dimensionId, "$field.dimension_id"), positive(version, field)) }
    fun requireDimensionEventBindingVersion(bindingId: String, version: Long?, field: String) { dimensionEventBindingSnapshot(text(bindingId, "$field.binding_id"), positive(version, field)) }
    fun dimensionEventBindingContext(eventId: String, eventVersion: Long): DimensionEventBindingContextRecord {
        val event = events.requireEvent(eventId, eventVersion)
        val topology = topologies.topologyBindingContext(event.topologyId, event.topologyVersion)
        val existing = listDimensionEventBindingsByEvent(event.id, eventVersion).mapTo(hashSetOf()) { "${it.dimensionId}:v${it.dimensionVersion}" }
        val candidates = listDimensions().map {
            val disabled = when {
                it.currentVersion <= 0 -> "维度缺少当前版本，不能绑定事件"
                it.status.equals("ARCHIVED", ignoreCase = true) -> "已归档维度不能绑定事件"
                "${it.id}:v${it.currentVersion}" in existing -> "当前事件已绑定该维度版本"
                else -> null
            }
            DimensionEventBindingDimensionCandidateRecord(it.id, it.code, it.dimensionCodeNamespace, it.dimensionCodeNamespaceDisplayName, it.localCode, it.displayName, it.owner, it.status, it.currentVersion, disabled)
        }
        return DimensionEventBindingContextRecord(event, topology.topologyLogicalTableRefs, candidates, topology.reachableLogicUnits)
    }
    private fun text(value: String?, field: String): String = value?.trim()?.takeUnless { it.isEmpty() } ?: throw ProducerContractException("$field is required")
    private fun positive(value: Long?, field: String): Long = value?.takeIf { it > 0 } ?: throw ProducerContractException("$field is required and must be a positive version")
    private fun encodeDefinition(value: GovernanceDefinition): String = com.olap.prototype.codec.ProtoBytes.storage(com.olap.prototype.codec.GovernanceProto.definition(value))
}
