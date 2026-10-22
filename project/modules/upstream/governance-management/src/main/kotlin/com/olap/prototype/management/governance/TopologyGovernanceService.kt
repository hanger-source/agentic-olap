package com.olap.prototype.management.governance

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.governance.contract.*
import com.olap.prototype.query.SourceJoinContract
import com.olap.prototype.query.TemporalJoinContract
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.util.OlapJsonUtils
import java.time.Instant

class TopologyGovernanceService(private val database: PlatformDatabase, private val logicalModel: LogicalModelGovernanceService) : TopologyReferenceResolver {
    private val repository = GovernanceRepository(database)
    private val versionRefs = GovernanceVersionRefRepository(database)
    private val snapshots = GovernanceSnapshotReader(database)
    override fun temporalConstraint(joinPathId: String, joinPathVersion: Long): TemporalJoinContract.Constraint? =
        TemporalJoinContract.parse(text(joinPathSnapshot(joinPathId, joinPathVersion).temporalConfigJson, "exact relationship temporal contract"), "exact relationship temporal contract")

    fun createTopology(request: TopologyCreateRequest): TopologyCreateResult = database.inTransaction { createTopology(request, null) }
    fun reviseTopology(request: GovernanceRevisionRequest<TopologyCreateRequest>): TopologyCreateResult = database.inTransaction { createTopology(request.definition, request) }
    private fun createTopology(request: TopologyCreateRequest, revision: GovernanceRevisionRequest<TopologyCreateRequest>?): TopologyCreateResult {
        val code = simpleCode(request.code, "topology.code")
        val namespace = text(request.topologyImplementationNamespaceCode, "topology.topology_implementation_namespace_code")
        if (!namespace.matches(Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+"))) throw ProducerContractException("topology.topology_implementation_namespace_code must be a dotted topology implementation namespace")
        val id = revision?.objectId ?: "$namespace.$code"
        val draft = GovernanceDraftVersions(database).start(GovernanceDraftVersions.Asset.TOPOLOGY, id, revision, request.createdBy)
        val backboneId = text(request.backboneLogicalTableId, "topology.backbone_logical_table_id")
        val backboneVersion = positive(request.backboneLogicalTableVersion, "topology.backbone_logical_table_version")
        logicalModel.requireLogicalTableVersion(backboneId, backboneVersion, "topology.backbone_logical_table_version")
        val backbone = logicalModel.logicalTableSnapshot(backboneId, backboneVersion)
        if (text(backbone.tableRole, "topology.backbone.logical_table.table_role") !in setOf("FACT", "SNAPSHOT", "AGGREGATE"))
            throw ProducerContractException("topology.backbone_logical_table_id must be a fact/snapshot/summary logical table: ${backbone.id}")
        val actor = text(request.createdBy, "topology.created_by")
        val now = Instant.now()
        val topology = TopologyRecord(id, code, text(request.displayName, "topology.display_name"), namespace,
            text(request.topologyImplementationNamespaceDisplayName, "topology.topology_implementation_namespace_display_name"), backboneId, request.description,
            text(request.owner, "topology.owner"), "DRAFT", draft.version, actor, now, actor, now)
        val version = TopologyVersionRecord("$id:v${draft.version}", id, draft.version, "DRAFT", json(TopologySnapshots.topologyVersionContent(topology)), "{}", draft.sourceVersion,
            actor, now, null, null, null, null, null, null)
        val ref = TopologyLogicalTableRefRecord("$id:v${draft.version}:logical-table:$backboneId:v$backboneVersion:BACKBONE_FACT", id, draft.version, backboneId, backboneVersion, "BACKBONE_FACT", now)
        saveTopology(topology); saveTopologyVersion(version); saveTopologyLogicalTableRef(ref)
        return TopologyCreateResult(topology, version, ref)
    }
    fun createTopologyLogicalTableRef(request: TopologyLogicalTableRefCreateRequest): TopologyLogicalTableRefCreateResult = database.inTransaction {
        GovernanceDraftVersions(database).requireEditable(GovernanceDraftVersions.Asset.TOPOLOGY, request.topologyId, request.topologyVersion)
        val id = text(request.topologyId, "topology_logical_table_ref.topology_id")
        val version = positive(request.topologyVersion, "topology_logical_table_ref.topology_version")
        validateTopologyVersion(id, version)
        val table = text(request.logicalTableId, "topology_logical_table_ref.logical_table_id")
        val tableVersion = positive(request.logicalTableVersion, "topology_logical_table_ref.logical_table_version")
        logicalModel.requireLogicalTableVersion(table, tableVersion, "topology_logical_table_ref.logical_table_version")
        if (listTopologyLogicalTableRefs(id, version).any { it.logicalTableId == table && it.logicalTableVersion == tableVersion })
            throw ProducerContractException("logical table version is already referenced by topology: $id#$version -> $table#$tableVersion")
        val role = request.usageRole.name
        val ref = TopologyLogicalTableRefRecord("$id:v$version:logical-table:$table:v$tableVersion:$role", id, version, table, tableVersion, role, Instant.now())
        saveTopologyLogicalTableRef(ref)
        TopologyLogicalTableRefCreateResult(ref)
    }

    private data class AccessTarget(val tableId: String, val tableVersion: Long)
    private fun accessTarget(id: String, version: Long, access: RelationAccessPath, field: String): AccessTarget {
        topologySnapshot(id, version)
        val roots = listTopologyLogicalTableRefs(id, version).filter { it.usageRole == "BACKBONE_FACT" }
        if (roots.size != 1) throw ProducerContractException("$field requires one exact topology backbone")
        var current = AccessTarget(roots.single().logicalTableId, roots.single().logicalTableVersion)
        val edges = listTopologyJoinPathRefs(id, version)
        access.joinPathRefs.forEach { ref ->
            if (edges.none { it.joinPathId == ref.joinPathId && it.joinPathVersion == ref.joinPathVersion }) throw ProducerContractException("$field path is outside the exact topology version")
            val edge = joinPathSnapshot(ref.joinPathId, ref.joinPathVersion)
            if (edge.sourceLogicalTableId != current.tableId || edge.sourceLogicalTableVersion != current.tableVersion) throw ProducerContractException("$field path must be continuous from its declared local root")
            current = AccessTarget(edge.targetLogicalTableId, edge.targetLogicalTableVersion)
        }
        return current
    }
    override fun requireAccessPath(topologyId: String, topologyVersion: Long, path: RelationAccessPath, field: String) { accessTarget(topologyId, topologyVersion, path, field) }
    override fun requireRelatedTopology(topologyId: String, topologyVersion: Long, path: RelationAccessPath, targetTopologyId: String, targetTopologyVersion: Long, field: String) {
        if (accessTarget(topologyId, topologyVersion, path, field) != accessTarget(targetTopologyId, targetTopologyVersion, RelationAccessPath(emptyList()), "$field.related"))
            throw ProducerContractException("$field must end at the exact related event backbone")
    }
    override fun requirePositionedLogicUnit(topologyId: String, topologyVersion: Long, path: RelationAccessPath, logicUnitId: String, logicUnitVersion: Long, field: String) {
        val target = accessTarget(topologyId, topologyVersion, path, field)
        val owner = logicalModel.logicUnitOwnerRef(logicUnitId, logicUnitVersion)
        if (target.tableId != owner.logicalTableId || target.tableVersion != owner.logicalTableVersion) throw ProducerContractException("$field LogicUnit owner differs from the explicit role position")
    }

    fun topologyBindingContext(topologyId: String, topologyVersion: Long): TopologyBindingContextRecord {
        val topology = topologySnapshot(topologyId, topologyVersion)
        val refs = listTopologyLogicalTableRefs(topologyId, topologyVersion)
        val tables = refs.map {
            logicalModel.requireLogicalTableVersion(it.logicalTableId, it.logicalTableVersion, "topology_binding_context.logical_table_version")
            logicalModel.logicalTableSnapshot(it.logicalTableId, it.logicalTableVersion)
        }
        val units = refs.flatMap { logicalModel.listLogicalTableLogicUnitRefs(it.logicalTableId, it.logicalTableVersion) }.map { logicalModel.logicUnitSnapshot(it.logicUnitId, it.logicUnitVersion) }
        val byTable = tables.associateBy { it.id }
        val reachable = units.map { unit ->
            val tableId = text(unit.logicalTableId, "topology_binding_context.logic_unit.logical_table_id")
            val table = byTable[tableId] ?: throw ProducerContractException("topology_binding_context.logic_unit.logical_table not found: $tableId")
            ReachableLogicUnitCandidateRecord(unit.id, tableId, text(table.displayName, "topology_binding_context.logic_unit.logical_table_display_name"),
                text(unit.code, "topology_binding_context.logic_unit.code"), text(unit.displayName, "topology_binding_context.logic_unit.display_name"),
                text(unit.sourceKind, "topology_binding_context.logic_unit.source_kind"), unit.expressionJson, unit.logicType, unit.valueShape,
                unit.logicType?.let { GovernanceContract.requireLogicType(it, "topology_binding_context.logic_unit.logic_type").displayName }, unit.isTime,
                unit.isPrimaryKey, unit.isForeignKey, unit.filterable, unit.aggregatable, text(unit.status, "topology_binding_context.logic_unit.status"),
                positive(unit.currentVersion, "topology_binding_context.logic_unit.current_version"), text(unit.createdBy, "topology_binding_context.logic_unit.created_by"), unit.createdAt, unit.updatedBy, unit.updatedAt)
        }
        val paths = listJoinPaths(topologyId, topologyVersion)
        val keys = paths.flatMap { listJoinPathKeyRefs(it.id, positive(it.currentVersion, "join_path.current_version")) }
        val targets = refs.associateTo(linkedMapOf()) { source -> source.logicalTableId to refs.filter { it.logicalTableId != source.logicalTableId }.map { ref ->
            val table = byTable[ref.logicalTableId] ?: throw ProducerContractException("topology_binding_context.logical_table not found: ${ref.logicalTableId}")
            val ready = table.currentVersion > 0 && !table.status.equals("ARCHIVED", ignoreCase = true)
            TopologyTableCandidateRecord(table.id, ref.logicalTableVersion, table.id, text(table.displayName, "topology_binding_context.logical_table.display_name"), table.tableRole, table.tableRole, null,
                table.status, ready, if (ready) null else "逻辑表缺少可用版本或已归档")
        } }
        val joinKeys = refs.associateTo(linkedMapOf()) { ref -> ref.logicalTableId to units.filter { it.logicalTableId == ref.logicalTableId && joinKeyCandidate(it) }.map { unit ->
            val type = text(unit.logicType, "logic_unit.logic_type")
            TopologyJoinKeyCandidateRecord(unit.code, positive(unit.currentVersion, "logic_unit.current_version"), type, GovernanceContract.requireLogicType(type, "logic_unit.logic_type").displayName,
                text(unit.displayName, "logic_unit.display_name"), unit.filterable, unit.isPrimaryKey)
        } }
        return TopologyBindingContextRecord(topology, refs, tables, reachable, paths, keys, targets, joinKeys)
    }
    private fun joinKeyCandidate(unit: LogicUnitRecord): Boolean = unit.valueShape == null && unit.currentVersion > 0 &&
        (unit.isPrimaryKey || unit.isForeignKey || unit.isTime || unit.logicType?.uppercase() in setOf("STRING", "NUMBER", "ID"))

    fun createJoinPath(request: JoinPathCreateRequest): JoinPathCreateResult = database.inTransaction { createJoinPath(request, null) }
    fun reviseJoinPath(request: GovernanceRevisionRequest<JoinPathCreateRequest>): JoinPathCreateResult = database.inTransaction { createJoinPath(request.definition, request) }
    private fun createJoinPath(request: JoinPathCreateRequest, revision: GovernanceRevisionRequest<JoinPathCreateRequest>?): JoinPathCreateResult {
        val topologyId = text(request.topologyId, "join_path.topology_id")
        val topologyVersion = positive(request.topologyVersion, "join_path.topology_version")
        GovernanceDraftVersions(database).requireEditable(GovernanceDraftVersions.Asset.TOPOLOGY, topologyId, topologyVersion)
        validateTopologyVersion(topologyId, topologyVersion)
        val code = simpleCode(request.code, "join_path.code")
        val source = text(request.sourceLogicalTableId, "join_path.source_logical_table_id")
        val sourceVersion = positive(request.sourceLogicalTableVersion, "join_path.source_logical_table_version")
        val target = text(request.targetLogicalTableId, "join_path.target_logical_table_id")
        val targetVersion = positive(request.targetLogicalTableVersion, "join_path.target_logical_table_version")
        if (source == target) throw ProducerContractException("join_path source and target logical table must be different: $source")
        logicalModel.requireLogicalTableVersion(source, sourceVersion, "join_path.source_logical_table_version")
        logicalModel.requireLogicalTableVersion(target, targetVersion, "join_path.target_logical_table_version")
        val join = text(request.joinType, "join_path.join_type")
        val cardinality = text(request.cardinality, "join_path.cardinality")
        val temporal = text(request.temporalConfigJson, "join_path.temporal_config_json")
        val policy = text(request.nullPolicy, "join_path.null_policy")
        SourceJoinContract.require(join, cardinality, policy)
        val actor = text(request.createdBy, "join_path.created_by")
        val now = Instant.now()
        if (request.keyRefs.isEmpty()) throw ProducerContractException("join_path_key_ref is required")
        val id = revision?.objectId ?: "$topologyId:join:$code"
        val draft = GovernanceDraftVersions(database).start(GovernanceDraftVersions.Asset.JOIN_PATH, id, revision, actor)
        val selected = revision?.let {
            val prior = joinPathSnapshot(id, requireNotNull(draft.sourceVersion))
            if (prior.topologyId != topologyId) throw ProducerContractException("JoinPath revision cannot change topology identity")
            draft.sourceVersion.takeIf { prior.topologyVersion == topologyVersion }
        }
        val path = JoinPathRecord(id, topologyId, topologyVersion, code, source, sourceVersion, target, targetVersion, join, cardinality, temporal, policy, "DRAFT", draft.version, actor, now)
        requireJoinPathDefinition(path)
        val version = JoinPathVersionRecord("$id:v${draft.version}", id, draft.version, "DRAFT", json(TopologySnapshots.joinPathVersionContent(path)), join, cardinality, temporal, policy,
            "{}", draft.sourceVersion, actor, now, null, null, null, null, null, null)
        val ref = TopologyJoinPathRefRecord("$topologyId:v$topologyVersion:join-path:$id:v${draft.version}", topologyId, topologyVersion, id, draft.version, now)
        val keys = request.keyRefs.mapIndexed { index, input ->
            val key = JoinPathKeyRefRecord("$id:v${draft.version}:key:${index + 1}", id, draft.version, index + 1, text(input.sourceLogicUnitId, "join_path_key_ref.source_logic_unit_id"),
                positive(input.sourceLogicUnitVersion, "join_path_key_ref.source_logic_unit_version"), text(input.targetLogicUnitId, "join_path_key_ref.target_logic_unit_id"),
                positive(input.targetLogicUnitVersion, "join_path_key_ref.target_logic_unit_version"), now)
            requireJoinPathKey(path, key); key
        }
        repository.saveJoinPath(path); versionRefs.saveJoinPathVersion(version); saveTopologyJoinPathRef(ref, selected); keys.forEach(versionRefs::saveJoinPathKeyRef)
        return JoinPathCreateResult(path, version, ref, keys)
    }

    private fun validateTemporal(path: JoinPathRecord) {
        val constraint = try { TemporalJoinContract.parse(text(path.temporalConfigJson, "join_path.temporal_config_json"), "join_path.temporal_config_json") }
        catch (invalid: IllegalArgumentException) { throw ProducerContractException(requireNotNull(invalid.message), invalid) }
        constraint?.intervals?.forEach { interval ->
            val clock = interval.sourceTime
            if (clock is TemporalJoinContract.LogicUnitTime) {
                var sourceTable = AccessTarget(path.sourceLogicalTableId, path.sourceLogicalTableVersion)
                if (clock is TemporalJoinContract.BackboneLogicUnitClock) {
                    val backbones = listTopologyLogicalTableRefs(path.topologyId, path.topologyVersion).filter { it.usageRole == "BACKBONE_FACT" }
                    if (backbones.size != 1) throw ProducerContractException("temporal backbone clock requires one exact topology backbone")
                    sourceTable = AccessTarget(backbones.single().logicalTableId, backbones.single().logicalTableVersion)
                }
                val visited = hashSetOf(sourceTable.tableId)
                clock.joinPathRefs.forEach { ref ->
                    val edge = requireJoinPathVersion(ref.joinPathId, ref.joinPathVersion, "temporal source time path")
                    if (clock is TemporalJoinContract.BackboneLogicUnitClock && edge.cardinality !in setOf("ONE_TO_ONE", "MANY_TO_ONE"))
                        throw ProducerContractException("temporal backbone clock path must not expand the fact grain")
                    val included = listTopologyJoinPathRefs(path.topologyId, path.topologyVersion).any { it.joinPathId == ref.joinPathId && it.joinPathVersion == ref.joinPathVersion }
                    if (!included || edge.topologyId != path.topologyId || edge.topologyVersion != path.topologyVersion || edge.sourceLogicalTableId != sourceTable.tableId ||
                        edge.sourceLogicalTableVersion != sourceTable.tableVersion || edge.targetLogicalTableId == path.targetLogicalTableId || !visited.add(edge.targetLogicalTableId))
                        throw ProducerContractException("temporal source time path must be continuous, in the same topology and independent of its target")
                    sourceTable = AccessTarget(edge.targetLogicalTableId, edge.targetLogicalTableVersion)
                }
                requireTemporalUnit(clock.logicUnitId, clock.logicUnitVersion, sourceTable, "join_path.temporal.source_time")
            }
            val target = AccessTarget(path.targetLogicalTableId, path.targetLogicalTableVersion)
            requireTemporalUnit(interval.targetValidFromLogicUnitId, interval.targetValidFromLogicUnitVersion, target, "join_path.temporal.target_valid_from")
            requireTemporalUnit(interval.targetValidToLogicUnitId, interval.targetValidToLogicUnitVersion, target, "join_path.temporal.target_valid_to")
            if (clock is TemporalJoinContract.QueryClock) listOf(
                logicalModel.logicUnitSnapshot(interval.targetValidFromLogicUnitId, interval.targetValidFromLogicUnitVersion),
                logicalModel.logicUnitSnapshot(interval.targetValidToLogicUnitId, interval.targetValidToLogicUnitVersion)).forEach {
                if (clock.valueType.name != it.logicType) throw ProducerContractException("query clock type must match both exact interval endpoint LogicUnit versions")
            }
        }
    }
    private fun requireTemporalUnit(id: String, version: Long, table: AccessTarget, field: String) {
        logicalModel.requireLogicUnitVersion(id, version, field)
        val unit = logicalModel.findLogicUnit(id) ?: throw ProducerContractException("$field not found: $id@$version")
        if (unit.logicalTableId != table.tableId) throw ProducerContractException("$field must belong to logical table ${table.tableId}@${table.tableVersion}")
    }
    private fun requireJoinPathDefinition(path: JoinPathRecord) {
        validateTopologyVersion(path.topologyId, path.topologyVersion)
        logicalModel.requireLogicalTableVersion(path.sourceLogicalTableId, path.sourceLogicalTableVersion, "join_path.source_logical_table_version")
        logicalModel.requireLogicalTableVersion(path.targetLogicalTableId, path.targetLogicalTableVersion, "join_path.target_logical_table_version")
        validateTemporal(path)
        requirePathMembers(path.topologyId, path.topologyVersion, path)
    }
    private fun requirePathMembers(id: String, version: Long, path: JoinPathRecord) {
        val members = listTopologyLogicalTableRefs(id, version).mapTo(hashSetOf()) { "${it.logicalTableId}#${it.logicalTableVersion}" }
        if ("${path.sourceLogicalTableId}#${path.sourceLogicalTableVersion}" !in members || "${path.targetLogicalTableId}#${path.targetLogicalTableVersion}" !in members)
            throw ProducerContractException("topology_join_path_ref.join_path must connect logical table versions already in topology: ${path.id}")
    }
    private fun requireJoinPathKey(path: JoinPathRecord, key: JoinPathKeyRefRecord) {
        logicalModel.requireLogicUnitVersion(key.sourceLogicUnitId, key.sourceLogicUnitVersion, "join_path_key_ref.source_logic_unit_version")
        logicalModel.requireLogicUnitVersion(key.targetLogicUnitId, key.targetLogicUnitVersion, "join_path_key_ref.target_logic_unit_version")
        val source = logicalModel.logicUnitSnapshot(key.sourceLogicUnitId, key.sourceLogicUnitVersion)
        val target = logicalModel.logicUnitSnapshot(key.targetLogicUnitId, key.targetLogicUnitVersion)
        if (source.logicalTableId != path.sourceLogicalTableId) throw ProducerContractException("join_path_key_ref.source_logic_unit.logical_table_id must match join_path.source_logical_table_id")
        if (target.logicalTableId != path.targetLogicalTableId) throw ProducerContractException("join_path_key_ref.target_logic_unit.logical_table_id must match join_path.target_logical_table_id")
    }
    private fun validateTopologyVersion(id: String, version: Long?) {
        text(id, "topology.id"); val exact = positive(version, "topology.version")
        if (!repository.topologyIdentityExists(id)) throw ProducerContractException("topology not found: $id")
        if (!repository.topologyVersionExists(id, exact)) throw ProducerContractException("topology_version not found: $id#$version")
    }
    fun requireTopologyVersion(id: String, version: Long?, field: String) { validateTopologyVersion(id, version) }
    fun requireJoinPathVersion(id: String, version: Long?, field: String): JoinPathRecord = joinPathSnapshot(text(id, "$field.join_path_id"), positive(version, field))
    override fun requireReachableLogicUnit(topologyId: String, topologyVersion: Long?, logicUnitId: String, logicUnitVersion: Long?, field: String) {
        val version = positive(logicUnitVersion, "${field}_version")
        if (!repository.logicUnitVersionExists(logicUnitId, version)) throw ProducerContractException("${field}_version not found: $logicUnitId#$logicUnitVersion")
        val unit = logicalModel.logicUnitSnapshot(logicUnitId, version)
        if (listTopologyLogicalTableRefs(topologyId, positive(topologyVersion, "topology.version")).none { it.logicalTableId == unit.logicalTableId })
            throw ProducerContractException("$field must belong to logical table reachable from topology: $logicUnitId not in $topologyId#$topologyVersion")
    }
    internal fun saveTopology(record: TopologyRecord) = repository.saveTopology(record)
    internal fun saveTopologyVersion(record: TopologyVersionRecord) {
        if (!repository.topologyIdentityExists(record.topologyId)) throw ProducerContractException("topology_version.topology_id not found: ${record.topologyId}")
        versionRefs.saveTopologyVersion(record)
    }
    internal fun saveTopologyLogicalTableRef(record: TopologyLogicalTableRefRecord) {
        validateTopologyVersion(record.topologyId, record.topologyVersion)
        logicalModel.requireLogicalTableVersion(record.logicalTableId, record.logicalTableVersion, "topology_logical_table_ref.logical_table_version")
        repository.saveTopologyLogicalTableRef(record)
    }
    internal fun saveTopologyJoinPathRef(record: TopologyJoinPathRefRecord, selectedSourceVersion: Long?) {
        validateTopologyVersion(record.topologyId, record.topologyVersion)
        val path = requireJoinPathVersion(record.joinPathId, record.joinPathVersion, "topology_join_path_ref.join_path_version")
        requirePathMembers(record.topologyId, record.topologyVersion, path)
        if (selectedSourceVersion == null) versionRefs.saveTopologyJoinPathRef(record) else versionRefs.replaceTopologyJoinPathRef(record, selectedSourceVersion)
    }
    internal fun saveJoinPath(record: JoinPathRecord) { requireJoinPathDefinition(record); repository.saveJoinPath(record) }
    internal fun saveJoinPathVersion(record: JoinPathVersionRecord) {
        if (!repository.joinPathIdentityExists(record.joinPathId)) throw ProducerContractException("join_path_version.join_path_id not found: ${record.joinPathId}")
        versionRefs.saveJoinPathVersion(record)
    }
    internal fun saveJoinPathKeyRef(record: JoinPathKeyRefRecord) { requireJoinPathKey(requireJoinPathVersion(record.joinPathId, record.joinPathVersion, "join_path_key_ref.join_path_version"), record); versionRefs.saveJoinPathKeyRef(record) }
    fun findTopology(id: String): TopologyRecord? = repository.findTopology(id)
    override fun topologySnapshot(topologyId: String, topologyVersion: Long): TopologyRecord = snapshots.topology(topologyId, topologyVersion)
    fun listTopologies(): List<TopologyRecord> = repository.listTopologies()
    fun listTopologyLogicalTableRefs(id: String, version: Long): List<TopologyLogicalTableRefRecord> = repository.listTopologyLogicalTableRefs(id, version)
    fun listTopologyLogicalTableRefsByLogicalTable(id: String): List<TopologyLogicalTableRefRecord> = repository.listTopologyLogicalTableRefsByLogicalTable(id)
    fun listTopologyJoinPathRefs(id: String, version: Long): List<TopologyJoinPathRefRecord> { validateTopologyVersion(id, version); return versionRefs.listTopologyJoinPathRefs(id, version) }
    fun listJoinPaths(id: String, version: Long): List<JoinPathRecord> = repository.listJoinPaths(id, version)
    fun listJoinPathKeyRefs(id: String, version: Long): List<JoinPathKeyRefRecord> { requireJoinPathVersion(id, version, "join_path_key_ref.join_path_version"); return versionRefs.listJoinPathKeyRefs(id, version) }
    fun joinPathSnapshot(id: String, version: Long): JoinPathRecord = snapshots.joinPath(id, version)
    private fun text(value: String?, field: String): String = value?.trim()?.takeUnless { it.isEmpty() } ?: throw ProducerContractException("$field is required")
    private fun positive(value: Long?, field: String): Long = value?.takeIf { it > 0 } ?: throw ProducerContractException("$field is required and must be a positive version")
    private fun simpleCode(value: String, field: String): String = text(value, field).also { if (!it.matches(Regex("[a-z][a-z0-9_]*"))) throw ProducerContractException("$field must be lower snake case and start with a letter") }
    private fun json(value: Any): String = try { OlapJsonUtils.mapper().writeValueAsString(value) } catch (failure: Exception) { throw ProducerContractException("canonical governance content_json invalid", failure) }
}
