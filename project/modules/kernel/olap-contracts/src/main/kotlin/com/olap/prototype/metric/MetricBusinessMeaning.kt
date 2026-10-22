package com.olap.prototype.metric

import com.olap.prototype.contract.ProducerContracts

/** 面向消费方的业务含义，不是物理实现或聚合规则。 */
class MetricBusinessMeaning(val definition: String, val unit: String, val grain: String, limitations: List<String>) {
    val limitations: List<String> = java.util.List.copyOf(limitations)
    init {
        ProducerContracts.requireText(definition, "metric.businessMeaning.definition")
        ProducerContracts.requireText(unit, "metric.businessMeaning.unit")
        ProducerContracts.requireText(grain, "metric.businessMeaning.grain")
        this.limitations.forEach { ProducerContracts.requireText(it, "metric.businessMeaning.limitation") }
    }
    override fun equals(other: Any?): Boolean = other is MetricBusinessMeaning && definition == other.definition && unit == other.unit && grain == other.grain && limitations == other.limitations
    override fun hashCode(): Int = listOf(definition, unit, grain, limitations).hashCode()
}
