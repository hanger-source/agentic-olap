import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File
import java.net.URI
import java.net.ProxySelector
import java.net.InetSocketAddress
import java.net.http.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.security.MessageDigest
import java.time.Duration

/** 发行装配：消费 Gradle 的真实解析结果，不维护第二份模块或依赖清单。 */
abstract class ProductDistribution : DefaultTask() {
    @get:InputFile abstract val manifest: RegularFileProperty
    @get:InputFile abstract val bootstrapJar: RegularFileProperty
    @get:Input abstract val published: Property<Boolean>

    @TaskAction fun assembleProduct() {
        val build = readObject(manifest.get().asFile)
        val workspace = project.rootDir.parentFile
        val version = build.text("version")
        val output = project.layout.buildDirectory.dir(if (published.get()) "npm" else "distribution").get().asFile
        project.delete(output)
        output.mkdirs()
        val root = if (published.get()) output.resolve("package") else output
        root.mkdirs()
        val modules = build.objects("modules")
        val external = build.objects("externalArtifacts").associateBy { it.obj("file").text("path") }
        val applications = build.obj("applications")
        val installed = linkedMapOf<String, MutableMap<String, Any?>>()
        val downloads = linkedMapOf<String, MutableMap<String, Any?>>()
        val filenames = mutableMapOf<String, String>()
        val clientBuilder = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(60))
        (System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy"))?.takeIf { it.isNotBlank() }?.let { raw ->
            val proxy = URI.create(raw)
            require(proxy.scheme == "http" && proxy.host != null && proxy.userInfo == null) { "Maven 装配代理必须为无凭据 HTTP 代理" }
            clientBuilder.proxy(ProxySelector.of(InetSocketAddress(proxy.host, if (proxy.port < 0) 80 else proxy.port)))
        }
        val client = clientBuilder.build()

        fun install(identity: Map<String, Any?>, location: String): Map<String, String> {
            val local = File(identity.text("path"))
            require(local.isFile && fileHash(local) == identity.text("sha256")) { "构件不同于本轮 Gradle 记录：$local" }
            val module = modules.singleOrNull { it.obj("jar").text("path") == local.path }
            var actual = local
            val coordinate: String
            val url: String
            if (module != null) {
                val classifier = if (module.text("category") == "connector") ":plugin" else ""
                coordinate = "${module.text("groupId")}:${module.text("artifactId")}:${module.text("version")}$classifier"
                url = "https://jitpack.io/${module.text("groupId").replace('.', '/')}/${module.text("artifactId")}/${module.text("version")}/${local.name}"
                if (published.get()) {
                    actual = output.resolve("maven/${local.name}")
                    actual.parentFile.mkdirs()
                    val response = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(30)).GET().build(),
                        HttpResponse.BodyHandlers.ofFile(actual.toPath()))
                    require(response.statusCode() == 200 && actual.length() > 0) { "JitPack 构件不可读取：$coordinate HTTP ${response.statusCode()}" }
                }
            } else {
                val artifact = external[local.path] ?: error("运行构件没有正式 Maven 来源：$local")
                coordinate = artifact.text("coordinate")
                url = artifact.text("url")
            }
            val hash = fileHash(actual)
            filenames[location]?.let { require(it == hash) { "安装构件文件名冲突：$location" } }
            filenames[location] = hash
            if (published.get()) {
                val download = downloads.getOrPut(hash) { linkedMapOf("coordinate" to coordinate, "url" to url,
                    "sha256" to hash, "size" to actual.length(), "locations" to mutableListOf<String>()) }
                @Suppress("UNCHECKED_CAST")
                val locations = download.getValue("locations") as MutableList<String>
                if (location !in locations) locations += location
            } else actual.copyTo(root.resolve(location).apply { parentFile.mkdirs() }, overwrite = true)
            installed[local.path] = linkedMapOf("path" to location, "sha256" to hash)
            return mapOf("path" to location, "sha256" to hash)
        }
        fun application(name: String): Map<String, Any?> {
            val value = applications.obj(name)
            return mapOf("mainClass" to value.text("mainClass"), "classpath" to value.objects("classpath").map {
                installed[it.text("path")] ?: install(it, "java/lib/${File(it.text("path")).name}")
            })
        }
        val server = application("server")
        val worker = application("worker")
        val script = application("script")
        val dsl = build.objects("dsl").map { installed[it.text("path")] ?: install(it, "java/lib/${File(it.text("path")).name}") }
        val distribution = mapOf("formatVersion" to "agentic-olap-distribution/v4", "version" to version,
            "server" to server, "worker" to worker,
            "scripting" to mapOf("dslApiVersion" to "agentic-olap-kotlin-dsl/v11", "runtime" to script, "dsl" to dsl))
        write(root.resolve("distribution.json"), distribution)
        val pluginManifests = mutableListOf<String>()
        for (id in listOf("h2", "duckdb")) {
            val module = modules.single { it.text("artifactId") == "connector-$id" }
            val group = "connectors/$id"
            val jars = listOf(module.obj("jar")) + build.obj("drivers").objects("connector-$id")
            val files = jars.map { install(it, "$group/${File(it.text("path")).name}") }
            val hash = MessageDigest.getInstance("SHA-256")
            files.forEach { identity ->
                hash.update(File(identity.getValue("path")).name.toByteArray(Charsets.UTF_8)); hash.update(0)
                hash.update(java.util.HexFormat.of().parseHex(identity.getValue("sha256")))
            }
            val path = "$group/connector.json"
            write(root.resolve(path), mapOf("connectorRef" to mapOf("id" to id, "version" to "1.0.0", "sha256" to hex(hash.digest())),
                "apiVersion" to build.text("connectorApiVersion"), "jars" to files.map { mapOf("file" to File(it.getValue("path")).name, "sha256" to it.getValue("sha256")) }))
            pluginManifests += path
        }
        val sdk = modules.filter { it.text("category") == "kernel" || it.text("artifactId") == "connector-loader" }
            .associate { it.text("artifactId") to "${it.text("groupId")}:${it.text("artifactId")}:${it.text("version")}" }
        write(root.resolve("resources.json"), mapOf("version" to version, "connectorApiVersion" to build.text("connectorApiVersion"),
            "sdk" to "sdk", "sdkRepository" to "https://jitpack.io", "sdkArtifacts" to sdk, "connectors" to pluginManifests))
        listOf("API.md" to "project/modules/kernel/connector-api/README.md", "SOURCE-ACCESS.md" to "docs/source-access.md",
            "LOADER.md" to "project/modules/applications/connector-loader/README.md").forEach { (name, path) ->
            root.resolve("sdk/$name").apply { parentFile.mkdirs(); writeText(workspace.resolve(path).readText()
                .replace("../../../../docs/source-access.md", "SOURCE-ACCESS.md")
                .replace("../../applications/connector-loader/README.md", "LOADER.md")
                .replace("../project/modules/kernel/connector-api/README.md", "API.md")
                .replace("../../kernel/connector-api/README.md", "API.md")) }
        }
        workspace.resolve("LICENSE").copyTo(root.resolve("LICENSE"), overwrite = true)
        workspace.resolve("skills/agentic-olap/references/server.example.json").copyTo(root.resolve("server.example.json"), overwrite = true)
        project.rootDir.resolve("cli/README.md").copyTo(root.resolve("README.md"), overwrite = true)
        project.rootDir.resolve("cli/bin/agentic-olap").copyTo(root.resolve("runtime/agentic-olap").apply { parentFile.mkdirs() }, overwrite = true)
        require(root.resolve("runtime/agentic-olap").setExecutable(true, false)) { "启动入口无法设置可执行权限" }
        bootstrapJar.get().asFile.copyTo(root.resolve("runtime/agentic-olap-bootstrap.jar"), overwrite = true)
        project.rootDir.resolve("build/runtime-licenses").copyRecursively(root.resolve("licenses/jvm"), overwrite = true)
        project.rootDir.resolve("cli/package.json").copyTo(root.resolve("package.json"), overwrite = true)
        val jre = root.resolve("jre")
        val image = temporaryDir.resolve("jre-image")
        project.delete(image)
        val javaHome = System.getenv("JAVA_HOME") ?: System.getProperty("java.home")
        execute(listOf(File(javaHome).resolve("bin/jlink").path, "--add-modules", "java.se,jdk.unsupported,jdk.crypto.ec", "--strip-debug",
            "--no-header-files", "--no-man-pages", "--compress=2", "--output", image.path), workspace)
        materializeJre(image.toPath(), jre.toPath())
        require(jre.resolve("bin/java").canExecute()) { "产品缺少包内 JRE" }
        if (published.get()) {
            val tag = "v$version"
            val head = output(listOf("git", "rev-parse", "HEAD"), workspace)
            require(head == output(listOf("git", "rev-parse", "$tag^{commit}"), workspace)) { "npm 包必须使用同版精确发行标签" }
            require(System.getProperty("os.name").contains("Mac") && System.getProperty("os.arch") == "aarch64") { "npm 产品仅装配 macOS arm64" }
            write(root.resolve("jvm-dependencies.json"), mapOf("formatVersion" to "agentic-olap-jvm-dependencies/v2", "version" to version, "artifacts" to downloads.values.toList()))
            write(root.resolve("build.json"), mapOf("version" to version, "target" to "darwin-arm64", "revision" to head,
                "files" to root.walkTopDown().filter { it.isFile && it != root.resolve("build.json") }.associate { it.relativeTo(root).invariantSeparatorsPath to fileHash(it) }))
            val tarballs = output.resolve("tarballs").apply { mkdirs() }
            execute(listOf("npm", "pack", "--quiet", root.path, "--pack-destination", tarballs.path), workspace)
            val archive = tarballs.resolve("hanger-source-agentic-olap-$version.tgz")
            require(archive.isFile) { "npm pack 未生成产品包" }
            tarballs.resolve("SHA256SUMS").writeText("${fileHash(archive)}  ${archive.name}\n")
        } else {
            // 研发发行已经具备全部本地构件；使用与 postinstall 相同的精确启动参数格式。
            val classpath = (server.getValue("classpath") as List<*>).map { @Suppress("UNCHECKED_CAST") val value = it as Map<String, String>; root.resolve(value.getValue("path")).absolutePath }
            root.resolve("runtime/server.args").writeText(listOf("-cp", classpath.joinToString(File.pathSeparator), server.getValue("mainClass").toString())
                .joinToString("\n", postfix = "\n") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" })
            write(root.resolve("runtime/installed.json"), mapOf("version" to version, "development" to true))
        }
        logger.lifecycle("${if (published.get()) "npm 产品包" else "本地 JVM 发行"}：$output")
    }
}

/** npm 只携带普通文件和目录；镜像内的链接按真实目标物化，不把链接带入归档。 */
private fun materializeJre(image: Path, destination: Path) {
    val sourceRoot = image.toRealPath()
    fun copy(source: Path, target: Path, ancestors: Set<Path>) {
        // toRealPath 同时拒绝悬空链接；每次递归都校验，链接到链接也不能离开所选镜像。
        val resolved = source.toRealPath()
        require(resolved.startsWith(sourceRoot)) { "JRE 链接目标离开所选镜像：$source" }
        when {
            Files.isDirectory(resolved, NOFOLLOW_LINKS) -> {
                require(resolved !in ancestors) { "JRE 目录链接形成循环：$source" }
                Files.createDirectory(target)
                Files.newDirectoryStream(resolved).use { children ->
                    children.forEach { child -> copy(child, target.resolve(child.fileName.toString()), ancestors + setOf(resolved)) }
                }
                // 子项写完后再收齐原目录权限，不为复制临时放宽来源权限。
                Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(resolved, NOFOLLOW_LINKS))
            }
            Files.isRegularFile(resolved, NOFOLLOW_LINKS) -> Files.copy(resolved, target, COPY_ATTRIBUTES)
            else -> error("JRE 镜像只允许普通文件和目录：$source")
        }
    }
    copy(sourceRoot, destination, emptySet())
}

@Suppress("UNCHECKED_CAST")
private fun readObject(file: File) = JsonSlurper().parse(file) as Map<String, Any?>
private fun Map<String, Any?>.text(key: String) = getValue(key) as String
@Suppress("UNCHECKED_CAST") private fun Map<String, Any?>.obj(key: String) = getValue(key) as Map<String, Any?>
@Suppress("UNCHECKED_CAST") private fun Map<String, Any?>.objects(key: String) = getValue(key) as List<Map<String, Any?>>
private fun write(file: File, value: Any) { file.parentFile.mkdirs(); file.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(value)) + "\n") }
private fun hex(bytes: ByteArray) = java.util.HexFormat.of().formatHex(bytes)
private fun fileHash(file: File): String = file.inputStream().use { input ->
    val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
    while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }; hex(hash.digest())
}
private fun execute(command: List<String>, directory: File) {
    require(ProcessBuilder(command).directory(directory).inheritIO().start().waitFor() == 0) { "命令失败：${command.first()}" }
}
private fun output(command: List<String>, directory: File): String {
    val process = ProcessBuilder(command).directory(directory).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val value = process.inputStream.bufferedReader().use { it.readText() }.trim()
    require(process.waitFor() == 0) { "命令失败：${command.first()}" }; return value
}
