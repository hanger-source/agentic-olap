package com.olap.prototype.codec

import com.google.protobuf.Any
import com.google.protobuf.ByteString
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.Message
import java.security.MessageDigest
import java.util.Base64

/** 官方 Protobuf 的确定性字节与受限读取；不根据输入选择类或构造模型。 */
object ProtoBytes {
    fun storage(message: Message, maximumBytes: Int = message.serializedSize): String = Base64.getEncoder().encodeToString(encode(message, maximumBytes))
    fun stored(value: String): ByteArray = Base64.getDecoder().decode(value)
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun digest(message: Message): String = digest(encode(message, message.serializedSize))
    fun encode(message: Message, maximumBytes: Int = message.serializedSize): ByteArray {
        val size = message.serializedSize
        require(size >= 0 && maximumBytes >= 0 && size <= maximumBytes) { "protobuf message exceeds byte budget" }
        val bytes = ByteArray(size)
        CodedOutputStream.newInstance(bytes).apply {
            useDeterministicSerialization()
            message.writeTo(this)
            checkNoSpaceLeft()
        }
        return bytes
    }
    fun pack(message: Message, maximumBytes: Int): Any = Any.newBuilder()
        .setTypeUrl("type.googleapis.com/${message.descriptorForType.fullName}")
        .setValue(ByteString.copyFrom(encode(message, maximumBytes))).build()

    @Suppress("UNCHECKED_CAST")
    fun <T : Message> unpack(envelope: Any, expected: T, maximumBytes: Int, maximumDepth: Int): T {
        require(envelope.typeUrl == "type.googleapis.com/${expected.descriptorForType.fullName}") { "protobuf Any differs from the registered concrete message" }
        return decode(envelope.value.toByteArray(), expected, maximumBytes, maximumDepth)
    }
    @Suppress("UNCHECKED_CAST")
    fun <T : Message> decode(bytes: ByteArray, expected: T, maximumBytes: Int = bytes.size, maximumDepth: Int = 128): T {
        require(bytes.size <= maximumBytes && maximumDepth > 0) { "protobuf input exceeds its declared budget" }
        val input = CodedInputStream.newInstance(bytes)
        input.setSizeLimit(maximumBytes)
        input.setRecursionLimit(maximumDepth)
        val result = expected.parserForType.parseFrom(input) as T
        input.checkLastTagWas(0)
        require(input.isAtEnd) { "protobuf input has trailing content" }
        return result
    }
}
