package com.olap.prototype.metric

enum class MetricMergePolicy {
    ADDITIVE, REAGGREGATE, DISTINCT_NON_ADDITIVE, RATIO_RECOMPUTE, SEMI_ADDITIVE, DESCRIPTIVE_SET, UNSUPPORTED;
    fun publishable(): Boolean = this != UNSUPPORTED
    companion object {
        fun parse(rawValue: String?, field: String): MetricMergePolicy {
            require(!rawValue.isNullOrBlank()) { "$field is required" }
            return try { valueOf(rawValue.trim()) } catch (failure: IllegalArgumentException) { throw IllegalArgumentException("$field unsupported: $rawValue", failure) }
        }
    }
}
