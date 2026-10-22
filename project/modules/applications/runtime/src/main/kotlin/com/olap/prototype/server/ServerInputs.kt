package com.olap.prototype.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.util.OlapJsonUtils

internal object ServerInputs {
    fun fields(payload: JsonNode, allowed: Set<String>) {
        if (!payload.isObject) throw ProducerContractException("payload must be an object")
        payload.fieldNames().forEachRemaining {
            if (it !in allowed) throw ProducerContractException("unsupported payload field: $it")
        }
    }

    fun text(value: JsonNode, field: String): String {
        val result = value.get(field)
        if (result == null || !result.isTextual || result.textValue().isBlank())
            throw ProducerContractException("$field is required")
        return result.textValue()
    }

    fun optionalText(value: JsonNode, field: String): String? =
        if (!value.has(field) || value.get(field).isNull) null else text(value, field)

    fun integer(value: JsonNode, field: String, default: Int, minimum: Int, maximum: Int): Int {
        val number = value.get(field) ?: return default
        if (!number.isIntegralNumber || !number.canConvertToInt() || number.intValue() !in minimum..maximum)
            throw ProducerContractException("invalid $field")
        return number.intValue()
    }

    fun command(operation: String, payload: JsonNode): ObjectNode =
        OlapJsonUtils.mapper().createObjectNode().put("operation", operation).set("payload", payload)
}
