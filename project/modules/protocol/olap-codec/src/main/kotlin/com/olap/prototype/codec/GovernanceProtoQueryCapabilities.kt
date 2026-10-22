package com.olap.prototype.codec

/** 正式领域消息的显式字段转换。 */
internal fun PublishedQueryEntryToProto(value: com.olap.prototype.query.contract.PublishedQueryEntry): com.olap.prototype.wire.governance.PublishedQueryEntry = com.olap.prototype.wire.governance.PublishedQueryEntry.newBuilder().apply {
    setCapability(QueryCapabilityViewToProto(value.capability))
    setCatalogHash(value.catalogHash)
    setSemanticCatalogHash(value.semanticCatalogHash)
    setSemanticCatalog(PublishedSemanticCatalogToProto(value.semanticCatalog))
}.build()
internal fun PublishedQueryEntryFromProto(value: com.olap.prototype.wire.governance.PublishedQueryEntry): com.olap.prototype.query.contract.PublishedQueryEntry = com.olap.prototype.query.contract.PublishedQueryEntry(
    capability = required(value.hasCapability(), "PublishedQueryEntry.capability") { QueryCapabilityViewFromProto(value.getCapability()) },
    catalogHash = value.getCatalogHash(),
    semanticCatalogHash = value.getSemanticCatalogHash(),
    semanticCatalog = required(value.hasSemanticCatalog(), "PublishedQueryEntry.semanticCatalog") { PublishedSemanticCatalogFromProto(value.getSemanticCatalog()) }
)

internal fun QueryCapabilityViewToProto(value: com.olap.prototype.query.contract.QueryCapabilityView): com.olap.prototype.wire.governance.QueryCapabilityView = com.olap.prototype.wire.governance.QueryCapabilityView.newBuilder().apply {
    setScenario(QueryScenarioRefToProto(value.scenario))
    setSubjectAreaCapabilityContext(SubjectAreaCapabilityContextToProto(value.subjectAreaCapabilityContext))
    setReleaseId(value.releaseId)
    setReleaseVersion(value.releaseVersion)
    setReleaseDisplayName(value.releaseDisplayName)
    addAllMetricCandidates(value.metricCandidates.map { MetricCandidateToProto(it) })
    addAllMemberCandidates(value.memberCandidates.map { MemberCandidateToProto(it) })
    addAllAnalysisDimensionCandidates(value.analysisDimensionCandidates.map { AnalysisDimensionCandidateToProto(it) })
    addAllTimeAnalysisDimensionCandidates(value.timeAnalysisDimensionCandidates.map { TimeAnalysisDimensionCandidateToProto(it) })
    addAllFilterCandidates(value.filterCandidates.map { FilterCandidateToProto(it) })
    addAllRelationFilterCandidates(value.relationFilterCandidates.map { RelationFilterCandidateToProto(it) })
    addAllTemporalClockCandidates(value.temporalClockCandidates.map { TemporalClockCandidateToProto(it) })
    addAllPostProcessCapabilities(value.postProcessCapabilities.map { PostProcessCapabilityToProto(it) })
    addAllMetricAnalysisDimensionMatrix(value.metricAnalysisDimensionMatrix.map { MetricAnalysisDimensionCapabilityToProto(it) })
    addAllDisabledReasons(value.disabledReasons.map { DisabledReasonToProto(it) })
    addAllDiagnostics(value.diagnostics.map { DiagnosticIssueRefToProto(it) })
    setExecutionTypes(QueryExecutionTypesToProto(value.executionTypes))
    setNativeDefinitions(QueryNativeDefinitionsToProto(value.nativeDefinitions))
    value.capabilityHash?.let { setCapabilityHash(it) }
}.build()
internal fun QueryCapabilityViewFromProto(value: com.olap.prototype.wire.governance.QueryCapabilityView): com.olap.prototype.query.contract.QueryCapabilityView = com.olap.prototype.query.contract.QueryCapabilityView(
    scenario = required(value.hasScenario(), "QueryCapabilityView.scenario") { QueryScenarioRefFromProto(value.getScenario()) },
    subjectAreaCapabilityContext = required(value.hasSubjectAreaCapabilityContext(), "QueryCapabilityView.subjectAreaCapabilityContext") { SubjectAreaCapabilityContextFromProto(value.getSubjectAreaCapabilityContext()) },
    releaseId = value.getReleaseId(),
    releaseVersion = value.getReleaseVersion(),
    releaseDisplayName = value.getReleaseDisplayName(),
    metricCandidates = value.getMetricCandidatesList().map { MetricCandidateFromProto(it) },
    memberCandidates = value.getMemberCandidatesList().map { MemberCandidateFromProto(it) },
    analysisDimensionCandidates = value.getAnalysisDimensionCandidatesList().map { AnalysisDimensionCandidateFromProto(it) },
    timeAnalysisDimensionCandidates = value.getTimeAnalysisDimensionCandidatesList().map { TimeAnalysisDimensionCandidateFromProto(it) },
    filterCandidates = value.getFilterCandidatesList().map { FilterCandidateFromProto(it) },
    relationFilterCandidates = value.getRelationFilterCandidatesList().map { RelationFilterCandidateFromProto(it) },
    temporalClockCandidates = value.getTemporalClockCandidatesList().map { TemporalClockCandidateFromProto(it) },
    postProcessCapabilities = value.getPostProcessCapabilitiesList().map { PostProcessCapabilityFromProto(it) },
    metricAnalysisDimensionMatrix = value.getMetricAnalysisDimensionMatrixList().map { MetricAnalysisDimensionCapabilityFromProto(it) },
    disabledReasons = value.getDisabledReasonsList().map { DisabledReasonFromProto(it) },
    diagnostics = value.getDiagnosticsList().map { DiagnosticIssueRefFromProto(it) },
    executionTypes = required(value.hasExecutionTypes(), "QueryCapabilityView.executionTypes") { QueryExecutionTypesFromProto(value.getExecutionTypes()) },
    nativeDefinitions = required(value.hasNativeDefinitions(), "QueryCapabilityView.nativeDefinitions") { QueryNativeDefinitionsFromProto(value.getNativeDefinitions()) },
    capabilityHash = if (value.hasCapabilityHash()) value.getCapabilityHash() else null
)

internal fun QueryScenarioRefToProto(value: com.olap.prototype.query.contract.QueryScenarioRef): com.olap.prototype.wire.governance.QueryScenarioRef = com.olap.prototype.wire.governance.QueryScenarioRef.newBuilder().apply {
    setScenarioId(value.scenarioId)
    setScenarioVersion(value.scenarioVersion)
    setScenarioDisplayName(value.scenarioDisplayName)
    setReleaseId(value.releaseId)
    setReleaseVersion(value.releaseVersion)
    setReleaseDisplayName(value.releaseDisplayName)
}.build()
internal fun QueryScenarioRefFromProto(value: com.olap.prototype.wire.governance.QueryScenarioRef): com.olap.prototype.query.contract.QueryScenarioRef = com.olap.prototype.query.contract.QueryScenarioRef(
    scenarioId = value.getScenarioId(),
    scenarioVersion = value.getScenarioVersion(),
    scenarioDisplayName = value.getScenarioDisplayName(),
    releaseId = value.getReleaseId(),
    releaseVersion = value.getReleaseVersion(),
    releaseDisplayName = value.getReleaseDisplayName()
)

internal fun SubjectAreaCapabilityContextToProto(value: com.olap.prototype.query.contract.SubjectAreaCapabilityContext): com.olap.prototype.wire.governance.SubjectAreaCapabilityContext = com.olap.prototype.wire.governance.SubjectAreaCapabilityContext.newBuilder().apply {
    setSubjectAreaId(value.subjectAreaId)
    setSubjectAreaVersion(value.subjectAreaVersion)
    setSubjectAreaScenarioRefId(value.subjectAreaScenarioRefId)
    setSubjectAreaScenarioRefStatus(value.subjectAreaScenarioRefStatus)
}.build()
internal fun SubjectAreaCapabilityContextFromProto(value: com.olap.prototype.wire.governance.SubjectAreaCapabilityContext): com.olap.prototype.query.contract.SubjectAreaCapabilityContext = com.olap.prototype.query.contract.SubjectAreaCapabilityContext(
    subjectAreaId = value.getSubjectAreaId(),
    subjectAreaVersion = value.getSubjectAreaVersion(),
    subjectAreaScenarioRefId = value.getSubjectAreaScenarioRefId(),
    subjectAreaScenarioRefStatus = value.getSubjectAreaScenarioRefStatus()
)

internal fun MetricCandidateToProto(value: com.olap.prototype.query.contract.MetricCandidate): com.olap.prototype.wire.governance.MetricCandidate = com.olap.prototype.wire.governance.MetricCandidate.newBuilder().apply {
    setAdoptionId(value.adoptionId)
    setAdoptionVersion(value.adoptionVersion)
    setSubjectAreaId(value.subjectAreaId)
    setSubjectAreaVersion(value.subjectAreaVersion)
    setCode(value.code)
    setDisplayName(value.displayName)
    setBusinessMeaning(SemanticProto.toProto(value.businessMeaning))
    setDefinition(SemanticProto.toProto(value.definition))
    setInstance(SemanticProto.toProto(value.instance))
    setValueContract(SemanticProto.toProto(value.valueContract))
    value.snapshotMeaning?.let { setSnapshotMeaning(MetricSnapshotRuleMeaningToProto(it)) }
    setAdoptionStatus(value.adoptionStatus)
    setScenarioMetricRefId(value.scenarioMetricRefId)
    setReleaseObjectRefId(value.releaseObjectRefId)
    addAllSources(value.sources.map { MetricSourceToProto(it) })
    setDefinitionKind(value.definitionKind)
    addAllFixedArguments(value.fixedArguments.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.MetricCandidateFixedArgumentsEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    addAllParameters(value.parameters.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.MetricCandidateParametersEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    addAllGroupingCandidates(value.groupingCandidates.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.MetricCandidateGroupingCandidatesEntry.newBuilder().setKey(entry.key).apply { setValue(com.olap.prototype.wire.governance.ListScopedInputContractDimensionRef.newBuilder().addAllValues(entry.value.map { SemanticProto.toProto(it) }).build()) }.build() })
    addAllMemberParameters(value.memberParameters.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.MetricCandidateMemberParametersEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    addAllRelationParameters(value.relationParameters.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.MetricCandidateRelationParametersEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    setRequiredByDefault(value.requiredByDefault)
    setSortOrder(value.sortOrder)
}.build()
internal fun MetricCandidateFromProto(value: com.olap.prototype.wire.governance.MetricCandidate): com.olap.prototype.query.contract.MetricCandidate = com.olap.prototype.query.contract.MetricCandidate(
    adoptionId = value.getAdoptionId(),
    adoptionVersion = value.getAdoptionVersion(),
    subjectAreaId = value.getSubjectAreaId(),
    subjectAreaVersion = value.getSubjectAreaVersion(),
    code = value.getCode(),
    displayName = value.getDisplayName(),
    businessMeaning = required(value.hasBusinessMeaning(), "MetricCandidate.businessMeaning") { SemanticProto.fromProto(value.getBusinessMeaning()) as com.olap.prototype.metric.MetricBusinessMeaning },
    definition = required(value.hasDefinition(), "MetricCandidate.definition") { SemanticProto.fromProto(value.getDefinition()) as com.olap.prototype.calculation.MetricRef },
    instance = required(value.hasInstance(), "MetricCandidate.instance") { SemanticProto.fromProto(value.getInstance()) as com.olap.prototype.calculation.MetricInstanceRef },
    valueContract = required(value.hasValueContract(), "MetricCandidate.valueContract") { SemanticProto.fromProto(value.getValueContract()) as com.olap.prototype.metric.MetricValueContract },
    snapshotMeaning = if (value.hasSnapshotMeaning()) MetricSnapshotRuleMeaningFromProto(value.getSnapshotMeaning()) else null,
    adoptionStatus = value.getAdoptionStatus(),
    scenarioMetricRefId = value.getScenarioMetricRefId(),
    releaseObjectRefId = value.getReleaseObjectRefId(),
    sources = value.getSourcesList().map { MetricSourceFromProto(it) },
    definitionKind = value.getDefinitionKind(),
    fixedArguments = strictMap(value.getFixedArgumentsList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.Value }),
    parameters = strictMap(value.getParametersList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.Parameter }),
    groupingCandidates = strictMap(value.getGroupingCandidatesList().map { it.getKey() to it.getValue().getValuesList().map { SemanticProto.fromProto(it) as com.olap.prototype.query.ScopedInputContract.DimensionRef } }),
    memberParameters = strictMap(value.getMemberParametersList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.MemberSlot }),
    relationParameters = strictMap(value.getRelationParametersList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.RelationSlot }),
    requiredByDefault = value.getRequiredByDefault(),
    sortOrder = value.getSortOrder()
)

internal fun AnalysisDimensionCandidateToProto(value: com.olap.prototype.query.contract.AnalysisDimensionCandidate): com.olap.prototype.wire.governance.AnalysisDimensionCandidate = com.olap.prototype.wire.governance.AnalysisDimensionCandidate.newBuilder().apply {
    setAnalysisDimensionId(value.analysisDimensionId)
    setAnalysisDimensionVersion(value.analysisDimensionVersion)
    setAnalysisDimensionDisplayName(value.analysisDimensionDisplayName)
    setOutputAlias(value.outputAlias)
    setSubjectAreaAnalysisDimensionRefId(value.subjectAreaAnalysisDimensionRefId)
    setSubjectAreaAnalysisDimensionRefStatus(value.subjectAreaAnalysisDimensionRefStatus)
    setScenarioAnalysisDimensionRefId(value.scenarioAnalysisDimensionRefId)
    setReleaseObjectRefId(value.releaseObjectRefId)
    setDefaultLevelCode(value.defaultLevelCode)
    setDefaultLevelDisplayName(value.defaultLevelDisplayName)
    addAllAvailableLevelCodes(value.availableLevelCodes.map { it })
    addAllEventMappings(value.eventMappings.map { AnalysisDimensionEventMappingCandidateToProto(it) })
    setRequiredByDefault(value.requiredByDefault)
    setSortOrder(value.sortOrder)
}.build()
internal fun AnalysisDimensionCandidateFromProto(value: com.olap.prototype.wire.governance.AnalysisDimensionCandidate): com.olap.prototype.query.contract.AnalysisDimensionCandidate = com.olap.prototype.query.contract.AnalysisDimensionCandidate(
    analysisDimensionId = value.getAnalysisDimensionId(),
    analysisDimensionVersion = value.getAnalysisDimensionVersion(),
    analysisDimensionDisplayName = value.getAnalysisDimensionDisplayName(),
    outputAlias = value.getOutputAlias(),
    subjectAreaAnalysisDimensionRefId = value.getSubjectAreaAnalysisDimensionRefId(),
    subjectAreaAnalysisDimensionRefStatus = value.getSubjectAreaAnalysisDimensionRefStatus(),
    scenarioAnalysisDimensionRefId = value.getScenarioAnalysisDimensionRefId(),
    releaseObjectRefId = value.getReleaseObjectRefId(),
    defaultLevelCode = value.getDefaultLevelCode(),
    defaultLevelDisplayName = value.getDefaultLevelDisplayName(),
    availableLevelCodes = value.getAvailableLevelCodesList().map { it },
    eventMappings = value.getEventMappingsList().map { AnalysisDimensionEventMappingCandidateFromProto(it) },
    requiredByDefault = value.getRequiredByDefault(),
    sortOrder = value.getSortOrder()
)

internal fun AnalysisDimensionEventMappingCandidateToProto(value: com.olap.prototype.query.contract.AnalysisDimensionEventMappingCandidate): com.olap.prototype.wire.governance.AnalysisDimensionEventMappingCandidate = com.olap.prototype.wire.governance.AnalysisDimensionEventMappingCandidate.newBuilder().apply {
    setMappingId(value.mappingId)
    setAnalysisDimensionId(value.analysisDimensionId)
    setAnalysisDimensionVersion(value.analysisDimensionVersion)
    setLevelCode(value.levelCode)
    setLevelDisplayName(value.levelDisplayName)
    setEventId(value.eventId)
    setEventVersion(value.eventVersion)
    setEventDisplayName(value.eventDisplayName)
    setDimensionId(value.dimensionId)
    setDimensionVersion(value.dimensionVersion)
    setDimensionCodeNamespace(value.dimensionCodeNamespace)
    setDimensionCodeNamespaceDisplayName(value.dimensionCodeNamespaceDisplayName)
    setDimensionDisplayName(value.dimensionDisplayName)
    setDimensionBindingId(value.dimensionBindingId)
    setDimensionBindingVersion(value.dimensionBindingVersion)
    setLogicUnitId(value.logicUnitId)
    setLogicUnitVersion(value.logicUnitVersion)
    setLogicUnitDisplayName(value.logicUnitDisplayName)
    value.valueAlignmentKey?.let { setValueAlignmentKey(it) }
    setValueAlignmentStatus(value.valueAlignmentStatus)
}.build()
internal fun AnalysisDimensionEventMappingCandidateFromProto(value: com.olap.prototype.wire.governance.AnalysisDimensionEventMappingCandidate): com.olap.prototype.query.contract.AnalysisDimensionEventMappingCandidate = com.olap.prototype.query.contract.AnalysisDimensionEventMappingCandidate(
    mappingId = value.getMappingId(),
    analysisDimensionId = value.getAnalysisDimensionId(),
    analysisDimensionVersion = value.getAnalysisDimensionVersion(),
    levelCode = value.getLevelCode(),
    levelDisplayName = value.getLevelDisplayName(),
    eventId = value.getEventId(),
    eventVersion = value.getEventVersion(),
    eventDisplayName = value.getEventDisplayName(),
    dimensionId = value.getDimensionId(),
    dimensionVersion = value.getDimensionVersion(),
    dimensionCodeNamespace = value.getDimensionCodeNamespace(),
    dimensionCodeNamespaceDisplayName = value.getDimensionCodeNamespaceDisplayName(),
    dimensionDisplayName = value.getDimensionDisplayName(),
    dimensionBindingId = value.getDimensionBindingId(),
    dimensionBindingVersion = value.getDimensionBindingVersion(),
    logicUnitId = value.getLogicUnitId(),
    logicUnitVersion = value.getLogicUnitVersion(),
    logicUnitDisplayName = value.getLogicUnitDisplayName(),
    valueAlignmentKey = if (value.hasValueAlignmentKey()) value.getValueAlignmentKey() else null,
    valueAlignmentStatus = value.getValueAlignmentStatus()
)

internal fun TimeAnalysisDimensionCandidateToProto(value: com.olap.prototype.query.contract.TimeAnalysisDimensionCandidate): com.olap.prototype.wire.governance.TimeAnalysisDimensionCandidate = com.olap.prototype.wire.governance.TimeAnalysisDimensionCandidate.newBuilder().apply {
    setAnalysisDimensionId(value.analysisDimensionId)
    setAnalysisDimensionVersion(value.analysisDimensionVersion)
    setAnalysisDimensionDisplayName(value.analysisDimensionDisplayName)
    setOutputAlias(value.outputAlias)
    setScenarioAnalysisDimensionRefId(value.scenarioAnalysisDimensionRefId)
    setReleaseObjectRefId(value.releaseObjectRefId)
    setDefaultLevelCode(value.defaultLevelCode)
    setDefaultLevelDisplayName(value.defaultLevelDisplayName)
    addAllAvailableLevelCodes(value.availableLevelCodes.map { it })
    addAllEventAnchors(value.eventAnchors.map { TimeAnchorEventCandidateToProto(it) })
    addAllMetricEventCapabilities(value.metricEventCapabilities.map { MetricAnalysisDimensionCapabilityToProto(it) })
    setRequiredByDefault(value.requiredByDefault)
    setSortOrder(value.sortOrder)
}.build()
internal fun TimeAnalysisDimensionCandidateFromProto(value: com.olap.prototype.wire.governance.TimeAnalysisDimensionCandidate): com.olap.prototype.query.contract.TimeAnalysisDimensionCandidate = com.olap.prototype.query.contract.TimeAnalysisDimensionCandidate(
    analysisDimensionId = value.getAnalysisDimensionId(),
    analysisDimensionVersion = value.getAnalysisDimensionVersion(),
    analysisDimensionDisplayName = value.getAnalysisDimensionDisplayName(),
    outputAlias = value.getOutputAlias(),
    scenarioAnalysisDimensionRefId = value.getScenarioAnalysisDimensionRefId(),
    releaseObjectRefId = value.getReleaseObjectRefId(),
    defaultLevelCode = value.getDefaultLevelCode(),
    defaultLevelDisplayName = value.getDefaultLevelDisplayName(),
    availableLevelCodes = value.getAvailableLevelCodesList().map { it },
    eventAnchors = value.getEventAnchorsList().map { TimeAnchorEventCandidateFromProto(it) },
    metricEventCapabilities = value.getMetricEventCapabilitiesList().map { MetricAnalysisDimensionCapabilityFromProto(it) },
    requiredByDefault = value.getRequiredByDefault(),
    sortOrder = value.getSortOrder()
)

internal fun TimeAnchorEventCandidateToProto(value: com.olap.prototype.query.contract.TimeAnchorEventCandidate): com.olap.prototype.wire.governance.TimeAnchorEventCandidate = com.olap.prototype.wire.governance.TimeAnchorEventCandidate.newBuilder().apply {
    setMappingId(value.mappingId)
    setAnalysisDimensionId(value.analysisDimensionId)
    setAnalysisDimensionVersion(value.analysisDimensionVersion)
    setLevelCode(value.levelCode)
    setLevelDisplayName(value.levelDisplayName)
    setEventId(value.eventId)
    setEventVersion(value.eventVersion)
    setEventDisplayName(value.eventDisplayName)
    setTopologyId(value.topologyId)
    setTopologyVersion(value.topologyVersion)
    setTopologyImplementationNamespaceCode(value.topologyImplementationNamespaceCode)
    setTopologyImplementationNamespaceDisplayName(value.topologyImplementationNamespaceDisplayName)
    setEffectiveTimeLogicUnitId(value.effectiveTimeLogicUnitId)
    setEffectiveTimeLogicUnitVersion(value.effectiveTimeLogicUnitVersion)
    setEffectiveTimeLogicUnitDisplayName(value.effectiveTimeLogicUnitDisplayName)
    setTimeRole(value.timeRole)
}.build()
internal fun TimeAnchorEventCandidateFromProto(value: com.olap.prototype.wire.governance.TimeAnchorEventCandidate): com.olap.prototype.query.contract.TimeAnchorEventCandidate = com.olap.prototype.query.contract.TimeAnchorEventCandidate(
    mappingId = value.getMappingId(),
    analysisDimensionId = value.getAnalysisDimensionId(),
    analysisDimensionVersion = value.getAnalysisDimensionVersion(),
    levelCode = value.getLevelCode(),
    levelDisplayName = value.getLevelDisplayName(),
    eventId = value.getEventId(),
    eventVersion = value.getEventVersion(),
    eventDisplayName = value.getEventDisplayName(),
    topologyId = value.getTopologyId(),
    topologyVersion = value.getTopologyVersion(),
    topologyImplementationNamespaceCode = value.getTopologyImplementationNamespaceCode(),
    topologyImplementationNamespaceDisplayName = value.getTopologyImplementationNamespaceDisplayName(),
    effectiveTimeLogicUnitId = value.getEffectiveTimeLogicUnitId(),
    effectiveTimeLogicUnitVersion = value.getEffectiveTimeLogicUnitVersion(),
    effectiveTimeLogicUnitDisplayName = value.getEffectiveTimeLogicUnitDisplayName(),
    timeRole = value.getTimeRole()
)

internal fun MetricAnalysisDimensionCapabilityToProto(value: com.olap.prototype.query.contract.MetricAnalysisDimensionCapability): com.olap.prototype.wire.governance.MetricAnalysisDimensionCapability = com.olap.prototype.wire.governance.MetricAnalysisDimensionCapability.newBuilder().apply {
    setInstance(SemanticProto.toProto(value.instance))
    setEventId(value.eventId)
    setEventVersion(value.eventVersion)
    setAnalysisDimensionId(value.analysisDimensionId)
    setAnalysisDimensionVersion(value.analysisDimensionVersion)
    value.levelCode?.let { setLevelCode(it) }
    setAvailable(value.available)
    value.reasonCode?.let { setReasonCode(it) }
    value.reasonMessage?.let { setReasonMessage(it) }
    value.producerStage?.let { setProducerStage(it) }
    value.targetRef?.let { setTargetRef(it) }
    value.targetVersionRef?.let { setTargetVersionRef(it) }
    setBlocking(value.blocking)
    setPageBlocking(value.pageBlocking)
    setExecutableBlocking(value.executableBlocking)
}.build()
internal fun MetricAnalysisDimensionCapabilityFromProto(value: com.olap.prototype.wire.governance.MetricAnalysisDimensionCapability): com.olap.prototype.query.contract.MetricAnalysisDimensionCapability = com.olap.prototype.query.contract.MetricAnalysisDimensionCapability(
    instance = required(value.hasInstance(), "MetricAnalysisDimensionCapability.instance") { SemanticProto.fromProto(value.getInstance()) as com.olap.prototype.calculation.MetricInstanceRef },
    eventId = value.getEventId(),
    eventVersion = value.getEventVersion(),
    analysisDimensionId = value.getAnalysisDimensionId(),
    analysisDimensionVersion = value.getAnalysisDimensionVersion(),
    levelCode = if (value.hasLevelCode()) value.getLevelCode() else null,
    available = value.getAvailable(),
    reasonCode = if (value.hasReasonCode()) value.getReasonCode() else null,
    reasonMessage = if (value.hasReasonMessage()) value.getReasonMessage() else null,
    producerStage = if (value.hasProducerStage()) value.getProducerStage() else null,
    targetRef = if (value.hasTargetRef()) value.getTargetRef() else null,
    targetVersionRef = if (value.hasTargetVersionRef()) value.getTargetVersionRef() else null,
    blocking = value.getBlocking(),
    pageBlocking = value.getPageBlocking(),
    executableBlocking = value.getExecutableBlocking()
)

internal fun FilterCandidateToProto(value: com.olap.prototype.query.contract.FilterCandidate): com.olap.prototype.wire.governance.FilterCandidate = com.olap.prototype.wire.governance.FilterCandidate.newBuilder().apply {
    setFilterRefId(value.filterRefId)
    setFilterTargetType(value.filterTargetType)
    setFilterTargetTypeDisplayName(value.filterTargetTypeDisplayName)
    setQueryFieldKind(value.queryFieldKind)
    setFilterTargetObjectId(value.filterTargetObjectId)
    setFilterTargetObjectVersion(value.filterTargetObjectVersion)
    setFilterTargetDisplayName(value.filterTargetDisplayName)
    setReleaseObjectRefId(value.releaseObjectRefId)
    addAllOperators(value.operators.map { it })
    addAllFilterValueTypes(value.filterValueTypes.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.FilterCandidateFilterValueTypesEntry.newBuilder().setKey(entry.key).apply { setValue(entry.value) }.build() })
    setFilterControlKind(value.filterControlKind)
    setFilterValueCardinality(value.filterValueCardinality)
    value.valueProviderId?.let { setValueProviderId(it) }
    value.valueProviderType?.let { setValueProviderType(it) }
    value.valueProviderLogicUnitId?.let { setValueProviderLogicUnitId(it) }
    value.valueProviderLogicUnitVersion?.let { setValueProviderLogicUnitVersion(it) }
    addAllEventMappings(value.eventMappings.map { AnalysisDimensionEventMappingCandidateToProto(it) })
    setRequiredByDefault(value.requiredByDefault)
    setSortOrder(value.sortOrder)
}.build()
internal fun FilterCandidateFromProto(value: com.olap.prototype.wire.governance.FilterCandidate): com.olap.prototype.query.contract.FilterCandidate = com.olap.prototype.query.contract.FilterCandidate(
    filterRefId = value.getFilterRefId(),
    filterTargetType = value.getFilterTargetType(),
    filterTargetTypeDisplayName = value.getFilterTargetTypeDisplayName(),
    queryFieldKind = value.getQueryFieldKind(),
    filterTargetObjectId = value.getFilterTargetObjectId(),
    filterTargetObjectVersion = value.getFilterTargetObjectVersion(),
    filterTargetDisplayName = value.getFilterTargetDisplayName(),
    releaseObjectRefId = value.getReleaseObjectRefId(),
    operators = value.getOperatorsList().map { it },
    filterValueTypes = strictMap(value.getFilterValueTypesList().map { it.getKey() to it.getValue() }),
    filterControlKind = value.getFilterControlKind(),
    filterValueCardinality = value.getFilterValueCardinality(),
    valueProviderId = if (value.hasValueProviderId()) value.getValueProviderId() else null,
    valueProviderType = if (value.hasValueProviderType()) value.getValueProviderType() else null,
    valueProviderLogicUnitId = if (value.hasValueProviderLogicUnitId()) value.getValueProviderLogicUnitId() else null,
    valueProviderLogicUnitVersion = if (value.hasValueProviderLogicUnitVersion()) value.getValueProviderLogicUnitVersion() else null,
    eventMappings = value.getEventMappingsList().map { AnalysisDimensionEventMappingCandidateFromProto(it) },
    requiredByDefault = value.getRequiredByDefault(),
    sortOrder = value.getSortOrder()
)

internal fun PublishedRelationFilterToProto(value: com.olap.prototype.query.contract.PublishedRelationFilter): com.olap.prototype.wire.governance.PublishedRelationFilter = com.olap.prototype.wire.governance.PublishedRelationFilter.newBuilder().apply {
    setRelation(RelationFilterToProto(value.relation))
    setScenarioId(value.scenarioId)
    setDisplayName(value.displayName)
    setPermission(RelationFilterPermissionToProto(value.permission))
    setRequiredByDefault(value.requiredByDefault)
    setSortOrder(value.sortOrder)
}.build()
internal fun PublishedRelationFilterFromProto(value: com.olap.prototype.wire.governance.PublishedRelationFilter): com.olap.prototype.query.contract.PublishedRelationFilter = com.olap.prototype.query.contract.PublishedRelationFilter(
    relation = required(value.hasRelation(), "PublishedRelationFilter.relation") { RelationFilterFromProto(value.getRelation()) },
    scenarioId = value.getScenarioId(),
    displayName = value.getDisplayName(),
    permission = required(value.hasPermission(), "PublishedRelationFilter.permission") { RelationFilterPermissionFromProto(value.getPermission()) },
    requiredByDefault = value.getRequiredByDefault(),
    sortOrder = value.getSortOrder()
)

internal fun TemporalClockCandidateToProto(value: com.olap.prototype.query.contract.TemporalClockCandidate): com.olap.prototype.wire.governance.TemporalClockCandidate = com.olap.prototype.wire.governance.TemporalClockCandidate.newBuilder().apply {
    setClockRef(value.clockRef)
    setValueType(value.valueType.name)
    setDescription(value.description)
}.build()
internal fun TemporalClockCandidateFromProto(value: com.olap.prototype.wire.governance.TemporalClockCandidate): com.olap.prototype.query.contract.TemporalClockCandidate = com.olap.prototype.query.contract.TemporalClockCandidate(
    clockRef = value.getClockRef(),
    valueType = com.olap.prototype.time.TemporalWindow.ValueType.valueOf(value.getValueType()),
    description = value.getDescription()
)

internal fun DisabledReasonToProto(value: com.olap.prototype.query.contract.DisabledReason): com.olap.prototype.wire.governance.DisabledReason = com.olap.prototype.wire.governance.DisabledReason.newBuilder().apply {
    setId(value.id)
    setReleaseId(value.releaseId)
    setReleaseVersion(value.releaseVersion)
    setScopeType(value.scopeType)
    value.scenarioId?.let { setScenarioId(it) }
    value.scenarioVersion?.let { setScenarioVersion(it) }
    value.metricId?.let { setMetricId(it) }
    value.metricVersion?.let { setMetricVersion(it) }
    value.analysisDimensionId?.let { setAnalysisDimensionId(it) }
    value.analysisDimensionVersion?.let { setAnalysisDimensionVersion(it) }
    value.levelCode?.let { setLevelCode(it) }
    setReasonCode(value.reasonCode)
    setReasonMessage(value.reasonMessage)
    setProducerStage(value.producerStage)
    setTargetRef(value.targetRef)
    setTargetVersionRef(value.targetVersionRef)
    setBlocking(value.blocking)
    setPageBlocking(value.pageBlocking)
    setExecutableBlocking(value.executableBlocking)
}.build()
internal fun DisabledReasonFromProto(value: com.olap.prototype.wire.governance.DisabledReason): com.olap.prototype.query.contract.DisabledReason = com.olap.prototype.query.contract.DisabledReason(
    id = value.getId(),
    releaseId = value.getReleaseId(),
    releaseVersion = value.getReleaseVersion(),
    scopeType = value.getScopeType(),
    scenarioId = if (value.hasScenarioId()) value.getScenarioId() else null,
    scenarioVersion = if (value.hasScenarioVersion()) value.getScenarioVersion() else null,
    metricId = if (value.hasMetricId()) value.getMetricId() else null,
    metricVersion = if (value.hasMetricVersion()) value.getMetricVersion() else null,
    analysisDimensionId = if (value.hasAnalysisDimensionId()) value.getAnalysisDimensionId() else null,
    analysisDimensionVersion = if (value.hasAnalysisDimensionVersion()) value.getAnalysisDimensionVersion() else null,
    levelCode = if (value.hasLevelCode()) value.getLevelCode() else null,
    reasonCode = value.getReasonCode(),
    reasonMessage = value.getReasonMessage(),
    producerStage = value.getProducerStage(),
    targetRef = value.getTargetRef(),
    targetVersionRef = value.getTargetVersionRef(),
    blocking = value.getBlocking(),
    pageBlocking = value.getPageBlocking(),
    executableBlocking = value.getExecutableBlocking()
)

internal fun DiagnosticIssueRefToProto(value: com.olap.prototype.query.contract.DiagnosticIssueRef): com.olap.prototype.wire.governance.DiagnosticIssueRef = com.olap.prototype.wire.governance.DiagnosticIssueRef.newBuilder().apply {
    setDiagnosticRunId(value.diagnosticRunId)
    setIssueId(value.issueId)
    setSeverity(value.severity)
    setReasonCode(value.reasonCode)
    setReasonMessage(value.reasonMessage)
    setProducerStage(value.producerStage)
    setTargetRef(value.targetRef)
    setTargetVersionRef(value.targetVersionRef)
    setBlocking(value.blocking)
    setPageBlocking(value.pageBlocking)
    setExecutableBlocking(value.executableBlocking)
}.build()
internal fun DiagnosticIssueRefFromProto(value: com.olap.prototype.wire.governance.DiagnosticIssueRef): com.olap.prototype.query.contract.DiagnosticIssueRef = com.olap.prototype.query.contract.DiagnosticIssueRef(
    diagnosticRunId = value.getDiagnosticRunId(),
    issueId = value.getIssueId(),
    severity = value.getSeverity(),
    reasonCode = value.getReasonCode(),
    reasonMessage = value.getReasonMessage(),
    producerStage = value.getProducerStage(),
    targetRef = value.getTargetRef(),
    targetVersionRef = value.getTargetVersionRef(),
    blocking = value.getBlocking(),
    pageBlocking = value.getPageBlocking(),
    executableBlocking = value.getExecutableBlocking()
)

internal fun QueryExecutionTypesToProto(value: com.olap.prototype.query.contract.QueryExecutionTypes): com.olap.prototype.wire.governance.QueryExecutionTypes = com.olap.prototype.wire.governance.QueryExecutionTypes.newBuilder().apply {
    setFormatVersion(value.formatVersion)
    addAllMetricInstances(value.metricInstances.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.QueryExecutionTypesMetricInstancesEntry.newBuilder().setKey(entry.key).apply { setValue(QueryExecutionTypesContextBindingToProto(entry.value)) }.build() })
    addAllMembers(value.members.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.QueryExecutionTypesMembersEntry.newBuilder().setKey(entry.key).apply { setValue(QueryExecutionTypesMemberToProto(entry.value)) }.build() })
    addAllDimensions(value.dimensions.map { QueryExecutionTypesDimensionToProto(it) })
}.build()
internal fun QueryExecutionTypesFromProto(value: com.olap.prototype.wire.governance.QueryExecutionTypes): com.olap.prototype.query.contract.QueryExecutionTypes = com.olap.prototype.query.contract.QueryExecutionTypes(
    formatVersion = value.getFormatVersion(),
    metricInstances = strictMap(value.getMetricInstancesList().map { it.getKey() to QueryExecutionTypesContextBindingFromProto(it.getValue()) }),
    members = strictMap(value.getMembersList().map { it.getKey() to QueryExecutionTypesMemberFromProto(it.getValue()) }),
    dimensions = value.getDimensionsList().map { QueryExecutionTypesDimensionFromProto(it) }
)

internal fun QueryExecutionTypesContextBindingToProto(value: com.olap.prototype.query.contract.QueryExecutionTypes.ContextBinding): com.olap.prototype.wire.governance.QueryExecutionTypesContextBinding = com.olap.prototype.wire.governance.QueryExecutionTypesContextBinding.newBuilder().apply { when (value) {
    is com.olap.prototype.query.contract.QueryExecutionTypes.FixedContext -> setFixedContext(QueryExecutionTypesFixedContextToProto(value))
    is com.olap.prototype.query.contract.QueryExecutionTypes.Inputs -> setInputs(QueryExecutionTypesInputsToProto(value))
} }.build()
internal fun QueryExecutionTypesContextBindingFromProto(value: com.olap.prototype.wire.governance.QueryExecutionTypesContextBinding): com.olap.prototype.query.contract.QueryExecutionTypes.ContextBinding = when (value.kindCase) {
    com.olap.prototype.wire.governance.QueryExecutionTypesContextBinding.KindCase.FIXED_CONTEXT -> QueryExecutionTypesFixedContextFromProto(value.getFixedContext())
    com.olap.prototype.wire.governance.QueryExecutionTypesContextBinding.KindCase.INPUTS -> QueryExecutionTypesInputsFromProto(value.getInputs())
    com.olap.prototype.wire.governance.QueryExecutionTypesContextBinding.KindCase.KIND_NOT_SET -> error("QueryExecutionTypesContextBinding variant is required")
}

internal fun QueryExecutionTypesFixedContextToProto(value: com.olap.prototype.query.contract.QueryExecutionTypes.FixedContext): com.olap.prototype.wire.governance.QueryExecutionTypesFixedContext = com.olap.prototype.wire.governance.QueryExecutionTypesFixedContext.newBuilder().apply {
    setContext(QueryExecutionTypesContextToProto(value.context))
}.build()
internal fun QueryExecutionTypesFixedContextFromProto(value: com.olap.prototype.wire.governance.QueryExecutionTypesFixedContext): com.olap.prototype.query.contract.QueryExecutionTypes.FixedContext = com.olap.prototype.query.contract.QueryExecutionTypes.FixedContext(
    context = required(value.hasContext(), "QueryExecutionTypesFixedContext.context") { QueryExecutionTypesContextFromProto(value.getContext()) }
)

internal fun QueryExecutionTypesContextToProto(value: com.olap.prototype.query.contract.QueryExecutionTypes.Context): com.olap.prototype.wire.governance.QueryExecutionTypesContext = com.olap.prototype.wire.governance.QueryExecutionTypesContext.newBuilder().apply {
    setExecutionGroup(value.executionGroup)
    setDialect(value.dialect)
    setConstraintSnapshotAvailable(value.constraintSnapshotAvailable)
}.build()
internal fun QueryExecutionTypesContextFromProto(value: com.olap.prototype.wire.governance.QueryExecutionTypesContext): com.olap.prototype.query.contract.QueryExecutionTypes.Context = com.olap.prototype.query.contract.QueryExecutionTypes.Context(
    executionGroup = value.getExecutionGroup(),
    dialect = value.getDialect(),
    constraintSnapshotAvailable = value.getConstraintSnapshotAvailable()
)

internal fun QueryExecutionTypesInputsToProto(value: com.olap.prototype.query.contract.QueryExecutionTypes.Inputs): com.olap.prototype.wire.governance.QueryExecutionTypesInputs = com.olap.prototype.wire.governance.QueryExecutionTypesInputs.newBuilder().apply {
    addAllMemberParameters(value.memberParameters.map { it }.sorted())
    addAllRelationParameters(value.relationParameters.map { it }.sorted())
}.build()
internal fun QueryExecutionTypesInputsFromProto(value: com.olap.prototype.wire.governance.QueryExecutionTypesInputs): com.olap.prototype.query.contract.QueryExecutionTypes.Inputs = com.olap.prototype.query.contract.QueryExecutionTypes.Inputs(
    memberParameters = strictSet(value.getMemberParametersList().map { it }),
    relationParameters = strictSet(value.getRelationParametersList().map { it })
)

internal fun QueryExecutionTypesProjectionMemberToProto(value: com.olap.prototype.query.contract.QueryExecutionTypes.ProjectionMember): com.olap.prototype.wire.governance.QueryExecutionTypesProjectionMember = com.olap.prototype.wire.governance.QueryExecutionTypesProjectionMember.newBuilder().apply {
    setContext(QueryExecutionTypesContextToProto(value.context))
    addAllOutputs(value.outputs.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.QueryExecutionTypesProjectionMemberOutputsEntry.newBuilder().setKey(entry.key).apply { setValue(NativeValueTypeToProto(entry.value)) }.build() })
}.build()
internal fun QueryExecutionTypesProjectionMemberFromProto(value: com.olap.prototype.wire.governance.QueryExecutionTypesProjectionMember): com.olap.prototype.query.contract.QueryExecutionTypes.ProjectionMember = com.olap.prototype.query.contract.QueryExecutionTypes.ProjectionMember(
    context = required(value.hasContext(), "QueryExecutionTypesProjectionMember.context") { QueryExecutionTypesContextFromProto(value.getContext()) },
    outputs = strictMap(value.getOutputsList().map { it.getKey() to NativeValueTypeFromProto(it.getValue()) })
)

internal fun QueryExecutionTypesComputedMemberToProto(value: com.olap.prototype.query.contract.QueryExecutionTypes.ComputedMember): com.olap.prototype.wire.governance.QueryExecutionTypesComputedMember = com.olap.prototype.wire.governance.QueryExecutionTypesComputedMember.newBuilder().apply {
    setContext(QueryExecutionTypesContextBindingToProto(value.context))
}.build()
internal fun QueryExecutionTypesComputedMemberFromProto(value: com.olap.prototype.wire.governance.QueryExecutionTypesComputedMember): com.olap.prototype.query.contract.QueryExecutionTypes.ComputedMember = com.olap.prototype.query.contract.QueryExecutionTypes.ComputedMember(
    context = required(value.hasContext(), "QueryExecutionTypesComputedMember.context") { QueryExecutionTypesContextBindingFromProto(value.getContext()) }
)

internal fun QueryExecutionTypesDimensionToProto(value: com.olap.prototype.query.contract.QueryExecutionTypes.Dimension): com.olap.prototype.wire.governance.QueryExecutionTypesDimension = com.olap.prototype.wire.governance.QueryExecutionTypesDimension.newBuilder().apply {
    setDimension(SemanticProto.toProto(value.dimension))
    setEventId(value.eventId)
    setEventVersion(value.eventVersion)
    setContext(QueryExecutionTypesContextToProto(value.context))
    setType(NativeValueTypeToProto(value.type))
}.build()
internal fun QueryExecutionTypesDimensionFromProto(value: com.olap.prototype.wire.governance.QueryExecutionTypesDimension): com.olap.prototype.query.contract.QueryExecutionTypes.Dimension = com.olap.prototype.query.contract.QueryExecutionTypes.Dimension(
    dimension = required(value.hasDimension(), "QueryExecutionTypesDimension.dimension") { SemanticProto.fromProto(value.getDimension()) as com.olap.prototype.query.ScopedInputContract.DimensionRef },
    eventId = value.getEventId(),
    eventVersion = value.getEventVersion(),
    context = required(value.hasContext(), "QueryExecutionTypesDimension.context") { QueryExecutionTypesContextFromProto(value.getContext()) },
    type = required(value.hasType(), "QueryExecutionTypesDimension.type") { NativeValueTypeFromProto(value.getType()) }
)

internal fun QueryNativeDefinitionsToProto(value: com.olap.prototype.query.contract.QueryNativeDefinitions): com.olap.prototype.wire.governance.QueryNativeDefinitions = com.olap.prototype.wire.governance.QueryNativeDefinitions.newBuilder().apply {
    setCatalog(ExecutableCatalogToProto(value.catalog))
}.build()
internal fun QueryNativeDefinitionsFromProto(value: com.olap.prototype.wire.governance.QueryNativeDefinitions): com.olap.prototype.query.contract.QueryNativeDefinitions = com.olap.prototype.query.contract.QueryNativeDefinitions(
    catalog = required(value.hasCatalog(), "QueryNativeDefinitions.catalog") { ExecutableCatalogFromProto(value.getCatalog()) }
)

internal fun SealedSourceCollectionMeaningToProto(value: com.olap.prototype.query.contract.SealedSourceCollectionMeaning): com.olap.prototype.wire.governance.SealedSourceCollectionMeaning = com.olap.prototype.wire.governance.SealedSourceCollectionMeaning.newBuilder().apply {
    setId(value.id)
    setSourceAuthority(value.sourceAuthority)
    setScope(SealedSourceDatasetScopeToProto(value.scope))
    addAllRuleIds(value.ruleIds.map { it })
    setMeaning(value.meaning)
}.build()
internal fun SealedSourceCollectionMeaningFromProto(value: com.olap.prototype.wire.governance.SealedSourceCollectionMeaning): com.olap.prototype.query.contract.SealedSourceCollectionMeaning = com.olap.prototype.query.contract.SealedSourceCollectionMeaning(
    id = value.getId(),
    sourceAuthority = value.getSourceAuthority(),
    scope = required(value.hasScope(), "SealedSourceCollectionMeaning.scope") { SealedSourceDatasetScopeFromProto(value.getScope()) },
    ruleIds = value.getRuleIdsList().map { it },
    meaning = value.getMeaning()
)

internal fun PublishedExecutableCatalogToProto(value: com.olap.prototype.query.contract.PublishedExecutableCatalog): com.olap.prototype.wire.governance.PublishedExecutableCatalog = com.olap.prototype.wire.governance.PublishedExecutableCatalog.newBuilder().apply {
    setFormatVersion(value.formatVersion)
    setCoordinate(QueryProto.toProto(value.coordinate))
    setQueryCapability(QueryCapabilityViewToProto(value.queryCapability))
    setCatalog(ExecutableCatalogToProto(value.catalog))
    addAllMetricAdoptions(value.metricAdoptions.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedExecutableCatalogMetricAdoptionsEntry.newBuilder().setKey(entry.key).apply { setValue(MetricAdoptionToProto(entry.value)) }.build() })
    addAllObjectVersionRefs(value.objectVersionRefs.map { PublishedObjectVersionRefToProto(it) })
    value.generatedAt?.let { setGeneratedAt(it.toString()) }
    value.diagnosticRunId?.let { setDiagnosticRunId(it) }
}.build()
internal fun PublishedExecutableCatalogFromProto(value: com.olap.prototype.wire.governance.PublishedExecutableCatalog): com.olap.prototype.query.contract.PublishedExecutableCatalog = com.olap.prototype.query.contract.PublishedExecutableCatalog(
    formatVersion = value.getFormatVersion(),
    coordinate = required(value.hasCoordinate(), "PublishedExecutableCatalog.coordinate") { QueryProto.fromProto(value.getCoordinate()) as com.olap.prototype.query.contract.QueryCapabilityRequest },
    queryCapability = required(value.hasQueryCapability(), "PublishedExecutableCatalog.queryCapability") { QueryCapabilityViewFromProto(value.getQueryCapability()) },
    catalog = required(value.hasCatalog(), "PublishedExecutableCatalog.catalog") { ExecutableCatalogFromProto(value.getCatalog()) },
    metricAdoptions = strictMap(value.getMetricAdoptionsList().map { it.getKey() to MetricAdoptionFromProto(it.getValue()) }),
    objectVersionRefs = value.getObjectVersionRefsList().map { PublishedObjectVersionRefFromProto(it) },
    generatedAt = if (value.hasGeneratedAt()) java.time.Instant.parse(value.getGeneratedAt()) else null,
    diagnosticRunId = if (value.hasDiagnosticRunId()) value.getDiagnosticRunId() else null
)

internal fun MetricAdoptionToProto(value: com.olap.prototype.query.contract.MetricAdoption): com.olap.prototype.wire.governance.MetricAdoption = com.olap.prototype.wire.governance.MetricAdoption.newBuilder().apply {
    setAdoptionId(value.adoptionId)
    setSubjectAreaId(value.subjectAreaId)
    setSubjectAreaVersion(value.subjectAreaVersion)
    setCode(value.code)
    setDisplayName(value.displayName)
    setBusinessMeaning(SemanticProto.toProto(value.businessMeaning))
    setDefinition(SemanticProto.toProto(value.definition))
    setBindings(SemanticProto.toProto(value.bindings))
    addAllFixedArguments(value.fixedArguments.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.MetricAdoptionFixedArgumentsEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
}.build()
internal fun MetricAdoptionFromProto(value: com.olap.prototype.wire.governance.MetricAdoption): com.olap.prototype.query.contract.MetricAdoption = com.olap.prototype.query.contract.MetricAdoption(
    adoptionId = value.getAdoptionId(),
    subjectAreaId = value.getSubjectAreaId(),
    subjectAreaVersion = value.getSubjectAreaVersion(),
    code = value.getCode(),
    displayName = value.getDisplayName(),
    businessMeaning = required(value.hasBusinessMeaning(), "MetricAdoption.businessMeaning") { SemanticProto.fromProto(value.getBusinessMeaning()) as com.olap.prototype.metric.MetricBusinessMeaning },
    definition = required(value.hasDefinition(), "MetricAdoption.definition") { SemanticProto.fromProto(value.getDefinition()) as com.olap.prototype.calculation.MetricRef },
    bindings = required(value.hasBindings(), "MetricAdoption.bindings") { SemanticProto.fromProto(value.getBindings()) as com.olap.prototype.calculation.CalculationBindings },
    fixedArguments = strictMap(value.getFixedArgumentsList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.Value })
)

internal fun PublishedObjectVersionRefToProto(value: com.olap.prototype.query.contract.PublishedObjectVersionRef): com.olap.prototype.wire.governance.PublishedObjectVersionRef = com.olap.prototype.wire.governance.PublishedObjectVersionRef.newBuilder().apply {
    value.objectType?.let { setObjectType(it) }
    value.objectId?.let { setObjectId(it) }
    value.objectVersion?.let { setObjectVersion(it) }
    value.objectCode?.let { setObjectCode(it) }
    value.objectDisplayName?.let { setObjectDisplayName(it) }
    value.refRole?.let { setRefRole(it) }
}.build()
internal fun PublishedObjectVersionRefFromProto(value: com.olap.prototype.wire.governance.PublishedObjectVersionRef): com.olap.prototype.query.contract.PublishedObjectVersionRef = com.olap.prototype.query.contract.PublishedObjectVersionRef(
    objectType = if (value.hasObjectType()) value.getObjectType() else null,
    objectId = if (value.hasObjectId()) value.getObjectId() else null,
    objectVersion = if (value.hasObjectVersion()) value.getObjectVersion() else null,
    objectCode = if (value.hasObjectCode()) value.getObjectCode() else null,
    objectDisplayName = if (value.hasObjectDisplayName()) value.getObjectDisplayName() else null,
    refRole = if (value.hasRefRole()) value.getRefRole() else null
)
