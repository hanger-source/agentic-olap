package com.olap.prototype.query.contract

import java.time.Instant

/** 保存查询的不可变正文；正式结果另带正文制品的精确引用。 */
class QueryDefinitionView(
    val definitionId: String, val definitionVersion: Long, val name: String,
    val scenarioId: String, val scenarioVersion: Long, val scenarioDisplayName: String,
    val releaseId: String, val releaseVersion: String, val releaseDisplayName: String,
    val catalogHash: String, val subjectAreaId: String, val subjectAreaVersion: Long,
    val capabilityHash: String, val intentHash: String, val intent: QueryIntent, val createdAt: Instant,
    val source: QuerySource?,
) {

    init {
        require(listOf(definitionId, name, scenarioId, scenarioDisplayName, releaseId, releaseVersion,
            releaseDisplayName, catalogHash, subjectAreaId, capabilityHash, intentHash).all { it.isNotBlank() }) {
            "saved query metadata must be explicit nonempty text"
        }
        require(definitionVersion > 0 && scenarioVersion > 0 && subjectAreaVersion > 0) { "saved query versions must be positive" }
    }

    override fun equals(other: Any?): Boolean = other is QueryDefinitionView &&
        definitionId == other.definitionId && definitionVersion == other.definitionVersion && name == other.name &&
        scenarioId == other.scenarioId && scenarioVersion == other.scenarioVersion && scenarioDisplayName == other.scenarioDisplayName &&
        releaseId == other.releaseId && releaseVersion == other.releaseVersion && releaseDisplayName == other.releaseDisplayName &&
        catalogHash == other.catalogHash && subjectAreaId == other.subjectAreaId && subjectAreaVersion == other.subjectAreaVersion &&
        capabilityHash == other.capabilityHash && intentHash == other.intentHash && intent == other.intent && createdAt == other.createdAt && source == other.source

    override fun hashCode(): Int = listOf(definitionId, definitionVersion, name, scenarioId, scenarioVersion,
        scenarioDisplayName, releaseId, releaseVersion, releaseDisplayName, catalogHash, subjectAreaId,
        subjectAreaVersion, capabilityHash, intentHash, intent, createdAt, source).hashCode()
}

/** 读取或保存查询的正式回执；引用摘要由持久化正文生成，不参与正文自身的摘要。 */
data class QueryDefinitionResult(val definition: QueryDefinitionView, val reference: QueryReference) {
    init {
        require(reference.definitionId == definition.definitionId && reference.definitionVersion == definition.definitionVersion) {
            "query reference identity differs from its definition"
        }
    }
}
