package com.olap.prototype.server.process

import com.olap.prototype.util.OlapJsonUtils
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/** Seatbelt path/quoting assembly reviewed against SRT commit 3a2954dc8ab938877fe4e279f04dfe4dd18ab40d,
 * src/sandbox/macos-sandbox-utils.ts. Policy deliberately grants only this JVM's dependencies and private root. */
internal class ScriptSandbox(directory: Path, java: Path, dependencies: List<Path>) {
    val directory = directory.toRealPath()
    val java = java.toRealPath()
    val jre = this.java.parent.parent
    private val files = dependencies.map { it.toRealPath().also { path -> require(Files.isRegularFile(path)) { "script dependency must be regular" } } }.distinct()
    init {
        require(Files.isDirectory(this.directory) && Files.getPosixFilePermissions(this.directory).none {
            it in setOf(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)
        }) { "script sandbox requires its private directory" }
        require(this.java.fileName.toString() == "java" && this.java.parent.fileName.toString() == "bin") { "exact JRE executable is required" }
    }
    fun arguments(arguments: List<String>, transfer: Path): List<String> = listOf("-p", profile(transfer), java.toString(), "-XX:-UsePerfData",
        "-Duser.home=$directory", "-Djava.io.tmpdir=$directory") + arguments
    val environment get() = mapOf("JAVA_HOME" to jre.toString(), "HOME" to directory.toString(), "TMPDIR" to directory.toString(), "LANG" to "en_US.UTF-8", "TZ" to "UTC")
    private fun quote(path: Path) = OlapJsonUtils.mapper().writeValueAsString(path.toString())
    private fun profile(transferDirectory: Path): String {
        val transfer = transferDirectory.toRealPath()
        require(Files.isDirectory(transfer) && !transfer.startsWith(directory) && !directory.startsWith(transfer)) {
            "protocol transfer root must be outside the author-writable session root"
        }
        return buildString {
        append("""(version 1)
(deny default)
(allow process-exec (literal ${quote(java)}))
(allow process-info* (target self))
(allow mach-priv-task-port (target self))
(allow signal (target self))
(allow sysctl-read)
(allow file-read-metadata (vnode-type DIRECTORY))
(allow file-read* (literal "/") (subpath "/System") (subpath "/usr/lib")
  (literal "/dev/null") (literal "/dev/random") (literal "/dev/urandom")
  (subpath ${quote(jre)}) (subpath ${quote(directory)}) (subpath ${quote(transfer)}))
(allow file-write* (literal "/dev/null") (subpath ${quote(directory)}) (subpath ${quote(transfer)}))
(allow file-ioctl (literal "/dev/null"))
""")
        files.forEach { append("(allow file-read* (literal ${quote(it)}))\n") }
        // The parent is service-owned and not writable by scripts. Exact deny rules also protect the
        // granted transfer root itself: child file creation/atomic rename remains allowed.
        var protected: Path? = transfer
        while (protected != null) {
            append("(deny file-write* (literal ${quote(protected)}))\n")
            protected = protected.parent
        }
        }
    }
}
