package com.olap.prototype.server.process

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.util.OlapJsonUtils
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

internal class JvmDistribution(directory: Path) {
    val root = directory.toRealPath()
    private val manifest = OlapJsonUtils.mapper().readTree(root.resolve("distribution.json").toFile()).also {
        require(it.path("formatVersion").asText() == "agentic-olap-distribution/v4") { "unsupported JVM distribution" }
    }
    val java = root.resolve("jre/bin/java").toRealPath().also { require(it.startsWith(root) && Files.isExecutable(it)) { "installed JRE is required" } }
    val workerMain = main(manifest.path("worker"), "com.olap.prototype.runtime.ApplicationMainKt")
    val worker = classpath(manifest.path("worker").path("classpath"))
    val scriptMain = main(manifest.path("scripting").path("runtime"), "com.olap.prototype.runtime.ScriptMainKt")
    val script = classpath(manifest.path("scripting").path("runtime").path("classpath"))
    val dsl = classpath(manifest.path("scripting").path("dsl"))
    val dslVersion = manifest.path("scripting").path("dslApiVersion").asText().also {
        require(it == "agentic-olap-kotlin-dsl/v6") { "exact deployed DSL API is required" }
    }
    private fun main(node: JsonNode, expected: String) = node.path("mainClass").asText().also {
        require(it == expected) { "unexpected JVM distribution entry point" }
    }
    private fun classpath(node: JsonNode): List<Path> {
        require(node.isArray && !node.isEmpty) { "explicit distribution classpath is required" }
        val paths = node.map { artifact ->
            val name = artifact.path("path").asText()
            val relative = Path.of(name)
            require(!relative.isAbsolute && relative.normalize() == relative && name.startsWith("java/lib/") &&
                name.endsWith(".jar") && '\\' !in name) { "artifact must name an exact java/lib JAR" }
            val file = root.resolve(relative).toRealPath()
            require(file.startsWith(root) && Files.isRegularFile(file, NOFOLLOW_LINKS)) { "artifact escapes distribution or is not regular" }
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val size = stream.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            }
            require(HexFormat.of().formatHex(digest.digest()) == artifact.path("sha256").asText()) { "artifact hash mismatch: $name" }
            file
        }
        require(paths.distinct().size == paths.size) { "distribution classpath repeats an artifact" }
        return paths
    }
}
