package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.*
import com.olap.prototype.engine.catalog.ExecutableCatalog
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.member.MemberSource
import com.olap.prototype.query.QueryStageAnalysis.ScopeId

/** 来源成员与形参采用的规则，不持调用完成缓存。 */
internal class MemberSources(private val session: ExecutableBindingSession, private val catalog: ExecutableCatalog) {
    fun bindSource(source: MemberSource, selection: MemberInvocationBinding.Bound, suppliedMembers: Map<String, CalculationBindings.Member>, suppliedRelations: Map<String, CalculationBindings.Relation>,
        caller: MemberEnvironment, callId: String, depth: Int, stack: MutableSet<String>): ExecutableMemberInput {
        val scope = caller.memberOwnerScope
        return when (source) {
            is MemberSource.Projection -> { require(suppliedMembers.isEmpty() && suppliedRelations.isEmpty()) { "event projections do not declare member or relation parameters" }; projectionMember(source, selection, callId, scope) }
            is MemberSource.Computed -> {
                val bodyScope = scope.member(callId)
                val body = session.bindMemberCalculation(source, selection, suppliedMembers, suppliedRelations, caller, bodyScope, depth, stack)
                ExecutableMemberInput.Computed(callId, scope, source, selection, body, bodyScope)
            }
        }
    }
    fun projectionMember(source: MemberSource.Projection, selection: MemberInvocationBinding.Bound, callId: String, scope: ScopeId): ExecutableMemberInput.Projection {
        val ref = source.definition.event
        val event = requireNotNull(catalog.events["${ref.eventId}@${ref.eventVersion}"]) { "member event is missing" }
        val topology = requireNotNull(catalog.topologies["${event.topologyId}@${event.topologyVersion}"]) { "member topology is missing" }
        return ExecutableMemberInput.Projection(callId, scope, source, event, topology, selection)
    }
    fun bindSourceMember(root: Calculation.Members, parent: CalculationBinding, outer: ScopeAssembly, scope: ScopeId, depth: Int, stack: MutableSet<String>): ExecutableMemberInput {
        val source = catalog.requireMemberSource((root.target as Calculation.MemberSource).source)
        val selection = MemberInvocationBinding.sourceSelection(root, source, parent.definition, parent.bindings, parent.arguments,
            { catalog.memberDimensionMappings(source, it) }, catalog::memberDimensionMapping)
        val supplied = CalculationInstantiation.resolveMembers(root.members, parent.bindings, parent.arguments)
        return bindSource(source, selection, supplied, root.relations, MemberEnvironment(outer.callerInputs(), scope, scope), root.callId, depth, stack)
    }
    fun bindFormal(calculation: Calculation, bindings: CalculationBindings, arguments: Map<String, Calculation.Value>, slot: String,
        caller: MemberEnvironment, scope: ScopeId, depth: Int, stack: MutableSet<String>): ExecutableMemberInput {
        val formal = calculation.memberSlots.first { it.name == slot }
        val binding = requireNotNull(bindings.members[slot]) { "member slot is not bound" }
        if (binding is CalculationBindings.LocalRef) return LocalMemberBinding.bind(formal, bindings, binding,
            caller.input(binding), caller.inputScope(binding), scope, slot)
        require(binding is CalculationBindings.SourceCall) { "member parameter must have an explicit actual input" }
        val full = Calculation.Members(slot, Calculation.MemberParameter(slot), formal.outputs.keys.map { Calculation.MemberProjection(it, it, null) }, null, emptyMap(), MemberCondition.Unrestricted(), emptyMap(), emptyMap(), emptyMap())
        val source = catalog.requireMemberSource(binding.source)
        val selection = MemberInvocationBinding.bind(calculation, full, bindings, arguments, source, catalog::memberDimensionMapping)
        return bindSource(source, selection, CalculationInstantiation.resolveMembers(binding.members, bindings, arguments), binding.relations, caller, slot, depth + 1, stack)
    }
    fun select(invocation: CalculationBinding, root: Calculation.Members, full: ExecutableMemberInput, scope: ScopeId): ExecutableMemberInput {
        val owner = requireNotNull(invocation.definition) { "member parameter selection requires a parameterized calculation" }
        if (full is ExecutableMemberInput.Local) return LocalMemberBinding.select(owner, invocation.arguments, root, full, scope)
        val call = invocation.bindings.members.getValue((root.target as Calculation.MemberParameter).slot) as CalculationBindings.SourceCall
        val source = catalog.requireMemberSource(call.source)
        val selection = MemberInvocationBinding.bind(owner, root, invocation.bindings, invocation.arguments, source, catalog::memberDimensionMapping)
        return when (full) {
            is ExecutableMemberInput.Computed -> full.copy(callId = root.callId, scope = scope, selection = selection)
            is ExecutableMemberInput.Projection -> projectionMember(source as MemberSource.Projection, selection, root.callId, scope)
            is ExecutableMemberInput.Local -> error("local selection was handled before source lookup")
        }
    }
}
