package com.olap.prototype.management.governance

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.util.OlapJsonUtils

/** 有界工作目录只公开摘要；完整正文由精确版本读取。 */
class DefinitionDiscoveryService(private val database: PlatformDatabase) {
    class Request(assetTypes: Set<String>, val text: String?, val offset: Int, val limit: Int) {
        val assetTypes: Set<String> = java.util.Set.copyOf(assetTypes)
        init {
            require(this.assetTypes.isNotEmpty() && offset >= 0 && limit in 1..1000) { "explicit definition kinds and bounded pagination are required" }
            this.assetTypes.forEach { GovernanceDraftVersions.Asset.valueOf(it) }
        }
        override fun equals(other: Any?): Boolean = other is Request && assetTypes == other.assetTypes && text == other.text && offset == other.offset && limit == other.limit
        override fun hashCode(): Int = listOf(assetTypes, text, offset, limit).hashCode()
    }
    class Summary(val assetType: String, val id: String, val version: Long, val status: String, metadata: JsonNode) {
        private val frozenMetadata = metadata.deepCopy<JsonNode>()
        val metadata: JsonNode get() = frozenMetadata.deepCopy<JsonNode>()
        override fun equals(other: Any?): Boolean = other is Summary && assetType == other.assetType && id == other.id && version == other.version && status == other.status && frozenMetadata == other.frozenMetadata
        override fun hashCode(): Int = listOf(assetType, id, version, status, frozenMetadata).hashCode()
    }
    class Page(items: List<Summary>, val nextOffset: Int?) {
        val items: List<Summary> = java.util.List.copyOf(items)
        override fun equals(other: Any?): Boolean = other is Page && items == other.items && nextOffset == other.nextOffset
        override fun hashCode(): Int = 31 * items.hashCode() + (nextOffset ?: 0)
    }
    fun search(request: Request): Page = database.inReadSnapshot {
        val kinds = request.assetTypes.sorted()
        val selections = kinds.map { kind ->
            val asset = GovernanceDraftVersions.Asset.valueOf(kind); val table = "${asset.table}_version"
            "SELECT '$kind' AS asset_type,v.${asset.column} AS asset_id,v.version,v.version_status,v.content_json FROM $table v WHERE v.version_status IN ('DRAFT','ACTIVE') AND NOT EXISTS (SELECT 1 FROM $table candidate WHERE candidate.${asset.column}=v.${asset.column} AND candidate.version_status='DRAFT' AND (v.version_status='ACTIVE' OR candidate.version>v.version)) AND (? IS NULL OR LOCATE(LOWER(?),LOWER(v.content_json))>0)"
        }
        val values = kinds.flatMap { listOf(request.text, request.text) } + listOf(request.limit + 1, request.offset)
        val rows = try {
            GovernanceJdbcSupport.query(database, selections.joinToString(" UNION ALL ") + " ORDER BY asset_type,asset_id,version LIMIT ? OFFSET ?", values) { row ->
                val content = OlapJsonUtils.mapper().readTree(row.getString("content_json"))
                val summary = OlapJsonUtils.mapper().createObjectNode()
                listOf("code", "displayName", "description", "owner", "businessMeaning").forEach { if (content.has(it)) summary.set<JsonNode>(it, content.get(it)) }
                Summary(row.getString("asset_type"), row.getString("asset_id"), row.getLong("version"), row.getString("version_status"), summary)
            }
        } catch (failure: java.sql.SQLException) { throw IllegalStateException("cannot search working definition metadata", failure) }
        catch (failure: java.io.IOException) { throw IllegalStateException("cannot search working definition metadata", failure) }
        Page(rows.take(request.limit), if (rows.size > request.limit) Math.addExact(request.offset, request.limit) else null)
    }
}
