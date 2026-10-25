package com.olap.prototype.engine.executable

import com.fasterxml.jackson.annotation.JsonIgnore
import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.connector.SourceSqlRequest
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.contract.JdbcRelationCheck
import com.olap.prototype.contract.JdbcSourceRequirement
import com.olap.prototype.warehouse.contract.SourceReadBasis
import java.util.Collections

/** 完成原生编译后一次性交出的完整SQL合同，不保留可后补字段。 */
class ExecutableLoweringResult(val sql: String, val dataSourceId: String, val connectorRef: ConnectorRef,
    @get:JsonIgnore val compilationSupport: CompilationSupport, val dialect: String, parameters: List<JdbcParameter>,
    aliasMapping: Map<String, String>, aliasDisplayNames: Map<String, String>, sourceRequirements: List<JdbcSourceRequirement>,
    relationChecks: List<JdbcRelationCheck>, val outputLayout: ExecutableSourceLayout) {
    val parameters: List<JdbcParameter> = java.util.List.copyOf(parameters)
    val aliasMapping: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(aliasMapping))
    val aliasDisplayNames: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(aliasDisplayNames))
    val sourceRequirements: List<JdbcSourceRequirement> = java.util.List.copyOf(sourceRequirements)
    val relationChecks: List<JdbcRelationCheck> = java.util.List.copyOf(relationChecks)
    fun toSourceRequest(operation: String, expectedReadBasis: SourceReadBasis.Versioned?): SourceSqlRequest = SourceSqlRequest(operation,
        sql, parameters, aliasMapping, aliasDisplayNames, outputLayout.fields.associate { it.name to it.type }, sourceRequirements, relationChecks, expectedReadBasis)
    init {
        require(listOf(sql, dataSourceId, dialect).all { it.isNotBlank() }) { "executable SQL, source and dialect must be explicit" }
        require(this.aliasMapping.isNotEmpty() && this.aliasDisplayNames.keys == this.aliasMapping.keys) { "executable aliases and display names must have identical nonempty output keys" }
        require(this.aliasMapping.keys.toList() == outputLayout.fields.map { it.name }) { "executable aliases differ from the source physical column layout or order" }
        this.aliasMapping.forEach { (result, lowered) -> require(result.isNotBlank() && lowered.isNotBlank() && this.aliasDisplayNames.getValue(result).isNotBlank()) { "executable output aliases and display names must be nonempty" } }
        require(this.aliasMapping.values.distinct().size == this.aliasMapping.size) { "executable result aliases must be unique" }
        require(this.sourceRequirements.all { it.dataSourceId == dataSourceId && it.dialect == dialect && it.connectorRef == connectorRef }) { "source requirement is outside compiled datasource/dialect" }
    }
}
