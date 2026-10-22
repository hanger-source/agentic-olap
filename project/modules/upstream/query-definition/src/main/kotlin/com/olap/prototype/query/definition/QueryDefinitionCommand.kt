package com.olap.prototype.query.definition

import com.fasterxml.jackson.databind.JsonNode

/** 本次完整查询的显式保存或精确源版本修订；不是字段补丁。 */
class QueryDefinitionCommand(
    val definitionId: String?, val sourceVersion: Long?, val capabilityHash: String,
    val name: String, val scenarioId: String, val scenarioVersion: Long,
    val releaseId: String, val releaseVersion: String, intent: JsonNode,
) {
    private val frozenIntent: JsonNode = intent.deepCopy()
    val intent: JsonNode get() = frozenIntent.deepCopy()
    init {
        require(definitionId == null || definitionId.isNotBlank()) { "definitionId must not be blank when provided" }
        require(sourceVersion == null || sourceVersion > 0) { "query_definition.sourceVersion must be positive" }
        require(scenarioVersion > 0) { "query_definition.scenarioVersion must be positive" }
        require(listOf(capabilityHash, name, scenarioId, releaseId, releaseVersion).all { it.isNotBlank() }) { "query definition metadata must be explicit nonempty text" }
        require(intent.isObject) { "query_definition.intent must be a typed object" }
    }
}
