package com.olap.prototype.query

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.expression.ScalarFunction
import com.olap.prototype.expression.NativeScalarFunction
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.ResultExpression.*
import java.math.BigDecimal

/** 消费声明和实际位置的值算术；SQL类型和关系变换归Calcite。 */
object QueryValueSemantics {
    fun expression(expression: ResultExpression, symbols: Map<String, QueryStageAnalysis.Symbol>,
        nativeFunctions: Map<ResultNativeCall, NativeScalarFunction> = emptyMap()): QueryValue = expression(expression, symbols, null, null, nativeFunctions)
    private fun expression(expression: ResultExpression, symbols: Map<String, QueryStageAnalysis.Symbol>, declaration: MetricValueContract?,
        equation: MetricValueContract.UnitEquation?, nativeFunctions: Map<ResultNativeCall, NativeScalarFunction>): QueryValue = when (expression) {
        is ResultCall -> functionValue(expression, symbols, declaration, nativeFunctions)
        is ResultNativeCall -> nativeValue(expression, symbols, nativeFunctions)
        is ResultSymbol -> requireNotNull(symbols[expression.symbol]) { "numeric symbol is unavailable" }.value
        is ResultQuantityLiteral -> QueryValue.Measure(QueryQuantity(expression.valueContract, QueryQuantity.Fixed((expression.valueContract.unit as MetricValueContract.Fixed).identity), QueryQuantity.Constant(expression.value.toPlainString())))
        is ResultWeightedContribution -> weightedValue(expression, symbols, nativeFunctions)
        is ResultUniformAllocation -> allocatedValue(expression, symbols, nativeFunctions)
        is ResultNumeric -> declaredValue(expression, symbols, nativeFunctions)
        is ResultCollectionSort -> expression(expression.expression, symbols, nativeFunctions).also { require(it is QueryValue.Array) { "collection sort requires a typed array" } }
        is ResultCollectionText -> {
            val input = expression(expression.expression, symbols, nativeFunctions)
            require(input is QueryValue.Array && input.element is QueryValue.Plain) { "collection text requires categorical scalar elements; quantities need explicit business formatting" }
            QueryValue.anonymous()
        }
        is ResultRowField -> {
            val input = expression(expression.expression, symbols, nativeFunctions)
            require(input is QueryValue.Row && expression.index < input.fields.size) { "ROW_FIELD requires an existing ordinal field of a typed row" }
            input.fields[expression.index]
        }
        is ResultRow -> QueryValue.Row(expression.operands.map { expression(it, symbols, nativeFunctions) })
        is ResultCast -> {
            val value = expression(expression.expression, symbols, declaration, null, nativeFunctions)
            require(value !is QueryValue.Measure || declaration != null) { "quantity casts require an explicit numeric output contract" }
            if (value is QueryValue.Plain) QueryValue.anonymous() else value
        }
        is ResultRound -> {
            val actual = requireNumericDeclaration(declaration)
            expression(expression.expression, symbols, actual, null, nativeFunctions).also { requirePrecision(actual, listOf(measure(it))) }
        }
        is ResultCaseWhen -> conditionalValue(expression, symbols, declaration, nativeFunctions)
        is ResultLiteral -> if (declaration != null && expression.value is BigDecimal) numericConstant(expression.value) else QueryValue.anonymous()
        is ResultTypedLiteral -> if (declaration != null && expression.valueType == ScalarValueType.NUMBER && expression.value is BigDecimal) numericConstant(expression.value) else QueryValue.anonymous()
        is ResultArgument -> throw IllegalArgumentException("stage scalar argument must be bound before value analysis")
        is ResultOperation -> operationValue(expression, symbols, declaration, equation, nativeFunctions)
    }

    private fun functionValue(call: ResultCall, symbols: Map<String, QueryStageAnalysis.Symbol>, declaration: MetricValueContract?,
        nativeFunctions: Map<ResultNativeCall, NativeScalarFunction>): QueryValue {
        if (call.function == ScalarFunction.ABS) return operationValue(ResultOperation(ResultOperator.ABS, call.arguments), symbols, declaration, null, nativeFunctions)
        val values = call.arguments.map { argument ->
            if (nullLiteral(argument)) null else expression(argument, symbols, declaration, null, nativeFunctions)
        }
        return when (call.function) {
            ScalarFunction.ABS -> error("absolute value handled before argument analysis")
            ScalarFunction.COALESCE -> alternatives(values.filterNotNull())
            ScalarFunction.NULLIF -> {
                val first = values.first()
                val second = values[1]
                if (first != null && second != null) requireComparable(first, second)
                first ?: QueryValue.anonymous()
            }
            ScalarFunction.CONCAT, ScalarFunction.LOWER, ScalarFunction.UPPER, ScalarFunction.SUBSTRING,
            ScalarFunction.TIMESTAMPDIFF, ScalarFunction.TIMESTAMPADD, ScalarFunction.DATE_TRUNC -> {
                require(values.filterNotNull().all { it is QueryValue.Plain }) { "categorical scalar functions cannot consume quantities, rows or collections" }
                QueryValue.anonymous()
            }
        }
    }

    private fun nativeValue(call: ResultNativeCall, symbols: Map<String, QueryStageAnalysis.Symbol>,
        nativeFunctions: Map<ResultNativeCall, NativeScalarFunction>): QueryValue {
        val function = requireNotNull(nativeFunctions[call]) { "completed native call has no registered declaration in its analysis scope: ${call.function}" }
        function.requireScalarCall(call.function, call.arguments.size)
        val values = call.arguments.map { expression(it, symbols, nativeFunctions) }
        values.forEachIndexed { index, value ->
            when (value) {
                is QueryValue.Plain -> Unit
                is QueryValue.Measure -> {
                    require(function.arithmetic != null) {
                        "categorical native functions cannot erase quantity meaning; quantity arguments require a quantity-preserving numeric result"
                    }
                    require(function.parameters[index].scalarType == ScalarValueType.NUMBER &&
                        QueryQuantity.fixedIdentity(value.quantity.unit) == MetricValueContract.ONE) {
                        "native scalar arguments cannot erase a measurement unit; provide categorical or explicitly dimensionless values"
                    }
                    function.arithmetic?.let { MetricValueContract.requireArithmeticInput(it, value.quantity.declaration.arithmetic) }
                }
                is QueryValue.Row, is QueryValue.Array -> throw IllegalArgumentException("native scalar arguments cannot consume rows or collections")
            }
        }
        val contract = function.resultValueContract() ?: return QueryValue.anonymous()
        val provenance = QuantityProvenance.observed(QueryQuantity.Nature.SCALAR_FUNCTION, values.map(QuantityProvenance::provenance))
        return QueryValue.Measure(QueryQuantity(contract, QueryQuantity.Fixed(MetricValueContract.ONE), provenance))
    }

    private fun weightedValue(weighted: ResultWeightedContribution, symbols: Map<String, QueryStageAnalysis.Symbol>,
        nativeFunctions: Map<ResultNativeCall, NativeScalarFunction>): QueryValue {
        val value = expression(weighted.value, symbols, nativeFunctions)
        val weight = expression(weighted.weight, symbols, nativeFunctions)
        val numeric = ResultNumeric(if (weighted.component == WeightedComponent.VALUE) ResultOperation(ResultOperator.MULTIPLY, listOf(weighted.value, weighted.weight)) else weighted.weight, weighted.valueContract, weighted.unitEquation)
        val actual = expression(numeric, symbols, nativeFunctions)
        val grain = (weighted.observationUnit as? Calculation.ObjectsBy)?.keys?.map { requireNotNull(symbols[it]) } ?: emptyList()
        return weightedContribution(actual, value, weight, grain, weighted.component)
    }
    private fun allocatedValue(allocation: ResultUniformAllocation, symbols: Map<String, QueryStageAnalysis.Symbol>,
        nativeFunctions: Map<ResultNativeCall, NativeScalarFunction>): QueryValue {
        val quantity = measure(expression(ResultNumeric(allocation.value, allocation.valueContract, null), symbols, nativeFunctions))
        val equation = allocation.unitEquation?.let { requireNotNull(allocation.valueContract.unitEquations[it]) { "uniform allocation requires its declared division unit equation" } }
        val unit = binaryUnit(ResultOperator.DIVIDE, quantity.unit, QueryQuantity.Fixed(MetricValueContract.ONE), equation, null)
        val divided = QueryQuantity(quantity.declaration, unit, quantity.provenance)
        requireDeclaration(allocation.valueContract, divided)
        return uniformAllocation(QueryValue.Measure(divided), allocation.sourceKeys.map { requireNotNull(symbols[it]) }, allocation.memberKeys.map { requireNotNull(symbols[it]) })
    }
    private fun declaredValue(numeric: ResultNumeric, symbols: Map<String, QueryStageAnalysis.Symbol>,
        nativeFunctions: Map<ResultNativeCall, NativeScalarFunction>): QueryValue {
        val value = expression(numeric.expression, symbols, numeric.valueContract, numeric.unitEquation?.let { numeric.valueContract.unitEquations[it] }, nativeFunctions)
        if (value is QueryValue.Plain) {
            require((numeric.valueContract.unit as? MetricValueContract.Fixed)?.identity == MetricValueContract.ONE && numeric.unitEquation == null) { "categorical numeric magnitudes may only be explicitly interpreted as dimensionless values" }
            return QueryValue.Measure(QueryQuantity(numeric.valueContract, QueryQuantity.Fixed(MetricValueContract.ONE), QueryQuantity.Raw(value.source, emptyList())))
        }
        val quantity = measure(value)
        requireDeclaration(numeric.valueContract, quantity)
        return QueryValue.Measure(QueryQuantity(numeric.valueContract, quantity.unit, quantity.provenance))
    }
    private fun conditionalValue(conditional: ResultCaseWhen, symbols: Map<String, QueryStageAnalysis.Symbol>, declaration: MetricValueContract?,
        nativeFunctions: Map<ResultNativeCall, NativeScalarFunction>): QueryValue {
        val values = mutableListOf<QueryValue>()
        conditional.branches.forEach { branch ->
            expression(branch.`when`, symbols, nativeFunctions)
            if (!nullLiteral(branch.then)) values.add(expression(branch.then, symbols, declaration, null, nativeFunctions))
        }
        if (!nullLiteral(conditional.otherwise)) values.add(expression(conditional.otherwise, symbols, declaration, null, nativeFunctions))
        return alternatives(values)
    }
    private fun operationValue(operation: ResultOperation, symbols: Map<String, QueryStageAnalysis.Symbol>, declaration: MetricValueContract?,
        equation: MetricValueContract.UnitEquation?, nativeFunctions: Map<ResultNativeCall, NativeScalarFunction>): QueryValue {
        val values = operation.operands.map { expression(it, symbols, declaration, null, nativeFunctions) }
        return when (operation.operator) {
            ResultOperator.ADD, ResultOperator.SUBTRACT -> {
                val actual = requireNumericDeclaration(declaration)
                val first = measure(values.first()); val second = measure(values[1])
                require(QueryQuantity.sameUnit(first.unit, second.unit)) { "addition and subtraction require one proved measurement unit" }
                requirePrecision(actual, listOf(first, second))
                QueryValue.Measure(QueryQuantity(actual, first.unit, QuantityProvenance.linear(operation.operator, first.provenance, second.provenance)))
            }
            ResultOperator.MULTIPLY, ResultOperator.DIVIDE, ResultOperator.SAFE_DIVIDE -> {
                val actual = requireNumericDeclaration(declaration)
                val first = measure(values.first()); val second = measure(values[1])
                requirePrecision(actual, listOf(first, second))
                QueryValue.Measure(QueryQuantity(actual, binaryUnit(operation.operator, first.unit, second.unit, equation, operation.operands[1]), QuantityProvenance.product(operation.operator, first.provenance, second.provenance)))
            }
            ResultOperator.NEGATE, ResultOperator.ABS -> {
                val actual = requireNumericDeclaration(declaration)
                val first = measure(values.first())
                requirePrecision(actual, listOf(first))
                QueryValue.Measure(QueryQuantity(actual, first.unit, if (operation.operator == ResultOperator.NEGATE) first.provenance else QuantityProvenance.observed(QueryQuantity.Nature.ABSOLUTE, listOf(first.provenance))))
            }
            ResultOperator.EQ, ResultOperator.NE, ResultOperator.GT, ResultOperator.GTE, ResultOperator.LT, ResultOperator.LTE -> { requireComparable(values.first(), values[1]); QueryValue.anonymous() }
            ResultOperator.IS_NULL, ResultOperator.IS_NOT_NULL, ResultOperator.AND, ResultOperator.OR, ResultOperator.NOT -> QueryValue.anonymous()
            ResultOperator.TIMESTAMPDIFF, ResultOperator.TIMESTAMPADD, ResultOperator.DATE_TRUNC -> {
                require(values.none { it is QueryValue.Measure }) { "temporal operations require temporal or categorical arguments" }
                QueryValue.anonymous()
            }
        }
    }
    private fun numericConstant(number: BigDecimal): QueryValue {
        val scale = maxOf(0, number.scale())
        val precision = maxOf(scale + 1, number.precision() + maxOf(0, -number.scale()))
        val declaration = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(), MetricValueContract.Decimal(precision, scale, MetricValueContract.Rounding.NONE), emptyMap(), MetricValueContract.Overflow.FAIL)
        return QueryValue.Measure(QueryQuantity(declaration, QueryQuantity.Fixed(MetricValueContract.ONE), QueryQuantity.Constant(number.toPlainString())))
    }
    private fun nullLiteral(expression: ResultExpression): Boolean = expression is ResultLiteral && expression.value == null
    private fun requireNumericDeclaration(value: MetricValueContract?): MetricValueContract = requireNotNull(value) { "numeric arithmetic requires a NUMERIC expression with an explicit value contract" }
    fun measure(value: QueryValue): QueryQuantity = (value as? QueryValue.Measure)?.quantity ?: throw IllegalArgumentException("categorical values and collections cannot be consumed as scalar quantities")

    fun requireDeclaration(declaration: MetricValueContract, actual: QueryQuantity) {
        val unit = declaration.unit
        if (unit is MetricValueContract.Fixed) require(QueryQuantity.fixedIdentity(actual.unit) == unit.identity) { "numeric output differs from its declared fixed unit" }
        else {
            val axis = when (unit) { is MetricValueContract.AnalysisDimension -> unit.axis; is MetricValueContract.SlotDimension -> unit.axis; is MetricValueContract.EventDimension -> unit.axis; is MetricValueContract.Fixed -> error("fixed unit handled above") }
            val actualUnit = actual.unit
            val actualAxis = when (actualUnit) { is QueryQuantity.Dimension -> actualUnit.axis; is QueryQuantity.SelectedMember -> actualUnit.axis; is QueryQuantity.Fixed -> null }
            require(actualAxis != null && MetricValueContract.compatibleAxes(axis, actualAxis)) { "numeric output differs from its declared unit axis" }
            if (unit is MetricValueContract.AnalysisDimension) when (actualUnit) {
                is QueryQuantity.Dimension -> require(actualUnit.keys.any { (it.reference as? QueryQuantity.AnalysisDimensionRef)?.dimension == unit.dimension }) { "numeric output does not retain its declared adopted unit dimension" }
                is QueryQuantity.SelectedMember -> require((actualUnit.reference as? QueryQuantity.AnalysisDimensionRef)?.dimension == unit.dimension) { "numeric output restriction belongs to a different adopted unit dimension" }
                is QueryQuantity.Fixed -> Unit
            }
        }
        requirePrecision(declaration, listOf(actual))
    }
    private fun requirePrecision(declaration: MetricValueContract, inputs: List<QueryQuantity>) { inputs.forEach { MetricValueContract.requireArithmeticInput(declaration.arithmetic, it.declaration.arithmetic) } }
    private fun requireComparable(left: QueryValue, right: QueryValue) {
        when {
            left is QueryValue.Measure || right is QueryValue.Measure -> {
                val first = measure(left); val second = measure(right)
                require(QueryQuantity.sameUnit(first.unit, second.unit)) { "quantity comparison requires equal units at the same result positions" }
                require(first.declaration.arithmetic::class == second.declaration.arithmetic::class) { "quantity comparison must explicitly bind a common precision policy" }
            }
            left is QueryValue.Row && right is QueryValue.Row && left.fields.size == right.fields.size -> left.fields.zip(right.fields).forEach { (first, second) -> requireComparable(first, second) }
            left is QueryValue.Array && right is QueryValue.Array -> requireComparable(left.element, right.element)
            left is QueryValue.Plain && right is QueryValue.Plain -> Unit
            else -> throw IllegalArgumentException("comparison changes scalar, record or array meaning")
        }
    }
    fun requireSameMeaning(left: QueryValue, right: QueryValue) {
        when {
            left is QueryValue.Measure && right is QueryValue.Measure -> require(QueryQuantity.sameUnit(left.quantity.unit, right.quantity.unit) && left.quantity.declaration.arithmetic == right.quantity.declaration.arithmetic && left.quantity.declaration.result == right.quantity.declaration.result) { "branches or set positions have different quantity meanings" }
            left is QueryValue.Row && right is QueryValue.Row && left.fields.size == right.fields.size -> left.fields.zip(right.fields).forEach { (first, second) -> requireSameMeaning(first, second) }
            left is QueryValue.Array && right is QueryValue.Array -> requireSameMeaning(left.element, right.element)
            left is QueryValue.Plain && right is QueryValue.Plain -> Unit
            else -> throw IllegalArgumentException("branches or set positions change categorical, quantity or collection meaning")
        }
    }
    private fun binaryUnit(operation: ResultOperator, left: QueryQuantity.Unit, right: QueryQuantity.Unit, equation: MetricValueContract.UnitEquation?, rightOperand: ResultExpression?): QueryQuantity.Unit {
        val divide = operation != ResultOperator.MULTIPLY
        val operator = if (divide) MetricValueContract.BinaryOperator.DIVIDE else MetricValueContract.BinaryOperator.MULTIPLY
        if (equation is MetricValueContract.SameUnitRatio) {
            require(divide && QueryQuantity.sameUnit(left, right)) { "ratio equation requires division of equal units" }
            return QueryQuantity.Fixed(MetricValueContract.ONE)
        }
        val first = QueryQuantity.fixedIdentity(left); val second = QueryQuantity.fixedIdentity(right)
        return when (equation) {
            is MetricValueContract.PreserveUnit -> {
                require(equation.operator == operator && (if (equation.operand == MetricValueContract.Operand.LEFT) second else first) == MetricValueContract.ONE) { "unit-preserving arithmetic requires its other operand to be dimensionless" }
                if (equation.operand == MetricValueContract.Operand.LEFT) left else right
            }
            is MetricValueContract.FixedBinary -> { require(equation.operator == operator && first == equation.left && second == equation.right) { "numeric operands differ from the declared unit equation" }; QueryQuantity.Fixed(equation.result) }
            is MetricValueContract.Scale -> {
                require(equation.operator == operator && first == equation.from && second == MetricValueContract.ONE) { "unit conversion operands differ from their declared equation" }
                requireFactor(rightOperand, equation.factor); QueryQuantity.Fixed(equation.to)
            }
            is MetricValueContract.PreserveUnitScale -> {
                require(equation.operator == operator && second == MetricValueContract.ONE) { "unit-preserving scale requires its declared dimensionless factor" }
                requireFactor(rightOperand, equation.factor); left
            }
            null -> { require(first == MetricValueContract.ONE && second == first) { "multiplication or division requires an explicit unit equation" }; QueryQuantity.Fixed(MetricValueContract.ONE) }
            is MetricValueContract.SameUnitRatio -> error("ratio handled above")
        }
    }
    private fun requireFactor(expression: ResultExpression?, factor: String) {
        val value = when (expression) { is ResultLiteral -> expression.value as? BigDecimal; is ResultQuantityLiteral -> expression.value; else -> null }
        require(value != null && value.compareTo(BigDecimal(factor)) == 0) { "unit scale must consume its declared exact factor" }
    }
    fun weightedContribution(actual: QueryValue, value: QueryValue, weight: QueryValue, observations: Collection<QueryStageAnalysis.Symbol>, component: WeightedComponent): QueryValue {
        val quantity = measure(actual)
        val components = listOf(measure(value).provenance, measure(weight).provenance)
        require(components.none { QuantityProvenance.hasCopied(it) }) { "weighted observations cannot erase copied quantities; form the intended observation population first" }
        return QueryValue.Measure(QueryQuantity(quantity.declaration, quantity.unit, QueryQuantity.Observed(if (component == WeightedComponent.VALUE) QueryQuantity.Nature.WEIGHTED_VALUE else QueryQuantity.Nature.WEIGHTED_WEIGHT, components, QuantityProvenance.coordinates(observations).toList(), true)))
    }
    fun uniformAllocation(actual: QueryValue, sourceSymbols: Collection<QueryStageAnalysis.Symbol>, memberSymbols: Collection<QueryStageAnalysis.Symbol>): QueryValue {
        val quantity = measure(actual)
        val input = (quantity.provenance as? QueryQuantity.Copied)?.input ?: quantity.provenance
        val original = QuantityProvenance.observations(input).toSet()
        val retained = QuantityProvenance.coordinates(sourceSymbols)
        require(QuantityProvenance.knownObservation(input) && original.isNotEmpty() && sourceSymbols.isNotEmpty() &&
            sourceSymbols.all { QuantityProvenance.coordinates(listOf(it)).intersect(original).isNotEmpty() } && retained.containsAll(original) &&
            !QuantityProvenance.hasCopied(input) && QueryAggregationSemantics.summable(input, retained, false) && QuantityProvenance.commonSnapshotScope(listOf(input), retained)) {
            "uniform allocation requires the complete original additive observation identity; averages, ratios, distinct counts, unresolved copies or different snapshot points cannot be allocated as additive quantities"
        }
        return QueryValue.Measure(QueryQuantity(quantity.declaration, quantity.unit, QueryQuantity.UniformAllocation(input, retained.toList(), QuantityProvenance.coordinates(memberSymbols).toList())))
    }
    fun copied(value: QueryValue): QueryValue = when (value) {
        is QueryValue.Plain -> value
        is QueryValue.Measure -> QueryValue.Measure(QueryQuantity(value.quantity.declaration, value.quantity.unit, QueryQuantity.Copied(value.quantity.provenance)))
        is QueryValue.Row -> QueryValue.Row(value.fields.map(::copied))
        is QueryValue.Array -> QueryValue.Array(copied(value.element))
    }
    fun alternatives(values: List<QueryValue>): QueryValue {
        if (values.isEmpty()) return QueryValue.anonymous()
        val first = values.first()
        values.forEach { requireSameMeaning(first, it) }
        return when (first) {
            is QueryValue.Measure -> QueryValue.Measure(QueryQuantity(first.quantity.declaration, first.quantity.unit, QueryQuantity.alternatives(values.map { measure(it).provenance })))
            is QueryValue.Row -> QueryValue.Row(first.fields.indices.map { index -> alternatives(values.map { (it as QueryValue.Row).fields[index] }) })
            is QueryValue.Array -> QueryValue.Array(alternatives(values.map { (it as QueryValue.Array).element }))
            is QueryValue.Plain -> QueryValue.anonymous()
        }
    }
}
