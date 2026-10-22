package com.olap.prototype.runtime

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.definition.QueryNativeValidation
import com.olap.prototype.server.ServerInputs.fields
import com.olap.prototype.server.ServerInputs.text
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.WarehouseExecutionControl

/** 无来源、无保存查询仓库的原生验证worker；查询持久化只属于metadata。 */
internal class QueryHandler(private val connectors: ConnectorRegistry, private val budgets: RuntimeBudgets) {
    private val mapper = OlapJsonUtils.mapper()
    private val capabilities = linkedMapOf<QueryCapabilityRequest, PublishedQueryEntry>()
    private var bytes = 0L

    fun execute(operation: String, payload: JsonNode, control: WarehouseExecutionControl): Any {
        control.checkCancellation()
        return when (operation) {
            "load-capability" -> load(payload, control)
            "unload-capability" -> {
                fields(payload, setOf("coordinate", "catalogHash", "capabilityHash"))
                val coordinate = mapper.treeToValue(payload.required("coordinate"), QueryCapabilityRequest::class.java)
                val entry = capabilities[coordinate] ?: error("exact capability is not loaded")
                require(entry.catalogHash == text(payload, "catalogHash") && entry.capability.capabilityHash == text(payload, "capabilityHash")) { "unload must name one exact loaded capability" }
                capabilities.remove(coordinate); bytes -= mapper.writeValueAsBytes(entry).size
                mapOf("unloaded" to true, "coordinate" to coordinate)
            }
            "validate-query" -> {
                val entry = capabilities[PublishedQueryContract.coordinate(payload)] ?: error("exact published capability is not loaded")
                QueryNativeValidation.validate(payload, entry.capability, connectors)
                control.checkCancellation()
                mapOf("valid" to true, "capabilityHash" to entry.capability.capabilityHash)
            }
            else -> throw IllegalArgumentException("unsupported query validation worker operation")
        }
    }

    private fun load(payload: JsonNode, control: WarehouseExecutionControl): Any {
        val entry = mapper.treeToValue(payload, PublishedQueryEntry::class.java)
        val coordinate = PublishedQueryEntry.key(entry.capability)
        capabilities[coordinate]?.let {
            require(mapper.valueToTree<JsonNode>(it) == mapper.valueToTree<JsonNode>(entry)) { "same capability identity has different immutable content" }
            return receipt(coordinate, it)
        }
        val size = mapper.writeValueAsBytes(entry).size.toLong()
        val maximum = minOf(budgets.catalogBytes, budgets.io.inputFileBytes.toLong())
        val maximumEntries = minOf(budgets.catalogEntries, budgets.io.inputFiles)
        if (size > maximum) throw RuntimeResourceLimitException("CAPABILITY_CACHE_BUDGET_EXCEEDED")
        while (capabilities.isNotEmpty() && (capabilities.size >= maximumEntries || size > maximum - bytes)) {
            val oldest = capabilities.entries.first()
            bytes -= mapper.writeValueAsBytes(oldest.value).size; capabilities.remove(oldest.key)
        }
        control.checkCancellation()
        capabilities[coordinate] = entry; bytes += size
        return receipt(coordinate, entry)
    }

    private fun receipt(coordinate: QueryCapabilityRequest, entry: PublishedQueryEntry) = mapOf(
        "coordinate" to coordinate, "capabilityHash" to entry.capability.capabilityHash, "catalogHash" to entry.catalogHash)
}
