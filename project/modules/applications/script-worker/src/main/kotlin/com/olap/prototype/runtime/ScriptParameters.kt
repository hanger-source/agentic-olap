package com.olap.prototype.runtime

import com.olap.prototype.codec.JsonStructure
import com.olap.prototype.codec.JsonDocumentException
import com.olap.prototype.dsl.ScriptArguments
import com.olap.prototype.dsl.ScriptValue
import com.olap.prototype.dsl.ScriptValueException
import kotlinx.serialization.json.*

/** 输入预算由 JSON 边界先行核验；普通值只做精确表示，数字不经浮点转换。 */
internal fun scriptParameters(bytes: ByteArray, limits: JsonStructure.Limits): ScriptArguments {
    val root = JsonStructure.parse(bytes, limits) as? JsonObject
        ?: throw JsonDocumentException(emptyList(), "SCRIPT_ARGUMENT_OBJECT", "NON_OBJECT")
    fun value(node: JsonElement, path: List<Any>): ScriptValue = when (node) {
        JsonNull -> ScriptValue.Null
        is JsonObject -> ScriptValue.ObjectValue(node.mapValues { (name, child) -> value(child, path + name) })
        is JsonArray -> ScriptValue.ArrayValue(node.mapIndexed { index, child -> value(child, path + index) })
        is JsonPrimitive -> at(path) {
            when {
                node.isString -> ScriptValue.Text(node.content)
                node.booleanOrNull != null -> ScriptValue.BooleanValue(requireNotNull(node.booleanOrNull))
                else -> ScriptValue.DecimalValue(node.content)
            }
        }
    }
    val values = root.mapValues { (name, child) -> value(child, listOf(name)) }
    return ScriptArguments(values)
}

private fun <T> at(path: List<Any>, construct: () -> T): T = try { construct() }
    catch (failure: ScriptValueException) { throw failure.withPathPrefix(path) }
