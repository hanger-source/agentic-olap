package com.olap.prototype.contract


/** 作者输入合同的公开拒绝；携带规则、位置和形态，不携带输入值或解析器堆栈。 */
class InputContractException private constructor(val rule: Rule, message: String, val details: Map<String, Any?>, cause: Throwable? = null) :
    ProducerContractException(message, cause) {
    enum class Rule(val code: String, val stage: String) {
        DECODE_INPUT("INVALID_REQUEST", "DECODE_INPUT"),
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
        EXACT_IDENTITY_MEANING_REQUIRED("EXACT_IDENTITY_MEANING_REQUIRED", "ANALYZE_STAGES"),
    }
    companion object {
        fun violation(rule: Rule, path: List<Any>, message: String, expectedKind: String, actualKind: String): InputContractException =
            InputContractException(rule, message, mapOf("fieldPath" to path.toList(), "expectedKind" to expectedKind, "actualKind" to actualKind))

    }
}
