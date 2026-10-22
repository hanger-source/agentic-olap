package com.olap.prototype.query

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.time.TemporalWindow
import com.olap.prototype.util.OlapJsonUtils

/** 时态关联是显式区间的合取，各区间拥有独立精确时钟与来源路径。 */
object TemporalJoinContract {
    private val intervalProperties by lazy {
        val mapper = OlapJsonUtils.mapper()
        mapper.deserializationConfig.introspect(mapper.constructType(Interval::class.java)).findProperties().filter { it.couldDeserialize() }
    }
    private val intervalFields: Set<String> by lazy { intervalProperties.map { it.name }.toSet() }

    /** 空对象是显式非时态关联；字段来自正式Jackson模型，不依赖Java record反射。 */
    fun wireContract(): Map<String, Any> {
        val clocks = listOf(
            mapOf("kind" to "LOGIC_UNIT", "requiredFields" to listOf("kind", "logicUnitId", "logicUnitVersion", "joinPathRefs")),
            mapOf("kind" to "BACKBONE_LOGIC_UNIT", "requiredFields" to listOf("kind", "logicUnitId", "logicUnitVersion", "joinPathRefs"),
                "meaning" to "ordered exact ONE_TO_ONE or MANY_TO_ONE path from the topology backbone; independent of the temporal target; query fact must be that backbone"),
            mapOf("kind" to "QUERY_CLOCK", "requiredFields" to listOf("kind", "valueType", "description"), "valueTypes" to listOf("DATE", "LOCAL_TIMESTAMP", "OFFSET_TIMESTAMP")),
        )
        val fields = intervalProperties.map { property ->
            if (property.primaryType.rawClass == Clock::class.java) mapOf("name" to property.name, "kind" to "discriminated-object", "requiredByContract" to true,
                "variants" to clocks, "additionalFieldsAllowed" to false)
            else mapOf("name" to property.name, "requiredByContract" to true, "kind" to when (property.primaryType.rawClass) {
                Boolean::class.javaPrimitiveType -> "boolean"
                Long::class.javaPrimitiveType -> "positive-integer"
                String::class.java -> "non-blank-string"
                else -> error("unsupported temporal interval field: ${property.name}")
            })
        }
        return mapOf("kind" to "string", "requiredByContract" to true, "encoding" to "json", "emptyObjectMeaning" to "NON_TEMPORAL_JOIN",
            "nonEmptyObject" to mapOf("kind" to "object", "additionalFieldsAllowed" to false, "fields" to listOf(
                mapOf("name" to "intervals", "kind" to "array", "requiredByContract" to true, "minItems" to 1,
                    "meaning" to "all intervals must hold in the join condition (AND)", "items" to mapOf("kind" to "object", "additionalFieldsAllowed" to false, "fields" to fields)))))
    }

    fun parse(json: String, field: String): Constraint? {
        require(json.isNotBlank()) { "$field is required" }
        val root = try { OlapJsonUtils.mapper().readTree(json) } catch (failure: Exception) { throw IllegalArgumentException("$field must be a JSON object", failure) }
        require(root != null && root.isObject) { "$field must be a JSON object" }
        if (root.isEmpty) return null
        requireFields(root, setOf("intervals"), field)
        val intervals = root["intervals"]
        require(intervals.isArray && !intervals.isEmpty) { "$field.intervals must be a non-empty array" }
        return Constraint(intervals.mapIndexed { index, interval ->
            val location = "$field.intervals[$index]"
            requireFields(interval, intervalFields, location)
            Interval(clock(interval["sourceTime"], "$location.sourceTime"), text(interval, "targetValidFromLogicUnitId", location),
                version(interval, "targetValidFromLogicUnitVersion", location), text(interval, "targetValidToLogicUnitId", location),
                version(interval, "targetValidToLogicUnitVersion", location), bool(interval, "validFromInclusive", location),
                bool(interval, "validToInclusive", location), bool(interval, "openEndedValidTo", location))
        })
    }

    private fun clock(root: JsonNode?, field: String): Clock = when (val kind = text(root, "kind", field)) {
        "LOGIC_UNIT", "BACKBONE_LOGIC_UNIT" -> {
            requireFields(root, setOf("kind", "logicUnitId", "logicUnitVersion", "joinPathRefs"), field)
            if (kind == "BACKBONE_LOGIC_UNIT") BackboneLogicUnitClock(text(root, "logicUnitId", field), version(root, "logicUnitVersion", field), sourceTimePaths(root, field))
            else LogicUnitClock(text(root, "logicUnitId", field), version(root, "logicUnitVersion", field), sourceTimePaths(root, field))
        }
        "QUERY_CLOCK" -> {
            requireFields(root, setOf("kind", "valueType", "description"), field)
            QueryClock(TemporalWindow.ValueType.valueOf(text(root, "valueType", field)), text(root, "description", field))
        }
        else -> throw IllegalArgumentException("$field.kind is unsupported: $kind")
    }
    private fun sourceTimePaths(root: JsonNode?, field: String): List<SourceTimeJoinPathRef> {
        val paths = root?.get("joinPathRefs")
        require(paths != null && paths.isArray) { "$field.joinPathRefs must be an explicit array" }
        val seen = linkedSetOf<String>()
        return paths.map { path ->
            requireFields(path, setOf("joinPathId", "joinPathVersion"), "$field.joinPathRefs")
            val ref = SourceTimeJoinPathRef(text(path, "joinPathId", field), version(path, "joinPathVersion", field))
            require(seen.add(ref.joinPathId)) { "$field.joinPathRefs repeats a path identity" }
            ref
        }
    }
    private fun requireFields(root: JsonNode?, fields: Set<String>, field: String) {
        require(root != null && root.isObject) { "$field must be a JSON object" }
        val actual = root.fieldNames().asSequence().toSet()
        require(actual == fields) { "$field must contain exactly $fields, missing=${fields - actual}, unknown=${actual - fields}" }
    }
    private fun text(root: JsonNode?, name: String, field: String): String {
        val value = root?.get(name)
        require(value != null && value.isTextual && value.textValue().isNotBlank()) { "$field.$name is required" }
        return value.textValue()
    }
    private fun version(root: JsonNode?, name: String, field: String): Long {
        val value = root?.get(name)
        require(value != null && value.isIntegralNumber && value.canConvertToLong() && value.longValue() > 0) { "$field.$name must be a positive integer" }
        return value.longValue()
    }
    private fun bool(root: JsonNode?, name: String, field: String): Boolean {
        val value = root?.get(name)
        require(value != null && value.isBoolean) { "$field.$name must be boolean" }
        return value.booleanValue()
    }

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
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = LogicUnitClock::class, name = "LOGIC_UNIT"), JsonSubTypes.Type(value = BackboneLogicUnitClock::class, name = "BACKBONE_LOGIC_UNIT"), JsonSubTypes.Type(value = QueryClock::class, name = "QUERY_CLOCK"))
    sealed interface Clock
    sealed interface LogicUnitTime : Clock { val logicUnitId: String; val logicUnitVersion: Long; val joinPathRefs: List<SourceTimeJoinPathRef> }
    class LogicUnitClock(override val logicUnitId: String, override val logicUnitVersion: Long, joinPathRefs: List<SourceTimeJoinPathRef>) : LogicUnitTime {
        override val joinPathRefs: List<SourceTimeJoinPathRef> = java.util.List.copyOf(joinPathRefs)
        init { requireRef(logicUnitId, logicUnitVersion, "source time LogicUnit") }
        override fun equals(other: Any?): Boolean = other is LogicUnitClock && logicUnitId == other.logicUnitId && logicUnitVersion == other.logicUnitVersion && joinPathRefs == other.joinPathRefs
        override fun hashCode(): Int = listOf(logicUnitId, logicUnitVersion, joinPathRefs).hashCode()
    }
    class BackboneLogicUnitClock(override val logicUnitId: String, override val logicUnitVersion: Long, joinPathRefs: List<SourceTimeJoinPathRef>) : LogicUnitTime {
        override val joinPathRefs: List<SourceTimeJoinPathRef> = java.util.List.copyOf(joinPathRefs)
        init { requireRef(logicUnitId, logicUnitVersion, "backbone source time LogicUnit") }
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
}
