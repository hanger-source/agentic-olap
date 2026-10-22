package com.olap.prototype.engine.catalog

import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.calculation.MetricRef

data class ExecutableMetric(val reference: MetricRef, val definition: MetricDefinition) {
    fun factMeasure(): MetricDefinition.FactMeasure = definition as? MetricDefinition.FactMeasure
        ?: throw IllegalArgumentException("metric is not a fact measure: $reference")
}
