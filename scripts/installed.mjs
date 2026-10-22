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
const installed = spawnSync("npm", ["install", "--no-audit", "--no-fund", "--registry=https://registry.npmjs.org", ...selection], { cwd: root, encoding: "utf8", env: { ...process.env, AGENTIC_OLAP_CACHE: process.env.AGENTIC_OLAP_CACHE ?? join(root, "dependency-cache") } });
assert.equal(installed.status, 0, installed.stderr);
const cli = join(root, "node_modules/@hanger-source/agentic-olap/bin/agentic-olap.mjs");
const nativeRoot = join(root, "node_modules/@hanger-source", `agentic-olap-runtime-${target}`);
assert.equal(JSON.parse(await readFile(join(root, "node_modules/@hanger-source/agentic-olap/package.json"), "utf8")).version, version);
const nativeMetadata = JSON.parse(await readFile(join(nativeRoot, "package.json"), "utf8"));
assert.equal(nativeMetadata.version, version);
const home = join(root, "persistent home");
const environment = { PATH: join(root, "no-system-tools"), HOME: root, TMPDIR: join(root, "tmp"), LANG: "en_US.UTF-8", HTTP_PROXY: "http://127.0.0.1:9", HTTPS_PROXY: "http://127.0.0.1:9", NO_PROXY: "127.0.0.1,localhost" };
const command = (args) => spawnSync(process.execPath, [cli, ...args], { cwd: root, env: environment, encoding: "utf8", timeout: 60000 });
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
const prepared = spawnSync(join(nativeRoot, "jre/bin/java"), ["-cp", join(h2Directory, drivers[0].file), "org.h2.tools.RunScript", "-url", jdbcUrl, "-user", "sa", "-script", join(root, "source.sql")], { encoding: "utf8" });
assert.equal(prepared.status, 0, prepared.stderr);
const call = (operation, payload) => {
  const result = json(["call", operation, "--payload", JSON.stringify(payload), "--home", home]);
  assert.equal(result.isError ?? false, false);
  const response = JSON.parse(result.content[0].text);
  appendFileSync(join(root, "mcp.log"), JSON.stringify({ operation, payload, response }) + "\n");
  assert.equal(response.success, true, JSON.stringify(response.error));
  return response.result;
};
const govern = (operation, payload) => {
  const receipt = call("write-governance", { operation, payload });
  assert.equal(receipt.formatVersion, "governance-write-receipt/v1");
  assert.equal(receipt.operation, operation);
  return receipt.result;
};
const read = (operation, payload) => call("read-governance", { operation, payload });
const readAll = (operation, payload) => {
  const items = [];
  let offset = 0;
  while (offset !== null) {
    const page = read(operation, { ...payload, offset, limit: 200 });
    items.push(...page.items);
    offset = page.nextOffset;
  }
  return items;
};
const activate = (objectType, objectId, objectVersion = 1) => govern("activate", {
  objectType, objectId, objectVersion, activatedBy: "local", reason: "审核安装后真实订单分析定义",
});

function verifySalesResult(reference) {
  const page = call("read-query-result", { resultReference: reference, offset: 0, limit: 10 });
  assert.equal(page.totalRows, 1);
  assert.equal(page.rows.length, 1);
  assert.equal(page.columns.length, 2);
  assert.equal(page.nextOffset, null);
  const names = page.columns.map((column, index) => {
    const name = call("read-query-result-metadata", {
      resultReference: reference, path: ["result", "columns", index, "symbol", "name"], offset: 0, limit: 100,
    });
    assert.equal(name.kind, "TEXT");
    assert.equal(name.nextOffset, null);
    return name.text;
  });
  assert.deepEqual(names, ["sales_amount", "order_count"]);
  const [amount, count] = page.rows[0];
  assert.equal(amount.type, "DECIMAL");
  // 预期来自上方明确写入的三笔订单100、50、25，不由发布定义或执行结果推导。
  assert.match(amount.value, /^175(?:\.0+)?$/);
  assert.deepEqual(count, { type: "INTEGER", value: "3" });
}
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
  const asset = govern("create-business-line", { code: "installed_product", displayName: "安装后的实际治理资产", owner: "local", description: "npm包真实创建与重启读取", createdBy: "local" });
  await stop();
  const config = JSON.parse(await readFile(info.configPath, "utf8"));
  config.connectors = [manifestPath];
  config.dataSources = [{ dataSourceId: "business", connectorRef: manifest.connectorRef, dialectId: "h2", identityParameters: { jdbcUrl, username: "sa", driverClassName: "org.h2.Driver" }, runtimeParameters: {}, credentialEnvironmentVariables: {} }];
  await writeFile(info.configPath, JSON.stringify(config));
  await start("reopened");
  const deployed = call("list-data-sources", {}).filter((source) => source.dataSourceId === "business");
  assert.equal(deployed.length, 1, "本试验只部署一份business精确绑定，不代选多个绑定");
  const sourceIdentityHash = deployed[0].sourceIdentityHash;
  assert.match(sourceIdentityHash, /^[0-9a-f]{64}$/);
  const structure = govern("register-source-structure", {
    dataSourceId: "business", sourceIdentityHash, catalog: null, schemaName: "PUBLIC", authority: "installed-product-configuration", revision: "sales-schema-v1", requestedBy: "local",
    tables: [{ table: { ref: { catalog: null, schema: "PUBLIC", table: "SALES_ORDERS" }, tableType: "TABLE", remarks: null },
      columns: [
        { name: "ORDER_ID", jdbcType: -5, typeName: "BIGINT", ordinalPosition: 1, nullable: false, precision: 19, scale: 0 },
        { name: "AMOUNT", jdbcType: 3, typeName: "DECIMAL", ordinalPosition: 2, nullable: true, precision: 18, scale: 2 }
      ], constraints: { primaryKeyColumns: ["ORDER_ID"], uniqueKeys: [], importedKeys: [], temporalKeys: [] } }]
  });
  const structureSnapshot = read("read-source-discovery", { snapshotId: structure.snapshotId });
  assert.equal(structureSnapshot.structureProvenance.kind, "DECLARED");
  assert.equal(structureSnapshot.driverName, null);
  const discovered = call("inspect-warehouse", { operation: "discover", payload: { dataSourceId: "business", sourceIdentityHash, schemaPattern: "PUBLIC", limit: 20 } });
  assert(JSON.stringify(discovered.tables).includes("SALES_ORDERS"), "安装包没有从实际业务库发现表");
  const readOperations = call("discover-governance-reads", {});
  assert(readOperations);
  const listing = call("read-governance", { operation: "list-business-lines", payload: { offset: 0, limit: 20 } });
  assert(JSON.stringify(listing).includes("installed_product"), "重启后没有读到实际提交的治理资产");
  await stop("SIGINT");
  await start("structure-reopened");
  const structures = readAll("list-source-discoveries", { dataSourceId: "business", catalog: "", schemaName: "PUBLIC" });
  assert(structures.some((snapshot) => snapshot.id === structure.snapshotId), "重启后未读到显式登记的结构");
  const observedTables = readAll("read-source-discovery-tables", { snapshotId: structure.snapshotId });
  assert.equal(observedTables.length, 1);
  const observed = observedTables[0];
  const columns = readAll("read-source-discovery-columns", { discoveredTableId: observed.id });
  assert.equal(columns.length, 2);
  for (const column of columns) {
    assert.equal(column.autoIncrement, null, "缺少声明时应保留未知的自动增长状态");
    assert.equal(column.generated, null, "缺少声明时应保留未知的生成列状态");
  }
  const physical = govern("create-physical-table", {
    code: "sales_orders", tableName: "SALES_ORDERS", displayName: "销售订单", description: "安装后真实H2订单",
    dataDomain: "sales", owner: "local", sensitivityLevel: "PUBLIC", sourceSystem: "installed-sales",
    dataSource: "business", dialectId: "h2", catalog: observed.catalogName, schemaName: observed.schemaName,
    schemaHash: observed.schemaHash, sourceConstraints: { discoveredTableId: observed.id, sourceCollectionIds: [] }, createdBy: "local",
    columns: columns.map((column) => ({
      columnName: column.columnName, displayName: column.columnName, columnType: column.typeName,
      sensitivityLevel: "PUBLIC", nullable: column.nullable, description: "订单原始字段",
      jdbcColumnType: { jdbcType: column.jdbcType, typeName: column.typeName, precision: column.precision, scale: column.scale, nullable: column.nullable },
    })),
  });
  activate("PHYSICAL_TABLE", physical.id);
  const physicalColumns = readAll("read-physical-columns", { id: physical.id, version: physical.version });
  const logical = govern("create-logical-table", {
    physicalTableId: physical.id, physicalTableVersion: physical.version, code: "sales_orders", displayName: "销售订单",
    tableRole: "FACT", description: "一条记录是一笔订单", owner: "local", createdBy: "local",
    logicUnits: physicalColumns.map((column) => ({
      code: column.columnName, displayName: column.columnName, sourceKind: "COLUMN", expressionJson: null,
      logicType: "NUMBER", valueShape: null, isTime: false, isPrimaryKey: column.columnName === "ORDER_ID",
      isForeignKey: false, filterable: false, aggregatable: true,
      sourceRefs: [{ physicalColumnId: column.id, physicalColumnVersion: 1, refRole: "DIRECT_SOURCE" }],
    })),
  });
  activate("LOGICAL_TABLE", logical.id);
  const unitRefs = read("read-logical-table-units", { id: logical.id, version: logical.version });
  const units = Object.fromEntries(unitRefs.map((ref) => {
    const unit = read("read-asset-version", { assetType: "LOGIC_UNIT", id: ref.logicUnitId, version: ref.logicUnitVersion });
    return [unit.code, unit];
  }));
  const topology = govern("create-topology", {
    code: "orders", displayName: "销售订单事实", topologyImplementationNamespaceCode: "installed.sales",
    topologyImplementationNamespaceDisplayName: "安装验收销售事实", backboneLogicalTableId: logical.id,
    backboneLogicalTableVersion: 1, owner: "local", description: "单表订单事实", createdBy: "local",
  });
  activate("TOPOLOGY", topology.id);
  const logicRef = (unit) => ({ logicUnitId: unit.id, logicUnitVersion: 1 });
  const event = govern("create-event", {
    topologyId: topology.id, topologyVersion: 1, code: "registered", displayName: "已登记订单", owner: "local",
    eventTimeLogicUnitId: null, eventTimeLogicUnitVersion: null,
    identity: { recordKey: [logicRef(units.ORDER_ID)], entityRoles: {} }, roles: {}, filterableLogicUnitRefs: [],
    measureLogicUnitRefs: [logicRef(units.ORDER_ID), logicRef(units.AMOUNT)], description: "按原订单记录汇总，不声明未知的完成状态", createdBy: "local",
  });
  activate("EVENT", event.id);
  const meanings = {
    sales_amount: { definition: "所有已登记订单的金额之和", unit: "CNY", grain: "订单", limitations: ["不包含退款及状态限定"] },
    order_count: { definition: "已登记订单记录数", unit: "单", grain: "订单", limitations: ["不作客户去重"] },
  };
  const metrics = [];
  for (const [code, unit, aggregation, identity, result] of [
    ["sales_amount", units.AMOUNT, "SUM", { authority: "iso4217", id: "CNY", version: 1 }, { kind: "DECIMAL", precision: 38, scale: 2, rounding: "NONE" }],
    ["order_count", units.ORDER_ID, "COUNT", { authority: "olap", id: "dimensionless", version: 1 }, { kind: "INTEGER", bits: 64 }],
  ]) {
    const metric = govern("create-metric", {
      sourceEventId: event.id, sourceEventVersion: 1, code, displayName: code, owner: "local",
      inputExpressionJson: JSON.stringify({ kind: "LOGIC_UNIT", ...logicRef(unit) }), aggregation, distinct: false,
      metricKind: "BASE", mergePolicy: "ADDITIVE", privatePredicateJson: null, roles: {}, description: meanings[code].definition,
      businessMeaning: meanings[code], snapshotRule: null,
      valueContract: { unit: { kind: "FIXED", identity }, arithmetic: { kind: "EXACT_DECIMAL" }, result, unitEquations: {}, overflow: "FAIL" },
      aggregationRounding: "NONE", createdBy: "local",
    });
    activate("METRIC", metric.id);
    metrics.push({ code, metric });
  }
  activate("BUSINESS_LINE", asset.id);
  const subject = govern("create-subject-area", {
    businessLineId: asset.id, businessLineVersion: 1, code: "sales", displayName: "销售经营",
    owner: "local", description: "安装后订单分析", createdBy: "local",
  });
  const adoptions = metrics.map(({ code, metric }) => govern("create-subject-area-metric-ref", {
    subjectAreaId: subject.id, subjectAreaVersion: 1, code, displayName: code, businessMeaning: meanings[code],
    metricId: metric.id, metricVersion: 1,
    bindings: { dimensions: {}, members: {}, measurements: {}, groupingCandidates: {}, relations: {} }, fixedArguments: {}, selectedBy: "local",
  }));
  const scenario = govern("create-scenario", {
    subjectAreaId: subject.id, subjectAreaVersion: 1, code: "orders", displayName: "订单经营分析",
    owner: "local", description: "公开销售额和订单数", createdBy: "local",
  });
  adoptions.forEach((adoption, index) => govern("create-scenario-metric-ref", {
    scenarioId: scenario.id, scenarioVersion: 1, adoptionId: adoption.id, adoptionVersion: 1, requiredByDefault: true, sortOrder: index + 1,
  }));
  activate("SUBJECT_AREA", subject.id);
  govern("build-release", {
    releaseId: "installed-sales", releaseVersion: "1", releaseDisplayName: "安装后销售口径",
    subjectAreaId: subject.id, subjectAreaVersion: 1, createdBy: "local",
  });
  const coordinate = { scenarioId: scenario.id, scenarioVersion: 1, releaseId: "installed-sales", releaseVersion: "1" };
  const publication = call("read-publication", coordinate);
  const calls = ["sales_amount", "order_count"].map((code) => {
    const candidates = publication.capability.metricCandidates.filter((candidate) => candidate.code === code);
    assert.equal(candidates.length, 1);
    return { callId: code, instance: candidates[0].instance, outputAlias: code, members: {}, relations: {}, arguments: {}, sourceCondition: { kind: "TRUE" } };
  });
  const intent = { formatVersion: "olap-query-intent/v6", ...coordinate, computation: {
    root: { kind: "MEASURES", calls, grouping: [], groupingLevels: null, sourceTimeRange: null, sourceClocks: {},
      sourceCondition: { kind: "TRUE" }, outputCondition: { kind: "TRUE" }, sourceEventConditions: [],
      population: { mode: "CALLS", callIds: calls.map((invocation) => invocation.callId) } }, inputs: {}, stages: [],
  } };
  const invalidIntent = structuredClone(intent);
  invalidIntent.computation.root.calls[0].arguments.flag = { kind: "SCALAR", scalarType: "BOOLEAN", value: true };
  const invalidCall = command(["call", "validate-query", "--payload", JSON.stringify({ intent: invalidIntent }), "--home", home]);
  assert.equal(invalidCall.status, 1);
  const invalid = JSON.parse(JSON.parse(invalidCall.stdout).content[0].text);
  assert.equal(invalid.success, false);
  assert.equal(invalid.error.code, "INVALID_REQUEST");
  assert.equal(invalid.error.stage, "DECODE_INPUT");
  assert.deepEqual(invalid.error.details.fieldPath, ["computation", "root", "calls", 0, "arguments", "flag", "value"]);
  assert.equal(invalid.error.details.expectedType, "string");
  assert.equal(invalid.error.details.actualType, "boolean");
  const missingCondition = structuredClone(intent);
  delete missingCondition.computation.root.calls[0].sourceCondition;
  const missingCall = command(["call", "validate-query", "--payload", JSON.stringify({ intent: missingCondition }), "--home", home]);
  assert.equal(missingCall.status, 1);
  const missing = JSON.parse(JSON.parse(missingCall.stdout).content[0].text);
  assert.equal(missing.error.code, "INVALID_REQUEST");
  assert.deepEqual(missing.error.details.fieldPath, ["computation", "root", "calls", 0, "sourceCondition"]);
  assert.equal(missing.error.details.actualType, "missing");
  const wrongRoot = structuredClone(intent);
  wrongRoot.computation = { root: { kind: "INPUT", inputId: "basis" },
    inputs: { basis: { mode: "RELATION", computation: structuredClone(intent.computation) } }, stages: [] };
  const rootCall = command(["call", "validate-query", "--payload", JSON.stringify({ intent: wrongRoot }), "--home", home]);
  assert.equal(rootCall.status, 1);
  const rootFailure = JSON.parse(JSON.parse(rootCall.stdout).content[0].text);
  assert.equal(rootFailure.error.code, "ROOT_INPUT_REQUIRED");
  assert.deepEqual(rootFailure.error.details.fieldPath, ["computation", "inputs", "basis", "mode"]);
  assert.equal(rootFailure.error.details.expectedKind, "ROOT");
  assert.equal(rootFailure.error.details.actualKind, "RELATION");
  const invalidCalculation = { code: "invalid_dimension_algorithm", displayName: "维度槽合同检查", owner: "local", createdBy: "local",
    description: null, businessMeaning: meanings.order_count, definition: { kind: "CALCULATION",
      calculation: { dimensionSlots: [], memberSlots: [], measurementSlots: [], parameters: {},
        relationSlots: [{ name: "completed", outputs: [{ name: "seller", shape: { kind: "DIMENSION",
          dimension: { id: "unadopted-dimension", version: "1", levelCode: "seller" } } }], keys: ["seller"] }],
        computation: { root: { kind: "RELATION", slot: "completed" }, inputs: {}, stages: [] } },
      measureOutput: "quantity", keyOutputs: [], valueContract: { unit: { kind: "FIXED", identity: { authority: "olap", id: "dimensionless", version: 1 } },
        arithmetic: { kind: "EXACT_DECIMAL" }, result: { kind: "INTEGER", bits: 64 }, unitEquations: {}, overflow: "FAIL" } } };
  const calculationCall = command(["call", "write-governance", "--payload", JSON.stringify({ operation: "create-metric-calculation", payload: invalidCalculation }), "--home", home]);
  assert.equal(calculationCall.status, 1);
  const calculationFailure = JSON.parse(JSON.parse(calculationCall.stdout).content[0].text);
  assert.equal(calculationFailure.error.code, "CALCULATION_DIMENSION_SLOT_REQUIRED");
  assert.deepEqual(calculationFailure.error.details.fieldPath, ["definition", "calculation", "relationSlots", 0, "outputs", 0, "shape", "kind"]);
  assert.equal(calculationFailure.error.details.expectedKind, "DIMENSION_SLOT");
  assert.equal(calculationFailure.error.details.actualKind, "DIMENSION");
  const unauthorizedIntent = structuredClone(intent);
  unauthorizedIntent.computation.root.calls[0].instance.id = `metric-instance-${"0".repeat(64)}`;
  const unauthorizedCall = command(["call", "validate-query", "--payload", JSON.stringify({ intent: unauthorizedIntent }), "--home", home]);
  assert.equal(unauthorizedCall.status, 1);
  const unauthorized = JSON.parse(JSON.parse(unauthorizedCall.stdout).content[0].text);
  assert.equal(unauthorized.error.stage, "AUTHORIZE_INPUT");
  assert.equal(unauthorized.error.code, "EXECUTABLE_QUERY_NOT_PUBLISHED");
  call("validate-query", { intent });
  const savedQuery = call("save-query", {
    definitionId: "installed-sales-summary", sourceVersion: null, capabilityHash: publication.capability.capabilityHash,
    name: "销售额与订单数", ...coordinate, intent,
  });
  const execution = call("execute-query", { intent, saveResult: true, question: "全部已登记订单销售额和订单数是多少？" });
  assert.equal(execution.status, "SUCCEEDED");
  assert(execution.runId);
  assert(execution.timingNanos.SERVICE_TOTAL >= execution.timingNanos.ENGINE_TOTAL);
  assert(execution.timingNanos.RESOLVE_COMPILE_RESOLVE_SOURCE >= 0);
  assert(execution.workers.some((worker) => worker.kind === "ENGINE" && ["STARTED", "REUSED"].includes(worker.outcome)));
  const observedRun = call("read-query-usage", { runId: execution.runId }).run;
  assert.deepEqual(observedRun.executionEvidence.serviceObservation.workers, execution.workers);
  assert(observedRun.executionEvidence.serviceObservation.timingNanos.RESULT_STORE >= 0);
  verifySalesResult(execution.resultReference);
  const carry = read("read-subject-context-selection", { objectId: subject.id, sourceVersion: 1 }).selection;
  govern("revise-subject-area", {
    objectId: subject.id, sourceVersion: 1,
    definition: { businessLineId: asset.id, businessLineVersion: 1, code: "sales", displayName: "销售经营",
      owner: "local", description: "通过正式 MCP 延续既有订单分析", createdBy: "local" }, carry,
  });
  activate("SUBJECT_AREA", subject.id, 2);
  govern("build-release", { releaseId: "installed-sales", releaseVersion: "2", releaseDisplayName: "延续订单分析",
    subjectAreaId: subject.id, subjectAreaVersion: 2, createdBy: "local" });
  const nextCoordinate = { ...coordinate, scenarioVersion: 2, releaseVersion: "2" };
  const nextPublication = call("read-publication", nextCoordinate);
  const nextIntent = structuredClone(intent);
  Object.assign(nextIntent, nextCoordinate);
  nextIntent.computation.root.calls.forEach((invocation) => {
    invocation.instance = nextPublication.capability.metricCandidates.find((candidate) => candidate.code === invocation.callId).instance;
  });
  const continued = call("execute-query", { intent: nextIntent, saveResult: true });
  assert.equal(continued.status, "SUCCEEDED");
  verifySalesResult(continued.resultReference);
  await stop();
  await start("analysis-reopened");
  verifySalesResult(execution.resultReference);
  const repeated = call("execute-query", { queryReference: savedQuery.queryReference, saveResult: true, question: "重启后按同一发布重新计算销售额与订单数" });
  assert.equal(repeated.status, "SUCCEEDED");
  assert.notEqual(repeated.runId, execution.runId);
  verifySalesResult(repeated.resultReference);
  await stop();
  const report = { version: command(["--version"]).stdout.trim(), target, mode, nativePackage: mode === "registry" ? `${nativeMetadata.name}@${nativeMetadata.version}` : native[0], tools: tools.tools.length, assetCreated: Boolean(asset), persistentRead: true, actualSourceRead: true, declaredStructure: true, structurePersistentRead: true, analysisPersistentRead: true, contextRevision: nextCoordinate, sourceIdentityHash, publication: coordinate, queryReference: savedQuery.queryReference, runIds: [execution.runId, continued.runId, repeated.runId], expected: { sales_amount: "175.00", order_count: "3" }, systemToolsPath: environment.PATH };
  await writeFile(join(root, "report.json"), JSON.stringify(report, null, 2) + "\n");
  console.log(JSON.stringify(report));
} catch (failure) {
  await writeFile(join(root, "report.json"), JSON.stringify({ target, mode, status: "FAILED", message: failure.message }, null, 2) + "\n");
  throw failure;
} finally {
  await stop();
}
