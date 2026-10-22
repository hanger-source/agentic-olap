package com.olap.prototype.engine.executable

import com.olap.prototype.engine.catalog.ExecutablePhysicalExecutionBinding
import com.olap.prototype.engine.catalog.ExecutableRelationPosition.Expression
import com.olap.prototype.engine.catalog.ExecutableRelationPosition.Id
import com.olap.prototype.query.TemporalClockBindings

/** 外层位置仅作相关读取，不进入量词局部扫描。 */
data class ResolvedQuantifier(val parent: Id, val predicate: Expression, val positions: Map<Id, ExecutablePhysicalExecutionBinding>,
    val joins: List<ExecutablePhysicalJoin>, val clocks: Map<String, TemporalClockBindings.Value>, val quantifiers: Map<List<String>, ResolvedQuantifier>)
