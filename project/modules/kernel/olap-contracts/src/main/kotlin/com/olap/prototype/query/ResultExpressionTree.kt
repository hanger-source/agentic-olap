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
        visit(expression, emptyList()) { value, _ -> add(value) }
    }

    /** 使用正式字段路径访问同一棵树；身份检查不自行复制节点布局。 */
    fun visit(expression: ResultExpression, path: List<Any>, visitor: (ResultExpression, List<Any>) -> Unit) {
        fun descend(value: ResultExpression, current: List<Any>) {
            visitor(value, current)
            when (value) {
                is ResultCall -> value.arguments.forEachIndexed { index, child -> descend(child, current + listOf("arguments", index)) }
                is ResultNativeCall -> value.arguments.forEachIndexed { index, child -> descend(child, current + listOf("arguments", index)) }
                is ResultOperation -> value.operands.forEachIndexed { index, child -> descend(child, current + listOf("operands", index)) }
                is ResultRow -> value.operands.forEachIndexed { index, child -> descend(child, current + listOf("operands", index)) }
                is ResultCaseWhen -> {
                    value.branches.forEachIndexed { index, branch ->
                        val branchPath = current + listOf("branches", index)
                        descend(branch.`when`, branchPath + "when")
                        descend(branch.then, branchPath + "then")
                    }
                    descend(value.otherwise, current + "otherwise")
                }
                is ResultCast -> descend(value.expression, current + "expression")
                is ResultRowField -> descend(value.expression, current + "expression")
                is ResultCollectionSort -> descend(value.expression, current + "expression")
                is ResultCollectionText -> descend(value.expression, current + "expression")
                is ResultNumeric -> descend(value.expression, current + "expression")
                is ResultRound -> descend(value.expression, current + "expression")
                is ResultWeightedContribution -> {
                    descend(value.value, current + "value")
                    descend(value.weight, current + "weight")
                }
                is ResultUniformAllocation -> descend(value.value, current + "value")
                is ResultArgument, is ResultSymbol, is ResultLiteral, is ResultTypedLiteral, is ResultQuantityLiteral -> Unit
            }
        }
        descend(expression, path)
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
