package com.olap.prototype.governance.contract

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.calculation.CalculationBindings
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.member.MemberRef
import com.olap.prototype.member.MemberSourceDefinition
import java.time.Instant



sealed interface MemberDefinitionOrigin {
    class Local : MemberDefinitionOrigin {
        override fun equals(other: Any?): Boolean = other is Local
        override fun hashCode(): Int = 0
    }
    data class Shared(val reference: MemberDefinitionRef) : MemberDefinitionOrigin
}
data class MemberDefinitionRef(val id: String, val version: Long) {
    init { ProducerContracts.requireText(id, "member definition id"); ProducerContracts.requireVersion(version, "member definition version") }
}
data class MemberDefinitionCreateRequest(val code: String, val displayName: String, val definition: MemberSourceDefinition, val description: String?, val owner: String, val createdBy: String)
data class MemberDefinitionRecord(val id: String, val code: String, val displayName: String, val definition: MemberSourceDefinition, val description: String?, val owner: String, val status: String, val currentVersion: Long, val createdBy: String, val createdAt: Instant) {
     val reference: MemberDefinitionRef get() = MemberDefinitionRef(id, currentVersion)
}
class MemberDefinitionAdoptRequest(val subjectAreaId: String, val subjectAreaVersion: Long, val code: String, val displayName: String, val definition: MemberDefinitionRef, val bindings: CalculationBindings, fixedArguments: Map<String, Calculation.Value>, val actor: String) {
    val fixedArguments: Map<String, Calculation.Value> = java.util.Map.copyOf(fixedArguments)
    override fun equals(other: Any?): Boolean = other is MemberDefinitionAdoptRequest && subjectAreaId == other.subjectAreaId && subjectAreaVersion == other.subjectAreaVersion && code == other.code && displayName == other.displayName && definition == other.definition && bindings == other.bindings && fixedArguments == other.fixedArguments && actor == other.actor
    override fun hashCode(): Int = listOf(subjectAreaId, subjectAreaVersion, code, displayName, definition, bindings, fixedArguments, actor).hashCode()
}
class MemberAdoptionRevisionRequest(val source: MemberRef, val expectedDefinition: MemberDefinitionRef,
    val definition: MemberDefinitionRef, val displayName: String, val bindings: CalculationBindings,
    fixedArguments: Map<String, Calculation.Value>, val actor: String) {
    val fixedArguments: Map<String, Calculation.Value> = java.util.Map.copyOf(fixedArguments)
}
class MemberAdoptionConflictException(val source: MemberRef, val expectedDefinition: MemberDefinitionRef,
    val actualDefinition: MemberDefinitionRef) : ProducerContractException("member adoption no longer references the expected shared definition; read the exact member source before revising it")
class MemberSourceCreateRequest(val subjectAreaId: String, val subjectAreaVersion: Long, val code: String, val displayName: String, val definition: MemberSourceDefinition, val bindings: CalculationBindings, fixedArguments: Map<String, Calculation.Value>, val actor: String) {
    val fixedArguments: Map<String, Calculation.Value> = java.util.Map.copyOf(fixedArguments)
    override fun equals(other: Any?): Boolean = other is MemberSourceCreateRequest && subjectAreaId == other.subjectAreaId && subjectAreaVersion == other.subjectAreaVersion && code == other.code && displayName == other.displayName && definition == other.definition && bindings == other.bindings && fixedArguments == other.fixedArguments && actor == other.actor
    override fun hashCode(): Int = listOf(subjectAreaId, subjectAreaVersion, code, displayName, definition, bindings, fixedArguments, actor).hashCode()
}
class MemberSourceRecord(val id: String, val subjectAreaId: String, val subjectAreaVersion: Long, val code: String, val displayName: String, val origin: MemberDefinitionOrigin, val definition: MemberSourceDefinition, val bindings: CalculationBindings, fixedArguments: Map<String, Calculation.Value>) {
    val fixedArguments: Map<String, Calculation.Value> = java.util.Map.copyOf(fixedArguments)
    init { require(definition !is MemberSourceDefinition.Projection || (bindings == CalculationBindings.empty() && this.fixedArguments.isEmpty())) { "projection source has no calculation bindings or fixed arguments" } }
    override fun equals(other: Any?): Boolean = other is MemberSourceRecord && id == other.id && subjectAreaId == other.subjectAreaId && subjectAreaVersion == other.subjectAreaVersion && code == other.code && displayName == other.displayName && origin == other.origin && definition == other.definition && bindings == other.bindings && fixedArguments == other.fixedArguments
    override fun hashCode(): Int = listOf(id, subjectAreaId, subjectAreaVersion, code, displayName, origin, definition, bindings, fixedArguments).hashCode()
}
