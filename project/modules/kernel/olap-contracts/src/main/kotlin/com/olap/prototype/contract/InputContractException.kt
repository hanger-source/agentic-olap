package com.olap.prototype.contract


/** 作者输入合同的公开拒绝；携带规则、位置和形态，分页可携数值坐标，不携带业务内容或解析器堆栈。 */
class InputContractException private constructor(val rule: Rule, message: String, val details: Map<String, Any?>, cause: Throwable? = null) :
    ProducerContractException(message, cause) {
    enum class Rule(val code: String, val stage: String) {
        DECODE_INPUT("INVALID_REQUEST", "DECODE_INPUT"),
        PAGINATION("INVALID_PAGINATION", "CONTRACT_INPUT"),
        DIMENSION_SLOT_REQUIRED("CALCULATION_DIMENSION_SLOT_REQUIRED", "CONTRACT_INPUT"),
        MEASUREMENT_BINDING_OVERRIDE("MEASUREMENT_BINDING_OVERRIDE", "CONTRACT_INPUT"),
        MEMBER_QUANTITY_TYPE("MEMBER_QUANTITY_TYPE", "CONTRACT_INPUT"),
        MEMBER_QUANTITY_UNIT_EQUATIONS("MEMBER_QUANTITY_UNIT_EQUATIONS", "CONTRACT_INPUT"),
        LOGIC_UNIT_VALUE_KIND("LOGIC_UNIT_VALUE_KIND", "CONTRACT_INPUT"),
        LOGIC_UNIT_CONTAINER_FLAG("LOGIC_UNIT_CONTAINER_FLAG", "CONTRACT_INPUT"),
        SOURCE_CONTAINER_KIND("SOURCE_CONTAINER_KIND", "CONTRACT_INPUT"),
        SOURCE_CONTAINER_LEAF("SOURCE_CONTAINER_LEAF", "CONTRACT_INPUT"),
        SOURCE_CONTAINER_QUANTITY_UNIT("SOURCE_CONTAINER_QUANTITY_UNIT", "CONTRACT_INPUT"),
        ROOT_INPUT_REQUIRED("ROOT_INPUT_REQUIRED", "AUTHORIZE_INPUT"),
        RESULT_NUMERIC_CONTRACT_REQUIRED("RESULT_NUMERIC_CONTRACT_REQUIRED", "ANALYZE_STAGES"),
        RESULT_UNIT_EQUATION_REQUIRED("RESULT_UNIT_EQUATION_REQUIRED", "ANALYZE_STAGES"),
        RESULT_UNIT_EQUATION_MISMATCH("RESULT_UNIT_EQUATION_MISMATCH", "ANALYZE_STAGES"),
        UNIT_EQUATION_RESULT_MISMATCH("UNIT_EQUATION_RESULT_MISMATCH", "CONTRACT_INPUT"),
        RESULT_UNIT_MISMATCH("RESULT_UNIT_MISMATCH", "ANALYZE_STAGES"),
        EXACT_IDENTITY_MEANING_REQUIRED("EXACT_IDENTITY_MEANING_REQUIRED", "ANALYZE_STAGES"),
    }
    companion object {
        fun violation(rule: Rule, path: List<Any>, message: String, expectedKind: String, actualKind: String): InputContractException =
            InputContractException(rule, message, mapOf("fieldPath" to path.toList(), "expectedKind" to expectedKind, "actualKind" to actualKind))

        fun pagination(field: String, actual: Number, minimum: Long, maximum: Long): InputContractException =
            InputContractException(Rule.PAGINATION, "$field must be an integer in $minimum..$maximum; actual=$actual",
                mapOf("fieldPath" to listOf(field), "expectedKind" to "INTEGER_IN_RANGE[$minimum,$maximum]",
                    "actualKind" to actual.toString(), "expected" to mapOf("minimum" to minimum, "maximum" to maximum), "actual" to actual))

    }
}
