package com.olap.prototype.server.results

import com.olap.prototype.codec.QueryArtifactProto
import com.olap.prototype.governance.contract.DeploymentAvailability
import com.olap.prototype.query.contract.QueryResultFormatException
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
    fun inspect(reference: QueryResultReference): DeploymentAvailability.Check {
        val coordinate = mapOf<String, Any>("runId" to reference.runId, "sha256" to reference.sha256)
        return try {
            read(reference)
            DeploymentAvailability.Check(DeploymentAvailability.Kind.RESULT_READ, coordinate, DeploymentAvailability.Status.AVAILABLE)
        } catch (failure: QueryResultFormatException) {
            DeploymentAvailability.Check(DeploymentAvailability.Kind.RESULT_READ, coordinate, DeploymentAvailability.Status.UNSUPPORTED,
                listOf(DeploymentAvailability.Issue(failure.code, requireNotNull(failure.message), failure.details)))
        } catch (failure: java.nio.file.NoSuchFileException) {
            DeploymentAvailability.Check(DeploymentAvailability.Kind.RESULT_READ, coordinate, DeploymentAvailability.Status.MISSING,
                listOf(DeploymentAvailability.Issue("RESULT_NOT_FOUND", "saved result file is absent")))
        } catch (failure: Exception) {
            DeploymentAvailability.Check(DeploymentAvailability.Kind.RESULT_READ, coordinate, DeploymentAvailability.Status.INVALID,
                listOf(DeploymentAvailability.Issue("RESULT_UNREADABLE", "saved result bytes are not readable by this deployment")))
        }
    }
    private fun decode(reference: QueryResultReference, bytes: ByteArray): SavedQueryResultRecord {
        val artifact = SavedQueryResultArtifact.parseFrom(bytes)
        require(artifact.sha256 == reference.sha256) { "result bytes differ from the exact reference" }
        val record = QueryArtifactProto.decode(bytes, bytes.size, bytes.size.coerceAtLeast(1))
        require(record.runId == reference.runId) { "result run differs from its reference" }
        return record
    }
}
