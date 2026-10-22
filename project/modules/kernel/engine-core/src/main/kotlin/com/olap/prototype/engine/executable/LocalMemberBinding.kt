package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.calculation.CalculationBindings
import com.olap.prototype.calculation.CalculationInstantiation
import com.olap.prototype.calculation.MemberInvocationBinding
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.QueryStageAnalysis
import com.olap.prototype.query.QueryStageAnalysis.ScopeId

/** 完成Local成员的形参映射与本次选择，不解析源、不持完成缓存。 */
internal object LocalMemberBinding {
    fun bind(formal: Calculation.MemberSlot, bindings: CalculationBindings, binding: CalculationBindings.LocalRef,
        actual: ExecutableInputScope, actualScope: ScopeId, scope: ScopeId, callId: String): ExecutableMemberInput.Local {
        require(actual.inputMode == "ROOT") { "local member inputs must explicitly declare ROOT mode" }
        val meanings = linkedMapOf<String, QueryStageAnalysis.MemberMeaning>()
        val contracts = linkedMapOf<String, MetricValueContract?>()
        formal.outputs.forEach { (name, output) ->
            meanings[name] = when (output) {
                is Calculation.MemberDimension -> bindings.dimensions.getValue(output.dimensionSlot).let { QueryStageAnalysis.MappedMemberDimension(it.reference, it.role) }
                is Calculation.MemberContainerProperty -> QueryStageAnalysis.MemberContainer(output.shape, output.role)
                is Calculation.MemberProperty -> QueryStageAnalysis.MemberProperty(output.valueType, output.role)
            }
            contracts[name] = Calculation.rootValueContract(output)?.adopted(bindings)
        }
        val selection = MemberInvocationBinding.Bound(binding.outputSlots, meanings, contracts, emptyMap(), binding.outputSlots.values.toSet(),
            MemberCondition.Unrestricted(), null, emptyMap(), emptyMap())
        return ExecutableMemberInput.Local(callId, scope, actualScope, formal, bindings, selection)
    }
    fun select(calculation: Calculation, arguments: Map<String, Calculation.Value>, root: Calculation.Members,
        full: ExecutableMemberInput.Local, scope: ScopeId): ExecutableMemberInput.Local {
        require(root.range == null && root.clocks.isEmpty()) { "a local completed member input has no declared source clock or range slot" }
        val outputs = linkedMapOf<String, String>()
        val meanings = linkedMapOf<String, QueryStageAnalysis.MemberMeaning>()
        val contracts = linkedMapOf<String, MetricValueContract?>()
        root.outputs.forEach { projection ->
            outputs[projection.outputAlias] = full.outputs.getValue(projection.slot)
            meanings[projection.outputAlias] = full.meanings.getValue(projection.slot)
            contracts[projection.outputAlias] = full.valueContracts[projection.slot]
        }
        val used = outputs.values.toCollection(linkedSetOf())
        val condition = MemberCondition.resolve(root.condition, full.outputs::get, { CalculationInstantiation.resolve(it, full.bindings, arguments) }, used)
        MemberCondition.requireParameterUnits(root.condition, condition,
            { symbol -> val slot = full.outputs.entries.first { it.value == symbol }.key; Calculation.rootValueContract(full.formal.outputs.getValue(slot)) },
            full.outputs::get, { slot -> Calculation.rootValueContract(full.formal.outputs.getValue(slot)) },
            { name -> calculation.parameters.getValue(name).valueContract }, emptyMap())
        return full.copy(callId = root.callId, scope = scope, selection = MemberInvocationBinding.Bound(outputs, meanings, contracts,
            emptyMap(), used, condition, null, emptyMap(), emptyMap()))
    }
}
