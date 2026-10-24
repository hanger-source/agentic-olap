package com.olap.development

import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/** 真实服务、真实 REPL 和操作系统边界；不模拟进程、授权或能力回调。 */
internal class RuntimeTrial(private val distribution: Path, private val root: Path, private val workspace: Path) {
    fun run() {
        require(!Files.exists(root)) { "运行试验目录必须不存在：$root" }
        Files.createDirectories(root)
        LocalIssuer(root).use(::exercise)
    }

    private fun exercise(issuer: LocalIssuer) {
        val audience = "https://owned-olap.test/mcp"
        val token = issuer.token(audience, "runtime-author")
        val base = read(workspace.resolve("skills/agentic-olap/references/server.example.json")).jsonObject
        val grant = base.objectAt("auth").array("grants").single().jsonObject.with(
            "subjects" to JsonArray(listOf(text("runtime-author"), text("other-author"))), "scopes" to JsonArray(listOf(text("olap.manage"))))
        val port = ServerSocket(0).use { it.localPort }
        val data = root.resolve("data")
        val config = base.with("listen" to objectOf("host" to text("127.0.0.1"), "port" to number(port),
            "allowedHosts" to JsonArray(listOf(text("127.0.0.1:*"))), "allowedOrigins" to JsonArray(emptyList())),
            "dataRoot" to text(data.toString()), "auth" to objectOf("mode" to text("external-jwt"), "issuer" to text(issuer.address),
                "audience" to text(audience), "jwksUri" to text(issuer.address + "jwks"), "algorithms" to JsonArray(listOf(text("RS256"))),
                "grants" to JsonArray(listOf(grant))),
            "limits" to base.objectAt("limits").with("maxInFlight" to number(1), "responseBytes" to number(65536),
                "pendingOutputBytes" to number(131072)))
        val configFile = root.resolve("server.json")
        write(configFile, config)
        val endpoint = "http://127.0.0.1:$port/mcp"
        val client = McpClient(endpoint, { token }, root.resolve("mcp.jsonl"))
        client.responseLimit = 65536
        val processes = linkedSetOf<Long>()
        val sessions = linkedSetOf<String>()
        ManagedProcess(listOf(distribution.resolve("runtime/agentic-olap").toString(), "start", "--config", configFile.toString()),
            workspace, root.resolve("service.log"), mapOf("AGENTIC_OLAP_TEST_SECRET" to "must-not-reach-the-author",
                "SSL_CERT_FILE" to issuer.ca.toString(), "NO_PROXY" to "127.0.0.1,localhost")).use { service ->
            service.ready { runCatching { java.net.Socket("127.0.0.1", port).use { true } }.getOrDefault(false) }
            val version = client.call("describe-script-api").jsonObject.string("dslApiVersion")
            fun complete(raw: JsonObject): ScriptResult {
                var last = raw
                var envelope = client.delivered(last)
                var report = envelope.objectAt("result")
                sessions += report.string("sessionId")
                while (report.string("state") == "RUNNING") {
                    last = client.rpc("wait-script", objectOf("executionId" to report.getValue("executionId"), "waitMillis" to number(1000)))
                    envelope = client.delivered(last)
                    report = envelope.objectAt("result")
                }
                return ScriptResult(last, envelope, report)
            }
            fun submit(source: String, label: String, session: String? = null): ScriptResult = complete(client.rpc("execute-script",
                objectOf("executionId" to text(UUID.randomUUID().toString()), "waitMillis" to number(1000), "source" to text(source),
                    "sourceName" to text("runtime-boundaries.olap.kts"), "dslApiVersion" to text(version),
                    "parameters" to objectOf("label" to text(label), "config" to text(configFile.toString()), "port" to number(port)))
                    .let { if (session == null) it else it.with("sessionId" to text(session)) }))
            fun identity(value: ScriptResult): JsonObject {
                val result = value.succeeded().value().objectAt("object").objectAt("values")
                processes += result.objectAt("pid").string("text").toLong()
                return result
            }
            val initial = """
                val retainedArguments = parameters
                val productModel = BusinessLineCreateRequest(parameters.text("label"), "语言历史", olap.caller.subject, "运行试验", olap.caller.subject)
                val privateRoot = olap.temporaryDirectory
                java.nio.file.Files.writeString(privateRoot.resolve("private.txt"), parameters.text("label"))
                check(olap.sources.list(EmptyRequest).items.isEmpty())
                olap.output(ScriptValue.ObjectValue(mapOf("pid" to ScriptValue.Text(ProcessHandle.current().pid().toString()),
                    "root" to ScriptValue.Text(privateRoot.toString()), "model" to ScriptValue.Text(productModel.code))))
            """.trimIndent()
            try {
                val first = submit(initial, "first")
                val firstIdentity = identity(first)
                val firstSession = first.report.string("sessionId")
                val repeated = complete(client.rpc("execute-script", objectOf("executionId" to first.report.getValue("executionId"),
                    "waitMillis" to number(0), "source" to text(initial), "sourceName" to text("runtime-boundaries.olap.kts"),
                    "dslApiVersion" to text(version), "parameters" to objectOf("label" to text("first"),
                        "config" to text(configFile.toString()), "port" to number(port))))).succeeded()
                check(repeated.report == first.report) { "相同执行身份不应重新执行" }
                val conflict = client.rpc("execute-script", objectOf("executionId" to first.report.getValue("executionId"), "waitMillis" to number(0),
                    "source" to text("error(\"must not execute\")"), "sourceName" to text("runtime-boundaries.olap.kts"),
                    "dslApiVersion" to text(version), "parameters" to JsonObject(emptyMap())))
                check(!conflict.getValue("success").jsonPrimitive.boolean && conflict.objectAt("error")["code"] == text("SCRIPT_EXECUTION_CONFLICT"))
                val second = submit(initial, "second")
                val secondIdentity = identity(second)
                val secondSession = second.report.string("sessionId")
                check(firstIdentity.objectAt("root") != secondIdentity.objectAt("root"))
                val other = McpClient(endpoint, { issuer.token(audience, "other-author") }, root.resolve("other-author.jsonl"))
                val refused = other.rpc("read-script-session", objectOf("sessionId" to text(firstSession)))
                check(!refused.getValue("success").jsonPrimitive.boolean && refused.objectAt("error")["code"] == text("SCRIPT_SESSION_UNAVAILABLE"))
                val continued = submit("""
                    check(java.nio.file.Files.readString(privateRoot.resolve("private.txt")) == "first")
                    check(olap.sources.list(EmptyRequest).items.isEmpty())
                    olap.output(ScriptValue.Text(retainedArguments.text("label") + "/" + parameters.text("label") + "/" + productModel.code))
                """.trimIndent(), "changed", firstSession).succeeded()
                check(continued.report["workerId"] == first.report["workerId"])
                check(continued.value()["text"] == text("first/changed/first"))
                val secondContinued = submit("olap.output(ScriptValue.Text(productModel.code))", "changed", secondSession).succeeded()
                check(secondContinued.value()["text"] == text("second"))

                // 被拒绝目标先由宿主确认真实存在，避免把不存在误判成权限隔离。
                val otherFile = Path.of(secondIdentity.objectAt("root").string("text")).resolve("private.txt")
                check(Files.readString(otherFile) == "second")
                val forbidden = JsonPrimitive(otherFile.toString()).toString()
                submit("""
                    check(runCatching { java.nio.file.Files.readString(java.nio.file.Path.of(parameters.text("config"))) }.isFailure)
                    check(runCatching { java.nio.file.Files.readString(java.nio.file.Path.of($forbidden)) }.isFailure)
                    check(runCatching { java.net.Socket("127.0.0.1", parameters.decimal("port").toInt()).close() }.isFailure)
                    check(runCatching { ProcessBuilder("/usr/bin/true").start().waitFor() }.isFailure)
                    check(System.getenv("AGENTIC_OLAP_TEST_SECRET") == null)
                    check(java.nio.file.Files.readString(privateRoot.resolve("private.txt")) == "first")
                    olap.output(ScriptValue.Text("permissions-held"))
                """.trimIndent(), "permissions", firstSession).succeeded()

                submit("""
                    val failure = runCatching {
                        olap.semantics.asset(GovernanceReadRequests.AssetVersionRequest("BUSINESS_LINE", "not-created", 1))
                    }.exceptionOrNull()
                    check(failure is com.olap.prototype.dsl.transport.CapabilityFailureException)
                    check(failure.failure.operationId == "read-asset-version" && failure.failure.callId.isNotBlank())
                    olap.output(ScriptValue.Text("failure-correlated"))
                """.trimIndent(), "failure", firstSession).succeeded()

                val partial = submit("""
                    val committedLine = olap.governance.createBusinessLine(BusinessLineCreateRequest("partial_trial", "部分成功", olap.caller.subject, "真实写入", olap.caller.subject)).businessLine
                    olap.governance.activate(GovernanceVersionActivationRequest(GovernanceActivationObjectType.BUSINESS_LINE,
                        committedLine.id, 1, olap.caller.subject, "明确激活"))
                    print("标签🌟".repeat(16000))
                    olap.output(ScriptValue.Text(committedLine.id))
                    error("author stopped after the committed write")
                """.trimIndent(), "partial", firstSession)
                check(!partial.envelope.getValue("success").jsonPrimitive.boolean && partial.raw.objectAt("result")["delivery"] == text("SAVED"))
                val printed = java.io.ByteArrayOutputStream()
                partial.report.array("outputs").map { it.jsonObject }.filter { it["kind"] == text("STDOUT") }.forEach { output ->
                    printed.write(if (output["encoding"] == text("UTF8")) output.string("content").toByteArray(Charsets.UTF_8)
                        else java.util.Base64.getDecoder().decode(output.string("content")))
                }
                check(printed.toByteArray().contentEquals("标签🌟".repeat(16000).toByteArray(Charsets.UTF_8)))
                check(partial.report.array("outputs").last().jsonObject["kind"] == text("VALUE"))
                val effects = partial.report.array("calls").flatMap { it.jsonObject.array("effects") }.map { it.jsonObject }
                check(effects.size == 2 && effects.first()["assetVersion"] == effects.last()["assetVersion"])
                val created = partial.value().getValue("text").jsonPrimitive.content
                check(effects.last().objectAt("assetVersion")["id"] == text(created))
                val recovered = submit("""
                    val recoveredLine = olap.semantics.asset(GovernanceReadRequests.AssetVersionRequest("BUSINESS_LINE", ${JsonPrimitive(created)}, 1)) as AssetSnapshot.BusinessLine
                    olap.output(ScriptValue.Text(recoveredLine.value.status))
                """.trimIndent(), "recover", firstSession).succeeded()
                check(recovered.value()["text"] == text("ACTIVE"))

                val execution = UUID.randomUUID().toString()
                val raw = client.rpc("execute-script", objectOf("sessionId" to text(firstSession), "executionId" to text(execution),
                    "waitMillis" to number(1000), "source" to text("println(\"entered-uncooperative-loop\"); while (true) { Thread.onSpinWait() }"),
                    "sourceName" to text("uncooperative.olap.kts"), "dslApiVersion" to text(version), "parameters" to JsonObject(emptyMap())))
                var running = client.delivered(raw).objectAt("result")
                val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
                while (running.array("outputs").none { it.jsonObject["content"]?.jsonPrimitive?.content?.contains("entered-uncooperative-loop") == true }) {
                    check(running.string("state") == "RUNNING" && System.nanoTime() < deadline) { "失控代码尚未进入求值：$running" }
                    running = client.call("wait-script", objectOf("executionId" to text(execution), "waitMillis" to number(1000))).jsonObject
                }
                val cancelled = client.delivered(client.rpc("cancel-script", objectOf("executionId" to text(execution)))).objectAt("result")
                check(cancelled.string("state") == "CANCELLED" && !cancelled.getValue("sessionUsable").jsonPrimitive.boolean)
                val firstPid = firstIdentity.objectAt("pid").string("text").toLong()
                check(ProcessHandle.of(firstPid).isEmpty || !ProcessHandle.of(firstPid).get().isAlive) { "失控 worker 尚未退出" }
                check(!Files.exists(Path.of(firstIdentity.objectAt("root").string("text"))))
                submit("olap.output(ScriptValue.Text(productModel.code))", "survivor", secondSession).succeeded()

                val requestBytes = config.objectAt("limits").getValue("requestBytes").jsonPrimitive.int
                HttpClient.newHttpClient().use { http ->
                    val oversized = ByteArray(requestBytes + 1) { 32 }
                    val request = HttpRequest.newBuilder(URI(endpoint)).timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer $token").header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(oversized) }).build()
                    check(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 413) { "chunked 请求未按实际字节预算拒绝" }
                }
                check(client.call("list-data-sources").jsonObject.array("items").isEmpty())
            } finally {
                sessions.forEach { session -> client.call("close-script-session", objectOf("sessionId" to text(session))) }
            }
        }
        processes.forEach { pid -> check(ProcessHandle.of(pid).isEmpty || !ProcessHandle.of(pid).get().isAlive) { "受管 JVM 遗留：$pid" } }
        for (directory in listOf("scripting", "protocol-transfers")) {
            val path = data.resolve("runtime/$directory")
            if (Files.exists(path)) Files.list(path).use { check(it.findAny().isEmpty) { "运行目录未回收：$path" } }
        }
        write(root.resolve("report.json"), objectOf("passed" to bool(true), "persistentLanguageHistory" to bool(true),
            "independentSessions" to bool(true), "identityIsolation" to bool(true), "singleAdmissionCallback" to bool(true), "sandbox" to bool(true),
            "executionIdentity" to bool(true), "completeOrderedOutput" to bool(true), "committedEffectsRecovered" to bool(true),
            "forcedCancellation" to bool(true), "processesReaped" to bool(true), "chunkedInputLimit" to bool(true)))
    }
}
