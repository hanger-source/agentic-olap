package com.olap.prototype.query

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.ScalarFunction
import com.olap.prototype.metric.MetricValueContract
import java.math.BigDecimal

/** 结果表达式的封闭语言；定义与 Query 使用同一组值、类型和构造规则。 */
sealed interface ResultExpression {
    /** 在当前实际来源中变换已完成的标量值；不引入物理表实参或另选来源。 */
    class ResultNativeCall(val connectorRef: ConnectorRef, val function: String, arguments: List<ResultExpression>) : ResultExpression {
        val arguments: List<ResultExpression> = java.util.List.copyOf(arguments)
        init { ScopedInputContract.requireId(function) }
        override fun equals(other: Any?): Boolean = other is ResultNativeCall && connectorRef == other.connectorRef && function == other.function && arguments == other.arguments
        override fun hashCode(): Int = listOf(connectorRef, function, arguments).hashCode()
    }
    class ResultCall(val function: ScalarFunction, arguments: List<ResultExpression>) : ResultExpression {
        val arguments: List<ResultExpression> = java.util.List.copyOf(arguments)
        init {
            function.requireArity(this.arguments.size)
            if (function.temporal) require((this.arguments.first() as? ResultTypedLiteral)?.let {
                    it.valueType == ScalarValueType.TEXT && it.value is String && it.value.isNotBlank()
                } == true) { "temporal function requires a non-empty TYPED_LITERAL TEXT time unit" }
        }
        override fun equals(other: Any?): Boolean = other is ResultCall && function == other.function && arguments == other.arguments
        override fun hashCode(): Int = listOf(function, arguments).hashCode()
    }
    data class ResultArgument(val name: String, val valueType: ScalarValueType) : ResultExpression {
        init { ScopedInputContract.requireId(name, listOf("ResultExpression.ResultArgument", "name")) }
    }
    data class ResultSymbol(val symbol: String) : ResultExpression {
        init { ScopedInputContract.requireInputSymbol(symbol, "expression.symbol") }
    }
    class ResultLiteral(value: Any?) : ResultExpression {
        val value: Any? = if (value is BigDecimal && value.scale() < 0) value.setScale(0) else value
        init { require(value == null || value is Boolean || value is BigDecimal) { "literal requires explicit null, boolean or exact decimal" } }
        override fun equals(other: Any?): Boolean = other is ResultLiteral && value == other.value
        override fun hashCode(): Int = value?.hashCode() ?: 0
    }
    class ResultTypedLiteral(val valueType: ScalarValueType, value: Any?) : ResultExpression {
        val value: Any?
        init {
            require(valueType in setOf(ScalarValueType.TEXT, ScalarValueType.NUMBER, ScalarValueType.DATE,
                ScalarValueType.LOCAL_TIMESTAMP, ScalarValueType.OFFSET_TIMESTAMP)) { "unsupported categorical literal type" }
            val parameter = valueType.parameter(value)
            this.value = if (valueType == ScalarValueType.NUMBER) parameter else value
        }
        override fun equals(other: Any?): Boolean = other is ResultTypedLiteral && valueType == other.valueType && value == other.value
        override fun hashCode(): Int = listOf(valueType, value).hashCode()
    }
    class ResultOperation(val operator: ResultOperator, operands: List<ResultExpression>) : ResultExpression {
        val operands: List<ResultExpression> = java.util.List.copyOf(operands)
        init {
            require(this.operands.size == operator.arity) { "expression operands differ from operator arity" }
            if (operator in setOf(ResultOperator.TIMESTAMPDIFF, ResultOperator.TIMESTAMPADD, ResultOperator.DATE_TRUNC)) {
                val unit = this.operands.first() as? ResultTypedLiteral
                require(unit?.valueType == ScalarValueType.TEXT && unit.value is String && (unit.value as String).isNotBlank()) {
                    "temporal operation requires a non-empty TYPED_LITERAL TEXT Calcite time unit; temporal operand types and unit are checked by Calcite compilation"
                }
            }
        }
        override fun equals(other: Any?): Boolean = other is ResultOperation && operator == other.operator && operands == other.operands
        override fun hashCode(): Int = listOf(operator, operands).hashCode()
        companion object { fun contract(): Map<String, Any> = QueryValueSemantics.operationContract() }
    }
    class ResultRow(operands: List<ResultExpression>) : ResultExpression {
        val operands: List<ResultExpression> = java.util.List.copyOf(operands)
        init { require(this.operands.isNotEmpty()) { "row requires explicit fields" } }
        override fun equals(other: Any?): Boolean = other is ResultRow && operands == other.operands
        override fun hashCode(): Int = operands.hashCode()
    }
    data class ResultCaseBranch(val `when`: ResultExpression, val then: ResultExpression) {
        init { require(canProduceBoolean(`when`)) { "case condition must produce BOOLEAN" } }
    }
    class ResultCaseWhen(branches: List<ResultCaseBranch>, val otherwise: ResultExpression) : ResultExpression {
        val branches: List<ResultCaseBranch> = java.util.List.copyOf(branches)
        init { require(this.branches.isNotEmpty()) { "case requires branches" } }
        override fun equals(other: Any?): Boolean = other is ResultCaseWhen && branches == other.branches && otherwise == other.otherwise
        override fun hashCode(): Int = listOf(branches, otherwise).hashCode()
    }
    data class ResultRowField(val expression: ResultExpression, val index: Int) : ResultExpression {
        init { require(index >= 0) { "row field index must be nonnegative" } }
    }
    data class ResultCast(val expression: ResultExpression, val targetType: SemanticExpression.ValueType,
        val precision: Int?, val scale: Int?, val rounding: MetricValueContract.Rounding) : ResultExpression {
        init { SemanticExpression.Cast.validateTarget(targetType, precision, scale) }
    }
    data class ResultRound(val expression: ResultExpression, val scale: Int, val rounding: MetricValueContract.Rounding) : ResultExpression {
        init { require(scale in MINIMUM_SCALE..MAXIMUM_SCALE && rounding != MetricValueContract.Rounding.NONE) { "ROUND requires a finite scale and explicit rounding mode" } }
        companion object {
            const val MINIMUM_SCALE = -38
            const val MAXIMUM_SCALE = 38
            fun contract(): Map<String, Any> = QueryValueSemantics.roundContract()
        }
    }
    data class ResultNumeric(val expression: ResultExpression, val valueContract: MetricValueContract, val unitEquation: String?) : ResultExpression {
        init { require(unitEquation == null || unitEquation in valueContract.unitEquations) { "numeric expression references an undeclared unit equation" } }
        companion object { fun contract(): Map<String, Any> = QueryValueSemantics.numericContract() }
    }
    class ResultQuantityLiteral(value: BigDecimal, val valueContract: MetricValueContract) : ResultExpression {
        val value: BigDecimal = value.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
        init {
            require(valueContract.unit is MetricValueContract.Fixed) { "quantity literal requires a fixed unit identity" }
            if (valueContract.result is MetricValueContract.Integer || valueContract.result is MetricValueContract.BinaryFloat)
                valueContract.requireScalar(Calculation.Scalar(ScalarValueType.NUMBER, this.value.toPlainString()))
        }
        override fun equals(other: Any?): Boolean = other is ResultQuantityLiteral && value == other.value && valueContract == other.valueContract
        override fun hashCode(): Int = listOf(value, valueContract).hashCode()
    }
    enum class WeightedComponent { VALUE, WEIGHT }
    data class ResultWeightedContribution(val value: ResultExpression, val weight: ResultExpression,
        val observationUnit: Calculation.ObservationUnit, val component: WeightedComponent,
        val valueContract: MetricValueContract, val unitEquation: String?) : ResultExpression {
        init {
            unitEquation?.let(ScopedInputContract::requireId)
            require(component != WeightedComponent.WEIGHT || unitEquation == null) { "paired weight preserves its actual unit without a multiplication equation" }
        }
    }
    class ResultUniformAllocation(val value: ResultExpression, sourceKeys: List<String>, memberKeys: List<String>,
        val valueContract: MetricValueContract, val unitEquation: String?) : ResultExpression {
        val sourceKeys: List<String> = ScopedInputContract.requireInputSymbols(sourceKeys, "allocation.sourceKeys")
        val memberKeys: List<String> = ScopedInputContract.requireInputSymbols(memberKeys, "allocation.memberKeys")
        init {
            require(this.sourceKeys.isNotEmpty() && this.memberKeys.isNotEmpty()) { "uniform allocation requires complete source and member identities" }
            unitEquation?.let(ScopedInputContract::requireId)
        }
        override fun equals(other: Any?): Boolean = other is ResultUniformAllocation && value == other.value && sourceKeys == other.sourceKeys &&
            memberKeys == other.memberKeys && valueContract == other.valueContract && unitEquation == other.unitEquation
        override fun hashCode(): Int = listOf(value, sourceKeys, memberKeys, valueContract, unitEquation).hashCode()
    }
    data class ResultCollectionSort(val expression: ResultExpression, val direction: String, val nulls: String) : ResultExpression {
        init { require(direction in setOf("ASC", "DESC") && nulls in setOf("FIRST", "LAST")) { "collection sort requires explicit ASC/DESC and FIRST/LAST" } }
    }
    data class ResultCollectionText(val expression: ResultExpression, val delimiter: String, val nulls: String, val nullText: String?) : ResultExpression {
        init { require(nulls == "SKIP" && nullText == null || nulls == "REPLACE" && nullText != null) { "collection text requires SKIP with null nullText or REPLACE with explicit nullText" } }
    }

    enum class ResultOperator(val arity: Int) {
        ADD(2), SUBTRACT(2), MULTIPLY(2), DIVIDE(2), SAFE_DIVIDE(2), NEGATE(1), ABS(1),
        TIMESTAMPDIFF(3), TIMESTAMPADD(3), DATE_TRUNC(2), EQ(2), NE(2), GT(2), GTE(2), LT(2), LTE(2),
        IS_NULL(1), IS_NOT_NULL(1), AND(2), OR(2), NOT(1);

        val quantityArithmetic: Boolean get() = when (this) {
            ADD, SUBTRACT, MULTIPLY, DIVIDE, SAFE_DIVIDE, NEGATE, ABS -> true
            else -> false
        }
        companion object { fun contract(): Map<String, Any> = QueryValueSemantics.operationContract() }
    }

    companion object {
        fun canProduceBoolean(expression: ResultExpression): Boolean = when (expression) {
            is ResultCall -> when (expression.function) {
                ScalarFunction.COALESCE -> expression.arguments.all(::canProduceBooleanOrNull)
                ScalarFunction.NULLIF -> canProduceBoolean(expression.arguments.first())
                else -> false
            }
            is ResultArgument -> expression.valueType == ScalarValueType.BOOLEAN
            is ResultCast -> expression.targetType == SemanticExpression.ValueType.BOOLEAN
            is ResultSymbol, is ResultRowField, is ResultNativeCall -> true // 实际列/字段/原生返回类型继续由编译器证明。
            is ResultLiteral -> expression.value is Boolean
            is ResultCaseWhen -> expression.branches.all { canProduceBooleanOrNull(it.then) } && canProduceBooleanOrNull(expression.otherwise)
            is ResultOperation -> expression.operator in setOf(ResultOperator.EQ, ResultOperator.NE, ResultOperator.GT,
                ResultOperator.GTE, ResultOperator.LT, ResultOperator.LTE, ResultOperator.IS_NULL, ResultOperator.IS_NOT_NULL,
                ResultOperator.AND, ResultOperator.OR, ResultOperator.NOT)
            is ResultTypedLiteral, is ResultRow, is ResultCollectionSort, is ResultCollectionText, is ResultWeightedContribution,
            is ResultUniformAllocation, is ResultNumeric, is ResultQuantityLiteral, is ResultRound -> false
        }

        private fun canProduceBooleanOrNull(expression: ResultExpression): Boolean =
            expression is ResultLiteral && expression.value == null || canProduceBoolean(expression)

        fun requireRoundingScale(expression: ResultExpression, field: String): Int {
            val number = (expression as? ResultLiteral)?.value as? BigDecimal
                ?: throw IllegalArgumentException("$field must be an explicit integer literal rounding scale")
            return try { number.intValueExact().also { require(it in -38..38) } }
            catch (failure: ArithmeticException) { throw IllegalArgumentException("$field must be an integer rounding scale between -38 and 38", failure) }
        }
    }
}
