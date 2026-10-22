package com.olap.prototype.warehouse

/** 引擎与来源访问实现共享的取消边界。 */
interface WarehouseExecutionControl {
    fun checkCancellation()
    fun cancellationEvidence(): com.olap.prototype.warehouse.contract.SourceCancellationEvidence
    fun registerCancellationAction(action: CancellationAction): Registration
    fun interface CancellationAction { fun cancel() }
    fun interface Registration : AutoCloseable { override fun close() }
}
