package com.olap.prototype.query.contract

/** 服务在交付确认之前记录的运行事实；阶段可以嵌套，不能将各计时直接相加。 */
class QueryServiceObservation(val scope: String, timingNanos: Map<String, Long>, workers: List<QueryWorkerAcquisition>) {
    val timingNanos: Map<String, Long> = java.util.Collections.unmodifiableMap(LinkedHashMap(timingNanos))
    val workers: List<QueryWorkerAcquisition> = java.util.List.copyOf(workers)
    init {
        require(scope == "SERVER_BEFORE_DELIVERY_CONFIRMATION") { "service observation boundary is required" }
        require(this.timingNanos.all { it.key.isNotBlank() && it.value >= 0L }) { "nonnegative named service timings are required" }
    }
    override fun equals(other: Any?): Boolean = other is QueryServiceObservation && scope == other.scope && timingNanos == other.timingNanos && workers == other.workers
    override fun hashCode(): Int = listOf(scope, timingNanos, workers).hashCode()
}

data class QueryWorkerAcquisition(val kind: String, val outcome: String, val queueNanos: Long, val startupNanos: Long) {
    init { require(kind.isNotBlank() && outcome.isNotBlank() && queueNanos >= 0L && startupNanos >= 0L) { "worker acquisition facts are required" } }
}
