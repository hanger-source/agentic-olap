#!/usr/bin/env node
import { spawn } from "node:child_process";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join, delimiter } from "node:path";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const metadata = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8"));
if (process.argv[2] === "--version") {
  console.log(metadata.version);
  process.exit(0);
}
const target = `${process.platform}-${process.arch}`;
const name = `@hanger-source/agentic-olap-runtime-${target}`;
if (!(name in metadata.optionalDependencies)) {
  console.error(`不支持 ${target}；当前发行支持 macOS arm64。`);
  process.exit(1);
}
let packagePath;
try {
  packagePath = require.resolve(`${name}/package.json`);
} catch {
  console.error(`缺少同版本平台运行包 ${name}@${metadata.version}；请重新安装并启用 npm optional dependencies。`);
  process.exit(1);
}
const nativeMetadata = JSON.parse(readFileSync(packagePath, "utf8"));
if (nativeMetadata.version !== metadata.version) {
  console.error(`平台运行包版本不一致：需要 ${metadata.version}，实际 ${nativeMetadata.version}`);
  process.exit(1);
}
const root = dirname(packagePath);
const javaHome = join(root, "jre");
const child = spawn(join(root, "distribution", "runtime", "agentic-olap-runtime"), process.argv.slice(2), {
  stdio: "inherit",
  // 终端信号由当前入口转发一次，避免同一 Ctrl-C 同时命中两个进程。
  detached: true,
  env: {
    ...process.env,
    AGENTIC_OLAP_HOME: join(root, "distribution"),
    AGENTIC_OLAP_PACKAGE_ROOT: fileURLToPath(new URL("..", import.meta.url)),
    AGENTIC_OLAP_VERSION: metadata.version,
    JAVA_HOME: javaHome,
    PATH: join(javaHome, "bin") + delimiter + (process.env.PATH ?? ""),
  },
});
for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => child.kill(signal));
}
child.on("error", (error) => {
  console.error(`无法启动产品运行时：${error.message}`);
  process.exitCode = 1;
});
child.on("exit", (code, signal) => {
  process.exitCode = code ?? (signal === "SIGINT" ? 130 : 143);
});
