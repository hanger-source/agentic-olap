package com.olap.prototype.dsl

import java.math.BigDecimal
import java.util.Collections

/** 脚本参数和普通返回值；不允许把 JVM 对象、闭包或类当成持久语义。 */
sealed interface ScriptValue {
    data object Null : ScriptValue
    data class BooleanValue(val value: Boolean) : ScriptValue
    data class Text(val value: String) : ScriptValue
    class DecimalValue(val value: BigDecimal) : ScriptValue {
        constructor(value: String) : this(decimal(value))
        override fun equals(other: Any?): Boolean = other is DecimalValue && value == other.value
        override fun hashCode(): Int = value.hashCode()
    }
    class ArrayValue(values: List<ScriptValue>) : ScriptValue {
        val values: List<ScriptValue> = java.util.List.copyOf(values)
        override fun equals(other: Any?): Boolean = other is ArrayValue && values == other.values
        override fun hashCode(): Int = values.hashCode()
    }
    class ObjectValue(values: Map<String, ScriptValue>) : ScriptValue {
        val values: Map<String, ScriptValue> = Collections.unmodifiableMap(values.toSortedMap())
        override fun equals(other: Any?): Boolean = other is ObjectValue && values == other.values
        override fun hashCode(): Int = values.hashCode()
    }
    /** 精确引用只携带已有正式身份；不授予对所引用对象的新权限。 */
    sealed interface ReferenceValue : ScriptValue
    data class QueryReferenceValue(val reference: com.olap.prototype.query.contract.QueryReference) : ReferenceValue
    data class ResultReferenceValue(val reference: com.olap.prototype.query.contract.QueryResultReference) : ReferenceValue
    data class MetricReferenceValue(val reference: com.olap.prototype.calculation.MetricRef) : ReferenceValue
    data class MetricInstanceReferenceValue(val reference: com.olap.prototype.calculation.MetricInstanceRef) : ReferenceValue
    data class MemberReferenceValue(val reference: com.olap.prototype.member.MemberRef) : ReferenceValue
    data class EventReferenceValue(val reference: com.olap.prototype.event.EventRef) : ReferenceValue
    data class DimensionReferenceValue(val reference: com.olap.prototype.query.ScopedInputContract.DimensionRef) : ReferenceValue
    data class SourceReferenceValue(val reference: com.olap.prototype.warehouse.contract.SourceBindingRef) : ReferenceValue
    data class ConnectorReferenceValue(val reference: com.olap.prototype.connector.ConnectorRef) : ReferenceValue
    /** 只能由正式客户端为已注册返回合同产生，客户端不会选择任意类加载。 */
    class RegisteredModel internal constructor(val operationId: String, content: ByteArray) : ScriptValue {
        private val bytes = content.copyOf()
        val content: ByteArray get() = bytes.copyOf()
        val contentSize: Int get() = bytes.size
        init { require(operationId.isNotBlank()) }
        override fun equals(other: Any?): Boolean = other is RegisteredModel && operationId == other.operationId && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = 31 * operationId.hashCode() + bytes.contentHashCode()
    }
    companion object {
        fun contract(): Map<String, Any> = mapOf(
            "appliesTo" to listOf("SCRIPT_ARGUMENTS", "ORDINARY_SCRIPT_RESULTS"),
            "representation" to mapOf("decimal" to "EXACT_JAVA_BIG_DECIMAL_VALUE_AND_SCALE",
                "text" to "JVM_STRING", "objectKeys" to "ANY_STRING_INCLUDING_EMPTY_AND_WHITESPACE",
                "collections" to "IMMUTABLE_DEFENSIVE_COPY",
                "violationCode" to "SCRIPT_VALUE_DECIMAL_REPRESENTATION"),
            "modelCapacity" to "NO_FIXED_NODE_DEPTH_TEXT_KEY_OR_DECIMAL_CAPACITY_LIMITS",
            "inputBudget" to mapOf("owner" to "ACTUAL_JSON_INPUT_LIMITS_BEFORE_SCRIPT_EVALUATION",
                "rootDepth" to 1, "rootCounted" to true, "containersCounted" to true, "nullCounted" to true,
                "objectKeysCounted" to false, "rejectionCount" to "FIRST_EXCESS"),
            "outputDelivery" to "COMPLETE_INLINE_RESPONSE_WHEN_IT_FITS_OTHERWISE_AUTOMATIC_COMPLETE_FILE_DELIVERY",
            "intermediateValues" to "JVM_EXECUTION_RESOURCES_APPLY_WITHOUT_A_SEPARATE_FIXED_MODEL_LIMIT",
        )

        private fun decimal(value: String): BigDecimal = try { BigDecimal(value) }
            catch (_: NumberFormatException) { throw ScriptValueException(ScriptValueException.Reason.DECIMAL_REPRESENTATION,
                emptyList(), "JAVA_BIG_DECIMAL_TEXT", "UNREPRESENTABLE_DECIMAL") }
    }
}

class ScriptArguments(values: Map<String, ScriptValue>) {
    val values: Map<String, ScriptValue> = ScriptValue.ObjectValue(values).values
    operator fun get(name: String): ScriptValue = values[name] ?: error("script argument is missing: $name")
    fun text(name: String): String = (get(name) as? ScriptValue.Text)?.value ?: error("script argument must be text: $name")
    fun decimal(name: String): BigDecimal = (get(name) as? ScriptValue.DecimalValue)?.value ?: error("script argument must be decimal: $name")
    fun boolean(name: String): Boolean = (get(name) as? ScriptValue.BooleanValue)?.value ?: error("script argument must be boolean: $name")
}
