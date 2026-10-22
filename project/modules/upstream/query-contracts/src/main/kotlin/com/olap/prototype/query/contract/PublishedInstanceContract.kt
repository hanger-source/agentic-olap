package com.olap.prototype.query.contract

import com.olap.prototype.calculation.MetricInstance
import com.olap.prototype.calculation.MetricSource
import com.olap.prototype.engine.catalog.ExecutableCatalog
import com.olap.prototype.member.MemberRef

/** 完整发布和匿名编译共有的精确实例合同，不推断采用身份或授权。 */
object PublishedInstanceContract {
    fun require(catalog: ExecutableCatalog, capability: QueryCapabilityView): MetricSource.Closure {
        val closure = MetricSource.closure(capability.metricCandidates.map { it.instance },
            catalog.memberSources.values.map { MemberRef(it.id, it.version) },
            { catalog.requireMetric(it).definition }, catalog::requireMetricInstance,
            { requireNotNull(catalog.memberSources["${it.id}@${it.version}"]) { "native member dependency is missing" } })
        val reachable = linkedMapOf<String, MetricInstance>()
        closure.dependencies.filterIsInstance<MetricSource.InstanceDependency>().forEach { reachable[it.reference.id] = catalog.requireMetricInstance(it.reference) }
        require(reachable == catalog.metricInstances) { "native compilation instances differ from the published dependency closure" }
        capability.metricCandidates.forEach { candidate ->
            val instance = catalog.metricInstances[candidate.instance.id]
            require(instance != null && instance.definition == candidate.definition && instance.displayName == candidate.displayName && instance.fixedArguments == candidate.fixedArguments) {
                "native compilation instance differs from its published adoption: ${candidate.instance}"
            }
            val definition = catalog.requireMetric(instance.definition).definition
            require(instance.remainingParameters(definition) == candidate.parameters && instance.remainingGroupingCandidates(definition) == candidate.groupingCandidates &&
                instance.remainingMemberParameters(definition) == candidate.memberParameters && instance.remainingRelationParameters(definition) == candidate.relationParameters) { "native compilation parameter contract differs from its published adoption" }
        }
        return closure
    }
}
