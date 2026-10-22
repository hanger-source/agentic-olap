package com.olap.prototype.metric

enum class MetricAggregation {
    SUM, COUNT, AVG, STDDEV_SAMP, MIN, MAX;
    companion object {
        fun requireValueContract(aggregation: String, declaration: MetricValueContract?) {
            require(aggregation != STDDEV_SAMP.name || declaration != null && declaration.arithmetic !is MetricValueContract.Exact) { "STDDEV_SAMP requires explicit native floating or approximate arithmetic" }
        }
        fun parse(rawValue: String?, field: String): MetricAggregation {
            require(!rawValue.isNullOrBlank()) { "$field is required" }
            return try { valueOf(rawValue.trim()) } catch (failure: IllegalArgumentException) { throw IllegalArgumentException("$field unsupported: $rawValue", failure) }
        }
    }
}
