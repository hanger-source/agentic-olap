package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.query.PostOperationContract
import com.olap.prototype.query.QueryStageAnalysis.ScopeId
import java.util.Collections
import java.util.IdentityHashMap

/** 资格只沿实际测量人口附加，不改写已完成成员、版本边界或参照总体。 */
internal class ExecutableObjectQualification(private val localInputs: (ScopeId) -> ExecutableInputScope) {
    private val facts = IdentityHashMap<ExecutableMetricInput, MutableList<ExecutableObjectRestriction>>()
    private val records = IdentityHashMap<ExecutableInputScope, MutableList<ExecutableObjectRestriction>>()
    private val memberUses = IdentityHashMap<ExecutableInputScope, ExecutableMemberInput>()
    fun memberUse(input: ExecutableInputScope, member: ExecutableMemberInput?) {
        if (member != null) check(memberUses.putIfAbsent(input, member) == null) { "bound member use is already registered" }
    }
    fun facts(input: ExecutableMetricInput): List<ExecutableObjectRestriction> = facts[input]?.toList() ?: emptyList()
    fun records(input: ExecutableInputScope): List<ExecutableObjectRestriction> = records[input]?.toList() ?: emptyList()
    fun attach(input: ExecutableInputScope, objects: ExecutableInputScope, objectScope: ScopeId, role: Calculation.ObjectRole,
        recordSource: ExecutableMemberInput?, visited: MutableSet<ExecutableInputScope>): Int {
        if (!visited.add(input)) return 0
        when (input.root) {
            is ExecutableInputRoot.ObjectMeasure -> return attach(input.additionalInputs.getValue("measurement"), objects, objectScope, role, recordSource, visited)
            is ExecutableInputRoot.Input, is ExecutableInputRoot.Relation, is ExecutableInputRoot.AlignMeasurements, is ExecutableInputRoot.ObservationStatistics, is ExecutableInputRoot.SetInputs -> return 0
            else -> Unit
        }
        require(input.postOps.none { it is PostOperationContract.SelectVersions || it is PostOperationContract.ObserveWindow }) { "object qualification must select the formed record boundary after version/window observation" }
        var result = if (role.target is Calculation.EventRole) attachFactObjects(input, objects, objectScope, role) else attachRecordObjects(input, objectScope, role, recordSource)
        input.metricCalls.flatMap { it.calculations() }.forEach { result += attach(it.input, objects, objectScope, role, recordSource, visited) }
        return result
    }
    private fun attachFactObjects(input: ExecutableInputScope, objects: ExecutableInputScope, objectScope: ScopeId, role: Calculation.ObjectRole): Int {
        val target = role.target as Calculation.EventRole
        var count = 0
        input.metrics.forEach { fact ->
            val keys = fact.event.identity.entityRoles[target.role] ?: return@forEach
            require(!consumesCompletedScope(objects, input, Collections.newSetFromMap(IdentityHashMap<ExecutableInputScope, Boolean>()))) { "object qualification cannot rewrite the completed measurement used to form its own object population" }
            val snapshot = fact.metric.factMeasure().snapshotRule
            require(snapshot == null || snapshot.entityRole == target.role) { "snapshot object qualification must preserve its full version-selection entity; qualify formed records for other roles" }
            require(keys.size == role.objectKeys.size) { "object qualification must cover the complete fact entity role tuple" }
            facts.getOrPut(fact) { mutableListOf() }.add(ExecutableObjectRestriction(objectScope, role.objectKeys, role.target, emptyList(), emptyMap()))
            count++
        }
        return count
    }
    private fun attachRecordObjects(input: ExecutableInputScope, objectScope: ScopeId, role: Calculation.ObjectRole, recordSource: ExecutableMemberInput?): Int {
        if (!sameMemberCall(recordSource, memberUses[input])) return 0
        val source = requireNotNull(recordSource)
        val target = role.target as Calculation.RecordOutputs
        val mapped = target.keys.map { requireNotNull(source.outputs[it]) { "qualified record output is not mapped by its exact member formal" } }
        require(mapped.size == role.objectKeys.size) { "object qualification must map the complete record object tuple" }
        records.getOrPut(input) { mutableListOf() }.add(ExecutableObjectRestriction(objectScope, role.objectKeys, role.target, mapped, source.meanings))
        return 1
    }
    private fun sameMemberCall(expected: ExecutableMemberInput?, actual: ExecutableMemberInput?): Boolean = when {
        expected == null || actual == null -> false
        expected === actual -> true
        expected is ExecutableMemberInput.Local && actual is ExecutableMemberInput.Local -> expected.inputScope == actual.inputScope
        expected is ExecutableMemberInput.Computed && actual is ExecutableMemberInput.Computed -> expected.computation === actual.computation && expected.computationScope == actual.computationScope
        else -> false
    }
    private fun consumesCompletedScope(consumer: ExecutableInputScope, source: ExecutableInputScope, visited: MutableSet<ExecutableInputScope>): Boolean {
        if (consumer === source) return true
        if (!visited.add(consumer)) return false
        val local = (consumer.root as? ExecutableInputRoot.Members)?.input as? ExecutableMemberInput.Local
        if (local != null && consumesCompletedScope(localInputs(local.inputScope), source, visited)) return true
        val relation = (consumer.root as? ExecutableInputRoot.Relation)?.input
        if (relation != null && consumesCompletedScope(localInputs(relation.inputScope), source, visited)) return true
        val children = consumer.memberComputations().values + consumer.metricCalls.flatMap { it.calculations().map { body -> body.input } } + consumer.additionalInputs.values
        return children.any { consumesCompletedScope(it, source, visited) }
    }
}
