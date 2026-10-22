package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.CalculationInstantiation
import com.olap.prototype.calculation.MemberInvocationBinding
import com.olap.prototype.query.QueryStageAnalysis.ScopeId
import java.util.Collections
import java.util.IdentityHashMap

/** 请求内的预算、词法完成缓存与资格终结，交付结果后退出生命周期。 */
internal class ExecutableBindingCompletion {
    private val expansions = intArrayOf(0)
    private val scopes = linkedMapOf<ScopeId, ExecutableInputScope>()
    val qualifications = ExecutableObjectQualification(::scope)
    private val completed = IdentityHashMap<ExecutableInputScope, ExecutableInputScope>()
    private val active = Collections.newSetFromMap(IdentityHashMap<ExecutableInputScope, Boolean>())
    fun charge(depth: Int) = CalculationInstantiation.budget(depth, expansions)
    fun scope(id: ScopeId): ExecutableInputScope = requireNotNull(scopes[id]) { "local lexical input has not completed binding" }
    fun register(id: ScopeId, input: ExecutableInputScope, memberUse: ExecutableMemberInput?): ExecutableInputScope {
        val prior = scopes.putIfAbsent(id, input)
        require(prior == null || prior === input) { "one lexical scope has conflicting bound inputs" }
        if (prior == null) qualifications.memberUse(input, memberUse)
        return input
    }
    fun finish(input: ExecutableInputScope): ExecutableInputScope {
        completed[input]?.let { return it }
        require(active.add(input)) { "bound calculation bodies contain a cycle" }
        return try {
            val children = input.additionalInputs.mapValues { finish(it.value) }
            val restrictions = input.objectRestrictions + qualifications.records(input)
            val root = when (val root = input.root) {
                is ExecutableInputRoot.Measures -> root.copy(calls = root.calls.map(::call))
                is ExecutableInputRoot.Members -> ExecutableInputRoot.Members(member(root.input, restrictions.flatMap { it.recordSlots }))
                else -> root
            }
            ExecutableInputScope(input.inputMode, root, restrictions, children, input.postOps).also { completed[input] = it }
        } finally { active.remove(input) }
    }
    private fun call(call: ExecutableMetricCall): ExecutableMetricCall {
        val invocation = call.invocation.copy(members = call.invocation.members.mapValues { member(it.value, emptyList()) })
        return when (call) {
            is ExecutableMetricCall.Fact -> call.input.let { input -> ExecutableMetricCall.Fact(ExecutableMetricInput(invocation, input.selection,
                input.event, input.topology, input.timeAnchor, input.rangeDimension, input.analysisDimensions, input.filterExpression,
                input.relationConditions, input.objectRestrictions + qualifications.facts(input))) }
            is ExecutableMetricCall.Calculation -> call.copy(invocation = invocation, input = finish(call.input))
            is ExecutableMetricCall.LevelCalculation -> call.copy(invocation = invocation,
                levels = call.levels.map { it.copy(calculation = call(it.calculation) as ExecutableMetricCall.Calculation) })
        }
    }
    private fun member(member: ExecutableMemberInput, slots: List<String>): ExecutableMemberInput {
        val selection = qualifiedSelection(member.selection, slots)
        return when (member) {
            is ExecutableMemberInput.Local -> if (selection === member.selection) member else member.copy(selection = selection)
            is ExecutableMemberInput.Computed -> member.copy(selection = selection, computation = finish(member.computation))
            is ExecutableMemberInput.Projection -> ExecutableMemberInput.Projection(member.callId, member.scope, member.memberSource, member.event, member.topology, selection)
        }
    }
    private fun qualifiedSelection(original: MemberInvocationBinding.Bound, slots: List<String>): MemberInvocationBinding.Bound {
        if (original.usedSlots.containsAll(slots)) return original
        return MemberInvocationBinding.Bound(original.outputs, original.meanings, original.valueContracts, original.unitDimensions,
            original.usedSlots + slots, original.condition, original.range, original.clocks, original.sourceArguments)
    }
}
