package com.olap.prototype.contract

import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.warehouse.contract.PhysicalDataContract
import com.olap.prototype.warehouse.contract.PhysicalSourceAdoption
import com.olap.prototype.warehouse.contract.SourceBindingRef

/** 发布明确选择的原生 TABLE 参数；数据合同与执行采用分别保留。 */
data class NativeTableDependency(val dataContract: PhysicalDataContract, val adoption: PhysicalSourceAdoption) {
    init { adoption.requireContract(dataContract) }
    val physicalTableId: String get() = dataContract.reference.id
    val physicalTableVersion: Long get() = dataContract.reference.version
    val dataSourceId: String get() = adoption.binding.dataSourceId
    val connectorRef: ConnectorRef get() = adoption.connectorRef
    val dialect: String get() = adoption.dialect
    val table get() = adoption.table
    fun key(): String = "$physicalTableId@$physicalTableVersion"
    fun bindingRef(): SourceBindingRef = adoption.binding
    fun matchesSource(binding: SourceBindingRef, connector: ConnectorRef, sourceDialect: String): Boolean =
        bindingRef() == binding && adoption.connectorRef == connector && dialect == sourceDialect
    fun requireSource(binding: SourceBindingRef, connector: ConnectorRef, sourceDialect: String) {
        require(matchesSource(binding, connector, sourceDialect)) { "native table differs from its exact source binding, connector or dialect" }
    }
}
