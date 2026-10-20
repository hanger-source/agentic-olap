// 从实际 npm tarball 安装并操作服务；运行环境不提供 Python、Java 或源码路径。
import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { once } from "node:events";
import { mkdir, readFile, readdir, writeFile, open } from "node:fs/promises";
import { createServer } from "node:net";
import { join, resolve } from "node:path";

const [tarballArg, outputArg, mode = "tarball"] = process.argv.slice(2);
assert(tarballArg && outputArg, "需要 npm tarball 目录与尚不存在的消费目录");
assert(["tarball", "registry"].includes(mode), "第三个参数只能是 registry 或 tarball");
const tarballs = resolve(tarballArg);
const root = resolve(outputArg);
await mkdir(root);
await mkdir(join(root, "tmp"));
const target = `${process.platform}-${process.arch}`;
const files = await readdir(tarballs);
const main = files.filter((file) => /^hanger-source-agentic-olap-\d.*\.tgz$/.test(file));
const native = files.filter((file) => file.startsWith(`hanger-source-agentic-olap-runtime-${target}-`));
assert.equal(main.length, 1);
assert.equal(native.length, 1);
await writeFile(join(root, "package.json"), JSON.stringify({ private: true }));
const version = /^hanger-source-agentic-olap-(.+)\.tgz$/.exec(main[0])[1];
const selection = mode === "registry"
  ? ["--include=optional", "--cache", join(root, "npm-cache"), `@hanger-source/agentic-olap@${version}`]
  : ["--omit=optional", join(tarballs, main[0]), join(tarballs, native[0])];
const installed = spawnSync("npm", ["install", "--ignore-scripts", "--no-audit", "--no-fund", "--registry=https://registry.npmjs.org", ...selection], { cwd: root, encoding: "utf8" });
assert.equal(installed.status, 0, installed.stderr);
const cli = join(root, "node_modules/@hanger-source/agentic-olap/bin/agentic-olap.mjs");
const nativeRoot = join(root, "node_modules/@hanger-source", `agentic-olap-runtime-${target}`);
assert.equal(JSON.parse(await readFile(join(root, "node_modules/@hanger-source/agentic-olap/package.json"), "utf8")).version, version);
const nativeMetadata = JSON.parse(await readFile(join(nativeRoot, "package.json"), "utf8"));
assert.equal(nativeMetadata.version, version);
const connectors = files.filter((file) => /^agentic-olap-connectors-\d.*\.tgz$/.test(file));
assert.equal(connectors.length, 1, "需要同次发行的独立连接器组");
const pluginRoot = join(root, "plugins");
await mkdir(pluginRoot);
const unpacked = spawnSync("tar", ["-xzf", join(tarballs, connectors[0]), "-C", pluginRoot], { encoding: "utf8" });
assert.equal(unpacked.status, 0, unpacked.stderr);
const driver = (await readdir(join(pluginRoot, "h2"))).find((file) => /^h2-.*\.jar$/.test(file));
assert(driver);
const jdbcUrl = `jdbc:h2:${join(root, "business")}`;
await writeFile(join(root, "source.sql"), "CREATE TABLE SALES_ORDERS (ORDER_ID BIGINT PRIMARY KEY, AMOUNT DECIMAL(18,2));\nINSERT INTO SALES_ORDERS VALUES (1,100.00),(2,50.00),(3,25.00);\n");
const prepared = spawnSync(join(nativeRoot, "jre/bin/java"), ["-cp", join(pluginRoot, "h2", driver), "org.h2.tools.RunScript", "-url", jdbcUrl, "-user", "sa", "-script", join(root, "source.sql")], { encoding: "utf8" });
assert.equal(prepared.status, 0, prepared.stderr);
const home = join(root, "persistent home");
const environment = { PATH: join(root, "no-system-tools"), HOME: root, TMPDIR: join(root, "tmp"), LANG: "en_US.UTF-8" };
const command = (args) => spawnSync(process.execPath, [cli, ...args], { cwd: root, env: environment, encoding: "utf8", timeout: 60000 });
const json = (args) => {
  const result = command(args);
  assert.equal(result.status, 0, result.stderr + result.stdout);
  return JSON.parse(result.stdout);
};
const call = (operation, payload) => {
  const result = json(["call", operation, "--payload", JSON.stringify(payload), "--home", home]);
  assert.equal(result.isError ?? false, false);
  const response = JSON.parse(result.content[0].text);
  assert.equal(response.success, true, JSON.stringify(response.error));
  return response.result;
};
const portServer = createServer();
await new Promise((done) => portServer.listen(0, "127.0.0.1", done));
const port = portServer.address().port;
await new Promise((done, fail) => portServer.close((error) => error ? fail(error) : done()));
let server;
let log;
async function start(name) {
  log = await open(join(root, `${name}.log`), "a");
  server = spawn(process.execPath, [cli, "start", "--home", home, "--port", String(port)], { cwd: root, env: environment, stdio: ["ignore", log.fd, log.fd] });
  server.on("error", (error) => { throw error; });
  const deadline = Date.now() + 60000;
  while (Date.now() < deadline) {
    assert.equal(server.exitCode, null, await readFile(join(root, `${name}.log`), "utf8"));
    try {
      const response = await fetch(`http://127.0.0.1:${port}/mcp`, { method: "POST" });
      if (response.status === 401) return;
    } catch {}
    await new Promise((done) => setTimeout(done, 150));
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
  assert.equal(info.url, `http://127.0.0.1:${port}/mcp`);
  const tools = json(["tools", "--home", home]);
  assert(tools.tools.some((tool) => tool.name === "write-governance"));
  assert(tools.tools.some((tool) => tool.name === "execute-query"));
  assert.deepEqual(call("list-data-sources", {}), []);
  const asset = call("write-governance", { operation: "create-business-line", payload: { code: "installed_product", displayName: "安装后的实际治理资产", owner: "local", description: "npm包真实创建与重启读取", createdBy: "local" } });
  await stop();
  const config = JSON.parse(await readFile(info.configPath, "utf8"));
  const manifestPath = join(pluginRoot, "h2/connector.json");
  const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
  config.connectors = [manifestPath];
  config.dataSources = [{ dataSourceId: "business", connectorRef: manifest.connectorRef, dialectId: "h2", jdbcUrl, username: "sa", passwordEnvironmentVariable: null, driverClassName: "org.h2.Driver" }];
  await writeFile(info.configPath, JSON.stringify(config));
  await start("reopened");
  const discovered = call("inspect-warehouse", { operation: "discover", payload: { dataSourceId: "business", schemaPattern: "PUBLIC", limit: 20 } });
  assert(JSON.stringify(discovered.tables).includes("SALES_ORDERS"), "安装包没有从实际业务库发现表");
  const readOperations = call("discover-governance-reads", {});
  assert(readOperations);
  const listing = call("read-governance", { operation: "list-business-lines", payload: { offset: 0, limit: 20 } });
  assert(JSON.stringify(listing).includes("installed_product"), "重启后没有读到实际提交的治理资产");
  await stop("SIGINT");
  const report = { version: command(["--version"]).stdout.trim(), target, mode, nativePackage: mode === "registry" ? `${nativeMetadata.name}@${nativeMetadata.version}` : native[0], tools: tools.tools.length, assetCreated: Boolean(asset), persistentRead: true, actualSourceRead: true, systemToolsPath: environment.PATH };
  await writeFile(join(root, "report.json"), JSON.stringify(report, null, 2) + "\n");
  console.log(JSON.stringify(report));
} finally {
  await stop();
}
