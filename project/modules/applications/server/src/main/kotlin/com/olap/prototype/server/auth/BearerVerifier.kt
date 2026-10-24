package com.olap.prototype.server.auth

import com.olap.prototype.server.ServerAuthorization
import com.olap.prototype.server.config.Deployment
import com.olap.prototype.server.http.ExternalJson
import java.math.BigInteger
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.*
import java.security.spec.*
import java.time.Duration
import java.time.Instant
import java.util.Base64

class BearerVerifier(private val config: Deployment.Authentication) : AutoCloseable {
    private val client = NetworkClient.create()
    private val local = if (config.mode == "local-token") readLocalToken(Path.of(config.tokenFile)) else null
    private var keys: Map<String, Pair<PublicKey, String?>> = emptyMap()
    private var refreshed = Instant.MIN
    fun verify(bearer: String): ServerAuthorization.Principal {
        if (local != null) {
            if (!MessageDigest.isEqual(local.toByteArray(), bearer.toByteArray())) throw SecurityException("invalid bearer")
            return ServerAuthorization.Principal(config.subject, config.scopes)
        }
        val parts = bearer.split('.')
        require(parts.size == 3)
        val decode = Base64.getUrlDecoder()
        val header = ExternalJson.mapper.readTree(decode.decode(parts[0]))
        val claims = ExternalJson.mapper.readTree(decode.decode(parts[1]))
        val algorithm = header.path("alg").asText()
        require(algorithm in config.algorithms && header.path("kid").isTextual)
        val key = signingKey(header.path("kid").asText(), algorithm)
        val signature = Signature.getInstance(when (algorithm) {
            "RS256" -> "SHA256withRSA"; "RS384" -> "SHA384withRSA"; "RS512" -> "SHA512withRSA"
            "ES256" -> "SHA256withECDSAinP1363Format"; "ES384" -> "SHA384withECDSAinP1363Format"
            else -> error("unsupported JWT algorithm")
        })
        signature.initVerify(key)
        signature.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        require(signature.verify(decode.decode(parts[2]))) { "invalid JWT signature" }
        val now = Instant.now().epochSecond
        require(claims.path("iss").asText() == config.issuer && claims.path("sub").isTextual && claims.path("sub").asText().isNotBlank())
        val audience = claims.path("aud")
        require((audience.isTextual && audience.asText() == config.audience) || (audience.isArray && audience.any { it.isTextual && it.asText() == config.audience }))
        require(claims.path("exp").isNumber && claims.path("exp").decimalValue() > now.toBigDecimal()) { "JWT expired or missing expiration" }
        if (claims.has("nbf")) require(claims.path("nbf").isNumber && claims.path("nbf").decimalValue() <= now.toBigDecimal())
        val scopes = claims.path("scope")
        require(scopes.isMissingNode || scopes.isTextual)
        return ServerAuthorization.Principal(claims.path("sub").asText(), scopes.asText("").split(Regex("\\s+")).filter(String::isNotBlank))
    }
    @Synchronized private fun signingKey(kid: String, algorithm: String): PublicKey {
        if (kid !in keys || Duration.between(refreshed, Instant.now()) >= Duration.ofMinutes(5)) {
            val document = ExternalJson.mapper.readTree(NetworkClient.getHTTPS(client, URI(config.jwksUri), Duration.ofSeconds(5)))
            val next = linkedMapOf<String, Pair<PublicKey, String?>>()
            document.path("keys").forEach { key ->
                if (key.path("kid").asText().isBlank() || key.path("use").asText("sig") != "sig") return@forEach
                val decode = Base64.getUrlDecoder()
                fun integer(name: String) = BigInteger(1, decode.decode(key.path(name).asText()))
                val publicKey = when (key.path("kty").asText()) {
                    "RSA" -> KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(integer("n"), integer("e")))
                    "EC" -> {
                        val curve = when (key.path("crv").asText()) { "P-256" -> "secp256r1"; "P-384" -> "secp384r1"; else -> return@forEach }
                        val parameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec(curve)) }.getParameterSpec(ECParameterSpec::class.java)
                        KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(integer("x"), integer("y")), parameters))
                    }
                    else -> return@forEach
                }
                check(next.put(key.path("kid").asText(), publicKey to key.get("alg")?.asText()) == null) { "ambiguous JWKS key" }
            }
            keys = next
            refreshed = Instant.now()
        }
        val (key, declared) = keys[kid] ?: throw SecurityException("JWT signing key unavailable")
        require(declared == null || declared == algorithm)
        require((algorithm.startsWith("RS") && key.algorithm == "RSA") || (algorithm.startsWith("ES") && key.algorithm == "EC"))
        if (algorithm.startsWith("ES")) require((key as java.security.interfaces.ECPublicKey).params.curve.field.fieldSize == if (algorithm == "ES256") 256 else 384)
        return key
    }
    override fun close() = client.close()
    companion object {
        fun readLocalToken(path: Path): String {
            require(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rw-------")) { "local token must be a private 0600 regular file" }
            val bytes = com.olap.prototype.server.results.PrivateFiles.read(path)
            val token = bytes.toString(Charsets.UTF_8).removeSuffix("\n")
            require(token.matches(Regex("[A-Za-z0-9_-]{43,255}"))) { "invalid local token file" }
            return token
        }
    }
}
