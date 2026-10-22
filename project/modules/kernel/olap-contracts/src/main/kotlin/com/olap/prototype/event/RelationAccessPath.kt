package com.olap.prototype.event

import com.olap.prototype.contract.ProducerContracts

/** 从局部关系位置按顺序访问精确连接；有界自关联允许重复资产。 */
class RelationAccessPath(joinPathRefs: List<JoinRef>) {
    val joinPathRefs: List<JoinRef> = java.util.List.copyOf(joinPathRefs)
    init { require(this.joinPathRefs.size <= 16) { "role access path must be finite and bounded" } }
    data class JoinRef(val joinPathId: String, val joinPathVersion: Long) {
        init { ProducerContracts.requireText(joinPathId, "role path JoinPath"); ProducerContracts.requireVersion(joinPathVersion, "role path JoinPath version") }
    }
    override fun equals(other: Any?): Boolean = other is RelationAccessPath && joinPathRefs == other.joinPathRefs
    override fun hashCode(): Int = joinPathRefs.hashCode()
}
