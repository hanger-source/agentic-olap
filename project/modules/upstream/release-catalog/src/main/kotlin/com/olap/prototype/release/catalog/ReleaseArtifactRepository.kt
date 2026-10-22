package com.olap.prototype.release.catalog

import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.codec.PublicationProto
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.engine.catalog.ExecutableCatalogManifest
import com.olap.prototype.query.contract.PublishedExecutableCatalog
import com.olap.prototype.release.contract.*
import com.olap.prototype.storage.PlatformDatabase
import java.sql.ResultSet
import java.time.Instant

/** 发布产物持久化及精确内容身份校验，事务生命周期由调用数据库持有。 */
class ReleaseArtifactRepository(private val database: PlatformDatabase) : ReleaseArtifactWriter {
    override fun saveCatalog(catalog: ReleaseCatalogView, executableCatalog: PublishedExecutableCatalog, createdAt: Instant): ReleaseCatalogSnapshot {
        requireExecutableCoordinate(ReleaseCatalogRequest(catalog.scenarioId, catalog.scenarioVersion, catalog.releaseId, catalog.releaseVersion), executableCatalog)
        try {
            val producerJson = ProtoBytes.storage(GovernanceProto.toProto(catalog))
            val producerHash = contentHash(producerJson)
            val executableJson = ProtoBytes.storage(GovernanceProto.toProto(executableCatalog))
            val executableHash = PublicationProto.contentHash(executableCatalog)
            database.connection().use { connection -> connection.prepareStatement(
                """
                INSERT INTO release_executable_catalog
                  (release_id,release_version,scenario_id,scenario_version,producer_catalog_proto,producer_catalog_hash,
                   executable_catalog_proto,catalog_hash,compiler_version,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, catalog.releaseId); statement.setString(2, catalog.releaseVersion)
                statement.setString(3, catalog.scenarioId); statement.setLong(4, catalog.scenarioVersion)
                statement.setString(5, producerJson); statement.setString(6, producerHash)
                statement.setString(7, executableJson); statement.setString(8, executableHash)
                statement.setString(9, ReleaseCompilationContract.COMPILER_VERSION); statement.setObject(10, createdAt)
                statement.executeUpdate()
            } }
            return ReleaseCatalogSnapshot(catalog.scenarioId, catalog.scenarioVersion, executableHash)
        } catch (failure: Exception) { throw IllegalStateException("Failed to freeze release executable catalog: ${catalog.releaseId}#${catalog.releaseVersion}#${catalog.scenarioId}", failure) }
    }
    override fun finalizeArtifact(releaseId: String, releaseVersion: String, subjectAreaId: String, subjectAreaVersion: Long, createdBy: String, createdAt: Instant): ReleaseArtifactRecord {
        try {
            val closure = releaseClosure(releaseId, releaseVersion)
            val catalogs = catalogSnapshots(releaseId, releaseVersion)
            if (closure.isEmpty()) throw ProducerContractException("release artifact governance closure is empty")
            if (catalogs.isEmpty()) throw ProducerContractException("release artifact executable catalogs are empty")
            val closureHash = ProtoBytes.digest(GovernanceProto.toProto(ReleaseClosure(closure)))
            val catalogHash = catalogs.singleOrNull()?.catalogHash ?: ProtoBytes.digest(GovernanceProto.toProto(ReleaseCatalogSnapshots(catalogs)))
            val manifest = ReleaseArtifactManifest(releaseId, releaseVersion, subjectAreaId, subjectAreaVersion,
                ReleaseCompilationContract.COMPILER_VERSION, ReleaseCompilationContract.RULE_SET_VERSION, closureHash, catalogHash, catalogs)
            val manifestProto = ProtoBytes.storage(GovernanceProto.toProto(manifest))
            val artifactHash = contentHash(manifestProto)
            database.connection().use { connection -> connection.prepareStatement(
                """
                INSERT INTO release_artifact
                  (release_id,release_version,subject_area_id,subject_area_version,compiler_version,rule_set_version,
                   governance_closure_hash,catalog_hash,artifact_hash,manifest_proto,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, releaseId); statement.setString(2, releaseVersion)
                statement.setString(3, subjectAreaId); statement.setLong(4, subjectAreaVersion)
                statement.setString(5, ReleaseCompilationContract.COMPILER_VERSION); statement.setString(6, ReleaseCompilationContract.RULE_SET_VERSION)
                statement.setString(7, closureHash); statement.setString(8, catalogHash); statement.setString(9, artifactHash)
                statement.setString(10, manifestProto); statement.setString(11, createdBy); statement.setObject(12, createdAt)
                statement.executeUpdate()
            } }
            return ReleaseArtifactRecord(releaseId, releaseVersion, subjectAreaId, subjectAreaVersion, closureHash, catalogHash, artifactHash, manifestProto)
        } catch (failure: ProducerContractException) { throw failure }
        catch (failure: Exception) { throw IllegalStateException("Failed to finalize release artifact: $releaseId#$releaseVersion", failure) }
    }
    fun loadArtifact(releaseId: String, releaseVersion: String): ReleaseArtifactRecord {
        ProducerContracts.requireText(releaseId, "releaseId"); ProducerContracts.requireText(releaseVersion, "releaseVersion")
        try {
            database.connection().use { connection -> connection.prepareStatement(
                """
                SELECT subject_area_id,subject_area_version,governance_closure_hash,catalog_hash,artifact_hash,manifest_proto
                  FROM release_artifact WHERE release_id=? AND release_version=?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, releaseId); statement.setString(2, releaseVersion)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) throw ProducerContractException("committed release artifact not found: $releaseId#$releaseVersion")
                    val artifact = ReleaseArtifactRecord(releaseId, releaseVersion, rows.getString(1), rows.getLong(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6))
                    val manifest = GovernanceProto.fromProto(ProtoBytes.decode(ProtoBytes.stored(artifact.manifestProto), com.olap.prototype.wire.governance.ReleaseArtifactManifest.getDefaultInstance()))
                    if (artifact.artifactHash != contentHash(artifact.manifestProto) || releaseId != manifest.releaseId || releaseVersion != manifest.releaseVersion ||
                        artifact.subjectAreaId != manifest.subjectAreaId || artifact.subjectAreaVersion != manifest.subjectAreaVersion ||
                        artifact.governanceClosureHash != manifest.governanceClosureHash || artifact.catalogHash != manifest.catalogHash) throw ProducerContractException("stored release identity differs from its immutable manifest")
                    return artifact
                }
            } }
        } catch (failure: ProducerContractException) { throw failure }
        catch (failure: Exception) { throw IllegalStateException("Failed to read committed release artifact", failure) }
    }
    fun loadCatalog(request: ReleaseCatalogRequest): ReleaseCatalogView = try {
        catalogRow(request, "producer_catalog_proto,producer_catalog_hash", "frozen release catalog not found") { row ->
            val json = row.getString("producer_catalog_proto")
            val actual = contentHash(json)
            val expected = row.getString("producer_catalog_hash")
            if (expected != actual) throw ProducerContractException("frozen release catalog hash mismatch: expected=$expected actual=$actual")
            val catalog = GovernanceProto.fromProto(ProtoBytes.decode(ProtoBytes.stored(json), com.olap.prototype.wire.governance.ReleaseCatalogView.getDefaultInstance()))
            if (request != ReleaseCatalogRequest(catalog.scenarioId, catalog.scenarioVersion, catalog.releaseId, catalog.releaseVersion)) throw ProducerContractException("frozen release catalog coordinate differs from its storage key")
            catalog
        }
    } catch (failure: ProducerContractException) { throw failure }
    catch (failure: Exception) { throw IllegalStateException("Failed to load frozen release catalog", failure) }

    fun loadExecutableCatalog(request: ReleaseCatalogRequest): PublishedExecutableCatalog = try {
        catalogRow(request, "executable_catalog_proto,catalog_hash", "frozen executable catalog not found") { row ->
            val encoded = row.getString("executable_catalog_proto")
            val expected = row.getString("catalog_hash")
            val message = try {
                ProtoBytes.decode(ProtoBytes.stored(encoded), com.olap.prototype.wire.governance.PublishedExecutableCatalog.getDefaultInstance())
            } catch (failure: IllegalArgumentException) {
                throw ProducerContractException("frozen executable catalog contains invalid protobuf bytes", failure)
            } catch (failure: com.google.protobuf.InvalidProtocolBufferException) {
                throw ProducerContractException("frozen executable catalog contains invalid protobuf bytes", failure)
            }
            val actual = PublicationProto.contentHash(message)
            if (expected != actual) throw ProducerContractException("frozen executable catalog hash mismatch: expected=$expected actual=$actual")
            val catalog = GovernanceProto.fromProto(message)
            ExecutableCatalogManifest.requireSupportedSchema(catalog.catalog.manifest.schemaVersion)
            requireExecutableCoordinate(request, catalog)
            catalog
        }
    } catch (failure: ProducerContractException) { throw failure }
    catch (failure: Exception) { throw IllegalStateException("Failed to load frozen executable catalog", failure) }

    fun catalogHash(request: ReleaseCatalogRequest): String = PublicationProto.contentHash(loadExecutableCatalog(request))

    fun contentHash(encoded: String): String = ProtoBytes.digest(ProtoBytes.stored(encoded))

    /** 字段选择仅由上面三个读取操作给出，精确坐标与连接的生命周期只装配一次。 */
    private fun <T> catalogRow(request: ReleaseCatalogRequest, columns: String, missing: String, read: (ResultSet) -> T): T {
        database.connection().use { connection -> connection.prepareStatement(
            "SELECT $columns FROM release_executable_catalog WHERE release_id=? AND release_version=? AND scenario_id=? AND scenario_version=?",
        ).use { statement ->
            statement.setString(1, request.releaseId); statement.setString(2, request.releaseVersion)
            statement.setString(3, request.scenarioId); statement.setLong(4, request.scenarioVersion)
            statement.executeQuery().use { rows ->
                if (!rows.next()) throw ProducerContractException("$missing: ${request.releaseId}#${request.releaseVersion}#${request.scenarioId}@${request.scenarioVersion}")
                return read(rows)
            }
        } }
    }
    private fun requireExecutableCoordinate(request: ReleaseCatalogRequest, catalog: PublishedExecutableCatalog) {
        catalog.validate()
        val coordinate = catalog.coordinate
        if (request.scenarioId != coordinate.scenarioId || request.scenarioVersion != coordinate.scenarioVersion || request.releaseId != coordinate.releaseId || request.releaseVersion != coordinate.releaseVersion) throw ProducerContractException("frozen publication coordinate differs from its storage key")
    }
    private fun releaseClosure(releaseId: String, releaseVersion: String): List<ReleaseClosureEntry> {
        database.connection().use { connection -> connection.prepareStatement(
            """
            SELECT object_type,object_id,object_version,object_code,object_display_name,ref_role,snapshot_proto
              FROM release_object_ref WHERE release_id=? AND release_version=? ORDER BY object_type,object_id,object_version
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, releaseId); statement.setString(2, releaseVersion)
            statement.executeQuery().use { result ->
                val rows = mutableListOf<ReleaseClosureEntry>()
                while (result.next()) rows.add(ReleaseClosureEntry(result.getString("object_type"), result.getString("object_id"),
                    result.getLong("object_version"), result.getString("object_code"), result.getString("object_display_name"),
                    result.getString("ref_role"), result.getString("snapshot_proto")))
                return rows
            }
        } }
    }
    private fun catalogSnapshots(releaseId: String, releaseVersion: String): List<ReleaseCatalogSnapshot> {
        database.connection().use { connection -> connection.prepareStatement(
            "SELECT scenario_id,scenario_version,catalog_hash FROM release_executable_catalog WHERE release_id=? AND release_version=? ORDER BY scenario_id,scenario_version",
        ).use { statement ->
            statement.setString(1, releaseId); statement.setString(2, releaseVersion)
            statement.executeQuery().use { result ->
                val rows = mutableListOf<ReleaseCatalogSnapshot>()
                while (result.next()) rows.add(ReleaseCatalogSnapshot(result.getString("scenario_id"), result.getLong("scenario_version"), result.getString("catalog_hash")))
                return rows
            }
        } }
    }
}
