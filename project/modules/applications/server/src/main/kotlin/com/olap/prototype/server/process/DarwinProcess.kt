package com.olap.prototype.server.process

import com.sun.jna.Library
import com.sun.jna.LastErrorException
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Darwin owns process groups at spawn time; no shell, parent chdir or post-start grouping race. */
internal class DarwinProcess private constructor(val pid: Int, val input: OutputStream,
    val output: InputStream, val error: InputStream) : AutoCloseable {
    val exit = CompletableFuture<Int>()
    private var terminated = false
    private val log = org.slf4j.LoggerFactory.getLogger(DarwinProcess::class.java)
    private val reaper = Thread.ofPlatform().daemon(true).name("jvm-wait-$pid").start {
        val status = IntByReference()
        try {
            while (true) {
                val result = try { libc.waitpid(pid, status, 0) }
                    catch (failure: LastErrorException) { if (failure.errorCode == EINTR) continue else throw failure }
                if (result == pid) {
                    log.debug("JVM child exit observed: pid={} rawStatus={}", pid, status.value)
                    exit.complete(status.value)
                    break
                }
                throw IllegalStateException("waitpid returned an unexpected child: pid=$pid result=$result")
            }
        } catch (failure: Throwable) {
            log.error("JVM child wait failed: pid={}", pid, failure)
            exit.completeExceptionally(failure)
        }
    }
    @Synchronized fun terminate() {
        if (terminated) return
        signal(15)
        if (!exit.isDone) {
            try { exit.get(1, TimeUnit.SECONDS) }
            catch (_: java.util.concurrent.TimeoutException) { signal(9) }
        }
        // A terminal protocol channel is not proof of process exit; waitpid is authoritative.
        exit.join()
        // A departed leader may still have descendants holding its protocol descriptors.
        signal(9)
        terminated = true
    }
    fun closePipes() {
        var primary: Throwable? = null
        listOf(input, output, error).forEach { stream ->
            try { stream.close() } catch (failure: Throwable) {
                if (primary == null) primary = failure else primary!!.addSuppressed(failure)
            }
        }
        primary?.let { throw it }
    }
    override fun close() {
        var failure: Throwable? = null
        try { terminate() } catch (problem: Throwable) { failure = problem }
        try { closePipes() } catch (problem: Throwable) { if (failure == null) failure = problem else failure.addSuppressed(problem) }
        failure?.let { throw it }
    }
    private fun signal(signal: Int) {
        try {
            check(libc.kill(-pid, signal) == 0) { "process-group signal returned an invalid result: pid=$pid signal=$signal" }
        } catch (failure: LastErrorException) {
            if (failure.errorCode == ESRCH) return
            throw IllegalStateException("process-group signal failed: pid=$pid signal=$signal errno=${failure.errorCode}", failure)
        }
    }

    companion object {
        private const val EINTR = 4
        private const val ESRCH = 3
        private val libc: DarwinLibC by lazy {
            require(System.getProperty("os.name") == "Mac OS X") { "this product process runtime requires macOS" }
            Native.load("System", DarwinLibC::class.java)
        }
        fun spawn(executable: Path, arguments: List<String>, environment: Map<String, String>, directory: Path): DarwinProcess {
            require(executable.isAbsolute && directory.isAbsolute) { "absolute process paths are required" }
            val values = listOf(executable.toString()) + arguments
            require(values.none { '\u0000' in it } && environment.all { (name, value) ->
                name.isNotEmpty() && '=' !in name && '\u0000' !in name && '\u0000' !in value }) { "invalid process argument or environment" }
            val pipes = ArrayList<Descriptor>()
            val actions = PointerByReference()
            val attributes = PointerByReference()
            var actionsReady = false
            var attributesReady = false
            var transferred = false
            var owned: DarwinProcess? = null
            var primary: Throwable? = null
            try {
                fun pipe(): MutableList<Descriptor> {
                    val raw = IntArray(2)
                    check(libc.pipe(raw) == 0) { "pipe returned an invalid result" }
                    val pair = raw.map { Descriptor(it).also(pipes::add) }.toMutableList()
                    pair.indices.forEach { index ->
                        val descriptor = pair[index]
                        if (descriptor.fd < 3) {
                            val duplicate = libc.fcntl(descriptor.fd, 67, 3) // Darwin F_DUPFD_CLOEXEC.
                            check(duplicate >= 3) { "pipe descriptor duplication returned an invalid descriptor" }
                            val replacement = Descriptor(duplicate).also(pipes::add)
                            descriptor.close()
                            pair[index] = replacement
                        }
                    }
                    return pair
                }
                val stdin = pipe(); val stdout = pipe(); val stderr = pipe()
                checked(libc.posix_spawn_file_actions_init(actions), "file actions init"); actionsReady = true
                checked(libc.posix_spawnattr_init(attributes), "attributes init"); attributesReady = true
                checked(libc.posix_spawnattr_setflags(attributes, (0x0002 or 0x4000).toShort()), "set process group and close inherited descriptors")
                checked(libc.posix_spawnattr_setpgroup(attributes, 0), "set process group")
                checked(libc.posix_spawn_file_actions_addchdir_np(actions, directory.toString()), "set child directory")
                listOf(stdin[0] to 0, stdout[1] to 1, stderr[1] to 2).forEach { (from, to) ->
                    checked(libc.posix_spawn_file_actions_adddup2(actions, from.fd, to), "redirect child descriptor")
                }
                (stdin + stdout + stderr).forEach { checked(libc.posix_spawn_file_actions_addclose(actions, it.fd), "close child pipe descriptor") }
                val pid = IntByReference()
                StringArray(values.toTypedArray()).use { argv ->
                    StringArray(environment.map { "${it.key}=${it.value}" }.toTypedArray()).use { env ->
                        checked(libc.posix_spawn(pid, executable.toString(), actions, attributes, argv, env), "spawn")
                    }
                }
                val process = DarwinProcess(pid.value, DescriptorOutput(stdin[1]), DescriptorInput(stdout[0]), DescriptorInput(stderr[0]))
                owned = process
                listOf(stdin[0], stdout[1], stderr[1]).forEach(Descriptor::close)
                transferred = true
                return process
            } catch (failure: Throwable) {
                primary = failure
                try { owned?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            } finally {
                if (actionsReady) libc.posix_spawn_file_actions_destroy(actions)
                if (attributesReady) libc.posix_spawnattr_destroy(attributes)
                if (!transferred) pipes.forEach { descriptor ->
                    try { descriptor.close() } catch (cleanup: Throwable) {
                        val original = primary
                        if (original != null) original.addSuppressed(cleanup) else throw cleanup
                    }
                }
            }
        }
        private fun checked(code: Int, action: String) { check(code == 0) { "posix_spawn $action failed: errno=$code" } }
        private class Descriptor(val fd: Int) : AutoCloseable {
            private var closed = false
            @Synchronized override fun close() {
                if (closed) return
                closed = true
                try { check(libc.close(fd) == 0) { "pipe descriptor close returned an invalid result: descriptor=$fd" } }
                catch (failure: LastErrorException) { throw java.io.IOException("pipe descriptor close failed: descriptor=$fd errno=${failure.errorCode}", failure) }
            }
        }
        private class DescriptorInput(private val descriptor: Descriptor) : InputStream() {
            private val fd get() = descriptor.fd
            override fun read(): Int { val one = ByteArray(1); return if (read(one) < 0) -1 else one[0].toInt() and 255 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                java.util.Objects.checkFromIndexSize(offset, length, bytes.size)
                if (length == 0) return 0
                return Memory(length.toLong()).use { memory ->
                    var count: Long
                    while (true) {
                        try { count = libc.read(fd, memory, NativeLong(length.toLong())).toLong(); break }
                        catch (failure: LastErrorException) {
                            if (failure.errorCode == EINTR) continue
                            throw java.io.IOException("pipe read failed: descriptor=$fd errno=${failure.errorCode}", failure)
                        }
                    }
                    if (count < 0) throw java.io.IOException("pipe read returned an invalid length: descriptor=$fd length=$count")
                    if (count == 0L) -1
                    else { memory.read(0, bytes, offset, count.toInt()); count.toInt() }
                }
            }
            override fun close() = descriptor.close()
        }
        private class DescriptorOutput(private val descriptor: Descriptor) : OutputStream() {
            private val fd get() = descriptor.fd
            override fun write(value: Int) = write(byteArrayOf(value.toByte()))
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                java.util.Objects.checkFromIndexSize(offset, length, bytes.size)
                if (length == 0) return
                Memory(length.toLong()).use { memory ->
                    memory.write(0, bytes, offset, length)
                    var written = 0L
                    while (written < length) {
                        val count = try { libc.write(fd, memory.share(written), NativeLong(length - written)).toLong() }
                        catch (failure: LastErrorException) {
                            if (failure.errorCode == EINTR) continue
                            throw java.io.IOException("pipe write failed: descriptor=$fd errno=${failure.errorCode}", failure)
                        }
                        if (count > 0) written += count
                        else throw java.io.IOException("pipe write returned an invalid length: descriptor=$fd length=$count")
                    }
                }
            }
            override fun close() = descriptor.close()
        }
    }
}

internal interface DarwinLibC : Library {
    @Throws(LastErrorException::class)
    fun pipe(descriptors: IntArray): Int
    @Throws(LastErrorException::class)
    fun fcntl(fd: Int, command: Int, argument: Int): Int
    @Throws(LastErrorException::class)
    fun close(fd: Int): Int
    @Throws(LastErrorException::class)
    fun read(fd: Int, buffer: Pointer, count: NativeLong): NativeLong
    @Throws(LastErrorException::class)
    fun write(fd: Int, buffer: Pointer, count: NativeLong): NativeLong
    @Throws(LastErrorException::class)
    fun kill(pid: Int, signal: Int): Int
    @Throws(LastErrorException::class)
    fun waitpid(pid: Int, status: IntByReference, options: Int): Int
    fun posix_spawnattr_init(attributes: PointerByReference): Int
    fun posix_spawnattr_destroy(attributes: PointerByReference): Int
    fun posix_spawnattr_setflags(attributes: PointerByReference, flags: Short): Int
    fun posix_spawnattr_setpgroup(attributes: PointerByReference, group: Int): Int
    fun posix_spawn_file_actions_init(actions: PointerByReference): Int
    fun posix_spawn_file_actions_destroy(actions: PointerByReference): Int
    fun posix_spawn_file_actions_adddup2(actions: PointerByReference, from: Int, to: Int): Int
    fun posix_spawn_file_actions_addclose(actions: PointerByReference, descriptor: Int): Int
    fun posix_spawn_file_actions_addchdir_np(actions: PointerByReference, directory: String): Int
    fun posix_spawn(pid: IntByReference, executable: String, actions: PointerByReference,
        attributes: PointerByReference, arguments: StringArray, environment: StringArray): Int
}
