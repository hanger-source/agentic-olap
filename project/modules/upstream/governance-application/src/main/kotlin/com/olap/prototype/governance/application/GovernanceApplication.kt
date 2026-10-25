package com.olap.prototype.governance.application

import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.engine.catalog.ExecutablePostOperationCapabilities
import com.olap.prototype.governance.contract.*
import com.olap.prototype.management.governance.*
import com.olap.prototype.management.warehouse.WarehouseRegistryRepository
import com.olap.prototype.management.warehouse.PhysicalSourceAdoptionService
import com.olap.prototype.release.catalog.*
import com.olap.prototype.release.catalog.capability.QueryCapabilityProducer
import com.olap.prototype.release.catalog.capability.ReleaseQueryCapabilityRepository
import com.olap.prototype.release.catalog.capability.ReleasedScenarioEntrypointRepository
import com.olap.prototype.storage.PlatformDatabase

/** 本进程领域规则的唯一装配；请求、主体、进程调度不进入这些长期服务。 */
class GovernanceApplication(private val database: PlatformDatabase, connectors: ConnectorRegistry) {
    internal val snapshots = GovernanceSnapshotReader(database)
    internal val executableCatalogAssembler = ReleaseExecutableCatalogAssembler(connectors)
    internal val semanticCatalogCompiler = PublishedSemanticCatalogProducer()
    internal val publishedQueries = JdbcPublishedQueryEntryProvider(database)
    private val releaseRepository = CanonicalReleaseRepository(database)
    private val diagnostics = DiagnosticLifecycleRepository(database)
    internal val warehouseRepository = WarehouseRegistryRepository(database)
    internal val businessLines = BusinessLineGovernanceService(database)
    internal val physicalAssets = PhysicalAssetGovernanceService(database, warehouseRepository)
    internal val sourceAdoptions = PhysicalSourceAdoptionService(warehouseRepository, physicalAssets)
    internal val logicalModel = LogicalModelGovernanceService(database, physicalAssets, connectors)
    private val nativeExpressions = NativeExpressionBindings(physicalAssets, connectors)
    internal val topologies = TopologyGovernanceService(database, logicalModel)
    internal val events = EventGovernanceService(database, topologies, nativeExpressions)
    internal val dimensions = DimensionGovernanceService(database, topologies, events)
    internal val metrics = MetricGovernanceService(database, logicalModel, topologies, events, dimensions, nativeExpressions)
    internal val subjectAreas = SubjectAreaGovernanceService(database, businessLines)
    internal val analysisDimensions = AnalysisDimensionGovernanceService(database, subjectAreas, logicalModel, events, dimensions)
    internal val memberDefinitions = MemberDefinitionGovernanceService(database, metrics, events, dimensions, logicalModel)
    internal val adoptions = SubjectAreaAdoptionService(database, subjectAreas, analysisDimensions, metrics, memberDefinitions, events, dimensions)
    internal val scenarios = ScenarioGovernanceService(database, subjectAreas, adoptions, analysisDimensions, logicalModel, metrics)
    internal val contextRevisions = SubjectContextRevisionService(database, subjectAreas, analysisDimensions, adoptions, scenarios)
    internal val definitionImpact = DefinitionImpactService(database)
    internal val definitionRevisionDiff = DefinitionRevisionDiffService(database)
    internal val definitionDiscovery = DefinitionDiscoveryService(database)
    internal val lifecycle = GovernanceVersionLifecycleService(database, businessLines, physicalAssets, logicalModel,
        topologies, events, dimensions, metrics, subjectAreas, adoptions, analysisDimensions, memberDefinitions,
        scenarios, diagnostics, warehouseRepository)
    internal val artifacts = ReleaseArtifactRepository(database)
    internal val capabilities = ReleaseQueryCapabilityRepository(database)
    internal val releasedScenarios = ReleasedScenarioEntrypointRepository(database::connection)
    internal val releases = SubjectAreaReleaseBuildService(releaseRepository,
        ReleaseFreezer(physicalAssets, nativeExpressions, logicalModel, topologies, events, dimensions, metrics,
            subjectAreas, adoptions, analysisDimensions, memberDefinitions, scenarios, JdbcReleaseCatalogRepository(database::connection)),
        artifacts, executableCatalogAssembler, semanticCatalogCompiler,
        QueryCapabilityProducer(ExecutablePostOperationCapabilities::supports, connectors), capabilities)

    internal fun reviseMemberAdoption(request: MemberAdoptionRevisionRequest): MemberSourceRecord = database.inTransaction {
        val revised = adoptions.reviseMemberAdoption(request)
        subjectAreas.listSubjectAreaScenarioRefs(revised.subjectAreaId, revised.subjectAreaVersion).forEach { reference ->
            val scenario = scenarios.scenarioSnapshot(reference.scenarioId, reference.scenarioVersion)
            scenarios.validateMemberSourceRefs(scenario)
            scenarios.validateRelationFilters(scenario, scenarios.listScenarioMetricRefs(scenario.id, scenario.currentVersion),
                scenarios.relationFilters(scenario.id, scenario.currentVersion))
        }
        revised
    }
}
