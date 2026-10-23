package com.olap.prototype.query

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.ResultExpression.*

/** 表达式结构的唯一遍历与由内到外重写；调用方不解释节点布局。 */
object ResultExpressionTree {
    fun groupingSelections(expression: ResultExpression): List<String> = nodes(expression).flatMap { value ->
        when (value) {
            is ResultWeightedContribution -> when (val unit = value.observationUnit) {
                is Calculation.ObjectsBy -> unit.keys
                is Calculation.RawRows -> emptyList()
            }
            is ResultUniformAllocation -> value.sourceKeys + value.memberKeys
            is ResultArgument, is ResultSymbol, is ResultLiteral, is ResultTypedLiteral, is ResultQuantityLiteral,
            is ResultCall, is ResultNativeCall, is ResultOperation, is ResultRow, is ResultCaseWhen, is ResultCast, is ResultRowField,
            is ResultCollectionSort, is ResultCollectionText, is ResultNumeric, is ResultRound -> emptyList()
        }
    }

    fun nodes(expression: ResultExpression): List<ResultExpression> = buildList {
        fun visit(value: ResultExpression) {
            add(value)
            when (value) {
                is ResultCall -> value.arguments.forEach(::visit)
                is ResultNativeCall -> value.arguments.forEach(::visit)
                is ResultOperation -> value.operands.forEach(::visit)
                is ResultRow -> value.operands.forEach(::visit)
                is ResultCaseWhen -> {
                    value.branches.forEach {
                        visit(it.`when`)
                        visit(it.then)
                    }
                    visit(value.otherwise)
                }
                is ResultCast -> visit(value.expression)
                is ResultRowField -> visit(value.expression)
                is ResultCollectionSort -> visit(value.expression)
                is ResultCollectionText -> visit(value.expression)
                is ResultNumeric -> visit(value.expression)
                is ResultRound -> visit(value.expression)
                is ResultWeightedContribution -> {
                    visit(value.value)
                    visit(value.weight)
                }
                is ResultUniformAllocation -> visit(value.value)
                is ResultArgument, is ResultSymbol, is ResultLiteral, is ResultTypedLiteral, is ResultQuantityLiteral -> Unit
            }
        }
        visit(expression)
    }

    fun rewrite(value: ResultExpression, selections: (List<String>) -> List<String>,
        contracts: (MetricValueContract) -> MetricValueContract, transform: (ResultExpression) -> ResultExpression): ResultExpression {
        fun bind(child: ResultExpression): ResultExpression = rewrite(child, selections, contracts, transform)
        return transform(when (value) {
            is ResultCall -> ResultCall(value.function, value.arguments.map(::bind))
            is ResultNativeCall -> ResultNativeCall(value.connectorRef, value.function, value.arguments.map(::bind))
            is ResultOperation -> ResultOperation(value.operator, value.operands.map(::bind))
            is ResultRow -> ResultRow(value.operands.map(::bind))
            is ResultRowField -> value.copy(expression = bind(value.expression))
            is ResultCollectionSort -> value.copy(expression = bind(value.expression))
            is ResultCollectionText -> value.copy(expression = bind(value.expression))
            is ResultCaseWhen -> ResultCaseWhen(value.branches.map { it.copy(`when` = bind(it.`when`), then = bind(it.then)) }, bind(value.otherwise))
            is ResultCast -> value.copy(expression = bind(value.expression))
            is ResultRound -> value.copy(expression = bind(value.expression))
            is ResultNumeric -> value.copy(expression = bind(value.expression), valueContract = contracts(value.valueContract))
            is ResultWeightedContribution -> value.copy(
                value = bind(value.value),
                weight = bind(value.weight),
                observationUnit = when (val unit = value.observationUnit) {
                    is Calculation.ObjectsBy -> Calculation.ObjectsBy(selections(unit.keys))
                    is Calculation.RawRows -> unit
                },
                valueContract = contracts(value.valueContract)
            )
            is ResultUniformAllocation -> ResultUniformAllocation(bind(value.value), selections(value.sourceKeys), selections(value.memberKeys), contracts(value.valueContract), value.unitEquation)
            is ResultQuantityLiteral -> ResultQuantityLiteral(value.value, contracts(value.valueContract))
            is ResultArgument, is ResultSymbol, is ResultLiteral, is ResultTypedLiteral -> value
        })
    }
}
