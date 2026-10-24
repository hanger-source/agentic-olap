package com.olap.development

import kotlinx.serialization.json.*
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** 安装验收只能消费实际 npm 包；运行产品时 PATH 不提供开发机工具，且外网代理不可用。 */
internal class InstalledProduct(private val tarballs: Path, private val root: Path, private val workspace: Path, private val mode: String) {
    private val packageRoot = root.resolve("node_modules/@hanger-source/agentic-olap")
    private val cli = root.resolve("node_modules/.bin/agentic-olap")
    private val home = root.resolve("persistent home")
    private val environment = mapOf("PATH" to root.resolve("no-system-tools").toString(), "HOME" to root.toString(),
        "TMPDIR" to root.resolve("tmp").toString(), "LANG" to "en_US.UTF-8", "HTTP_PROXY" to "http://127.0.0.1:9",
        "HTTPS_PROXY" to "http://127.0.0.1:9", "NO_PROXY" to "127.0.0.1,localhost")
    private val port = ServerSocket(0).use { it.localPort }
    private lateinit var info: JsonObject
    private lateinit var config: JsonObject
    private lateinit var client: McpClient
    private lateinit var dslVersion: String
    private lateinit var author: String
    private var process: ManagedProcess? = null
    private var orderColumnVersion = ""

    fun run() {
        require(mode in setOf("tarball", "registry"))
        require(!Files.exists(root)) { "安装消费目录必须不存在：$root" }
        Files.createDirectories(root.resolve("tmp"))
        try {
            val archives = Files.list(tarballs).use { values -> values.filter { it.fileName.toString().matches(Regex("hanger-source-agentic-olap-\\d.*\\.tgz")) }.toList() }
            require(archives.size == 1) { "需要一个实际 npm 产品包" }
            val version = archives.single().fileName.toString().removePrefix("hanger-source-agentic-olap-").removeSuffix(".tgz")
            write(root.resolve("package.json"), objectOf("private" to bool(true)))
            execute(listOf("npm", "install", "--no-audit", "--no-fund", "--registry=https://registry.npmjs.org",
                if (mode == "registry") "@hanger-source/agentic-olap@$version" else archives.single().toAbsolutePath().toString()), root,
                mapOf("AGENTIC_OLAP_CACHE" to (System.getenv("AGENTIC_OLAP_CACHE") ?: root.resolve("dependency-cache").toString())))
            val metadata = read(packageRoot.resolve("package.json")).jsonObject
            check(metadata.string("version") == version)
            val sdk = jsonCommand("sdk")
            check(sdk.string("version") == version && command("sdk", "--repository").trim() == sdk.string("mavenRepository"))
            val groups = jsonCommand("connectors").array("connectors").map { Path.of(it.jsonObject.string("manifestPath")) }
            val h2 = groups.single { read(it).jsonObject.objectAt("connectorRef").string("id") == "h2" }
            val manifest = read(h2).jsonObject
            val driver = manifest.array("jars").map { it.jsonObject.string("file") }.single { it.matches(Regex("h2-.*\\.jar")) }
            val jdbc = "jdbc:h2:${root.resolve("business")}"
            Files.writeString(root.resolve("source.sql"), "CREATE TABLE SALES_ORDERS (ORDER_ID BIGINT PRIMARY KEY, AMOUNT DECIMAL(18,2));\nINSERT INTO SALES_ORDERS VALUES (1,100.00),(2,50.00),(3,25.00);\n")
            execute(listOf(packageRoot.resolve("jre/bin/java").toString(), "-cp", h2.parent.resolve(driver).toString(), "org.h2.tools.RunScript", "-url", jdbc, "-user", "sa", "-script", root.resolve("source.sql").toString()), root)
            author = Files.readString(workspace.resolve("project/cli/tests/installed.olap.kts"))
            start("initial")
            info = jsonCommand("info", "--home", home.toString())
            config = read(Path.of(info.string("configPath"))).jsonObject
            check(info.string("url") == "http://127.0.0.1:$port/mcp")
            val tools = jsonCommand("tools", "--home", home.toString()).array("tools").map { it.jsonObject.string("name") }
            check("describe-script-api" in tools && "execute-script" in tools)
            initializeClient()
            dslVersion = client.call("describe-script-api").jsonObject.string("dslApiVersion")
            val created = program(objectOf("phase" to text("create")))
            val businessLineId = created.objectAt("businessLineId").string("text")
            stop()
            config = config.with("connectors" to JsonArray(listOf(text(h2.toString()))), "dataSources" to JsonArray(listOf(objectOf(
                "dataSourceId" to text("business"), "connectorRef" to manifest.getValue("connectorRef"), "dialectId" to text("h2"),
                "identityParameters" to objectOf("jdbcUrl" to text(jdbc), "username" to text("sa"), "driverClassName" to text("org.h2.Driver")),
                "runtimeParameters" to JsonObject(emptyMap()), "credentialEnvironmentVariables" to JsonObject(emptyMap())))))
            saveConfig(); start("source-reopened"); initializeClient()
            check(client.call("list-data-sources").jsonObject.array("items").single().jsonObject.objectAt("binding").string("dataSourceId") == "business")
            val structure = program(objectOf("phase" to text("structure"), "businessLineId" to text(businessLineId)))
            val snapshotId = structure.objectAt("snapshotId").string("text")
            val sourceIdentityHash = structure.objectAt("sourceIdentityHash").string("text")
            check(sourceIdentityHash.matches(Regex("[a-f0-9]{64}")))
            stop(); start("structure-reopened"); initializeClient()
            val built = program(objectOf("phase" to text("build"), "businessLineId" to text(businessLineId), "snapshotId" to text(snapshotId)))
            val query = built.objectAt("query").objectAt("reference").objectAt("query")
            val result = built.objectAt("result").objectAt("reference").objectAt("result")
            val continued = built.objectAt("continuedResult").objectAt("reference").objectAt("result")
            orderColumnVersion = built.objectAt("orderColumnVersion").string("text")
            salesResult(result); salesResult(continued)
            authorContracts()
            stop(); start("analysis-reopened"); initializeClient()
            salesResult(result)
            val reopened = program(objectOf("phase" to text("reopen"), "definitionId" to query.getValue("definitionId"),
                "definitionVersion" to query.getValue("definitionVersion"), "querySha256" to query.getValue("sha256"),
                "resultRunId" to result.getValue("runId"), "resultSha256" to result.getValue("sha256")))
            val repeated = reopened.objectAt("result").objectAt("reference").objectAt("result")
            check(repeated["runId"] != result["runId"]); salesResult(repeated)
            stop()
            val complete = completeResponses()
            write(root.resolve("report.json"), objectOf("version" to text(command("--version").trim()), "target" to text("darwin-arm64"),
                "mode" to text(mode), "package" to text("${metadata.string("name")}@$version"), "dslApiVersion" to text(dslVersion),
                "persistentRead" to bool(true), "actualSourceRead" to bool(true), "declaredStructure" to bool(true), "structurePersistentRead" to bool(true),
                "analysisPersistentRead" to bool(true), "sourceIdentityHash" to text(sourceIdentityHash), "queryReference" to query,
                "runIds" to JsonArray(listOf(result.getValue("runId"), continued.getValue("runId"), repeated.getValue("runId"))),
                "expected" to objectOf("sales_amount" to text("175.00"), "order_count" to text("3")), "completeResponseDelivery" to complete))
        } catch (failure: Throwable) {
            write(root.resolve("report.json"), objectOf("status" to text("FAILED"), "message" to text(failure.message ?: failure.javaClass.name)))
            throw failure
        } finally { stop() }
    }

    private fun start(name: String) {
        check(process == null)
        process = ManagedProcess(listOf(cli.toString(), "start", "--home", home.toString(), "--port", port.toString()), root,
            root.resolve("$name.log"), environment, isolatedEnvironment = true).also { child ->
            child.ready { runCatching { java.net.Socket("127.0.0.1", port).use { true } }.getOrDefault(false) }
        }
    }
    private fun stop() { process?.close(); process = null }
    private fun initializeClient() {
        client = McpClient(info.string("url"), { Files.readString(Path.of(config.objectAt("auth").string("tokenFile"))).trim() }, root.resolve("mcp.log"))
        client.responseLimit = config.objectAt("limits").getValue("responseBytes").jsonPrimitive.int
    }
    private fun saveConfig() = write(Path.of(info.string("configPath")), config)
    private fun command(vararg args: String): String {
        val builder = ProcessBuilder(listOf(cli.toString()) + args).directory(root.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT)
        builder.environment().clear(); builder.environment().putAll(environment)
        val child = builder.start()
        val value = child.inputStream.bufferedReader().use { it.readText() }
        check(child.waitFor() == 0) { "安装后的公开 CLI 失败：${args.firstOrNull()}" }; return value
    }
    private fun jsonCommand(vararg args: String) = Json.parseToJsonElement(command(*args)).jsonObject
    private fun program(parameters: JsonObject): JsonObject {
        val execution = client.script(author, parameters, dslVersion).succeeded()
        check(execution.report.array("calls").size > 1)
        return execution.references()
    }
    private fun salesResult(reference: JsonObject) {
        val page = client.call("read-query-result", objectOf("resultReference" to reference, "offset" to number(0), "limit" to number(10))).jsonObject
        check(page.getValue("totalRows").jsonPrimitive.long == 1L && page.array("rows").size == 1)
        check(page.array("columns").map { it.jsonObject.objectAt("symbol").string("name") } == listOf("sales_amount", "order_count"))
        val values = page.array("rows")[0].jsonObject.array("values").map { it.jsonObject.objectAt("complete").objectAt("scalar") }
        check(values[0].string("type") == "DECIMAL" && values[0].string("value").toBigDecimal().compareTo("175".toBigDecimal()) == 0)
        check(values[1] == objectOf("type" to text("INTEGER"), "value" to text("3")))
        val metadata = client.call("read-query-result-metadata", objectOf("resultReference" to reference, "section" to text("RUN"), "offset" to number(0), "limit" to number(20))).jsonObject
        check(metadata.objectAt("content").objectAt("run").objectAt("run")["runId"] == reference["runId"])
        val explanation = client.call("read-query-result-metadata", objectOf("resultReference" to reference, "section" to text("EXPLANATION"),
            "explanationSection" to text("EXECUTION_REQUIREMENTS"), "offset" to number(0), "limit" to number(20))).jsonObject
            .objectAt("content").objectAt("explanation").objectAt("executionRequirements").objectAt("value")
        val source = explanation.array("sourceConstraints").map { it.jsonObject }.single { it["schema"] == text("PUBLIC") && it["table"] == text("SALES_ORDERS") }
        check(source.array("constraints").map { it.jsonObject }.single { it["kind"] == text("NOT_NULL") }.objectAt("columns") == objectOf("ORDER_ID" to text(orderColumnVersion)))
    }
    private fun authorContracts() {
        val invalid = client.script("Calculation.Scalar(ScalarValueType.BOOLEAN, true)", JsonObject(emptyMap()), dslVersion)
        check(!invalid.envelope.getValue("success").jsonPrimitive.boolean && invalid.report.array("calls").isEmpty())
        check(invalid.report.array("diagnostics").any { it.jsonObject["stage"] == text("COMPILE") })
        val rules = client.call("describe-script-api", objectOf("type" to text("com.olap.prototype.calculation.Calculation.Call"))).jsonObject
            .objectAt("rules").objectAt("targetRules").objectAt("MEASUREMENT").array("emptyMaps")
        check(rules == JsonArray(listOf(text("dimensionSlots"), text("measurementSlots"))))
        for (field in listOf("dimensionSlots", "measurementSlots", "valid")) {
            val source = """
                val selected = Calculation.Call("selected", Calculation.Measurement("adopted"), "total",
                  ${if (field == "dimensionSlots") "mapOf(\"seller\" to \"seller\")" else "emptyMap()"}, emptyMap(),
                  ${if (field == "measurementSlots") "mapOf(\"amount\" to \"amount\")" else "emptyMap()"}, emptyMap(), emptyMap(),
                  Calculation.Condition(DimensionCondition.Unrestricted()))
                olap.output(olap.sources.list(EmptyRequest))
            """.trimIndent()
            val execution = client.script(source, JsonObject(emptyMap()), dslVersion, "author-contract.olap.kts")
            if (field == "valid") execution.succeeded() else {
                check(execution.report.array("calls").isEmpty())
                val diagnostic = execution.report.array("diagnostics").map { it.jsonObject }.single { it["code"] == text("MEASUREMENT_BINDING_OVERRIDE") }
                check(diagnostic["stage"] == text("CONTRACT_INPUT") && diagnostic["fieldPath"] == JsonArray(listOf(text(field))))
                check(diagnostic.objectAt("location").getValue("startLine").jsonPrimitive.int > 0)
            }
        }
        val memberRules = client.call("describe-script-api", objectOf("type" to text("com.olap.prototype.member.MemberResult"))).jsonObject
            .objectAt("rules").objectAt("outputRules").objectAt("quantity")
        check(memberRules["valueType"] == text("NUMBER") && memberRules["unitEquations"] == text("EMPTY_MAP"))
        for ((equations, valueType, code) in listOf(Triple(true, "NUMBER", "MEMBER_QUANTITY_UNIT_EQUATIONS"),
            Triple(false, "TEXT", "MEMBER_QUANTITY_TYPE"), Triple(false, "NUMBER", ""))) {
            val source = """
                val quantity = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(),
                  MetricValueContract.Integer(64), ${if (equations) "mapOf(\"ratio\" to MetricValueContract.SameUnitRatio())" else "emptyMap()"}, MetricValueContract.Overflow.FAIL)
                val result = MemberResult("observation", "observation", MemberShape.RAW_BAG, emptyList(), mapOf(
                  "total" to MemberSourceDefinition.ComputedPropertyOutput("合计", "total", ScalarValueType.$valueType, "total", quantity)),
                  emptyMap(), null, emptyMap(), null)
                olap.output(olap.sources.list(EmptyRequest))
            """.trimIndent()
            val execution = client.script(source, JsonObject(emptyMap()), dslVersion, "author-contract.olap.kts")
            if (code.isEmpty()) execution.succeeded() else {
                check(execution.report.array("calls").isEmpty())
                val diagnostic = execution.report.array("diagnostics").map { it.jsonObject }.single { it["code"] == text(code) }
                check(diagnostic["stage"] == text("CONTRACT_INPUT") && diagnostic.objectAt("location").getValue("startLine").jsonPrimitive.int > 0)
            }
        }
    }

    private fun completeResponses(): JsonObject {
        config = config.with("limits" to config.objectAt("limits").with("responseBytes" to number(65536), "pendingOutputBytes" to number(131072)))
        saveConfig(); start("complete-response"); initializeClient()
        val catalog = JsonArray((0 until 14).map { table -> objectOf("name" to text("table_$table"), "columns" to JsonArray((0 until if (table == 0) 114 else 113).map { column ->
            JsonObject((0 until 11).associate { property -> "property_$property" to if (property == 10) JsonNull else text("$table/$column/$property") })
        })) })
        val ordinary = client.script("""
            val catalog = parameters["declaredStructure"] as ScriptValue.ArrayValue
            val columns = catalog.values.sumOf { ((it as ScriptValue.ObjectValue).values.getValue("columns") as ScriptValue.ArrayValue).values.size }
            val first = olap.sources.list(EmptyRequest)
            val second = olap.sources.list(EmptyRequest)
            check(first.items == second.items)
            olap.output(ScriptValue.DecimalValue(columns.toString()))
            first
        """.trimIndent(), objectOf("declaredStructure" to catalog), dslVersion).succeeded()
        check(ordinary.value()["decimal"] == text("1583"))
        val escaped = client.script("olap.output(ScriptValue.Text(\"\\u0001\".repeat(14000)))", JsonObject(emptyMap()), dslVersion).succeeded()
        check(escaped.raw.objectAt("result")["delivery"] == text("SAVED") && escaped.value()["text"] == text("\u0001".repeat(14000)))
        val large = client.script("olap.output(ScriptValue.Text(\"标签🌟\".repeat(20000)))", JsonObject(emptyMap()), dslVersion).succeeded()
        check(large.raw.objectAt("result")["delivery"] == text("SAVED"))
        val savedPayload = large.raw.objectAt("result").objectAt("read").objectAt("payload")
        check(client.rpc("read-response", savedPayload, "r".repeat(64000)).getValue("success").jsonPrimitive.boolean)
        stop(); start("response-reopened"); initializeClient()
        val bytes = client.savedBytes(savedPayload)
        check(bytes.contentEquals(Files.readAllBytes(Path.of(large.raw.objectAt("result").string("location")))))
        check(large.value()["text"] == text("标签🌟".repeat(20000)))
        stop()
        config = config.with("limits" to config.objectAt("limits").with("responseBytes" to number(1536)))
        saveConfig(); start("partial-response"); initializeClient()
        val partial = client.script("""
            val created = olap.governance.createBusinessLine(BusinessLineCreateRequest(parameters.text("code"), "部分成功", olap.caller.subject, "完整失败回执", olap.caller.subject)).businessLine
            olap.governance.activate(GovernanceVersionActivationRequest(GovernanceActivationObjectType.BUSINESS_LINE, created.id, 1, olap.caller.subject, "明确激活"))
            olap.output(ScriptValue.Text(created.id))
            error("author stopped after the committed write")
        """.trimIndent(), objectOf("code" to text("partial_${UUID.randomUUID().toString().replace("-", "")}")), dslVersion)
        check(!partial.envelope.getValue("success").jsonPrimitive.boolean && partial.raw.objectAt("result")["delivery"] == text("SAVED"))
        val effects = partial.report.array("calls").flatMap { it.jsonObject.array("effects") }.map { it.jsonObject }
        check(effects.size == 2 && effects.all { it["readOperation"] == text("read-asset-version") } && effects[0]["assetVersion"] == effects[1]["assetVersion"])
        check(partial.value()["text"] == effects[0].objectAt("assetVersion")["id"])
        val recovered = client.script("""
            val id = parameters.text("id")
            val version = parameters.text("version").toLong()
            val snapshot = olap.semantics.asset(GovernanceReadRequests.AssetVersionRequest(parameters.text("assetType"), id, version)) as AssetSnapshot.BusinessLine
            check(snapshot.value.id == id && snapshot.value.currentVersion == version && snapshot.value.status == "ACTIVE")
            olap.output(ScriptValue.Text(snapshot.value.id))
        """.trimIndent(), effects[1].objectAt("assetVersion"), dslVersion).succeeded()
        check(recovered.report.array("calls").map { it.jsonObject["operationId"] } == listOf(text("read-asset-version")))
        stop()
        val auth = config.objectAt("auth")
        config = config.with("auth" to auth.with("grants" to JsonArray(auth.array("grants").map { raw ->
            val grant = raw.jsonObject; grant.with("operations" to JsonArray(grant.array("operations").filter { it != text("execute-query") }))
        })))
        saveConfig(); start("response-authority-changed"); initializeClient()
        val refused = client.rpc("read-response", savedPayload)
        check(!refused.getValue("success").jsonPrimitive.boolean && refused.objectAt("error")["code"] == text("SAVED_RESPONSE_AUTHORIZATION_CHANGED"))
        stop()
        return objectOf("parameterColumns" to number(1583), "escapedJSONSaved" to bool(true), "internalFileFrame" to bool(true), "restartRead" to bool(true),
            "completeSHA256" to savedPayload.objectAt("reference").getValue("sha256"), "partialWriteRecovered" to bool(true), "authorityChangeRefused" to bool(true))
    }
}
