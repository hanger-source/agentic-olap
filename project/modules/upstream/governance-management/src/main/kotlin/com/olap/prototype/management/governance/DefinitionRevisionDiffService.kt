package com.olap.prototype.management.governance

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.governance.contract.*
import com.olap.prototype.governance.contract.DefinitionRevisionDiff.Classification
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.util.OlapJsonUtils

/** 从正式精确版本和其所有权表读取；不持久化差异或推断业务口径。 */
class DefinitionRevisionDiffService(private val database: PlatformDatabase) {
    private val snapshots = GovernanceSnapshotReader(database)
    private val adoptions = SubjectAreaAdoptionRepository(database)
    private val scenarios = ScenarioGovernanceRepository(database)
    private val analysis = AnalysisDimensionGovernanceRepository(database)
    private val subjects = SubjectAreaGovernanceRepository(database)
    private val impact = DefinitionImpactService(database)
    private val refs = GovernanceVersionRefRepository(database)
    private val structural = GovernanceRepository(database)

    fun read(request: DefinitionRevisionDiffRequest): DefinitionRevisionDiff = database.inReadSnapshot {
        val before = DefinitionImpact.Reference(request.assetType, request.id, request.fromVersion.toString())
        val after = before.copy(version = request.toVersion.toString())
        val changes = mutableListOf<DefinitionRevisionDiff.Change>()
        difference("", definition(request.assetType, request.id, request.fromVersion), definition(request.assetType, request.id, request.toVersion), changes)
        val oldDependencies = impact.directReferences(DefinitionImpactRequest(request.assetType, request.id, request.fromVersion)).toSet()
        val newDependencies = impact.directReferences(DefinitionImpactRequest(request.assetType, request.id, request.toVersion)).toSet()
        DefinitionRevisionDiff(before, after, changes.toList(), (newDependencies - oldDependencies).toList(), (oldDependencies - newDependencies).toList())
    }

    private fun definition(type: String, id: String, version: Long): JsonNode {
        val result = OlapJsonUtils.mapper().createObjectNode()
        val content: Any = when (type) {
            "METRIC_ADOPTION" -> adoptions.metricAdoption(id, version)
            "MEMBER_SOURCE" -> adoptions.memberSource(id, version)
            else -> snapshots.definition(type, id, version)
        }
        result.set<JsonNode>("definition", OlapJsonUtils.mapper().valueToTree(content))
        if (type in setOf("BUSINESS_LINE", "SUBJECT_AREA", "SCENARIO")) {
            val asset = GovernanceDraftVersions.Asset.valueOf(type)
            result.set<JsonNode>("authorizationContext", OlapJsonUtils.mapper().valueToTree(mapOf("versionStatus" to snapshots.versionSnapshot("${asset.table}_version", asset.column, id, version).versionStatus)))
        }
        when (type) {
            "LOGICAL_TABLE" -> result.set<JsonNode>("members", OlapJsonUtils.mapper().valueToTree(refs.listLogicalTableLogicUnitRefs(id, version)))
            "LOGIC_UNIT" -> result.set<JsonNode>("members", OlapJsonUtils.mapper().valueToTree(refs.listLogicUnitSourceRefs(id, version)))
            "TOPOLOGY" -> result.set<JsonNode>("members", OlapJsonUtils.mapper().valueToTree(mapOf("tables" to refs.listTopologyLogicalTableRefs(id, version), "joins" to refs.listTopologyJoinPathRefs(id, version))))
            "JOIN_PATH" -> result.set<JsonNode>("members", OlapJsonUtils.mapper().valueToTree(refs.listJoinPathKeyRefs(id, version)))
            "EVENT" -> result.set<JsonNode>("members", OlapJsonUtils.mapper().valueToTree(mapOf("units" to refs.listEventLogicUnitRefs(id, version), "filters" to structural.listEventSliceFilters(id, version))))
            "SCENARIO" -> {
                result.set<JsonNode>("selection", OlapJsonUtils.mapper().valueToTree(mapOf(
                    "metrics" to scenarios.listScenarioMetricRefs(id, version), "dimensions" to scenarios.listScenarioAnalysisDimensionRefs(id, version),
                    "pipelineRules" to scenarios.listScenarioPipelineRules(id, version))))
                result.set<JsonNode>("authorization", OlapJsonUtils.mapper().valueToTree(mapOf(
                    "filters" to scenarios.listScenarioFilterRefs(id, version), "members" to scenarios.memberSourceRefs(id, version), "relations" to scenarios.relationFilters(id, version))))
            }
            "ANALYSIS_DIMENSION" -> result.set<JsonNode>("members", OlapJsonUtils.mapper().valueToTree(mapOf(
                "levels" to analysis.listAnalysisDimensionLevels(id, version), "mappings" to analysis.listAnalysisDimensionEventMappings(id, version),
                "providers" to analysis.listAnalysisDimensionValueProviders(id, version), "alignments" to analysis.listAnalysisDimensionValueAlignments(id, version))))
            "SUBJECT_AREA" -> {
                result.set<JsonNode>("adoptions", OlapJsonUtils.mapper().valueToTree(adoptions.listSubjectAreaMetricRefs(id, version).map { adoptions.metricAdoption(it.id, it.subjectAreaVersion) }))
                result.set<JsonNode>("memberSources", OlapJsonUtils.mapper().valueToTree(adoptions.memberSources(id, version)))
                result.set<JsonNode>("selections", OlapJsonUtils.mapper().valueToTree(mapOf(
                    "dimensions" to analysis.listSubjectAreaAnalysisDimensionRefs(id, version), "scenarios" to subjects.listSubjectAreaScenarioRefs(id, version))))
            }
        }
        return result
    }
    private fun difference(path: String, before: JsonNode?, after: JsonNode?, changes: MutableList<DefinitionRevisionDiff.Change>) {
        if (before == after) return
        if (before?.isObject == true && after?.isObject == true) {
            (before.fieldNames().asSequence().toSet() + after.fieldNames().asSequence().toSet()).sorted().forEach { field ->
                difference("$path/${field.replace("~", "~0").replace("/", "~1")}", before.get(field), after.get(field), changes)
            }
        } else if (before?.isArray == true && after?.isArray == true) {
            repeat(maxOf(before.size(), after.size())) { index -> difference("$path/$index", before.get(index), after.get(index), changes) }
        } else changes.add(DefinitionRevisionDiff.Change(path, classification(path), before?.deepCopy(), after?.deepCopy()))
    }
    private fun classification(path: String): Classification {
        val fields = path.split('/')
        return when {
            fields.any { it in setOf("authorization", "authorizationContext", "owner") || it.contains("permission", true) } -> Classification.AUTHORIZATION
            fields.last() in setOf("displayName", "description", "code", "levelDisplayName") -> Classification.DISPLAY
            fields.any { it in setOf("bindings", "mappings", "adoptions", "memberSources", "selections", "selection", "providers", "columnVersionRefs", "roles", "accessPath", "joinPathRefs") } || fields.last().endsWith("Version") -> Classification.DEPENDENCY
            fields.last() in setOf("kind", "id", "sourceKind", "result", "root", "valueType", "identitySlots") -> Classification.IDENTITY_OR_STRUCTURE
            else -> Classification.CONTENT
        }
    }
}
