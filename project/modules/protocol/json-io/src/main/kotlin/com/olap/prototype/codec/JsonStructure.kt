package com.olap.prototype.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** 先检查重复键与结构预算，再交给 JSON 实现解释标量，避免重复键被覆盖。 */
object JsonStructure {
    data class Limits(val bytes: Long = 16L * 1024 * 1024, val depth: Int = 128, val nodes: Int = 1_000_000) {
        init { require(bytes > 0 && depth > 0 && nodes > 0) { "JSON document budgets must be positive" } }
    }
    private val parser = Json { isLenient = false; ignoreUnknownKeys = false; allowSpecialFloatingPointValues = false }
    fun parse(bytes: ByteArray, limits: Limits = Limits()): JsonElement {
        if (bytes.size.toLong() > limits.bytes) throw JsonDocumentException.resourceLimit(
            JsonDocumentException.Resource.JSON_INPUT_BYTES, limits.bytes, bytes.size.toLong(), emptyList())
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (failure: Exception) { throw JsonDocumentException(emptyList(), "UTF8", "INVALID_BYTES", failure) }
        return try {
            Scanner(text, limits).check()
            parser.parseToJsonElement(text)
        } catch (failure: JsonDocumentException) { throw failure }
        catch (failure: Exception) { throw JsonDocumentException(emptyList(), "JSON_DOCUMENT", "INVALID_JSON", failure) }
    }

    private class Scanner(val text: String, val limits: Limits) {
        var cursor = 0
        var nodes = 0L
        fun check() { value(1, emptyList()); whitespace(); require(cursor == text.length) { "模型 JSON 存在尾随内容" } }
        fun value(depth: Long, path: List<Any>) {
            if (depth > limits.depth) throw JsonDocumentException.resourceLimit(
                JsonDocumentException.Resource.JSON_INPUT_DEPTH, limits.depth.toLong(), depth, path)
            if (++nodes > limits.nodes) throw JsonDocumentException.resourceLimit(
                JsonDocumentException.Resource.JSON_INPUT_NODES, limits.nodes.toLong(), nodes, path)
            whitespace()
            when (peek()) {
                '{' -> {
                    cursor++; whitespace()
                    val keys = HashSet<String>()
                    if (take('}')) return
                    do {
                        whitespace(); val name = string()
                        if (!keys.add(name)) throw JsonDocumentException(path + name, "UNIQUE_OBJECT_KEY", "DUPLICATE_KEY")
                        whitespace(); require(take(':')) { "模型 JSON 缺少字段分隔符" }
                        value(depth + 1, path + name); whitespace()
                    } while (take(','))
                    require(take('}')) { "模型 JSON 对象未闭合" }
                }
                '[' -> {
                    cursor++; whitespace()
                    if (take(']')) return
                    var index = 0
                    do { value(depth + 1, path + index++); whitespace() } while (take(','))
                    require(take(']')) { "模型 JSON 数组未闭合" }
                }
                '"' -> string()
                else -> {
                    val start = cursor
                    while (cursor < text.length && text[cursor] !in " \t\r\n,]}") cursor++
                    require(cursor > start) { "模型 JSON 缺少值" }
                }
            }
        }
        fun string(): String {
            val start = cursor
            require(take('"')) { "模型 JSON 字段名必须为字符串" }
            var escaped = false
            while (cursor < text.length) {
                val current = text[cursor++]
                if (!escaped && current == '"') return (Json.parseToJsonElement(text.substring(start, cursor)) as JsonPrimitive).content
                escaped = if (escaped) false else current == '\\'
            }
            throw IllegalArgumentException("模型 JSON 字符串未闭合")
        }
        fun peek(): Char = text.getOrNull(cursor) ?: throw IllegalArgumentException("模型 JSON 未完成")
        fun take(value: Char): Boolean = if (text.getOrNull(cursor) == value) { cursor++; true } else false
        fun whitespace() { while (cursor < text.length && text[cursor] in " \t\r\n") cursor++ }
    }
}
