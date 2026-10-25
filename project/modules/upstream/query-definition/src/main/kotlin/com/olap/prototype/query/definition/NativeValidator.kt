package com.olap.prototype.query.definition

import com.olap.prototype.query.QueryExecutionMode
import com.olap.prototype.query.contract.QueryCapabilityView
import com.olap.prototype.query.contract.QueryIntent

fun interface NativeValidator {
    fun validate(intent: QueryIntent, capability: QueryCapabilityView, executionMode: QueryExecutionMode)
}
