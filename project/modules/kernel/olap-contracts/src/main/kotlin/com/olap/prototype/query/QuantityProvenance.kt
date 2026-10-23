package com.olap.prototype.query

import com.olap.prototype.calculation.Calculation
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
        if (operator == ResultExpression.ResultOperator.SUBTRACT && left is Window && left.cumulative && left.totalOrder && right is Offset &&
            right.totalOrder && right.function == "LAG" && right.distance == 1 && right.input == left && right.partition == left.partition && right.order == left.order) return Delta(left)
        return Linear(listOf(left, right))
    }
    fun product(operator: ResultExpression.ResultOperator, left: Provenance, right: Provenance): Provenance {
        if (operator == ResultExpression.ResultOperator.MULTIPLY) {
            if (left is Constant) return right
            if (right is Constant) return left
        } else if (right is Constant && BigDecimal(right.value).signum() != 0) return left
        return observed(if (operator == ResultExpression.ResultOperator.MULTIPLY) Nature.PRODUCT else Nature.RATIO, listOf(left, right))
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
            is Reduction -> when (value.aggregation) {
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
            is Alternatives -> value.branches.forEach { snapshotScopes(it, retained, result) }
            is Raw, is Constant -> kotlin.Unit
        }
    }
    fun allCoordinates(value: QueryValue, result: MutableSet<ValuePosition>) {
        when (value) { is QueryValue.Plain -> result.addAll(value.positions); is QueryValue.Row -> value.fields.forEach { allCoordinates(it, result) }; is QueryValue.Array -> allCoordinates(value.element, result); is QueryValue.Measure -> kotlin.Unit }
    }
}
