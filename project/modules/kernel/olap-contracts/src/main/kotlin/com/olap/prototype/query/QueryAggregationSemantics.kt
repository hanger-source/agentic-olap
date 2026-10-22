package com.olap.prototype.query

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.QueryQuantity.*

/** 聚合、窗口、位移与选版的数量政策，消费共同来源证明。 */
object QueryAggregationSemantics {
    private fun countDeclaration(): MetricValueContract = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(), MetricValueContract.Integer(64), emptyMap(), MetricValueContract.Overflow.FAIL)
    fun count(): QueryValue = QueryValue.Measure(QueryQuantity(countDeclaration(), Fixed(MetricValueContract.ONE), Observed(Nature.RANK, emptyList(), emptyList(), false)))

    fun aggregate(aggregation: ResultAggregation, input: QueryValue, grouping: Collection<QueryStageAnalysis.Symbol>, singleObservation: Boolean): QueryValue {
        val observations = grouping.map { ValuePosition.column(it.id) }
        val retained = QuantityProvenance.coordinates(grouping)
        return when (aggregation) {
            is ResultAggregation.Collect -> QueryValue.Array(input)
            is ResultAggregation.Count -> QueryValue.Measure(QueryQuantity(countDeclaration(), Fixed(MetricValueContract.ONE),
                Reduction(aggregation.kind, QuantityProvenance.provenance(input), observations)))
            is ResultAggregation.Minimum, is ResultAggregation.Maximum -> {
                if (input is QueryValue.Plain) QueryValue.anonymous()
                else {
                    val quantity = QueryValueSemantics.measure(input)
                    requireGroupedUnits(quantity, retained)
                    val copied = quantity.provenance as? Copied
                    val provenance = if (copied != null && QuantityProvenance.knownObservation(copied.input) && retained.containsAll(QuantityProvenance.observations(copied.input))) copied.input
                        else Reduction(aggregation.kind, quantity.provenance, observations)
                    QueryValue.Measure(QueryQuantity(quantity.declaration, quantity.unit, provenance))
                }
            }
            is ResultAggregation.Numeric -> {
                val quantity = QueryValueSemantics.measure(input)
                requireGroupedUnits(quantity, retained)
                when (aggregation) {
                    is ResultAggregation.Sum -> require(summable(quantity.provenance, retained, singleObservation) &&
                        (singleObservation || QuantityProvenance.commonSnapshotScope(listOf(quantity.provenance), retained))) {
                        "quantity cannot be summed after losing its observation axis, snapshot scope or original multiplicity; consume original objects or explicit component quantities"
                    }
                    is ResultAggregation.Average, is ResultAggregation.SampleDeviation -> Unit
                }
                QueryValueSemantics.requireDeclaration(aggregation.valueContract, quantity)
                QueryValue.Measure(QueryQuantity(aggregation.valueContract, quantity.unit, Reduction(aggregation.kind, quantity.provenance, observations)))
            }
        }
    }

    fun window(aggregation: ResultAggregation, input: QueryValue, partition: Collection<QueryStageAnalysis.Symbol>, order: List<Order>, frame: WindowFrameContract.Frame?,
        cumulative: Boolean, totalOrder: Boolean, singleObservation: Boolean): QueryValue {
        val oneFrameRow = !cumulative && frame != null && frame.lower.kind == "CURRENT_ROW" && frame.upper.kind == "CURRENT_ROW" && (frame.mode == "ROWS" || totalOrder)
        val result = aggregate(aggregation, input, partition, singleObservation || oneFrameRow)
        if (result !is QueryValue.Measure) return result
        val actual = QuantityProvenance.provenance(input)
        return QueryValue.Measure(QueryQuantity(result.quantity.declaration, result.quantity.unit, Window(aggregation.kind, actual, QuantityProvenance.observations(actual),
            partition.map { ValuePosition.column(it.id) }, order, frame, cumulative, totalOrder)))
    }

    fun offset(input: QueryValue, partition: Collection<QueryStageAnalysis.Symbol>, order: List<Order>, function: String, distance: Int, totalOrder: Boolean): QueryValue {
        val internal = hashSetOf<ValuePosition>()
        QuantityProvenance.allCoordinates(input, internal)
        requireOffsetUnits(input, internal, QuantityProvenance.coordinates(partition))
        return offsetValue(input, partition.map { ValuePosition.column(it.id) }, order, function, distance, totalOrder)
    }
    private fun offsetValue(value: QueryValue, partition: List<ValuePosition>, order: List<Order>, function: String, distance: Int, totalOrder: Boolean): QueryValue = when (value) {
        is QueryValue.Plain -> value
        is QueryValue.Measure -> QueryValue.Measure(QueryQuantity(value.quantity.declaration, value.quantity.unit, Offset(value.quantity.provenance, partition, order, function, distance, totalOrder)))
        is QueryValue.Row -> QueryValue.Row(value.fields.map { offsetValue(it, partition, order, function, distance, totalOrder) })
        is QueryValue.Array -> QueryValue.Array(offsetValue(value.element, partition, order, function, distance, totalOrder))
    }
    fun selectedSnapshot(actual: QueryValue, timeAxes: Collection<QueryStageAnalysis.Symbol>, asOf: Calculation.Scalar?): QueryValue = selectedSnapshotAt(actual, QuantityProvenance.coordinates(timeAxes).toList(), asOf)
    fun selectedSnapshotAt(actual: QueryValue, timeAxes: List<ValuePosition>, asOf: Calculation.Scalar?): QueryValue {
        val axes = java.util.List.copyOf(timeAxes)
        fun select(value: QueryValue): QueryValue = when (value) {
            is QueryValue.Plain -> value
            is QueryValue.Measure -> QueryValue.Measure(QueryQuantity(value.quantity.declaration, value.quantity.unit, SelectedSnapshot(value.quantity.provenance, axes, asOf)))
            is QueryValue.Row -> QueryValue.Row(value.fields.map(::select))
            is QueryValue.Array -> QueryValue.Array(select(value.element))
        }
        return select(actual)
    }
    private fun requireGroupedUnits(quantity: QueryQuantity, retained: Set<ValuePosition>) {
        val unit = quantity.unit
        require(unit !is Dimension || unit.keys.any { it.position in retained }) { "quantity aggregation must group or partition by its measurement-unit key" }
    }

    internal fun summable(provenance: Provenance, retained: Set<ValuePosition>, singleObservation: Boolean): Boolean = when (provenance) {
        is Raw, is Constant -> true
        is Copied -> QuantityProvenance.rawContribution(provenance.input) || singleObservation
        is Fact -> when {
            provenance.distinct || provenance.aggregation !in setOf(MetricAggregation.SUM, MetricAggregation.COUNT) -> singleObservation
            provenance.snapshot == null -> true
            provenance.snapshot.timeAxes.isNotEmpty() -> retained.containsAll(provenance.snapshot.timeAxes)
            else -> provenance.snapshot.boundClocks.size == 1 || singleObservation
        }
        is SelectedSnapshot -> {
            val coordinates = if (provenance.asOf == null) retained else retained + provenance.timeAxes
            singleObservation || (provenance.asOf != null || retained.containsAll(provenance.timeAxes)) && summable(provenance.input, coordinates, false)
        }
        is UniformAllocation -> summable(provenance.input, retained, singleObservation)
        is Reduction -> when (provenance.aggregation) {
            ResultAggregation.Kind.SUM -> summable(provenance.input, retained, singleObservation)
            ResultAggregation.Kind.COUNT -> true
            ResultAggregation.Kind.AVG, ResultAggregation.Kind.STDDEV_SAMP, ResultAggregation.Kind.MIN, ResultAggregation.Kind.MAX,
            ResultAggregation.Kind.COUNT_DISTINCT, ResultAggregation.Kind.ARRAY_AGG, ResultAggregation.Kind.ARRAY_AGG_DISTINCT -> singleObservation
        }
        is Linear -> (singleObservation || QuantityProvenance.commonSnapshotScope(provenance.terms, retained)) && provenance.terms.all { summable(it, retained, singleObservation) }
        is Observed -> QuantityProvenance.rowWise(provenance) || provenance.nature in setOf(Nature.WEIGHTED_VALUE, Nature.WEIGHTED_WEIGHT) && provenance.knownObservation && !QuantityProvenance.hasCopied(provenance) || singleObservation
        is Window -> singleObservation
        is Offset -> singleObservation || provenance.totalOrder && summable(provenance.input, retained, false)
        is Delta -> summable(provenance.cumulative.input, retained, singleObservation)
        is Alternatives -> (singleObservation || QuantityProvenance.commonSnapshotScope(provenance.branches, retained)) && provenance.branches.all { summable(it, retained, singleObservation) }
    }
    private fun requireOffsetUnits(value: QueryValue, internal: Set<ValuePosition>, partition: Set<ValuePosition>) {
        when (value) {
            is QueryValue.Measure -> {
                val unit = value.quantity.unit
                require(unit !is Dimension || unit.keys.any { it.position in internal || it.position in partition }) { "offset quantity must carry its own unit field or partition by its external unit key" }
            }
            is QueryValue.Row -> value.fields.forEach { requireOffsetUnits(it, internal, partition) }
            is QueryValue.Array -> requireOffsetUnits(value.element, internal, partition)
            is QueryValue.Plain -> kotlin.Unit
        }
    }
}
