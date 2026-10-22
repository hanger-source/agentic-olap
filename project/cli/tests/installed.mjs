// 从实际 npm tarball 安装并操作服务；运行环境不提供 Python、Java 或源码路径。
import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { once } from "node:events";
import { appendFileSync } from "node:fs";
import { mkdir, readFile, readdir, writeFile, open } from "node:fs/promises";
import { createServer } from "node:net";
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
const call = (operation, payload) => {
  const result = json(["call", operation, "--payload", JSON.stringify(payload), "--home", home]);
  assert.equal(result.isError ?? false, false);
  const response = JSON.parse(result.content[0].text);
  appendFileSync(join(root, "mcp.log"), JSON.stringify({ operation, payload, response }) + "\n");
  assert.equal(response.success, true, JSON.stringify(response.error));
  return response.result;
};
let dslApiVersion;
function program(parameters) {
  const report = call("execute-script", { source: author, sourceName: "installed.olap.kts", dslApiVersion, parameters });
  assert.equal(report.success, true, JSON.stringify(report.diagnostics));
  assert(report.calls.length > 1, "真实安装验收必须在程序中继续调用正式能力");
  return report.value.object.values;
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
  const created = program({ phase: "create" });
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
  const structure = program({ phase: "structure", businessLineId });
  const snapshotId = structure.snapshotId.text;
  const sourceIdentityHash = structure.sourceIdentityHash.text;
  assert.match(sourceIdentityHash, /^[0-9a-f]{64}$/);
  await stop("SIGINT");

  await start("structure-reopened");
  const built = program({ phase: "build", businessLineId, snapshotId });
  const queryReference = built.query.reference.query;
  const resultReference = built.result.reference.result;
  const continuedReference = built.continuedResult.reference.result;
  verifySalesResult(resultReference);
  verifySalesResult(continuedReference);
  const invalid = command(["call", "execute-script", "--payload", JSON.stringify({
    source: "Calculation.Scalar(ScalarValueType.BOOLEAN, true)", sourceName: "invalid.olap.kts", dslApiVersion, parameters: {},
  }), "--home", home]);
  assert.equal(invalid.status, 1, "类型错误不能进入正式能力执行");
  const failed = JSON.parse(JSON.parse(invalid.stdout).content[0].text);
  assert.equal(failed.success, false);
  assert(failed.result.diagnostics.some(diagnostic => diagnostic.stage === "COMPILE"), "应给出脚本编译诊断");
  assert.equal(failed.result.calls.length, 0, "构造类型错误前不得调用业务能力");
  await stop();

  await start("analysis-reopened");
  verifySalesResult(resultReference);
  const reopened = program({ phase: "reopen", definitionId: queryReference.definitionId, definitionVersion: queryReference.definitionVersion,
    querySha256: queryReference.sha256, resultRunId: resultReference.runId, resultSha256: resultReference.sha256 });
  const repeatedReference = reopened.result.reference.result;
  assert.notEqual(repeatedReference.runId, resultReference.runId);
  verifySalesResult(repeatedReference);
  await stop();
  const report = {
    version: command(["--version"]).stdout.trim(), target, mode,
    package: packageMetadata.name + "@" + packageMetadata.version, dslApiVersion,
    persistentRead: true, actualSourceRead: true, declaredStructure: true, structurePersistentRead: true,
    analysisPersistentRead: true, contextRevision: { scenarioId: built.scenarioId.text, scenarioVersion: 2, releaseId: "installed-sales", releaseVersion: "2" },
    sourceIdentityHash, queryReference, runIds: [resultReference.runId, continuedReference.runId, repeatedReference.runId],
    expected: { sales_amount: "175.00", order_count: "3" }, systemToolsPath: environment.PATH,
  };
  await writeFile(join(root, "report.json"), JSON.stringify(report, null, 2) + "\n");
  console.log(JSON.stringify(report));
} catch (failure) {
  await writeFile(join(root, "report.json"), JSON.stringify({ target, mode, status: "FAILED", message: failure.message }, null, 2) + "\n");
  throw failure;
} finally {
  await stop();
}
