package com.olap.prototype.server

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.codec.*
import com.olap.prototype.connector.OlapConnector
import com.olap.prototype.connector.loader.ConnectorPlugins
import com.olap.prototype.connector.loader.DeployedSources
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.dsl.*
import com.olap.prototype.dsl.transport.CapabilityRegistry
import com.olap.prototype.governance.application.*
import com.olap.prototype.governance.contract.DeploymentAvailabilityRequest
import com.olap.prototype.management.governance.PhysicalAssetGovernanceService
import com.olap.prototype.management.warehouse.*
import com.olap.prototype.protocol.*
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.definition.*
import com.olap.prototype.query.usage.QueryUsageRepository
import com.olap.prototype.release.catalog.JdbcPublishedQueryEntryProvider
import com.olap.prototype.runtime.JsonResourceLimits
import com.olap.prototype.server.ServerInputs.fields
import com.olap.prototype.server.ServerInputs.text
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.contract.SourceConfiguration
import com.olap.prototype.warehouse.onboarding.WarehouseOnboardingService
import com.olap.prototype.warehouse.onboarding.SourceDatasetRegistrationRequest
import com.olap.prototype.source.contract.EmptyRequest as SourceEmptyRequest
import com.olap.prototype.source.contract.SourceInspection
import com.olap.prototype.source.contract.ConnectorCapabilitiesRequest
import java.nio.file.Path

/** 元数据与业务授权的owner；进程监督和结果文件只归Go。 */
internal class ServerApplication private constructor(private val plugins: ConnectorPlugins,
    private val database: PlatformDatabase, private val config: JsonNode,
    private val limits: JsonResourceLimits, workers: RemoteWorkers) : AutoCloseable {
    private val mapper = OlapJsonUtils.mapper()
    private val capabilityProtos = CapabilityProtos(limits.requestBytes, limits.responseBytes, limits.inputDepth)
    private val configured = mapper.treeToValue(config.required("dataSources"), Array<SourceConfiguration>::class.java).toList()
    private val deployed = DeployedSources(configured, plugins.registry)
    private val authorization = ServerAuthorization(mapper.convertValue(config.required("auth").required("grants"),
        object : TypeReference<List<ServerAuthorization.Grant>>() {}))
    private val publicationRepository = JdbcPublishedQueryEntryProvider(database)
    private val usage = QueryUsageRepository(database, limits)
    private val results = ServerResults(usage, publicationRepository, authorization,
        config.required("history").required("retentionDays").longValue(), config.required("limits").required("maxInFlight").intValue())
    private val publications = ServerPublications(publicationRepository, authorization)
    private val queries = ServerQueries(JdbcQueryDefinitionRepository(database, limits), publicationRepository,
        authorization, workers, deployed, usage, results, config.required("history").required("enabled").booleanValue(), limits)
    private val availability = DeploymentAvailabilityService(database, plugins.registry, limits)
    private val sources: ServerSources
    private val governance: ServerGovernance
    private val operations: Map<String, Operation>
    private class Operation(val readOnly: Boolean, val description: String, val payload: Map<String,String>, val permission: String,
        val handler: ((ServerRequest, JsonNode) -> Any)?) {
        fun description(): Map<String,Any> = mapOf("readOnly" to readOnly, "description" to description, "payload" to payload)
    }
    private val resultReaders = setOf("read-query-result", "read-query-result-metadata", "read-query-result-value", "profile-query-result")

    init {
        SourceRegistrationService(database, plugins.registry).initializeAuthorizedSources(configured)
        usage.interruptUnfinished()
        results.retainHistory()
        val warehouse = WarehouseRegistryRepository(database)
        val physical = PhysicalAssetGovernanceService(database, warehouse)
        val onboarding = WarehouseOnboardingService(physical, WarehouseRegistryService(database, warehouse, physical), deployed::requireConfiguration)
        sources = ServerSources(deployed, plugins.registry, authorization, publicationRepository, workers, onboarding)
        governance = ServerGovernance(GovernanceOperations(GovernanceApplication(database, plugins.registry), deployed.bindings(), onboarding), authorization, workers)
        operations = registeredOperations()
    }

    fun ready(): RuntimeReady = RuntimeReady.newBuilder().setConnectorApi(OlapConnector.API_VERSION).apply {
        operations.forEach { (name, operation) -> addOperations(com.olap.prototype.protocol.Operation.newBuilder()
            .setName(name).setReadOnly(operation.readOnly).setDescription(operation.description)
            .setPayloadJson(com.google.protobuf.ByteString.copyFrom(mapper.writeValueAsBytes(operation.payload)))) }
        addAllResultOperations(resultReaders)
        configured.forEachIndexed { index, source -> addSources(RegisteredSource.newBuilder().setConfigurationIndex(index)
            .setBinding(SourceBinding.newBuilder().setDataSourceId(source.dataSourceId).setSourceIdentityHash(source.sourceIdentityHash()))) }
    }.build()

    fun dispatch(operation: String, request: ServerRequest, payload: JsonNode): Any {
        require(payload.isObject) { "payload must be an object" }
        when (operation) {
            "operation-discovery" -> {
                fields(payload, emptySet())
                return mapOf("operations" to operations.filterValues { authorization.matching(request.principal, it.permission).isNotEmpty() }
                    .mapValues { it.value.description() })
            }
            "authorize-script" -> {
                fields(payload, emptySet()); authorization.operation(request.principal, "execute-script")
                return mapOf("authorized" to true)
            }
        }
        val registered = operations[operation] ?: throw ProducerContractException("operation is not registered")
        authorization.operation(request.principal, registered.permission)
        request.control.checkCancellation()
        return requireNotNull(registered.handler) { "operation belongs to the Go service" }(request, payload)
    }

    fun invoke(request: ServerRequest, invocation: CapabilityInvocation): Response {
        val operation = invocation.operationId
        val contract = CapabilityRegistry.require(operation)
        authorization.operation(request.principal, contract.permission)
        request.control.checkCancellation()
        val input = capabilityProtos.decodeRequest(operation, invocation.input)
        if (operation == "execute-query") { results.retainHistory(); return queries.execute(request, input as QueryExecutionRequest) }
        val result: Any = when (operation) {
            "validate-query" -> queries.validate(request, input as QueryValidationRequest)
            "explain-query" -> queries.explain(request, input as QueryExplanationRequest)
            "save-query", "update-query" -> queries.save(operation, request, input as QueryDefinitionCommand)
            "read-query" -> queries.read(request, input as QueryVersionRequest)
            "list-queries" -> queries.list(request, input as QueryListRequest)
            "list-data-sources" -> sources.list(request,input as SourceEmptyRequest)
            "read-connector-capabilities" -> sources.capabilities(request,input as ConnectorCapabilitiesRequest)
            "inspect-warehouse" -> sources.inspect(request,input as SourceInspection)
            "register-source-dataset" -> sources.registerDataset(request,input as SourceDatasetRegistrationRequest)
            "candidate-values" -> sources.candidates(request,input as CandidateRequest)
            "read-deployment-availability" -> availability(request,input as DeploymentAvailabilityRequest)
            else -> if (contract.owner == "SemanticClient" && contract.permission == "read-query-capability") publications.invoke(operation,request,input)
                else if (GovernanceCapabilityCodec.supports(operation)) governance.invoke(operation, request, input)
                else results.invoke(operation, request, input)
        }
        return Response.newBuilder().setRequestId(request.work.requestId).setSuccess(true)
            .setModel(capabilityProtos.encodeResult(operation, result)).build()
    }

    fun completeDelivery(request: ServerRequest, confirmation: DeliveryConfirmation): Response =
        Response.newBuilder().setRequestId(request.work.requestId).setSuccess(true)
            .setModel(capabilityProtos.encodeResult("execute-query", results.completeDelivery(request, confirmation,
                capabilityProtos.decodeResult("execute-query", ProtoBytes.encode(confirmation.executionReceipt, limits.responseBytes)) as QueryExecutionReceipt))).build()

    fun authorizeResult(request: ServerRequest, input: ResultAuthorization): Response {
        require(input.operationId in resultReaders && input.hasReference()) { "exact result read authorization is required" }
        val selected = results.authorize(request, QueryProto.fromProto(input.reference), input.operationId)
        return Response.newBuilder().setRequestId(request.work.requestId).setSuccess(true)
            .setModel(ProtoBytes.pack(QueryProto.toProto(selected), limits.responseBytes)).build()
    }

    private fun describeApi(request: ServerRequest, payload: JsonNode): Any {
        fields(payload, setOf("operation", "type"))
        require(!(payload.has("operation") && payload.has("type"))) { "select one operation or public type" }
        return when {
            payload.has("operation") -> {
                val operation = text(payload, "operation")
                authorization.operation(request.principal, CapabilityRegistry.require(operation).permission)
                ApiDiscovery.operation(operation)
            }
            payload.has("type") -> ApiDiscovery.type(text(payload, "type"))
            else -> ApiDiscovery.overview().let { view -> view.copy(operations = view.operations.filter {
                authorization.matching(request.principal, it.permission).isNotEmpty()
            }) }
        }
    }
    private fun registeredOperations(): Map<String, Operation> = buildMap {
        fun add(name: String, description: String, payload: Map<String,String> = emptyMap(),
            readOnly: Boolean = true, permission: String = name, handler: ((ServerRequest,JsonNode)->Any)? = null) {
            check(put(name, Operation(readOnly, description, payload, permission, handler)) == null)
        }
        add("describe-script-api", "读取实际Kotlin API、构造器、封闭变体与完整脚本示例。", mapOf("operation" to "optional exact operation", "type" to "optional fully qualified public type"), handler = ::describeApi)
        add("execute-script", "在隔离JVM中编译并运行整段kts；各次正式调用分别授权和记录。",
            mapOf("source" to "complete kts source", "sourceName" to "diagnostic filename", "dslApiVersion" to "exact deployed DSL version",
                "parameters" to "bounded ordinary JSON object; exact limits and counting rules: describe-script-api type=com.olap.prototype.dsl.ScriptArguments, rules"), false)
        add("describe-operation", "读取获准操作的输入说明。", mapOf("operation" to "exact operation"), handler = { r,p ->
            fields(p, setOf("operation")); val selected = text(p,"operation")
            val operation = operations[selected] ?: throw ProducerContractException("operation is not registered")
            authorization.operation(r.principal, operation.permission); operation.description()
        })
        add("list-data-sources", "列出获准来源的精确身份与能力，不返回凭据。", handler = {r,p -> fields(p,emptySet());sources.list(r,SourceEmptyRequest)})
        add("list-publications", "分页发现获准发布。", handler = publications::list)
        add("read-deployment-availability", "核对精确资产在本部署的可用性，不执行来源查询。", handler = ::availability)
        add("search-query-usage", "检索本人实际用法。", handler = results::search)
        add("read-query-usage", "读取本人运行与交付事实。", handler = results::read)
        add("append-query-usage-note", "为已有用法追加明确说明。", readOnly = false, handler = results::appendNote)
        resultReaders.forEach { add(it, "读取本人已保存结果；不重新执行来源查询。", permission = CapabilityRegistry.require(it).permission) }
    }

    private fun availability(request: ServerRequest, payload: JsonNode): Any {
        return availability(request,mapper.treeToValue(payload, DeploymentAvailabilityRequest::class.java))
    }
    private fun availability(request: ServerRequest, input: DeploymentAvailabilityRequest): com.olap.prototype.governance.contract.DeploymentAvailability {
        authorization.governance(request.principal, "read-deployment-availability")
        input.results.forEach { reference ->
            val run = usage.require(reference.runId)
            if (run.subject != request.principal.subject) throw SecurityException("saved result belongs to another subject")
            authorization.publication(request.principal, "read-deployment-availability", run.subjectAreaId, run.publication.releaseId)
        }
        return availability.read(input, deployed.bindings(), request.control) { source -> authorization.source(request.principal, "read-deployment-availability", source) }
    }
    override fun close() { results.close(); try { database.close() } finally { plugins.close() } }
    companion object {
        fun open(config: JsonNode, limits: JsonResourceLimits, workers: RemoteWorkers): ServerApplication {
            val plugins = ConnectorPlugins.load(config.required("connectors").map { Path.of(it.textValue()) })
            var database: PlatformDatabase? = null
            try {
                database = PlatformDatabase.openSingleInstance(Path.of(text(config,"dataRoot")).resolve("metadata"))
                return ServerApplication(plugins, database, config, limits, workers)
            } catch (failure: Throwable) {
                try { database?.close() } catch (close: Exception) { failure.addSuppressed(close) }
                try { plugins.close() } catch (close: Exception) { failure.addSuppressed(close) }
                throw failure
            }
        }
    }
}
