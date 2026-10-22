package com.olap.prototype.engine.catalog

import com.olap.prototype.connector.ConnectorRef

/** 一个原生查询只能消费一个精确部署绑定；输入位置不含连接参数或凭据。 */
class SourceBindingConflictException(inputs: List<Input>) : IllegalArgumentException("one query requires one exact source binding; conflicting inputs: ${inputs.joinToString { "${it.scopePath}:${it.anchor}=${it.dataSourceId}@${it.sourceIdentityHash}" }}") {
    val inputs: List<Input> = java.util.List.copyOf(inputs)
    val code: String get() = "SOURCE_BINDING_CONFLICT"
    data class Input(val scopePath: List<String>, val anchor: String, val dataSourceId: String, val sourceIdentityHash: String,
        val connectorRef: ConnectorRef, val dialect: String, val physicalTableId: String, val physicalTableVersion: String)
    companion object {
        fun input(binding: ExecutablePhysicalExecutionBinding, scopePath: List<String>, anchor: String): Input = Input(scopePath.toList(), anchor,
            binding.dataSourceId, binding.sourceConstraints.sourceIdentityHash, binding.connectorRef, binding.dialect, binding.physicalTableId, binding.physicalTableVersion)
        fun requireSingleBinding(inputs: List<Input>) {
            val first = inputs.firstOrNull() ?: return
            if (inputs.any { it.dataSourceId != first.dataSourceId || it.sourceIdentityHash != first.sourceIdentityHash || it.connectorRef != first.connectorRef || it.dialect != first.dialect })
                throw SourceBindingConflictException(inputs)
        }
    }
}
