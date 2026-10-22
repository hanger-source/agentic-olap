package com.olap.prototype.governance.contract

import com.olap.prototype.calculation.MetricCalculation
import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.metric.MetricBusinessMeaning
import com.olap.prototype.metric.MetricMergePolicy
import com.olap.prototype.metric.MetricSnapshotRule
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.SetOutputContract
import java.time.Instant
import java.util.Collections

class MetricCreateRequest(val sourceEventId: String, val sourceEventVersion: Long, val code: String, val displayName: String, val owner: String, val inputExpressionJson: String?, val aggregation: String, val distinct: Boolean, val metricKind: String, val mergePolicy: String, val privatePredicateJson: String?, roles: Map<String, RelationAccessPath>, val description: String?, val businessMeaning: MetricBusinessMeaning, val snapshotRule: MetricSnapshotRule?, val valueContract: MetricValueContract, val aggregationRounding: MetricValueContract.Rounding, val createdBy: String) {
    val roles: Map<String, RelationAccessPath> = Collections.unmodifiableMap(LinkedHashMap(roles))
    override fun equals(other: Any?): Boolean = other is MetricCreateRequest && sourceEventId == other.sourceEventId && sourceEventVersion == other.sourceEventVersion && code == other.code && displayName == other.displayName && owner == other.owner && inputExpressionJson == other.inputExpressionJson && aggregation == other.aggregation && distinct == other.distinct && metricKind == other.metricKind && mergePolicy == other.mergePolicy && privatePredicateJson == other.privatePredicateJson && roles == other.roles && description == other.description && businessMeaning == other.businessMeaning && snapshotRule == other.snapshotRule && valueContract == other.valueContract && aggregationRounding == other.aggregationRounding && createdBy == other.createdBy
    override fun hashCode(): Int = listOf(sourceEventId, sourceEventVersion, code, displayName, owner, inputExpressionJson, aggregation, distinct, metricKind, mergePolicy, privatePredicateJson, roles, description, businessMeaning, snapshotRule, valueContract, aggregationRounding, createdBy).hashCode()
    companion object {
        @JvmField val METRIC_KINDS: Set<String> = java.util.Set.of("BASE")
        @JvmField val DISTINCT_AGGREGATION: String = MetricAggregation.COUNT.name
        @JvmField val DISTINCT_MERGE_POLICY: String = MetricMergePolicy.DISTINCT_NON_ADDITIVE.name
    }
}
data class MetricCreateResult(val metric: MetricRecord, val metricVersion: MetricVersionRecord)
data class MetricCalculationCreateRequest(val code: String, val displayName: String, val owner: String, val definition: MetricCalculation, val description: String?, val businessMeaning: MetricBusinessMeaning, val createdBy: String)
class MetricCalculationCreateResult(val metric: MetricRecord, val metricVersion: MetricVersionRecord, dependencies: List<MetricDependencyRecord>) {
    val dependencies: List<MetricDependencyRecord> = java.util.List.copyOf(dependencies)
    override fun equals(other: Any?): Boolean = other is MetricCalculationCreateResult && metric == other.metric && metricVersion == other.metricVersion && dependencies == other.dependencies
    override fun hashCode(): Int = listOf(metric, metricVersion, dependencies).hashCode()
}
data class MetricRecord(val id: String, val code: String, val displayName: String, val definition: MetricDefinition, val description: String?, val owner: String, val status: String, val currentVersion: Long, val createdBy: String, val createdAt: Instant, val updatedBy: String?, val updatedAt: Instant?) {
    fun factMeasure(): MetricDefinition.FactMeasure = definition as? MetricDefinition.FactMeasure ?: throw IllegalArgumentException("metric is a calculation, not a fact measure: $id")
}
data class MetricVersionRecord(val id: String, val metricId: String, val version: Long, val versionStatus: String, val contentJson: String, val diagnosticJson: String?, val sourceVersion: Long?, val createdBy: String, val createdAt: Instant, val activatedBy: String?, val activatedAt: Instant?, val deprecatedBy: String?, val deprecatedAt: Instant?, val archivedBy: String?, val archivedAt: Instant?)
data class MetricDependencyRecord(val id: String, val metricId: String, val metricVersion: Long, val dependencyScope: String, val dependencyType: String, val dependencyId: String, val dependencyVersion: Long?, val referencedSymbol: String?, val bindingStatus: String, val diagnosticCode: String?, val createdAt: Instant)
data class MetricMeasureLogicUnitCandidateRecord(val logicUnitId: String, val logicUnitVersion: Long, val logicalTableId: String, val logicalTableDisplayName: String, val code: String, val displayName: String, val sourceKind: String, val logicType: String?, val valueShape: SetOutputContract.Shape?, val logicTypeDisplayName: String?, val aggregatable: Boolean, val filterable: Boolean, val isTime: Boolean, val isPrimaryKey: Boolean, val isForeignKey: Boolean)
class MetricBindingContextRecord(val event: EventRecord, measureLogicUnits: List<MetricMeasureLogicUnitCandidateRecord>) {
    val measureLogicUnits: List<MetricMeasureLogicUnitCandidateRecord> = java.util.List.copyOf(measureLogicUnits)
    override fun equals(other: Any?): Boolean = other is MetricBindingContextRecord && event == other.event && measureLogicUnits == other.measureLogicUnits
    override fun hashCode(): Int = listOf(event, measureLogicUnits).hashCode()
}
