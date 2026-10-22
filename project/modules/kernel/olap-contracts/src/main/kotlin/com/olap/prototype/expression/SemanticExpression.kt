package com.olap.prototype.expression

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.metric.MetricValueContract.Rounding
import com.olap.prototype.query.ScopedInputContract
import java.math.BigDecimal

/** 跨治理、发布与引擎的规范表达式。只引用精确版本，不接收物理名字或 SQL。 */
@JsonSerialize(using = SemanticExpressionJsonSerializer::class)
@JsonDeserialize(using = SemanticExpressionJsonDeserializer::class)
sealed interface SemanticExpression {
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = LocalParent::class, name = "LOCAL"), JsonSubTypes.Type(value = RoleParent::class, name = "ROLE"))
    sealed interface RelationParent
    class LocalParent : RelationParent {
        override fun equals(other: Any?): Boolean = other is LocalParent
        override fun hashCode(): Int = 0
    }
    data class RoleParent(val role: String) : RelationParent {
        init { ScopedInputContract.requireId(role) }
    }

    /** predicate的局部字段属于量化位置，外层字段须显式引用角色。 */
    data class Exists(val instanceId: String, val from: RelationParent, val accessPath: RelationAccessPath, val predicate: SemanticExpression) : SemanticExpression {
        init {
            ScopedInputContract.requireId(instanceId)
            require(accessPath.joinPathRefs.isNotEmpty()) { "EXISTS requires an explicit relationship path" }
        }
    }

    enum class ReferenceKind { LOGIC_UNIT, ROLE_LOGIC_UNIT, PHYSICAL_COLUMN }
    enum class Operator { ADD, SUBTRACT, MULTIPLY, DIVIDE, SAFE_DIVIDE, MODULO, EQ, NE, GT, GTE, LT, LTE, LIKE, IN, BETWEEN, IS_NULL, IS_NOT_NULL, AND, OR, NOT, NEGATE }
    enum class ValueType { STRING, INTEGER, BIGINT, DECIMAL, DOUBLE, BOOLEAN, DATE, TIMESTAMP }

    data class Reference(val referenceKind: ReferenceKind, val id: String, val version: Long, val role: String? = null) : SemanticExpression {
        init {
            text(id, "reference id")
            require(version > 0) { "reference version 必须大于 0: $id" }
            if (referenceKind == ReferenceKind.ROLE_LOGIC_UNIT) ScopedInputContract.requireId(role)
            else require(role == null) { "local references cannot carry a relationship role" }
        }
    }

    class Literal(value: Any) : SemanticExpression {
        val value: Any = if (value is BigDecimal && value.scale() < 0) value.setScale(0) else value
        init { require(value !is Float && value !is Double) { "exact semantic numbers must be constructed from decimal text, never floating point" } }
        override fun equals(other: Any?): Boolean = other is Literal && value == other.value
        override fun hashCode(): Int = value.hashCode()
    }

    class Call(val function: ScalarFunction, arguments: List<SemanticExpression>) : SemanticExpression {
        val arguments: List<SemanticExpression> = expressions(arguments, "function arguments")
        init { function.requireArity(this.arguments.size) }
        override fun equals(other: Any?): Boolean = other is Call && function == other.function && arguments == other.arguments
        override fun hashCode(): Int = listOf(function, arguments).hashCode()
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = ScalarArgument::class, name = "SCALAR"), JsonSubTypes.Type(value = TableArgument::class, name = "PHYSICAL_TABLE"))
    sealed interface NativeArgument
    data class ScalarArgument(val expression: SemanticExpression) : NativeArgument
    data class TableArgument(val physicalTableId: String, val physicalTableVersion: Long) : NativeArgument {
        init {
            text(physicalTableId, "native function physical table")
            require(physicalTableVersion > 0) { "native function requires an exact physical table version" }
        }
        fun key(): String = "$physicalTableId@$physicalTableVersion"
    }

    class NativeCall(val connectorRef: ConnectorRef, val function: String, arguments: List<NativeArgument>) : SemanticExpression {
        val arguments: List<NativeArgument> = java.util.List.copyOf(arguments)
        init { ScopedInputContract.requireId(function) }
        override fun equals(other: Any?): Boolean = other is NativeCall && connectorRef == other.connectorRef && function == other.function && arguments == other.arguments
        override fun hashCode(): Int = listOf(connectorRef, function, arguments).hashCode()
    }

    class Operation(val operator: Operator, operands: List<SemanticExpression>) : SemanticExpression {
        val operands: List<SemanticExpression> = expressions(operands, "operation operands")
        override fun equals(other: Any?): Boolean = other is Operation && operator == other.operator && operands == other.operands
        override fun hashCode(): Int = listOf(operator, operands).hashCode()
    }

    class CaseWhen(branches: List<Branch>, val otherwise: SemanticExpression?) : SemanticExpression {
        val branches: List<Branch> = java.util.List.copyOf(branches)
        init { require(this.branches.isNotEmpty()) { "case branches 不能为空" } }
        override fun equals(other: Any?): Boolean = other is CaseWhen && branches == other.branches && otherwise == other.otherwise
        override fun hashCode(): Int = listOf(branches, otherwise).hashCode()
    }
    data class Branch(val `when`: SemanticExpression, val then: SemanticExpression)

    data class Round(val expression: SemanticExpression, val scale: Int, val rounding: Rounding) : SemanticExpression {
        init { require(scale in -38..38 && rounding != Rounding.NONE) { "ROUND requires a finite scale and explicit rounding mode" } }
    }

    data class Cast(val expression: SemanticExpression, val targetType: ValueType, val precision: Int?, val scale: Int?, val rounding: Rounding) : SemanticExpression {
        init { validateTarget(targetType, precision, scale) }
        companion object {
            fun validateTarget(targetType: ValueType, precision: Int?, scale: Int?) {
                when (targetType) {
                    ValueType.DECIMAL -> require(precision != null && precision > 0 && scale != null && scale in 0..precision) { "DECIMAL cast 必须明确合法 precision 和 scale" }
                    ValueType.TIMESTAMP -> require((precision == null || precision in 0..9) && scale == null) { "TIMESTAMP cast 小数秒 precision 必须为 0..9，不使用 scale" }
                    ValueType.STRING -> require((precision == null || precision > 0) && scale == null) { "STRING cast 可明确正数 precision，不使用 scale" }
                    else -> require(precision == null && scale == null) { "该 cast 类型不接受 precision 或 scale: $targetType" }
                }
            }
        }
    }

    companion object {
        private fun text(value: String, label: String) { require(value.isNotBlank()) { "$label 不能为空" } }
        private fun expressions(values: List<SemanticExpression>, label: String): List<SemanticExpression> {
            require(values.isNotEmpty()) { "$label 不能为空" }
            return java.util.List.copyOf(values)
        }
    }
}
