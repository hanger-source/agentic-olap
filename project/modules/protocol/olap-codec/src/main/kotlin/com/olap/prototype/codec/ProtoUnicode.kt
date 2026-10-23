package com.olap.prototype.codec

import com.google.protobuf.Descriptors.FieldDescriptor.JavaType
import com.google.protobuf.Message

/** 检查已选定的官方消息，不解释业务对象、Any 内容或未知字节。 */
internal object ProtoUnicode {
    private data class Pending(val message: Message, val path: List<String>)

    fun requireMessage(message: Message) {
        val pending = ArrayDeque<Pending>()
        pending.addLast(Pending(message, emptyList()))
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            current.message.allFields.forEach { (field, value) ->
                val path = current.path + field.jsonName
                fun inspect(value: Any, path: List<String>) {
                    when (field.javaType) {
                        JavaType.STRING -> requireText(value as String, path)
                        JavaType.MESSAGE -> pending.addLast(Pending(value as Message, path))
                        else -> Unit
                    }
                }
                if (field.isRepeated) {
                    // map 也是正式的 repeated entry 消息；路径用位置，不回显未校验的键。
                    (value as List<*>).forEachIndexed { index, element -> inspect(requireNotNull(element), path + index.toString()) }
                } else inspect(value, path)
            }
        }
    }

    fun requireText(text: String, path: List<String>): String = text.also {
        var index = 0
        while (index < text.length) {
            val character = text[index++]
            if (character.isHighSurrogate()) {
                if (index == text.length || !text[index++].isLowSurrogate()) throw ProtoStringRepresentationException(path)
            } else if (character.isLowSurrogate()) throw ProtoStringRepresentationException(path)
        }
    }
}

/** 字段位置只来自官方 descriptor 与集合位置，不携带原字符串。 */
class ProtoStringRepresentationException(path: List<String>) :
    IllegalArgumentException("formal protobuf string contains an unpaired UTF-16 surrogate and cannot round-trip through UTF-8") {
    val fieldPath: List<String> = java.util.List.copyOf(path)
}
