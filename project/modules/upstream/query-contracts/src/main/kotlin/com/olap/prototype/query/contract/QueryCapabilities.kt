package com.olap.prototype.query.contract

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.olap.prototype.calculation.MetricInstanceRef
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.engine.catalog.ExecutableCatalogManifest
import com.olap.prototype.query.contract.catalog.CatalogJson
import com.olap.prototype.runtime.BoundedJson
import com.olap.prototype.runtime.JsonResourceLimits
import com.olap.prototype.runtime.RuntimeResourceLimitException
import com.olap.prototype.util.OlapJsonUtils
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections
import java.util.HexFormat

data class QueryCapabilityRequest(val scenarioId: String, val scenarioVersion: Long, val releaseId: String, val releaseVersion: String) {
    init {
        ProducerContracts.requireText(scenarioId, "scenarioId"); ProducerContracts.requireVersion(scenarioVersion, "scenarioVersion")
        ProducerContracts.requireText(releaseId, "releaseId"); ProducerContracts.requireText(releaseVersion, "releaseVersion")
    }
}
fun interface QueryCapabilityProvider { fun produce(request: QueryCapabilityRequest): QueryCapabilityView }
fun interface QueryCapabilityWriter { fun save(capability: QueryCapabilityView, createdAt: Instant) }
interface PublishedQueryEntryProvider : QueryCapabilityProvider {
    fun require(request: QueryCapabilityRequest): PublishedQueryEntry
    override fun produce(request: QueryCapabilityRequest): QueryCapabilityView = require(request).capability
}

/** 对外只交去除物理定位的中性编译目录，JSON内容始终防御复制。 */
class QueryNativeDefinitions(catalog: JsonNode) {
    private val content: JsonNode = catalog.deepCopy()
    val catalog: JsonNode get() = content.deepCopy()
    init {
        require(content.isObject && content.path("manifest").path("schemaVersion").textValue() == ExecutableCatalogManifest.SCHEMA_VERSION) { "current neutral query compilation catalog is required" }
    }
    override fun equals(other: Any?): Boolean = other is QueryNativeDefinitions && content == other.content
    override fun hashCode(): Int = content.hashCode()
}

object QueryCapabilityHash {
    fun compute(capability: QueryCapabilityView): String {
        val content = OlapJsonUtils.mapper().valueToTree<ObjectNode>(capability)
        content.remove("capabilityHash")
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(OlapJsonUtils.mapper().writeValueAsBytes(content)))
    }
    fun attach(capability: QueryCapabilityView): QueryCapabilityView = QueryCapabilityView(capability.scenario, capability.subjectAreaCapabilityContext,
        capability.releaseId, capability.releaseVersion, capability.releaseDisplayName, capability.metricCandidates, capability.memberCandidates,
        capability.analysisDimensionCandidates, capability.timeAnalysisDimensionCandidates, capability.filterCandidates, capability.relationFilterCandidates,
        capability.temporalClockCandidates, capability.postProcessCapabilities, capability.metricAnalysisDimensionMatrix, capability.disabledReasons,
        capability.diagnostics, capability.executionTypes, capability.nativeDefinitions, compute(capability))
    fun requireValid(capability: QueryCapabilityView): String = compute(capability).also {
        if (capability.capabilityHash != it) throw ProducerContractException("query capability hash mismatch")
    }
}

/** 消费冻结调用来源，不从维度可用性矩阵重建指标事件闭包。 */
object QueryCapabilityExecutionGraph {
    fun requireMetricEventKeys(capability: QueryCapabilityView, metricInstances: Set<MetricInstanceRef>): Set<String> {
        val result = linkedSetOf<String>()
        metricInstances.forEach { instance ->
            val candidate = capability.metricCandidates.firstOrNull { it.instance == instance } ?: throw IllegalArgumentException("metric instance is not published: $instance")
            candidate.sources.forEach { result.add("${it.event.eventId}@${it.event.eventVersion}") }
        }
        return Collections.unmodifiableSet(result)
    }
}

/** 文件只是不可变发布交接，不拥有查询执行或业务定义管理。 */
class FrozenQueryCapabilityFiles(file: Path, limits: JsonResourceLimits) : PublishedQueryEntryProvider {
    private val loaded: Map<QueryCapabilityRequest, PublishedQueryEntry>
    init {
        val envelope = BoundedJson.read(file, limits)
        if (!envelope.path("entries").isArray || envelope.path("entries").size() > limits.inputFiles) throw RuntimeResourceLimitException("FROZEN_CAPABILITIES_EXCEEDED")
        val input = CatalogJson.read(envelope, Input::class.java)
        loaded = Collections.unmodifiableMap(linkedMapOf<QueryCapabilityRequest, PublishedQueryEntry>().apply {
            input.entries.forEach { require(putIfAbsent(key(it.capability), it) == null) { "duplicate exact capability coordinate" } }
        })
    }
    override fun require(request: QueryCapabilityRequest): PublishedQueryEntry = loaded[request] ?: throw IllegalArgumentException("exact published capability is unavailable")
    fun entries(): List<PublishedQueryEntry> = java.util.List.copyOf(loaded.values)
    class Input(@param:JsonProperty(required = true) val formatVersion: String, @param:JsonProperty(required = true) entries: List<PublishedQueryEntry>) {
        val entries: List<PublishedQueryEntry> = java.util.List.copyOf(entries)
        init { require(formatVersion == "olap-query-construction-input/v15") { "invalid query construction input" } }
        override fun equals(other: Any?): Boolean = other is Input && formatVersion == other.formatVersion && entries == other.entries
        override fun hashCode(): Int = listOf(formatVersion, entries).hashCode()
    }
    companion object { fun key(capability: QueryCapabilityView): QueryCapabilityRequest = QueryCapabilityRequest(capability.scenario.scenarioId, capability.scenario.scenarioVersion, capability.releaseId, capability.releaseVersion) }
}
