package com.olap.prototype.query.usage

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.olap.prototype.query.contract.PublishedQueryContract
import com.olap.prototype.query.contract.QueryCapabilityRequest
import com.olap.prototype.util.OlapJsonUtils
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.HexFormat

/** 将内核已核验的业务结果封存；保存字节及分页属于服务层，不在这里重新计算数量。 */
object QueryResultArtifact {
    class Prepared internal constructor(
        val runId: String,
        val sha256: String,
        val content: ByteArray,
        val rows: Long,
    )

    fun prepare(record: JsonNode, intent: JsonNode, maxBytes: Long): Prepared {
        require(maxBytes > 0) { "result artifact byte budget must be positive" }
        val mapper = OlapJsonUtils.mapper()
        val runId = text(record, "runId")
        require(runId.matches(Regex("run-[0-9a-f]{32}"))) { "exact result run identity required" }
        val coordinate = mapper.treeToValue(record.required("coordinate"), QueryCapabilityRequest::class.java)
        require(coordinate == PublishedQueryContract.coordinate(intent)) { "result publication differs from its accepted query" }
        for (field in listOf("catalogHash", "capabilityHash"))
            require(text(record, field).matches(Regex("[0-9a-f]{64}"))) { "exact result provenance hash required" }
        for (field in listOf("startedAt", "completedAt")) OffsetDateTime.parse(text(record, field))
        val status = text(record, "status")
        val succeeded = status == "SUCCEEDED"
        require(status in setOf("SUCCEEDED", "ENGINE_FAILED", "EXECUTION_FAILED", "CANCELLED", "NOT_EXECUTED", "OUTCOME_UNKNOWN")) { "unsupported actual execution status" }
        val result = if (succeeded) {
            record.required("result").required("computation").required("businessResult").also { result ->
                require(record.required("error").isNull && result.isObject && result.required("rows").isArray) {
                    "successful execution must contain its producer-validated business result"
                }
            }
        } else {
            require(!record.required("error").isNull) { "failed execution must contain its actual error" }
            null
        }
        val business = mapper.createObjectNode().apply {
            for (field in listOf("requestId", "runId", "queryReference", "coordinate", "catalogHash", "capabilityHash", "startedAt", "completedAt", "status",
                "clientOutcome", "sourceOutcome", "sourceReadEvidence", "timingNanos", "explanation"))
                set<JsonNode>(field, record.required(field))
            set<JsonNode>("queryScope", intent.required("computation"))
            // record.error 已由正式服务按公开错误合同脱敏，封存不能再次丢掉诊断事实。
            if (succeeded) putNull("error") else set<JsonNode>("error", record.required("error"))
            if (result == null) putNull("result") else set<JsonNode>("result", result)
        }
        text(business, "requestId")
        val frozen = encode(business, maxBytes)
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(frozen))
        val artifact = mapper.createObjectNode().apply {
            put("formatVersion", "olap-query-result/v3")
            put("sha256", sha)
            put("resultJson", frozen.toString(Charsets.UTF_8))
        }
        return Prepared(runId, sha, encode(artifact, maxBytes), result?.required("rows")?.size()?.toLong() ?: 0)
    }

    private fun encode(value: JsonNode, maximum: Long): ByteArray = OlapJsonUtils.mapper().writeValueAsBytes(value).also {
        require(it.size.toLong() <= maximum) { "result artifact exceeds the deployment byte budget" }
    }

    private fun text(value: JsonNode, field: String): String {
        val node = value.required(field)
        require(node.isTextual && node.textValue().isNotBlank()) { "$field must be explicit nonempty text" }
        return node.textValue()
    }
}
