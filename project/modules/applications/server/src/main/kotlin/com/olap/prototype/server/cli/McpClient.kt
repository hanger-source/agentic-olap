package com.olap.prototype.server.cli

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.server.auth.NetworkClient
import com.olap.prototype.server.http.ExternalJson
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/** CLI 仅通过正式 MCP 服务读取/修改业务状态，不直接打开元数据库。 */
class McpClient(address: String, private val token: String, private val version: String, private val timeout: Duration) : AutoCloseable {
    private val uri = URI(address).also { require(it.scheme in setOf("http", "https") && it.host != null && it.userInfo == null && it.fragment == null) }
    private val http = NetworkClient.create()
    private var protocol = "2025-11-25"
    private var session: String? = null
    val serverInfo: JsonNode
    init {
        val initialized = rpc("initialize", mapOf("protocolVersion" to protocol, "capabilities" to emptyMap<String, Any>(),
            "clientInfo" to mapOf("name" to "agentic-olap-cli", "version" to version)))
        protocol = initialized.path("protocolVersion").asText()
        serverInfo = initialized.path("serverInfo")
        rpc("notifications/initialized", null, false)
    }
    fun tools(): JsonNode {
        val tools = ExternalJson.mapper.createArrayNode()
        var cursor: String? = null
        do {
            val result = rpc("tools/list", cursor?.let { mapOf("cursor" to it) } ?: emptyMap<String, Any>())
            result.path("tools").forEach(tools::add)
            cursor = result.get("nextCursor")?.takeUnless { it.isNull }?.asText()
        } while (cursor != null)
        return ExternalJson.mapper.valueToTree(mapOf("serverInfo" to serverInfo, "protocolVersion" to protocol, "tools" to tools))
    }
    fun call(tool: String, payload: JsonNode): JsonNode = rpc("tools/call", mapOf("name" to tool, "arguments" to mapOf("payload" to payload)))
    fun envelope(tool: String, payload: JsonNode): JsonNode {
        val response = call(tool, payload)
        require(response.path("content").size() == 1 && response.path("content")[0].path("type").asText() == "text")
        return ExternalJson.mapper.readTree(response.path("content")[0].path("text").asText())
    }
    fun information(): Map<String, Any?> {
        require(serverInfo.path("name").asText() == "agentic-olap" && serverInfo.path("version").asText().isNotBlank())
        val visible = tools().path("tools").map { it.path("name").asText() }.toSet()
        val checks = linkedMapOf<String, Any?>()
        fun status(envelope: JsonNode): Map<String, Any?> = if (envelope.path("success").asBoolean()) mapOf("status" to "AVAILABLE", "result" to envelope.path("result"))
            else mapOf("status" to "FAILED", "requestId" to envelope.path("requestId"), "error" to envelope.path("error"))
        checks["list-data-sources"] = if ("list-data-sources" in visible) status(envelope("list-data-sources", ExternalJson.mapper.createObjectNode()))
            else mapOf("status" to "NOT_EXPOSED", "tool" to "list-data-sources")
        checks["read-connector-capabilities"] = if ("read-connector-capabilities" in visible)
            status(envelope("read-connector-capabilities", ExternalJson.mapper.createObjectNode().putNull("aggregate")))
            else mapOf("status" to "NOT_EXPOSED", "tool" to "read-connector-capabilities")
        return mapOf("status" to "REACHABLE", "version" to serverInfo.path("version").asText(), "versionMatchesInstalled" to (serverInfo.path("version").asText() == version),
            "protocolVersion" to protocol, "sourceQuery" to "NOT_CHECKED", "checks" to checks)
    }
    private fun rpc(method: String, params: Any?, responseRequired: Boolean = true): JsonNode {
        val body = linkedMapOf<String, Any?>("jsonrpc" to "2.0", "method" to method)
        if (responseRequired) body["id"] = UUID.randomUUID().toString()
        if (params != null) body["params"] = params
        val request = HttpRequest.newBuilder(uri).timeout(timeout).header("Authorization", "Bearer $token")
            .header("Accept", "application/json, text/event-stream").header("Content-Type", "application/json")
            .header("MCP-Protocol-Version", protocol)
        session?.let { request.header("Mcp-Session-Id", it) }
        val reply = http.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(ExternalJson.mapper.writeValueAsBytes(body))).build(), HttpResponse.BodyHandlers.ofByteArray())
        require(reply.statusCode() in 200..299) { "MCP HTTP ${reply.statusCode()}: ${reply.body().toString(Charsets.UTF_8)}" }
        reply.headers().firstValue("Mcp-Session-Id").ifPresent { session = it }
        if (!responseRequired) return ExternalJson.mapper.nullNode()
        val result = ExternalJson.mapper.readTree(reply.body())
        require(!result.hasNonNull("error")) { "MCP protocol error: ${result.path("error")}" }
        return result.required("result")
    }
    override fun close() = http.close()
}
