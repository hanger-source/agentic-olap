package com.olap.prototype.codec

/** 正式Executable消息的显式字段转换。 */
internal fun ExecutableCatalogToProto(value: com.olap.prototype.engine.catalog.ExecutableCatalog): com.olap.prototype.wire.governance.ExecutableCatalog = com.olap.prototype.wire.governance.ExecutableCatalog.newBuilder().apply {
    setManifest(ExecutableCatalogManifestToProto(value.manifest))
    addAllMetrics(value.metrics.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.ExecutableCatalogMetricsEntry.newBuilder().setKey(entry.key).apply { setValue(ExecutableMetricToProto(entry.value)) }.build() })
    addAllMetricInstances(value.metricInstances.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.ExecutableCatalogMetricInstancesEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    addAllMemberSources(value.memberSources.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.ExecutableCatalogMemberSourcesEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    addAllRelationFilters(value.relationFilters.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.ExecutableCatalogRelationFiltersEntry.newBuilder().setKey(entry.key).apply { setValue(RelationFilterToProto(entry.value)) }.build() })
    addAllAnalysisDimensions(value.analysisDimensions.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.ExecutableCatalogAnalysisDimensionsEntry.newBuilder().setKey(entry.key).apply { setValue(ExecutableAnalysisDimensionToProto(entry.value)) }.build() })
    addAllEvents(value.events.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.ExecutableCatalogEventsEntry.newBuilder().setKey(entry.key).apply { setValue(ExecutableEventToProto(entry.value)) }.build() })
    addAllTopologies(value.topologies.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.ExecutableCatalogTopologiesEntry.newBuilder().setKey(entry.key).apply { setValue(ExecutableTopologyToProto(entry.value)) }.build() })
    addAllDimensions(value.dimensions.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.ExecutableCatalogDimensionsEntry.newBuilder().setKey(entry.key).apply { setValue(ExecutableDimensionToProto(entry.value)) }.build() })
    addAllDimensionBindings(value.dimensionBindings.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.ExecutableCatalogDimensionBindingsEntry.newBuilder().setKey(entry.key).apply { setValue(ExecutableDimensionBindingToProto(entry.value)) }.build() })
    addAllLogicUnits(value.logicUnits.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.ExecutableCatalogLogicUnitsEntry.newBuilder().setKey(entry.key).apply { setValue(ExecutableLogicUnitToProto(entry.value)) }.build() })
    addAllTimeAnchors(value.timeAnchors.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.ExecutableCatalogTimeAnchorsEntry.newBuilder().setKey(entry.key).apply { setValue(ExecutableTimeAnchorToProto(entry.value)) }.build() })
    addAllJoinPaths(value.joinPaths.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.ExecutableCatalogJoinPathsEntry.newBuilder().setKey(entry.key).apply { setValue(ExecutableJoinPathToProto(entry.value)) }.build() })
    addAllPhysicalExecutionBindings(value.physicalExecutionBindings.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.ExecutableCatalogPhysicalExecutionBindingsEntry.newBuilder().setKey(entry.key).apply { setValue(ExecutablePhysicalExecutionBindingToProto(entry.value)) }.build() })
}.build()
internal fun ExecutableCatalogFromProto(value: com.olap.prototype.wire.governance.ExecutableCatalog): com.olap.prototype.engine.catalog.ExecutableCatalog = com.olap.prototype.engine.catalog.ExecutableCatalog(
    manifest = required(value.hasManifest(), "ExecutableCatalog.manifest") { ExecutableCatalogManifestFromProto(value.getManifest()) },
    metrics = strictMap(value.getMetricsList().map { it.getKey() to ExecutableMetricFromProto(it.getValue()) }),
    metricInstances = strictMap(value.getMetricInstancesList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.MetricInstance }),
    memberSources = strictMap(value.getMemberSourcesList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.member.MemberSource }),
    relationFilters = strictMap(value.getRelationFiltersList().map { it.getKey() to RelationFilterFromProto(it.getValue()) }),
    analysisDimensions = strictMap(value.getAnalysisDimensionsList().map { it.getKey() to ExecutableAnalysisDimensionFromProto(it.getValue()) }),
    events = strictMap(value.getEventsList().map { it.getKey() to ExecutableEventFromProto(it.getValue()) }),
    topologies = strictMap(value.getTopologiesList().map { it.getKey() to ExecutableTopologyFromProto(it.getValue()) }),
    dimensions = strictMap(value.getDimensionsList().map { it.getKey() to ExecutableDimensionFromProto(it.getValue()) }),
    dimensionBindings = strictMap(value.getDimensionBindingsList().map { it.getKey() to ExecutableDimensionBindingFromProto(it.getValue()) }),
    logicUnits = strictMap(value.getLogicUnitsList().map { it.getKey() to ExecutableLogicUnitFromProto(it.getValue()) }),
    timeAnchors = strictMap(value.getTimeAnchorsList().map { it.getKey() to ExecutableTimeAnchorFromProto(it.getValue()) }),
    joinPaths = strictMap(value.getJoinPathsList().map { it.getKey() to ExecutableJoinPathFromProto(it.getValue()) }),
    physicalExecutionBindings = strictMap(value.getPhysicalExecutionBindingsList().map { it.getKey() to ExecutablePhysicalExecutionBindingFromProto(it.getValue()) })
)

internal fun ExecutableCatalogManifestToProto(value: com.olap.prototype.engine.catalog.ExecutableCatalogManifest): com.olap.prototype.wire.governance.ExecutableCatalogManifest = com.olap.prototype.wire.governance.ExecutableCatalogManifest.newBuilder().apply {
    setCatalogId(value.catalogId)
    setSchemaVersion(value.schemaVersion)
}.build()
internal fun ExecutableCatalogManifestFromProto(value: com.olap.prototype.wire.governance.ExecutableCatalogManifest): com.olap.prototype.engine.catalog.ExecutableCatalogManifest = com.olap.prototype.engine.catalog.ExecutableCatalogManifest(
    catalogId = value.getCatalogId(),
    schemaVersion = value.getSchemaVersion()
)

internal fun ExecutableMetricToProto(value: com.olap.prototype.engine.catalog.ExecutableMetric): com.olap.prototype.wire.governance.ExecutableMetric = com.olap.prototype.wire.governance.ExecutableMetric.newBuilder().apply {
    setReference(SemanticProto.toProto(value.reference))
    setDefinition(SemanticProto.toProto(value.definition))
}.build()
internal fun ExecutableMetricFromProto(value: com.olap.prototype.wire.governance.ExecutableMetric): com.olap.prototype.engine.catalog.ExecutableMetric = com.olap.prototype.engine.catalog.ExecutableMetric(
    reference = required(value.hasReference(), "ExecutableMetric.reference") { SemanticProto.fromProto(value.getReference()) as com.olap.prototype.calculation.MetricRef },
    definition = required(value.hasDefinition(), "ExecutableMetric.definition") { SemanticProto.fromProto(value.getDefinition()) as com.olap.prototype.calculation.MetricDefinition }
)

internal fun ExecutableAnalysisDimensionToProto(value: com.olap.prototype.engine.catalog.ExecutableAnalysisDimension): com.olap.prototype.wire.governance.ExecutableAnalysisDimension = com.olap.prototype.wire.governance.ExecutableAnalysisDimension.newBuilder().apply {
    setAnalysisDimensionId(value.analysisDimensionId)
    setAnalysisDimensionVersion(value.analysisDimensionVersion)
    value.displayName?.let { setDisplayName(it) }
    setAnalysisDimensionCode(value.analysisDimensionCode)
    setOutputAlias(value.outputAlias)
    value.defaultLevelCode?.let { setDefaultLevelCode(it) }
    addAllLevels(value.levels.map { ExecutableAnalysisDimensionLevelToProto(it) })
    addAllEventMappings(value.eventMappings.map { ExecutableAnalysisDimensionEventMappingToProto(it) })
}.build()
internal fun ExecutableAnalysisDimensionFromProto(value: com.olap.prototype.wire.governance.ExecutableAnalysisDimension): com.olap.prototype.engine.catalog.ExecutableAnalysisDimension = com.olap.prototype.engine.catalog.ExecutableAnalysisDimension(
    analysisDimensionId = value.getAnalysisDimensionId(),
    analysisDimensionVersion = value.getAnalysisDimensionVersion(),
    displayName = if (value.hasDisplayName()) value.getDisplayName() else null,
    analysisDimensionCode = value.getAnalysisDimensionCode(),
    outputAlias = value.getOutputAlias(),
    defaultLevelCode = if (value.hasDefaultLevelCode()) value.getDefaultLevelCode() else null,
    levels = value.getLevelsList().map { ExecutableAnalysisDimensionLevelFromProto(it) },
    eventMappings = value.getEventMappingsList().map { ExecutableAnalysisDimensionEventMappingFromProto(it) }
)

internal fun ExecutableAnalysisDimensionLevelToProto(value: com.olap.prototype.engine.catalog.ExecutableAnalysisDimensionLevel): com.olap.prototype.wire.governance.ExecutableAnalysisDimensionLevel = com.olap.prototype.wire.governance.ExecutableAnalysisDimensionLevel.newBuilder().apply {
    setLevelCode(value.levelCode)
    value.displayName?.let { setDisplayName(it) }
    value.parentLevelCode?.let { setParentLevelCode(it) }
}.build()
internal fun ExecutableAnalysisDimensionLevelFromProto(value: com.olap.prototype.wire.governance.ExecutableAnalysisDimensionLevel): com.olap.prototype.engine.catalog.ExecutableAnalysisDimensionLevel = com.olap.prototype.engine.catalog.ExecutableAnalysisDimensionLevel(
    levelCode = value.getLevelCode(),
    displayName = if (value.hasDisplayName()) value.getDisplayName() else null,
    parentLevelCode = if (value.hasParentLevelCode()) value.getParentLevelCode() else null
)

internal fun ExecutableAnalysisDimensionEventMappingToProto(value: com.olap.prototype.engine.catalog.ExecutableAnalysisDimensionEventMapping): com.olap.prototype.wire.governance.ExecutableAnalysisDimensionEventMapping = com.olap.prototype.wire.governance.ExecutableAnalysisDimensionEventMapping.newBuilder().apply {
    setMappingId(value.mappingId)
    setAnalysisDimensionId(value.analysisDimensionId)
    setAnalysisDimensionVersion(value.analysisDimensionVersion)
    setLevelCode(value.levelCode)
    setEventId(value.eventId)
    setEventVersion(value.eventVersion)
    setDimensionId(value.dimensionId)
    setDimensionVersion(value.dimensionVersion)
    setDimensionBindingId(value.dimensionBindingId)
    setDimensionBindingVersion(value.dimensionBindingVersion)
    setLogicUnitId(value.logicUnitId)
    setLogicUnitVersion(value.logicUnitVersion)
    value.valueAlignmentKey?.let { setValueAlignmentKey(it) }
    setValueAlignmentStatus(value.valueAlignmentStatus)
}.build()
internal fun ExecutableAnalysisDimensionEventMappingFromProto(value: com.olap.prototype.wire.governance.ExecutableAnalysisDimensionEventMapping): com.olap.prototype.engine.catalog.ExecutableAnalysisDimensionEventMapping = com.olap.prototype.engine.catalog.ExecutableAnalysisDimensionEventMapping(
    mappingId = value.getMappingId(),
    analysisDimensionId = value.getAnalysisDimensionId(),
    analysisDimensionVersion = value.getAnalysisDimensionVersion(),
    levelCode = value.getLevelCode(),
    eventId = value.getEventId(),
    eventVersion = value.getEventVersion(),
    dimensionId = value.getDimensionId(),
    dimensionVersion = value.getDimensionVersion(),
    dimensionBindingId = value.getDimensionBindingId(),
    dimensionBindingVersion = value.getDimensionBindingVersion(),
    logicUnitId = value.getLogicUnitId(),
    logicUnitVersion = value.getLogicUnitVersion(),
    valueAlignmentKey = if (value.hasValueAlignmentKey()) value.getValueAlignmentKey() else null,
    valueAlignmentStatus = value.getValueAlignmentStatus()
)

internal fun ExecutableEventToProto(value: com.olap.prototype.engine.catalog.ExecutableEvent): com.olap.prototype.wire.governance.ExecutableEvent = com.olap.prototype.wire.governance.ExecutableEvent.newBuilder().apply {
    setEventId(value.eventId)
    setEventVersion(value.eventVersion)
    setTopologyId(value.topologyId)
    setTopologyVersion(value.topologyVersion)
    value.displayName?.let { setDisplayName(it) }
    setIdentity(SemanticProto.toProto(value.identity))
    addAllRoles(value.roles.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.ExecutableEventRolesEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    value.baseFilter?.let { setBaseFilter(SemanticProto.toProto(it)) }
    value.timeLogicUnitId?.let { setTimeLogicUnitId(it) }
    value.timeLogicUnitVersion?.let { setTimeLogicUnitVersion(it) }
}.build()
internal fun ExecutableEventFromProto(value: com.olap.prototype.wire.governance.ExecutableEvent): com.olap.prototype.engine.catalog.ExecutableEvent = com.olap.prototype.engine.catalog.ExecutableEvent(
    eventId = value.getEventId(),
    eventVersion = value.getEventVersion(),
    topologyId = value.getTopologyId(),
    topologyVersion = value.getTopologyVersion(),
    displayName = if (value.hasDisplayName()) value.getDisplayName() else null,
    identity = required(value.hasIdentity(), "ExecutableEvent.identity") { SemanticProto.fromProto(value.getIdentity()) as com.olap.prototype.event.EventIdentity },
    roles = strictMap(value.getRolesList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.event.RelationAccessPath }),
    baseFilter = if (value.hasBaseFilter()) SemanticProto.fromProto(value.getBaseFilter()) as com.olap.prototype.expression.SemanticExpression else null,
    timeLogicUnitId = if (value.hasTimeLogicUnitId()) value.getTimeLogicUnitId() else null,
    timeLogicUnitVersion = if (value.hasTimeLogicUnitVersion()) value.getTimeLogicUnitVersion() else null
)

internal fun ExecutableTopologyToProto(value: com.olap.prototype.engine.catalog.ExecutableTopology): com.olap.prototype.wire.governance.ExecutableTopology = com.olap.prototype.wire.governance.ExecutableTopology.newBuilder().apply {
    setTopologyId(value.topologyId)
    setTopologyVersion(value.topologyVersion)
    value.topologyImplementationNamespaceCode?.let { setTopologyImplementationNamespaceCode(it) }
    value.topologyImplementationNamespaceDisplayName?.let { setTopologyImplementationNamespaceDisplayName(it) }
    setBackboneLogicalTableId(value.backboneLogicalTableId)
    setBackboneLogicalTableVersion(value.backboneLogicalTableVersion)
    addAllEventRefs(value.eventRefs.map { ExecutableEventRefToProto(it) })
    addAllJoinPathRefs(value.joinPathRefs.map { ExecutableJoinPathRefToProto(it) })
}.build()
internal fun ExecutableTopologyFromProto(value: com.olap.prototype.wire.governance.ExecutableTopology): com.olap.prototype.engine.catalog.ExecutableTopology = com.olap.prototype.engine.catalog.ExecutableTopology(
    topologyId = value.getTopologyId(),
    topologyVersion = value.getTopologyVersion(),
    topologyImplementationNamespaceCode = if (value.hasTopologyImplementationNamespaceCode()) value.getTopologyImplementationNamespaceCode() else null,
    topologyImplementationNamespaceDisplayName = if (value.hasTopologyImplementationNamespaceDisplayName()) value.getTopologyImplementationNamespaceDisplayName() else null,
    backboneLogicalTableId = value.getBackboneLogicalTableId(),
    backboneLogicalTableVersion = value.getBackboneLogicalTableVersion(),
    eventRefs = value.getEventRefsList().map { ExecutableEventRefFromProto(it) },
    joinPathRefs = value.getJoinPathRefsList().map { ExecutableJoinPathRefFromProto(it) }
)

internal fun ExecutableEventRefToProto(value: com.olap.prototype.engine.catalog.ExecutableEventRef): com.olap.prototype.wire.governance.ExecutableEventRef = com.olap.prototype.wire.governance.ExecutableEventRef.newBuilder().apply {
    setEventId(value.eventId)
    setEventVersion(value.eventVersion)
}.build()
internal fun ExecutableEventRefFromProto(value: com.olap.prototype.wire.governance.ExecutableEventRef): com.olap.prototype.engine.catalog.ExecutableEventRef = com.olap.prototype.engine.catalog.ExecutableEventRef(
    eventId = value.getEventId(),
    eventVersion = value.getEventVersion()
)

internal fun ExecutableJoinPathRefToProto(value: com.olap.prototype.engine.catalog.ExecutableJoinPathRef): com.olap.prototype.wire.governance.ExecutableJoinPathRef = com.olap.prototype.wire.governance.ExecutableJoinPathRef.newBuilder().apply {
    setJoinPathId(value.joinPathId)
    setJoinPathVersion(value.joinPathVersion)
}.build()
internal fun ExecutableJoinPathRefFromProto(value: com.olap.prototype.wire.governance.ExecutableJoinPathRef): com.olap.prototype.engine.catalog.ExecutableJoinPathRef = com.olap.prototype.engine.catalog.ExecutableJoinPathRef(
    joinPathId = value.getJoinPathId(),
    joinPathVersion = value.getJoinPathVersion()
)

internal fun ExecutableDimensionToProto(value: com.olap.prototype.engine.catalog.ExecutableDimension): com.olap.prototype.wire.governance.ExecutableDimension = com.olap.prototype.wire.governance.ExecutableDimension.newBuilder().apply {
    setDimensionId(value.dimensionId)
    setDimensionVersion(value.dimensionVersion)
    value.dimensionCode?.let { setDimensionCode(it) }
    setDimensionCodeNamespace(value.dimensionCodeNamespace)
    value.dimensionLocalCode?.let { setDimensionLocalCode(it) }
    value.displayName?.let { setDisplayName(it) }
}.build()
internal fun ExecutableDimensionFromProto(value: com.olap.prototype.wire.governance.ExecutableDimension): com.olap.prototype.engine.catalog.ExecutableDimension = com.olap.prototype.engine.catalog.ExecutableDimension(
    dimensionId = value.getDimensionId(),
    dimensionVersion = value.getDimensionVersion(),
    dimensionCode = if (value.hasDimensionCode()) value.getDimensionCode() else null,
    dimensionCodeNamespace = value.getDimensionCodeNamespace(),
    dimensionLocalCode = if (value.hasDimensionLocalCode()) value.getDimensionLocalCode() else null,
    displayName = if (value.hasDisplayName()) value.getDisplayName() else null
)

internal fun ExecutableDimensionBindingToProto(value: com.olap.prototype.engine.catalog.ExecutableDimensionBinding): com.olap.prototype.wire.governance.ExecutableDimensionBinding = com.olap.prototype.wire.governance.ExecutableDimensionBinding.newBuilder().apply {
    setBindingId(value.bindingId)
    setBindingVersion(value.bindingVersion)
    setDimensionId(value.dimensionId)
    setDimensionVersion(value.dimensionVersion)
    setEventId(value.eventId)
    setEventVersion(value.eventVersion)
    setTopologyId(value.topologyId)
    setTopologyVersion(value.topologyVersion)
    setLogicUnitId(value.logicUnitId)
    setLogicUnitVersion(value.logicUnitVersion)
    setExpression(SemanticProto.toProto(value.expression))
    setAccessPath(SemanticProto.toProto(value.accessPath))
    addAllRoles(value.roles.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.ExecutableDimensionBindingRolesEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    addAllJoinPathRefs(value.joinPathRefs.map { ExecutableJoinPathRefToProto(it) })
    setPhysicalExecutionBindingId(value.physicalExecutionBindingId)
    setPhysicalExecutionBindingVersion(value.physicalExecutionBindingVersion)
}.build()
internal fun ExecutableDimensionBindingFromProto(value: com.olap.prototype.wire.governance.ExecutableDimensionBinding): com.olap.prototype.engine.catalog.ExecutableDimensionBinding = com.olap.prototype.engine.catalog.ExecutableDimensionBinding(
    bindingId = value.getBindingId(),
    bindingVersion = value.getBindingVersion(),
    dimensionId = value.getDimensionId(),
    dimensionVersion = value.getDimensionVersion(),
    eventId = value.getEventId(),
    eventVersion = value.getEventVersion(),
    topologyId = value.getTopologyId(),
    topologyVersion = value.getTopologyVersion(),
    logicUnitId = value.getLogicUnitId(),
    logicUnitVersion = value.getLogicUnitVersion(),
    expression = required(value.hasExpression(), "ExecutableDimensionBinding.expression") { SemanticProto.fromProto(value.getExpression()) as com.olap.prototype.expression.SemanticExpression },
    accessPath = required(value.hasAccessPath(), "ExecutableDimensionBinding.accessPath") { SemanticProto.fromProto(value.getAccessPath()) as com.olap.prototype.event.RelationAccessPath },
    roles = strictMap(value.getRolesList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.event.RelationAccessPath }),
    joinPathRefs = value.getJoinPathRefsList().map { ExecutableJoinPathRefFromProto(it) },
    physicalExecutionBindingId = value.getPhysicalExecutionBindingId(),
    physicalExecutionBindingVersion = value.getPhysicalExecutionBindingVersion()
)

internal fun ExecutableLogicUnitToProto(value: com.olap.prototype.engine.catalog.ExecutableLogicUnit): com.olap.prototype.wire.governance.ExecutableLogicUnit = com.olap.prototype.wire.governance.ExecutableLogicUnit.newBuilder().apply {
    setLogicUnitId(value.logicUnitId)
    setLogicUnitVersion(value.logicUnitVersion)
    value.valueType?.let { setValueType(it.name) }
    value.valueShape?.let { setValueShape(SemanticProto.toProto(it)) }
    value.topologyId?.let { setTopologyId(it) }
    value.topologyVersion?.let { setTopologyVersion(it) }
    setLogicUnitCode(value.logicUnitCode)
    value.displayName?.let { setDisplayName(it) }
    setPhysicalExecutionBindingId(value.physicalExecutionBindingId)
    setPhysicalExecutionBindingVersion(value.physicalExecutionBindingVersion)
    addAllOutputSymbols(value.outputSymbols.map { it })
}.build()
internal fun ExecutableLogicUnitFromProto(value: com.olap.prototype.wire.governance.ExecutableLogicUnit): com.olap.prototype.engine.catalog.ExecutableLogicUnit = com.olap.prototype.engine.catalog.ExecutableLogicUnit(
    logicUnitId = value.getLogicUnitId(),
    logicUnitVersion = value.getLogicUnitVersion(),
    valueType = if (value.hasValueType()) com.olap.prototype.query.ScalarValueType.valueOf(value.getValueType()) else null,
    valueShape = if (value.hasValueShape()) SemanticProto.fromProto(value.getValueShape()) as com.olap.prototype.query.SetOutputContract.Shape else null,
    topologyId = if (value.hasTopologyId()) value.getTopologyId() else null,
    topologyVersion = if (value.hasTopologyVersion()) value.getTopologyVersion() else null,
    logicUnitCode = value.getLogicUnitCode(),
    displayName = if (value.hasDisplayName()) value.getDisplayName() else null,
    physicalExecutionBindingId = value.getPhysicalExecutionBindingId(),
    physicalExecutionBindingVersion = value.getPhysicalExecutionBindingVersion(),
    outputSymbols = value.getOutputSymbolsList().map { it }
)

internal fun ExecutableTimeAnchorToProto(value: com.olap.prototype.engine.catalog.ExecutableTimeAnchor): com.olap.prototype.wire.governance.ExecutableTimeAnchor = com.olap.prototype.wire.governance.ExecutableTimeAnchor.newBuilder().apply {
    setEventId(value.eventId)
    setEventVersion(value.eventVersion)
    setAnalysisDimensionId(value.analysisDimensionId)
    setAnalysisDimensionVersion(value.analysisDimensionVersion)
    setLevelCode(value.levelCode)
    setTopologyId(value.topologyId)
    setTopologyVersion(value.topologyVersion)
    value.topologyImplementationNamespaceCode?.let { setTopologyImplementationNamespaceCode(it) }
    value.topologyImplementationNamespaceDisplayName?.let { setTopologyImplementationNamespaceDisplayName(it) }
    setEffectiveTimeLogicUnitId(value.effectiveTimeLogicUnitId)
    setEffectiveTimeLogicUnitVersion(value.effectiveTimeLogicUnitVersion)
    value.effectiveTimeLogicUnitDisplayName?.let { setEffectiveTimeLogicUnitDisplayName(it) }
    setPhysicalExecutionBindingId(value.physicalExecutionBindingId)
    setPhysicalExecutionBindingVersion(value.physicalExecutionBindingVersion)
    value.timeRole?.let { setTimeRole(it) }
}.build()
internal fun ExecutableTimeAnchorFromProto(value: com.olap.prototype.wire.governance.ExecutableTimeAnchor): com.olap.prototype.engine.catalog.ExecutableTimeAnchor = com.olap.prototype.engine.catalog.ExecutableTimeAnchor(
    eventId = value.getEventId(),
    eventVersion = value.getEventVersion(),
    analysisDimensionId = value.getAnalysisDimensionId(),
    analysisDimensionVersion = value.getAnalysisDimensionVersion(),
    levelCode = value.getLevelCode(),
    topologyId = value.getTopologyId(),
    topologyVersion = value.getTopologyVersion(),
    topologyImplementationNamespaceCode = if (value.hasTopologyImplementationNamespaceCode()) value.getTopologyImplementationNamespaceCode() else null,
    topologyImplementationNamespaceDisplayName = if (value.hasTopologyImplementationNamespaceDisplayName()) value.getTopologyImplementationNamespaceDisplayName() else null,
    effectiveTimeLogicUnitId = value.getEffectiveTimeLogicUnitId(),
    effectiveTimeLogicUnitVersion = value.getEffectiveTimeLogicUnitVersion(),
    effectiveTimeLogicUnitDisplayName = if (value.hasEffectiveTimeLogicUnitDisplayName()) value.getEffectiveTimeLogicUnitDisplayName() else null,
    physicalExecutionBindingId = value.getPhysicalExecutionBindingId(),
    physicalExecutionBindingVersion = value.getPhysicalExecutionBindingVersion(),
    timeRole = if (value.hasTimeRole()) value.getTimeRole() else null
)

internal fun ExecutableJoinPathToProto(value: com.olap.prototype.engine.catalog.ExecutableJoinPath): com.olap.prototype.wire.governance.ExecutableJoinPath = com.olap.prototype.wire.governance.ExecutableJoinPath.newBuilder().apply {
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
    value.temporalConstraint?.let { setTemporalConstraint(SemanticProto.toProto(it)) }
    setNullPolicy(value.nullPolicy)
    addAllKeys(value.keys.map { ExecutableJoinKeyToProto(it) })
}.build()
internal fun ExecutableJoinPathFromProto(value: com.olap.prototype.wire.governance.ExecutableJoinPath): com.olap.prototype.engine.catalog.ExecutableJoinPath = com.olap.prototype.engine.catalog.ExecutableJoinPath(
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
    temporalConstraint = if (value.hasTemporalConstraint()) SemanticProto.fromProto(value.getTemporalConstraint()) as com.olap.prototype.query.TemporalJoinContract.Constraint else null,
    nullPolicy = value.getNullPolicy(),
    keys = value.getKeysList().map { ExecutableJoinKeyFromProto(it) }
)

internal fun ExecutableJoinKeyToProto(value: com.olap.prototype.engine.catalog.ExecutableJoinKey): com.olap.prototype.wire.governance.ExecutableJoinKey = com.olap.prototype.wire.governance.ExecutableJoinKey.newBuilder().apply {
    setOrder(value.order)
    setSourceLogicUnitId(value.sourceLogicUnitId)
    setSourceLogicUnitVersion(value.sourceLogicUnitVersion)
    setTargetLogicUnitId(value.targetLogicUnitId)
    setTargetLogicUnitVersion(value.targetLogicUnitVersion)
}.build()
internal fun ExecutableJoinKeyFromProto(value: com.olap.prototype.wire.governance.ExecutableJoinKey): com.olap.prototype.engine.catalog.ExecutableJoinKey = com.olap.prototype.engine.catalog.ExecutableJoinKey(
    order = value.getOrder(),
    sourceLogicUnitId = value.getSourceLogicUnitId(),
    sourceLogicUnitVersion = value.getSourceLogicUnitVersion(),
    targetLogicUnitId = value.getTargetLogicUnitId(),
    targetLogicUnitVersion = value.getTargetLogicUnitVersion()
)

internal fun ExecutablePhysicalExecutionBindingToProto(value: com.olap.prototype.engine.catalog.ExecutablePhysicalExecutionBinding): com.olap.prototype.wire.governance.ExecutablePhysicalExecutionBinding = com.olap.prototype.wire.governance.ExecutablePhysicalExecutionBinding.newBuilder().apply {
    setBindingId(value.bindingId)
    setBindingVersion(value.bindingVersion)
    value.eventId?.let { setEventId(it) }
    value.eventVersion?.let { setEventVersion(it) }
    value.logicUnitId?.let { setLogicUnitId(it) }
    value.logicUnitVersion?.let { setLogicUnitVersion(it) }
    setLogicalTableId(value.logicalTableId)
    setLogicalTableVersion(value.logicalTableVersion)
    setPhysicalTableId(value.physicalTableId)
    setPhysicalTableVersion(value.physicalTableVersion)
    setDataSourceId(value.dataSourceId)
    setConnectorRef(SemanticProto.toProto(value.connectorRef))
    addAllQualifiedTablePath(value.qualifiedTablePath.map { it })
    setDialect(value.dialect)
    value.catalogName?.let { setCatalogName(it) }
    value.schemaName?.let { setSchemaName(it) }
    setTableName(value.tableName)
    setSourceConstraints(PhysicalSourceConstraintsToProto(value.sourceConstraints))
    addAllSnapshotUniqueKeys(value.snapshotUniqueKeys.map { SnapshotUniqueKeyToProto(it) })
    addAllNativeTableDependencies(value.nativeTableDependencies.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.ExecutablePhysicalExecutionBindingNativeTableDependenciesEntry.newBuilder().setKey(entry.key).apply { setValue(NativeTableDependencyToProto(entry.value)) }.build() })
    addAllLogicUnitBindings(value.logicUnitBindings.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.ExecutablePhysicalExecutionBindingLogicUnitBindingsEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    addAllPhysicalColumnBindings(value.physicalColumnBindings.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.ExecutablePhysicalExecutionBindingPhysicalColumnBindingsEntry.newBuilder().setKey(entry.key).apply { setValue(entry.value) }.build() })
    addAllPhysicalColumnTypes(value.physicalColumnTypes.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.ExecutablePhysicalExecutionBindingPhysicalColumnTypesEntry.newBuilder().setKey(entry.key).apply { setValue(JdbcColumnTypeToProto(entry.value)) }.build() })
}.build()
internal fun ExecutablePhysicalExecutionBindingFromProto(value: com.olap.prototype.wire.governance.ExecutablePhysicalExecutionBinding): com.olap.prototype.engine.catalog.ExecutablePhysicalExecutionBinding = com.olap.prototype.engine.catalog.ExecutablePhysicalExecutionBinding(
    bindingId = value.getBindingId(),
    bindingVersion = value.getBindingVersion(),
    eventId = if (value.hasEventId()) value.getEventId() else null,
    eventVersion = if (value.hasEventVersion()) value.getEventVersion() else null,
    logicUnitId = if (value.hasLogicUnitId()) value.getLogicUnitId() else null,
    logicUnitVersion = if (value.hasLogicUnitVersion()) value.getLogicUnitVersion() else null,
    logicalTableId = value.getLogicalTableId(),
    logicalTableVersion = value.getLogicalTableVersion(),
    physicalTableId = value.getPhysicalTableId(),
    physicalTableVersion = value.getPhysicalTableVersion(),
    dataSourceId = value.getDataSourceId(),
    connectorRef = required(value.hasConnectorRef(), "ExecutablePhysicalExecutionBinding.connectorRef") { SemanticProto.fromProto(value.getConnectorRef()) as com.olap.prototype.connector.ConnectorRef },
    qualifiedTablePath = value.getQualifiedTablePathList().map { it },
    dialect = value.getDialect(),
    catalogName = if (value.hasCatalogName()) value.getCatalogName() else null,
    schemaName = if (value.hasSchemaName()) value.getSchemaName() else null,
    tableName = value.getTableName(),
    sourceConstraints = required(value.hasSourceConstraints(), "ExecutablePhysicalExecutionBinding.sourceConstraints") { PhysicalSourceConstraintsFromProto(value.getSourceConstraints()) },
    snapshotUniqueKeys = value.getSnapshotUniqueKeysList().map { SnapshotUniqueKeyFromProto(it) },
    nativeTableDependencies = strictMap(value.getNativeTableDependenciesList().map { it.getKey() to NativeTableDependencyFromProto(it.getValue()) }),
    logicUnitBindings = strictMap(value.getLogicUnitBindingsList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.expression.SemanticExpression }),
    physicalColumnBindings = strictMap(value.getPhysicalColumnBindingsList().map { it.getKey() to it.getValue() }),
    physicalColumnTypes = strictMap(value.getPhysicalColumnTypesList().map { it.getKey() to JdbcColumnTypeFromProto(it.getValue()) })
)

internal fun SourceBindingConflictExceptionInputToProto(value: com.olap.prototype.engine.catalog.SourceBindingConflictException.Input): com.olap.prototype.wire.governance.SourceBindingConflictExceptionInput = com.olap.prototype.wire.governance.SourceBindingConflictExceptionInput.newBuilder().apply {
    addAllScopePath(value.scopePath.map { it })
    setAnchor(value.anchor)
    setDataSourceId(value.dataSourceId)
    setSourceIdentityHash(value.sourceIdentityHash)
    setConnectorRef(SemanticProto.toProto(value.connectorRef))
    setDialect(value.dialect)
    setPhysicalTableId(value.physicalTableId)
    setPhysicalTableVersion(value.physicalTableVersion)
}.build()
internal fun SourceBindingConflictExceptionInputFromProto(value: com.olap.prototype.wire.governance.SourceBindingConflictExceptionInput): com.olap.prototype.engine.catalog.SourceBindingConflictException.Input = com.olap.prototype.engine.catalog.SourceBindingConflictException.Input(
    scopePath = value.getScopePathList().map { it },
    anchor = value.getAnchor(),
    dataSourceId = value.getDataSourceId(),
    sourceIdentityHash = value.getSourceIdentityHash(),
    connectorRef = required(value.hasConnectorRef(), "SourceBindingConflictExceptionInput.connectorRef") { SemanticProto.fromProto(value.getConnectorRef()) as com.olap.prototype.connector.ConnectorRef },
    dialect = value.getDialect(),
    physicalTableId = value.getPhysicalTableId(),
    physicalTableVersion = value.getPhysicalTableVersion()
)
