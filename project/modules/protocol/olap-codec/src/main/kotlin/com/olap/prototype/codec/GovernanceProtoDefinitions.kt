package com.olap.prototype.codec

/** 正式领域消息的显式字段转换。 */
internal fun DefinitionImpactRequestToProto(value: com.olap.prototype.governance.contract.DefinitionImpactRequest): com.olap.prototype.wire.governance.DefinitionImpactRequest = com.olap.prototype.wire.governance.DefinitionImpactRequest.newBuilder().apply {
    setAssetType(value.assetType)
    setId(value.id)
    setVersion(value.version)
}.build()
internal fun DefinitionImpactRequestFromProto(value: com.olap.prototype.wire.governance.DefinitionImpactRequest): com.olap.prototype.governance.contract.DefinitionImpactRequest = com.olap.prototype.governance.contract.DefinitionImpactRequest(
    assetType = value.getAssetType(),
    id = value.getId(),
    version = value.getVersion()
)

internal fun DefinitionImpactToProto(value: com.olap.prototype.governance.contract.DefinitionImpact): com.olap.prototype.wire.governance.DefinitionImpact = com.olap.prototype.wire.governance.DefinitionImpact.newBuilder().apply {
    setTarget(DefinitionImpactReferenceToProto(value.target))
    addAllDependents(value.dependents.map { DefinitionImpactDependentToProto(it) })
}.build()
internal fun DefinitionImpactFromProto(value: com.olap.prototype.wire.governance.DefinitionImpact): com.olap.prototype.governance.contract.DefinitionImpact = com.olap.prototype.governance.contract.DefinitionImpact(
    target = required(value.hasTarget(), "DefinitionImpact.target") { DefinitionImpactReferenceFromProto(value.getTarget()) },
    dependents = value.getDependentsList().map { DefinitionImpactDependentFromProto(it) }
)

internal fun DefinitionImpactReferenceToProto(value: com.olap.prototype.governance.contract.DefinitionImpact.Reference): com.olap.prototype.wire.governance.DefinitionImpactReference = com.olap.prototype.wire.governance.DefinitionImpactReference.newBuilder().apply {
    setAssetType(value.assetType)
    setId(value.id)
    setVersion(value.version)
}.build()
internal fun DefinitionImpactReferenceFromProto(value: com.olap.prototype.wire.governance.DefinitionImpactReference): com.olap.prototype.governance.contract.DefinitionImpact.Reference = com.olap.prototype.governance.contract.DefinitionImpact.Reference(
    assetType = value.getAssetType(),
    id = value.getId(),
    version = value.getVersion()
)

internal fun DefinitionImpactDependentToProto(value: com.olap.prototype.governance.contract.DefinitionImpact.Dependent): com.olap.prototype.wire.governance.DefinitionImpactDependent = com.olap.prototype.wire.governance.DefinitionImpactDependent.newBuilder().apply {
    setReference(DefinitionImpactReferenceToProto(value.reference))
    addAllPath(value.path.map { DefinitionImpactStepToProto(it) })
}.build()
internal fun DefinitionImpactDependentFromProto(value: com.olap.prototype.wire.governance.DefinitionImpactDependent): com.olap.prototype.governance.contract.DefinitionImpact.Dependent = com.olap.prototype.governance.contract.DefinitionImpact.Dependent(
    reference = required(value.hasReference(), "DefinitionImpactDependent.reference") { DefinitionImpactReferenceFromProto(value.getReference()) },
    path = value.getPathList().map { DefinitionImpactStepFromProto(it) }
)

internal fun DefinitionImpactStepToProto(value: com.olap.prototype.governance.contract.DefinitionImpact.Step): com.olap.prototype.wire.governance.DefinitionImpactStep = com.olap.prototype.wire.governance.DefinitionImpactStep.newBuilder().apply {
    setConsumer(DefinitionImpactReferenceToProto(value.consumer))
    setRelation(value.relation.name)
}.build()
internal fun DefinitionImpactStepFromProto(value: com.olap.prototype.wire.governance.DefinitionImpactStep): com.olap.prototype.governance.contract.DefinitionImpact.Step = com.olap.prototype.governance.contract.DefinitionImpact.Step(
    consumer = required(value.hasConsumer(), "DefinitionImpactStep.consumer") { DefinitionImpactReferenceFromProto(value.getConsumer()) },
    relation = com.olap.prototype.governance.contract.DefinitionImpact.Relation.valueOf(value.getRelation())
)

internal fun DefinitionRevisionDiffRequestToProto(value: com.olap.prototype.governance.contract.DefinitionRevisionDiffRequest): com.olap.prototype.wire.governance.DefinitionRevisionDiffRequest = com.olap.prototype.wire.governance.DefinitionRevisionDiffRequest.newBuilder().apply {
    setAssetType(value.assetType)
    setId(value.id)
    setFromVersion(value.fromVersion)
    setToVersion(value.toVersion)
}.build()
internal fun DefinitionRevisionDiffRequestFromProto(value: com.olap.prototype.wire.governance.DefinitionRevisionDiffRequest): com.olap.prototype.governance.contract.DefinitionRevisionDiffRequest = com.olap.prototype.governance.contract.DefinitionRevisionDiffRequest(
    assetType = value.getAssetType(),
    id = value.getId(),
    fromVersion = value.getFromVersion(),
    toVersion = value.getToVersion()
)

internal fun DefinitionRevisionDiffToProto(value: com.olap.prototype.governance.contract.DefinitionRevisionDiff): com.olap.prototype.wire.governance.DefinitionRevisionDiff = com.olap.prototype.wire.governance.DefinitionRevisionDiff.newBuilder().apply {
    setBefore(DefinitionImpactReferenceToProto(value.before))
    setAfter(DefinitionImpactReferenceToProto(value.after))
    addAllChanges(value.changes.map { DefinitionRevisionDiffChangeToProto(it) })
    addAllAddedDependencies(value.addedDependencies.map { DefinitionImpactReferenceToProto(it) })
    addAllRemovedDependencies(value.removedDependencies.map { DefinitionImpactReferenceToProto(it) })
}.build()
internal fun DefinitionRevisionDiffFromProto(value: com.olap.prototype.wire.governance.DefinitionRevisionDiff): com.olap.prototype.governance.contract.DefinitionRevisionDiff = com.olap.prototype.governance.contract.DefinitionRevisionDiff(
    before = required(value.hasBefore(), "DefinitionRevisionDiff.before") { DefinitionImpactReferenceFromProto(value.getBefore()) },
    after = required(value.hasAfter(), "DefinitionRevisionDiff.after") { DefinitionImpactReferenceFromProto(value.getAfter()) },
    changes = value.getChangesList().map { DefinitionRevisionDiffChangeFromProto(it) },
    addedDependencies = value.getAddedDependenciesList().map { DefinitionImpactReferenceFromProto(it) },
    removedDependencies = value.getRemovedDependenciesList().map { DefinitionImpactReferenceFromProto(it) }
)

internal fun DefinitionRevisionDiffChangeToProto(value: com.olap.prototype.governance.contract.DefinitionRevisionDiff.Change): com.olap.prototype.wire.governance.DefinitionRevisionDiffChange = com.olap.prototype.wire.governance.DefinitionRevisionDiffChange.newBuilder().apply {
    setPath(value.path)
    setClassification(value.classification.name)
    value.before?.let { setBefore(DefinitionRevisionDiffValueToProto(it)) }
    value.after?.let { setAfter(DefinitionRevisionDiffValueToProto(it)) }
}.build()
internal fun DefinitionRevisionDiffChangeFromProto(value: com.olap.prototype.wire.governance.DefinitionRevisionDiffChange): com.olap.prototype.governance.contract.DefinitionRevisionDiff.Change = com.olap.prototype.governance.contract.DefinitionRevisionDiff.Change(
    path = value.getPath(),
    classification = com.olap.prototype.governance.contract.DefinitionRevisionDiff.Classification.valueOf(value.getClassification()),
    before = if (value.hasBefore()) DefinitionRevisionDiffValueFromProto(value.getBefore()) else null,
    after = if (value.hasAfter()) DefinitionRevisionDiffValueFromProto(value.getAfter()) else null
)

internal fun DefinitionRevisionDiffValueToProto(value: com.olap.prototype.governance.contract.DefinitionRevisionDiff.Value): com.olap.prototype.wire.governance.DefinitionRevisionDiffValue = com.olap.prototype.wire.governance.DefinitionRevisionDiffValue.newBuilder().apply { when (value) {
    is com.olap.prototype.governance.contract.DefinitionRevisionDiff.Value.Definition -> setDefinition(DefinitionRevisionDiffValueDefinitionToProto(value))
    is com.olap.prototype.governance.contract.DefinitionRevisionDiff.Value.MetricAdoptionValue -> setMetricAdoptionValue(DefinitionRevisionDiffValueMetricAdoptionValueToProto(value))
    is com.olap.prototype.governance.contract.DefinitionRevisionDiff.Value.MemberSourceValue -> setMemberSourceValue(DefinitionRevisionDiffValueMemberSourceValueToProto(value))
    is com.olap.prototype.governance.contract.DefinitionRevisionDiff.Value.Text -> setText(DefinitionRevisionDiffValueTextToProto(value))
} }.build()
internal fun DefinitionRevisionDiffValueFromProto(value: com.olap.prototype.wire.governance.DefinitionRevisionDiffValue): com.olap.prototype.governance.contract.DefinitionRevisionDiff.Value = when (value.kindCase) {
    com.olap.prototype.wire.governance.DefinitionRevisionDiffValue.KindCase.DEFINITION -> DefinitionRevisionDiffValueDefinitionFromProto(value.getDefinition())
    com.olap.prototype.wire.governance.DefinitionRevisionDiffValue.KindCase.METRIC_ADOPTION_VALUE -> DefinitionRevisionDiffValueMetricAdoptionValueFromProto(value.getMetricAdoptionValue())
    com.olap.prototype.wire.governance.DefinitionRevisionDiffValue.KindCase.MEMBER_SOURCE_VALUE -> DefinitionRevisionDiffValueMemberSourceValueFromProto(value.getMemberSourceValue())
    com.olap.prototype.wire.governance.DefinitionRevisionDiffValue.KindCase.TEXT -> DefinitionRevisionDiffValueTextFromProto(value.getText())
    com.olap.prototype.wire.governance.DefinitionRevisionDiffValue.KindCase.KIND_NOT_SET -> error("DefinitionRevisionDiffValue variant is required")
}

internal fun DefinitionRevisionDiffValueDefinitionToProto(value: com.olap.prototype.governance.contract.DefinitionRevisionDiff.Value.Definition): com.olap.prototype.wire.governance.DefinitionRevisionDiffValueDefinition = com.olap.prototype.wire.governance.DefinitionRevisionDiffValueDefinition.newBuilder().apply {
    setValue(GovernanceDefinitionToProto(value.value))
}.build()
internal fun DefinitionRevisionDiffValueDefinitionFromProto(value: com.olap.prototype.wire.governance.DefinitionRevisionDiffValueDefinition): com.olap.prototype.governance.contract.DefinitionRevisionDiff.Value.Definition = com.olap.prototype.governance.contract.DefinitionRevisionDiff.Value.Definition(
    value = required(value.hasValue(), "DefinitionRevisionDiffValueDefinition.value") { GovernanceDefinitionFromProto(value.getValue()) }
)

internal fun GovernanceDefinitionToProto(value: com.olap.prototype.governance.contract.GovernanceDefinition): com.olap.prototype.wire.governance.GovernanceDefinition = com.olap.prototype.wire.governance.GovernanceDefinition.newBuilder().apply { when (value) {
    is com.olap.prototype.governance.contract.GovernanceDefinition.PhysicalTable -> setPhysicalTable(GovernanceDefinitionPhysicalTableToProto(value))
    is com.olap.prototype.governance.contract.GovernanceDefinition.PhysicalColumn -> setPhysicalColumn(GovernanceDefinitionPhysicalColumnToProto(value))
    is com.olap.prototype.governance.contract.GovernanceDefinition.LogicalTable -> setLogicalTable(GovernanceDefinitionLogicalTableToProto(value))
    is com.olap.prototype.governance.contract.GovernanceDefinition.LogicUnit -> setLogicUnit(GovernanceDefinitionLogicUnitToProto(value))
    is com.olap.prototype.governance.contract.GovernanceDefinition.Topology -> setTopology(GovernanceDefinitionTopologyToProto(value))
    is com.olap.prototype.governance.contract.GovernanceDefinition.JoinPath -> setJoinPath(GovernanceDefinitionJoinPathToProto(value))
    is com.olap.prototype.governance.contract.GovernanceDefinition.Event -> setEvent(GovernanceDefinitionEventToProto(value))
    is com.olap.prototype.governance.contract.GovernanceDefinition.Metric -> setMetric(GovernanceDefinitionMetricToProto(value))
    is com.olap.prototype.governance.contract.GovernanceDefinition.Dimension -> setDimension(GovernanceDefinitionDimensionToProto(value))
    is com.olap.prototype.governance.contract.GovernanceDefinition.DimensionBinding -> setDimensionBinding(GovernanceDefinitionDimensionBindingToProto(value))
    is com.olap.prototype.governance.contract.GovernanceDefinition.BusinessLine -> setBusinessLine(GovernanceDefinitionBusinessLineToProto(value))
    is com.olap.prototype.governance.contract.GovernanceDefinition.SubjectArea -> setSubjectArea(GovernanceDefinitionSubjectAreaToProto(value))
    is com.olap.prototype.governance.contract.GovernanceDefinition.AnalysisDimension -> setAnalysisDimension(GovernanceDefinitionAnalysisDimensionToProto(value))
    is com.olap.prototype.governance.contract.GovernanceDefinition.Scenario -> setScenario(GovernanceDefinitionScenarioToProto(value))
} }.build()
internal fun GovernanceDefinitionFromProto(value: com.olap.prototype.wire.governance.GovernanceDefinition): com.olap.prototype.governance.contract.GovernanceDefinition = when (value.kindCase) {
    com.olap.prototype.wire.governance.GovernanceDefinition.KindCase.PHYSICAL_TABLE -> GovernanceDefinitionPhysicalTableFromProto(value.getPhysicalTable())
    com.olap.prototype.wire.governance.GovernanceDefinition.KindCase.PHYSICAL_COLUMN -> GovernanceDefinitionPhysicalColumnFromProto(value.getPhysicalColumn())
    com.olap.prototype.wire.governance.GovernanceDefinition.KindCase.LOGICAL_TABLE -> GovernanceDefinitionLogicalTableFromProto(value.getLogicalTable())
    com.olap.prototype.wire.governance.GovernanceDefinition.KindCase.LOGIC_UNIT -> GovernanceDefinitionLogicUnitFromProto(value.getLogicUnit())
    com.olap.prototype.wire.governance.GovernanceDefinition.KindCase.TOPOLOGY -> GovernanceDefinitionTopologyFromProto(value.getTopology())
    com.olap.prototype.wire.governance.GovernanceDefinition.KindCase.JOIN_PATH -> GovernanceDefinitionJoinPathFromProto(value.getJoinPath())
    com.olap.prototype.wire.governance.GovernanceDefinition.KindCase.EVENT -> GovernanceDefinitionEventFromProto(value.getEvent())
    com.olap.prototype.wire.governance.GovernanceDefinition.KindCase.METRIC -> GovernanceDefinitionMetricFromProto(value.getMetric())
    com.olap.prototype.wire.governance.GovernanceDefinition.KindCase.DIMENSION -> GovernanceDefinitionDimensionFromProto(value.getDimension())
    com.olap.prototype.wire.governance.GovernanceDefinition.KindCase.DIMENSION_BINDING -> GovernanceDefinitionDimensionBindingFromProto(value.getDimensionBinding())
    com.olap.prototype.wire.governance.GovernanceDefinition.KindCase.BUSINESS_LINE -> GovernanceDefinitionBusinessLineFromProto(value.getBusinessLine())
    com.olap.prototype.wire.governance.GovernanceDefinition.KindCase.SUBJECT_AREA -> GovernanceDefinitionSubjectAreaFromProto(value.getSubjectArea())
    com.olap.prototype.wire.governance.GovernanceDefinition.KindCase.ANALYSIS_DIMENSION -> GovernanceDefinitionAnalysisDimensionFromProto(value.getAnalysisDimension())
    com.olap.prototype.wire.governance.GovernanceDefinition.KindCase.SCENARIO -> GovernanceDefinitionScenarioFromProto(value.getScenario())
    com.olap.prototype.wire.governance.GovernanceDefinition.KindCase.KIND_NOT_SET -> error("GovernanceDefinition variant is required")
}

internal fun GovernanceDefinitionPhysicalTableToProto(value: com.olap.prototype.governance.contract.GovernanceDefinition.PhysicalTable): com.olap.prototype.wire.governance.GovernanceDefinitionPhysicalTable = com.olap.prototype.wire.governance.GovernanceDefinitionPhysicalTable.newBuilder().apply {
    setRecord(PhysicalTableRecordToProto(value.record))
    addAllColumnVersionRefs(value.columnVersionRefs.map { GovernanceDefinitionVersionRefToProto(it) })
    setSourceConstraints(PhysicalSourceConstraintsToProto(value.sourceConstraints))
    value.dataDomain?.let { setDataDomain(it) }
    value.sensitivityLevel?.let { setSensitivityLevel(it) }
    value.sourceSystem?.let { setSourceSystem(it) }
}.build()
internal fun GovernanceDefinitionPhysicalTableFromProto(value: com.olap.prototype.wire.governance.GovernanceDefinitionPhysicalTable): com.olap.prototype.governance.contract.GovernanceDefinition.PhysicalTable = com.olap.prototype.governance.contract.GovernanceDefinition.PhysicalTable(
    record = required(value.hasRecord(), "GovernanceDefinitionPhysicalTable.record") { PhysicalTableRecordFromProto(value.getRecord()) },
    columnVersionRefs = value.getColumnVersionRefsList().map { GovernanceDefinitionVersionRefFromProto(it) },
    sourceConstraints = required(value.hasSourceConstraints(), "GovernanceDefinitionPhysicalTable.sourceConstraints") { PhysicalSourceConstraintsFromProto(value.getSourceConstraints()) },
    dataDomain = if (value.hasDataDomain()) value.getDataDomain() else null,
    sensitivityLevel = if (value.hasSensitivityLevel()) value.getSensitivityLevel() else null,
    sourceSystem = if (value.hasSourceSystem()) value.getSourceSystem() else null
)

internal fun GovernanceDefinitionVersionRefToProto(value: com.olap.prototype.governance.contract.GovernanceDefinition.VersionRef): com.olap.prototype.wire.governance.GovernanceDefinitionVersionRef = com.olap.prototype.wire.governance.GovernanceDefinitionVersionRef.newBuilder().apply {
    setId(value.id)
    setVersion(value.version)
}.build()
internal fun GovernanceDefinitionVersionRefFromProto(value: com.olap.prototype.wire.governance.GovernanceDefinitionVersionRef): com.olap.prototype.governance.contract.GovernanceDefinition.VersionRef = com.olap.prototype.governance.contract.GovernanceDefinition.VersionRef(
    id = value.getId(),
    version = value.getVersion()
)

internal fun GovernanceDefinitionPhysicalColumnToProto(value: com.olap.prototype.governance.contract.GovernanceDefinition.PhysicalColumn): com.olap.prototype.wire.governance.GovernanceDefinitionPhysicalColumn = com.olap.prototype.wire.governance.GovernanceDefinitionPhysicalColumn.newBuilder().apply {
    setRecord(PhysicalColumnRecordToProto(value.record))
    value.sensitivityLevel?.let { setSensitivityLevel(it) }
}.build()
internal fun GovernanceDefinitionPhysicalColumnFromProto(value: com.olap.prototype.wire.governance.GovernanceDefinitionPhysicalColumn): com.olap.prototype.governance.contract.GovernanceDefinition.PhysicalColumn = com.olap.prototype.governance.contract.GovernanceDefinition.PhysicalColumn(
    record = required(value.hasRecord(), "GovernanceDefinitionPhysicalColumn.record") { PhysicalColumnRecordFromProto(value.getRecord()) },
    sensitivityLevel = if (value.hasSensitivityLevel()) value.getSensitivityLevel() else null
)

internal fun GovernanceDefinitionLogicalTableToProto(value: com.olap.prototype.governance.contract.GovernanceDefinition.LogicalTable): com.olap.prototype.wire.governance.GovernanceDefinitionLogicalTable = com.olap.prototype.wire.governance.GovernanceDefinitionLogicalTable.newBuilder().apply {
    setRecord(LogicalTableRecordToProto(value.record))
    setPhysicalTableId(value.physicalTableId)
    setPhysicalTableVersion(value.physicalTableVersion)
    setLogicUnitCount(value.logicUnitCount)
}.build()
internal fun GovernanceDefinitionLogicalTableFromProto(value: com.olap.prototype.wire.governance.GovernanceDefinitionLogicalTable): com.olap.prototype.governance.contract.GovernanceDefinition.LogicalTable = com.olap.prototype.governance.contract.GovernanceDefinition.LogicalTable(
    record = required(value.hasRecord(), "GovernanceDefinitionLogicalTable.record") { LogicalTableRecordFromProto(value.getRecord()) },
    physicalTableId = value.getPhysicalTableId(),
    physicalTableVersion = value.getPhysicalTableVersion(),
    logicUnitCount = value.getLogicUnitCount()
)

internal fun GovernanceDefinitionLogicUnitToProto(value: com.olap.prototype.governance.contract.GovernanceDefinition.LogicUnit): com.olap.prototype.wire.governance.GovernanceDefinitionLogicUnit = com.olap.prototype.wire.governance.GovernanceDefinitionLogicUnit.newBuilder().apply {
    setRecord(LogicUnitRecordToProto(value.record))
    addAllSourceRefs(value.sourceRefs.map { LogicalTableCreateRequestSourceRefInputToProto(it) })
}.build()
internal fun GovernanceDefinitionLogicUnitFromProto(value: com.olap.prototype.wire.governance.GovernanceDefinitionLogicUnit): com.olap.prototype.governance.contract.GovernanceDefinition.LogicUnit = com.olap.prototype.governance.contract.GovernanceDefinition.LogicUnit(
    record = required(value.hasRecord(), "GovernanceDefinitionLogicUnit.record") { LogicUnitRecordFromProto(value.getRecord()) },
    sourceRefs = value.getSourceRefsList().map { LogicalTableCreateRequestSourceRefInputFromProto(it) }
)

internal fun GovernanceDefinitionTopologyToProto(value: com.olap.prototype.governance.contract.GovernanceDefinition.Topology): com.olap.prototype.wire.governance.GovernanceDefinitionTopology = com.olap.prototype.wire.governance.GovernanceDefinitionTopology.newBuilder().apply {
    setRecord(TopologyRecordToProto(value.record))
}.build()
internal fun GovernanceDefinitionTopologyFromProto(value: com.olap.prototype.wire.governance.GovernanceDefinitionTopology): com.olap.prototype.governance.contract.GovernanceDefinition.Topology = com.olap.prototype.governance.contract.GovernanceDefinition.Topology(
    record = required(value.hasRecord(), "GovernanceDefinitionTopology.record") { TopologyRecordFromProto(value.getRecord()) }
)

internal fun GovernanceDefinitionJoinPathToProto(value: com.olap.prototype.governance.contract.GovernanceDefinition.JoinPath): com.olap.prototype.wire.governance.GovernanceDefinitionJoinPath = com.olap.prototype.wire.governance.GovernanceDefinitionJoinPath.newBuilder().apply {
    setRecord(JoinPathRecordToProto(value.record))
}.build()
internal fun GovernanceDefinitionJoinPathFromProto(value: com.olap.prototype.wire.governance.GovernanceDefinitionJoinPath): com.olap.prototype.governance.contract.GovernanceDefinition.JoinPath = com.olap.prototype.governance.contract.GovernanceDefinition.JoinPath(
    record = required(value.hasRecord(), "GovernanceDefinitionJoinPath.record") { JoinPathRecordFromProto(value.getRecord()) }
)

internal fun GovernanceDefinitionEventToProto(value: com.olap.prototype.governance.contract.GovernanceDefinition.Event): com.olap.prototype.wire.governance.GovernanceDefinitionEvent = com.olap.prototype.wire.governance.GovernanceDefinitionEvent.newBuilder().apply {
    setRecord(EventRecordToProto(value.record))
    setIdentity(SemanticProto.toProto(value.identity))
    addAllRoles(value.roles.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.GovernanceDefinitionEventRolesEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    addAllFilterableLogicUnitRefs(value.filterableLogicUnitRefs.map { EventCreateRequestLogicUnitRefInputToProto(it) })
    addAllMeasureLogicUnitRefs(value.measureLogicUnitRefs.map { EventCreateRequestLogicUnitRefInputToProto(it) })
}.build()
internal fun GovernanceDefinitionEventFromProto(value: com.olap.prototype.wire.governance.GovernanceDefinitionEvent): com.olap.prototype.governance.contract.GovernanceDefinition.Event = com.olap.prototype.governance.contract.GovernanceDefinition.Event(
    record = required(value.hasRecord(), "GovernanceDefinitionEvent.record") { EventRecordFromProto(value.getRecord()) },
    identity = required(value.hasIdentity(), "GovernanceDefinitionEvent.identity") { SemanticProto.fromProto(value.getIdentity()) as com.olap.prototype.event.EventIdentity },
    roles = strictMap(value.getRolesList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.event.RelationAccessPath }),
    filterableLogicUnitRefs = value.getFilterableLogicUnitRefsList().map { EventCreateRequestLogicUnitRefInputFromProto(it) },
    measureLogicUnitRefs = value.getMeasureLogicUnitRefsList().map { EventCreateRequestLogicUnitRefInputFromProto(it) }
)

internal fun GovernanceDefinitionMetricToProto(value: com.olap.prototype.governance.contract.GovernanceDefinition.Metric): com.olap.prototype.wire.governance.GovernanceDefinitionMetric = com.olap.prototype.wire.governance.GovernanceDefinitionMetric.newBuilder().apply {
    setRecord(MetricRecordToProto(value.record))
    setBusinessMeaning(SemanticProto.toProto(value.businessMeaning))
}.build()
internal fun GovernanceDefinitionMetricFromProto(value: com.olap.prototype.wire.governance.GovernanceDefinitionMetric): com.olap.prototype.governance.contract.GovernanceDefinition.Metric = com.olap.prototype.governance.contract.GovernanceDefinition.Metric(
    record = required(value.hasRecord(), "GovernanceDefinitionMetric.record") { MetricRecordFromProto(value.getRecord()) },
    businessMeaning = required(value.hasBusinessMeaning(), "GovernanceDefinitionMetric.businessMeaning") { SemanticProto.fromProto(value.getBusinessMeaning()) as com.olap.prototype.metric.MetricBusinessMeaning }
)

internal fun GovernanceDefinitionDimensionToProto(value: com.olap.prototype.governance.contract.GovernanceDefinition.Dimension): com.olap.prototype.wire.governance.GovernanceDefinitionDimension = com.olap.prototype.wire.governance.GovernanceDefinitionDimension.newBuilder().apply {
    setRecord(DimensionRecordToProto(value.record))
}.build()
internal fun GovernanceDefinitionDimensionFromProto(value: com.olap.prototype.wire.governance.GovernanceDefinitionDimension): com.olap.prototype.governance.contract.GovernanceDefinition.Dimension = com.olap.prototype.governance.contract.GovernanceDefinition.Dimension(
    record = required(value.hasRecord(), "GovernanceDefinitionDimension.record") { DimensionRecordFromProto(value.getRecord()) }
)

internal fun GovernanceDefinitionDimensionBindingToProto(value: com.olap.prototype.governance.contract.GovernanceDefinition.DimensionBinding): com.olap.prototype.wire.governance.GovernanceDefinitionDimensionBinding = com.olap.prototype.wire.governance.GovernanceDefinitionDimensionBinding.newBuilder().apply {
    setRecord(DimensionEventBindingRecordToProto(value.record))
    setAccessPath(SemanticProto.toProto(value.accessPath))
    addAllRoles(value.roles.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.GovernanceDefinitionDimensionBindingRolesEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
}.build()
internal fun GovernanceDefinitionDimensionBindingFromProto(value: com.olap.prototype.wire.governance.GovernanceDefinitionDimensionBinding): com.olap.prototype.governance.contract.GovernanceDefinition.DimensionBinding = com.olap.prototype.governance.contract.GovernanceDefinition.DimensionBinding(
    record = required(value.hasRecord(), "GovernanceDefinitionDimensionBinding.record") { DimensionEventBindingRecordFromProto(value.getRecord()) },
    accessPath = required(value.hasAccessPath(), "GovernanceDefinitionDimensionBinding.accessPath") { SemanticProto.fromProto(value.getAccessPath()) as com.olap.prototype.event.RelationAccessPath },
    roles = strictMap(value.getRolesList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.event.RelationAccessPath })
)

internal fun GovernanceDefinitionBusinessLineToProto(value: com.olap.prototype.governance.contract.GovernanceDefinition.BusinessLine): com.olap.prototype.wire.governance.GovernanceDefinitionBusinessLine = com.olap.prototype.wire.governance.GovernanceDefinitionBusinessLine.newBuilder().apply {
    setRecord(BusinessLineRecordToProto(value.record))
}.build()
internal fun GovernanceDefinitionBusinessLineFromProto(value: com.olap.prototype.wire.governance.GovernanceDefinitionBusinessLine): com.olap.prototype.governance.contract.GovernanceDefinition.BusinessLine = com.olap.prototype.governance.contract.GovernanceDefinition.BusinessLine(
    record = required(value.hasRecord(), "GovernanceDefinitionBusinessLine.record") { BusinessLineRecordFromProto(value.getRecord()) }
)

internal fun GovernanceDefinitionSubjectAreaToProto(value: com.olap.prototype.governance.contract.GovernanceDefinition.SubjectArea): com.olap.prototype.wire.governance.GovernanceDefinitionSubjectArea = com.olap.prototype.wire.governance.GovernanceDefinitionSubjectArea.newBuilder().apply {
    setRecord(SubjectAreaRecordToProto(value.record))
}.build()
internal fun GovernanceDefinitionSubjectAreaFromProto(value: com.olap.prototype.wire.governance.GovernanceDefinitionSubjectArea): com.olap.prototype.governance.contract.GovernanceDefinition.SubjectArea = com.olap.prototype.governance.contract.GovernanceDefinition.SubjectArea(
    record = required(value.hasRecord(), "GovernanceDefinitionSubjectArea.record") { SubjectAreaRecordFromProto(value.getRecord()) }
)

internal fun GovernanceDefinitionAnalysisDimensionToProto(value: com.olap.prototype.governance.contract.GovernanceDefinition.AnalysisDimension): com.olap.prototype.wire.governance.GovernanceDefinitionAnalysisDimension = com.olap.prototype.wire.governance.GovernanceDefinitionAnalysisDimension.newBuilder().apply {
    setRecord(AnalysisDimensionRecordToProto(value.record))
}.build()
internal fun GovernanceDefinitionAnalysisDimensionFromProto(value: com.olap.prototype.wire.governance.GovernanceDefinitionAnalysisDimension): com.olap.prototype.governance.contract.GovernanceDefinition.AnalysisDimension = com.olap.prototype.governance.contract.GovernanceDefinition.AnalysisDimension(
    record = required(value.hasRecord(), "GovernanceDefinitionAnalysisDimension.record") { AnalysisDimensionRecordFromProto(value.getRecord()) }
)

internal fun GovernanceDefinitionScenarioToProto(value: com.olap.prototype.governance.contract.GovernanceDefinition.Scenario): com.olap.prototype.wire.governance.GovernanceDefinitionScenario = com.olap.prototype.wire.governance.GovernanceDefinitionScenario.newBuilder().apply {
    setRecord(ScenarioRecordToProto(value.record))
}.build()
internal fun GovernanceDefinitionScenarioFromProto(value: com.olap.prototype.wire.governance.GovernanceDefinitionScenario): com.olap.prototype.governance.contract.GovernanceDefinition.Scenario = com.olap.prototype.governance.contract.GovernanceDefinition.Scenario(
    record = required(value.hasRecord(), "GovernanceDefinitionScenario.record") { ScenarioRecordFromProto(value.getRecord()) }
)

internal fun DefinitionRevisionDiffValueMetricAdoptionValueToProto(value: com.olap.prototype.governance.contract.DefinitionRevisionDiff.Value.MetricAdoptionValue): com.olap.prototype.wire.governance.DefinitionRevisionDiffValueMetricAdoptionValue = com.olap.prototype.wire.governance.DefinitionRevisionDiffValueMetricAdoptionValue.newBuilder().apply {
    setValue(MetricAdoptionToProto(value.value))
}.build()
internal fun DefinitionRevisionDiffValueMetricAdoptionValueFromProto(value: com.olap.prototype.wire.governance.DefinitionRevisionDiffValueMetricAdoptionValue): com.olap.prototype.governance.contract.DefinitionRevisionDiff.Value.MetricAdoptionValue = com.olap.prototype.governance.contract.DefinitionRevisionDiff.Value.MetricAdoptionValue(
    value = required(value.hasValue(), "DefinitionRevisionDiffValueMetricAdoptionValue.value") { MetricAdoptionFromProto(value.getValue()) }
)

internal fun DefinitionRevisionDiffValueMemberSourceValueToProto(value: com.olap.prototype.governance.contract.DefinitionRevisionDiff.Value.MemberSourceValue): com.olap.prototype.wire.governance.DefinitionRevisionDiffValueMemberSourceValue = com.olap.prototype.wire.governance.DefinitionRevisionDiffValueMemberSourceValue.newBuilder().apply {
    setValue(MemberSourceRecordToProto(value.value))
}.build()
internal fun DefinitionRevisionDiffValueMemberSourceValueFromProto(value: com.olap.prototype.wire.governance.DefinitionRevisionDiffValueMemberSourceValue): com.olap.prototype.governance.contract.DefinitionRevisionDiff.Value.MemberSourceValue = com.olap.prototype.governance.contract.DefinitionRevisionDiff.Value.MemberSourceValue(
    value = required(value.hasValue(), "DefinitionRevisionDiffValueMemberSourceValue.value") { MemberSourceRecordFromProto(value.getValue()) }
)

internal fun DefinitionRevisionDiffValueTextToProto(value: com.olap.prototype.governance.contract.DefinitionRevisionDiff.Value.Text): com.olap.prototype.wire.governance.DefinitionRevisionDiffValueText = com.olap.prototype.wire.governance.DefinitionRevisionDiffValueText.newBuilder().apply {
    setValue(value.value)
}.build()
internal fun DefinitionRevisionDiffValueTextFromProto(value: com.olap.prototype.wire.governance.DefinitionRevisionDiffValueText): com.olap.prototype.governance.contract.DefinitionRevisionDiff.Value.Text = com.olap.prototype.governance.contract.DefinitionRevisionDiff.Value.Text(
    value = value.getValue()
)

internal fun AssetSnapshotToProto(value: com.olap.prototype.governance.contract.AssetSnapshot): com.olap.prototype.wire.governance.AssetSnapshot = com.olap.prototype.wire.governance.AssetSnapshot.newBuilder().apply { when (value) {
    is com.olap.prototype.governance.contract.AssetSnapshot.BusinessLine -> setBusinessLine(AssetSnapshotBusinessLineToProto(value))
    is com.olap.prototype.governance.contract.AssetSnapshot.PhysicalTable -> setPhysicalTable(AssetSnapshotPhysicalTableToProto(value))
    is com.olap.prototype.governance.contract.AssetSnapshot.PhysicalColumn -> setPhysicalColumn(AssetSnapshotPhysicalColumnToProto(value))
    is com.olap.prototype.governance.contract.AssetSnapshot.LogicalTable -> setLogicalTable(AssetSnapshotLogicalTableToProto(value))
    is com.olap.prototype.governance.contract.AssetSnapshot.LogicalUnit -> setLogicalUnit(AssetSnapshotLogicalUnitToProto(value))
    is com.olap.prototype.governance.contract.AssetSnapshot.Topology -> setTopology(AssetSnapshotTopologyToProto(value))
    is com.olap.prototype.governance.contract.AssetSnapshot.JoinPath -> setJoinPath(AssetSnapshotJoinPathToProto(value))
    is com.olap.prototype.governance.contract.AssetSnapshot.Event -> setEvent(AssetSnapshotEventToProto(value))
    is com.olap.prototype.governance.contract.AssetSnapshot.Metric -> setMetric(AssetSnapshotMetricToProto(value))
    is com.olap.prototype.governance.contract.AssetSnapshot.Dimension -> setDimension(AssetSnapshotDimensionToProto(value))
    is com.olap.prototype.governance.contract.AssetSnapshot.DimensionBinding -> setDimensionBinding(AssetSnapshotDimensionBindingToProto(value))
    is com.olap.prototype.governance.contract.AssetSnapshot.SubjectArea -> setSubjectArea(AssetSnapshotSubjectAreaToProto(value))
    is com.olap.prototype.governance.contract.AssetSnapshot.MemberSource -> setMemberSource(AssetSnapshotMemberSourceToProto(value))
    is com.olap.prototype.governance.contract.AssetSnapshot.MemberDefinition -> setMemberDefinition(AssetSnapshotMemberDefinitionToProto(value))
    is com.olap.prototype.governance.contract.AssetSnapshot.AnalysisDimension -> setAnalysisDimension(AssetSnapshotAnalysisDimensionToProto(value))
    is com.olap.prototype.governance.contract.AssetSnapshot.Scenario -> setScenario(AssetSnapshotScenarioToProto(value))
} }.build()
internal fun AssetSnapshotFromProto(value: com.olap.prototype.wire.governance.AssetSnapshot): com.olap.prototype.governance.contract.AssetSnapshot = when (value.kindCase) {
    com.olap.prototype.wire.governance.AssetSnapshot.KindCase.BUSINESS_LINE -> AssetSnapshotBusinessLineFromProto(value.getBusinessLine())
    com.olap.prototype.wire.governance.AssetSnapshot.KindCase.PHYSICAL_TABLE -> AssetSnapshotPhysicalTableFromProto(value.getPhysicalTable())
    com.olap.prototype.wire.governance.AssetSnapshot.KindCase.PHYSICAL_COLUMN -> AssetSnapshotPhysicalColumnFromProto(value.getPhysicalColumn())
    com.olap.prototype.wire.governance.AssetSnapshot.KindCase.LOGICAL_TABLE -> AssetSnapshotLogicalTableFromProto(value.getLogicalTable())
    com.olap.prototype.wire.governance.AssetSnapshot.KindCase.LOGICAL_UNIT -> AssetSnapshotLogicalUnitFromProto(value.getLogicalUnit())
    com.olap.prototype.wire.governance.AssetSnapshot.KindCase.TOPOLOGY -> AssetSnapshotTopologyFromProto(value.getTopology())
    com.olap.prototype.wire.governance.AssetSnapshot.KindCase.JOIN_PATH -> AssetSnapshotJoinPathFromProto(value.getJoinPath())
    com.olap.prototype.wire.governance.AssetSnapshot.KindCase.EVENT -> AssetSnapshotEventFromProto(value.getEvent())
    com.olap.prototype.wire.governance.AssetSnapshot.KindCase.METRIC -> AssetSnapshotMetricFromProto(value.getMetric())
    com.olap.prototype.wire.governance.AssetSnapshot.KindCase.DIMENSION -> AssetSnapshotDimensionFromProto(value.getDimension())
    com.olap.prototype.wire.governance.AssetSnapshot.KindCase.DIMENSION_BINDING -> AssetSnapshotDimensionBindingFromProto(value.getDimensionBinding())
    com.olap.prototype.wire.governance.AssetSnapshot.KindCase.SUBJECT_AREA -> AssetSnapshotSubjectAreaFromProto(value.getSubjectArea())
    com.olap.prototype.wire.governance.AssetSnapshot.KindCase.MEMBER_SOURCE -> AssetSnapshotMemberSourceFromProto(value.getMemberSource())
    com.olap.prototype.wire.governance.AssetSnapshot.KindCase.MEMBER_DEFINITION -> AssetSnapshotMemberDefinitionFromProto(value.getMemberDefinition())
    com.olap.prototype.wire.governance.AssetSnapshot.KindCase.ANALYSIS_DIMENSION -> AssetSnapshotAnalysisDimensionFromProto(value.getAnalysisDimension())
    com.olap.prototype.wire.governance.AssetSnapshot.KindCase.SCENARIO -> AssetSnapshotScenarioFromProto(value.getScenario())
    com.olap.prototype.wire.governance.AssetSnapshot.KindCase.KIND_NOT_SET -> error("AssetSnapshot variant is required")
}

internal fun AssetSnapshotBusinessLineToProto(value: com.olap.prototype.governance.contract.AssetSnapshot.BusinessLine): com.olap.prototype.wire.governance.AssetSnapshotBusinessLine = com.olap.prototype.wire.governance.AssetSnapshotBusinessLine.newBuilder().apply {
    setValue(BusinessLineRecordToProto(value.value))
}.build()
internal fun AssetSnapshotBusinessLineFromProto(value: com.olap.prototype.wire.governance.AssetSnapshotBusinessLine): com.olap.prototype.governance.contract.AssetSnapshot.BusinessLine = com.olap.prototype.governance.contract.AssetSnapshot.BusinessLine(
    value = required(value.hasValue(), "AssetSnapshotBusinessLine.value") { BusinessLineRecordFromProto(value.getValue()) }
)

internal fun AssetSnapshotPhysicalTableToProto(value: com.olap.prototype.governance.contract.AssetSnapshot.PhysicalTable): com.olap.prototype.wire.governance.AssetSnapshotPhysicalTable = com.olap.prototype.wire.governance.AssetSnapshotPhysicalTable.newBuilder().apply {
    setValue(PhysicalTableRecordToProto(value.value))
}.build()
internal fun AssetSnapshotPhysicalTableFromProto(value: com.olap.prototype.wire.governance.AssetSnapshotPhysicalTable): com.olap.prototype.governance.contract.AssetSnapshot.PhysicalTable = com.olap.prototype.governance.contract.AssetSnapshot.PhysicalTable(
    value = required(value.hasValue(), "AssetSnapshotPhysicalTable.value") { PhysicalTableRecordFromProto(value.getValue()) }
)

internal fun AssetSnapshotPhysicalColumnToProto(value: com.olap.prototype.governance.contract.AssetSnapshot.PhysicalColumn): com.olap.prototype.wire.governance.AssetSnapshotPhysicalColumn = com.olap.prototype.wire.governance.AssetSnapshotPhysicalColumn.newBuilder().apply {
    setValue(PhysicalColumnRecordToProto(value.value))
}.build()
internal fun AssetSnapshotPhysicalColumnFromProto(value: com.olap.prototype.wire.governance.AssetSnapshotPhysicalColumn): com.olap.prototype.governance.contract.AssetSnapshot.PhysicalColumn = com.olap.prototype.governance.contract.AssetSnapshot.PhysicalColumn(
    value = required(value.hasValue(), "AssetSnapshotPhysicalColumn.value") { PhysicalColumnRecordFromProto(value.getValue()) }
)

internal fun AssetSnapshotLogicalTableToProto(value: com.olap.prototype.governance.contract.AssetSnapshot.LogicalTable): com.olap.prototype.wire.governance.AssetSnapshotLogicalTable = com.olap.prototype.wire.governance.AssetSnapshotLogicalTable.newBuilder().apply {
    setValue(LogicalTableRecordToProto(value.value))
}.build()
internal fun AssetSnapshotLogicalTableFromProto(value: com.olap.prototype.wire.governance.AssetSnapshotLogicalTable): com.olap.prototype.governance.contract.AssetSnapshot.LogicalTable = com.olap.prototype.governance.contract.AssetSnapshot.LogicalTable(
    value = required(value.hasValue(), "AssetSnapshotLogicalTable.value") { LogicalTableRecordFromProto(value.getValue()) }
)

internal fun AssetSnapshotLogicalUnitToProto(value: com.olap.prototype.governance.contract.AssetSnapshot.LogicalUnit): com.olap.prototype.wire.governance.AssetSnapshotLogicalUnit = com.olap.prototype.wire.governance.AssetSnapshotLogicalUnit.newBuilder().apply {
    setValue(LogicUnitRecordToProto(value.value))
}.build()
internal fun AssetSnapshotLogicalUnitFromProto(value: com.olap.prototype.wire.governance.AssetSnapshotLogicalUnit): com.olap.prototype.governance.contract.AssetSnapshot.LogicalUnit = com.olap.prototype.governance.contract.AssetSnapshot.LogicalUnit(
    value = required(value.hasValue(), "AssetSnapshotLogicalUnit.value") { LogicUnitRecordFromProto(value.getValue()) }
)

internal fun AssetSnapshotTopologyToProto(value: com.olap.prototype.governance.contract.AssetSnapshot.Topology): com.olap.prototype.wire.governance.AssetSnapshotTopology = com.olap.prototype.wire.governance.AssetSnapshotTopology.newBuilder().apply {
    setValue(TopologyRecordToProto(value.value))
}.build()
internal fun AssetSnapshotTopologyFromProto(value: com.olap.prototype.wire.governance.AssetSnapshotTopology): com.olap.prototype.governance.contract.AssetSnapshot.Topology = com.olap.prototype.governance.contract.AssetSnapshot.Topology(
    value = required(value.hasValue(), "AssetSnapshotTopology.value") { TopologyRecordFromProto(value.getValue()) }
)

internal fun AssetSnapshotJoinPathToProto(value: com.olap.prototype.governance.contract.AssetSnapshot.JoinPath): com.olap.prototype.wire.governance.AssetSnapshotJoinPath = com.olap.prototype.wire.governance.AssetSnapshotJoinPath.newBuilder().apply {
    setValue(JoinPathRecordToProto(value.value))
}.build()
internal fun AssetSnapshotJoinPathFromProto(value: com.olap.prototype.wire.governance.AssetSnapshotJoinPath): com.olap.prototype.governance.contract.AssetSnapshot.JoinPath = com.olap.prototype.governance.contract.AssetSnapshot.JoinPath(
    value = required(value.hasValue(), "AssetSnapshotJoinPath.value") { JoinPathRecordFromProto(value.getValue()) }
)

internal fun AssetSnapshotEventToProto(value: com.olap.prototype.governance.contract.AssetSnapshot.Event): com.olap.prototype.wire.governance.AssetSnapshotEvent = com.olap.prototype.wire.governance.AssetSnapshotEvent.newBuilder().apply {
    setValue(EventRecordToProto(value.value))
}.build()
internal fun AssetSnapshotEventFromProto(value: com.olap.prototype.wire.governance.AssetSnapshotEvent): com.olap.prototype.governance.contract.AssetSnapshot.Event = com.olap.prototype.governance.contract.AssetSnapshot.Event(
    value = required(value.hasValue(), "AssetSnapshotEvent.value") { EventRecordFromProto(value.getValue()) }
)

internal fun AssetSnapshotMetricToProto(value: com.olap.prototype.governance.contract.AssetSnapshot.Metric): com.olap.prototype.wire.governance.AssetSnapshotMetric = com.olap.prototype.wire.governance.AssetSnapshotMetric.newBuilder().apply {
    setValue(MetricRecordToProto(value.value))
    setBusinessMeaning(SemanticProto.toProto(value.businessMeaning))
}.build()
internal fun AssetSnapshotMetricFromProto(value: com.olap.prototype.wire.governance.AssetSnapshotMetric): com.olap.prototype.governance.contract.AssetSnapshot.Metric = com.olap.prototype.governance.contract.AssetSnapshot.Metric(
    value = required(value.hasValue(), "AssetSnapshotMetric.value") { MetricRecordFromProto(value.getValue()) },
    businessMeaning = required(value.hasBusinessMeaning(), "AssetSnapshotMetric.businessMeaning") { SemanticProto.fromProto(value.getBusinessMeaning()) as com.olap.prototype.metric.MetricBusinessMeaning }
)

internal fun AssetSnapshotDimensionToProto(value: com.olap.prototype.governance.contract.AssetSnapshot.Dimension): com.olap.prototype.wire.governance.AssetSnapshotDimension = com.olap.prototype.wire.governance.AssetSnapshotDimension.newBuilder().apply {
    setValue(DimensionRecordToProto(value.value))
}.build()
internal fun AssetSnapshotDimensionFromProto(value: com.olap.prototype.wire.governance.AssetSnapshotDimension): com.olap.prototype.governance.contract.AssetSnapshot.Dimension = com.olap.prototype.governance.contract.AssetSnapshot.Dimension(
    value = required(value.hasValue(), "AssetSnapshotDimension.value") { DimensionRecordFromProto(value.getValue()) }
)

internal fun AssetSnapshotDimensionBindingToProto(value: com.olap.prototype.governance.contract.AssetSnapshot.DimensionBinding): com.olap.prototype.wire.governance.AssetSnapshotDimensionBinding = com.olap.prototype.wire.governance.AssetSnapshotDimensionBinding.newBuilder().apply {
    setValue(DimensionEventBindingRecordToProto(value.value))
}.build()
internal fun AssetSnapshotDimensionBindingFromProto(value: com.olap.prototype.wire.governance.AssetSnapshotDimensionBinding): com.olap.prototype.governance.contract.AssetSnapshot.DimensionBinding = com.olap.prototype.governance.contract.AssetSnapshot.DimensionBinding(
    value = required(value.hasValue(), "AssetSnapshotDimensionBinding.value") { DimensionEventBindingRecordFromProto(value.getValue()) }
)

internal fun AssetSnapshotSubjectAreaToProto(value: com.olap.prototype.governance.contract.AssetSnapshot.SubjectArea): com.olap.prototype.wire.governance.AssetSnapshotSubjectArea = com.olap.prototype.wire.governance.AssetSnapshotSubjectArea.newBuilder().apply {
    setValue(SubjectAreaRecordToProto(value.value))
}.build()
internal fun AssetSnapshotSubjectAreaFromProto(value: com.olap.prototype.wire.governance.AssetSnapshotSubjectArea): com.olap.prototype.governance.contract.AssetSnapshot.SubjectArea = com.olap.prototype.governance.contract.AssetSnapshot.SubjectArea(
    value = required(value.hasValue(), "AssetSnapshotSubjectArea.value") { SubjectAreaRecordFromProto(value.getValue()) }
)

internal fun AssetSnapshotMemberSourceToProto(value: com.olap.prototype.governance.contract.AssetSnapshot.MemberSource): com.olap.prototype.wire.governance.AssetSnapshotMemberSource = com.olap.prototype.wire.governance.AssetSnapshotMemberSource.newBuilder().apply {
    setValue(MemberSourceRecordToProto(value.value))
}.build()
internal fun AssetSnapshotMemberSourceFromProto(value: com.olap.prototype.wire.governance.AssetSnapshotMemberSource): com.olap.prototype.governance.contract.AssetSnapshot.MemberSource = com.olap.prototype.governance.contract.AssetSnapshot.MemberSource(
    value = required(value.hasValue(), "AssetSnapshotMemberSource.value") { MemberSourceRecordFromProto(value.getValue()) }
)

internal fun AssetSnapshotMemberDefinitionToProto(value: com.olap.prototype.governance.contract.AssetSnapshot.MemberDefinition): com.olap.prototype.wire.governance.AssetSnapshotMemberDefinition = com.olap.prototype.wire.governance.AssetSnapshotMemberDefinition.newBuilder().apply {
    setValue(MemberDefinitionRecordToProto(value.value))
}.build()
internal fun AssetSnapshotMemberDefinitionFromProto(value: com.olap.prototype.wire.governance.AssetSnapshotMemberDefinition): com.olap.prototype.governance.contract.AssetSnapshot.MemberDefinition = com.olap.prototype.governance.contract.AssetSnapshot.MemberDefinition(
    value = required(value.hasValue(), "AssetSnapshotMemberDefinition.value") { MemberDefinitionRecordFromProto(value.getValue()) }
)

internal fun AssetSnapshotAnalysisDimensionToProto(value: com.olap.prototype.governance.contract.AssetSnapshot.AnalysisDimension): com.olap.prototype.wire.governance.AssetSnapshotAnalysisDimension = com.olap.prototype.wire.governance.AssetSnapshotAnalysisDimension.newBuilder().apply {
    setValue(AnalysisDimensionRecordToProto(value.value))
}.build()
internal fun AssetSnapshotAnalysisDimensionFromProto(value: com.olap.prototype.wire.governance.AssetSnapshotAnalysisDimension): com.olap.prototype.governance.contract.AssetSnapshot.AnalysisDimension = com.olap.prototype.governance.contract.AssetSnapshot.AnalysisDimension(
    value = required(value.hasValue(), "AssetSnapshotAnalysisDimension.value") { AnalysisDimensionRecordFromProto(value.getValue()) }
)

internal fun AssetSnapshotScenarioToProto(value: com.olap.prototype.governance.contract.AssetSnapshot.Scenario): com.olap.prototype.wire.governance.AssetSnapshotScenario = com.olap.prototype.wire.governance.AssetSnapshotScenario.newBuilder().apply {
    setValue(ScenarioRecordToProto(value.value))
}.build()
internal fun AssetSnapshotScenarioFromProto(value: com.olap.prototype.wire.governance.AssetSnapshotScenario): com.olap.prototype.governance.contract.AssetSnapshot.Scenario = com.olap.prototype.governance.contract.AssetSnapshot.Scenario(
    value = required(value.hasValue(), "AssetSnapshotScenario.value") { ScenarioRecordFromProto(value.getValue()) }
)

internal fun DefinitionDiscoveryRequestToProto(value: com.olap.prototype.governance.contract.DefinitionDiscovery.Request): com.olap.prototype.wire.governance.DefinitionDiscoveryRequest = com.olap.prototype.wire.governance.DefinitionDiscoveryRequest.newBuilder().apply {
    addAllAssetTypes(value.assetTypes.map { it }.sorted())
    value.text?.let { setText(it) }
    setOffset(value.offset)
    setLimit(value.limit)
}.build()
internal fun DefinitionDiscoveryRequestFromProto(value: com.olap.prototype.wire.governance.DefinitionDiscoveryRequest): com.olap.prototype.governance.contract.DefinitionDiscovery.Request = com.olap.prototype.governance.contract.DefinitionDiscovery.Request(
    assetTypes = strictSet(value.getAssetTypesList().map { it }),
    text = if (value.hasText()) value.getText() else null,
    offset = value.getOffset(),
    limit = value.getLimit()
)

internal fun DefinitionDiscoveryPageToProto(value: com.olap.prototype.governance.contract.DefinitionDiscovery.Page): com.olap.prototype.wire.governance.DefinitionDiscoveryPage = com.olap.prototype.wire.governance.DefinitionDiscoveryPage.newBuilder().apply {
    addAllItems(value.items.map { DefinitionDiscoverySummaryToProto(it) })
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun DefinitionDiscoveryPageFromProto(value: com.olap.prototype.wire.governance.DefinitionDiscoveryPage): com.olap.prototype.governance.contract.DefinitionDiscovery.Page = com.olap.prototype.governance.contract.DefinitionDiscovery.Page(
    items = value.getItemsList().map { DefinitionDiscoverySummaryFromProto(it) },
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun DefinitionDiscoverySummaryToProto(value: com.olap.prototype.governance.contract.DefinitionDiscovery.Summary): com.olap.prototype.wire.governance.DefinitionDiscoverySummary = com.olap.prototype.wire.governance.DefinitionDiscoverySummary.newBuilder().apply {
    setAssetType(value.assetType)
    setId(value.id)
    setVersion(value.version)
    setStatus(value.status)
    value.code?.let { setCode(it) }
    value.displayName?.let { setDisplayName(it) }
    value.description?.let { setDescription(it) }
    value.owner?.let { setOwner(it) }
    value.businessMeaning?.let { setBusinessMeaning(SemanticProto.toProto(it)) }
}.build()
internal fun DefinitionDiscoverySummaryFromProto(value: com.olap.prototype.wire.governance.DefinitionDiscoverySummary): com.olap.prototype.governance.contract.DefinitionDiscovery.Summary = com.olap.prototype.governance.contract.DefinitionDiscovery.Summary(
    assetType = value.getAssetType(),
    id = value.getId(),
    version = value.getVersion(),
    status = value.getStatus(),
    code = if (value.hasCode()) value.getCode() else null,
    displayName = if (value.hasDisplayName()) value.getDisplayName() else null,
    description = if (value.hasDescription()) value.getDescription() else null,
    owner = if (value.hasOwner()) value.getOwner() else null,
    businessMeaning = if (value.hasBusinessMeaning()) SemanticProto.fromProto(value.getBusinessMeaning()) as com.olap.prototype.metric.MetricBusinessMeaning else null
)

internal fun DefinitionDependenciesToProto(value: com.olap.prototype.governance.contract.DefinitionDependencies): com.olap.prototype.wire.governance.DefinitionDependencies = com.olap.prototype.wire.governance.DefinitionDependencies.newBuilder().apply {
    setTarget(DefinitionImpactReferenceToProto(value.target))
    addAllItems(value.items.map { DefinitionImpactReferenceToProto(it) })
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun DefinitionDependenciesFromProto(value: com.olap.prototype.wire.governance.DefinitionDependencies): com.olap.prototype.governance.contract.DefinitionDependencies = com.olap.prototype.governance.contract.DefinitionDependencies(
    target = required(value.hasTarget(), "DefinitionDependencies.target") { DefinitionImpactReferenceFromProto(value.getTarget()) },
    items = value.getItemsList().map { DefinitionImpactReferenceFromProto(it) },
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)
