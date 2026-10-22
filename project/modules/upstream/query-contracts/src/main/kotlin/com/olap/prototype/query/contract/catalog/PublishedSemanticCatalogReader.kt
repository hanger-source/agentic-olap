package com.olap.prototype.query.contract.catalog

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.*
import com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset
import com.olap.prototype.query.contract.catalog.SemanticCatalogReads.AssetReference
import com.olap.prototype.query.contract.catalog.SemanticCatalogReads.AssetRequest
import com.olap.prototype.query.contract.catalog.SemanticCatalogReads.AssetResult
import com.olap.prototype.query.contract.catalog.SemanticCatalogReads.CatalogReference
import com.olap.prototype.query.contract.catalog.SemanticCatalogReads.CatalogRequest
import com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionReference
import com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionRequest
import com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionResult
import com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Kind
import com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Scenario
import com.olap.prototype.query.contract.catalog.SemanticCatalogReads.SearchItem
import com.olap.prototype.query.contract.catalog.SemanticCatalogReads.SearchPage
import com.olap.prototype.query.contract.catalog.SemanticCatalogReads.SearchRequest
import java.util.Locale

/** 在已经授权和核验的发布上直接读取类型化投影，不重新解释计算或读取草稿。 */
class PublishedSemanticCatalogReader(private val artifact: PublishedSemanticCatalog) {
    private val catalog = artifact.catalog
    val reference = CatalogReference(catalog.coordinate, artifact.sha256)

    fun read(request: CatalogRequest): PublishedSemanticCatalog {
        requireReference(request.reference)
        return artifact
    }
    fun search(request: SearchRequest): SearchPage {
        require(request.publication == catalog.coordinate.request()) { "semantic search publication differs from the authorized catalog" }
        val needle = request.query.lowercase(Locale.ROOT)
        val matches = assets().filter { (request.kind == null || it.reference.kind == request.kind) &&
            (needle.isEmpty() || needle in it.searchable.lowercase(Locale.ROOT)) }.toList()
        val start = minOf(request.offset, matches.size)
        val end = minOf(start.toLong() + request.limit, matches.size.toLong()).toInt()
        return SearchPage(matches.subList(start, end).map { SearchItem(it.reference, it.displayName, preview(it.summary)) },
            matches.size, request.offset, end.takeIf { it < matches.size })
    }
    fun asset(request: AssetRequest): AssetResult {
        requireReference(request.reference.catalog)
        val selected = assets().firstOrNull { it.reference == request.reference }
            ?: throw ProducerContractException("exact semantic asset is unavailable in the authorized publication")
        return AssetResult(selected.reference, selected.value)
    }
    fun definition(request: DefinitionRequest): DefinitionResult {
        requireReference(request.catalog)
        val selected = catalog.semanticDependencies.firstOrNull { dependency -> when (val wanted = request.reference) {
            is DefinitionReference.Calculation -> dependency is CalculationDependency && dependency.reference == wanted.reference
            is DefinitionReference.Fact -> dependency is FactDependency && dependency.reference == wanted.reference
            is DefinitionReference.Instance -> dependency is InstanceDependency && dependency.reference == wanted.reference
            is DefinitionReference.Member -> dependency is MemberDependency && dependency.reference == wanted.reference
        } } ?: throw ProducerContractException("exact definition is absent from the public semantic closure")
        return DefinitionResult(reference, selected)
    }

    private data class Entry(val reference: AssetReference, val value: Asset, val displayName: String, val summary: String, val searchable: String)
    private fun entry(kind: Kind, id: String, version: Long, value: Asset, displayName: String, summary: String = displayName, terms: List<String> = emptyList()): Entry =
        Entry(AssetReference(reference, kind, id, version), value, displayName, summary, (listOf(id, displayName, summary) + terms).joinToString(" "))
    private fun assets(): Sequence<Entry> = sequence {
        yield(entry(Kind.SCENARIO, catalog.coordinate.scenarioId, catalog.coordinate.scenarioVersion,
            Asset.ScenarioAsset(Scenario(catalog.coordinate.scenarioId, catalog.coordinate.scenarioVersion, catalog.displayName, catalog.sourceJoinContract,
                catalog.metrics.size, catalog.memberSources.size, catalog.relationFilters.size, catalog.dimensions.size, catalog.timeDimensions.size, catalog.filters.size)), catalog.displayName))
        catalog.metrics.forEach { yield(entry(Kind.METRIC, it.adoptionId, it.adoptionVersion, Asset.MetricAsset(it), it.displayName, it.businessMeaning.definition,
            listOf(it.code, it.businessMeaning.unit, it.businessMeaning.grain) + it.businessMeaning.limitations)) }
        catalog.memberSources.forEach { yield(entry(Kind.MEMBER, it.sourceId, it.sourceVersion, Asset.MemberAsset(it), it.displayName, terms = listOf(it.entity, it.role) + it.outputs.keys)) }
        catalog.relationFilters.forEach { yield(entry(Kind.RELATION_FILTER, it.id, it.version, Asset.RelationFilterAsset(it), it.displayName)) }
        catalog.dimensions.forEach { yield(entry(Kind.DIMENSION, it.id, it.version, Asset.DimensionAsset(it), it.displayName)) }
        catalog.timeDimensions.forEach { yield(entry(Kind.TIME_DIMENSION, it.id, it.version, Asset.TimeDimensionAsset(it), it.displayName)) }
        catalog.filters.forEach { yield(entry(Kind.FILTER, it.id, it.version, Asset.FilterAsset(it), it.displayName)) }
    }
    private fun requireReference(selected: CatalogReference) { require(selected == reference) { "exact semantic catalog version and hash are unavailable" } }
    private fun preview(value: String): String = value.substring(0, value.offsetByCodePoints(0, minOf(240, value.codePointCount(0, value.length))))
}
