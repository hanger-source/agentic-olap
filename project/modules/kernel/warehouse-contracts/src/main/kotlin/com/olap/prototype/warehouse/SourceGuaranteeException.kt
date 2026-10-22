package com.olap.prototype.warehouse

/** 只携带源保证的安全失败事实，不携带连接或底层错误原文。 */
class SourceGuaranteeException(val code: String, val sqlState: String? = null, val failureType: String? = null) : IllegalStateException(code) {
    init { require(code.matches(Regex("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*"))) { "source guarantee failure requires a machine-readable code" } }
}
