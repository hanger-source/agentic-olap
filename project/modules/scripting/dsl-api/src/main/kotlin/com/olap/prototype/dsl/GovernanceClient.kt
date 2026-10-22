package com.olap.prototype.dsl

import com.olap.prototype.governance.contract.*
import com.olap.prototype.warehouse.onboarding.*
import com.olap.prototype.dsl.transport.Capability
import com.olap.prototype.dsl.transport.CapabilityCalls

/** 直接使用正式请求和回执；所有操作继续经过服务授权和领域事务。 */
class GovernanceClient internal constructor(private val calls: CapabilityCalls) {
    @Capability("create-physical-table", "write-governance", "WRITE")
    fun createPhysicalTable(request: PhysicalTableCreateRequest): PhysicalTableCreateResult = calls.call(GovernanceClient::class.java, "createPhysicalTable", request)

    @Capability("create-business-line", "write-governance", "WRITE")
    fun createBusinessLine(request: BusinessLineCreateRequest): BusinessLineCreateResult = calls.call(GovernanceClient::class.java, "createBusinessLine", request)

    @Capability("create-logical-table", "write-governance", "WRITE")
    fun createLogicalTable(request: LogicalTableCreateRequest): LogicalTableCreateResult = calls.call(GovernanceClient::class.java, "createLogicalTable", request)

    @Capability("create-topology", "write-governance", "WRITE")
    fun createTopology(request: TopologyCreateRequest): TopologyCreateResult = calls.call(GovernanceClient::class.java, "createTopology", request)

    @Capability("create-topology-table-ref", "write-governance", "WRITE")
    fun createTopologyTableRef(request: TopologyLogicalTableRefCreateRequest): TopologyLogicalTableRefCreateResult = calls.call(GovernanceClient::class.java, "createTopologyTableRef", request)

    @Capability("create-join-path", "write-governance", "WRITE")
    fun createJoinPath(request: JoinPathCreateRequest): JoinPathCreateResult = calls.call(GovernanceClient::class.java, "createJoinPath", request)

    @Capability("create-event", "write-governance", "WRITE")
    fun createEvent(request: EventCreateRequest): EventCreateResult = calls.call(GovernanceClient::class.java, "createEvent", request)

    @Capability("create-metric", "write-governance", "WRITE")
    fun createMetric(request: MetricCreateRequest): MetricCreateResult = calls.call(GovernanceClient::class.java, "createMetric", request)

    @Capability("create-dimension", "write-governance", "WRITE")
    fun createDimension(request: DimensionCreateRequest): DimensionCreateResult = calls.call(GovernanceClient::class.java, "createDimension", request)

    @Capability("create-dimension-event-binding", "write-governance", "WRITE")
    fun createDimensionEventBinding(request: DimensionEventBindingCreateRequest): DimensionEventBindingCreateResult = calls.call(GovernanceClient::class.java, "createDimensionEventBinding", request)

    @Capability("create-subject-area", "write-governance", "WRITE")
    fun createSubjectArea(request: SubjectAreaCreateRequest): SubjectAreaCreateResult = calls.call(GovernanceClient::class.java, "createSubjectArea", request)

    @Capability("create-subject-area-metric-ref", "write-governance", "WRITE")
    fun createSubjectAreaMetricRef(request: SubjectAreaMetricRefCreateRequest): SubjectAreaMetricRefCreateResult = calls.call(GovernanceClient::class.java, "createSubjectAreaMetricRef", request)

    @Capability("create-analysis-dimension", "write-governance", "WRITE")
    fun createAnalysisDimension(request: AnalysisDimensionCreateRequest): AnalysisDimensionCreateResult = calls.call(GovernanceClient::class.java, "createAnalysisDimension", request)

    @Capability("create-member-source", "write-governance", "WRITE")
    fun createMemberSource(request: MemberSourceCreateRequest): MemberSourceRecord = calls.call(GovernanceClient::class.java, "createMemberSource", request)

    @Capability("create-member-definition", "write-governance", "WRITE")
    fun createMemberDefinition(request: MemberDefinitionCreateRequest): MemberDefinitionRecord = calls.call(GovernanceClient::class.java, "createMemberDefinition", request)

    @Capability("adopt-member-definition", "write-governance", "WRITE")
    fun adoptMemberDefinition(request: MemberDefinitionAdoptRequest): MemberSourceRecord = calls.call(GovernanceClient::class.java, "adoptMemberDefinition", request)

    @Capability("revise-member-adoption", "write-governance", "WRITE")
    fun reviseMemberAdoption(request: MemberAdoptionRevisionRequest): MemberSourceRecord = calls.call(GovernanceClient::class.java, "reviseMemberAdoption", request)

    @Capability("create-analysis-dimension-event-mapping", "write-governance", "WRITE")
    fun createAnalysisDimensionEventMapping(request: AnalysisDimensionEventMappingCreateRequest): AnalysisDimensionEventMappingCreateResult = calls.call(GovernanceClient::class.java, "createAnalysisDimensionEventMapping", request)

    @Capability("create-analysis-dimension-value-provider", "write-governance", "WRITE")
    fun createAnalysisDimensionValueProvider(request: AnalysisDimensionValueProviderCreateRequest): AnalysisDimensionValueProviderCreateResult = calls.call(GovernanceClient::class.java, "createAnalysisDimensionValueProvider", request)

    @Capability("create-analysis-dimension-value-alignment", "write-governance", "WRITE")
    fun createAnalysisDimensionValueAlignment(request: AnalysisDimensionValueAlignmentCreateRequest): AnalysisDimensionValueAlignmentRecord = calls.call(GovernanceClient::class.java, "createAnalysisDimensionValueAlignment", request)

    @Capability("create-scenario", "write-governance", "WRITE")
    fun createScenario(request: ScenarioCreateRequest): ScenarioCreateResult = calls.call(GovernanceClient::class.java, "createScenario", request)

    @Capability("create-scenario-metric-ref", "write-governance", "WRITE")
    fun createScenarioMetricRef(request: ScenarioMetricRefCreateRequest): ScenarioMetricRefCreateResult = calls.call(GovernanceClient::class.java, "createScenarioMetricRef", request)

    @Capability("create-scenario-member-source-ref", "write-governance", "WRITE")
    fun createScenarioMemberSourceRef(request: ScenarioMemberSourceRefCreateRequest): ScenarioMemberSourceRefRecord = calls.call(GovernanceClient::class.java, "createScenarioMemberSourceRef", request)

    @Capability("create-scenario-relation-filter", "write-governance", "WRITE")
    fun createScenarioRelationFilter(request: ScenarioRelationFilterCreateRequest): ScenarioRelationFilterRecord = calls.call(GovernanceClient::class.java, "createScenarioRelationFilter", request)

    @Capability("create-scenario-analysis-dimension-ref", "write-governance", "WRITE")
    fun createScenarioAnalysisDimensionRef(request: ScenarioAnalysisDimensionRefCreateRequest): ScenarioAnalysisDimensionRefCreateResult = calls.call(GovernanceClient::class.java, "createScenarioAnalysisDimensionRef", request)

    @Capability("create-scenario-filter-ref", "write-governance", "WRITE")
    fun createScenarioFilterRef(request: ScenarioFilterRefCreateRequest): ScenarioFilterRefCreateResult = calls.call(GovernanceClient::class.java, "createScenarioFilterRef", request)

    @Capability("create-scenario-pipeline-rule", "write-governance", "WRITE")
    fun createScenarioPipelineRule(request: ScenarioPipelineRuleCreateRequest): ScenarioPipelineRuleCreateResult = calls.call(GovernanceClient::class.java, "createScenarioPipelineRule", request)

    @Capability("refresh-scenario-pipeline-rule-contract", "write-governance", "WRITE")
    fun refreshScenarioPipelineRuleContract(request: ScenarioPipelineRuleContractRefreshRequest): ScenarioPipelineRuleRecord = calls.call(GovernanceClient::class.java, "refreshScenarioPipelineRuleContract", request)

    @Capability("revise-physical-table", "write-governance", "WRITE")
    fun revisePhysicalTable(request: GovernanceRevisionRequest<PhysicalTableCreateRequest>): PhysicalTableCreateResult = calls.call(GovernanceClient::class.java, "revisePhysicalTable", request)

    @Capability("update-physical-table-metadata", "write-governance", "WRITE")
    fun updatePhysicalTableMetadata(request: PhysicalTableMetadataUpdateRequest): PhysicalTableMetadataUpdateResult = calls.call(GovernanceClient::class.java, "updatePhysicalTableMetadata", request)

    @Capability("update-physical-table-columns", "write-governance", "WRITE")
    fun updatePhysicalTableColumns(request: PhysicalTableColumnsUpdateRequest): PhysicalTableColumnsUpdateResult = calls.call(GovernanceClient::class.java, "updatePhysicalTableColumns", request)

    @Capability("revise-business-line", "write-governance", "WRITE")
    fun reviseBusinessLine(request: GovernanceRevisionRequest<BusinessLineCreateRequest>): BusinessLineCreateResult = calls.call(GovernanceClient::class.java, "reviseBusinessLine", request)

    @Capability("revise-logical-table", "write-governance", "WRITE")
    fun reviseLogicalTable(request: GovernanceRevisionRequest<LogicalTableCreateRequest>): LogicalTableCreateResult = calls.call(GovernanceClient::class.java, "reviseLogicalTable", request)

    @Capability("revise-topology", "write-governance", "WRITE")
    fun reviseTopology(request: GovernanceRevisionRequest<TopologyCreateRequest>): TopologyCreateResult = calls.call(GovernanceClient::class.java, "reviseTopology", request)

    @Capability("revise-join-path", "write-governance", "WRITE")
    fun reviseJoinPath(request: GovernanceRevisionRequest<JoinPathCreateRequest>): JoinPathCreateResult = calls.call(GovernanceClient::class.java, "reviseJoinPath", request)

    @Capability("revise-event", "write-governance", "WRITE")
    fun reviseEvent(request: GovernanceRevisionRequest<EventCreateRequest>): EventCreateResult = calls.call(GovernanceClient::class.java, "reviseEvent", request)

    @Capability("revise-metric", "write-governance", "WRITE")
    fun reviseMetric(request: GovernanceRevisionRequest<MetricCreateRequest>): MetricCreateResult = calls.call(GovernanceClient::class.java, "reviseMetric", request)

    @Capability("create-metric-calculation", "write-governance", "WRITE")
    fun createMetricCalculation(request: MetricCalculationCreateRequest): MetricCalculationCreateResult = calls.call(GovernanceClient::class.java, "createMetricCalculation", request)

    @Capability("revise-metric-calculation", "write-governance", "WRITE")
    fun reviseMetricCalculation(request: GovernanceRevisionRequest<MetricCalculationCreateRequest>): MetricCalculationCreateResult = calls.call(GovernanceClient::class.java, "reviseMetricCalculation", request)

    @Capability("revise-member-definition", "write-governance", "WRITE")
    fun reviseMemberDefinition(request: GovernanceRevisionRequest<MemberDefinitionCreateRequest>): MemberDefinitionRecord = calls.call(GovernanceClient::class.java, "reviseMemberDefinition", request)

    @Capability("revise-dimension", "write-governance", "WRITE")
    fun reviseDimension(request: GovernanceRevisionRequest<DimensionCreateRequest>): DimensionCreateResult = calls.call(GovernanceClient::class.java, "reviseDimension", request)

    @Capability("revise-dimension-event-binding", "write-governance", "WRITE")
    fun reviseDimensionEventBinding(request: GovernanceRevisionRequest<DimensionEventBindingCreateRequest>): DimensionEventBindingCreateResult = calls.call(GovernanceClient::class.java, "reviseDimensionEventBinding", request)

    @Capability("revise-subject-area", "write-governance", "WRITE")
    fun reviseSubjectArea(request: SubjectContextRevisionRequest): SubjectContextRevisionResult = calls.call(GovernanceClient::class.java, "reviseSubjectArea", request)

    @Capability("revise-analysis-dimension", "write-governance", "WRITE")
    fun reviseAnalysisDimension(request: GovernanceRevisionRequest<AnalysisDimensionCreateRequest>): AnalysisDimensionCreateResult = calls.call(GovernanceClient::class.java, "reviseAnalysisDimension", request)

    @Capability("revise-scenario", "write-governance", "WRITE")
    fun reviseScenario(request: GovernanceRevisionRequest<ScenarioCreateRequest>): ScenarioCreateResult = calls.call(GovernanceClient::class.java, "reviseScenario", request)

    @Capability("activate", "write-governance", "WRITE")
    fun activate(request: GovernanceVersionActivationRequest): GovernanceVersionActivationResult = calls.call(GovernanceClient::class.java, "activate", request)

    @Capability("deprecate", "write-governance", "WRITE")
    fun deprecate(request: GovernanceVersionDeprecationRequest): GovernanceVersionDeprecationResult = calls.call(GovernanceClient::class.java, "deprecate", request)

    @Capability("build-release", "write-governance", "WRITE")
    fun buildRelease(request: SubjectAreaReleaseBuildRequest): SubjectAreaReleaseBuildResult = calls.call(GovernanceClient::class.java, "buildRelease", request)

    @Capability("register-source-structure", "write-governance", "WRITE")
    fun registerSourceStructure(request: SourceStructureRegistrationRequest): SourceDiscoveryReceipt = calls.call(GovernanceClient::class.java, "registerSourceStructure", request)

    @Capability("discover-source", "write-governance", "WRITE")
    fun discoverSource(request: WarehouseDiscoveryCommand): SourceDiscoveryReceipt = calls.call(GovernanceClient::class.java, "discoverSource", request)
}
