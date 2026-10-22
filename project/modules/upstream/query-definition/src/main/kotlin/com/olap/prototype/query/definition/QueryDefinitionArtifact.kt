package com.olap.prototype.query.definition

import com.olap.prototype.query.contract.QueryDefinitionView
import com.olap.prototype.query.contract.QueryIntentHash
import com.olap.prototype.runtime.BoundedJson
import com.olap.prototype.runtime.JsonResourceLimits
import com.olap.prototype.util.OlapJsonUtils
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/** 精确正文、原始字节哈希及查询内容身份由这一处产生和读取。 */
object QueryDefinitionArtifact {
    const val FORMAT_VERSION = "olap-query-definition-artifact/v10"
    data class Artifact(val formatVersion: String, val sha256: String, val definitionJson: String)
    data class Reference(val definitionId: String, val definitionVersion: Long, val sha256: String) {
        init {
            require(definitionId.isNotBlank() && definitionVersion > 0 && sha256.matches(Regex("[0-9a-f]{64}"))) { "exact saved query reference required" }
        }
    }

    fun view(id: String, version: Long, command: QueryDefinitionCommand, context: QueryDefinitionWriteContext): QueryDefinitionView {
        val capability = context.entry.capability
        return QueryDefinitionView(id, version, command.name, command.scenarioId, command.scenarioVersion,
            capability.scenario.scenarioDisplayName, command.releaseId, command.releaseVersion, capability.releaseDisplayName,
            context.catalogHash, context.subjectAreaId, context.subjectAreaVersion, command.capabilityHash,
            QueryIntentHash.compute(command.intent), command.intent, Instant.now())
    }

    fun encode(view: QueryDefinitionView, limits: JsonResourceLimits): Artifact {
        val json = OlapJsonUtils.mapper().writeValueAsString(view)
        return Artifact(FORMAT_VERSION, hash(json), json).also { BoundedJson.encode(it, limits.inputFileBytes) }
    }

    fun decode(artifact: Artifact, limits: JsonResourceLimits): QueryDefinitionView {
        require(artifact.formatVersion == FORMAT_VERSION) { "unsupported query definition artifact format: ${artifact.formatVersion}; supported format: $FORMAT_VERSION" }
        check(hash(artifact.definitionJson) == artifact.sha256) { "query artifact hash mismatch" }
        val view = OlapJsonUtils.mapper().treeToValue(
            BoundedJson.decode(artifact.definitionJson.toByteArray(Charsets.UTF_8), limits), QueryDefinitionView::class.java)
        check(QueryIntentHash.compute(view.intent) == view.intentHash) { "query intent hash mismatch" }
        return view
    }

    internal fun hash(value: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
}
