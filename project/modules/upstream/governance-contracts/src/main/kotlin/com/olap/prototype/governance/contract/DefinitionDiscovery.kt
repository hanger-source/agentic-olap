package com.olap.prototype.governance.contract

import com.olap.prototype.metric.MetricBusinessMeaning

/** 工作目录只公开可检索的作者摘要；完整正文由精确版本读取。 */
object DefinitionDiscovery {
    class Request(assetTypes: Set<String>, val text: String?, val offset: Int, val limit: Int) {
        val assetTypes: Set<String> = java.util.Set.copyOf(assetTypes)
        init { require(this.assetTypes.isNotEmpty() && offset >= 0 && limit in 1..1000) { "explicit definition kinds and bounded pagination are required" } }
        override fun equals(other: Any?): Boolean = other is Request && assetTypes == other.assetTypes && text == other.text && offset == other.offset && limit == other.limit
        override fun hashCode(): Int = listOf(assetTypes, text, offset, limit).hashCode()
    }
    data class Summary(val assetType: String, val id: String, val version: Long, val status: String,
        val code: String?, val displayName: String?, val description: String?, val owner: String?, val businessMeaning: MetricBusinessMeaning?)
    class Page(items: List<Summary>, val nextOffset: Int?) {
        val items: List<Summary> = java.util.List.copyOf(items)
        override fun equals(other: Any?): Boolean = other is Page && items == other.items && nextOffset == other.nextOffset
        override fun hashCode(): Int = 31 * items.hashCode() + (nextOffset ?: 0)
    }
}
