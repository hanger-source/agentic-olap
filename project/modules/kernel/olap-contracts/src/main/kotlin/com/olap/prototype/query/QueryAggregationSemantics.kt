package com.olap.prototype.query

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.QueryQuantity.*

/** 完成关系的显式聚合保留实际数量来源；原贡献等价性证明由需要它的操作单独消费。 */
object QueryAggregationSemantics {
    fun sumContract(): Map<String, Any> = mapOf(
        "valueContract" to "EXPLICIT_COMPATIBLE_UNIT_ARITHMETIC_RESULT_CAPACITY_AND_OVERFLOW",
        "input" to "SUMS_THE_ACTUAL_COMPLETED_NUMERIC_VALUES_IN_THE_EXPLICIT_GROUP_OR_WINDOW",
        "multiplicity" to "RETAINS_THE_ACTUAL_INPUT_BAG_INCLUDING_REPEATED_COMPLETED_VALUES_WITHOUT_IMPLICIT_DEDUPLICATION",
        "countDistinct" to "SUM_OF_GROUP_DISTINCT_COUNTS_IS_NOT_A_DISTINCT_COUNT_OF_THE_UNION",
        "completedAggregates" to "MAY_SUM_COUNT_DISTINCT_MIN_MAX_AVERAGE_RATIO_AND_WINDOW_RESULTS_WITHOUT_RECOMPUTING_THEIR_ORIGINAL_AGGREGATION",
        "provenance" to "PRESERVES_SUM_OVER_THE_FULL_INPUT_QUANTITY_PROVENANCE_AND_ACTUAL_OUTPUT_GROUPING_COORDINATES",
        "observations" to "DOES_NOT_REQUIRE_AN_OBSERVATION_STATISTICS_ROOT_OR_AN_ADDITIONAL_OBSERVATION_DECLARATION",
        "units" to "RETAINS_MEASUREMENT_UNIT_KEYS_AND_REQUIRES_COMPATIBLE_OUTPUT_UNIT_AND_ARITHMETIC",
        "snapshots" to "DOES_NOT_SELECT_REBIND_OR_ESTABLISH_EQUALITY_OF_INPUT_SOURCE_SNAPSHOTS",
        "originalObjects" to "A_UNION_DISTINCT_COUNT_REQUIRES_THE_ORIGINAL_IDENTITIES_NOT_SUMMED_DISTINCT_COUNTS",
        "sourceEquivalence" to "DOES_NOT_PROVE_THE_RESULT_EQUALS_AN_ORIGINAL_SOURCE_TOTAL_OR_AUTHORIZE_SOURCE_REWRITES_OR_ALLOCATION",
    )
    fun countContract(): Map<String, Any> = mapOf(
        "result" to "BUILT_IN_ONE_EXACT_INTEGER64_OVERFLOW_FAIL",
        "population" to "THE_ACTUAL_INPUT_OBJECTS_GROUPING_DISTINCT_AND_NULL_POLICY_DEFINE_WHAT_IS_COUNTED",
        "distinctFalse" to "COUNTS_NON_NULL_INPUT_OCCURRENCES_WITH_THE_DECLARED_NULL_POLICY",
        "distinctTrue" to "COUNTS_DISTINCT_INPUT_IDENTITIES_WITHIN_EACH_ACTUAL_GROUP",
        "observations" to "PRESERVES_THE_ACTUAL_GROUPING_COORDINATES_IN_QUANTITY_PROVENANCE",
        "completedSum" to "SUM_ADDS_THE_ACTUAL_COMPLETED_COUNT_VALUES_AND_PRESERVES_THEIR_DISTINCT_POPULATION_AND_MULTIPLICITY_PROVENANCE",
        "unionDistinct" to "PER_GROUP_DISTINCT_COUNTS_CANNOT_RECONSTRUCT_THE_UNION_DISTINCT_COUNT_WITHOUT_ORIGINAL_IDENTITIES",
    )
    fun observationContract(): Map<String, Any> = mapOf(
        "owner" to "CALCULATION_OBSERVATION_STATISTICS_WITH_OBJECTS_BY",
        "identity" to "KEYS_MUST_EQUAL_THE_COMPLETE_ACTUAL_UNIQUE_CATEGORICAL_OBSERVATION_KEY_AND_BE_NON_NULL",
        "reduction" to "USES_THE_SAME_EXPLICIT_COMPLETED_AGGREGATION_RULES_AS_REGROUP_AND_WINDOW_AGGREGATION",
        "sum" to "SUMS_THE_ACTUAL_OBSERVATION_VALUES_WITHOUT_RECONSTRUCTING_THE_ORIGINAL_COUNT_AVERAGE_MAXIMUM_OR_RATIO",
        "guards" to "PRESERVES_ACTUAL_OBJECT_IDENTITY_SOURCE_REQUIREMENTS_MEASUREMENT_UNIT_AND_ALL_NUMERIC_CONTRACTS",
        "provenance" to "RETAINS_THE_COMPLETE_INPUT_QUANTITY_TREE_FOR_LATER_COMPOSITION_AND_SOURCE_EQUIVALENCE_CHECKS",
        "rawRows" to "USES_ACTUAL_INPUT_OCCURRENCES_WITHOUT_ASSERTING_A_UNIQUE_OBJECT_IDENTITY",
    )
    private fun countDeclaration(): MetricValueContract = MetricValueContract(MetricValueContract.Fixed(MetricValueContract.ONE), MetricValueContract.Exact(), MetricValueContract.Integer(64), emptyMap(), MetricValueContract.Overflow.FAIL)
    fun count(): QueryValue = QueryValue.Measure(QueryQuantity(countDeclaration(), Fixed(MetricValueContract.ONE), Observed(Nature.RANK, emptyList(), emptyList(), false)))

    fun aggregate(aggregation: ResultAggregation, input: QueryValue, grouping: Collection<QueryStageAnalysis.Symbol>): QueryValue {
        val observations = grouping.map { ValuePosition.column(it.id) }
        val retained = QuantityProvenance.coordinates(grouping)
        return when (aggregation) {
            is ResultAggregation.Collect -> QueryValue.Array(input)
            is ResultAggregation.Count -> QueryValue.Measure(QueryQuantity(countDeclaration(), Fixed(MetricValueContract.ONE),
                Reduction(aggregation, QuantityProvenance.provenance(input), observations, (input as? QueryValue.Measure)?.quantity?.declaration)))
            is ResultAggregation.Minimum, is ResultAggregation.Maximum -> {
                if (input is QueryValue.Plain) QueryValue.anonymous()
                else {
                    val quantity = QueryValueSemantics.measure(input)
                    requireGroupedUnits(quantity, retained)
                    QueryValue.Measure(QueryQuantity(quantity.declaration, quantity.unit,
                        Reduction(aggregation, quantity.provenance, observations, quantity.declaration)))
                }
            }
            is ResultAggregation.Numeric -> {
                val quantity = QueryValueSemantics.measure(input)
                requireGroupedUnits(quantity, retained)
                QueryValueSemantics.requireDeclaration(aggregation.valueContract, quantity)
                QueryValue.Measure(QueryQuantity(aggregation.valueContract, quantity.unit,
                    Reduction(aggregation, quantity.provenance, observations, quantity.declaration)))
            }
        }
    }

    fun window(aggregation: ResultAggregation, input: QueryValue, partition: Collection<QueryStageAnalysis.Symbol>, order: List<Order>, frame: WindowFrameContract.Frame?,
        cumulative: Boolean, totalOrder: Boolean): QueryValue {
        val result = aggregate(aggregation, input, partition)
        if (result !is QueryValue.Measure) return result
        val actual = QuantityProvenance.provenance(input)
        return QueryValue.Measure(QueryQuantity(result.quantity.declaration, result.quantity.unit, Window(aggregation, actual, QuantityProvenance.observations(actual),
            partition.map { ValuePosition.column(it.id) }, order, frame, cumulative, totalOrder, (input as? QueryValue.Measure)?.quantity?.declaration)))
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

    /** 仅证明重新组合后仍等价于原可加贡献；显式完成值 SUM 不要求这项证明。 */
    internal fun hasAdditiveSourceProof(provenance: Provenance, retained: Set<ValuePosition>, singleObservation: Boolean): Boolean = when (provenance) {
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
            singleObservation || (provenance.asOf != null || retained.containsAll(provenance.timeAxes)) &&
                hasAdditiveSourceProof(provenance.input, coordinates, false)
        }
        is UniformAllocation -> hasAdditiveSourceProof(provenance.input, retained, singleObservation)
        is Reduction -> when (provenance.aggregation.kind) {
            ResultAggregation.Kind.SUM -> hasAdditiveSourceProof(provenance.input, retained, singleObservation)
            ResultAggregation.Kind.COUNT -> true
            ResultAggregation.Kind.COUNT_DISTINCT -> singleObservation
            ResultAggregation.Kind.MIN, ResultAggregation.Kind.MAX -> singleObservation ||
                (QuantityProvenance.withoutLosslessChanges(provenance.input) as? Copied)?.input?.let { original ->
                    QuantityProvenance.knownObservation(original) && retained.containsAll(QuantityProvenance.observations(original)) &&
                        hasAdditiveSourceProof(original, retained, false)
                } == true
            ResultAggregation.Kind.AVG, ResultAggregation.Kind.STDDEV_SAMP,
            ResultAggregation.Kind.ARRAY_AGG, ResultAggregation.Kind.ARRAY_AGG_DISTINCT -> singleObservation
        }
        is Linear -> (singleObservation || QuantityProvenance.commonSnapshotScope(provenance.terms, retained)) &&
            provenance.terms.all { hasAdditiveSourceProof(it, retained, singleObservation) }
        is Observed -> QuantityProvenance.constantScaleInput(provenance)?.let { hasAdditiveSourceProof(it, retained, singleObservation) } == true ||
            QuantityProvenance.rowWise(provenance) || provenance.nature in setOf(Nature.WEIGHTED_VALUE, Nature.WEIGHTED_WEIGHT) &&
            provenance.knownObservation && !QuantityProvenance.hasCopied(provenance) || singleObservation
        is Window -> singleObservation
        is Offset -> singleObservation || provenance.totalOrder && hasAdditiveSourceProof(provenance.input, retained, false)
        is Delta -> hasAdditiveSourceProof(provenance.cumulative.input, retained, singleObservation)
        is Declared -> singleObservation || QuantityProvenance.lossless(provenance) && hasAdditiveSourceProof(provenance.input, retained, false)
        is Quantized -> singleObservation || QuantityProvenance.lossless(provenance) && hasAdditiveSourceProof(provenance.input, retained, false)
        is Cast -> singleObservation || QuantityProvenance.lossless(provenance) && hasAdditiveSourceProof(provenance.input, retained, false)
        is Alternatives -> (singleObservation || QuantityProvenance.commonSnapshotScope(provenance.branches, retained)) &&
            provenance.branches.all { hasAdditiveSourceProof(it, retained, singleObservation) }
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
