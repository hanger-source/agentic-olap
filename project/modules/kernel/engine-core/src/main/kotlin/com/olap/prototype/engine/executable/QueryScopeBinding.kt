package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.engine.catalog.ExecutableCatalog
import com.olap.prototype.query.DimensionCondition
import com.olap.prototype.query.QueryComputation
import com.olap.prototype.query.QueryStageAnalysis.ScopeId
import com.olap.prototype.query.ScopedInputContract

/** 作者Query的一个输入作用域。 */
internal class QueryScopeBinding(private val session: ExecutableBindingSession, private val catalog: ExecutableCatalog,
    private val syntax: QueryComputation.Scope, private val scope: ScopeId, private val depth: Int, mode: String?) {
    private val input = ScopeAssembly(mode)
    fun bind(): ExecutableInputScope {
        session.completion.charge(depth)
        syntax.inputs.forEach { (id, child) -> input.inputs[id] = session.bindQueryScope(child.computation, scope.child(id), depth + 1, child.mode) }
        val boundRoot = when (val root = syntax.root) {
            is QueryComputation.Measures -> bindMeasures(root)
            is QueryComputation.Members -> ExecutableInputRoot.Members(session.members.bindPublicMember(root, input, scope, depth, linkedSetOf()))
            is QueryComputation.Reuse -> ExecutableInputRoot.Input(root.inputId)
            is QueryComputation.SetInputs -> ExecutableInputRoot.SetInputs(root.operation, root.all, root.inputs, root.outputSlots)
            is QueryComputation.Compute -> bindCompute(root)
        }
        return session.completion.register(scope, input.complete(boundRoot, syntax.stages), null)
    }
    private fun bindMeasures(root: QueryComputation.Measures): ExecutableInputRoot.Measures {
        val grouping = linkedMapOf<String, ScopedInputContract.DimensionRef>()
        root.grouping.forEach { ref ->
            val dimension = requireNotNull(catalog.analysisDimensions[ExecutableBindingSession.versionKey(ref.id, ref.version)]) { "selected exact dimension is missing" }
            require(grouping.putIfAbsent(dimension.outputAlias, ref) == null) { "query grouping aliases conflict" }
        }
        val range = root.sourceTimeRange?.let { ExecutableTimeRange.from(it.dimension, it.window) }
        val selection = ExecutableMeasureSelection(grouping, emptyMap(), root.groupingLevels?.bind(grouping), range, root.sourceClocks)
        val calls = root.calls.map { bindMeasureCall(root, selection, it) }
        val facts = calls.filterIsInstance<ExecutableMetricCall.Fact>()
        require(facts.isNotEmpty() || range == null && root.sourceClocks.isEmpty() && root.sourceCondition is DimensionCondition.Unrestricted && root.sourceEventConditions.isEmpty()) { "source conditions have no direct fact consumer" }
        root.sourceEventConditions.forEach { condition -> require(facts.any { it.input.event.eventId == condition.eventId && it.input.event.eventVersion == condition.eventVersion }) { "source event condition has no direct fact invocation" } }
        return ExecutableInputRoot.Measures(calls, selection, root.population, root.outputCondition)
    }
    private fun bindMeasureCall(root: QueryComputation.Measures, selection: ExecutableMeasureSelection, declaration: QueryComputation.Invocation): ExecutableMetricCall {
        val call = session.metrics.published(declaration, scope, input, depth + 1)
        val fact = call.metric.definition as? MetricDefinition.FactMeasure
        var filter = declaration.sourceCondition
        if (fact != null) {
            filter = DimensionCondition.and(root.sourceCondition, filter)
            root.sourceEventConditions.filter { it.eventId == fact.event.eventId && it.eventVersion == fact.event.eventVersion.toString() }
                .forEach { filter = DimensionCondition.and(filter, it.condition) }
        }
        return session.metrics.bindLevelCall(call, MeasureSelection(if (fact != null) selection else selection.withoutSourceWindow(),
            filter, null), depth + 1, linkedSetOf())
    }
    private fun bindCompute(root: QueryComputation.Compute): ExecutableInputRoot.Input {
        var id = "computation"
        while (id in input.inputs) id += "_"
        ScopedInputContract.requireId(id)
        input.inputs[id] = session.bindInline(root, input, scope, scope.child(id), depth + 1)
        return ExecutableInputRoot.Input(id)
    }
}
