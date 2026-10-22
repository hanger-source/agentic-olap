package com.olap.prototype.release.catalog

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.query.QueryComputation
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.definition.*
import com.olap.prototype.release.contract.ReleaseCatalogView
import com.olap.prototype.storage.PlatformDatabase
import com.olap.prototype.util.OlapJsonUtils
import java.nio.file.Path

/** 同一公开发布经文件交接后真实保存/重读查询定义，不使用内存假仓储。 */
internal class PublishedQueryDefinitionFixture(directory: Path, name: String, release: ReleaseCatalogView, val publication: PublishedExecutableCatalog) : AutoCloseable {
    private val database = PlatformDatabase.inMemory()
    val service: QueryDefinitionService
    init {
        val mapper = OlapJsonUtils.mapper()
        val capability = publication.queryCapability
        val semantic = PublishedSemanticCatalogProducer().produce(capability, release, publication)
        val entry = PublishedQueryEntry(capability, QueryIntentHash.compute(mapper.valueToTree(publication)), semantic.sha256, semantic)
        val handoff = directory.resolve("$name-input.json")
        mapper.writeValue(handoff.toFile(), FrozenQueryCapabilityFiles.Input("olap-query-construction-input/v15", listOf(entry)))
        val capabilities = FrozenQueryCapabilityFiles(handoff, RuntimeDeploymentLimits.io)
        service = QueryDefinitionService(JdbcQueryDefinitionRepository(database, RuntimeDeploymentLimits.io), capabilities,
            NativeValidator { intent, checked -> QueryNativeValidation.validate(intent, checked, TestConnectors.registry) })
    }

    fun command(id: String, computation: QueryComputation.Scope): QueryDefinitionCommand = QueryDefinitionCommand(id, null,
        requireNotNull(publication.queryCapability.capabilityHash), "真实完成查询", "scenario", 1, "release", "1", intent(computation))

    fun intent(computation: QueryComputation.Scope): JsonNode = OlapJsonUtils.mapper().valueToTree(mapOf(
        "formatVersion" to PublishedQueryContract.FORMAT_VERSION, "scenarioId" to "scenario", "scenarioVersion" to 1,
        "releaseId" to "release", "releaseVersion" to "1", "computation" to computation))

    override fun close() = database.close()
}
