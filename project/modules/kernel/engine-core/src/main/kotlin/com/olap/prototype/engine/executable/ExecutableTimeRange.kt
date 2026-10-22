package com.olap.prototype.engine.executable

import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.time.TemporalWindow

data class ExecutableTimeRange(val start: String?, val end: String?, val valueType: TemporalWindow.ValueType,
    val boundary: String, val timeDimensionId: String, val timeDimensionVersion: String, val levelCode: String) {
    fun window(): TemporalWindow = TemporalWindow(valueType, boundary, start, end)
    companion object {
        internal fun from(dimension: ScopedInputContract.DimensionRef, window: TemporalWindow): ExecutableTimeRange =
            ExecutableTimeRange(window.start, window.end, window.valueType, window.boundary, dimension.id, dimension.version, dimension.levelCode)
    }
}
