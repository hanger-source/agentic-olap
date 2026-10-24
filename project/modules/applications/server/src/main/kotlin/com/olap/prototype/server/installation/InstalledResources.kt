package com.olap.prototype.server.installation

import com.olap.prototype.server.http.ExternalJson
import java.nio.file.Files
import java.nio.file.Path

/** 安装期间已经准备完整；任何 CLI 和启动入口都不会隐式下载依赖。 */
class InstalledResources(val root: Path, private val version: String) {
    private val resources = ExternalJson.mapper.readTree(Files.readAllBytes(root.resolve("resources.json")))
    init {
        require(resources.path("version").asText() == version && resources.path("connectorApiVersion").asText().isNotBlank())
        require(resources.path("sdkRepository").asText() == "https://jitpack.io" && resources.path("sdkArtifacts").isObject)
    }
    private fun path(relative: String): Path {
        val value = Path.of(relative)
        require(!value.isAbsolute && value.normalize() == value && value.nameCount > 0 && !value.startsWith(".."))
        return root.resolve(value).also { require(Files.exists(it)) { "npm 产品缺少资源 $relative；请重新安装完整产品" } }
    }
    fun sdk(): Map<String, Any> {
        val sdk = resources.required("sdk").asText()
        return mapOf("version" to version, "connectorApiVersion" to resources.path("connectorApiVersion").asText(), "root" to path(sdk).toString(),
            "mavenRepository" to resources.path("sdkRepository").asText(), "artifacts" to resources.required("sdkArtifacts"),
            "documents" to listOf("API.md", "SOURCE-ACCESS.md", "LOADER.md").associateWith { path("$sdk/$it").toString() })
    }
    fun connectors(): Map<String, Any> = mapOf("version" to version, "connectorApiVersion" to resources.path("connectorApiVersion").asText(),
        "connectors" to resources.required("connectors").map { entry ->
            val path = path(entry.asText())
            val manifest = ExternalJson.mapper.readTree(Files.readAllBytes(path))
            require(manifest.path("apiVersion") == resources.path("connectorApiVersion") && manifest.hasNonNull("connectorRef"))
            mapOf("manifestPath" to path.toString(), "connectorRef" to manifest.required("connectorRef"), "apiVersion" to manifest.path("apiVersion").asText())
        })
    companion object {
        fun root(): Path = System.getProperty("agentic.olap.home")?.let(Path::of)
            ?: error("安装入口必须提供精确 agentic.olap.home")
    }
}
