package com.olap.prototype.release.contract

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.engine.catalog.ExecutableCatalog
import com.olap.prototype.query.contract.PublishedExecutableCatalog
import com.olap.prototype.query.contract.QueryCapabilityView
import com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog
import java.time.Instant
import java.util.Locale

object ReleaseCompilationContract {
    const val COMPILER_VERSION = "olap-v2-release-14"
    const val RULE_SET_VERSION = "olap-v2-rules-14"
}
data class ReleaseArtifactRecord(val releaseId: String, val releaseVersion: String, val subjectAreaId: String, val subjectAreaVersion: Long, val governanceClosureHash: String, val catalogHash: String, val artifactHash: String, val manifestProto: String)
data class ReleaseCatalogSnapshot(val scenarioId: String, val scenarioVersion: Long, val catalogHash: String)
data class ReleaseCatalogRequest(val scenarioId: String, val scenarioVersion: Long, val releaseId: String, val releaseVersion: String) {
    init {
        ProducerContracts.requireText(scenarioId, "scenarioId"); ProducerContracts.requireVersion(scenarioVersion, "scenarioVersion")
        ProducerContracts.requireText(releaseId, "releaseId"); ProducerContracts.requireText(releaseVersion, "releaseVersion")
    }
}
interface ReleaseArtifactWriter {
    fun saveCatalog(catalog: ReleaseCatalogView, executableCatalog: PublishedExecutableCatalog, createdAt: Instant): ReleaseCatalogSnapshot
    fun finalizeArtifact(releaseId: String, releaseVersion: String, subjectAreaId: String, subjectAreaVersion: Long, createdBy: String, createdAt: Instant): ReleaseArtifactRecord
}
fun interface ReleaseCatalogProvider { fun produce(request: ReleaseCatalogRequest): ReleaseCatalogView }
interface ReleaseCatalogRepository {
    fun findReleaseDisplayName(releaseId: String, releaseVersion: String): String?
    fun findScenarioDisplayName(scenarioId: String, scenarioVersion: Long, releaseId: String, releaseVersion: String): String?
    fun findReleaseObjectVersionRefs(releaseId: String, releaseVersion: String): List<ReleaseObjectRef>
    fun findReleaseMetricDimensionCapabilities(releaseId: String, releaseVersion: String, scenarioId: String?, scenarioVersion: Long?): List<ReleaseMetricDimensionCapability>
    fun findReleaseDimensionMappings(releaseId: String, releaseVersion: String): List<ReleaseDimensionMapping>
    fun findReleaseTimeAnchorRules(releaseId: String, releaseVersion: String): List<ReleaseTimeAnchorRule>
    fun findReleaseUnavailableReasons(releaseId: String, releaseVersion: String, scenarioId: String?, scenarioVersion: Long?): List<ReleaseUnavailableReason>
    fun findReleaseDiagnostics(releaseId: String, releaseVersion: String, scenarioId: String?, scenarioVersion: Long?): List<ReleaseDiagnosticIssue>
}
interface ReleaseExecutableCatalogCompiler {
    fun compileAssets(releaseCatalog: ReleaseCatalogView, metricReferences: List<ScenarioMetricReference>): ExecutableCatalog
    fun assemble(release: ReleaseCatalogView, assets: ExecutableCatalog, capability: QueryCapabilityView): PublishedExecutableCatalog
}
fun interface ReleaseSemanticCatalogCompiler { fun produce(capability: QueryCapabilityView, release: ReleaseCatalogView, executable: PublishedExecutableCatalog): PublishedSemanticCatalog }
fun interface ReleaseQueryCapabilityCompiler { fun produce(release: ReleaseCatalogView, scenario: ReleaseScenarioSnapshot, assets: ExecutableCatalog): QueryCapabilityView }

enum class ReleaseObjectKind {
    PHYSICAL_TABLE, PHYSICAL_COLUMN, LOGICAL_TABLE, LOGIC_UNIT, TOPOLOGY, JOIN_PATH, EVENT, METRIC,
    METRIC_ADOPTION, MEMBER_SOURCE, MEMBER_DEFINITION, SOURCE_UNIQUE_KEY, ANALYSIS_DIMENSION, DIMENSION,
    DIMENSION_EVENT_BINDING, SUBJECT_AREA, SCENARIO, RELATION_FILTER;
    val refRole: String get() = if (this == DIMENSION_EVENT_BINDING) "DIMENSION_BINDING" else name
    fun requireRefRole(value: String?): String {
        if (value?.trim()?.uppercase(Locale.ROOT) != refRole) throw ProducerContractException("release_object_ref.ref_role must match release_object_ref.object_type")
        return refRole
    }
    companion object {
        @JvmStatic fun require(value: String?): ReleaseObjectKind {
            if (value.isNullOrBlank()) throw ProducerContractException("release_object_ref.object_type is required")
            return entries.firstOrNull { it.name == value.trim().uppercase(Locale.ROOT) }
                ?: throw ProducerContractException("release_object_ref.object_type cannot be frozen: $value")
        }
    }
}
class ReleaseObjectRef(val id: String, val releaseId: String, val releaseVersion: String, objectType: String, val objectId: String, val objectVersion: Long, val objectCode: String, val objectDisplayName: String, refRole: String, val snapshotProto: String, val createdAt: Instant) {
    val objectType: String
    val refRole: String
    init {
        val kind = ReleaseObjectKind.require(objectType)
        this.objectType = kind.name
        this.refRole = kind.requireRefRole(refRole)
        ProducerContracts.requireText(id, "id"); ProducerContracts.requireText(releaseId, "releaseId"); ProducerContracts.requireText(releaseVersion, "releaseVersion")
        ProducerContracts.requireText(objectId, "objectId"); ProducerContracts.requireVersion(objectVersion, "objectVersion")
        ProducerContracts.requireText(objectCode, "objectCode"); ProducerContracts.requireText(objectDisplayName, "objectDisplayName")
    }
    override fun equals(other: Any?): Boolean = other is ReleaseObjectRef && id == other.id && releaseId == other.releaseId && releaseVersion == other.releaseVersion && objectType == other.objectType && objectId == other.objectId && objectVersion == other.objectVersion && objectCode == other.objectCode && objectDisplayName == other.objectDisplayName && refRole == other.refRole && snapshotProto == other.snapshotProto && createdAt == other.createdAt
    override fun hashCode(): Int = listOf(id, releaseId, releaseVersion, objectType, objectId, objectVersion, objectCode, objectDisplayName, refRole, snapshotProto, createdAt).hashCode()
}
class ReleaseCatalogView(val scenarioId: String, val scenarioVersion: Long, val scenarioDisplayName: String, val releaseId: String, val releaseVersion: String, val releaseDisplayName: String, objectVersionRefs: List<ReleaseObjectRef>, metricAnalysisDimensionCapability: List<ReleaseMetricDimensionCapability>, dimensionMappings: List<ReleaseDimensionMapping>, timeAnchorRule: List<ReleaseTimeAnchorRule>, unavailableReason: List<ReleaseUnavailableReason>, diagnostics: List<ReleaseDiagnosticIssue>) {
    val objectVersionRefs: List<ReleaseObjectRef> = ProducerContracts.requireList(objectVersionRefs, "objectVersionRefs")
    val metricAnalysisDimensionCapability: List<ReleaseMetricDimensionCapability> = ProducerContracts.requireList(metricAnalysisDimensionCapability, "metricAnalysisDimensionCapability")
    val dimensionMappings: List<ReleaseDimensionMapping> = ProducerContracts.requireList(dimensionMappings, "dimensionMappings")
    val timeAnchorRule: List<ReleaseTimeAnchorRule> = ProducerContracts.requireList(timeAnchorRule, "timeAnchorRule")
    val unavailableReason: List<ReleaseUnavailableReason> = ProducerContracts.requireList(unavailableReason, "unavailableReason")
    val diagnostics: List<ReleaseDiagnosticIssue> = ProducerContracts.requireList(diagnostics, "diagnostics")
    init {
        ProducerContracts.requireText(scenarioId, "scenarioId"); ProducerContracts.requireVersion(scenarioVersion, "scenarioVersion"); ProducerContracts.requireText(scenarioDisplayName, "scenarioDisplayName")
        ProducerContracts.requireText(releaseId, "releaseId"); ProducerContracts.requireText(releaseVersion, "releaseVersion"); ProducerContracts.requireText(releaseDisplayName, "releaseDisplayName")
    }
    override fun equals(other: Any?): Boolean = other is ReleaseCatalogView && scenarioId == other.scenarioId && scenarioVersion == other.scenarioVersion && scenarioDisplayName == other.scenarioDisplayName && releaseId == other.releaseId && releaseVersion == other.releaseVersion && releaseDisplayName == other.releaseDisplayName && objectVersionRefs == other.objectVersionRefs && metricAnalysisDimensionCapability == other.metricAnalysisDimensionCapability && dimensionMappings == other.dimensionMappings && timeAnchorRule == other.timeAnchorRule && unavailableReason == other.unavailableReason && diagnostics == other.diagnostics
    override fun hashCode(): Int = listOf(scenarioId, scenarioVersion, scenarioDisplayName, releaseId, releaseVersion, releaseDisplayName, objectVersionRefs, metricAnalysisDimensionCapability, dimensionMappings, timeAnchorRule, unavailableReason, diagnostics).hashCode()
}
