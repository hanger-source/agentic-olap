package com.olap.prototype.codec

/** 正式领域消息的显式字段转换。 */
internal fun ReleaseArtifactManifestToProto(value: com.olap.prototype.release.catalog.ReleaseArtifactManifest): com.olap.prototype.wire.governance.ReleaseArtifactManifest = com.olap.prototype.wire.governance.ReleaseArtifactManifest.newBuilder().apply {
    setReleaseId(value.releaseId)
    setReleaseVersion(value.releaseVersion)
    setSubjectAreaId(value.subjectAreaId)
    setSubjectAreaVersion(value.subjectAreaVersion)
    setCompilerVersion(value.compilerVersion)
    setRuleSetVersion(value.ruleSetVersion)
    setGovernanceClosureHash(value.governanceClosureHash)
    setCatalogHash(value.catalogHash)
    addAllCatalogs(value.catalogs.map { ReleaseCatalogSnapshotToProto(it) })
}.build()
internal fun ReleaseArtifactManifestFromProto(value: com.olap.prototype.wire.governance.ReleaseArtifactManifest): com.olap.prototype.release.catalog.ReleaseArtifactManifest = com.olap.prototype.release.catalog.ReleaseArtifactManifest(
    releaseId = value.getReleaseId(),
    releaseVersion = value.getReleaseVersion(),
    subjectAreaId = value.getSubjectAreaId(),
    subjectAreaVersion = value.getSubjectAreaVersion(),
    compilerVersion = value.getCompilerVersion(),
    ruleSetVersion = value.getRuleSetVersion(),
    governanceClosureHash = value.getGovernanceClosureHash(),
    catalogHash = value.getCatalogHash(),
    catalogs = value.getCatalogsList().map { ReleaseCatalogSnapshotFromProto(it) }
)

internal fun ReleaseCatalogSnapshotToProto(value: com.olap.prototype.release.contract.ReleaseCatalogSnapshot): com.olap.prototype.wire.governance.ReleaseCatalogSnapshot = com.olap.prototype.wire.governance.ReleaseCatalogSnapshot.newBuilder().apply {
    setScenarioId(value.scenarioId)
    setScenarioVersion(value.scenarioVersion)
    setCatalogHash(value.catalogHash)
}.build()
internal fun ReleaseCatalogSnapshotFromProto(value: com.olap.prototype.wire.governance.ReleaseCatalogSnapshot): com.olap.prototype.release.contract.ReleaseCatalogSnapshot = com.olap.prototype.release.contract.ReleaseCatalogSnapshot(
    scenarioId = value.getScenarioId(),
    scenarioVersion = value.getScenarioVersion(),
    catalogHash = value.getCatalogHash()
)

internal fun ReleaseClosureEntryToProto(value: com.olap.prototype.release.catalog.ReleaseClosureEntry): com.olap.prototype.wire.governance.ReleaseClosureEntry = com.olap.prototype.wire.governance.ReleaseClosureEntry.newBuilder().apply {
    setObjectType(value.objectType)
    setObjectId(value.objectId)
    setObjectVersion(value.objectVersion)
    value.objectCode?.let { setObjectCode(it) }
    value.objectDisplayName?.let { setObjectDisplayName(it) }
    setRefRole(value.refRole)
    setSnapshotModel(value.snapshotModel)
}.build()
internal fun ReleaseClosureEntryFromProto(value: com.olap.prototype.wire.governance.ReleaseClosureEntry): com.olap.prototype.release.catalog.ReleaseClosureEntry = com.olap.prototype.release.catalog.ReleaseClosureEntry(
    objectType = value.getObjectType(),
    objectId = value.getObjectId(),
    objectVersion = value.getObjectVersion(),
    objectCode = if (value.hasObjectCode()) value.getObjectCode() else null,
    objectDisplayName = if (value.hasObjectDisplayName()) value.getObjectDisplayName() else null,
    refRole = value.getRefRole(),
    snapshotModel = value.getSnapshotModel()
)

internal fun ReleaseClosureToProto(value: com.olap.prototype.release.catalog.ReleaseClosure): com.olap.prototype.wire.governance.ReleaseClosure = com.olap.prototype.wire.governance.ReleaseClosure.newBuilder().apply {
    addAllEntries(value.entries.map { ReleaseClosureEntryToProto(it) })
}.build()
internal fun ReleaseClosureFromProto(value: com.olap.prototype.wire.governance.ReleaseClosure): com.olap.prototype.release.catalog.ReleaseClosure = com.olap.prototype.release.catalog.ReleaseClosure(
    entries = value.getEntriesList().map { ReleaseClosureEntryFromProto(it) }
)

internal fun ReleaseCatalogSnapshotsToProto(value: com.olap.prototype.release.catalog.ReleaseCatalogSnapshots): com.olap.prototype.wire.governance.ReleaseCatalogSnapshots = com.olap.prototype.wire.governance.ReleaseCatalogSnapshots.newBuilder().apply {
    addAllCatalogs(value.catalogs.map { ReleaseCatalogSnapshotToProto(it) })
}.build()
internal fun ReleaseCatalogSnapshotsFromProto(value: com.olap.prototype.wire.governance.ReleaseCatalogSnapshots): com.olap.prototype.release.catalog.ReleaseCatalogSnapshots = com.olap.prototype.release.catalog.ReleaseCatalogSnapshots(
    catalogs = value.getCatalogsList().map { ReleaseCatalogSnapshotFromProto(it) }
)

internal fun EventExecutionSnapshotToProto(value: com.olap.prototype.release.contract.EventExecutionSnapshot): com.olap.prototype.wire.governance.EventExecutionSnapshot = com.olap.prototype.wire.governance.EventExecutionSnapshot.newBuilder().apply {
    setTopologyId(value.topologyId)
    setTopologyVersion(value.topologyVersion)
    value.eventTimeLogicUnitId?.let { setEventTimeLogicUnitId(it) }
    value.eventTimeLogicUnitVersion?.let { setEventTimeLogicUnitVersion(it) }
    setIdentity(SemanticProto.toProto(value.identity))
    addAllRoles(value.roles.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.EventExecutionSnapshotRolesEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    value.baseFilter?.let { setBaseFilter(SemanticProto.toProto(it)) }
}.build()
internal fun EventExecutionSnapshotFromProto(value: com.olap.prototype.wire.governance.EventExecutionSnapshot): com.olap.prototype.release.contract.EventExecutionSnapshot = com.olap.prototype.release.contract.EventExecutionSnapshot(
    topologyId = value.getTopologyId(),
    topologyVersion = value.getTopologyVersion(),
    eventTimeLogicUnitId = if (value.hasEventTimeLogicUnitId()) value.getEventTimeLogicUnitId() else null,
    eventTimeLogicUnitVersion = if (value.hasEventTimeLogicUnitVersion()) value.getEventTimeLogicUnitVersion() else null,
    identity = required(value.hasIdentity(), "EventExecutionSnapshot.identity") { SemanticProto.fromProto(value.getIdentity()) as com.olap.prototype.event.EventIdentity },
    roles = strictMap(value.getRolesList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.event.RelationAccessPath }),
    baseFilter = if (value.hasBaseFilter()) SemanticProto.fromProto(value.getBaseFilter()) else null
)

internal fun TopologyExecutionSnapshotToProto(value: com.olap.prototype.release.contract.TopologyExecutionSnapshot): com.olap.prototype.wire.governance.TopologyExecutionSnapshot = com.olap.prototype.wire.governance.TopologyExecutionSnapshot.newBuilder().apply {
    setTopologyId(value.topologyId)
    setTopologyVersion(value.topologyVersion)
    setTopologyImplementationNamespaceCode(value.topologyImplementationNamespaceCode)
    setTopologyImplementationNamespaceDisplayName(value.topologyImplementationNamespaceDisplayName)
    setBackboneLogicalTableId(value.backboneLogicalTableId)
    setBackboneLogicalTableVersion(value.backboneLogicalTableVersion)
    addAllJoinPathRefs(value.joinPathRefs.map { ReleaseJoinPathRefToProto(it) })
}.build()
internal fun TopologyExecutionSnapshotFromProto(value: com.olap.prototype.wire.governance.TopologyExecutionSnapshot): com.olap.prototype.release.contract.TopologyExecutionSnapshot = com.olap.prototype.release.contract.TopologyExecutionSnapshot(
    topologyId = value.getTopologyId(),
    topologyVersion = value.getTopologyVersion(),
    topologyImplementationNamespaceCode = value.getTopologyImplementationNamespaceCode(),
    topologyImplementationNamespaceDisplayName = value.getTopologyImplementationNamespaceDisplayName(),
    backboneLogicalTableId = value.getBackboneLogicalTableId(),
    backboneLogicalTableVersion = value.getBackboneLogicalTableVersion(),
    joinPathRefs = value.getJoinPathRefsList().map { ReleaseJoinPathRefFromProto(it) }
)

internal fun ReleaseJoinPathRefToProto(value: com.olap.prototype.release.contract.ReleaseJoinPathRef): com.olap.prototype.wire.governance.ReleaseJoinPathRef = com.olap.prototype.wire.governance.ReleaseJoinPathRef.newBuilder().apply {
    setJoinPathId(value.joinPathId)
    setJoinPathVersion(value.joinPathVersion)
}.build()
internal fun ReleaseJoinPathRefFromProto(value: com.olap.prototype.wire.governance.ReleaseJoinPathRef): com.olap.prototype.release.contract.ReleaseJoinPathRef = com.olap.prototype.release.contract.ReleaseJoinPathRef(
    joinPathId = value.getJoinPathId(),
    joinPathVersion = value.getJoinPathVersion()
)

internal fun MetricExecutionSnapshotToProto(value: com.olap.prototype.release.contract.MetricExecutionSnapshot): com.olap.prototype.wire.governance.MetricExecutionSnapshot = com.olap.prototype.wire.governance.MetricExecutionSnapshot.newBuilder().apply {
    setDefinition(SemanticProto.toProto(value.definition))
    setBusinessMeaning(SemanticProto.toProto(value.businessMeaning))
}.build()
internal fun MetricExecutionSnapshotFromProto(value: com.olap.prototype.wire.governance.MetricExecutionSnapshot): com.olap.prototype.release.contract.MetricExecutionSnapshot = com.olap.prototype.release.contract.MetricExecutionSnapshot(
    definition = required(value.hasDefinition(), "MetricExecutionSnapshot.definition") { SemanticProto.fromProto(value.getDefinition()) as com.olap.prototype.calculation.MetricDefinition },
    businessMeaning = required(value.hasBusinessMeaning(), "MetricExecutionSnapshot.businessMeaning") { SemanticProto.fromProto(value.getBusinessMeaning()) as com.olap.prototype.metric.MetricBusinessMeaning }
)

internal fun LogicUnitExecutionSnapshotToProto(value: com.olap.prototype.release.contract.LogicUnitExecutionSnapshot): com.olap.prototype.wire.governance.LogicUnitExecutionSnapshot = com.olap.prototype.wire.governance.LogicUnitExecutionSnapshot.newBuilder().apply {
    setFormatVersion(value.formatVersion)
    setPhysicalExecutionBindingId(value.physicalExecutionBindingId)
    setPhysicalExecutionBindingVersion(value.physicalExecutionBindingVersion)
    setLogicUnitId(value.logicUnitId)
    setLogicUnitVersion(value.logicUnitVersion)
    value.valueType?.let { setValueType(it) }
    value.valueShape?.let { setValueShape(SemanticProto.toProto(it)) }
    setLogicalTableId(value.logicalTableId)
    setLogicalTableVersion(value.logicalTableVersion)
    setPhysicalTableId(value.physicalTableId)
    setPhysicalTableVersion(value.physicalTableVersion)
    setDataSourceId(value.dataSourceId)
    value.catalogName?.let { setCatalogName(it) }
    value.schemaName?.let { setSchemaName(it) }
    setTableName(value.tableName)
    setDialect(value.dialect)
    addAllLogicUnitBindings(value.logicUnitBindings.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.LogicUnitExecutionSnapshotLogicUnitBindingsEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    addAllPhysicalColumnBindings(value.physicalColumnBindings.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.LogicUnitExecutionSnapshotPhysicalColumnBindingsEntry.newBuilder().setKey(entry.key).apply { setValue(entry.value) }.build() })
    addAllPhysicalColumnTypes(value.physicalColumnTypes.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.LogicUnitExecutionSnapshotPhysicalColumnTypesEntry.newBuilder().setKey(entry.key).apply { setValue(JdbcColumnTypeToProto(entry.value)) }.build() })
    addAllNativeTableDependencies(value.nativeTableDependencies.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.LogicUnitExecutionSnapshotNativeTableDependenciesEntry.newBuilder().setKey(entry.key).apply { setValue(NativeTableDependencyToProto(entry.value)) }.build() })
    setSourceConstraints(PhysicalSourceConstraintsToProto(value.sourceConstraints))
}.build()
internal fun LogicUnitExecutionSnapshotFromProto(value: com.olap.prototype.wire.governance.LogicUnitExecutionSnapshot): com.olap.prototype.release.contract.LogicUnitExecutionSnapshot = com.olap.prototype.release.contract.LogicUnitExecutionSnapshot(
    formatVersion = value.getFormatVersion(),
    physicalExecutionBindingId = value.getPhysicalExecutionBindingId(),
    physicalExecutionBindingVersion = value.getPhysicalExecutionBindingVersion(),
    logicUnitId = value.getLogicUnitId(),
    logicUnitVersion = value.getLogicUnitVersion(),
    valueType = if (value.hasValueType()) value.getValueType() else null,
    valueShape = if (value.hasValueShape()) SemanticProto.fromProto(value.getValueShape()) as com.olap.prototype.query.SetOutputContract.Shape else null,
    logicalTableId = value.getLogicalTableId(),
    logicalTableVersion = value.getLogicalTableVersion(),
    physicalTableId = value.getPhysicalTableId(),
    physicalTableVersion = value.getPhysicalTableVersion(),
    dataSourceId = value.getDataSourceId(),
    catalogName = if (value.hasCatalogName()) value.getCatalogName() else null,
    schemaName = if (value.hasSchemaName()) value.getSchemaName() else null,
    tableName = value.getTableName(),
    dialect = value.getDialect(),
    logicUnitBindings = strictMap(value.getLogicUnitBindingsList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.expression.SemanticExpression }),
    physicalColumnBindings = strictMap(value.getPhysicalColumnBindingsList().map { it.getKey() to it.getValue() }),
    physicalColumnTypes = strictMap(value.getPhysicalColumnTypesList().map { it.getKey() to JdbcColumnTypeFromProto(it.getValue()) }),
    nativeTableDependencies = strictMap(value.getNativeTableDependenciesList().map { it.getKey() to NativeTableDependencyFromProto(it.getValue()) }),
    sourceConstraints = required(value.hasSourceConstraints(), "LogicUnitExecutionSnapshot.sourceConstraints") { PhysicalSourceConstraintsFromProto(value.getSourceConstraints()) }
)

internal fun SourceUniqueKeySnapshotToProto(value: com.olap.prototype.release.contract.SourceUniqueKeySnapshot): com.olap.prototype.wire.governance.SourceUniqueKeySnapshot = com.olap.prototype.wire.governance.SourceUniqueKeySnapshot.newBuilder().apply {
    setProofId(value.proofId)
    setPhysicalTableId(value.physicalTableId)
    setPhysicalTableVersion(value.physicalTableVersion)
    setDiscoverySnapshotId(value.discoverySnapshotId)
    setSchemaHash(value.schemaHash)
    setSourceIdentityHash(value.sourceIdentityHash)
    setKey(SnapshotUniqueKeyToProto(value.key))
}.build()
internal fun SourceUniqueKeySnapshotFromProto(value: com.olap.prototype.wire.governance.SourceUniqueKeySnapshot): com.olap.prototype.release.contract.SourceUniqueKeySnapshot = com.olap.prototype.release.contract.SourceUniqueKeySnapshot(
    proofId = value.getProofId(),
    physicalTableId = value.getPhysicalTableId(),
    physicalTableVersion = value.getPhysicalTableVersion(),
    discoverySnapshotId = value.getDiscoverySnapshotId(),
    schemaHash = value.getSchemaHash(),
    sourceIdentityHash = value.getSourceIdentityHash(),
    key = required(value.hasKey(), "SourceUniqueKeySnapshot.key") { SnapshotUniqueKeyFromProto(value.getKey()) }
)

internal fun FrozenAnalysisDimensionMeaningToProto(value: com.olap.prototype.release.contract.FrozenAnalysisDimensionMeaning): com.olap.prototype.wire.governance.FrozenAnalysisDimensionMeaning = com.olap.prototype.wire.governance.FrozenAnalysisDimensionMeaning.newBuilder().apply {
    value.description?.let { setDescription(it) }
    addAllValueAlignmentEvidence(value.valueAlignmentEvidence.map { AnalysisDimensionValueAlignmentRecordToProto(it) })
}.build()
internal fun FrozenAnalysisDimensionMeaningFromProto(value: com.olap.prototype.wire.governance.FrozenAnalysisDimensionMeaning): com.olap.prototype.release.contract.FrozenAnalysisDimensionMeaning = com.olap.prototype.release.contract.FrozenAnalysisDimensionMeaning(
    description = if (value.hasDescription()) value.getDescription() else null,
    valueAlignmentEvidence = value.getValueAlignmentEvidenceList().map { AnalysisDimensionValueAlignmentRecordFromProto(it) }
)

internal fun ReleaseCompilationContractToProto(value: com.olap.prototype.release.contract.ReleaseCompilationContract): com.olap.prototype.wire.governance.ReleaseCompilationContract = com.olap.prototype.wire.governance.ReleaseCompilationContract.getDefaultInstance()
internal fun ReleaseCompilationContractFromProto(value: com.olap.prototype.wire.governance.ReleaseCompilationContract): com.olap.prototype.release.contract.ReleaseCompilationContract = com.olap.prototype.release.contract.ReleaseCompilationContract

internal fun ReleaseArtifactRecordToProto(value: com.olap.prototype.release.contract.ReleaseArtifactRecord): com.olap.prototype.wire.governance.ReleaseArtifactRecord = com.olap.prototype.wire.governance.ReleaseArtifactRecord.newBuilder().apply {
    setReleaseId(value.releaseId)
    setReleaseVersion(value.releaseVersion)
    setSubjectAreaId(value.subjectAreaId)
    setSubjectAreaVersion(value.subjectAreaVersion)
    setGovernanceClosureHash(value.governanceClosureHash)
    setCatalogHash(value.catalogHash)
    setArtifactHash(value.artifactHash)
    setManifestProto(value.manifestProto)
}.build()
internal fun ReleaseArtifactRecordFromProto(value: com.olap.prototype.wire.governance.ReleaseArtifactRecord): com.olap.prototype.release.contract.ReleaseArtifactRecord = com.olap.prototype.release.contract.ReleaseArtifactRecord(
    releaseId = value.getReleaseId(),
    releaseVersion = value.getReleaseVersion(),
    subjectAreaId = value.getSubjectAreaId(),
    subjectAreaVersion = value.getSubjectAreaVersion(),
    governanceClosureHash = value.getGovernanceClosureHash(),
    catalogHash = value.getCatalogHash(),
    artifactHash = value.getArtifactHash(),
    manifestProto = value.getManifestProto()
)

internal fun ReleaseCatalogRequestToProto(value: com.olap.prototype.release.contract.ReleaseCatalogRequest): com.olap.prototype.wire.governance.ReleaseCatalogRequest = com.olap.prototype.wire.governance.ReleaseCatalogRequest.newBuilder().apply {
    setScenarioId(value.scenarioId)
    setScenarioVersion(value.scenarioVersion)
    setReleaseId(value.releaseId)
    setReleaseVersion(value.releaseVersion)
}.build()
internal fun ReleaseCatalogRequestFromProto(value: com.olap.prototype.wire.governance.ReleaseCatalogRequest): com.olap.prototype.release.contract.ReleaseCatalogRequest = com.olap.prototype.release.contract.ReleaseCatalogRequest(
    scenarioId = value.getScenarioId(),
    scenarioVersion = value.getScenarioVersion(),
    releaseId = value.getReleaseId(),
    releaseVersion = value.getReleaseVersion()
)

internal fun ReleaseObjectRefToProto(value: com.olap.prototype.release.contract.ReleaseObjectRef): com.olap.prototype.wire.governance.ReleaseObjectRef = com.olap.prototype.wire.governance.ReleaseObjectRef.newBuilder().apply {
    setId(value.id)
    setReleaseId(value.releaseId)
    setReleaseVersion(value.releaseVersion)
    setObjectType(value.objectType)
    setObjectId(value.objectId)
    setObjectVersion(value.objectVersion)
    setObjectCode(value.objectCode)
    setObjectDisplayName(value.objectDisplayName)
    setRefRole(value.refRole)
    setSnapshotProto(value.snapshotProto)
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun ReleaseObjectRefFromProto(value: com.olap.prototype.wire.governance.ReleaseObjectRef): com.olap.prototype.release.contract.ReleaseObjectRef = com.olap.prototype.release.contract.ReleaseObjectRef(
    id = value.getId(),
    releaseId = value.getReleaseId(),
    releaseVersion = value.getReleaseVersion(),
    objectType = value.getObjectType(),
    objectId = value.getObjectId(),
    objectVersion = value.getObjectVersion(),
    objectCode = value.getObjectCode(),
    objectDisplayName = value.getObjectDisplayName(),
    refRole = value.getRefRole(),
    snapshotProto = value.getSnapshotProto(),
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)

internal fun ReleaseCatalogViewToProto(value: com.olap.prototype.release.contract.ReleaseCatalogView): com.olap.prototype.wire.governance.ReleaseCatalogView = com.olap.prototype.wire.governance.ReleaseCatalogView.newBuilder().apply {
    setScenarioId(value.scenarioId)
    setScenarioVersion(value.scenarioVersion)
    setScenarioDisplayName(value.scenarioDisplayName)
    setReleaseId(value.releaseId)
    setReleaseVersion(value.releaseVersion)
    setReleaseDisplayName(value.releaseDisplayName)
    addAllObjectVersionRefs(value.objectVersionRefs.map { ReleaseObjectRefToProto(it) })
    addAllMetricAnalysisDimensionCapability(value.metricAnalysisDimensionCapability.map { ReleaseMetricDimensionCapabilityToProto(it) })
    addAllDimensionMappings(value.dimensionMappings.map { ReleaseDimensionMappingToProto(it) })
    addAllTimeAnchorRule(value.timeAnchorRule.map { ReleaseTimeAnchorRuleToProto(it) })
    addAllUnavailableReason(value.unavailableReason.map { ReleaseUnavailableReasonToProto(it) })
    addAllDiagnostics(value.diagnostics.map { ReleaseDiagnosticIssueToProto(it) })
}.build()
internal fun ReleaseCatalogViewFromProto(value: com.olap.prototype.wire.governance.ReleaseCatalogView): com.olap.prototype.release.contract.ReleaseCatalogView = com.olap.prototype.release.contract.ReleaseCatalogView(
    scenarioId = value.getScenarioId(),
    scenarioVersion = value.getScenarioVersion(),
    scenarioDisplayName = value.getScenarioDisplayName(),
    releaseId = value.getReleaseId(),
    releaseVersion = value.getReleaseVersion(),
    releaseDisplayName = value.getReleaseDisplayName(),
    objectVersionRefs = value.getObjectVersionRefsList().map { ReleaseObjectRefFromProto(it) },
    metricAnalysisDimensionCapability = value.getMetricAnalysisDimensionCapabilityList().map { ReleaseMetricDimensionCapabilityFromProto(it) },
    dimensionMappings = value.getDimensionMappingsList().map { ReleaseDimensionMappingFromProto(it) },
    timeAnchorRule = value.getTimeAnchorRuleList().map { ReleaseTimeAnchorRuleFromProto(it) },
    unavailableReason = value.getUnavailableReasonList().map { ReleaseUnavailableReasonFromProto(it) },
    diagnostics = value.getDiagnosticsList().map { ReleaseDiagnosticIssueFromProto(it) }
)

internal fun ReleaseMetricDimensionCapabilityToProto(value: com.olap.prototype.release.contract.ReleaseMetricDimensionCapability): com.olap.prototype.wire.governance.ReleaseMetricDimensionCapability = com.olap.prototype.wire.governance.ReleaseMetricDimensionCapability.newBuilder().apply {
    setId(value.id)
    setReleaseId(value.releaseId)
    setReleaseVersion(value.releaseVersion)
    setSubjectAreaId(value.subjectAreaId)
    value.scenarioId?.let { setScenarioId(it) }
    value.scenarioVersion?.let { setScenarioVersion(it) }
    setAdoptionId(value.adoptionId)
    setAdoptionVersion(value.adoptionVersion)
    setMetricId(value.metricId)
    setMetricVersion(value.metricVersion)
    setMetricDisplayName(value.metricDisplayName)
    setEventId(value.eventId)
    setEventVersion(value.eventVersion)
    setAnalysisDimensionId(value.analysisDimensionId)
    setAnalysisDimensionVersion(value.analysisDimensionVersion)
    setAnalysisDimensionDisplayName(value.analysisDimensionDisplayName)
    value.levelCode?.let { setLevelCode(it) }
    value.levelDisplayName?.let { setLevelDisplayName(it) }
    setAvailable(value.available)
    value.reasonCode?.let { setReasonCode(it) }
    value.reasonMessage?.let { setReasonMessage(it) }
    value.producerStage?.let { setProducerStage(it) }
    value.targetRef?.let { setTargetRef(it) }
    value.targetVersionRef?.let { setTargetVersionRef(it) }
    setBlocking(value.blocking)
    setPageBlocking(value.pageBlocking)
    setExecutableBlocking(value.executableBlocking)
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun ReleaseMetricDimensionCapabilityFromProto(value: com.olap.prototype.wire.governance.ReleaseMetricDimensionCapability): com.olap.prototype.release.contract.ReleaseMetricDimensionCapability = com.olap.prototype.release.contract.ReleaseMetricDimensionCapability(
    id = value.getId(),
    releaseId = value.getReleaseId(),
    releaseVersion = value.getReleaseVersion(),
    subjectAreaId = value.getSubjectAreaId(),
    scenarioId = if (value.hasScenarioId()) value.getScenarioId() else null,
    scenarioVersion = if (value.hasScenarioVersion()) value.getScenarioVersion() else null,
    adoptionId = value.getAdoptionId(),
    adoptionVersion = value.getAdoptionVersion(),
    metricId = value.getMetricId(),
    metricVersion = value.getMetricVersion(),
    metricDisplayName = value.getMetricDisplayName(),
    eventId = value.getEventId(),
    eventVersion = value.getEventVersion(),
    analysisDimensionId = value.getAnalysisDimensionId(),
    analysisDimensionVersion = value.getAnalysisDimensionVersion(),
    analysisDimensionDisplayName = value.getAnalysisDimensionDisplayName(),
    levelCode = if (value.hasLevelCode()) value.getLevelCode() else null,
    levelDisplayName = if (value.hasLevelDisplayName()) value.getLevelDisplayName() else null,
    available = value.getAvailable(),
    reasonCode = if (value.hasReasonCode()) value.getReasonCode() else null,
    reasonMessage = if (value.hasReasonMessage()) value.getReasonMessage() else null,
    producerStage = if (value.hasProducerStage()) value.getProducerStage() else null,
    targetRef = if (value.hasTargetRef()) value.getTargetRef() else null,
    targetVersionRef = if (value.hasTargetVersionRef()) value.getTargetVersionRef() else null,
    blocking = value.getBlocking(),
    pageBlocking = value.getPageBlocking(),
    executableBlocking = value.getExecutableBlocking(),
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)

internal fun ReleaseDimensionMappingToProto(value: com.olap.prototype.release.contract.ReleaseDimensionMapping): com.olap.prototype.wire.governance.ReleaseDimensionMapping = com.olap.prototype.wire.governance.ReleaseDimensionMapping.newBuilder().apply {
    setId(value.id)
    setMappingId(value.mappingId)
    setReleaseId(value.releaseId)
    setReleaseVersion(value.releaseVersion)
    setSubjectAreaId(value.subjectAreaId)
    setEventId(value.eventId)
    setEventVersion(value.eventVersion)
    setAnalysisDimensionId(value.analysisDimensionId)
    setAnalysisDimensionVersion(value.analysisDimensionVersion)
    setAnalysisDimensionDisplayName(value.analysisDimensionDisplayName)
    setLevelCode(value.levelCode)
    setLevelDisplayName(value.levelDisplayName)
    setDimensionId(value.dimensionId)
    setDimensionVersion(value.dimensionVersion)
    setDimensionCodeNamespace(value.dimensionCodeNamespace)
    setDimensionCodeNamespaceDisplayName(value.dimensionCodeNamespaceDisplayName)
    setDimensionDisplayName(value.dimensionDisplayName)
    setDimensionBindingId(value.dimensionBindingId)
    setDimensionBindingVersion(value.dimensionBindingVersion)
    value.valueAlignmentKey?.let { setValueAlignmentKey(it) }
    setValueAlignmentStatus(value.valueAlignmentStatus)
    setTopologyId(value.topologyId)
    setTopologyVersion(value.topologyVersion)
    setTopologyImplementationNamespaceCode(value.topologyImplementationNamespaceCode)
    setTopologyImplementationNamespaceDisplayName(value.topologyImplementationNamespaceDisplayName)
    setLogicUnitId(value.logicUnitId)
    setLogicUnitVersion(value.logicUnitVersion)
    setLogicUnitDisplayName(value.logicUnitDisplayName)
    addAllJoinPathRefs(value.joinPathRefs.map { ReleaseJoinPathRefToProto(it) })
    setExpression(SemanticProto.toProto(value.expression))
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun ReleaseDimensionMappingFromProto(value: com.olap.prototype.wire.governance.ReleaseDimensionMapping): com.olap.prototype.release.contract.ReleaseDimensionMapping = com.olap.prototype.release.contract.ReleaseDimensionMapping(
    id = value.getId(),
    mappingId = value.getMappingId(),
    releaseId = value.getReleaseId(),
    releaseVersion = value.getReleaseVersion(),
    subjectAreaId = value.getSubjectAreaId(),
    eventId = value.getEventId(),
    eventVersion = value.getEventVersion(),
    analysisDimensionId = value.getAnalysisDimensionId(),
    analysisDimensionVersion = value.getAnalysisDimensionVersion(),
    analysisDimensionDisplayName = value.getAnalysisDimensionDisplayName(),
    levelCode = value.getLevelCode(),
    levelDisplayName = value.getLevelDisplayName(),
    dimensionId = value.getDimensionId(),
    dimensionVersion = value.getDimensionVersion(),
    dimensionCodeNamespace = value.getDimensionCodeNamespace(),
    dimensionCodeNamespaceDisplayName = value.getDimensionCodeNamespaceDisplayName(),
    dimensionDisplayName = value.getDimensionDisplayName(),
    dimensionBindingId = value.getDimensionBindingId(),
    dimensionBindingVersion = value.getDimensionBindingVersion(),
    valueAlignmentKey = if (value.hasValueAlignmentKey()) value.getValueAlignmentKey() else null,
    valueAlignmentStatus = value.getValueAlignmentStatus(),
    topologyId = value.getTopologyId(),
    topologyVersion = value.getTopologyVersion(),
    topologyImplementationNamespaceCode = value.getTopologyImplementationNamespaceCode(),
    topologyImplementationNamespaceDisplayName = value.getTopologyImplementationNamespaceDisplayName(),
    logicUnitId = value.getLogicUnitId(),
    logicUnitVersion = value.getLogicUnitVersion(),
    logicUnitDisplayName = value.getLogicUnitDisplayName(),
    joinPathRefs = value.getJoinPathRefsList().map { ReleaseJoinPathRefFromProto(it) },
    expression = required(value.hasExpression(), "ReleaseDimensionMapping.expression") { SemanticProto.fromProto(value.getExpression()) },
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)

internal fun ReleaseTimeAnchorRuleToProto(value: com.olap.prototype.release.contract.ReleaseTimeAnchorRule): com.olap.prototype.wire.governance.ReleaseTimeAnchorRule = com.olap.prototype.wire.governance.ReleaseTimeAnchorRule.newBuilder().apply {
    setId(value.id)
    setReleaseId(value.releaseId)
    setReleaseVersion(value.releaseVersion)
    setSubjectAreaId(value.subjectAreaId)
    value.scenarioId?.let { setScenarioId(it) }
    value.scenarioVersion?.let { setScenarioVersion(it) }
    setEventId(value.eventId)
    setEventVersion(value.eventVersion)
    setTopologyId(value.topologyId)
    setTopologyVersion(value.topologyVersion)
    setTopologyImplementationNamespaceCode(value.topologyImplementationNamespaceCode)
    setTopologyImplementationNamespaceDisplayName(value.topologyImplementationNamespaceDisplayName)
    setEffectiveTimeLogicUnitId(value.effectiveTimeLogicUnitId)
    setEffectiveTimeLogicUnitVersion(value.effectiveTimeLogicUnitVersion)
    setEffectiveTimeLogicUnitDisplayName(value.effectiveTimeLogicUnitDisplayName)
    setTimeRole(value.timeRole)
    setAnalysisDimensionId(value.analysisDimensionId)
    setAnalysisDimensionVersion(value.analysisDimensionVersion)
    setAnalysisDimensionDisplayName(value.analysisDimensionDisplayName)
    setLevelCode(value.levelCode)
    value.partitionLogicUnitId?.let { setPartitionLogicUnitId(it) }
    value.partitionLogicUnitVersion?.let { setPartitionLogicUnitVersion(it) }
    value.diagnosticJson?.let { setDiagnosticJson(it) }
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun ReleaseTimeAnchorRuleFromProto(value: com.olap.prototype.wire.governance.ReleaseTimeAnchorRule): com.olap.prototype.release.contract.ReleaseTimeAnchorRule = com.olap.prototype.release.contract.ReleaseTimeAnchorRule(
    id = value.getId(),
    releaseId = value.getReleaseId(),
    releaseVersion = value.getReleaseVersion(),
    subjectAreaId = value.getSubjectAreaId(),
    scenarioId = if (value.hasScenarioId()) value.getScenarioId() else null,
    scenarioVersion = if (value.hasScenarioVersion()) value.getScenarioVersion() else null,
    eventId = value.getEventId(),
    eventVersion = value.getEventVersion(),
    topologyId = value.getTopologyId(),
    topologyVersion = value.getTopologyVersion(),
    topologyImplementationNamespaceCode = value.getTopologyImplementationNamespaceCode(),
    topologyImplementationNamespaceDisplayName = value.getTopologyImplementationNamespaceDisplayName(),
    effectiveTimeLogicUnitId = value.getEffectiveTimeLogicUnitId(),
    effectiveTimeLogicUnitVersion = value.getEffectiveTimeLogicUnitVersion(),
    effectiveTimeLogicUnitDisplayName = value.getEffectiveTimeLogicUnitDisplayName(),
    timeRole = value.getTimeRole(),
    analysisDimensionId = value.getAnalysisDimensionId(),
    analysisDimensionVersion = value.getAnalysisDimensionVersion(),
    analysisDimensionDisplayName = value.getAnalysisDimensionDisplayName(),
    levelCode = value.getLevelCode(),
    partitionLogicUnitId = if (value.hasPartitionLogicUnitId()) value.getPartitionLogicUnitId() else null,
    partitionLogicUnitVersion = if (value.hasPartitionLogicUnitVersion()) value.getPartitionLogicUnitVersion() else null,
    diagnosticJson = if (value.hasDiagnosticJson()) value.getDiagnosticJson() else null,
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)

internal fun ReleaseUnavailableReasonToProto(value: com.olap.prototype.release.contract.ReleaseUnavailableReason): com.olap.prototype.wire.governance.ReleaseUnavailableReason = com.olap.prototype.wire.governance.ReleaseUnavailableReason.newBuilder().apply {
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
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun ReleaseUnavailableReasonFromProto(value: com.olap.prototype.wire.governance.ReleaseUnavailableReason): com.olap.prototype.release.contract.ReleaseUnavailableReason = com.olap.prototype.release.contract.ReleaseUnavailableReason(
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
    executableBlocking = value.getExecutableBlocking(),
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)

internal fun ReleaseDiagnosticIssueToProto(value: com.olap.prototype.release.contract.ReleaseDiagnosticIssue): com.olap.prototype.wire.governance.ReleaseDiagnosticIssue = com.olap.prototype.wire.governance.ReleaseDiagnosticIssue.newBuilder().apply {
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
internal fun ReleaseDiagnosticIssueFromProto(value: com.olap.prototype.wire.governance.ReleaseDiagnosticIssue): com.olap.prototype.release.contract.ReleaseDiagnosticIssue = com.olap.prototype.release.contract.ReleaseDiagnosticIssue(
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

internal fun ReleaseJoinPathSnapshotToProto(value: com.olap.prototype.release.contract.ReleaseJoinPathSnapshot): com.olap.prototype.wire.governance.ReleaseJoinPathSnapshot = com.olap.prototype.wire.governance.ReleaseJoinPathSnapshot.newBuilder().apply {
    setJoinPathId(value.joinPathId)
    setJoinPathVersion(value.joinPathVersion)
    setTopologyId(value.topologyId)
    setTopologyVersion(value.topologyVersion)
    setSourceLogicalTableId(value.sourceLogicalTableId)
    setSourceLogicalTableVersion(value.sourceLogicalTableVersion)
    setTargetLogicalTableId(value.targetLogicalTableId)
    setTargetLogicalTableVersion(value.targetLogicalTableVersion)
    setJoinType(value.joinType)
    setCardinality(value.cardinality)
    value.temporal?.let { setTemporal(SemanticProto.toProto(it)) }
    setNullPolicy(value.nullPolicy)
    addAllKeys(value.keys.map { ReleaseJoinPathSnapshotKeyToProto(it) })
}.build()
internal fun ReleaseJoinPathSnapshotFromProto(value: com.olap.prototype.wire.governance.ReleaseJoinPathSnapshot): com.olap.prototype.release.contract.ReleaseJoinPathSnapshot = com.olap.prototype.release.contract.ReleaseJoinPathSnapshot(
    joinPathId = value.getJoinPathId(),
    joinPathVersion = value.getJoinPathVersion(),
    topologyId = value.getTopologyId(),
    topologyVersion = value.getTopologyVersion(),
    sourceLogicalTableId = value.getSourceLogicalTableId(),
    sourceLogicalTableVersion = value.getSourceLogicalTableVersion(),
    targetLogicalTableId = value.getTargetLogicalTableId(),
    targetLogicalTableVersion = value.getTargetLogicalTableVersion(),
    joinType = value.getJoinType(),
    cardinality = value.getCardinality(),
    temporal = if (value.hasTemporal()) SemanticProto.fromProto(value.getTemporal()) else null,
    nullPolicy = value.getNullPolicy(),
    keys = value.getKeysList().map { ReleaseJoinPathSnapshotKeyFromProto(it) }
)

internal fun ReleaseJoinPathSnapshotKeyToProto(value: com.olap.prototype.release.contract.ReleaseJoinPathSnapshot.Key): com.olap.prototype.wire.governance.ReleaseJoinPathSnapshotKey = com.olap.prototype.wire.governance.ReleaseJoinPathSnapshotKey.newBuilder().apply {
    setOrder(value.order)
    setSourceLogicUnitId(value.sourceLogicUnitId)
    setSourceLogicUnitVersion(value.sourceLogicUnitVersion)
    setTargetLogicUnitId(value.targetLogicUnitId)
    setTargetLogicUnitVersion(value.targetLogicUnitVersion)
}.build()
internal fun ReleaseJoinPathSnapshotKeyFromProto(value: com.olap.prototype.wire.governance.ReleaseJoinPathSnapshotKey): com.olap.prototype.release.contract.ReleaseJoinPathSnapshot.Key = com.olap.prototype.release.contract.ReleaseJoinPathSnapshot.Key(
    order = value.getOrder(),
    sourceLogicUnitId = value.getSourceLogicUnitId(),
    sourceLogicUnitVersion = value.getSourceLogicUnitVersion(),
    targetLogicUnitId = value.getTargetLogicUnitId(),
    targetLogicUnitVersion = value.getTargetLogicUnitVersion()
)

internal fun ScenarioMetricReferenceToProto(value: com.olap.prototype.release.contract.ScenarioMetricReference): com.olap.prototype.wire.governance.ScenarioMetricReference = com.olap.prototype.wire.governance.ScenarioMetricReference.newBuilder().apply {
    setAdoptionId(value.adoptionId)
    setAdoptionVersion(value.adoptionVersion)
    setAdoptionStatus(value.adoptionStatus)
    setScenarioMetricRefId(value.scenarioMetricRefId)
    setReleaseObjectRefId(value.releaseObjectRefId)
    setRequiredByDefault(value.requiredByDefault)
    setSortOrder(value.sortOrder)
}.build()
internal fun ScenarioMetricReferenceFromProto(value: com.olap.prototype.wire.governance.ScenarioMetricReference): com.olap.prototype.release.contract.ScenarioMetricReference = com.olap.prototype.release.contract.ScenarioMetricReference(
    adoptionId = value.getAdoptionId(),
    adoptionVersion = value.getAdoptionVersion(),
    adoptionStatus = value.getAdoptionStatus(),
    scenarioMetricRefId = value.getScenarioMetricRefId(),
    releaseObjectRefId = value.getReleaseObjectRefId(),
    requiredByDefault = value.getRequiredByDefault(),
    sortOrder = value.getSortOrder()
)

internal fun ReleaseScenarioSnapshotToProto(value: com.olap.prototype.release.contract.ReleaseScenarioSnapshot): com.olap.prototype.wire.governance.ReleaseScenarioSnapshot = com.olap.prototype.wire.governance.ReleaseScenarioSnapshot.newBuilder().apply {
    setFormatVersion(value.formatVersion)
    setScenario(QueryScenarioRefToProto(value.scenario))
    setSubjectArea(SubjectAreaCapabilityContextToProto(value.subjectArea))
    addAllMetrics(value.metrics.map { ScenarioMetricReferenceToProto(it) })
    addAllMembers(value.members.map { ReleaseScenarioSnapshotMemberGrantToProto(it) })
    addAllDimensions(value.dimensions.map { ReleaseScenarioSnapshotDimensionGrantToProto(it) })
    addAllFilters(value.filters.map { ReleaseScenarioSnapshotFilterGrantToProto(it) })
    addAllStages(value.stages.map { PostProcessCapabilityToProto(it) })
}.build()
internal fun ReleaseScenarioSnapshotFromProto(value: com.olap.prototype.wire.governance.ReleaseScenarioSnapshot): com.olap.prototype.release.contract.ReleaseScenarioSnapshot = com.olap.prototype.release.contract.ReleaseScenarioSnapshot(
    formatVersion = value.getFormatVersion(),
    scenario = required(value.hasScenario(), "ReleaseScenarioSnapshot.scenario") { QueryScenarioRefFromProto(value.getScenario()) },
    subjectArea = required(value.hasSubjectArea(), "ReleaseScenarioSnapshot.subjectArea") { SubjectAreaCapabilityContextFromProto(value.getSubjectArea()) },
    metrics = value.getMetricsList().map { ScenarioMetricReferenceFromProto(it) },
    members = value.getMembersList().map { ReleaseScenarioSnapshotMemberGrantFromProto(it) },
    dimensions = value.getDimensionsList().map { ReleaseScenarioSnapshotDimensionGrantFromProto(it) },
    filters = value.getFiltersList().map { ReleaseScenarioSnapshotFilterGrantFromProto(it) },
    stages = value.getStagesList().map { PostProcessCapabilityFromProto(it) }
)

internal fun ReleaseScenarioSnapshotMemberGrantToProto(value: com.olap.prototype.release.contract.ReleaseScenarioSnapshot.MemberGrant): com.olap.prototype.wire.governance.ReleaseScenarioSnapshotMemberGrant = com.olap.prototype.wire.governance.ReleaseScenarioSnapshotMemberGrant.newBuilder().apply {
    setSourceId(value.sourceId)
    setSourceVersion(value.sourceVersion)
    setPermission(MemberQueryPermissionToProto(value.permission))
}.build()
internal fun ReleaseScenarioSnapshotMemberGrantFromProto(value: com.olap.prototype.wire.governance.ReleaseScenarioSnapshotMemberGrant): com.olap.prototype.release.contract.ReleaseScenarioSnapshot.MemberGrant = com.olap.prototype.release.contract.ReleaseScenarioSnapshot.MemberGrant(
    sourceId = value.getSourceId(),
    sourceVersion = value.getSourceVersion(),
    permission = required(value.hasPermission(), "ReleaseScenarioSnapshotMemberGrant.permission") { MemberQueryPermissionFromProto(value.getPermission()) }
)

internal fun ReleaseScenarioSnapshotDimensionGrantToProto(value: com.olap.prototype.release.contract.ReleaseScenarioSnapshot.DimensionGrant): com.olap.prototype.wire.governance.ReleaseScenarioSnapshotDimensionGrant = com.olap.prototype.wire.governance.ReleaseScenarioSnapshotDimensionGrant.newBuilder().apply {
    setId(value.id)
    setVersion(value.version)
    setDisplayName(value.displayName)
    setOutputAlias(value.outputAlias)
    setSubjectAreaRefId(value.subjectAreaRefId)
    setSubjectAreaRefStatus(value.subjectAreaRefStatus)
    setScenarioRefId(value.scenarioRefId)
    setReleaseObjectRefId(value.releaseObjectRefId)
    setDefaultLevelCode(value.defaultLevelCode)
    setDefaultLevelDisplayName(value.defaultLevelDisplayName)
    setRequiredByDefault(value.requiredByDefault)
    setSortOrder(value.sortOrder)
}.build()
internal fun ReleaseScenarioSnapshotDimensionGrantFromProto(value: com.olap.prototype.wire.governance.ReleaseScenarioSnapshotDimensionGrant): com.olap.prototype.release.contract.ReleaseScenarioSnapshot.DimensionGrant = com.olap.prototype.release.contract.ReleaseScenarioSnapshot.DimensionGrant(
    id = value.getId(),
    version = value.getVersion(),
    displayName = value.getDisplayName(),
    outputAlias = value.getOutputAlias(),
    subjectAreaRefId = value.getSubjectAreaRefId(),
    subjectAreaRefStatus = value.getSubjectAreaRefStatus(),
    scenarioRefId = value.getScenarioRefId(),
    releaseObjectRefId = value.getReleaseObjectRefId(),
    defaultLevelCode = value.getDefaultLevelCode(),
    defaultLevelDisplayName = value.getDefaultLevelDisplayName(),
    requiredByDefault = value.getRequiredByDefault(),
    sortOrder = value.getSortOrder()
)

internal fun ReleaseScenarioSnapshotFilterGrantToProto(value: com.olap.prototype.release.contract.ReleaseScenarioSnapshot.FilterGrant): com.olap.prototype.wire.governance.ReleaseScenarioSnapshotFilterGrant = com.olap.prototype.wire.governance.ReleaseScenarioSnapshotFilterGrant.newBuilder().apply {
    setId(value.id)
    setTargetType(value.targetType)
    setTargetId(value.targetId)
    setTargetVersion(value.targetVersion)
    addAllOperators(value.operators.map { it })
    value.valueProviderId?.let { setValueProviderId(it) }
    value.valueProviderType?.let { setValueProviderType(it) }
    value.valueProviderLogicUnitId?.let { setValueProviderLogicUnitId(it) }
    value.valueProviderLogicUnitVersion?.let { setValueProviderLogicUnitVersion(it) }
    setRequiredByDefault(value.requiredByDefault)
    setSortOrder(value.sortOrder)
}.build()
internal fun ReleaseScenarioSnapshotFilterGrantFromProto(value: com.olap.prototype.wire.governance.ReleaseScenarioSnapshotFilterGrant): com.olap.prototype.release.contract.ReleaseScenarioSnapshot.FilterGrant = com.olap.prototype.release.contract.ReleaseScenarioSnapshot.FilterGrant(
    id = value.getId(),
    targetType = value.getTargetType(),
    targetId = value.getTargetId(),
    targetVersion = value.getTargetVersion(),
    operators = value.getOperatorsList().map { it },
    valueProviderId = if (value.hasValueProviderId()) value.getValueProviderId() else null,
    valueProviderType = if (value.hasValueProviderType()) value.getValueProviderType() else null,
    valueProviderLogicUnitId = if (value.hasValueProviderLogicUnitId()) value.getValueProviderLogicUnitId() else null,
    valueProviderLogicUnitVersion = if (value.hasValueProviderLogicUnitVersion()) value.getValueProviderLogicUnitVersion() else null,
    requiredByDefault = value.getRequiredByDefault(),
    sortOrder = value.getSortOrder()
)

internal fun ReleasedScenarioEntrypointToProto(value: com.olap.prototype.release.catalog.capability.ReleasedScenarioEntrypoint): com.olap.prototype.wire.governance.ReleasedScenarioEntrypoint = com.olap.prototype.wire.governance.ReleasedScenarioEntrypoint.newBuilder().apply {
    setScenarioId(value.scenarioId)
    setScenarioVersion(value.scenarioVersion)
    setScenarioDisplayName(value.scenarioDisplayName)
    setSubjectAreaId(value.subjectAreaId)
    setSubjectAreaVersion(value.subjectAreaVersion)
    setSubjectAreaDisplayName(value.subjectAreaDisplayName)
    setReleaseId(value.releaseId)
    setReleaseVersion(value.releaseVersion)
    setReleaseDisplayName(value.releaseDisplayName)
    setReleasedAt(value.releasedAt.toString())
}.build()
internal fun ReleasedScenarioEntrypointFromProto(value: com.olap.prototype.wire.governance.ReleasedScenarioEntrypoint): com.olap.prototype.release.catalog.capability.ReleasedScenarioEntrypoint = com.olap.prototype.release.catalog.capability.ReleasedScenarioEntrypoint(
    scenarioId = value.getScenarioId(),
    scenarioVersion = value.getScenarioVersion(),
    scenarioDisplayName = value.getScenarioDisplayName(),
    subjectAreaId = value.getSubjectAreaId(),
    subjectAreaVersion = value.getSubjectAreaVersion(),
    subjectAreaDisplayName = value.getSubjectAreaDisplayName(),
    releaseId = value.getReleaseId(),
    releaseVersion = value.getReleaseVersion(),
    releaseDisplayName = value.getReleaseDisplayName(),
    releasedAt = java.time.Instant.parse(value.getReleasedAt())
)
