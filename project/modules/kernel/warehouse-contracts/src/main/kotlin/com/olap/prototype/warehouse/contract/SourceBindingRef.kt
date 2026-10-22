package com.olap.prototype.warehouse.contract

/** 同一来源的一份不可变访问绑定；摘要包括精确插件与参数，不包括凭据值。 */
data class SourceBindingRef(val dataSourceId: String, val sourceIdentityHash: String) {
    init {
        require(dataSourceId.isNotBlank()) { "source binding requires a data source identity" }
        require(sourceIdentityHash.matches(Regex("[0-9a-f]{64}"))) { "source binding requires its exact identity hash" }
    }
}
