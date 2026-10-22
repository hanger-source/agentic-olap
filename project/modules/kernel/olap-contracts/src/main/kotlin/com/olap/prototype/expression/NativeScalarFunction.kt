package com.olap.prototype.expression

import com.olap.prototype.contract.NativeValueType
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.ScopedInputContract

/** 精确连接器公开的只读原生标量合同；物理表不是表名文本。 */
class NativeScalarFunction(
    val id: String,
    parameters: List<Parameter>,
    val resultType: NativeValueType,
    val arithmetic: MetricValueContract.Arithmetic?,
    val stability: Stability,
) {
    val parameters: List<Parameter> = java.util.List.copyOf(parameters)
    /** 读持续变化表数据的函数必须声明 STATEMENT 或 VOLATILE。 */
    enum class Stability { IMMUTABLE, STATEMENT, VOLATILE }
    enum class ParameterKind { SCALAR, PHYSICAL_TABLE }
    data class Parameter(val kind: ParameterKind, val scalarType: ScalarValueType?) {
        init { require((kind == ParameterKind.SCALAR) == (scalarType != null)) { "only scalar parameters declare a scalar type" } }
    }

    init {
        ScopedInputContract.requireId(id)
        require(resultType.elementType == null && resultType.fields == null) { "native scalar functions cannot return containers" }
        require(resultType.name !in setOf("ANY", "NULL", "OTHER")) { "native scalar function result type must be determined" }
        val numeric = resultType.name in setOf("TINYINT", "SMALLINT", "INTEGER", "BIGINT", "DECIMAL", "REAL", "FLOAT", "DOUBLE")
        require(numeric == (arithmetic != null)) { "native numeric results require their arithmetic guarantee" }
        require(arithmetic !is MetricValueContract.Exact || resultType.name !in setOf("REAL", "FLOAT", "DOUBLE")) { "native floating results cannot declare exact arithmetic" }
    }

    /** 只核声明形状；原生类型、表采用授权和 SQL 映射各归实际边界。 */
    fun requireCall(call: SemanticExpression.NativeCall) {
        require(id == call.function) { "native function differs from its declaration" }
        require(call.arguments.size == parameters.size) { "native function requires its declared exact argument count" }
        parameters.forEachIndexed { index, parameter ->
            require((call.arguments[index] is SemanticExpression.ScalarArgument) == (parameter.kind == ParameterKind.SCALAR)) {
                "native function argument kind differs from its declaration at $index"
            }
        }
    }
    override fun equals(other: Any?): Boolean = other is NativeScalarFunction && id == other.id && parameters == other.parameters && resultType == other.resultType && arithmetic == other.arithmetic && stability == other.stability
    override fun hashCode(): Int = listOf(id, parameters, resultType, arithmetic, stability).hashCode()
}
