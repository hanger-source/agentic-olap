package com.olap.prototype.warehouse.contract

import com.olap.prototype.connector.ConnectorRef
import java.util.Locale

/** JDBC 瞬时连接描述。密码仅允许通过环境变量引用。 */
class JdbcConnectionSpec(
    dataSourceId: String,
    val connectorRef: ConnectorRef,
    dialectId: String,
    jdbcUrl: String,
    username: String?,
    passwordEnvironmentVariable: String?,
    driverClassName: String?,
) {
    val dataSourceId: String = text(dataSourceId, "dataSourceId")
    val dialectId: String = text(dialectId, "dialectId").lowercase(Locale.ROOT)
    val jdbcUrl: String = text(jdbcUrl, "jdbcUrl")
    val username: String? = normalize(username)
    val passwordEnvironmentVariable: String? = normalize(passwordEnvironmentVariable)
    val driverClassName: String? = normalize(driverClassName)

    init {
        val normalized = this.jdbcUrl.lowercase(Locale.ROOT)
        require(!Regex(".*[?;&](password|passwd|pwd|user|username|token|access_token)=.*").matches(normalized) &&
            !Regex(".*//[^/@]+:[^/@]+@.*").matches(normalized)) {
            "jdbcUrl 不允许内嵌用户名、密码或 token；请使用 username 和 passwordEnvironmentVariable"
        }
    }

    fun sourceIdentityHash(): String = source().sourceIdentityHash()

    fun source(): SourceConfiguration = SourceConfiguration(dataSourceId, connectorRef, dialectId,
        linkedMapOf<String, Any?>("jdbcUrl" to jdbcUrl).apply {
            username?.let { put("username", it) }
            driverClassName?.let { put("driverClassName", it) }
        }, emptyMap(), passwordEnvironmentVariable?.let { mapOf("password" to it) } ?: emptyMap())

    override fun equals(other: Any?): Boolean = other is JdbcConnectionSpec &&
        dataSourceId == other.dataSourceId && connectorRef == other.connectorRef && dialectId == other.dialectId &&
        jdbcUrl == other.jdbcUrl && username == other.username && passwordEnvironmentVariable == other.passwordEnvironmentVariable &&
        driverClassName == other.driverClassName
    override fun hashCode(): Int = listOf(dataSourceId, connectorRef, dialectId, jdbcUrl, username, passwordEnvironmentVariable, driverClassName).hashCode()

    companion object {
        fun from(source: SourceConfiguration): JdbcConnectionSpec {
            require(setOf("jdbcUrl", "username", "driverClassName").containsAll(source.identityParameters.keys) &&
                setOf("password").containsAll(source.credentialEnvironmentVariables.keys)) {
                "unsupported JDBC source parameter or credential slot"
            }
            require(source.identityParameters.values.all { it == null || it is String }) { "JDBC source parameters must be strings" }
            return JdbcConnectionSpec(source.dataSourceId, source.connectorRef, source.dialectId,
                requireNotNull(source.identityParameters["jdbcUrl"] as String?) { "jdbcUrl 不能为空" },
                source.identityParameters["username"] as String?, source.credentialEnvironmentVariables["password"],
                source.identityParameters["driverClassName"] as String?)
        }

        private fun text(value: String, label: String): String {
            require(value.isNotBlank()) { "$label 不能为空" }
            return value.trim()
        }
        private fun normalize(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
    }
}
