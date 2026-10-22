package com.olap.prototype.query

import com.olap.prototype.time.TemporalWindow

/** 时态关联是显式区间的合取，各区间拥有独立精确时钟与来源路径。 */
object TemporalJoinContract {
    class Constraint(intervals: List<Interval>) {
        val intervals: List<Interval> = java.util.List.copyOf(intervals)
        init { require(this.intervals.isNotEmpty()) { "temporal intervals are required" } }
        override fun equals(other: Any?): Boolean = other is Constraint && intervals == other.intervals
        override fun hashCode(): Int = intervals.hashCode()
    }
    data class Interval(val sourceTime: Clock, val targetValidFromLogicUnitId: String, val targetValidFromLogicUnitVersion: Long,
        val targetValidToLogicUnitId: String, val targetValidToLogicUnitVersion: Long, val validFromInclusive: Boolean, val validToInclusive: Boolean, val openEndedValidTo: Boolean) {
        init {
            requireRef(targetValidFromLogicUnitId, targetValidFromLogicUnitVersion, "target interval start LogicUnit")
            requireRef(targetValidToLogicUnitId, targetValidToLogicUnitVersion, "target interval end LogicUnit")
        }
    }
    sealed interface Clock
    sealed interface LogicUnitTime : Clock { val logicUnitId: String; val logicUnitVersion: Long; val joinPathRefs: List<SourceTimeJoinPathRef> }
    class LogicUnitClock(override val logicUnitId: String, override val logicUnitVersion: Long, joinPathRefs: List<SourceTimeJoinPathRef>) : LogicUnitTime {
        override val joinPathRefs: List<SourceTimeJoinPathRef> = java.util.List.copyOf(joinPathRefs)
        init { requireRef(logicUnitId, logicUnitVersion, "source time LogicUnit"); requirePath(this.joinPathRefs) }
        override fun equals(other: Any?): Boolean = other is LogicUnitClock && logicUnitId == other.logicUnitId && logicUnitVersion == other.logicUnitVersion && joinPathRefs == other.joinPathRefs
        override fun hashCode(): Int = listOf(logicUnitId, logicUnitVersion, joinPathRefs).hashCode()
    }
    class BackboneLogicUnitClock(override val logicUnitId: String, override val logicUnitVersion: Long, joinPathRefs: List<SourceTimeJoinPathRef>) : LogicUnitTime {
        override val joinPathRefs: List<SourceTimeJoinPathRef> = java.util.List.copyOf(joinPathRefs)
        init { requireRef(logicUnitId, logicUnitVersion, "backbone source time LogicUnit"); requirePath(this.joinPathRefs) }
        override fun equals(other: Any?): Boolean = other is BackboneLogicUnitClock && logicUnitId == other.logicUnitId && logicUnitVersion == other.logicUnitVersion && joinPathRefs == other.joinPathRefs
        override fun hashCode(): Int = listOf(logicUnitId, logicUnitVersion, joinPathRefs).hashCode()
    }
    data class QueryClock(val valueType: TemporalWindow.ValueType, val description: String) : Clock {
        init { require(description.isNotBlank()) { "query clock business description is required" } }
    }
    data class SourceTimeJoinPathRef(val joinPathId: String, val joinPathVersion: Long) {
        init { requireRef(joinPathId, joinPathVersion, "source time join path") }
    }
    private fun requireRef(id: String, version: Long, field: String) { require(id.isNotBlank() && version > 0) { "$field requires an exact identity and positive version" } }
    private fun requirePath(path: List<SourceTimeJoinPathRef>) {
        require(path.map { it.joinPathId }.distinct().size == path.size) { "source time path repeats a relationship identity" }
    }
}
