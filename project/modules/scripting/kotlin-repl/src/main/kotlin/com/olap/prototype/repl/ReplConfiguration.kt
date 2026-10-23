package com.olap.prototype.repl

import java.nio.file.Files
import java.nio.file.Path

/**
 * 会话创建时固定的语言环境。不搜索宿主 classpath，也不在求值期间替换依赖。
 * [parentClassLoader] 由装配方持有，正式宿主类型必须由这个稳定的加载环境提供。
 */
class ReplConfiguration(
    classpath: List<Path>,
    defaultImports: List<String>,
    val jvmTarget: String,
    val parentClassLoader: ClassLoader,
) {
    val classpath: List<Path> = java.util.List.copyOf(classpath.map { it.toRealPath() }.distinct())
    val defaultImports: List<String> = java.util.List.copyOf(defaultImports)

    init {
        require(KotlinVersion.CURRENT.toString() == KOTLIN_VERSION) {
            "Kotlin REPL runtime must match compiler $KOTLIN_VERSION"
        }
        require(this.classpath.isNotEmpty()) { "an explicit Kotlin compilation classpath is required" }
        this.classpath.forEach { path ->
            require(Files.isDirectory(path) || Files.isRegularFile(path) && path.fileName.toString().endsWith(".jar")) {
                "Kotlin compilation dependencies must be explicit JAR files or class directories: $path"
            }
        }
        require(this.defaultImports.none(String::isBlank)) { "default imports must not be blank" }
        require(jvmTarget.isNotBlank()) { "a fixed JVM target is required" }
    }

    companion object {
        const val KOTLIN_VERSION = "2.2.21"
    }
}
