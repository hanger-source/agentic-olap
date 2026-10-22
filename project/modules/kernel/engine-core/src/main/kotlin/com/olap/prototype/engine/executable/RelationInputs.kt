package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.calculation.CalculationBindings

/** 绑定词法目标一次；调用转发只采用已绑定的精确位置，不重新捕获同名输入。 */
internal class RelationInputs {
    fun bind(calculation: Calculation, bindings: CalculationBindings, slot: String, caller: MemberEnvironment): ExecutableRelationInput {
        val binding = bindings.relations.getValue(slot)
        require(binding is CalculationBindings.LocalRelation) { "relation parameter must have an explicit actual completed input" }
        val input = caller.input(binding)
        require(input.inputMode == "ROOT") { "local relation input must explicitly declare ROOT mode" }
        val formal = calculation.relationSlots.first { it.name == slot }
        return ExecutableRelationInput(caller.inputScope(binding), listOf(requirement(formal, bindings, binding.outputSlots)))
    }
    fun adopt(calculation: Calculation, bindings: CalculationBindings, slot: String, actual: ExecutableRelationInput): ExecutableRelationInput {
        val formal = calculation.relationSlots.first { it.name == slot }
        val outputs = formal.outputs.associate { output -> output.name to actual.projection.outputSlots.getValue(output.name) }
        return ExecutableRelationInput(actual.inputScope, actual.requirements + requirement(formal, bindings, outputs))
    }

    private fun requirement(formal: Calculation.RelationSlot, bindings: CalculationBindings,
        outputSlots: Map<String, String>): ExecutableRelationInput.Requirement {
        require(outputSlots.keys == formal.outputs.map { it.name }.toSet()) { "relation input must map all and only formal outputs" }
        return ExecutableRelationInput.Requirement(outputSlots, formal.outputs.map { it.adopted(bindings) }, formal.keys)
    }
}
