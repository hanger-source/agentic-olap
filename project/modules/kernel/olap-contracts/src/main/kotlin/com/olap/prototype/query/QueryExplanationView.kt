package com.olap.prototype.query

import com.olap.prototype.calculation.*
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.time.TemporalWindow

/** 引擎实际观察的公开事实，不携带SQL、物理字段、Calcite节点或执行统计猜测。 */
data class QueryExplanationView(val requestId: String, val summary: Summary, val scopes: List<Scope>, val sources: List<Source>,
    val plan: List<PlanNode>, val compilationScale: CompilationScale?, val executionRequirements: ExecutionRequirements?) {
    data class ExecutionRequirements(val capabilities: List<String>, val relationChecks: List<RelationCheck>,
        val constraintKinds: List<String>, val sourceCapabilitiesChecked: Boolean)
    data class RelationCheck(val code: String, val scope: QueryStageAnalysis.ScopeId?, val keys: List<String>, val reason: String?)
    data class Summary(val scopeCount: Int, val sourceInputCount: Int, val executionBoundary: String,
        val populationContract: PopulationContract, val actualSourceStatistics: String, val rowNullAttribution: String)
    data class PopulationMode(val mode: String, val fields: List<String>, val groups: String, val inputs: String?)
    data class PopulationContract(val formatVersion: String, val required: Boolean, val modes: List<PopulationMode>,
        val scope: String, val grain: String, val unmatchedMetrics: String, val calculationMetric: String, val emptyPopulation: String, val coverage: String)
    data class Qualification(val objectsScope: QueryStageAnalysis.ScopeId, val objectKeys: List<String>, val target: Calculation.ObjectTarget,
        val effect: String, val recordSlots: List<String>, val recordMeanings: Map<String, QueryStageAnalysis.MemberMeaning>)
    data class Child(val kind: String, val name: String, val scope: QueryStageAnalysis.ScopeId)
    data class Scope(val scope: QueryStageAnalysis.ScopeId, val inputMode: String?, val root: Root, val qualifications: List<Qualification>,
        val children: List<Child>, val finalGrain: QueryStageAnalysis.Key, val relationInputMatch: RelationMatch?,
        val outputs: List<QueryStageAnalysis.Symbol>, val selectedObservations: List<QueryStageAnalysis.SelectedObservation>, val stages: List<Stage>)
    data class Stage(val index: Int, val operation: String, val contract: PostOperationContract.ValidatedOperation,
        val inputs: List<QueryStageAnalysis.SymbolId>, val imports: List<QueryStageAnalysis.SymbolId>, val outputs: List<QueryStageAnalysis.Symbol>, val grain: QueryStageAnalysis.Key)
    data class RelationRequirement(val outputSlots: Map<String, String>, val outputs: List<SetOutputContract>, val keys: List<String>?)
    data class RelationMatch(val status: String, val inputScope: QueryStageAnalysis.ScopeId, val actualOutputs: List<QueryStageAnalysis.Symbol>,
        val actualGrain: QueryStageAnalysis.Key, val requirements: List<RelationRequirement>, val projection: Map<String,String>, val population: String, val effect: String)
    sealed interface Root {
        data class Measures(val rowPopulation: RowPopulationContract.Selection, val calls: List<String>, val timeRange: QueryComputation.TimeRange?,
            val clocks: Map<String,TemporalClockBindings.Value>, val outputCondition: DimensionCondition) : Root
        data class Members(val origin: MemberOrigin, val outputs: Map<String,String>, val condition: MemberCondition, val range: TemporalWindow?) : Root
        data class Input(val inputId: String) : Root
        data class Relation(val inputScope: QueryStageAnalysis.ScopeId, val outputs: Map<String,String>, val keys: List<String>?, val requirements: List<RelationRequirement>) : Root
        data class ObjectMeasure(val roles: List<Calculation.ObjectRole>) : Root
        data class AlignMeasurements(val keys: Map<String,Map<String,String>>, val observationKeys: List<String>, val population: Calculation.AlignmentPopulation) : Root
        data class ObservationStatistics(val unit: Calculation.ObservationUnit, val outerKeys: List<String>) : Root
        data class SetInputs(val operation: Calculation.SetKind, val all: Boolean, val inputs: List<Calculation.SetProjection>) : Root
    }
    sealed interface MemberOrigin {
        data class Projection(val id: String, val version: Long) : MemberOrigin
        data class Computed(val id: String, val version: Long, val computationScope: QueryStageAnalysis.ScopeId) : MemberOrigin
        data class Local(val inputScope: QueryStageAnalysis.ScopeId) : MemberOrigin
    }
    sealed interface SourceIdentity {
        data class Fact(val callId: String, val owner: CalculationOwner, val metric: MetricRef, val grouping: Map<String,ScopedInputContract.DimensionRef>, val filter: DimensionCondition) : SourceIdentity
        data class Member(val sourceId: String, val sourceVersion: String, val outputs: Map<String,String>, val meanings: Map<String,QueryStageAnalysis.MemberMeaning>, val condition: MemberCondition) : SourceIdentity
    }
    data class RelationPosition(val scope: List<String>, val role: String, val path: List<RelationAccessPath.JoinRef>)
    data class Position(val position: RelationPosition, val bindingId: String, val bindingVersion: String)
    data class Join(val joinPathId: String, val joinPathVersion: String, val sourcePosition: RelationPosition, val targetPosition: RelationPosition,
        val sourceTimes: Map<Int,RelationPosition>, val clockSlots: Map<Int,String>, val requiredMatch: Boolean)
    data class Quantified(val occurrence: List<String>, val parentPosition: RelationPosition, val positions: List<Position>, val joins: List<Join>,
        val clocks: Map<String,TemporalClockBindings.Value>, val quantifiers: List<Quantified>)
    data class RelationSource(val instanceId: String, val filterRefId: String, val source: Source)
    data class Source(val scope: QueryStageAnalysis.ScopeId, val identity: SourceIdentity, val eventId: String, val eventVersion: String,
        val recordIdentity: EventIdentity, val range: TemporalWindow?, val clocks: Map<String,TemporalClockBindings.Value>,
        val positions: List<Position>, val joins: List<Join>, val quantifiers: List<Quantified>, val relations: List<RelationSource>)
    data class PlanNode(val id: String, val operator: String, val inputs: List<String>, val scan: String?, val joinType: String?,
        val groupColumns: List<Int>?, val aggregations: List<String>?)
    data class RelationSize(val nodes: Int, val edges: Int, val scalarCalls: Int, val numericChecks: Int, val repeatedNumericChecks: Int)
    data class CompilationScale(val raw: RelationSize, val optimized: RelationSize, val sqlRelation: RelationSize,
        val sqlBytes: Int, val sqlCaseExpressions: Int, val sqlNumericChecks: Int, val numericBindings: Int, val reusedNumericChecks: Int)
}

/** 分页只裁切读取投影，不改变计算含义。 */
sealed interface QueryExplanationContent {
    data class Summary(val value: QueryExplanationView.Summary) : QueryExplanationContent
    data class Scopes(val items: List<QueryExplanationView.Scope>, val totalItems: Int, val nextOffset: Int?) : QueryExplanationContent
    data class Sources(val items: List<QueryExplanationView.Source>, val totalItems: Int, val nextOffset: Int?) : QueryExplanationContent
    data class Plan(val items: List<QueryExplanationView.PlanNode>, val totalItems: Int, val nextOffset: Int?) : QueryExplanationContent
    data class CompilationScale(val value: QueryExplanationView.CompilationScale?) : QueryExplanationContent
    data class ExecutionRequirements(val value: QueryExplanationView.ExecutionRequirements?) : QueryExplanationContent
}
