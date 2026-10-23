package com.olap.prototype.query.usage

import com.olap.prototype.codec.QueryArtifactProto
import com.olap.prototype.query.contract.QueryExecutionStatus
import com.olap.prototype.query.contract.SavedQueryResultRecord
import com.olap.prototype.wire.query.SavedQueryResultArtifact

/** 封存已经核验的正式执行事实；文件保存与分页由Go承担。 */
object QueryResultArtifact {
    const val FORMAT = QueryArtifactProto.FORMAT
    class Prepared internal constructor(val runId: String, val sha256: String, val content: ByteArray, val rows: Long)

    fun prepare(record: SavedQueryResultRecord): Prepared {
        require(record.catalogHash.matches(Regex("[0-9a-f]{64}")) && record.capabilityHash.matches(Regex("[0-9a-f]{64}"))) {
            "result provenance requires exact hashes"
        }
        require(record.receipt.resultReference == null && !record.receipt.saved) { "result bytes precede actual delivery confirmation" }
        if (record.receipt.status == QueryExecutionStatus.SUCCEEDED) {
            require(record.receipt.result != null && record.receipt.error == null) { "successful execution requires its validated result" }
        } else require(record.receipt.result == null && record.receipt.error != null) { "failed execution requires its actual failure" }
        val content = QueryArtifactProto.encode(record)
        return Prepared(record.runId, SavedQueryResultArtifact.parseFrom(content).sha256, content, record.receipt.rows)
    }
}
