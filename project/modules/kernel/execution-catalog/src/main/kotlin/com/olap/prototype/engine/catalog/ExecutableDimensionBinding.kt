package com.olap.prototype.engine.catalog

import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.expression.SemanticExpression

data class ExecutableDimensionBinding(val bindingId: String, val bindingVersion: String, val dimensionId: String,
    val dimensionVersion: String, val eventId: String, val eventVersion: String, val topologyId: String, val topologyVersion: String,
    val logicUnitId: String, val logicUnitVersion: String, val expression: SemanticExpression, val accessPath: RelationAccessPath,
    val roles: Map<String, RelationAccessPath>, val joinPathRefs: List<ExecutableJoinPathRef>,
    val physicalExecutionBindingId: String, val physicalExecutionBindingVersion: String)
