package com.olap.prototype.server.responses

import com.olap.prototype.server.ServerAuthorization
import com.olap.prototype.server.http.ExternalJson
import com.olap.prototype.server.results.PrivateFiles
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardOpenOption.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.channels.FileChannel
import java.nio.file.attribute.PosixFilePermissions
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/** 完整传输消息的独立存档；不制造语义资产，不限制累计磁盘容量。 */
class ResponseFiles(dataRoot: Path) {
    class AuthorizationChanged : SecurityException("saved response subject, scopes or authorization policy differs from this request")
    data class Reference(val responseId: String, val sha256: String) {
        init { require(responseId.matches(Regex("response-[0-9a-f]{32}")) && sha256.matches(Regex("[0-9a-f]{64}"))) }
    }
    data class Metadata(val subject: String, val scopes: List<String>, val authorizationPolicyHash: String,
        val operation: String, val requestId: String, val originalSuccess: Boolean, val bytes: Long)
    data class Record(val format: String, val reference: Reference, val metadata: Metadata)
    data class Page(val reference: Reference, val content: String, val offset: Long, val nextOffset: Long?, val totalBytes: Long)
    private val directory = dataRoot.resolve("responses")
    fun capture(messageBytes: Int): Capture = Capture(messageBytes)
    /** 以最终 HTTP 消息字节计数；越界后只改变承载位置，继续接收全部内容。 */
    inner class Capture internal constructor(private val limit: Int) : OutputStream(), AutoCloseable {
        private val memory = ByteArrayOutputStream()
        private val digest = MessageDigest.getInstance("SHA-256")
        private var staging: Path? = null
        private var file: OutputStream? = null
        private var bytes = 0L
        private var closed = false
        val oversized: Boolean get() = staging != null
        fun inline(): ByteArray { check(!oversized); return memory.toByteArray() }
        fun protocolSucceeded(): Boolean {
            file?.flush()
            val input = staging?.let(Files::newInputStream) ?: memory.toByteArray().inputStream()
            return input.use { source -> ExternalJson.mapper.factory.createParser(source).use { parser ->
                require(parser.nextToken() == com.fasterxml.jackson.core.JsonToken.START_OBJECT)
                while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_OBJECT) {
                    val field = parser.currentName
                    parser.nextToken()
                    if (field == "error") return@use false
                    parser.skipChildren()
                }
                true
            } }
        }
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(content: ByteArray, offset: Int, length: Int) {
            check(!closed)
            if (file == null && bytes + length > limit) {
                PrivateFiles.directory(directory)
                staging = Files.createTempFile(directory, ".response-", ".pending", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                file = Files.newOutputStream(staging, WRITE)
                memory.writeTo(file)
                memory.reset()
            }
            if (file == null) memory.write(content, offset, length) else file!!.write(content, offset, length)
            digest.update(content, offset, length)
            bytes += length
        }
        fun commit(principal: ServerAuthorization.Principal, policy: String, operation: String, requestId: String, success: Boolean): Reference {
            check(!closed && oversized)
            require(policy.matches(Regex("[0-9a-f]{64}")))
            file!!.close()
            file = null
            val path = requireNotNull(staging)
            FileChannel.open(path, WRITE).use { it.force(true) }
            val reference = Reference("response-${UUID.randomUUID().toString().replace("-", "")}", digest.digest().joinToString("") { "%02x".format(it) })
            Files.createLink(location(reference), path)
            val metadata = Metadata(principal.subject, principal.scopes.distinct().sorted(), policy, operation, requestId, success, bytes)
            PrivateFiles.write(directory.resolve("${reference.responseId}.meta.json"), ExternalJson.mapper.writeValueAsBytes(Record("mcp-jsonrpc-response/v1", reference, metadata)))
            return reference
        }
        override fun close() { if (!closed) { closed = true; file?.close(); staging?.let(Files::deleteIfExists) } }
    }
    fun location(reference: Reference): Path = directory.resolve("${reference.responseId}.json")
    fun read(reference: Reference, principal: ServerAuthorization.Principal, policy: String, offset: Long, length: Int): Page {
        require(offset >= 0 && length > 0)
        val record = ExternalJson.mapper.readValue(PrivateFiles.read(directory.resolve("${reference.responseId}.meta.json")), Record::class.java)
        require(record.format == "mcp-jsonrpc-response/v1" && record.reference == reference)
        if (record.metadata.subject != principal.subject || record.metadata.scopes != principal.scopes.distinct().sorted() || record.metadata.authorizationPolicyHash != policy)
            throw AuthorizationChanged()
        val path = location(reference)
        require(Files.isRegularFile(path, NOFOLLOW_LINKS))
        return FileChannel.open(path, READ, NOFOLLOW_LINKS).use { file ->
            val total = file.size()
            require(total == record.metadata.bytes && offset <= total)
            if (offset == total) return@use Page(reference, "", offset, null, total)
            val size = minOf(length.toLong(), total - offset).toInt()
            val content = ByteArray(size)
            val buffer = ByteBuffer.wrap(content)
            var position = offset
            while (buffer.hasRemaining()) { val count = file.read(buffer, position); check(count > 0); position += count }
            require((content[0].toInt() and 0xc0) != 0x80) { "offset must follow a UTF-8 boundary" }
            var end = content.size
            if (offset + end < total) {
                val following = ByteBuffer.allocate(1)
                check(file.read(following, offset + end) == 1)
                if ((following.array()[0].toInt() and 0xc0) == 0x80) {
                    while (end > 0 && (content[end - 1].toInt() and 0xc0) == 0x80) end--
                    if (end > 0) end--
                }
            }
            require(end > 0) { "length cannot contain one complete UTF-8 character" }
            val decoder = UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = decoder.decode(ByteBuffer.wrap(content, 0, end)).toString()
            Page(reference, text, offset, (offset + end).takeIf { it < total }, total)
        }
    }
}
