package com.olap.prototype.dsl

import com.olap.prototype.dsl.transport.CapabilityRegistry
import com.olap.prototype.dsl.transport.CapabilityContract
import com.olap.prototype.query.GroupingLevels
import com.olap.prototype.query.PostOperationContract
import com.olap.prototype.calculation.Calculation
import com.olap.prototype.member.MemberResult
import com.olap.prototype.governance.contract.LogicUnitRecord
import com.olap.prototype.governance.contract.LogicalTableCreateRequest
import com.olap.prototype.query.SetOutputContract
import com.olap.prototype.query.ResultExpression
import com.olap.prototype.query.QueryValueSemantics
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
    const val VERSION = "agentic-olap-kotlin-dsl/v8"
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
    fun type(name: String): ApiType = describe(types[name] ?: throw com.olap.prototype.contract.InputContractException.violation(
        com.olap.prototype.contract.InputContractException.Rule.DECODE_INPUT, listOf("type"),
        "unknown public DSL type: $name; describe an operation and use its exact input or output type",
        "EXACT_PUBLIC_TYPE_FROM_OPERATION_DISCOVERY", name))

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
        listOf(Calculation::class, Calculation.Scope::class, Computations::class, CalculationScopeBuilder::class, InputMode::class,
            ScriptValue::class, ScriptArguments::class, Caller::class,
            OlapEnvironment::class).forEach(::addClass)
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
        when {
            type.objectInstance != null -> functions(type, access(type))
            type == CalculationScopeBuilder::class -> functions(type, "this")
            else -> emptyList()
        },
        type.companionObject?.takeIf { it.visibility == KVisibility.PUBLIC }?.let { companion ->
            val access = access(companion)
            ApiCompanion(requireNotNull(companion.qualifiedName), access, properties(companion, access), functions(companion, access))
        },
        type.sealedSubclasses.mapNotNull { it.qualifiedName }.sorted(),
        type.java.enumConstants?.map { (it as Enum<*>).name } ?: emptyList(),
        when (type) {
            CalculationScopeBuilder::class -> mapOf("receiverContext" to "Computations.scope(root) { ... }",
                "construction" to "CREATED_BY_SCOPE", "result" to "Calculation.Scope")
            Calculation.Call::class -> Calculation.Call.contract()
            Calculation.Compute::class -> Calculation.Compute.contract()
            com.olap.prototype.warehouse.contract.SourceAdoptionRegistrationRequest::class ->
                com.olap.prototype.warehouse.contract.SourceAdoptionRegistrationRequest.contract()
            com.olap.prototype.warehouse.contract.SourceAdoptions::class ->
                com.olap.prototype.warehouse.contract.SourceAdoptions.contract()
            com.olap.prototype.governance.contract.SubjectAreaReleaseBuildRequest::class ->
                com.olap.prototype.governance.contract.SubjectAreaReleaseBuildRequest.contract()
            com.olap.prototype.governance.contract.ScenarioPipelineRuleCreateRequest::class ->
                com.olap.prototype.governance.contract.ScenarioPipelineRuleCreateRequest.contract()
            com.olap.prototype.governance.contract.ScenarioPipelineRuleContractRefreshRequest::class ->
                com.olap.prototype.governance.contract.ScenarioPipelineRuleContractRefreshRequest.contract()
            com.olap.prototype.query.contract.StageAuthorization::class -> com.olap.prototype.query.contract.StageAuthorization.contract()
            com.olap.prototype.query.contract.ExpressionAuthorization::class -> com.olap.prototype.query.contract.ExpressionAuthorization.contract()
            PostOperationContract.TopN::class -> PostOperationContract.TopN.contract()
            PostOperationContract.ResultGrouping::class -> PostOperationContract.ResultGrouping.contract()
            Calculation.SetCombination::class -> Calculation.SetCombination.contract()
            Calculation.SetProjection::class -> Calculation.SetProjection.contract()
            GroupingLevels::class -> GroupingLevels.contract()
            QueryRunRequest::class -> QueryRunRequest.contract()
            QueryRunListRequest::class -> QueryRunListRequest.contract()
            com.olap.prototype.query.contract.QueryExplanationRequest::class -> com.olap.prototype.query.contract.QueryExplanationRequest.contract()
            com.olap.prototype.query.contract.QueryExplanationSection::class -> com.olap.prototype.query.contract.QueryExplanationSection.contract()
            ResultMetadataRequest::class -> ResultMetadataRequest.contract()
            com.olap.prototype.governance.contract.GovernanceReadRequests.PageInput::class,
            com.olap.prototype.governance.contract.GovernanceReadRequests.SourceDiscoveriesInput::class,
            com.olap.prototype.governance.contract.GovernanceReadRequests.SnapshotPageInput::class,
            com.olap.prototype.governance.contract.GovernanceReadRequests.DiscoveredTablePageInput::class,
            com.olap.prototype.governance.contract.GovernanceReadRequests.SourcePageInput::class,
            com.olap.prototype.governance.contract.GovernanceReadRequests.VersionPageInput::class,
            com.olap.prototype.governance.contract.GovernanceReadRequests.SubjectPageInput::class ->
                com.olap.prototype.governance.contract.GovernanceReadRequests.paginationContract()
            com.olap.prototype.governance.contract.GovernanceReadRequests.DefinitionDependenciesRequest::class ->
                com.olap.prototype.governance.contract.GovernanceReadRequests.DefinitionDependenciesRequest.contract()
            com.olap.prototype.query.contract.catalog.SemanticCatalogReads.SearchRequest::class ->
                com.olap.prototype.query.contract.catalog.SemanticCatalogReads.SearchRequest.contract()
            com.olap.prototype.query.contract.CandidateRequest::class -> com.olap.prototype.query.contract.CandidateRequest.contract()
            MemberResult::class -> MemberResult.contract()
            LogicUnitRecord::class -> LogicUnitRecord.contract()
            LogicalTableCreateRequest.LogicUnitInput::class -> LogicalTableCreateRequest.LogicUnitInput.contract()
            SetOutputContract.Container::class, SetOutputContract.Array::class, SetOutputContract.Row::class -> SetOutputContract.Container.contract()
            ResultExpression.ResultNumeric::class -> ResultExpression.ResultNumeric.contract()
            ResultExpression.ResultCaseWhen::class -> QueryValueSemantics.alternativeContract()
            ResultExpression.ResultCall::class -> mapOf("coalesceAlternatives" to QueryValueSemantics.alternativeContract())
            ResultExpression.ResultRound::class -> ResultExpression.ResultRound.contract()
            ResultExpression.ResultOperation::class -> ResultExpression.ResultOperation.contract()
            com.olap.prototype.metric.MetricValueContract.FixedBinary::class -> com.olap.prototype.metric.MetricValueContract.FixedBinary.contract()
            com.olap.prototype.metric.MetricValueContract.UnitEquation::class -> mapOf(
                "fixedBinary" to com.olap.prototype.metric.MetricValueContract.FixedBinary.contract())
            com.olap.prototype.query.QueryExecutionMode::class -> com.olap.prototype.query.QueryExecutionMode.contract()
            com.olap.prototype.query.definition.QueryDefinitionCommand::class -> com.olap.prototype.query.definition.QueryDefinitionCommand.contract()
            ResultExpression.ResultOperator::class -> ResultExpression.ResultOperator.contract()
            com.olap.prototype.query.ResultAggregation.Sum::class -> com.olap.prototype.query.ResultAggregation.Sum.contract()
            com.olap.prototype.query.ResultAggregation.Count::class -> com.olap.prototype.query.ResultAggregation.Count.contract()
            com.olap.prototype.metric.MeanTarget::class -> com.olap.prototype.metric.MeanTarget.contract()
            com.olap.prototype.query.ResultAggregation.Average::class -> com.olap.prototype.metric.MeanTarget.contract() + mapOf(
                "meanTarget" to "EXPLICIT_REQUIRED_TARGET_FOR_THIS_COMPLETED_OR_WINDOW_AVERAGE",
                "quantity" to "EXPLICIT_RESULT_UNIT_ARITHMETIC_CAPACITY_AND_NODE_FINAL_QUANTIZATION",
                "nullPolicy" to "ACTUAL_PARTICIPATING_VALUES_IGNORE_OR_PROPAGATE",
            )
            com.olap.prototype.calculation.MetricDefinition.FactMeasure::class -> com.olap.prototype.metric.MeanTarget.contract() + mapOf(
                "meanTarget" to "REQUIRED_FOR_AVG_ABSENT_FOR_OTHER_FACT_AGGREGATIONS",
                "snapshot" to "SNAPSHOT_MEAN_TARGET_AND_INTERMEDIATE_QUANTITY_ARE_INDEPENDENT_OF_OUTER_FACT_AGGREGATION",
            )
            com.olap.prototype.metric.MetricSnapshotRule::class -> com.olap.prototype.metric.MeanTarget.contract() + mapOf(
                "meanTarget" to "REQUIRED_WITH_MEAN_VALUE_CONTRACT_FOR_MEAN_ABSENT_FOR_FIRST_AND_LAST",
                "nullPolicy" to "MEAN_REQUIRES_IGNORE_OR_PROPAGATE_FIRST_AND_LAST_PRESERVE_SELECTED",
            )
            com.olap.prototype.expression.NativeAverageBehavior::class -> mapOf(
                "owner" to "EXACT_CONNECTOR_ACTUAL_AGGREGATE_OR_WINDOW_IMPLEMENTATION",
                "quantizedMean" to "EXACT_ACCUMULATION_AND_COUNT_THEN_ONE_QUANTIZATION_TO_ACTUAL_NATIVE_DECIMAL_SCALE",
                "nativeFloating" to "NATIVE_FLOATING_MEAN_WITHOUT_UNKNOWN_DECIMAL_OR_BUSINESS_ERROR_GUARANTEES",
                "meanTarget" to "IMPLEMENTATION_FACT_NOT_AUTHOR_TARGET_COMPILER_CHECKS_TARGET_AGAINST_ACTUAL_INPUT_AND_RESULT",
            )
            com.olap.prototype.source.contract.ConnectorCapabilitiesRequest.Aggregate::class,
            com.olap.prototype.source.contract.ConnectorCapabilities.Aggregate::class -> mapOf(
                "groupedFalse" to "GLOBAL_AGGREGATE_WITHOUT_GROUPING_KEYS",
                "groupedTrue" to "ORDINARY_AGGREGATE_WITH_ONE_GROUPING_KEY",
                "averageBehavior" to "DECLARATION_FOR_THIS_DISCOVERY_SHAPE_ONLY_NOT_ALL_WINDOW_OR_GROUPING_SHAPES",
                "execution" to "NO_SOURCE_QUERY_OR_BACKEND_VALIDATION_REAL_QUERY_CHECKS_ACTUAL_AGGREGATE_OR_REXOVER",
            )
            Calculation.ObservationStatistics::class -> Calculation.ObservationStatistics.contract()
            Calculation.ObjectsBy::class -> Calculation.ObjectsBy.contract()
            ResultExpression.ResultNativeCall::class -> mapOf(
                "connector" to "CURRENT_EXACT_SOURCE_CONNECTOR_REF",
                "function" to "DECLARED_NATIVE_SCALAR_FUNCTION_ID",
                "arguments" to "DECLARED_ORDER_COUNT_AND_SCALAR_TYPES_ONLY",
                "physicalTable" to "SOURCE_NATIVE_CALL_AND_ADOPTED_PHYSICAL_CLOSURE_ONLY",
                "authorization" to "PUBLISHED_NATIVE_CALL_EXPRESSION_KIND_AND_RECURSIVE_ARGUMENT_AUTHORIZATION",
                "case" to "ALL_BRANCHES_STATICALLY_VALIDATED_CALL_REMAINS_IN_ITS_AUTHORED_BRANCH",
                "nativeSemantics" to "PLUGIN_AND_BACKEND_OWN_FORMAT_NULL_ERROR_AND_STABILITY_GUARANTEES",
                "numericResult" to "DIMENSIONLESS_NATIVE_CAPACITY_AND_DECLARED_ARITHMETIC_NO_UPGRADE",
                "numericProvenance" to "PRESERVE_ARGUMENT_OBSERVATION_AND_MULTIPLICITY_AS_SCALAR_FUNCTION",
                "measureArguments" to "NUMBER_TO_NUMBER_ONLY_DIMENSIONLESS_WITH_COMPATIBLE_NATIVE_ARITHMETIC_NO_BUSINESS_UNIT_ERASURE",
                "nonNumericResultArguments" to "CATEGORICAL_SCALARS_ONLY_NO_MEASUREMENT_PROVENANCE_ERASURE",
            )
            else -> when {
                type == ScriptArguments::class || ScriptValue::class.java.isAssignableFrom(type.java) -> ScriptValue.contract()
                type == OlapEnvironment::class || type == OlapClient::class -> mapOf(
                    "environmentAccess" to "IMPORTED_TOP_LEVEL_PROPERTIES_BOUND_TO_CURRENT_EVALUATION_THREAD",
                    "declarationVisibility" to "DECLARE_DEPENDENCIES_BEFORE_FUNCTIONS_AND_CLASSES_THAT_USE_THEM",
                    "lifetime" to "SESSION",
                    "parameters" to "CURRENT_EXECUTION_IMMUTABLE_SNAPSHOT_GETTER_SAVED_VALUES_REMAIN_SNAPSHOTS",
                    "capabilityCalls" to "CURRENT_ACTIVE_EVALUATION_THREAD_WITH_TRUSTED_EXECUTION_CONTEXT",
                    "temporaryDirectory" to "SESSION_PRIVATE_ROOT",
                    "output" to "EXPLICIT_ORDERED_SNAPSHOT_ACTION_RETURNS_UNIT",
                    "lastExpression" to "ANY_KOTLIN_VALUE_NO_IMPLICIT_OUTPUT",
                    "businessEffects" to "FORMAL_CALLS_COMMIT_INDEPENDENTLY_AND_KEEP_THEIR_ACTUAL_EFFECT_REFERENCES",
                )
                else -> emptyMap()
            }
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
            olap.output(if (selected == null) ScriptValue.Null else ScriptValue.Text(selected.id))
        """.trimIndent()),
        ApiExample("执行并返回回执", "完整 Query 由本次代码构造，执行经正式发布授权和结果交付。", """
            val publication = QueryCapabilityRequest(parameters.text("scenarioId"), parameters.decimal("scenarioVersion").longValueExact(), parameters.text("releaseId"), parameters.text("releaseVersion"))
            val source = MemberRef(parameters.text("memberSourceId"), parameters.decimal("memberSourceVersion").longValueExact())
            val computation = Computations.members("population", source, emptyMap(),
                listOf(Calculation.MemberProjection("identity", parameters.text("identitySlot"), null)))
            val receipt = olap.queries.execute(QueryExecutionRequest(QuerySelection.Inline(QueryIntent(publication, computation)), true, "本次分析", null))
            olap.output(receipt)
        """.trimIndent()),
        ApiExample("后续片段继续使用", "在同一 sessionId 提交；函数再次调用使用当前执行的参数和正式调用归属。", """
            fun readSources() = olap.sources.list(EmptyRequest)
            val originalParameters = parameters
            val observedSources = readSources()
            // 后续片段可再次 readSources()；原 observedSources 和 originalParameters 保留当时快照。
            olap.output(observedSources)
        """.trimIndent())
    )
}
