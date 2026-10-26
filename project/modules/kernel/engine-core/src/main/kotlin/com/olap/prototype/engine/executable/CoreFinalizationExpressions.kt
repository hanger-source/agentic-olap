package com.olap.prototype.engine.executable

import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.connector.FiniteDecimalTypes
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.ScalarFunction
import com.olap.prototype.expression.SemanticNativeTypes
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.ResultExpression
import com.olap.prototype.query.ResultExpressionTree
import com.olap.prototype.query.ResultExpression.*
import com.olap.prototype.query.QueryExecutionMode
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.warehouse.WarehouseExecutionControl
import org.apache.calcite.jdbc.JavaTypeFactoryImpl
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeSystemImpl
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.type.SqlTypeUtil
import java.math.BigDecimal
import java.math.BigInteger

internal sealed interface CoreValue {
    data class Numeric(val value: CoreExactNumber) : CoreValue
    data class Logical(val value: Boolean) : CoreValue
    data object Missing : CoreValue
    data class Preserved(val value: ExecutableBusinessResult.Value) : CoreValue
}

internal data class CoreScalarType(val kind: Kind, val nullable: Boolean, val storage: RelDataType?) {
    enum class Kind { NUMBER, BOOLEAN, NULL, PRESERVED }
}

internal class CoreExpression(val type: CoreScalarType,
    val evaluate: (Map<String, CoreValue>, WarehouseExecutionControl) -> CoreValue)

internal class CoreFinalizationProblem(val code: String, val fieldPath: List<Any>, message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** 只定型正式本地标量；不调用来源方言，也不接受原生函数或自由代码。 */
internal class CoreFinalizationExpressions(private val symbols: Map<String, CoreScalarType>) {
    fun compile(value: ResultExpression, path: List<Any>): CoreExpression = when (value) {
        is ResultSymbol -> {
            val type = symbols[value.symbol] ?: unsupported(path, "symbol is unavailable at the local execution boundary")
            expression(type, path) { row, _ -> requireNotNull(row[value.symbol]) }
        }
        is ResultLiteral -> literal(value.value, path)
        is ResultTypedLiteral -> {
            if (value.valueType != ScalarValueType.NUMBER) unsupported(path, "local typed literals support exact NUMBER only")
            literal(value.value, path)
        }
        is ResultNumeric -> numeric(compile(value.expression, path + "expression"), value.valueContract, path)
        is ResultQuantityLiteral -> numeric(literal(value.value, path + "value"), value.valueContract, path)
        is ResultRound -> {
            val input = compile(value.expression, path + "expression")
            requireKind(input, CoreScalarType.Kind.NUMBER, path)
            expression(CoreScalarType(CoreScalarType.Kind.NUMBER, input.type.nullable, null), path) { row, control ->
                input.evaluate(row, control).numberOrNull { CoreValue.Numeric(it.rounded(value.scale, value.rounding)) }
            }
        }
        is ResultCast -> cast(value, path)
        is ResultCaseWhen -> conditional(value, path)
        is ResultCall -> call(value, path)
        is ResultOperation -> operation(value, path)
        is ResultArgument -> unsupported(path, "scalar arguments must be bound before local compilation")
        is ResultNativeCall, is ResultRow, is ResultRowField, is ResultCollectionSort,
        is ResultCollectionText, is ResultWeightedContribution, is ResultUniformAllocation ->
            unsupported(path, "expression is not supported by the core scalar finalization boundary")
    }

    private fun literal(value: Any?, path: List<Any>): CoreExpression = when (value) {
        null -> expression(CoreScalarType(CoreScalarType.Kind.NULL, true, sqlType(SqlTypeName.NULL, true)), path) { _, _ -> CoreValue.Missing }
        is Boolean -> expression(CoreScalarType(CoreScalarType.Kind.BOOLEAN, false, sqlType(SqlTypeName.BOOLEAN, false)), path) { _, _ -> CoreValue.Logical(value) }
        is BigDecimal -> expression(CoreScalarType(CoreScalarType.Kind.NUMBER, false,
            decimalType(maxOf(maxOf(0, value.scale()) + 1, value.precision() + maxOf(0, -value.scale())), maxOf(0, value.scale()), false)), path) { _, _ -> CoreValue.Numeric(CoreExactNumber.decimal(value)) }
        else -> unsupported(path, "local literals require NULL, BOOLEAN or exact decimal values")
    }

    private fun numeric(input: CoreExpression, contract: MetricValueContract, path: List<Any>): CoreExpression {
        requireKind(input, CoreScalarType.Kind.NUMBER, path)
        if (contract.arithmetic !is MetricValueContract.Exact || contract.result is MetricValueContract.BinaryFloat)
            unsupported(path, "core numeric finalization requires an Exact integer or decimal contract")
        val type = CoreScalarType(CoreScalarType.Kind.NUMBER, input.type.nullable, resultType(contract.result, input.type.nullable))
        return expression(type, path) { row, control -> input.evaluate(row, control).numberOrNull { number ->
            val result = when (val capacity = contract.result) {
                MetricValueContract.FiniteDecimal -> number.also { it.decimal() }
                is MetricValueContract.Decimal -> number.rounded(capacity.scale, capacity.rounding).also { requireDecimal(it, capacity.precision, capacity.scale) }
                is MetricValueContract.Integer -> number.also { requireInteger(it, capacity.bits) }
                is MetricValueContract.BinaryFloat -> error("floating contract was not admitted")
            }
            CoreValue.Numeric(result)
        } }
    }

    private fun cast(cast: ResultCast, path: List<Any>): CoreExpression {
        val input = compile(cast.expression, path + "expression")
        if (cast.targetType == SemanticExpression.ValueType.BOOLEAN) {
            if (cast.rounding != MetricValueContract.Rounding.NONE) unsupported(path, "BOOLEAN CAST must use NONE rounding")
            requireKind(input, CoreScalarType.Kind.BOOLEAN, path)
            return expression(CoreScalarType(CoreScalarType.Kind.BOOLEAN, input.type.nullable, sqlType(SqlTypeName.BOOLEAN, input.type.nullable)), path, input.evaluate)
        }
        val result = when (cast.targetType) {
            SemanticExpression.ValueType.INTEGER -> MetricValueContract.Integer(32)
            SemanticExpression.ValueType.BIGINT -> MetricValueContract.Integer(64)
            SemanticExpression.ValueType.DECIMAL -> MetricValueContract.Decimal(requireNotNull(cast.precision), requireNotNull(cast.scale), cast.rounding)
            else -> unsupported(path, "local CAST supports exact numeric targets and same-type BOOLEAN only")
        }
        requireKind(input, CoreScalarType.Kind.NUMBER, path)
        return expression(CoreScalarType(CoreScalarType.Kind.NUMBER, input.type.nullable, resultType(result, input.type.nullable)), path) { row, control ->
            input.evaluate(row, control).numberOrNull { number ->
                val scale = (result as? MetricValueContract.Decimal)?.scale ?: 0
                val rounded = number.rounded(scale, cast.rounding)
                when (result) {
                    MetricValueContract.FiniteDecimal -> error("finite decimal CAST is not a fixed-capacity conversion")
                    is MetricValueContract.Decimal -> requireDecimal(rounded, result.precision, result.scale)
                    is MetricValueContract.Integer -> requireInteger(rounded, result.bits)
                    is MetricValueContract.BinaryFloat -> error("floating cast was not admitted")
                }
                CoreValue.Numeric(rounded)
            }
        }
    }

    private fun conditional(case: ResultCaseWhen, path: List<Any>): CoreExpression {
        val branches = case.branches.mapIndexed { index, branch ->
            val condition = compile(branch.`when`, path + listOf("branches", index, "when"))
            requireKind(condition, CoreScalarType.Kind.BOOLEAN, path + listOf("branches", index, "when"))
            condition to compile(branch.then, path + listOf("branches", index, "then"))
        }
        val otherwise = compile(case.otherwise, path + "otherwise")
        val values = branches.map { it.second } + otherwise
        val type = alternativeType(values, values.any { it.type.nullable }, path)
        return expression(type, path) { row, control ->
            val selected = branches.firstOrNull { (condition, _) -> (condition.evaluate(row, control) as? CoreValue.Logical)?.value == true }
            (selected?.second ?: otherwise).evaluate(row, control)
        }
    }

    private fun call(call: ResultCall, path: List<Any>): CoreExpression {
        if (!QueryExecutionMode.supportsCoreFunction(call.function)) unsupported(path, "function is not supported by the core exact scalar evaluator: ${call.function}")
        val arguments = call.arguments.mapIndexed { index, argument -> compile(argument, path + listOf("arguments", index)) }
        return when (call.function) {
            ScalarFunction.COALESCE -> {
                val type = alternativeType(arguments, arguments.all { it.type.nullable }, path)
                expression(type, path) { row, control ->
                    var result: CoreValue = CoreValue.Missing
                    for (argument in arguments) {
                        result = argument.evaluate(row, control)
                        if (result != CoreValue.Missing) break
                    }
                    result
                }
            }
            ScalarFunction.ABS -> {
                val input = arguments.single()
                requireKind(input, CoreScalarType.Kind.NUMBER, path + listOf("arguments", 0))
                expression(CoreScalarType(CoreScalarType.Kind.NUMBER, input.type.nullable, null), path) { row, control ->
                    input.evaluate(row, control).numberOrNull { CoreValue.Numeric(it.abs()) }
                }
            }
            ScalarFunction.NULLIF -> {
                requireComparable(ResultOperator.EQ, arguments, path)
                val firstType = arguments.first().type
                val type = firstType.copy(nullable = true, storage = firstType.storage?.let {
                    if (FiniteDecimalTypes.isFinite(it)) FiniteDecimalTypes.create(true) else factory.createTypeWithNullability(it, true)
                })
                expression(type, path) { row, control ->
                    val first = arguments[0].evaluate(row, control)
                    val second = arguments[1].evaluate(row, control)
                    if (first != CoreValue.Missing && second != CoreValue.Missing && compare(first, second) == 0) CoreValue.Missing else first
                }
            }
            else -> unsupported(path, "function is not supported by the core exact scalar evaluator: ${call.function}")
        }
    }

    private fun alternativeType(values: List<CoreExpression>, nullable: Boolean, path: List<Any>): CoreScalarType {
        val actual = values.map { it.type.kind }.filter { it != CoreScalarType.Kind.NULL }.distinct()
        if (actual.size > 1 || actual.singleOrNull() == CoreScalarType.Kind.PRESERVED)
            unsupported(path, "local alternatives must share a numeric or boolean type")
        val kind = actual.singleOrNull() ?: CoreScalarType.Kind.NULL
        val storage = when (kind) {
            CoreScalarType.Kind.BOOLEAN -> sqlType(SqlTypeName.BOOLEAN, nullable)
            CoreScalarType.Kind.NULL -> sqlType(SqlTypeName.NULL, true)
            CoreScalarType.Kind.NUMBER -> {
                val types = values.filter { it.type.kind != CoreScalarType.Kind.NULL }.map { it.type.storage }
                if (types.any { it == null }) null else {
                    val actualTypes = types.filterNotNull()
                    if (actualTypes.any(FiniteDecimalTypes::isFinite)) FiniteDecimalTypes.create(nullable)
                    else {
                        val scale = actualTypes.maxOf { maxOf(0, it.scale) }
                        val integer = actualTypes.maxOf { it.precision - maxOf(0, it.scale) }
                        decimalType(maxOf(1, Math.addExact(integer, scale)), scale, nullable)
                    }
                }
            }
            CoreScalarType.Kind.PRESERVED -> error("preserved alternatives were not admitted")
        }
        return CoreScalarType(kind, nullable, storage)
    }

    private fun operation(operation: ResultOperation, path: List<Any>): CoreExpression {
        val operands = operation.operands.mapIndexed { index, operand -> compile(operand, path + listOf("operands", index)) }
        val nullable = operands.any { it.type.nullable }
        val numeric = setOf(ResultOperator.ADD, ResultOperator.SUBTRACT, ResultOperator.MULTIPLY, ResultOperator.DIVIDE,
            ResultOperator.SAFE_DIVIDE, ResultOperator.NEGATE, ResultOperator.ABS)
        val boolean = setOf(ResultOperator.AND, ResultOperator.OR, ResultOperator.NOT)
        val comparisons = setOf(ResultOperator.EQ, ResultOperator.NE, ResultOperator.GT, ResultOperator.GTE, ResultOperator.LT, ResultOperator.LTE)
        return when (operation.operator) {
            in numeric -> {
                operands.forEach { requireKind(it, CoreScalarType.Kind.NUMBER, path) }
                expression(CoreScalarType(CoreScalarType.Kind.NUMBER, nullable || operation.operator == ResultOperator.SAFE_DIVIDE, null), path) { row, control ->
                    val values = operands.map { it.evaluate(row, control) }
                    if (values.any { it == CoreValue.Missing }) CoreValue.Missing else {
                        val first = (values[0] as CoreValue.Numeric).value
                        val second = (values.getOrNull(1) as? CoreValue.Numeric)?.value
                        val result = when (operation.operator) {
                            ResultOperator.ADD -> first + requireNotNull(second)
                            ResultOperator.SUBTRACT -> first - requireNotNull(second)
                            ResultOperator.MULTIPLY -> first * requireNotNull(second)
                            ResultOperator.DIVIDE -> first / requireNotNull(second)
                            ResultOperator.SAFE_DIVIDE -> if (requireNotNull(second).isZero) null else first / second
                            ResultOperator.NEGATE -> -first
                            ResultOperator.ABS -> first.abs()
                            else -> error("non-arithmetic operation")
                        }
                        result?.let { CoreValue.Numeric(it) } ?: CoreValue.Missing
                    }
                }
            }
            in boolean -> {
                operands.forEach { requireKind(it, CoreScalarType.Kind.BOOLEAN, path) }
                expression(CoreScalarType(CoreScalarType.Kind.BOOLEAN, nullable, sqlType(SqlTypeName.BOOLEAN, nullable)), path) { row, control ->
                    val first = (operands[0].evaluate(row, control) as? CoreValue.Logical)?.value
                    val value = when (operation.operator) {
                        ResultOperator.NOT -> first?.not()
                        ResultOperator.AND -> if (first == false) false else {
                            val second = (operands[1].evaluate(row, control) as? CoreValue.Logical)?.value
                            if (second == false) false else if (first == null || second == null) null else true
                        }
                        ResultOperator.OR -> if (first == true) true else {
                            val second = (operands[1].evaluate(row, control) as? CoreValue.Logical)?.value
                            if (second == true) true else if (first == null || second == null) null else false
                        }
                        else -> error("non-boolean operation")
                    }
                    value?.let { CoreValue.Logical(it) } ?: CoreValue.Missing
                }
            }
            in comparisons -> comparison(operation.operator, operands, path)
            ResultOperator.IS_NULL, ResultOperator.IS_NOT_NULL -> expression(CoreScalarType(CoreScalarType.Kind.BOOLEAN, false, sqlType(SqlTypeName.BOOLEAN, false)), path) { row, control ->
                val missing = operands.single().evaluate(row, control) == CoreValue.Missing
                CoreValue.Logical(if (operation.operator == ResultOperator.IS_NULL) missing else !missing)
            }
            else -> unsupported(path, "local operations do not include temporal or source-native calculations")
        }
    }

    private fun comparison(operator: ResultOperator, operands: List<CoreExpression>, path: List<Any>): CoreExpression {
        requireComparable(operator, operands, path)
        val nullable = operands.any { it.type.nullable }
        return expression(CoreScalarType(CoreScalarType.Kind.BOOLEAN, nullable, sqlType(SqlTypeName.BOOLEAN, nullable)), path) { row, control ->
            val first = operands[0].evaluate(row, control)
            val second = operands[1].evaluate(row, control)
            if (first == CoreValue.Missing || second == CoreValue.Missing) CoreValue.Missing else {
                val compared = compare(first, second)
                CoreValue.Logical(when (operator) {
                    ResultOperator.EQ -> compared == 0
                    ResultOperator.NE -> compared != 0
                    ResultOperator.GT -> compared > 0
                    ResultOperator.GTE -> compared >= 0
                    ResultOperator.LT -> compared < 0
                    ResultOperator.LTE -> compared <= 0
                    else -> error("not a comparison")
                })
            }
        }
    }

    private fun requireComparable(operator: ResultOperator, operands: List<CoreExpression>, path: List<Any>) {
        val kinds = operands.map { it.type.kind }.filter { it != CoreScalarType.Kind.NULL }.distinct()
        if (kinds.size > 1 || kinds.any { it !in setOf(CoreScalarType.Kind.NUMBER, CoreScalarType.Kind.BOOLEAN) } ||
            kinds.singleOrNull() == CoreScalarType.Kind.BOOLEAN && operator !in setOf(ResultOperator.EQ, ResultOperator.NE))
            unsupported(path, "local comparison requires exact numeric operands or BOOLEAN equality")
    }

    private fun compare(first: CoreValue, second: CoreValue): Int = when (first) {
        is CoreValue.Numeric -> first.value.compareTo((second as CoreValue.Numeric).value)
        is CoreValue.Logical -> first.value.compareTo((second as CoreValue.Logical).value)
        else -> error("comparison input was not admitted")
    }

    companion object {
        /** 在规划时识别真实来源求值，不能把这些节点移过核心归约。 */
        fun needsSource(expression: ResultExpression): Boolean = ResultExpressionTree.nodes(expression).any { value -> when (value) {
            is ResultNativeCall, is ResultRow, is ResultRowField, is ResultCollectionSort, is ResultCollectionText,
            is ResultWeightedContribution, is ResultUniformAllocation -> true
            is ResultTypedLiteral -> value.valueType != ScalarValueType.NUMBER
            is ResultLiteral -> value.value != null && value.value !is Boolean && value.value !is BigDecimal
            is ResultCast -> value.targetType !in setOf(SemanticExpression.ValueType.INTEGER, SemanticExpression.ValueType.BIGINT,
                SemanticExpression.ValueType.DECIMAL, SemanticExpression.ValueType.BOOLEAN)
            is ResultCall -> !QueryExecutionMode.supportsCoreFunction(value.function)
            is ResultOperation -> value.operator in setOf(ResultOperator.TIMESTAMPDIFF, ResultOperator.TIMESTAMPADD, ResultOperator.DATE_TRUNC)
            is ResultNumeric -> value.valueContract.arithmetic !is MetricValueContract.Exact || value.valueContract.result is MetricValueContract.BinaryFloat
            is ResultQuantityLiteral -> value.valueContract.arithmetic !is MetricValueContract.Exact || value.valueContract.result is MetricValueContract.BinaryFloat
            else -> false
        } }
        private val factory = JavaTypeFactoryImpl(object : RelDataTypeSystemImpl() {
            override fun getMaxPrecision(typeName: SqlTypeName): Int = if (typeName == SqlTypeName.DECIMAL) Int.MAX_VALUE else super.getMaxPrecision(typeName)
            override fun getMaxScale(typeName: SqlTypeName): Int = if (typeName == SqlTypeName.DECIMAL) Int.MAX_VALUE else super.getMaxScale(typeName)
        })
        fun sourceType(type: RelDataType): CoreScalarType = CoreScalarType(when {
            SqlTypeUtil.isExactNumeric(type) -> CoreScalarType.Kind.NUMBER
            type.sqlTypeName == SqlTypeName.BOOLEAN -> CoreScalarType.Kind.BOOLEAN
            type.sqlTypeName == SqlTypeName.NULL -> CoreScalarType.Kind.NULL
            else -> CoreScalarType.Kind.PRESERVED
        }, type.isNullable || type.sqlTypeName == SqlTypeName.NULL, type)
        fun projectionType(types: List<CoreScalarType>, nullable: Boolean, path: List<Any>): CoreScalarType {
            val kinds = types.map { it.kind }.filter { it != CoreScalarType.Kind.NULL }.distinct()
            if (kinds.size > 1) unsupported(path, "completed identity projections require the same actual scalar kind")
            val storage = types.map { requireNotNull(it.storage) { "completed projection needs its actual representation" } }
            if (storage.any(FiniteDecimalTypes::isFinite)) {
                if (storage.any { it.sqlTypeName != SqlTypeName.NULL && !SqlTypeUtil.isExactNumeric(it) })
                    unsupported(path, "finite decimal identity projections require exact numeric alternatives")
                return CoreScalarType(CoreScalarType.Kind.NUMBER, nullable, FiniteDecimalTypes.create(nullable))
            }
            val common = SemanticNativeTypes.commonType(factory, storage, "completed projection")
            return CoreScalarType(kinds.singleOrNull() ?: CoreScalarType.Kind.NULL, nullable, factory.createTypeWithNullability(common, nullable))
        }
        fun resultType(result: MetricValueContract.Result, nullable: Boolean): RelDataType = when (result) {
            MetricValueContract.FiniteDecimal -> FiniteDecimalTypes.create(nullable)
            is MetricValueContract.Decimal -> decimalType(result.precision, result.scale, nullable)
            is MetricValueContract.Integer -> when (result.bits) {
                8 -> sqlType(SqlTypeName.TINYINT, nullable)
                16 -> sqlType(SqlTypeName.SMALLINT, nullable)
                32 -> sqlType(SqlTypeName.INTEGER, nullable)
                64 -> sqlType(SqlTypeName.BIGINT, nullable)
                128 -> decimalType(39, 0, nullable)
                else -> error("integer capacity was not admitted")
            }
            is MetricValueContract.BinaryFloat -> error("local exact finalization cannot create floating output")
        }
        private fun sqlType(type: SqlTypeName, nullable: Boolean): RelDataType = factory.createTypeWithNullability(factory.createSqlType(type), nullable)
        private fun decimalType(precision: Int, scale: Int, nullable: Boolean): RelDataType =
            factory.createTypeWithNullability(factory.createSqlType(SqlTypeName.DECIMAL, precision, scale), nullable)
        private fun requireKind(value: CoreExpression, expected: CoreScalarType.Kind, path: List<Any>) {
            if (value.type.kind !in setOf(expected, CoreScalarType.Kind.NULL)) unsupported(path, "local operand requires ${expected.name}; actual=${value.type.kind}")
        }
        private fun requireInteger(number: CoreExactNumber, bits: Int) {
            val value = number.integer()
            val limit = BigInteger.ONE.shiftLeft(bits - 1)
            if (value < limit.negate() || value >= limit) throw CoreArithmeticFailure("CORE_FINALIZATION_NUMERIC_CAPACITY_EXCEEDED", "value exceeds declared integer capacity")
        }
        private fun requireDecimal(number: CoreExactNumber, precision: Int, scale: Int) {
            if (number.abs() >= CoreExactNumber.decimal(BigDecimal.ONE.scaleByPowerOfTen(precision - scale)))
                throw CoreArithmeticFailure("CORE_FINALIZATION_NUMERIC_CAPACITY_EXCEEDED", "value exceeds declared decimal capacity")
        }
        private fun expression(type: CoreScalarType, path: List<Any>, operation: (Map<String, CoreValue>, WarehouseExecutionControl) -> CoreValue): CoreExpression =
            CoreExpression(type) { row, control ->
                control.checkCancellation()
                try { operation(row, control) }
                catch (failure: CoreArithmeticFailure) { throw CoreFinalizationProblem(failure.code, path.toList(), requireNotNull(failure.message), failure) }
            }
        private fun CoreValue.numberOrNull(operation: (CoreExactNumber) -> CoreValue): CoreValue =
            if (this == CoreValue.Missing) this else operation((this as CoreValue.Numeric).value)
        fun unsupported(path: List<Any>, message: String): Nothing = throw EngineExecutionException(
            "COMPILE_CORE_FINALIZATION", "CORE_FINALIZATION_UNSUPPORTED", message, null,
            mapOf("fieldPath" to path.toList(), "expectedKind" to "SUPPORTED_CORE_EXACT_SCALAR_FINALIZATION", "actualKind" to "UNSUPPORTED_LOCAL_EXPRESSION_OR_TYPE"))
    }
}
