package com.olap.prototype.runtime

import com.google.protobuf.CodedInputStream
import com.olap.prototype.protocol.Frame
import com.olap.prototype.protocol.ProtocolFile
import java.io.InputStream
import java.io.FilterInputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

/** 只交接完整官方 Frame。主服务持有根生命周期，OS 权限禁止脚本替换根及祖先；
 * 文件本身通过 NOFOLLOW、实际打开长度、完整读取字节和摘要核验。 */
class ProtocolFiles(directory: Path) : AutoCloseable {
    private val root = directory.toAbsolutePath().normalize().also {
        require(Files.isDirectory(it, NOFOLLOW_LINKS) && it == it.toRealPath()) { "private protocol transfer directory is required" }
    }
    private val closed = java.util.concurrent.atomic.AtomicBoolean()

    fun outbound(frame: Frame, maximumPipeBytes: Int): Frame {
        check(!closed.get()) { "protocol file lifecycle is closed" }
        require(!frame.hasFile()) { "protocol file references cannot be nested" }
        if (frame.serializedSize <= maximumPipeBytes) return frame
        val name = "frame-${UUID.randomUUID().toString().replace("-", "")}.pb"
        val destination = root.resolve(name)
        val partial = root.resolve("$name.part")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val size = FileChannel.open(partial, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { channel ->
                DigestOutputStream(Channels.newOutputStream(channel), digest).use { output ->
                    frame.writeTo(output)
                    output.flush()
                    channel.size()
                }
            }
            Files.move(partial, destination, ATOMIC_MOVE)
            return Frame.newBuilder().setFile(ProtocolFile.newBuilder().setName(name).setBytes(size)
                .setSha256(hex(digest.digest()))).build()
        } catch (failure: Throwable) {
            try { Files.deleteIfExists(partial) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun read(input: InputStream, maximumPipeBytes: Int): Frame? {
        check(!closed.get()) { "protocol file lifecycle is closed" }
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
            FileChannel.open(path, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                require(channel.size() == reference.bytes) { "opened protocol transfer file differs from its declared size" }
                val digest = MessageDigest.getInstance("SHA-256")
                val counted = CountingInput(Channels.newInputStream(channel))
                DigestInputStream(counted, digest).use { content ->
                    val coded = CodedInputStream.newInstance(content).apply {
                        setSizeLimit(Int.MAX_VALUE)
                        setRecursionLimit(Int.MAX_VALUE)
                    }
                    val frame = Frame.parseFrom(coded)
                    coded.checkLastTagWas(0)
                    require(coded.isAtEnd && content.read() == -1) { "protocol transfer file has trailing bytes" }
                    val after = attributes(path)
                    require(after.isRegularFile && before.fileKey() == after.fileKey() && before.size() == after.size() &&
                        counted.bytes == reference.bytes && channel.size() == reference.bytes &&
                        hex(digest.digest()) == reference.sha256) { "protocol transfer file identity or content changed" }
                    require(!frame.hasFile()) { "protocol file references cannot be nested" }
                    return frame
                }
            }
        } finally {
            try {
                val current = try { attributes(path) } catch (_: NoSuchFileException) { null }
                if (current?.isRegularFile == true && current.fileKey() == before.fileKey()) Files.delete(path)
            } catch (failure: Exception) {
                org.slf4j.LoggerFactory.getLogger(ProtocolFiles::class.java)
                    .warn("protocol transfer file cleanup failed: path={}", path, failure)
            }
        }
    }

    private fun attributes(path: Path): BasicFileAttributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)

    override fun close() { closed.set(true) }

    private class CountingInput(input: InputStream) : FilterInputStream(input) {
        var bytes = 0L
            private set
        override fun read(): Int = `in`.read().also { if (it >= 0) bytes = Math.addExact(bytes, 1) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = `in`.read(buffer, offset, length).also {
            if (it > 0) bytes = Math.addExact(bytes, it.toLong())
        }
    }

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
