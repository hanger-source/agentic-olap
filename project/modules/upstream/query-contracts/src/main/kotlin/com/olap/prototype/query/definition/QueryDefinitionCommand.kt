package com.olap.prototype.query.definition

import com.olap.prototype.query.QueryExecutionMode
import com.olap.prototype.query.contract.QueryIntent
import com.olap.prototype.query.contract.QuerySource

/** 本次完整查询的保存或精确源版本修订；executionMode 只决定本次保存前校验，不进入保存定义或约束后续执行。 */
class QueryDefinitionCommand(
    val definitionId: String?, val sourceVersion: Long?, val capabilityHash: String,
    val name: String, val intent: QueryIntent, val source: QuerySource?,
    val executionMode: QueryExecutionMode = QueryExecutionMode.SOURCE,
) {
    val scenarioId: String get() = intent.publication.scenarioId
    val scenarioVersion: Long get() = intent.publication.scenarioVersion
    val releaseId: String get() = intent.publication.releaseId
    val releaseVersion: String get() = intent.publication.releaseVersion
    init {
        require(definitionId == null || definitionId.isNotBlank()) { "definitionId must not be blank when provided" }
        require(sourceVersion == null || sourceVersion > 0) { "query_definition.sourceVersion must be positive" }
        require(scenarioVersion > 0) { "query_definition.scenarioVersion must be positive" }
        require(listOf(capabilityHash, name, scenarioId, releaseId, releaseVersion).all { it.isNotBlank() }) { "query definition metadata must be explicit nonempty text" }
    }
    companion object {
        fun contract(): Map<String, Any> = mapOf(
            "executionMode" to "THIS_SAVE_OR_UPDATE_FULL_COMPILATION_VALIDATION_ONLY",
            "defaultExecutionMode" to "SOURCE",
            "identity" to "MODE_IS_NOT_PART_OF_QUERY_INTENT_SAVED_DEFINITION_OR_INTENT_HASH",
            "laterRequests" to "EXECUTE_VALIDATE_AND_EXPLAIN_SELECT_THEIR_OWN_EXPLICIT_MODE",
            "validation" to "FULL_AUTHORIZATION_BINDING_AND_SELECTED_EXECUTION_PLAN_NO_SOURCE_ACCESS_OR_AUTOMATIC_FALLBACK",
        )
    }
}
