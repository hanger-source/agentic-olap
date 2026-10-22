package com.olap.prototype.query.contract

import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.warehouse.contract.SealedSourceDataset

/** 分析可见的有限源依据，不携带连接、原生表名或源集合代码。 */
class SealedSourceCollectionMeaning(val id: String, val sourceAuthority: String, val scope: SealedSourceDataset.Scope,
    ruleIds: List<String>, val meaning: String) {
    val ruleIds: List<String> = java.util.List.copyOf(ruleIds)
    init {
        ProducerContracts.requireText(id, "source collection id"); ProducerContracts.requireText(sourceAuthority, "source authority")
        require(this.ruleIds.size in 1..64 && this.ruleIds.none { it.isBlank() } && this.ruleIds.distinct().size == this.ruleIds.size) { "finite source requires distinct authority rule references" }
        ProducerContracts.requireText(meaning, "finite source meaning")
    }
    override fun equals(other: Any?): Boolean = other is SealedSourceCollectionMeaning && id == other.id && sourceAuthority == other.sourceAuthority && scope == other.scope && ruleIds == other.ruleIds && meaning == other.meaning
    override fun hashCode(): Int = listOf(id, sourceAuthority, scope, ruleIds, meaning).hashCode()
    companion object {
        const val MEANING = "有限源登记集合；仅说明相应物理位置的源集合范围，事件谓词、关系路径、独立时钟与查询条件仍分别约束输入，不证明连接后人口、现实全集、未来覆盖或默认零值。"
    }
}
