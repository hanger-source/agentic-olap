package com.olap.prototype.governance.application

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.InputContractException
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
import com.olap.prototype.governance.contract.*
import com.olap.prototype.management.governance.*
import com.olap.prototype.query.contract.PublishedQueryEntry
import com.olap.prototype.query.contract.QueryCapabilityRequest
import com.olap.prototype.query.contract.QueryLanguageContract
import com.olap.prototype.release.contract.ReleaseCatalogRequest
import com.olap.prototype.warehouse.contract.SourceAdoptionReadRequest

/** 同一注册事实拥有治理读取的工具名、输入类型和执行规则。 */
internal class GovernanceReads(
    private val application: GovernanceApplication,
    private val writeNames: () -> List<String>,
    private val writeDescription: (DescribeCommandInput) -> Any,
) {
    fun read(operation: String, payload: Any): Any {
        return requireOperation(operation).read(this, payload)
    }

    private class ReadOperation<T>(val name: String, val inputType: Class<T>, val action: (GovernanceReads, T) -> Any) {
        fun read(reads: GovernanceReads, payload: Any): Any {
            require(inputType.isInstance(payload)) { "read input differs from registered type: ${inputType.name}" }
            @Suppress("UNCHECKED_CAST") return action(reads, payload as T)
        }
    }

    companion object {
        private fun <T> operation(name: String, type: Class<T>, action: (GovernanceReads, T) -> Any): Pair<String, ReadOperation<*>> =
            name to ReadOperation(name, type, action)

        private val registered = mapOf(
            operation("list-write-commands", EmptyInput::class.java) { r, _ -> r.writeNames() },
            operation("describe-write-command", DescribeCommandInput::class.java) { r, input -> r.writeDescription(input) },
            operation("list-source-discoveries", SourceDiscoveriesInput::class.java) { r, input ->
                page(r.application.warehouseRepository.listDiscoverySnapshots(requiredText(input.dataSourceId, "dataSourceId"), requiredCatalog(input.catalog), requiredText(input.schemaName, "schemaName")), input.offset, input.limit) },
            operation("read-source-discovery", SnapshotInput::class.java) { r, input -> r.sourceDiscovery(requiredText(input.snapshotId, "snapshotId")) },
            operation("read-source-adoptions", SourceAdoptionReadRequest::class.java) { r, input -> r.application.sourceAdoptions.read(input) },
            operation("read-source-discovery-tables", SnapshotPageInput::class.java) { r, input -> r.readSourceDiscoveryTables(input) },
            operation("read-source-discovery-columns", DiscoveredTablePageInput::class.java) { r, input -> r.readSourceDiscoveryColumns(input) },
            operation("read-source-discovery-unique-keys", DiscoveredTablePageInput::class.java) { r, input -> r.readSourceDiscoveryUniqueKeys(input) },
            operation("list-source-collections", SourcePageInput::class.java) { r, input -> page(r.application.warehouseRepository.listSourceCollections(requiredText(input.dataSourceId, "dataSourceId")), input.offset, input.limit) },
            operation("read-source-collection", IdentityInput::class.java) { r, input -> r.application.warehouseRepository.findSourceCollection(requiredText(input.id, "id")) ?: throw ProducerContractException("exact source collection is missing") },
            operation("read-subject-area-member-sources", VersionPageInput::class.java) { r, input -> r.versionMemberPage("SUBJECT_AREA", input, r.application.adoptions::memberSources) },
            operation("list-business-lines", PageInput::class.java) { r, input -> page(r.application.businessLines.listBusinessLines(), input.offset, input.limit) },
            operation("list-physical-tables", PageInput::class.java) { r, input -> page(r.application.physicalAssets.listPhysicalTables(), input.offset, input.limit) },
            operation("list-logical-tables", PageInput::class.java) { r, input -> page(r.application.logicalModel.listLogicalTables(), input.offset, input.limit) },
            operation("list-topologies", PageInput::class.java) { r, input -> page(r.application.topologies.listTopologies(), input.offset, input.limit) },
            operation("list-events", PageInput::class.java) { r, input -> page(r.application.events.listEvents(), input.offset, input.limit) },
            operation("list-metrics", PageInput::class.java) { r, input -> page(r.application.metrics.listMetrics(), input.offset, input.limit) },
            operation("list-member-definitions", PageInput::class.java) { r, input -> page(r.application.memberDefinitions.list(), input.offset, input.limit) },
            operation("read-definition-impact", DefinitionImpactRequest::class.java) { r, input -> r.application.definitionImpact.read(input) },
            operation("read-definition-revision-diff", DefinitionRevisionDiffRequest::class.java) { r, input -> r.application.definitionRevisionDiff.read(input) },
            operation("read-subject-context-selection", SubjectContextSelectionRequest::class.java) { r, input -> r.application.contextRevisions.selection(input) },
            operation("search-definitions", DefinitionDiscovery.Request::class.java) { r, input -> r.application.definitionDiscovery.search(input) },
            operation("read-definition-dependencies", DefinitionDependenciesRequest::class.java) { r, input -> r.application.definitionImpact.direct(DefinitionImpactRequest(input.assetType, input.id, input.version), input.offset, input.limit) },
            operation("list-dimensions", PageInput::class.java) { r, input -> page(r.application.dimensions.listDimensions(), input.offset, input.limit) },
            operation("list-subject-areas", PageInput::class.java) { r, input -> page(r.application.subjectAreas.listSubjectAreas(), input.offset, input.limit) },
            operation("list-scenarios", SubjectPageInput::class.java) { r, input -> page(r.application.scenarios.listScenarios(requiredText(input.subjectAreaId, "subjectAreaId")), input.offset, input.limit) },
            operation("list-released-scenarios", PageInput::class.java) { r, input -> page(r.application.releasedScenarios.list(), input.offset, input.limit) },
            operation("read-release-identity", ReleaseIdentityRequest::class.java) { r, input -> r.readReleaseIdentity(input) },
            operation("list-analysis-dimensions", SubjectPageInput::class.java) { r, input -> page(r.application.analysisDimensions.listAnalysisDimensions(requiredText(input.subjectAreaId, "subjectAreaId")), input.offset, input.limit) },
            operation("read-subject-area-metrics", VersionPageInput::class.java) { r, input -> r.versionMemberPage("SUBJECT_AREA", input, r.application.adoptions::listSubjectAreaMetricRefs) },
            operation("read-subject-area-dimensions", VersionPageInput::class.java) { r, input -> r.versionMemberPage("SUBJECT_AREA", input, r.application.analysisDimensions::listSubjectAreaAnalysisDimensionRefs) },
            operation("read-subject-area-scenarios", VersionPageInput::class.java) { r, input -> r.versionMemberPage("SUBJECT_AREA", input, r.application.subjectAreas::listSubjectAreaScenarioRefs) },
            operation("read-analysis-dimension-levels", VersionPageInput::class.java) { r, input -> r.versionMemberPage("ANALYSIS_DIMENSION", input, r.application.analysisDimensions::listAnalysisDimensionLevels) },
            operation("read-analysis-dimension-mappings", VersionPageInput::class.java) { r, input -> r.versionMemberPage("ANALYSIS_DIMENSION", input, r.application.analysisDimensions::listAnalysisDimensionEventMappings) },
            operation("read-analysis-dimension-providers", VersionPageInput::class.java) { r, input -> r.versionMemberPage("ANALYSIS_DIMENSION", input, r.application.analysisDimensions::listAnalysisDimensionValueProviders) },
            operation("read-analysis-dimension-alignments", VersionPageInput::class.java) { r, input -> r.versionMemberPage("ANALYSIS_DIMENSION", input, r.application.analysisDimensions::listAnalysisDimensionValueAlignments) },
            operation("read-scenario-metrics", VersionPageInput::class.java) { r, input -> r.versionMemberPage("SCENARIO", input, r.application.scenarios::listScenarioMetricRefs) },
            operation("read-scenario-dimensions", VersionPageInput::class.java) { r, input -> r.versionMemberPage("SCENARIO", input, r.application.scenarios::listScenarioAnalysisDimensionRefs) },
            operation("read-scenario-filters", VersionPageInput::class.java) { r, input -> r.versionMemberPage("SCENARIO", input, r.application.scenarios::listScenarioFilterRefs) },
            operation("read-scenario-pipeline-rules", VersionPageInput::class.java) { r, input -> r.versionMemberPage("SCENARIO", input, r.application.scenarios::listScenarioPipelineRules) },
            operation("read-scenario-member-sources", VersionPageInput::class.java) { r, input -> r.versionMemberPage("SCENARIO", input, r.application.scenarios::memberSourceRefs) },
            operation("read-scenario-relation-filters", VersionPageInput::class.java) { r, input -> r.versionMemberPage("SCENARIO", input, r.application.scenarios::relationFilters) },
            operation("read-asset-definition", AssetVersionRequest::class.java) { r, input -> r.application.snapshots.definition(requiredText(input.assetType, "assetType"), requiredText(input.id, "id"), requiredVersion(input.version)) },
            operation("read-asset-version", AssetVersionRequest::class.java) { r, input -> r.readAssetVersion(input) },
            operation("read-physical-columns", VersionPageInput::class.java) { r, input -> r.versionMemberPage("PHYSICAL_TABLE", input, r.application.physicalAssets::physicalTableColumnSnapshots) },
            operation("read-logical-table-units", VersionInput::class.java) { r, input -> r.application.logicalModel.listLogicalTableLogicUnitRefs(requiredText(input.id, "id"), requiredVersion(input.version)) },
            operation("read-logic-unit-sources", VersionInput::class.java) { r, input -> r.application.logicalModel.listLogicUnitSourceRefs(requiredText(input.id, "id"), requiredVersion(input.version)) },
            operation("read-topology-tables", VersionInput::class.java) { r, input -> r.application.topologies.listTopologyLogicalTableRefs(requiredText(input.id, "id"), requiredVersion(input.version)) },
            operation("read-topology-joins", VersionInput::class.java) { r, input -> r.application.topologies.listJoinPaths(requiredText(input.id, "id"), requiredVersion(input.version)) },
            operation("read-join-keys", VersionInput::class.java) { r, input -> r.application.topologies.listJoinPathKeyRefs(requiredText(input.id, "id"), requiredVersion(input.version)) },
            operation("read-event-units", VersionInput::class.java) { r, input -> r.application.events.listEventLogicUnitRefs(requiredText(input.id, "id"), requiredVersion(input.version)) },
            operation("read-event-filters", VersionInput::class.java) { r, input -> r.application.events.listEventSliceFilters(requiredText(input.id, "id"), requiredVersion(input.version)) },
            operation("read-dimension-bindings", VersionInput::class.java) { r, input -> r.application.dimensions.listDimensionEventBindings(requiredText(input.id, "id"), requiredVersion(input.version)) },
            operation("load-release-catalog", ReleaseCatalogRequest::class.java) { r, input -> r.application.artifacts.loadCatalog(input) },
            operation("load-executable-catalog", ReleaseCatalogRequest::class.java) { r, input -> r.application.artifacts.loadExecutableCatalog(input) },
            operation("read-query-language-contract", QueryLanguageContract.Selection::class.java) { _, input -> QueryLanguageContract.describe(input) },
            operation("read-semantic-expression-contract", EmptyInput::class.java) { _, _ -> com.olap.prototype.expression.SemanticCalciteOperators.contract() },
            operation("load-query-capability", QueryCapabilityRequest::class.java) { r, input -> r.application.capabilities.load(input) },
            operation("load-query-construction-entry", QueryCapabilityRequest::class.java) { r, input -> r.application.publishedQueries.require(input) },
            operation("load-published-semantic-catalog", QueryCapabilityRequest::class.java) { r, input -> r.application.publishedQueries.require(input).semanticCatalog },
            operation("list-read-operations", EmptyInput::class.java) { _, _ -> operations() },
            operation("describe-read-operation", OperationInput::class.java) { _, input -> describe(requiredText(input.operation, "operation")) },
        )

        @JvmStatic fun operations(): List<String> = registered.keys.sorted()
        fun inputType(operation: String): Class<*> = requireOperation(operation).inputType
        @JvmStatic fun describe(operation: String): Any = requireOperation(operation).let {
            mapOf("operation" to it.name, "inputShape" to GovernanceCommandSchema.describeInput(it.inputType))
        }
        private fun requireOperation(name: String): ReadOperation<*> = registered[name]
            ?: throw ProducerContractException("unsupported governance read operation: $name")

        private fun <T> page(rows: List<T>, requestedOffset: Int?, requestedLimit: Int?): Page<T> {
            val offset = GovernanceReadRequests.pageOffset(requestedOffset)
            val limit = GovernanceReadRequests.pageLimit(requestedLimit)
            val ordered = rows
            val start = minOf(offset, ordered.size)
            val end = minOf(start.toLong() + limit, ordered.size.toLong()).toInt()
            return Page(ordered.subList(start, end), ordered.size, if (end < ordered.size) end else null)
        }

        private fun requiredText(value: String?, field: String): String = value?.takeIf { it.isNotBlank() }
            ?: throw ProducerContractException("$field is required")

        private fun requiredCatalog(value: String?): String = value
            ?: throw ProducerContractException("catalog must be explicit text; the empty string denotes no catalog")

        private fun requiredVersion(version: Long): Long {
            if (version <= 0) throw ProducerContractException("positive version is required")
            return version
        }
    }

    private fun readSourceDiscoveryTables(input: SnapshotPageInput): Any {
        val id = requiredText(input.snapshotId, "snapshotId")
        sourceDiscovery(id)
        return page(application.warehouseRepository.listDiscoveredTables(id).map { table ->
            SourceDiscoveryTable(table.id, table.discoverySnapshotId, table.catalogName, table.schemaName, table.tableName, table.tableComment, table.rowCount, table.schemaHash, table.constraints, table.createdAt)
        }, input.offset, input.limit)
    }

    private fun readSourceDiscoveryColumns(input: DiscoveredTablePageInput): Any {
        val id = requiredText(input.discoveredTableId, "discoveredTableId")
        val table = application.warehouseRepository.findDiscoveredTable(id)
            ?: throw ProducerContractException("exact discovered table not found: $id")
        sourceDiscovery(table.discoverySnapshotId)
        return page(application.warehouseRepository.listDiscoveredColumns(id), input.offset, input.limit)
    }

    private fun readSourceDiscoveryUniqueKeys(input: DiscoveredTablePageInput): Any {
        val id = requiredText(input.discoveredTableId, "discoveredTableId")
        application.warehouseRepository.findDiscoveredTable(id) ?: throw ProducerContractException("exact discovered table is missing")
        return page(application.warehouseRepository.listDiscoveredUniqueKeys(id), input.offset, input.limit)
    }

    private fun readReleaseIdentity(reference: ReleaseIdentityRequest): Any {
        val artifact = application.artifacts.loadArtifact(reference.releaseId, reference.releaseVersion)
        return ReleaseIdentityView(artifact.releaseId, artifact.releaseVersion, artifact.subjectAreaId, artifact.subjectAreaVersion, artifact.governanceClosureHash, artifact.catalogHash, artifact.artifactHash)
    }

    private fun sourceDiscovery(id: String) = application.warehouseRepository.findDiscoverySnapshot(id)
        ?: throw ProducerContractException("exact source discovery snapshot not found: $id")

    fun readAssetVersion(request: AssetVersionRequest): AssetSnapshot {
        val id = requiredText(request.id, "id")
        val version = requiredVersion(request.version)
        return when (request.assetType ?: throw ProducerContractException("assetType is required")) {
            "BUSINESS_LINE" -> AssetSnapshot.BusinessLine(application.businessLines.businessLineSnapshot(id, version))
            "PHYSICAL_TABLE" -> AssetSnapshot.PhysicalTable(application.physicalAssets.physicalTableSnapshot(id, version))
            "PHYSICAL_COLUMN" -> AssetSnapshot.PhysicalColumn(application.physicalAssets.physicalColumnSnapshot(id, version))
            "LOGICAL_TABLE" -> AssetSnapshot.LogicalTable(application.logicalModel.logicalTableSnapshot(id, version))
            "LOGIC_UNIT" -> AssetSnapshot.LogicalUnit(application.logicalModel.logicUnitSnapshot(id, version))
            "TOPOLOGY" -> AssetSnapshot.Topology(application.topologies.topologySnapshot(id, version))
            "JOIN_PATH" -> AssetSnapshot.JoinPath(application.topologies.joinPathSnapshot(id, version))
            "EVENT" -> AssetSnapshot.Event(application.events.eventSnapshot(id, version))
            "METRIC" -> AssetSnapshot.Metric(application.metrics.metricSnapshot(id, version), application.metrics.metricBusinessMeaning(id, version))
            "DIMENSION" -> AssetSnapshot.Dimension(application.dimensions.dimensionSnapshot(id, version))
            "DIMENSION_EVENT_BINDING" -> AssetSnapshot.DimensionBinding(application.dimensions.dimensionEventBindingSnapshot(id, version))
            "SUBJECT_AREA" -> AssetSnapshot.SubjectArea(application.subjectAreas.subjectAreaSnapshot(id, version))
            "MEMBER_SOURCE" -> AssetSnapshot.MemberSource(application.adoptions.memberSource(id, version))
            "MEMBER_DEFINITION" -> AssetSnapshot.MemberDefinition(application.memberDefinitions.read(id, version))
            "ANALYSIS_DIMENSION" -> AssetSnapshot.AnalysisDimension(application.analysisDimensions.analysisDimensionSnapshot(id, version))
            "SCENARIO" -> AssetSnapshot.Scenario(application.scenarios.scenarioSnapshot(id, version))
            else -> throw ProducerContractException("unsupported assetType: ${request.assetType}")
        }
    }

    private fun <T> versionMemberPage(assetType: String, input: VersionPageInput, reader: (String, Long) -> List<T>): Page<T> {
        val id = requiredText(input.id, "id")
        val version = requiredVersion(input.version)
        readAssetVersion(AssetVersionRequest(assetType, id, version))
        return page(reader(id, version), input.offset, input.limit)
    }
}
