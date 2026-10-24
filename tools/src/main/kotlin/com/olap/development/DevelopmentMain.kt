package com.olap.development

import kotlinx.serialization.json.*
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit

fun main(arguments: Array<String>) {
    require(arguments.isNotEmpty()) { "用法：scenario <name> <distribution> <output> <workspace> | runtime <distribution> <output> <workspace> | installed <tarballs> <output> <workspace> [mode]" }
    when (arguments[0]) {
        "scenario" -> Scenario(arguments[1], Path.of(arguments[2]), Path.of(arguments[3]), Path.of(arguments[4]), arguments.getOrNull(5)?.let(Path::of)).run()
        "installed" -> InstalledProduct(Path.of(arguments[1]), Path.of(arguments[2]), Path.of(arguments[3]), arguments.getOrElse(4) { "tarball" }).run()
        "runtime" -> RuntimeTrial(Path.of(arguments[1]), Path.of(arguments[2]), Path.of(arguments[3])).run()
        else -> error("未知研发操作：${arguments[0]}")
    }
}

internal fun objectOf(vararg entries: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*entries))
internal fun text(value: String) = JsonPrimitive(value)
internal fun number(value: Number) = JsonPrimitive(value)
internal fun bool(value: Boolean) = JsonPrimitive(value)
internal fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content
internal fun JsonObject.objectAt(name: String) = getValue(name).jsonObject
internal fun JsonObject.array(name: String) = getValue(name).jsonArray
internal fun read(path: Path) = Json.parseToJsonElement(Files.readString(path))
internal fun write(path: Path, value: JsonElement) {
    Files.createDirectories(path.toAbsolutePath().parent)
    Files.writeString(path, Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), value) + "\n")
}
internal fun JsonObject.with(vararg entries: Pair<String, JsonElement>) = JsonObject(this + entries)
internal fun sha(path: Path): String = Files.newInputStream(path).use { stream ->
    val digest = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(65536)
    while (true) { val count = stream.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
    java.util.HexFormat.of().formatHex(digest.digest())
}
internal fun sha(bytes: ByteArray) = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
internal fun execute(command: List<String>, directory: Path, environment: Map<String, String> = emptyMap(), inherit: Boolean = true): String {
    val builder = ProcessBuilder(command).directory(directory.toFile())
    builder.environment().putAll(environment)
    if (inherit) builder.inheritIO() else builder.redirectError(ProcessBuilder.Redirect.INHERIT)
    val process = builder.start()
    val output = if (inherit) "" else process.inputStream.bufferedReader().use { it.readText() }
    check(process.waitFor() == 0) { "研发命令失败：${command.first()}" }
    return output
}

internal class ManagedProcess(command: List<String>, directory: Path, output: Path,
    environment: Map<String, String> = emptyMap(), isolatedEnvironment: Boolean = false) : Closeable {
    private val process: Process
    init {
        Files.createDirectories(output.toAbsolutePath().parent)
        val builder = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile())
        if (isolatedEnvironment) builder.environment().clear()
        builder.environment().putAll(environment)
        process = builder.start()
    }
    fun ready(timeout: Duration = Duration.ofMinutes(2), predicate: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!predicate()) {
            check(process.isAlive) { "正式产品在就绪前退出，状态 ${process.exitValue()}" }
            check(System.nanoTime() < deadline) { "正式产品未就绪" }
            Thread.sleep(50)
        }
    }
    override fun close() {
        if (!process.isAlive) { check(process.exitValue() == 0) { "正式产品异常退出：${process.exitValue()}" }; return }
        process.destroy()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "正式产品没有完成进程回收" }
        check(process.exitValue() in setOf(0, 143)) { "正式产品停止失败：${process.exitValue()}" }
    }
}
