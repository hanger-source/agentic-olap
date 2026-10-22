package com.olap.prototype.engine.context

/** 请求审计与实测计时，不承载可变计划或SQL。 */
class OlapQueryContext(val requestId: String) {
    private val logs = mutableListOf<String>()
    private val times = linkedMapOf<String, Long>()
    val stageTimes: Map<String, Long> get() = times.toMap()
    val auditLogs: List<String> get() = logs.toList()
    init { require(requestId.isNotBlank()) { "OlapQueryContext requires requestId from ExecutableQueryRequest" } }
    fun addAuditLog(log: String) { logs.add(log) }
    fun recordStageTime(stage: String, startNano: Long) = recordStageDuration(stage, System.nanoTime() - startNano)
    fun recordStageDuration(stage: String, durationNanos: Long) {
        require(stage.isNotBlank() && durationNanos >= 0) { "invalid observed stage duration" }
        times[stage] = (times[stage] ?: 0) + durationNanos
    }
    val auditReport: String get() = buildString {
        append("\n=== OLAP ENGINE DEEP AUDIT REPORT ===\nRequest ID: ").append(requestId).append("\n--- Execution Stages ---\n")
        times.forEach { (stage, nanos) -> append(String.format("[%s]: %.2f ms\n", stage, nanos / 1_000_000.0)) }
        append("--- Logic Trace ---\n"); logs.forEach { append("  - ").append(it).append('\n') }
        append("=====================================\n")
    }
}
