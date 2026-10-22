import { createReadStream } from 'node:fs';
import { readFile, readdir, lstat, mkdtemp, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { createGunzip } from 'node:zlib';
import { spawn, execFileSync } from 'node:child_process';
import { parseArgs } from 'node:util';
import { tmpdir } from 'node:os';
import path from 'node:path';

const repository = 'hanger-source/agentic-olap-skill';
const main = '@hanger-source/agentic-olap';
const targets = ['darwin-arm64', 'linux-x64'];
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
      const collect = name === 'package/package.json' || name === 'package/build.json' || name === 'package/resources.json';
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

async function checkPackage(file, name, version, runtimes) {
  const members = await archiveFiles(file);
  require([...members.keys()].every(name => name.startsWith('package/')), 'npm 文件必须位于 package/ 内');
  const pkg = members.get('package/package.json')?.json;
  require(pkg?.name === name && pkg.version === version, 'npm 归档实际名称或版本不符');
  require(pkg.repository?.url === `git+https://github.com/${repository}.git`, '包 repository.url 与公开发布仓库不同');
  require(equal(pkg.publishConfig, { access: 'public', registry }) || equal(pkg.publishConfig, { access: 'public', registry: `${registry}/` }), '包 publishConfig 不符');
  if (name === main) {
    require(equal(pkg.optionalDependencies, runtimes), '主包必须引用两个精确版本平台包');
    const resources = members.get('package/resources.json')?.json;
    require(resources?.version === version && resources.sdk === 'sdk' && resources.connectorApiVersion?.startsWith('agentic-olap-connector/'), '主包必须交付同版本 SDK 与连接器 API 身份');
    require(Array.isArray(resources.connectors) && resources.connectors.length > 0 && resources.connectors.every(file => members.has(`package/${file}`)), '主包缺少实际插件 manifest');
    require(['API.md', 'SOURCE-ACCESS.md', 'LOADER.md'].every(file => members.has(`package/sdk/${file}`)) && [...members.keys()].some(file => file.startsWith('package/sdk/maven/') && file.endsWith('.pom')), '主包缺少可直接消费的 SDK 文档或 Maven 仓库');
    return null;
  }
  const target = name.slice(`${main}-runtime-`.length);
  require(targets.includes(target), '未知平台包');
  const [system, cpu] = target.split('-');
  require(JSON.stringify(pkg.os) === JSON.stringify([system]) && JSON.stringify(pkg.cpu) === JSON.stringify([cpu]), '平台包 os/cpu 不符');
  const build = members.get('package/build.json')?.json;
  require(build?.version === version && build.target === target && /^[0-9a-f]{40}$/.test(build.revision), '平台缺少精确版本、目标或 revision');
  require(/^[0-9a-f]{64}$/.test(build.goModSha256) && /^[0-9a-f]{64}$/.test(build.goSumSha256), '平台缺少 Go 构建依赖身份');
  require(build.files && Object.keys(build.files).length > 0, '平台没有实际文件清单');
  require(members.size === Object.keys(build.files).length + 1, '平台实际文件与 build.json 清单不同');
  for (const [item, expected] of Object.entries(build.files)) require(/^[0-9a-f]{64}$/.test(expected) && members.get(`package/${item}`)?.sha256 === expected, `平台文件摘要不符：${item}`);
  return build.revision;
}
async function checkArchives(directory, metadata, tag) {
  const release = JSON.parse(await readFile(metadata, 'utf8'));
  require(Object.keys(release).sort().join() === ['version', 'package', 'runtimes'].sort().join(), 'release.json 形状不符');
  require(/^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$/.test(release.version) && tag === `v${release.version}` && release.package === main, 'release 版本、tag 或包身份不符');
  const runtimes = Object.fromEntries(targets.map(target => [`${main}-runtime-${target}`, release.version]));
  require(equal(release.runtimes, runtimes), 'release 平台包集合或版本不同');
  const packages = [...Object.keys(runtimes), main].map(name => ({ name, version: release.version, file: path.join(directory, `${name.replace(/^@/, '').replace('/', '-')}-${release.version}.tgz`) }));
  const expected = new Set(packages.map(item => path.basename(item.file)));
  const sums = new Map();
  for (const line of (await readFile(path.join(directory, 'SHA256SUMS'), 'utf8')).trimEnd().split('\n')) {
    const match = /^([0-9a-f]{64})  ([A-Za-z0-9_.-]+[.]tgz)$/.exec(line);
    require(match && !sums.has(match[2]), 'SHA256SUMS 含无效或重复条目');
    sums.set(match[2], match[1]);
  }
  const actual = (await readdir(directory)).filter(name => name.endsWith('.tgz'));
  require(sums.size === expected.size && actual.length === expected.size && actual.every(name => expected.has(name)) && [...sums.keys()].every(name => expected.has(name)), 'release 必须包含主包及两个平台包的三个精确 npm 归档');
  for (const [name, expectedHash] of sums) {
    const file = path.join(directory, name);
    const stat = await lstat(file);
    require(stat.isFile() && !stat.isSymbolicLink() && await hashFile(file) === expectedHash, `release 资产摘要不符：${name}`);
  }
  const revisions = new Set();
  for (const item of packages) { const revision = await checkPackage(item.file, item.name, item.version, runtimes); if (revision) revisions.add(revision); }
  require(revisions.size === 1, '两个平台包必须来自同一源码 revision');
  console.log(JSON.stringify({ version: release.version, archivesChecked: expected.size, revision: [...revisions][0] }));
  return packages;
}
async function publishedIntegrity(name, version) {
  const response = await fetch(`${registry}/${encodeURIComponent(name)}/${encodeURIComponent(version)}`, { signal: AbortSignal.timeout(30000) });
  if (response.status === 404) return null;
  require(response.ok, `registry 请求失败：${response.status}`);
  const metadata = await response.json();
  require(metadata.name === name && metadata.version === version && typeof metadata.dist?.integrity === 'string', 'registry 返回不同包、版本或无效摘要');
  return metadata.dist.integrity;
}
async function waitForIntegrity(item) {
  const deadline = performance.now() + 3600000;
  while (performance.now() < deadline) {
    const actual = await publishedIntegrity(item.name, item.version);
    if (actual !== null) { require(actual === item.integrity, `发布后 integrity 不符：${item.name}`); return; }
    console.log(`npm 已接受，等待 registry 可读：${item.name}@${item.version}`);
    await new Promise(resolve => setTimeout(resolve, Math.min(5000, Math.max(0, deadline - performance.now()))));
  }
  throw new Error(`npm 已接受提交，但 3600 秒内不可读：${item.name}@${item.version}`);
}
async function publish(packages, submitted) {
  require([...submitted].every(name => packages.some(item => item.name === name)), '已受理包不属于本次发行');
  require(process.env.GITHUB_ACTIONS === 'true' && process.env.GITHUB_REPOSITORY === repository, '正式发布只允许指定公开仓库的 GitHub Actions');
  require(process.env.ACTIONS_ID_TOKEN_REQUEST_URL && process.env.ACTIONS_ID_TOKEN_REQUEST_TOKEN, '发布缺少 GitHub OIDC 授权');
  require(!('NODE_AUTH_TOKEN' in process.env) && !('NPM_TOKEN' in process.env), '发布不得提供长期 npm 令牌');
  require(execFileSync('npm', ['--version'], { encoding: 'utf8' }).trim() === '11.17.0' && process.version.startsWith('v24.'), '发布要求 npm 11.17.0 和 Node.js 24');
  const pending = [];
  for (const item of packages) {
    item.integrity = `sha512-${await hashFile(item.file, 'sha512', 'base64')}`;
    const actual = await publishedIntegrity(item.name, item.version);
    require(actual === null || actual === item.integrity, `同一 npm 版本已存在不同字节：${item.name}`);
    if (actual === null) pending.push(item); else console.log(`已存在且摘要匹配：${item.name}@${item.version}`);
  }
  const temporary = await mkdtemp(path.join(tmpdir(), 'agentic-olap-publish-'));
  const user = path.join(temporary, 'user.npmrc'), global = path.join(temporary, 'global.npmrc');
  await writeFile(user, ''); await writeFile(global, '');
  const environment = Object.fromEntries(Object.entries(process.env).filter(([key]) => !key.toLowerCase().startsWith('npm_config_')));
  Object.assign(environment, { NPM_CONFIG_USERCONFIG: user, NPM_CONFIG_GLOBALCONFIG: global, NPM_CONFIG_CACHE: path.join(temporary, 'cache') });
  for (const batch of [pending.filter(item => item.name !== main), pending.filter(item => item.name === main)]) {
    for (const item of batch) {
      if (submitted.has(item.name)) { console.log(`按明确已受理记录继续，不重复提交：${item.name}@${item.version}`); continue; }
      await new Promise((resolve, reject) => {
        const child = spawn('npm', ['publish', item.file, '--access', 'public', '--registry', registry, '--ignore-scripts', '--provenance'], { cwd: temporary, env: environment, stdio: 'inherit' });
        child.once('error', reject); child.once('exit', (code, signal) => code === 0 ? resolve() : reject(new Error(`npm publish 失败：${code ?? signal}`)));
      });
      console.log(`npm 已接受提交：${item.name}@${item.version}`);
    }
    for (const item of batch) { await waitForIntegrity(item); console.log(`已发布并核对摘要：${item.name}@${item.version}`); }
  }
}
const { values } = parseArgs({ options: { artifacts: { type: 'string' }, metadata: { type: 'string', default: 'release.json' }, tag: { type: 'string' }, submitted: { type: 'string', default: '' }, check: { type: 'boolean', default: false } } });
require(values.artifacts && values.tag, '需要 --artifacts 与 --tag');
require(!values.check || !values.submitted, '离线核验不能指定已受理包');
const packages = await checkArchives(path.resolve(values.artifacts), path.resolve(values.metadata), values.tag);
if (!values.check) await publish(packages, new Set(values.submitted.split(/\s+/).filter(Boolean)));
