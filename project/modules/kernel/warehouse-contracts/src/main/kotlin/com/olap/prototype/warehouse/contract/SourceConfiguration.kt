package com.olap.prototype.warehouse.contract

import com.olap.prototype.connector.ConnectorRef
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Collections
import java.util.HexFormat
import java.util.Locale
import java.util.TreeMap

/** 部署拥有的来源绑定。参数由插件解释，凭据只保存环境变量引用。 */
class SourceConfiguration(
    dataSourceId: String,
    val connectorRef: ConnectorRef,
    dialectId: String,
    identityParameters: Map<String, Any?>,
    runtimeParameters: Map<String, Any?>,
    credentialEnvironmentVariables: Map<String, String>,
) {
    val dataSourceId: String = text(dataSourceId, "dataSourceId")
    val dialectId: String = text(dialectId, "dialectId").lowercase(Locale.ROOT)
    val identityParameters: Map<String, Any?> = freezeMap(identityParameters, 0)
    val runtimeParameters: Map<String, Any?> = freezeMap(runtimeParameters, 0)
    init { require(this.identityParameters.keys.intersect(this.runtimeParameters.keys).isEmpty()) { "source parameter roles must be disjoint" } }
    val credentialEnvironmentVariables: Map<String, String> = Collections.unmodifiableMap(
        TreeMap<String, String>().apply {
            credentialEnvironmentVariables.forEach { (slot, variable) ->
                text(slot, "credential slot")
                require(variable.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                    "source credential must reference an environment variable"
                }
                put(slot, variable)
            }
        },
    )

    fun bindingRef(): SourceBindingRef = SourceBindingRef(dataSourceId, sourceIdentityHash())

    /** 发布只携带摘要；凭据值和凭据存储位置不成为可发布的源身份。 */
    fun sourceIdentityHash(): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            write(output, listOf("olap-source-binding/v1", dataSourceId, connectorRef.id,
                connectorRef.version, connectorRef.sha256, dialectId))
            write(output, identityParameters)
            write(output, credentialEnvironmentVariables.keys.toList())
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))
    }

    override fun equals(other: Any?): Boolean = other is SourceConfiguration &&
        dataSourceId == other.dataSourceId && connectorRef == other.connectorRef &&
        dialectId == other.dialectId && identityParameters == other.identityParameters && runtimeParameters == other.runtimeParameters &&
        credentialEnvironmentVariables == other.credentialEnvironmentVariables
    override fun hashCode(): Int = listOf(dataSourceId, connectorRef, dialectId, identityParameters, runtimeParameters, credentialEnvironmentVariables).hashCode()
    override fun toString(): String = "SourceConfiguration[dataSourceId=$dataSourceId, connectorRef=$connectorRef, dialectId=$dialectId, identityParameterKeys=${identityParameters.keys}, runtimeParameterKeys=${runtimeParameters.keys}, credentialSlots=${credentialEnvironmentVariables.keys}]"

    companion object {
        private fun text(value: String, label: String): String {
            require(value.isNotBlank()) { "$label is required" }
            return value.trim()
        }

        /** 深复制 JSON 值，避免外部对象在构造后改变精确来源身份。 */
        private fun freezeMap(values: Map<*, *>, depth: Int): Map<String, Any?> {
            require(depth <= 32) { "source parameters exceed nesting limit" }
            val result = TreeMap<String, Any?>()
            values.forEach { (key, value) ->
                require(key is String && key.isNotBlank()) { "source parameter keys must be nonempty strings" }
                result[key] = freeze(value, depth + 1)
            }
            return Collections.unmodifiableMap(result)
        }

        private fun freeze(value: Any?, depth: Int): Any? {
            require(depth <= 32) { "source parameters exceed nesting limit" }
            return when (value) {
                null, is String, is Boolean -> value
                is Number -> try { BigDecimal(value.toString()).stripTrailingZeros() }
                    catch (failure: NumberFormatException) { throw IllegalArgumentException("source parameters require finite JSON numbers", failure) }
                is Map<*, *> -> freezeMap(value, depth)
                is List<*> -> Collections.unmodifiableList(value.map { freeze(it, depth + 1) })
                else -> throw IllegalArgumentException("source parameters must contain JSON values")
            }
        }

        private fun write(output: DataOutputStream, value: Any?) {
            when (value) {
                null -> output.writeByte('N'.code)
                is String -> {
                    output.writeByte('S'.code)
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                }
                is Boolean -> output.writeByte(if (value) 'T'.code else 'F'.code)
                is BigDecimal -> {
                    output.writeByte('D'.code)
                    write(output, value.stripTrailingZeros().toEngineeringString())
                }
                is List<*> -> {
                    output.writeByte('L'.code)
                    output.writeInt(value.size)
                    value.forEach { write(output, it) }
                }
                is Map<*, *> -> {
                    output.writeByte('O'.code)
                    output.writeInt(value.size)
                    value.forEach { (key, item) -> write(output, key); write(output, item) }
                }
                else -> throw IllegalArgumentException("source parameters must contain JSON values")
            }
        }
    }
}
