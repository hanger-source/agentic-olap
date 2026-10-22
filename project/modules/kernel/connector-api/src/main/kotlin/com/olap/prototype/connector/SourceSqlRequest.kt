package com.olap.prototype.connector

import com.olap.prototype.api.ResultValueType
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.contract.JdbcRelationCheck
import com.olap.prototype.contract.JdbcSourceRequirement
import com.olap.prototype.connector.SourceCapabilities.Capability

/** 核心已编译的 SQL 与完整结果合同；访问插件不接收业务公式。 */
class SourceSqlRequest(
    val operation: String,
    val sql: String,
    parameters: List<JdbcParameter>,
    aliasMapping: Map<String, String>,
    aliasDisplayNames: Map<String, String>,
    outputTypes: Map<String, ResultValueType>,
    sourceRequirements: List<JdbcSourceRequirement>,
    relationChecks: List<JdbcRelationCheck>,
    val expectedReadBasis: com.olap.prototype.warehouse.contract.SourceReadBasis.Versioned?,
) {
    val parameters: List<JdbcParameter> = java.util.List.copyOf(parameters)
    val aliasMapping: Map<String, String> = java.util.Collections.unmodifiableMap(LinkedHashMap(aliasMapping))
    val aliasDisplayNames: Map<String, String> = java.util.Map.copyOf(aliasDisplayNames)
    val outputTypes: Map<String, ResultValueType> = java.util.Map.copyOf(outputTypes)
    val sourceRequirements: List<JdbcSourceRequirement> = java.util.List.copyOf(sourceRequirements)
    val relationChecks: List<JdbcRelationCheck> = java.util.List.copyOf(relationChecks)

    init {
        require(operation.isNotBlank() && sql.isNotBlank()) { "compiled source SQL and operation are required" }
        require(this.aliasMapping.isNotEmpty() && this.aliasDisplayNames.keys == this.aliasMapping.keys &&
            this.outputTypes.keys == this.aliasMapping.values.toSet()) { "source SQL requires its complete output contract" }
    }

    fun requireCapabilities(source: String, capabilities: SourceCapabilities) {
        expectedReadBasis?.let {
            require(it.pinning == com.olap.prototype.warehouse.contract.SourcePinning.EXACT_VERSION) { "requested read basis cannot be pinned" }
            capabilities.require(source, operation, Capability.PINNED_READ)
        }
        if (parameters.isNotEmpty() || relationChecks.any { it.parameters.isNotEmpty() })
            capabilities.require(source, operation, Capability.PARAMETER_BINDING)
        val constrained = sourceRequirements.any { it.constraintUses.isNotEmpty() }
        if (relationChecks.isNotEmpty() || constrained) capabilities.require(source, operation, Capability.CONSISTENT_READ)
        if (constrained) capabilities.require(source, operation, Capability.CONSTRAINT_VERIFICATION)
        if (sourceRequirements.any { it.readsRows() && it.evidence.sourceCollections.isNotEmpty() })
            capabilities.require(source, operation, Capability.SEALED_DATASET_VERIFICATION)
    }

    override fun equals(other: Any?): Boolean = other is SourceSqlRequest && operation == other.operation && sql == other.sql && parameters == other.parameters && aliasMapping == other.aliasMapping && aliasDisplayNames == other.aliasDisplayNames && outputTypes == other.outputTypes && sourceRequirements == other.sourceRequirements && relationChecks == other.relationChecks && expectedReadBasis == other.expectedReadBasis
    override fun hashCode(): Int = listOf(operation, sql, parameters, aliasMapping, aliasDisplayNames, outputTypes, sourceRequirements, relationChecks, expectedReadBasis).hashCode()
}
