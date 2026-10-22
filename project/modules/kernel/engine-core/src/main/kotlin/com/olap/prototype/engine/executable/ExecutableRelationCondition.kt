package com.olap.prototype.engine.executable

import com.olap.prototype.event.RelationFilter

data class ExecutableRelationCondition(val instanceId: String, val definition: RelationFilter, val input: ExecutableMemberInput.Projection)
