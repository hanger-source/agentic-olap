"""核验已审核的 release 归档；正式发布只允许 GitHub Actions 的 OIDC 身份。"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import tarfile
import tempfile
import time
from pathlib import Path, PurePosixPath
from typing import Any
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import urlopen

REPOSITORY = "hanger-source/agentic-olap-skill"
MAIN = "@hanger-source/agentic-olap"
TARGETS = ("darwin-arm64", "linux-x64")
REGISTRY = "https://registry.npmjs.org"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def file_hash(path: Path, algorithm: str = "sha256") -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def archive_members(archive: tarfile.TarFile) -> dict[str, tarfile.TarInfo]:
    result = {}
    for item in archive:
        path = PurePosixPath(item.name)
        require(not path.is_absolute() and ".." not in path.parts, "归档含越界路径")
        require(item.isdir() or item.isfile(), "归档含非普通文件或目录")
        if item.isfile():
            require(item.name not in result, "归档含重复文件：" + item.name)
            result[item.name] = item
    return result


def read_json(archive: tarfile.TarFile, members: dict[str, tarfile.TarInfo], name: str) -> Any:
    item = members[name]
    require(item.size <= 4 * 1024 * 1024, "JSON 文件超过 4 MiB：" + name)
    stream = archive.extractfile(item)
    require(stream is not None, "归档文件不可读取：" + name)
    assert stream is not None
    with stream:
        return json.load(stream)


def check_package(path: Path, name: str, version: str, runtimes: dict[str, str]) -> str | None:
    with tarfile.open(path, "r:gz") as archive:
        members = archive_members(archive)
        require(
            all(item.startswith("package/") for item in members), "npm 归档必须位于 package/ 内"
        )
        package = read_json(archive, members, "package/package.json")
        require(
            package["name"] == name and package["version"] == version, "tar 包实际名称或版本不符"
        )
        require(
            package["repository"]["url"] == "git+https://github.com/" + REPOSITORY + ".git",
            "包 repository.url 与公开发布仓库不符",
        )
        publish_config = package["publishConfig"]
        require(
            set(publish_config) == {"access", "registry"}
            and publish_config["access"] == "public"
            and publish_config["registry"] in (REGISTRY, REGISTRY + "/"),
            "包 publishConfig 不符",
        )
        if name == MAIN:
            require(package["optionalDependencies"] == runtimes, "主包必须引用两个精确版本平台包")
            return None
        target = name.removeprefix(MAIN + "-runtime-")
        require(target in TARGETS, "未知平台包")
        system, cpu = target.split("-")
        require(package["os"] == [system] and package["cpu"] == [cpu], "平台包 os/cpu 不符")
        build = read_json(archive, members, "package/build.json")
        require(
            build["version"] == version and build["target"] == target,
            "平台 build.json 版本或目标不符",
        )
        require(
            re.fullmatch(r"[0-9a-f]{40}", build["revision"]) is not None,
            "平台缺少精确源码 revision",
        )
        require(
            re.fullmatch(r"[0-9a-f]{64}", build["binaryLockSha256"]) is not None,
            "平台缺少构建依赖锁身份",
        )
        files = build["files"]
        require(isinstance(files, dict) and bool(files), "平台缺少实际文件清单")
        require(
            {"package/" + item for item in files} == set(members) - {"package/build.json"},
            "平台实际文件与 build.json 清单不一致",
        )
        for item, expected in files.items():
            require(re.fullmatch(r"[0-9a-f]{64}", expected) is not None, "平台文件摘要无效")
            stream = archive.extractfile(members["package/" + item])
            require(stream is not None, "平台文件不可读取")
            assert stream is not None
            with stream:
                require(
                    hashlib.file_digest(stream, "sha256").hexdigest() == expected,
                    "平台文件内容摘要不符：" + item,
                )
        return build["revision"]


def check_archives(directory: Path, metadata: Path, tag: str) -> list[tuple[str, str, Path]]:
    release = json.loads(metadata.read_bytes())
    require(set(release) == {"version", "package", "runtimes"}, "release.json 形状不符")
    version = release["version"]
    require(
        isinstance(version, str)
        and re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?", version) is not None,
        "release 版本无效",
    )
    require(tag == "v" + version and release["package"] == MAIN, "release tag 或包身份不符")
    runtimes = {MAIN + "-runtime-" + target: version for target in TARGETS}
    require(release["runtimes"] == runtimes, "release 平台包集合或版本不符")
    packages = [
        (
            name,
            version,
            directory / (name.removeprefix("@").replace("/", "-") + "-" + version + ".tgz"),
        )
        for name in [*runtimes, MAIN]
    ]
    expected = {path.name for _, _, path in packages} | {
        "agentic-olap-connectors-" + version + ".tgz",
        "agentic-olap-connector-sdk-" + version + ".tgz",
    }
    sums = {}
    for line in (directory / "SHA256SUMS").read_text().splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9_.-]+[.]tgz)", line)
        require(match is not None, "SHA256SUMS 含无效条目")
        assert match is not None
        checksum, filename = match.groups()
        require(filename not in sums, "SHA256SUMS 含重复文件")
        sums[filename] = checksum
    require(set(sums) == expected, "SHA256SUMS 必须精确列出五个正式归档")
    require({path.name for path in directory.glob("*.tgz")} == expected, "release 归档集合不符")
    for filename, checksum in sums.items():
        path = directory / filename
        require(path.is_file() and not path.is_symlink(), "release 资产须为普通文件")
        require(file_hash(path) == checksum, "release 资产摘要不符：" + filename)
    revisions = set()
    for name, package_version, path in packages:
        revision = check_package(path, name, package_version, runtimes)
        if revision is not None:
            revisions.add(revision)
    require(len(revisions) == 1, "两个平台包必须来自同一源码 revision")
    print(
        json.dumps(
            {
                "version": version,
                "archivesChecked": len(expected),
                "revision": next(iter(revisions)),
            }
        ),
        flush=True,
    )
    return packages


def integrity(path: Path) -> str:
    with path.open("rb") as stream:
        raw = hashlib.file_digest(stream, "sha512").digest()
    return "sha512-" + base64.b64encode(raw).decode("ascii")


def published_integrity(name: str, version: str, *, timeout: float = 30) -> str | None:
    try:
        with urlopen(
            REGISTRY + "/" + quote(name, safe="") + "/" + quote(version, safe=""), timeout=timeout
        ) as response:
            metadata = json.load(response)
    except HTTPError as failure:
        if failure.code == 404:
            return None
        raise
    require(
        metadata["name"] == name and metadata["version"] == version, "registry 返回了不同包或版本"
    )
    return metadata["dist"]["integrity"]


def wait_for_published_integrity(name: str, version: str, expected: str) -> None:
    deadline = time.monotonic() + 3600
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("npm 提交已接受，但 3600 秒内尚未可读：" + name + "@" + version)
        actual = published_integrity(name, version, timeout=min(30, remaining))
        if actual is not None:
            require(actual == expected, "发布后 registry integrity 不符：" + name)
            return
        print("npm 正在处理，等待 registry 可读：" + name + "@" + version, flush=True)
        time.sleep(min(5, max(0, deadline - time.monotonic())))


def publish(packages: list[tuple[str, str, Path]]) -> None:
    require(
        os.environ.get("GITHUB_ACTIONS") == "true"
        and os.environ.get("GITHUB_REPOSITORY") == REPOSITORY,
        "正式发布仅允许指定公开仓库的 GitHub Actions",
    )
    require(
        bool(os.environ.get("ACTIONS_ID_TOKEN_REQUEST_URL"))
        and bool(os.environ.get("ACTIONS_ID_TOKEN_REQUEST_TOKEN")),
        "正式发布缺少 GitHub OIDC 授权",
    )
    require(
        not any(key in os.environ for key in ("NODE_AUTH_TOKEN", "NPM_TOKEN")),
        "发布不得提供长期 npm 令牌",
    )
    require(
        subprocess.check_output(["npm", "--version"], text=True).strip() == "11.17.0",
        "发布要求 npm 11.17.0",
    )
    require(
        subprocess.check_output(["node", "--version"], text=True).startswith("v24."),
        "发布要求 Node.js 24",
    )
    pending = []
    for name, version, path in packages:
        actual = published_integrity(name, version)
        expected = integrity(path)
        require(actual is None or actual == expected, "同一 npm 版本已存在不同字节：" + name)
        if actual is None:
            pending.append((name, version, path, expected))
        else:
            print("已存在且 integrity 精确匹配：" + name + "@" + version, flush=True)
    with tempfile.TemporaryDirectory(prefix="agentic-olap-publish-") as temporary:
        root = Path(temporary)
        user, global_config = root / "user.npmrc", root / "global.npmrc"
        user.write_text("")
        global_config.write_text("")
        environment = {
            key: value
            for key, value in os.environ.items()
            if not key.lower().startswith("npm_config_")
        }
        environment.update(
            NPM_CONFIG_USERCONFIG=str(user),
            NPM_CONFIG_GLOBALCONFIG=str(global_config),
            NPM_CONFIG_CACHE=str(root / "cache"),
        )
        for name, version, path, expected in pending:
            subprocess.run(
                [
                    "npm",
                    "publish",
                    str(path.resolve()),
                    "--access",
                    "public",
                    "--registry",
                    REGISTRY,
                    "--ignore-scripts",
                    "--provenance",
                ],
                cwd=root,
                env=environment,
                check=True,
            )
            print("npm 已接受提交：" + name + "@" + version, flush=True)
            wait_for_published_integrity(name, version, expected)
            print("已发布并核对 integrity：" + name + "@" + version, flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, default=Path("release.json"))
    parser.add_argument("--tag", required=True)
    parser.add_argument(
        "--check", action="store_true", help="仅离线核验归档，不查询或写入 registry"
    )
    args = parser.parse_args()
    packages = check_archives(
        args.artifacts.resolve(strict=True), args.metadata.resolve(strict=True), args.tag
    )
    if not args.check:
        publish(packages)


if __name__ == "__main__":
    main()
