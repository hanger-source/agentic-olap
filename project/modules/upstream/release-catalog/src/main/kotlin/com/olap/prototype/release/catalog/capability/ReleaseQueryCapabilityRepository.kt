package com.olap.prototype.release.catalog.capability

import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.query.contract.*
import com.olap.prototype.release.contract.ReleaseCompilationContract
import com.olap.prototype.storage.PlatformDatabase
import java.time.Instant

class ReleaseQueryCapabilityRepository(private val database: PlatformDatabase) : QueryCapabilityWriter {
    override fun save(capability: QueryCapabilityView, createdAt: Instant) {
        try {
            val capabilityProto = ProtoBytes.storage(GovernanceProto.toProto(capability))
            val hash = QueryCapabilityHash.requireValid(capability)
            database.connection().use { connection -> connection.prepareStatement(
                """
                INSERT INTO release_query_capability
                  (release_id, release_version, scenario_id, scenario_version,
                   capability_proto, capability_hash, rule_set_version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, capability.releaseId); statement.setString(2, capability.releaseVersion)
                statement.setString(3, capability.scenario.scenarioId); statement.setLong(4, capability.scenario.scenarioVersion)
                statement.setString(5, capabilityProto); statement.setString(6, hash)
                statement.setString(7, ReleaseCompilationContract.RULE_SET_VERSION); statement.setObject(8, createdAt)
                statement.executeUpdate()
            } }
        } catch (failure: ProducerContractException) { throw failure }
        catch (failure: Exception) { throw IllegalStateException("Failed to freeze release query capability", failure) }
    }
    fun load(request: QueryCapabilityRequest): QueryCapabilityView {
        try {
            database.connection().use { connection -> connection.prepareStatement(
                """
                SELECT capability_proto, capability_hash FROM release_query_capability
                 WHERE release_id=? AND release_version=? AND scenario_id=? AND scenario_version=?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, request.releaseId); statement.setString(2, request.releaseVersion)
                statement.setString(3, request.scenarioId); statement.setLong(4, request.scenarioVersion)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw ProducerContractException("frozen query capability not found: ${request.releaseId}#${request.releaseVersion}#${request.scenarioId}@${request.scenarioVersion}")
                    val capability = GovernanceProto.fromProto(ProtoBytes.decode(ProtoBytes.stored(result.getString("capability_proto")), com.olap.prototype.wire.governance.QueryCapabilityView.getDefaultInstance()))
                    if (result.getString("capability_hash") != QueryCapabilityHash.requireValid(capability)) throw ProducerContractException("frozen query capability hash mismatch")
                    if (request != PublishedQueryEntry.key(capability)) throw ProducerContractException("frozen query capability coordinate differs from its storage key")
                    return capability
                }
            } }
        } catch (failure: ProducerContractException) { throw failure }
        catch (failure: com.google.protobuf.InvalidProtocolBufferException) { throw ProducerContractException("frozen query capability encoding does not match the published contract", failure) }
        catch (failure: IllegalArgumentException) { throw ProducerContractException("frozen query capability contract is invalid", failure) }
        catch (failure: Exception) { throw IllegalStateException("Failed to load frozen query capability", failure) }
    }
}
