package com.olap.prototype.member

import com.olap.prototype.calculation.MetricDefinition

data class MemberRef(val id: String, val version: Long) {
    init { MetricDefinition.text(id, "member sourceId"); MetricDefinition.version(version) }
}
enum class MemberShape { RAW_BAG, RECORDS, UNIQUE_MEMBERS }
