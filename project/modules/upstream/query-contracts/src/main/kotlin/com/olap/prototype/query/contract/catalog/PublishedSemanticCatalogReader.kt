package com.olap.prototype.query.contract.catalog

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.query.contract.QueryIntentHash
import com.olap.prototype.util.OlapJsonUtils
import java.util.Locale

/** 读取同一已授权、已核验发布的公开投影；不重新解释计算，也不读取治理草稿。 */
class PublishedSemanticCatalogReader(artifact: PublishedSemanticCatalog) {
    private val mapper = OlapJsonUtils.mapper()
    private val document = mapper.valueToTree<ObjectNode>(artifact)
    private val catalog = document.required("catalog")
    private val reference = mapper.createObjectNode().apply {
        set<JsonNode>("coordinate", catalog.required("coordinate"))
        set<JsonNode>("sha256", document.required("sha256"))
    }

    fun read(operation: String, payload: JsonNode): JsonNode = when (operation) {
        "search-semantics" -> search(payload)
        "read-semantic-catalog" -> { requireReference(payload.required("catalogRef")); document.deepCopy() }
        "read-semantic-asset" -> readAsset(payload.required("assetReference"))
        "read-semantic-definition" -> readDefinition(payload)
        else -> throw ProducerContractException("unsupported semantic read operation: $operation")
    }

    private data class Asset(val kind: String, val id: JsonNode, val version: JsonNode, val value: JsonNode)

    private fun assets(): Sequence<Asset> = sequence {
        val coordinate = catalog.required("coordinate")
        val scenario = mapper.createObjectNode().apply {
            set<JsonNode>("id", coordinate.required("scenarioId"))
            set<JsonNode>("version", coordinate.required("scenarioVersion"))
            set<JsonNode>("displayName", catalog.required("displayName"))
            set<JsonNode>("sourceJoinContract", catalog.required("sourceJoinContract"))
            putObject("assetCounts").apply { for (kind in KINDS) put(kind, catalog.required(kind).size()) }
        }
        yield(Asset("scenario", scenario.required("id"), scenario.required("version"), scenario))
        for (kind in KINDS) {
            for (original in catalog.required(kind)) {
                val id = when (kind) { "metrics" -> "adoptionId"; "memberSources" -> "sourceId"; else -> "id" }
                val version = when (kind) { "metrics" -> "adoptionVersion"; "memberSources" -> "sourceVersion"; else -> "version" }
                val value = original.deepCopy<ObjectNode>()
                if (kind == "memberSources") {
                    value.set<JsonNode>("id", original.required(id))
                    value.set<JsonNode>("version", original.required(version))
                }
                yield(Asset(kind, original.required(id), original.required(version), value))
            }
        }
    }

    private fun search(payload: JsonNode): JsonNode {
        val query = payload.get("query")?.also { require(it.isTextual) { "semantic query must be text" } }?.textValue() ?: ""
        val offset = pageNumber(payload, "offset", 0, 0, Int.MAX_VALUE)
        val limit = pageNumber(payload, "limit", 20, 1, 200)
        val kind = payload.get("kind")?.also { require(it.isTextual) { "semantic kind must be text" } }?.textValue()
        require(kind == null || kind in KINDS || kind == "scenario") { "unsupported semantic asset kind" }
        val needle = query.lowercase(Locale.ROOT)
        val matches = assets().filter { asset ->
            (kind == null || asset.kind == kind) &&
                (needle.isEmpty() || strings(asset.value).joinToString(" ").lowercase(Locale.ROOT).contains(needle))
        }.toList()
        val end = minOf(offset.toLong() + limit, matches.size.toLong()).toInt()
        return mapper.createObjectNode().apply {
            val items = putArray("items")
            if (offset < matches.size) for (asset in matches.subList(offset, end)) {
                val summary = if (asset.kind == "metrics") asset.value.required("businessMeaning").required("definition").textValue()
                    else asset.value.required("displayName").textValue()
                items.addObject().apply {
                    set<JsonNode>("assetReference", assetReference(asset))
                    set<JsonNode>("displayName", asset.value.required("displayName"))
                    put("businessSummary", summary.substring(0, summary.offsetByCodePoints(0, minOf(240, summary.codePointCount(0, summary.length)))))
                }
            }
            put("total", matches.size)
            put("offset", offset)
            if (end < matches.size && offset < matches.size) put("nextOffset", end) else putNull("nextOffset")
        }
    }

    private fun readAsset(selected: JsonNode): JsonNode {
        exact(selected, setOf("catalogRef", "kind", "id", "version"))
        requireReference(selected.required("catalogRef"))
        require(selected.required("kind").isTextual && selected.required("id").isTextual &&
            selected.required("version").isIntegralNumber && selected.required("version").longValue() > 0) { "exact semantic asset identity required" }
        val asset = assets().firstOrNull { it.kind == selected.required("kind").textValue() &&
            it.id == selected.required("id") && selected.required("version").canConvertToLong() && it.version.longValue() == selected.required("version").longValue() }
            ?: throw ProducerContractException("exact semantic asset is unavailable in the handed publication")
        return mapper.createObjectNode().apply {
            set<JsonNode>("assetReference", selected.deepCopy())
            set<JsonNode>("asset", asset.value.deepCopy())
        }
    }

    private fun readDefinition(payload: JsonNode): JsonNode {
        requireReference(payload.required("catalogRef"))
        val kind = payload.required("kind")
        require(kind.isTextual && kind.textValue() in setOf("CALCULATION", "FACT", "INSTANCE", "MEMBER")) { "unsupported semantic definition kind" }
        val selected = payload.required("reference")
        require(selected.isObject) { "definition reference must be a typed object" }
        val definition = catalog.required("semanticDependencies").firstOrNull {
            it.required("kind") == kind && QueryIntentHash.compute(it.required("reference")) == QueryIntentHash.compute(selected)
        } ?: throw ProducerContractException("exact definition is absent from the public semantic closure")
        return mapper.createObjectNode().apply {
            set<JsonNode>("catalogRef", reference.deepCopy())
            set<JsonNode>("definition", definition.deepCopy())
        }
    }

    private fun assetReference(asset: Asset): ObjectNode = mapper.createObjectNode().apply {
        set<JsonNode>("catalogRef", reference.deepCopy())
        put("kind", asset.kind)
        set<JsonNode>("id", asset.id)
        set<JsonNode>("version", asset.version)
    }

    private fun requireReference(selected: JsonNode) {
        exact(selected, setOf("coordinate", "sha256"))
        require(QueryIntentHash.compute(selected) == QueryIntentHash.compute(reference)) { "exact semantic catalog version and hash are unavailable" }
    }

    private fun exact(value: JsonNode, fields: Set<String>) {
        require(value.isObject && value.fieldNames().asSequence().toSet() == fields) { "semantic reference fields differ from the contract" }
    }

    private fun pageNumber(payload: JsonNode, name: String, default: Int, minimum: Int, maximum: Int): Int {
        val node = payload.get(name) ?: return default
        require(node.isIntegralNumber && node.canConvertToInt() && node.intValue() in minimum..maximum) { "$name is outside allowed range" }
        return node.intValue()
    }

    private fun strings(value: JsonNode): Sequence<String> = sequence {
        if (value.isTextual) yield(value.textValue())
        else if (value.isContainerNode) for (child in value) yieldAll(strings(child))
    }

    companion object {
        private val KINDS = listOf("metrics", "memberSources", "relationFilters", "dimensions", "timeDimensions", "filters")
    }
}
