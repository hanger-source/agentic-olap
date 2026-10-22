package com.olap.prototype.query.definition

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.query.contract.QueryCapabilityView

fun interface NativeValidator {
    fun validate(intent: JsonNode, capability: QueryCapabilityView)
}
