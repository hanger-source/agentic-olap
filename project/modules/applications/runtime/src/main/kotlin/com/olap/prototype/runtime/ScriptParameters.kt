package com.olap.prototype.runtime

import com.olap.prototype.codec.JsonStructure
import com.olap.prototype.codec.JsonDocumentException
import com.olap.prototype.dsl.ScriptArguments
import com.olap.prototype.dsl.ScriptValue
import com.olap.prototype.dsl.ScriptValueException
import kotlinx.serialization.json.*

/** 普通 JSON 只存在于 MCP 参数边界，数字从原十进制文本直接构造。 */
internal fun scriptParameters(bytes: ByteArray, limits: JsonStructure.Limits): ScriptArguments {
    val root = JsonStructure.parse(bytes, limits) as? JsonObject
        ?: throw JsonDocumentException(emptyList(), "SCRIPT_ARGUMENT_OBJECT", "NON_OBJECT")
    fun value(node: JsonElement, path: List<Any>): ScriptValue = when (node) {
        JsonNull -> ScriptValue.Null
        is JsonObject -> {
            val children = node.mapValues { (name, child) -> value(child, path + name) }
            at(path) { ScriptValue.ObjectValue(children) }
        }
        is JsonArray -> {
            val children = node.mapIndexed { index, child -> value(child, path + index) }
            at(path) { ScriptValue.ArrayValue(children) }
        }
        is JsonPrimitive -> at(path) {
            when {
                node.isString -> ScriptValue.Text(node.content)
                node.booleanOrNull != null -> ScriptValue.BooleanValue(requireNotNull(node.booleanOrNull))
                else -> ScriptValue.DecimalValue(node.content)
            }
        }
    }
    val values = root.mapValues { (name, child) -> value(child, listOf(name)) }
    return at(emptyList()) { ScriptArguments(values) }
}

private fun <T> at(path: List<Any>, construct: () -> T): T = try { construct() }
    catch (failure: ScriptValueException) { throw failure.withPathPrefix(path) }
