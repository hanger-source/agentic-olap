package com.olap.prototype.engine.executable

import com.olap.prototype.engine.catalog.ExecutableCatalog
import com.olap.prototype.warehouse.WarehouseExecutionControl

interface ExecutableEngineExecutionPort {
    fun execute(catalog: ExecutableCatalog, request: ExecutableQueryRequest, observer: ExecutableExecutionObserver, executionControl: WarehouseExecutionControl): ExecutableExecutionResult
}
