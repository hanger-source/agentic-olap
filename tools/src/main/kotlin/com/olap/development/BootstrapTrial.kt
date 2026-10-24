package com.olap.development

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/** 独立运行真实 bootstrap，下载正式 Maven 构件；不将此组件试验称为完整 npm 产品验收。 */
internal class BootstrapTrial(private val bootstrap: Path, private val manifest: Path, private val root: Path) {
    fun run() {
        require(!Files.exists(root)) { "安装引导试验目录必须不存在：$root" }
        Files.createDirectories(root)
        val build = read(manifest).jsonObject
        val dependency = build.array("externalArtifacts").map { it.jsonObject }.single {
            it.string("coordinate").startsWith("com.h2database:h2:")
        }
        val expected = Path.of(dependency.objectAt("file").string("path"))
        val hash = dependency.objectAt("file").string("sha256")
        val cache = root.resolve("cache")
        val location = "java/lib/${expected.fileName}"

        fun installation(name: String, digest: String = hash): Path {
            val directory = root.resolve(name)
            Files.createDirectories(directory.resolve("runtime"))
            write(directory.resolve("jvm-dependencies.json"), objectOf("formatVersion" to text("agentic-olap-jvm-dependencies/v2"),
                "version" to build.getValue("version"), "artifacts" to JsonArray(listOf(objectOf(
                    "coordinate" to dependency.getValue("coordinate"), "url" to dependency.getValue("url"),
                    "sha256" to text(digest), "size" to dependency.getValue("size"), "locations" to JsonArray(listOf(text(location))))))))
            write(directory.resolve("distribution.json"), objectOf("formatVersion" to text("agentic-olap-distribution/v4"),
                "version" to build.getValue("version"), "server" to objectOf("mainClass" to text("org.h2.tools.Shell"),
                    "classpath" to JsonArray(listOf(objectOf("path" to text(location), "sha256" to text(digest)))))))
            return directory
        }
        fun start(directory: Path, selectedCache: Path, offline: Boolean): Process {
            val java = Path.of(System.getProperty("java.home"), "bin/java")
            val builder = ProcessBuilder(java.toString(), "-jar", bootstrap.toString(), directory.toString())
                .redirectErrorStream(true).redirectOutput(directory.resolve("bootstrap.log").toFile())
            builder.environment()["AGENTIC_OLAP_CACHE"] = selectedCache.toString()
            if (offline) builder.environment().putAll(mapOf("HTTPS_PROXY" to "http://127.0.0.1:9", "https_proxy" to "http://127.0.0.1:9",
                "NO_PROXY" to "", "no_proxy" to ""))
            return builder.start()
        }
        fun await(process: Process, success: Boolean) {
            if (!process.waitFor(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly().waitFor()
                error("安装引导进程未结束；查看试验目录中的 bootstrap.log")
            }
            check((process.exitValue() == 0) == success) { "安装引导状态不符：${process.exitValue()}；查看 bootstrap.log" }
        }
        fun installed(directory: Path) {
            check(Files.mismatch(expected, directory.resolve(location)) == -1L) { "已安装字节不同于独立解析的正式 Maven 构件" }
            check(Files.isRegularFile(directory.resolve("runtime/installed.json")))
            // 真正执行已下载库和生成的 Java 参数文件，覆盖含空格和引号的安装路径。
            val child = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "@${directory.resolve("runtime/server.args")}", "-url", "jdbc:h2:mem:bootstrap_trial", "-user", "sa", "-sql", "SELECT 6 * 7 AS ANSWER")
                .redirectErrorStream(true).redirectOutput(directory.resolve("launched-library.log").toFile()).start()
            await(child, true)
            val result = Files.readString(directory.resolve("launched-library.log"))
            check(result.lineSequence().any { it.trim() == "42" }) { "下载后构件未能通过生成的启动参数实际运行" }
        }

        val cold = installation("cold \"installation\"")
        await(start(cold, cache, false), true)
        installed(cold)
        val warm = installation("offline installation")
        await(start(warm, cache, true), true)
        installed(warm)

        val first = installation("concurrent first")
        val second = installation("concurrent second")
        val concurrentCache = root.resolve("concurrent-cache")
        val firstProcess = start(first, concurrentCache, false)
        val secondProcess = start(second, concurrentCache, false)
        try { await(firstProcess, true); await(secondProcess, true) }
        finally {
            listOf(firstProcess, secondProcess).filter(Process::isAlive).forEach { it.destroyForcibly().waitFor() }
        }
        installed(first); installed(second)
        Files.list(concurrentCache).use { entries -> check(entries.noneMatch { it.fileName.toString().endsWith(".part") }) }

        val rejected = installation("wrong digest", "0".repeat(64))
        val badCache = root.resolve("bad-cache")
        await(start(rejected, badCache, false), false)
        check(!Files.exists(rejected.resolve(location)) && !Files.exists(rejected.resolve("runtime/installed.json")))
        Files.list(badCache).use { entries -> check(entries.noneMatch { it.fileName.toString().endsWith(".jar") || it.fileName.toString().endsWith(".part") }) }
        write(root.resolve("report.json"), objectOf("passed" to bool(true), "artifact" to dependency.getValue("coordinate"),
            "coldDownload" to bool(true), "offlineCache" to bool(true), "concurrentCache" to bool(true),
            "checksumFailureNotInstalled" to bool(true), "downloadedJarExecuted" to bool(true)))
    }
}
