package com.olap.prototype.query.definition

import com.olap.prototype.query.contract.PublishedQueryEntry

/** 保存时采用的同一精确发布与认证主体。 */
data class QueryDefinitionWriteContext(val entry: PublishedQueryEntry, val createdBy: String) {
    init { require(createdBy.isNotBlank()) { "query definition createdBy must be explicit" } }
    val subjectAreaId: String get() = entry.capability.subjectAreaCapabilityContext.subjectAreaId
    val subjectAreaVersion: Long get() = entry.capability.subjectAreaCapabilityContext.subjectAreaVersion
    val catalogHash: String get() = entry.catalogHash
}
