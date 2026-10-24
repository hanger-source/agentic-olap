package com.olap.prototype.bootstrap

import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/** 安装时运行；其依赖全部包含在 bootstrap JAR 中，不加载待下载的产品。 */
fun main(args: Array<String>) {
    require(args.size == 1) { "安装引导需要明确的 npm 产品根目录" }
    val root = Path.of(args.single()).toRealPath()
    val document = json(root.resolve("jvm-dependencies.json"))
    require(document.string("formatVersion") == "agentic-olap-jvm-dependencies/v2") { "JVM 安装清单格式不同" }
    val distribution = json(root.resolve("distribution.json"))
    require(distribution.string("formatVersion") == "agentic-olap-distribution/v4" &&
        distribution.string("version") == document.string("version")) { "产品与依赖清单版本不同" }
    val cache = Path.of(System.getenv("AGENTIC_OLAP_CACHE") ?:
        Path.of(System.getProperty("user.home"), ".cache", "agentic-olap", "jvm").toString())
        .toAbsolutePath().normalize()
    Files.createDirectories(cache)
    val client = HttpClient.newBuilder().proxy(EnvironmentProxy()).followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(60)).build()
    val entries = document.getValue("artifacts").jsonArray
    require(entries.isNotEmpty()) { "安装清单没有 JVM 构件" }
    // 每个内容摘要的文件锁允许并行 npm 安装共享缓存；不同内容互不阻塞。
    entries.forEach { value ->
        val artifact = value.jsonObject
        val hash = artifact.string("sha256")
        require(hash.matches(Regex("[a-f0-9]{64}"))) { "无效构件 SHA-256" }
        val size = artifact.getValue("size").jsonPrimitive.long
        require(size > 0) { "构件缺少精确长度" }
        val url = URI.create(artifact.string("url"))
        require(url.scheme == "https" && url.userInfo == null &&
            url.host in setOf("jitpack.io", "repo.maven.apache.org")) { "未授权 Maven 构件来源：$url" }
        val blob = cache.resolve("$hash.jar")
        FileChannel.open(cache.resolve("$hash.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use {
                if (!matches(blob, size, hash)) {
                    val temporary = cache.resolve("$hash.${UUID.randomUUID()}.part")
                    try {
                        val response = client.send(HttpRequest.newBuilder(url).timeout(Duration.ofMinutes(30))
                            .header("User-Agent", "Agentic-OLAP/${document.string("version")}")
                            .GET().build(), HttpResponse.BodyHandlers.ofFile(temporary, CREATE_NEW, WRITE))
                        require(response.statusCode() == 200 && response.uri().scheme == "https") {
                            "Maven 下载失败：${artifact.string("coordinate")}，HTTP ${response.statusCode()}"
                        }
                        require(matches(temporary, size, hash)) { "Maven 构件长度或 SHA-256 不符：${artifact.string("coordinate")}" }
                        Files.move(temporary, blob, ATOMIC_MOVE, REPLACE_EXISTING)
                    } finally {
                        Files.deleteIfExists(temporary)
                    }
                }
                artifact.getValue("locations").jsonArray.forEach { location ->
                    val target = contained(root, location.jsonPrimitive.content)
                    if (!matches(target, size, hash)) {
                        Files.createDirectories(target.parent)
                        contained(root, location.jsonPrimitive.content)
                        val temporary = target.resolveSibling("${target.fileName}.${UUID.randomUUID()}.part")
                        try {
                            Files.copy(blob, temporary)
                            require(matches(temporary, size, hash)) { "安装构件校验失败：$target" }
                            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
                        } finally {
                            Files.deleteIfExists(temporary)
                        }
                    }
                }
            }
        }
    }
    val server = distribution.getValue("server").jsonObject
    writeJavaArguments(root, "server", server)
    // 只有全部构件校验、落位成功后才写就绪事实。启动入口不联网、不补装。
    Files.writeString(root.resolve("runtime/installed.json"), buildJsonObject {
        put("version", distribution.string("version"))
        put("dependencyManifestSha256", digest(root.resolve("jvm-dependencies.json")))
    }.toString() + "\n")
    println("Agentic OLAP ${document.string("version")} 已安装，运行 agentic-olap start 即可启动。")
}

private fun writeJavaArguments(root: Path, name: String, application: JsonObject) {
    val files = application.getValue("classpath").jsonArray.map { raw ->
        val identity = raw.jsonObject
        val path = contained(root, identity.string("path"))
        require(Files.isRegularFile(path) && digest(path) == identity.string("sha256")) { "启动构件不符：$path" }
        path.toString()
    }
    require(files.isNotEmpty()) { "$name classpath 为空" }
    val arguments = listOf("-cp", files.joinToString(java.io.File.pathSeparator), application.string("mainClass"))
    Files.writeString(root.resolve("runtime/$name.args"), arguments.joinToString("\n", postfix = "\n") { argument ->
        "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
    })
}

private fun contained(root: Path, relative: String): Path {
    val value = Path.of(relative)
    require(!value.isAbsolute && value.none { it.toString() == ".." }) { "发行路径越界：$relative" }
    val target = root.resolve(value).normalize()
    require(target.startsWith(root) && target != root) { "发行路径越界：$relative" }
    var parent: Path? = target
    while (parent != null && parent.startsWith(root)) {
        require(!Files.isSymbolicLink(parent)) { "安装路径不能经符号链接离开发行根：$relative" }
        parent = parent.parent
    }
    return target
}

private fun json(path: Path) = Json.parseToJsonElement(Files.readString(path)).jsonObject
private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content
private fun matches(path: Path, size: Long, hash: String) =
    Files.isRegularFile(path) && Files.size(path) == size && digest(path) == hash
private fun digest(path: Path): String = Files.newInputStream(path).use { input ->
    val hash = MessageDigest.getInstance("SHA-256")
    val bytes = ByteArray(65536)
    while (true) { val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count) }
    hash.digest().joinToString("") { "%02x".format(it) }
}

private class EnvironmentProxy : ProxySelector() {
    override fun select(uri: URI): List<Proxy> {
        val exclusions = (System.getenv("NO_PROXY") ?: System.getenv("no_proxy") ?: "").split(',')
        if (exclusions.any { rule ->
            val host = rule.trim().substringBefore(':').removePrefix(".")
            host == "*" || host.isNotEmpty() && (uri.host == host || uri.host.endsWith(".$host"))
        }) return listOf(Proxy.NO_PROXY)
        val raw = if (uri.scheme == "https") System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy")
            else System.getenv("HTTP_PROXY") ?: System.getenv("http_proxy")
        if (raw.isNullOrBlank()) return listOf(Proxy.NO_PROXY)
        val proxy = URI.create(raw)
        require(proxy.scheme == "http" && proxy.userInfo == null && proxy.host != null) { "安装代理必须为无凭据 HTTP 代理" }
        return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.host, if (proxy.port < 0) 80 else proxy.port)))
    }
    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) = Unit
}
