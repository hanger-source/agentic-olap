package com.olap.prototype.calculation

import com.olap.prototype.member.MemberRef

/** 完整计算的实际根owner，指标实例与成员来源有独立精确身份。 */
sealed interface CalculationOwner {
    data class Metric(val instance: MetricInstanceRef) : CalculationOwner
    data class Member(val source: MemberRef) : CalculationOwner
    class Query(path: List<String>) : CalculationOwner {
        val path: List<String> = java.util.List.copyOf(path)
        init { this.path.forEach { MetricDefinition.text(it, "query calculation position") } }
        override fun equals(other: Any?): Boolean = other is Query && path == other.path
        override fun hashCode(): Int = path.hashCode()
    }
}
