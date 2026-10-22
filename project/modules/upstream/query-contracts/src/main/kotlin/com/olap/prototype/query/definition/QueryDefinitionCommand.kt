package com.olap.prototype.query.definition

import com.olap.prototype.query.contract.QueryIntent
import com.olap.prototype.query.contract.QuerySource

/** 本次完整查询的显式保存或精确源版本修订；不是字段补丁。 */
class QueryDefinitionCommand(
    val definitionId: String?, val sourceVersion: Long?, val capabilityHash: String,
    val name: String, val intent: QueryIntent, val source: QuerySource?,
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
}
