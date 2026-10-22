package com.olap.prototype.release.catalog

import com.olap.prototype.release.contract.ReleaseCatalogProvider
import com.olap.prototype.release.contract.ReleaseCatalogRepository
import com.olap.prototype.release.contract.ReleaseCatalogRequest
import com.olap.prototype.release.contract.ReleaseCatalogView
import com.olap.prototype.contract.ProducerContracts

class ReleaseCatalogProducer(private val repository: ReleaseCatalogRepository) : ReleaseCatalogProvider {
    override fun produce(request: ReleaseCatalogRequest): ReleaseCatalogView = ReleaseCatalogView(
        request.scenarioId, request.scenarioVersion,
        ProducerContracts.requireText(repository.findScenarioDisplayName(request.scenarioId, request.scenarioVersion, request.releaseId, request.releaseVersion), "scenarioDisplayName"),
        request.releaseId, request.releaseVersion,
        ProducerContracts.requireText(repository.findReleaseDisplayName(request.releaseId, request.releaseVersion), "releaseDisplayName"),
        repository.findReleaseObjectVersionRefs(request.releaseId, request.releaseVersion),
        repository.findReleaseMetricDimensionCapabilities(request.releaseId, request.releaseVersion, request.scenarioId, request.scenarioVersion),
        repository.findReleaseDimensionMappings(request.releaseId, request.releaseVersion),
        repository.findReleaseTimeAnchorRules(request.releaseId, request.releaseVersion),
        repository.findReleaseUnavailableReasons(request.releaseId, request.releaseVersion, request.scenarioId, request.scenarioVersion),
        repository.findReleaseDiagnostics(request.releaseId, request.releaseVersion, request.scenarioId, request.scenarioVersion),
    )
}
