package com.olap.prototype.expression

import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.Calc
import org.apache.calcite.rel.core.CorrelationId
import org.apache.calcite.rel.core.Filter
import org.apache.calcite.rel.core.Project
import org.apache.calcite.rel.core.Join
import org.apache.calcite.rel.core.Sort
import org.apache.calcite.rel.core.Window
import org.apache.calcite.rel.core.RepeatUnion
import org.apache.calcite.rel.logical.LogicalFilter
import org.apache.calcite.rel.logical.LogicalProject
import org.apache.calcite.rex.*
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import java.util.Collections
import java.util.IdentityHashMap

/** 优化完成后绑定数值检查的求值槽；每层仍是一行输入对应一行输出的投影。 */
internal class NumericSqlProjection {
    private val rewritten = IdentityHashMap<RelNode, RelNode>()
    private val guarantees = IdentityHashMap<RelNode, Map<Int, Range>>()
    private val containsChecks = IdentityHashMap<RexNode, Boolean>()
    private val containsQueries = IdentityHashMap<RexNode, Boolean>()
    private val subQueries = object : RexShuttle() {
        override fun visitSubQuery(query: RexSubQuery): RexNode {
            val visited = super.visitSubQuery(query) as RexSubQuery
            return lower(visited.rel).let { if (it === visited.rel) visited else visited.clone(it) }
        }
    }
    val boundaries: MutableSet<RelNode> = Collections.newSetFromMap(IdentityHashMap())
    var bindings: Int = 0
        private set
    var reusedChecks: Int = 0
        private set

    fun lower(node: RelNode): RelNode = rewritten[node] ?: run {
        val inputs = node.inputs.map(::lower)
        val current = if (inputs.indices.any { inputs[it] !== node.getInput(it) }) {
            // 递归变量的列名是固定点合同；重建节点不能从优化后的seed重新推导另一组名称。
            val named = if (node is RepeatUnion) inputs.map { input ->
                check(input.rowType.fieldCount == node.rowType.fieldCount) { "numeric projection changed recursive relation width" }
                if (input.rowType.fieldNames == node.rowType.fieldNames) input else LogicalProject.create(input, emptyList(),
                    input.rowType.fieldList.mapIndexed { index, field -> RexInputRef(index, field.type) }, node.rowType.fieldNames, emptySet())
            } else inputs
            node.copy(node.traitSet, named)
        } else node
        val result = when (current) {
            is Project -> {
                val program = Program(current.input, current.variablesSet)
                val expressions = current.projects.map { program.expression(it) }
                val input = program.input(expressions)
                (if (input === current.input && expressions.indices.all { expressions[it] === current.projects[it] }) current
                else current.copy(current.traitSet, input, expressions.map(program::resolve), current.rowType)).also { projected ->
                    guarantees[projected] = expressions.mapIndexedNotNull { index, value -> program.range(value)?.let { index to it } }.toMap()
                }
            }
            is Filter -> {
                val program = Program(current.input, current.variablesSet)
                val predicate = program.expression(current.condition)
                val input = program.input(listOf(predicate) + current.rowType.fieldList.mapIndexed { index, field -> RexInputRef(index, field.type) })
                val filtered = if (input === current.input && predicate === current.condition) current else current.copy(current.traitSet, input, program.resolve(predicate))
                (if (input === current.input) filtered else LogicalProject.create(filtered, emptyList(),
                    current.rowType.fieldList.mapIndexed { index, field -> RexInputRef(index, field.type) }, current.rowType, emptySet())).also {
                    guarantees[it] = guarantees[current.input].orEmpty()
                }
            }
            is Calc -> {
                if (current.program.exprList.none(::containsCheck)) current.accept(subQueries) else {
                    val predicate = current.program.condition?.let(current.program::expandLocalRef)
                    val input = if (predicate == null) current.input else LogicalFilter.create(current.input, predicate, com.google.common.collect.ImmutableSet.copyOf(current.variablesSet))
                    lower(LogicalProject.create(input, emptyList(), current.program.projectList.map(current.program::expandLocalRef), current.rowType, current.variablesSet))
                }
            }
            is Sort, is Window -> current.also { guarantees[it] = guarantees[current.getInput(0)].orEmpty() }
            is Join -> current.also {
                val left = guarantees[current.left].orEmpty()
                guarantees[it] = if (!current.joinType.projectsRight()) left else left + guarantees[current.right].orEmpty().mapKeys { (index, _) -> index + current.left.rowType.fieldCount }
            }
            else -> current
        }
        rewritten[node] = result
        rewritten[result] = result
        result
    }

    private inner class Program(private val original: RelNode, private val correlations: Set<CorrelationId>) {
        private val rex = original.cluster.rexBuilder
        private val width = original.rowType.fieldCount
        private val slots = mutableListOf<Slot>()
        private val reusable = linkedMapOf<Binding, RexInputRef>()
        private val checks = linkedMapOf<Check, RexInputRef>()
        private val bounds = guarantees[original].orEmpty().toMutableMap()
        private val names = original.rowType.fieldNames.toMutableSet()
        private var resolvedIndexes = IntArray(width) { it }

        fun expression(value: RexNode, active: RexNode? = null): RexNode = if (!containsCheck(value)) {
            if (containsSubQuery(value)) value.accept(subQueries) else value
        } else when (value) {
            is RexSubQuery -> {
                val operands = value.operands.map { expression(it, active) }
                val query = if (operands.indices.all { operands[it] === value.operands[it] }) value else value.clone(value.type, operands)
                lower(query.rel).let { if (it === query.rel) query else query.clone(it) }
            }
            is RexFieldAccess -> rex.makeFieldAccess(expression(value.referenceExpr, active), value.field.index)
            // 窗口输入按完整分区求值，不能套用某个输出行的CASE条件。
            is RexOver -> value
            is RexCall -> when {
                value.operator is SemanticNumericRex.NumericCheck -> check(value, active)
                value.kind == SqlKind.CASE -> conditional(value, active)
                value.kind == SqlKind.COALESCE -> coalesce(value, active)
                value.kind == SqlKind.AND || value.kind == SqlKind.OR -> boolean(value, active)
                else -> value.clone(value.type, value.operands.map { expression(it, active) })
            }
            else -> value
        }

        private fun check(call: RexCall, active: RexNode?): RexNode {
            val check = call.operator as SemanticNumericRex.NumericCheck
            val value = bind(expression(call.operands[0], active), active)
            val known = bounds[value.index]
            if (known != null && check.minimum <= known.minimum && check.maximum >= known.maximum) {
                reusedChecks++
                return value
            }
            val key = Check(value, active, call.type, check.minimum, check.maximum)
            checks[key]?.let { reusedChecks++; return it }
            val checked = call.clone(call.type, listOf(value) + call.operands.drop(1))
            return bind(checked, active).also { checks[key] = it; bounds[it.index] = Range(check.minimum, check.maximum) }
        }

        fun range(value: RexNode): Range? = when (value) {
            is RexInputRef -> bounds[value.index]
            is RexCall -> when {
                value.operator is SemanticNumericRex.NumericCheck -> (value.operator as SemanticNumericRex.NumericCheck).let { Range(it.minimum, it.maximum) }
                value.kind == SqlKind.CASE -> {
                    val values = value.operands.filterIndexed { index, _ -> index % 2 == 1 || index == value.operands.lastIndex }.filterNot { RexLiteral.isNullLiteral(it) }
                    val ranges = values.map(::range)
                    if (ranges.isEmpty() || ranges.any { it == null }) null else Range(ranges.filterNotNull().minOf { it.minimum }, ranges.filterNotNull().maxOf { it.maximum })
                }
                value.kind == SqlKind.CAST && value.type.sqlTypeName == value.operands[0].type.sqlTypeName &&
                    value.type.precision == value.operands[0].type.precision && value.type.scale == value.operands[0].type.scale -> range(value.operands[0])
                else -> null
            }
            else -> null
        }

        private fun conditional(call: RexCall, active: RexNode?): RexNode {
            var remaining = active
            val operands = mutableListOf<RexNode>()
            call.operands.dropLast(1).chunked(2).forEach { branch ->
                val predicate = bind(expression(branch[0], remaining), remaining)
                operands.add(predicate)
                operands.add(expression(branch[1], both(remaining, rex.makeCall(Ops.IS_TRUE, predicate))))
                remaining = bind(both(remaining, rex.makeCall(Ops.IS_NOT_TRUE, predicate)), null)
            }
            operands.add(expression(call.operands.last(), remaining))
            return call.clone(call.type, operands)
        }

        private fun coalesce(call: RexCall, active: RexNode?): RexNode {
            var remaining = active
            val operands = mutableListOf<RexNode>()
            call.operands.dropLast(1).forEach { operand ->
                val value = bind(expression(operand, remaining), remaining)
                operands.add(rex.makeCall(Ops.IS_NOT_NULL, value)); operands.add(value)
                remaining = bind(both(remaining, rex.makeCall(Ops.IS_NULL, value)), null)
            }
            operands.add(expression(call.operands.last(), remaining))
            return rex.makeCall(call.type, Ops.CASE, operands)
        }

        private fun boolean(call: RexCall, active: RexNode?): RexNode {
            var remaining = active
            val operands = call.operands.map { operand ->
                bind(expression(operand, remaining), remaining).also { value ->
                    remaining = bind(both(remaining, rex.makeCall(if (call.kind == SqlKind.AND) Ops.IS_NOT_FALSE else Ops.IS_NOT_TRUE, value)), null)
                }
            }
            return call.clone(call.type, operands)
        }

        private fun both(active: RexNode?, predicate: RexNode): RexNode = if (active == null) predicate else rex.makeCall(Ops.AND, active, predicate)

        private fun bind(value: RexNode, active: RexNode?): RexInputRef {
            if (value is RexInputRef) return value
            val key = Binding(value, active)
            val deterministic = !containsSubQuery(value) && RexUtil.isDeterministic(value) && !RexUtil.containsCorrelation(value)
            if (deterministic) reusable[key]?.let { return it }
            val guarded = if (active == null) value else rex.makeCall(Ops.CASE, active, value, rex.makeNullLiteral(value.type))
            val dependencies = org.apache.calcite.plan.RelOptUtil.InputFinder.bits(guarded).asList()
            val depth = (dependencies.filter { it >= width }.maxOfOrNull { slots[it - width].depth } ?: 0) + 1
            var name = "__numeric_${slots.size}"
            while (!names.add(name)) name += "_"
            val reference = RexInputRef(width + slots.size, guarded.type)
            slots.add(Slot(guarded, name, depth))
            range(value)?.let { bounds[reference.index] = it }
            if (deterministic) reusable[key] = reference
            return reference
        }

        fun input(outputs: List<RexNode>): RelNode {
            var input = original
            resolvedIndexes = IntArray(width + slots.size) { if (it < width) it else -1 }
            val needed = mutableSetOf<Int>()
            val lastUse = IntArray(width + slots.size)
            fun requireValue(value: RexNode, depth: Int) {
                org.apache.calcite.plan.RelOptUtil.InputFinder.bits(value).asList().forEach { index ->
                    lastUse[index] = maxOf(lastUse[index], depth)
                    if (index >= width && needed.add(index - width)) requireValue(slots[index - width].value, slots[index - width].depth)
                }
            }
            val finalDepth = (slots.maxOfOrNull { it.depth } ?: 0) + 1
            outputs.forEach { requireValue(it, finalDepth) }
            var available = (0 until width).toList()
            // 第一层也必须读取既有关系列，不能让SQL转换把Aggregate/Window/Project重新内联进检查。
            if (needed.isNotEmpty()) boundaries.add(original)
            needed.groupBy { slots[it].depth }.toSortedMap().forEach { (depth, indexes) ->
                // 相关变量按原输入序号取字段；行保持槽可以追加列，不能重排其词法输入前缀。
                val retained = available.filter { it < width && correlations.isNotEmpty() || lastUse[it] > depth }
                val identities = retained.map { index -> RexInputRef(resolvedIndexes[index], input.rowType.fieldList[resolvedIndexes[index]].type) }
                val values = indexes.map { resolve(slots[it].value) }
                input = LogicalProject.create(input, emptyList(), identities + values,
                    retained.map { index -> if (index < width) original.rowType.fieldNames[index] else slots[index - width].name } + indexes.map { slots[it].name }, correlations).also(boundaries::add)
                available = retained + indexes.map { width + it }
                resolvedIndexes.fill(-1)
                available.forEachIndexed { index, virtual -> resolvedIndexes[virtual] = index }
                bindings += indexes.size
            }
            return input
        }

        fun resolve(value: RexNode): RexNode = value.accept(object : RexShuttle() {
            override fun visitInputRef(ref: RexInputRef): RexNode = RexInputRef(resolvedIndexes[ref.index].also {
                check(it >= 0) { "numeric evaluation slot references a later projection" }
            }, ref.type)
        })
    }

    private fun containsCheck(value: RexNode): Boolean = containsChecks[value] ?: when (value) {
        // 子查询关系在自己的作用域递归降低；其存在本身不要求拆开外层AND/CASE。
        is RexSubQuery -> value.operands.any(::containsCheck)
        is RexCall -> SemanticNumericRex.isNumericCheck(value) || value.operands.any(::containsCheck)
        is RexFieldAccess -> containsCheck(value.referenceExpr)
        else -> false
    }.also { containsChecks[value] = it }

    private fun containsSubQuery(value: RexNode): Boolean = containsQueries[value] ?: when (value) {
        is RexSubQuery -> true
        is RexCall -> value.operands.any(::containsSubQuery)
        is RexFieldAccess -> containsSubQuery(value.referenceExpr)
        else -> false
    }.also { containsQueries[value] = it }

    private data class Slot(val value: RexNode, val name: String, val depth: Int)
    private data class Binding(val value: RexNode, val active: RexNode?)
    private data class Range(val minimum: java.math.BigDecimal, val maximum: java.math.BigDecimal)
    private data class Check(val value: RexInputRef, val active: RexNode?, val type: org.apache.calcite.rel.type.RelDataType,
        val minimum: java.math.BigDecimal, val maximum: java.math.BigDecimal)
}
