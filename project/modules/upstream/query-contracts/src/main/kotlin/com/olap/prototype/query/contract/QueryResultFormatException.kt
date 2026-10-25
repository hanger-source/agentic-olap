package com.olap.prototype.query.contract

/** 保存结果的明确读取边界；不尝试转换或解释不受支持的历史内容。 */
class QueryResultFormatException(val component: Component, val actual: String, val supported: String) :
    IllegalArgumentException("saved query result format is not supported by this deployment; use a matching product version to read the unchanged result") {
    enum class Component { ARTIFACT, RECORD }
    val code: String get() = "QUERY_RESULT_FORMAT_UNSUPPORTED"
    val details: Map<String, String> get() = mapOf("component" to component.name, "actual" to actual, "supported" to supported)
}
