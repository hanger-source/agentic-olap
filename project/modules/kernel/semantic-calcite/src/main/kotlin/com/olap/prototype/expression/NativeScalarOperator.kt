package com.olap.prototype.expression

import com.olap.prototype.connector.CompilationSupport
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.*
import org.apache.calcite.sql.type.OperandTypes
import org.apache.calcite.tools.RelBuilder
import java.security.MessageDigest
import java.util.HexFormat

/** Rex中的精确原生身份；只有插件拥有SQL AST渲染。 */
class NativeScalarOperator private constructor(expression: SemanticExpression.NativeCall,
    val declaration: NativeScalarFunction, private val support: CompilationSupport,
    private val arguments: List<Argument>) : SqlFunction(identity(expression), SqlKind.OTHER_FUNCTION,
    { binding -> SemanticNativeTypes.resolve(binding.typeFactory, declaration.resultType) },
    null, OperandTypes.VARIADIC, SqlFunctionCategory.USER_DEFINED_FUNCTION) {

    private sealed interface Argument {
        data class Scalar(val index: Int) : Argument
        data class Table(val value: CompilationSupport.TableArgument) : Argument
    }

    override fun isDeterministic(): Boolean = declaration.stability == NativeScalarFunction.Stability.IMMUTABLE
    override fun isDynamicFunction(): Boolean = declaration.stability != NativeScalarFunction.Stability.IMMUTABLE

    override fun unparse(writer: SqlWriter, call: SqlCall, left: Int, right: Int) {
        val values = arguments.map {
            when (it) {
                is Argument.Scalar -> CompilationSupport.ScalarArgument(call.operand(it.index))
                is Argument.Table -> it.value
            }
        }
        support.nativeScalarCall(declaration.id, values).unparse(writer, left, right)
    }

    companion object {
        private fun identity(expression: SemanticExpression.NativeCall): String = "NATIVE_CALL_" +
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(SemanticExpressionCodec.write(expression).toByteArray(Charsets.UTF_8)))

        fun compile(builder: RelBuilder, scope: NativeExpressionScope, call: SemanticExpression.NativeCall,
            operands: (SemanticExpression) -> RexNode): RexNode {
            val declaration = scope.requireCall(call)
            val values = mutableListOf<RexNode>()
            val layout = call.arguments.mapIndexed { index, argument ->
                when (argument) {
                    is SemanticExpression.ScalarArgument -> {
                        val value = operands(argument.expression)
                        SemanticNativeTypes.requireScalarType(value.type, requireNotNull(declaration.parameters[index].scalarType))
                        Argument.Scalar(values.size).also { values.add(value) }
                    }
                    is SemanticExpression.TableArgument -> {
                        val table = scope.requireTable(argument).table
                        Argument.Table(CompilationSupport.TableArgument(scope.support.tablePath(table.catalog, table.schema, table.table)))
                    }
                }
            }
            return builder.rexBuilder.makeCall(SemanticNativeTypes.resolve(builder.typeFactory, declaration.resultType),
                NativeScalarOperator(call, declaration, scope.support, layout), values)
        }
    }
}
