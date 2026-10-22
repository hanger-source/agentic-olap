package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.expression.*
import com.olap.prototype.member.*
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.QueryStageAnalysis
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.time.TemporalWindow
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable
import org.apache.calcite.tools.RelBuilder
import org.apache.calcite.util.ImmutableBitSet

/** 来源投影证明身份和属性；完成关系在其实际粒度上执行成员选择。 */
internal object MemberRelationCompiler {
    fun projection(sources: SourceRelationCompiler, builder: RelBuilder, input: ExecutableMemberInput.Projection,
        display: MutableMap<String, String>, parameters: MutableList<JdbcParameter>,
        restrictions: List<ExecutableObjectRestriction>, objects: (ExecutableObjectRestriction) -> RelNode): RelNode {
        val rows = sources.eventRows(builder, input, parameters)
        rows.filter(listOf(condition(builder, rows, input, input.condition, parameters)))
        restrictions.forEach { restriction -> SourceRelationCompiler.qualifyObjects(builder, objects(restriction), restriction.objectKeys,
            restriction.recordSlots.map { memberField(builder, rows, input, it) }) }
        val slots = input.usedSlots.toList()
        rows.project(slots.map { memberField(builder, rows, input, it) }, slots)
        formProjectedMembers(builder, input, slots)
        val source = input.memberSource
        builder.project(input.outputs.map { (alias, slot) -> SemanticNumericRex.output(builder, builder.field(slot), input.valueContracts[alias]) }, input.outputs.keys.toList(), true)
        input.outputs.forEach { (alias, slot) -> display[alias] = source.definition.result.outputs.getValue(slot).displayName }
        return builder.build()
    }
    private fun formProjectedMembers(builder: RelBuilder, input: ExecutableMemberInput.Projection, slots: List<String>) {
        val source = input.memberSource
        val declaration = source.definition.result
        val keys = ImmutableBitSet.of(declaration.identitySlots.map(slots::indexOf))
        val relation = builder.peek()
        val metadata = relation.cluster.metadataQuery
        if (declaration.shape == MemberShape.RECORDS)
            require(metadata.areColumnsUnique(relation, keys, false) == true) { "member record identity is not proved by the adopted source keys: ${source.id}" }
        if (declaration.shape == MemberShape.UNIQUE_MEMBERS) {
            val outputs = ImmutableBitSet.of(input.outputs.values.map(slots::indexOf))
            require(metadata.determinesSet(relation, keys, outputs) == true) { "member attributes require a source identity that determines them: ${source.id}" }
            val retained = (declaration.identitySlots + input.outputs.values).distinct()
            builder.project(retained.map(builder::field), retained, true)
            builder.aggregate(builder.groupKey(*retained.toTypedArray()), emptyList<RelBuilder.AggCall>())
        }
    }
    private fun memberField(builder: RelBuilder, rows: SourceRelationCompiler.Rows, input: ExecutableMemberInput.Projection, slot: String): RexNode {
        val source = input.memberSource
        val expression = requireNotNull(source.outputExpressions[slot]) { "approved member expression is missing" }
        val value = rows.compile(expression, rows.source(input).memberOutputs.getValue(slot))
        val declaration = source.definition.result.outputs.getValue(slot)
        if (declaration is MemberSourceDefinition.ScalarOutput) SemanticNativeTypes.requireScalarType(value.type, declaration.valueType)
        else SemanticNativeTypes.requireSetType(value.type, MemberSourceDefinition.containerShape(declaration))
        val numericUnit = source.definition.result.outputs.values.mapNotNull(MemberSourceDefinition::rootValueContract).any { contract ->
            (contract.unit as? MetricValueContract.SlotDimension)?.let { it.slot == slot && it.axis.memberType == ScalarValueType.NUMBER } == true
        }
        if (numericUnit) SemanticNumericRex.requireExactMemberCode(value)
        return SemanticNumericRex.output(builder, value, MemberSourceDefinition.rootValueContract(declaration))
    }
    fun condition(builder: RelBuilder, rows: SourceRelationCompiler.Rows, input: ExecutableMemberInput.Projection,
        condition: MemberCondition, parameters: MutableList<JdbcParameter>): RexNode =
        condition(builder, { memberField(builder, rows, input, it) }, condition, parameters)

    fun condition(builder: RelBuilder, fields: (String) -> RexNode, condition: MemberCondition, parameters: MutableList<JdbcParameter>): RexNode = when (condition) {
        is MemberCondition.Unrestricted -> builder.literal(true)
        is MemberCondition.All -> builder.and(condition.operands.map { condition(builder, fields, it, parameters) })
        is MemberCondition.Any -> builder.or(condition.operands.map { condition(builder, fields, it, parameters) })
        is MemberCondition.Not -> builder.not(condition(builder, fields, condition.operand, parameters))
        is MemberCondition.Predicate -> {
            val field = fields(condition.slot)
            val values = condition.values.map { value ->
                check(value is Calculation.Scalar) { "member scalar argument reached Rel compilation without binding" }
                SemanticNativeTypes.requireScalarType(field.type, value.scalarType)
                SemanticCalciteRexCompiler.parameter(builder, parameters, SemanticNativeTypes.scalarParameterType(builder, value), value.jdbcValue())
            }
            SemanticCalciteOperators.predicate(builder, field, condition.operator, values)
        }
    }

    fun completed(builder: RelBuilder, input: ExecutableMemberInput, completed: RelNode, provedKey: List<String>,
        display: MutableMap<String, String>, parameters: MutableList<JdbcParameter>,
        restrictions: List<ExecutableObjectRestriction>, objects: (ExecutableObjectRestriction) -> RelNode): RelNode {
        builder.push(completed)
        val symbols = completedSymbols(builder, input)
        val raw = when (input) {
            is ExecutableMemberInput.Computed -> input.source.definition.result.shape == MemberShape.RAW_BAG
            is ExecutableMemberInput.Local -> input.formal.shape == MemberShape.RAW_BAG
            is ExecutableMemberInput.Projection -> error("projection is not a completed member input")
        }
        val identity = if (input is ExecutableMemberInput.Computed) input.source.definition.result.identitySlots.map { symbols.getValue(it) } else provedKey
        if (!raw) requireCompletedIdentity(builder, identity)
        val fields: (String) -> RexNode = { builder.field(requireNotNull(symbols[it]) { "member selection references an unavailable completed output" }) }
        restrictions.forEach { restriction -> SourceRelationCompiler.qualifyObjects(builder, objects(restriction), restriction.objectKeys, restriction.recordSlots.map(fields)) }
        builder.filter(condition(builder, fields, input.condition, parameters))
        selectRange(builder, input, fields, parameters)
        builder.project(input.outputs.map { (alias, slot) -> SemanticNumericRex.output(builder, fields(slot), input.valueContracts[alias]) }, input.outputs.keys.toList(), true)
        input.outputs.forEach { (alias, slot) -> display[alias] = if (input is ExecutableMemberInput.Computed) input.source.definition.result.outputs.getValue(slot).displayName else slot }
        return builder.build()
    }
    private fun completedSymbols(builder: RelBuilder, input: ExecutableMemberInput): Map<String, String> = when (input) {
        is ExecutableMemberInput.Computed -> input.source.definition.result.outputs.mapValues { (_, output) ->
            MemberSourceDefinition.completedSymbol(output).also { symbol ->
                if (output is MemberSourceDefinition.ScalarOutput) SemanticNativeTypes.requireScalarType(builder.field(symbol).type, output.valueType)
                else SemanticNativeTypes.requireSetType(builder.field(symbol).type, MemberSourceDefinition.containerShape(output))
            }
        }
        is ExecutableMemberInput.Local -> input.usedSlots.associateWith { it }.also {
            input.outputs.forEach { (alias, symbol) -> when (val meaning = input.meanings[alias]) {
                is QueryStageAnalysis.MemberProperty -> SemanticNativeTypes.requireScalarType(builder.field(symbol).type, meaning.valueType)
                is QueryStageAnalysis.MemberContainer -> SemanticNativeTypes.requireSetType(builder.field(symbol).type, meaning.shape)
                else -> Unit
            } }
        }
        is ExecutableMemberInput.Projection -> error("projection is not a completed member input")
    }
    private fun requireCompletedIdentity(builder: RelBuilder, identity: List<String>) {
        val relation = builder.peek()
        val indexes = identity.map(relation.rowType.fieldNames::indexOf)
        require(indexes.none { it < 0 } && relation.cluster.metadataQuery.areColumnsUnique(relation, ImmutableBitSet.of(indexes), false) == true) {
            "member identity is not proved by the actual completed native relation"
        }
    }
    private fun selectRange(builder: RelBuilder, input: ExecutableMemberInput, fields: (String) -> RexNode, parameters: MutableList<JdbcParameter>) {
        val window = input.range ?: return
        val computed = input as ExecutableMemberInput.Computed
        val time = fields(requireNotNull(computed.source.definition.result.timeSlot))
        SemanticNativeTypes.requireTemporalType(time.type, window.valueType)
        window.start?.let { builder.filter(builder.call(SqlStdOperatorTable.GREATER_THAN_OR_EQUAL, time, temporalParameter(builder, parameters, window, it))) }
        window.end?.let { builder.filter(builder.call(SqlStdOperatorTable.LESS_THAN, time, temporalParameter(builder, parameters, window, it))) }
    }
    private fun temporalParameter(builder: RelBuilder, parameters: MutableList<JdbcParameter>, window: TemporalWindow, text: String): RexNode {
        val value = Calculation.Scalar(ScalarValueType.valueOf(window.valueType.name), text)
        return SemanticCalciteRexCompiler.parameter(builder, parameters, SemanticNativeTypes.scalarParameterType(builder, value), value.jdbcValue())
    }
}
