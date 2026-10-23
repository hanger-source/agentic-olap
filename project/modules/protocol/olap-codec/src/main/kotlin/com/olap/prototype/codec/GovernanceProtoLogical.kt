package com.olap.prototype.codec

/** 正式领域消息的显式字段转换。 */
internal fun LogicalTableCreateRequestToProto(value: com.olap.prototype.governance.contract.LogicalTableCreateRequest): com.olap.prototype.wire.governance.LogicalTableCreateRequest = com.olap.prototype.wire.governance.LogicalTableCreateRequest.newBuilder().apply {
    setPhysicalTableId(value.physicalTableId)
    setPhysicalTableVersion(value.physicalTableVersion)
    setCode(value.code)
    setDisplayName(value.displayName)
    setTableRole(value.tableRole.name)
    value.description?.let { setDescription(it) }
    setOwner(value.owner)
    setCreatedBy(value.createdBy)
    addAllLogicUnits(value.logicUnits.map { LogicalTableCreateRequestLogicUnitInputToProto(it) })
}.build()
internal fun LogicalTableCreateRequestFromProto(value: com.olap.prototype.wire.governance.LogicalTableCreateRequest): com.olap.prototype.governance.contract.LogicalTableCreateRequest = com.olap.prototype.governance.contract.LogicalTableCreateRequest(
    physicalTableId = value.getPhysicalTableId(),
    physicalTableVersion = value.getPhysicalTableVersion(),
    code = value.getCode(),
    displayName = value.getDisplayName(),
    tableRole = com.olap.prototype.governance.contract.LogicalTableRole.valueOf(value.getTableRole()),
    description = if (value.hasDescription()) value.getDescription() else null,
    owner = value.getOwner(),
    createdBy = value.getCreatedBy(),
    logicUnits = value.getLogicUnitsList().map { LogicalTableCreateRequestLogicUnitInputFromProto(it) }
)

internal fun LogicalTableCreateRequestLogicUnitInputToProto(value: com.olap.prototype.governance.contract.LogicalTableCreateRequest.LogicUnitInput): com.olap.prototype.wire.governance.LogicalTableCreateRequestLogicUnitInput = com.olap.prototype.wire.governance.LogicalTableCreateRequestLogicUnitInput.newBuilder().apply {
    setCode(value.code)
    setDisplayName(value.displayName)
    setSourceKind(value.sourceKind.name)
    value.expression?.let { setExpression(SemanticProto.toProto(it)) }
    value.logicType?.let { setLogicType(it.name) }
    value.valueShape?.let { setValueShape(SemanticProto.toProto(it)) }
    value.isTime?.let { setIsTime(it) }
    value.isPrimaryKey?.let { setIsPrimaryKey(it) }
    value.isForeignKey?.let { setIsForeignKey(it) }
    value.filterable?.let { setFilterable(it) }
    value.aggregatable?.let { setAggregatable(it) }
    value.sourceRefs?.let { setSourceRefs(com.olap.prototype.wire.governance.LogicalTableCreateRequestLogicUnitInputSourceRefsValues.newBuilder().addAllValues(it.map { LogicalTableCreateRequestSourceRefInputToProto(it) }).build()) }
}.build()
internal fun LogicalTableCreateRequestLogicUnitInputFromProto(value: com.olap.prototype.wire.governance.LogicalTableCreateRequestLogicUnitInput): com.olap.prototype.governance.contract.LogicalTableCreateRequest.LogicUnitInput = com.olap.prototype.governance.contract.LogicalTableCreateRequest.LogicUnitInput(
    code = value.getCode(),
    displayName = value.getDisplayName(),
    sourceKind = com.olap.prototype.governance.contract.LogicUnitSourceKind.valueOf(value.getSourceKind()),
    expression = if (value.hasExpression()) SemanticProto.fromProto(value.getExpression()) as com.olap.prototype.expression.SemanticExpression else null,
    logicType = if (value.hasLogicType()) com.olap.prototype.governance.contract.LogicType.valueOf(value.getLogicType()) else null,
    valueShape = if (value.hasValueShape()) com.olap.prototype.query.SetOutputContract.requireSourceContainer(SemanticProto.fromProto(value.getValueShape()), listOf("valueShape")) else null,
    isTime = if (value.hasIsTime()) value.getIsTime() else null,
    isPrimaryKey = if (value.hasIsPrimaryKey()) value.getIsPrimaryKey() else null,
    isForeignKey = if (value.hasIsForeignKey()) value.getIsForeignKey() else null,
    filterable = if (value.hasFilterable()) value.getFilterable() else null,
    aggregatable = if (value.hasAggregatable()) value.getAggregatable() else null,
    sourceRefs = if (value.hasSourceRefs()) value.getSourceRefs().getValuesList().map { LogicalTableCreateRequestSourceRefInputFromProto(it) } else null
)

internal fun LogicalTableCreateRequestSourceRefInputToProto(value: com.olap.prototype.governance.contract.LogicalTableCreateRequest.SourceRefInput): com.olap.prototype.wire.governance.LogicalTableCreateRequestSourceRefInput = com.olap.prototype.wire.governance.LogicalTableCreateRequestSourceRefInput.newBuilder().apply {
    setPhysicalColumnId(value.physicalColumnId)
    setPhysicalColumnVersion(value.physicalColumnVersion)
    setRefRole(value.refRole.name)
}.build()
internal fun LogicalTableCreateRequestSourceRefInputFromProto(value: com.olap.prototype.wire.governance.LogicalTableCreateRequestSourceRefInput): com.olap.prototype.governance.contract.LogicalTableCreateRequest.SourceRefInput = com.olap.prototype.governance.contract.LogicalTableCreateRequest.SourceRefInput(
    physicalColumnId = value.getPhysicalColumnId(),
    physicalColumnVersion = value.getPhysicalColumnVersion(),
    refRole = com.olap.prototype.governance.contract.LogicUnitSourceRefRole.valueOf(value.getRefRole())
)

internal fun LogicalTableCreateResultToProto(value: com.olap.prototype.governance.contract.LogicalTableCreateResult): com.olap.prototype.wire.governance.LogicalTableCreateResult = com.olap.prototype.wire.governance.LogicalTableCreateResult.newBuilder().apply {
    setLogicalTable(LogicalTableRecordToProto(value.logicalTable))
    setLogicalTableVersion(LogicalTableVersionRecordToProto(value.logicalTableVersion))
    setPhysicalTableRef(PhysicalLogicalTableRefRecordToProto(value.physicalTableRef))
    addAllLogicUnits(value.logicUnits.map { LogicUnitRecordToProto(it) })
    addAllLogicUnitVersions(value.logicUnitVersions.map { LogicUnitVersionRecordToProto(it) })
    addAllLogicUnitRefs(value.logicUnitRefs.map { LogicalTableLogicUnitRefRecordToProto(it) })
    addAllSourceRefs(value.sourceRefs.map { LogicUnitSourceRefRecordToProto(it) })
}.build()
internal fun LogicalTableCreateResultFromProto(value: com.olap.prototype.wire.governance.LogicalTableCreateResult): com.olap.prototype.governance.contract.LogicalTableCreateResult = com.olap.prototype.governance.contract.LogicalTableCreateResult(
    logicalTable = required(value.hasLogicalTable(), "LogicalTableCreateResult.logicalTable") { LogicalTableRecordFromProto(value.getLogicalTable()) },
    logicalTableVersion = required(value.hasLogicalTableVersion(), "LogicalTableCreateResult.logicalTableVersion") { LogicalTableVersionRecordFromProto(value.getLogicalTableVersion()) },
    physicalTableRef = required(value.hasPhysicalTableRef(), "LogicalTableCreateResult.physicalTableRef") { PhysicalLogicalTableRefRecordFromProto(value.getPhysicalTableRef()) },
    logicUnits = value.getLogicUnitsList().map { LogicUnitRecordFromProto(it) },
    logicUnitVersions = value.getLogicUnitVersionsList().map { LogicUnitVersionRecordFromProto(it) },
    logicUnitRefs = value.getLogicUnitRefsList().map { LogicalTableLogicUnitRefRecordFromProto(it) },
    sourceRefs = value.getSourceRefsList().map { LogicUnitSourceRefRecordFromProto(it) }
)

internal fun LogicalTableRecordToProto(value: com.olap.prototype.governance.contract.LogicalTableRecord): com.olap.prototype.wire.governance.LogicalTableRecord = com.olap.prototype.wire.governance.LogicalTableRecord.newBuilder().apply {
    setId(value.id)
    setCode(value.code)
    setDisplayName(value.displayName)
    setTableRole(value.tableRole)
    value.description?.let { setDescription(it) }
    setOwner(value.owner)
    setStatus(value.status)
    setCurrentVersion(value.currentVersion)
    setCreatedBy(value.createdBy)
    setCreatedAt(value.createdAt.toString())
    value.updatedBy?.let { setUpdatedBy(it) }
    value.updatedAt?.let { setUpdatedAt(it.toString()) }
}.build()
internal fun LogicalTableRecordFromProto(value: com.olap.prototype.wire.governance.LogicalTableRecord): com.olap.prototype.governance.contract.LogicalTableRecord = com.olap.prototype.governance.contract.LogicalTableRecord(
    id = value.getId(),
    code = value.getCode(),
    displayName = value.getDisplayName(),
    tableRole = value.getTableRole(),
    description = if (value.hasDescription()) value.getDescription() else null,
    owner = value.getOwner(),
    status = value.getStatus(),
    currentVersion = value.getCurrentVersion(),
    createdBy = value.getCreatedBy(),
    createdAt = java.time.Instant.parse(value.getCreatedAt()),
    updatedBy = if (value.hasUpdatedBy()) value.getUpdatedBy() else null,
    updatedAt = if (value.hasUpdatedAt()) java.time.Instant.parse(value.getUpdatedAt()) else null
)

internal fun LogicalTableVersionRecordToProto(value: com.olap.prototype.governance.contract.LogicalTableVersionRecord): com.olap.prototype.wire.governance.LogicalTableVersionRecord = com.olap.prototype.wire.governance.LogicalTableVersionRecord.newBuilder().apply {
    setId(value.id)
    setLogicalTableId(value.logicalTableId)
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
internal fun LogicalTableVersionRecordFromProto(value: com.olap.prototype.wire.governance.LogicalTableVersionRecord): com.olap.prototype.governance.contract.LogicalTableVersionRecord = com.olap.prototype.governance.contract.LogicalTableVersionRecord(
    id = value.getId(),
    logicalTableId = value.getLogicalTableId(),
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

internal fun LogicUnitRecordToProto(value: com.olap.prototype.governance.contract.LogicUnitRecord): com.olap.prototype.wire.governance.LogicUnitRecord = com.olap.prototype.wire.governance.LogicUnitRecord.newBuilder().apply {
    setId(value.id)
    setLogicalTableId(value.logicalTableId)
    setCode(value.code)
    setDisplayName(value.displayName)
    setSourceKind(value.sourceKind)
    value.expression?.let { setExpression(SemanticProto.toProto(it)) }
    value.logicType?.let { setLogicType(it) }
    value.valueShape?.let { setValueShape(SemanticProto.toProto(it)) }
    setIsTime(value.isTime)
    setIsPrimaryKey(value.isPrimaryKey)
    setIsForeignKey(value.isForeignKey)
    setFilterable(value.filterable)
    setAggregatable(value.aggregatable)
    setStatus(value.status)
    setCurrentVersion(value.currentVersion)
    setCreatedBy(value.createdBy)
    setCreatedAt(value.createdAt.toString())
    value.updatedBy?.let { setUpdatedBy(it) }
    value.updatedAt?.let { setUpdatedAt(it.toString()) }
}.build()
internal fun LogicUnitRecordFromProto(value: com.olap.prototype.wire.governance.LogicUnitRecord): com.olap.prototype.governance.contract.LogicUnitRecord = com.olap.prototype.governance.contract.LogicUnitRecord(
    id = value.getId(),
    logicalTableId = value.getLogicalTableId(),
    code = value.getCode(),
    displayName = value.getDisplayName(),
    sourceKind = value.getSourceKind(),
    expression = if (value.hasExpression()) SemanticProto.fromProto(value.getExpression()) as com.olap.prototype.expression.SemanticExpression else null,
    logicType = if (value.hasLogicType()) value.getLogicType() else null,
    valueShape = if (value.hasValueShape()) com.olap.prototype.query.SetOutputContract.requireSourceContainer(SemanticProto.fromProto(value.getValueShape()), listOf("valueShape")) else null,
    isTime = value.getIsTime(),
    isPrimaryKey = value.getIsPrimaryKey(),
    isForeignKey = value.getIsForeignKey(),
    filterable = value.getFilterable(),
    aggregatable = value.getAggregatable(),
    status = value.getStatus(),
    currentVersion = value.getCurrentVersion(),
    createdBy = value.getCreatedBy(),
    createdAt = java.time.Instant.parse(value.getCreatedAt()),
    updatedBy = if (value.hasUpdatedBy()) value.getUpdatedBy() else null,
    updatedAt = if (value.hasUpdatedAt()) java.time.Instant.parse(value.getUpdatedAt()) else null
)

internal fun LogicUnitVersionRecordToProto(value: com.olap.prototype.governance.contract.LogicUnitVersionRecord): com.olap.prototype.wire.governance.LogicUnitVersionRecord = com.olap.prototype.wire.governance.LogicUnitVersionRecord.newBuilder().apply {
    setId(value.id)
    setLogicUnitId(value.logicUnitId)
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
internal fun LogicUnitVersionRecordFromProto(value: com.olap.prototype.wire.governance.LogicUnitVersionRecord): com.olap.prototype.governance.contract.LogicUnitVersionRecord = com.olap.prototype.governance.contract.LogicUnitVersionRecord(
    id = value.getId(),
    logicUnitId = value.getLogicUnitId(),
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

internal fun LogicalTableLogicUnitRefRecordToProto(value: com.olap.prototype.governance.contract.LogicalTableLogicUnitRefRecord): com.olap.prototype.wire.governance.LogicalTableLogicUnitRefRecord = com.olap.prototype.wire.governance.LogicalTableLogicUnitRefRecord.newBuilder().apply {
    setId(value.id)
    setLogicalTableId(value.logicalTableId)
    setLogicalTableVersion(value.logicalTableVersion)
    setLogicUnitId(value.logicUnitId)
    setLogicUnitVersion(value.logicUnitVersion)
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun LogicalTableLogicUnitRefRecordFromProto(value: com.olap.prototype.wire.governance.LogicalTableLogicUnitRefRecord): com.olap.prototype.governance.contract.LogicalTableLogicUnitRefRecord = com.olap.prototype.governance.contract.LogicalTableLogicUnitRefRecord(
    id = value.getId(),
    logicalTableId = value.getLogicalTableId(),
    logicalTableVersion = value.getLogicalTableVersion(),
    logicUnitId = value.getLogicUnitId(),
    logicUnitVersion = value.getLogicUnitVersion(),
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)

internal fun LogicUnitSourceRefRecordToProto(value: com.olap.prototype.governance.contract.LogicUnitSourceRefRecord): com.olap.prototype.wire.governance.LogicUnitSourceRefRecord = com.olap.prototype.wire.governance.LogicUnitSourceRefRecord.newBuilder().apply {
    setId(value.id)
    setLogicUnitId(value.logicUnitId)
    setLogicUnitVersion(value.logicUnitVersion)
    setPhysicalColumnId(value.physicalColumnId)
    setPhysicalColumnVersion(value.physicalColumnVersion)
    setRefRole(value.refRole)
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun LogicUnitSourceRefRecordFromProto(value: com.olap.prototype.wire.governance.LogicUnitSourceRefRecord): com.olap.prototype.governance.contract.LogicUnitSourceRefRecord = com.olap.prototype.governance.contract.LogicUnitSourceRefRecord(
    id = value.getId(),
    logicUnitId = value.getLogicUnitId(),
    logicUnitVersion = value.getLogicUnitVersion(),
    physicalColumnId = value.getPhysicalColumnId(),
    physicalColumnVersion = value.getPhysicalColumnVersion(),
    refRole = value.getRefRole(),
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)
