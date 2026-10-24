package com.olap.prototype.server

import com.olap.prototype.server.cli.Cli
import com.olap.prototype.server.http.ExternalJson
import com.olap.prototype.server.http.McpHttpService
import com.olap.prototype.server.installation.InstalledResources
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/** 安装后的唯一命令入口；正式服务与 CLI 使用同一份精确产品构件。 */
fun main(arguments: Array<String>) {
    val code = try {
        val root = InstalledResources.root().toRealPath()
        val manifest = Files.newInputStream(root.resolve("distribution.json")).use(ExternalJson.mapper::readTree)
        val version = manifest.required("version").textValue().also { require(!it.isNullOrBlank()) { "installed product version is required" } }
        Cli.run(arguments, version) { _, deployment ->
            ServerHost(deployment, root).use { application ->
                val http = McpHttpService(deployment, application, version)
                val stopping = AtomicBoolean()
                fun stop() {
                    if (stopping.compareAndSet(false, true)) {
                        try { http.close() } finally { application.close() }
                    }
                }
                val hook = Thread(::stop, "agentic-olap-shutdown")
                Runtime.getRuntime().addShutdownHook(hook)
                try { http.start(wait = true) } finally {
                    stop()
                    try { Runtime.getRuntime().removeShutdownHook(hook) }
                    catch (_: IllegalStateException) { /* JVM 已进入关闭，钩子由该生命周期拥有。 */ }
                }
            }
        }
    } catch (failure: Exception) {
        org.slf4j.LoggerFactory.getLogger("agentic-olap").error("command failed", failure)
        System.err.println(failure.message ?: failure.javaClass.simpleName)
        1
    }
    exitProcess(code)
}
