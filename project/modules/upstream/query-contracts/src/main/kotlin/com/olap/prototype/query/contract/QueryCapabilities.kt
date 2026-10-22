package com.olap.prototype.query.contract

import com.olap.prototype.calculation.MetricInstanceRef
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.engine.catalog.ExecutableCatalogManifest
import java.time.Instant
import java.util.Collections

data class QueryCapabilityRequest(val scenarioId: String, val scenarioVersion: Long, val releaseId: String, val releaseVersion: String) {
    init {
        ProducerContracts.requireText(scenarioId, "scenarioId"); ProducerContracts.requireVersion(scenarioVersion, "scenarioVersion")
        ProducerContracts.requireText(releaseId, "releaseId"); ProducerContracts.requireText(releaseVersion, "releaseVersion")
    }
}
fun interface QueryCapabilityProvider { fun produce(request: QueryCapabilityRequest): QueryCapabilityView }
fun interface QueryCapabilityWriter { fun save(capability: QueryCapabilityView, createdAt: Instant) }
interface PublishedQueryEntryProvider : QueryCapabilityProvider {
    fun require(request: QueryCapabilityRequest): PublishedQueryEntry
    override fun produce(request: QueryCapabilityRequest): QueryCapabilityView = require(request).capability
}
/** 对外只交去除物理定位的中性编译目录，直接采用不可变正式模型。 */
class QueryNativeDefinitions(val catalog: com.olap.prototype.engine.catalog.ExecutableCatalog) {
    override fun equals(other: Any?): Boolean = other is QueryNativeDefinitions && catalog == other.catalog
    override fun hashCode(): Int = catalog.hashCode()
}

/** 消费冻结调用来源，不从维度可用性矩阵重建指标事件闭包。 */
object QueryCapabilityExecutionGraph {
    fun requireMetricEventKeys(capability: QueryCapabilityView, metricInstances: Set<MetricInstanceRef>): Set<String> {
        val result = linkedSetOf<String>()
        metricInstances.forEach { instance ->
            val candidate = capability.metricCandidates.firstOrNull { it.instance == instance } ?: throw IllegalArgumentException("metric instance is not published: $instance")
            candidate.sources.forEach { result.add("${it.event.eventId}@${it.event.eventVersion}") }
        }
        return Collections.unmodifiableSet(result)
    }
}
