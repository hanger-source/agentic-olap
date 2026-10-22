package com.olap.prototype.engine.catalog

import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.SetOutputContract

data class ExecutableLogicUnit(val logicUnitId: String, val logicUnitVersion: String, val valueType: ScalarValueType?,
    val valueShape: SetOutputContract.Shape?, val topologyId: String?, val topologyVersion: String?, val logicUnitCode: String,
    val displayName: String?, val physicalExecutionBindingId: String, val physicalExecutionBindingVersion: String,
    val outputSymbols: List<String> = emptyList())
