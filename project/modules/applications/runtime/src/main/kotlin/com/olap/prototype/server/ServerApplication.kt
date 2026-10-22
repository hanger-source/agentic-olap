package com.olap.prototype.server

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.connector.OlapConnector
import com.olap.prototype.connector.loader.ConnectorPlugins
import com.olap.prototype.connector.loader.DeployedSources
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.governance.application.GovernanceApplication
import com.olap.prototype.governance.application.GovernanceCommandSchema
import com.olap.prototype.governance.application.GovernanceOperations
import com.olap.prototype.governance.application.DeploymentAvailabilityService
import com.olap.prototype.governance.contract.DeploymentAvailabilityRequest
import com.olap.prototype.management.governance.PhysicalAssetGovernanceService
import com.olap.prototype.management.warehouse.*
import com.olap.prototype.protocol.Operation as WireOperation
import com.olap.prototype.protocol.RegisteredSource
import com.olap.prototype.protocol.RuntimeReady
import com.olap.prototype.protocol.SourceBinding
import com.olap.prototype.query.contract.PublishedQueryContract
import com.olap.prototype.query.definition.JdbcQueryDefinitionRepository
import com.olap.prototype.query.definition.QueryDefinitionCommand
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
import java.nio.file.Path

/** 唯一元数据应用：领域状态、授权和操作注册；进程与工作准入全部由Go监督者拥有。 */
internal class ServerApplication private constructor(
    private val plugins: ConnectorPlugins, private val database: PlatformDatabase,
    private val config: JsonNode, private val limits: JsonResourceLimits, workers: RemoteWorkers,
) : AutoCloseable {
    private val mapper = OlapJsonUtils.mapper()
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
        authorization, workers, deployed, usage, results, config.required("history").required("enabled").booleanValue(), limits.inputFileBytes.toLong())
    private val sources: ServerSources
    private val governance: ServerGovernance
    private val operations: Map<String, Operation>
    private val availability = DeploymentAvailabilityService(database, plugins.registry, limits)

    private sealed interface Target {
        class Jvm(val handler: (ServerRequest, JsonNode) -> Any) : Target
        object GoResultReader : Target
    }

    private class Operation(val readOnly: Boolean, val description: String, val payload: Map<String, String>,
        val target: Target,
        val inputDescription: (() -> Any)? = null) {
        fun publicDescription(): Map<String, Any> = mapOf("readOnly" to readOnly, "description" to description, "payload" to payload)
        fun describe(): Any = inputDescription?.invoke() ?: publicDescription()
    }

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

    fun ready(): RuntimeReady {
        val ready = RuntimeReady.newBuilder().setConnectorApi(OlapConnector.API_VERSION)
        operations.forEach { (name, operation) ->
            ready.addOperations(WireOperation.newBuilder().setName(name).setReadOnly(operation.readOnly)
                .setDescription(operation.description).setPayloadJson(com.google.protobuf.ByteString.copyFrom(mapper.writeValueAsBytes(operation.payload))))
            if (operation.target === Target.GoResultReader) ready.addResultOperations(name)
        }
        configured.forEachIndexed { index, source -> ready.addSources(RegisteredSource.newBuilder()
            .setConfigurationIndex(index).setBinding(SourceBinding.newBuilder().setDataSourceId(source.dataSourceId)
                .setSourceIdentityHash(source.sourceIdentityHash()))) }
        return ready.build()
    }

    fun dispatch(operation: String, request: ServerRequest, payload: JsonNode): Any {
        if (!payload.isObject) throw ProducerContractException("payload must be an object")
        when (operation) {
            "operation-discovery" -> {
                fields(payload, emptySet())
                return mapOf("operations" to operations.filterKeys { authorization.matching(request.principal, it).isNotEmpty() }
                    .mapValues { it.value.publicDescription() })
            }
            "complete-result-delivery" -> return results.completeDelivery(request, payload)
            "authorize-result" -> {
                val selected = text(payload, "operation")
                if (operations[selected]?.target !== Target.GoResultReader) throw ProducerContractException("result read operation is required")
                return results.authorize(request, payload, selected)
            }
        }
        val registered = operations[operation] ?: throw ProducerContractException("operation is not registered")
        authorization.operation(request.principal, operation)
        request.control.checkCancellation()
        return when (val target = registered.target) {
            is Target.Jvm -> target.handler(request, payload)
            Target.GoResultReader -> throw ProducerContractException("saved result paging belongs to the Go result reader")
        }
    }

    private fun describe(request: ServerRequest, payload: JsonNode): Any {
        fields(payload, setOf("operation"))
        val selected = text(payload, "operation")
        authorization.operation(request.principal, selected)
        return (operations[selected] ?: throw ProducerContractException("operation is not registered")).describe()
    }

    private fun availability(request: ServerRequest, payload: JsonNode): Any {
        authorization.governance(request.principal, "read-deployment-availability")
        val input = mapper.treeToValue(payload, DeploymentAvailabilityRequest::class.java)
        input.results.forEach { reference ->
            val run = usage.require(reference.runId)
            if (run.subject != request.principal.subject) throw SecurityException("saved result belongs to another subject")
            authorization.publication(request.principal, "read-deployment-availability", run.subjectAreaId, run.publication.releaseId)
        }
        request.control.checkCancellation()
        val checked = availability.read(input, deployed.bindings(), request.control) { source ->
            authorization.source(request.principal, "read-deployment-availability", source)
        }
        return checked.copy(supported = checked.supported + ("resultFormat" to "olap-query-result/v3"))
    }

    private fun retained(handler: (ServerRequest, JsonNode) -> Any): (ServerRequest, JsonNode) -> Any = { request, payload ->
        results.retainHistory(); handler(request, payload)
    }

    private fun registeredOperations(): Map<String, Operation> {
        val registered = linkedMapOf<String, Operation>()
        fun add(name: String, operation: Operation) {
            check(registered.putIfAbsent(name, operation) == null) { "duplicate service operation" }
        }
        fun register(name: String, readOnly: Boolean, description: String, payload: Map<String, String> = emptyMap(),
            handler: (ServerRequest, JsonNode) -> Any, inputDescription: (() -> Any)? = null) {
            add(name, Operation(readOnly, description, payload, Target.Jvm(handler), inputDescription))
        }
        fun registerResultReader(name: String, description: String, payload: Map<String, String>) {
            add(name, Operation(true, description, payload, Target.GoResultReader))
        }
        register("describe-operation", true, "读取获准操作的参数合同。", mapOf("operation" to "string"), ::describe)
        register("read-deployment-availability", true, "核对所选精确发布、查询与保存结果在本部署的可用性；不迁移、不执行来源SQL。需要治理资产授权，结果仍限本人。",
            mapOf("publications" to "optional exact publication array", "queries" to "optional exact saved query reference array",
                "results" to "optional exact result reference array; at most 100 references total"), ::availability,
            inputDescription = { GovernanceCommandSchema.describeInput(DeploymentAvailabilityRequest::class.java) })
        register("read-query-language-contract", true, "读取完整或局部查询语言合同及稳定contentHash。section/root/operation互斥，path沿JSON字段，variant只选终点标签；未知selector拒绝。",
            mapOf("section" to "optional intent|scope", "root" to "optional exact root discriminator", "operation" to "optional exact post-operation opType", "path" to "optional JSON field-name array", "variant" to "optional endpoint union discriminator"),
            { r, p -> governance.language("read-query-language-contract", r, p) })
        register("read-semantic-expression-contract", true, "读取本部署表达式、函数与引用合同。", handler = { r, p -> governance.language("read-semantic-expression-contract", r, p) })
        register("explain-query", true, "编译完整查询并读取真实人口、粒度、NULL、来源及计划解释；不执行来源SQL，不要求先校验或保存。",
            mapOf("intent" to "complete intent, exclusive with queryReference", "queryReference" to "exact saved query reference",
                "section" to "optional summary|scopes|sources|plan|compilationScale", "offset" to "optional integer>=0",
                "limit" to "optional integer 1..200"), queries::explain)
        register("list-data-sources", true, "列出获准来源的精确binding、连接器及能力；不返回连接或凭据。", handler = sources::list)
        register("read-connector-capabilities", true, "读取获准连接器的原生函数、编译能力、参数提交和配置声明；可按聚合、完整输入类型和分组方式查询原生结果类型及AVG舍入。不执行来源SQL或证明完整查询可用。",
            mapOf("aggregate" to "optional exact connectorRef, aggregation, inputType and grouped boolean"), sources::capabilities,
            inputDescription = { GovernanceCommandSchema.describeInput(ConnectorCapabilitiesRequest::class.java) })
        register("register-source-dataset", false, "部署方登记源生产者的有限集合声明；须单独获准本操作和精确来源，实际核验后登记。",
            mapOf("dataSourceId" to "exact configured source ID", "sourceIdentityHash" to "exact binding hash",
                "declarationJson" to "complete source producer declaration text", "sha256" to "SHA-256 of exact declaration text"),
            sources::registerDataset, inputDescription = { GovernanceCommandSchema.describeInput(SourceDatasetRegistrationRequest::class.java) })
        register("discover-governance-commands", true, "列出实际注册的定义创建、修订、采用、生命周期与发布命令。", handler = governance::commands)
        register("discover-governance-reads", true, "列出同一正式注册表的治理读取操作。", handler = governance::reads)
        register("describe-governance-read", true, "读取治理读操作真实参数图。", mapOf("operation" to "registered governance read operation"), governance::describeRead)
        register("describe-governance-command", true, "读取实际命令完整或局部合同及contentHash；未知path/variant拒绝。",
            mapOf("operation" to "registered command", "path" to "optional JSON field-name array", "variant" to "optional endpoint union discriminator"), governance::describeCommand)
        register("read-governance", true, "读取精确治理事实。先discover-governance-reads，再describe-governance-read取得真实输入。",
            mapOf("operation" to "registered read", "payload" to "described read request"), governance::read)
        register("write-governance", false, "提交明确的定义、采用、生命周期或发布命令；actor须与主体一致，返回精确短回执和详细读取请求。",
            mapOf("operation" to "registered command", "payload" to "described command object"), governance::write)
        register("list-publications", true, "分页发现获准的已提交发布。", mapOf("subjectAreaId" to "optional string", "releaseId" to "optional string", "offset" to "optional integer>=0", "limit" to "optional integer 1..200"), publications::list)
        register("read-publication", true, "读取精确发布的公开语义与Query能力，不返回物理执行目录。",
            mapOf("scenarioId" to "string", "scenarioVersion" to "positive integer", "releaseId" to "string", "releaseVersion" to "string"), publications::read)
        register("search-semantics", true, "搜索精确发布语义，分页返回业务摘要和精确资产引用。",
            mapOf("coordinate" to "exact publication", "query" to "optional text", "kind" to "optional semantic kind", "offset" to "optional integer>=0", "limit" to "optional integer 1..200"), { r, p -> publications.semantics("search-semantics", r, p) })
        register("read-semantic-catalog", true, "显式读取精确catalogRef的完整公开语义。", mapOf("catalogRef" to "{coordinate,sha256}"), { r, p -> publications.semantics("read-semantic-catalog", r, p) })
        register("read-semantic-asset", true, "读取搜索返回的精确assetReference，不选择最新版。", mapOf("assetReference" to "{catalogRef,kind,id,version}"), { r, p -> publications.semantics("read-semantic-asset", r, p) })
        register("read-semantic-definition", true, "按精确引用读取冻结定义的一层正文。", mapOf("catalogRef" to "exact catalog reference", "kind" to "CALCULATION/FACT/INSTANCE/MEMBER", "reference" to "exact typed definition reference"), { r, p -> publications.semantics("read-semantic-definition", r, p) })
        register("inspect-warehouse", true, "只读调查精确已授权来源；read-inspection-capability使用空payload，其余须dataSourceId/sourceIdentityHash。禁止连接、SQL和服务器路径。", mapOf("operation" to "read-inspection-capability/discover/describe/snapshot/preview/profile/profile-relationship", "payload" to "typed inspection request"), sources::inspect)
        register("candidate-values", true, "读取精确发布下实际候选值。", mapOf("scenarioId" to "string", "scenarioVersion" to "positive integer", "releaseId" to "string", "releaseVersion" to "string", "capabilityHash" to "exact hash", "target" to "DIMENSION_PROVIDER or MEMBER_OUTPUT", "offset" to "optional integer", "limit" to "optional integer 1..200"), sources::candidates)
        for (name in listOf("validate-query", "execute-query")) {
            val input = mapOf("intent" to "complete intent, exclusive with queryReference", "queryReference" to "exact saved query reference", "saveResult" to "optional boolean", "question" to "optional explicit business question") +
                if (name == "execute-query") mapOf("readBasis" to "optional exact VERSIONED source read basis; requires PINNED_READ and exact returned evidence") else emptyMap()
            register(name, name != "execute-query", "对精确发布校验或执行完整查询；intent/queryReference二选一，保存结果是显式操作。", input,
                if (name == "validate-query") queries::validate else retained(queries::execute), inputDescription = {
                    mapOf("readOnly" to (name != "execute-query"), "payload" to (input + ("intent" to PublishedQueryContract.intentContract())))
                })
        }
        for (name in listOf("save-query", "update-query")) register(name, false, "显式保存或修订可复用查询；更新要求精确sourceVersion。", mapOf("payload" to "QueryDefinitionCommand"),
            { r, p -> queries.save(name, r, p) }, inputDescription = { GovernanceCommandSchema.describeInput(QueryDefinitionCommand::class.java) })
        register("read-query", true, "读取精确保存查询并核正文hash。", mapOf("queryReference" to "{definitionId,definitionVersion,sha256}"), queries::read)
        register("list-queries", true, "分页读取精确发布下保存查询。", mapOf("coordinate" to "exact publication", "offset" to "optional integer", "limit" to "optional integer 1..200"), queries::list)
        register("search-query-usage", true, "检索当前主体在精确发布下的实际用法。", mapOf("coordinate" to "exact publication", "text" to "optional question/note text", "offset" to "optional integer", "limit" to "optional integer"), retained(results::search))
        register("read-query-usage", true, "读取获准运行的执行、交付、引用与明确说明。", mapOf("runId" to "exact run", "offset" to "optional integer", "limit" to "optional integer"), retained(results::read))
        register("append-query-usage-note", false, "给已发生的用法追加明确业务说明。", mapOf("runId" to "exact run", "text" to "explicit business note"), retained(results::appendNote))
        registerResultReader("read-query-result", "分页读取精确已保存结果；columnIds只接受结果列ID。", mapOf("resultReference" to "{runId,sha256}", "offset" to "optional integer>=0", "limit" to "optional integer 0..200", "columnIds" to "optional exact column IDs"))
        registerResultReader("read-query-result-metadata", "按正式字段/序号路径读取结果元数据；不接受文件路径。", mapOf("resultReference" to "{runId,sha256}", "path" to "nonempty field/index array, maximum 256", "offset" to "optional integer>=0", "limit" to "optional integer 0..2000"))
        registerResultReader("read-query-result-value", "按同一结果交出的valueReference读取长值或集合，不重查业务源。", mapOf("resultReference" to "{runId,sha256}", "valueReference" to "exact typed value reference", "offset" to "optional integer>=0", "limit" to "optional integer 1..2000"))
        registerResultReader("profile-query-result", "统计仅描述这份保存结果，必须明确columnIds。", mapOf("resultReference" to "{runId,sha256}", "columnIds" to "required exact column IDs"))
        return registered.toMap()
    }

    override fun close() {
        results.close()
        try { database.close() } finally { plugins.close() }
    }

    companion object {
        fun open(config: JsonNode, limits: JsonResourceLimits, workers: RemoteWorkers): ServerApplication {
            val plugins = ConnectorPlugins.load(config.required("connectors").map { Path.of(it.textValue()) })
            var database: PlatformDatabase? = null
            try {
                database = PlatformDatabase.openSingleInstance(Path.of(text(config, "dataRoot")).resolve("metadata"))
                return ServerApplication(plugins, database, config, limits, workers)
            } catch (failure: Throwable) {
                try { database?.close() } catch (close: Exception) { failure.addSuppressed(close) }
                try { plugins.close() } catch (close: Exception) { failure.addSuppressed(close) }
                throw failure
            }
        }
    }
}
