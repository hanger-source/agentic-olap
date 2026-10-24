package com.olap.prototype.query

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.contract.InputContractException
import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.QueryQuantity.*

/** 聚合、窗口、位移与选版的数量政策，消费共同来源证明。 */
object QueryAggregationSemantics {
    fun sumContract(): Map<String, Any> = mapOf(
        "valueContract" to "EXPLICIT_COMPATIBLE_UNIT_ARITHMETIC_RESULT_CAPACITY_AND_OVERFLOW",
        "ordinaryReduction" to "REQUIRES_ADDITIVE_PROVENANCE_OR_A_SINGLE_ACTUAL_OBSERVATION",
        "count" to "NON_DISTINCT_COUNT_REDUCTIONS_MAY_BE_SUMMED_SUBJECT_TO_MULTIPLICITY_UNIT_AND_SNAPSHOT_RULES",
        "countDistinct" to "CURRENT_ORDINARY_SUM_REQUIRES_A_SINGLE_ACTUAL_OBSERVATION_EVEN_WHEN_DISTINCT_COUNT_GROUPING_COORDINATES_ARE_RETAINED",
        "completedObservationSum" to observationContract(),
        "originalObjects" to "RETAIN_THE_COMPLETE_ORIGINAL_OBJECT_OBSERVATION_TUPLE_THEN_COUNT_THE_INTENDED_NON_NULL_OBJECTS",
        "declaration" to "UNIT_ONE_NUMERIC_CAST_OR_A_NEW_RESULT_CAPACITY_DOES_NOT_ERASE_PROVENANCE",
        "scope" to "CURRENT_QUANTITY_COMPOSITION_POLICY_NOT_A_CLAIM_THAT_NUMERIC_SUMS_OF_STATISTICS_ARE_MATHEMATICALLY_UNDEFINED",
        "violationCode" to InputContractException.Rule.RESULT_QUANTITY_NOT_SUMMABLE.code,
    )
    fun countContract(): Map<String, Any> = mapOf(
        "result" to "BUILT_IN_ONE_EXACT_INTEGER64_OVERFLOW_FAIL",
        "population" to "THE_ACTUAL_INPUT_OBJECTS_GROUPING_DISTINCT_AND_NULL_POLICY_DEFINE_WHAT_IS_COUNTED",
        "distinctFalse" to "COUNTS_NON_NULL_INPUT_OCCURRENCES_WITH_THE_DECLARED_NULL_POLICY",
        "distinctTrue" to "COUNTS_DISTINCT_INPUT_IDENTITIES_WITHIN_EACH_ACTUAL_GROUP",
        "observations" to "PRESERVES_THE_ACTUAL_GROUPING_COORDINATES_IN_QUANTITY_PROVENANCE",
        "ordinaryResum" to "COUNT_IS_ADDITIVE_SUBJECT_TO_MULTIPLICITY_AND_SNAPSHOT_RULES_COUNT_DISTINCT_REQUIRES_A_SINGLE_ACTUAL_OBSERVATION",
        "completedObservationSum" to observationContract(),
    )
    fun observationContract(): Map<String, Any> = mapOf(
        "owner" to "CALCULATION_OBSERVATION_STATISTICS_WITH_OBJECTS_BY",
        "identity" to "KEYS_MUST_EQUAL_THE_COMPLETE_ACTUAL_UNIQUE_CATEGORICAL_OBSERVATION_KEY_AND_BE_NON_NULL",
        "reduction" to "ONLY_THE_ROOTS_GENERATED_STATISTICAL_REGROUP_RECEIVES_THIS_CONTEXT_NOT_ITS_LATER_POST_OPERATIONS",
        "sum" to "SUMS_COMPLETED_NUMERIC_VALUES_AS_DISTINCT_STATISTICAL_OBSERVATIONS_WITHOUT_CLAIMING_TO_RECONSTRUCT_THE_ORIGINAL_COUNT_AVERAGE_OR_RATIO",
        "guards" to "NO_UNRESOLVED_COPIED_QUANTITIES_COMMON_SNAPSHOT_SCOPE_RETAINED_MEASUREMENT_UNIT_AND_ALL_NUMERIC_CONTRACTS",
        "provenance" to "RETAINS_SUM_OVER_THE_ORIGINAL_QUANTITY_PROVENANCE_ORDINARY_LATER_SUMS_RECHECK_THAT_ORIGINAL_TREE",
        "rawRows" to "DOES_NOT_GRANT_THE_COMPLETED_OBJECT_OBSERVATION_SUM_CONTEXT",
    )
    private fun countDeclaration(): MetricValueContract = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(), MetricValueContract.Integer(64), emptyMap(), MetricValueContract.Overflow.FAIL)
    fun count(): QueryValue = QueryValue.Measure(QueryQuantity(countDeclaration(), Fixed(MetricValueContract.ONE), Observed(Nature.RANK, emptyList(), emptyList(), false)))

    fun aggregate(aggregation: ResultAggregation, input: QueryValue, grouping: Collection<QueryStageAnalysis.Symbol>, singleObservation: Boolean,
        fieldPath: List<Any> = emptyList()): QueryValue = aggregate(aggregation, input, grouping, singleObservation, fieldPath, false)

    internal fun observationAggregate(aggregation: ResultAggregation, input: QueryValue, grouping: Collection<QueryStageAnalysis.Symbol>, singleObservation: Boolean,
        fieldPath: List<Any>): QueryValue = aggregate(aggregation, input, grouping, singleObservation, fieldPath, true)

    private fun aggregate(aggregation: ResultAggregation, input: QueryValue, grouping: Collection<QueryStageAnalysis.Symbol>, singleObservation: Boolean,
        fieldPath: List<Any>, statisticalObservation: Boolean): QueryValue {
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
                    is ResultAggregation.Sum -> requireSummable(quantity.provenance, retained, singleObservation, statisticalObservation, fieldPath)
                    is ResultAggregation.Average, is ResultAggregation.SampleDeviation -> Unit
                }
                QueryValueSemantics.requireDeclaration(aggregation.valueContract, quantity)
                QueryValue.Measure(QueryQuantity(aggregation.valueContract, quantity.unit, Reduction(aggregation.kind, quantity.provenance, observations)))
            }
        }
    }

    fun window(aggregation: ResultAggregation, input: QueryValue, partition: Collection<QueryStageAnalysis.Symbol>, order: List<Order>, frame: WindowFrameContract.Frame?,
        cumulative: Boolean, totalOrder: Boolean, singleObservation: Boolean, fieldPath: List<Any> = emptyList()): QueryValue {
        val oneFrameRow = !cumulative && frame != null && frame.lower.kind == "CURRENT_ROW" && frame.upper.kind == "CURRENT_ROW" && (frame.mode == "ROWS" || totalOrder)
        val result = aggregate(aggregation, input, partition, singleObservation || oneFrameRow, fieldPath)
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

    internal fun summable(provenance: Provenance, retained: Set<ValuePosition>, singleObservation: Boolean): Boolean =
        summationFailure(provenance, retained, singleObservation) == null

    private fun requireSummable(provenance: Provenance, retained: Set<ValuePosition>, singleObservation: Boolean,
        statisticalObservation: Boolean, path: List<Any>) {
        val failure = when {
            statisticalObservation && QuantityProvenance.hasCopied(provenance) -> SumFailure.COPIED_COMPLETED_QUANTITY
            !statisticalObservation -> summationFailure(provenance, retained, singleObservation)
            else -> null
        } ?: if (!singleObservation && !QuantityProvenance.commonSnapshotScope(listOf(provenance), retained)) SumFailure.SNAPSHOT_SCOPE_MISMATCH else null
        if (failure != null) {
            val guidance = if (statisticalObservation) {
                if (failure == SumFailure.COPIED_COMPLETED_QUANTITY)
                    "Return to the original contribution population and resolve the actual copied multiplicity before statistical SUM; a complete current key does not repair that history"
                else "Resolve the actual observation time or retain the required snapshot scope before statistical SUM; a complete current key does not establish a common source snapshot"
            } else "For a sum of completed statistical observations use ObservationStatistics with the complete ObjectsBy identity; " +
                "for counts of object observations retain the complete original object-observation tuple and count it. " +
                "Neither path may erase copied quantities, snapshot scope or measurement units"
            throw InputContractException.violation(InputContractException.Rule.RESULT_QUANTITY_NOT_SUMMABLE, path,
                "${failure.explanation}; this is a quantity-composition restriction, not a claim that the numeric sum is undefined. $guidance",
                if (statisticalObservation) "UNCOPIED_STATISTICAL_OBSERVATION_WITH_COMMON_SNAPSHOT" else "ADDITIVE_OR_SINGLE_OBSERVATION_QUANTITY",
                failure.name)
        }
    }

    private enum class SumFailure(val explanation: String) {
        CROSS_OBSERVATION_COUNT_DISTINCT("ordinary SUM has no additive-source proof for multiple COUNT_DISTINCT observations even when their grouping coordinates are retained"),
        COPIED_COMPLETED_QUANTITY("SUM cannot discard a completed quantity's copied multiplicity even when the current relation has a unique key"),
        SNAPSHOT_AXIS_NOT_RETAINED("SUM does not retain the quantity's required snapshot observation axis"),
        SNAPSHOT_SCOPE_MISMATCH("SUM does not have a common resolved snapshot scope for the quantities being combined"),
        CROSS_OBSERVATION_COMPLETED_AGGREGATE("ordinary SUM cannot combine multiple AVG, deviation, MIN, MAX or collection observations as original additive contributions"),
        CROSS_OBSERVATION_DERIVED_QUANTITY("ordinary SUM has no additive contribution proof for this derived statistic across observations"),
        CROSS_OBSERVATION_WINDOW("ordinary SUM cannot treat a completed window value as an original additive contribution"),
        OFFSET_ORDER_UNPROVEN("SUM requires the offset quantity's actual complete order before reusing its contributions"),
    }

    private fun summationFailure(provenance: Provenance, retained: Set<ValuePosition>, singleObservation: Boolean): SumFailure? = when (provenance) {
        is Raw, is Constant -> null
        is Copied -> if (QuantityProvenance.rawContribution(provenance.input) || singleObservation) null else SumFailure.COPIED_COMPLETED_QUANTITY
        is Fact -> when {
            provenance.distinct || provenance.aggregation !in setOf(MetricAggregation.SUM, MetricAggregation.COUNT) ->
                if (singleObservation) null else if (provenance.distinct && provenance.aggregation == MetricAggregation.COUNT)
                    SumFailure.CROSS_OBSERVATION_COUNT_DISTINCT else SumFailure.CROSS_OBSERVATION_COMPLETED_AGGREGATE
            provenance.snapshot == null -> null
            provenance.snapshot.timeAxes.isNotEmpty() -> if (retained.containsAll(provenance.snapshot.timeAxes)) null else SumFailure.SNAPSHOT_AXIS_NOT_RETAINED
            else -> if (provenance.snapshot.boundClocks.size == 1 || singleObservation) null else SumFailure.SNAPSHOT_SCOPE_MISMATCH
        }
        is SelectedSnapshot -> {
            val coordinates = if (provenance.asOf == null) retained else retained + provenance.timeAxes
            when {
                singleObservation -> null
                provenance.asOf == null && !retained.containsAll(provenance.timeAxes) -> SumFailure.SNAPSHOT_AXIS_NOT_RETAINED
                else -> summationFailure(provenance.input, coordinates, false)
            }
        }
        is UniformAllocation -> summationFailure(provenance.input, retained, singleObservation)
        is Reduction -> when (provenance.aggregation) {
            ResultAggregation.Kind.SUM -> summationFailure(provenance.input, retained, singleObservation)
            ResultAggregation.Kind.COUNT -> null
            ResultAggregation.Kind.COUNT_DISTINCT -> if (singleObservation) null else SumFailure.CROSS_OBSERVATION_COUNT_DISTINCT
            ResultAggregation.Kind.AVG, ResultAggregation.Kind.STDDEV_SAMP, ResultAggregation.Kind.MIN, ResultAggregation.Kind.MAX,
            ResultAggregation.Kind.ARRAY_AGG, ResultAggregation.Kind.ARRAY_AGG_DISTINCT -> if (singleObservation) null else SumFailure.CROSS_OBSERVATION_COMPLETED_AGGREGATE
        }
        is Linear -> if (!singleObservation && !QuantityProvenance.commonSnapshotScope(provenance.terms, retained)) SumFailure.SNAPSHOT_SCOPE_MISMATCH
            else provenance.terms.firstNotNullOfOrNull { summationFailure(it, retained, singleObservation) }
        is Observed -> if (QuantityProvenance.rowWise(provenance) || provenance.nature in setOf(Nature.WEIGHTED_VALUE, Nature.WEIGHTED_WEIGHT) &&
            provenance.knownObservation && !QuantityProvenance.hasCopied(provenance) || singleObservation) null
            else if (QuantityProvenance.hasCopied(provenance)) SumFailure.COPIED_COMPLETED_QUANTITY else SumFailure.CROSS_OBSERVATION_DERIVED_QUANTITY
        is Window -> if (singleObservation) null else SumFailure.CROSS_OBSERVATION_WINDOW
        is Offset -> if (singleObservation) null else if (!provenance.totalOrder) SumFailure.OFFSET_ORDER_UNPROVEN else summationFailure(provenance.input, retained, false)
        is Delta -> summationFailure(provenance.cumulative.input, retained, singleObservation)
        is Alternatives -> if (!singleObservation && !QuantityProvenance.commonSnapshotScope(provenance.branches, retained)) SumFailure.SNAPSHOT_SCOPE_MISMATCH
            else provenance.branches.firstNotNullOfOrNull { summationFailure(it, retained, singleObservation) }
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
