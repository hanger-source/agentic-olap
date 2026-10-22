package com.olap.prototype.engine.compiler

import com.olap.prototype.engine.executable.*
import com.olap.prototype.query.QueryStageAnalysis
import com.olap.prototype.query.DimensionCondition
import com.olap.prototype.query.RowPopulationContract
import com.olap.prototype.query.TemporalClockBindings
import com.olap.prototype.query.QueryComputation
import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.calculation.Calculation
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.time.TemporalWindow

/** 只投影已经绑定的计算事实；不重新推导人口、粒度或业务条件。 */
data class CompiledScopeExplanation(
    val scope: QueryStageAnalysis.ScopeId,
    val inputMode: String?,
    val root: Root,
    val qualifications: List<ExecutableObjectRestriction>,
    val children: List<Child>,
) {
    data class Child(val kind: ExecutableInputGraph.EdgeKind, val name: String, val scope: QueryStageAnalysis.ScopeId)
    sealed interface Root {
        data class Measures(val rowPopulation: RowPopulationContract.Selection, val calls: List<String>,
            val timeRange: QueryComputation.TimeRange?, val clocks: Map<String, TemporalClockBindings.Value>, val outputCondition: DimensionCondition) : Root
        data class Members(val source: Member) : Root
        data class Input(val inputId: String) : Root
        data class Relation(val inputScope: QueryStageAnalysis.ScopeId, val outputs: Map<String, String>,
            val keys: List<String>?, val requirements: List<ExecutableRelationInput.Requirement>) : Root
        data class ObjectMeasure(val roles: List<Calculation.ObjectRole>) : Root
        data class AlignMeasurements(val keys: Map<String, Map<String, String>>, val observationKeys: List<String>,
            val population: Calculation.AlignmentPopulation) : Root
        data class ObservationStatistics(val unit: Calculation.ObservationUnit, val outerKeys: List<String>) : Root
        data class SetInputs(val operation: Calculation.SetKind, val all: Boolean, val inputs: List<Calculation.SetProjection>) : Root
    }
    data class Member(val origin: MemberOrigin, val outputs: Map<String, String>, val condition: MemberCondition, val range: TemporalWindow?)
    sealed interface MemberOrigin {
        data class Projection(val id: String, val version: Long) : MemberOrigin
        data class Computed(val id: String, val version: Long, val computationScope: QueryStageAnalysis.ScopeId) : MemberOrigin
        data class Local(val inputScope: QueryStageAnalysis.ScopeId) : MemberOrigin
    }

    companion object {
        fun collect(graph: ExecutableInputGraph): List<CompiledScopeExplanation> = graph.scopes.map { (scope, input) ->
            CompiledScopeExplanation(scope, input.inputMode, root(input.root),
                input.objectRestrictions + input.metrics.flatMap { it.objectRestrictions },
                graph.children(scope).map { Child(it.kind, it.name, it.scope) })
        }

        private fun root(root: ExecutableInputRoot): Root = when (root) {
            is ExecutableInputRoot.Measures -> Root.Measures(root.population, root.calls.map { it.invocation.callId }, root.timeRange?.let {
                QueryComputation.TimeRange(ScopedInputContract.DimensionRef(it.timeDimensionId, it.timeDimensionVersion, it.levelCode), it.window())
            }, root.clocks, root.outputCondition)
            is ExecutableInputRoot.Members -> Root.Members(member(root.input))
            is ExecutableInputRoot.Input -> Root.Input(root.inputId)
            is ExecutableInputRoot.Relation -> Root.Relation(root.input.inputScope, root.input.projection.outputSlots, root.input.projection.keys, root.input.requirements)
            is ExecutableInputRoot.ObjectMeasure -> Root.ObjectMeasure(root.roles)
            is ExecutableInputRoot.AlignMeasurements -> Root.AlignMeasurements(root.keys, root.observationKeys, root.population)
            is ExecutableInputRoot.ObservationStatistics -> Root.ObservationStatistics(root.unit, root.outerKeys)
            is ExecutableInputRoot.SetInputs -> Root.SetInputs(root.operation, root.all, root.inputs)
        }

        private fun member(input: ExecutableMemberInput): Member {
            val origin = when (input) {
                is ExecutableMemberInput.Projection -> MemberOrigin.Projection(input.memberSource.id, input.memberSource.version)
                is ExecutableMemberInput.Computed -> MemberOrigin.Computed(input.source.id, input.source.version, input.computationScope)
                is ExecutableMemberInput.Local -> MemberOrigin.Local(input.inputScope)
            }
            return Member(origin, input.outputs, input.condition, input.range)
        }
    }
}
