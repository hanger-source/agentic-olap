package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.query.QueryExecutionMode

/** 一次内核计算的精确目录、请求身份与类型化计算。 */
data class ExecutableQueryRequest(val formatVersion: String, val requestId: String, val catalogId: String, val computation: Calculation.Scope,
    val executionMode: QueryExecutionMode = QueryExecutionMode.SOURCE) {
    companion object { const val FORMAT_VERSION = "olap-kernel-query/v8" }
}
