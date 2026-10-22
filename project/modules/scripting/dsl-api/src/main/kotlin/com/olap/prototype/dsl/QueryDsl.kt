package com.olap.prototype.dsl

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.query.*
import com.olap.prototype.query.contract.QueryCapabilityRequest
import com.olap.prototype.query.contract.QueryIntent
import com.olap.prototype.expression.ScalarFunction
import java.math.BigDecimal

@DslMarker
annotation class OlapDsl

/** 局部 builder 只在 build 时交出完整正式模型，不保存 lambda。 */
@OlapDsl
class QueryScopeBuilder internal constructor(private val root: QueryComputation.Root) {
    private val inputs = linkedMapOf<String, QueryComputation.Input>()
    private val stages = mutableListOf<PostOperationContract.ValidatedOperation>()
    fun input(id: String, computation: QueryComputation.Scope, mode: InputMode) {
        require(id !in inputs) { "query input identity is repeated: $id" }
        inputs[id] = QueryComputation.Input(mode.name, computation)
    }
    fun stage(operation: PostOperationContract.ValidatedOperation) { stages.add(operation) }
    internal fun build(): QueryComputation.Scope = QueryComputation.Scope(root, inputs, stages)
}
enum class InputMode { ROOT, KEY_ALIGNMENT, RELATION }
fun scope(root: QueryComputation.Root, build: QueryScopeBuilder.() -> Unit = {}): QueryComputation.Scope =
    QueryScopeBuilder(root).apply(build).build()
fun query(publication: QueryCapabilityRequest, root: QueryComputation.Root, build: QueryScopeBuilder.() -> Unit = {}): QueryIntent =
    QueryIntent(publication, scope(root, build))

fun symbol(name: String): ResultExpression = ResultExpression.ResultSymbol(name)
fun number(value: String): ResultExpression = ResultExpression.ResultLiteral(BigDecimal(value))
fun boolean(value: Boolean): ResultExpression = ResultExpression.ResultLiteral(value)
fun nullValue(): ResultExpression = ResultExpression.ResultLiteral(null)
fun text(value: String): ResultExpression = ResultExpression.ResultTypedLiteral(ScalarValueType.TEXT, value)
fun argument(name: String, type: ScalarValueType): ResultExpression = ResultExpression.ResultArgument(name, type)
fun call(function: ScalarFunction, vararg arguments: ResultExpression): ResultExpression = ResultExpression.ResultCall(function, arguments.toList())
fun row(vararg fields: ResultExpression): ResultExpression = ResultExpression.ResultRow(fields.toList())
fun ResultExpression.field(index: Int): ResultExpression = ResultExpression.ResultRowField(this, index)
