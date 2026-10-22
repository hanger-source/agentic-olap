package com.olap.prototype.scripting

import java.nio.file.Files
import java.nio.file.Path

/** 只接受装配方明确列出的 DSL 依赖；不搜索当前进程或用户 Maven 仓库。 */
class ScriptEnvironment(
    val dslApiVersion: String,
    classpath: List<Path>,
    val limits: ScriptLimits = ScriptLimits(),
) {
    val classpath: List<Path> = classpath.map { it.toRealPath() }.distinct().toList()

    init {
        require(dslApiVersion.isNotBlank()) { "DSL API version is required" }
        require(KotlinVersion.CURRENT.toString() == COMPILER_VERSION) {
            "script Kotlin runtime must match compiler $COMPILER_VERSION"
        }
        require(this.classpath.isNotEmpty()) { "explicit DSL compilation classpath is required" }
        this.classpath.forEach { path ->
                require(Files.isRegularFile(path) && path.fileName.toString().endsWith(".jar")) {
                    "DSL compilation dependencies must be explicit JAR files: $path"
                }
        }
    }

    companion object {
        const val COMPILER_VERSION = "2.2.21"
        const val JVM_TARGET = "21"
    }
}
