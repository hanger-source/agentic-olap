package com.olap.development

import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.time.Duration
import java.util.UUID

/** 真正调用公开 MCP，不读服务器的内存对象或私有发布文件。 */
internal class McpClient(private val endpoint: String, private val token: () -> String, private val trace: Path) {
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    var responseLimit: Int? = null
    fun rpc(operation: String, payload: JsonObject, id: String = UUID.randomUUID().toString()): JsonObject {
        val body = objectOf("jsonrpc" to text("2.0"), "id" to text(id), "method" to text("tools/call"), "params" to
            objectOf("name" to text(operation), "arguments" to objectOf("payload" to payload)))
        val request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofMinutes(5))
            .header("Authorization", "Bearer ${token()}").header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-11-25")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode() == 200) { "$operation 返回 HTTP ${response.statusCode()}：${String(response.body())}" }
        responseLimit?.let { check(response.body().size <= it) { "最终 JSON-RPC 超过配置消息预算" } }
        val rpc = Json.parseToJsonElement(String(response.body(), Charsets.UTF_8)).jsonObject
        check(rpc["id"] == text(id) && rpc["error"] == null) { "MCP 协议错误：$rpc" }
        val content = rpc.objectAt("result").array("content")
        check(content.size == 1 && content[0].jsonObject.string("type") == "text") { "MCP 内容不是唯一正式 JSON" }
        val envelope = Json.parseToJsonElement(content[0].jsonObject.string("text")).jsonObject
        Files.createDirectories(trace.toAbsolutePath().parent)
        Files.writeString(trace, objectOf("operation" to text(operation), "payload" to payload, "response" to envelope).toString() + "\n", CREATE, APPEND)
        return envelope
    }
    fun delivered(envelope: JsonObject): JsonObject {
        val result = envelope["result"] as? JsonObject ?: return envelope
        if (result["delivery"] == null) return envelope
        check(result.string("delivery") == "SAVED") { "完整响应保存失败：$result" }
        val read = result.objectAt("read")
        check(read.string("operation") == "read-response")
        val payload = read.objectAt("payload")
        val bytes = savedBytes(payload)
        val message = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        check(message["error"] == null)
        return Json.parseToJsonElement(message.objectAt("result").array("content")[0].jsonObject.string("text")).jsonObject
    }
    fun savedBytes(payload: JsonObject): ByteArray {
        var offset = payload.getValue("offset").jsonPrimitive.long
        check(offset == 0L)
        val content = java.io.ByteArrayOutputStream()
        while (true) {
            val envelope = rpc("read-response", payload.with("offset" to number(offset)))
            check(envelope.getValue("success").jsonPrimitive.boolean) { "保存消息读取失败：$envelope" }
            val page = envelope.objectAt("result")
            check(page.getValue("offset").jsonPrimitive.long == offset)
            content.write(page.string("content").toByteArray(Charsets.UTF_8))
            if (page["nextOffset"] == JsonNull || page["nextOffset"] == null) {
                check(page.getValue("totalBytes").jsonPrimitive.long == content.size().toLong()); break
            }
            val next = page.getValue("nextOffset").jsonPrimitive.long
            check(next > offset && next == content.size().toLong()); offset = next
        }
        val bytes = content.toByteArray()
        check(sha(bytes) == payload.objectAt("reference").string("sha256")) { "保存消息 SHA-256 不符" }
        return bytes
    }
    fun call(operation: String, payload: JsonObject = JsonObject(emptyMap())): JsonElement {
        val result = delivered(rpc(operation, payload))
        check(result.getValue("success").jsonPrimitive.boolean) { "$operation 失败：$result" }
        return result.getValue("result")
    }
    fun script(source: String, parameters: JsonObject, version: String, name: String = "author.olap.kts"): ScriptResult {
        val id = UUID.randomUUID().toString()
        var session: String? = null
        try {
            var raw = rpc("execute-script", objectOf("executionId" to text(id), "waitMillis" to number(1000), "source" to text(source),
                "sourceName" to text(name), "dslApiVersion" to text(version), "parameters" to parameters))
            var envelope = delivered(raw)
            var report = envelope["result"] as? JsonObject ?: error("脚本未返回运行事实：$envelope")
            session = report.string("sessionId")
            while (report.string("state") == "RUNNING") {
                raw = rpc("wait-script", objectOf("executionId" to text(id), "waitMillis" to number(1000)))
                envelope = delivered(raw); report = envelope.objectAt("result")
                check(report.string("executionId") == id && report.string("sessionId") == session)
            }
            return ScriptResult(raw, envelope, report)
        } finally {
            session?.let { check(call("close-script-session", objectOf("sessionId" to text(it))).jsonObject.string("state") == "CLOSED") }
        }
    }
}

internal data class ScriptResult(val raw: JsonObject, val envelope: JsonObject, val report: JsonObject) {
    fun succeeded(): ScriptResult {
        check(envelope.getValue("success").jsonPrimitive.boolean && report.getValue("success").jsonPrimitive.boolean) { "作者程序失败：$envelope" }; return this
    }
    fun value(): JsonObject {
        val outputs = report.array("outputs").map { it.jsonObject }.filter { it.string("kind") == "VALUE" }
        check(outputs.size == 1) { "作者程序必须显式输出一个完整值" }; return outputs.single().objectAt("value")
    }
    fun references() = value().objectAt("object").objectAt("values")
}
