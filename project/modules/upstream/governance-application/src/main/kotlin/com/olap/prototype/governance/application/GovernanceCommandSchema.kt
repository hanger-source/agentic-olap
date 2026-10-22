package com.olap.prototype.governance.application

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition
import com.olap.prototype.calculation.Calculation
import com.olap.prototype.governance.contract.*
import com.olap.prototype.management.governance.*
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.member.MemberResult
import com.olap.prototype.member.MemberSourceDefinition
import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.metric.MetricMergePolicy
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.SemanticExpressionCodec
import com.olap.prototype.query.*
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.util.WireTypeShape
import com.olap.prototype.warehouse.onboarding.SourceStructureRegistrationRequest
import com.olap.prototype.warehouse.onboarding.WarehouseDiscoveryCommand

/** 命令字段的公开说明来自实际绑定类型；领域合法性和主体准入由所属能力负责。 */
class GovernanceCommandSchema private constructor() : WireTypeShape(mapper) {
    companion object {
        private val mapper = OlapJsonUtils.mapper()
        private const val FORMAT = "olap-governance-command-shape/v1"

        @JvmStatic
        fun describeInput(type: Class<*>): Any = describe(mapper.constructType(type))

        @JvmStatic
        fun describe(type: JavaType): Any = GovernanceCommandSchema().describe(type, FORMAT)

        @JvmStatic
        fun describe(type: JavaType, path: List<String>?, variant: String?): Any =
            GovernanceCommandSchema().describe(type, FORMAT, path, variant)
    }

    override fun typeRules(type: JavaType): Map<String, Any> = when {
        type.rawClass == Calculation.Call::class.java -> Calculation.Call.contract()
        type.rawClass == MemberSourceDefinition.Projection::class.java -> mapOf(
            "outputs" to listOf("DIMENSION", "PROPERTY", "CONTAINER_PROPERTY"),
            "references" to "EXACT_ADOPTED_EVENT_AND_ITS_DIMENSION_BINDINGS_OR_LOGIC_UNIT_POSITIONS")
        type.rawClass == MemberResult::class.java -> mapOf(
            "identity" to "RAW_BAG_REQUIRES_EMPTY_IDENTITY_OTHER_SHAPES_REQUIRE_DISTINCT_DECLARED_SCALAR_IDENTITY_SLOTS",
            "filters" to "ONLY_DECLARED_SCALAR_OUTPUT_SLOTS_WITH_EXPLICIT_DISTINCT_ALLOWED_OPERATORS",
            "timeSlot" to "EXPLICIT_NULL_OR_DECLARED_TEMPORAL_SCALAR_OUTPUT_SLOT",
            "clockSlots" to "EXPLICIT_OBJECT_OF_DISTINCT_EXACT_CLOCK_POSITIONS_EMPTY_WHEN_NONE")
        MemberCondition::class.java.isAssignableFrom(type.rawClass) -> MemberCondition.contract()
        else -> super.typeRules(type)
    }

    override fun fieldShape(owner: Class<*>, field: BeanPropertyDefinition, type: JavaType): Map<String, Any> {
        val name = field.name
        return when {
            owner in setOf(WarehouseDiscoveryCommand::class.java, SourceStructureRegistrationRequest::class.java) && name == "sourceIdentityHash" -> mapOf(
                "kind" to "string", "requiredByContract" to true, "pattern" to "[0-9a-f]{64}",
                "source" to "exact immutable registered source binding authorized by this deployment; no current or latest selection")
            owner in setOf(ScenarioPipelineRuleCreateRequest::class.java, ScenarioPipelineRuleContractRefreshRequest::class.java) && name == "ruleType" -> mapOf(
                "kind" to "enum", "values" to PostOperationContract.operationTypes(),
                "argumentContracts" to PostOperationSchema.contract())
            owner == WarehouseDiscoveryCommand::class.java && name == "dataSourceId" -> mapOf(
                "kind" to "string", "requiredByContract" to true,
                "source" to "an explicitly authorized and registered data source; connection and credentials are deployment-owned")
            owner == WarehouseDiscoveryCommand::class.java && name == "schemaName" -> mapOf(
                "kind" to "string", "requiredByContract" to true,
                "scope" to "exact schema from metadata discovery; tablePattern and limit select this survey, not a business population")
            owner == WarehouseDiscoveryCommand::class.java && name in setOf("temporalKeys", "snapshotUniqueKeys") -> mapOf(
                "kind" to "explicit-constraint-survey", "requiredByContract" to true,
                "shape" to shape(field.primaryType), "ordinaryDiscovery" to "{}: no additional constraint claim or immutable database anchor required",
                "whenRequested" to "exact table names and bounded rules with a real semantic need; full-source verification, no sample inference")
            owner == PhysicalTableCreateRequest::class.java && name == "sourceConstraints" -> mapOf(
                "kind" to "source-evidence-reference", "required" to true, "shape" to shape(field.primaryType),
                "source" to "discoveredTableId from read-source-discovery-tables; not a physical asset ID",
                "discovery" to "discover-source persists a survey of an authorized source; list-source-discoveries and read-source-discovery select exact saved observations",
                "columns" to "read-source-discovery-columns by exact discoveredTableId; every observed column and native type required",
                "finiteCollections" to "sourceCollectionIds must be explicit: [] for no finite source declaration, or selected exact list-source-collections/read-source-collection IDs for this same deployment and base table; declarations are frozen by this physical version, never adopted automatically or by member sources",
                "identity" to "same successful discovery source binding, exact table, schemaHash and column types; revise-physical-table adopts this exact new evidence into the same physical identity and new version; no evidence inheritance across bindings or latest lookup")
            owner == PhysicalTableCreateRequest::class.java && name == "schemaHash" -> mapOf(
                "kind" to "string", "required" to true, "source" to "schemaHash of the same exact discoveredTableId")
            owner == EventCreateRequest::class.java && name in setOf("eventTimeLogicUnitId", "eventTimeLogicUnitVersion") -> mapOf(
                "kind" to "nullable", "required" to true, "shape" to shape(field.primaryType),
                "pair" to "eventTimeLogicUnitId + eventTimeLogicUnitVersion: exact reference or both explicit null",
                "nullMeaning" to "SOURCE_RELATION_HAS_NO_PRIMARY_EVENT_TIME_ROLE_NO_SYNTHETIC_CLOCK",
                "queryScope" to "timeRange=null means no query time interpretation; temporal windows require a published anchor")
            owner == MetricCreateRequest::class.java && name == "metricKind" -> enum(MetricCreateRequest.METRIC_KINDS.sorted())
            owner == MetricCreateRequest::class.java && name == "distinct" -> mapOf(
                "kind" to "boolean", "required" to true,
                "whenTrue" to mapOf("aggregation" to MetricCreateRequest.DISTINCT_AGGREGATION, "mergePolicy" to MetricCreateRequest.DISTINCT_MERGE_POLICY),
                "nullInputPolicy" to "EXCLUDED", "crossGroupCountAddition" to "FORBIDDEN")
            owner == MetricCreateRequest::class.java && name == "aggregation" -> shape(MAPPER.constructType(MetricAggregation::class.java))
            owner == MetricCreateRequest::class.java && name == "snapshotRule" -> mapOf(
                "kind" to "conditional", "requiredWhen" to "mergePolicy=SEMI_ADDITIVE", "forbiddenOtherwise" to true,
                "shape" to shape(field.primaryType),
                "grain" to "entityRole selects exact event entityKeys; entityKeys + timeKey + tieBreakKeys must cover event recordKey; duplicate rows fail",
                "timeScope" to "query filters and non-NULL event time before reduction; selected dimensions partition entities",
                "firstLast" to "lexicographic time/tie order; NULL tie keys last; preserve selected NULL, never last-non-NULL",
                "mean" to "per entity observed-row mean, then metric aggregation; not an imputed calendar mean")
            owner == LogicalTableCreateRequest.LogicUnitInput::class.java && name == "expressionJson" ->
                SemanticExpressionCodec.wireContract(SemanticExpression.ReferenceKind.PHYSICAL_COLUMN, true)
            owner == MetricCreateRequest::class.java && name in setOf("privatePredicateJson", "inputExpressionJson") ||
                owner == DimensionEventBindingCreateRequest::class.java && name == "expressionJson" ->
                SemanticExpressionCodec.wireContract(SemanticExpression.ReferenceKind.LOGIC_UNIT, true)
            owner == ScenarioFilterRefCreateRequest::class.java && name == "filterTargetType" -> enum(ScenarioFilterRefCreateRequest.TARGET_TYPES.sorted())
            owner == ScenarioFilterRefCreateRequest::class.java && name == "valueProviderId" -> mapOf(
                "kind" to "string", "nullable" to true,
                "nullMeaning" to "NO_CANDIDATE_ENUMERATION_PREDICATES_REMAIN_AVAILABLE",
                "whenPresent" to "EXACT_PROVIDER_FOR_TARGET_DIMENSION_VERSION_AND_DEFAULT_LEVEL")
            owner == AnalysisDimensionEventMappingCreateRequest::class.java && name == "alignmentStatus" -> enum(ValueAlignmentContract.allowedStatuses().sorted())
            owner == AnalysisDimensionValueAlignmentCreateRequest::class.java && name == "alignmentStatus" -> enum(ValueAlignmentContract.crossEventStatuses().sorted())
            owner in setOf(ScenarioMetricRefCreateRequest::class.java, ScenarioAnalysisDimensionRefCreateRequest::class.java,
                ScenarioFilterRefCreateRequest::class.java, ScenarioPipelineRuleCreateRequest::class.java) && name == "sortOrder" ->
                mapOf("kind" to "integer", "minimum" to 1, "requiredByContract" to true)
            owner == JoinPathCreateRequest::class.java && name == "temporalConfigJson" -> TemporalJoinContract.wireContract()
            owner == JoinPathCreateRequest::class.java && name in setOf("joinType", "cardinality", "nullPolicy") -> {
                val key = when (name) { "joinType" -> "joinTypes"; "cardinality" -> "cardinalities"; else -> "nullPolicies" }
                val contract = SourceJoinContract.contract()
                mapOf("kind" to "enum", "values" to requireNotNull(contract[key]), "relationshipContract" to contract)
            }
            owner == MetricCreateRequest::class.java && name == "mergePolicy" ->
                enum(MetricMergePolicy.entries.filter { it.publishable() }.map { it.name })
            else -> shape(field.primaryType)
        }
    }

    private fun enum(values: Any): Map<String, Any> = mapOf("kind" to "enum", "values" to values)
}
