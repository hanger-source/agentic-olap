package com.olap.prototype.query.definition

import com.olap.prototype.query.contract.QueryDefinitionView
import com.olap.prototype.runtime.BoundedJson
import com.olap.prototype.runtime.JsonResourceLimits
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.util.OlapJsonUtils
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

/** 查询身份、不可变版本与源版本竞争检查在同一元数据事务内完成。 */
class JdbcQueryDefinitionRepository(private val database: PlatformDatabase, private val limits: JsonResourceLimits) : QueryDefinitionRepository {
    override fun save(command: QueryDefinitionCommand, context: QueryDefinitionWriteContext): QueryDefinitionView =
        write(command.definitionId ?: "query_${UUID.randomUUID()}", null, command, context)

    override fun update(definitionId: String, sourceVersion: Long, command: QueryDefinitionCommand, context: QueryDefinitionWriteContext): QueryDefinitionView =
        write(definitionId, sourceVersion, command, context)

    private fun write(id: String, sourceVersion: Long?, command: QueryDefinitionCommand, context: QueryDefinitionWriteContext): QueryDefinitionView = database.inTransaction {
        database.connection().use { connection ->
            val version = sourceVersion?.let { Math.addExact(it, 1) } ?: 1L
            if (sourceVersion == null) {
                connection.prepareStatement("INSERT INTO query_definition(id,current_version) VALUES (?,1)").use {
                    it.setString(1, id)
                    it.executeUpdate()
                }
            } else {
                connection.prepareStatement("UPDATE query_definition SET current_version=? WHERE id=? AND current_version=?").use {
                    it.setLong(1, version)
                    it.setString(2, id)
                    it.setLong(3, sourceVersion)
                    require(it.executeUpdate() == 1) { "query source version is stale or missing" }
                }
            }
            val view = QueryDefinitionArtifact.view(id, version, command, context)
            val artifact = QueryDefinitionArtifact.encode(view, limits)
            val encoded = BoundedJson.encode(artifact, limits.inputFileBytes).toString(Charsets.UTF_8)
            connection.prepareStatement("""
                INSERT INTO query_definition_version
                (definition_id,version,name,scenario_id,scenario_version,release_id,release_version,subject_area_id,subject_area_version,catalog_hash,capability_hash,intent_hash,artifact_sha256,artifact_json,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """.trimIndent()).use {
                it.setString(1, id)
                it.setLong(2, version)
                it.setString(3, view.name)
                it.setString(4, view.scenarioId)
                it.setLong(5, view.scenarioVersion)
                it.setString(6, view.releaseId)
                it.setString(7, view.releaseVersion)
                it.setString(8, view.subjectAreaId)
                it.setLong(9, view.subjectAreaVersion)
                it.setString(10, view.catalogHash)
                it.setString(11, view.capabilityHash)
                it.setString(12, view.intentHash)
                it.setString(13, artifact.sha256)
                it.setString(14, encoded)
                it.setString(15, context.createdBy)
                it.setTimestamp(16, Timestamp.from(view.createdAt))
                it.executeUpdate()
            }
            view
        }
    }

    override fun findByIdAndVersion(definitionId: String, definitionVersion: Long): QueryDefinitionView? = read(definitionId, definitionVersion)?.view

    fun requireReference(id: String, version: Long): QueryDefinitionArtifact.Reference =
        QueryDefinitionArtifact.Reference(id, version, requireStored(id, version).artifact.sha256)

    fun requireArtifact(id: String, version: Long): QueryDefinitionArtifact.Artifact = requireStored(id, version).artifact

    private fun requireStored(id: String, version: Long): Stored = read(id, version) ?: throw IllegalArgumentException("query version is missing")

    private fun read(id: String, version: Long): Stored? {
        require(id.isNotBlank() && version > 0) { "query identity and positive version are required" }
        return database.connection().use { connection ->
            connection.prepareStatement("SELECT * FROM query_definition_version WHERE definition_id=? AND version=?").use {
                it.setString(1, id)
                it.setLong(2, version)
                it.executeQuery().use { rows ->
                    if (!rows.next()) null else stored(rows).also { stored ->
                        check(stored.view.definitionId == id && stored.view.definitionVersion == version) { "query artifact identity differs from requested version" }
                    }
                }
            }
        }
    }

    private fun stored(row: ResultSet): Stored {
        val artifact = OlapJsonUtils.mapper().treeToValue(
            BoundedJson.decode(row.getString("artifact_json").toByteArray(Charsets.UTF_8), limits), QueryDefinitionArtifact.Artifact::class.java)
        val view = QueryDefinitionArtifact.decode(artifact, limits)
        check(row.getString("definition_id") == view.definitionId && row.getLong("version") == view.definitionVersion &&
            row.getString("artifact_sha256") == artifact.sha256 && row.getString("scenario_id") == view.scenarioId &&
            row.getLong("scenario_version") == view.scenarioVersion && row.getString("release_id") == view.releaseId &&
            row.getString("release_version") == view.releaseVersion && row.getString("subject_area_id") == view.subjectAreaId &&
            row.getLong("subject_area_version") == view.subjectAreaVersion && row.getString("name") == view.name &&
            row.getString("catalog_hash") == view.catalogHash && row.getString("capability_hash") == view.capabilityHash &&
            row.getString("intent_hash") == view.intentHash) { "query artifact differs from indexed identity or publication" }
        return Stored(view, artifact)
    }

    override fun find(scenarioId: String?, scenarioVersion: Long?, releaseId: String?, releaseVersion: String?): List<QueryDefinitionView> =
        page(scenarioId, scenarioVersion, releaseId, releaseVersion, 0, Int.MAX_VALUE)

    fun page(scenarioId: String?, scenarioVersion: Long?, releaseId: String?, releaseVersion: String?, offset: Int, limit: Int): List<QueryDefinitionView> {
        require(offset >= 0 && limit > 0) { "invalid query page" }
        return database.inReadSnapshot {
            database.connection().use { connection ->
                connection.prepareStatement("""
                    SELECT v.* FROM query_definition d JOIN query_definition_version v ON v.definition_id=d.id AND v.version=d.current_version
                    WHERE (? IS NULL OR v.scenario_id=?) AND (? IS NULL OR v.scenario_version=?)
                    AND (? IS NULL OR v.release_id=?) AND (? IS NULL OR v.release_version=?)
                    ORDER BY v.definition_id LIMIT ? OFFSET ?
                """.trimIndent()).use {
                    it.setString(1, scenarioId)
                    it.setString(2, scenarioId)
                    it.setObject(3, scenarioVersion)
                    it.setObject(4, scenarioVersion)
                    it.setString(5, releaseId)
                    it.setString(6, releaseId)
                    it.setString(7, releaseVersion)
                    it.setString(8, releaseVersion)
                    it.setInt(9, limit)
                    it.setInt(10, offset)
                    it.executeQuery().use { rows -> buildList { while (rows.next()) add(stored(rows).view) } }
                }
            }
        }
    }

    private data class Stored(val view: QueryDefinitionView, val artifact: QueryDefinitionArtifact.Artifact)
}
