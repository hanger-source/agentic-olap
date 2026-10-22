package com.olap.prototype.contract

import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.warehouse.contract.JdbcTableConstraints
import com.olap.prototype.warehouse.contract.PhysicalSourceConstraints
import com.olap.prototype.warehouse.contract.SourceBindingRef

/** 原生函数实际使用的精确已采用物理表；不伪造字段或逻辑表。 */
data class NativeTableDependency(
    val physicalTableId: String,
    val physicalTableVersion: Long,
    val dataSourceId: String,
    val dialect: String,
    val table: JdbcTableConstraints.Table,
    val sourceConstraints: PhysicalSourceConstraints,
) {
    init {
        ProducerContracts.requireText(physicalTableId, "native physical table id")
        require(physicalTableVersion > 0) { "native table version must be exact" }
        ProducerContracts.requireText(dataSourceId, "native table source")
        ProducerContracts.requireText(dialect, "native table dialect")
    }
    fun key(): String = "$physicalTableId@$physicalTableVersion"
    fun bindingRef(): SourceBindingRef = SourceBindingRef(dataSourceId, sourceConstraints.sourceIdentityHash)
    fun matchesSource(binding: SourceBindingRef, connector: ConnectorRef, sourceDialect: String): Boolean =
        bindingRef() == binding && sourceConstraints.connectorRef == connector && dialect == sourceDialect
    fun requireSource(binding: SourceBindingRef, connector: ConnectorRef, sourceDialect: String) {
        require(matchesSource(binding, connector, sourceDialect)) { "native table differs from its exact source binding, connector or dialect" }
    }
}
