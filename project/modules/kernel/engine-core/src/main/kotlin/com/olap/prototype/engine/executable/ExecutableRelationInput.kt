package com.olap.prototype.engine.executable

import com.olap.prototype.query.QueryStageAnalysis.ScopeId
import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.query.SetOutputContract

/** 同一精确完成位置逐层承担采用合同；最后一份要求决定本次读取投影。 */
class ExecutableRelationInput(val inputScope: ScopeId, requirements: List<Requirement>) {
    val requirements: List<Requirement> = java.util.List.copyOf(requirements)
    val projection: Requirement get() = this.requirements.last()
    init { require(this.requirements.size in 1..ScopedInputContract.MAX_DEPTH) { "relation adoption exceeds the explicit dependency depth budget" } }

    class Requirement(outputSlots: Map<String, String>, outputs: List<SetOutputContract>, keys: List<String>?) {
        val outputSlots: Map<String, String> = java.util.Map.copyOf(outputSlots)
        val outputs: List<SetOutputContract> = java.util.List.copyOf(outputs)
        val keys: List<String>? = keys?.let { java.util.List.copyOf(it) }
    }
}
