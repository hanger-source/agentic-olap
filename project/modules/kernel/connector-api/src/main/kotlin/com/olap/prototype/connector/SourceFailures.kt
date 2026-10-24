package com.olap.prototype.connector

import com.olap.prototype.warehouse.contract.SourceBindingRef
import com.olap.prototype.metric.MetricAggregation

class ConnectorUnavailableException(val connectorRef: ConnectorRef) : IllegalArgumentException("exact connector is not deployed: $connectorRef")

/** 原生类型映射明确拒绝该聚合；公开消息不带插件异常原文。 */
class NativeAggregateMappingException(val aggregate: MetricAggregation, val inputType: String, cause: Throwable) :
    IllegalArgumentException("native ${aggregate.name} mapping unavailable for input type $inputType", cause) {
    val code: String = "NATIVE_AGGREGATE_MAPPING_UNAVAILABLE"
}

/** 实际原生算子在绑定／返回类型推导时拒绝输入；不公开插件异常原文或业务值。 */
class NativeArithmeticMappingException(val operation: String, inputTypes: List<String>, cause: Throwable) :
    IllegalArgumentException("native $operation mapping unavailable for the declared input types", cause) {
    val inputTypes: List<String> = java.util.List.copyOf(inputTypes)
    val code: String = "NATIVE_ARITHMETIC_MAPPING_UNAVAILABLE"
}

/** 原生SQL构造所需的编译保证；不包含查询文本或业务值。 */
class NativeCompilationCapabilityException(val capability: CompilationSupport.Capability, val operation: String) :
    IllegalArgumentException("native SQL operation $operation requires compilation capability ${capability.name}") {
    val code: String = "NATIVE_COMPILATION_CAPABILITY_MISSING"
}
class SourceBindingUnavailableException private constructor(val binding: SourceBindingRef, override val message: String) : IllegalArgumentException(message) {
    constructor(binding: SourceBindingRef) : this(binding, "exact source binding is not deployed: $binding")
}

/** 保留缺失能力的来源与操作，不产生全来源可用状态。 */
class SourceCapabilityException private constructor(val dataSourceId: String, val operation: String,
    val capability: SourceCapabilities.Capability, override val message: String) : IllegalStateException(message) {
    constructor(dataSourceId: String, operation: String, capability: SourceCapabilities.Capability) :
        this(dataSourceId, operation, capability, "SOURCE_CAPABILITY_MISSING: source=$dataSourceId, operation=$operation, capability=$capability")
    val code: String = "SOURCE_CAPABILITY_MISSING"
}

/** 已有来源结果不能按本次编译合同交付，与访问失败及预算超限区分。 */
class SourceResultMaterializationException(cause: Throwable) : IllegalStateException("source result materialization failed", cause) {
    val code: String = "EXECUTABLE_SOURCE_RESULT_FAILED"
}
