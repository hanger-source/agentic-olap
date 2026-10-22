package com.olap.prototype.query.contract.catalog

import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.SourceJoinContract
import com.olap.prototype.query.contract.SealedSourceCollectionMeaning

/** 关联的语义键、区间和独立物理位置派生的有限源说明。 */
class SemanticJoin(val joinType: SourceJoinContract.JoinType,
    val cardinality: SourceJoinContract.Cardinality,
    val nullPolicy: SourceJoinContract.NullPolicy,
    keys: List<KeyPair>, intervals: List<Interval>,
    sourceSealedSourceCollections: CatalogOptional<List<SealedSourceCollectionMeaning>> = CatalogOptional.absent(),
    targetSealedSourceCollections: CatalogOptional<List<SealedSourceCollectionMeaning>> = CatalogOptional.absent()) {
    val keys: List<KeyPair> = CatalogValues.nonempty(keys)
    val intervals: List<Interval> = java.util.List.copyOf(intervals)
    val sourceSealedSourceCollections: CatalogOptional<List<SealedSourceCollectionMeaning>> = collections(sourceSealedSourceCollections)
    val targetSealedSourceCollections: CatalogOptional<List<SealedSourceCollectionMeaning>> = collections(targetSealedSourceCollections)
    data class KeyPair(val source: PublicExpression, val target: PublicExpression) {
        init { local(source); local(target) }
    }
    enum class ClockScope { JOIN_SOURCE, TOPOLOGY_BACKBONE, QUERY }

    sealed interface Clock
    data class LogicClock(val logicUnitId: String, val logicUnitVersion: Long) : Clock {
        init { CatalogValues.text(logicUnitId); CatalogValues.version(logicUnitVersion) }
    }
    data class QueryClock(val clockRef: String, val valueType: ScalarValueType,
        val description: String) : Clock {
        init {
            require(clockRef.matches(Regex("clock_[0-9a-f]{64}"))) { "exact public query clock required" }
            require(valueType in setOf(ScalarValueType.DATE, ScalarValueType.LOCAL_TIMESTAMP, ScalarValueType.OFFSET_TIMESTAMP)) { "query clock requires a temporal type" }
            CatalogValues.text(description)
        }
    }
    class Interval(val clock: Clock, val clockScope: ClockScope,
        clockAccess: List<SemanticJoin>, val from: PublicExpression,
        val to: PublicExpression, val fromInclusive: Boolean,
        val toInclusive: Boolean, val openEndedTo: Boolean) {
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
