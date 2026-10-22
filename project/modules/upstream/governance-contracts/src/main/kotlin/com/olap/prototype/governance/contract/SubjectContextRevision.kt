package com.olap.prototype.governance.contract

/** 明确选择精确主题版本中的已有采用；空集合表示不延续，不猜测依赖或自动扩大授权。 */
class SubjectContextSelection(metrics: List<String>, members: List<String>, dimensions: List<String>, scenarios: List<String>) {
    val metrics: List<String> = selection(metrics)
    val members: List<String> = selection(members)
    val dimensions: List<String> = selection(dimensions)
    val scenarios: List<String> = selection(scenarios)

    private fun selection(ids: List<String>): List<String> {
        require(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size) { "context selection requires distinct exact identities" }
        return java.util.List.copyOf(ids)
    }
}

data class SubjectContextSelectionRequest(val objectId: String, val sourceVersion: Long)
data class SubjectContextSelectionView(val objectId: String, val sourceVersion: Long, val selection: SubjectContextSelection)
data class SubjectContextRevisionRequest(val objectId: String, val sourceVersion: Long,
    val definition: SubjectAreaCreateRequest, val carry: SubjectContextSelection)
data class ContextRevisionReference(val assetType: String, val id: String, val sourceVersion: Long, val targetVersion: Long)
class SubjectContextRevisionResult(val subjectArea: SubjectAreaCreateResult, carried: List<ContextRevisionReference>) {
    val carried: List<ContextRevisionReference> = java.util.List.copyOf(carried)
}
