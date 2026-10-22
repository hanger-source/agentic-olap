package com.olap.prototype.query

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.PostOperationContract.AggregateOutput
import com.olap.prototype.query.PostOperationContract.AncestorClosure
import com.olap.prototype.query.PostOperationContract.Cumulative
import com.olap.prototype.query.PostOperationContract.Derive
import com.olap.prototype.query.PostOperationContract.ExpandCollection
import com.olap.prototype.query.PostOperationContract.JoinInput
import com.olap.prototype.query.PostOperationContract.ObserveWindow
import com.olap.prototype.query.PostOperationContract.OffsetValue
import com.olap.prototype.query.PostOperationContract.OrderKey
import com.olap.prototype.query.PostOperationContract.OrderedRank
import com.olap.prototype.query.PostOperationContract.PartitionAggregate
import com.olap.prototype.query.PostOperationContract.PostFilter
import com.olap.prototype.query.PostOperationContract.Project
import com.olap.prototype.query.PostOperationContract.Rank
import com.olap.prototype.query.PostOperationContract.Regroup
import com.olap.prototype.query.PostOperationContract.ResultAggregate
import com.olap.prototype.query.PostOperationContract.ResultGrouping
import com.olap.prototype.query.PostOperationContract.SelectVersions
import com.olap.prototype.query.PostOperationContract.Sort
import com.olap.prototype.query.PostOperationContract.TopN
import com.olap.prototype.query.PostOperationContract.ValidatedOperation
import com.olap.prototype.query.PostOperationContract.WindowAggregate
import com.olap.prototype.query.ResultExpression.ResultNumeric
import com.olap.prototype.query.ResultExpression.ResultQuantityLiteral
import com.olap.prototype.query.ResultExpression.ResultSymbol
import com.olap.prototype.query.ResultExpression.ResultUniformAllocation
import com.olap.prototype.query.ResultExpression.ResultWeightedContribution

/** 结构访问与替换共用正式阶段；不解析实参、推断类型或执行查询。 */
object PostOperationTraversal {
    data class BindingUse(val binding: Calculation.Binding, val kind: Calculation.ParameterKind)

    fun bindings(operation: ValidatedOperation): List<BindingUse> = buildList {
        when (operation) {
            is SelectVersions -> operation.snapshot?.asOf?.let { add(BindingUse(it, Calculation.ParameterKind.SCALAR)) }
            is ObserveWindow -> {
                operation.observationRange?.let { add(BindingUse(it, Calculation.ParameterKind.RANGE)) }
                operation.displayRange?.let { add(BindingUse(it, Calculation.ParameterKind.RANGE)) }
            }
            else -> Unit
        }
    }
    fun expressions(operation: ValidatedOperation): List<ResultExpression> = when (operation) {
        is JoinInput -> listOf(operation.condition)
        is Derive -> listOf(operation.expression)
        is PostFilter -> listOf(operation.expression)
        else -> emptyList()
    }
    fun valueContracts(operation: ValidatedOperation): List<MetricValueContract> = buildList {
        when (operation) {
            is Cumulative -> add(operation.aggregation.valueContract)
            is AggregateOutput -> (operation.aggregation as? ResultAggregation.Numeric)?.valueContract?.let(::add)
            is ObserveWindow -> (operation.aggregation as? ResultAggregation.Numeric)?.valueContract?.let(::add)
            is Regroup -> operation.grouping.aggregates.mapNotNullTo(this) { (it.aggregation as? ResultAggregation.Numeric)?.valueContract }
            else -> Unit
        }
        expressions(operation).flatMap(ResultExpressionTree::nodes).mapNotNullTo(this) { expression ->
            when (expression) {
                is ResultNumeric -> expression.valueContract
                is ResultQuantityLiteral -> expression.valueContract
                is ResultWeightedContribution -> expression.valueContract
                is ResultUniformAllocation -> expression.valueContract
                else -> null
            }
        }
    }

    /** 只有分组选择允许分组族展开；排序键仍指向一个明确符号。 */
    fun groupingSelections(operation: ValidatedOperation): List<String> = when (operation) {
        is Project -> operation.symbols
        is Regroup -> operation.grouping.groupBy
        is Rank -> operation.partitionBy
        is OrderedRank -> operation.partitionBy
        is Cumulative -> operation.partitionBy
        is AggregateOutput -> operation.partitionBy
        is OffsetValue -> operation.partitionBy
        is SelectVersions -> operation.partitionBy
        is ObserveWindow -> operation.partitionBy
        else -> emptyList()
    }
    fun dependencies(operation: ValidatedOperation): Set<String> = buildSet {
        addAll(groupingSelections(operation))
        when (operation) {
            is Rank -> add(operation.orderBy)
            is OrderedRank -> operation.orderKeys.mapTo(this, OrderKey::symbol)
            is Cumulative -> {
                add(operation.input)
                add(operation.orderBy)
            }
            is AggregateOutput -> {
                add(operation.input)
                if (operation is WindowAggregate) operation.orderKeys.mapTo(this, OrderKey::symbol)
            }
            is OffsetValue -> {
                add(operation.input)
                operation.orderKeys.mapTo(this, OrderKey::symbol)
            }
            is SelectVersions -> {
                operation.orderKeys.mapTo(this, OrderKey::symbol)
                operation.snapshot?.let { add(it.timeSymbol) }
            }
            is ObserveWindow -> {
                addAll(operation.inputKeys)
                operation.orderKeys.mapTo(this, OrderKey::symbol)
                operation.timeSymbol?.let(::add)
            }
            is Sort -> operation.orderKeys.mapTo(this, OrderKey::symbol)
            is TopN -> operation.orderKeys.mapTo(this, OrderKey::symbol)
            is ExpandCollection -> add(operation.input)
            is AncestorClosure -> {
                addAll(operation.sourceKeys)
                addAll(operation.parentKeys)
            }
            is Regroup -> operation.grouping.aggregates.mapTo(this) { it.input }
            is JoinInput, is Derive, is PostFilter, is Project -> Unit
        }
        expressions(operation).forEach { expression ->
            addAll(ResultExpressionTree.groupingSelections(expression))
            ResultExpressionTree.nodes(expression).mapNotNullTo(this) { (it as? ResultSymbol)?.symbol }
        }
    }

    fun rewrite(operation: ValidatedOperation, expressions: (ResultExpression) -> ResultExpression,
        groupingSelections: (List<String>) -> List<String>, contracts: (MetricValueContract) -> MetricValueContract,
        bindings: (Calculation.Binding) -> Calculation.Binding): ValidatedOperation = when (operation) {
        is JoinInput -> operation.copy(condition = expressions(operation.condition))
        is Derive -> operation.copy(expression = expressions(operation.expression))
        is PostFilter -> operation.copy(expression = expressions(operation.expression))
        is Project -> Project(groupingSelections(operation.symbols))
        is ExpandCollection, is AncestorClosure, is Sort, is TopN -> operation
        is Rank -> Rank(operation.orderBy, operation.direction, groupingSelections(operation.partitionBy), operation.outputAlias, operation.outputDisplayName)
        is OrderedRank -> OrderedRank(operation.operationType, groupingSelections(operation.partitionBy), operation.outputAlias, operation.outputDisplayName, operation.orderKeys)
        is Cumulative -> Cumulative(operation.input, operation.orderBy, operation.direction, groupingSelections(operation.partitionBy), operation.outputAlias,
            operation.outputDisplayName, operation.aggregation.copy(valueContract = contracts(operation.aggregation.valueContract)))
        is PartitionAggregate -> PartitionAggregate(operation.input, groupingSelections(operation.partitionBy), operation.outputAlias, operation.outputDisplayName,
            operation.aggregation.mapContracts(contracts))
        is WindowAggregate -> WindowAggregate(operation.input, groupingSelections(operation.partitionBy), operation.outputAlias, operation.outputDisplayName,
            operation.orderKeys, operation.aggregation.mapContracts(contracts), operation.windowFrame)
        is OffsetValue -> OffsetValue(operation.input, groupingSelections(operation.partitionBy), operation.outputAlias, operation.outputDisplayName,
            operation.orderKeys, operation.function, operation.distance)
        is Regroup -> operation.copy(grouping = rewriteGrouping(operation.grouping, groupingSelections, contracts))
        is SelectVersions -> SelectVersions(groupingSelections(operation.partitionBy), operation.orderKeys, operation.ties,
            operation.snapshot?.let { it.copy(asOf = it.asOf?.let(bindings)) })
        is ObserveWindow -> ObserveWindow(operation.inputKeys, groupingSelections(operation.partitionBy), operation.outputAlias, operation.outputDisplayName,
            operation.orderKeys, operation.aggregation.mapContracts(contracts), operation.windowFrame,
            operation.domain, operation.timeSymbol, operation.observationRange?.let(bindings), operation.displayRange?.let(bindings), operation.calendar)
    }
    private fun rewriteGrouping(grouping: ResultGrouping, selections: (List<String>) -> List<String>,
        contracts: (MetricValueContract) -> MetricValueContract): ResultGrouping {
        val keys = selections(grouping.groupBy)
        val levels = grouping.groupingLevels?.let { levels ->
            GroupingLevels(levels.outputAlias, levels.levels.map { GroupingLevels.Level(it.id, selections(it.groupBy)) }).bind(keys)
        }
        return ResultGrouping(keys, grouping.aggregates.map { aggregate ->
            aggregate.copy(aggregation = aggregate.aggregation.mapContracts(contracts))
        }, levels)
    }
}
