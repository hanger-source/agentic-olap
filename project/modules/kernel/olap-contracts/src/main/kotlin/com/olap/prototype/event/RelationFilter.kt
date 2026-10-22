package com.olap.prototype.event

import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.query.ScopedInputContract

class RelationFilter(val id: String, val version: Long, val parent: EventRef, val relationshipRole: String, val memberSourceId: String, val memberSourceVersion: Long, clockSlots: Map<String, String>) {
    val clockSlots: Map<String, String> = java.util.Map.copyOf(clockSlots)
    init {
        MetricDefinition.text(id, "relation filter id"); MetricDefinition.version(version)
        ScopedInputContract.requireId(relationshipRole)
        MetricDefinition.text(memberSourceId, "related member source"); MetricDefinition.version(memberSourceVersion)
        this.clockSlots.forEach { (name, position) -> MetricDefinition.text(name, "relation clock alias"); MetricDefinition.text(position, "relation clock position") }
    }
    override fun equals(other: Any?): Boolean = other is RelationFilter && id == other.id && version == other.version && parent == other.parent && relationshipRole == other.relationshipRole && memberSourceId == other.memberSourceId && memberSourceVersion == other.memberSourceVersion && clockSlots == other.clockSlots
    override fun hashCode(): Int = listOf(id, version, parent, relationshipRole, memberSourceId, memberSourceVersion, clockSlots).hashCode()
}
