package com.olap.prototype.codec

import com.olap.prototype.query.contract.PublishedExecutableCatalog
import com.olap.prototype.query.contract.PublishedQueryEntry
import com.olap.prototype.query.contract.QueryCapabilityHash
import com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog

/** 发布身份只由正式语义内容决定；生成时间和本次诊断不参与内容身份。 */
object PublicationProto {
    fun contentHash(value: PublishedExecutableCatalog): String = contentHash(GovernanceProto.toProto(value))

    fun contentHash(value: com.olap.prototype.wire.governance.PublishedExecutableCatalog): String = ProtoBytes.digest(
        value.toBuilder().clearGeneratedAt().clearDiagnosticRunId().build())

    fun semanticCatalog(value: PublishedSemanticCatalog.Catalog): PublishedSemanticCatalog = PublishedSemanticCatalog(
        PublishedSemanticCatalog.FORMAT_VERSION, ProtoBytes.digest(GovernanceProto.toProto(value)), value)

    fun requireValid(value: PublishedSemanticCatalog): PublishedSemanticCatalog = value.also {
        require(it.sha256 == semanticCatalog(it.catalog).sha256) { "published semantic catalog hash mismatch" }
    }

    fun requireValid(value: PublishedQueryEntry): PublishedQueryEntry = value.also {
        QueryCapabilityHash.requireValid(it.capability)
        requireValid(it.semanticCatalog)
        require(it.semanticCatalogHash == it.semanticCatalog.sha256) { "semantic catalog reference differs from content" }
    }
}
