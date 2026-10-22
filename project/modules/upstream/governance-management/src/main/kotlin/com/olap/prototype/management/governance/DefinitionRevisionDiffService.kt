package com.olap.prototype.management.governance

import com.olap.prototype.governance.contract.*
import com.olap.prototype.governance.contract.DefinitionRevisionDiff.Classification
import com.olap.prototype.governance.contract.DefinitionRevisionDiff.Value
import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.storage.PlatformDatabase

/** 比较正式定义与独立引用、授权事实；不通过编码字段名推测领域责任。 */
class DefinitionRevisionDiffService(private val database: PlatformDatabase) {
    private val snapshots = GovernanceSnapshotReader(database)
    private val adoptions = SubjectAreaAdoptionRepository(database)
    private val scenarios = ScenarioGovernanceRepository(database)
    private val analysis = AnalysisDimensionGovernanceRepository(database)
    private val subjects = SubjectAreaGovernanceRepository(database)
    private val refs = GovernanceVersionRefRepository(database)
    private val structural = GovernanceRepository(database)
    private val impact = DefinitionImpactService(database)

    fun read(request: DefinitionRevisionDiffRequest): DefinitionRevisionDiff = database.inReadSnapshot {
        val beforeRef = DefinitionImpact.Reference(request.assetType, request.id, request.fromVersion.toString())
        val afterRef = beforeRef.copy(version = request.toVersion.toString())
        val before = definition(request.assetType, request.id, request.fromVersion)
        val after = definition(request.assetType, request.id, request.toVersion)
        val changes = mutableListOf<DefinitionRevisionDiff.Change>()
        if (before != after) changes.add(DefinitionRevisionDiff.Change("/definition", Classification.CONTENT, before, after))
        owned(request, changes)
        if (request.assetType in setOf("BUSINESS_LINE", "SUBJECT_AREA", "SCENARIO")) {
            val asset = GovernanceDraftVersions.Asset.valueOf(request.assetType)
            val oldStatus = snapshots.versionSnapshot("${asset.table}_version", asset.column, request.id, request.fromVersion).versionStatus
            val newStatus = snapshots.versionSnapshot("${asset.table}_version", asset.column, request.id, request.toVersion).versionStatus
            if (oldStatus != newStatus) changes.add(DefinitionRevisionDiff.Change("/authorization/versionStatus", Classification.AUTHORIZATION, Value.Text(oldStatus), Value.Text(newStatus)))
        }
        val oldDependencies = impact.directReferences(DefinitionImpactRequest(request.assetType, request.id, request.fromVersion)).toSet()
        val newDependencies = impact.directReferences(DefinitionImpactRequest(request.assetType, request.id, request.toVersion)).toSet()
        DefinitionRevisionDiff(beforeRef, afterRef, changes, (newDependencies - oldDependencies).toList(), (oldDependencies - newDependencies).toList())
    }
    private fun definition(type: String, id: String, version: Long): Value = when (type) {
        "METRIC_ADOPTION" -> Value.MetricAdoptionValue(adoptions.metricAdoption(id, version))
        "MEMBER_SOURCE" -> Value.MemberSourceValue(adoptions.memberSource(id, version))
        else -> Value.Definition(snapshots.definition(type, id, version))
    }

    /** 选择由各资产仓储拥有；这里只比较正式读取值并生成不可执行的审阅显示。 */
    private fun owned(request: DefinitionRevisionDiffRequest, changes: MutableList<DefinitionRevisionDiff.Change>) {
        val id = request.id
        val before = request.fromVersion
        val after = request.toVersion
        fun <T> compare(path: String, classification: Classification, old: List<T>, current: List<T>, display: (T) -> String) {
            if (old != current) changes.add(DefinitionRevisionDiff.Change(path, classification,
                Value.Text(old.joinToString("\n", transform = display)), Value.Text(current.joinToString("\n", transform = display))))
        }
        when (request.assetType) {
            "LOGICAL_TABLE" -> compare("/members/logicUnits", Classification.DEPENDENCY, refs.listLogicalTableLogicUnitRefs(id, before), refs.listLogicalTableLogicUnitRefs(id, after)) { show(GovernanceProto.toProto(it)) }
            "LOGIC_UNIT" -> compare("/members/sources", Classification.DEPENDENCY, refs.listLogicUnitSourceRefs(id, before), refs.listLogicUnitSourceRefs(id, after)) { show(GovernanceProto.toProto(it)) }
            "TOPOLOGY" -> {
                compare("/members/tables", Classification.DEPENDENCY, refs.listTopologyLogicalTableRefs(id, before), refs.listTopologyLogicalTableRefs(id, after)) { show(GovernanceProto.toProto(it)) }
                compare("/members/joins", Classification.DEPENDENCY, refs.listTopologyJoinPathRefs(id, before), refs.listTopologyJoinPathRefs(id, after)) { show(GovernanceProto.toProto(it)) }
            }
            "JOIN_PATH" -> compare("/members/keys", Classification.IDENTITY_OR_STRUCTURE, refs.listJoinPathKeyRefs(id, before), refs.listJoinPathKeyRefs(id, after)) { show(GovernanceProto.toProto(it)) }
            "EVENT" -> {
                compare("/members/logicUnits", Classification.DEPENDENCY, refs.listEventLogicUnitRefs(id, before), refs.listEventLogicUnitRefs(id, after)) { show(GovernanceProto.toProto(it)) }
                compare("/members/filters", Classification.CONTENT, structural.listEventSliceFilters(id, before), structural.listEventSliceFilters(id, after)) { show(GovernanceProto.toProto(it)) }
            }
            "SCENARIO" -> {
                compare("/selection/metrics", Classification.DEPENDENCY, scenarios.listScenarioMetricRefs(id, before), scenarios.listScenarioMetricRefs(id, after)) { show(GovernanceProto.toProto(it)) }
                compare("/selection/dimensions", Classification.DEPENDENCY, scenarios.listScenarioAnalysisDimensionRefs(id, before), scenarios.listScenarioAnalysisDimensionRefs(id, after)) { show(GovernanceProto.toProto(it)) }
                compare("/authorization/stages", Classification.AUTHORIZATION, scenarios.listScenarioPipelineRules(id, before), scenarios.listScenarioPipelineRules(id, after)) { show(GovernanceProto.toProto(it)) }
                compare("/authorization/filters", Classification.AUTHORIZATION, scenarios.listScenarioFilterRefs(id, before), scenarios.listScenarioFilterRefs(id, after)) { show(GovernanceProto.toProto(it)) }
                compare("/authorization/members", Classification.AUTHORIZATION, scenarios.memberSourceRefs(id, before), scenarios.memberSourceRefs(id, after)) { show(GovernanceProto.toProto(it)) }
                compare("/authorization/relations", Classification.AUTHORIZATION, scenarios.relationFilters(id, before), scenarios.relationFilters(id, after)) { show(GovernanceProto.toProto(it)) }
            }
            "ANALYSIS_DIMENSION" -> {
                compare("/members/levels", Classification.IDENTITY_OR_STRUCTURE, analysis.listAnalysisDimensionLevels(id, before), analysis.listAnalysisDimensionLevels(id, after)) { show(GovernanceProto.toProto(it)) }
                compare("/members/mappings", Classification.DEPENDENCY, analysis.listAnalysisDimensionEventMappings(id, before), analysis.listAnalysisDimensionEventMappings(id, after)) { show(GovernanceProto.toProto(it)) }
                compare("/members/providers", Classification.DEPENDENCY, analysis.listAnalysisDimensionValueProviders(id, before), analysis.listAnalysisDimensionValueProviders(id, after)) { show(GovernanceProto.toProto(it)) }
                compare("/members/alignments", Classification.CONTENT, analysis.listAnalysisDimensionValueAlignments(id, before), analysis.listAnalysisDimensionValueAlignments(id, after)) { show(GovernanceProto.toProto(it)) }
            }
            "SUBJECT_AREA" -> {
                compare("/adoptions", Classification.DEPENDENCY,
                    adoptions.listSubjectAreaMetricRefs(id, before).map { adoptions.metricAdoption(it.id, it.subjectAreaVersion) },
                    adoptions.listSubjectAreaMetricRefs(id, after).map { adoptions.metricAdoption(it.id, it.subjectAreaVersion) }) {
                    show(GovernanceProto.toProto(it))
                }
                compare("/memberSources", Classification.DEPENDENCY, adoptions.memberSources(id, before), adoptions.memberSources(id, after)) {
                    show(GovernanceProto.toProto(it))
                }
                compare("/selection/dimensions", Classification.DEPENDENCY, analysis.listSubjectAreaAnalysisDimensionRefs(id, before), analysis.listSubjectAreaAnalysisDimensionRefs(id, after)) { show(GovernanceProto.toProto(it)) }
                compare("/selection/scenarios", Classification.DEPENDENCY, subjects.listSubjectAreaScenarioRefs(id, before), subjects.listSubjectAreaScenarioRefs(id, after)) { show(GovernanceProto.toProto(it)) }
            }
        }
    }
    private fun show(message: com.google.protobuf.Message): String = com.google.protobuf.TextFormat.shortDebugString(message)
}
