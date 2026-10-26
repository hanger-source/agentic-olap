package com.olap.prototype.query

import com.olap.prototype.calculation.*
import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.contract.JdbcSourceRequirement
import com.olap.prototype.event.EventIdentity
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.time.TemporalWindow
import com.olap.prototype.warehouse.contract.SourceBindingRef

/** 引擎实际观察的公开事实；执行要求披露本次计算实际采用的精确来源声明，不携带SQL或执行统计猜测。 */
data class QueryExplanationView(val requestId: String, val summary: Summary, val scopes: List<Scope>, val sources: List<Source>,
    val plan: List<PlanNode>, val compilationScale: CompilationScale?, val executionRequirements: ExecutionRequirements?) {
    data class ExecutionRequirements(val capabilities: List<String>, val relationChecks: List<RelationCheck>,
        val constraintKinds: List<String>, val sourceCapabilitiesChecked: Boolean, val sourceConstraints: List<SourceConstraints>)
    data class SourceConstraints(val binding: SourceBindingRef, val connectorRef: ConnectorRef, val dialect: String,
        val physicalTableId: String, val physicalTableVersion: String, val catalog: String?, val schema: String?,
        val table: String, val constraints: List<SourceConstraint>)
    /** columns 将实际采用的原生列名映射至冻结的精确物理列版本；采用不代表来源已兑现保证。 */
    data class SourceConstraint(val kind: JdbcSourceRequirement.ConstraintKind, val name: String?,
        val columns: Map<String, String>, val adoptedFor: String)
    data class RelationCheck(val code: String, val scope: QueryStageAnalysis.ScopeId?, val keys: List<String>, val reason: String?)
    data class Summary(val scopeCount: Int, val sourceInputCount: Int, val executionBoundary: String,
        val populationContract: PopulationContract, val actualSourceStatistics: String, val rowNullAttribution: String,
        val sourceOutputScopes: List<SourceOutput>) {
        companion object {
            fun contract(): Map<String, Any> = mapOf(
                "sourceOutputScopes" to SourceOutput.contract(),
                "actualSourceStatistics" to "NOT_OBSERVED_UNLESS_ACTUAL_SOURCE_STATISTICS_HAVE_BEEN_COLLECTED",
                "execution" to "EXPLANATION_DESCRIBES_THE_COMPILED_PLAN_WITHOUT_EXECUTING_SOURCE_SQL_OR_VERIFYING_SOURCE_GUARANTEES",
            )
        }
    }
    enum class SourceRepresentation {
        OBSERVATION_BAG, EXACT_AGGREGATE_WORK;
        companion object {
            fun contract(): Map<String, Any> = mapOf(
                "OBSERVATION_BAG" to "ACTUAL_SOURCE_CUT_ROWS_WITH_ORIGINAL_MULTIPLICITY_NOT_REPLACED_BY_EXACT_AGGREGATE_WORK_NOT_A_CLAIM_OF_RAW_DATABASE_DETAIL_SCANS",
                "EXACT_AGGREGATE_WORK" to "PROVEN_SOURCE_WORK_STATES_FOR_THE_SELECTED_ORIGINAL_REGROUP_CORE_STILL_COMPLETES_ITS_CONTRACT",
                "scope" to "SOURCE_OUTPUT_REPRESENTATION_DOES_NOT_ASSERT_A_PHYSICAL_SCAN_COUNT_OR_MATERIALIZATION",
            )
        }
    }
    /** 对本次真实来源布局的公开投影；尝试过的归约与已经选择的归约分别记录。 */
    data class SourceOutput(val scope: QueryStageAnalysis.ScopeId, val representation: SourceRepresentation,
        val reductionStage: Int?, val reductionReason: String?, val attemptedReductionStage: Int?) {
        init {
            require(reductionStage == null || reductionStage >= 0) { "selected reduction stage must be nonnegative" }
            require(attemptedReductionStage == null || attemptedReductionStage >= 0) { "attempted reduction stage must be nonnegative" }
            require(reductionReason == null || reductionReason.isNotBlank()) { "reduction reason must be an explicit compiler decision" }
            require(attemptedReductionStage == null || reductionReason != null) { "an unselected reduction candidate must retain its actual reason" }
            require((representation == SourceRepresentation.EXACT_AGGREGATE_WORK) == (reductionStage != null)) {
                "only exact aggregate work selects an original reduction stage"
            }
            require(representation != SourceRepresentation.EXACT_AGGREGATE_WORK || reductionReason == null && attemptedReductionStage == null) {
                "selected exact aggregate work cannot report a declined candidate"
            }
        }
        companion object {
            fun contract(): Map<String, Any> = mapOf(
                "scope" to "EXACT_MATCH_TO_SCOPES_SCOPE_NO_INFERENCE_FROM_SQL_OR_SUM_COUNTS",
                "representation" to SourceRepresentation.contract(),
                "reductionStage" to "SELECTED_EXACT_AGGREGATE_WORK_ONLY_ZERO_BASED_ORIGINAL_REGROUP_INDEX_IN_THE_SAME_SCOPE_STAGES_OTHERWISE_NULL",
                "reductionReason" to "ACTUAL_COMPILER_DECISION_FOR_RETAINING_THE_BAG_WHEN_AVAILABLE_NOT_A_SOURCE_FAILURE_OR_GUESSED_OPTIMIZER_CAUSE",
                "attemptedReductionStage" to "UNSELECTED_CANDIDATE_REGROUP_INDEX_WHEN_KNOWN_NOT_AN_EXECUTED_OR_SELECTED_REDUCTION",
                "execution" to "COMPILED_SELECTION_ONLY_NOT_OBSERVED_SOURCE_EXECUTION_OR_VERIFIED_SOURCE_CAPABILITIES",
            )
        }
    }
    data class PopulationMode(val mode: String, val fields: List<String>, val groups: String, val inputs: String?)
    data class PopulationContract(val formatVersion: String, val required: Boolean, val modes: List<PopulationMode>,
        val scope: String, val grain: String, val unmatchedMetrics: String, val calculationMetric: String, val emptyPopulation: String, val coverage: String)
    data class Qualification(val objectsScope: QueryStageAnalysis.ScopeId, val objectKeys: List<String>, val target: Calculation.ObjectTarget,
        val effect: String, val recordSlots: List<String>, val recordMeanings: Map<String, QueryStageAnalysis.MemberMeaning>)
    data class Child(val kind: String, val name: String, val scope: QueryStageAnalysis.ScopeId)
    data class Scope(val scope: QueryStageAnalysis.ScopeId, val inputMode: String?, val root: Root, val qualifications: List<Qualification>,
        val children: List<Child>, val finalGrain: QueryStageAnalysis.Key, val relationInputMatch: RelationMatch?,
        val outputs: List<QueryStageAnalysis.Symbol>, val selectedObservations: List<QueryStageAnalysis.SelectedObservation>, val stages: List<Stage>,
        val rootExecutionLocation: ExecutionLocation)
    enum class ExecutionLocation { SOURCE, CORE }
    data class Stage(val index: Int, val operation: String, val contract: PostOperationContract.ValidatedOperation,
        val inputs: List<QueryStageAnalysis.SymbolId>, val imports: List<QueryStageAnalysis.SymbolId>, val outputs: List<QueryStageAnalysis.Symbol>, val grain: QueryStageAnalysis.Key,
        val executionLocation: ExecutionLocation = ExecutionLocation.SOURCE)
    data class RelationRequirement(val outputSlots: Map<String, String>, val outputs: List<SetOutputContract>, val keys: List<String>?)
    data class RelationMatch(val status: String, val inputScope: QueryStageAnalysis.ScopeId, val actualOutputs: List<QueryStageAnalysis.Symbol>,
        val actualGrain: QueryStageAnalysis.Key, val requirements: List<RelationRequirement>, val projection: Map<String,String>, val population: String, val effect: String)
    sealed interface Root {
        data class Measures(val rowPopulation: RowPopulationContract.Selection, val calls: List<String>, val timeRange: Calculation.TimeRange?,
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
