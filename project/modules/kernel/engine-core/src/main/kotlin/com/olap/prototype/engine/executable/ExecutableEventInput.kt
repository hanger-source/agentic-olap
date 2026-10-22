package com.olap.prototype.engine.executable

import com.olap.prototype.engine.catalog.ExecutableEvent
import com.olap.prototype.engine.catalog.ExecutableTopology
import com.olap.prototype.engine.catalog.ExecutableTimeAnchor
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.query.DimensionCondition
import com.olap.prototype.query.TemporalClockBindings
import com.olap.prototype.time.TemporalWindow

/** 事实测量和成员投影共用的事件读取边界。 */
sealed interface ExecutableEventInput {
    val event: ExecutableEvent
    val topology: ExecutableTopology
    val timeAnchor: ExecutableTimeAnchor?
    val analysisDimensions: List<ExecutableBoundAnalysisDimension>
    val filterExpression: DimensionCondition
    fun clocks(): Map<String, TemporalClockBindings.Value>
    fun window(): TemporalWindow?
    fun rangeExpression(): SemanticExpression?
    fun sourceExpressions(): List<SemanticExpression>
}
