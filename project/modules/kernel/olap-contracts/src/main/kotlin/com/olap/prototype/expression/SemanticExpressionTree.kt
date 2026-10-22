package com.olap.prototype.expression

import com.olap.prototype.expression.SemanticExpression.*

/** 只拥有子节点结构，不解释引用、量词作用域、类型或 SQL。 */
object SemanticExpressionTree {
    fun children(expression: SemanticExpression): List<SemanticExpression> = buildList {
        mapChildren(expression) { add(it); it }
    }

    /** 只变换直接子节点，未变化节点保留引用身份。原生表不是表达式子节点。 */
    fun mapChildren(expression: SemanticExpression, mapper: (SemanticExpression) -> SemanticExpression): SemanticExpression = when (expression) {
        is Reference, is Literal -> expression
        is Call -> map(expression.arguments, mapper).let { if (it === expression.arguments) expression else Call(expression.function, it) }
        is NativeCall -> map(expression.arguments) { argument ->
            when (argument) {
                is TableArgument -> argument
                is ScalarArgument -> mapper(argument.expression).let { if (it === argument.expression) argument else argument.copy(expression = it) }
            }
        }.let { if (it === expression.arguments) expression else NativeCall(expression.connectorRef, expression.function, it) }
        is Operation -> map(expression.operands, mapper).let { if (it === expression.operands) expression else Operation(expression.operator, it) }
        is CaseWhen -> {
            val branches = map(expression.branches) { branch ->
                val condition = mapper(branch.`when`)
                val value = mapper(branch.then)
                if (condition === branch.`when` && value === branch.then) branch else branch.copy(`when` = condition, then = value)
            }
            val otherwise = expression.otherwise?.let(mapper)
            if (branches === expression.branches && otherwise === expression.otherwise) expression else CaseWhen(branches, otherwise)
        }
        is Exists -> mapper(expression.predicate).let { if (it === expression.predicate) expression else expression.copy(predicate = it) }
        is Cast -> mapper(expression.expression).let { if (it === expression.expression) expression else expression.copy(expression = it) }
        is Round -> mapper(expression.expression).let { if (it === expression.expression) expression else expression.copy(expression = it) }
    }

    fun rewrite(expression: SemanticExpression, mapper: (SemanticExpression) -> SemanticExpression): SemanticExpression =
        mapper(mapChildren(expression) { rewrite(it, mapper) })

    private fun <T> map(values: List<T>, mapper: (T) -> T): List<T> {
        var result: MutableList<T>? = null
        values.forEachIndexed { index, previous ->
            val value = mapper(previous)
            if (result == null && value !== previous) result = values.toMutableList()
            result?.set(index, value)
        }
        return result?.let { java.util.List.copyOf(it) } ?: values
    }
}
