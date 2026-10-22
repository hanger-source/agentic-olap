package com.olap.prototype.warehouse.contract

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 源生产者出具的有限封存集合声明；数据库行数不产生完整性声明。 */
class SealedSourceDataset(
    val formatVersion: String,
    val sourceAuthority: String,
    val ruleDocumentSha256: String,
    val anchor: JdbcTableConstraints.DataAnchor,
    val scope: Scope,
    collections: List<Collection>,
) {
    val collections: List<Collection> = java.util.List.copyOf(collections)
    init {
        require(formatVersion == FORMAT_VERSION) { "unsupported sealed source dataset declaration" }
        require(sourceAuthority.isNotBlank()) { "source authority is required" }
        require(ruleDocumentSha256.matches(Regex("[0-9a-f]{64}"))) { "exact authority document SHA-256 is required" }
        require(this.collections.size in 1..5000 && this.collections.map { it.code }.distinct().size == this.collections.size &&
            this.collections.map { it.table }.distinct().size == this.collections.size) { "source declaration requires bounded distinct source collections" }
    }

    data class Scope(val timezone: String, val reportingStartInclusive: LocalDate, val reportingEndExclusive: LocalDate, val observedThrough: LocalDateTime) {
        init {
            ZoneId.of(timezone)
            require(reportingStartInclusive.isBefore(reportingEndExclusive)) { "source reporting interval must be nonempty and half-open" }
        }
    }

    class Collection(val code: String, val table: JdbcTableConstraints.Table, ruleIds: List<String>) {
        val ruleIds: List<String> = java.util.List.copyOf(ruleIds)
        init {
            require(code.matches(Regex("[a-z][a-z0-9_.-]{0,127}"))) { "source collection requires an exact code" }
            require(this.ruleIds.size in 1..64 && this.ruleIds.all { it.isNotBlank() } && this.ruleIds.distinct().size == this.ruleIds.size) {
                "source collection requires distinct authority rule references"
            }
        }
        override fun equals(other: Any?): Boolean = other is Collection && code == other.code && table == other.table && ruleIds == other.ruleIds
        override fun hashCode(): Int = listOf(code, table, ruleIds).hashCode()
    }

    override fun equals(other: Any?): Boolean = other is SealedSourceDataset && formatVersion == other.formatVersion && sourceAuthority == other.sourceAuthority && ruleDocumentSha256 == other.ruleDocumentSha256 && anchor == other.anchor && scope == other.scope && collections == other.collections
    override fun hashCode(): Int = listOf(formatVersion, sourceAuthority, ruleDocumentSha256, anchor, scope, collections).hashCode()
    companion object { const val FORMAT_VERSION = "olap-sealed-source-dataset/v1" }
}
