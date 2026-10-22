package com.olap.prototype.query.contract

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/** 保存查询的精确版本；JSON 树在进入和离开版本对象时冻结，不能旁路修改其内容身份。 */
class QueryDefinitionView(
    val definitionId: String, val definitionVersion: Long, val name: String,
    val scenarioId: String, val scenarioVersion: Long, val scenarioDisplayName: String,
    val releaseId: String, val releaseVersion: String, val releaseDisplayName: String,
    val catalogHash: String, val subjectAreaId: String, val subjectAreaVersion: Long,
    val capabilityHash: String, val intentHash: String, intent: JsonNode, val createdAt: Instant,
) {
    private val frozenIntent: JsonNode = intent.deepCopy()
    val intent: JsonNode get() = frozenIntent.deepCopy()

    init {
        require(listOf(definitionId, name, scenarioId, scenarioDisplayName, releaseId, releaseVersion,
            releaseDisplayName, catalogHash, subjectAreaId, capabilityHash, intentHash).all { it.isNotBlank() }) {
            "saved query metadata must be explicit nonempty text"
        }
        require(definitionVersion > 0 && scenarioVersion > 0 && subjectAreaVersion > 0) { "saved query versions must be positive" }
        require(intent.isObject) { "intent must be produced as typed object" }
    }

    override fun equals(other: Any?): Boolean = other is QueryDefinitionView &&
        definitionId == other.definitionId && definitionVersion == other.definitionVersion && name == other.name &&
        scenarioId == other.scenarioId && scenarioVersion == other.scenarioVersion && scenarioDisplayName == other.scenarioDisplayName &&
        releaseId == other.releaseId && releaseVersion == other.releaseVersion && releaseDisplayName == other.releaseDisplayName &&
        catalogHash == other.catalogHash && subjectAreaId == other.subjectAreaId && subjectAreaVersion == other.subjectAreaVersion &&
        capabilityHash == other.capabilityHash && intentHash == other.intentHash && frozenIntent == other.frozenIntent && createdAt == other.createdAt

    override fun hashCode(): Int = listOf(definitionId, definitionVersion, name, scenarioId, scenarioVersion,
        scenarioDisplayName, releaseId, releaseVersion, releaseDisplayName, catalogHash, subjectAreaId,
        subjectAreaVersion, capabilityHash, intentHash, frozenIntent, createdAt).hashCode()
}
