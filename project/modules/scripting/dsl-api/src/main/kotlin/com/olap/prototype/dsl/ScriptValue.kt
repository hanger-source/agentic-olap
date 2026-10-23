package com.olap.prototype.dsl

import java.math.BigDecimal
import java.util.Collections

/** 脚本参数和普通返回值；不允许把 JVM 对象、闭包或类当成持久语义。 */
sealed interface ScriptValue {
    data object Null : ScriptValue
    data class BooleanValue(val value: Boolean) : ScriptValue
    data class Text(val value: String) : ScriptValue {
        init { limit(ScriptValueException.Reason.TEXT_LENGTH, "UTF16_CODE_UNITS", MAX_TEXT, value.length.toLong()) }
    }
    class DecimalValue(val value: BigDecimal) : ScriptValue {
        constructor(value: String) : this(decimal(value))
        init {
            limit(ScriptValueException.Reason.DECIMAL_PRECISION, "PRECISION", MAX_TEXT, value.precision().toLong())
            limit(ScriptValueException.Reason.DECIMAL_SCALE, "ABS_SCALE", MAX_TEXT, kotlin.math.abs(value.scale().toLong()))
        }
        override fun equals(other: Any?): Boolean = other is DecimalValue && value == other.value
        override fun hashCode(): Int = value.hashCode()
    }
    class ArrayValue(values: List<ScriptValue>) : ScriptValue {
        val values: List<ScriptValue> = java.util.List.copyOf(values)
        init { checkSize(this.values) }
        override fun equals(other: Any?): Boolean = other is ArrayValue && values == other.values
        override fun hashCode(): Int = values.hashCode()
    }
    class ObjectValue(values: Map<String, ScriptValue>) : ScriptValue {
        val values: Map<String, ScriptValue> = Collections.unmodifiableMap(values.toSortedMap())
        init {
            this.values.keys.forEach { key ->
                if (key.isBlank()) throw ScriptValueException(ScriptValueException.Reason.OBJECT_KEY_BLANK, emptyList(), "NON_BLANK_KEY", "BLANK_KEY")
                limit(ScriptValueException.Reason.OBJECT_KEY_LENGTH, "KEY_UTF16_CODE_UNITS", MAX_KEY, key.length.toLong())
            }
            checkSize(this.values.values)
        }
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
        init { require(operationId.isNotBlank()) }
        override fun equals(other: Any?): Boolean = other is RegisteredModel && operationId == other.operationId && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = 31 * operationId.hashCode() + bytes.contentHashCode()
    }
    companion object {
        private const val MAX_TEXT = 1_000_000
        private const val MAX_NODES = 10_000
        private const val MAX_DEPTH = 32
        private const val MAX_KEY = 256

        fun contract(): Map<String, Any> = mapOf(
            "appliesTo" to listOf("SCRIPT_ARGUMENTS", "ORDINARY_SCRIPT_RESULTS"),
            "structure" to mapOf("maximumDescendantNodes" to MAX_NODES, "maximumDescendantDepth" to MAX_DEPTH,
                "containerIncluded" to false, "objectKeysCounted" to false, "nullCounted" to true,
                "containersCounted" to true, "directChildDepth" to 1, "rejectionCount" to "FIRST_EXCESS",
                "scope" to "EACH_CONTAINER_AND_ALL_SCRIPT_ARGUMENTS"),
            "text" to mapOf("maximumUtf16CodeUnits" to MAX_TEXT),
            "objectKeys" to mapOf("nonBlank" to true, "maximumUtf16CodeUnits" to MAX_KEY),
            "decimal" to mapOf("maximumPrecision" to MAX_TEXT, "maximumAbsoluteScale" to MAX_TEXT),
            "deploymentBudgets" to "JSON_INPUT_AND_PROTOBUF_OUTPUT_BUDGETS_APPLY_INDEPENDENTLY",
        )

        private fun decimal(value: String): BigDecimal = try { BigDecimal(value) }
            catch (_: NumberFormatException) { throw ScriptValueException(ScriptValueException.Reason.DECIMAL_SYNTAX,
                emptyList(), "DECIMAL_TEXT", "INVALID_DECIMAL") }

        private fun limit(reason: ScriptValueException.Reason, measure: String, maximum: Int, observed: Long) {
            if (observed > maximum) throw ScriptValueException(reason, emptyList(), "$measure<=$maximum", observed.toString())
        }

        private fun checkSize(values: Collection<ScriptValue>) {
            var nodes = 0
            fun visit(value: ScriptValue, depth: Int) {
                limit(ScriptValueException.Reason.DEPTH, "DESCENDANT_DEPTH", MAX_DEPTH, depth.toLong())
                limit(ScriptValueException.Reason.NODE_COUNT, "DESCENDANT_NODES", MAX_NODES, (++nodes).toLong())
                when (value) {
                    is ArrayValue -> value.values.forEach { visit(it, depth + 1) }
                    is ObjectValue -> value.values.values.forEach { visit(it, depth + 1) }
                    else -> Unit
                }
            }
            values.forEach { visit(it, 1) }
        }
    }
}

class ScriptArguments(values: Map<String, ScriptValue>) {
    val values: Map<String, ScriptValue> = ScriptValue.ObjectValue(values).values
    operator fun get(name: String): ScriptValue = values[name] ?: error("script argument is missing: $name")
    fun text(name: String): String = (get(name) as? ScriptValue.Text)?.value ?: error("script argument must be text: $name")
    fun decimal(name: String): BigDecimal = (get(name) as? ScriptValue.DecimalValue)?.value ?: error("script argument must be decimal: $name")
    fun boolean(name: String): Boolean = (get(name) as? ScriptValue.BooleanValue)?.value ?: error("script argument must be boolean: $name")
}
