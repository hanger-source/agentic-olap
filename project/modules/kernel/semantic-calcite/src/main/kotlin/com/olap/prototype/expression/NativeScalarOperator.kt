package com.olap.prototype.expression

import com.olap.prototype.connector.CompilationSupport
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.*
import org.apache.calcite.sql.type.OperandTypes
import org.apache.calcite.tools.RelBuilder
import java.security.MessageDigest
import java.util.HexFormat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

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
        private fun identity(expression: SemanticExpression.NativeCall): String {
            // 标量是Rex调用操作数；原生操作符身份只拥有连接器、函数和不作为Rex列的表实参。
            val fields = listOf(expression.connectorRef.id, expression.connectorRef.version, expression.connectorRef.sha256, expression.function) +
                expression.arguments.flatMap { argument -> when (argument) {
                    is SemanticExpression.ScalarArgument -> listOf("SCALAR")
                    is SemanticExpression.TableArgument -> listOf("PHYSICAL_TABLE", argument.physicalTableId, argument.physicalTableVersion.toString())
                } }
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { output ->
                output.writeInt(fields.size)
                fields.forEach { field -> val encoded = field.toByteArray(Charsets.UTF_8); output.writeInt(encoded.size); output.write(encoded) }
            }
            return "NATIVE_CALL_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))
        }

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
