package com.olap.prototype.server

import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.connector.OlapConnector
import com.olap.prototype.connector.loader.DeployedSources
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.expression.NativeAggregateTypes
import com.olap.prototype.query.contract.*
import com.olap.prototype.release.catalog.JdbcPublishedQueryEntryProvider
import com.olap.prototype.runtime.CandidateWorkerCommand
import com.olap.prototype.runtime.CandidateWorkerResult
import com.olap.prototype.source.contract.*
import com.olap.prototype.warehouse.contract.SourceBindingRef
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.onboarding.SourceDatasetRegistrationRequest
import com.olap.prototype.warehouse.onboarding.WarehouseOnboardingService

/** 来源身份与授权先确定；业务、调查与候选输入始终保持正式类型。 */
internal class ServerSources(private val sources: DeployedSources,
    private val connectors: ConnectorRegistry, private val authorization: ServerAuthorization,
    private val publications: JdbcPublishedQueryEntryProvider, private val workers: WorkerPool,
    private val onboarding: WarehouseOnboardingService) {

    fun registerDataset(request: ServerRequest, input: SourceDatasetRegistrationRequest): com.olap.prototype.dsl.SourceCollections {
        authorization.source(request.principal, "register-source-dataset", input.dataSourceId)
        sources.requireConfiguration(SourceBindingRef(input.dataSourceId, input.sourceIdentityHash))
        return com.olap.prototype.dsl.SourceCollections(onboarding.registerSourceDataset(input, request.control, SourceInvestigationClient(workers, request.work)))
    }

    private fun visible(request: ServerRequest, operation: String) = sources.configurations().filter { source ->
        authorization.matching(request.principal, operation).any { ServerAuthorization.includes(it.dataSources, source.dataSourceId) }
    }

    fun list(request: ServerRequest, input: EmptyRequest): AvailableSources = AvailableSources(
        visible(request, "list-data-sources").sortedWith(compareBy<SourceConfiguration> { it.dataSourceId }.thenBy { it.sourceIdentityHash() }).map {
            AvailableSource(it.bindingRef(), it.connectorRef, it.dialectId, connectors.require(it.connectorRef).access.capabilities(it).supported.map { capability -> capability.name }.toSet())
        })

    fun capabilities(request: ServerRequest, input: ConnectorCapabilitiesRequest): ConnectorCapabilities {
        val permitted = visible(request, "read-connector-capabilities").map { it.connectorRef }.distinct()
        val aggregate = input.aggregate?.let {
            if (it.connectorRef !in permitted) throw SecurityException("connector is not used by an authorized source")
            ConnectorCapabilities.Aggregate(it.connectorRef, it.aggregation, it.inputType, it.grouped,
                NativeAggregateTypes.describe(connectors.require(it.connectorRef).compilation, it.aggregation, it.inputType, it.grouped))
        }
        return ConnectorCapabilities(OlapConnector.API_VERSION, permitted.map {
            val connector = connectors.require(it)
            ConnectorCapabilities.Connector(it, connector.compilation.nativeScalarFunctions,
                CompilationSupport.Capability.entries.filter(connector.compilation::supports).map { capability -> capability.name }.toSet(),
                connector.compilation.parameterSubmission.name, connector.access.configurationParameters)
        }, aggregate)
    }

    fun inspect(request: ServerRequest, input: SourceInspection): SourceInspectionResult {
        val binding = when(input) {
            SourceInspection.Capabilities -> null
            is SourceInspection.Discover -> input.binding
            is SourceInspection.Describe -> input.binding
            is SourceInspection.Snapshot -> input.binding
            is SourceInspection.Preview -> input.binding
            is SourceInspection.Profile -> input.binding
            is SourceInspection.Relationship -> input.binding
        }
        val selected = binding?.let {
            authorization.source(request.principal, "inspect-warehouse", it.dataSourceId)
            listOf(sources.requireConfiguration(it))
        }.orEmpty()
        return workers.borrow(WorkerPool.Kind.INSPECTION, selected, request.work).use { it.inspect(input) }
    }

    fun candidates(request: ServerRequest, input: CandidateRequest): CandidateValues {
        val publication = publications.readPublication(input.publication)
        val entry = publication.entry
        authorization.publication(request.principal, "candidate-values", entry)
        val resolution = workers.borrow(WorkerPool.Kind.CANDIDATE_VALUES, emptyList(), request.work).use {
            WorkerPublications.candidate(it, publication)
            (it.candidate(CandidateWorkerCommand.Resolve(input)) as? CandidateWorkerResult.Resolved)?.value
                ?: error("candidate worker returned a different resolution receipt")
        }
        check(resolution.coordinate == publication.executable.coordinate && resolution.catalogHash == entry.catalogHash &&
            resolution.capabilityHash == entry.capability.capabilityHash) { "resolved candidate input differs from exact publication" }
        val selected = authorizedSources(request, resolution)
        return workers.borrow(WorkerPool.Kind.CANDIDATE_VALUES, selected, request.work).use {
            WorkerPublications.candidate(it, publication)
            val actual = (it.candidate(CandidateWorkerCommand.Values(input)) as? CandidateWorkerResult.Values)?.value
                ?: error("candidate worker returned a different values receipt")
            check(actual.queryHash == resolution.queryHash && actual.coordinate == resolution.coordinate &&
                actual.catalogHash == resolution.catalogHash && actual.capabilityHash == resolution.capabilityHash) { "candidate execution differs from authorized typed request" }
            actual
        }
    }

    private fun authorizedSources(request: ServerRequest, resolution: CandidateResolution): List<SourceConfiguration> {
        val selected = linkedMapOf<SourceBindingRef, SourceConfiguration>()
        resolution.sources.forEach {
            authorization.source(request.principal, "candidate-values", it.binding.dataSourceId)
            val source = sources.requireConfiguration(it.binding)
            if (source.connectorRef != it.connectorRef) throw ProducerContractException("resolved candidate connector differs from deployed source")
            selected[source.bindingRef()] = source
        }
        return selected.values.toList()
    }
}
