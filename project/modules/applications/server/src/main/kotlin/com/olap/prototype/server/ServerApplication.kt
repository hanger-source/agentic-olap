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

/** 主服务中的正式应用；治理、查询和授权在本地调用，编码只属于真实边界。 */
internal class ServerApplication private constructor(private val plugins: ConnectorPlugins,
    private val database: PlatformDatabase, private val config: JsonNode,
    private val limits: JsonResourceLimits, workers: WorkerPool) : AutoCloseable {
    private val mapper = OlapJsonUtils.mapper()
    private val capabilityProtos = CapabilityProtos(limits.requestBytes, limits.inputDepth)
    private val configured = mapper.treeToValue(config.required("dataSources"), Array<SourceConfiguration>::class.java).toList()
    private val deployed = DeployedSources(configured, plugins.registry)
    private val authorization = ServerAuthorization(mapper.convertValue(config.required("auth").required("grants"),
        object : TypeReference<List<ServerAuthorization.Grant>>() {}))
    private val publicationRepository = JdbcPublishedQueryEntryProvider(database)
    private val usage = QueryUsageRepository(database, limits)
    private val resultFiles = com.olap.prototype.server.results.ResultFiles(java.nio.file.Path.of(config.required("dataRoot").textValue()))
    private val results = ServerResults(usage, publicationRepository, authorization,
        config.required("history").required("retentionDays").longValue(),
        resultFiles)
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

    val authorizationPolicyHash: String = ProtoBytes.digest(mapper.writeValueAsBytes(config.required("auth")))

    fun descriptions(principal: ServerAuthorization.Principal): Map<String, OperationDescription> = operations
        .filterValues { authorization.matching(principal, it.permission).isNotEmpty() }
        .mapValues { (_, operation) -> OperationDescription(operation.readOnly, operation.description, operation.payload) }

    fun authorize(principal: ServerAuthorization.Principal, operation: String) = authorization.operation(principal, operation)

    fun dispatch(operation: String, request: ServerRequest, payload: JsonNode): Any {
        require(payload.isObject) { "payload must be an object" }
        val registered = operations[operation] ?: throw ProducerContractException("operation is not registered")
        authorization.operation(request.principal, registered.permission)
        request.control.checkCancellation()
        registered.handler?.let { return it(request, payload) }
        val input = when (operation) {
            "read-query-result" -> mapper.treeToValue(payload, ResultReadRequest::class.java)
            "read-query-result-metadata" -> mapper.treeToValue(payload, ResultMetadataRequest::class.java)
            "read-query-result-value" -> mapper.treeToValue(payload, ResultValueRequest::class.java)
            "profile-query-result" -> mapper.treeToValue(payload, ResultProfileRequest::class.java)
            else -> throw ProducerContractException("operation requires the formal script lifecycle entry: $operation")
        }
        return results.invoke(operation, request, input)
    }

    fun invoke(request: ServerRequest, invocation: CapabilityInvocation): Any {
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
        return result
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
            payload.has("type") -> ApiDiscovery.type(text(payload, "type")).let { type ->
                if (type.name == ScriptArguments::class.qualifiedName || type.name.startsWith("com.olap.prototype.dsl.ScriptValue"))
                    type.copy(rules = type.rules + ("deploymentBudgets" to scriptBudgets())) else type
            }
            else -> ApiDiscovery.overview().let { view -> view.copy(operations = view.operations.filter {
                authorization.matching(request.principal, it.permission).isNotEmpty()
            }) }
        }
    }
    private fun scriptBudgets(): Map<String, Any> = mapOf(
        "requestBytes" to limits.requestBytes, "inputNodes" to limits.inputNodes, "inputDepth" to limits.inputDepth,
        "responseBytes" to limits.responseBytes,
        "inputCounting" to "ROOT_DEPTH_1_ROOT_CONTAINERS_AND_NULL_COUNTED_OBJECT_KEYS_NOT_COUNTED",
        "transport" to "FINAL_MCP_JSON_BYTES_INLINE_OR_AUTOMATIC_COMPLETE_FILE_WITH_READ_RESPONSE",
    )
    private fun registeredOperations(): Map<String, Operation> = buildMap {
        fun add(name: String, description: String, payload: Map<String,String> = emptyMap(),
            readOnly: Boolean = true, permission: String = name, handler: ((ServerRequest,JsonNode)->Any)? = null) {
            check(put(name, Operation(readOnly, description, payload, permission, handler)) == null)
        }
        add("describe-script-api", "读取实际Kotlin API、构造器、封闭变体与完整脚本示例。", mapOf("operation" to "optional exact operation", "type" to "optional fully qualified public type"), handler = ::describeApi)
        add("execute-script", "在持续 Kotlin 会话求值代码；首次可建立会话。执行身份去重，正式调用各自授权与记录。",
            mapOf("sessionId" to "optional exact session; omit to establish one", "executionId" to "required caller execution identity; identical retries observe the existing execution",
                "source" to "complete Kotlin snippet", "sourceName" to "diagnostic .kts display name", "dslApiVersion" to "exact deployed DSL version",
                "parameters" to "current immutable ordinary parameter snapshot; deployment JSON budgets apply",
                "waitMillis" to "wait duration only; zero returns current state, later waits do not restart or extend execution"), false)
        add("wait-script", "等待已有执行，不重放源码、不刷新执行期限。", mapOf("executionId" to "exact caller execution identity", "waitMillis" to "maximum wait in milliseconds; zero reads current state"))
        add("cancel-script", "请求停止已有执行；保留实际调用和输出，来源取消结局独立报告。", mapOf("executionId" to "exact caller execution identity"), false)
        add("read-script-session", "读取本人的语言会话状态，不执行代码。", mapOf("sessionId" to "exact owned session"))
        add("reset-script-session", "停止当前执行并确认旧进程退出后重建语言上下文；不重放业务。", mapOf("sessionId" to "exact owned session"), false)
        add("close-script-session", "停止当前执行并确认进程退出后关闭会话，保留持久业务资产。", mapOf("sessionId" to "exact owned session"), false)
        add("describe-operation", "读取获准操作的输入说明。", mapOf("operation" to "exact operation"), handler = { r,p ->
            fields(p, setOf("operation")); val selected = text(p,"operation")
            val operation = operations[selected] ?: throw ProducerContractException("operation is not registered")
            authorization.operation(r.principal, operation.permission)
            if (selected == "execute-script") operation.description() + ("resourceBudgets" to scriptBudgets()) else operation.description()
        })
        add("list-data-sources", "列出获准来源的精确身份与能力，不返回凭据。", handler = {r,p -> fields(p,emptySet());sources.list(r,SourceEmptyRequest)})
        add("read-connector-capabilities", "读取获准来源连接器的函数、聚合映射与配置声明，不执行来源查询。",
            mapOf("aggregate" to "optional exact connector aggregate mapping request"), handler = { r,p ->
                fields(p, setOf("aggregate"))
                sources.capabilities(r, mapper.treeToValue(p, ConnectorCapabilitiesRequest::class.java))
            })
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
        val view = availability.read(input, deployed.bindings(), request.control) { source -> authorization.source(request.principal, "read-deployment-availability", source) }
        return view.copy(checks = view.checks.map { check ->
            if (check.kind != com.olap.prototype.governance.contract.DeploymentAvailability.Kind.RESULT_READ ||
                check.status != com.olap.prototype.governance.contract.DeploymentAvailability.Status.NOT_CHECKED) check
            else {
                val reference = QueryResultReference(check.reference.getValue("runId") as String, check.reference.getValue("sha256") as String)
                val observed = resultFiles.inspect(reference)
                val issues = (observed.getValue("issues") as List<*>).map { code ->
                    com.olap.prototype.governance.contract.DeploymentAvailability.Issue(code as String, "saved result bytes are not readable by this deployment")
                }
                check.copy(status = if (issues.isEmpty()) com.olap.prototype.governance.contract.DeploymentAvailability.Status.AVAILABLE
                    else com.olap.prototype.governance.contract.DeploymentAvailability.Status.INVALID, issues = issues)
            }
        })
    }
    override fun close() { results.close(); try { database.close() } finally { plugins.close() } }
    companion object {
        fun open(config: JsonNode, limits: JsonResourceLimits, workers: WorkerPool): ServerApplication {
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
