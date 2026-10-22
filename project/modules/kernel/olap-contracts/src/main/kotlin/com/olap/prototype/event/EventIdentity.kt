package com.olap.prototype.event

import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.query.ScopedInputContract
import java.util.Collections
import java.util.TreeMap

data class EventRef(val eventId: String, val eventVersion: Long) {
    init { require(eventId.isNotBlank()) { "eventId is required" }; require(eventVersion > 0) { "exact positive version is required" } }
}

/** 声明粒度与实体角色；空记录键表示未声明，声明不等于源已保证唯一。 */
class EventIdentity(recordKey: List<LogicUnitRef>, entityRoles: Map<String, List<LogicUnitRef>>) {
    val recordKey: List<LogicUnitRef> = java.util.List.copyOf(recordKey)
    val entityRoles: Map<String, List<LogicUnitRef>> = Collections.unmodifiableMap(TreeMap<String, List<LogicUnitRef>>().apply {
        entityRoles.forEach { (role, references) ->
            ScopedInputContract.requireId(role)
            require(references.isNotEmpty() && references.distinct().size == references.size) { "event identity requires nonempty distinct exact keys" }
            put(role, java.util.List.copyOf(references))
        }
    })
    init { require(this.recordKey.distinct().size == this.recordKey.size) { "declared event record keys must be distinct" } }
    data class LogicUnitRef(val logicUnitId: String, val logicUnitVersion: Long) {
        init { ProducerContracts.requireText(logicUnitId, "identity LogicUnit"); ProducerContracts.requireVersion(logicUnitVersion, "identity LogicUnit version") }
    }
    override fun equals(other: Any?): Boolean = other is EventIdentity && recordKey == other.recordKey && entityRoles == other.entityRoles
    override fun hashCode(): Int = listOf(recordKey, entityRoles).hashCode()
}
