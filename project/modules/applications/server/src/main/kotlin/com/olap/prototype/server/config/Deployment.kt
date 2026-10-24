package com.olap.prototype.server.config

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.server.ServerAuthorization
import com.olap.prototype.server.http.ExternalJson
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.readBytes

/** 部署预算是唯一运行政策；不附加另一套写死的 worker/调用/深度上限。 */
data class Deployment(val listen: Listen, val auth: Authentication, val dataRoot: String, val connectors: List<String>,
    val dataSources: List<JsonNode>, val limits: Limits, val history: History, val scripting: Scripting) {
    data class Listen(val host: String, val port: Int, val allowedHosts: List<String>, val allowedOrigins: List<String>)
    data class Authentication(val mode: String, val tokenFile: String = "", val subject: String = "", val scopes: List<String> = emptyList(),
        val issuer: String = "", val audience: String = "", val jwksUri: String = "", val algorithms: List<String> = emptyList(),
        val grants: List<ServerAuthorization.Grant>)
    data class Limits(val requestSeconds: Double, val startupSeconds: Double, val maxInFlight: Int, val workerCount: Int,
        val catalogEntriesPerWorker: Int, val requestBytes: Int, val inputDepth: Int, val inputNodes: Int, val inputFileBytes: Int,
        val inputFiles: Int, val responseBytes: Int, val pendingOutputBytes: Int, val workerJvmHeapMiB: Int, val nativeMemoryBytes: Long,
        val catalogBytes: Long, val sqlBytes: Int, val parameters: Int, val resultRows: Long, val resultValues: Long, val resultBytes: Long, val resultDepth: Int)
    data class History(val enabled: Boolean, val retentionDays: Int)
    data class Scripting(val workerCount: Int, val jvmHeapMiB: Int, val maxCalls: Int, val nativeLogBytes: Long)
    init {
        require(listen.host.isNotBlank() && listen.port in 1..65535 && listen.allowedHosts.isNotEmpty() && dataRoot.isNotBlank())
        when (auth.mode) {
            "local-token" -> {
                require(listen.host in setOf("127.0.0.1", "::1")) { "local token requires a loopback listener" }
                require(auth.tokenFile.isNotBlank() && auth.subject.isNotBlank() && auth.scopes.isNotEmpty())
                require(auth.issuer.isEmpty() && auth.audience.isEmpty() && auth.jwksUri.isEmpty() && auth.algorithms.isEmpty())
            }
            "external-jwt" -> {
                require(auth.tokenFile.isEmpty() && auth.subject.isEmpty() && auth.scopes.isEmpty())
                listOf(auth.issuer, auth.audience, auth.jwksUri).forEach { endpoint ->
                    val uri = URI(endpoint)
                    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null)
                }
                require(auth.algorithms.isNotEmpty() && auth.algorithms.all { it in setOf("RS256", "RS384", "RS512", "ES256", "ES384") })
            }
            else -> error("explicit authentication mode is required")
        }
        auth.grants.forEach { require(it.subjects.isNotEmpty() && it.scopes.isNotEmpty() && it.operations.isNotEmpty()) }
        require(limits.requestSeconds.isFinite() && limits.requestSeconds > 0 && limits.startupSeconds.isFinite() && limits.startupSeconds > 0)
        require(listOf(limits.maxInFlight, limits.workerCount, limits.catalogEntriesPerWorker, limits.requestBytes, limits.inputDepth,
            limits.inputNodes, limits.inputFileBytes, limits.inputFiles, limits.responseBytes, limits.workerJvmHeapMiB,
            limits.sqlBytes, limits.parameters, limits.resultDepth).all { it > 0 })
        require(listOf(limits.catalogBytes, limits.resultRows, limits.resultValues, limits.resultBytes).all { it > 0 })
        require(limits.pendingOutputBytes >= limits.responseBytes && limits.nativeMemoryBytes > 0 && history.retentionDays > 0)
        require(scripting.workerCount > 0 && scripting.jvmHeapMiB > 0 && scripting.maxCalls > 0 && scripting.nativeLogBytes > 0)
        require(dataSources.isEmpty() || connectors.isNotEmpty())
        dataSources.forEach { require(it.path("dataSourceId").isTextual && it.path("dataSourceId").asText().isNotBlank()) }
    }
    val url: String get() = "http://${if (listen.host.contains(':')) "[${listen.host}]" else listen.host}:${listen.port}/mcp"
    fun json(): JsonNode = ExternalJson.mapper.valueToTree(this)
    companion object {
        fun read(path: Path): Deployment {
            val tree = ExternalJson.mapper.readTree(path.readBytes())
            listOf("listen", "auth", "dataRoot", "connectors", "dataSources", "limits", "history", "scripting").forEach { require(tree.hasNonNull(it)) { "configuration requires $it" } }
            require(tree.path("history").path("enabled").isBoolean && tree.path("auth").path("grants").isArray)
            val value = ExternalJson.mapper.treeToValue(tree, Deployment::class.java)
            val parent = path.toAbsolutePath().normalize().parent
            fun absolute(value: String) = parent.resolve(value).normalize().toString()
            return value.copy(dataRoot = absolute(value.dataRoot), connectors = value.connectors.map(::absolute),
                auth = if (value.auth.mode == "local-token") value.auth.copy(tokenFile = absolute(value.auth.tokenFile)) else value.auth)
        }
    }
}
