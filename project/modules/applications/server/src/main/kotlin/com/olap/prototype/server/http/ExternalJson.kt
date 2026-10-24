package com.olap.prototype.server.http

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import java.util.ArrayDeque
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.core.JsonGenerator
import com.google.protobuf.Message
import com.google.protobuf.TypeRegistry
import com.google.protobuf.util.JsonFormat

/** 外部 JSON 编解码；领域对象不会为本地调用先转 JSON。 */
object ExternalJson {
    val mapper: ObjectMapper = com.olap.prototype.util.OlapJsonUtils.mapper().copy()
        .apply { factory.setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(Int.MAX_VALUE)
            .maxStringLength(Int.MAX_VALUE).maxNumberLength(Int.MAX_VALUE).build())
            factory.setStreamWriteConstraints(com.fasterxml.jackson.core.StreamWriteConstraints.builder().maxNestingDepth(Int.MAX_VALUE).build()) }
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
            DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .registerModule(SimpleModule().addSerializer(Message::class.java, object : JsonSerializer<Message>() {
            private val printer by lazy {
                val registry = TypeRegistry.newBuilder()
                val files = HashSet<String>()
                fun file(descriptor: com.google.protobuf.Descriptors.FileDescriptor) {
                    if (!files.add(descriptor.fullName)) return
                    descriptor.dependencies.forEach(::file)
                    fun message(value: com.google.protobuf.Descriptors.Descriptor) { registry.add(value); value.nestedTypes.forEach(::message) }
                    descriptor.messageTypes.forEach(::message)
                }
                file(com.olap.prototype.protocol.Frame.getDescriptor().file)
                file(com.olap.prototype.wire.query.QueryIntent.getDescriptor().file)
                file(com.olap.prototype.wire.governance.WarehouseDiscoveryCommand.getDescriptor().file)
                JsonFormat.printer().usingTypeRegistry(registry.build()).includingDefaultValueFields()
            }
            override fun serialize(value: Message, generator: JsonGenerator, provider: SerializerProvider) {
                val encoded = printer.print(value)
                if (value is com.olap.prototype.protocol.ScriptDiagnostic && value.hasCapabilityFailure()) {
                    val document = mapper.readTree(encoded) as com.fasterxml.jackson.databind.node.ObjectNode
                    val failure = document.required("capabilityFailure") as com.fasterxml.jackson.databind.node.ObjectNode
                    failure.remove("detailsJson")
                    if (!value.capabilityFailure.detailsJson.isEmpty) {
                        val details = mapper.readTree(value.capabilityFailure.detailsJson.toByteArray())
                        require(details.isObject) { "controlled capability failure details must be an object" }
                        failure.set<JsonNode>("details", details)
                    }
                    generator.writeTree(document)
                } else generator.writeRawValue(encoded)
            }
        }))
        .registerModule(SimpleModule().addSerializer(com.olap.prototype.server.scripting.ScriptOutput::class.java,
            object : JsonSerializer<com.olap.prototype.server.scripting.ScriptOutput>() {
                override fun serialize(value: com.olap.prototype.server.scripting.ScriptOutput, generator: JsonGenerator, provider: SerializerProvider) {
                    generator.writeStartObject()
                    generator.writeNumberField("sequence", value.sequence)
                    generator.writeStringField("kind", value.kind)
                    if (value.kind == "VALUE") { generator.writeFieldName("value"); provider.defaultSerializeValue(value.value, generator) }
                    else {
                        val bytes = requireNotNull(value.content)
                        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        val text = try { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() } catch (_: java.nio.charset.CharacterCodingException) { null }
                        generator.writeStringField("encoding", if (text == null) "BASE64" else "UTF8")
                        generator.writeStringField("content", text ?: java.util.Base64.getEncoder().encodeToString(bytes))
                    }
                    generator.writeEndObject()
                }
            }))

    fun objectDocument(bytes: ByteArray, maximumDepth: Int, maximumNodes: Int): JsonNode {
        val input = mapper.copy().apply {
            factory.setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(maximumDepth)
                .maxStringLength(bytes.size.coerceAtLeast(1)).maxNumberLength(bytes.size.coerceAtLeast(1)).build())
            enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        }
        val root = requireNotNull(input.readTree(bytes)) { "JSON document is empty" }
        require(root.isObject) { "one complete JSON object is required" }
        val pending = ArrayDeque<Pair<JsonNode, Int>>()
        pending.add(root to 1)
        var nodes = 0L
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeLast()
            nodes++
            require(depth <= maximumDepth) { "JSON_INPUT_RESOURCE_LIMIT: depth=$depth limit=$maximumDepth" }
            require(nodes <= maximumNodes.toLong()) { "JSON_INPUT_RESOURCE_LIMIT: nodes=$nodes limit=$maximumNodes" }
            if (node.isContainerNode) node.forEach { pending.add(it to depth + 1) }
        }
        return root
    }
}
