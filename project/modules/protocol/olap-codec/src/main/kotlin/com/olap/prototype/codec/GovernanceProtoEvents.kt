package com.olap.prototype.codec

/** 正式领域消息的显式字段转换。 */
internal fun RelationFilterToProto(value: com.olap.prototype.event.RelationFilter): com.olap.prototype.wire.governance.RelationFilter = com.olap.prototype.wire.governance.RelationFilter.newBuilder().apply {
    setId(value.id)
    setVersion(value.version)
    setParent(SemanticProto.toProto(value.parent))
    setRelationshipRole(value.relationshipRole)
    setMemberSourceId(value.memberSourceId)
    setMemberSourceVersion(value.memberSourceVersion)
    addAllClockSlots(value.clockSlots.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.RelationFilterClockSlotsEntry.newBuilder().setKey(entry.key).apply { setValue(entry.value) }.build() })
}.build()
internal fun RelationFilterFromProto(value: com.olap.prototype.wire.governance.RelationFilter): com.olap.prototype.event.RelationFilter = com.olap.prototype.event.RelationFilter(
    id = value.getId(),
    version = value.getVersion(),
    parent = required(value.hasParent(), "RelationFilter.parent") { SemanticProto.fromProto(value.getParent()) as com.olap.prototype.event.EventRef },
    relationshipRole = value.getRelationshipRole(),
    memberSourceId = value.getMemberSourceId(),
    memberSourceVersion = value.getMemberSourceVersion(),
    clockSlots = strictMap(value.getClockSlotsList().map { it.getKey() to it.getValue() })
)

internal fun PublishedDimensionBindingToProto(value: com.olap.prototype.event.PublishedDimensionBinding): com.olap.prototype.wire.governance.PublishedDimensionBinding = com.olap.prototype.wire.governance.PublishedDimensionBinding.newBuilder().apply {
    setId(value.id)
    setVersion(value.version)
    setDimensionId(value.dimensionId)
    setDimensionVersion(value.dimensionVersion)
    setEvent(SemanticProto.toProto(value.event))
    setTopologyId(value.topologyId)
    setTopologyVersion(value.topologyVersion)
    setLogicUnitId(value.logicUnitId)
    setLogicUnitVersion(value.logicUnitVersion)
    setExpression(SemanticProto.toProto(value.expression))
    setAccessPath(SemanticProto.toProto(value.accessPath))
    addAllRoles(value.roles.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedDimensionBindingRolesEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
}.build()
internal fun PublishedDimensionBindingFromProto(value: com.olap.prototype.wire.governance.PublishedDimensionBinding): com.olap.prototype.event.PublishedDimensionBinding = com.olap.prototype.event.PublishedDimensionBinding(
    id = value.getId(),
    version = value.getVersion(),
    dimensionId = value.getDimensionId(),
    dimensionVersion = value.getDimensionVersion(),
    event = required(value.hasEvent(), "PublishedDimensionBinding.event") { SemanticProto.fromProto(value.getEvent()) as com.olap.prototype.event.EventRef },
    topologyId = value.getTopologyId(),
    topologyVersion = value.getTopologyVersion(),
    logicUnitId = value.getLogicUnitId(),
    logicUnitVersion = value.getLogicUnitVersion(),
    expression = required(value.hasExpression(), "PublishedDimensionBinding.expression") { SemanticProto.fromProto(value.getExpression()) as com.olap.prototype.expression.SemanticExpression },
    accessPath = required(value.hasAccessPath(), "PublishedDimensionBinding.accessPath") { SemanticProto.fromProto(value.getAccessPath()) as com.olap.prototype.event.RelationAccessPath },
    roles = strictMap(value.getRolesList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.event.RelationAccessPath })
)

internal fun EventCreateRequestToProto(value: com.olap.prototype.governance.contract.EventCreateRequest): com.olap.prototype.wire.governance.EventCreateRequest = com.olap.prototype.wire.governance.EventCreateRequest.newBuilder().apply {
    setTopologyId(value.topologyId)
    setTopologyVersion(value.topologyVersion)
    setCode(value.code)
    setDisplayName(value.displayName)
    setOwner(value.owner)
    value.eventTimeLogicUnitId?.let { setEventTimeLogicUnitId(it) }
    value.eventTimeLogicUnitVersion?.let { setEventTimeLogicUnitVersion(it) }
    setIdentity(SemanticProto.toProto(value.identity))
    addAllRoles(value.roles.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.EventCreateRequestRolesEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    addAllFilterableLogicUnitRefs(value.filterableLogicUnitRefs.map { EventCreateRequestLogicUnitRefInputToProto(it) })
    addAllMeasureLogicUnitRefs(value.measureLogicUnitRefs.map { EventCreateRequestLogicUnitRefInputToProto(it) })
    value.description?.let { setDescription(it) }
    setCreatedBy(value.createdBy)
}.build()
internal fun EventCreateRequestFromProto(value: com.olap.prototype.wire.governance.EventCreateRequest): com.olap.prototype.governance.contract.EventCreateRequest = com.olap.prototype.governance.contract.EventCreateRequest(
    topologyId = value.getTopologyId(),
    topologyVersion = value.getTopologyVersion(),
    code = value.getCode(),
    displayName = value.getDisplayName(),
    owner = value.getOwner(),
    eventTimeLogicUnitId = if (value.hasEventTimeLogicUnitId()) value.getEventTimeLogicUnitId() else null,
    eventTimeLogicUnitVersion = if (value.hasEventTimeLogicUnitVersion()) value.getEventTimeLogicUnitVersion() else null,
    identity = required(value.hasIdentity(), "EventCreateRequest.identity") { SemanticProto.fromProto(value.getIdentity()) as com.olap.prototype.event.EventIdentity },
    roles = strictMap(value.getRolesList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.event.RelationAccessPath }),
    filterableLogicUnitRefs = value.getFilterableLogicUnitRefsList().map { EventCreateRequestLogicUnitRefInputFromProto(it) },
    measureLogicUnitRefs = value.getMeasureLogicUnitRefsList().map { EventCreateRequestLogicUnitRefInputFromProto(it) },
    description = if (value.hasDescription()) value.getDescription() else null,
    createdBy = value.getCreatedBy()
)

internal fun EventCreateRequestLogicUnitRefInputToProto(value: com.olap.prototype.governance.contract.EventCreateRequest.LogicUnitRefInput): com.olap.prototype.wire.governance.EventCreateRequestLogicUnitRefInput = com.olap.prototype.wire.governance.EventCreateRequestLogicUnitRefInput.newBuilder().apply {
    setLogicUnitId(value.logicUnitId)
    setLogicUnitVersion(value.logicUnitVersion)
}.build()
internal fun EventCreateRequestLogicUnitRefInputFromProto(value: com.olap.prototype.wire.governance.EventCreateRequestLogicUnitRefInput): com.olap.prototype.governance.contract.EventCreateRequest.LogicUnitRefInput = com.olap.prototype.governance.contract.EventCreateRequest.LogicUnitRefInput(
    logicUnitId = value.getLogicUnitId(),
    logicUnitVersion = value.getLogicUnitVersion()
)

internal fun EventCreateResultToProto(value: com.olap.prototype.governance.contract.EventCreateResult): com.olap.prototype.wire.governance.EventCreateResult = com.olap.prototype.wire.governance.EventCreateResult.newBuilder().apply {
    setEvent(EventRecordToProto(value.event))
    setEventVersion(EventVersionRecordToProto(value.eventVersion))
    addAllLogicUnitRefs(value.logicUnitRefs.map { EventLogicUnitRefRecordToProto(it) })
}.build()
internal fun EventCreateResultFromProto(value: com.olap.prototype.wire.governance.EventCreateResult): com.olap.prototype.governance.contract.EventCreateResult = com.olap.prototype.governance.contract.EventCreateResult(
    event = required(value.hasEvent(), "EventCreateResult.event") { EventRecordFromProto(value.getEvent()) },
    eventVersion = required(value.hasEventVersion(), "EventCreateResult.eventVersion") { EventVersionRecordFromProto(value.getEventVersion()) },
    logicUnitRefs = value.getLogicUnitRefsList().map { EventLogicUnitRefRecordFromProto(it) }
)

internal fun EventRecordToProto(value: com.olap.prototype.governance.contract.EventRecord): com.olap.prototype.wire.governance.EventRecord = com.olap.prototype.wire.governance.EventRecord.newBuilder().apply {
    setId(value.id)
    setCode(value.code)
    setDisplayName(value.displayName)
    setTopologyId(value.topologyId)
    setTopologyVersion(value.topologyVersion)
    value.eventTimeLogicUnitId?.let { setEventTimeLogicUnitId(it) }
    value.eventTimeLogicUnitVersion?.let { setEventTimeLogicUnitVersion(it) }
    value.description?.let { setDescription(it) }
    setOwner(value.owner)
    setStatus(value.status)
    setCurrentVersion(value.currentVersion)
    setCreatedBy(value.createdBy)
    setCreatedAt(value.createdAt.toString())
    value.updatedBy?.let { setUpdatedBy(it) }
    value.updatedAt?.let { setUpdatedAt(it.toString()) }
}.build()
internal fun EventRecordFromProto(value: com.olap.prototype.wire.governance.EventRecord): com.olap.prototype.governance.contract.EventRecord = com.olap.prototype.governance.contract.EventRecord(
    id = value.getId(),
    code = value.getCode(),
    displayName = value.getDisplayName(),
    topologyId = value.getTopologyId(),
    topologyVersion = value.getTopologyVersion(),
    eventTimeLogicUnitId = if (value.hasEventTimeLogicUnitId()) value.getEventTimeLogicUnitId() else null,
    eventTimeLogicUnitVersion = if (value.hasEventTimeLogicUnitVersion()) value.getEventTimeLogicUnitVersion() else null,
    description = if (value.hasDescription()) value.getDescription() else null,
    owner = value.getOwner(),
    status = value.getStatus(),
    currentVersion = value.getCurrentVersion(),
    createdBy = value.getCreatedBy(),
    createdAt = java.time.Instant.parse(value.getCreatedAt()),
    updatedBy = if (value.hasUpdatedBy()) value.getUpdatedBy() else null,
    updatedAt = if (value.hasUpdatedAt()) java.time.Instant.parse(value.getUpdatedAt()) else null
)

internal fun EventVersionRecordToProto(value: com.olap.prototype.governance.contract.EventVersionRecord): com.olap.prototype.wire.governance.EventVersionRecord = com.olap.prototype.wire.governance.EventVersionRecord.newBuilder().apply {
    setId(value.id)
    setEventId(value.eventId)
    setVersion(value.version)
    setVersionStatus(value.versionStatus)
    setContentProto(value.contentProto)
    value.diagnosticJson?.let { setDiagnosticJson(it) }
    value.sourceVersion?.let { setSourceVersion(it) }
    setCreatedBy(value.createdBy)
    setCreatedAt(value.createdAt.toString())
    value.activatedBy?.let { setActivatedBy(it) }
    value.activatedAt?.let { setActivatedAt(it.toString()) }
    value.deprecatedBy?.let { setDeprecatedBy(it) }
    value.deprecatedAt?.let { setDeprecatedAt(it.toString()) }
    value.archivedBy?.let { setArchivedBy(it) }
    value.archivedAt?.let { setArchivedAt(it.toString()) }
}.build()
internal fun EventVersionRecordFromProto(value: com.olap.prototype.wire.governance.EventVersionRecord): com.olap.prototype.governance.contract.EventVersionRecord = com.olap.prototype.governance.contract.EventVersionRecord(
    id = value.getId(),
    eventId = value.getEventId(),
    version = value.getVersion(),
    versionStatus = value.getVersionStatus(),
    contentProto = value.getContentProto(),
    diagnosticJson = if (value.hasDiagnosticJson()) value.getDiagnosticJson() else null,
    sourceVersion = if (value.hasSourceVersion()) value.getSourceVersion() else null,
    createdBy = value.getCreatedBy(),
    createdAt = java.time.Instant.parse(value.getCreatedAt()),
    activatedBy = if (value.hasActivatedBy()) value.getActivatedBy() else null,
    activatedAt = if (value.hasActivatedAt()) java.time.Instant.parse(value.getActivatedAt()) else null,
    deprecatedBy = if (value.hasDeprecatedBy()) value.getDeprecatedBy() else null,
    deprecatedAt = if (value.hasDeprecatedAt()) java.time.Instant.parse(value.getDeprecatedAt()) else null,
    archivedBy = if (value.hasArchivedBy()) value.getArchivedBy() else null,
    archivedAt = if (value.hasArchivedAt()) java.time.Instant.parse(value.getArchivedAt()) else null
)

internal fun EventLogicUnitRefRecordToProto(value: com.olap.prototype.governance.contract.EventLogicUnitRefRecord): com.olap.prototype.wire.governance.EventLogicUnitRefRecord = com.olap.prototype.wire.governance.EventLogicUnitRefRecord.newBuilder().apply {
    setId(value.id)
    setEventId(value.eventId)
    setEventVersion(value.eventVersion)
    setLogicUnitId(value.logicUnitId)
    setLogicUnitVersion(value.logicUnitVersion)
    setRefRole(value.refRole)
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun EventLogicUnitRefRecordFromProto(value: com.olap.prototype.wire.governance.EventLogicUnitRefRecord): com.olap.prototype.governance.contract.EventLogicUnitRefRecord = com.olap.prototype.governance.contract.EventLogicUnitRefRecord(
    id = value.getId(),
    eventId = value.getEventId(),
    eventVersion = value.getEventVersion(),
    logicUnitId = value.getLogicUnitId(),
    logicUnitVersion = value.getLogicUnitVersion(),
    refRole = value.getRefRole(),
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)

internal fun EventSliceFilterRecordToProto(value: com.olap.prototype.governance.contract.EventSliceFilterRecord): com.olap.prototype.wire.governance.EventSliceFilterRecord = com.olap.prototype.wire.governance.EventSliceFilterRecord.newBuilder().apply {
    setId(value.id)
    setEventId(value.eventId)
    setEventVersion(value.eventVersion)
    setPredicate(SemanticProto.toProto(value.predicate))
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun EventSliceFilterRecordFromProto(value: com.olap.prototype.wire.governance.EventSliceFilterRecord): com.olap.prototype.governance.contract.EventSliceFilterRecord = com.olap.prototype.governance.contract.EventSliceFilterRecord(
    id = value.getId(),
    eventId = value.getEventId(),
    eventVersion = value.getEventVersion(),
    predicate = required(value.hasPredicate(), "EventSliceFilterRecord.predicate") { SemanticProto.fromProto(value.getPredicate()) as com.olap.prototype.expression.SemanticExpression },
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)
