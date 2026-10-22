package com.olap.prototype.management.governance

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.calculation.MetricCalculation
import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.expression.SemanticExpressionInspector
import com.olap.prototype.governance.contract.*
import com.olap.prototype.management.governance.GovernanceJdbcSupport.execute
import com.olap.prototype.management.governance.GovernanceJdbcSupport.query
import com.olap.prototype.management.warehouse.WarehouseDataSourceReader
import com.olap.prototype.storage.PlatformDatabase
import java.time.Instant
import java.util.UUID

/** Exact aggregate lifecycle, owned child versions and audit commit in one transaction. */
class GovernanceVersionLifecycleService(private val database: PlatformDatabase, private val businessLines: BusinessLineGovernanceService,
    private val physicalAssets: PhysicalAssetGovernanceService, private val logicalModel: LogicalModelGovernanceService,
    private val topologies: TopologyGovernanceService, private val events: EventGovernanceService, private val dimensions: DimensionGovernanceService,
    private val metrics: MetricGovernanceService, private val subjectAreas: SubjectAreaGovernanceService, private val adoptions: SubjectAreaAdoptionService,
    private val analysisDimensions: AnalysisDimensionGovernanceService, private val memberDefinitions: MemberDefinitionGovernanceService,
    private val scenarios: ScenarioGovernanceService, private val diagnostics: DiagnosticLifecycleRepository, private val warehouseDataSources: WarehouseDataSourceReader) {
    fun activate(request: GovernanceVersionActivationRequest): GovernanceVersionActivationResult {
        val id = text(request.objectId, "objectId")
        val version = positive(request.objectVersion, "objectVersion")
        val actor = text(request.activatedBy, "activatedBy")
        return database.inTransaction {
            val type = request.objectType
            GovernanceDraftVersions(database).requireEditable(asset(type), id, version)
            val status = versionStatus(asset(type), id, version)
            if (status != "DRAFT") throw ProducerContractException("${type.name}#$id#$version must be DRAFT before activation, actual status=$status")
            validateDependencies(type, id, version)
            val at = Instant.now()
            val previous = activeVersion(type, id)
            if (previous != null) deprecateVersion(type, id, previous, actor, at)
            if (update("UPDATE ${asset(type).table}_version SET version_status='ACTIVE',activated_by=?,activated_at=? WHERE ${asset(type).column}=? AND version=? AND version_status='DRAFT'", actor, at, id, version) != 1)
                throw ProducerContractException("failed to activate version: ${type.name}#$id#$version")
            owned(type, id, version) { child, childId, childVersion -> activateChild(child, childId, childVersion, actor, at) }
            if (type == GovernanceActivationObjectType.SUBJECT_AREA) transitionOwnedRows(id, version, "DRAFT", "ACTIVE")
            diagnostics.saveLifecycleEvent(DiagnosticLifecycleRepository.LifecycleEventRecord(UUID.randomUUID().toString(), type.name, id, version, "ACTIVATED", request.reason?.trim()?.takeUnless { it.isEmpty() }, null, actor, at))
            GovernanceVersionActivationResult(type.name, id, version, previous, "ACTIVE", at)
        }
    }
    fun deprecate(request: GovernanceVersionDeprecationRequest): GovernanceVersionDeprecationResult {
        val id = text(request.objectId, "objectId")
        val version = positive(request.objectVersion, "objectVersion")
        val actor = text(request.deprecatedBy, "deprecatedBy")
        val reason = text(request.reason, "reason")
        return database.inTransaction {
            val type = request.objectType
            GovernanceDraftVersions(database).lock(asset(type), id)
            requireActive(versionStatus(asset(type), id, version), type.name, id, version)
            val at = Instant.now()
            deprecateVersion(type, id, version, actor, at)
            owned(type, id, version) { child, childId, childVersion ->
                GovernanceDraftVersions(database).lock(child, childId)
                if (update("UPDATE ${child.table}_version SET version_status='DEPRECATED',deprecated_by=?,deprecated_at=? WHERE ${child.column}=? AND version=? AND version_status='ACTIVE'", actor, at, childId, childVersion) != 1)
                    throw ProducerContractException("owned version is not ACTIVE: ${child.name}#$childId#$childVersion")
            }
            if (type == GovernanceActivationObjectType.SUBJECT_AREA) transitionOwnedRows(id, version, "ACTIVE", "DEPRECATED")
            diagnostics.saveLifecycleEvent(DiagnosticLifecycleRepository.LifecycleEventRecord(UUID.randomUUID().toString(), type.name, id, version, "DEPRECATED", reason, null, actor, at))
            GovernanceVersionDeprecationResult(type.name, id, version, "DEPRECATED", at)
        }
    }

    private fun validateDependencies(type: GovernanceActivationObjectType, id: String, version: Long) {
        when (type) {
            GovernanceActivationObjectType.MEMBER_DEFINITION -> memberDefinitions.validate(memberDefinitions.read(id, version).definition, true)
            GovernanceActivationObjectType.PHYSICAL_TABLE -> {
                val table = physicalAssets.physicalTableSnapshot(id, version)
                val source = warehouseDataSources.findDataSource(table.dataSource) ?: throw ProducerContractException("warehouse_data_source not found: ${table.dataSource}")
                requireActive(source.status, "WAREHOUSE_DATA_SOURCE", source.id, null)
                physicalAssets.physicalTableSourceConstraints(id, version)
                if (physicalAssets.physicalTableColumnSnapshots(id, version).isEmpty()) throw ProducerContractException("PHYSICAL_TABLE#$id#$version has no exact column versions")
            }
            GovernanceActivationObjectType.LOGICAL_TABLE -> validateLogical(id, version)
            GovernanceActivationObjectType.TOPOLOGY -> {
                topologies.listTopologyLogicalTableRefs(id, version).forEach { ref ->
                    val table = logicalModel.logicalTableSnapshot(ref.logicalTableId, ref.logicalTableVersion)
                    referenceable(table.status, "LOGICAL_TABLE", table.id, ref.logicalTableVersion)
                }
                topologies.listTopologyJoinPathRefs(id, version).forEach { ref -> topologies.listJoinPathKeyRefs(ref.joinPathId, ref.joinPathVersion).forEach {
                    requireActiveLogicUnit(it.sourceLogicUnitId, it.sourceLogicUnitVersion); requireActiveLogicUnit(it.targetLogicUnitId, it.targetLogicUnitVersion)
                } }
            }
            GovernanceActivationObjectType.EVENT -> {
                val event = events.eventSnapshot(id, version)
                events.eventIdentity(id, version)
                val topology = topologies.topologySnapshot(event.topologyId, event.topologyVersion)
                referenceable(topology.status, "TOPOLOGY", topology.id, event.topologyVersion)
                events.listEventLogicUnitRefs(id, version).forEach { requireActiveLogicUnit(it.logicUnitId, it.logicUnitVersion) }
            }
            GovernanceActivationObjectType.METRIC -> requireMetricDependencies(metrics.metricSnapshot(id, version))
            GovernanceActivationObjectType.DIMENSION, GovernanceActivationObjectType.BUSINESS_LINE -> Unit
            GovernanceActivationObjectType.DIMENSION_EVENT_BINDING -> {
                val binding = dimensions.dimensionEventBindingSnapshot(id, version)
                val dimension = dimensions.dimensionSnapshot(binding.dimensionId, binding.dimensionVersion)
                referenceable(dimension.status, "DIMENSION", dimension.id, binding.dimensionVersion)
                val event = events.eventSnapshot(binding.eventId, binding.eventVersion)
                referenceable(event.status, "EVENT", event.id, binding.eventVersion)
                val topology = topologies.topologySnapshot(binding.topologyId, binding.topologyVersion)
                referenceable(topology.status, "TOPOLOGY", topology.id, binding.topologyVersion)
                requireActiveLogicUnit(text(binding.logicUnitId, "dimension_event_binding.logic_unit_id"),
                    positive(binding.logicUnitVersion, "dimension_event_binding.logic_unit_version"))
            }
            GovernanceActivationObjectType.SUBJECT_AREA -> {
                val subject = subjectAreas.subjectAreaSnapshot(id, version)
                val business = businessLines.businessLineSnapshot(subject.businessLineId, subject.businessLineVersion)
                referenceable(business.status, "BUSINESS_LINE", business.id, subject.businessLineVersion)
                noRows("""SELECT 'metric#'||r.metric_id||'#'||r.metric_version FROM subject_area_metric_ref r
                    JOIN metric_version v ON v.metric_id=r.metric_id AND v.version=r.metric_version
                    WHERE r.subject_area_id=? AND r.subject_area_version=? AND v.version_status NOT IN ('ACTIVE','DEPRECATED')""", type, id, version, "EXACT_METRIC_DEPENDENCY_NOT_APPROVED", id, version)
                validateSubjectChildren(id, version)
            }
        }
    }
    private fun validateLogical(id: String, version: Long) {
        val refs = logicalModel.listPhysicalLogicalTableRefsByLogicalTable(id).filter { it.logicalTableVersion == version }
        if (refs.isEmpty()) throw ProducerContractException("LOGICAL_TABLE#$id#$version has no exact physical table reference")
        refs.forEach {
            val table = physicalAssets.physicalTableSnapshot(it.physicalTableId, it.physicalTableVersion)
            referenceable(table.status, "PHYSICAL_TABLE", table.id, it.physicalTableVersion)
            physicalAssets.physicalTableSourceConstraints(it.physicalTableId, it.physicalTableVersion)
        }
        logicalModel.listLogicalTableLogicUnitRefs(id, version).forEach { ref ->
            val expanded = logicalModel.expandLogicUnit(ref.logicUnitId, ref.logicUnitVersion)
            expanded.units.values.forEach {
                val owner = logicalModel.logicUnitOwnerRef(it.id, it.currentVersion)
                if (owner.logicalTableId != id || owner.logicalTableVersion != version) referenceable(it.status, "LOGIC_UNIT", it.id, it.currentVersion)
            }
            expanded.columns.values.forEach { referenceable(it.status, "PHYSICAL_COLUMN", it.id, it.currentVersion) }
        }
    }
    private fun requireMetricDependencies(metric: MetricRecord) {
        when (val definition = metric.definition) {
            is MetricDefinition.FactMeasure -> {
                metrics.requireFactRecordKey(definition.event)
                val event = events.eventSnapshot(definition.event.eventId, definition.event.eventVersion)
                referenceable(event.status, "EVENT", event.id, definition.event.eventVersion)
                val expressions = listOfNotNull(definition.input, definition.privatePredicate) + (definition.snapshotRule?.expressions() ?: emptyList())
                expressions.forEach { expression ->
                    SemanticExpressionInspector.references(expression).forEach { requireActiveLogicUnit(it.id, it.version) }
                    SemanticExpressionInspector.physicalTables(expression).forEach { physicalAssets.nativeTable(it) }
                }
            }
            is MetricCalculation -> {
                val calculation = definition.calculation
                calculation.callSites().forEach { site -> (site.call.target as? Calculation.Fixed)?.let {
                    val child = metrics.metricSnapshot(it.metric.id, it.metric.version.toLong())
                    referenceable(child.status, "METRIC", child.id, child.currentVersion)
                } }
                calculation.memberSlots.forEach { slot -> slot.fixedSource?.let { adoptions.validateMemberSource(adoptions.memberSource(it.id, it.version)) } }
                calculation.declaredDimensions().values.forEach {
                    val dimension = dimensions.dimensionSnapshot(it.dimensionId, it.dimensionVersion)
                    referenceable(dimension.status, "DIMENSION", dimension.id, it.dimensionVersion)
                }
            }
        }
    }
    private fun validateSubjectChildren(id: String, version: Long) {
        adoptions.memberSources(id, version).forEach(adoptions::validateMemberSource)
        subjectAreas.listSubjectAreaScenarioRefs(id, version).forEach {
            val scenario = scenarios.scenarioSnapshot(it.scenarioId, it.scenarioVersion)
            scenarios.validateMemberSourceRefs(scenario)
            scenarios.validateRelationFilters(scenario, scenarios.listScenarioMetricRefs(it.scenarioId, it.scenarioVersion), scenarios.relationFilters(it.scenarioId, it.scenarioVersion))
        }
        adoptions.listSubjectAreaMetricRefs(id, version).forEach { adoptions.validateMetricAdoption(it, adoptions.metricAdoptionBindings(it.id, it.subjectAreaVersion), adoptions.metricAdoption(it.id, it.subjectAreaVersion).fixedArguments) }
        noRows("""SELECT 'dimension#'||m.dimension_id||'#'||m.dimension_version FROM subject_area_analysis_dimension_ref r
            JOIN analysis_dimension_event_mapping m ON m.analysis_dimension_id=r.analysis_dimension_id AND m.analysis_dimension_version=r.analysis_dimension_version
            JOIN dimension_version v ON v.dimension_id=m.dimension_id AND v.version=m.dimension_version
            WHERE r.subject_area_id=? AND r.subject_area_version=? AND v.version_status NOT IN ('ACTIVE','DEPRECATED')""", GovernanceActivationObjectType.SUBJECT_AREA, id, version, "EXACT_DIMENSION_DEPENDENCY_NOT_APPROVED", id, version)
        noRows("""SELECT 'dimension_event_binding#'||m.binding_id||'#'||m.binding_version FROM subject_area_analysis_dimension_ref r
            JOIN analysis_dimension_event_mapping m ON m.analysis_dimension_id=r.analysis_dimension_id AND m.analysis_dimension_version=r.analysis_dimension_version
            JOIN dimension_event_binding_version v ON v.binding_id=m.binding_id AND v.version=m.binding_version
            WHERE r.subject_area_id=? AND r.subject_area_version=? AND v.version_status NOT IN ('ACTIVE','DEPRECATED')""", GovernanceActivationObjectType.SUBJECT_AREA, id, version, "EXACT_BINDING_DEPENDENCY_NOT_APPROVED", id, version)
        noRows("""SELECT 'metric#'||a.metric_id||'#'||a.metric_version FROM subject_area_scenario_ref sr
            JOIN scenario_metric_ref r ON r.scenario_id=sr.scenario_id AND r.scenario_version=sr.scenario_version
            JOIN subject_area_metric_ref a ON a.id=r.adoption_id AND a.subject_area_version=r.adoption_version
            JOIN metric_version v ON v.metric_id=a.metric_id AND v.version=a.metric_version
            WHERE sr.subject_area_id=? AND sr.subject_area_version=? AND v.version_status NOT IN ('ACTIVE','DEPRECATED')""", GovernanceActivationObjectType.SUBJECT_AREA, id, version, "EXACT_SCENARIO_METRIC_DEPENDENCY_NOT_APPROVED", id, version)
        noRows("""SELECT 'analysis_dimension#'||r.analysis_dimension_id||'#'||r.analysis_dimension_version FROM subject_area_analysis_dimension_ref r
            WHERE r.subject_area_id=? AND r.subject_area_version=? AND NOT EXISTS (SELECT 1 FROM analysis_dimension_event_mapping m
            WHERE m.analysis_dimension_id=r.analysis_dimension_id AND m.analysis_dimension_version=r.analysis_dimension_version)""", GovernanceActivationObjectType.SUBJECT_AREA, id, version, "ANALYSIS_DIMENSION_EVENT_MAPPINGS_REQUIRED", id, version)
        noRows("""SELECT 'scenario#'||r.scenario_id||'#'||r.scenario_version FROM subject_area_scenario_ref r WHERE r.subject_area_id=? AND r.subject_area_version=?
            AND (NOT EXISTS (SELECT 1 FROM scenario_metric_ref m WHERE m.scenario_id=r.scenario_id AND m.scenario_version=r.scenario_version)
            AND NOT EXISTS (SELECT 1 FROM scenario_member_source_ref m WHERE m.scenario_id=r.scenario_id AND m.scenario_version=r.scenario_version))""", GovernanceActivationObjectType.SUBJECT_AREA, id, version, "SCENARIO_REAL_INPUT_REQUIRED", id, version)
    }

    private fun owned(type: GovernanceActivationObjectType, id: String, version: Long, action: (GovernanceDraftVersions.Asset, String, Long) -> Unit) {
        when (type) {
            GovernanceActivationObjectType.PHYSICAL_TABLE -> physicalAssets.physicalTableColumnSnapshots(id, version).forEach { action(GovernanceDraftVersions.Asset.PHYSICAL_COLUMN, it.id, it.currentVersion) }
            GovernanceActivationObjectType.LOGICAL_TABLE -> logicalModel.listLogicalTableLogicUnitRefs(id, version).forEach { action(GovernanceDraftVersions.Asset.LOGIC_UNIT, it.logicUnitId, it.logicUnitVersion) }
            GovernanceActivationObjectType.TOPOLOGY -> topologies.listTopologyJoinPathRefs(id, version).forEach { action(GovernanceDraftVersions.Asset.JOIN_PATH, it.joinPathId, it.joinPathVersion) }
            GovernanceActivationObjectType.SUBJECT_AREA -> {
                analysisDimensions.listSubjectAreaAnalysisDimensionRefs(id, version).forEach { action(GovernanceDraftVersions.Asset.ANALYSIS_DIMENSION, it.analysisDimensionId, it.analysisDimensionVersion) }
                subjectAreas.listSubjectAreaScenarioRefs(id, version).forEach { action(GovernanceDraftVersions.Asset.SCENARIO, it.scenarioId, it.scenarioVersion) }
            }
            else -> Unit
        }
    }
    private fun transitionOwnedRows(id: String, version: Long, from: String, to: String) {
        // These three row kinds belong to exact adopted child versions, not all versions of an ID.
        update("""UPDATE analysis_dimension_level SET status=? WHERE status=? AND EXISTS (SELECT 1 FROM subject_area_analysis_dimension_ref r
            WHERE r.subject_area_id=? AND r.subject_area_version=? AND r.analysis_dimension_id=analysis_dimension_level.analysis_dimension_id
            AND r.analysis_dimension_version=analysis_dimension_level.analysis_dimension_version)""", to, from, id, version)
        update("""UPDATE analysis_dimension_value_provider SET status=? WHERE status=? AND EXISTS (SELECT 1 FROM subject_area_analysis_dimension_ref r
            WHERE r.subject_area_id=? AND r.subject_area_version=? AND r.analysis_dimension_id=analysis_dimension_value_provider.analysis_dimension_id
            AND r.analysis_dimension_version=analysis_dimension_value_provider.analysis_dimension_version)""", to, from, id, version)
        update("""UPDATE scenario_pipeline_rule SET status=? WHERE status=? AND EXISTS (SELECT 1 FROM subject_area_scenario_ref r
            WHERE r.subject_area_id=? AND r.subject_area_version=? AND r.scenario_id=scenario_pipeline_rule.scenario_id AND r.scenario_version=scenario_pipeline_rule.scenario_version)""", to, from, id, version)
        update("UPDATE subject_area_metric_ref SET status=? WHERE subject_area_id=? AND subject_area_version=? AND status=?", to, id, version, from)
        update("UPDATE subject_area_analysis_dimension_ref SET status=? WHERE subject_area_id=? AND subject_area_version=? AND status=?", to, id, version, from)
        update("UPDATE subject_area_scenario_ref SET status=? WHERE subject_area_id=? AND subject_area_version=? AND status=?", to, id, version, from)
    }
    private fun activateChild(asset: GovernanceDraftVersions.Asset, id: String, version: Long, actor: String, at: Instant) {
        GovernanceDraftVersions(database).lock(asset, id)
        val status = versionStatus(asset, id, version)
        if (status !in setOf("DRAFT", "ACTIVE")) throw ProducerContractException("${asset.table}#$id#$version cannot be activated from status=$status")
        if (status == "DRAFT") {
            update("UPDATE ${asset.table}_version SET version_status='DEPRECATED',deprecated_by=?,deprecated_at=? WHERE ${asset.column}=? AND version_status='ACTIVE'", actor, at, id)
            if (update("UPDATE ${asset.table}_version SET version_status='ACTIVE',activated_by=?,activated_at=? WHERE ${asset.column}=? AND version=? AND version_status='DRAFT'", actor, at, id, version) != 1)
                throw ProducerContractException("failed to activate owned ${asset.table}#$id#$version")
        }
    }
    private fun requireActiveLogicUnit(id: String, version: Long) { val unit = logicalModel.logicUnitSnapshot(id, version); referenceable(unit.status, "LOGIC_UNIT", unit.id, version) }
    private fun versionStatus(asset: GovernanceDraftVersions.Asset, id: String, version: Long): String = read("Failed to read version status") {
        query(database, "SELECT version_status FROM ${asset.table}_version WHERE ${asset.column}=? AND version=?", listOf(id, version)) { it.getString(1) }.firstOrNull()
            ?: throw ProducerContractException("version not found: ${asset.table}_version#$id#$version")
    }
    private fun activeVersion(type: GovernanceActivationObjectType, id: String): Long? = read("Failed to read active version") {
        val asset = asset(type)
        val versions = query(database, "SELECT version FROM ${asset.table}_version WHERE ${asset.column}=? AND version_status='ACTIVE'", listOf(id)) { it.getLong(1) }
        if (versions.size > 1) throw ProducerContractException("multiple ACTIVE versions exist for ${type.name}#$id")
        versions.singleOrNull()
    }
    private fun deprecateVersion(type: GovernanceActivationObjectType, id: String, version: Long, actor: String, at: Instant) {
        val asset = asset(type)
        if (update("UPDATE ${asset.table}_version SET version_status='DEPRECATED',deprecated_by=?,deprecated_at=? WHERE ${asset.column}=? AND version=? AND version_status='ACTIVE'", actor, at, id, version) != 1)
            throw ProducerContractException("failed to deprecate previous ACTIVE version: ${type.name}#$id#$version")
    }
    private fun noRows(sql: String, type: GovernanceActivationObjectType, id: String, version: Long, constraint: String, vararg parameters: Any?) {
        read("Failed to validate activation dependencies") {
            query(database, sql, parameters.toList()) { it.getString(1) }.firstOrNull()?.let { throw ProducerContractException("${type.name}#$id#$version activation rejected [$constraint]: $it") }
        }
    }
    private fun update(sql: String, vararg parameters: Any?): Int = try { execute(database, sql, parameters.toList()) }
        catch (failure: Exception) { throw IllegalStateException("Failed to update governance lifecycle", failure) }
    private fun <T> read(message: String, action: () -> T): T = try { action() } catch (failure: ProducerContractException) { throw failure } catch (failure: Exception) { throw IllegalStateException(message, failure) }
    private fun text(value: String?, field: String): String { require(!value.isNullOrBlank()) { "$field 不能为空" }; return value.trim() }
    private fun positive(value: Long?, field: String): Long { require(value != null && value > 0) { "$field 必须为正数" }; return value }
    private fun requireActive(status: String, type: String, id: String, version: Long?) {
        if (status != "ACTIVE") throw ProducerContractException("activation requires ACTIVE exact dependency: $type#$id${version?.let { "#$it" } ?: ""} status=$status")
    }
    private fun referenceable(status: String, type: String, id: String, version: Long) = GovernanceContract.requireReferenceableVersion(status, type, id, version)
    private fun asset(type: GovernanceActivationObjectType): GovernanceDraftVersions.Asset = when (type) {
        GovernanceActivationObjectType.PHYSICAL_TABLE -> GovernanceDraftVersions.Asset.PHYSICAL_TABLE
        GovernanceActivationObjectType.LOGICAL_TABLE -> GovernanceDraftVersions.Asset.LOGICAL_TABLE
        GovernanceActivationObjectType.TOPOLOGY -> GovernanceDraftVersions.Asset.TOPOLOGY
        GovernanceActivationObjectType.EVENT -> GovernanceDraftVersions.Asset.EVENT
        GovernanceActivationObjectType.METRIC -> GovernanceDraftVersions.Asset.METRIC
        GovernanceActivationObjectType.MEMBER_DEFINITION -> GovernanceDraftVersions.Asset.MEMBER_DEFINITION
        GovernanceActivationObjectType.DIMENSION -> GovernanceDraftVersions.Asset.DIMENSION
        GovernanceActivationObjectType.DIMENSION_EVENT_BINDING -> GovernanceDraftVersions.Asset.DIMENSION_EVENT_BINDING
        GovernanceActivationObjectType.BUSINESS_LINE -> GovernanceDraftVersions.Asset.BUSINESS_LINE
        GovernanceActivationObjectType.SUBJECT_AREA -> GovernanceDraftVersions.Asset.SUBJECT_AREA
    }
}
