package com.olap.prototype.dsl

import com.olap.prototype.dsl.transport.Capability
import com.olap.prototype.dsl.transport.CapabilityCalls
import com.olap.prototype.governance.contract.*
import com.olap.prototype.governance.contract.GovernanceReadRequests.AssetVersionRequest
import com.olap.prototype.governance.contract.GovernanceReadRequests.DefinitionDependenciesRequest
import com.olap.prototype.governance.contract.GovernanceReadRequests.ReleaseIdentityRequest
import com.olap.prototype.governance.contract.GovernanceReadRequests.Page
import com.olap.prototype.governance.contract.GovernanceReadRequests.EmptyInput
import com.olap.prototype.governance.contract.GovernanceReadRequests.OperationInput
import com.olap.prototype.governance.contract.GovernanceReadRequests.DescribeCommandInput
import com.olap.prototype.governance.contract.GovernanceReadRequests.PageInput
import com.olap.prototype.governance.contract.GovernanceReadRequests.SourceDiscoveriesInput
import com.olap.prototype.governance.contract.GovernanceReadRequests.SnapshotInput
import com.olap.prototype.governance.contract.GovernanceReadRequests.SnapshotPageInput
import com.olap.prototype.governance.contract.GovernanceReadRequests.DiscoveredTablePageInput
import com.olap.prototype.governance.contract.GovernanceReadRequests.SourcePageInput
import com.olap.prototype.governance.contract.GovernanceReadRequests.IdentityInput
import com.olap.prototype.governance.contract.GovernanceReadRequests.VersionInput
import com.olap.prototype.governance.contract.GovernanceReadRequests.VersionPageInput
import com.olap.prototype.governance.contract.GovernanceReadRequests.SubjectPageInput
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.contract.catalog.*
import com.olap.prototype.management.warehouse.*
import com.olap.prototype.release.contract.*
import com.olap.prototype.release.catalog.capability.ReleasedScenarioEntrypoint

/** 读取可复用定义、精确采用及组合合同，不隐式选择 latest。 */
class SemanticClient internal constructor(private val calls: CapabilityCalls) {
    @Capability("list-business-lines", "read-governance", "READ")
    fun businessLines(request: PageInput): Page<BusinessLineRecord> = calls.call(SemanticClient::class.java, "businessLines", request)
    @Capability("list-physical-tables", "read-governance", "READ")
    fun physicalTables(request: PageInput): Page<PhysicalTableRecord> = calls.call(SemanticClient::class.java, "physicalTables", request)
    @Capability("list-logical-tables", "read-governance", "READ")
    fun logicalTables(request: PageInput): Page<LogicalTableRecord> = calls.call(SemanticClient::class.java, "logicalTables", request)
    @Capability("list-topologies", "read-governance", "READ")
    fun topologies(request: PageInput): Page<TopologyRecord> = calls.call(SemanticClient::class.java, "topologies", request)
    @Capability("list-events", "read-governance", "READ")
    fun events(request: PageInput): Page<EventRecord> = calls.call(SemanticClient::class.java, "events", request)
    @Capability("list-metrics", "read-governance", "READ")
    fun metrics(request: PageInput): Page<MetricRecord> = calls.call(SemanticClient::class.java, "metrics", request)
    @Capability("list-member-definitions", "read-governance", "READ")
    fun memberDefinitions(request: PageInput): Page<MemberDefinitionRecord> = calls.call(SemanticClient::class.java, "memberDefinitions", request)
    @Capability("list-dimensions", "read-governance", "READ")
    fun dimensions(request: PageInput): Page<DimensionRecord> = calls.call(SemanticClient::class.java, "dimensions", request)
    @Capability("list-subject-areas", "read-governance", "READ")
    fun subjectAreas(request: PageInput): Page<SubjectAreaRecord> = calls.call(SemanticClient::class.java, "subjectAreas", request)
    @Capability("list-scenarios", "read-governance", "READ")
    fun scenarios(request: SubjectPageInput): Page<ScenarioRecord> = calls.call(SemanticClient::class.java, "scenarios", request)
    @Capability("read-subject-area-member-sources", "read-governance", "READ")
    fun memberSources(request: VersionPageInput): Page<MemberSourceRecord> = calls.call(SemanticClient::class.java, "memberSources", request)
    @Capability("read-subject-area-metrics", "read-governance", "READ")
    fun metricAdoptions(request: VersionPageInput): Page<SubjectAreaMetricRefRecord> = calls.call(SemanticClient::class.java, "metricAdoptions", request)
    @Capability("read-subject-area-dimensions", "read-governance", "READ")
    fun subjectDimensions(request: VersionPageInput): Page<SubjectAreaAnalysisDimensionRefRecord> = calls.call(SemanticClient::class.java, "subjectDimensions", request)
    @Capability("read-subject-area-scenarios", "read-governance", "READ")
    fun subjectScenarios(request: VersionPageInput): Page<SubjectAreaScenarioRefRecord> = calls.call(SemanticClient::class.java, "subjectScenarios", request)
    @Capability("read-scenario-metrics", "read-governance", "READ")
    fun scenarioMetrics(request: VersionPageInput): Page<ScenarioMetricRefRecord> = calls.call(SemanticClient::class.java, "scenarioMetrics", request)
    @Capability("read-scenario-dimensions", "read-governance", "READ")
    fun scenarioDimensions(request: VersionPageInput): Page<ScenarioAnalysisDimensionRefRecord> = calls.call(SemanticClient::class.java, "scenarioDimensions", request)
    @Capability("read-scenario-member-sources", "read-governance", "READ")
    fun scenarioMembers(request: VersionPageInput): Page<ScenarioMemberSourceRefRecord> = calls.call(SemanticClient::class.java, "scenarioMembers", request)
    @Capability("read-scenario-filters", "read-governance", "READ")
    fun scenarioFilters(request: VersionPageInput): Page<ScenarioFilterRefRecord> = calls.call(SemanticClient::class.java, "scenarioFilters", request)
    @Capability("read-scenario-relation-filters", "read-governance", "READ")
    fun scenarioRelations(request: VersionPageInput): Page<ScenarioRelationFilterRecord> = calls.call(SemanticClient::class.java, "scenarioRelations", request)
    @Capability("read-scenario-pipeline-rules", "read-governance", "READ")
    fun scenarioStages(request: VersionPageInput): Page<ScenarioPipelineRuleRecord> = calls.call(SemanticClient::class.java, "scenarioStages", request)
    @Capability("read-physical-columns", "read-governance", "READ")
    fun physicalColumns(request: VersionPageInput): Page<PhysicalColumnRecord> = calls.call(SemanticClient::class.java, "physicalColumns", request)
    @Capability("read-logical-table-units", "read-governance", "READ")
    fun logicalUnits(request: VersionInput): List<LogicalTableLogicUnitRefRecord> = calls.call(SemanticClient::class.java, "logicalUnits", request)
    @Capability("read-logic-unit-sources", "read-governance", "READ")
    fun logicalSources(request: VersionInput): List<LogicUnitSourceRefRecord> = calls.call(SemanticClient::class.java, "logicalSources", request)
    @Capability("read-event-units", "read-governance", "READ")
    fun eventUnits(request: VersionInput): List<EventLogicUnitRefRecord> = calls.call(SemanticClient::class.java, "eventUnits", request)
    @Capability("read-dimension-bindings", "read-governance", "READ")
    fun dimensionBindings(request: VersionInput): List<DimensionEventBindingRecord> = calls.call(SemanticClient::class.java, "dimensionBindings", request)
    @Capability("read-analysis-dimension-mappings", "read-governance", "READ")
    fun dimensionMappings(request: VersionPageInput): Page<AnalysisDimensionEventMappingRecord> = calls.call(SemanticClient::class.java, "dimensionMappings", request)
    @Capability("read-subject-context-selection", "read-governance", "READ")
    fun contextSelection(request: SubjectContextSelectionRequest): SubjectContextSelectionView = calls.call(SemanticClient::class.java, "contextSelection", request)
    @Capability("read-definition-impact", "read-governance", "READ")
    fun impact(request: DefinitionImpactRequest): DefinitionImpact = calls.call(SemanticClient::class.java, "impact", request)
    @Capability("read-definition-revision-diff", "read-governance", "READ")
    fun revisionDiff(request: DefinitionRevisionDiffRequest): DefinitionRevisionDiff = calls.call(SemanticClient::class.java, "revisionDiff", request)
    @Capability("load-query-construction-entry", "read-query-capability", "READ")
    fun publication(request: QueryCapabilityRequest): PublishedQueryEntry = calls.call(SemanticClient::class.java, "publication", request)
    @Capability("read-asset-version", "read-governance", "READ")
    fun asset(request: AssetVersionRequest): AssetSnapshot = calls.call(SemanticClient::class.java, "asset", request)
    @Capability("list-source-discoveries", "read-governance", "READ")
    fun sourceDiscoveries(request: SourceDiscoveriesInput): Page<WarehouseDiscoverySnapshotRecord> = calls.call(SemanticClient::class.java, "sourceDiscoveries", request)
    @Capability("read-source-discovery", "read-governance", "READ")
    fun sourceDiscovery(request: SnapshotInput): WarehouseDiscoverySnapshotRecord = calls.call(SemanticClient::class.java, "sourceDiscovery", request)
    @Capability("read-source-discovery-tables", "read-governance", "READ")
    fun discoveredTables(request: SnapshotPageInput): Page<SourceDiscoveryTable> = calls.call(SemanticClient::class.java, "discoveredTables", request)
    @Capability("read-source-discovery-columns", "read-governance", "READ")
    fun discoveredColumns(request: DiscoveredTablePageInput): Page<WarehouseDiscoveredColumnRecord> = calls.call(SemanticClient::class.java, "discoveredColumns", request)
    @Capability("read-source-discovery-unique-keys", "read-governance", "READ")
    fun discoveredKeys(request: DiscoveredTablePageInput): Page<WarehouseDiscoveredUniqueKeyRecord> = calls.call(SemanticClient::class.java, "discoveredKeys", request)
    @Capability("list-source-collections", "read-governance", "READ")
    fun sourceCollections(request: SourcePageInput): Page<com.olap.prototype.warehouse.contract.SourceCollectionRecord> = calls.call(SemanticClient::class.java, "sourceCollections", request)
    @Capability("read-source-collection", "read-governance", "READ")
    fun sourceCollection(request: IdentityInput): com.olap.prototype.warehouse.contract.SourceCollectionRecord = calls.call(SemanticClient::class.java, "sourceCollection", request)
    @Capability("search-definitions", "read-governance", "READ")
    fun searchDefinitions(request: DefinitionDiscovery.Request): DefinitionDiscovery.Page = calls.call(SemanticClient::class.java, "searchDefinitions", request)
    @Capability("read-definition-dependencies", "read-governance", "READ")
    fun dependencies(request: DefinitionDependenciesRequest): DefinitionDependencies = calls.call(SemanticClient::class.java, "dependencies", request)
    @Capability("list-released-scenarios", "read-governance", "READ")
    fun releasedScenarios(request: PageInput): Page<ReleasedScenarioEntrypoint> = calls.call(SemanticClient::class.java, "releasedScenarios", request)
    @Capability("read-release-identity", "read-governance", "READ")
    fun releaseIdentity(request: ReleaseIdentityRequest): ReleaseIdentityView = calls.call(SemanticClient::class.java, "releaseIdentity", request)
    @Capability("list-analysis-dimensions", "read-governance", "READ")
    fun analysisDimensions(request: SubjectPageInput): Page<AnalysisDimensionRecord> = calls.call(SemanticClient::class.java, "analysisDimensions", request)
    @Capability("read-analysis-dimension-levels", "read-governance", "READ")
    fun dimensionLevels(request: VersionPageInput): Page<AnalysisDimensionLevelRecord> = calls.call(SemanticClient::class.java, "dimensionLevels", request)
    @Capability("read-analysis-dimension-providers", "read-governance", "READ")
    fun dimensionProviders(request: VersionPageInput): Page<AnalysisDimensionValueProviderRecord> = calls.call(SemanticClient::class.java, "dimensionProviders", request)
    @Capability("read-analysis-dimension-alignments", "read-governance", "READ")
    fun dimensionAlignments(request: VersionPageInput): Page<AnalysisDimensionValueAlignmentRecord> = calls.call(SemanticClient::class.java, "dimensionAlignments", request)
    @Capability("read-topology-tables", "read-governance", "READ")
    fun topologyTables(request: VersionInput): List<TopologyLogicalTableRefRecord> = calls.call(SemanticClient::class.java, "topologyTables", request)
    @Capability("read-topology-joins", "read-governance", "READ")
    fun topologyJoins(request: VersionInput): List<JoinPathRecord> = calls.call(SemanticClient::class.java, "topologyJoins", request)
    @Capability("read-join-keys", "read-governance", "READ")
    fun joinKeys(request: VersionInput): List<JoinPathKeyRefRecord> = calls.call(SemanticClient::class.java, "joinKeys", request)
    @Capability("read-event-filters", "read-governance", "READ")
    fun eventFilters(request: VersionInput): List<EventSliceFilterRecord> = calls.call(SemanticClient::class.java, "eventFilters", request)
    @Capability("read-asset-definition", "read-governance", "READ")
    fun definition(request: AssetVersionRequest): GovernanceDefinition = calls.call(SemanticClient::class.java, "definition", request)
    @Capability("load-release-catalog", "read-governance", "READ")
    fun releaseCatalog(request: ReleaseCatalogRequest): ReleaseCatalogView = calls.call(SemanticClient::class.java, "releaseCatalog", request)
    @Capability("load-executable-catalog", "read-governance", "READ")
    fun executableCatalog(request: ReleaseCatalogRequest): PublishedExecutableCatalog = calls.call(SemanticClient::class.java, "executableCatalog", request)
    @Capability("load-query-capability", "read-query-capability", "READ")
    fun queryCapability(request: QueryCapabilityRequest): QueryCapabilityView = calls.call(SemanticClient::class.java, "queryCapability", request)
    @Capability("load-published-semantic-catalog", "read-query-capability", "READ")
    fun publishedCatalog(request: QueryCapabilityRequest): PublishedSemanticCatalog = calls.call(SemanticClient::class.java, "publishedCatalog", request)
    @Capability("search-semantics", "read-query-capability", "READ")
    fun search(request: SemanticCatalogReads.SearchRequest): SemanticCatalogReads.SearchPage = calls.call(SemanticClient::class.java, "search", request)
    @Capability("read-semantic-catalog", "read-query-capability", "READ")
    fun catalog(request: SemanticCatalogReads.CatalogRequest): PublishedSemanticCatalog = calls.call(SemanticClient::class.java, "catalog", request)
    @Capability("read-semantic-asset", "read-query-capability", "READ")
    fun publishedAsset(request: SemanticCatalogReads.AssetRequest): SemanticCatalogReads.AssetResult = calls.call(SemanticClient::class.java, "publishedAsset", request)
    @Capability("read-semantic-definition", "read-query-capability", "READ")
    fun publishedDefinition(request: SemanticCatalogReads.DefinitionRequest): SemanticCatalogReads.DefinitionResult = calls.call(SemanticClient::class.java, "publishedDefinition", request)
}
