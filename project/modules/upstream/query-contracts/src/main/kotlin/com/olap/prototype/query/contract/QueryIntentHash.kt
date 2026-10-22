package com.olap.prototype.query.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.olap.prototype.util.OlapJsonUtils
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/** 精确语义 JSON 的内容身份，数字保持原本的无损编码。 */
object QueryIntentHash {
    @JvmStatic
    fun compute(intent: JsonNode): String = try {
        val json = OlapJsonUtils.mapper().writeValueAsString(canonicalize(intent))
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.toByteArray(StandardCharsets.UTF_8)))
    } catch (failure: Exception) {
        throw IllegalStateException("Failed to hash query intent", failure)
    }

    private fun canonicalize(node: JsonNode): JsonNode = when {
        node.isObject -> OlapJsonUtils.mapper().createObjectNode().apply {
            for ((name, value) in node.fields().asSequence().associate { it.key to it.value }.toSortedMap())
                set<JsonNode>(name, canonicalize(value))
        }
        node.isArray -> OlapJsonUtils.mapper().createArrayNode().apply { for (value in node) add(canonicalize(value)) }
        else -> node
    }
}
