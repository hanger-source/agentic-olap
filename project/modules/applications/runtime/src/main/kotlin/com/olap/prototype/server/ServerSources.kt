package com.olap.prototype.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.core.JsonProcessingException
import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.connector.OlapConnector
import com.olap.prototype.connector.loader.DeployedSources
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.InputContractException
import com.olap.prototype.expression.NativeAggregateTypes
import com.olap.prototype.query.contract.QueryCapabilityRequest
import com.olap.prototype.release.catalog.JdbcPublishedQueryEntryProvider
import com.olap.prototype.server.ServerInputs.command
import com.olap.prototype.server.ServerInputs.fields
import com.olap.prototype.server.ServerInputs.text
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.contract.SourceBindingRef
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.onboarding.SourceDatasetRegistrationRequest
import com.olap.prototype.warehouse.onboarding.WarehouseOnboardingService

internal class ServerSources(private val sources: DeployedSources,
    private val connectors: ConnectorRegistry, private val authorization: ServerAuthorization,
    private val publications: JdbcPublishedQueryEntryProvider, private val workers: RemoteWorkers,
    private val onboarding: WarehouseOnboardingService) {
    private val mapper = OlapJsonUtils.mapper()

    fun registerDataset(request: ServerRequest, payload: JsonNode): Any {
        fields(payload, setOf("dataSourceId", "sourceIdentityHash", "declarationJson", "sha256"))
        val input = mapper.treeToValue(payload, SourceDatasetRegistrationRequest::class.java)
        authorization.source(request.principal, "register-source-dataset", input.dataSourceId)
        sources.requireConfiguration(SourceBindingRef(input.dataSourceId, input.sourceIdentityHash))
        return onboarding.registerSourceDataset(input, request.control, SourceInvestigationClient(workers, request.work))
    }

    private fun visible(request: ServerRequest, operation: String) = sources.configurations().filter { source ->
        authorization.matching(request.principal, operation).any { ServerAuthorization.includes(it.dataSources, source.dataSourceId) }
    }

    fun list(request: ServerRequest, payload: JsonNode): Any {
        fields(payload, emptySet())
        return visible(request, "list-data-sources").sortedWith(compareBy<SourceConfiguration> { it.dataSourceId }.thenBy { it.sourceIdentityHash() }).map {
            mapOf("dataSourceId" to it.dataSourceId, "sourceIdentityHash" to it.sourceIdentityHash(),
                "connectorRef" to it.connectorRef, "dialectId" to it.dialectId,
                "accessCapabilities" to connectors.require(it.connectorRef).access.capabilities(it).supported)
        }
    }

    fun capabilities(request: ServerRequest, payload: JsonNode): Any {
        val input = try { mapper.treeToValue(payload, ConnectorCapabilitiesRequest::class.java) }
        catch (failure: JsonProcessingException) { throw InputContractException.decoding(failure, payload) }
        val permitted = visible(request, "read-connector-capabilities").map { it.connectorRef }.distinct()
        val aggregate = input.aggregate?.let {
            if (it.connectorRef !in permitted) throw SecurityException("connector is not used by an authorized source")
            mapOf("connectorRef" to it.connectorRef, "aggregation" to it.aggregation, "inputType" to it.inputType,
                "grouped" to it.grouped, "mapping" to NativeAggregateTypes.describe(connectors.require(it.connectorRef).compilation,
                    it.aggregation, it.inputType, it.grouped))
        }
        return buildMap {
            put("apiVersion", OlapConnector.API_VERSION)
            put("connectors", permitted.map {
                val connector = connectors.require(it)
                mapOf("connectorRef" to it, "nativeScalarFunctions" to connector.compilation.nativeScalarFunctions,
                    "compilationCapabilities" to CompilationSupport.Capability.entries.filter(connector.compilation::supports),
                    "parameterSubmission" to connector.compilation.parameterSubmission,
                    "configurationParameters" to connector.access.configurationParameters)
            })
            if (aggregate != null) put("aggregate", aggregate)
        }
    }

    fun inspect(request: ServerRequest, payload: JsonNode): Any {
        fields(payload, setOf("operation", "payload"))
        val body = payload.required("payload")
        val operation = text(payload, "operation")
        val selected = if (operation == "read-inspection-capability") {
            fields(body, emptySet()); emptyList()
        } else {
            val id = text(body, "dataSourceId")
            authorization.source(request.principal, "inspect-warehouse", id)
            listOf(sources.requireConfiguration(SourceBindingRef(id, text(body, "sourceIdentityHash"))))
        }
        return workers.borrow(RemoteWorkers.Kind.INSPECTION, selected, request.work).use {
            it.exchange(command(operation, body))
        }
    }

    fun candidates(request: ServerRequest, payload: JsonNode): Any {
        val coordinate = QueryCapabilityRequest(text(payload, "scenarioId"), payload.required("scenarioVersion").longValue(),
            text(payload, "releaseId"), text(payload, "releaseVersion"))
        val publication = publications.readPublication(coordinate)
        val entry = publication.entry
        authorization.publication(request.principal, "candidate-values", entry)
        val resolution = workers.borrow(RemoteWorkers.Kind.CANDIDATE_VALUES, emptyList(), request.work).use {
            WorkerPublications.candidate(it, publication)
            it.exchange(command("resolve-candidate-input", payload))
        }
        check(mapper.treeToValue(resolution.required("coordinate"), QueryCapabilityRequest::class.java) == publication.executable.coordinate &&
            text(resolution, "catalogHash") == entry.catalogHash && text(resolution, "capabilityHash") == entry.capability.capabilityHash) {
            "resolved candidate input differs from exact publication"
        }
        val selected = authorizedSources(request, resolution)
        return workers.borrow(RemoteWorkers.Kind.CANDIDATE_VALUES, selected, request.work).use {
            WorkerPublications.candidate(it, publication)
            val actual = it.exchange(command("candidate-values", payload))
            check(text(actual, "queryHash") == text(resolution, "queryHash")) { "candidate execution differs from authorized typed request" }
            actual
        }
    }

    private fun authorizedSources(request: ServerRequest, resolution: JsonNode): List<SourceConfiguration> {
        if (!resolution.required("sources").isArray) throw ProducerContractException("typed candidate source requirements are required")
        val selected = linkedMapOf<SourceBindingRef, SourceConfiguration>()
        resolution.required("sources").forEach {
            val id = text(it, "dataSourceId")
            authorization.source(request.principal, "candidate-values", id)
            val source = sources.requireConfiguration(SourceBindingRef(id, text(it, "sourceIdentityHash")))
            if (source.connectorRef != mapper.treeToValue(it.required("connectorRef"), ConnectorRef::class.java))
                throw ProducerContractException("resolved candidate connector differs from deployed source")
            selected[source.bindingRef()] = source
        }
        return selected.values.toList()
    }
}
