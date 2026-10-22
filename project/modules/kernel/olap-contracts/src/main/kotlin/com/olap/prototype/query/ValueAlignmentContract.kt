package com.olap.prototype.query

/** 跨事件维度只使用发布冻结且可证明的规范值对齐。 */
object ValueAlignmentContract {
    const val PROVEN_SAME_KEY = "PROVEN_SAME_KEY"
    const val EXPLICIT_MAPPING = "EXPLICIT_MAPPING"
    const val MANUALLY_CONFIRMED = "MANUALLY_CONFIRMED"
    const val NOT_ALIGNABLE = "NOT_ALIGNABLE"
    const val SINGLE_EVENT_ONLY = "SINGLE_EVENT_ONLY"
    private val statuses = java.util.Set.of(PROVEN_SAME_KEY, EXPLICIT_MAPPING, MANUALLY_CONFIRMED, NOT_ALIGNABLE, SINGLE_EVENT_ONLY)
    private val crossEvent = java.util.Set.of(PROVEN_SAME_KEY, EXPLICIT_MAPPING, MANUALLY_CONFIRMED)
    fun allowedStatuses(): Set<String> = statuses
    fun crossEventStatuses(): Set<String> = crossEvent
    fun requireStatus(status: String?, field: String): String = status?.takeIf { it in statuses }
        ?: throw IllegalArgumentException("$field must be one of ${statuses.joinToString(", ")}")
    fun requireEvidenceReference(status: String, alignmentKey: String?, field: String) {
        requireStatus(status, "$field.status")
        if (status in crossEvent) requireText(alignmentKey, "$field.alignmentKey")
    }
    data class AlignmentRef(val eventRef: String, val alignmentKey: String?, val status: String?)
    fun requireCrossEventAlignment(mappings: Collection<AlignmentRef>?, field: String) {
        if (mappings == null || mappings.size < 2) return
        val byEvent = linkedMapOf<String, AlignmentRef>()
        mappings.forEach { mapping ->
            val event = requireText(mapping.eventRef, "$field.eventRef")
            val previous = byEvent.putIfAbsent(event, mapping)
            require(previous == null || previous.alignmentKey == mapping.alignmentKey && previous.status == mapping.status) { "$field has conflicting mappings for event=$event" }
        }
        if (byEvent.size < 2) return
        val keys = byEvent.values.map { mapping ->
            val status = requireStatus(mapping.status, "$field.status")
            require(status in crossEvent) { "$field is not cross-event alignable: event=${mapping.eventRef}, status=$status" }
            requireText(mapping.alignmentKey, "$field.alignmentKey")
        }.toSet()
        require(keys.size == 1) { "$field requires one frozen alignment key across events: $keys" }
    }
    private fun requireText(value: String?, field: String): String = value?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("$field is required")
}
