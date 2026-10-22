package com.olap.prototype.release.catalog

import com.olap.prototype.release.contract.ReleaseCatalogSnapshot

/** 已封存发布的存储身份；不用于宣称不同定义在业务上等价。 */
class ReleaseArtifactManifest(val releaseId: String, val releaseVersion: String, val subjectAreaId: String,
    val subjectAreaVersion: Long, val compilerVersion: String, val ruleSetVersion: String,
    val governanceClosureHash: String, val catalogHash: String, catalogs: List<ReleaseCatalogSnapshot>) {
    val catalogs: List<ReleaseCatalogSnapshot> = java.util.List.copyOf(catalogs)
    override fun equals(other: Any?): Boolean = other is ReleaseArtifactManifest && releaseId == other.releaseId && releaseVersion == other.releaseVersion && subjectAreaId == other.subjectAreaId && subjectAreaVersion == other.subjectAreaVersion && compilerVersion == other.compilerVersion && ruleSetVersion == other.ruleSetVersion && governanceClosureHash == other.governanceClosureHash && catalogHash == other.catalogHash && catalogs == other.catalogs
    override fun hashCode(): Int = listOf(releaseId, releaseVersion, subjectAreaId, subjectAreaVersion, compilerVersion, ruleSetVersion, governanceClosureHash, catalogHash, catalogs).hashCode()
}

data class ReleaseClosureEntry(val objectType: String, val objectId: String, val objectVersion: Long,
    val objectCode: String?, val objectDisplayName: String?, val refRole: String, val snapshotModel: String)

class ReleaseClosure(entries: List<ReleaseClosureEntry>) {
    val entries: List<ReleaseClosureEntry> = java.util.List.copyOf(entries)
    override fun equals(other: Any?): Boolean = other is ReleaseClosure && entries == other.entries
    override fun hashCode(): Int = entries.hashCode()
}
class ReleaseCatalogSnapshots(catalogs: List<ReleaseCatalogSnapshot>) {
    val catalogs: List<ReleaseCatalogSnapshot> = java.util.List.copyOf(catalogs)
    override fun equals(other: Any?): Boolean = other is ReleaseCatalogSnapshots && catalogs == other.catalogs
    override fun hashCode(): Int = catalogs.hashCode()
}
