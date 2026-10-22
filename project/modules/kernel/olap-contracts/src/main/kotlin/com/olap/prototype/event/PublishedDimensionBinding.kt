package com.olap.prototype.event

import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.query.ScopedInputContract

/** 精确维度事件表达；角色与局部位置归实际使用入口。 */
class PublishedDimensionBinding(val id: String, val version: Long, val dimensionId: String, val dimensionVersion: Long, val event: EventRef,
    val topologyId: String, val topologyVersion: Long, val logicUnitId: String, val logicUnitVersion: Long,
    val expression: SemanticExpression, val accessPath: RelationAccessPath, roles: Map<String, RelationAccessPath>) {
    val roles: Map<String, RelationAccessPath> = java.util.Map.copyOf(roles)
    init {
        MetricDefinition.text(id, "binding id"); MetricDefinition.version(version)
        MetricDefinition.text(dimensionId, "semantic dimension"); MetricDefinition.version(dimensionVersion)
        MetricDefinition.text(topologyId, "topology"); MetricDefinition.version(topologyVersion)
        MetricDefinition.text(logicUnitId, "binding LogicUnit"); MetricDefinition.version(logicUnitVersion)
        this.roles.keys.forEach { ScopedInputContract.requireId(it) }
    }
    override fun equals(other: Any?): Boolean = other is PublishedDimensionBinding && id == other.id && version == other.version && dimensionId == other.dimensionId && dimensionVersion == other.dimensionVersion && event == other.event && topologyId == other.topologyId && topologyVersion == other.topologyVersion && logicUnitId == other.logicUnitId && logicUnitVersion == other.logicUnitVersion && expression == other.expression && accessPath == other.accessPath && roles == other.roles
    override fun hashCode(): Int = listOf(id, version, dimensionId, dimensionVersion, event, topologyId, topologyVersion, logicUnitId, logicUnitVersion, expression, accessPath, roles).hashCode()
}
