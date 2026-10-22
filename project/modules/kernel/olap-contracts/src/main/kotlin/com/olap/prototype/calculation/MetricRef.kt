package com.olap.prototype.calculation

/** 精确指标定义引用，不解释上游命名。 */
data class MetricRef(val id: String, val version: String) {
    init { MetricDefinition.text(id, "metric definition id"); MetricDefinition.text(version, "metric definition version") }
    fun key(): String = "${id.toByteArray(Charsets.UTF_8).size}:$id${version.toByteArray(Charsets.UTF_8).size}:$version"
}
data class MetricInstanceRef(val id: String) {
    init { MetricDefinition.text(id, "metric instance id") }
}
