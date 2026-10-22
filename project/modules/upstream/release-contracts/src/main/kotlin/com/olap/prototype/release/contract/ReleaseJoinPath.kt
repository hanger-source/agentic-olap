package com.olap.prototype.release.contract

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.query.SourceJoinContract

data class ReleaseJoinPathRef(val joinPathId: String, val joinPathVersion: Long) {
    init { ProducerContracts.requireText(joinPathId, "joinPathId"); ProducerContracts.requireVersion(joinPathVersion, "joinPathVersion") }
}
class ReleaseJoinPathSnapshot(val joinPathId: String, val joinPathVersion: Long, val topologyId: String, val topologyVersion: Long, val sourceLogicalTableId: String, val sourceLogicalTableVersion: Long, val targetLogicalTableId: String, val targetLogicalTableVersion: Long, val joinType: String, val cardinality: String, val temporalConfigJson: String?, val nullPolicy: String, keys: List<Key>) {
    val keys: List<Key> = ProducerContracts.requireList(keys, "keys")
    init {
        ProducerContracts.requireText(joinPathId, "joinPathId"); ProducerContracts.requireVersion(joinPathVersion, "joinPathVersion")
        ProducerContracts.requireText(topologyId, "topologyId"); ProducerContracts.requireVersion(topologyVersion, "topologyVersion")
        ProducerContracts.requireText(sourceLogicalTableId, "sourceLogicalTableId"); ProducerContracts.requireVersion(sourceLogicalTableVersion, "sourceLogicalTableVersion")
        ProducerContracts.requireText(targetLogicalTableId, "targetLogicalTableId"); ProducerContracts.requireVersion(targetLogicalTableVersion, "targetLogicalTableVersion")
        ProducerContracts.requireText(joinType, "joinType"); ProducerContracts.requireText(cardinality, "cardinality"); SourceJoinContract.require(joinType, cardinality, nullPolicy)
        if (this.keys.isEmpty()) throw ProducerContractException("join path keys 不能为空: $joinPathId@$joinPathVersion")
    }
    data class Key(val order: Int, val sourceLogicUnitId: String, val sourceLogicUnitVersion: Long, val targetLogicUnitId: String, val targetLogicUnitVersion: Long) {
        init {
            if (order < 0) throw ProducerContractException("join path key order 必须大于等于 0")
            ProducerContracts.requireText(sourceLogicUnitId, "sourceLogicUnitId"); ProducerContracts.requireVersion(sourceLogicUnitVersion, "sourceLogicUnitVersion")
            ProducerContracts.requireText(targetLogicUnitId, "targetLogicUnitId"); ProducerContracts.requireVersion(targetLogicUnitVersion, "targetLogicUnitVersion")
        }
    }
    override fun equals(other: Any?): Boolean = other is ReleaseJoinPathSnapshot && joinPathId == other.joinPathId && joinPathVersion == other.joinPathVersion && topologyId == other.topologyId && topologyVersion == other.topologyVersion && sourceLogicalTableId == other.sourceLogicalTableId && sourceLogicalTableVersion == other.sourceLogicalTableVersion && targetLogicalTableId == other.targetLogicalTableId && targetLogicalTableVersion == other.targetLogicalTableVersion && joinType == other.joinType && cardinality == other.cardinality && temporalConfigJson == other.temporalConfigJson && nullPolicy == other.nullPolicy && keys == other.keys
    override fun hashCode(): Int = listOf(joinPathId, joinPathVersion, topologyId, topologyVersion, sourceLogicalTableId, sourceLogicalTableVersion, targetLogicalTableId, targetLogicalTableVersion, joinType, cardinality, temporalConfigJson, nullPolicy, keys).hashCode()
}
