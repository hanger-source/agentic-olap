package com.olap.prototype.query.contract.catalog

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.SourceJoinContract
import com.olap.prototype.query.contract.SealedSourceCollectionMeaning

/** 关联的语义键、区间和独立物理位置派生的有限源说明。 */
class SemanticJoin(@param:JsonProperty(required = true) val joinType: SourceJoinContract.JoinType,
    @param:JsonProperty(required = true) val cardinality: SourceJoinContract.Cardinality,
    @param:JsonProperty(required = true) val nullPolicy: SourceJoinContract.NullPolicy,
    @param:JsonProperty(required = true) keys: List<KeyPair>, @param:JsonProperty(required = true) intervals: List<Interval>,
    sourceSealedSourceCollections: CatalogOptional<List<SealedSourceCollectionMeaning>> = CatalogOptional.absent(),
    targetSealedSourceCollections: CatalogOptional<List<SealedSourceCollectionMeaning>> = CatalogOptional.absent()) {
    val keys: List<KeyPair> = CatalogJson.nonempty(keys)
    val intervals: List<Interval> = java.util.List.copyOf(intervals)
    @get:JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = CatalogOptional.Missing::class)
    val sourceSealedSourceCollections: CatalogOptional<List<SealedSourceCollectionMeaning>> = collections(sourceSealedSourceCollections)
    @get:JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = CatalogOptional.Missing::class)
    val targetSealedSourceCollections: CatalogOptional<List<SealedSourceCollectionMeaning>> = collections(targetSealedSourceCollections)
    data class KeyPair(@param:JsonProperty(required = true) val source: PublicExpression, @param:JsonProperty(required = true) val target: PublicExpression) {
        init { local(source); local(target) }
    }
    enum class ClockScope { JOIN_SOURCE, TOPOLOGY_BACKBONE, QUERY }
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = LogicClock::class, name = "LOGIC_UNIT"), JsonSubTypes.Type(value = QueryClock::class, name = "QUERY_CLOCK"))
    sealed interface Clock
    data class LogicClock(@param:JsonProperty(required = true) val logicUnitId: String, @param:JsonProperty(required = true) val logicUnitVersion: Long) : Clock {
        init { CatalogJson.text(logicUnitId); CatalogJson.version(logicUnitVersion) }
    }
    data class QueryClock(@param:JsonProperty(required = true) val clockRef: String, @param:JsonProperty(required = true) val valueType: ScalarValueType,
        @param:JsonProperty(required = true) val description: String) : Clock {
        init {
            require(clockRef.matches(Regex("clock_[0-9a-f]{64}"))) { "exact public query clock required" }
            require(valueType in setOf(ScalarValueType.DATE, ScalarValueType.LOCAL_TIMESTAMP, ScalarValueType.OFFSET_TIMESTAMP)) { "query clock requires a temporal type" }
            CatalogJson.text(description)
        }
    }
    class Interval(@param:JsonProperty(required = true) val clock: Clock, @param:JsonProperty(required = true) val clockScope: ClockScope,
        @param:JsonProperty(required = true) clockAccess: List<SemanticJoin>, @param:JsonProperty(required = true) val from: PublicExpression,
        @param:JsonProperty(required = true) val to: PublicExpression, @param:JsonProperty(required = true) val fromInclusive: Boolean,
        @param:JsonProperty(required = true) val toInclusive: Boolean, @param:JsonProperty(required = true) val openEndedTo: Boolean) {
        val clockAccess: List<SemanticJoin> = java.util.List.copyOf(clockAccess)
        init {
            local(from); local(to)
            require((clock is QueryClock) == (clockScope == ClockScope.QUERY)) { "public clock differs from its position scope" }
        }
        override fun equals(other: Any?): Boolean = other is Interval && clock == other.clock && clockScope == other.clockScope && clockAccess == other.clockAccess && from == other.from && to == other.to && fromInclusive == other.fromInclusive && toInclusive == other.toInclusive && openEndedTo == other.openEndedTo
        override fun hashCode(): Int = listOf(clock, clockScope, clockAccess, from, to, fromInclusive, toInclusive, openEndedTo).hashCode()
    }
    fun references(references: MutableSet<String>) {
        keys.forEach { it.source.references(emptySet(), references); it.target.references(emptySet(), references) }
        intervals.forEach { interval ->
            interval.clockAccess.forEach { it.references(references) }
            (interval.clock as? LogicClock)?.let { references.add("${it.logicUnitId}@${it.logicUnitVersion}") }
            interval.from.references(emptySet(), references); interval.to.references(emptySet(), references)
        }
    }
    override fun equals(other: Any?): Boolean = other is SemanticJoin && joinType == other.joinType && cardinality == other.cardinality && nullPolicy == other.nullPolicy && keys == other.keys && intervals == other.intervals && sourceSealedSourceCollections == other.sourceSealedSourceCollections && targetSealedSourceCollections == other.targetSealedSourceCollections
    override fun hashCode(): Int = listOf(joinType, cardinality, nullPolicy, keys, intervals, sourceSealedSourceCollections, targetSealedSourceCollections).hashCode()
    companion object {
        fun local(expression: PublicExpression): PublicExpression.Reference {
            require(expression is PublicExpression.Reference && expression.role == null) { "semantic join requires an exact local LogicUnit reference" }
            return expression
        }
        fun collections(value: CatalogOptional<List<SealedSourceCollectionMeaning>>): CatalogOptional<List<SealedSourceCollectionMeaning>> {
            if (!value.present) return CatalogOptional.absent()
            val values = java.util.List.copyOf(requireNotNull(value.value) { "explicit source collections cannot be NULL" })
            require(values.map { it.id }.distinct().size == values.size) { "duplicate finite source collection" }
            return CatalogOptional.of(values)
        }
    }
}
