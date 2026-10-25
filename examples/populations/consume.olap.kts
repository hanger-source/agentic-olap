import com.olap.prototype.dsl.*
import com.olap.prototype.dsl.transport.CapabilityFailureException
import com.olap.prototype.calculation.*
import com.olap.prototype.member.*
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.*
import com.olap.prototype.query.PostOperationContract.Project
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog
import com.olap.prototype.query.contract.catalog.SemanticCatalogReads
import com.olap.prototype.query.definition.QueryDefinitionCommand
import com.olap.prototype.engine.executable.ExecutableBusinessResult
import java.math.BigDecimal

// 独立作者：只发现和消费正式发布，不依赖main.olap.kts的函数、变量或资产ID。
val question = parameters.text("question")
require(question in setOf("Q1", "Q2", "Q3"))
val countCode = parameters.text("selectedCount")
require(countCode == if (question == "Q3") "known_amount_count" else "source_record_count")
val releaseId = parameters.text("releaseId"); val releaseVersion = parameters.text("releaseVersion")
require(releaseVersion in setOf("reuse-original", "reuse-revised"))
val scenarioName = parameters.text("scenarioDisplayName")
val from = parameters.text("currentStart"); val to = parameters.text("currentEnd")
val previousFrom = parameters.text("previousStart"); val previousTo = parameters.text("previousEnd")
require(listOf(from, to, previousFrom, previousTo).all { it.matches(Regex("[0-9]{8}")) } && from <= to && previousFrom <= previousTo)
// 本用法的独立预期只声明这组原始输入和明确期间，不声称任意期间均已验收。
require(previousFrom == "19991231" && previousTo == "19991231" && from == "20000101" && to == if (question == "Q2") "20000101" else "20000102")

// 精确坐标来自正式list-publications入口；普通消费不要求治理目录的全资产读取权限。
val coordinate = QueryCapabilityRequest(parameters.text("scenarioId"), parameters.text("scenarioVersion").toLong(), releaseId, releaseVersion)
val publication = olap.semantics.publication(coordinate)
check(publication.capability.scenario.scenarioDisplayName == scenarioName)

fun search(kind: SemanticCatalogReads.Kind, text: String): SemanticCatalogReads.SearchItem {
    val items = mutableListOf<SemanticCatalogReads.SearchItem>()
    var offset = 0
    while (true) {
        val page = olap.semantics.search(SemanticCatalogReads.SearchRequest(coordinate, text, kind, offset, 2))
        items += page.items
        val next = page.nextOffset ?: break
        check(next == offset + page.items.size && next > offset); offset = next
    }
    check(items.size == 1) { "业务候选需明确且唯一，搜索不负责按签名自动选择：$text，$items" }
    return items.single()
}
val paidSearch = search(SemanticCatalogReads.Kind.MEMBER, "复用付费标记对象")
val visitedSearch = search(SemanticCatalogReads.Kind.MEMBER, "复用当前目录访问对象")
val activitySearch = search(SemanticCatalogReads.Kind.MEMBER, "复用期间活动完成量")
val catalogSearch = search(SemanticCatalogReads.Kind.MEMBER, "复用当前目录完成量")
val countSearches = listOf("source_record_count", "known_amount_count").associateWith { search(SemanticCatalogReads.Kind.METRIC, it) }
val references = listOf(paidSearch, visitedSearch, activitySearch, catalogSearch) + countSearches.values
check(references.map { it.reference.catalog }.distinct().size == 1)
val catalogReference = paidSearch.reference.catalog
check(publication.semanticCatalogHash == catalogReference.sha256 && catalogReference.coordinate.request() == coordinate)
fun member(item: SemanticCatalogReads.SearchItem): PublishedSemanticCatalog.Member {
    val asset = olap.semantics.publishedAsset(SemanticCatalogReads.AssetRequest(item.reference))
    check(asset.reference == item.reference)
    return (asset.asset as SemanticCatalogReads.Asset.MemberAsset).value
}
fun definition(member: PublishedSemanticCatalog.Member): PublishedSemanticCatalog.ComputedMeaning {
    val result = olap.semantics.publishedDefinition(SemanticCatalogReads.DefinitionRequest(catalogReference,
        SemanticCatalogReads.DefinitionReference.Member(MemberRef(member.sourceId, member.sourceVersion))))
    check(result.catalog == catalogReference)
    return ((result.definition as PublishedSemanticCatalog.MemberDependency).definition as PublishedSemanticCatalog.ComputedMeaning)
}
val paid = member(paidSearch); val visited = member(visitedSearch); val activity = member(activitySearch); val catalog = member(catalogSearch)
val definitions = listOf(paid, visited, activity, catalog).associateWith(::definition)
check(paid.shape == MemberShape.UNIQUE_MEMBERS && visited.shape == MemberShape.UNIQUE_MEMBERS && activity.shape == MemberShape.RECORDS && catalog.shape == MemberShape.RECORDS)
listOf(paid, visited, activity, catalog).forEach { check(it.identitySlots == listOf("subject") && it.memberParameters.isEmpty() && it.relationParameters.isEmpty()) }
val axis = definitions.getValue(paid).bindings.dimensions.getValue("subject")
val subjectSlot = definitions.getValue(paid).calculation.dimensionSlots.single { it.name == "subject" }
definitions.values.forEach { check(it.bindings.dimensions.getValue("subject") == axis) }
listOf(paid, visited, activity).forEach { selected ->
    check(selected.parameters.keys == setOf("from", "to") && selected.parameters.values.all { it.kind == Calculation.ParameterKind.SCALAR && it.valueType == "TEXT" && it.defaultValue == null })
}
check(catalog.parameters.isEmpty())
fun quantities(member: PublishedSemanticCatalog.Member) = member.outputs.filterKeys { it != "subject" && it in member.permission.outputs }.mapValues { (name, output) ->
    requireNotNull(MemberCandidate.rootValueContract(output)) { "完成出口必须保正式数量含义：$name" }
}
val activityQuantities = quantities(activity); val catalogQuantities = quantities(catalog)
check(activityQuantities.keys == setOf("amount", "records", "known") && catalogQuantities.keys == setOf("items", "qualified_items"))
val metrics = countSearches.mapValues { (code, item) ->
    val asset = olap.semantics.publishedAsset(SemanticCatalogReads.AssetRequest(item.reference))
    check(asset.reference == item.reference)
    val metric = (asset.asset as SemanticCatalogReads.Asset.MetricAsset).value
    check(metric.code == code && metric.definitionKind == PublishedSemanticCatalog.MetricKind.CALCULATION)
    val instance = olap.semantics.publishedDefinition(SemanticCatalogReads.DefinitionRequest(catalogReference, SemanticCatalogReads.DefinitionReference.Instance(metric.instance)))
        .definition as PublishedSemanticCatalog.InstanceDependency
    check(instance.instance.definition == metric.definition && instance.instance.bindings.dimensions.getValue("subject") == axis)
    val saved = olap.semantics.publishedDefinition(SemanticCatalogReads.DefinitionRequest(catalogReference, SemanticCatalogReads.DefinitionReference.Calculation(metric.definition)))
        .definition as PublishedSemanticCatalog.CalculationDependency
    check(saved.definition.keyOutputs == listOf("subject") && saved.definition.valueContract == metric.valueContract)
    check(metric.parameters == activity.parameters && metric.memberParameters.isEmpty() && metric.relationParameters.isEmpty() && metric.groupingCandidates.isEmpty())
    metric
}
val countContract = metrics.getValue("source_record_count").valueContract
check(metrics.getValue("known_amount_count").valueContract == countContract && countContract == activityQuantities.getValue("records") && countContract == activityQuantities.getValue("known"))
check(countContract.unit == MetricValueContract.Fixed(MetricValueContract.ONE) && countContract.arithmetic is MetricValueContract.Exact && countContract.result == MetricValueContract.Integer(64) && countContract.overflow == MetricValueContract.Overflow.FAIL)
val metric = metrics.getValue(countCode)
check(publication.semanticCatalogHash == catalogReference.sha256 && publication.semanticCatalog.catalog.coordinate.request() == coordinate)

fun arguments(start: String, end: String): Map<String, Calculation.Value> = mapOf("from" to Calculation.Scalar(ScalarValueType.TEXT, start), "to" to Calculation.Scalar(ScalarValueType.TEXT, end))
fun members(member: PublishedSemanticCatalog.Member, call: String, arguments: Map<String, Calculation.Value>): QueryComputation.Scope {
    val dimension = member.outputs.getValue("subject") as MemberCandidate.Dimension
    check("subject" in member.permission.outputs && axis.reference in dimension.mappings && dimension.dimensionId == subjectSlot.dimensionId && dimension.dimensionVersion == subjectSlot.dimensionVersion)
    return QueryComputation.Scope(QueryComputation.Members(call, MemberRef(member.sourceId, member.sourceVersion), arguments,
        listOf(QueryComputation.MappedDimension("subject", "subject", axis.reference)) + member.permission.outputs.filter { it != "subject" }.sorted().map { QueryComputation.Slot(it, it) },
        null, emptyMap(), MemberCondition.Unrestricted(), emptyMap(), emptyMap()), emptyMap(), emptyList())
}
val inputScopes = linkedMapOf("paid_before" to members(paid, "paid_before", arguments(previousFrom, previousTo)),
    "visited_current" to members(visited, "visited_current", arguments(from, to)), "paid_current" to members(paid, "paid_current", arguments(from, to)),
    "activity_completed" to members(activity, "activity_completed", arguments(from, to)), "catalog_completed" to members(catalog, "catalog_completed", emptyMap()))
val inputQuantities = linkedMapOf("paid_before" to emptyMap<String, MetricValueContract>(), "visited_current" to emptyMap(), "paid_current" to emptyMap(),
    "activity_completed" to activityQuantities, "catalog_completed" to catalogQuantities)
val key = listOf(SetOutputContract("subject", SetOutputContract.DimensionSlot("subject")))
fun relation(name: String) = Calculation.Scope(Calculation.Relation(name), emptyMap(), emptyList())
fun set(kind: Calculation.SetKind, left: Calculation.Scope, right: Calculation.Scope) = Calculation.Scope(Calculation.SetCombination(kind, false,
    listOf(Calculation.SetProjection("left", listOf("subject")), Calculation.SetProjection("right", listOf("subject"))), key),
    mapOf("left" to Calculation.InputScope("ROOT", left), "right" to Calculation.InputScope("ROOT", right)), emptyList())
val population = set(if (question == "Q2") Calculation.SetKind.INTERSECT else Calculation.SetKind.EXCEPT,
    set(Calculation.SetKind.INTERSECT, relation("paid_before"), relation("visited_current")), relation("paid_current"))
fun intent(selectedContract: MetricValueContract): QueryIntent {
    val slot = Calculation.MeasurementSlot("selected_count", metric.parameters, listOf(subjectSlot), emptyList(), selectedContract)
    val measured = Calculation.Scope(Calculation.Measures(listOf(Calculation.Call("chosen_count", Calculation.Measurement("selected_count"), "selected_count",
        emptyMap(), emptyMap(), emptyMap(), emptyMap(), arguments(from, to), Calculation.Condition(DimensionCondition.Unrestricted()))),
        listOf("subject"), emptyList(), null, null, emptyMap(), Calculation.Population(listOf("chosen_count")), null), emptyMap(), emptyList())
    fun aligned(body: Calculation.Scope, values: List<String>) = Calculation.AlignedInput(body, mapOf("subject" to "subject"), values.map { Calculation.AlignedValue(it, it, Calculation.NoMatch.PRESERVE_NULL) })
    val calculation = Calculation(listOf(subjectSlot), emptyList(), listOf(slot), inputQuantities.map { (name, values) ->
        Calculation.RelationSlot(name, key + values.map { (column, contract) -> SetOutputContract(column, SetOutputContract.Quantity(contract)) }, listOf("subject"))
    }, emptyMap(), Calculation.Scope(Calculation.AlignMeasurements(linkedMapOf("population" to aligned(population, emptyList()),
        "catalog" to aligned(relation("catalog_completed"), listOf("items", "qualified_items")),
        "activity" to aligned(relation("activity_completed"), listOf("amount", "records", "known")), "count" to aligned(measured, listOf("selected_count"))),
        listOf("subject"), Calculation.AlignmentPopulation(Calculation.PopulationKind.BASE, listOf("population"))), emptyMap(),
        listOf(Project(listOf("subject", "items", "qualified_items", "amount", "records", "known", "selected_count")))))
    val bindings = CalculationBindings(mapOf("subject" to axis), emptyMap(), emptyMap(), mapOf("selected_count" to metric.instance), inputScopes.keys.associateWith { CalculationBindings.RelationParameter() })
    val local = inputQuantities.mapValues { (name, values) -> CalculationBindings.LocalRelation(name, (listOf("subject") + values.keys).associateWith { it }) }
    return QueryIntent(coordinate, QueryComputation.Scope(QueryComputation.Compute(calculation, bindings, emptyMap(), emptyMap(), local), inputScopes.mapValues { QueryComputation.Input("ROOT", it.value) }, emptyList()))
}
val correct = intent(countContract)
check(olap.queries.validate(QueryValidationRequest(QuerySelection.Inline(correct), QueryExecutionMode.SOURCE)).valid)
val rejected: com.olap.prototype.dsl.transport.CapabilityFailure? = if (question == "Q3") {
    // 一个现实的作者错误：把已读到的金额合同放进计数槽；只走正式validate，不提交来源执行。
    val failure = runCatching { olap.queries.validate(QueryValidationRequest(QuerySelection.Inline(intent(activityQuantities.getValue("amount"))), QueryExecutionMode.SOURCE)) }.exceptionOrNull()
    check(failure is CapabilityFailureException) { "错误数量签名应被正式校验拒绝：$failure" }
    failure.failure.also { check(it.operationId == "validate-query") }
} else null
val saved = olap.queries.save(QueryDefinitionCommand(null, null, requireNotNull(publication.capability.capabilityHash), "$question 正式语义复用", correct, null, executionMode = QueryExecutionMode.SOURCE))
val execution = olap.queries.execute(QueryExecutionRequest(QuerySelection.Saved(saved.reference), true, "$question 使用明确期间、集合与计数实例", null, executionMode = QueryExecutionMode.SOURCE))
check(execution.status == QueryExecutionStatus.SUCCEEDED && execution.saved && execution.sourceState == SourceExecutionState.SUCCEEDED && execution.executionMode == QueryExecutionMode.SOURCE)
check(execution.sourceReadEvidence?.basis is SourceReadBasis.Unknown)
val result = requireNotNull(execution.resultReference)
val rows = mutableListOf<Map<String, String?>>()
var offset = 0
while (true) {
    val page = olap.results.read(ResultReadRequest(result, offset, 1, null))
    check(page.resultReference == result && page.offset == offset && page.totalRows.toLong() == execution.rows)
    page.rows.forEach { row -> rows += page.columns.map { it.symbol.name }.zip(row).associate { (name, cell) ->
        val value = (cell as ResultValueView.Complete).value
        name to when (value) { is ExecutableBusinessResult.NullValue -> null; is ExecutableBusinessResult.ScalarValue -> value.value; else -> error("本例消费标量完成量") }
    } }
    offset += page.rows.size
    val next = page.nextOffset ?: break
    check(next == offset && page.rows.isNotEmpty())
}
check(rows.size.toLong() == execution.rows)
val revised = releaseVersion == "reuse-revised"
val expected = if (question == "Q2") mapOf("D" to mapOf("items" to "1", "qualified_items" to "1", "amount" to "5", "records" to "1", "known" to "1", "selected_count" to "1")) else linkedMapOf(
    "" to mapOf("items" to "1", "qualified_items" to "1", "amount" to null, "records" to "1", "known" to "0", "selected_count" to if (question == "Q3") "0" else "1"),
    "A" to mapOf("items" to "2", "qualified_items" to if (revised) "1" else "2", "amount" to null, "records" to "2", "known" to "0", "selected_count" to if (question == "Q3") "0" else "2"),
    "B" to mapOf("items" to "1", "qualified_items" to if (revised) "0" else "1", "amount" to "7", "records" to "1", "known" to "1", "selected_count" to "1"),
    "C" to mapOf("items" to "1", "qualified_items" to "0", "amount" to null, "records" to null, "known" to null, "selected_count" to null))
val actual = rows.associateBy { requireNotNull(it["subject"]) }
check(actual.keys == expected.keys && actual.size == rows.size)
expected.forEach { (subject, values) -> values.forEach { (name, value) ->
    val observed = actual.getValue(subject).getValue(name)
    check(if (value == null) observed == null else observed != null && BigDecimal(value).compareTo(BigDecimal(observed)) == 0) { "$question/$subject/$name：实际$observed，预期$value" }
} }
val output = linkedMapOf<String, ScriptValue>("query" to ScriptValue.QueryReferenceValue(saved.reference), "result" to ScriptValue.ResultReferenceValue(result),
    "publication" to ScriptValue.ObjectValue(mapOf("releaseId" to ScriptValue.Text(releaseId), "releaseVersion" to ScriptValue.Text(releaseVersion),
        "scenarioId" to ScriptValue.Text(coordinate.scenarioId), "scenarioVersion" to ScriptValue.DecimalValue(BigDecimal.valueOf(coordinate.scenarioVersion)), "semanticCatalogHash" to ScriptValue.Text(catalogReference.sha256))),
    "selectedCount" to ScriptValue.Text(metric.code))
rejected?.let { output["rejectedCountSignature"] = ScriptValue.ObjectValue(mapOf("operation" to ScriptValue.Text(it.operationId), "code" to ScriptValue.Text(it.code), "stage" to ScriptValue.Text(it.stage), "message" to ScriptValue.Text(it.message))) }
olap.output(ScriptValue.ObjectValue(output))
