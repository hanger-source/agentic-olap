package com.olap.prototype.query.contract

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.util.OlapJsonUtils
import java.security.MessageDigest
import java.util.HexFormat

/** 内容身份排序对象键并排除生成时间；完整文件字节另由交接摘要保护。 */
object PublicationContentHash {
    fun compute(content: JsonNode): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(OlapJsonUtils.mapper().writeValueAsBytes(canonical(content))))
    private fun canonical(node: JsonNode): JsonNode = when {
        node.isObject -> OlapJsonUtils.mapper().createObjectNode().apply {
            node.fields().asSequence().filter { it.key !in setOf("createdAt", "generatedAt") }.sortedBy { it.key }.forEach { set<JsonNode>(it.key, canonical(it.value)) }
        }
        node.isArray -> OlapJsonUtils.mapper().createArrayNode().apply { node.forEach { add(canonical(it)) } }
        else -> node
    }
}
