package com.olap.prototype.query

import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.PostOperationContract as Stage
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.util.WireTypeShape

/** 完成关系语言的公开说明，不拥有发布能力与授权。 */
object PostOperationSchema {
    fun contract(): Map<String, Any> = mapOf("formatVersion" to "olap-post-operation-contract/v18", "evaluationOrder" to "EXPLICIT_ARRAY_ORDER",
        "symbolScope" to "CURRENT_RESULT_COLUMNS_PROJECT_AND_REGROUP_REPLACE_SCOPE",
        "rowScope" to "Each operation consumes the preceding result, including preceding row limits.",
        "statistics" to statisticsContract(), "operations" to Stage.operationTypes().map { mapOf("opType" to it, "arguments" to argumentContract(it)) })

    fun statisticsContract(): Map<String, Any> = mapOf("STDDEV_SAMP" to mapOf(
        "population" to "CURRENT_NON_NULL_INPUT_OBSERVATIONS_WITH_BAG_MULTIPLICITY", "minimumObservations" to 2, "insufficientObservations" to "NULL",
        "unit" to "PRESERVE_INPUT_MEASUREMENT_UNIT", "arithmetic" to "EXPLICIT_NATIVE_FLOATING_OR_APPROXIMATE_ARITHMETIC",
        "nativeResult" to "EXACT_CONNECTOR_NATIVE_AGGREGATE_RESULT", "aggregationRounding" to "NONE",
        "rollup" to "REEXECUTE_ON_OBSERVATIONS_NEVER_COMBINE_COMPLETED_STANDARD_DEVIATIONS"))

    fun argumentContract(operationType: String): Map<String, Any> = when (operationType) {
        Stage.EXPAND_COLLECTION -> mapOf("requiredArguments" to listOf("input", "elementAlias", "ordinalAlias", "mode"), "input" to "ONE_TYPED_ARRAY_SYMBOL",
            "nativeRequirement" to "COLLECTION_EXPANSION_IN_SELECTED_EXACT_EXECUTION_CONTEXT", "mode" to listOf("INNER", "OUTER"),
            "ordinal" to "ONE_BASED_ACTUAL_ARRAY_POSITION_NOT_A_BUSINESS_ORDER", "nullAndEmpty" to "INNER_ZERO_ROWS_OUTER_ONE_NULL_ELEMENT_AND_NULL_ORDINAL",
            "multiplicity" to "PRESERVE_NULL_ELEMENTS_AND_DUPLICATES", "identity" to "INNER_MAY_EXTEND_ACTUAL_INPUT_IDENTITY_WITH_ORDINAL_OUTER_HAS_NO_ELEMENT_IDENTITY",
            "element" to "COMPLETE_TYPED_VALUE_ROW_NULL_DISTINCT_FROM_ROW_WITH_NULL_FIELDS",
            "nativeMapping" to "CONNECTOR_EXPAND_COLLECTION_RECEIVES_ORDERED_RECURSIVE_ELEMENT_TYPE",
            "quantity" to "COPIED_PARENT_MEASURES_DO_NOT_BECOME_ADDITIVE")
        Stage.SELECT_VERSIONS -> mapOf("requiredArguments" to listOf("partitionBy", "orderKeys", "ties", "snapshot"), "orderKeys" to orderKeysContract(),
            "ties" to listOf("FAIL", "KEEP_ALL"), "snapshot" to "EXPLICIT_NULL_OR_TIME_SYMBOL_AND_NULLABLE_TEMPORAL_AS_OF_BINDING",
            "evaluation" to "BOUND_AS_OF_FILTER_THEN_SELECT_FIRST_ORDERED_VERSION_PER_PARTITION",
            "failure" to "FAIL_CHECKS_ACTUAL_WINNING_PEERS_IN_THE_EXECUTION_TRANSACTION", "quantity" to "ONLY_EXPLICIT_SNAPSHOT_SELECTION_PRESERVES_ACTUAL_STOCK_TIME_POINT")
        Stage.OBSERVE_WINDOW -> mapOf("requiredArguments" to listOf("inputKeys", "partitionBy", "outputAlias", "outputDisplayName", "orderKeys", "aggregation", "nullPolicy", "frame",
            "valueContract", "aggregationRounding", "domain", "timeSymbol", "observationRange", "displayRange", "calendar"),
            "domains" to listOf("ROWS", "ELAPSED", "CALENDAR"), "aggregations" to PostOperationCodec.aggregationTypes.keys.toList(), "frame" to WindowFrameContract.contract(),
            "ranges" to "EXPLICIT_NULL_OR_RANGE_BINDING_OBSERVATION_BEFORE_WINDOW_DISPLAY_AFTER_WINDOW",
            "calendar" to "EXPLICIT_NULL_OR_DECLARED_INPUT_ID_AND_DATE_SYMBOL_NO_GENERATED_DATES_OR_ZERO_FILL",
            "coverage" to "CALENDAR_REQUIRES_FINITE_DATE_OBSERVATION_RANGE_AND_TRANSACTIONAL_NONNULL_UNIQUE_DAY_COVERAGE",
            "distinct" to "COUNT_DISTINCT_USES_THE_COMPLETE_INPUT_KEY_TUPLE_NOT_SUM_OF_COMPLETED_COUNTS")
        Stage.ANCESTOR_CLOSURE -> mapOf("requiredArguments" to listOf("sourceKeys", "parentKeys", "descendantAliases", "ancestorAliases", "includeSelf"),
            "keys" to "EQUAL_LENGTH_NONEMPTY_EXACT_SCALAR_IDENTITY_TUPLES_UP_TO_16_COMPONENTS",
            "result" to "DISTINCT_REACHABLE_DESCENDANT_ANCESTOR_PAIRS_REPLACES_CURRENT_COLUMNS",
            "reflexivity" to "EXPLICIT_INCLUDE_SELF_OVER_OBSERVED_NON_NULL_ENDPOINT_IDENTITIES", "sourceNull" to "REJECT_ANY_NULL_SOURCE_COMPONENT",
            "parentNull" to "ALL_NULL_ENDS_OBSERVED_RELATION_PARTIAL_NULL_REJECTED", "cycles" to "FAIL_BEFORE_BUSINESS_QUERY",
            "unknownTargets" to "REACHABILITY_DOES_NOT_PROVE_REGISTRATION_OR_BUSINESS_ROOT", "execution" to "NATIVE_DISTINCT_RECURSIVE_FIXED_POINT_NO_FIXED_DEPTH")
        Stage.JOIN_INPUT -> mapOf("requiredArguments" to listOf("inputId", "occurrenceId", "joinType", "condition"),
            "joinTypes" to listOf("INNER", "LEFT", "RIGHT", "FULL", "SEMI", "ANTI"), "expressionContract" to ResultExpressionSchema.contract(),
            "scope" to "CURRENT_COMPLETED_RESULT_AND_EXPLICIT_RELATION_INPUT_OUTPUTS", "symbols" to "RIGHT_COLUMNS_PREFIXED_BY_EXPLICIT_OCCURRENCE_ID_DOUBLE_UNDERSCORE",
            "predicate" to "SQL_THREE_VALUED_ON_TRUE_MATCHES_NO_IMPLICIT_NULL_EQUALITY", "multiplicity" to "PRESERVE_BAG_MATCHES_NO_IMPLICIT_DEDUPLICATION_OR_AGGREGATION",
            "semiAnti" to "ONLY_LEFT_COLUMNS_AND_EACH_LEFT_ROW_AT_MOST_ONCE", "phase" to "CHILD_LOCAL_STAGES_FINISH_BEFORE_JOIN_SUBSEQUENT_STAGES_CONSUME_JOIN_RESULT")
        Stage.PROJECT -> mapOf("requiredArguments" to listOf("symbols"), "symbols" to "NONEMPTY_UNIQUE_CURRENT_RESULT_SYMBOLS_IN_OUTPUT_ORDER",
            "rowScope" to "PRESERVE_ALL_CURRENT_ROWS_DUPLICATES_NULLS_AND_ORDER", "types" to "PRESERVE_EXACT_SELECTED_COLUMN_TYPES_AND_MEANINGS",
            "grain" to "DOES_NOT_REAGGREGATE_OR_CHANGE_UNIQUE_KEYS")
        Stage.DERIVE -> mapOf("requiredArguments" to listOf("expression", "outputAlias", "outputDisplayName"), "expressionContract" to ResultExpressionSchema.contract())
        Stage.CUMULATIVE -> mapOf("requiredArguments" to listOf("input", "orderBy", "direction", "partitionBy", "outputAlias", "outputDisplayName", "valueContract", "aggregationRounding"),
            "directions" to listOf("ASC", "DESC"), "frame" to "ROWS_UNBOUNDED_PRECEDING_TO_CURRENT_ROW", "nullInputs" to "IGNORED_BY_SUM_ALL_NULL_FRAME_RETURNS_NULL",
            "orderRequirement" to "Caller must provide a unique ordering key when peer order affects meaning.")
        Stage.RANK -> mapOf("requiredArguments" to listOf("orderBy", "direction", "partitionBy", "outputAlias", "outputDisplayName"),
            "directions" to listOf("ASC", "DESC"), "ties" to "EQUAL_RANK_WITH_GAPS")
        Stage.TOP_N -> mapOf("requiredArguments" to listOf("orderKeys", "limit"), "orderKeys" to orderKeysContract(), "maxLimit" to Stage.MAX_TOP_N_LIMIT,
            "scope" to "GLOBAL_CURRENT_RESULT", "ties" to "NO_ADDITIONAL_TIE_BREAKER")
        Stage.POST_FILTER -> mapOf("requiredArguments" to listOf("expression"), "expressionContract" to ResultExpressionSchema.contract(), "rootType" to "BOOLEAN",
            "retainedRows" to "TRUE_ONLY_FALSE_AND_UNKNOWN_EXCLUDED", "scope" to "CURRENT_AGGREGATED_ALIGNED_AND_PRECEDING_OPERATION_RESULTS_NOT_SOURCE_ROWS")
        Stage.SORT -> mapOf("requiredArguments" to listOf("orderKeys"), "orderKeys" to orderKeysContract(), "rowLimit" to "NONE", "scope" to "GLOBAL_CURRENT_RESULT")
        Stage.ROW_NUMBER, Stage.DENSE_RANK -> mapOf("requiredArguments" to listOf("orderKeys", "partitionBy", "outputAlias", "outputDisplayName"), "orderKeys" to orderKeysContract(),
            "partitionBy" to "AVAILABLE_RESULT_SYMBOLS", "ties" to if (operationType == Stage.ROW_NUMBER)
                "DISTINCT_SEQUENTIAL_NUMBERS_CALLER_MUST_SUPPLY_TOTAL_ORDER_FOR_DETERMINISM" else "EQUAL_ORDER_KEY_TUPLES_SHARE_RANK_WITHOUT_GAPS")
        Stage.PARTITION_AGGREGATE -> mapOf("requiredArguments" to listOf("input", "aggregation", "nullPolicy", "partitionBy", "outputAlias", "outputDisplayName", "valueContract", "aggregationRounding"),
            "aggregations" to PostOperationCodec.aggregationTypes.keys.toList(), "frame" to "COMPLETE_CURRENT_PARTITION", "retainedRows" to "ALL_INPUT_ROWS_WITH_REPEATED_PARTITION_VALUE",
            "nullPolicies" to mapOf("IGNORE" to "SQL_AGGREGATE_NULL_RULES_COUNT_RETURNS_ZERO_FOR_ALL_NULL", "PROPAGATE" to "ANY_NULL_INPUT_MAKES_WHOLE_PARTITION_RESULT_NULL"),
            "collections" to collectionContract(), "coverage" to "ONLY_EXISTING_ROWS_NOT_PROOF_OF_MISSING_SOURCE_RECORDS_OR_EXPECTED_PERIODS")
        Stage.WINDOW_AGGREGATE -> mapOf("requiredArguments" to listOf("input", "aggregation", "nullPolicy", "partitionBy", "orderKeys", "frame", "outputAlias", "outputDisplayName", "valueContract", "aggregationRounding"),
            "aggregations" to PostOperationCodec.aggregationTypes.keys.toList(), "collections" to collectionContract(),
            "nullPolicies" to mapOf("IGNORE" to "SQL_AGGREGATE_NULL_RULES", "PROPAGATE" to "ANY_NULL_IN_FRAME_RETURNS_NULL"), "orderKeys" to orderKeysContract(),
            "frame" to WindowFrameContract.contract(), "retainedRows" to "ALL_CURRENT_INPUT_ROWS_EMPTY_FRAME_COUNT_AND_COUNT_DISTINCT_ZERO_OTHER_AGGREGATES_NULL")
        Stage.OFFSET_VALUE -> mapOf("requiredArguments" to listOf("input", "function", "offset", "partitionBy", "orderKeys", "outputAlias", "outputDisplayName"),
            "functions" to listOf("LAG", "LEAD"), "offset" to "POSITIVE_INTEGER_UP_TO_2147483647", "orderKeys" to orderKeysContract(),
            "nullInputs" to "RESPECT_NULLS_NO_NON_NULL_SEARCH", "missingOffsetRow" to "NULL_NO_DEFAULT_VALUE", "ordering" to "CALLER_MUST_SUPPLY_TOTAL_ORDER",
            "periodMeaning" to "OFFSET_IN_EXISTING_ROWS_NOT_PREVIOUS_CALENDAR_PERIOD")
        Stage.REGROUP -> regroupContract()
        else -> throw IllegalArgumentException("post operation is not executable: $operationType")
    }

    private fun regroupContract(): Map<String, Any> = mapOf("requiredArguments" to listOf("groupBy", "aggregates", "groupingLevels"),
        "groupBy" to "EXPLICIT_UNIQUE_CURRENT_RESULT_SYMBOLS_EMPTY_MEANS_GLOBAL",
        "aggregates" to mapOf("minimumItems" to 1,
            "fields" to listOf("input", "aggregation", "nullPolicy", "outputAlias", "outputDisplayName", "valueContract", "aggregationRounding"),
            "aggregations" to PostOperationCodec.aggregationTypes.keys.toList(), "nullPolicies" to listOf("IGNORE", "PROPAGATE", "RESPECT"), "valueAndRounding" to aggregateValueContract(),
            "wireShape" to WireTypeShape(OlapJsonUtils.mapper()).describe(OlapJsonUtils.mapper().constructType(Stage.ResultAggregate::class.java), "olap-wire-type-shape/v1")),
        "collections" to collectionContract(), "outputScope" to "ONLY_GROUP_BY_COLUMNS_AND_DECLARED_AGGREGATE_OUTPUTS_IN_ARRAY_ORDER",
        "nullRules" to "IGNORE_USES_SQL_RULES_PROPAGATE_ANY_NULL_INPUT_MAKES_GROUP_RESULT_NULL",
        "emptyInput" to "GROUPED_RETURNS_NO_GROUPS_GLOBAL_COUNT_RETURNS_ZERO_OTHER_AGGREGATES_NULL",
        "distinctScope" to "DISTINCT_CURRENT_INPUT_VALUES_NOT_SUM_OF_PREAGGREGATED_DISTINCT_COUNTS", "coverage" to "NO_MISSING_GROUP_OR_PERIOD_COMPLETION_NO_IMPLICIT_DEDUPLICATION")

    fun aggregateValueContract(): Map<String, Any> = mapOf("requiredFields" to listOf("valueContract", "aggregationRounding"),
        "byAggregation" to PostOperationCodec.aggregationTypes.mapValues { (_, type) -> mapOf(
            "valueContract" to if (ResultAggregation.Numeric::class.java.isAssignableFrom(type)) "REQUIRED_NON_NULL_METRIC_VALUE_CONTRACT" else "REQUIRED_EXPLICIT_NULL",
            "aggregationRounding" to if (type == ResultAggregation.Average::class.java) MetricValueContract.Rounding.entries.map { it.name } else listOf("NONE")) })

    private fun collectionContract(): Map<String, Any> = mapOf("aggregations" to PostOperationCodec.aggregationTypes.filterValues { it == ResultAggregation.Collect::class.java }.keys.toList(),
        "nullPolicy" to "RESPECT_ONLY_NULL_ELEMENTS_AND_ROW_FIELDS_PRESERVED", "order" to "UNSPECIFIED_NOT_A_SEQUENCE_OR_PARALLEL_ARRAY_POSITIONAL_JOIN",
        "distinct" to "WHOLE_INPUT_VALUE_OR_WHOLE_ROW_NOT_PER_FIELD", "emptyInput" to "NULL_NOT_A_COMPLETED_SOURCE_OR_KNOWN_EMPTY_POPULATION",
        "values" to "RECURSIVE_TYPED_ARRAY_AND_ROW_NOT_JSON_TEXT", "scope" to "CURRENT_ROWS_OR_EXPLICIT_WINDOW_NO_DYNAMIC_JOIN_OR_MISSING_DATE_COMPLETION")
    private fun orderKeysContract(): Map<String, Any> = mapOf("minimumItems" to 1, "fields" to listOf("symbol", "direction", "nulls"), "directions" to listOf("ASC", "DESC"),
        "nulls" to listOf("FIRST", "LAST"), "precedence" to "ARRAY_ORDER", "symbols" to "SELECTED_RESULTS_AND_PRECEDING_OPERATION_OUTPUTS")
}
