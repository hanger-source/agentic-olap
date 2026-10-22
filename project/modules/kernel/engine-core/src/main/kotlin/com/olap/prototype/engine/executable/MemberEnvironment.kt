package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.CalculationBindings
import com.olap.prototype.query.QueryStageAnalysis.ScopeId

/** 调用者的词法访问域，不持调用完成状态。 */
internal data class MemberEnvironment(val localInputs: (String) -> ExecutableInputScope, val memberOwnerScope: ScopeId, val localOwnerScope: ScopeId) {
    fun input(reference: CalculationBindings.LocalRef): ExecutableInputScope = localInputs(reference.inputId)
    fun inputScope(reference: CalculationBindings.LocalRef): ScopeId = localOwnerScope.child(reference.inputId)
    fun input(reference: CalculationBindings.LocalRelation): ExecutableInputScope = localInputs(reference.inputId)
    fun inputScope(reference: CalculationBindings.LocalRelation): ScopeId = localOwnerScope.child(reference.inputId)
    fun memberBody(bodyScope: ScopeId): MemberEnvironment = copy(memberOwnerScope = bodyScope)
}
