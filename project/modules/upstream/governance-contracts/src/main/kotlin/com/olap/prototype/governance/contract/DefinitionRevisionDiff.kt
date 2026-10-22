package com.olap.prototype.governance.contract

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.contract.ProducerContracts

data class DefinitionRevisionDiffRequest(val assetType: String, val id: String, val fromVersion: Long, val toVersion: Long) {
    init {
        DefinitionImpactRequest(assetType, id, fromVersion)
        ProducerContracts.requireVersion(toVersion, "toVersion")
    }
}

/** 精确正文、所拥有的成员、授权和依赖的结构差异；不判断业务含义等价。 */
data class DefinitionRevisionDiff(
    val before: DefinitionImpact.Reference,
    val after: DefinitionImpact.Reference,
    val changes: List<Change>,
    val addedDependencies: List<DefinitionImpact.Reference>,
    val removedDependencies: List<DefinitionImpact.Reference>,
) {
    enum class Classification { IDENTITY_OR_STRUCTURE, DEPENDENCY, AUTHORIZATION, DISPLAY, CONTENT }
    data class Change(val path: String, val classification: Classification, val before: JsonNode?, val after: JsonNode?)
}
