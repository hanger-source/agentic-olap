package com.olap.prototype.metric

/** 均值的计算目标；输入数量的算术保证与最终结果量化由各自的数量合同保留。 */
enum class MeanTarget {
    /** 对本节点实际参与的值求数学均值，再完成该节点明确声明的最终量化。 */
    MATHEMATICAL,

    /** 有意采用本发布所选实现的原生均值行为，随后应用明确声明的输出合同。 */
    SOURCE_NATIVE;

    companion object {
        fun contract(): Map<String, Any> = linkedMapOf(
            "requiredFor" to listOf("FACT_AVG", "COMPLETED_AVG", "WINDOW_AVG", "SNAPSHOT_MEAN"),
            "mathematical" to "MEAN_OF_ACTUAL_PARTICIPATING_VALUES_THEN_THIS_NODE_FINAL_QUANTIZATION",
            "sourceNative" to "NATIVE_MEAN_OF_THE_EXACT_IMPLEMENTATION_SELECTED_BY_THIS_RELEASE_THEN_DECLARED_OUTPUT",
            "arithmetic" to "INDEPENDENT_INPUT_GUARANTEES_ARE_NOT_UPGRADED_BY_TARGET_OR_RESULT_REPRESENTATION",
            "nativeBehavior" to "CONNECTOR_DESCRIBES_ACTUAL_AGGREGATE_OR_WINDOW_COMPILER_CHECKS_TARGET",
            "snapshot" to "MEAN_VALUE_CONTRACT_QUANTIZES_THE_ENTITY_TIME_MEAN_BEFORE_ANY_OUTER_AGGREGATION",
            "unsupported" to "REJECT_BEFORE_SOURCE_SUBMISSION_WITHOUT_CHANGING_ALGORITHM_OR_EXECUTION_LOCATION"
        )
    }
}
