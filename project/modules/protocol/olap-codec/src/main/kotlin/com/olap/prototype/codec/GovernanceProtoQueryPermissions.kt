package com.olap.prototype.codec

/** 正式领域消息的显式字段转换。 */
internal fun MemberCandidateToProto(value: com.olap.prototype.query.contract.MemberCandidate): com.olap.prototype.wire.governance.MemberCandidate = com.olap.prototype.wire.governance.MemberCandidate.newBuilder().apply {
    setSourceId(value.sourceId)
    setSourceVersion(value.sourceVersion)
    setDisplayName(value.displayName)
    setEntity(value.entity)
    setRole(value.role)
    setShape(value.shape.name)
    addAllIdentitySlots(value.identitySlots.map { it })
    addAllOutputs(value.outputs.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.MemberCandidateOutputsEntry.newBuilder().setKey(entry.key).apply { setValue(MemberCandidateOutputToProto(entry.value)) }.build() })
    setPermission(MemberQueryPermissionToProto(value.permission))
    addAllFilterTypes(value.filterTypes.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.MemberCandidateFilterTypesEntry.newBuilder().setKey(entry.key).apply { setValue(entry.value.name) }.build() })
    addAllFilterValueContracts(value.filterValueContracts.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.MemberCandidateFilterValueContractsEntry.newBuilder().setKey(entry.key).apply { entry.value?.let { setValue(SemanticProto.toProto(it)) } }.build() })
    addAllUnitDimensions(value.unitDimensions.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.MemberCandidateUnitDimensionsEntry.newBuilder().setKey(entry.key).apply { setValue(com.olap.prototype.wire.governance.ListScopedInputContractDimensionRef.newBuilder().addAllValues(entry.value.map { SemanticProto.toProto(it) }).build()) }.build() })
    value.timeRange?.let { setTimeRange(MemberCandidateTimeRangeCapabilityToProto(it)) }
    addAllClockSlots(value.clockSlots.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.MemberCandidateClockSlotsEntry.newBuilder().setKey(entry.key).apply { setValue(entry.value) }.build() })
    addAllParameters(value.parameters.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.MemberCandidateParametersEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    addAllFixedArguments(value.fixedArguments.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.MemberCandidateFixedArgumentsEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    addAllGroupingCandidates(value.groupingCandidates.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.MemberCandidateGroupingCandidatesEntry.newBuilder().setKey(entry.key).apply { setValue(com.olap.prototype.wire.governance.ListScopedInputContractDimensionRef.newBuilder().addAllValues(entry.value.map { SemanticProto.toProto(it) }).build()) }.build() })
    addAllMemberParameters(value.memberParameters.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.MemberCandidateMemberParametersEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    addAllRelationParameters(value.relationParameters.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.MemberCandidateRelationParametersEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
}.build()
internal fun MemberCandidateFromProto(value: com.olap.prototype.wire.governance.MemberCandidate): com.olap.prototype.query.contract.MemberCandidate = com.olap.prototype.query.contract.MemberCandidate(
    sourceId = value.getSourceId(),
    sourceVersion = value.getSourceVersion(),
    displayName = value.getDisplayName(),
    entity = value.getEntity(),
    role = value.getRole(),
    shape = com.olap.prototype.member.MemberShape.valueOf(value.getShape()),
    identitySlots = value.getIdentitySlotsList().map { it },
    outputs = strictMap(value.getOutputsList().map { it.getKey() to MemberCandidateOutputFromProto(it.getValue()) }),
    permission = required(value.hasPermission(), "MemberCandidate.permission") { MemberQueryPermissionFromProto(value.getPermission()) },
    filterTypes = strictMap(value.getFilterTypesList().map { it.getKey() to com.olap.prototype.query.ScalarValueType.valueOf(it.getValue()) }),
    filterValueContracts = strictMap(value.getFilterValueContractsList().map { it.getKey() to if (it.hasValue()) SemanticProto.fromProto(it.getValue()) as com.olap.prototype.metric.MetricValueContract else null }),
    unitDimensions = strictMap(value.getUnitDimensionsList().map { it.getKey() to it.getValue().getValuesList().map { SemanticProto.fromProto(it) as com.olap.prototype.query.ScopedInputContract.DimensionRef } }),
    timeRange = if (value.hasTimeRange()) MemberCandidateTimeRangeCapabilityFromProto(value.getTimeRange()) else null,
    clockSlots = strictMap(value.getClockSlotsList().map { it.getKey() to it.getValue() }),
    parameters = strictMap(value.getParametersList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.Parameter }),
    fixedArguments = strictMap(value.getFixedArgumentsList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.Value }),
    groupingCandidates = strictMap(value.getGroupingCandidatesList().map { it.getKey() to it.getValue().getValuesList().map { SemanticProto.fromProto(it) as com.olap.prototype.query.ScopedInputContract.DimensionRef } }),
    memberParameters = strictMap(value.getMemberParametersList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.MemberSlot }),
    relationParameters = strictMap(value.getRelationParametersList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.RelationSlot })
)

internal fun MemberCandidateOutputToProto(value: com.olap.prototype.query.contract.MemberCandidate.Output): com.olap.prototype.wire.governance.MemberCandidateOutput = com.olap.prototype.wire.governance.MemberCandidateOutput.newBuilder().apply { when (value) {
    is com.olap.prototype.query.contract.MemberCandidate.ScalarOutput -> setScalarOutput(MemberCandidateScalarOutputToProto(value))
    is com.olap.prototype.query.contract.MemberCandidate.ContainerProperty -> setContainerProperty(MemberCandidateContainerPropertyToProto(value))
} }.build()
internal fun MemberCandidateOutputFromProto(value: com.olap.prototype.wire.governance.MemberCandidateOutput): com.olap.prototype.query.contract.MemberCandidate.Output = when (value.kindCase) {
    com.olap.prototype.wire.governance.MemberCandidateOutput.KindCase.SCALAR_OUTPUT -> MemberCandidateScalarOutputFromProto(value.getScalarOutput())
    com.olap.prototype.wire.governance.MemberCandidateOutput.KindCase.CONTAINER_PROPERTY -> MemberCandidateContainerPropertyFromProto(value.getContainerProperty())
    com.olap.prototype.wire.governance.MemberCandidateOutput.KindCase.KIND_NOT_SET -> error("MemberCandidateOutput variant is required")
}

internal fun MemberCandidateScalarOutputToProto(value: com.olap.prototype.query.contract.MemberCandidate.ScalarOutput): com.olap.prototype.wire.governance.MemberCandidateScalarOutput = com.olap.prototype.wire.governance.MemberCandidateScalarOutput.newBuilder().apply { when (value) {
    is com.olap.prototype.query.contract.MemberCandidate.Dimension -> setDimension(MemberCandidateDimensionToProto(value))
    is com.olap.prototype.query.contract.MemberCandidate.Property -> setProperty(MemberCandidatePropertyToProto(value))
} }.build()
internal fun MemberCandidateScalarOutputFromProto(value: com.olap.prototype.wire.governance.MemberCandidateScalarOutput): com.olap.prototype.query.contract.MemberCandidate.ScalarOutput = when (value.kindCase) {
    com.olap.prototype.wire.governance.MemberCandidateScalarOutput.KindCase.DIMENSION -> MemberCandidateDimensionFromProto(value.getDimension())
    com.olap.prototype.wire.governance.MemberCandidateScalarOutput.KindCase.PROPERTY -> MemberCandidatePropertyFromProto(value.getProperty())
    com.olap.prototype.wire.governance.MemberCandidateScalarOutput.KindCase.KIND_NOT_SET -> error("MemberCandidateScalarOutput variant is required")
}

internal fun MemberCandidateDimensionToProto(value: com.olap.prototype.query.contract.MemberCandidate.Dimension): com.olap.prototype.wire.governance.MemberCandidateDimension = com.olap.prototype.wire.governance.MemberCandidateDimension.newBuilder().apply {
    setDisplayName(value.displayName)
    setRole(value.role)
    setValueType(value.valueType.name)
    setDimensionId(value.dimensionId)
    setDimensionVersion(value.dimensionVersion)
    addAllMappings(value.mappings.map { SemanticProto.toProto(it) })
    value.valueContract?.let { setValueContract(SemanticProto.toProto(it)) }
}.build()
internal fun MemberCandidateDimensionFromProto(value: com.olap.prototype.wire.governance.MemberCandidateDimension): com.olap.prototype.query.contract.MemberCandidate.Dimension = com.olap.prototype.query.contract.MemberCandidate.Dimension(
    displayName = value.getDisplayName(),
    role = value.getRole(),
    valueType = com.olap.prototype.query.ScalarValueType.valueOf(value.getValueType()),
    dimensionId = value.getDimensionId(),
    dimensionVersion = value.getDimensionVersion(),
    mappings = value.getMappingsList().map { SemanticProto.fromProto(it) as com.olap.prototype.query.ScopedInputContract.DimensionRef },
    valueContract = if (value.hasValueContract()) SemanticProto.fromProto(value.getValueContract()) as com.olap.prototype.metric.MetricValueContract else null
)

internal fun MemberCandidatePropertyToProto(value: com.olap.prototype.query.contract.MemberCandidate.Property): com.olap.prototype.wire.governance.MemberCandidateProperty = com.olap.prototype.wire.governance.MemberCandidateProperty.newBuilder().apply {
    setDisplayName(value.displayName)
    setRole(value.role)
    setValueType(value.valueType.name)
    value.valueContract?.let { setValueContract(SemanticProto.toProto(it)) }
}.build()
internal fun MemberCandidatePropertyFromProto(value: com.olap.prototype.wire.governance.MemberCandidateProperty): com.olap.prototype.query.contract.MemberCandidate.Property = com.olap.prototype.query.contract.MemberCandidate.Property(
    displayName = value.getDisplayName(),
    role = value.getRole(),
    valueType = com.olap.prototype.query.ScalarValueType.valueOf(value.getValueType()),
    valueContract = if (value.hasValueContract()) SemanticProto.fromProto(value.getValueContract()) as com.olap.prototype.metric.MetricValueContract else null
)

internal fun MemberCandidateContainerPropertyToProto(value: com.olap.prototype.query.contract.MemberCandidate.ContainerProperty): com.olap.prototype.wire.governance.MemberCandidateContainerProperty = com.olap.prototype.wire.governance.MemberCandidateContainerProperty.newBuilder().apply {
    setDisplayName(value.displayName)
    setRole(value.role)
    setShape(SemanticProto.toProto(value.shape))
}.build()
internal fun MemberCandidateContainerPropertyFromProto(value: com.olap.prototype.wire.governance.MemberCandidateContainerProperty): com.olap.prototype.query.contract.MemberCandidate.ContainerProperty = com.olap.prototype.query.contract.MemberCandidate.ContainerProperty(
    displayName = value.getDisplayName(),
    role = value.getRole(),
    shape = required(value.hasShape(), "MemberCandidateContainerProperty.shape") { SemanticProto.fromProto(value.getShape()) as com.olap.prototype.query.SetOutputContract.Shape }
)

internal fun MemberQueryPermissionToProto(value: com.olap.prototype.query.contract.MemberQueryPermission): com.olap.prototype.wire.governance.MemberQueryPermission = com.olap.prototype.wire.governance.MemberQueryPermission.newBuilder().apply {
    addAllOutputs(value.outputs.map { it })
    addAllValues(value.values.map { it })
    addAllFilters(value.filters.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.MemberQueryPermissionFiltersEntry.newBuilder().setKey(entry.key).apply { setValue(com.olap.prototype.wire.governance.ListMemberConditionOperator.newBuilder().addAllValues(entry.value.map { it.name }).build()) }.build() })
    setTimeRange(value.timeRange)
    addAllClocks(value.clocks.map { it })
}.build()
internal fun MemberQueryPermissionFromProto(value: com.olap.prototype.wire.governance.MemberQueryPermission): com.olap.prototype.query.contract.MemberQueryPermission = com.olap.prototype.query.contract.MemberQueryPermission(
    outputs = value.getOutputsList().map { it },
    values = value.getValuesList().map { it },
    filters = strictMap(value.getFiltersList().map { it.getKey() to it.getValue().getValuesList().map { com.olap.prototype.member.MemberCondition.Operator.valueOf(it) } }),
    timeRange = value.getTimeRange(),
    clocks = value.getClocksList().map { it }
)

internal fun MemberCandidateTimeRangeCapabilityToProto(value: com.olap.prototype.query.contract.MemberCandidate.TimeRangeCapability): com.olap.prototype.wire.governance.MemberCandidateTimeRangeCapability = com.olap.prototype.wire.governance.MemberCandidateTimeRangeCapability.newBuilder().apply {
    setSlot(value.slot)
    setValueType(value.valueType.name)
}.build()
internal fun MemberCandidateTimeRangeCapabilityFromProto(value: com.olap.prototype.wire.governance.MemberCandidateTimeRangeCapability): com.olap.prototype.query.contract.MemberCandidate.TimeRangeCapability = com.olap.prototype.query.contract.MemberCandidate.TimeRangeCapability(
    slot = value.getSlot(),
    valueType = com.olap.prototype.query.ScalarValueType.valueOf(value.getValueType())
)

internal fun RelationFilterCandidateToProto(value: com.olap.prototype.query.contract.RelationFilterCandidate): com.olap.prototype.wire.governance.RelationFilterCandidate = com.olap.prototype.wire.governance.RelationFilterCandidate.newBuilder().apply {
    setDefinition(PublishedRelationFilterToProto(value.definition))
    setEntity(value.entity)
    setRole(value.role)
    addAllFilterTypes(value.filterTypes.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.RelationFilterCandidateFilterTypesEntry.newBuilder().setKey(entry.key).apply { setValue(entry.value.name) }.build() })
    addAllFilterValueContracts(value.filterValueContracts.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.RelationFilterCandidateFilterValueContractsEntry.newBuilder().setKey(entry.key).apply { entry.value?.let { setValue(SemanticProto.toProto(it)) } }.build() })
    value.timeRange?.let { setTimeRange(MemberCandidateTimeRangeCapabilityToProto(it)) }
    addAllClockSlots(value.clockSlots.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.RelationFilterCandidateClockSlotsEntry.newBuilder().setKey(entry.key).apply { setValue(entry.value) }.build() })
}.build()
internal fun RelationFilterCandidateFromProto(value: com.olap.prototype.wire.governance.RelationFilterCandidate): com.olap.prototype.query.contract.RelationFilterCandidate = com.olap.prototype.query.contract.RelationFilterCandidate(
    definition = required(value.hasDefinition(), "RelationFilterCandidate.definition") { PublishedRelationFilterFromProto(value.getDefinition()) },
    entity = value.getEntity(),
    role = value.getRole(),
    filterTypes = strictMap(value.getFilterTypesList().map { it.getKey() to com.olap.prototype.query.ScalarValueType.valueOf(it.getValue()) }),
    filterValueContracts = strictMap(value.getFilterValueContractsList().map { it.getKey() to if (it.hasValue()) SemanticProto.fromProto(it.getValue()) as com.olap.prototype.metric.MetricValueContract else null }),
    timeRange = if (value.hasTimeRange()) MemberCandidateTimeRangeCapabilityFromProto(value.getTimeRange()) else null,
    clockSlots = strictMap(value.getClockSlotsList().map { it.getKey() to it.getValue() })
)

internal fun RelationFilterPermissionToProto(value: com.olap.prototype.query.contract.RelationFilterPermission): com.olap.prototype.wire.governance.RelationFilterPermission = com.olap.prototype.wire.governance.RelationFilterPermission.newBuilder().apply {
    addAllFilters(value.filters.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.RelationFilterPermissionFiltersEntry.newBuilder().setKey(entry.key).apply { setValue(com.olap.prototype.wire.governance.ListMemberConditionOperator.newBuilder().addAllValues(entry.value.map { it.name }).build()) }.build() })
    setTimeRange(value.timeRange)
    addAllClocks(value.clocks.map { it })
}.build()
internal fun RelationFilterPermissionFromProto(value: com.olap.prototype.wire.governance.RelationFilterPermission): com.olap.prototype.query.contract.RelationFilterPermission = com.olap.prototype.query.contract.RelationFilterPermission(
    filters = strictMap(value.getFiltersList().map { it.getKey() to it.getValue().getValuesList().map { com.olap.prototype.member.MemberCondition.Operator.valueOf(it) } }),
    timeRange = value.getTimeRange(),
    clocks = value.getClocksList().map { it }
)

internal fun PostProcessCapabilityToProto(value: com.olap.prototype.query.contract.PostProcessCapability): com.olap.prototype.wire.governance.PostProcessCapability = com.olap.prototype.wire.governance.PostProcessCapability.newBuilder().apply {
    setRuleId(value.ruleId)
    setRuleType(value.ruleType)
    setRuleDisplayName(value.ruleDisplayName)
    setAuthorization(StageAuthorizationToProto(value.authorization))
    setSortOrder(value.sortOrder)
    value.reasonCode?.let { setReasonCode(it) }
    value.reasonMessage?.let { setReasonMessage(it) }
    value.producerStage?.let { setProducerStage(it) }
    value.targetRef?.let { setTargetRef(it) }
    value.targetVersionRef?.let { setTargetVersionRef(it) }
    setAvailable(value.available)
    setBlocking(value.blocking)
    setPageBlocking(value.pageBlocking)
    setExecutableBlocking(value.executableBlocking)
}.build()
internal fun PostProcessCapabilityFromProto(value: com.olap.prototype.wire.governance.PostProcessCapability): com.olap.prototype.query.contract.PostProcessCapability = com.olap.prototype.query.contract.PostProcessCapability(
    ruleId = value.getRuleId(),
    ruleType = value.getRuleType(),
    ruleDisplayName = value.getRuleDisplayName(),
    authorization = required(value.hasAuthorization(), "PostProcessCapability.authorization") { StageAuthorizationFromProto(value.getAuthorization()) },
    sortOrder = value.getSortOrder(),
    reasonCode = if (value.hasReasonCode()) value.getReasonCode() else null,
    reasonMessage = if (value.hasReasonMessage()) value.getReasonMessage() else null,
    producerStage = if (value.hasProducerStage()) value.getProducerStage() else null,
    targetRef = if (value.hasTargetRef()) value.getTargetRef() else null,
    targetVersionRef = if (value.hasTargetVersionRef()) value.getTargetVersionRef() else null,
    available = value.getAvailable(),
    blocking = value.getBlocking(),
    pageBlocking = value.getPageBlocking(),
    executableBlocking = value.getExecutableBlocking()
)

internal fun StageAuthorizationToProto(value: com.olap.prototype.query.contract.StageAuthorization): com.olap.prototype.wire.governance.StageAuthorization = com.olap.prototype.wire.governance.StageAuthorization.newBuilder().apply {
    setOperationType(value.operationType)
    addAllJoinTypes(value.joinTypes.map { it.name }.sorted())
    addAllAggregations(value.aggregations.map { it.name }.sorted())
    setExpressions(ExpressionAuthorizationToProto(value.expressions))
}.build()
internal fun StageAuthorizationFromProto(value: com.olap.prototype.wire.governance.StageAuthorization): com.olap.prototype.query.contract.StageAuthorization = com.olap.prototype.query.contract.StageAuthorization(
    operationType = value.getOperationType(),
    joinTypes = strictSet(value.getJoinTypesList().map { com.olap.prototype.query.contract.StageAuthorization.JoinType.valueOf(it) }),
    aggregations = strictSet(value.getAggregationsList().map { com.olap.prototype.query.ResultAggregation.Kind.valueOf(it) }),
    expressions = required(value.hasExpressions(), "StageAuthorization.expressions") { ExpressionAuthorizationFromProto(value.getExpressions()) }
)

internal fun ExpressionAuthorizationToProto(value: com.olap.prototype.query.contract.ExpressionAuthorization): com.olap.prototype.wire.governance.ExpressionAuthorization = com.olap.prototype.wire.governance.ExpressionAuthorization.newBuilder().apply {
    addAllKinds(value.kinds.map { it.name }.sorted())
    addAllOperators(value.operators.map { it.name }.sorted())
    addAllFunctions(value.functions.map { it.name }.sorted())
    addAllCastTypes(value.castTypes.map { it.name }.sorted())
    addAllLiteralTypes(value.literalTypes.map { it.name }.sorted())
}.build()
internal fun ExpressionAuthorizationFromProto(value: com.olap.prototype.wire.governance.ExpressionAuthorization): com.olap.prototype.query.contract.ExpressionAuthorization = com.olap.prototype.query.contract.ExpressionAuthorization(
    kinds = strictSet(value.getKindsList().map { com.olap.prototype.query.contract.ExpressionAuthorization.Kind.valueOf(it) }),
    operators = strictSet(value.getOperatorsList().map { com.olap.prototype.query.ResultExpression.ResultOperator.valueOf(it) }),
    functions = strictSet(value.getFunctionsList().map { com.olap.prototype.expression.ScalarFunction.valueOf(it) }),
    castTypes = strictSet(value.getCastTypesList().map { com.olap.prototype.expression.SemanticExpression.ValueType.valueOf(it) }),
    literalTypes = strictSet(value.getLiteralTypesList().map { com.olap.prototype.query.ScalarValueType.valueOf(it) })
)

internal fun QueryExecutionTypesMemberToProto(value: com.olap.prototype.query.contract.QueryExecutionTypes.Member): com.olap.prototype.wire.governance.QueryExecutionTypesMember = com.olap.prototype.wire.governance.QueryExecutionTypesMember.newBuilder().apply { when (value) {
    is com.olap.prototype.query.contract.QueryExecutionTypes.ProjectionMember -> setProjectionMember(QueryExecutionTypesProjectionMemberToProto(value))
    is com.olap.prototype.query.contract.QueryExecutionTypes.ComputedMember -> setComputedMember(QueryExecutionTypesComputedMemberToProto(value))
} }.build()
internal fun QueryExecutionTypesMemberFromProto(value: com.olap.prototype.wire.governance.QueryExecutionTypesMember): com.olap.prototype.query.contract.QueryExecutionTypes.Member = when (value.kindCase) {
    com.olap.prototype.wire.governance.QueryExecutionTypesMember.KindCase.PROJECTION_MEMBER -> QueryExecutionTypesProjectionMemberFromProto(value.getProjectionMember())
    com.olap.prototype.wire.governance.QueryExecutionTypesMember.KindCase.COMPUTED_MEMBER -> QueryExecutionTypesComputedMemberFromProto(value.getComputedMember())
    com.olap.prototype.wire.governance.QueryExecutionTypesMember.KindCase.KIND_NOT_SET -> error("QueryExecutionTypesMember variant is required")
}

internal fun PublishedMemberSourceToProto(value: com.olap.prototype.query.contract.PublishedMemberSource): com.olap.prototype.wire.governance.PublishedMemberSource = com.olap.prototype.wire.governance.PublishedMemberSource.newBuilder().apply {
    setSource(SemanticProto.toProto(value.source))
    setSubjectAreaId(value.subjectAreaId)
    setCode(value.code)
    setDisplayName(value.displayName)
}.build()
internal fun PublishedMemberSourceFromProto(value: com.olap.prototype.wire.governance.PublishedMemberSource): com.olap.prototype.query.contract.PublishedMemberSource = com.olap.prototype.query.contract.PublishedMemberSource(
    source = required(value.hasSource(), "PublishedMemberSource.source") { SemanticProto.fromProto(value.getSource()) as com.olap.prototype.member.MemberSource },
    subjectAreaId = value.getSubjectAreaId(),
    code = value.getCode(),
    displayName = value.getDisplayName()
)
