package com.olap.prototype.server.results

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

internal object PrivateFiles {
    fun directory(path: Path) { Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))) }
    fun read(path: Path): ByteArray {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "saved content must be a regular file" }
        return FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            require(channel.size() <= Int.MAX_VALUE) { "saved content exceeds this process's addressable byte array" }
            val buffer = java.nio.ByteBuffer.allocate(channel.size().toInt())
            while (buffer.hasRemaining()) check(channel.read(buffer) >= 0) { "saved content changed during read" }
            buffer.array()
        }
    }
    fun write(path: Path, bytes: ByteArray, replace: Boolean = false, observe: (String, Long) -> Unit = { _, _ -> }) {
        fun <T> timed(stage: String, action: () -> T): T {
            val started = System.nanoTime()
            return try { action() } finally { observe(stage, System.nanoTime() - started) }
        }
        val staging = timed("RESULT_CREATE") {
            directory(path.parent)
            Files.createTempFile(path.parent, ".${path.fileName}-", ".pending", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        }
        try {
            timed("RESULT_CREATE") { FileChannel.open(staging, WRITE) }.use { channel ->
                timed("RESULT_WRITE") {
                    val buffer = java.nio.ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                }
                timed("RESULT_SYNC") { channel.force(true) }
            }
            timed("RESULT_COMMIT") {
                if (replace) Files.move(staging, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                else Files.createLink(path, staging)
            }
            timed("RESULT_DIRECTORY_SYNC") { FileChannel.open(path.parent, READ).use { it.force(true) } }
        } finally { timed("RESULT_CLEANUP") { Files.deleteIfExists(staging) } }
    }
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
