package com.olap.prototype.codec

/** 正式领域消息的显式字段转换。 */
internal fun MetricSnapshotRuleMeaningToProto(value: com.olap.prototype.metric.MetricSnapshotRule.Meaning): com.olap.prototype.wire.governance.MetricSnapshotRuleMeaning = com.olap.prototype.wire.governance.MetricSnapshotRuleMeaning.newBuilder().apply {
    setReduction(value.reduction.name)
    setNullPolicy(value.nullPolicy.name)
    setEntityRole(value.entityRole)
    setPartitionScope(value.partitionScope)
    setSourceFilterStage(value.sourceFilterStage)
    setPrivatePredicateStage(value.privatePredicateStage)
    setUnknownTimePolicy(value.unknownTimePolicy)
    setMeanPopulation(value.meanPopulation)
    setCrossEntityNullPolicy(value.crossEntityNullPolicy)
    setDuplicatePolicy(value.duplicatePolicy)
    value.meanValueContract?.let { setMeanValueContract(SemanticProto.toProto(it)) }
    value.meanTarget?.let { setMeanTarget(it.name) }
}.build()
internal fun MetricSnapshotRuleMeaningFromProto(value: com.olap.prototype.wire.governance.MetricSnapshotRuleMeaning): com.olap.prototype.metric.MetricSnapshotRule.Meaning = com.olap.prototype.metric.MetricSnapshotRule.Meaning(
    reduction = com.olap.prototype.metric.MetricSnapshotRule.Reduction.valueOf(value.getReduction()),
    nullPolicy = com.olap.prototype.metric.MetricSnapshotRule.NullPolicy.valueOf(value.getNullPolicy()),
    entityRole = value.getEntityRole(),
    partitionScope = value.getPartitionScope(),
    sourceFilterStage = value.getSourceFilterStage(),
    privatePredicateStage = value.getPrivatePredicateStage(),
    unknownTimePolicy = value.getUnknownTimePolicy(),
    meanPopulation = value.getMeanPopulation(),
    crossEntityNullPolicy = value.getCrossEntityNullPolicy(),
    duplicatePolicy = value.getDuplicatePolicy(),
    meanValueContract = if (value.hasMeanValueContract()) SemanticProto.fromProto(value.getMeanValueContract()) as com.olap.prototype.metric.MetricValueContract else null,
    meanTarget = if (value.hasMeanTarget()) com.olap.prototype.metric.MeanTarget.valueOf(value.getMeanTarget()) else null
)

internal fun MetricSourceToProto(value: com.olap.prototype.calculation.MetricSource): com.olap.prototype.wire.governance.MetricSource = com.olap.prototype.wire.governance.MetricSource.newBuilder().apply {
    addAllCallPath(value.callPath.map { it })
    setOrigin(MetricSourceOriginToProto(value.origin))
    setEvent(SemanticProto.toProto(value.event))
}.build()
internal fun MetricSourceFromProto(value: com.olap.prototype.wire.governance.MetricSource): com.olap.prototype.calculation.MetricSource = com.olap.prototype.calculation.MetricSource(
    callPath = value.getCallPathList().map { it },
    origin = required(value.hasOrigin(), "MetricSource.origin") { MetricSourceOriginFromProto(value.getOrigin()) },
    event = required(value.hasEvent(), "MetricSource.event") { SemanticProto.fromProto(value.getEvent()) as com.olap.prototype.event.EventRef }
)

internal fun MetricSourceOriginToProto(value: com.olap.prototype.calculation.MetricSource.Origin): com.olap.prototype.wire.governance.MetricSourceOrigin = com.olap.prototype.wire.governance.MetricSourceOrigin.newBuilder().apply { when (value) {
    is com.olap.prototype.calculation.MetricSource.Fact -> setFact(MetricSourceFactToProto(value))
    is com.olap.prototype.calculation.MetricSource.Member -> setMember(MetricSourceMemberToProto(value))
} }.build()
internal fun MetricSourceOriginFromProto(value: com.olap.prototype.wire.governance.MetricSourceOrigin): com.olap.prototype.calculation.MetricSource.Origin = when (value.kindCase) {
    com.olap.prototype.wire.governance.MetricSourceOrigin.KindCase.FACT -> MetricSourceFactFromProto(value.getFact())
    com.olap.prototype.wire.governance.MetricSourceOrigin.KindCase.MEMBER -> MetricSourceMemberFromProto(value.getMember())
    com.olap.prototype.wire.governance.MetricSourceOrigin.KindCase.KIND_NOT_SET -> error("MetricSourceOrigin variant is required")
}

internal fun MetricSourceFactToProto(value: com.olap.prototype.calculation.MetricSource.Fact): com.olap.prototype.wire.governance.MetricSourceFact = com.olap.prototype.wire.governance.MetricSourceFact.newBuilder().apply {
    setMetric(SemanticProto.toProto(value.metric))
}.build()
internal fun MetricSourceFactFromProto(value: com.olap.prototype.wire.governance.MetricSourceFact): com.olap.prototype.calculation.MetricSource.Fact = com.olap.prototype.calculation.MetricSource.Fact(
    metric = required(value.hasMetric(), "MetricSourceFact.metric") { SemanticProto.fromProto(value.getMetric()) as com.olap.prototype.calculation.MetricRef }
)

internal fun MetricSourceMemberToProto(value: com.olap.prototype.calculation.MetricSource.Member): com.olap.prototype.wire.governance.MetricSourceMember = com.olap.prototype.wire.governance.MetricSourceMember.newBuilder().apply {
    setSourceId(value.sourceId)
    setSourceVersion(value.sourceVersion)
    setRole(value.role)
    addAllSlots(value.slots.map { it })
}.build()
internal fun MetricSourceMemberFromProto(value: com.olap.prototype.wire.governance.MetricSourceMember): com.olap.prototype.calculation.MetricSource.Member = com.olap.prototype.calculation.MetricSource.Member(
    sourceId = value.getSourceId(),
    sourceVersion = value.getSourceVersion(),
    role = value.getRole(),
    slots = value.getSlotsList().map { it }
)

internal fun GovernanceRevisionRequestPhysicalTableCreateRequestToProto(value: com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.PhysicalTableCreateRequest>): com.olap.prototype.wire.governance.GovernanceRevisionRequestPhysicalTableCreateRequest = com.olap.prototype.wire.governance.GovernanceRevisionRequestPhysicalTableCreateRequest.newBuilder().apply {
    setObjectId(value.objectId)
    setSourceVersion(value.sourceVersion)
    setDefinition(PhysicalTableCreateRequestToProto(value.definition))
}.build()
internal fun GovernanceRevisionRequestPhysicalTableCreateRequestFromProto(value: com.olap.prototype.wire.governance.GovernanceRevisionRequestPhysicalTableCreateRequest): com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.PhysicalTableCreateRequest> = com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.PhysicalTableCreateRequest>(
    objectId = value.getObjectId(),
    sourceVersion = value.getSourceVersion(),
    definition = required(value.hasDefinition(), "GovernanceRevisionRequestPhysicalTableCreateRequest.definition") { PhysicalTableCreateRequestFromProto(value.getDefinition()) }
)

internal fun GovernanceRevisionRequestBusinessLineCreateRequestToProto(value: com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.BusinessLineCreateRequest>): com.olap.prototype.wire.governance.GovernanceRevisionRequestBusinessLineCreateRequest = com.olap.prototype.wire.governance.GovernanceRevisionRequestBusinessLineCreateRequest.newBuilder().apply {
    setObjectId(value.objectId)
    setSourceVersion(value.sourceVersion)
    setDefinition(BusinessLineCreateRequestToProto(value.definition))
}.build()
internal fun GovernanceRevisionRequestBusinessLineCreateRequestFromProto(value: com.olap.prototype.wire.governance.GovernanceRevisionRequestBusinessLineCreateRequest): com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.BusinessLineCreateRequest> = com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.BusinessLineCreateRequest>(
    objectId = value.getObjectId(),
    sourceVersion = value.getSourceVersion(),
    definition = required(value.hasDefinition(), "GovernanceRevisionRequestBusinessLineCreateRequest.definition") { BusinessLineCreateRequestFromProto(value.getDefinition()) }
)

internal fun GovernanceRevisionRequestLogicalTableCreateRequestToProto(value: com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.LogicalTableCreateRequest>): com.olap.prototype.wire.governance.GovernanceRevisionRequestLogicalTableCreateRequest = com.olap.prototype.wire.governance.GovernanceRevisionRequestLogicalTableCreateRequest.newBuilder().apply {
    setObjectId(value.objectId)
    setSourceVersion(value.sourceVersion)
    setDefinition(LogicalTableCreateRequestToProto(value.definition))
}.build()
internal fun GovernanceRevisionRequestLogicalTableCreateRequestFromProto(value: com.olap.prototype.wire.governance.GovernanceRevisionRequestLogicalTableCreateRequest): com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.LogicalTableCreateRequest> = com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.LogicalTableCreateRequest>(
    objectId = value.getObjectId(),
    sourceVersion = value.getSourceVersion(),
    definition = required(value.hasDefinition(), "GovernanceRevisionRequestLogicalTableCreateRequest.definition") { LogicalTableCreateRequestFromProto(value.getDefinition()) }
)

internal fun GovernanceRevisionRequestTopologyCreateRequestToProto(value: com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.TopologyCreateRequest>): com.olap.prototype.wire.governance.GovernanceRevisionRequestTopologyCreateRequest = com.olap.prototype.wire.governance.GovernanceRevisionRequestTopologyCreateRequest.newBuilder().apply {
    setObjectId(value.objectId)
    setSourceVersion(value.sourceVersion)
    setDefinition(TopologyCreateRequestToProto(value.definition))
}.build()
internal fun GovernanceRevisionRequestTopologyCreateRequestFromProto(value: com.olap.prototype.wire.governance.GovernanceRevisionRequestTopologyCreateRequest): com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.TopologyCreateRequest> = com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.TopologyCreateRequest>(
    objectId = value.getObjectId(),
    sourceVersion = value.getSourceVersion(),
    definition = required(value.hasDefinition(), "GovernanceRevisionRequestTopologyCreateRequest.definition") { TopologyCreateRequestFromProto(value.getDefinition()) }
)

internal fun GovernanceRevisionRequestJoinPathCreateRequestToProto(value: com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.JoinPathCreateRequest>): com.olap.prototype.wire.governance.GovernanceRevisionRequestJoinPathCreateRequest = com.olap.prototype.wire.governance.GovernanceRevisionRequestJoinPathCreateRequest.newBuilder().apply {
    setObjectId(value.objectId)
    setSourceVersion(value.sourceVersion)
    setDefinition(JoinPathCreateRequestToProto(value.definition))
}.build()
internal fun GovernanceRevisionRequestJoinPathCreateRequestFromProto(value: com.olap.prototype.wire.governance.GovernanceRevisionRequestJoinPathCreateRequest): com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.JoinPathCreateRequest> = com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.JoinPathCreateRequest>(
    objectId = value.getObjectId(),
    sourceVersion = value.getSourceVersion(),
    definition = required(value.hasDefinition(), "GovernanceRevisionRequestJoinPathCreateRequest.definition") { JoinPathCreateRequestFromProto(value.getDefinition()) }
)

internal fun GovernanceRevisionRequestEventCreateRequestToProto(value: com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.EventCreateRequest>): com.olap.prototype.wire.governance.GovernanceRevisionRequestEventCreateRequest = com.olap.prototype.wire.governance.GovernanceRevisionRequestEventCreateRequest.newBuilder().apply {
    setObjectId(value.objectId)
    setSourceVersion(value.sourceVersion)
    setDefinition(EventCreateRequestToProto(value.definition))
}.build()
internal fun GovernanceRevisionRequestEventCreateRequestFromProto(value: com.olap.prototype.wire.governance.GovernanceRevisionRequestEventCreateRequest): com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.EventCreateRequest> = com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.EventCreateRequest>(
    objectId = value.getObjectId(),
    sourceVersion = value.getSourceVersion(),
    definition = required(value.hasDefinition(), "GovernanceRevisionRequestEventCreateRequest.definition") { EventCreateRequestFromProto(value.getDefinition()) }
)

internal fun GovernanceRevisionRequestMetricCreateRequestToProto(value: com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.MetricCreateRequest>): com.olap.prototype.wire.governance.GovernanceRevisionRequestMetricCreateRequest = com.olap.prototype.wire.governance.GovernanceRevisionRequestMetricCreateRequest.newBuilder().apply {
    setObjectId(value.objectId)
    setSourceVersion(value.sourceVersion)
    setDefinition(MetricCreateRequestToProto(value.definition))
}.build()
internal fun GovernanceRevisionRequestMetricCreateRequestFromProto(value: com.olap.prototype.wire.governance.GovernanceRevisionRequestMetricCreateRequest): com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.MetricCreateRequest> = com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.MetricCreateRequest>(
    objectId = value.getObjectId(),
    sourceVersion = value.getSourceVersion(),
    definition = required(value.hasDefinition(), "GovernanceRevisionRequestMetricCreateRequest.definition") { MetricCreateRequestFromProto(value.getDefinition()) }
)

internal fun GovernanceRevisionRequestMetricCalculationCreateRequestToProto(value: com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.MetricCalculationCreateRequest>): com.olap.prototype.wire.governance.GovernanceRevisionRequestMetricCalculationCreateRequest = com.olap.prototype.wire.governance.GovernanceRevisionRequestMetricCalculationCreateRequest.newBuilder().apply {
    setObjectId(value.objectId)
    setSourceVersion(value.sourceVersion)
    setDefinition(MetricCalculationCreateRequestToProto(value.definition))
}.build()
internal fun GovernanceRevisionRequestMetricCalculationCreateRequestFromProto(value: com.olap.prototype.wire.governance.GovernanceRevisionRequestMetricCalculationCreateRequest): com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.MetricCalculationCreateRequest> = com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.MetricCalculationCreateRequest>(
    objectId = value.getObjectId(),
    sourceVersion = value.getSourceVersion(),
    definition = required(value.hasDefinition(), "GovernanceRevisionRequestMetricCalculationCreateRequest.definition") { MetricCalculationCreateRequestFromProto(value.getDefinition()) }
)

internal fun GovernanceRevisionRequestMemberDefinitionCreateRequestToProto(value: com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.MemberDefinitionCreateRequest>): com.olap.prototype.wire.governance.GovernanceRevisionRequestMemberDefinitionCreateRequest = com.olap.prototype.wire.governance.GovernanceRevisionRequestMemberDefinitionCreateRequest.newBuilder().apply {
    setObjectId(value.objectId)
    setSourceVersion(value.sourceVersion)
    setDefinition(MemberDefinitionCreateRequestToProto(value.definition))
}.build()
internal fun GovernanceRevisionRequestMemberDefinitionCreateRequestFromProto(value: com.olap.prototype.wire.governance.GovernanceRevisionRequestMemberDefinitionCreateRequest): com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.MemberDefinitionCreateRequest> = com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.MemberDefinitionCreateRequest>(
    objectId = value.getObjectId(),
    sourceVersion = value.getSourceVersion(),
    definition = required(value.hasDefinition(), "GovernanceRevisionRequestMemberDefinitionCreateRequest.definition") { MemberDefinitionCreateRequestFromProto(value.getDefinition()) }
)

internal fun GovernanceRevisionRequestDimensionCreateRequestToProto(value: com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.DimensionCreateRequest>): com.olap.prototype.wire.governance.GovernanceRevisionRequestDimensionCreateRequest = com.olap.prototype.wire.governance.GovernanceRevisionRequestDimensionCreateRequest.newBuilder().apply {
    setObjectId(value.objectId)
    setSourceVersion(value.sourceVersion)
    setDefinition(DimensionCreateRequestToProto(value.definition))
}.build()
internal fun GovernanceRevisionRequestDimensionCreateRequestFromProto(value: com.olap.prototype.wire.governance.GovernanceRevisionRequestDimensionCreateRequest): com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.DimensionCreateRequest> = com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.DimensionCreateRequest>(
    objectId = value.getObjectId(),
    sourceVersion = value.getSourceVersion(),
    definition = required(value.hasDefinition(), "GovernanceRevisionRequestDimensionCreateRequest.definition") { DimensionCreateRequestFromProto(value.getDefinition()) }
)

internal fun GovernanceRevisionRequestDimensionEventBindingCreateRequestToProto(value: com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.DimensionEventBindingCreateRequest>): com.olap.prototype.wire.governance.GovernanceRevisionRequestDimensionEventBindingCreateRequest = com.olap.prototype.wire.governance.GovernanceRevisionRequestDimensionEventBindingCreateRequest.newBuilder().apply {
    setObjectId(value.objectId)
    setSourceVersion(value.sourceVersion)
    setDefinition(DimensionEventBindingCreateRequestToProto(value.definition))
}.build()
internal fun GovernanceRevisionRequestDimensionEventBindingCreateRequestFromProto(value: com.olap.prototype.wire.governance.GovernanceRevisionRequestDimensionEventBindingCreateRequest): com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.DimensionEventBindingCreateRequest> = com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.DimensionEventBindingCreateRequest>(
    objectId = value.getObjectId(),
    sourceVersion = value.getSourceVersion(),
    definition = required(value.hasDefinition(), "GovernanceRevisionRequestDimensionEventBindingCreateRequest.definition") { DimensionEventBindingCreateRequestFromProto(value.getDefinition()) }
)

internal fun ContextRevisionReferenceToProto(value: com.olap.prototype.governance.contract.ContextRevisionReference): com.olap.prototype.wire.governance.ContextRevisionReference = com.olap.prototype.wire.governance.ContextRevisionReference.newBuilder().apply {
    setAssetType(value.assetType)
    setId(value.id)
    setSourceVersion(value.sourceVersion)
    setTargetVersion(value.targetVersion)
}.build()
internal fun ContextRevisionReferenceFromProto(value: com.olap.prototype.wire.governance.ContextRevisionReference): com.olap.prototype.governance.contract.ContextRevisionReference = com.olap.prototype.governance.contract.ContextRevisionReference(
    assetType = value.getAssetType(),
    id = value.getId(),
    sourceVersion = value.getSourceVersion(),
    targetVersion = value.getTargetVersion()
)

internal fun GovernanceRevisionRequestAnalysisDimensionCreateRequestToProto(value: com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.AnalysisDimensionCreateRequest>): com.olap.prototype.wire.governance.GovernanceRevisionRequestAnalysisDimensionCreateRequest = com.olap.prototype.wire.governance.GovernanceRevisionRequestAnalysisDimensionCreateRequest.newBuilder().apply {
    setObjectId(value.objectId)
    setSourceVersion(value.sourceVersion)
    setDefinition(AnalysisDimensionCreateRequestToProto(value.definition))
}.build()
internal fun GovernanceRevisionRequestAnalysisDimensionCreateRequestFromProto(value: com.olap.prototype.wire.governance.GovernanceRevisionRequestAnalysisDimensionCreateRequest): com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.AnalysisDimensionCreateRequest> = com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.AnalysisDimensionCreateRequest>(
    objectId = value.getObjectId(),
    sourceVersion = value.getSourceVersion(),
    definition = required(value.hasDefinition(), "GovernanceRevisionRequestAnalysisDimensionCreateRequest.definition") { AnalysisDimensionCreateRequestFromProto(value.getDefinition()) }
)

internal fun GovernanceRevisionRequestScenarioCreateRequestToProto(value: com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.ScenarioCreateRequest>): com.olap.prototype.wire.governance.GovernanceRevisionRequestScenarioCreateRequest = com.olap.prototype.wire.governance.GovernanceRevisionRequestScenarioCreateRequest.newBuilder().apply {
    setObjectId(value.objectId)
    setSourceVersion(value.sourceVersion)
    setDefinition(ScenarioCreateRequestToProto(value.definition))
}.build()
internal fun GovernanceRevisionRequestScenarioCreateRequestFromProto(value: com.olap.prototype.wire.governance.GovernanceRevisionRequestScenarioCreateRequest): com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.ScenarioCreateRequest> = com.olap.prototype.governance.contract.GovernanceRevisionRequest<com.olap.prototype.governance.contract.ScenarioCreateRequest>(
    objectId = value.getObjectId(),
    sourceVersion = value.getSourceVersion(),
    definition = required(value.hasDefinition(), "GovernanceRevisionRequestScenarioCreateRequest.definition") { ScenarioCreateRequestFromProto(value.getDefinition()) }
)

internal fun GovernanceVersionActivationRequestToProto(value: com.olap.prototype.governance.contract.GovernanceVersionActivationRequest): com.olap.prototype.wire.governance.GovernanceVersionActivationRequest = com.olap.prototype.wire.governance.GovernanceVersionActivationRequest.newBuilder().apply {
    setObjectType(value.objectType.name)
    setObjectId(value.objectId)
    setObjectVersion(value.objectVersion)
    setActivatedBy(value.activatedBy)
    setReason(value.reason)
}.build()
internal fun GovernanceVersionActivationRequestFromProto(value: com.olap.prototype.wire.governance.GovernanceVersionActivationRequest): com.olap.prototype.governance.contract.GovernanceVersionActivationRequest = com.olap.prototype.governance.contract.GovernanceVersionActivationRequest(
    objectType = com.olap.prototype.governance.contract.GovernanceActivationObjectType.valueOf(value.getObjectType()),
    objectId = value.getObjectId(),
    objectVersion = value.getObjectVersion(),
    activatedBy = value.getActivatedBy(),
    reason = value.getReason()
)

internal fun GovernanceVersionActivationResultToProto(value: com.olap.prototype.governance.contract.GovernanceVersionActivationResult): com.olap.prototype.wire.governance.GovernanceVersionActivationResult = com.olap.prototype.wire.governance.GovernanceVersionActivationResult.newBuilder().apply {
    setObjectType(value.objectType)
    setObjectId(value.objectId)
    setObjectVersion(value.objectVersion)
    value.previousActiveVersion?.let { setPreviousActiveVersion(it) }
    setStatus(value.status)
    setActivatedAt(value.activatedAt.toString())
}.build()
internal fun GovernanceVersionActivationResultFromProto(value: com.olap.prototype.wire.governance.GovernanceVersionActivationResult): com.olap.prototype.governance.contract.GovernanceVersionActivationResult = com.olap.prototype.governance.contract.GovernanceVersionActivationResult(
    objectType = value.getObjectType(),
    objectId = value.getObjectId(),
    objectVersion = value.getObjectVersion(),
    previousActiveVersion = if (value.hasPreviousActiveVersion()) value.getPreviousActiveVersion() else null,
    status = value.getStatus(),
    activatedAt = java.time.Instant.parse(value.getActivatedAt())
)

internal fun GovernanceVersionDeprecationRequestToProto(value: com.olap.prototype.governance.contract.GovernanceVersionDeprecationRequest): com.olap.prototype.wire.governance.GovernanceVersionDeprecationRequest = com.olap.prototype.wire.governance.GovernanceVersionDeprecationRequest.newBuilder().apply {
    setObjectType(value.objectType.name)
    setObjectId(value.objectId)
    setObjectVersion(value.objectVersion)
    setDeprecatedBy(value.deprecatedBy)
    setReason(value.reason)
}.build()
internal fun GovernanceVersionDeprecationRequestFromProto(value: com.olap.prototype.wire.governance.GovernanceVersionDeprecationRequest): com.olap.prototype.governance.contract.GovernanceVersionDeprecationRequest = com.olap.prototype.governance.contract.GovernanceVersionDeprecationRequest(
    objectType = com.olap.prototype.governance.contract.GovernanceActivationObjectType.valueOf(value.getObjectType()),
    objectId = value.getObjectId(),
    objectVersion = value.getObjectVersion(),
    deprecatedBy = value.getDeprecatedBy(),
    reason = value.getReason()
)

internal fun GovernanceVersionDeprecationResultToProto(value: com.olap.prototype.governance.contract.GovernanceVersionDeprecationResult): com.olap.prototype.wire.governance.GovernanceVersionDeprecationResult = com.olap.prototype.wire.governance.GovernanceVersionDeprecationResult.newBuilder().apply {
    setObjectType(value.objectType)
    setObjectId(value.objectId)
    setObjectVersion(value.objectVersion)
    setStatus(value.status)
    setDeprecatedAt(value.deprecatedAt.toString())
}.build()
internal fun GovernanceVersionDeprecationResultFromProto(value: com.olap.prototype.wire.governance.GovernanceVersionDeprecationResult): com.olap.prototype.governance.contract.GovernanceVersionDeprecationResult = com.olap.prototype.governance.contract.GovernanceVersionDeprecationResult(
    objectType = value.getObjectType(),
    objectId = value.getObjectId(),
    objectVersion = value.getObjectVersion(),
    status = value.getStatus(),
    deprecatedAt = java.time.Instant.parse(value.getDeprecatedAt())
)

internal fun SourceDiscoveryTableToProto(value: com.olap.prototype.governance.contract.SourceDiscoveryTable): com.olap.prototype.wire.governance.SourceDiscoveryTable = com.olap.prototype.wire.governance.SourceDiscoveryTable.newBuilder().apply {
    setId(value.id)
    setDiscoverySnapshotId(value.discoverySnapshotId)
    setCatalogName(value.catalogName)
    setSchemaName(value.schemaName)
    setTableName(value.tableName)
    value.tableComment?.let { setTableComment(it) }
    value.rowCount?.let { setRowCount(it) }
    value.schemaHash?.let { setSchemaHash(it) }
    setConstraints(JdbcTableConstraintsToProto(value.constraints))
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun SourceDiscoveryTableFromProto(value: com.olap.prototype.wire.governance.SourceDiscoveryTable): com.olap.prototype.governance.contract.SourceDiscoveryTable = com.olap.prototype.governance.contract.SourceDiscoveryTable(
    id = value.getId(),
    discoverySnapshotId = value.getDiscoverySnapshotId(),
    catalogName = value.getCatalogName(),
    schemaName = value.getSchemaName(),
    tableName = value.getTableName(),
    tableComment = if (value.hasTableComment()) value.getTableComment() else null,
    rowCount = if (value.hasRowCount()) value.getRowCount() else null,
    schemaHash = if (value.hasSchemaHash()) value.getSchemaHash() else null,
    constraints = required(value.hasConstraints(), "SourceDiscoveryTable.constraints") { JdbcTableConstraintsFromProto(value.getConstraints()) },
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)

internal fun ReleaseIdentityViewToProto(value: com.olap.prototype.governance.contract.ReleaseIdentityView): com.olap.prototype.wire.governance.ReleaseIdentityView = com.olap.prototype.wire.governance.ReleaseIdentityView.newBuilder().apply {
    setReleaseId(value.releaseId)
    setReleaseVersion(value.releaseVersion)
    setSubjectAreaId(value.subjectAreaId)
    setSubjectAreaVersion(value.subjectAreaVersion)
    setGovernanceClosureHash(value.governanceClosureHash)
    setCatalogHash(value.catalogHash)
    setArtifactHash(value.artifactHash)
}.build()
internal fun ReleaseIdentityViewFromProto(value: com.olap.prototype.wire.governance.ReleaseIdentityView): com.olap.prototype.governance.contract.ReleaseIdentityView = com.olap.prototype.governance.contract.ReleaseIdentityView(
    releaseId = value.getReleaseId(),
    releaseVersion = value.getReleaseVersion(),
    subjectAreaId = value.getSubjectAreaId(),
    subjectAreaVersion = value.getSubjectAreaVersion(),
    governanceClosureHash = value.getGovernanceClosureHash(),
    catalogHash = value.getCatalogHash(),
    artifactHash = value.getArtifactHash()
)

internal fun NativeScalarFunctionToProto(value: com.olap.prototype.expression.NativeScalarFunction): com.olap.prototype.wire.governance.NativeScalarFunction = com.olap.prototype.wire.governance.NativeScalarFunction.newBuilder().apply {
    setId(value.id)
    addAllParameters(value.parameters.map { NativeScalarFunctionParameterToProto(it) })
    setResultType(NativeValueTypeToProto(value.resultType))
    value.arithmetic?.let { setArithmetic(SemanticProto.toProto(it)) }
    setStability(value.stability.name)
}.build()
internal fun NativeScalarFunctionFromProto(value: com.olap.prototype.wire.governance.NativeScalarFunction): com.olap.prototype.expression.NativeScalarFunction = com.olap.prototype.expression.NativeScalarFunction(
    id = value.getId(),
    parameters = value.getParametersList().map { NativeScalarFunctionParameterFromProto(it) },
    resultType = required(value.hasResultType(), "NativeScalarFunction.resultType") { NativeValueTypeFromProto(value.getResultType()) },
    arithmetic = if (value.hasArithmetic()) SemanticProto.fromProto(value.getArithmetic()) as com.olap.prototype.metric.MetricValueContract.Arithmetic else null,
    stability = com.olap.prototype.expression.NativeScalarFunction.Stability.valueOf(value.getStability())
)

internal fun NativeScalarFunctionParameterToProto(value: com.olap.prototype.expression.NativeScalarFunction.Parameter): com.olap.prototype.wire.governance.NativeScalarFunctionParameter = com.olap.prototype.wire.governance.NativeScalarFunctionParameter.newBuilder().apply {
    setKind(value.kind.name)
    value.scalarType?.let { setScalarType(it.name) }
}.build()
internal fun NativeScalarFunctionParameterFromProto(value: com.olap.prototype.wire.governance.NativeScalarFunctionParameter): com.olap.prototype.expression.NativeScalarFunction.Parameter = com.olap.prototype.expression.NativeScalarFunction.Parameter(
    kind = com.olap.prototype.expression.NativeScalarFunction.ParameterKind.valueOf(value.getKind()),
    scalarType = if (value.hasScalarType()) com.olap.prototype.query.ScalarValueType.valueOf(value.getScalarType()) else null
)

internal fun NativeAggregateMappingToProto(value: com.olap.prototype.expression.NativeAggregateMapping): com.olap.prototype.wire.governance.NativeAggregateMapping = com.olap.prototype.wire.governance.NativeAggregateMapping.newBuilder().apply { when (value) {
    is com.olap.prototype.expression.NativeAggregateMapping.Mapped -> setMapped(NativeAggregateMappingMappedToProto(value))
    is com.olap.prototype.expression.NativeAggregateMapping.Unavailable -> setUnavailable(NativeAggregateMappingUnavailableToProto(value))
} }.build()
internal fun NativeAggregateMappingFromProto(value: com.olap.prototype.wire.governance.NativeAggregateMapping): com.olap.prototype.expression.NativeAggregateMapping = when (value.kindCase) {
    com.olap.prototype.wire.governance.NativeAggregateMapping.KindCase.MAPPED -> NativeAggregateMappingMappedFromProto(value.getMapped())
    com.olap.prototype.wire.governance.NativeAggregateMapping.KindCase.UNAVAILABLE -> NativeAggregateMappingUnavailableFromProto(value.getUnavailable())
    com.olap.prototype.wire.governance.NativeAggregateMapping.KindCase.KIND_NOT_SET -> error("NativeAggregateMapping variant is required")
}

internal fun NativeAggregateMappingMappedToProto(value: com.olap.prototype.expression.NativeAggregateMapping.Mapped): com.olap.prototype.wire.governance.NativeAggregateMappingMapped = com.olap.prototype.wire.governance.NativeAggregateMappingMapped.newBuilder().apply {
    setResultType(NativeValueTypeToProto(value.resultType))
    value.averageBehavior?.let { setAverageBehavior(SemanticProto.toProto(it)) }
}.build()
internal fun NativeAggregateMappingMappedFromProto(value: com.olap.prototype.wire.governance.NativeAggregateMappingMapped): com.olap.prototype.expression.NativeAggregateMapping.Mapped = com.olap.prototype.expression.NativeAggregateMapping.Mapped(
    resultType = required(value.hasResultType(), "NativeAggregateMappingMapped.resultType") { NativeValueTypeFromProto(value.getResultType()) },
    averageBehavior = if (value.hasAverageBehavior()) SemanticProto.fromProto(value.getAverageBehavior()) else null
)

internal fun NativeAggregateMappingUnavailableToProto(value: com.olap.prototype.expression.NativeAggregateMapping.Unavailable): com.olap.prototype.wire.governance.NativeAggregateMappingUnavailable = com.olap.prototype.wire.governance.NativeAggregateMappingUnavailable.newBuilder().apply {
    setCode(value.code)
    setMessage(value.message)
}.build()
internal fun NativeAggregateMappingUnavailableFromProto(value: com.olap.prototype.wire.governance.NativeAggregateMappingUnavailable): com.olap.prototype.expression.NativeAggregateMapping.Unavailable = com.olap.prototype.expression.NativeAggregateMapping.Unavailable(
    code = value.getCode(),
    message = value.getMessage()
)
