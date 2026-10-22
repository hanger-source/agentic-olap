package com.olap.prototype.release.catalog

import com.fasterxml.jackson.databind.DeserializationFeature
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.engine.catalog.ExecutableCatalogManifest
import com.olap.prototype.query.contract.PublicationContentHash
import com.olap.prototype.query.contract.PublishedExecutableCatalog
import com.olap.prototype.release.contract.*
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.util.OlapJsonUtils
import java.sql.ResultSet
import java.time.Instant

/** 发布产物持久化及精确内容身份校验，事务生命周期由调用数据库持有。 */
class ReleaseArtifactRepository(private val database: PlatformDatabase) : ReleaseArtifactWriter {
    override fun saveCatalog(catalog: ReleaseCatalogView, executableCatalog: PublishedExecutableCatalog, createdAt: Instant): ReleaseCatalogSnapshot {
        requireExecutableCoordinate(ReleaseCatalogRequest(catalog.scenarioId, catalog.scenarioVersion, catalog.releaseId, catalog.releaseVersion), executableCatalog)
        try {
            val producerJson = OlapJsonUtils.mapper().writeValueAsString(catalog)
            val producerHash = contentHash(producerJson)
            val executableJson = OlapJsonUtils.mapper().writeValueAsString(executableCatalog)
            val executableHash = contentHash(executableJson)
            database.connection().use { connection -> connection.prepareStatement(
                """
                INSERT INTO release_executable_catalog
                  (release_id,release_version,scenario_id,scenario_version,producer_catalog_json,producer_catalog_hash,
                   executable_catalog_json,catalog_hash,compiler_version,created_at)
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
            val closureHash = contentHash(OlapJsonUtils.mapper().writeValueAsString(closure))
            val catalogHash = catalogs.singleOrNull()?.catalogHash ?: contentHash(OlapJsonUtils.mapper().writeValueAsString(catalogs))
            val manifest = linkedMapOf<String, Any>("releaseId" to releaseId, "releaseVersion" to releaseVersion, "subjectAreaId" to subjectAreaId,
                "subjectAreaVersion" to subjectAreaVersion, "compilerVersion" to ReleaseCompilationContract.COMPILER_VERSION,
                "ruleSetVersion" to ReleaseCompilationContract.RULE_SET_VERSION, "governanceClosureHash" to closureHash, "catalogHash" to catalogHash, "catalogs" to catalogs)
            val manifestJson = OlapJsonUtils.mapper().writeValueAsString(manifest)
            val artifactHash = contentHash(manifestJson)
            database.connection().use { connection -> connection.prepareStatement(
                """
                INSERT INTO release_artifact
                  (release_id,release_version,subject_area_id,subject_area_version,compiler_version,rule_set_version,
                   governance_closure_hash,catalog_hash,artifact_hash,manifest_json,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, releaseId); statement.setString(2, releaseVersion)
                statement.setString(3, subjectAreaId); statement.setLong(4, subjectAreaVersion)
                statement.setString(5, ReleaseCompilationContract.COMPILER_VERSION); statement.setString(6, ReleaseCompilationContract.RULE_SET_VERSION)
                statement.setString(7, closureHash); statement.setString(8, catalogHash); statement.setString(9, artifactHash)
                statement.setString(10, manifestJson); statement.setString(11, createdBy); statement.setObject(12, createdAt)
                statement.executeUpdate()
            } }
            return ReleaseArtifactRecord(releaseId, releaseVersion, subjectAreaId, subjectAreaVersion, closureHash, catalogHash, artifactHash, manifestJson)
        } catch (failure: ProducerContractException) { throw failure }
        catch (failure: Exception) { throw IllegalStateException("Failed to finalize release artifact: $releaseId#$releaseVersion", failure) }
    }
    fun loadArtifact(releaseId: String, releaseVersion: String): ReleaseArtifactRecord {
        ProducerContracts.requireText(releaseId, "releaseId"); ProducerContracts.requireText(releaseVersion, "releaseVersion")
        try {
            database.connection().use { connection -> connection.prepareStatement(
                """
                SELECT subject_area_id,subject_area_version,governance_closure_hash,catalog_hash,artifact_hash,manifest_json
                  FROM release_artifact WHERE release_id=? AND release_version=?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, releaseId); statement.setString(2, releaseVersion)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) throw ProducerContractException("committed release artifact not found: $releaseId#$releaseVersion")
                    val artifact = ReleaseArtifactRecord(releaseId, releaseVersion, rows.getString(1), rows.getLong(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6))
                    val manifest = OlapJsonUtils.mapper().readTree(artifact.manifestJson)
                    if (artifact.artifactHash != contentHash(artifact.manifestJson) || releaseId != manifest.path("releaseId").asText() || releaseVersion != manifest.path("releaseVersion").asText() ||
                        artifact.subjectAreaId != manifest.path("subjectAreaId").asText() || artifact.subjectAreaVersion != manifest.path("subjectAreaVersion").asLong() ||
                        artifact.governanceClosureHash != manifest.path("governanceClosureHash").asText() || artifact.catalogHash != manifest.path("catalogHash").asText()) throw ProducerContractException("stored release identity differs from its immutable manifest")
                    return artifact
                }
            } }
        } catch (failure: ProducerContractException) { throw failure }
        catch (failure: Exception) { throw IllegalStateException("Failed to read committed release artifact", failure) }
    }
    fun loadCatalog(request: ReleaseCatalogRequest): ReleaseCatalogView = try {
        catalogRow(request, "producer_catalog_json,producer_catalog_hash", "frozen release catalog not found") { row ->
            val json = row.getString("producer_catalog_json")
            val actual = contentHash(json)
            val expected = row.getString("producer_catalog_hash")
            if (expected != actual) throw ProducerContractException("frozen release catalog hash mismatch: expected=$expected actual=$actual")
            val catalog = OlapJsonUtils.mapper().readValue(json, ReleaseCatalogView::class.java)
            if (request != ReleaseCatalogRequest(catalog.scenarioId, catalog.scenarioVersion, catalog.releaseId, catalog.releaseVersion)) throw ProducerContractException("frozen release catalog coordinate differs from its storage key")
            catalog
        }
    } catch (failure: ProducerContractException) { throw failure }
    catch (failure: Exception) { throw IllegalStateException("Failed to load frozen release catalog", failure) }

    fun loadExecutableCatalog(request: ReleaseCatalogRequest): PublishedExecutableCatalog = try {
        catalogRow(request, "executable_catalog_json,catalog_hash", "frozen executable catalog not found") { row ->
            val json = row.getString("executable_catalog_json")
            val expected = row.getString("catalog_hash")
            val actual = contentHash(json)
            if (expected != actual) throw ProducerContractException("frozen executable catalog hash mismatch: expected=$expected actual=$actual")
            val payload = OlapJsonUtils.mapper().readTree(json)
            ExecutableCatalogManifest.requireSupportedSchema(payload.path("catalog").path("manifest").path("schemaVersion").textValue())
            val catalog = OlapJsonUtils.mapper().readerFor(PublishedExecutableCatalog::class.java).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue<PublishedExecutableCatalog>(payload.traverse(OlapJsonUtils.mapper()))
            requireExecutableCoordinate(request, catalog)
            catalog
        }
    } catch (failure: ProducerContractException) { throw failure }
    catch (failure: Exception) { throw IllegalStateException("Failed to load frozen executable catalog", failure) }

    fun catalogHash(request: ReleaseCatalogRequest): String = try {
        catalogRow(request, "executable_catalog_json,catalog_hash", "frozen release catalog hash not found") { row ->
            val hash = row.getString("catalog_hash")
            if (hash != contentHash(row.getString("executable_catalog_json"))) throw ProducerContractException("frozen executable catalog hash mismatch")
            hash
        }
    } catch (failure: ProducerContractException) { throw failure }
    catch (failure: Exception) { throw IllegalStateException("Failed to read frozen release catalog hash", failure) }

    fun contentHash(json: String): String = try { PublicationContentHash.compute(OlapJsonUtils.mapper().readTree(json)) }
    catch (failure: Exception) { throw IllegalArgumentException("release artifact JSON cannot be canonicalized", failure) }

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
    private fun releaseClosure(releaseId: String, releaseVersion: String): List<Map<String, Any?>> {
        database.connection().use { connection -> connection.prepareStatement(
            """
            SELECT object_type,object_id,object_version,object_code,object_display_name,ref_role,snapshot_summary_json
              FROM release_object_ref WHERE release_id=? AND release_version=? ORDER BY object_type,object_id,object_version
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, releaseId); statement.setString(2, releaseVersion)
            statement.executeQuery().use { result ->
                val rows = mutableListOf<Map<String, Any?>>()
                while (result.next()) rows.add(linkedMapOf("objectType" to result.getString("object_type"), "objectId" to result.getString("object_id"),
                    "objectVersion" to result.getLong("object_version"), "objectCode" to result.getString("object_code"), "objectDisplayName" to result.getString("object_display_name"),
                    "refRole" to result.getString("ref_role"), "snapshot" to OlapJsonUtils.mapper().readTree(result.getString("snapshot_summary_json"))))
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
