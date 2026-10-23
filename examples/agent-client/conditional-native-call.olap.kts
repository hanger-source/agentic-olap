import com.olap.prototype.dsl.*
import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.expression.NativeScalarFunction
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.member.MemberRef
import com.olap.prototype.query.*
import com.olap.prototype.query.PostOperationContract.Derive
import com.olap.prototype.query.PostOperationContract.JoinInput
import com.olap.prototype.query.PostOperationContract.Project
import com.olap.prototype.query.ResultExpression.*
import com.olap.prototype.query.contract.*
import com.olap.prototype.source.contract.ConnectorCapabilitiesRequest

// 来源、成员、场景与发布均须是当前主体可用的精确对象；本程序不创建定义。
val connector = ConnectorRef(parameters.text("connectorId"), parameters.text("connectorVersion"), parameters.text("connectorSha256"))
val native = olap.sources.connectorCapabilities(ConnectorCapabilitiesRequest(null)).connectors
    .single { it.connectorRef == connector }.nativeScalarFunctions.single { it.id == parameters.text("parseFunction") }
require(native.parameters.map { it.kind to it.scalarType } == List(2) { NativeScalarFunction.ParameterKind.SCALAR to ScalarValueType.TEXT })
require(native.resultType.name == "DATE") { "本程序要求已声明的 TEXT、格式TEXT → DATE 函数" }

fun resultSymbol(name: String): ResultExpression = ResultSymbol(name)
fun resultText(value: String): ResultExpression = ResultTypedLiteral(ScalarValueType.TEXT, value)
fun resultOp(operator: ResultOperator, vararg arguments: ResultExpression): ResultExpression = ResultOperation(operator, arguments.toList())

val facts = QueryComputation.Members("facts", MemberRef(parameters.text("factMemberId"), parameters.decimal("factMemberVersion").longValueExact()),
    emptyMap(), listOf(QueryComputation.Slot("record_day", parameters.text("factDaySlot")),
        QueryComputation.Slot("record_id", parameters.text("factIdSlot")),
        QueryComputation.Slot("eligibility_text", parameters.text("eligibilitySlot"))),
    null, emptyMap(), MemberCondition.Unrestricted(), emptyMap(), emptyMap())
val details = QueryComputation.Scope(QueryComputation.Members("details",
    MemberRef(parameters.text("detailMemberId"), parameters.decimal("detailMemberVersion").longValueExact()),
    emptyMap(), listOf(QueryComputation.Slot("record_day", parameters.text("detailDaySlot")),
        QueryComputation.Slot("record_id", parameters.text("detailIdSlot")),
        QueryComputation.Slot("raw_text", parameters.text("rawTextSlot"))),
    null, emptyMap(), MemberCondition.Unrestricted(), emptyMap(), emptyMap()), emptyMap(), emptyList())

// 仅按记录身份 LEFT；右侧跨日期多版本均保留，不推断单键唯一，也不提前解析明细。
val stages: List<PostOperationContract.ValidatedOperation> = listOf(
    JoinInput("details", "detail", "LEFT",
        resultOp(ResultOperator.EQ, resultSymbol("record_id"), resultSymbol("detail__record_id"))),
    Derive(ResultCaseWhen(listOf(ResultCaseBranch(
        resultOp(ResultOperator.NE, resultSymbol("eligibility_text"), resultText("")),
        ResultNativeCall(connector, native.id, listOf(resultSymbol("detail__raw_text"), resultText(parameters.text("format"))))
    )), ResultLiteral(null)), "parsed_date", "合格贡献的格式日期"),
    Project(listOf("record_day", "record_id", "eligibility_text", "detail__raw_text", "parsed_date")))
val publication = QueryCapabilityRequest(parameters.text("scenarioId"), parameters.decimal("scenarioVersion").longValueExact(),
    parameters.text("releaseId"), parameters.text("releaseVersion"))
val selection = QuerySelection.Inline(QueryIntent(publication, QueryComputation.Scope(facts,
    mapOf("details" to QueryComputation.Input("RELATION", details)), stages)))
when (parameters.text("operation")) {
    "validate" -> olap.output(olap.queries.validate(QueryValidationRequest(selection)))
    "execute" -> olap.output(olap.queries.execute(QueryExecutionRequest(selection, true, "关联后按资格条件解析日期", null)))
    else -> error("operation must be validate or execute")
}
