package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.expression.*
import com.olap.prototype.query.*
import com.olap.prototype.query.QueryStageAnalysis.Analysis
import com.olap.prototype.query.QueryStageAnalysis.ScopeId
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rex.RexNode
import org.apache.calcite.tools.RelBuilder

/** 绑定根发射关系；完成作用域与导入仍由调用方调度。 */
internal class InputRootCompiler(
    private val builder: RelBuilder,
    private val analyses: Map<ScopeId, Analysis>,
    private val parameters: MutableList<JdbcParameter>,
    private val relationChecks: MutableList<RelationalCheck>,
    private val facts: FactRelationCompiler,
    private val sharedFacts: Map<ExecutableMetricInput, RelNode>,
    private val completed: CompletedInput,
) {
    fun interface CompletedInput { fun relation(scope: ScopeId, display: MutableMap<String, String>): RelNode }
    private fun relation(scope: ScopeId, display: MutableMap<String, String>) = completed.relation(scope, display)

    fun compile(input: ExecutableInputScope, scope: ScopeId, display: MutableMap<String, String>): RelNode = when (val root = input.root) {
        is ExecutableInputRoot.Measures -> measureRelation(input, scope, display)
        is ExecutableInputRoot.Members -> memberRelation(input, root.input, display)
        is ExecutableInputRoot.Input -> rootInput(input, scope, root.inputId, display)
        is ExecutableInputRoot.Relation -> relationInput(root.input, display)
        is ExecutableInputRoot.ObjectMeasure -> objectMeasure(input, scope, root, display)
        is ExecutableInputRoot.ObservationStatistics -> observationStatistics(input, scope, root, display)
        is ExecutableInputRoot.AlignMeasurements -> alignedMeasurements(input, scope, root, display)
        is ExecutableInputRoot.SetInputs -> setRelation(input, scope, root, display)
    }

    private fun rootInput(owner: ExecutableInputScope, scope: ScopeId, id: String, display: MutableMap<String, String>): RelNode {
        val declaration = requireNotNull(owner.additionalInputs[id]) { "root input is not declared" }
        require(declaration.inputMode == "ROOT") { "root input must declare ROOT mode" }
        return relation(scope.child(id), display)
    }
    private fun relationInput(input: ExecutableRelationInput, display: MutableMap<String, String>): RelNode {
        val originalDisplay = linkedMapOf<String, String>()
        builder.push(relation(input.inputScope, originalDisplay))
        val projection = input.projection
        val names = projection.outputs.map { it.name }
        names.forEach { name -> display[name] = originalDisplay[projection.outputSlots.getValue(name)] ?: name }
        builder.project(projection.outputs.map { output -> builder.field(projection.outputSlots.getValue(output.name)).also { SemanticNativeTypes.requireSetType(it.type, output.shape) } }, names, true)
        return builder.build()
    }
    private fun objects(restriction: ExecutableObjectRestriction) = relation(restriction.objectsScope, linkedMapOf())

    private fun memberRelation(input: ExecutableInputScope, member: ExecutableMemberInput, display: MutableMap<String, String>): RelNode {
        if (member is ExecutableMemberInput.Projection)
            return MemberRelationCompiler.projection(facts.sourceRows, builder, member, display, parameters, input.objectRestrictions, ::objects)
        val bodyScope = when (member) {
            is ExecutableMemberInput.Computed -> member.computationScope
            is ExecutableMemberInput.Local -> member.inputScope
            is ExecutableMemberInput.Projection -> error("projection handled above")
        }
        val body = relation(bodyScope, linkedMapOf())
        val analysis = analyses.getValue(bodyScope)
        val key = (analysis.key as? QueryStageAnalysis.UniqueKey)?.let { unique ->
            analysis.outputs.filter { it.id in unique.columns }.map { it.name }
        } ?: emptyList()
        return MemberRelationCompiler.completed(builder, member, body, key, display, parameters, input.objectRestrictions, ::objects)
    }

    private fun objectMeasure(input: ExecutableInputScope, scope: ScopeId, root: ExecutableInputRoot.ObjectMeasure, display: MutableMap<String, String>): RelNode {
        val population = rootInput(input, scope, "objects", linkedMapOf())
        root.roles.forEach { RelationalIdentity.checkKnown(builder, population, it.objectKeys, "OBSERVATION_OBJECT_IDENTITY_NULL", relationChecks, scope.child("objects")) }
        return rootInput(input, scope, "measurement", display)
    }
    private fun observationStatistics(input: ExecutableInputScope, scope: ScopeId, root: ExecutableInputRoot.ObservationStatistics, display: MutableMap<String, String>): RelNode {
        val observations = rootInput(input, scope, "observations", display)
        (root.unit as? Calculation.ObjectsBy)?.let { RelationalIdentity.checkKnown(builder, observations, it.keys, "OBSERVATION_OBJECT_IDENTITY_NULL", relationChecks, scope.child("observations")) }
        return observations
    }
    private fun alignedMeasurements(input: ExecutableInputScope, scope: ScopeId, root: ExecutableInputRoot.AlignMeasurements, display: MutableMap<String, String>): RelNode {
        val alignment = ObservationAlignmentCompiler(builder, root, relationChecks, scope)
        root.keys.forEach { (id, _) -> alignment.input(id, rootInput(input, scope, id, linkedMapOf())) }
        return alignment.compile(display)
    }
    private fun setRelation(input: ExecutableInputScope, scope: ScopeId, root: ExecutableInputRoot.SetInputs, display: MutableMap<String, String>): RelNode {
        val operands = root.inputs.map { projection ->
            builder.push(rootInput(input, scope, projection.inputId, linkedMapOf()))
            val values = projection.symbols.mapIndexed { index, symbol ->
                builder.field(symbol).also { SemanticNativeTypes.requireSetType(it.type, root.outputSlots[index].shape) }
            }
            builder.project(values, root.outputSlots.map { it.name }, true)
            builder.build()
        }
        root.outputSlots.forEach { display[it.name] = it.name }
        return CompletedRelations.set(builder, root.operation, root.all, operands, root.outputSlots)
    }

    fun filterOutputs(root: ExecutableInputRoot.Measures) { builder.filter(outputCondition(root, root.outputCondition)) }
    private fun outputCondition(root: ExecutableInputRoot.Measures, condition: DimensionCondition): RexNode = when (condition) {
        is DimensionCondition.Unrestricted -> builder.literal(true)
        is DimensionCondition.Exists -> throw IllegalArgumentException("EXISTS cannot consume completed grouped outputs")
        is DimensionCondition.All -> builder.and(condition.operands.map { outputCondition(root, it) })
        is DimensionCondition.Any -> builder.or(condition.operands.map { outputCondition(root, it) })
        is DimensionCondition.Not -> builder.not(outputCondition(root, condition.operand))
        is DimensionCondition.Predicate -> {
            val alias = root.grouping.entries.firstOrNull { it.value == condition.dimension }?.key
                ?: throw IllegalArgumentException("output condition dimension is not retained in completed results")
            val field = builder.field(alias)
            val values = condition.values.map { SemanticCalciteRexCompiler.parameter(builder, parameters, SemanticNativeTypes.scalarParameterType(builder, it), it.jdbcValue()) }
            SemanticCalciteOperators.predicate(builder, field, condition.operator, values)
        }
    }

    private data class Calls(val relations: List<RelNode>, val calls: Map<String, RelNode>)
    private fun measureRelation(input: ExecutableInputScope, scope: ScopeId, display: MutableMap<String, String>): RelNode {
        val root = input.measures()
        RowPopulationContract.requireSelected(root.population, input.metricCalls.map { it.invocation.callId }.toSet())
        val dimensions = root.grouping.keys.toMutableList().apply { root.groupingLevels?.let { add(it.outputAlias) } }
        facts.requireAlignment(input.metrics, root.grouping.keys.toList())
        val factCalls = factCalls(input)
        val calculationCalls = calculationCalls(input, scope)
        val calls = factCalls.calls + calculationCalls.calls
        val branches = factCalls.relations + calculationCalls.relations
        require(branches.isNotEmpty()) { "measure selection requires completed metric instances" }
        val population = root.population.callIds.map { requireNotNull(calls[it]) { "completed population instance is missing" } }
        builder.push(CompletedRelations.align(builder, branches, population, dimensions))
        return measureOutputs(input, dimensions, display)
    }
    private fun factCalls(input: ExecutableInputScope): Calls {
        val branches = mutableListOf<RelNode>()
        val calls = linkedMapOf<String, RelNode>()
        facts.aggregateInputs(input.metrics.filterNot(sharedFacts::containsKey)).forEach { group ->
            val relation = facts.branch(builder, group, parameters) { objects(it) }
            branches += relation
            group.forEach { calls[it.callId] = relation }
        }
        input.metrics.forEach { metric -> sharedFacts[metric]?.let { branches += it; calls[metric.callId] = it } }
        return Calls(branches.toList(), calls.toMap())
    }
    private fun calculationCalls(input: ExecutableInputScope, scope: ScopeId): Calls {
        val branches = mutableListOf<RelNode>()
        val calls = linkedMapOf<String, RelNode>()
        val projections = linkedMapOf<ScopeId, MutableList<RelNode>>()
        input.metricCalls.filter { it.calculations().isNotEmpty() }.forEach { call ->
            val levels = mutableListOf<GroupingLevels.BoundLevel>()
            val variants = call.calculationScopes(scope).map { (child, calculation) ->
                val grouping = input.measures().grouping.toMutableMap()
                if (call is ExecutableMetricCall.LevelCalculation) {
                    val level = call.levels.first { it.calculation === calculation }.level
                    grouping.keys.retainAll(level.groupBy)
                    levels += level
                }
                calculationRelation(calculation, child, grouping)
            }
            val relation = if (call is ExecutableMetricCall.LevelCalculation)
                CompletedRelations.combineLevels(builder, variants, levels, input.measures().grouping.keys.toList(), call.groupingLevels.outputAlias, listOf(call.invocation.outputAlias))
            else variants.first()
            branches += relation
            calls[call.invocation.callId] = relation
            if (call is ExecutableMetricCall.Calculation) (call.input.root as? ExecutableInputRoot.Relation)?.let { root ->
                projections.getOrPut(root.input.inputScope) { mutableListOf() }.add(relation)
            }
        }
        projections.forEach { (source, group) ->
            if (group.size < 2) return@forEach
            // outputSlots 已编译为实际投影；合并仍要求它们回到这个精确完成位置及同一完整键。
            val merged = CompletedRelations.combineProjections(builder, relation(source, linkedMapOf()), group, input.measures().grouping.keys.toList()) ?: return@forEach
            val first = branches.indexOfFirst { branch -> group.any { it === branch } }
            branches.removeAll { branch -> group.any { it === branch } }
            branches.add(first, merged)
            calls.entries.forEach { if (group.any { branch -> branch === it.value }) it.setValue(merged) }
        }
        return Calls(branches.toList(), calls.toMap())
    }
    private fun measureOutputs(input: ExecutableInputScope, dimensions: List<String>, display: MutableMap<String, String>): RelNode {
        val projection = dimensions.mapTo(mutableListOf<RexNode>()) { builder.field(it) }
        val names = dimensions.toMutableList()
        dimensions.forEach { display[it] = it }
        input.metricCalls.forEach { call ->
            val alias = call.invocation.outputAlias
            require(alias !in names) { "conflicting invocation output alias: $alias" }
            projection += builder.field(alias); names += alias; display[alias] = call.invocation.displayName
        }
        builder.project(projection, names, true)
        return builder.build()
    }
    private fun calculationRelation(calculation: ExecutableMetricCall.Calculation, child: ScopeId, grouping: Map<String, ScopedInputContract.DimensionRef>): RelNode {
        val grain = analyses.getValue(child).alignmentGrain()
        require(grain.values.toSet() == grouping.values.toSet()) { "calculation output grain differs from its explicit layer grouping: ${calculation.invocation.callId}" }
        builder.push(relation(child, linkedMapOf()))
        val projection = grouping.values.mapTo(mutableListOf<RexNode>()) { dimension -> builder.field(grain.entries.first { it.value == dimension }.key) }
        projection += SemanticNumericRex.output(builder, builder.field(calculation.measureOutput), calculation.invocation.metric.definition.valueContract.adopted(calculation.invocation.bindings))
        builder.project(projection, grouping.keys.toList() + calculation.invocation.outputAlias, true)
        return builder.build()
    }
}
