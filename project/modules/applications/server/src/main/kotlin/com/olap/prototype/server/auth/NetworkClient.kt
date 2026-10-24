package com.olap.prototype.server.auth

import java.net.*
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.Duration
import javax.net.ssl.*

/** 服务自己的 TLS/代理配置，不改变来源插件的网络行为。 */
object NetworkClient {
    fun create(): HttpClient {
        val builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NEVER)
        System.getenv("SSL_CERT_FILE")?.takeIf { it.isNotBlank() }?.let { path ->
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            val defaults = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
            val roots = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            defaults.acceptedIssuers.forEachIndexed { index, certificate -> roots.setCertificateEntry("system-$index", certificate) }
            val extra = Files.newInputStream(Path.of(path)).use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
            require(extra.isNotEmpty()) { "configured TLS CA file contains no certificates" }
            extra.forEachIndexed { index, certificate -> roots.setCertificateEntry("configured-$index", certificate) }
            factory.init(roots)
            builder.sslContext(SSLContext.getInstance("TLS").apply { init(null, factory.trustManagers, null) })
        }
        builder.proxy(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                val bypass = (System.getenv("NO_PROXY") ?: System.getenv("no_proxy")).orEmpty().split(',').any { raw ->
                    val host = raw.trim().substringBefore(':')
                    host == "*" || host.isNotEmpty() && (uri.host.equals(host.trimStart('.'), true) || uri.host.endsWith(".${host.trimStart('.')}", true))
                }
                if (bypass || uri.host in setOf("localhost", "127.0.0.1", "::1")) return listOf(Proxy.NO_PROXY)
                val name = if (uri.scheme == "https") "HTTPS_PROXY" else "HTTP_PROXY"
                val address = (System.getenv(name) ?: System.getenv(name.lowercase()))?.takeIf { it.isNotBlank() } ?: return listOf(Proxy.NO_PROXY)
                val proxy = URI(address)
                require(proxy.scheme == "http" && proxy.userInfo == null && proxy.host != null) { "HTTP proxy requires a credential-free http URI" }
                return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.host, if (proxy.port < 0) 80 else proxy.port)))
            }
            override fun connectFailed(uri: URI?, address: SocketAddress?, failure: java.io.IOException?) { System.err.println("HTTP proxy connection failed: $uri: ${failure?.javaClass?.simpleName}") }
        })
        return builder.build()
    }
    fun getHTTPS(client: HttpClient, initial: URI, timeout: Duration): ByteArray {
        var address = initial
        repeat(6) { attempt ->
            require(address.scheme == "https" && address.userInfo == null) { "JWKS redirects must remain credential-free HTTPS" }
            val response = client.send(HttpRequest.newBuilder(address).timeout(timeout).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() in setOf(301, 302, 303, 307, 308)) {
                require(attempt < 5) { "too many JWKS redirects" }
                address = address.resolve(response.headers().firstValue("location").orElseThrow())
            } else { require(response.statusCode() == 200) { "JWKS unavailable" }; return response.body() }
        }
        error("JWKS redirect limit")
    }
}
