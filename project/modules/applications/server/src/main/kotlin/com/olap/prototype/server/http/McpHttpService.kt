package com.olap.prototype.server.http

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.server.*
import com.olap.prototype.server.ApplicationResponse
import com.olap.prototype.server.auth.BearerVerifier
import com.olap.prototype.server.config.Deployment
import com.olap.prototype.server.responses.ResponseFiles
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.*
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.cio.CIO
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import io.ktor.utils.io.*
import io.modelcontextprotocol.kotlin.sdk.server.*
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.io.readByteArray
import java.nio.file.Path
import java.util.UUID

/** HTTP 传输无状态；每请求建立主体。语言会话由 ScriptSessions 独立持有。 */
class McpHttpService(private val config: Deployment, private val endpoint: ApplicationEndpoint, private val version: String) : AutoCloseable {
    private val verifier = BearerVerifier(config.auth)
    private val responses = ResponseFiles(Path.of(config.dataRoot))
    private val capabilityProtos = com.olap.prototype.codec.CapabilityProtos(config.limits.requestBytes, config.limits.inputDepth)
    private val stateKey = AttributeKey<State>("agentic-olap-delivery")
    private var engine: EmbeddedServer<*, *>? = null
    private data class State(val principal: ServerAuthorization.Principal, val id: JsonNode?, val method: String,
        var operation: String = "", var requestId: String = "", var success: Boolean = true)

    fun start(wait: Boolean = true) {
        val owner = this
        engine = embeddedServer(CIO, host = config.listen.host, port = config.listen.port) {
            install(ContentNegotiation) { json(McpJson) }
            install(RequestBodyLimit) { bodyLimit { config.limits.requestBytes.toLong() } }
            install(DoubleReceive)
            install(createApplicationPlugin("CompleteResponseDelivery") {
                on(ResponseBodyReadyForSend) { call, content ->
                    val state = call.attributes.getOrNull(stateKey)
                    if (state != null && content !is OutgoingContent.NoContent && content !is OutgoingContent.ProtocolUpgrade) {
                        val delivered = try { owner.capture(content).use { owner.deliver(it, state) } }
                        catch (failure: Exception) {
                            System.err.println("response delivery failed requestId=${state.requestId} operationSuccess=${state.success}: $failure")
                            owner.deliveryNotice(state, mapOf("delivery" to "FAILED", "code" to "RESPONSE_PERSISTENCE_FAILED",
                                "message" to "完整响应未能保存；原操作状态保持，勿自动重新执行，请按请求标识查看诊断。"))
                        }
                        transformBodyTo(object : OutgoingContent.ByteArrayContent() {
                            override val contentType = content.contentType ?: ContentType.Application.Json
                            override val status = content.status
                            override val headers = Headers.build { content.headers.forEach { key, values -> if (!key.equals(HttpHeaders.ContentLength, true)) appendAll(key, values) } }
                            override fun bytes(): ByteArray = delivered
                        })
                    }
                }
            })
            routing {
                route("/.well-known/oauth-protected-resource") { get { resource(call) }; get("/mcp") { resource(call) } }
                route("/mcp") { handle { withTimeout((config.limits.requestSeconds * 1000).toLong()) { exchange(call) } } }
            }
        }.start(wait)
    }
    private suspend fun resource(call: ApplicationCall) {
        if (!authorizedOrigin(call)) return
        call.respondText(ExternalJson.mapper.writeValueAsString(mapOf("resource" to config.url,
            "authorization_servers" to if (config.auth.mode == "external-jwt") listOf(config.auth.issuer) else emptyList<String>(), "bearer_methods_supported" to listOf("header"))), ContentType.Application.Json)
    }
    private suspend fun authorizedOrigin(call: ApplicationCall): Boolean {
        if (!matches(call.request.header(HttpHeaders.Host).orEmpty(), config.listen.allowedHosts) ||
            call.request.header(HttpHeaders.Origin)?.let { !matches(it, config.listen.allowedOrigins) } == true) {
            call.respondText("host or origin is not authorized", status = HttpStatusCode.Forbidden); return false
        }
        return true
    }
    private suspend fun exchange(call: ApplicationCall) {
        val deadline = System.nanoTime() + (config.limits.requestSeconds * 1_000_000_000).toLong()
        if (!authorizedOrigin(call)) return
        val bearer = call.request.header(HttpHeaders.Authorization)
        val principal = try {
            require(bearer != null && bearer.startsWith("Bearer "))
            withContext(Dispatchers.IO) { verifier.verify(bearer.removePrefix("Bearer ")) }
        } catch (_: Exception) {
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer resource_metadata=\"${config.url.removeSuffix("/mcp")}/.well-known/oauth-protected-resource/mcp\"")
            call.respondText("bearer is not authorized", status = HttpStatusCode.Unauthorized); return
        }
        if (call.request.httpMethod != HttpMethod.Post) {
            call.respond(HttpStatusCode.MethodNotAllowed); return
        }
        val raw = call.receiveChannel().readRemaining(config.limits.requestBytes.toLong() + 1).readByteArray()
        if (raw.size > config.limits.requestBytes) { call.respond(HttpStatusCode.PayloadTooLarge); return }
        val document = try { ExternalJson.objectDocument(raw, config.limits.inputDepth, config.limits.inputNodes) } catch (failure: Exception) {
            call.respondText(failure.message ?: "invalid JSON request", status = HttpStatusCode.BadRequest); return
        }
        val state = State(principal, document.get("id"), document.path("method").asText())
        call.attributes.put(stateKey, state)
        val server = Server(Implementation("agentic-olap", version), ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools())),
            "先用 describe-script-api 发现正式 Kotlin 类型。execute-script 使用明确 executionId；RUNNING 时 wait-script 观察原执行，不重复源码。REPL 会话独立于 MCP HTTP 传输。正式能力分别授权、记录。工具参数放在 payload 中。")
        val available = endpoint.operations(principal).toMutableMap()
        available["read-response"] = OperationDescription(true, "按精确引用分段读取本人在相同授权下保存的完整原始响应，不重执行原操作。")
        available.forEach { (operation, description) ->
            server.addTool(operation, description.description, ScriptToolSchemas.schema(operation),
                toolAnnotations = ToolAnnotations(readOnlyHint = description.readOnly, destructiveHint = !description.readOnly, openWorldHint = false)) { request ->
                val response = try {
                    val payload = try {
                        val arguments = request.params.arguments ?: error("tool arguments require payload")
                        require(arguments.keys == setOf("payload")) { "tool arguments require only payload" }
                        ExternalJson.objectDocument(arguments.getValue("payload").toString().toByteArray(), config.limits.inputDepth, config.limits.inputNodes)
                    } catch (failure: Exception) {
                        throw com.olap.prototype.contract.InputContractException.violation(com.olap.prototype.contract.InputContractException.Rule.DECODE_INPUT,
                            listOf("payload"), "tool arguments require exactly one complete payload object", "JSON_OBJECT", "INVALID_TOOL_ARGUMENTS")
                    }
                    if (operation == "read-response") readResponse(payload, state)
                    else runInterruptible(Dispatchers.IO) { endpoint.call(principal, operation, payload, ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)) }
                } catch (failure: Exception) { failure(operation, failure) }
                state.operation = operation; state.requestId = response.requestId; state.success = response.success
                CallToolResult(listOf(TextContent(envelope(response, externalResult(operation, response.value)))))
            }
        }
        val transport = StreamableHttpServerTransport(StreamableHttpServerTransport.Configuration(enableJsonResponse = true,
            maxRequestBodySize = config.limits.requestBytes.toLong())).apply { setSessionIdGenerator(null) }
        val session = server.createSession(transport)
        try {
            transport.handleRequest(null, call)
        } finally { try { session.close() } finally { server.close() } }
    }
    private fun readResponse(payload: JsonNode, state: State): ApplicationResponse {
        require(payload.fieldNames().asSequence().all { it in setOf("reference", "offset", "length") })
        val reference = ExternalJson.mapper.treeToValue(payload.required("reference"), ResponseFiles.Reference::class.java)
        require(payload.path("offset").isIntegralNumber && payload.path("offset").canConvertToLong())
        var length = if (payload.has("length")) payload.path("length").let {
            require(it.isIntegralNumber && it.canConvertToInt() && it.intValue() > 0); it.intValue()
        } else config.limits.responseBytes
        while (true) {
            val page = responses.read(reference, state.principal, endpoint.authorizationPolicyHash, payload.path("offset").longValue(), length)
            val response = ApplicationResponse("response-read-${UUID.randomUUID()}", true, page)
            if (rpc(state.id, envelope(response)).size <= config.limits.responseBytes) return response
            require(length > 4) { "message budget cannot contain one saved response range and reference" }
            length /= 2
        }
    }
    /** 仅外部 HTTP 交付选择正式公开 codec；应用和文件读取始终返回原模型。 */
    private fun externalResult(operation: String, value: Any?): Any? = when {
        value == null -> null
        operation in jsonOperations -> value
        com.olap.prototype.codec.QueryCapabilityCodec.supports(operation) || com.olap.prototype.codec.GovernanceCapabilityCodec.supports(operation) ->
            capabilityProtos.encodeResult(operation, value)
        else -> error("public operation has no registered external result contract: $operation")
    }
    private fun envelope(response: ApplicationResponse, externalValue: Any? = response.value): String = ExternalJson.mapper.writeValueAsString(linkedMapOf(
        "requestId" to response.requestId, "success" to response.success, "result" to externalValue, "error" to response.error,
        "effects" to response.effects, "timingNanos" to response.timingNanos, "workers" to response.workers))
    private fun deliver(capture: ResponseFiles.Capture, state: State): ByteArray {
        if (state.method != "tools/call") state.success = capture.protocolSucceeded()
        if (!capture.oversized) return capture.inline()
        val result = try {
            val reference = capture.commit(state.principal, endpoint.authorizationPolicyHash, state.operation.ifEmpty { state.method }, state.requestId, state.success)
            mapOf("delivery" to "SAVED", "message" to "结果较大，完整响应已保存；按需读取，无需重新执行。", "location" to responses.location(reference).toString(),
                "read" to mapOf("operation" to "read-response", "payload" to mapOf("reference" to reference, "offset" to 0)))
        } catch (failure: Exception) {
            System.err.println("complete response persistence failed requestId=${state.requestId} success=${state.success}: $failure")
            mapOf("delivery" to "FAILED", "code" to "RESPONSE_PERSISTENCE_FAILED", "message" to "完整响应未能保存；原操作状态保持，勿自动重新执行，请按请求标识查看诊断。")
        }
        return deliveryNotice(state, result)
    }
    private fun deliveryNotice(state: State, result: Map<String, Any>): ByteArray {
        val response = ApplicationResponse(state.requestId, state.success, result,
            if (state.success) null else ApplicationFailure("DELIVERY", "ORIGINAL_OPERATION_NOT_SUCCEEDED", "原操作未成功；完整错误见保存响应或服务诊断。"))
        return if (state.method == "tools/call") rpc(state.id, envelope(response))
        else ExternalJson.mapper.writeValueAsBytes(mapOf("jsonrpc" to "2.0", "id" to state.id, "error" to mapOf("code" to -32001,
            "message" to "完整协议响应交付信息见 data。", "data" to mapOf("originalSuccess" to state.success, "delivery" to result))))
    }
    private fun rpc(id: JsonNode?, text: String): ByteArray {
        val identity = requireNotNull(id) { "MCP tool response requires its exact request identity" }
        val requestId = when {
            identity.isTextual -> RequestId.StringId(identity.textValue())
            identity.isIntegralNumber && identity.canConvertToLong() -> RequestId.NumberId(identity.longValue())
            else -> error("MCP request identity must match its official string or integer contract")
        }
        val message = JSONRPCResponse(requestId, CallToolResult(listOf(TextContent(text))))
        return McpJson.encodeToString(JSONRPCResponse.serializer(), message).toByteArray(Charsets.UTF_8)
    }
    private fun failure(operation: String, failure: Exception): ApplicationResponse {
        if (failure is ResponseFiles.AuthorizationChanged) return ApplicationResponse("response-read-${UUID.randomUUID()}", false,
            error = ApplicationFailure("AUTHORIZATION", "SAVED_RESPONSE_AUTHORIZATION_CHANGED", "保存响应的主体、作用域或完整授权策略与本次不一致；原操作不会被执行。"))
        if (failure is com.olap.prototype.server.scripting.ScriptException) return ApplicationResponse("mcp-${UUID.randomUUID()}", false,
            error = ApplicationFailure("SCRIPT", failure.code, failure.message, mapOf("sessionId" to failure.sessionId, "executionId" to failure.executionId, "state" to failure.state)))
        if (failure is java.util.concurrent.CancellationException || failure is InterruptedException) return ApplicationResponse("mcp-${UUID.randomUUID()}", false,
            error = ApplicationFailure("CLIENT_WAIT", "CLIENT_WAIT_CANCELLED", "客户端停止等待；原操作实际结局需按运行记录确认。",
                if (operation in setOf("execute-query", "candidate-values")) mapOf("sourceOutcome" to mapOf("state" to "UNKNOWN", "cancellation" to "UNCONFIRMED")) else emptyMap()))
        val id = "mcp-${UUID.randomUUID()}"
        val projection = ServerFailures.correlated(ServerFailures.project(failure, "APPLICATION", "APPLICATION_FAILED"),
            id, operation, McpHttpService::class.java.name)
        org.slf4j.LoggerFactory.getLogger(McpHttpService::class.java).warn("service operation failed: requestId={} operation={}", id, operation, failure)
        return ApplicationResponse(id, false, error = ApplicationFailure(projection.stage, projection.code, projection.message, projection.details))
    }
    private suspend fun capture(content: OutgoingContent): ResponseFiles.Capture {
        val capture = responses.capture(config.limits.responseBytes)
        try {
            suspend fun consume(channel: ByteReadChannel) {
                val bytes = ByteArray(8192)
                while (true) { val count = channel.readAvailable(bytes); if (count < 0) break; capture.write(bytes, 0, count) }
                channel.closedCause?.let { throw it }
            }
            when (content) {
                is OutgoingContent.ByteArrayContent -> capture.write(content.bytes())
                is OutgoingContent.ReadChannelContent -> {
                    val source = content.readFrom()
                    try { consume(source) } finally { source.cancel() }
                }
                is OutgoingContent.WriteChannelContent -> coroutineScope {
                    val channel = ByteChannel()
                    val writer = async {
                        try { content.writeTo(channel); channel.close() }
                        catch (failure: Throwable) { channel.cancel(failure); throw failure }
                    }
                    consume(channel)
                    writer.await()
                }
                else -> error("HTTP response has no materializable message body")
            }
            return capture
        } catch (failure: Throwable) { capture.close(); throw failure }
    }
    override fun close() { engine?.stop(1000, (config.limits.startupSeconds * 1000).toLong()); verifier.close() }
    companion object {
        private val jsonOperations = setOf("describe-script-api", "describe-operation", "execute-script", "wait-script", "cancel-script",
            "read-script-session", "reset-script-session", "close-script-session", "list-publications", "read-response")
        private fun matches(value: String, patterns: List<String>) = patterns.any { pattern -> value.equals(pattern, true) || pattern.endsWith('*') && value.startsWith(pattern.dropLast(1), true) }
    }
}
