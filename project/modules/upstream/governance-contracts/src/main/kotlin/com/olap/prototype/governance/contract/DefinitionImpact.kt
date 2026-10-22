package com.olap.prototype.governance.contract

import com.olap.prototype.contract.ProducerContracts

class DefinitionImpact(val target: Reference, dependents: List<Dependent>) {
    val dependents: List<Dependent> = java.util.List.copyOf(dependents)
    data class Reference(val assetType: String, val id: String, val version: String)
    enum class Relation { DEFINITION_REFERENCE, ADOPTION_BINDING, CONTEXT_MEMBER, SCENARIO_SELECTION, AUTHORIZATION, FROZEN_SNAPSHOT }
    data class Step(val consumer: Reference, val relation: Relation)
    class Dependent(val reference: Reference, path: List<Step>) {
        val path: List<Step> = java.util.List.copyOf(path)
         val direct: Boolean get() = path.size == 1
        override fun equals(other: Any?): Boolean = other is Dependent && reference == other.reference && path == other.path
        override fun hashCode(): Int = 31 * reference.hashCode() + path.hashCode()
    }
    override fun equals(other: Any?): Boolean = other is DefinitionImpact && target == other.target && dependents == other.dependents
    override fun hashCode(): Int = 31 * target.hashCode() + dependents.hashCode()
}
data class DefinitionImpactRequest(val assetType: String, val id: String, val version: Long) {
    init {
        ProducerContracts.requireText(id, "impact id"); ProducerContracts.requireVersion(version, "impact version")
        require(assetType in ASSET_TYPES) { "unsupported exact impact assetType: $assetType" }
    }
    companion object {
        val ASSET_TYPES: Set<String> = setOf("PHYSICAL_TABLE", "PHYSICAL_COLUMN", "LOGICAL_TABLE", "LOGIC_UNIT", "TOPOLOGY", "JOIN_PATH", "EVENT", "METRIC", "MEMBER_DEFINITION",
            "DIMENSION", "DIMENSION_EVENT_BINDING", "BUSINESS_LINE", "SUBJECT_AREA", "ANALYSIS_DIMENSION", "SCENARIO", "MEMBER_SOURCE", "METRIC_ADOPTION")
    }
}
