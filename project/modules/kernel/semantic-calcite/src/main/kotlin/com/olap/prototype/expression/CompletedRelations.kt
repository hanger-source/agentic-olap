package com.olap.prototype.expression

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.query.GroupingLevels
import com.olap.prototype.query.RowPopulationContract
import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.query.SetOutputContract
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.Calc
import org.apache.calcite.rel.core.Correlate
import org.apache.calcite.rel.core.JoinRelType
import org.apache.calcite.rel.core.Project
import org.apache.calcite.rel.core.RepeatUnion
import org.apache.calcite.rel.core.TableScan
import org.apache.calcite.rex.*
import org.apache.calcite.schema.TransientTable
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.tools.RelBuilder
import org.apache.calcite.util.ImmutableBitSet
import java.math.BigDecimal
import java.util.IdentityHashMap

/** 完成关系的集合、分层及人口对齐，保留bag、NULL和字段容量。 */
object CompletedRelations {
    /** 调用方已绑定同一精确 ROOT；仅合并不改变该完成关系人口和完整键的确定投影。 */
    fun combineProjections(builder: RelBuilder, basis: RelNode, branches: List<RelNode>, dimensions: List<String>): RelNode? {
        if (branches.size < 2 || !deterministic(basis)) return null
        // RelBuilder 会把调用投影并入 basis 自身的 Project；沿这份精确 basis 找到其实际共享输入。
        var shared = basis
        while (projectionExpressions(shared)?.all { safeExpression(it, true) } == true) shared = shared.getInput(0)
        val values = branches.map { projection(it, shared) ?: return null }
        val keys = branches.mapIndexed { branch, relation -> dimensions.map { name ->
            val index = relation.rowType.fieldNames.indexOf(name)
            if (index < 0) return null
            (values[branch][index] as? RexInputRef)?.index ?: return null
        } }
        if (keys.any { it != keys.first() } || shared.cluster.metadataQuery.areColumnsUnique(shared, ImmutableBitSet.of(keys.first()), false) != true) return null
        val names = dimensions.toMutableList()
        val expressions = keys.first().mapTo(mutableListOf<RexNode>()) { builder.rexBuilder.makeInputRef(shared, it) }
        branches.forEachIndexed { branch, relation -> relation.rowType.fieldNames.forEachIndexed { index, name ->
            if (name !in dimensions) {
                if (name in names) return null
                names += name; expressions += values[branch][index]
            }
        } }
        builder.push(shared).project(expressions, names, true)
        return builder.build()
    }

    private fun projection(relation: RelNode, basis: RelNode): List<RexNode>? {
        if (relation === basis) return relation.rowType.fieldList.indices.map { relation.cluster.rexBuilder.makeInputRef(relation, it) }
        val expressions = projectionExpressions(relation) ?: return null
        if (!expressions.all { safeExpression(it, true) }) return null
        val inputs = projection(relation.getInput(0), basis) ?: return null
        val substitute = object : RexShuttle() { override fun visitInputRef(reference: RexInputRef): RexNode = inputs[reference.index] }
        return expressions.map { it.accept(substitute) }
    }

    private fun projectionExpressions(relation: RelNode): List<RexNode>? = when (relation) {
        is Project -> relation.projects
        is Calc -> {
            if (relation.program.condition == null) relation.program.projectList.map(relation.program::expandLocalRef) else null
        }
        else -> null
    }

    private fun deterministic(root: RelNode): Boolean {
        val seen = IdentityHashMap<RelNode, Boolean>()
        fun visit(node: RelNode): Boolean {
            seen[node]?.let { return it }
            // 与 SQL 关系复用保持相同的求值边界，不把关联或递归状态当作可共享完成值。
            if (node is Correlate || node is RepeatUnion || node is TableScan && node.table?.unwrap(TransientTable::class.java) != null) return false
            var safe = true
            node.accept(object : RexShuttle() {
                override fun visitCall(call: RexCall): RexNode { safe = safe && safeExpression(call, false); return call }
                override fun visitCorrelVariable(variable: RexCorrelVariable): RexNode { safe = false; return variable }
                override fun visitSubQuery(query: RexSubQuery): RexNode { safe = false; return query }
            })
            return (safe && node.inputs.all(::visit)).also { seen[node] = it }
        }
        return visit(root)
    }

    private fun safeExpression(expression: RexNode, projection: Boolean): Boolean {
        var safe = true
        expression.accept(object : RexVisitorImpl<Void?>(true) {
            override fun visitCall(call: RexCall): Void? {
                safe = safe && call.operator.isDeterministic && !call.operator.isDynamicFunction && (!projection || !call.operator.isAggregator)
                return super.visitCall(call)
            }
            override fun visitOver(over: RexOver): Void? { if (projection) safe = false; return super.visitOver(over) }
            override fun visitCorrelVariable(variable: RexCorrelVariable): Void? { safe = false; return null }
            override fun visitSubQuery(query: RexSubQuery): Void? { safe = false; return null }
        })
        return safe
    }

    fun set(builder: RelBuilder, operation: Calculation.SetKind, all: Boolean, operands: List<RelNode>, slots: List<SetOutputContract>): RelNode {
        require(operands.size >= 2) { "set combination requires at least two completed inputs" }
        slots.indices.forEach { column ->
            val types = operands.map { it.rowType.fieldList[column].type }
            val result = builder.typeFactory.leastRestrictive(types)
            require(result != null && types.all { SemanticNativeTypes.preservesCapacity(it, result) }) { "set slot requires explicit lossless CAST of its inputs: ${slots[column]}" }
        }
        operands.forEach(builder::push)
        when (operation) {
            Calculation.SetKind.UNION -> builder.union(all, operands.size)
            Calculation.SetKind.INTERSECT -> builder.intersect(all, operands.size)
            Calculation.SetKind.EXCEPT -> builder.minus(all, operands.size)
        }
        return builder.build()
    }

    fun combineLevels(builder: RelBuilder, variants: List<RelNode>, levels: List<GroupingLevels.BoundLevel>, dimensions: List<String>, levelAlias: String, measures: List<String>): RelNode {
        val types = dimensions.associateWith { dimension ->
            val candidates = variants.filter { dimension in it.rowType.fieldNames }.map { requireNotNull(it.rowType.getField(dimension, true, false)).type }
            val common = builder.typeFactory.leastRestrictive(candidates)
            require(common != null && candidates.all { SemanticNativeTypes.preservesCapacity(it, common) }) { "layer dimension has no lossless common native type" }
            common
        }
        val completed = variants.mapIndexed { index, variant ->
            builder.push(variant)
            val fields = dimensions.map { if (it in variant.rowType.fieldNames) builder.field(it) else builder.rexBuilder.makeNullLiteral(types.getValue(it)) }.toMutableList<RexNode>()
            val names = dimensions.toMutableList()
            fields.add(builder.rexBuilder.makeBigintLiteral(BigDecimal.valueOf(levels[index].code)))
            names.add(levelAlias)
            measures.forEach { fields.add(builder.field(it)); names.add(it) }
            builder.project(fields, names, true).build()
        }
        completed.forEach(builder::push)
        if (completed.size > 1) builder.union(true, completed.size)
        return builder.build()
    }

    /** 单一已聚合人口直接保留；多个输入由去重并集决定输出组。 */
    fun align(b: RelBuilder, branches: List<RelNode>, population: List<RelNode>, dimensions: List<String>): RelNode {
        if (branches.size == 1) return branches.first()
        val seen = IdentityHashMap<RelNode, Boolean>()
        val selected = population.filter { seen.put(it, true) == null }
        val populationBranch = selected.singleOrNull()
        if (dimensions.isEmpty()) {
            if (populationBranch != null) b.push(populationBranch) else {
                val occupied = branches.flatMapTo(linkedSetOf()) { it.rowType.fieldNames }
                val marker = SemanticNativeTypes.internalAlias(occupied, "__population_")
                selected.forEach { b.push(it).project(listOf(b.literal(true)), listOf(marker), true) }
                b.union(false, selected.size)
            }
            branches.filter { it !== populationBranch }.forEach { b.push(it).join(JoinRelType.LEFT, b.literal(true)) }
            return b.build()
        }
        val outputs = dimensions.toCollection(linkedSetOf())
        if (populationBranch != null) { b.push(populationBranch); outputs.addAll(populationBranch.rowType.fieldNames) }
        else {
            selected.forEach { b.push(it).project(dimensions.map(b::field), dimensions, true) }
            b.union(false, selected.size)
        }
        for (branch in branches) {
            if (branch === populationBranch) continue
            b.push(branch)
            val equality = dimensions.map { b.call(Ops.IS_NOT_DISTINCT_FROM, b.field(2, 0, it), b.field(2, 1, it)) }
            val projection = outputs.map { b.field(2, 0, it).index }.toMutableList()
            branch.rowType.fieldNames.filterNot { it in dimensions }.forEach {
                check(outputs.add(it)) { "duplicate branch metric output: $it" }
                projection.add(b.field(2, 1, it).index)
            }
            b.join(JoinRelType.LEFT, b.and(equality))
            // 外连接后再取引用，采用右侧已补空的真实行类型。
            b.project(projection.map(b::field), outputs.toList(), true)
        }
        val ordered = dimensions + branches.flatMap { it.rowType.fieldNames.filterNot(dimensions::contains) }
        return b.project(ordered.map(b::field), ordered, true).build()
    }

    fun alignInputs(builder: RelBuilder, root: RelNode, primary: Map<String, ScopedInputContract.DimensionRef>,
        completed: Map<String, RelNode>, grains: Map<String, Map<String, ScopedInputContract.DimensionRef>>,
        population: RowPopulationContract.Selection?): RelNode {
        val aliases = primary.keys.toList()
        val grain = primary.values.toList()
        var current = root
        if (population?.mode == "SCOPED_UNION") {
            val inputIds = requireNotNull(population.inputIds) { "SCOPED_UNION requires its declared inputs" }
            builder.push(current).project(aliases.map(builder::field), aliases, true)
            inputIds.forEach { id ->
                val inputGrain = grains.getValue(id)
                builder.push(completed.getValue(id))
                val keys = grain.map { ref -> inputGrain.entries.first { it.value == ref }.key }.map(builder::field)
                builder.project(keys, aliases, true)
            }
            builder.union(false, inputIds.size + 1).push(current)
            val keys = aliases.map { builder.call(Ops.IS_NOT_DISTINCT_FROM, builder.field(2, 0, it), builder.field(2, 1, it)) }
            builder.join(JoinRelType.LEFT, builder.and(keys))
            val names = current.rowType.fieldNames
            val values = names.mapIndexed { index, name -> aliases.indexOf(name).let { dimension -> builder.field(if (dimension >= 0) dimension else aliases.size + index) } }
            current = builder.project(values, names, true).build()
        }
        completed.forEach { (id, right) ->
            val inputGrain = grains.getValue(id)
            val names = right.rowType.fieldNames
            builder.push(current).push(right)
            val keys = inputGrain.map { (name, ref) -> builder.call(Ops.IS_NOT_DISTINCT_FROM,
                builder.field(2, 0, aliases[grain.indexOf(ref)]), builder.field(2, 1, name)) }
            builder.join(JoinRelType.LEFT, builder.and(keys))
            val outputNames = current.rowType.fieldNames.toMutableList()
            val leftCount = current.rowType.fieldCount
            val projection = (0 until leftCount).map(builder::field).toMutableList<RexNode>()
            names.forEachIndexed { index, name ->
                if (name !in inputGrain) { projection.add(builder.field(leftCount + index)); outputNames.add(name) }
            }
            current = builder.project(projection, outputNames, true).build()
        }
        return current
    }
}
