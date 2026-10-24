package com.olap.prototype.server.results

import com.olap.prototype.codec.QueryArtifactProto
import com.olap.prototype.query.contract.QueryResultReference
import com.olap.prototype.query.contract.SavedQueryResultRecord
import com.olap.prototype.query.usage.QueryResultArtifact
import com.olap.prototype.wire.query.SavedQueryResultArtifact
import java.nio.file.Path

/** 只保存及读取生产者正式核验的结果，不重新规划、访问来源或核验业务数值。 */
class ResultFiles(dataRoot: Path) {
    private val directory = dataRoot.resolve("results")
    fun save(prepared: QueryResultArtifact.Prepared): QueryResultReference {
        val reference = QueryResultReference(prepared.runId, prepared.sha256)
        decode(reference, prepared.content)
        PrivateFiles.write(location(reference), prepared.content)
        return reference
    }
    fun read(reference: QueryResultReference): SavedQueryResultRecord = decode(reference, PrivateFiles.read(location(reference)))
    fun location(reference: QueryResultReference): Path = directory.resolve("${reference.runId}.pb")
    fun inspect(reference: QueryResultReference): Map<String, Any> = try {
        read(reference)
        mapOf("reference" to reference, "status" to "READABLE", "issues" to emptyList<String>())
    } catch (failure: Exception) {
        val missing = failure is java.nio.file.NoSuchFileException
        mapOf("reference" to reference, "status" to "UNAVAILABLE", "issues" to listOf(if (missing) "RESULT_NOT_FOUND" else "RESULT_UNREADABLE"))
    }
    private fun decode(reference: QueryResultReference, bytes: ByteArray): SavedQueryResultRecord {
        val artifact = SavedQueryResultArtifact.parseFrom(bytes)
        require(artifact.sha256 == reference.sha256) { "result bytes differ from the exact reference" }
        val record = QueryArtifactProto.decode(bytes, bytes.size, bytes.size.coerceAtLeast(1))
        require(record.runId == reference.runId) { "result run differs from its reference" }
        return record
    }
}
