import { createReadStream } from 'node:fs';
import { readFile, readdir, lstat, mkdtemp, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { createGunzip } from 'node:zlib';
import { spawn, execFileSync } from 'node:child_process';
import { parseArgs } from 'node:util';
import { tmpdir } from 'node:os';
import path from 'node:path';

const repository = 'hanger-source/agentic-olap';
const main = '@hanger-source/agentic-olap';
const registry = 'https://registry.npmjs.org';
function require(value, message) { if (!value) throw new Error(message); }
function equal(a, b) { return JSON.stringify(Object.entries(a).sort()) === JSON.stringify(Object.entries(b).sort()); }
async function hashFile(file, algorithm = 'sha256', encoding = 'hex') {
  const hash = createHash(algorithm);
  for await (const chunk of createReadStream(file)) hash.update(chunk);
  return hash.digest(encoding);
}

// 顺序读取 tar，逐文件摘要；平台运行时的大文件不载入内存或解包到磁盘。
async function archiveFiles(file) {
  const stream = createReadStream(file).pipe(createGunzip());
  const iterator = stream[Symbol.asyncIterator]();
  let buffered = Buffer.alloc(0);
  async function take(size) {
    while (buffered.length < size) {
      const next = await iterator.next();
      require(!next.done, '归档在完整条目之前结束');
      buffered = Buffer.concat([buffered, next.value]);
    }
    const result = buffered.subarray(0, size);
    buffered = buffered.subarray(size);
    return result;
  }
  function text(buffer) { const end = buffer.indexOf(0); return buffer.subarray(0, end < 0 ? buffer.length : end).toString('utf8'); }
  function octal(buffer) { const value = text(buffer).trim(); require(/^[0-7]*$/.test(value), 'tar 整数字段无效'); return Number.parseInt(value || '0', 8); }
  function pax(data) {
    const fields = {};
    for (let offset = 0; offset < data.length;) {
      const separator = data.indexOf(32, offset);
      require(separator > offset, 'PAX 长度字段无效');
      const length = Number(data.subarray(offset, separator).toString('ascii'));
      require(Number.isSafeInteger(length) && length > separator - offset + 1 && offset + length <= data.length && data[offset + length - 1] === 10, 'PAX 条目越界');
      const record = data.subarray(separator + 1, offset + length - 1).toString('utf8');
      const equalAt = record.indexOf('=');
      require(equalAt > 0, 'PAX 字段无效');
      fields[record.slice(0, equalAt)] = record.slice(equalAt + 1);
      offset += length;
    }
    return fields;
  }
  const result = new Map();
  let global = {}, extended = {};
  try {
    for (;;) {
      const header = await take(512);
      if (header.every(value => value === 0)) {
        require(buffered.every(value => value === 0), 'tar 结束后存在非零内容');
        for await (const chunk of iterator) require(chunk.every(value => value === 0), 'tar 结束后存在非零内容');
        return result;
      }
      const checksum = octal(header.subarray(148, 156));
      let actual = 0;
      for (let index = 0; index < header.length; index++) actual += index >= 148 && index < 156 ? 32 : header[index];
      require(actual === checksum, 'tar header 校验失败');
      const size = octal(header.subarray(124, 136));
      require(Number.isSafeInteger(size) && size >= 0, 'tar 文件长度无效');
      const type = header[156] === 0 ? '0' : String.fromCharCode(header[156]);
      if (type === 'x' || type === 'g' || type === 'L') {
        require(size <= 4 * 1024 * 1024, 'tar 扩展头超过 4 MiB');
        const data = await take(size);
        if (type === 'g') global = { ...global, ...pax(data) };
        else extended = type === 'L' ? { path: text(data) } : pax(data);
        await take((512 - size % 512) % 512);
        continue;
      }
      const prefix = text(header.subarray(345, 500));
      const rawName = text(header.subarray(0, 100));
      const name = extended.path ?? global.path ?? (prefix ? `${prefix}/${rawName}` : rawName);
      if (extended.size !== undefined || global.size !== undefined) require(Number(extended.size ?? global.size) === size, 'PAX 文件长度与 header 不同');
      extended = {};
      require(name && !path.posix.isAbsolute(name) && !name.split('/').includes('..'), '归档含越界路径');
      require(type === '0' || type === '5', '归档含非普通文件或目录');
      require(type !== '5' || size === 0, '归档目录包含文件数据');
      const digest = createHash('sha256');
      const collect = ['package/package.json', 'package/build.json', 'package/resources.json', 'package/jvm-dependencies.json', 'package/distribution.json'].includes(name) || name.endsWith('/connector.json');
      require(!collect || size <= 4 * 1024 * 1024, `JSON 文件超过 4 MiB：${name}`);
      const pieces = [];
      for (let remaining = size; remaining > 0;) {
        const data = await take(Math.min(64 * 1024, remaining));
        digest.update(data); if (collect) pieces.push(data); remaining -= data.length;
      }
      await take((512 - size % 512) % 512);
      if (type === '0') {
        require(!result.has(name), `归档含重复文件：${name}`);
        result.set(name, { sha256: digest.digest('hex'), json: collect ? JSON.parse(Buffer.concat(pieces).toString('utf8')) : undefined });
      }
    }
  } finally { stream.destroy(); }
}

async function checkPackage(file, name, version) {
  const members = await archiveFiles(file);
  require([...members.keys()].every(name => name.startsWith('package/')), 'npm 文件必须位于 package/ 内');
  const pkg = members.get('package/package.json')?.json;
  require(pkg?.name === name && pkg.version === version, 'npm 归档实际名称或版本不符');
  require(pkg.repository?.url === `git+https://github.com/${repository}.git`, '包 repository.url 与公开发布仓库不同');
  require(equal(pkg.publishConfig, { access: 'public', registry }) || equal(pkg.publishConfig, { access: 'public', registry: `${registry}/` }), '包 publishConfig 不符');
  require(!pkg.optionalDependencies && !pkg.dependencies, '产品必须由一个 npm 包完整交付');
  require(JSON.stringify(pkg.os) === '["darwin"]' && JSON.stringify(pkg.cpu) === '["arm64"]', '产品仅支持 macOS arm64');
  require(pkg.bin?.['agentic-olap'] === 'runtime/agentic-olap' && members.has('package/runtime/agentic-olap'), '命令必须使用随包 JVM 启动入口');
  require(pkg.scripts?.postinstall === './jre/bin/java -jar ./runtime/agentic-olap-bootstrap.jar .', 'JVM 下载必须由独立引导在 npm 安装阶段完成');
  require(members.has('package/runtime/agentic-olap-bootstrap.jar') && members.has('package/jre/bin/java'), '安装引导必须自包含并使用包内 JRE');
  require(![...members.keys()].some(file => file.endsWith('.jar') && !file.startsWith('package/jre/') && file !== 'package/runtime/agentic-olap-bootstrap.jar'), '产品依赖 JAR 不应被打进 npm 包');
  const resources = members.get('package/resources.json')?.json;
  require(resources?.version === version && resources.sdk === 'sdk' && resources.connectorApiVersion?.startsWith('agentic-olap-connector/'), '缺少同版本 SDK 与连接器 API 身份');
  require(['API.md', 'SOURCE-ACCESS.md', 'LOADER.md'].every(file => members.has(`package/sdk/${file}`)), '缺少 SDK 文档');
  require(resources.sdkRepository === 'https://jitpack.io' && Object.values(resources.sdkArtifacts ?? {}).length > 0 && Object.values(resources.sdkArtifacts).every(coordinate => coordinate.startsWith('com.github.hanger-source.agentic-olap:') && coordinate.endsWith(`:v${version}`)), 'SDK 必须使用本版精确 JitPack 坐标');
  const downloads = members.get('package/jvm-dependencies.json')?.json;
  require(downloads?.formatVersion === 'agentic-olap-jvm-dependencies/v2' && downloads.version === version && downloads.artifacts.length > 0, '缺少同版 JVM 安装清单');
  const targets = new Map();
  for (const artifact of downloads.artifacts) {
    require(/^[0-9a-f]{64}$/.test(artifact.sha256) && Number.isSafeInteger(artifact.size) && artifact.size > 0 && artifact.locations.length > 0, 'Maven 构件必须锁定大小与摘要');
    require(artifact.url.startsWith('https://repo.maven.apache.org/maven2/') || artifact.url.startsWith(`https://jitpack.io/com/github/hanger-source/agentic-olap/`) && artifact.url.includes(`/v${version}/`), 'Maven 构件必须来自正式仓库的精确版本');
    for (const location of artifact.locations) {
      require((location.startsWith('java/lib/') || location.startsWith('connectors/')) && !location.split('/').includes('..') && location.endsWith('.jar'), 'Maven 安装目标不合法');
      require(!targets.has(location), 'Maven 安装目标重复'); targets.set(location, artifact.sha256);
    }
  }
  const distribution = members.get('package/distribution.json')?.json;
  require(distribution?.formatVersion === 'agentic-olap-distribution/v4' && distribution.version === version, '缺少精确服务发行清单');
  require(distribution.scripting?.dslApiVersion === 'agentic-olap-kotlin-dsl/v2', '缺少本版脚本 DSL 身份');
  const classpaths = [distribution.server?.classpath, distribution.worker?.classpath, distribution.scripting.runtime?.classpath, distribution.scripting.dsl];
  require(classpaths.every(group => Array.isArray(group) && group.length > 0), '服务、脚本运行及 DSL 类路径必须完整');
  const required = new Map();
  const include = item => {
    require(typeof item.path === 'string' && /^[0-9a-f]{64}$/.test(item.sha256), '类路径必须保存精确路径与摘要');
    require(!required.has(item.path) || required.get(item.path) === item.sha256, '同一运行文件具有冲突摘要');
    required.set(item.path, item.sha256);
  };
  classpaths.forEach(group => group.forEach(include));
  require(Array.isArray(resources.connectors) && resources.connectors.length > 0, '缺少内置插件声明');
  for (const file of resources.connectors) {
    const plugin = members.get(`package/${file}`)?.json;
    require(plugin?.apiVersion === resources.connectorApiVersion && plugin.jars?.length, '缺少实际插件 manifest');
    plugin.jars.forEach(jar => include({ path: path.posix.join(path.posix.dirname(file), jar.file), sha256: jar.sha256 }));
  }
  require(required.size === targets.size && [...required].every(([file, digest]) => targets.get(file) === digest), '安装清单未精确覆盖全部服务、脚本运行、DSL和插件');
  const build = members.get('package/build.json')?.json;
  require(build?.version === version && build.target === 'darwin-arm64' && /^[0-9a-f]{40}$/.test(build.revision), '缺少精确版本、目标或 revision');
  require(build.files && members.size === Object.keys(build.files).length + 1, '实际文件与 build.json 清单不同');
  for (const [item, expected] of Object.entries(build.files)) require(/^[0-9a-f]{64}$/.test(expected) && members.get(`package/${item}`)?.sha256 === expected, `文件摘要不符：${item}`);
  return build.revision;
}
async function checkArchives(directory, metadata, tag) {
  const release = JSON.parse(await readFile(metadata, 'utf8'));
  require(/^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$/.test(release.version) && tag === `v${release.version}` && release.name === main, 'package.json 版本、tag 或包身份不符');
  const packages = [{ name: main, version: release.version, file: path.join(directory, `hanger-source-agentic-olap-${release.version}.tgz`) }];
  const expected = new Set(packages.map(item => path.basename(item.file)));
  const sums = new Map();
  for (const line of (await readFile(path.join(directory, 'SHA256SUMS'), 'utf8')).trimEnd().split('\n')) {
    const match = /^([0-9a-f]{64})  ([A-Za-z0-9_.-]+[.]tgz)$/.exec(line);
    require(match && !sums.has(match[2]), 'SHA256SUMS 含无效或重复条目');
    sums.set(match[2], match[1]);
  }
  const actual = (await readdir(directory)).filter(name => name.endsWith('.tgz'));
  require(sums.size === expected.size && actual.length === expected.size && actual.every(name => expected.has(name)) && [...sums.keys()].every(name => expected.has(name)), 'release 归档必须与发行声明一致');
  for (const [name, expectedHash] of sums) {
    const file = path.join(directory, name);
    const stat = await lstat(file);
    require(stat.isFile() && !stat.isSymbolicLink() && await hashFile(file) === expectedHash, `release 资产摘要不符：${name}`);
  }
  const revisions = new Set();
  for (const item of packages) { const revision = await checkPackage(item.file, item.name, item.version); if (revision) revisions.add(revision); }
  require(revisions.size === 1, '产品必须包含精确源码 revision');
  console.log(JSON.stringify({ version: release.version, archivesChecked: expected.size, revision: [...revisions][0] }));
  return packages[0];
}
async function publish(item) {
  require(process.env.GITHUB_ACTIONS === 'true' && process.env.GITHUB_REPOSITORY === repository, '正式发布只允许指定公开仓库的 GitHub Actions');
  require(process.env.ACTIONS_ID_TOKEN_REQUEST_URL && process.env.ACTIONS_ID_TOKEN_REQUEST_TOKEN, '发布缺少 GitHub OIDC 授权');
  require(!('NODE_AUTH_TOKEN' in process.env) && !('NPM_TOKEN' in process.env), '发布不得提供长期 npm 令牌');
  require(execFileSync('npm', ['--version'], { encoding: 'utf8' }).trim() === '11.17.0' && process.version.startsWith('v24.'), '发布要求 npm 11.17.0 和 Node.js 24');
  const temporary = await mkdtemp(path.join(tmpdir(), 'agentic-olap-publish-'));
  const user = path.join(temporary, 'user.npmrc'), global = path.join(temporary, 'global.npmrc');
  await writeFile(user, ''); await writeFile(global, '');
  const environment = Object.fromEntries(Object.entries(process.env).filter(([key]) => !key.toLowerCase().startsWith('npm_config_')));
  Object.assign(environment, { NPM_CONFIG_USERCONFIG: user, NPM_CONFIG_GLOBALCONFIG: global, NPM_CONFIG_CACHE: path.join(temporary, 'cache') });
    await new Promise((resolve, reject) => {
      const child = spawn('npm', ['publish', item.file, '--access', 'public', '--registry', registry, '--ignore-scripts', '--provenance', '--loglevel=error'], { cwd: temporary, env: environment, stdio: 'inherit' });
      child.once('error', reject); child.once('exit', (code, signal) => code === 0 ? resolve() : reject(new Error(`${item.name} 发布失败：${code ?? signal}`)));
    });
    console.log(`npm 已接受提交：${item.name}@${item.version}`);
}
const { values } = parseArgs({ options: { artifacts: { type: 'string' }, metadata: { type: 'string', default: 'project/cli/package.json' }, tag: { type: 'string' }, check: { type: 'boolean', default: false } } });
require(values.artifacts && values.tag, '需要 --artifacts 与 --tag');
const product = await checkArchives(path.resolve(values.artifacts), path.resolve(values.metadata), values.tag);
if (!values.check) await publish(product);
