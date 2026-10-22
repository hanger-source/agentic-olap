package com.olap.prototype.governance.application

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.governance.contract.SubjectAreaReleaseBuildRequest
import com.olap.prototype.governance.contract.SubjectAreaReleaseBuildResult
import com.olap.prototype.management.governance.CanonicalReleaseRepository
import com.olap.prototype.query.contract.PublishedExecutableCatalog
import com.olap.prototype.query.contract.QueryCapabilityView
import com.olap.prototype.query.contract.QueryCapabilityWriter
import com.olap.prototype.release.contract.*
import java.io.IOException

/** 冻结、编译与封存在同一事务完成；所有编译消费即将封存的同一组不可变行。 */
class SubjectAreaReleaseBuildService(private val repository: CanonicalReleaseRepository, private val freezer: ReleaseFreezer, private val artifacts: ReleaseArtifactWriter,
    private val executable: ReleaseExecutableCatalogCompiler, private val semantic: ReleaseSemanticCatalogCompiler, private val capabilities: ReleaseQueryCapabilityCompiler,
    private val capabilityWriter: QueryCapabilityWriter) {
    fun build(request: SubjectAreaReleaseBuildRequest): SubjectAreaReleaseBuildResult = repository.inTransaction {
        val frozen = freezer.freeze(request)
        val compiled = frozen.objects.filter { it.objectType == "SCENARIO" }.map { row ->
            val snapshot = try { GovernanceProto.fromProto(ProtoBytes.decode(ProtoBytes.stored(row.snapshotProto),
                com.olap.prototype.wire.governance.ReleaseScenarioSnapshot.getDefaultInstance())) }
            catch (failure: IOException) { throw ProducerContractException("complete exact frozen scenario selection is required", failure) }
            catch (failure: IllegalArgumentException) { throw ProducerContractException("complete exact frozen scenario selection is required", failure) }
            if (snapshot.scenario.scenarioId != row.objectId || snapshot.scenario.scenarioVersion != row.objectVersion)
                throw ProducerContractException("frozen scenario selection differs from its exact object reference")
            val producer = frozen.catalog(snapshot)
            val assets = executable.compileAssets(producer, snapshot.metrics)
            val capability = capabilities.produce(producer, snapshot, assets)
            val catalog = executable.assemble(producer, assets, capability)
            semantic.produce(capability, producer, catalog)
            CompiledScenario(producer, catalog, capability)
        }
        repository.save(frozen.release, frozen.objects, frozen.capabilities, frozen.unavailable, frozen.mappings, frozen.timeAnchors)
        compiled.forEach { scenario ->
            artifacts.saveCatalog(scenario.producer, scenario.executable, frozen.release.createdAt)
            capabilityWriter.save(scenario.capability, frozen.release.createdAt)
        }
        val release = frozen.release
        artifacts.finalizeArtifact(release.releaseId, release.releaseVersion, release.subjectAreaId, release.subjectAreaVersion, release.createdBy, release.createdAt)
        frozen.result()
    }
    private data class CompiledScenario(val producer: ReleaseCatalogView, val executable: PublishedExecutableCatalog, val capability: QueryCapabilityView)
}
