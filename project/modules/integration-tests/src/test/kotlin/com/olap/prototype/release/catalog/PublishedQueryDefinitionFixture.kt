package com.olap.prototype.release.catalog

import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.codec.PublicationProto
import com.olap.prototype.integration.RuntimeDeploymentLimits
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.calculation.Calculation
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.definition.*
import com.olap.prototype.release.contract.ReleaseCatalogView
import com.olap.prototype.storage.PlatformDatabase
import java.nio.file.Files
import java.nio.file.Path

/** 同一公开发布经文件交接后真实保存/重读查询定义，不使用内存假仓储。 */
internal class PublishedQueryDefinitionFixture(directory: Path, name: String, release: ReleaseCatalogView, val publication: PublishedExecutableCatalog) : AutoCloseable {
    private val database = PlatformDatabase.inMemory()
    val service: QueryDefinitionService
    init {
        val capability = publication.queryCapability
        val semantic = PublishedSemanticCatalogProducer().produce(capability, release, publication)
        val entry = PublishedQueryEntry(capability, PublicationProto.contentHash(publication), semantic.sha256, semantic)
        val handoff = directory.resolve("$name-input.pb")
        Files.write(handoff, ProtoBytes.encode(GovernanceProto.toProto(entry), RuntimeDeploymentLimits.io.inputFileBytes))
        val capabilities = object : PublishedQueryEntryProvider {
            override fun require(request: QueryCapabilityRequest): PublishedQueryEntry = PublicationProto.requireValid(GovernanceProto.fromProto(ProtoBytes.decode(Files.readAllBytes(handoff),
                com.olap.prototype.wire.governance.PublishedQueryEntry.getDefaultInstance(), RuntimeDeploymentLimits.io.inputFileBytes))).also {
                require(PublishedQueryEntry.key(it.capability) == request) { "query fixture requires the exact handed publication" }
            }
        }
        service = QueryDefinitionService(JdbcQueryDefinitionRepository(database, RuntimeDeploymentLimits.io), capabilities,
            NativeValidator { intent, checked, executionMode -> QueryNativeValidation.validate(intent, checked, TestConnectors.registry, executionMode) })
    }

    fun command(id: String, computation: Calculation.Scope): QueryDefinitionCommand = QueryDefinitionCommand(id, null,
        requireNotNull(publication.queryCapability.capabilityHash), "真实完成查询", intent(computation), null)

    fun intent(computation: Calculation.Scope): QueryIntent = QueryIntent(publication.coordinate, computation)

    override fun close() = database.close()
}
