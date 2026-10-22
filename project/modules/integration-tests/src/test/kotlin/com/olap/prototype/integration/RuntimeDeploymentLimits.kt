package com.olap.prototype.integration

import com.olap.prototype.runtime.JsonResourceLimits
import com.olap.prototype.warehouse.ResultMaterializationBudget
import com.olap.prototype.warehouse.ResultMaterializationLimits
import com.olap.prototype.warehouse.WarehouseExecutionControl
import java.util.concurrent.CancellationException

/** 数据库行为共用明确预算，进程部署通过正式服务配置表达。 */
object RuntimeDeploymentLimits {
    val io = JsonResourceLimits(32 * 1024 * 1024, 64 * 1024 * 1024, 256, 2_000_000, 128 * 1024 * 1024, 256)
    val result = ResultMaterializationLimits(1_000_000, 8_000_000, 128 * 1024 * 1024, 64)
    val nativeMemoryBytes = 256L * 1024 * 1024
    fun budget() = ResultMaterializationBudget(result)

    /** 固定小源准备不设整套试验共用的到期时刻；真实期限试验直接持有DeadlineExecutionControl。 */
    val CONTROL = object : WarehouseExecutionControl {
        override fun cancellationEvidence() = if (Thread.currentThread().isInterrupted)
            com.olap.prototype.warehouse.contract.SourceCancellationEvidence.Unconfirmed("trial interrupted without a source terminal receipt")
            else com.olap.prototype.warehouse.contract.SourceCancellationEvidence.NotRequested
        override fun checkCancellation() { if (Thread.currentThread().isInterrupted) throw CancellationException("trial interrupted") }
        override fun registerCancellationAction(action: WarehouseExecutionControl.CancellationAction): WarehouseExecutionControl.Registration {
            checkCancellation()
            return WarehouseExecutionControl.Registration { }
        }
    }
}
