package com.olap.prototype.engine.catalog

import com.olap.prototype.query.PostOperationContract

/** 已实现的共享阶段语言；方言、类型及数据条件仍在实际编译执行时核验。 */
object ExecutablePostOperationCapabilities {
    private val supported = PostOperationContract.operationTypes().toSet()
    fun supports(operationType: String?): Boolean = operationType in supported
}
