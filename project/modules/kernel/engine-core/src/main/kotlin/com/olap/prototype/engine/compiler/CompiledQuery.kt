package com.olap.prototype.engine.compiler

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonIgnore
import com.olap.prototype.calculation.*
import com.olap.prototype.engine.catalog.ExecutableRelationPosition.Id
import com.olap.prototype.engine.executable.ExecutableLoweringResult
import com.olap.prototype.engine.executable.CoreFinalizationPlan
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.query.DimensionCondition
import com.olap.prototype.query.QueryStageAnalysis
import com.olap.prototype.query.QueryExplanationView
import com.olap.prototype.query.QueryExecutionMode
import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.query.TemporalClockBindings
import com.olap.prototype.time.TemporalWindow

/** 编译已观察到的证据；失败保留实际完成阶段，不伪造后续计划。 */
data class CompiledQuery(val requestId: String, val catalogId: String,
    val stageAnalyses: List<QueryStageAnalysis.Analysis>, val resolvedSources: List<SourceEvidence> = emptyList(),
    val metricInvocations: List<InvocationEvidence> = emptyList(), val executableLoweringResult: ExecutableLoweringResult? = null,
    val rawRelationalPlan: String? = null, val optimizedRelationalPlan: String? = null,
    val rawRelationalPlanJson: String? = null, val optimizedRelationalPlanJson: String? = null,
    val scopes: List<CompiledScopeExplanation> = emptyList(),
    val sqlRelationalPlan: String? = null, val sqlRelationalPlanJson: String? = null,
    val compilationScale: CompilationScale? = null,
    val sqlPlan: List<QueryExplanationView.PlanNode> = emptyList(),
    val executionMode: QueryExecutionMode = QueryExecutionMode.SOURCE,
    @get:JsonIgnore val coreFinalizationPlan: CoreFinalizationPlan? = null) {
    val formatVersion: String get() = "olap-compiled-query/v11"
    data class InvocationEvidence(val scope: QueryStageAnalysis.ScopeId, val callId: String, val owner: CalculationOwner,
        val metric: MetricRef, val outputAlias: String, val arguments: Map<String, Calculation.Value>, val bindings: CalculationBindings) {
        init { ScopedInputContract.requireId(callId) }
    }
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = FactIdentity::class, name = "FACT_INPUT"), JsonSubTypes.Type(value = MemberIdentity::class, name = "MEMBER_INPUT"))
    sealed interface SourceIdentity
    data class FactIdentity(val callId: String, val owner: CalculationOwner, val metric: MetricRef,
        val grouping: Map<String, ScopedInputContract.DimensionRef>, val filter: DimensionCondition) : SourceIdentity {
        init { ScopedInputContract.requireId(callId) }
    }
    data class MemberIdentity(val sourceId: String, val sourceVersion: String, val outputs: Map<String, String>,
        val meanings: Map<String, QueryStageAnalysis.MemberMeaning>, val condition: MemberCondition) : SourceIdentity
    data class PositionEvidence(val position: Id, val bindingId: String, val bindingVersion: String)
    data class JoinEvidence(val joinPathId: String, val joinPathVersion: String, val source: Id, val target: Id,
        val sourceTimes: Map<Int, Id>, val clockSlots: Map<Int, String>, val requiredMatch: Boolean)
    data class QuantifiedEvidence(val occurrence: List<String>, val parent: Id, val positions: List<PositionEvidence>,
        val joins: List<JoinEvidence>, val clocks: Map<String, TemporalClockBindings.Value>, val quantifiers: List<QuantifiedEvidence>)
    data class RelationEvidence(val instanceId: String, val filterRefId: String, val source: SourceEvidence)
    data class SourceEvidence(val scope: QueryStageAnalysis.ScopeId, val identity: SourceIdentity, val eventId: String,
        val eventVersion: String, val eventIdentity: EventIdentity, val positions: List<PositionEvidence>, val joins: List<JoinEvidence>,
        val range: TemporalWindow?, val clocks: Map<String, TemporalClockBindings.Value>, val quantifiers: List<QuantifiedEvidence>, val relations: List<RelationEvidence>)
}
