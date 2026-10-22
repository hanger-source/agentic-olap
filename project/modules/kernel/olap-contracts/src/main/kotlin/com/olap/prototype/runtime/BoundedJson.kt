package com.olap.prototype.runtime

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.util.OlapJsonUtils
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.CancellationException

/** 完整 JSON 文档的有界编解码；不拥有请求调度、输运行或期限。 */
object BoundedJson {
    fun decode(bytes: ByteArray, limits: JsonResourceLimits): JsonNode {
        if (bytes.size > limits.inputFileBytes) throw RuntimeResourceLimitException("INPUT_FILE_BYTES_EXCEEDED")
        val mapper = OlapJsonUtils.mapper().copy().apply {
            factory.setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(limits.inputDepth)
                .maxStringLength(limits.inputFileBytes).maxNumberLength(limits.inputFileBytes).build())
            enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        }
        val root = requireNotNull(mapper.readTree(bytes)) { "JSON document is empty" }
        val pending = ArrayDeque<Pair<JsonNode, Int>>()
        pending.add(root to 1)
        var nodes = 0L
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeLast()
            if (depth > limits.inputDepth) throw RuntimeResourceLimitException("INPUT_DEPTH_EXCEEDED")
            if (++nodes > limits.inputNodes) throw RuntimeResourceLimitException("INPUT_NODES_EXCEEDED")
            if (node.isContainerNode) {
                if (pending.size.toLong() + node.size() + nodes > limits.inputNodes) throw RuntimeResourceLimitException("INPUT_NODES_EXCEEDED")
                node.elements().forEachRemaining { pending.add(it to depth + 1) }
            }
        }
        return root
    }

    fun read(path: Path, limits: JsonResourceLimits): JsonNode = decode(readBytes(path, limits), limits)

    fun readBytes(path: Path, limits: JsonResourceLimits): ByteArray {
        require(Files.isRegularFile(path)) { "frozen input must be a regular file" }
        val bytes = ByteArrayOutputStream(minOf(limits.inputFileBytes, 8192))
        Files.newInputStream(path).use { source ->
            val chunk = ByteArray(65536)
            while (true) {
                val count = source.read(chunk)
                if (count < 0) break
                if (Thread.currentThread().isInterrupted) throw CancellationException("input file read interrupted")
                if (bytes.size().toLong() + count > limits.inputFileBytes) throw RuntimeResourceLimitException("INPUT_FILE_BYTES_EXCEEDED")
                bytes.write(chunk, 0, count)
            }
        }
        return bytes.toByteArray()
    }

    fun encode(value: Any?, maximum: Int): ByteArray {
        require(maximum > 0) { "positive encoded byte budget is required" }
        val bytes = ByteArrayOutputStream(minOf(maximum, 8192))
        try {
            object : OutputStream() {
                override fun write(value: Int) {
                    if (bytes.size() == maximum) throw RuntimeResourceLimitException("ENCODED_BYTES_EXCEEDED")
                    bytes.write(value)
                }
                override fun write(data: ByteArray, offset: Int, length: Int) {
                    if (bytes.size().toLong() + length > maximum) throw RuntimeResourceLimitException("ENCODED_BYTES_EXCEEDED")
                    bytes.write(data, offset, length)
                }
            }.use { OlapJsonUtils.mapper().writeValue(it, value) }
        } catch (failure: JsonMappingException) {
            var cause: Throwable? = failure
            while (cause != null) {
                if (cause is RuntimeResourceLimitException) throw cause
                cause = cause.cause
            }
            throw failure
        }
        return bytes.toByteArray()
    }
}
