package com.olap.prototype.dsl

import com.olap.prototype.dsl.transport.CapabilityRegistry
import com.olap.prototype.dsl.transport.CapabilityContract
import com.olap.prototype.query.QueryComputation
import com.olap.prototype.calculation.Calculation
import com.olap.prototype.member.MemberResult
import com.olap.prototype.governance.contract.LogicUnitRecord
import com.olap.prototype.governance.contract.LogicalTableCreateRequest
import com.olap.prototype.query.SetOutputContract
import java.lang.reflect.Type
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.memberProperties
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KVisibility

data class ApiOperation(val operationId: String, val receiver: String, val function: String, val input: String,
    val output: String, val permission: String, val effect: String)
data class ApiParameter(val name: String, val type: String, val optional: Boolean, val variableArguments: Boolean)
data class ApiConstructor(val parameters: List<ApiParameter>)
data class ApiProperty(val name: String, val type: String, val mutable: Boolean, val access: String?)
data class ApiFunction(val name: String, val access: String, val parameters: List<ApiParameter>, val output: String,
    val typeParameters: Map<String, List<String>>)
data class ApiCompanion(val name: String, val access: String, val properties: List<ApiProperty>, val functions: List<ApiFunction>)
data class ApiType(val name: String, val kotlinName: String, val kind: String, val enclosingType: String?, val constructors: List<ApiConstructor>, val properties: List<ApiProperty>,
    val functions: List<ApiFunction>, val companion: ApiCompanion?, val variants: List<String>, val enumValues: List<String>, val rules: Map<String, Any>)
data class ApiExample(val name: String, val purpose: String, val source: String)
data class ApiOverview(val formatVersion: String, val dslApiVersion: String, val properties: Map<String, String>, val imports: List<String>,
    val operations: List<ApiOperation>, val examples: List<ApiExample>)

/** 结构来自真实公开声明；局部语义规则只读取已知模型自身合同，不执行任意 getter 或工厂。 */
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
            fun add(value: KType) {
                (value.classifier as? KClass<*>)?.let(::addClass)
                value.arguments.forEach { it.type?.let(::add) }
            }
            type.primaryConstructor?.parameters?.forEach { parameter ->
                add(parameter.type)
            }
            type.memberProperties.filter { it.visibility == KVisibility.PUBLIC }.forEach { add(it.returnType) }
            type.java.enclosingClass?.kotlin?.takeIf { it.visibility == KVisibility.PUBLIC }?.let(::addClass)
            type.companionObject?.takeIf { it.visibility == KVisibility.PUBLIC }?.let(::addClass)
            if (type.objectInstance != null) publicFunctions(type).forEach { function ->
                function.parameters.filter { it.kind == KParameter.Kind.VALUE }.forEach { add(it.type) }
                function.typeParameters.flatMap { it.upperBounds }.forEach(::add)
                add(function.returnType)
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
        type.java.enclosingClass?.kotlin?.qualifiedName,
        listOfNotNull(type.primaryConstructor?.takeIf { it.visibility == KVisibility.PUBLIC }?.let { constructor -> ApiConstructor(constructor.parameters.map(::parameter)) }),
        properties(type, if (type.objectInstance != null) access(type) else null),
        if (type.objectInstance != null) functions(type, access(type)) else emptyList(),
        type.companionObject?.takeIf { it.visibility == KVisibility.PUBLIC }?.let { companion ->
            val access = access(companion)
            ApiCompanion(requireNotNull(companion.qualifiedName), access, properties(companion, access), functions(companion, access))
        },
        type.sealedSubclasses.mapNotNull { it.qualifiedName }.sorted(),
        type.java.enumConstants?.map { (it as Enum<*>).name } ?: emptyList(),
        when (type) {
            Calculation.Call::class -> Calculation.Call.contract()
            MemberResult::class -> MemberResult.contract()
            LogicUnitRecord::class -> LogicUnitRecord.contract()
            LogicalTableCreateRequest.LogicUnitInput::class -> LogicalTableCreateRequest.LogicUnitInput.contract()
            SetOutputContract.Container::class, SetOutputContract.Array::class, SetOutputContract.Row::class -> SetOutputContract.Container.contract()
            else -> if (type == ScriptArguments::class || ScriptValue::class.java.isAssignableFrom(type.java)) ScriptValue.contract() else emptyMap()
        })

    private fun parameter(value: KParameter) = ApiParameter(requireNotNull(value.name), value.type.toString(), value.isOptional, value.isVararg)

    private fun properties(type: KClass<*>, access: String?): List<ApiProperty> = type.memberProperties.filter { it.visibility == KVisibility.PUBLIC }
        .map { ApiProperty(it.name, it.returnType.toString(), (it as? KMutableProperty<*>)?.setter?.visibility == KVisibility.PUBLIC, access?.let { owner -> "$owner.${it.name}" }) }
        .sortedBy { it.name }

    private fun publicFunctions(type: KClass<*>): List<KFunction<*>> = type.declaredMemberFunctions.filter { it.visibility == KVisibility.PUBLIC }

    private fun functions(type: KClass<*>, access: String): List<ApiFunction> = publicFunctions(type).map { function ->
        ApiFunction(function.name, "$access.${function.name}", function.parameters.filter { it.kind == KParameter.Kind.VALUE }.map(::parameter),
            function.returnType.toString(), function.typeParameters.associate { it.name to it.upperBounds.map(KType::toString) })
    }.sortedWith(compareBy({ it.name }, { it.parameters.joinToString { parameter -> parameter.type } }))

    private fun access(type: KClass<*>): String = requireNotNull(if (type.isCompanion) type.java.enclosingClass?.kotlin?.qualifiedName else type.qualifiedName)

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
