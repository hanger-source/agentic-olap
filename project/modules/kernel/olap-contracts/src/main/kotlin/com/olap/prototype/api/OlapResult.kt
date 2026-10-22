package com.olap.prototype.api

/** 技术执行结果。业务输出由完成合同投影，不按列名复制物理结果。 */
class OlapResult(
    val requestId: String,
    val sql: String,
    aliasMapping: Map<String, String>,
    val resultFrame: ResultFrame,
    val auditReport: String?,
    stageNanos: Map<String, Long>,
) {
    val aliasMapping: Map<String, String> = java.util.Collections.unmodifiableMap(LinkedHashMap(aliasMapping))
    /** 阶段经过纳秒；总时长包含子阶段，不能再次相加。 */
    val stageNanos: Map<String, Long> = java.util.Map.copyOf(stageNanos)
    override fun equals(other: Any?): Boolean = other is OlapResult && requestId == other.requestId && sql == other.sql && aliasMapping == other.aliasMapping && resultFrame == other.resultFrame && auditReport == other.auditReport && stageNanos == other.stageNanos
    override fun hashCode(): Int = listOf(requestId, sql, aliasMapping, resultFrame, auditReport, stageNanos).hashCode()
}
