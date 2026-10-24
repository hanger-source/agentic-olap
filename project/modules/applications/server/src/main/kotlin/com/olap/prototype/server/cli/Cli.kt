package com.olap.prototype.server.cli

import com.olap.prototype.server.auth.BearerVerifier
import com.olap.prototype.server.config.*
import com.olap.prototype.server.http.ExternalJson
import com.olap.prototype.server.installation.InstalledResources
import java.nio.file.Path
import java.time.Duration

object Cli {
    fun run(arguments: Array<String>, version: String, start: (Path, Deployment) -> Unit): Int {
        if (arguments.contentEquals(arrayOf("--version"))) { println(version); return 0 }
        if (arguments.isEmpty() || arguments[0] in setOf("--help", "-h")) { println("用法：agentic-olap <start|info|token|tools|call|sdk|connectors> [--home 目录] [--config 文件] [--port 端口] [--url MCP地址 --token-env 环境变量] [--payload JSON]"); return if (arguments.isEmpty()) 1 else 0 }
        val command = arguments[0]
        require(command in setOf("start", "info", "token", "tools", "call", "sdk", "connectors")) { "unknown command: $command" }
        var tool: String? = null
        var index = 1
        if (command == "call" && arguments.getOrNull(index)?.startsWith('-') == false) tool = arguments[index++]
        val options = linkedMapOf<String, String>()
        while (index < arguments.size) {
            val key = arguments[index++]
            if (key in setOf("--help", "-h")) { println("$command: --home <目录> | --url <MCP> --token-env <变量>；call <工具> --payload <完整JSON>；start [--config <文件> | --home <目录> --port <端口>]；sdk [--repository]"); return 0 }
            require(key in setOf("--home", "--config", "--port", "--url", "--token-env", "--timeout-seconds", "--payload", "--repository")) { "unknown option: $key" }
            require(key !in options) { "duplicate option: $key" }
            options[key] = if (key == "--repository") "true" else arguments.getOrNull(index++) ?: error("option requires a value: $key")
        }
        val installed = InstalledResources.root()
        fun print(value: Any?) { println(ExternalJson.mapper.writeValueAsString(value)) }
        if (command in setOf("sdk", "connectors")) {
            require(options.keys.all { command == "sdk" && it == "--repository" })
            val resources = InstalledResources(installed, version)
            if (command == "connectors") print(resources.connectors()) else if ("--repository" in options) println(resources.sdk()["mavenRepository"]) else print(resources.sdk())
            return 0
        }
        val home = LocalHome.home(options["--home"])
        val timeout = options["--timeout-seconds"]?.toDouble() ?: 120.0
        require(timeout.isFinite() && timeout > 0)
        if (command == "start") {
            require(options.keys.all { it in setOf("--home", "--config", "--port") })
            val port = options["--port"]?.toInt() ?: 0
            require(port in 0..65535)
            val (path, config) = options["--config"]?.let { name ->
                require("--home" !in options && "--port" !in options)
                Path.of(name).toAbsolutePath().normalize().let { it to Deployment.read(it) }
            } ?: LocalHome.initialize(home, installed.resolve("server.example.json"), port)
            print(LocalHome.information(path, config))
            start(path, config)
            return 0
        }
        require("--config" !in options && "--port" !in options)
        if (command in setOf("info", "token")) {
            require(options.keys.all { it in setOf("--home", "--timeout-seconds") })
            val (path, config) = LocalHome.initialized(home)
            val token = BearerVerifier.readLocalToken(Path.of(config.auth.tokenFile))
            if (command == "token") { println(token); return 0 }
            val information = LocalHome.information(path, config)
            information["installation"] = mapOf("version" to version, "packageRoot" to installed.toString(), "runtimeRoot" to installed.toString(), "javaHome" to System.getProperty("java.home"))
            var succeeded = true
            information["service"] = try {
                McpClient(config.url, token, version, Duration.ofMillis((timeout * 1000).toLong())).use { client ->
                    client.information().also { if (it["versionMatchesInstalled"] != true) succeeded = false }
                }
            } catch (failure: Exception) { succeeded = false; mapOf("reachable" to false, "error" to (failure.message ?: failure.javaClass.simpleName)) }
            print(information)
            return if (succeeded) 0 else 1
        }
        require(("--url" in options) == ("--token-env" in options) && !("--url" in options && "--home" in options))
        val (url, token) = if ("--url" in options) options.getValue("--url") to (System.getenv(options.getValue("--token-env"))?.takeIf(String::isNotBlank) ?: error("explicit bearer environment variable is unset"))
        else LocalHome.initialized(home).second.let { it.url to BearerVerifier.readLocalToken(Path.of(it.auth.tokenFile)) }
        return McpClient(url, token, version, Duration.ofMillis((timeout * 1000).toLong())).use { client ->
            if (command == "tools") { print(client.tools()); 0 } else {
                require(tool != null && options["--payload"] != null) { "call requires tool and complete --payload JSON" }
                val payload = ExternalJson.mapper.readTree(options.getValue("--payload"))
                require(payload.isObject)
                val result = client.call(requireNotNull(tool), payload)
                print(result)
                val envelope = result.path("content").firstOrNull()?.path("text")?.asText()?.let(ExternalJson.mapper::readTree)
                if (result.path("isError").asBoolean() || envelope?.path("success")?.asBoolean() != true) 1 else 0
            }
        }
    }
}
