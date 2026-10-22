package com.olap.prototype.warehouse.contract

/** 部署注册的声明集合，身份绑定完整声明与精确来源部署。 */
data class SourceCollectionRecord(
    val id: String,
    val dataSourceId: String,
    val sourceIdentityHash: String,
    val declarationSha256: String,
    val sourceAuthority: String,
    val ruleDocumentSha256: String,
    val anchor: JdbcTableConstraints.DataAnchor,
    val scope: SealedSourceDataset.Scope,
    val collection: SealedSourceDataset.Collection,
) {
    init {
        require(id.isNotBlank() && dataSourceId.isNotBlank()) { "source collection requires exact deployment identities" }
        require(listOf(sourceIdentityHash, declarationSha256, ruleDocumentSha256).all { it.matches(Regex("[0-9a-f]{64}")) }) { "source collection requires exact SHA-256 identities" }
        require(sourceAuthority.isNotBlank()) { "source authority is required" }
    }
}
