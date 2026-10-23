package com.olap.prototype.runtime

import com.google.protobuf.CodedInputStream
import com.olap.prototype.protocol.Frame
import com.olap.prototype.protocol.ProtocolFile
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

/** 只交接完整官方 Frame；目录由 Go 创建，文件引用不改变进程角色与领域消息。 */
internal class ProtocolFiles(directory: Path) {
    private val root = directory.toAbsolutePath().normalize().also {
        require(Files.isDirectory(it, NOFOLLOW_LINKS) && it == it.toRealPath()) { "private protocol transfer directory is required" }
    }

    fun outbound(frame: Frame, maximumPipeBytes: Int): Frame {
        require(!frame.hasFile()) { "protocol file references cannot be nested" }
        if (frame.serializedSize <= maximumPipeBytes) return frame
        val name = "frame-${UUID.randomUUID().toString().replace("-", "")}.pb"
        val destination = root.resolve(name)
        val partial = root.resolve("$name.part")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            DigestOutputStream(Files.newOutputStream(partial, CREATE_NEW, WRITE, NOFOLLOW_LINKS), digest).use(frame::writeTo)
            val size = Files.size(partial)
            Files.move(partial, destination, ATOMIC_MOVE)
            return Frame.newBuilder().setFile(ProtocolFile.newBuilder().setName(name).setBytes(size)
                .setSha256(hex(digest.digest()))).build()
        } catch (failure: Throwable) {
            Files.deleteIfExists(partial)
            throw failure
        }
    }

    fun read(input: InputStream, maximumPipeBytes: Int): Frame? {
        val frame = readPipeFrame(input, maximumPipeBytes) ?: return null
        return if (frame.hasFile()) consume(frame.file) else frame
    }

    private fun consume(reference: ProtocolFile): Frame {
        require(NAME.matches(reference.name) && reference.bytes > 0 && SHA256.matches(reference.sha256)) {
            "protocol file requires an exact private basename, byte length and SHA-256"
        }
        val path = root.resolve(reference.name)
        val before = attributes(path)
        require(before.isRegularFile && before.fileKey() != null && before.size() == reference.bytes) {
            "protocol transfer file differs from its declared regular file"
        }
        try {
            Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                val digest = MessageDigest.getInstance("SHA-256")
                DigestInputStream(Channels.newInputStream(channel), digest).use { content ->
                    val coded = CodedInputStream.newInstance(content).apply {
                        setSizeLimit(Int.MAX_VALUE)
                        setRecursionLimit(Int.MAX_VALUE)
                    }
                    val frame = Frame.parseFrom(coded)
                    coded.checkLastTagWas(0)
                    require(coded.isAtEnd && content.read() == -1) { "protocol transfer file has trailing bytes" }
                    val after = attributes(path)
                    require(after.isRegularFile && before.fileKey() == after.fileKey() && before.size() == after.size() &&
                        hex(digest.digest()) == reference.sha256) { "protocol transfer file identity or content changed" }
                    require(!frame.hasFile()) { "protocol file references cannot be nested" }
                    return frame
                }
            }
        } finally {
            try {
                val current = if (Files.exists(path, NOFOLLOW_LINKS)) attributes(path) else null
                if (current?.isRegularFile == true && current.fileKey() == before.fileKey()) Files.delete(path)
            } catch (failure: Exception) {
                org.slf4j.LoggerFactory.getLogger(ProtocolFiles::class.java)
                    .warn("protocol transfer file cleanup failed: path={}", path, failure)
            }
        }
    }

    private fun attributes(path: Path): BasicFileAttributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)

    companion object {
        private val NAME = Regex("frame-[0-9a-f]{32}\\.pb")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        /** 启动握手尚未取得目录；这里只读取管道中的官方帧。 */
        fun readPipeFrame(input: InputStream, maximum: Int): Frame? {
            val first = input.read()
            if (first < 0) return null
            var length = (first and 127).toLong()
            var next = first
            var shift = 7
            while (next and 128 != 0) {
                require(shift < 35) { "invalid protobuf frame length" }
                next = input.read()
                require(next >= 0) { "truncated protobuf frame length" }
                length = length or ((next and 127).toLong() shl shift)
                shift += 7
            }
            require(length in 1..maximum.toLong()) { "protobuf pipe frame exceeds byte budget" }
            val bytes = input.readNBytes(length.toInt())
            require(bytes.size == length.toInt()) { "truncated protobuf frame" }
            val coded = CodedInputStream.newInstance(bytes).apply {
                setSizeLimit(bytes.size)
                setRecursionLimit(Int.MAX_VALUE)
            }
            val frame = Frame.parseFrom(coded)
            coded.checkLastTagWas(0)
            require(coded.isAtEnd) { "protobuf pipe frame has trailing bytes" }
            return frame
        }
    }
}
