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
            Files.writeString(root.resolve("source.sql"), "CREATE TABLE SALES_ORDERS (ORDER_ID BIGINT PRIMARY KEY, AMOUNT DECIMAL(18,2), CALENDAR_DAYS INTEGER);\nINSERT INTO SALES_ORDERS VALUES (1,100.00,30),(2,50.00,30),(3,25.00,30),(4,NULL,31);\n")
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
            val numericReference = built.objectAt("numericResult").objectAt("reference").objectAt("result")
            orderColumnVersion = built.objectAt("orderColumnVersion").string("text")
            salesResult(result); salesResult(continued)
            val usagePagination = usagePagination(result)
            val numeric = divisionResult(numericReference)
            authorContracts(query, result)
            val serialCalls = serialCapabilities(built)
            val approvals = stageApprovalContracts(businessLineId, built.objectAt("scenarioId").string("text"))
            stop(); start("analysis-reopened"); initializeClient()
            salesResult(result)
            check(divisionResult(numericReference) == numeric)
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
                "expected" to objectOf("sales_amount" to text("175.00"), "order_count" to text("4")), "integerDivision" to numeric,
                "serialCapabilities" to serialCalls, "stageAuthorization" to approvals, "usagePagination" to usagePagination,
                "completeResponseDelivery" to complete))
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
    private fun usagePagination(reference: JsonObject): JsonObject {
        val runId = reference.getValue("runId")
        val expectedNotes = (1..3).map { "正式保存结果的分页说明 $it" }
        expectedNotes.forEach { client.call("append-query-usage-note", objectOf("runId" to runId, "text" to text(it))) }
        val first = client.call("read-query-usage", objectOf("runId" to runId, "offset" to number(0), "limit" to number(2))).jsonObject
        check(first.array("notes").size == 2)
        val next = first.getValue("nextNoteOffset")
        check(next.jsonPrimitive.isString && next.jsonPrimitive.long == 2L)
        val second = client.call("read-query-usage", objectOf("runId" to runId, "offset" to next, "limit" to number(1000))).jsonObject
        check(second.array("notes").size == 1 && !second.containsKey("nextNoteOffset"))
        val actualNotes = first.array("notes") + second.array("notes")
        check(actualNotes.map { it.jsonObject.string("text") }.toSet() == expectedNotes.toSet())
        check(actualNotes.map { it.jsonObject.string("id") }.distinct().size == 3)
        val maximumPage = client.call("read-query-usage", objectOf("runId" to runId, "offset" to number(0), "limit" to number(Int.MAX_VALUE))).jsonObject
        check(maximumPage.array("notes").size == 3 && !maximumPage.containsKey("nextNoteOffset"))
        val pastEnd = client.call("read-query-usage", objectOf("runId" to runId, "offset" to text("2147483648"), "limit" to number(Int.MAX_VALUE))).jsonObject
        check((pastEnd["notes"] as? JsonArray).orEmpty().isEmpty() && !pastEnd.containsKey("nextNoteOffset"))
        for ((field, actual, minimum) in listOf(Triple("limit", 0, 1L), Triple("offset", -1, 0L))) {
            val envelope = client.delivered(client.rpc("read-query-usage", objectOf("runId" to runId, field to number(actual))))
            check(!envelope.getValue("success").jsonPrimitive.boolean)
            val error = envelope.objectAt("error")
            check(error.string("code") == "INVALID_PAGINATION" && error.string("stage") == "CONTRACT_INPUT")
            val details = error.objectAt("details")
            check(details.array("fieldPath") == JsonArray(listOf(text(field))))
            check(details.getValue("actual").jsonPrimitive.long == actual.toLong())
            check(details.objectAt("expected").getValue("minimum").jsonPrimitive.long == minimum)
        }
        val persisted = maximumPage.objectAt("run")
        check(persisted.getValue("runId") == runId && persisted.objectAt("result").getValue("sha256") == reference.getValue("sha256"))
        return objectOf("largeTypedAndJsonPagesRead" to bool(true), "nextOffsetReused" to bool(true),
            "longOffsetRead" to bool(true), "invalidFieldsExplained" to bool(true))
    }

    private fun salesResult(reference: JsonObject) {
        val page = client.call("read-query-result", objectOf("resultReference" to reference, "offset" to number(0), "limit" to number(10))).jsonObject
        check(page.getValue("totalRows").jsonPrimitive.long == 1L && page.array("rows").size == 1)
        check(page.array("columns").map { it.jsonObject.objectAt("symbol").string("name") } == listOf("sales_amount", "order_count"))
        val values = page.array("rows")[0].jsonObject.array("values").map { it.jsonObject.objectAt("complete").objectAt("scalar") }
        check(values[0].string("type") == "DECIMAL" && values[0].string("value").toBigDecimal().compareTo("175".toBigDecimal()) == 0)
        check(values[1] == objectOf("type" to text("INTEGER"), "value" to text("4")))
        val metadata = client.call("read-query-result-metadata", objectOf("resultReference" to reference, "section" to text("RUN"), "offset" to number(0), "limit" to number(20))).jsonObject
        check(metadata.objectAt("content").objectAt("run").getValue("runId") == reference.getValue("runId"))
        val explanation = client.call("read-query-result-metadata", objectOf("resultReference" to reference, "section" to text("EXPLANATION"),
            "explanationSection" to text("EXECUTION_REQUIREMENTS"), "offset" to number(0), "limit" to number(20))).jsonObject
            .objectAt("content").objectAt("explanation").objectAt("executionRequirements").objectAt("value")
        val source = explanation.array("sourceConstraints").map { it.jsonObject }.single { it["schema"] == text("PUBLIC") && it["table"] == text("SALES_ORDERS") }
        check(source.array("constraints").map { it.jsonObject }.single { it["kind"] == text("NOT_NULL") }.objectAt("columns") == objectOf("ORDER_ID" to text(orderColumnVersion)))
        val invalid = client.delivered(client.rpc("read-query-result-metadata", objectOf("resultReference" to reference,
            "section" to text("COLUMNS"), "offset" to number(-1), "limit" to number(1000))))
        check(!invalid.getValue("success").jsonPrimitive.boolean)
        val error = invalid.objectAt("error")
        check(error.string("code") == "INVALID_PAGINATION" && error.string("stage") == "CONTRACT_INPUT")
        check(error.objectAt("details").array("fieldPath") == JsonArray(listOf(text("offset"))))
        check(error.objectAt("details").getValue("actual").jsonPrimitive.int == -1)
    }
    private fun divisionResult(reference: JsonObject): JsonObject {
        val page = client.call("read-query-result", objectOf("resultReference" to reference, "offset" to number(0), "limit" to number(10))).jsonObject
        check(page.getValue("totalRows").jsonPrimitive.long == 1L && page.array("rows").size == 1)
        check(page.array("columns").map { it.jsonObject.objectAt("symbol").string("name") } == listOf("day_sum", "valid_count", "mean_days", "wide_ratio", "zero_ratio"))
        val complete = page.array("rows").single().jsonObject.array("values").map { it.jsonObject.objectAt("complete") }
        val values = complete.take(4).map { it.objectAt("scalar") }
        check(values.map { it.string("type") } == listOf("INTEGER", "INTEGER", "DECIMAL", "DECIMAL"))
        check(complete.last().keys == setOf("nullValue"))
        check(values[0].string("value") == "121" && values[1].string("value") == "4")
        check(values[2].string("value").toBigDecimal().compareTo("30.25".toBigDecimal()) == 0)
        check(values[3].string("value").toBigDecimal().compareTo("-9.223372036854775808".toBigDecimal()) == 0)
        return objectOf("resultReference" to reference, "sum" to values[0].getValue("value"), "count" to values[1].getValue("value"),
            "mean" to values[2].getValue("value"), "wideRatio" to values[3].getValue("value"), "zeroDenominator" to text("NULL"))
    }
    private fun serialCapabilities(built: JsonObject): JsonObject {
        var session: String? = null
        fun submit(source: String): ScriptResult {
            val id = UUID.randomUUID().toString()
            var payload = objectOf("executionId" to text(id), "waitMillis" to number(1000), "source" to text(source),
                "sourceName" to text("serial-definitions.olap.kts"), "dslApiVersion" to text(dslVersion),
                "parameters" to JsonObject(listOf("logicalTableId", "orderUnitId", "amountUnitId", "daysUnitId").associateWith {
                    built.objectAt(it).getValue("text")
                }))
            session?.let { payload = payload.with("sessionId" to text(it)) }
            var raw = client.rpc("execute-script", payload)
            var envelope = client.delivered(raw)
            var report = envelope.objectAt("result")
            if (session == null) session = report.string("sessionId") else check(report.string("sessionId") == session)
            while (report.string("state") == "RUNNING") {
                raw = client.rpc("wait-script", objectOf("executionId" to text(id), "waitMillis" to number(1000)))
                envelope = client.delivered(raw); report = envelope.objectAt("result")
            }
            check(report.string("executionId") == id && report.string("sessionId") == session)
            return ScriptResult(raw, envelope, report).succeeded()
        }
        try {
            val declaration = submit("""
                fun readInstalledFields(tableId: String, unitIds: List<String>) {
                    val table = olap.semantics.definition(GovernanceReadRequests.AssetVersionRequest("LOGICAL_TABLE", tableId, 1)) as GovernanceDefinition.LogicalTable
                    check(table.record.id == tableId && table.logicUnitCount == 3)
                    val units = unitIds.map { id ->
                        val unit = olap.semantics.definition(GovernanceReadRequests.AssetVersionRequest("LOGIC_UNIT", id, 1)) as GovernanceDefinition.LogicUnit
                        check(unit.record.id == id)
                        unit
                    }
                    check(units.size == table.logicUnitCount && units.map { it.record.code }.toSet() == setOf("ORDER_ID", "AMOUNT", "CALENDAR_DAYS"))
                }
            """.trimIndent())
            check(declaration.report.array("calls").isEmpty())
            val reports = (1..4).map {
                submit("""
                    repeat(24) { round ->
                        readInstalledFields(parameters.text("logicalTableId"), listOf(parameters.text("orderUnitId"), parameters.text("amountUnitId"), parameters.text("daysUnitId")))
                        if (round % 6 == 5) olap.output(ScriptValue.Text("completed:" + (round + 1)))
                    }
                """.trimIndent()).report.also { report ->
                    check(report["workerId"] == declaration.report["workerId"] && report.getValue("sessionUsable").jsonPrimitive.boolean)
                    val calls = report.array("calls").map { it.jsonObject }
                    check(calls.size == 96 && calls.all { call -> call.string("state") == "COMPLETED" && call.getValue("success").jsonPrimitive.boolean && call.array("effects").isEmpty() })
                    check(report.array("outputs").map { it.jsonObject.objectAt("value").string("text") } == listOf("completed:6", "completed:12", "completed:18", "completed:24"))
                }
            }
            return objectOf("sessionId" to text(requireNotNull(session)), "fragments" to number(reports.size),
                "completedReads" to number(reports.sumOf { it.array("calls").size }), "effects" to number(0))
        } finally {
            session?.let { check(client.call("close-script-session", objectOf("sessionId" to text(it))).jsonObject.string("state") == "CLOSED") }
        }
    }
    private fun authorContracts(query: JsonObject, result: JsonObject) {
        val invalid = client.script("Calculation.Scalar(ScalarValueType.BOOLEAN, true)", JsonObject(emptyMap()), dslVersion)
        check(!invalid.envelope.getValue("success").jsonPrimitive.boolean && invalid.report.array("calls").isEmpty())
        check(invalid.report.array("diagnostics").any { it.jsonObject["stage"] == text("COMPILE") })
        val paginationParameters = objectOf("definitionId" to query.getValue("definitionId"),
            "definitionVersion" to query.getValue("definitionVersion"), "querySha256" to query.getValue("sha256"),
            "runId" to result.getValue("runId"), "resultSha256" to result.getValue("sha256"))
        for ((field, source) in listOf(
            "offset" to """
                import com.olap.prototype.query.contract.*
                val query = QuerySelection.Saved(QueryReference(parameters.text("definitionId"),
                  parameters.text("definitionVersion").toLong(), parameters.text("querySha256")))
                olap.output(olap.queries.explain(QueryExplanationRequest(query, QueryExplanationSection.SUMMARY, 1, 1000)))
            """.trimIndent(),
            "limit" to """
                import com.olap.prototype.query.contract.*
                import com.olap.prototype.dsl.ResultMetadataRequest
                val result = QueryResultReference(parameters.text("runId"), parameters.text("resultSha256"))
                olap.output(olap.results.metadata(ResultMetadataRequest(result, ResultMetadataSection.COLUMNS, null, null, 0, 0)))
            """.trimIndent()
        )) {
            val execution = client.script(source, paginationParameters, dslVersion, "explanation-pagination.olap.kts")
            check(!execution.envelope.getValue("success").jsonPrimitive.boolean && execution.report.array("calls").isEmpty())
            val diagnostic = execution.report.array("diagnostics").map { it.jsonObject }.single { it["code"] == text("INVALID_PAGINATION") }
            check(diagnostic["stage"] == text("CONTRACT_INPUT") && diagnostic["fieldPath"] == JsonArray(listOf(text(field))))
            check(diagnostic.objectAt("location").getValue("startLine").jsonPrimitive.int > 0)
            check(diagnostic["actual"] == text(if (field == "offset") "1" else "0"))
        }
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

    private fun stageApprovalContracts(businessLineId: String, activeScenarioId: String): JsonObject {
        val rejected = client.script("""
            val actor = olap.caller.subject
            val subject = olap.governance.createSubjectArea(SubjectAreaCreateRequest(parameters.text("businessLineId"), 1,
              parameters.text("code"), "阶段批准", actor, "安装后的授权拒绝与续行", actor)).subjectArea
            val scenario = olap.governance.createScenario(ScenarioCreateRequest(subject.id, 1, "calculation", "计算", actor, null, actor)).scenario
            val full = StageAuthorization.current("DERIVE")
            val e = full.expressions
            val subset = StageAuthorization("DERIVE", full.joinTypes, full.aggregations, ExpressionAuthorization(e.kinds,
              e.operators - ResultExpression.ResultOperator.MULTIPLY, e.functions, e.castTypes, e.literalTypes))
            val created = olap.governance.createScenarioPipelineRule(ScenarioPipelineRuleCreateRequest(scenario.id, 1, "DERIVE", 1, subset)).rule
            check(created.authorization == subset)
            val reduced = StageAuthorization("DERIVE", subset.joinTypes, subset.aggregations, ExpressionAuthorization(e.kinds,
              subset.expressions.operators - ResultExpression.ResultOperator.DIVIDE, e.functions, e.castTypes, e.literalTypes))
            olap.governance.refreshScenarioPipelineRuleContract(ScenarioPipelineRuleContractRefreshRequest(scenario.id, 1, "DERIVE", reduced))
            val selected = olap.semantics.scenarioStages(VersionPageInput(scenario.id, 1, 0, 20)).items.single()
            check(selected.id == created.id && selected.authorization == reduced)
            olap.output(ScriptValue.Text(scenario.id))
            val unsupported = StageAuthorization("DERIVE", setOf(StageAuthorization.JoinType.LEFT), reduced.aggregations, reduced.expressions)
            olap.governance.refreshScenarioPipelineRuleContract(ScenarioPipelineRuleContractRefreshRequest(scenario.id, 1, "DERIVE", unsupported))
        """.trimIndent(), objectOf("businessLineId" to text(businessLineId),
            "code" to text("approval_${UUID.randomUUID().toString().replace("-", "")}")), dslVersion, "stage-approval.olap.kts")
        check(!rejected.envelope.getValue("success").jsonPrimitive.boolean)
        val diagnostic = rejected.report.array("diagnostics").map { it.jsonObject }
            .single { it["code"] == text("STAGE_AUTHORIZATION_UNSUPPORTED") }.objectAt("capabilityFailure")
        val failedCall = rejected.report.array("calls").map { it.jsonObject }
            .single { it["operationId"] == text("refresh-scenario-pipeline-rule-contract") && it["success"] == bool(false) }
        check(diagnostic["fieldPath"] == JsonArray(listOf(text("approvedAuthorization"))))
        check(diagnostic.objectAt("details").objectAt("unsupported").array("joinTypes") == JsonArray(listOf(text("LEFT"))))
        check(diagnostic["diagnosticId"] == failedCall["requestId"] && failedCall.array("effects").isEmpty())
        check(diagnostic.objectAt("details").objectAt("serviceDiagnostic")["requestId"] == failedCall["requestId"])
        val scenarioId = rejected.value().string("text")
        val resumed = client.script("""
            val id = parameters.text("scenarioId")
            val before = olap.semantics.scenarioStages(VersionPageInput(id, 1, 0, 20)).items.single()
            val full = StageAuthorization.current("DERIVE")
            val e = full.expressions
            val reduced = StageAuthorization("DERIVE", full.joinTypes, full.aggregations, ExpressionAuthorization(e.kinds,
              e.operators - setOf(ResultExpression.ResultOperator.MULTIPLY, ResultExpression.ResultOperator.DIVIDE), e.functions, e.castTypes, e.literalTypes))
            check(before.authorization == reduced)
            olap.governance.refreshScenarioPipelineRuleContract(ScenarioPipelineRuleContractRefreshRequest(id, 1, "DERIVE", full))
            val after = olap.semantics.scenarioStages(VersionPageInput(id, 1, 0, 20)).items.single()
            check(before.id == after.id && after.authorization == full)
            olap.output(ScriptValue.Text(after.id))
        """.trimIndent(), objectOf("scenarioId" to text(scenarioId)), dslVersion, "stage-approval-resume.olap.kts").succeeded()
        check(resumed.report.array("calls").all { it.jsonObject["success"] == bool(true) })
        val active = client.script("""
            olap.governance.refreshScenarioPipelineRuleContract(ScenarioPipelineRuleContractRefreshRequest(
              parameters.text("scenarioId"), 2, "DERIVE", StageAuthorization.current("DERIVE")))
        """.trimIndent(), objectOf("scenarioId" to text(activeScenarioId)), dslVersion, "frozen-stage-approval.olap.kts")
        check(!active.envelope.getValue("success").jsonPrimitive.boolean)
        val state = active.report.array("diagnostics").map { it.jsonObject }
            .single { it["code"] == text("GOVERNANCE_DRAFT_REQUIRED") }.objectAt("capabilityFailure").objectAt("details")
        check(state["actualStatus"] == text("ACTIVE") && state.objectAt("objectReference")["id"] == text(activeScenarioId))
        check(active.report.array("calls").single().jsonObject.array("effects").isEmpty())
        return objectOf("explicitSubsetCreated" to bool(true), "approvalReduced" to bool(true),
            "unsupportedSelectionRejected" to bool(true), "unchangedAfterRejection" to bool(true),
            "explicitApprovalSucceeded" to bool(true), "activeVersionRejected" to bool(true), "diagnosticsDelivered" to bool(true))
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
