package com.olap.prototype.engine.catalog

import com.olap.prototype.event.EventIdentity
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.expression.SemanticExpression

data class ExecutableEvent(val eventId: String, val eventVersion: String, val topologyId: String, val topologyVersion: String,
    val displayName: String?, val identity: EventIdentity, val roles: Map<String, RelationAccessPath>, val baseFilter: SemanticExpression?,
    val timeLogicUnitId: String?, val timeLogicUnitVersion: String?)
