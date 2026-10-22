package com.olap.prototype.governance.contract

import java.util.Collections

/** 服务读取入口的完整请求；脚本及通信边界共同采用这些正式类型。 */
object GovernanceReadRequests {
    data class AssetVersionRequest(val assetType: String?, val id: String?, val version: Long)
    data class DefinitionDependenciesRequest(val assetType: String, val id: String, val version: Long, val offset: Int, val limit: Int)
    data class ReleaseIdentityRequest(val releaseId: String, val releaseVersion: String)
    class Page<T>(items: List<T>, val total: Int, val nextOffset: Int?) { val items: List<T> = java.util.List.copyOf(items) }
    class EmptyInput
    data class OperationInput(val operation: String?)
    class DescribeCommandInput(val operation: String?, path: List<String>?, val variant: String?) {
        val path: List<String>? = path?.let { Collections.unmodifiableList(ArrayList(it)) }
    }
    data class PageInput(val offset: Int?, val limit: Int?)
    data class SourceDiscoveriesInput(val dataSourceId: String?, val catalog: String?, val schemaName: String?, val offset: Int?, val limit: Int?)
    data class SnapshotInput(val snapshotId: String?)
    data class SnapshotPageInput(val snapshotId: String?, val offset: Int?, val limit: Int?)
    data class DiscoveredTablePageInput(val discoveredTableId: String?, val offset: Int?, val limit: Int?)
    data class SourcePageInput(val dataSourceId: String?, val offset: Int?, val limit: Int?)
    data class IdentityInput(val id: String?)
    data class VersionInput(val id: String?, val version: Long)
    data class VersionPageInput(val id: String?, val version: Long, val offset: Int?, val limit: Int?)
    data class SubjectPageInput(val subjectAreaId: String?, val offset: Int?, val limit: Int?)

}
