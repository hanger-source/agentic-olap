package com.olap.prototype.codec

/** 正式领域消息的显式字段转换。 */
internal fun TopologyCreateRequestToProto(value: com.olap.prototype.governance.contract.TopologyCreateRequest): com.olap.prototype.wire.governance.TopologyCreateRequest = com.olap.prototype.wire.governance.TopologyCreateRequest.newBuilder().apply {
    setCode(value.code)
    setDisplayName(value.displayName)
    setTopologyImplementationNamespaceCode(value.topologyImplementationNamespaceCode)
    setTopologyImplementationNamespaceDisplayName(value.topologyImplementationNamespaceDisplayName)
    setBackboneLogicalTableId(value.backboneLogicalTableId)
    setBackboneLogicalTableVersion(value.backboneLogicalTableVersion)
    setOwner(value.owner)
    value.description?.let { setDescription(it) }
    setCreatedBy(value.createdBy)
}.build()
internal fun TopologyCreateRequestFromProto(value: com.olap.prototype.wire.governance.TopologyCreateRequest): com.olap.prototype.governance.contract.TopologyCreateRequest = com.olap.prototype.governance.contract.TopologyCreateRequest(
    code = value.getCode(),
    displayName = value.getDisplayName(),
    topologyImplementationNamespaceCode = value.getTopologyImplementationNamespaceCode(),
    topologyImplementationNamespaceDisplayName = value.getTopologyImplementationNamespaceDisplayName(),
    backboneLogicalTableId = value.getBackboneLogicalTableId(),
    backboneLogicalTableVersion = value.getBackboneLogicalTableVersion(),
    owner = value.getOwner(),
    description = if (value.hasDescription()) value.getDescription() else null,
    createdBy = value.getCreatedBy()
)

internal fun TopologyCreateResultToProto(value: com.olap.prototype.governance.contract.TopologyCreateResult): com.olap.prototype.wire.governance.TopologyCreateResult = com.olap.prototype.wire.governance.TopologyCreateResult.newBuilder().apply {
    setTopology(TopologyRecordToProto(value.topology))
    setTopologyVersion(TopologyVersionRecordToProto(value.topologyVersion))
    setBackboneRef(TopologyLogicalTableRefRecordToProto(value.backboneRef))
}.build()
internal fun TopologyCreateResultFromProto(value: com.olap.prototype.wire.governance.TopologyCreateResult): com.olap.prototype.governance.contract.TopologyCreateResult = com.olap.prototype.governance.contract.TopologyCreateResult(
    topology = required(value.hasTopology(), "TopologyCreateResult.topology") { TopologyRecordFromProto(value.getTopology()) },
    topologyVersion = required(value.hasTopologyVersion(), "TopologyCreateResult.topologyVersion") { TopologyVersionRecordFromProto(value.getTopologyVersion()) },
    backboneRef = required(value.hasBackboneRef(), "TopologyCreateResult.backboneRef") { TopologyLogicalTableRefRecordFromProto(value.getBackboneRef()) }
)

internal fun TopologyRecordToProto(value: com.olap.prototype.governance.contract.TopologyRecord): com.olap.prototype.wire.governance.TopologyRecord = com.olap.prototype.wire.governance.TopologyRecord.newBuilder().apply {
    setId(value.id)
    setCode(value.code)
    setDisplayName(value.displayName)
    setTopologyImplementationNamespaceCode(value.topologyImplementationNamespaceCode)
    setTopologyImplementationNamespaceDisplayName(value.topologyImplementationNamespaceDisplayName)
    setBackboneLogicalTableId(value.backboneLogicalTableId)
    value.description?.let { setDescription(it) }
    setOwner(value.owner)
    setStatus(value.status)
    setCurrentVersion(value.currentVersion)
    setCreatedBy(value.createdBy)
    setCreatedAt(value.createdAt.toString())
    value.updatedBy?.let { setUpdatedBy(it) }
    value.updatedAt?.let { setUpdatedAt(it.toString()) }
}.build()
internal fun TopologyRecordFromProto(value: com.olap.prototype.wire.governance.TopologyRecord): com.olap.prototype.governance.contract.TopologyRecord = com.olap.prototype.governance.contract.TopologyRecord(
    id = value.getId(),
    code = value.getCode(),
    displayName = value.getDisplayName(),
    topologyImplementationNamespaceCode = value.getTopologyImplementationNamespaceCode(),
    topologyImplementationNamespaceDisplayName = value.getTopologyImplementationNamespaceDisplayName(),
    backboneLogicalTableId = value.getBackboneLogicalTableId(),
    description = if (value.hasDescription()) value.getDescription() else null,
    owner = value.getOwner(),
    status = value.getStatus(),
    currentVersion = value.getCurrentVersion(),
    createdBy = value.getCreatedBy(),
    createdAt = java.time.Instant.parse(value.getCreatedAt()),
    updatedBy = if (value.hasUpdatedBy()) value.getUpdatedBy() else null,
    updatedAt = if (value.hasUpdatedAt()) java.time.Instant.parse(value.getUpdatedAt()) else null
)

internal fun TopologyVersionRecordToProto(value: com.olap.prototype.governance.contract.TopologyVersionRecord): com.olap.prototype.wire.governance.TopologyVersionRecord = com.olap.prototype.wire.governance.TopologyVersionRecord.newBuilder().apply {
    setId(value.id)
    setTopologyId(value.topologyId)
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
internal fun TopologyVersionRecordFromProto(value: com.olap.prototype.wire.governance.TopologyVersionRecord): com.olap.prototype.governance.contract.TopologyVersionRecord = com.olap.prototype.governance.contract.TopologyVersionRecord(
    id = value.getId(),
    topologyId = value.getTopologyId(),
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

internal fun TopologyLogicalTableRefRecordToProto(value: com.olap.prototype.governance.contract.TopologyLogicalTableRefRecord): com.olap.prototype.wire.governance.TopologyLogicalTableRefRecord = com.olap.prototype.wire.governance.TopologyLogicalTableRefRecord.newBuilder().apply {
    setId(value.id)
    setTopologyId(value.topologyId)
    setTopologyVersion(value.topologyVersion)
    setLogicalTableId(value.logicalTableId)
    setLogicalTableVersion(value.logicalTableVersion)
    setUsageRole(value.usageRole)
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun TopologyLogicalTableRefRecordFromProto(value: com.olap.prototype.wire.governance.TopologyLogicalTableRefRecord): com.olap.prototype.governance.contract.TopologyLogicalTableRefRecord = com.olap.prototype.governance.contract.TopologyLogicalTableRefRecord(
    id = value.getId(),
    topologyId = value.getTopologyId(),
    topologyVersion = value.getTopologyVersion(),
    logicalTableId = value.getLogicalTableId(),
    logicalTableVersion = value.getLogicalTableVersion(),
    usageRole = value.getUsageRole(),
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)

internal fun TopologyLogicalTableRefCreateRequestToProto(value: com.olap.prototype.governance.contract.TopologyLogicalTableRefCreateRequest): com.olap.prototype.wire.governance.TopologyLogicalTableRefCreateRequest = com.olap.prototype.wire.governance.TopologyLogicalTableRefCreateRequest.newBuilder().apply {
    setTopologyId(value.topologyId)
    setTopologyVersion(value.topologyVersion)
    setLogicalTableId(value.logicalTableId)
    setLogicalTableVersion(value.logicalTableVersion)
    setUsageRole(value.usageRole.name)
}.build()
internal fun TopologyLogicalTableRefCreateRequestFromProto(value: com.olap.prototype.wire.governance.TopologyLogicalTableRefCreateRequest): com.olap.prototype.governance.contract.TopologyLogicalTableRefCreateRequest = com.olap.prototype.governance.contract.TopologyLogicalTableRefCreateRequest(
    topologyId = value.getTopologyId(),
    topologyVersion = value.getTopologyVersion(),
    logicalTableId = value.getLogicalTableId(),
    logicalTableVersion = value.getLogicalTableVersion(),
    usageRole = com.olap.prototype.governance.contract.TopologyNodeRole.valueOf(value.getUsageRole())
)

internal fun TopologyLogicalTableRefCreateResultToProto(value: com.olap.prototype.governance.contract.TopologyLogicalTableRefCreateResult): com.olap.prototype.wire.governance.TopologyLogicalTableRefCreateResult = com.olap.prototype.wire.governance.TopologyLogicalTableRefCreateResult.newBuilder().apply {
    setTopologyLogicalTableRef(TopologyLogicalTableRefRecordToProto(value.topologyLogicalTableRef))
}.build()
internal fun TopologyLogicalTableRefCreateResultFromProto(value: com.olap.prototype.wire.governance.TopologyLogicalTableRefCreateResult): com.olap.prototype.governance.contract.TopologyLogicalTableRefCreateResult = com.olap.prototype.governance.contract.TopologyLogicalTableRefCreateResult(
    topologyLogicalTableRef = required(value.hasTopologyLogicalTableRef(), "TopologyLogicalTableRefCreateResult.topologyLogicalTableRef") { TopologyLogicalTableRefRecordFromProto(value.getTopologyLogicalTableRef()) }
)

internal fun JoinPathCreateRequestToProto(value: com.olap.prototype.governance.contract.JoinPathCreateRequest): com.olap.prototype.wire.governance.JoinPathCreateRequest = com.olap.prototype.wire.governance.JoinPathCreateRequest.newBuilder().apply {
    setTopologyId(value.topologyId)
    setTopologyVersion(value.topologyVersion)
    setCode(value.code)
    setSourceLogicalTableId(value.sourceLogicalTableId)
    setSourceLogicalTableVersion(value.sourceLogicalTableVersion)
    setTargetLogicalTableId(value.targetLogicalTableId)
    setTargetLogicalTableVersion(value.targetLogicalTableVersion)
    setJoinType(value.joinType)
    setCardinality(value.cardinality)
    value.temporal?.let { setTemporal(SemanticProto.toProto(it)) }
    setNullPolicy(value.nullPolicy)
    setCreatedBy(value.createdBy)
    addAllKeyRefs(value.keyRefs.map { JoinPathCreateRequestKeyRefInputToProto(it) })
}.build()
internal fun JoinPathCreateRequestFromProto(value: com.olap.prototype.wire.governance.JoinPathCreateRequest): com.olap.prototype.governance.contract.JoinPathCreateRequest = com.olap.prototype.governance.contract.JoinPathCreateRequest(
    topologyId = value.getTopologyId(),
    topologyVersion = value.getTopologyVersion(),
    code = value.getCode(),
    sourceLogicalTableId = value.getSourceLogicalTableId(),
    sourceLogicalTableVersion = value.getSourceLogicalTableVersion(),
    targetLogicalTableId = value.getTargetLogicalTableId(),
    targetLogicalTableVersion = value.getTargetLogicalTableVersion(),
    joinType = value.getJoinType(),
    cardinality = value.getCardinality(),
    temporal = if (value.hasTemporal()) SemanticProto.fromProto(value.getTemporal()) else null,
    nullPolicy = value.getNullPolicy(),
    createdBy = value.getCreatedBy(),
    keyRefs = value.getKeyRefsList().map { JoinPathCreateRequestKeyRefInputFromProto(it) }
)

internal fun JoinPathCreateRequestKeyRefInputToProto(value: com.olap.prototype.governance.contract.JoinPathCreateRequest.KeyRefInput): com.olap.prototype.wire.governance.JoinPathCreateRequestKeyRefInput = com.olap.prototype.wire.governance.JoinPathCreateRequestKeyRefInput.newBuilder().apply {
    setSourceLogicUnitId(value.sourceLogicUnitId)
    setSourceLogicUnitVersion(value.sourceLogicUnitVersion)
    setTargetLogicUnitId(value.targetLogicUnitId)
    setTargetLogicUnitVersion(value.targetLogicUnitVersion)
}.build()
internal fun JoinPathCreateRequestKeyRefInputFromProto(value: com.olap.prototype.wire.governance.JoinPathCreateRequestKeyRefInput): com.olap.prototype.governance.contract.JoinPathCreateRequest.KeyRefInput = com.olap.prototype.governance.contract.JoinPathCreateRequest.KeyRefInput(
    sourceLogicUnitId = value.getSourceLogicUnitId(),
    sourceLogicUnitVersion = value.getSourceLogicUnitVersion(),
    targetLogicUnitId = value.getTargetLogicUnitId(),
    targetLogicUnitVersion = value.getTargetLogicUnitVersion()
)

internal fun JoinPathCreateResultToProto(value: com.olap.prototype.governance.contract.JoinPathCreateResult): com.olap.prototype.wire.governance.JoinPathCreateResult = com.olap.prototype.wire.governance.JoinPathCreateResult.newBuilder().apply {
    setJoinPath(JoinPathRecordToProto(value.joinPath))
    setJoinPathVersion(JoinPathVersionRecordToProto(value.joinPathVersion))
    setTopologyJoinPathRef(TopologyJoinPathRefRecordToProto(value.topologyJoinPathRef))
    addAllKeyRefs(value.keyRefs.map { JoinPathKeyRefRecordToProto(it) })
}.build()
internal fun JoinPathCreateResultFromProto(value: com.olap.prototype.wire.governance.JoinPathCreateResult): com.olap.prototype.governance.contract.JoinPathCreateResult = com.olap.prototype.governance.contract.JoinPathCreateResult(
    joinPath = required(value.hasJoinPath(), "JoinPathCreateResult.joinPath") { JoinPathRecordFromProto(value.getJoinPath()) },
    joinPathVersion = required(value.hasJoinPathVersion(), "JoinPathCreateResult.joinPathVersion") { JoinPathVersionRecordFromProto(value.getJoinPathVersion()) },
    topologyJoinPathRef = required(value.hasTopologyJoinPathRef(), "JoinPathCreateResult.topologyJoinPathRef") { TopologyJoinPathRefRecordFromProto(value.getTopologyJoinPathRef()) },
    keyRefs = value.getKeyRefsList().map { JoinPathKeyRefRecordFromProto(it) }
)

internal fun JoinPathRecordToProto(value: com.olap.prototype.governance.contract.JoinPathRecord): com.olap.prototype.wire.governance.JoinPathRecord = com.olap.prototype.wire.governance.JoinPathRecord.newBuilder().apply {
    setId(value.id)
    setTopologyId(value.topologyId)
    setTopologyVersion(value.topologyVersion)
    setCode(value.code)
    setSourceLogicalTableId(value.sourceLogicalTableId)
    setSourceLogicalTableVersion(value.sourceLogicalTableVersion)
    setTargetLogicalTableId(value.targetLogicalTableId)
    setTargetLogicalTableVersion(value.targetLogicalTableVersion)
    setJoinType(value.joinType)
    setCardinality(value.cardinality)
    value.temporal?.let { setTemporal(SemanticProto.toProto(it)) }
    value.nullPolicy?.let { setNullPolicy(it) }
    setStatus(value.status)
    setCurrentVersion(value.currentVersion)
    setCreatedBy(value.createdBy)
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun JoinPathRecordFromProto(value: com.olap.prototype.wire.governance.JoinPathRecord): com.olap.prototype.governance.contract.JoinPathRecord = com.olap.prototype.governance.contract.JoinPathRecord(
    id = value.getId(),
    topologyId = value.getTopologyId(),
    topologyVersion = value.getTopologyVersion(),
    code = value.getCode(),
    sourceLogicalTableId = value.getSourceLogicalTableId(),
    sourceLogicalTableVersion = value.getSourceLogicalTableVersion(),
    targetLogicalTableId = value.getTargetLogicalTableId(),
    targetLogicalTableVersion = value.getTargetLogicalTableVersion(),
    joinType = value.getJoinType(),
    cardinality = value.getCardinality(),
    temporal = if (value.hasTemporal()) SemanticProto.fromProto(value.getTemporal()) else null,
    nullPolicy = if (value.hasNullPolicy()) value.getNullPolicy() else null,
    status = value.getStatus(),
    currentVersion = value.getCurrentVersion(),
    createdBy = value.getCreatedBy(),
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)

internal fun JoinPathVersionRecordToProto(value: com.olap.prototype.governance.contract.JoinPathVersionRecord): com.olap.prototype.wire.governance.JoinPathVersionRecord = com.olap.prototype.wire.governance.JoinPathVersionRecord.newBuilder().apply {
    setId(value.id)
    setJoinPathId(value.joinPathId)
    setVersion(value.version)
    setVersionStatus(value.versionStatus)
    setContentProto(value.contentProto)
    setJoinType(value.joinType)
    setCardinality(value.cardinality)
    value.temporal?.let { setTemporal(SemanticProto.toProto(it)) }
    value.nullPolicy?.let { setNullPolicy(it) }
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
internal fun JoinPathVersionRecordFromProto(value: com.olap.prototype.wire.governance.JoinPathVersionRecord): com.olap.prototype.governance.contract.JoinPathVersionRecord = com.olap.prototype.governance.contract.JoinPathVersionRecord(
    id = value.getId(),
    joinPathId = value.getJoinPathId(),
    version = value.getVersion(),
    versionStatus = value.getVersionStatus(),
    contentProto = value.getContentProto(),
    joinType = value.getJoinType(),
    cardinality = value.getCardinality(),
    temporal = if (value.hasTemporal()) SemanticProto.fromProto(value.getTemporal()) else null,
    nullPolicy = if (value.hasNullPolicy()) value.getNullPolicy() else null,
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

internal fun TopologyJoinPathRefRecordToProto(value: com.olap.prototype.governance.contract.TopologyJoinPathRefRecord): com.olap.prototype.wire.governance.TopologyJoinPathRefRecord = com.olap.prototype.wire.governance.TopologyJoinPathRefRecord.newBuilder().apply {
    setId(value.id)
    setTopologyId(value.topologyId)
    setTopologyVersion(value.topologyVersion)
    setJoinPathId(value.joinPathId)
    setJoinPathVersion(value.joinPathVersion)
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun TopologyJoinPathRefRecordFromProto(value: com.olap.prototype.wire.governance.TopologyJoinPathRefRecord): com.olap.prototype.governance.contract.TopologyJoinPathRefRecord = com.olap.prototype.governance.contract.TopologyJoinPathRefRecord(
    id = value.getId(),
    topologyId = value.getTopologyId(),
    topologyVersion = value.getTopologyVersion(),
    joinPathId = value.getJoinPathId(),
    joinPathVersion = value.getJoinPathVersion(),
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)

internal fun JoinPathKeyRefRecordToProto(value: com.olap.prototype.governance.contract.JoinPathKeyRefRecord): com.olap.prototype.wire.governance.JoinPathKeyRefRecord = com.olap.prototype.wire.governance.JoinPathKeyRefRecord.newBuilder().apply {
    setId(value.id)
    setJoinPathId(value.joinPathId)
    setJoinPathVersion(value.joinPathVersion)
    setKeyOrder(value.keyOrder)
    setSourceLogicUnitId(value.sourceLogicUnitId)
    setSourceLogicUnitVersion(value.sourceLogicUnitVersion)
    setTargetLogicUnitId(value.targetLogicUnitId)
    setTargetLogicUnitVersion(value.targetLogicUnitVersion)
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun JoinPathKeyRefRecordFromProto(value: com.olap.prototype.wire.governance.JoinPathKeyRefRecord): com.olap.prototype.governance.contract.JoinPathKeyRefRecord = com.olap.prototype.governance.contract.JoinPathKeyRefRecord(
    id = value.getId(),
    joinPathId = value.getJoinPathId(),
    joinPathVersion = value.getJoinPathVersion(),
    keyOrder = value.getKeyOrder(),
    sourceLogicUnitId = value.getSourceLogicUnitId(),
    sourceLogicUnitVersion = value.getSourceLogicUnitVersion(),
    targetLogicUnitId = value.getTargetLogicUnitId(),
    targetLogicUnitVersion = value.getTargetLogicUnitVersion(),
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)
