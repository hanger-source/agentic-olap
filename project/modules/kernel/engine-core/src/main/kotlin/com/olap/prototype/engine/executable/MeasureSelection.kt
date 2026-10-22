package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.CalculationBindings
import com.olap.prototype.query.DimensionCondition

/** 一次测量的来源选择，不复制调用身份、活动栈或预算。 */
internal data class MeasureSelection(val source: ExecutableMeasureSelection, val filter: DimensionCondition, val timeBinding: CalculationBindings.Dimension?) {
    val grouping get() = source.grouping
    val levels get() = source.groupingLevels
    val range get() = source.timeRange
    val groupingBindings get() = source.groupingBindings
}
