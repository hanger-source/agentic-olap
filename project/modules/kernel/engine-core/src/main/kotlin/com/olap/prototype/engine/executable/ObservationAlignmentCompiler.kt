package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.expression.RelationalCheck
import com.olap.prototype.expression.RelationalIdentity
import com.olap.prototype.expression.SemanticCalciteOperators
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.JoinRelType
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable
import org.apache.calcite.sql.type.SqlTypeUtil
import org.apache.calcite.tools.RelBuilder

/** 一次完整观察对齐，局部输入在规范身份后才参与总体和缺席策略。 */
internal class ObservationAlignmentCompiler(private val builder: RelBuilder,
    private val aligned: ExecutableInputRoot.AlignMeasurements, private val checks: MutableList<RelationalCheck>,
    private val scope: com.olap.prototype.query.QueryStageAnalysis.ScopeId) {
    private val inputs = linkedMapOf<String, RelNode>()
    fun input(id: String, relation: RelNode) {
        val keys = requireNotNull(aligned.keys[id]) { "undeclared measurement input" }.values.toList()
        RelationalIdentity.requireUnique(relation, keys, "completed observation")
        RelationalIdentity.checkKnown(builder, relation, keys, "OBSERVATION_OBJECT_IDENTITY_NULL", checks, scope.child(id))
        require(inputs.putIfAbsent(id, relation) == null) { "measurement input is already attached" }
    }
    fun compile(display: MutableMap<String, String>): RelNode {
        check(inputs.keys == aligned.keys.keys) { "all declared observation inputs must be attached before alignment" }
        val normalized = normalize(display)
        val populationInput = aligned.population.inputIds.singleOrNull()
        // 单元素人口就是已核验的完成关系本身；其值不必从键集合再关联回来。
        var result = if (populationInput != null) {
            builder.push(normalized.getValue(populationInput).relation)
            val names = aligned.observationKeys + aligned.values.getValue(populationInput).map { it.outputAlias }
            builder.project(names.map(builder::field), names, true)
            builder.build()
        } else population(normalized.mapValues { it.value.relation })
        normalized.forEach { (id, measurement) ->
            if (id != populationInput) result = attach(result, id, measurement)
        }
        builder.push(result)
        val outputs = aligned.observationKeys + aligned.values.values.flatten().map { it.outputAlias }
        builder.project(outputs.map(builder::field), outputs, true)
        return builder.build().also { RelationalIdentity.requireUnique(it, aligned.observationKeys, "completed observation") }
    }
    private data class Measurement(val relation: RelNode, val marker: String)
    private fun normalize(display: MutableMap<String, String>): Map<String, Measurement> = inputs.entries.mapIndexed { index, (id, relation) ->
        builder.push(relation)
        val expressions = mutableListOf<RexNode>()
        val names = mutableListOf<String>()
        aligned.observationKeys.forEach { key ->
            expressions += builder.field(aligned.keys.getValue(id).getValue(key)); names += key; display[key] = key
        }
        aligned.values.getValue(id).forEach { value ->
            val field = builder.field(value.symbol)
            if (value.noMatch == Calculation.NoMatch.ZERO_WHEN_ABSENT)
                require(SqlTypeUtil.isNumeric(field.type)) { "absent measurement zero requires an actual numeric result" }
            expressions += field; names += value.outputAlias; display[value.outputAlias] = value.outputAlias
        }
        var marker = "__alignment_presence_$index"
        while (marker in names) marker += "_"
        expressions += builder.literal(true); names += marker
        builder.project(expressions, names, true)
        id to Measurement(builder.build(), marker)
    }.toMap(LinkedHashMap())

    private fun population(normalized: Map<String, RelNode>): RelNode {
        val populations = aligned.population.inputIds.map { id ->
            builder.push(normalized.getValue(id))
            if (aligned.observationKeys.isEmpty()) builder.project(listOf(builder.literal(true)), listOf("__scalar_population"), true)
            else builder.project(aligned.observationKeys.map(builder::field), aligned.observationKeys, true)
            builder.distinct(); builder.build()
        }
        populations.forEach(builder::push)
        if (populations.size > 1) {
            if (aligned.population.kind == Calculation.PopulationKind.INTERSECTION) builder.intersect(false, populations.size)
            else { builder.union(true, populations.size); builder.distinct() }
        }
        return builder.build()
    }
    private fun attach(result: RelNode, id: String, measurement: Measurement): RelNode {
        val leftNames = result.rowType.fieldNames
        builder.push(result); builder.push(measurement.relation)
        val equalities = aligned.observationKeys.flatMap { name ->
            val left = builder.field(2, 0, name)
            val right = builder.field(2, 1, name)
            require(SqlTypeUtil.equalSansNullability(builder.typeFactory, left.type, right.type)) { "aligned semantic identities have different native types" }
            listOf(SemanticCalciteOperators.codeEquals(builder, left, right), builder.equals(left, right))
        }
        builder.join(JoinRelType.LEFT, builder.and(equalities))
        val expressions = leftNames.indices.mapTo(mutableListOf<RexNode>()) { builder.field(it) }
        val names = leftNames.toMutableList()
        val rightNames = measurement.relation.rowType.fieldNames
        val presence = builder.field(leftNames.size + rightNames.indexOf(measurement.marker))
        aligned.values.getValue(id).forEach { value ->
            var field: RexNode = builder.field(leftNames.size + rightNames.indexOf(value.outputAlias))
            if (value.noMatch == Calculation.NoMatch.ZERO_WHEN_ABSENT) {
                field = builder.call(SqlStdOperatorTable.CASE, builder.isNull(presence), builder.rexBuilder.makeZeroLiteral(field.type), field)
            }
            expressions += field; names += value.outputAlias
        }
        builder.project(expressions, names, true)
        return builder.build()
    }
}
