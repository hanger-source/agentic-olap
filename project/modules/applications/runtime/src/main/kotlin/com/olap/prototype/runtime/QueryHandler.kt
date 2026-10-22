package com.olap.prototype.runtime

import com.olap.prototype.codec.GovernanceProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.definition.QueryNativeValidation
import com.olap.prototype.warehouse.WarehouseExecutionControl
import java.security.MessageDigest
import java.util.HexFormat

/** 无来源、无保存查询仓库的原生验证；输入已经是正式模型，不重新解释查询AST。 */
internal class QueryHandler(private val connectors: ConnectorRegistry, private val budgets: RuntimeBudgets) {
    private data class Loaded(val entry: PublishedQueryEntry, val hash: String, val bytes: Long)
    private val capabilities = linkedMapOf<QueryCapabilityRequest, Loaded>()
    private var bytes = 0L

    fun execute(command: QueryWorkerCommand, control: WarehouseExecutionControl): QueryWorkerResult {
        control.checkCancellation()
        return when (command) {
            is QueryWorkerCommand.Load -> load(command.entry, control)
            is QueryWorkerCommand.Unload -> {
                val loaded = capabilities[command.coordinate] ?: error("exact capability is not loaded")
                val entry = loaded.entry
                require(entry.catalogHash == command.catalogHash && entry.capability.capabilityHash == command.capabilityHash) { "unload must name exact immutable content" }
                capabilities.remove(command.coordinate); bytes -= loaded.bytes
                QueryWorkerResult.Unloaded(command.coordinate)
            }
            is QueryWorkerCommand.Validate -> {
                val entry = capabilities[command.intent.publication]?.entry ?: error("exact published capability is not loaded")
                QueryNativeValidation.validate(command.intent, entry.capability, connectors)
                control.checkCancellation()
                QueryWorkerResult.Validated(requireNotNull(entry.capability.capabilityHash))
            }
        }
    }
    private fun load(entry: PublishedQueryEntry, control: WarehouseExecutionControl): QueryWorkerResult.Loaded {
        val maximum = minOf(budgets.catalogBytes, budgets.io.inputFileBytes.toLong())
        val wire = GovernanceProto.toProto(entry)
        if (wire.serializedSize > maximum) throw RuntimeResourceLimitException("CAPABILITY_CACHE_BUDGET_EXCEEDED")
        val encoded = ProtoBytes.encode(wire, maximum.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded))
        val coordinate = PublishedQueryEntry.key(entry.capability)
        capabilities[coordinate]?.let {
            require(it.hash == hash) { "same capability identity has different immutable content" }
            control.checkCancellation()
            return receipt(coordinate, it.entry)
        }
        val size = encoded.size.toLong()
        val maximumEntries = minOf(budgets.catalogEntries, budgets.io.inputFiles)
        while (capabilities.isNotEmpty() && (capabilities.size >= maximumEntries || size > maximum - bytes)) {
            val oldest = capabilities.entries.first(); bytes -= oldest.value.bytes; capabilities.remove(oldest.key)
        }
        control.checkCancellation()
        capabilities[coordinate] = Loaded(entry, hash, size); bytes += size
        return receipt(coordinate, entry)
    }
    private fun receipt(coordinate: QueryCapabilityRequest, entry: PublishedQueryEntry) =
        QueryWorkerResult.Loaded(coordinate, entry.catalogHash, requireNotNull(entry.capability.capabilityHash))
}
