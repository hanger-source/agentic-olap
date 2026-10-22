package com.olap.prototype.governance.contract

import com.olap.prototype.query.contract.QueryCapabilityRequest

/** 调用者选择精确资产；不接受文件路径，不迁移或重放。 */
data class DeploymentAvailabilityRequest(
    val publications: List<QueryCapabilityRequest> = emptyList(),
    val queries: List<SavedQueryReference> = emptyList(),
    val results: List<ResultReference> = emptyList(),
) {
    init {
        require(publications.size + queries.size + results.size <= 100) { "availability selection exceeds 100 exact assets" }
        require(publications.distinct().size == publications.size && queries.distinct().size == queries.size && results.distinct().size == results.size) { "availability selection contains duplicate references" }
    }
    data class SavedQueryReference(val definitionId: String, val definitionVersion: Long, val sha256: String) {
        init { require(definitionId.isNotBlank() && definitionVersion > 0 && sha256.matches(Regex("[0-9a-f]{64}"))) { "exact saved query reference required" } }
    }
    data class ResultReference(val runId: String, val sha256: String) {
        init { require(runId.isNotBlank() && sha256.matches(Regex("[0-9a-f]{64}"))) { "exact result reference required" } }
    }
}

data class DeploymentAvailability(val supported: Map<String, String>, val checks: List<Check>) {
    enum class Kind { METADATA, DEFINITION, PUBLICATION, QUERY_EXECUTION, RESULT_READ }
    enum class Status { AVAILABLE, MISSING, UNSUPPORTED, INVALID, NOT_CHECKED }
    data class Issue(val code: String, val message: String, val details: Map<String, Any?> = emptyMap())
    data class Check(val kind: Kind, val reference: Map<String, Any>, val status: Status, val issues: List<Issue> = emptyList())
}
