package com.olap.prototype.metric

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.query.ScopedInputContract

/** 精确指标版本的时间归约，不是查询后对汇总余额再求和。 */
class MetricSnapshotRule(val reduction: Reduction, val entityRole: String, entityKeys: List<LogicUnitKey>, val timeKey: LogicUnitKey,
    tieBreakKeys: List<LogicUnitKey>, val nullPolicy: NullPolicy,
    val meanValueContract: MetricValueContract?, val meanTarget: MeanTarget? = null) {
    val entityKeys: List<LogicUnitKey> = java.util.List.copyOf(entityKeys)
    val tieBreakKeys: List<LogicUnitKey> = java.util.List.copyOf(tieBreakKeys)
    enum class Reduction { FIRST, LAST, MEAN }
    enum class NullPolicy { PRESERVE_SELECTED, IGNORE, PROPAGATE }
    data class LogicUnitKey(val id: String, val version: Long) {
        init { ProducerContracts.requireText(id, "snapshotRule.logicUnit.id"); ProducerContracts.requireVersion(version, "snapshotRule.logicUnit.version") }
        fun expression(): SemanticExpression.Reference = SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, id, version)
    }
    init {
        ScopedInputContract.requireId(entityRole)
        if (this.entityKeys.isEmpty()) throw ProducerContractException("snapshotRule requires nonempty entityKeys and explicit tieBreakKeys")
        if (keys().distinct().size != keys().size) throw ProducerContractException("snapshotRule keys must be distinct exact LogicUnits")
        if (if (reduction == Reduction.MEAN) nullPolicy == NullPolicy.PRESERVE_SELECTED else nullPolicy != NullPolicy.PRESERVE_SELECTED)
            throw ProducerContractException("FIRST/LAST preserve the selected NULL; MEAN requires IGNORE or PROPAGATE")
        if (if (reduction == Reduction.MEAN) meanValueContract == null || meanTarget == null else meanValueContract != null || meanTarget != null)
            throw ProducerContractException("MEAN requires its intermediate quantity and explicit meanTarget; FIRST/LAST do not declare either")
    }
    fun keys(): List<LogicUnitKey> = entityKeys + timeKey + tieBreakKeys
    fun expressions(): List<SemanticExpression> = keys().map { it.expression() }
    fun meaning(): Meaning = Meaning(reduction, nullPolicy, entityRole, "SELECTED_DIMENSIONS_AND_ENTITY", "EVENT_TIME_AND_QUERY_FILTERS_BEFORE_REDUCTION",
        "PRIVATE_PREDICATE_AFTER_SELECTION_OR_BEFORE_MEAN", "UNKNOWN_TIME_EXCLUDED", "MEAN_OF_OBSERVED_ROWS_NOT_IMPUTED_CALENDAR_DAYS",
        "STANDARD_SQL_AGGREGATION_OF_REDUCED_ENTITY_VALUES", "DUPLICATE_SNAPSHOT_GRAIN_FAILS", meanValueContract, meanTarget)
    data class Meaning(val reduction: Reduction, val nullPolicy: NullPolicy, val entityRole: String, val partitionScope: String,
        val sourceFilterStage: String, val privatePredicateStage: String, val unknownTimePolicy: String, val meanPopulation: String,
        val crossEntityNullPolicy: String, val duplicatePolicy: String, val meanValueContract: MetricValueContract?, val meanTarget: MeanTarget?)
    override fun equals(other: Any?): Boolean = other is MetricSnapshotRule && reduction == other.reduction && entityRole == other.entityRole && entityKeys == other.entityKeys && timeKey == other.timeKey && tieBreakKeys == other.tieBreakKeys && nullPolicy == other.nullPolicy && meanValueContract == other.meanValueContract && meanTarget == other.meanTarget
    override fun hashCode(): Int = listOf(reduction, entityRole, entityKeys, timeKey, tieBreakKeys, nullPolicy, meanValueContract, meanTarget).hashCode()
}
