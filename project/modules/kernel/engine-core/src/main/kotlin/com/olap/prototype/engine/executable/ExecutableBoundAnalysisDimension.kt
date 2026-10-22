package com.olap.prototype.engine.executable

import com.olap.prototype.engine.catalog.*
import com.olap.prototype.query.ScalarValueType

data class ExecutableBoundAnalysisDimension(val analysisDimension: ExecutableAnalysisDimension, val outputAlias: String,
    val levelCode: String, val event: ExecutableEvent, val dimension: ExecutableDimension, val binding: ExecutableDimensionBinding,
    val logicUnit: ExecutableLogicUnit, val valueType: ScalarValueType, val joinPaths: List<ExecutableJoinPath>,
    val physicalExecutionBinding: ExecutablePhysicalExecutionBinding, val filterOnly: Boolean,
    val valueAlignmentKey: String?, val valueAlignmentStatus: String)
