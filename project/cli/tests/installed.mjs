// 从实际 npm tarball 安装并操作服务；运行环境不提供 Python、Java 或源码路径。
import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { once } from "node:events";
import { appendFileSync } from "node:fs";
import { mkdir, readFile, readdir, writeFile, open } from "node:fs/promises";
import { createServer } from "node:net";
import { createHash, randomUUID } from "node:crypto";
import { dirname, join, resolve } from "node:path";

const [tarballArg, outputArg, mode = "tarball"] = process.argv.slice(2);
assert(tarballArg && outputArg, "需要 npm tarball 目录与尚不存在的消费目录");
assert(["tarball", "registry"].includes(mode), "第三个参数只能是 registry 或 tarball");
const tarballs = resolve(tarballArg);
const root = resolve(outputArg);
await mkdir(root);
await mkdir(join(root, "tmp"));
const target = `${process.platform}-${process.arch}`;
const author = await readFile(new URL("./installed.olap.kts", import.meta.url), "utf8");
const files = await readdir(tarballs);
const main = files.filter((file) => /^hanger-source-agentic-olap-\d.*\.tgz$/.test(file));
assert.equal(main.length, 1);
await writeFile(join(root, "package.json"), JSON.stringify({ private: true }));
const version = /^hanger-source-agentic-olap-(.+)\.tgz$/.exec(main[0])[1];
const selection = mode === "registry"
  ? ["--cache", join(root, "npm-cache"), `@hanger-source/agentic-olap@${version}`]
  : [join(tarballs, main[0])];
const installed = spawnSync("npm", ["install", "--no-audit", "--no-fund", "--registry=https://registry.npmjs.org", ...selection], { cwd: root, encoding: "utf8", env: { ...process.env, AGENTIC_OLAP_CACHE: process.env.AGENTIC_OLAP_CACHE ?? join(root, "dependency-cache") } });
assert.equal(installed.status, 0, installed.stderr);
const cli = join(root, "node_modules/.bin/agentic-olap");
const packageRoot = join(root, "node_modules/@hanger-source/agentic-olap");
assert.equal(JSON.parse(await readFile(join(root, "node_modules/@hanger-source/agentic-olap/package.json"), "utf8")).version, version);
const packageMetadata = JSON.parse(await readFile(join(packageRoot, "package.json"), "utf8"));
assert.equal(packageMetadata.version, version);
const home = join(root, "persistent home");
const environment = { PATH: join(root, "no-system-tools"), HOME: root, TMPDIR: join(root, "tmp"), LANG: "en_US.UTF-8", HTTP_PROXY: "http://127.0.0.1:9", HTTPS_PROXY: "http://127.0.0.1:9", NO_PROXY: "127.0.0.1,localhost" };
const command = (args) => spawnSync(cli, args, { cwd: root, env: environment, encoding: "utf8", timeout: 180000 });
const json = (args) => {
  const result = command(args);
  assert.equal(result.status, 0, result.stderr + result.stdout);
  return JSON.parse(result.stdout);
};
const sdk = json(["sdk"]);
assert.equal(sdk.version, version);
assert.equal(command(["sdk", "--repository"]).stdout.trim(), sdk.mavenRepository);
const installedPlugins = json(["connectors"]);
const groups = await Promise.all(installedPlugins.connectors.map(async ({ manifestPath }) => ({
  directory: dirname(manifestPath), manifestPath, manifest: JSON.parse(await readFile(manifestPath, "utf8")),
})));
const h2Groups = groups.filter((group) => group.manifest.connectorRef.id === "h2");
assert.equal(h2Groups.length, 1, "本试验须使用 npm 安装的唯一 H2 插件组");
const { directory: h2Directory, manifestPath, manifest } = h2Groups[0];
const drivers = manifest.jars.filter((jar) => /^h2-.*\.jar$/.test(jar.file));
assert.equal(drivers.length, 1, "H2插件manifest须声明唯一的H2驱动JAR");
const jdbcUrl = `jdbc:h2:${join(root, "business")}`;
await writeFile(join(root, "source.sql"), "CREATE TABLE SALES_ORDERS (ORDER_ID BIGINT PRIMARY KEY, AMOUNT DECIMAL(18,2));\nINSERT INTO SALES_ORDERS VALUES (1,100.00),(2,50.00),(3,25.00);\n");
const prepared = spawnSync(join(packageRoot, "jre/bin/java"), ["-cp", join(h2Directory, drivers[0].file), "org.h2.tools.RunScript", "-url", jdbcUrl, "-user", "sa", "-script", join(root, "source.sql")], { encoding: "utf8" });
assert.equal(prepared.status, 0, prepared.stderr);
const cliEnvelope = (operation, payload) => {
  const invoked = command(["call", operation, "--payload", JSON.stringify(payload), "--home", home]);
  assert([0, 1].includes(invoked.status), invoked.stderr + invoked.stdout);
  const result = JSON.parse(invoked.stdout);
  assert.equal(result.isError ?? false, false);
  const response = JSON.parse(result.content[0].text);
  appendFileSync(join(root, "mcp.log"), JSON.stringify({ operation, payload, response }) + "\n");
  assert.equal(invoked.status, response.success ? 0 : 1, invoked.stderr + invoked.stdout);
  return response;
};
const call = (operation, payload) => {
  const response = cliEnvelope(operation, payload);
  assert.equal(response.success, true, JSON.stringify(response.error));
  return response.result;
};
let dslApiVersion;
let orderColumnVersion;
const scriptPayload = (source, parameters = {}, sourceName = "installed.olap.kts") => ({
  executionId: randomUUID(), waitMillis: 1000, source, sourceName, dslApiVersion, parameters,
});
const outputValues = (report) => report.outputs.filter(event => event.kind === "VALUE").map(event => event.value);
function outputValue(report) {
  const values = outputValues(report);
  assert.equal(values.length, 1, "该作者程序应显式输出一份完整值");
  return values[0];
}

// 只读取正式保存的完整消息，保持原成功/失败，不重新提交源码或业务操作。
async function completeResponse(invoke, response) {
  if (!response.result?.delivery) return response;
  assert.equal(response.result.delivery, "SAVED", JSON.stringify(response.result));
  const { operation, payload } = response.result.read;
  assert.equal(operation, "read-response");
  assert.equal(payload.offset, 0);
  const fragments = [];
  let offset = payload.offset;
  let bytes = 0;
  for (;;) {
    const pageResponse = await invoke(operation, { ...payload, offset });
    assert.equal(pageResponse.success, true, JSON.stringify(pageResponse.error));
    const page = pageResponse.result;
    assert.equal(page.offset, offset);
    fragments.push(page.content);
    bytes += Buffer.byteLength(page.content);
    if (page.nextOffset === null) {
      assert.equal(bytes, page.totalBytes);
      break;
    }
    assert.equal(page.nextOffset, bytes);
    assert(page.nextOffset > offset);
    offset = page.nextOffset;
  }
  const complete = Buffer.from(fragments.join(""));
  assert.equal(createHash("sha256").update(complete).digest("hex"), payload.reference.sha256);
  const message = JSON.parse(complete);
  assert(!message.error, JSON.stringify(message.error));
  return JSON.parse(message.result.content[0].text);
}

async function executeScript(invoke, payload) {
  let sessionId = payload.sessionId;
  let primaryFailure;
  try {
    let response = await invoke("execute-script", payload);
    let envelope = await completeResponse(invoke, response);
    sessionId = envelope.result?.sessionId ?? sessionId;
    assert(sessionId, JSON.stringify(envelope));
    assert.equal(envelope.result.executionId, payload.executionId);
    while (envelope.result.state === "RUNNING") {
      response = await invoke("wait-script", { executionId: payload.executionId, waitMillis: 1000 });
      envelope = await completeResponse(invoke, response);
      assert.equal(envelope.result.sessionId, sessionId);
      assert.equal(envelope.result.executionId, payload.executionId);
    }
    return { response, envelope, report: envelope.result };
  } catch (failure) {
    primaryFailure = failure;
    throw failure;
  } finally {
    if (!payload.sessionId && sessionId) {
      try {
        const closed = await completeResponse(invoke, await invoke("close-script-session", { sessionId }));
        assert.equal(closed.success, true, JSON.stringify(closed.error));
        assert.equal(closed.result.sessionId, sessionId);
        assert.equal(closed.result.state, "CLOSED", "自建会话须确认进程退出后归还槽位");
      } catch (failure) {
        if (primaryFailure) throw new AggregateError([primaryFailure, failure], "执行与会话关闭均失败");
        throw failure;
      }
    }
  }
}

async function program(parameters) {
  const { envelope, report } = await executeScript(cliEnvelope, scriptPayload(author, parameters));
  assert.equal(envelope.success, true, JSON.stringify(envelope.error));
  assert.equal(report.success, true, JSON.stringify(report.diagnostics));
  assert(report.calls.length > 1, "真实安装验收必须在程序中继续调用正式能力");
  return outputValue(report).object.values;
}
async function authorContract(source, expected) {
  const { envelope: response } = await executeScript(cliEnvelope, scriptPayload(source, {}, "author-contract.olap.kts"));
  if (!expected) {
    assert.equal(response.success, true);
    assert.equal(response.result.success, true);
    assert.equal(response.result.calls.length, 1, "合法构造后应继续调用一次真实来源读取");
    return;
  }
  assert.equal(response.success, false);
  assert.equal(response.result.calls.length, 0, "非法构造不得进入后续业务能力调用");
  const diagnostic = response.result.diagnostics.find(item => item.code === expected.code);
  assert(diagnostic, JSON.stringify(response.result.diagnostics));
  assert.equal(diagnostic.stage, "CONTRACT_INPUT");
  assert.deepEqual(diagnostic.field_path, expected.path);
  assert.equal(diagnostic.expected, expected.expected);
  assert.equal(diagnostic.actual, expected.actual);
  assert.equal(diagnostic.location.source_name, "author-contract.olap.kts");
  assert(diagnostic.location.start_line > 0, "构造拒绝应保留实际脚本位置");
}
function verifySalesResult(reference) {
  const page = call("read-query-result", { resultReference: reference, offset: 0, limit: 10 });
  assert.equal(page.totalRows, 1);
  assert.equal(page.rows.length, 1);
  assert.equal(page.nextOffset ?? null, null);
  assert.deepEqual(page.columns.map(column => column.symbol.name), ["sales_amount", "order_count"]);
  const [amount, count] = page.rows[0].values.map(value => value.complete.scalar);
  // 独立参照来自本试验明确写入的三笔订单100、50、25。
  assert.equal(amount.type, "DECIMAL");
  assert.match(amount.value, /^175(?:[.]0+)?$/);
  assert.deepEqual(count, { type: "INTEGER", value: "3" });
  const metadata = call("read-query-result-metadata", { resultReference: reference, section: "RUN", offset: 0, limit: 20 });
  assert.equal(metadata.content.run.runId, reference.runId);
  const explanation = call("read-query-result-metadata", { resultReference: reference, section: "EXPLANATION",
    explanationSection: "EXECUTION_REQUIREMENTS", offset: 0, limit: 20 }).content.explanation.executionRequirements.value;
  const adopted = explanation.sourceConstraints.find(source => source.schema === "PUBLIC" && source.table === "SALES_ORDERS");
  assert(adopted, "保存及重启回读必须保留实际来源采用");
  const nonNull = adopted.constraints.filter(constraint => constraint.kind === "NOT_NULL");
  assert.equal(nonNull.length, 1);
  assert.deepEqual(nonNull[0].columns, { ORDER_ID: orderColumnVersion });
}
const portServer = createServer();
await new Promise(done => portServer.listen(0, "127.0.0.1", done));
const port = portServer.address().port;
await new Promise((done, fail) => portServer.close(error => error ? fail(error) : done()));
let server;
let log;
async function start(name) {
  log = await open(join(root, name + ".log"), "a");
  server = spawn(cli, ["start", "--home", home, "--port", String(port)], { cwd: root, env: environment, stdio: ["ignore", log.fd, log.fd] });
  server.on("error", error => { throw error; });
  const deadline = Date.now() + 60000;
  while (Date.now() < deadline) {
    assert.equal(server.exitCode, null, await readFile(join(root, name + ".log"), "utf8"));
    try {
      const response = await fetch("http://127.0.0.1:" + port + "/mcp", { method: "POST" });
      if (response.status === 401) return;
    } catch {}
    await new Promise(done => setTimeout(done, 150));
  }
  throw new Error("安装后的服务未就绪；查看实际服务日志");
}
async function stop(requestedSignal = "SIGTERM") {
  if (!server) return;
  const child = server;
  server = undefined;
  child.kill(requestedSignal);
  const timer = setTimeout(() => child.kill("SIGKILL"), 15000);
  const [code, signal] = await once(child, "exit");
  clearTimeout(timer);
  await log.close();
  assert.notEqual(signal, "SIGKILL", "服务没有正常回收");
  if (requestedSignal === "SIGINT") assert.equal(code, 130, "Ctrl-C 未按正常中断退出");
}
try {
  assert.equal(command(["info", "--home", home]).status, 1);
  await start("initial");
  const info = json(["info", "--home", home]);
  assert.equal(info.url, "http://127.0.0.1:" + port + "/mcp");
  assert.equal(info.service.checks["read-connector-capabilities"].status, "AVAILABLE");
  assert.equal(info.service.checks["list-data-sources"].status, "AVAILABLE");
  const tools = json(["tools", "--home", home]);
  assert(tools.tools.some(tool => tool.name === "describe-script-api"));
  assert(tools.tools.some(tool => tool.name === "execute-script"));
  const api = call("describe-script-api", {});
  dslApiVersion = api.dslApiVersion;
  const created = await program({ phase: "create" });
  const businessLineId = created.businessLineId.text;
  assert(businessLineId);
  await stop();

  const config = JSON.parse(await readFile(info.configPath, "utf8"));
  config.connectors = [manifestPath];
  config.dataSources = [{ dataSourceId: "business", connectorRef: manifest.connectorRef, dialectId: "h2",
    identityParameters: { jdbcUrl, username: "sa", driverClassName: "org.h2.Driver" },
    runtimeParameters: {}, credentialEnvironmentVariables: {} }];
  await writeFile(info.configPath, JSON.stringify(config));
  await start("source-reopened");
  const sourceInfo = json(["info", "--home", home]);
  const sourceCheck = sourceInfo.service.checks["list-data-sources"];
  const connectorCheck = sourceInfo.service.checks["read-connector-capabilities"];
  assert.equal(sourceCheck.status, "AVAILABLE");
  assert.equal(sourceCheck.result.items.length, 1);
  assert.equal(sourceCheck.result.items[0].binding.dataSourceId, "business");
  assert.equal(connectorCheck.status, "AVAILABLE");
  assert(connectorCheck.result.connectors.some(item => item.connectorRef.id === "h2"));
  const structure = await program({ phase: "structure", businessLineId });
  const snapshotId = structure.snapshotId.text;
  const sourceIdentityHash = structure.sourceIdentityHash.text;
  assert.match(sourceIdentityHash, /^[0-9a-f]{64}$/);
  await stop("SIGINT");

  await start("structure-reopened");
  const built = await program({ phase: "build", businessLineId, snapshotId });
  const queryReference = built.query.reference.query;
  orderColumnVersion = built.orderColumnVersion.text;
  const resultReference = built.result.reference.result;
  const continuedReference = built.continuedResult.reference.result;
  verifySalesResult(resultReference);
  verifySalesResult(continuedReference);
  const { envelope: failed } = await executeScript(cliEnvelope,
    scriptPayload("Calculation.Scalar(ScalarValueType.BOOLEAN, true)", {}, "invalid.olap.kts"));
  assert.equal(failed.success, false);
  assert(failed.result.diagnostics.some(diagnostic => diagnostic.stage === "COMPILE"), "应给出脚本编译诊断");
  assert.equal(failed.result.calls.length, 0, "构造类型错误前不得调用业务能力");
  const callRules = call("describe-script-api", { type: "com.olap.prototype.calculation.Calculation.Call" }).rules.targetRules.MEASUREMENT;
  assert.deepEqual(callRules.emptyMaps, ["dimensionSlots", "measurementSlots"]);
  const measurementCall = (field) => `
val selected = Calculation.Call("selected", Calculation.Measurement("adopted"), "total",
    ${field === "dimensionSlots" ? 'mapOf("seller" to "seller")' : "emptyMap()"}, emptyMap(),
    ${field === "measurementSlots" ? 'mapOf("amount" to "amount")' : "emptyMap()"}, emptyMap(), emptyMap(),
    Calculation.Condition(DimensionCondition.Unrestricted()))
olap.output(olap.sources.list(EmptyRequest))`;
  for (const field of callRules.emptyMaps) await authorContract(measurementCall(field), {
    code: "MEASUREMENT_BINDING_OVERRIDE", path: [field], expected: "EMPTY_MAP", actual: "NONEMPTY_MAP",
  });
  await authorContract(measurementCall(null));
  const memberRules = call("describe-script-api", { type: "com.olap.prototype.member.MemberResult" }).rules.outputRules.quantity;
  assert.equal(memberRules.valueType, "NUMBER");
  assert.equal(memberRules.unitEquations, "EMPTY_MAP");
  const quantityMember = (equations, valueType = "NUMBER") => `
val quantity = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(),
    MetricValueContract.Integer(64), ${equations ? 'mapOf("ratio" to MetricValueContract.SameUnitRatio())' : "emptyMap()"}, MetricValueContract.Overflow.FAIL)
val result = MemberResult("observation", "observation", MemberShape.RAW_BAG, emptyList(), mapOf(
    "total" to MemberSourceDefinition.ComputedPropertyOutput("合计", "total", ScalarValueType.${valueType}, "total", quantity)),
    emptyMap(), null, emptyMap(), null)
olap.output(olap.sources.list(EmptyRequest))`;
  await authorContract(quantityMember(true), {
    code: "MEMBER_QUANTITY_UNIT_EQUATIONS", path: ["outputs", "total", "valueContract", "unitEquations"], expected: "EMPTY_MAP", actual: "NONEMPTY_MAP",
  });
  await authorContract(quantityMember(false, "TEXT"), {
    code: "MEMBER_QUANTITY_TYPE", path: ["outputs", "total", "valueType"], expected: "NUMBER", actual: "TEXT",
  });
  await authorContract(quantityMember(false));
  await stop();

  await start("analysis-reopened");
  verifySalesResult(resultReference);
  const reopened = await program({ phase: "reopen", definitionId: queryReference.definitionId, definitionVersion: queryReference.definitionVersion,
    querySha256: queryReference.sha256, resultRunId: resultReference.runId, resultSha256: resultReference.sha256 });
  const repeatedReference = reopened.result.reference.result;
  assert.notEqual(repeatedReference.runId, resultReference.runId);
  verifySalesResult(repeatedReference);
  await stop();

  // 同一已安装产品，穿过真实脚本 JVM、Protobuf、MCP 和重启读取边界。
  config.limits.responseBytes = 65536;
  config.limits.pendingOutputBytes = 131072;
  await writeFile(info.configPath, JSON.stringify(config));
  await start("complete-response");
  const token = (await readFile(config.auth.tokenFile, "utf8")).trim();
  let responseSequence = 0;
  async function rpc(operation, payload, id = `delivery-${++responseSequence}`) {
    const response = await fetch(info.url, { method: "POST", headers: {
      Authorization: `Bearer ${token}`, "Content-Type": "application/json",
      Accept: "application/json, text/event-stream", "MCP-Protocol-Version": "2025-11-25",
    }, body: JSON.stringify({ jsonrpc: "2.0", id, method: "tools/call", params: { name: operation, arguments: { payload } } }) });
    assert.equal(response.status, 200);
    const bytes = Buffer.from(await response.arrayBuffer());
    assert(bytes.length <= config.limits.responseBytes, "最终 JSON-RPC 消息应在内联范围内");
    const message = JSON.parse(bytes);
    assert.equal(message.id, id);
    assert(!message.error, JSON.stringify(message.error));
    return JSON.parse(message.result.content[0].text);
  }
  const catalog = Array.from({ length: 14 }, (_, table) => ({
    name: `table_${table}`, columns: Array.from({ length: table === 0 ? 114 : 113 }, (_, column) =>
      Object.fromEntries(Array.from({ length: 11 }, (_, property) => [`property_${property}`, property === 10 ? null : `${table}/${column}/${property}`]))),
  }));
  const ordinary = await executeScript(rpc, scriptPayload(`
val catalog = parameters["declaredStructure"] as ScriptValue.ArrayValue
val columns = catalog.values.sumOf { ((it as ScriptValue.ObjectValue).values.getValue("columns") as ScriptValue.ArrayValue).values.size }
val first = olap.sources.list(EmptyRequest)
val second = olap.sources.list(EmptyRequest)
check(first.items == second.items)
olap.output(ScriptValue.DecimalValue(columns.toString()))
first`, { declaredStructure: catalog }));
  assert.equal(ordinary.envelope.success, true);
  assert.equal(outputValue(ordinary.report).decimal, "1583");
  assert(ordinary.report.calls.every(item => item.success));
  const escapedRun = await executeScript(rpc, scriptPayload('olap.output(ScriptValue.Text("\\u0001".repeat(14000)))'));
  const escaped = escapedRun.response;
  assert.equal(escaped.success, true);
  assert.equal(escaped.result.delivery, "SAVED", "最终 JSON/MCP 转义后的大小应触发保存，而非只看 Protobuf");
  const escapedFile = JSON.parse(await readFile(escaped.result.location, "utf8"));
  assert.equal(outputValue(JSON.parse(escapedFile.result.content[0].text).result).text, "\u0001".repeat(14000));
  const expectedText = "标签🌟".repeat(20000);
  const largeRun = await executeScript(rpc, scriptPayload('olap.output(ScriptValue.Text("标签🌟".repeat(20000)))'));
  const large = largeRun.response;
  assert.equal(large.success, true);
  assert.equal(large.result.delivery, "SAVED");
  const saved = large.result.read.payload.reference;
  // 接近消息容量的合法请求 ID 仍可缩页，不能把读取页再保存成下一份档案。
  const longPage = await rpc("read-response", { reference: saved, offset: 0 }, "r".repeat(64000));
  assert.equal(longPage.success, true);
  assert.equal(typeof longPage.result.content, "string");
  await stop();
  await start("response-reopened");
  const fragments = [];
  let offset = 0;
  for (;;) {
    const response = await rpc("read-response", { reference: saved, offset });
    assert.equal(response.success, true);
    const page = response.result;
    assert.equal(page.offset, offset);
    fragments.push(page.content);
    if (page.nextOffset === null) break;
    assert(page.nextOffset > offset);
    offset = page.nextOffset;
  }
  const complete = Buffer.from(fragments.join(""));
  assert.equal(createHash("sha256").update(complete).digest("hex"), saved.sha256);
  const original = JSON.parse(complete);
  assert.equal(outputValue(JSON.parse(original.result.content[0].text).result).text, expectedText);
  assert.deepEqual(complete, await readFile(large.result.location));
  await stop();

  // 正式写已完成后本地作者失败，完整档案保留失败状态及可正式读回的效果坐标。
  config.limits.responseBytes = 1536;
  await writeFile(info.configPath, JSON.stringify(config));
  await start("partial-response");
  const partialCode = `partial_${Date.now()}`;
  const partialRun = await executeScript(rpc, scriptPayload(`
val created = olap.governance.createBusinessLine(BusinessLineCreateRequest(parameters.text("code"), "部分成功", olap.caller.subject, "完整失败回执", olap.caller.subject)).businessLine
olap.governance.activate(GovernanceVersionActivationRequest(GovernanceActivationObjectType.BUSINESS_LINE, created.id, 1, olap.caller.subject, "明确激活"))
olap.output(ScriptValue.Text(created.id))
error("author stopped after the committed write")`, { code: partialCode }));
  const partial = partialRun.response;
  assert.equal(partial.success, false);
  assert.equal(partial.result.delivery, "SAVED");
  const partialOriginal = JSON.parse(JSON.parse(await readFile(partial.result.location, "utf8")).result.content[0].text);
  assert.equal(partialOriginal.success, false);
  const effects = partialOriginal.result.calls.filter(item => item.success).flatMap(item => item.effects);
  assert.equal(effects.length, 2);
  assert.equal(effects[0].readOperation, "read-asset-version");
  assert.equal(effects[1].readOperation, "read-asset-version");
  assert.deepEqual(effects[1].assetVersion, effects[0].assetVersion);
  assert.equal(outputValue(partialOriginal.result).text, effects[0].assetVersion.id,
    "作者异常前已显式输出的精确身份应与真实写效果一致并保留");
  const recovered = await executeScript(rpc, scriptPayload(`
val id = parameters.text("id")
val version = parameters.text("version").toLong()
val snapshot = olap.semantics.asset(GovernanceReadRequests.AssetVersionRequest(parameters.text("assetType"), id, version)) as AssetSnapshot.BusinessLine
val item = snapshot.value
check(item.id == id && item.currentVersion == version && item.status == "ACTIVE")
olap.output(ScriptValue.Text(item.id))`, effects[1].assetVersion));
  assert.equal(recovered.envelope.success, true);
  assert.deepEqual(recovered.report.calls.map(item => item.operationId), ["read-asset-version"],
    "恢复只消费真实效果的精确引用，不重放已经完成的创建或激活");
  assert(recovered.report.calls.every(item => item.success && item.effects.length === 0));
  assert.equal(outputValue(recovered.report).text, effects[0].assetVersion.id);
  await stop();
  assert(config.auth.grants[0].operations.includes("execute-query"));
  config.auth.grants[0].operations = config.auth.grants[0].operations.filter(operation => operation !== "execute-query");
  await writeFile(info.configPath, JSON.stringify(config));
  await start("response-authority-changed");
  const refused = await rpc("read-response", { reference: saved, offset: 0 });
  assert.equal(refused.success, false);
  assert.equal(refused.error.code, "SAVED_RESPONSE_AUTHORIZATION_CHANGED");
  await stop();
  const report = {
    version: command(["--version"]).stdout.trim(), target, mode,
    package: packageMetadata.name + "@" + packageMetadata.version, dslApiVersion,
    persistentRead: true, actualSourceRead: true, declaredStructure: true, structurePersistentRead: true,
    analysisPersistentRead: true, contextRevision: { scenarioId: built.scenarioId.text, scenarioVersion: 2, releaseId: "installed-sales", releaseVersion: "2" },
    sourceIdentityHash, queryReference, runIds: [resultReference.runId, continuedReference.runId, repeatedReference.runId],
    expected: { sales_amount: "175.00", order_count: "3" }, systemToolsPath: environment.PATH,
    completeResponseDelivery: { parameterColumns: 1583, escapedJSONSaved: true, internalFileFrame: true,
      restartRead: true, completeSHA256: saved.sha256, partialWriteRecovered: true, authorityChangeRefused: true },
  };
  await writeFile(join(root, "report.json"), JSON.stringify(report, null, 2) + "\n");
  console.log(JSON.stringify(report));
} catch (failure) {
  await writeFile(join(root, "report.json"), JSON.stringify({ target, mode, status: "FAILED", message: failure.message }, null, 2) + "\n");
  throw failure;
} finally {
  await stop();
}
