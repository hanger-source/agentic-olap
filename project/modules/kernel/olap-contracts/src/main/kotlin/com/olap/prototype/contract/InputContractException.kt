package com.olap.prototype.contract

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import java.util.Collections
import java.util.IdentityHashMap

/** 作者输入合同的公开拒绝；携带规则、位置和形态，不携带输入值或解析器堆栈。 */
class InputContractException private constructor(val rule: Rule, message: String, val details: Map<String, Any?>, cause: Throwable? = null) :
    ProducerContractException(message, cause) {
    enum class Rule(val code: String, val stage: String) {
        DECODE_INPUT("INVALID_REQUEST", "DECODE_INPUT"),
        DIMENSION_SLOT_REQUIRED("CALCULATION_DIMENSION_SLOT_REQUIRED", "CONTRACT_INPUT"),
        MEASUREMENT_BINDING_OVERRIDE("MEASUREMENT_BINDING_OVERRIDE", "CONTRACT_INPUT"),
        ROOT_INPUT_REQUIRED("ROOT_INPUT_REQUIRED", "AUTHORIZE_INPUT"),
    }
    companion object {
        fun violation(rule: Rule, path: List<Any>, message: String, expectedKind: String, actualKind: String): InputContractException =
            InputContractException(rule, message, mapOf("fieldPath" to path.toList(), "expectedKind" to expectedKind, "actualKind" to actualKind))

        fun decoding(failure: JsonProcessingException, input: JsonNode, prefix: List<String> = emptyList()): InputContractException {
            val path = (failure as? JsonMappingException)?.path.orEmpty().mapNotNull { reference ->
                reference.fieldName ?: reference.index.takeIf { it >= 0 }
            }
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            var cause: Throwable? = failure.cause
            while (cause != null && seen.add(cause)) {
                if (cause is InputContractException) return InputContractException(cause.rule, cause.message,
                    cause.details + ("fieldPath" to (prefix + path + (cause.details["fieldPath"] as List<*>))), failure)
                cause = cause.cause
            }
            var actual = input
            for (component in path) actual = when (component) {
                is String -> actual.path(component)
                is Int -> actual.path(component)
                else -> error("unsupported input path component")
            }
            return InputContractException(Rule.DECODE_INPUT, "input does not match the declared contract", buildMap {
                put("fieldPath", prefix + path)
                (failure as? MismatchedInputException)?.targetType?.let { put("expectedType", shape(it)) }
                put("actualType", if (actual.isMissingNode) "missing" else actual.nodeType.name.lowercase())
            }, failure)
        }

        private fun shape(type: Class<*>): String = when {
            type == String::class.java || type.isEnum -> "string"
            type == Boolean::class.java || type == java.lang.Boolean::class.java -> "boolean"
            type.isPrimitive || Number::class.java.isAssignableFrom(type) -> "number"
            type.isArray || Collection::class.java.isAssignableFrom(type) -> "array"
            else -> "object"
        }
    }
}
