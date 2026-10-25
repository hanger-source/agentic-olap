package com.olap.prototype.management.governance

import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.expression.NativeScalarFunction
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.SemanticExpressionInspector

/** 作者阶段核精确函数声明与可引用数据合同；执行采用在发布时选择并核验。 */
class NativeExpressionBindings(private val physicalAssets: PhysicalAssetGovernanceService, private val connectors: ConnectorRegistry) {
    fun function(call: SemanticExpression.NativeCall): NativeScalarFunction {
        val declaration = connectors.require(call.connectorRef).compilation.requireNativeScalarFunction(call.function)
        try { declaration.requireCall(call) }
        catch (failure: IllegalArgumentException) { throw ProducerContractException(requireNotNull(failure.message), failure) }
        SemanticExpressionInspector.physicalTables(call).forEach(physicalAssets::nativeTable)
        return declaration
    }
}
