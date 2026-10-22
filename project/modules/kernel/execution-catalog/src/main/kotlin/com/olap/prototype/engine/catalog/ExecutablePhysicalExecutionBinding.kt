package com.olap.prototype.engine.catalog

import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.contract.NativeTableDependency
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.warehouse.contract.JdbcTableConstraints
import com.olap.prototype.warehouse.contract.PhysicalSourceConstraints
import com.olap.prototype.warehouse.contract.SnapshotUniqueKey
import com.olap.prototype.warehouse.contract.SourceBindingRef

data class ExecutablePhysicalExecutionBinding(val bindingId: String, val bindingVersion: String, val eventId: String?,
    val eventVersion: String?, val logicUnitId: String?, val logicUnitVersion: String?, val logicalTableId: String,
    val logicalTableVersion: String, val physicalTableId: String, val physicalTableVersion: String,
    val dataSourceId: String, val connectorRef: ConnectorRef, val qualifiedTablePath: List<String>, val dialect: String,
    val catalogName: String?, val schemaName: String?, val tableName: String, val sourceConstraints: PhysicalSourceConstraints,
    val snapshotUniqueKeys: List<SnapshotUniqueKey>, val nativeTableDependencies: Map<String, NativeTableDependency> = emptyMap(),
    val logicUnitBindings: Map<String, SemanticExpression> = emptyMap(), val physicalColumnBindings: Map<String, String> = emptyMap(),
    val physicalColumnTypes: Map<String, JdbcColumnType> = emptyMap()) {
    /** 来源资产自身事实，不判断发布采用或主体权限。 */
    internal fun requireComplete() {
        require(sourceConstraints.connectorRef == connectorRef) { "physical binding connector differs from its exact adopted evidence" }
        require(qualifiedTablePath.isNotEmpty() && qualifiedTablePath.all { it.isNotBlank() } && qualifiedTablePath.last() == tableName) { "physical connector table path must identify its exact table" }
        require(physicalColumnBindings.keys == physicalColumnTypes.keys) { "source columns and native types must identify the same exact references" }
        val source = SourceBindingRef(dataSourceId, sourceConstraints.sourceIdentityHash)
        nativeTableDependencies.forEach { (key, dependency) ->
            require(key == dependency.key()) { "native table dependency requires its exact identity and version" }
            dependency.requireSource(source, connectorRef, dialect)
            if (key == "$physicalTableId@$physicalTableVersion") {
                val owner = NativeTableDependency(physicalTableId, physicalTableVersion.toLong(), dataSourceId, dialect,
                    JdbcTableConstraints.Table(catalogName, schemaName, tableName), sourceConstraints)
                require(owner == dependency) { "native table dependency differs from its exact physical execution owner" }
            }
        }
    }
}
