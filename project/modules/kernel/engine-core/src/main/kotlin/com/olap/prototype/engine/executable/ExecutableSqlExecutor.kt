package com.olap.prototype.engine.executable

import com.olap.prototype.connector.SourceExecutionResult
import com.olap.prototype.engine.context.OlapQueryContext
import com.olap.prototype.warehouse.WarehouseExecutionControl

fun interface ExecutableSqlExecutor {
    fun execute(loweringResult: ExecutableLoweringResult, context: OlapQueryContext, executionControl: WarehouseExecutionControl): SourceExecutionResult
}
