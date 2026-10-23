package com.olap.prototype.expression

import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.contract.NativeTableDependency
import org.apache.calcite.rex.RexNode
import org.apache.calcite.tools.RelBuilder

/** 精确插件及已采用物理表的表达式访问边界，与数值类型系统分离。 */
class NativeExpressionScope(val connector: ConnectorRef, val support: CompilationSupport,
    private val tables: (SemanticExpression.TableArgument) -> NativeTableDependency?) {
    fun requireCall(call: SemanticExpression.NativeCall): NativeScalarFunction {
        require(connector == call.connectorRef) { "native call connector is outside its exact source compilation scope" }
        return support.requireNativeScalarFunction(call.function).also { declaration ->
            declaration.requireCall(call)
            call.arguments.filterIsInstance<SemanticExpression.TableArgument>().forEach(::requireTable)
        }
    }

    fun requireScalarCall(connectorRef: ConnectorRef, function: String, count: Int): NativeScalarFunction {
        require(connector == connectorRef) { "native call connector is outside its exact source compilation scope" }
        return support.requireNativeScalarFunction(function).also { it.requireScalarCall(function, count) }
    }

    fun requireTable(table: SemanticExpression.TableArgument): NativeTableDependency {
        val dependency = requireNotNull(tables(table)) { "native table argument is outside the exact frozen dependency closure" }
        require(dependency.key() == table.key()) { "native table argument is outside the exact frozen dependency closure" }
        require(connector == dependency.sourceConstraints.connectorRef) { "native table uses a different exact connector" }
        return dependency
    }

    fun compile(builder: RelBuilder, call: SemanticExpression.NativeCall, operands: (SemanticExpression) -> RexNode): RexNode =
        NativeScalarOperator.compile(builder, this, call, operands)

    fun compileScalar(builder: RelBuilder, connectorRef: ConnectorRef, function: String, arguments: List<RexNode>): RexNode =
        NativeScalarOperator.compileScalar(builder, this, connectorRef, function, arguments)
}
