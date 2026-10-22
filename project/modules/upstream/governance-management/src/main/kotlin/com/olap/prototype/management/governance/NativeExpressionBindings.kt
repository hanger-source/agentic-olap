package com.olap.prototype.management.governance

import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.expression.NativeScalarFunction
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.SemanticExpressionInspector

/** 原生调用只采用作者指定插件的声明，以及同一插件绑定的物理表。 */
class NativeExpressionBindings(private val physicalAssets: PhysicalAssetGovernanceService, private val connectors: ConnectorRegistry) {
    fun function(call: SemanticExpression.NativeCall): NativeScalarFunction {
        val declaration = connectors.require(call.connectorRef).compilation.requireNativeScalarFunction(call.function)
        try { declaration.requireCall(call) }
        catch (failure: IllegalArgumentException) { throw ProducerContractException(requireNotNull(failure.message), failure) }
        SemanticExpressionInspector.physicalTables(call).forEach { reference ->
            if (physicalAssets.nativeTable(reference).sourceConstraints.connectorRef != call.connectorRef) throw ProducerContractException("native call table differs from its exact declared connector")
        }
        return declaration
    }
}
