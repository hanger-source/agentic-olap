package com.olap.prototype.release.catalog

import com.olap.prototype.query.contract.*
import com.olap.prototype.release.catalog.capability.ReleaseQueryCapabilityRepository
import com.olap.prototype.release.catalog.capability.ReleasedScenarioEntrypoint
import com.olap.prototype.release.catalog.capability.ReleasedScenarioEntrypointRepository
import com.olap.prototype.release.contract.ReleaseCatalogRequest
import com.olap.prototype.storage.PlatformDatabase

/** 同一数据库快照中读取已经提交的精确发布，不重新解析草稿。 */
class JdbcPublishedQueryEntryProvider(private val database: PlatformDatabase) : PublishedQueryEntryProvider {
    private val artifacts = ReleaseArtifactRepository(database)
    private val capabilities = ReleaseQueryCapabilityRepository(database)
    override fun require(request: QueryCapabilityRequest): PublishedQueryEntry = readPublication(request).entry
    fun readPublication(coordinate: QueryCapabilityRequest, observe: (String, Long) -> Unit = { _, _ -> }): Publication = database.inReadSnapshot {
        fun <T> timed(stage: String, action: () -> T): T {
            val started = System.nanoTime()
            return try { action() } finally { observe(stage, System.nanoTime() - started) }
        }
        val catalogCoordinate = ReleaseCatalogRequest(coordinate.scenarioId, coordinate.scenarioVersion, coordinate.releaseId, coordinate.releaseVersion)
        val capability = timed("CAPABILITY_LOAD") { capabilities.load(coordinate) }
        val executable = timed("EXECUTABLE_LOAD") { artifacts.loadExecutableCatalog(catalogCoordinate) }
        val producer = timed("PRODUCER_LOAD") { artifacts.loadCatalog(catalogCoordinate) }
        val semantic = timed("SEMANTIC_CATALOG") { PublishedSemanticCatalogProducer().produce(capability, producer, executable) }
        val hash = timed("CATALOG_HASH") { com.olap.prototype.codec.PublicationProto.contentHash(executable) }
        Publication(PublishedQueryEntry(capability, hash, semantic.sha256, semantic), executable)
    }
    fun list(offset: Int, limit: Int, subjectAreaId: String? = null, releaseId: String? = null, scopes: List<Scope> = listOf(Scope(null, null))): Page {
        require(offset >= 0 && limit in 1..1000) { "publication pagination requires offset>=0 and 1<=limit<=1000" }
        return database.inReadSnapshot {
            val rows = ReleasedScenarioEntrypointRepository(database::connection).list(subjectAreaId, releaseId, scopes, offset, limit + 1)
            Page(rows.take(limit), if (rows.size > limit) Math.addExact(offset, limit) else null)
        }
    }
    data class Publication(val entry: PublishedQueryEntry, val executable: PublishedExecutableCatalog)
    /** 每组取交集、组间取并集；NULL明确全部，空集合不授予范围。 */
    class Scope(subjectAreaIds: Set<String>?, releaseIds: Set<String>?) {
        val subjectAreaIds: Set<String>? = subjectAreaIds?.let { java.util.Set.copyOf(it) }
        val releaseIds: Set<String>? = releaseIds?.let { java.util.Set.copyOf(it) }
        fun empty(): Boolean = subjectAreaIds?.isEmpty() == true || releaseIds?.isEmpty() == true
        override fun equals(other: Any?): Boolean = other is Scope && subjectAreaIds == other.subjectAreaIds && releaseIds == other.releaseIds
        override fun hashCode(): Int = listOf(subjectAreaIds, releaseIds).hashCode()
    }
    class Page(items: List<ReleasedScenarioEntrypoint>, val nextOffset: Int?) {
        val items: List<ReleasedScenarioEntrypoint> = java.util.List.copyOf(items)
        override fun equals(other: Any?): Boolean = other is Page && items == other.items && nextOffset == other.nextOffset
        override fun hashCode(): Int = listOf(items, nextOffset).hashCode()
    }
}
