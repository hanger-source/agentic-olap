package com.olap.prototype.connector

import com.olap.prototype.api.ResultValueType
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.contract.JdbcRelationCheck
import com.olap.prototype.contract.JdbcSourceRequirement
import com.olap.prototype.connector.SourceCapabilities.Capability

/**
 * 核心已编译的主 SQL 与完整结果合同；访问插件不接收业务公式。
 * 主 SQL 拥有计算、选行与结果行序语义，列别名和输出类型不是另一份排序键合同。
 * 协议封装、分页与物化须保留主 SQL 的这些语义，不解析 SQL 后自行重建或聚合重排。
 * 没有最终排序时不承诺稳定行序，排序键未区分的并列行也不额外确定顺序。
 */
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

    /**
     * 计划解释与执行准入消费同一份要求；不据此声称来源已经兑现能力。
     * 非空原生类型也属于约束采用，不以 JOIN、聚合或唯一键归约为前提。
     * CONSISTENT_READ 要求保证覆盖本次计算，不限定事务实现，也不要求可重放的版本坐标。
     */
    val requiredCapabilities: Set<Capability> get() = buildSet {
        if (expectedReadBasis != null) add(Capability.PINNED_READ)
        if (parameters.isNotEmpty() || relationChecks.any { it.parameters.isNotEmpty() })
            add(Capability.PARAMETER_BINDING)
        val constrained = sourceRequirements.any { it.constraintUses.isNotEmpty() }
        if (relationChecks.isNotEmpty() || constrained) add(Capability.CONSISTENT_READ)
        if (constrained) add(Capability.CONSTRAINT_VERIFICATION)
        if (sourceRequirements.any { it.readsRows() && it.evidence.sourceCollections.isNotEmpty() })
            add(Capability.SEALED_DATASET_VERIFICATION)
    }

    fun requireCapabilities(source: String, capabilities: SourceCapabilities) {
        expectedReadBasis?.let {
            require(it.pinning == com.olap.prototype.warehouse.contract.SourcePinning.EXACT_VERSION) { "requested read basis cannot be pinned" }
        }
        requiredCapabilities.forEach { capabilities.require(source, operation, it) }
    }

    override fun equals(other: Any?): Boolean = other is SourceSqlRequest && operation == other.operation && sql == other.sql && parameters == other.parameters && aliasMapping == other.aliasMapping && aliasDisplayNames == other.aliasDisplayNames && outputTypes == other.outputTypes && sourceRequirements == other.sourceRequirements && relationChecks == other.relationChecks && expectedReadBasis == other.expectedReadBasis
    override fun hashCode(): Int = listOf(operation, sql, parameters, aliasMapping, aliasDisplayNames, outputTypes, sourceRequirements, relationChecks, expectedReadBasis).hashCode()
}
