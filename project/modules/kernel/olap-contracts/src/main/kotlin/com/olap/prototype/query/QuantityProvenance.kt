package com.olap.prototype.query

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.QueryQuantity.*
import java.math.BigDecimal

/** 来源代数的观察、复制与快照事实，不决定聚合是否可接受。 */
internal object QuantityProvenance {
    fun hasCopied(value: Provenance): Boolean = when (value) {
        is Copied -> true
        is Reduction -> hasCopied(value.input)
        is SelectedSnapshot -> hasCopied(value.input)
        is UniformAllocation -> hasCopied(value.input)
        is Linear -> value.terms.any(::hasCopied)
        is Observed -> value.components.any(::hasCopied)
        is Window -> hasCopied(value.input)
        is Offset -> hasCopied(value.input)
        is Delta -> hasCopied(value.cumulative.input)
        is Declared -> hasCopied(value.input)
        is Quantized -> hasCopied(value.input)
        is Cast -> hasCopied(value.input)
        is Alternatives -> value.branches.any(::hasCopied)
        else -> false
    }
    fun provenance(value: QueryValue): Provenance = when (value) {
        is QueryValue.Measure -> value.quantity.provenance
        is QueryValue.Plain -> Raw(value.source, emptyList())
        is QueryValue.Row -> {
            val keys = linkedSetOf<ValuePosition>()
            QueryValue.scalarReferences(value, keys)
            Raw(null, keys.toList())
        }
        is QueryValue.Array -> Raw(null, emptyList())
    }
    fun linear(operator: ResultExpression.ResultOperator, left: Provenance, right: Provenance): Provenance {
        val current = withoutLosslessChanges(left) as? Window
        val previous = withoutLosslessChanges(right) as? Offset
        val previousWindow = previous?.let { withoutLosslessChanges(it.input) as? Window }
        if (operator == ResultExpression.ResultOperator.SUBTRACT && current != null && previous != null && previousWindow != null &&
            exactCumulative(current) && exactCumulative(previousWindow) && sameWindowValues(current, previousWindow) &&
            previous.totalOrder && previous.function == "LAG" && previous.distance == 1 &&
            previous.partition == current.partition && previous.order == current.order) return Delta(current)
        return Linear(listOf(left, if (operator == ResultExpression.ResultOperator.SUBTRACT)
            product(ResultExpression.ResultOperator.MULTIPLY, right, Constant("-1")) else right))
    }

    /** 只用于原贡献等价性证明；不限制显式运算，也不把类型扩容当作新的舍入。 */
    fun lossless(value: Provenance): Boolean = when (value) {
        is Declared -> {
            val result = value.declaration.result
            // 可变关联数的除法能产生新小数；源值原scale不能证明分配输出的舍入无损。
            if (value.input is UniformAllocation && (value.declaration.arithmetic !is MetricValueContract.Exact ||
                result is MetricValueContract.BinaryFloat || result is MetricValueContract.Decimal && result.rounding != MetricValueContract.Rounding.NONE)) false
            else MetricValueContract.resultFits(value.inputDeclaration.result, result)
        }
        is Quantized -> when (val result = value.inputDeclaration.result) {
            is MetricValueContract.Decimal -> result.scale <= value.scale
            is MetricValueContract.Integer -> value.scale >= 0
            is MetricValueContract.BinaryFloat -> false
        }
        is Cast -> when (value.targetType) {
            SemanticExpression.ValueType.INTEGER -> MetricValueContract.resultFits(value.inputDeclaration.result, MetricValueContract.Integer(32))
            SemanticExpression.ValueType.BIGINT -> MetricValueContract.resultFits(value.inputDeclaration.result, MetricValueContract.Integer(64))
            SemanticExpression.ValueType.DECIMAL -> MetricValueContract.resultFits(value.inputDeclaration.result,
                MetricValueContract.Decimal(requireNotNull(value.precision), requireNotNull(value.scale), value.rounding))
            SemanticExpression.ValueType.DOUBLE -> when (val result = value.inputDeclaration.result) {
                is MetricValueContract.BinaryFloat -> result.bits <= 64
                is MetricValueContract.Integer -> result.bits <= 32
                is MetricValueContract.Decimal -> result.scale == 0 && result.precision <= 15
            }
            else -> false
        }
        else -> false
    }

    fun withoutLosslessChanges(value: Provenance): Provenance = when (value) {
        is Declared -> if (lossless(value)) withoutLosslessChanges(value.input) else value
        is Quantized -> if (lossless(value)) withoutLosslessChanges(value.input) else value
        is Cast -> if (lossless(value)) withoutLosslessChanges(value.input) else value
        else -> value
    }
    private fun exactCumulative(value: Window): Boolean {
        val sum = value.aggregation as? ResultAggregation.Sum ?: return false
        val input = value.inputDeclaration ?: return false
        return value.cumulative && value.totalOrder && input.arithmetic is MetricValueContract.Exact &&
            sum.valueContract.arithmetic is MetricValueContract.Exact && MetricValueContract.resultFits(input.result, sum.valueContract.result)
    }
    private fun sameWindowValues(left: Window, right: Window): Boolean =
        hasValueIdentity(left.input) && hasValueIdentity(right.input) &&
            withoutLosslessChanges(left.input) == withoutLosslessChanges(right.input) &&
            (left.aggregation as ResultAggregation.Sum).nullPolicy == (right.aggregation as ResultAggregation.Sum).nullPolicy &&
            left.observations == right.observations && left.partition == right.partition && left.order == right.order &&
            left.frame == right.frame && left.cumulative == right.cumulative && left.totalOrder == right.totalOrder
    /** 来源树没有保存函数/条件本体时，不能由相同的输入依赖推导相同的计算值。 */
    private fun hasValueIdentity(value: Provenance): Boolean = when (value) {
        is Raw -> value.source != null
        is Constant, is Fact -> true
        is Alternatives -> false
        is Observed -> value.nature !in setOf(Nature.SCALAR_FUNCTION, Nature.RANK) && value.components.all(::hasValueIdentity)
        is Linear -> value.terms.all(::hasValueIdentity)
        is Reduction -> hasValueIdentity(value.input)
        is Window -> hasValueIdentity(value.input)
        is Offset -> hasValueIdentity(value.input)
        is Copied -> hasValueIdentity(value.input)
        is SelectedSnapshot -> hasValueIdentity(value.input)
        is UniformAllocation -> hasValueIdentity(value.input)
        is Delta -> hasValueIdentity(value.cumulative)
        is Declared -> hasValueIdentity(value.input)
        is Quantized -> hasValueIdentity(value.input)
        is Cast -> hasValueIdentity(value.input)
    }
    fun product(operator: ResultExpression.ResultOperator, left: Provenance, right: Provenance): Provenance {
        return observed(if (operator == ResultExpression.ResultOperator.MULTIPLY) Nature.PRODUCT else Nature.RATIO, listOf(left, right))
    }
    fun constantScaleInput(value: Observed): Provenance? {
        if (value.components.size != 2) return null
        val first = withoutLosslessChanges(value.components[0]); val second = withoutLosslessChanges(value.components[1])
        return when (value.nature) {
            Nature.PRODUCT -> if (first is Constant) second else if (second is Constant) first else null
            Nature.RATIO -> if (second is Constant && BigDecimal(second.value).signum() != 0) first else null
            else -> null
        }
    }
    fun observed(nature: Nature, components: List<Provenance>): Observed = Observed(nature, components, components.flatMap(::observations).distinct(), components.all(::knownObservation))
    fun coordinates(symbols: Collection<QueryStageAnalysis.Symbol>): Set<ValuePosition> = linkedSetOf<ValuePosition>().apply {
        symbols.forEach { add(ValuePosition.column(it.id)); QueryValue.scalarReferences(it.value, this) }
    }
    fun observations(provenance: Provenance): List<ValuePosition> = when (provenance) {
        is Raw -> provenance.observations
        is Constant -> emptyList()
        is Fact -> provenance.observations
        is Reduction -> provenance.observations
        is Observed -> provenance.observations
        is Window -> provenance.observations
        is Offset -> observations(provenance.input)
        is Copied -> observations(provenance.input)
        is SelectedSnapshot -> observations(provenance.input)
        is UniformAllocation -> (provenance.sourceKeys + provenance.memberKeys).distinct()
        is Delta -> observations(provenance.cumulative.input)
        is Declared -> observations(provenance.input)
        is Quantized -> observations(provenance.input)
        is Cast -> observations(provenance.input)
        is Linear -> provenance.terms.flatMap(::observations).distinct()
        is Alternatives -> provenance.branches.flatMap(::observations).distinct()
    }
    fun knownObservation(provenance: Provenance): Boolean = when (provenance) {
        is Raw -> provenance.observations.isNotEmpty()
        is Observed -> provenance.knownObservation
        is Copied -> knownObservation(provenance.input)
        is SelectedSnapshot -> knownObservation(provenance.input)
        is UniformAllocation -> knownObservation(provenance.input)
        is Offset -> knownObservation(provenance.input)
        is Window -> knownObservation(provenance.input)
        is Declared -> knownObservation(provenance.input)
        is Quantized -> knownObservation(provenance.input)
        is Cast -> knownObservation(provenance.input)
        is Linear -> provenance.terms.all(::knownObservation)
        is Alternatives -> provenance.branches.all(::knownObservation)
        else -> true
    }
    fun rawContribution(value: Provenance): Boolean = when (value) {
        is Raw, is Constant -> true
        is Copied -> rawContribution(value.input)
        is Linear -> value.terms.all(::rawContribution)
        is Alternatives -> value.branches.all(::rawContribution)
        is Observed -> rowWise(value)
        is Declared -> lossless(value) && rawContribution(value.input)
        is Quantized -> lossless(value) && rawContribution(value.input)
        is Cast -> lossless(value) && rawContribution(value.input)
        else -> false
    }
    fun rowWise(observed: Observed): Boolean = when (observed.nature) {
        Nature.ABSOLUTE -> observed.components.size == 1 && rawContribution(observed.components.single())
        Nature.PRODUCT -> observed.components.isNotEmpty() && observed.components.all(::rawContribution)
        Nature.SCALAR_FUNCTION -> observed.components.all(::rawContribution)
        else -> false
    }
    fun commonSnapshotScope(values: List<Provenance>, retained: Set<ValuePosition>): Boolean {
        val snapshots = mutableListOf<SnapshotScope>()
        values.forEach { snapshotScopes(it, retained, snapshots) }
        if (snapshots.isEmpty()) return true
        val first = snapshots.first()
        return (first.asOf != null || first.boundClocks.size == 1) && snapshots.all { it == first }
    }
    /** 实际观察点，只用于比较，不建立另一份公开时间合同。 */
    data class SnapshotScope(val asOf: Calculation.Scalar?, val boundClocks: Map<String, TemporalClockBindings.Value>)
    fun snapshotScopes(value: Provenance, retained: Set<ValuePosition>, result: MutableList<SnapshotScope>) {
        when (value) {
            is Fact -> value.snapshot?.let { snapshot -> if (snapshot.timeAxes.isEmpty() || !retained.containsAll(snapshot.timeAxes)) result.add(SnapshotScope(null, snapshot.boundClocks)) }
            is SelectedSnapshot -> {
                if (value.asOf != null || !retained.containsAll(value.timeAxes)) result.add(SnapshotScope(value.asOf, emptyMap()))
                val coordinates = if (value.asOf == null) retained else retained + value.timeAxes
                snapshotScopes(value.input, coordinates, result)
            }
            is Reduction -> when (value.aggregation.kind) {
                ResultAggregation.Kind.COUNT, ResultAggregation.Kind.COUNT_DISTINCT -> Unit
                ResultAggregation.Kind.SUM, ResultAggregation.Kind.AVG, ResultAggregation.Kind.STDDEV_SAMP,
                ResultAggregation.Kind.MIN, ResultAggregation.Kind.MAX, ResultAggregation.Kind.ARRAY_AGG, ResultAggregation.Kind.ARRAY_AGG_DISTINCT ->
                    snapshotScopes(value.input, retained, result)
            }
            is Linear -> value.terms.forEach { snapshotScopes(it, retained, result) }
            is Observed -> value.components.forEach { snapshotScopes(it, retained, result) }
            is Window -> snapshotScopes(value.input, retained, result)
            is Offset -> snapshotScopes(value.input, retained, result)
            is Copied -> snapshotScopes(value.input, retained, result)
            is UniformAllocation -> snapshotScopes(value.input, retained, result)
            is Delta -> snapshotScopes(value.cumulative.input, retained, result)
            is Declared -> snapshotScopes(value.input, retained, result)
            is Quantized -> snapshotScopes(value.input, retained, result)
            is Cast -> snapshotScopes(value.input, retained, result)
            is Alternatives -> value.branches.forEach { snapshotScopes(it, retained, result) }
            is Raw, is Constant -> kotlin.Unit
        }
    }
    fun allCoordinates(value: QueryValue, result: MutableSet<ValuePosition>) {
        when (value) { is QueryValue.Plain -> result.addAll(value.positions); is QueryValue.Row -> value.fields.forEach { allCoordinates(it, result) }; is QueryValue.Array -> allCoordinates(value.element, result); is QueryValue.Measure -> kotlin.Unit }
    }
}
