package com.olap.prototype.expression

import org.apache.calcite.plan.RelOptUtil
import org.apache.calcite.rel.RelCollations
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.*
import org.apache.calcite.rel.logical.LogicalFilter
import org.apache.calcite.rel.logical.LogicalProject
import org.apache.calcite.rex.*
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.util.ImmutableBitSet
import java.util.Collections
import java.util.IdentityHashMap

/** 优化后为重复的稳定标量与数值检查建立有名求值列；每层保持原输入人口。 */
internal class SqlEvaluationProjection {
    private val rewritten = IdentityHashMap<RelNode, RelNode>()
    private val guarantees = IdentityHashMap<RelNode, Map<Int, Range>>()
    private val containsChecks = IdentityHashMap<RexNode, Boolean>()
    private val containsQueries = IdentityHashMap<RexNode, Boolean>()
    private val stableScalars = IdentityHashMap<RexNode, Boolean>()
    private val sharedPrograms: MutableSet<RelNode> = Collections.newSetFromMap(IdentityHashMap())
    private val subQueries = object : RexShuttle() {
        override fun visitSubQuery(query: RexSubQuery): RexNode {
            val visited = super.visitSubQuery(query) as RexSubQuery
            return lower(visited.rel).let { if (it === visited.rel) visited else visited.clone(it) }
        }
    }
    val boundaries: MutableSet<RelNode> = Collections.newSetFromMap(IdentityHashMap())
    // 仍只统计数值检查及其求值依赖；普通标量共享不计入既有数值统计。
    var numericBindings: Int = 0
        private set
    var reusedChecks: Int = 0
        private set

    fun lower(plan: RelNode): RelNode {
        rewritten[plan]?.let { return it }
        val uses = IdentityHashMap<RelNode, Int>()
        val visited: MutableSet<RelNode> = Collections.newSetFromMap(IdentityHashMap())
        fun count(node: RelNode) {
            if (!visited.add(node)) return
            node.inputs.forEach { input -> uses[input] = (uses[input] ?: 0) + 1; count(input) }
        }
        count(plan)
        sharedPrograms.addAll(uses.filterValues { it >= 2 }.keys)
        return visit(plan)
    }

    private fun visit(node: RelNode): RelNode = rewritten[node] ?: run {
        val result = when (node) {
            is Project -> project(node)
            is Aggregate -> aggregate(node)
            is Calc -> {
                // LocalRef 的单个产出也可能有多个消费者；在同一晚期程序内展开后计数。
                val predicate = node.program.condition?.let(node.program::expandLocalRef)
                val input = if (predicate == null) node.input else LogicalFilter.create(node.input, predicate,
                    com.google.common.collect.ImmutableSet.copyOf(node.variablesSet))
                visit(LogicalProject.create(input, emptyList(), node.program.projectList.map(node.program::expandLocalRef), node.rowType, node.variablesSet))
            }
            else -> when (val current = inputs(node)) {
                is Filter -> {
                    val program = Program(current.input, current.variablesSet, listOf(current.condition to 1))
                    val predicate = program.expression(current.condition)
                    val input = program.input(listOf(predicate) + current.rowType.fieldList.mapIndexed { index, field -> RexInputRef(index, field.type) })
                    val filtered = if (input === current.input && predicate === current.condition) current else current.copy(current.traitSet, input, program.resolve(predicate))
                    (if (input === current.input) filtered else LogicalProject.create(filtered, emptyList(),
                        current.rowType.fieldList.mapIndexed { index, field -> RexInputRef(index, field.type) }, current.rowType, emptySet())).also {
                        guarantees[it] = guarantees[current.input].orEmpty()
                    }
                }
                is Sort, is Window -> current.also { guarantees[it] = guarantees[current.getInput(0)].orEmpty() }
                is Join -> current.also {
                    val left = guarantees[current.left].orEmpty()
                    guarantees[it] = if (!current.joinType.projectsRight()) left else left + guarantees[current.right].orEmpty().mapKeys { (index, _) -> index + current.left.rowType.fieldCount }
                }
                else -> current.accept(subQueries)
            }
        }
        rewritten[node] = result
        rewritten[result] = result
        result
    }

    private fun inputs(node: RelNode): RelNode {
        val inputs = node.inputs.map(::visit)
        if (inputs.indices.all { inputs[it] === node.getInput(it) }) return node
        // 递归变量的列名是固定点合同，不能从重建后的 seed 推导另一组名称。
        val named = if (node is RepeatUnion) inputs.map { input ->
            check(input.rowType.fieldCount == node.rowType.fieldCount) { "evaluation projection changed recursive relation width" }
            if (input.rowType.fieldNames == node.rowType.fieldNames) input else LogicalProject.create(input, emptyList(),
                input.rowType.fieldList.mapIndexed { index, field -> RexInputRef(index, field.type) }, node.rowType.fieldNames, emptySet())
        } else inputs
        return node.copy(node.traitSet, named)
    }

    private fun project(project: Project): RelNode {
        val source = projection(project)
        val program = Program(source.input, project.variablesSet, source.values.map { it to 1 })
        val expressions = source.values.map { program.expression(it) }
        val input = program.input(expressions)
        return (if (input === project.input && expressions.indices.all { expressions[it] === project.projects[it] }) project
        else project.copy(project.traitSet, input, expressions.map(program::resolve), project.rowType)).also { projected ->
            guarantees[projected] = expressions.mapIndexedNotNull { index, value -> program.range(value)?.let { index to it } }.toMap()
        }
    }

    private fun aggregate(aggregate: Aggregate): RelNode {
        val projected = aggregate.input as? Project ?: return inputs(aggregate)
        val source = projection(projected, aggregate.aggCallList.any { it.hasFilter() })
        val uses = IntArray(source.values.size)
        val unconditional = BooleanArray(source.values.size)
        val filters = List(source.values.size) { linkedSetOf<Int>() }
        fun use(index: Int, filter: Int? = null) {
            uses[index]++
            if (filter == null) unconditional[index] = true else filters[index].add(filter)
        }
        // 分组列同时出现在完成关系输出与 GROUP BY 中，不能再内联同一标量。
        aggregate.groupSet.forEach { use(it); use(it) }
        aggregate.aggCallList.forEach { call ->
            val filter = call.filterArg.takeIf { it >= 0 }
            filter?.let { use(it) }
            call.argList.forEach { use(it, filter) }
            call.distinctKeys?.forEach { use(it, filter) }
            call.collation.fieldCollations.forEach { use(it.fieldIndex, filter) }
            call.rexList.forEach { value ->
                value.accept(object : RexShuttle() {
                    override fun visitInputRef(ref: RexInputRef): RexNode { use(ref.index, filter); return ref }
                })
            }
        }
        val required = uses.indices.filter { uses[it] > 0 }
        // COUNT(*) 没有值参数；不凭空制造非 NULL 值，也不改变其袋人口。
        if (required.isEmpty()) return aggregate.copy(aggregate.traitSet, source.input, aggregate.groupSet, aggregate.groupSets, aggregate.aggCallList)
        val program = Program(source.input, projected.variablesSet, required.map { source.values[it] to uses[it] })
        val predicates = linkedMapOf<Int, RexInputRef>()
        required.filterNot { unconditional[it] }.flatMap { filters[it] }.toSet().forEach { index ->
            // 每个真实 FILTER 列保留自己的产出身份；VOLATILE 谓词也不能为参数保护再调用一次。
            predicates[index] = program.bind(program.expression(source.values[index]), null)
        }
        val rex = source.input.cluster.rexBuilder
        val values = required.map { index ->
            predicates[index] ?: run {
                val active = if (unconditional[index]) null else filters[index].map { filter -> rex.makeCall(Ops.IS_TRUE, predicates.getValue(filter)) }
                    .reduce { left, right -> rex.makeCall(Ops.OR, left, right) }
                program.guarded(program.expression(source.values[index], active), active)
            }
        }
        val input = program.input(values)
        val selected = LogicalProject.create(input, projected.hints, values.map(program::resolve), required.map { projected.rowType.fieldNames[it] }, projected.variablesSet)
        guarantees[selected] = values.mapIndexedNotNull { index, value -> program.range(value)?.let { index to it } }.toMap()
        val indexes = IntArray(source.values.size) { -1 }
        required.forEachIndexed { index, prior -> indexes[prior] = index }
        fun remap(index: Int): Int = indexes[index].also { check(it >= 0) { "aggregate references an undeclared evaluation input" } }
        fun bits(value: ImmutableBitSet): ImmutableBitSet = ImmutableBitSet.of(value.map(::remap))
        val references = object : RexShuttle() {
            override fun visitInputRef(ref: RexInputRef): RexNode = RexInputRef(remap(ref.index), selected.rowType.fieldList[remap(ref.index)].type)
        }
        val calls = aggregate.aggCallList.map { call ->
            AggregateCall.create(call.parserPosition, call.aggregation, call.isDistinct, call.isApproximate, call.ignoreNulls(),
                call.rexList.map { it.accept(references) }, call.argList.map(::remap), if (call.filterArg < 0) -1 else remap(call.filterArg),
                call.distinctKeys?.let(::bits), RelCollations.of(call.collation.fieldCollations.map { it.withFieldIndex(remap(it.fieldIndex)) }), call.type, call.name)
        }
        return aggregate.copy(aggregate.traitSet, selected, bits(aggregate.groupSet), aggregate.groupSets.map(::bits), calls)
    }

    /** 只组合相邻的稳定列程序；数值检查不能越过调用者的活动域，VOLATILE 也不展开。 */
    private fun projection(project: Project, filtered: Boolean = false): Projection {
        var input = project.input
        var values = project.projects.toList()
        if (project.variablesSet.isEmpty() && values.none(::containsWindowOrCorrelation)) {
            while (input is Project && input !in sharedPrograms && input.variablesSet.isEmpty() && input.hints.isEmpty() &&
                input.projects.none(::containsWindowOrCorrelation) && input.projects.all(::statementStableScalar) &&
                (input.projects.none(::containsCheck) || !filtered && values.none(::containsActivityBoundary))) {
                val preceding = input
                val references = object : RexShuttle() {
                    override fun visitInputRef(ref: RexInputRef): RexNode = preceding.projects[ref.index]
                }
                values = values.map { it.accept(references) }
                input = preceding.input
            }
        }
        return Projection(visit(input), values)
    }

    private fun containsWindowOrCorrelation(value: RexNode): Boolean = when (value) {
        is RexOver, is RexCorrelVariable -> true
        is RexSubQuery -> true
        is RexCall -> value.operands.any(::containsWindowOrCorrelation)
        is RexFieldAccess -> containsWindowOrCorrelation(value.referenceExpr)
        else -> false
    }

    private fun containsActivityBoundary(value: RexNode): Boolean = when (value) {
        is RexCall -> value.kind in setOf(SqlKind.CASE, SqlKind.COALESCE, SqlKind.AND, SqlKind.OR) || value.operands.any(::containsActivityBoundary)
        is RexFieldAccess -> containsActivityBoundary(value.referenceExpr)
        else -> false
    }

    private inner class Program(private val original: RelNode, private val correlations: Set<CorrelationId>, roots: List<Pair<RexNode, Int>>) {
        private val rex = original.cluster.rexBuilder
        private val width = original.rowType.fieldCount
        private val slots = mutableListOf<Slot>()
        private val reusable = linkedMapOf<Binding, RexInputRef>()
        private val checks = linkedMapOf<Check, RexInputRef>()
        private val bounds = guarantees[original].orEmpty().toMutableMap()
        private val names = original.rowType.fieldNames.toMutableSet()
        private val occurrences = mutableMapOf<RexNode, Int>()
        private val neededExpressions = IdentityHashMap<RexNode, Boolean>()
        private val numericRoots = mutableSetOf<Int>()
        private var resolvedIndexes = IntArray(width) { it }

        init {
            fun count(value: RexNode, uses: Int) {
                occurrences[value] = minOf(2, (occurrences[value] ?: 0) + minOf(2, uses))
                when (value) {
                    is RexOver -> Unit
                    is RexCall -> value.operands.forEach { count(it, uses) }
                    is RexFieldAccess -> count(value.referenceExpr, uses)
                }
            }
            roots.forEach { (value, uses) -> count(value, uses) }
        }

        fun expression(value: RexNode, active: RexNode? = null): RexNode {
            if (!needsEvaluation(value)) return if (containsSubQuery(value)) value.accept(subQueries) else value
            val rewritten = when (value) {
                is RexSubQuery -> {
                    val operands = value.operands.map { expression(it, active) }
                    val query = if (operands.indices.all { operands[it] === value.operands[it] }) value else value.clone(value.type, operands)
                    lower(query.rel).let { if (it === query.rel) query else query.clone(it) }
                }
                is RexFieldAccess -> rex.makeFieldAccess(expression(value.referenceExpr, active), value.field.index)
                // 窗口输入按完整分区求值，不能套用某个输出行的 CASE 条件。
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
            return if ((occurrences[value] ?: 0) >= 2 && candidate(value)) bind(rewritten, active) else rewritten
        }

        private fun needsEvaluation(value: RexNode): Boolean = neededExpressions[value] ?: when {
            value is RexOver -> false
            value is RexCall && value.operator is SemanticNumericRex.NumericCheck -> true
            (occurrences[value] ?: 0) >= 2 && candidate(value) -> true
            value is RexCall -> value.operands.any(::needsEvaluation)
            value is RexFieldAccess -> needsEvaluation(value.referenceExpr)
            else -> false
        }.also { neededExpressions[value] = it }

        private fun check(call: RexCall, active: RexNode?): RexNode {
            val check = call.operator as SemanticNumericRex.NumericCheck
            val value = bind(expression(call.operands[0], active), active, numeric = true)
            val known = bounds[value.index]
            if (known != null && check.minimum <= known.minimum && check.maximum >= known.maximum) {
                reusedChecks++
                return value
            }
            val key = Check(value, active, call.type, check.minimum, check.maximum)
            checks[key]?.let { reusedChecks++; return it }
            val checked = call.clone(call.type, listOf(value) + call.operands.drop(1))
            return bind(checked, active, numeric = true).also { checks[key] = it; bounds[it.index] = Range(check.minimum, check.maximum) }
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
            val numeric = containsCheck(call)
            var remaining = active
            val operands = mutableListOf<RexNode>()
            call.operands.dropLast(1).chunked(2).forEach { branch ->
                val predicate = bind(expression(branch[0], remaining), remaining, numeric)
                operands.add(predicate)
                operands.add(expression(branch[1], both(remaining, rex.makeCall(Ops.IS_TRUE, predicate))))
                remaining = bind(both(remaining, rex.makeCall(Ops.IS_NOT_TRUE, predicate)), null, numeric)
            }
            operands.add(expression(call.operands.last(), remaining))
            return call.clone(call.type, operands)
        }

        private fun coalesce(call: RexCall, active: RexNode?): RexNode {
            val numeric = containsCheck(call)
            var remaining = active
            val operands = mutableListOf<RexNode>()
            call.operands.dropLast(1).forEach { operand ->
                val value = bind(expression(operand, remaining), remaining, numeric)
                operands.add(rex.makeCall(Ops.IS_NOT_NULL, value)); operands.add(value)
                remaining = bind(both(remaining, rex.makeCall(Ops.IS_NULL, value)), null, numeric)
            }
            operands.add(expression(call.operands.last(), remaining))
            return rex.makeCall(call.type, Ops.CASE, operands)
        }

        private fun boolean(call: RexCall, active: RexNode?): RexNode {
            val numeric = containsCheck(call)
            var remaining = active
            val operands = call.operands.map { operand ->
                bind(expression(operand, remaining), remaining, numeric).also { value ->
                    remaining = bind(both(remaining, rex.makeCall(if (call.kind == SqlKind.AND) Ops.IS_NOT_FALSE else Ops.IS_NOT_TRUE, value)), null, numeric)
                }
            }
            return call.clone(call.type, operands)
        }

        private fun both(active: RexNode?, predicate: RexNode): RexNode = if (active == null) predicate else rex.makeCall(Ops.AND, active, predicate)

        fun guarded(value: RexNode, active: RexNode?): RexNode = if (active == null) value else rex.makeCall(Ops.CASE, active, value, rex.makeNullLiteral(value.type))

        fun bind(value: RexNode, active: RexNode?, numeric: Boolean = false): RexInputRef {
            if (value is RexInputRef) return value.also { if (numeric) numericRoots.add(it.index) }
            val key = Binding(value, active)
            val stable = statementStableScalar(value)
            if (stable) reusable[key]?.let { if (numeric) numericRoots.add(it.index); return it }
            val guarded = guarded(value, active)
            val dependencies = RelOptUtil.InputFinder.bits(guarded).asList()
            val depth = (dependencies.filter { it >= width }.maxOfOrNull { slots[it - width].depth } ?: 0) + 1
            var name = "__evaluation_${slots.size}"
            while (!names.add(name)) name += "_"
            val reference = RexInputRef(width + slots.size, guarded.type)
            slots.add(Slot(guarded, name, depth))
            range(value)?.let { bounds[reference.index] = it }
            if (stable) reusable[key] = reference
            if (numeric) numericRoots.add(reference.index)
            return reference
        }

        fun input(outputs: List<RexNode>): RelNode {
            var input = original
            resolvedIndexes = IntArray(width + slots.size) { if (it < width) it else -1 }
            val needed = mutableSetOf<Int>()
            val lastUse = IntArray(width + slots.size)
            fun requireValue(value: RexNode, depth: Int) {
                RelOptUtil.InputFinder.bits(value).asList().forEach { index ->
                    lastUse[index] = maxOf(lastUse[index], depth)
                    if (index >= width && needed.add(index - width)) requireValue(slots[index - width].value, slots[index - width].depth)
                }
            }
            val numeric = mutableSetOf<Int>()
            fun requireNumeric(index: Int) {
                if (index >= width && numeric.add(index - width)) RelOptUtil.InputFinder.bits(slots[index - width].value).forEach(::requireNumeric)
            }
            numericRoots.forEach(::requireNumeric)
            val finalDepth = (slots.maxOfOrNull { it.depth } ?: 0) + 1
            outputs.forEach { requireValue(it, finalDepth) }
            var available = (0 until width).toList()
            // 第一层也只读取完成的关系列，不能再把 JOIN/Filter/Aggregate/Window 的输入拉入标量。
            if (needed.isNotEmpty()) boundaries.add(original)
            needed.groupBy { slots[it].depth }.toSortedMap().forEach { (depth, indexes) ->
                // 相关变量按原输入序号取字段，不能重排其词法输入前缀。
                val retained = available.filter { it < width && correlations.isNotEmpty() || lastUse[it] > depth }
                val identities = retained.map { index -> RexInputRef(resolvedIndexes[index], input.rowType.fieldList[resolvedIndexes[index]].type) }
                val values = indexes.map { resolve(slots[it].value) }
                input = LogicalProject.create(input, emptyList(), identities + values,
                    retained.map { index -> if (index < width) original.rowType.fieldNames[index] else slots[index - width].name } + indexes.map { slots[it].name }, correlations).also(boundaries::add)
                available = retained + indexes.map { width + it }
                resolvedIndexes.fill(-1)
                available.forEachIndexed { index, virtual -> resolvedIndexes[virtual] = index }
                numericBindings += indexes.count { it in numeric }
            }
            return input
        }

        fun resolve(value: RexNode): RexNode = value.accept(object : RexShuttle() {
            override fun visitInputRef(ref: RexInputRef): RexNode = RexInputRef(resolvedIndexes[ref.index].also {
                check(it >= 0) { "evaluation slot references a later projection" }
            }, ref.type)
        })
    }

    private fun candidate(value: RexNode): Boolean = (value is RexCall || value is RexFieldAccess) && statementStableScalar(value)

    private fun statementStableScalar(value: RexNode): Boolean = stableScalars[value] ?: when (value) {
        is RexOver, is RexSubQuery -> false
        is RexCall -> SemanticCalciteOperators.statementStable(value) && value.operands.all(::statementStableScalar)
        is RexFieldAccess -> statementStableScalar(value.referenceExpr)
        is RexInputRef, is RexLiteral, is RexDynamicParam -> true
        else -> false
    }.also { stableScalars[value] = it }

    private fun containsCheck(value: RexNode): Boolean = containsChecks[value] ?: when (value) {
        // 子查询关系在自己的作用域递归降低，其存在不要求拆开外层 AND/CASE。
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

    private data class Projection(val input: RelNode, val values: List<RexNode>)
    private data class Slot(val value: RexNode, val name: String, val depth: Int)
    private data class Binding(val value: RexNode, val active: RexNode?)
    private data class Range(val minimum: java.math.BigDecimal, val maximum: java.math.BigDecimal)
    private data class Check(val value: RexInputRef, val active: RexNode?, val type: org.apache.calcite.rel.type.RelDataType,
        val minimum: java.math.BigDecimal, val maximum: java.math.BigDecimal)
}
