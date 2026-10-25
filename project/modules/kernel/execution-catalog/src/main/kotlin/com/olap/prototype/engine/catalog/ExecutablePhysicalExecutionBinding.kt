package com.olap.prototype.engine.catalog

import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.contract.NativeTableDependency
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.warehouse.contract.PhysicalDataContract
import com.olap.prototype.warehouse.contract.PhysicalSourceAdoption

data class ExecutablePhysicalExecutionBinding(val bindingId: String, val bindingVersion: String, val eventId: String?,
    val eventVersion: String?, val logicUnitId: String?, val logicUnitVersion: String?, val logicalTableId: String,
    val logicalTableVersion: String, val qualifiedTablePath: List<String>, val dataContract: PhysicalDataContract,
    val adoption: PhysicalSourceAdoption, val nativeTableDependencies: Map<String, NativeTableDependency> = emptyMap(),
    val logicUnitBindings: Map<String, SemanticExpression> = emptyMap(), val physicalColumnBindings: Map<String, String> = emptyMap(),
    val physicalColumnTypes: Map<String, JdbcColumnType> = emptyMap()) {
    val physicalTableId: String get() = dataContract.reference.id
    val physicalTableVersion: String get() = dataContract.reference.version.toString()
    val dataSourceId: String get() = adoption.binding.dataSourceId
    val connectorRef: ConnectorRef get() = adoption.connectorRef
    val dialect: String get() = adoption.dialect
    val catalogName: String? get() = adoption.table.catalog
    val schemaName: String? get() = adoption.table.schema
    val tableName: String get() = adoption.table.table

    /** 列和约束属于 P；执行来源与技术观察来自发布选定的采用。 */
    internal fun requireComplete() {
        adoption.requireContract(dataContract)
        require(qualifiedTablePath.isNotEmpty() && qualifiedTablePath.all { it.isNotBlank() } && qualifiedTablePath.last() == tableName) { "physical connector table path must identify its exact table" }
        require(physicalColumnBindings.keys == physicalColumnTypes.keys) { "source columns and native types must identify the same exact references" }
        physicalColumnBindings.forEach { (reference, name) ->
            val column = dataContract.columns[name]
            require(column != null && column.ref == reference && column.type == physicalColumnTypes[reference]) {
                "physical binding column differs from its exact data contract: $reference"
            }
        }
        nativeTableDependencies.forEach { (key, dependency) ->
            require(key == dependency.key()) { "native table dependency requires its exact identity and version" }
            dependency.requireSource(adoption.binding, connectorRef, dialect)
            if (key == "$physicalTableId@$physicalTableVersion") {
                val owner = NativeTableDependency(dataContract, adoption)
                require(owner == dependency) { "native table dependency differs from its exact physical execution owner" }
            }
        }
    }
}
