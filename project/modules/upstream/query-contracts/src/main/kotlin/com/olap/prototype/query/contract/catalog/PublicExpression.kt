package com.olap.prototype.query.contract.catalog

import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.metric.MetricValueContract.Rounding
import com.olap.prototype.query.ScopedInputContract
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** 去除物理列与执行绑定；表参数仅保留精确采用坐标，量词保留实际公开关联。 */
sealed interface PublicExpression {
    data class Reference(val id: String, val version: Long, val role: String?) : PublicExpression {
        init { CatalogValues.text(id); CatalogValues.version(version); role?.let { ScopedInputContract.requireId(it) } }
    }
    data class TextLiteral(val value: String) : PublicExpression
    data class BooleanLiteral(val value: Boolean) : PublicExpression
    data class ExactNumber(val value: String) : PublicExpression { init { BigDecimal(CatalogValues.text(value)) } }
    class Call(val function: String, arguments: List<PublicExpression>) : PublicExpression {
        val arguments: List<PublicExpression> = CatalogValues.nonempty(arguments)
        init { CatalogValues.text(function) }
        override fun equals(other: Any?): Boolean = other is Call && function == other.function && arguments == other.arguments
        override fun hashCode(): Int = listOf(function, arguments).hashCode()
    }
    sealed interface NativeArgument
    data class ScalarArgument(val expression: PublicExpression) : NativeArgument
    data class TableArgument(val physicalTableId: String, val physicalTableVersion: Long) : NativeArgument {
        init { CatalogValues.text(physicalTableId); CatalogValues.version(physicalTableVersion) }
    }
    class NativeCall(val connectorRef: ConnectorRef, val function: String, arguments: List<NativeArgument>) : PublicExpression {
        val arguments: List<NativeArgument> = java.util.List.copyOf(arguments)
        init { ScopedInputContract.requireId(function) }
        override fun equals(other: Any?): Boolean = other is NativeCall && connectorRef == other.connectorRef && function == other.function && arguments == other.arguments
        override fun hashCode(): Int = listOf(connectorRef, function, arguments).hashCode()
    }
    class Operation(val operator: SemanticExpression.Operator, operands: List<PublicExpression>) : PublicExpression {
        val operands: List<PublicExpression> = CatalogValues.nonempty(operands)
        override fun equals(other: Any?): Boolean = other is Operation && operator == other.operator && operands == other.operands
        override fun hashCode(): Int = listOf(operator, operands).hashCode()
    }
    data class Branch(val `when`: PublicExpression, val then: PublicExpression)
    class CaseWhen(branches: List<Branch>, val otherwise: CatalogOptional<PublicExpression>) : PublicExpression {
        val branches: List<Branch> = CatalogValues.nonempty(branches)
        override fun equals(other: Any?): Boolean = other is CaseWhen && branches == other.branches && otherwise == other.otherwise
        override fun hashCode(): Int = listOf(branches, otherwise).hashCode()
    }
    data class Round(val expression: PublicExpression, val scale: Int, val rounding: Rounding) : PublicExpression {
        init { require(scale in -38..38 && rounding != Rounding.NONE) { "invalid published rounding" } }
    }
    data class Cast(val expression: PublicExpression, val targetType: SemanticExpression.ValueType, val precision: CatalogOptional<Int>,
        val scale: CatalogOptional<Int>, val rounding: Rounding) : PublicExpression {
        init { SemanticExpression.Cast.validateTarget(targetType, precision.value, scale.value) }
    }
    class Exists(val instanceId: String, val from: SemanticExpression.RelationParent, access: List<SemanticJoin>, val predicate: PublicExpression) : PublicExpression {
        val access: List<SemanticJoin> = CatalogValues.nonempty(access)
        init { ScopedInputContract.requireId(instanceId) }
        override fun equals(other: Any?): Boolean = other is Exists && instanceId == other.instanceId && from == other.from && access == other.access && predicate == other.predicate
        override fun hashCode(): Int = listOf(instanceId, from, access, predicate).hashCode()
    }

    fun references(roles: Set<String>, references: MutableSet<String>) {
        when (this) {
            is Reference -> { require(role == null || role in roles) { "published expression uses an undeclared role" }; references.add("$id@$version") }
            is Call -> arguments.forEach { it.references(roles, references) }
            is NativeCall -> arguments.filterIsInstance<ScalarArgument>().forEach { it.expression.references(roles, references) }
            is Operation -> operands.forEach { it.references(roles, references) }
            is CaseWhen -> { branches.forEach { it.`when`.references(roles, references); it.then.references(roles, references) }; otherwise.value?.references(roles, references) }
            is Round -> expression.references(roles, references)
            is Cast -> expression.references(roles, references)
            is Exists -> {
                val parent = from as? SemanticExpression.RoleParent
                require(parent == null || parent.role in roles) { "published quantifier parent role is undeclared" }
                access.forEach { it.references(references) }; predicate.references(roles, references)
            }
            is TextLiteral, is BooleanLiteral, is ExactNumber -> kotlin.Unit
        }
    }

    companion object {
        fun project(value: SemanticExpression, quantified: (SemanticExpression.Exists) -> Exists): PublicExpression = when (value) {
            is SemanticExpression.Reference -> {
                require(value.referenceKind != SemanticExpression.ReferenceKind.PHYSICAL_COLUMN) { "published expression cannot expose physical references" }
                Reference(value.id, value.version, value.role)
            }
            is SemanticExpression.Literal -> when (val literal = value.value) {
                is String -> TextLiteral(literal)
                is Boolean -> BooleanLiteral(literal)
                is Number -> ExactNumber(BigDecimal(literal.toString()).toPlainString())
                is LocalDate -> TextLiteral(literal.toString())
                is LocalDateTime -> TextLiteral(literal.toString())
                else -> throw IllegalArgumentException("unsupported published literal")
            }
            is SemanticExpression.Call -> Call(value.function.name, value.arguments.map { project(it, quantified) })
            is SemanticExpression.NativeCall -> NativeCall(value.connectorRef, value.function, value.arguments.map { argument -> when (argument) {
                is SemanticExpression.ScalarArgument -> ScalarArgument(project(argument.expression, quantified))
                is SemanticExpression.TableArgument -> TableArgument(argument.physicalTableId, argument.physicalTableVersion)
            } })
            is SemanticExpression.Operation -> Operation(value.operator, value.operands.map { project(it, quantified) })
            is SemanticExpression.CaseWhen -> CaseWhen(value.branches.map { Branch(project(it.`when`, quantified), project(it.then, quantified)) },
                value.otherwise?.let { CatalogOptional.of(project(it, quantified)) } ?: CatalogOptional.absent())
            is SemanticExpression.Round -> Round(project(value.expression, quantified), value.scale, value.rounding)
            is SemanticExpression.Cast -> Cast(project(value.expression, quantified), value.targetType,
                value.precision?.let { CatalogOptional.of(it) } ?: CatalogOptional.absent(), value.scale?.let { CatalogOptional.of(it) } ?: CatalogOptional.absent(), value.rounding)
            is SemanticExpression.Exists -> quantified(value)
        }

    }
}
