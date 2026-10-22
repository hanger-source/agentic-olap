package com.olap.prototype.codec

/** 正式领域消息的显式字段转换。 */
internal fun AnalysisDimensionValueAlignmentRecordToProto(value: com.olap.prototype.governance.contract.AnalysisDimensionValueAlignmentRecord): com.olap.prototype.wire.governance.AnalysisDimensionValueAlignmentRecord = com.olap.prototype.wire.governance.AnalysisDimensionValueAlignmentRecord.newBuilder().apply {
    setId(value.id)
    setAnalysisDimensionId(value.analysisDimensionId)
    setAnalysisDimensionVersion(value.analysisDimensionVersion)
    setLevelCode(value.levelCode)
    setAlignmentType(value.alignmentType)
    setEvidenceJson(value.evidenceJson)
    value.confirmedBy?.let { setConfirmedBy(it) }
    value.confirmedAt?.let { setConfirmedAt(it.toString()) }
    setDiagnosticStatus(value.diagnosticStatus)
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun AnalysisDimensionValueAlignmentRecordFromProto(value: com.olap.prototype.wire.governance.AnalysisDimensionValueAlignmentRecord): com.olap.prototype.governance.contract.AnalysisDimensionValueAlignmentRecord = com.olap.prototype.governance.contract.AnalysisDimensionValueAlignmentRecord(
    id = value.getId(),
    analysisDimensionId = value.getAnalysisDimensionId(),
    analysisDimensionVersion = value.getAnalysisDimensionVersion(),
    levelCode = value.getLevelCode(),
    alignmentType = value.getAlignmentType(),
    evidenceJson = value.getEvidenceJson(),
    confirmedBy = if (value.hasConfirmedBy()) value.getConfirmedBy() else null,
    confirmedAt = if (value.hasConfirmedAt()) java.time.Instant.parse(value.getConfirmedAt()) else null,
    diagnosticStatus = value.getDiagnosticStatus(),
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)

internal fun DimensionCreateRequestToProto(value: com.olap.prototype.governance.contract.DimensionCreateRequest): com.olap.prototype.wire.governance.DimensionCreateRequest = com.olap.prototype.wire.governance.DimensionCreateRequest.newBuilder().apply {
    setCode(value.code)
    setDimensionCodeNamespaceDisplayName(value.dimensionCodeNamespaceDisplayName)
    setDisplayName(value.displayName)
    setOwner(value.owner)
    value.description?.let { setDescription(it) }
    setCreatedBy(value.createdBy)
}.build()
internal fun DimensionCreateRequestFromProto(value: com.olap.prototype.wire.governance.DimensionCreateRequest): com.olap.prototype.governance.contract.DimensionCreateRequest = com.olap.prototype.governance.contract.DimensionCreateRequest(
    code = value.getCode(),
    dimensionCodeNamespaceDisplayName = value.getDimensionCodeNamespaceDisplayName(),
    displayName = value.getDisplayName(),
    owner = value.getOwner(),
    description = if (value.hasDescription()) value.getDescription() else null,
    createdBy = value.getCreatedBy()
)

internal fun DimensionCreateResultToProto(value: com.olap.prototype.governance.contract.DimensionCreateResult): com.olap.prototype.wire.governance.DimensionCreateResult = com.olap.prototype.wire.governance.DimensionCreateResult.newBuilder().apply {
    setDimension(DimensionRecordToProto(value.dimension))
    setDimensionVersion(DimensionVersionRecordToProto(value.dimensionVersion))
}.build()
internal fun DimensionCreateResultFromProto(value: com.olap.prototype.wire.governance.DimensionCreateResult): com.olap.prototype.governance.contract.DimensionCreateResult = com.olap.prototype.governance.contract.DimensionCreateResult(
    dimension = required(value.hasDimension(), "DimensionCreateResult.dimension") { DimensionRecordFromProto(value.getDimension()) },
    dimensionVersion = required(value.hasDimensionVersion(), "DimensionCreateResult.dimensionVersion") { DimensionVersionRecordFromProto(value.getDimensionVersion()) }
)

internal fun DimensionRecordToProto(value: com.olap.prototype.governance.contract.DimensionRecord): com.olap.prototype.wire.governance.DimensionRecord = com.olap.prototype.wire.governance.DimensionRecord.newBuilder().apply {
    setId(value.id)
    setCode(value.code)
    setDimensionCodeNamespace(value.dimensionCodeNamespace)
    setDimensionCodeNamespaceDisplayName(value.dimensionCodeNamespaceDisplayName)
    setLocalCode(value.localCode)
    setDisplayName(value.displayName)
    value.description?.let { setDescription(it) }
    setOwner(value.owner)
    setStatus(value.status)
    setCurrentVersion(value.currentVersion)
    setCreatedBy(value.createdBy)
    setCreatedAt(value.createdAt.toString())
    value.updatedBy?.let { setUpdatedBy(it) }
    value.updatedAt?.let { setUpdatedAt(it.toString()) }
}.build()
internal fun DimensionRecordFromProto(value: com.olap.prototype.wire.governance.DimensionRecord): com.olap.prototype.governance.contract.DimensionRecord = com.olap.prototype.governance.contract.DimensionRecord(
    id = value.getId(),
    code = value.getCode(),
    dimensionCodeNamespace = value.getDimensionCodeNamespace(),
    dimensionCodeNamespaceDisplayName = value.getDimensionCodeNamespaceDisplayName(),
    localCode = value.getLocalCode(),
    displayName = value.getDisplayName(),
    description = if (value.hasDescription()) value.getDescription() else null,
    owner = value.getOwner(),
    status = value.getStatus(),
    currentVersion = value.getCurrentVersion(),
    createdBy = value.getCreatedBy(),
    createdAt = java.time.Instant.parse(value.getCreatedAt()),
    updatedBy = if (value.hasUpdatedBy()) value.getUpdatedBy() else null,
    updatedAt = if (value.hasUpdatedAt()) java.time.Instant.parse(value.getUpdatedAt()) else null
)

internal fun DimensionVersionRecordToProto(value: com.olap.prototype.governance.contract.DimensionVersionRecord): com.olap.prototype.wire.governance.DimensionVersionRecord = com.olap.prototype.wire.governance.DimensionVersionRecord.newBuilder().apply {
    setId(value.id)
    setDimensionId(value.dimensionId)
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
internal fun DimensionVersionRecordFromProto(value: com.olap.prototype.wire.governance.DimensionVersionRecord): com.olap.prototype.governance.contract.DimensionVersionRecord = com.olap.prototype.governance.contract.DimensionVersionRecord(
    id = value.getId(),
    dimensionId = value.getDimensionId(),
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

internal fun DimensionEventBindingCreateRequestToProto(value: com.olap.prototype.governance.contract.DimensionEventBindingCreateRequest): com.olap.prototype.wire.governance.DimensionEventBindingCreateRequest = com.olap.prototype.wire.governance.DimensionEventBindingCreateRequest.newBuilder().apply {
    setDimensionId(value.dimensionId)
    setDimensionVersion(value.dimensionVersion)
    setEventId(value.eventId)
    setEventVersion(value.eventVersion)
    value.logicUnitId?.let { setLogicUnitId(it) }
    value.logicUnitVersion?.let { setLogicUnitVersion(it) }
    value.expression?.let { setExpression(SemanticProto.toProto(it)) }
    value.accessPath?.let { setAccessPath(SemanticProto.toProto(it)) }
    addAllRoles(value.roles.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.DimensionEventBindingCreateRequestRolesEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    setCreatedBy(value.createdBy)
}.build()
internal fun DimensionEventBindingCreateRequestFromProto(value: com.olap.prototype.wire.governance.DimensionEventBindingCreateRequest): com.olap.prototype.governance.contract.DimensionEventBindingCreateRequest = com.olap.prototype.governance.contract.DimensionEventBindingCreateRequest(
    dimensionId = value.getDimensionId(),
    dimensionVersion = value.getDimensionVersion(),
    eventId = value.getEventId(),
    eventVersion = value.getEventVersion(),
    logicUnitId = if (value.hasLogicUnitId()) value.getLogicUnitId() else null,
    logicUnitVersion = if (value.hasLogicUnitVersion()) value.getLogicUnitVersion() else null,
    expression = if (value.hasExpression()) SemanticProto.fromProto(value.getExpression()) as com.olap.prototype.expression.SemanticExpression else null,
    accessPath = if (value.hasAccessPath()) SemanticProto.fromProto(value.getAccessPath()) as com.olap.prototype.event.RelationAccessPath else null,
    roles = strictMap(value.getRolesList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.event.RelationAccessPath }),
    createdBy = value.getCreatedBy()
)

internal fun DimensionEventBindingCreateResultToProto(value: com.olap.prototype.governance.contract.DimensionEventBindingCreateResult): com.olap.prototype.wire.governance.DimensionEventBindingCreateResult = com.olap.prototype.wire.governance.DimensionEventBindingCreateResult.newBuilder().apply {
    setBinding(DimensionEventBindingRecordToProto(value.binding))
    setBindingVersion(DimensionEventBindingVersionRecordToProto(value.bindingVersion))
}.build()
internal fun DimensionEventBindingCreateResultFromProto(value: com.olap.prototype.wire.governance.DimensionEventBindingCreateResult): com.olap.prototype.governance.contract.DimensionEventBindingCreateResult = com.olap.prototype.governance.contract.DimensionEventBindingCreateResult(
    binding = required(value.hasBinding(), "DimensionEventBindingCreateResult.binding") { DimensionEventBindingRecordFromProto(value.getBinding()) },
    bindingVersion = required(value.hasBindingVersion(), "DimensionEventBindingCreateResult.bindingVersion") { DimensionEventBindingVersionRecordFromProto(value.getBindingVersion()) }
)

internal fun DimensionEventBindingRecordToProto(value: com.olap.prototype.governance.contract.DimensionEventBindingRecord): com.olap.prototype.wire.governance.DimensionEventBindingRecord = com.olap.prototype.wire.governance.DimensionEventBindingRecord.newBuilder().apply {
    setId(value.id)
    setDimensionId(value.dimensionId)
    setDimensionVersion(value.dimensionVersion)
    setEventId(value.eventId)
    setEventVersion(value.eventVersion)
    setTopologyId(value.topologyId)
    setTopologyVersion(value.topologyVersion)
    value.logicUnitId?.let { setLogicUnitId(it) }
    value.logicUnitVersion?.let { setLogicUnitVersion(it) }
    value.expression?.let { setExpression(SemanticProto.toProto(it)) }
    setStatus(value.status)
    setCurrentVersion(value.currentVersion)
    setCreatedBy(value.createdBy)
    setCreatedAt(value.createdAt.toString())
    value.updatedBy?.let { setUpdatedBy(it) }
    value.updatedAt?.let { setUpdatedAt(it.toString()) }
}.build()
internal fun DimensionEventBindingRecordFromProto(value: com.olap.prototype.wire.governance.DimensionEventBindingRecord): com.olap.prototype.governance.contract.DimensionEventBindingRecord = com.olap.prototype.governance.contract.DimensionEventBindingRecord(
    id = value.getId(),
    dimensionId = value.getDimensionId(),
    dimensionVersion = value.getDimensionVersion(),
    eventId = value.getEventId(),
    eventVersion = value.getEventVersion(),
    topologyId = value.getTopologyId(),
    topologyVersion = value.getTopologyVersion(),
    logicUnitId = if (value.hasLogicUnitId()) value.getLogicUnitId() else null,
    logicUnitVersion = if (value.hasLogicUnitVersion()) value.getLogicUnitVersion() else null,
    expression = if (value.hasExpression()) SemanticProto.fromProto(value.getExpression()) as com.olap.prototype.expression.SemanticExpression else null,
    status = value.getStatus(),
    currentVersion = value.getCurrentVersion(),
    createdBy = value.getCreatedBy(),
    createdAt = java.time.Instant.parse(value.getCreatedAt()),
    updatedBy = if (value.hasUpdatedBy()) value.getUpdatedBy() else null,
    updatedAt = if (value.hasUpdatedAt()) java.time.Instant.parse(value.getUpdatedAt()) else null
)

internal fun DimensionEventBindingVersionRecordToProto(value: com.olap.prototype.governance.contract.DimensionEventBindingVersionRecord): com.olap.prototype.wire.governance.DimensionEventBindingVersionRecord = com.olap.prototype.wire.governance.DimensionEventBindingVersionRecord.newBuilder().apply {
    setId(value.id)
    setBindingId(value.bindingId)
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
internal fun DimensionEventBindingVersionRecordFromProto(value: com.olap.prototype.wire.governance.DimensionEventBindingVersionRecord): com.olap.prototype.governance.contract.DimensionEventBindingVersionRecord = com.olap.prototype.governance.contract.DimensionEventBindingVersionRecord(
    id = value.getId(),
    bindingId = value.getBindingId(),
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

internal fun AnalysisDimensionCreateRequestToProto(value: com.olap.prototype.governance.contract.AnalysisDimensionCreateRequest): com.olap.prototype.wire.governance.AnalysisDimensionCreateRequest = com.olap.prototype.wire.governance.AnalysisDimensionCreateRequest.newBuilder().apply {
    setSubjectAreaId(value.subjectAreaId)
    setSubjectAreaVersion(value.subjectAreaVersion)
    setCode(value.code)
    setDisplayName(value.displayName)
    value.description?.let { setDescription(it) }
    setLevelCode(value.levelCode)
    setLevelDisplayName(value.levelDisplayName)
    setCreatedBy(value.createdBy)
}.build()
internal fun AnalysisDimensionCreateRequestFromProto(value: com.olap.prototype.wire.governance.AnalysisDimensionCreateRequest): com.olap.prototype.governance.contract.AnalysisDimensionCreateRequest = com.olap.prototype.governance.contract.AnalysisDimensionCreateRequest(
    subjectAreaId = value.getSubjectAreaId(),
    subjectAreaVersion = value.getSubjectAreaVersion(),
    code = value.getCode(),
    displayName = value.getDisplayName(),
    description = if (value.hasDescription()) value.getDescription() else null,
    levelCode = value.getLevelCode(),
    levelDisplayName = value.getLevelDisplayName(),
    createdBy = value.getCreatedBy()
)

internal fun AnalysisDimensionCreateResultToProto(value: com.olap.prototype.governance.contract.AnalysisDimensionCreateResult): com.olap.prototype.wire.governance.AnalysisDimensionCreateResult = com.olap.prototype.wire.governance.AnalysisDimensionCreateResult.newBuilder().apply {
    setAnalysisDimension(AnalysisDimensionRecordToProto(value.analysisDimension))
    setAnalysisDimensionVersion(AnalysisDimensionVersionRecordToProto(value.analysisDimensionVersion))
    setDefaultLevel(AnalysisDimensionLevelRecordToProto(value.defaultLevel))
    setSubjectAreaRef(SubjectAreaAnalysisDimensionRefRecordToProto(value.subjectAreaRef))
}.build()
internal fun AnalysisDimensionCreateResultFromProto(value: com.olap.prototype.wire.governance.AnalysisDimensionCreateResult): com.olap.prototype.governance.contract.AnalysisDimensionCreateResult = com.olap.prototype.governance.contract.AnalysisDimensionCreateResult(
    analysisDimension = required(value.hasAnalysisDimension(), "AnalysisDimensionCreateResult.analysisDimension") { AnalysisDimensionRecordFromProto(value.getAnalysisDimension()) },
    analysisDimensionVersion = required(value.hasAnalysisDimensionVersion(), "AnalysisDimensionCreateResult.analysisDimensionVersion") { AnalysisDimensionVersionRecordFromProto(value.getAnalysisDimensionVersion()) },
    defaultLevel = required(value.hasDefaultLevel(), "AnalysisDimensionCreateResult.defaultLevel") { AnalysisDimensionLevelRecordFromProto(value.getDefaultLevel()) },
    subjectAreaRef = required(value.hasSubjectAreaRef(), "AnalysisDimensionCreateResult.subjectAreaRef") { SubjectAreaAnalysisDimensionRefRecordFromProto(value.getSubjectAreaRef()) }
)

internal fun AnalysisDimensionRecordToProto(value: com.olap.prototype.governance.contract.AnalysisDimensionRecord): com.olap.prototype.wire.governance.AnalysisDimensionRecord = com.olap.prototype.wire.governance.AnalysisDimensionRecord.newBuilder().apply {
    setId(value.id)
    setSubjectAreaId(value.subjectAreaId)
    setSubjectAreaVersion(value.subjectAreaVersion)
    setCode(value.code)
    setDisplayName(value.displayName)
    value.description?.let { setDescription(it) }
    setStatus(value.status)
    setCurrentVersion(value.currentVersion)
    setCreatedBy(value.createdBy)
    setCreatedAt(value.createdAt.toString())
    value.updatedBy?.let { setUpdatedBy(it) }
    value.updatedAt?.let { setUpdatedAt(it.toString()) }
}.build()
internal fun AnalysisDimensionRecordFromProto(value: com.olap.prototype.wire.governance.AnalysisDimensionRecord): com.olap.prototype.governance.contract.AnalysisDimensionRecord = com.olap.prototype.governance.contract.AnalysisDimensionRecord(
    id = value.getId(),
    subjectAreaId = value.getSubjectAreaId(),
    subjectAreaVersion = value.getSubjectAreaVersion(),
    code = value.getCode(),
    displayName = value.getDisplayName(),
    description = if (value.hasDescription()) value.getDescription() else null,
    status = value.getStatus(),
    currentVersion = value.getCurrentVersion(),
    createdBy = value.getCreatedBy(),
    createdAt = java.time.Instant.parse(value.getCreatedAt()),
    updatedBy = if (value.hasUpdatedBy()) value.getUpdatedBy() else null,
    updatedAt = if (value.hasUpdatedAt()) java.time.Instant.parse(value.getUpdatedAt()) else null
)

internal fun AnalysisDimensionVersionRecordToProto(value: com.olap.prototype.governance.contract.AnalysisDimensionVersionRecord): com.olap.prototype.wire.governance.AnalysisDimensionVersionRecord = com.olap.prototype.wire.governance.AnalysisDimensionVersionRecord.newBuilder().apply {
    setId(value.id)
    setAnalysisDimensionId(value.analysisDimensionId)
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
internal fun AnalysisDimensionVersionRecordFromProto(value: com.olap.prototype.wire.governance.AnalysisDimensionVersionRecord): com.olap.prototype.governance.contract.AnalysisDimensionVersionRecord = com.olap.prototype.governance.contract.AnalysisDimensionVersionRecord(
    id = value.getId(),
    analysisDimensionId = value.getAnalysisDimensionId(),
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

internal fun AnalysisDimensionLevelRecordToProto(value: com.olap.prototype.governance.contract.AnalysisDimensionLevelRecord): com.olap.prototype.wire.governance.AnalysisDimensionLevelRecord = com.olap.prototype.wire.governance.AnalysisDimensionLevelRecord.newBuilder().apply {
    setId(value.id)
    setAnalysisDimensionId(value.analysisDimensionId)
    setAnalysisDimensionVersion(value.analysisDimensionVersion)
    setLevelCode(value.levelCode)
    setDisplayName(value.displayName)
    value.parentLevelCode?.let { setParentLevelCode(it) }
    setSortOrder(value.sortOrder)
    setStatus(value.status)
}.build()
internal fun AnalysisDimensionLevelRecordFromProto(value: com.olap.prototype.wire.governance.AnalysisDimensionLevelRecord): com.olap.prototype.governance.contract.AnalysisDimensionLevelRecord = com.olap.prototype.governance.contract.AnalysisDimensionLevelRecord(
    id = value.getId(),
    analysisDimensionId = value.getAnalysisDimensionId(),
    analysisDimensionVersion = value.getAnalysisDimensionVersion(),
    levelCode = value.getLevelCode(),
    displayName = value.getDisplayName(),
    parentLevelCode = if (value.hasParentLevelCode()) value.getParentLevelCode() else null,
    sortOrder = value.getSortOrder(),
    status = value.getStatus()
)

internal fun AnalysisDimensionEventMappingCreateRequestToProto(value: com.olap.prototype.governance.contract.AnalysisDimensionEventMappingCreateRequest): com.olap.prototype.wire.governance.AnalysisDimensionEventMappingCreateRequest = com.olap.prototype.wire.governance.AnalysisDimensionEventMappingCreateRequest.newBuilder().apply {
    setAnalysisDimensionId(value.analysisDimensionId)
    setAnalysisDimensionVersion(value.analysisDimensionVersion)
    setLevelCode(value.levelCode)
    setBindingId(value.bindingId)
    setBindingVersion(value.bindingVersion)
    setAlignmentStatus(value.alignmentStatus)
    value.alignmentId?.let { setAlignmentId(it) }
}.build()
internal fun AnalysisDimensionEventMappingCreateRequestFromProto(value: com.olap.prototype.wire.governance.AnalysisDimensionEventMappingCreateRequest): com.olap.prototype.governance.contract.AnalysisDimensionEventMappingCreateRequest = com.olap.prototype.governance.contract.AnalysisDimensionEventMappingCreateRequest(
    analysisDimensionId = value.getAnalysisDimensionId(),
    analysisDimensionVersion = value.getAnalysisDimensionVersion(),
    levelCode = value.getLevelCode(),
    bindingId = value.getBindingId(),
    bindingVersion = value.getBindingVersion(),
    alignmentStatus = value.getAlignmentStatus(),
    alignmentId = if (value.hasAlignmentId()) value.getAlignmentId() else null
)

internal fun AnalysisDimensionEventMappingCreateResultToProto(value: com.olap.prototype.governance.contract.AnalysisDimensionEventMappingCreateResult): com.olap.prototype.wire.governance.AnalysisDimensionEventMappingCreateResult = com.olap.prototype.wire.governance.AnalysisDimensionEventMappingCreateResult.newBuilder().apply {
    setMapping(AnalysisDimensionEventMappingRecordToProto(value.mapping))
}.build()
internal fun AnalysisDimensionEventMappingCreateResultFromProto(value: com.olap.prototype.wire.governance.AnalysisDimensionEventMappingCreateResult): com.olap.prototype.governance.contract.AnalysisDimensionEventMappingCreateResult = com.olap.prototype.governance.contract.AnalysisDimensionEventMappingCreateResult(
    mapping = required(value.hasMapping(), "AnalysisDimensionEventMappingCreateResult.mapping") { AnalysisDimensionEventMappingRecordFromProto(value.getMapping()) }
)

internal fun AnalysisDimensionEventMappingRecordToProto(value: com.olap.prototype.governance.contract.AnalysisDimensionEventMappingRecord): com.olap.prototype.wire.governance.AnalysisDimensionEventMappingRecord = com.olap.prototype.wire.governance.AnalysisDimensionEventMappingRecord.newBuilder().apply {
    setId(value.id)
    setAnalysisDimensionId(value.analysisDimensionId)
    setAnalysisDimensionVersion(value.analysisDimensionVersion)
    setLevelCode(value.levelCode)
    setEventId(value.eventId)
    setEventVersion(value.eventVersion)
    setDimensionId(value.dimensionId)
    setDimensionVersion(value.dimensionVersion)
    setBindingId(value.bindingId)
    setBindingVersion(value.bindingVersion)
    setAlignmentStatus(value.alignmentStatus)
    value.alignmentId?.let { setAlignmentId(it) }
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun AnalysisDimensionEventMappingRecordFromProto(value: com.olap.prototype.wire.governance.AnalysisDimensionEventMappingRecord): com.olap.prototype.governance.contract.AnalysisDimensionEventMappingRecord = com.olap.prototype.governance.contract.AnalysisDimensionEventMappingRecord(
    id = value.getId(),
    analysisDimensionId = value.getAnalysisDimensionId(),
    analysisDimensionVersion = value.getAnalysisDimensionVersion(),
    levelCode = value.getLevelCode(),
    eventId = value.getEventId(),
    eventVersion = value.getEventVersion(),
    dimensionId = value.getDimensionId(),
    dimensionVersion = value.getDimensionVersion(),
    bindingId = value.getBindingId(),
    bindingVersion = value.getBindingVersion(),
    alignmentStatus = value.getAlignmentStatus(),
    alignmentId = if (value.hasAlignmentId()) value.getAlignmentId() else null,
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)

internal fun AnalysisDimensionValueProviderCreateRequestToProto(value: com.olap.prototype.governance.contract.AnalysisDimensionValueProviderCreateRequest): com.olap.prototype.wire.governance.AnalysisDimensionValueProviderCreateRequest = com.olap.prototype.wire.governance.AnalysisDimensionValueProviderCreateRequest.newBuilder().apply {
    setAnalysisDimensionId(value.analysisDimensionId)
    setAnalysisDimensionVersion(value.analysisDimensionVersion)
    setLevelCode(value.levelCode)
    setBindingId(value.bindingId)
    setBindingVersion(value.bindingVersion)
    setRefreshPolicy(value.refreshPolicy)
    setCreatedBy(value.createdBy)
}.build()
internal fun AnalysisDimensionValueProviderCreateRequestFromProto(value: com.olap.prototype.wire.governance.AnalysisDimensionValueProviderCreateRequest): com.olap.prototype.governance.contract.AnalysisDimensionValueProviderCreateRequest = com.olap.prototype.governance.contract.AnalysisDimensionValueProviderCreateRequest(
    analysisDimensionId = value.getAnalysisDimensionId(),
    analysisDimensionVersion = value.getAnalysisDimensionVersion(),
    levelCode = value.getLevelCode(),
    bindingId = value.getBindingId(),
    bindingVersion = value.getBindingVersion(),
    refreshPolicy = value.getRefreshPolicy(),
    createdBy = value.getCreatedBy()
)

internal fun AnalysisDimensionValueProviderCreateResultToProto(value: com.olap.prototype.governance.contract.AnalysisDimensionValueProviderCreateResult): com.olap.prototype.wire.governance.AnalysisDimensionValueProviderCreateResult = com.olap.prototype.wire.governance.AnalysisDimensionValueProviderCreateResult.newBuilder().apply {
    setProvider(AnalysisDimensionValueProviderRecordToProto(value.provider))
}.build()
internal fun AnalysisDimensionValueProviderCreateResultFromProto(value: com.olap.prototype.wire.governance.AnalysisDimensionValueProviderCreateResult): com.olap.prototype.governance.contract.AnalysisDimensionValueProviderCreateResult = com.olap.prototype.governance.contract.AnalysisDimensionValueProviderCreateResult(
    provider = required(value.hasProvider(), "AnalysisDimensionValueProviderCreateResult.provider") { AnalysisDimensionValueProviderRecordFromProto(value.getProvider()) }
)

internal fun AnalysisDimensionValueProviderRecordToProto(value: com.olap.prototype.governance.contract.AnalysisDimensionValueProviderRecord): com.olap.prototype.wire.governance.AnalysisDimensionValueProviderRecord = com.olap.prototype.wire.governance.AnalysisDimensionValueProviderRecord.newBuilder().apply {
    setId(value.id)
    setAnalysisDimensionId(value.analysisDimensionId)
    setAnalysisDimensionVersion(value.analysisDimensionVersion)
    setLevelCode(value.levelCode)
    setProviderType(value.providerType)
    value.sourceTableId?.let { setSourceTableId(it) }
    value.sourceTableVersion?.let { setSourceTableVersion(it) }
    value.sourceLogicUnitId?.let { setSourceLogicUnitId(it) }
    value.sourceLogicUnitVersion?.let { setSourceLogicUnitVersion(it) }
    setRefreshPolicy(value.refreshPolicy)
    setStatus(value.status)
    setCreatedBy(value.createdBy)
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun AnalysisDimensionValueProviderRecordFromProto(value: com.olap.prototype.wire.governance.AnalysisDimensionValueProviderRecord): com.olap.prototype.governance.contract.AnalysisDimensionValueProviderRecord = com.olap.prototype.governance.contract.AnalysisDimensionValueProviderRecord(
    id = value.getId(),
    analysisDimensionId = value.getAnalysisDimensionId(),
    analysisDimensionVersion = value.getAnalysisDimensionVersion(),
    levelCode = value.getLevelCode(),
    providerType = value.getProviderType(),
    sourceTableId = if (value.hasSourceTableId()) value.getSourceTableId() else null,
    sourceTableVersion = if (value.hasSourceTableVersion()) value.getSourceTableVersion() else null,
    sourceLogicUnitId = if (value.hasSourceLogicUnitId()) value.getSourceLogicUnitId() else null,
    sourceLogicUnitVersion = if (value.hasSourceLogicUnitVersion()) value.getSourceLogicUnitVersion() else null,
    refreshPolicy = value.getRefreshPolicy(),
    status = value.getStatus(),
    createdBy = value.getCreatedBy(),
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)

internal fun AnalysisDimensionValueAlignmentCreateRequestToProto(value: com.olap.prototype.governance.contract.AnalysisDimensionValueAlignmentCreateRequest): com.olap.prototype.wire.governance.AnalysisDimensionValueAlignmentCreateRequest = com.olap.prototype.wire.governance.AnalysisDimensionValueAlignmentCreateRequest.newBuilder().apply {
    setAnalysisDimensionId(value.analysisDimensionId)
    setAnalysisDimensionVersion(value.analysisDimensionVersion)
    setLevelCode(value.levelCode)
    setAlignmentStatus(value.alignmentStatus)
    setEvidenceJson(value.evidenceJson)
    setConfirmedBy(value.confirmedBy)
}.build()
internal fun AnalysisDimensionValueAlignmentCreateRequestFromProto(value: com.olap.prototype.wire.governance.AnalysisDimensionValueAlignmentCreateRequest): com.olap.prototype.governance.contract.AnalysisDimensionValueAlignmentCreateRequest = com.olap.prototype.governance.contract.AnalysisDimensionValueAlignmentCreateRequest(
    analysisDimensionId = value.getAnalysisDimensionId(),
    analysisDimensionVersion = value.getAnalysisDimensionVersion(),
    levelCode = value.getLevelCode(),
    alignmentStatus = value.getAlignmentStatus(),
    evidenceJson = value.getEvidenceJson(),
    confirmedBy = value.getConfirmedBy()
)
