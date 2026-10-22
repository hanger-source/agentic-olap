package com.olap.prototype.query.definition

import com.olap.prototype.query.contract.QueryDefinitionView
import com.olap.prototype.query.contract.QueryIntentHash
import com.olap.prototype.runtime.JsonResourceLimits
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.codec.QueryProto
import java.time.Instant

/** 精确正文、原始字节哈希及查询内容身份由这一处产生和读取。 */
object QueryDefinitionArtifact {
    const val FORMAT_VERSION = "olap-query-definition-artifact/v11"
    data class Artifact(val formatVersion: String, val sha256: String, val definition: ByteArray)

    fun view(id: String, version: Long, command: QueryDefinitionCommand, context: QueryDefinitionWriteContext): QueryDefinitionView {
        val capability = context.entry.capability
        return QueryDefinitionView(id, version, command.name, command.scenarioId, command.scenarioVersion,
            capability.scenario.scenarioDisplayName, command.releaseId, command.releaseVersion, capability.releaseDisplayName,
            context.catalogHash, context.subjectAreaId, context.subjectAreaVersion, command.capabilityHash,
            QueryIntentHash.compute(command.intent), command.intent, Instant.now(), command.source)
    }

    fun encode(view: QueryDefinitionView, limits: JsonResourceLimits): Artifact {
        val encoded = ProtoBytes.encode(QueryProto.toProto(view), limits.inputFileBytes)
        return Artifact(FORMAT_VERSION, ProtoBytes.digest(encoded), encoded)
    }

    fun decode(artifact: Artifact, limits: JsonResourceLimits): QueryDefinitionView {
        require(artifact.formatVersion == FORMAT_VERSION) { "unsupported query definition artifact format: ${artifact.formatVersion}; supported format: $FORMAT_VERSION" }
        check(ProtoBytes.digest(artifact.definition) == artifact.sha256) { "query artifact hash mismatch" }
        val view = QueryProto.fromProto(ProtoBytes.decode(artifact.definition,
            com.olap.prototype.wire.query.QueryDefinitionView.getDefaultInstance(), limits.inputFileBytes, limits.inputDepth))
        check(QueryIntentHash.compute(view.intent) == view.intentHash) { "query intent hash mismatch" }
        return view
    }

}
