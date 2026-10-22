package com.olap.prototype.expression

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.query.GroupingLevels
import com.olap.prototype.query.RowPopulationContract
import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.query.SetOutputContract
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.JoinRelType
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.tools.RelBuilder
import java.math.BigDecimal

/** 完成关系的集合、分层及人口对齐，保留bag、NULL和字段容量。 */
object CompletedRelations {
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
        val populationBranch = population.singleOrNull()
        if (dimensions.isEmpty()) {
            if (populationBranch != null) b.push(populationBranch) else {
                val occupied = branches.flatMapTo(linkedSetOf()) { it.rowType.fieldNames }
                val marker = SemanticNativeTypes.internalAlias(occupied, "__population_")
                population.forEach { b.push(it).project(listOf(b.literal(true)), listOf(marker), true) }
                b.union(false, population.size)
            }
            branches.filter { it !== populationBranch }.forEach { b.push(it).join(JoinRelType.LEFT, b.literal(true)) }
            return b.build()
        }
        val outputs = dimensions.toCollection(linkedSetOf())
        if (populationBranch != null) { b.push(populationBranch); outputs.addAll(populationBranch.rowType.fieldNames) }
        else {
            population.forEach { b.push(it).project(dimensions.map(b::field), dimensions, true) }
            b.union(false, population.size)
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
