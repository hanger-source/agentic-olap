package com.olap.prototype.engine.executable

import com.olap.prototype.engine.catalog.ExecutableJoinPath
import com.olap.prototype.engine.catalog.ExecutableRelationPosition.Id

data class ExecutablePhysicalJoin(val joinPath: ExecutableJoinPath, val source: Id, val target: Id,
    val sourceTimes: Map<Int, Id>, val clockSlots: Map<Int, String>, val requiredMatch: Boolean) {
    init { require(source != target) { "join sides require independent relation positions" } }
}
