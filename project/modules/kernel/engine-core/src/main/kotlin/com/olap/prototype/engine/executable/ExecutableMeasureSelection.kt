package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.CalculationBindings
import com.olap.prototype.query.GroupingLevels
import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.query.TemporalClockBindings

data class ExecutableMeasureSelection(val grouping: Map<String, ScopedInputContract.DimensionRef>,
    val groupingBindings: Map<String, CalculationBindings.Dimension>, val groupingLevels: GroupingLevels.Bound?,
    val timeRange: ExecutableTimeRange?, val clocks: Map<String, TemporalClockBindings.Value>) {
    init {
        require(grouping.keys.containsAll(groupingBindings.keys)) { "adopted grouping must belong to the actual selection" }
        require(groupingBindings.all { (key, value) -> value.reference == grouping[key] }) { "grouping differs from its exact adoption" }
    }
    fun withoutSourceWindow(): ExecutableMeasureSelection = copy(timeRange = null, clocks = emptyMap())
}
