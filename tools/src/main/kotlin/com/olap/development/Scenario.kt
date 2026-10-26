package com.olap.development

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.serialization.json.*
import java.io.Closeable
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** 用已有真实业务作者程序完成建设、重开、保存结果读取与来源变化；不复制 OLAP 计算。 */
internal class Scenario(private val name: String, private val distribution: Path, private val output: Path, private val workspace: Path,
    private val fixtureLaunch: Path? = null) {
    private val business = if (name == "http-source") "orders" else name
    fun run() {
        require(name in setOf("orders", "procurement", "subscriptions", "populations", "kernel", "http-source"))
        require((name == "http-source") == (fixtureLaunch != null)) { "HTTP 用法必须显式提供已生成的真实 fixture 启动清单" }
        require(!Files.exists(output)) { "业务用法输出目录必须不存在：$output" }
        Files.createDirectories(output)
        val resources = read(distribution.resolve("resources.json")).jsonObject
        val connectors = output.resolve("connectors.json")
        write(connectors, JsonArray(resources.array("connectors").map { text(distribution.resolve(it.jsonPrimitive.content).toAbsolutePath().toString()) }))
        if (name == "kernel") {
            val limits = read(distribution.resolve("server.example.json")).jsonObject.objectAt("limits")
            val duration = limits.getValue("requestSeconds").jsonPrimitive.double * 1000
            execute(listOf(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", System.getProperty("java.class.path"),
                "Kernel", "--connectors", connectors.toAbsolutePath().toString(), output.resolve("run").toAbsolutePath().toString(),
                duration.toLong().toString(), limits.getValue("nativeMemoryBytes").jsonPrimitive.content), workspace)
            return
        }
        val input = output.resolve("input")
        prepare(connectors, "prepare", input)
        val sources = read(input.resolve("data-sources.json")).jsonArray
        val hashes = sources.map { Path.of(it.jsonObject.objectAt("identityParameters").string("jdbcUrl").removePrefix("jdbc:duckdb:")) }.associateWith(::sha)
        val fixture = if (name == "http-source") output.resolve("fixture") else null
        val sourceProcess = fixtureLaunch?.let { launch ->
            val command = read(launch).jsonObject.array("command").map { it.jsonPrimitive.content }
            ManagedProcess(command, workspace, output.resolve("http-source.log"))
        }
        sourceProcess.use { child ->
            if (fixture != null) requireNotNull(child).ready { Files.isRegularFile(fixture.resolve("ready.json")) }
            runService(connectors, input, sources, hashes, fixture)
        }
    }

    private fun runService(connectors: Path, input: Path, sources: JsonArray, hashes: Map<Path, String>, fixture: Path?) {
        val selectedConnectors = if (fixture == null) read(connectors) else read(fixture.resolve("connectors.json"))
        val selectedSources = if (fixture == null) sources else read(fixture.resolve("http-sources.json")).jsonArray
        val parameters = objectOf("sourceId" to text("example-$business")).let { values ->
            if (fixture == null) values else values.with("declaredStructure" to read(fixture.resolve("declared-structure.json")))
        }
        val configPath = output.resolve("server.json")
        val dataRoot = output.resolve("data")
        val port = ServerSocket(0).use { it.localPort }
        LocalIssuer(output).use { issuer ->
            val audience = "https://owned-olap.test/mcp"
            val base = read(workspace.resolve("skills/agentic-olap/references/server.example.json")).jsonObject
            val originalGrant = base.objectAt("auth").array("grants").first().jsonObject
            val grant = originalGrant.with("subjects" to JsonArray(listOf(text("http-trial-manager"))),
                "dataSources" to JsonArray(selectedSources.map { it.jsonObject.getValue("dataSourceId") }),
                "subjectAreas" to JsonArray(listOf(text("*"))), "releases" to JsonArray(listOf(text("*"))))
            var config = base.with("listen" to objectOf("host" to text("127.0.0.1"), "port" to number(port),
                "allowedHosts" to JsonArray(listOf(text("127.0.0.1:*"))), "allowedOrigins" to JsonArray(emptyList())),
                "dataRoot" to text(dataRoot.toAbsolutePath().toString()), "connectors" to selectedConnectors, "dataSources" to selectedSources,
                "scripting" to base.objectAt("scripting").with("maxCalls" to number(1000)),
                "auth" to objectOf("mode" to text("external-jwt"), "issuer" to text(issuer.address), "audience" to text(audience),
                    "jwksUri" to text(issuer.address + "jwks"), "algorithms" to JsonArray(listOf(text("RS256"))), "grants" to JsonArray(listOf(grant))))
            if (name == "populations") config = config.with("limits" to config.objectAt("limits").with("responseBytes" to number(8192)))
            write(configPath, config)
            val endpoint = "http://127.0.0.1:$port/mcp"
            val client = McpClient(endpoint, { issuer.token(audience, "http-trial-manager") }, output.resolve("mcp-calls.jsonl"))
            if (name == "populations") client.responseLimit = 8192
            fun start(phase: String): ManagedProcess = ManagedProcess(listOf(distribution.resolve("runtime/agentic-olap").toAbsolutePath().toString(),
                "start", "--config", configPath.toAbsolutePath().toString()), workspace, output.resolve("$phase.log"),
                (mapOf("SSL_CERT_FILE" to issuer.ca.toString(), "NO_PROXY" to "127.0.0.1,localhost") +
                    if (fixture == null) emptyMap() else mapOf("OLAP_TEST_HTTP_AUTH" to "Bearer fixture-query-access"))).also { process ->
                process.ready { runCatching { java.net.Socket("127.0.0.1", port).use { true } }.getOrDefault(false) }
            }
            val author = Files.readString(workspace.resolve("examples/$business/main.olap.kts"))
            lateinit var references: JsonObject
            lateinit var sourceBindings: JsonElement
            lateinit var version: String
            val report = linkedMapOf<String, JsonElement>("serviceRuntime" to text("Kotlin/JVM"))
            start("build").use {
                authentication(endpoint, issuer, audience)
                version = client.call("describe-script-api").jsonObject.string("dslApiVersion")
                sourceBindings = client.call("list-data-sources")
                if (name == "populations") {
                    val partial = client.script("""
                        val created = olap.governance.createBusinessLine(com.olap.prototype.governance.contract.BusinessLineCreateRequest(
                            code = parameters.text("code"), displayName = "保留已提交动作", owner = olap.caller.subject,
                            description = "作者随后失败不回滚先前正式调用", createdBy = olap.caller.subject)).businessLine
                        olap.output(ScriptValue.Text(created.id))
                        error("author stopped after a committed governance call")
                    """.trimIndent(), objectOf("code" to text("population_receipt_${java.util.UUID.randomUUID().toString().replace("-", "")}")), version, "partial-effects.olap.kts")
                    check(!partial.envelope.getValue("success").jsonPrimitive.boolean)
                    val effects = partial.report.array("calls").flatMap { it.jsonObject.array("effects") }.map { it.jsonObject }
                    check(effects.size == 1 && effects.single()["readOperation"] == text("read-asset-version"))
                    val reference = effects.single().objectAt("assetVersion")
                    val recovered = client.script("""
                        val exact = com.olap.prototype.governance.contract.GovernanceReadRequests.AssetVersionRequest(
                            parameters.text("assetType"), parameters.text("id"), parameters.text("version").toLong())
                        val asset = olap.semantics.asset(exact) as com.olap.prototype.governance.contract.AssetSnapshot.BusinessLine
                        check(asset.value.id == parameters.text("id"))
                        olap.output(ScriptValue.Text(asset.value.id))
                    """.trimIndent(), reference, version, "read-committed-effect.olap.kts").succeeded()
                    check(recovered.report.array("calls").map { it.jsonObject["operationId"] } == listOf(text("read-asset-version")))
                    report["partialEffects"] = objectOf("failedAuthor" to partial.report, "readCommittedEffect" to recovered.report)
                }
                if (fixture != null) {
                    report["unsupportedHttpCatalog"] = client.script("""
                        val selected = olap.sources.list(EmptyRequest).items.single { it.binding.dataSourceId == parameters.text("sourceId") }
                        val failure = runCatching {
                            olap.sources.inspect(SourceInspection.Snapshot(selected.binding, null, "main", null, listOf("TABLE"), 10))
                        }.exceptionOrNull() as com.olap.prototype.dsl.transport.CapabilityFailureException
                        check(failure.failure.code == "SOURCE_CAPABILITY_MISSING") { failure.failure.toString() }
                        olap.output(ScriptValue.Text(failure.failure.code))
                    """.trimIndent(), parameters, version, "unsupported-http-catalog.olap.kts").succeeded().report
                }
                val built = client.script(author, parameters.with("phase" to text("build")), version, "$business.olap.kts").succeeded()
                if (name == "populations") {
                    check(built.raw.objectAt("result")["delivery"] == text("SAVED")) { "完整作者回执应经过正式大响应保存及继续读取" }
                    report["completeBuildResponse"] = built.raw.objectAt("result")
                }
                references = built.references()
                report["build"] = built.report
                if (name == "populations") {
                    val rejected = references.objectAt("dailyInputRejected").objectAt("object").objectAt("values")
                    check(rejected.objectAt("operation")["text"] == text("validate-query") &&
                        rejected.objectAt("code")["text"] == text("RESULT_UNIT_MISMATCH") &&
                        rejected.objectAt("stage")["text"] == text("ANALYZE_STAGES"))
                    report["dailyInputContractMismatch"] = rejected
                    report["dailyTemplateReuse"] = JsonObject(listOf("Inline", "Nested", "Published").associate { stage ->
                        stage.lowercase() to objectOf(
                            "query" to references.objectAt("daily${stage}Query").objectAt("reference").getValue("query"),
                            "result" to references.objectAt("daily${stage}Result").objectAt("reference").getValue("result"))
                    })
                    report["sourceRelationCases"] = JsonObject(listOf("labelRelations", "ledgerOwnership", "ledgerIntersection").associate { case ->
                        case to objectOf(
                            "query" to references.objectAt("${case}Query").objectAt("reference").getValue("query"),
                            "result" to references.objectAt("${case}Result").objectAt("reference").getValue("result"))
                    })
                    report["ledgerBagCases"] = JsonObject(listOf("R", "S", "Both").associate { family ->
                        family to objectOf(
                            "query" to references.objectAt("ledgerBag${family}Query").objectAt("reference").getValue("query"),
                            "result" to references.objectAt("ledgerBag${family}Result").objectAt("reference").getValue("result"))
                    })
                    report["completedPopulationCase"] = objectOf(
                        "query" to references.objectAt("completedPopulationQuery").objectAt("reference").getValue("query"),
                        "result" to references.objectAt("completedPopulationResult").objectAt("reference").getValue("result"))
                }
            }
            val savedParameters = parameters.with(
                "query" to references.objectAt("query").objectAt("reference").getValue("query"),
                "result" to references.objectAt("result").objectAt("reference").getValue("result")
            ).let { saved ->
                if (name == "populations") saved.with(
                    "captionQuery" to references.objectAt("captionQuery").objectAt("reference").getValue("query"),
                    "captionResult" to references.objectAt("captionResult").objectAt("reference").getValue("result")
                ) else saved
            }
            start("reopen").use {
                val after = client.call("list-data-sources")
                check(after == sourceBindings) { "来源绑定在服务重开后变化" }
                report["reopen"] = client.script(author, savedParameters.with("phase" to text("reopen")), version).succeeded().report
            }
            if (name == "populations") {
                val consumer = Files.readString(workspace.resolve("examples/populations/consume.olap.kts"))
                fun consume(publication: String, question: String, selectedCount: String): JsonObject {
                    val selected = references.objectAt(publication).objectAt("object").objectAt("values")
                    val releaseId = selected.objectAt("releaseId").getValue("text")
                    val releaseVersion = selected.objectAt("releaseVersion").getValue("text")
                    val scenarioName = selected.objectAt("scenarioDisplayName").getValue("text")
                    val candidates = buildList {
                        var offset = 0
                        while (true) {
                            val page = client.call("list-publications", objectOf("releaseId" to releaseId,
                                "offset" to number(offset), "limit" to number(1))).jsonObject
                            addAll(page.array("items").map { it.jsonObject })
                            val nextValue = page.getValue("nextOffset")
                            if (nextValue == JsonNull) break
                            val next = nextValue.jsonPrimitive.int
                            check(next > offset) { "发布发现的分页未前进" }
                            offset = next
                        }
                    }
                    val entry = candidates.single { it["releaseId"] == releaseId && it["releaseVersion"] == releaseVersion &&
                        it["scenarioDisplayName"] == scenarioName }
                    val request = objectOf(
                        "releaseId" to releaseId, "releaseVersion" to releaseVersion, "scenarioDisplayName" to scenarioName,
                        "scenarioId" to entry.getValue("scenarioId"),
                        "scenarioVersion" to text(entry.getValue("scenarioVersion").jsonPrimitive.long.toString()),
                        "question" to text(question), "selectedCount" to text(selectedCount),
                        "previousStart" to text("19991231"), "previousEnd" to text("19991231"),
                        "currentStart" to text("20000101"), "currentEnd" to text(if (question == "Q2") "20000101" else "20000102")
                    )
                    return client.script(consumer, request, version, "consume-populations.olap.kts").succeeded().report
                }
                start("consume-definitions").use {
                    report["semanticReuse"] = objectOf(
                        "originalQ1" to consume("reuseOriginalPublication", "Q1", "source_record_count"),
                        "originalQ2" to consume("reuseOriginalPublication", "Q2", "source_record_count"),
                        "originalQ3" to consume("reuseOriginalPublication", "Q3", "known_amount_count"),
                        "revisedQ1" to consume("reuseRevisedPublication", "Q1", "source_record_count"),
                        "originalQ1AfterRevision" to consume("reuseOriginalPublication", "Q1", "source_record_count")
                    )
                }
            }
            config = config.with("connectors" to JsonArray(emptyList()), "dataSources" to JsonArray(emptyList()))
            write(configPath, config)
            start("saved-result-without-source").use {
                report["savedResultWithoutSource"] = client.script(author, savedParameters.with("phase" to text("read-result")), version).succeeded().report
            }
            hashes.forEach { (path, hash) -> check(sha(path) == hash) { "只读服务改变业务来源：$path" } }
            if (name in setOf("orders", "populations")) {
                prepare(connectors, "advance-source", input)
                hashes.forEach { (path, hash) -> check(sha(path) != hash) { "外部业务数据未发生预期改变：$path" } }
                config = config.with("connectors" to read(connectors), "dataSources" to sources)
                write(configPath, config)
                val changed = start("changed-source").use {
                    client.script(author, savedParameters.with("phase" to text("refresh-source")), version).succeeded()
                }
                report["changedSource"] = changed.report
                if (name == "populations") {
                    val freshCaptionResult = changed.references().objectAt("freshCaptionResult").objectAt("reference").getValue("result")
                    config = config.with("connectors" to JsonArray(emptyList()), "dataSources" to JsonArray(emptyList()))
                    write(configPath, config)
                    start("caption-history-without-source").use {
                        report["captionHistoryWithoutSource"] = client.script(author, savedParameters.with(
                            "phase" to text("read-caption-history"), "freshCaptionResult" to freshCaptionResult), version).succeeded().report
                    }
                }
            }
            report["passed"] = bool(true)
            write(output.resolve("http/report.json"), JsonObject(report))
        }
    }
    private fun prepare(connectors: Path, phase: String, input: Path) {
        val main = mapOf("orders" to "Orders", "procurement" to "Procurement", "subscriptions" to "Subscriptions", "populations" to "Populations").getValue(business)
        execute(listOf(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", System.getProperty("java.class.path"),
            main, "--connectors", connectors.toAbsolutePath().toString(), phase, input.toAbsolutePath().toString()), workspace)
    }
    private fun authentication(endpoint: String, issuer: LocalIssuer, audience: String) {
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
        for (token in listOf("", issuer.token(audience + "/wrong", "http-trial-manager"))) {
            val request = HttpRequest.newBuilder(URI.create(endpoint)).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
            if (token.isNotEmpty()) request.header("Authorization", "Bearer $token")
            check(client.send(request.POST(HttpRequest.BodyPublishers.ofString("{}" )).build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 401)
        }
    }
}

/** 研发场景自己的真实 HTTPS JWKS；不要求生产产品携带 keytool。 */
internal class LocalIssuer(output: Path) : Closeable {
    private val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val key: PrivateKey
    val ca = output.resolve("issuer-ca.pem")
    val address = "https://127.0.0.1:${server.address.port}/"
    init {
        val store = output.resolve("issuer.p12")
        val keytool = Path.of(System.getProperty("java.home"), "bin/keytool").toString()
        execute(listOf(keytool, "-genkeypair", "-alias", "issuer", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=owned-olap-test-issuer", "-ext", "SAN=ip:127.0.0.1",
            "-validity", "2", "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "scenario-owned-key", "-keypass", "scenario-owned-key", "-noprompt"), output)
        execute(listOf(keytool, "-exportcert", "-rfc", "-alias", "issuer", "-keystore", store.toString(), "-storepass", "scenario-owned-key", "-file", ca.toString()), output)
        val keystore = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(store).use { load(it, "scenario-owned-key".toCharArray()) } }
        key = keystore.getKey("issuer", "scenario-owned-key".toCharArray()) as PrivateKey
        val manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keystore, "scenario-owned-key".toCharArray()) }
        val context = SSLContext.getInstance("TLS").apply { init(manager.keyManagers, null, null) }
        server.httpsConfigurator = HttpsConfigurator(context)
        val public = keystore.getCertificate("issuer").publicKey as RSAPublicKey
        val body = objectOf("keys" to JsonArray(listOf(objectOf("kty" to text("RSA"), "use" to text("sig"), "alg" to text("RS256"),
            "kid" to text("owned-key"), "n" to text(integer(public.modulus)), "e" to text(integer(public.publicExponent)))))).toString().toByteArray()
        server.createContext("/jwks") { exchange -> exchange.responseHeaders.add("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) } }
        server.start()
    }
    fun token(audience: String, subject: String): String {
        val now = Instant.now().epochSecond
        val header = objectOf("alg" to text("RS256"), "typ" to text("JWT"), "kid" to text("owned-key"))
        val body = objectOf("iss" to text(address), "aud" to text(audience), "sub" to text(subject), "scope" to text("olap.manage"),
            "iat" to number(now), "exp" to number(now + 1800))
        val input = "${encoded(header.toString().toByteArray())}.${encoded(body.toString().toByteArray())}"
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(key); update(input.toByteArray(Charsets.US_ASCII)) }.sign()
        return "$input.${encoded(signature)}"
    }
    override fun close() { server.stop(0) }
    private fun encoded(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private fun integer(value: BigInteger) = encoded(value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
}
