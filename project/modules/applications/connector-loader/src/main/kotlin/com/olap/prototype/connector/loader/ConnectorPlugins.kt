package com.olap.prototype.connector.loader

import com.olap.prototype.connector.*
import com.olap.prototype.util.OlapJsonUtils
import java.io.IOException
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.ServiceLoader
import java.util.jar.JarFile
import kotlin.streams.asSequence

/** 精确构件组独立装载；SPI、Calcite和Kotlin运行库均由宿主唯一提供。 */
class ConnectorPlugins private constructor(
    private val loaders: List<URLClassLoader>,
    connectors: List<ResolvedConnector>,
) : AutoCloseable {
    data class Artifact(val file: String, val sha256: String)
    data class Manifest(val connectorRef: ConnectorRef, val apiVersion: String, val jars: List<Artifact>)

    val registry = ConnectorRegistry(connectors)

    override fun close() {
        var failure: IOException? = null
        fun failed(cause: Exception) {
            failure = failure?.apply { addSuppressed(cause) }
                ?: IOException("cannot close connector resources", cause)
        }
        registry.references().forEach { reference ->
            try {
                registry.require(reference).access.close()
            } catch (cause: Exception) {
                failed(cause)
            }
        }
        loaders.forEach { loader ->
            try {
                loader.close()
            } catch (cause: Exception) {
                failed(cause)
            }
        }
        failure?.let { throw it }
    }

    private class PluginClassLoader(jars: Array<URL>) : URLClassLoader(jars, OlapConnector::class.java.classLoader) {
        override fun getResource(name: String): URL? = findResource(name)
        override fun getResources(name: String): java.util.Enumeration<URL> = findResources(name)

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (SHARED.any(name::startsWith)) return super.loadClass(name, resolve)
            synchronized(getClassLoadingLock(name)) {
                val type = findLoadedClass(name) ?: findClass(name)
                if (resolve) resolveClass(type)
                return type
            }
        }
    }

    companion object {
        private val SHARED = listOf("java.", "javax.", "jdk.", "sun.", "com.sun.",
            "org.w3c.dom.", "org.xml.sax.", "org.ietf.jgss.", "com.olap.prototype.",
            "org.apache.calcite.", "com.google.common.", "org.slf4j.", "kotlin.", "kotlinx.",
            "org.jetbrains.annotations.")
        private val FORBIDDEN_BUNDLED = listOf("com/olap/prototype/", "org/apache/calcite/", "kotlin/", "kotlinx/")

        fun load(manifests: List<Path>): ConnectorPlugins {
            val loaders = mutableListOf<URLClassLoader>()
            val connectors = mutableListOf<ResolvedConnector>()
            try {
                manifests.forEach { path ->
                    val manifest = OlapJsonUtils.mapper().readValue(Files.readAllBytes(path), Manifest::class.java)
                    require(manifest.apiVersion == OlapConnector.API_VERSION) { "connector API version mismatch: $path" }
                    require(manifest.jars.isNotEmpty()) { "plugin JAR group is empty" }
                    val root = path.toRealPath().parent
                    val artifacts = linkedMapOf<String, String>()
                    val urls = manifest.jars.map { artifact ->
                        require(Regex("[^/\\\\]+\\.jar").matches(artifact.file)) { "plugin JAR must be in its manifest directory" }
                        val jar = root.resolve(artifact.file).toRealPath()
                        require(jar.parent == root) { "plugin JAR escapes deployment group" }
                        val actual = hash(jar)
                        require(actual == artifact.sha256) { "plugin JAR hash mismatch: ${artifact.file}" }
                        require(artifact.file !in artifacts) { "duplicate plugin JAR" }
                        artifacts[artifact.file] = actual
                        JarFile(jar.toFile()).use { contents ->
                            require(contents.entries().asSequence().none { entry -> entry.name.endsWith(".class") &&
                                FORBIDDEN_BUNDLED.any(entry.name::startsWith) }) { "plugin bundles shared host classes: ${artifact.file}" }
                        }
                        jar.toUri().toURL()
                    }
                    require(groupHash(artifacts) == manifest.connectorRef.sha256) { "connector artifact group hash mismatch" }
                    val loader = PluginClassLoader(urls.toTypedArray())
                    loaders += loader
                    val providers = ServiceLoader.load(OlapConnector::class.java, loader).stream().use { providers ->
                        providers.asSequence().filter { it.type().classLoader === loader }.toList()
                    }
                    require(providers.size == 1) { "plugin must register exactly one connector provider" }
                    val connector = providers.single().get()
                    require(connector.id == manifest.connectorRef.id && connector.version == manifest.connectorRef.version &&
                        connector.apiVersion == manifest.apiVersion) { "connector provider differs from manifest" }
                    connectors += ResolvedConnector(manifest.connectorRef, connector.compilation, connector.access(manifest.connectorRef))
                }
                return ConnectorPlugins(loaders.toList(), connectors.toList())
            } catch (failure: Throwable) {
                connectors.forEach { connector ->
                    try {
                        connector.access.close()
                    } catch (close: Exception) {
                        failure.addSuppressed(close)
                    }
                }
                loaders.forEach { loader ->
                    try {
                        loader.close()
                    } catch (close: Exception) {
                        failure.addSuppressed(close)
                    }
                }
                throw failure
            }
        }

        fun groupHash(artifacts: Map<String, String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            artifacts.forEach { (file, hash) ->
                digest.update(file.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
                digest.update(HexFormat.of().parseHex(hash))
            }
            return HexFormat.of().formatHex(digest.digest())
        }

        private fun hash(file: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val bytes = ByteArray(65536)
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    digest.update(bytes, 0, count)
                }
            }
            return HexFormat.of().formatHex(digest.digest())
        }
    }
}
