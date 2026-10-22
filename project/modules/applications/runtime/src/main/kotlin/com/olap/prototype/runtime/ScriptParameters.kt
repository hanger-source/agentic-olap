package com.olap.prototype.runtime

import com.olap.prototype.codec.JsonStructure
import com.olap.prototype.codec.JsonDocumentException
import com.olap.prototype.dsl.ScriptArguments
import com.olap.prototype.dsl.ScriptValue
import kotlinx.serialization.json.*

/** 普通 JSON 只存在于 MCP 参数边界，数字从原十进制文本直接构造。 */
internal fun scriptParameters(bytes: ByteArray, limits: JsonStructure.Limits): ScriptArguments {
    val root = JsonStructure.parse(bytes, limits) as? JsonObject
        ?: throw JsonDocumentException(emptyList(), "SCRIPT_ARGUMENT_OBJECT", "NON_OBJECT")
    fun value(node: JsonElement, path: List<Any>): ScriptValue = try {
        when (node) {
            JsonNull -> ScriptValue.Null
            is JsonObject -> ScriptValue.ObjectValue(node.mapValues { (name, child) -> value(child, path + name) })
            is JsonArray -> ScriptValue.ArrayValue(node.mapIndexed { index, child -> value(child, path + index) })
            is JsonPrimitive -> when {
                node.isString -> ScriptValue.Text(node.content)
                node.booleanOrNull != null -> ScriptValue.BooleanValue(requireNotNull(node.booleanOrNull))
                else -> ScriptValue.DecimalValue(node.content)
            }
        }
    } catch (failure: JsonDocumentException) { throw failure }
    catch (failure: IllegalArgumentException) { throw JsonDocumentException(path, "BOUNDED_SCRIPT_VALUE", "INVALID_VALUE", failure) }
    return try { ScriptArguments(root.mapValues { (name, child) -> value(child, listOf(name)) }) }
    catch (failure: JsonDocumentException) { throw failure }
    catch (failure: IllegalArgumentException) { throw JsonDocumentException(emptyList(), "SCRIPT_ARGUMENT_OBJECT", "INVALID_ARGUMENT_NAMES", failure) }
}
