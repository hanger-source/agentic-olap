package com.olap.prototype.dsl

import com.olap.prototype.dsl.transport.CapabilityRegistry
import com.olap.prototype.dsl.transport.CapabilityContract
import com.olap.prototype.query.QueryComputation
import com.olap.prototype.calculation.Calculation
import java.lang.reflect.Type
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.memberProperties
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KVisibility
import kotlin.reflect.jvm.jvmErasure

data class ApiOperation(val operationId: String, val receiver: String, val function: String, val input: String,
    val output: String, val permission: String, val effect: String)
data class ApiParameter(val name: String, val type: String, val optional: Boolean)
data class ApiConstructor(val parameters: List<ApiParameter>)
data class ApiProperty(val name: String, val type: String, val mutable: Boolean)
data class ApiType(val name: String, val kotlinName: String, val kind: String, val constructors: List<ApiConstructor>, val properties: List<ApiProperty>, val variants: List<String>, val enumValues: List<String>)
data class ApiExample(val name: String, val purpose: String, val source: String)
data class ApiOverview(val formatVersion: String, val dslApiVersion: String, val properties: Map<String, String>, val imports: List<String>,
    val operations: List<ApiOperation>, val examples: List<ApiExample>)

/** 从实际公开函数、正式构造器及封闭变体读取，不维护另一份 JSON 节点表。 */
object ApiDiscovery {
    const val VERSION = "agentic-olap-kotlin-dsl/v1"
    val imports: List<String> = listOf("com.olap.prototype.dsl.*", "com.olap.prototype.governance.contract.*",
        "com.olap.prototype.governance.contract.GovernanceReadRequests.AssetVersionRequest",
        "com.olap.prototype.governance.contract.GovernanceReadRequests.DefinitionDependenciesRequest",
        "com.olap.prototype.governance.contract.GovernanceReadRequests.ReleaseIdentityRequest",
        "com.olap.prototype.governance.contract.GovernanceReadRequests.Page",
        "com.olap.prototype.governance.contract.GovernanceReadRequests.EmptyInput",
        "com.olap.prototype.governance.contract.GovernanceReadRequests.OperationInput",
        "com.olap.prototype.governance.contract.GovernanceReadRequests.DescribeCommandInput",
        "com.olap.prototype.governance.contract.GovernanceReadRequests.PageInput",
        "com.olap.prototype.governance.contract.GovernanceReadRequests.SourceDiscoveriesInput",
        "com.olap.prototype.governance.contract.GovernanceReadRequests.SnapshotInput",
        "com.olap.prototype.governance.contract.GovernanceReadRequests.SnapshotPageInput",
        "com.olap.prototype.governance.contract.GovernanceReadRequests.DiscoveredTablePageInput",
        "com.olap.prototype.governance.contract.GovernanceReadRequests.SourcePageInput",
        "com.olap.prototype.governance.contract.GovernanceReadRequests.IdentityInput",
        "com.olap.prototype.governance.contract.GovernanceReadRequests.VersionInput",
        "com.olap.prototype.governance.contract.GovernanceReadRequests.VersionPageInput",
        "com.olap.prototype.governance.contract.GovernanceReadRequests.SubjectPageInput", "com.olap.prototype.query.contract.*",
        "com.olap.prototype.query.*", "com.olap.prototype.calculation.*", "com.olap.prototype.expression.*",
        "com.olap.prototype.metric.*", "com.olap.prototype.member.*", "com.olap.prototype.event.*",
        "com.olap.prototype.time.*", "com.olap.prototype.warehouse.onboarding.*", "com.olap.prototype.warehouse.contract.*", "com.olap.prototype.source.contract.*")
    fun overview(): ApiOverview = ApiOverview("agentic-olap-dsl-discovery/v1", VERSION,
        mapOf("olap" to OlapClient::class.qualifiedName!!, "olap.caller.subject" to "kotlin.String",
            "olap.temporaryDirectory" to "java.nio.file.Path", "parameters" to ScriptArguments::class.qualifiedName!!), imports,
        CapabilityRegistry.all().map(::operation), examples())
    fun operation(operationId: String): ApiOperation = operation(CapabilityRegistry.require(operationId))
    private fun operation(value: CapabilityContract) = ApiOperation(value.operationId,
        when (value.owner) { "GovernanceClient" -> "olap.governance"; "QueryClient" -> "olap.queries"; "SemanticClient" -> "olap.semantics"; "ResultClient" -> "olap.results"; "SourceClient" -> "olap.sources"; else -> error("unregistered API owner") },
        value.method, value.kotlinInput, value.kotlinOutput, value.permission, value.effect)
    fun type(type: Type): ApiType = describe(when (type) {
        is Class<*> -> type.kotlin
        is java.lang.reflect.ParameterizedType -> (type.rawType as Class<*>).kotlin
        else -> throw IllegalArgumentException("select a concrete registered public type")
    })
    fun type(name: String): ApiType = describe(types[name] ?: throw IllegalArgumentException("unknown public DSL type: $name"))

    private val types: Map<String, KClass<*>> by lazy {
        val found = linkedMapOf<String, KClass<*>>()
        fun addClass(type: KClass<*>) {
            val name = type.qualifiedName ?: return
            if (!name.startsWith("com.olap.prototype.") || found.putIfAbsent(name, type) != null) return
            require(found.size <= 4096) { "public API type graph exceeds its discovery budget" }
            type.primaryConstructor?.parameters?.forEach { parameter ->
                fun add(value: KType) {
                    (value.classifier as? KClass<*>)?.let(::addClass)
                    value.arguments.forEach { it.type?.let(::add) }
                }
                add(parameter.type)
            }
            type.sealedSubclasses.forEach(::addClass)
        }
        fun addJava(type: Type) {
            when (type) {
                is Class<*> -> addClass(type.kotlin)
                is java.lang.reflect.ParameterizedType -> { addJava(type.rawType); type.actualTypeArguments.forEach(::addJava) }
                is java.lang.reflect.WildcardType -> type.upperBounds.forEach(::addJava)
            }
        }
        CapabilityRegistry.all().forEach { addJava(it.inputType); addJava(it.outputType) }
        listOf(Calculation::class, QueryComputation.Scope::class, ScriptValue::class, ScriptArguments::class, Caller::class).forEach(::addClass)
        found
    }
    private fun describe(type: KClass<*>): ApiType = ApiType(type.qualifiedName ?: type.java.name,
        type.simpleName ?: type.java.simpleName,
        when {
            type.objectInstance != null -> "OBJECT"
            type.java.isEnum -> "ENUM"
            type.isSealed && type.java.isInterface -> "SEALED_INTERFACE"
            type.isSealed -> "SEALED_CLASS"
            type.java.isInterface -> "INTERFACE"
            type.isData -> "DATA_CLASS"
            else -> "CLASS"
        },
        listOfNotNull(type.primaryConstructor?.takeIf { it.visibility == KVisibility.PUBLIC }?.let { constructor -> ApiConstructor(constructor.parameters.map { parameter ->
            ApiParameter(requireNotNull(parameter.name), parameter.type.toString(), parameter.isOptional)
        }) }), type.memberProperties.filter { it.visibility == KVisibility.PUBLIC }
            .map { ApiProperty(it.name, it.returnType.toString(), (it as? KMutableProperty<*>)?.setter?.visibility == KVisibility.PUBLIC) }.sortedBy { it.name },
        type.sealedSubclasses.mapNotNull { it.qualifiedName }.sorted(),
        type.java.enumConstants?.map { (it as Enum<*>).name } ?: emptyList())

    private fun examples(): List<ApiExample> = listOf(
        ApiExample("读取后决定", "一次脚本读取获准定义，并用普通 Kotlin 分支处理真实返回。", """
            val page = olap.semantics.metrics(PageInput(offset = 0, limit = 20))
            val selected = page.items.firstOrNull { it.code == parameters.text("metricCode") }
            if (selected == null) ScriptValue.Null else ScriptValue.Text(selected.id)
        """.trimIndent()),
        ApiExample("执行并返回回执", "完整 Query 由本次代码构造，执行经正式发布授权和结果交付。", """
            val publication = QueryCapabilityRequest(parameters.text("scenarioId"), parameters.decimal("scenarioVersion").longValueExact(), parameters.text("releaseId"), parameters.text("releaseVersion"))
            val source = MemberRef(parameters.text("memberSourceId"), parameters.decimal("memberSourceVersion").longValueExact())
            val computation = scope(QueryComputation.Members("population", source, emptyMap(),
                listOf(QueryComputation.Slot("identity", parameters.text("identitySlot"))),
                null, emptyMap(), MemberCondition.Unrestricted(), emptyMap(), emptyMap()))
            val receipt = olap.queries.execute(QueryExecutionRequest(QuerySelection.Inline(QueryIntent(publication, computation)), true, "本次分析", null))
            olap.output(receipt)
        """.trimIndent())
    )
}
