package com.olap.prototype.server.config

import com.olap.prototype.server.ServerAuthorization
import com.olap.prototype.server.auth.BearerVerifier
import com.olap.prototype.server.http.ExternalJson
import com.olap.prototype.server.results.PrivateFiles
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64

object LocalHome {
    fun home(value: String?): Path {
        val user = Path.of(System.getProperty("user.home"))
        return when { value.isNullOrBlank() -> user.resolve(".agentic-olap"); value == "~" -> user; value.startsWith("~/") -> user.resolve(value.substring(2)); else -> Path.of(value) }.toAbsolutePath().normalize()
    }
    fun initialize(root: Path, template: Path, port: Int): Pair<Path, Deployment> {
        PrivateFiles.directory(root)
        val path = root.resolve("server.json")
        if (!Files.exists(path)) {
            val source = Deployment.read(template)
            val operations = source.auth.grants.flatMap { it.operations }.distinct()
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
            val tokenPath = root.resolve("access-token")
            PrivateFiles.write(tokenPath, "$token\n".toByteArray())
            val config = source.copy(listen = Deployment.Listen("127.0.0.1", if (port == 0) 8000 else port,
                listOf("127.0.0.1", "127.0.0.1:*", "localhost", "localhost:*"), listOf("http://127.0.0.1:*", "http://localhost:*")),
                auth = Deployment.Authentication("local-token", tokenPath.toString(), "local", listOf("olap.local"), grants = listOf(
                    ServerAuthorization.Grant(listOf("local"), listOf("olap.local"), operations, listOf("*"), listOf("*"), listOf("*"), true))),
                dataRoot = root.resolve("data").toString(), connectors = emptyList(), dataSources = emptyList())
            PrivateFiles.write(path, ExternalJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(config))
        }
        var (_, config) = initialized(root)
        if (port != 0 && port != config.listen.port) {
            config = config.copy(listen = config.listen.copy(port = port))
            PrivateFiles.write(path, ExternalJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(config), true)
        }
        return path to config
    }
    fun initialized(root: Path): Pair<Path, Deployment> {
        val path = root.resolve("server.json")
        val config = Deployment.read(path)
        require(config.auth.mode == "local-token") { "local home requires local-token; use start --config for external authentication" }
        BearerVerifier.readLocalToken(Path.of(config.auth.tokenFile))
        return path to config
    }
    fun information(path: Path, config: Deployment): MutableMap<String, Any?> = linkedMapOf("url" to config.url, "configPath" to path.toString(),
        "dataRoot" to config.dataRoot, "tokenPath" to config.auth.tokenFile.takeIf { config.auth.mode == "local-token" }, "authMode" to config.auth.mode)
}
