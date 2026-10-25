package com.olap.prototype.codec

/** 正式领域消息的显式字段转换。 */
internal fun ScenarioCreateRequestToProto(value: com.olap.prototype.governance.contract.ScenarioCreateRequest): com.olap.prototype.wire.governance.ScenarioCreateRequest = com.olap.prototype.wire.governance.ScenarioCreateRequest.newBuilder().apply {
    setSubjectAreaId(value.subjectAreaId)
    setSubjectAreaVersion(value.subjectAreaVersion)
    setCode(value.code)
    setDisplayName(value.displayName)
    setOwner(value.owner)
    value.description?.let { setDescription(it) }
    setCreatedBy(value.createdBy)
}.build()
internal fun ScenarioCreateRequestFromProto(value: com.olap.prototype.wire.governance.ScenarioCreateRequest): com.olap.prototype.governance.contract.ScenarioCreateRequest = com.olap.prototype.governance.contract.ScenarioCreateRequest(
    subjectAreaId = value.getSubjectAreaId(),
    subjectAreaVersion = value.getSubjectAreaVersion(),
    code = value.getCode(),
    displayName = value.getDisplayName(),
    owner = value.getOwner(),
    description = if (value.hasDescription()) value.getDescription() else null,
    createdBy = value.getCreatedBy()
)

internal fun ScenarioCreateResultToProto(value: com.olap.prototype.governance.contract.ScenarioCreateResult): com.olap.prototype.wire.governance.ScenarioCreateResult = com.olap.prototype.wire.governance.ScenarioCreateResult.newBuilder().apply {
    setScenario(ScenarioRecordToProto(value.scenario))
    setScenarioVersion(ScenarioVersionRecordToProto(value.scenarioVersion))
    setSubjectAreaScenarioRef(SubjectAreaScenarioRefRecordToProto(value.subjectAreaScenarioRef))
}.build()
internal fun ScenarioCreateResultFromProto(value: com.olap.prototype.wire.governance.ScenarioCreateResult): com.olap.prototype.governance.contract.ScenarioCreateResult = com.olap.prototype.governance.contract.ScenarioCreateResult(
    scenario = required(value.hasScenario(), "ScenarioCreateResult.scenario") { ScenarioRecordFromProto(value.getScenario()) },
    scenarioVersion = required(value.hasScenarioVersion(), "ScenarioCreateResult.scenarioVersion") { ScenarioVersionRecordFromProto(value.getScenarioVersion()) },
    subjectAreaScenarioRef = required(value.hasSubjectAreaScenarioRef(), "ScenarioCreateResult.subjectAreaScenarioRef") { SubjectAreaScenarioRefRecordFromProto(value.getSubjectAreaScenarioRef()) }
)

internal fun ScenarioRecordToProto(value: com.olap.prototype.governance.contract.ScenarioRecord): com.olap.prototype.wire.governance.ScenarioRecord = com.olap.prototype.wire.governance.ScenarioRecord.newBuilder().apply {
    setId(value.id)
    setSubjectAreaId(value.subjectAreaId)
    setSubjectAreaVersion(value.subjectAreaVersion)
    setCode(value.code)
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
internal fun ScenarioRecordFromProto(value: com.olap.prototype.wire.governance.ScenarioRecord): com.olap.prototype.governance.contract.ScenarioRecord = com.olap.prototype.governance.contract.ScenarioRecord(
    id = value.getId(),
    subjectAreaId = value.getSubjectAreaId(),
    subjectAreaVersion = value.getSubjectAreaVersion(),
    code = value.getCode(),
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

internal fun ScenarioVersionRecordToProto(value: com.olap.prototype.governance.contract.ScenarioVersionRecord): com.olap.prototype.wire.governance.ScenarioVersionRecord = com.olap.prototype.wire.governance.ScenarioVersionRecord.newBuilder().apply {
    setId(value.id)
    setScenarioId(value.scenarioId)
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
internal fun ScenarioVersionRecordFromProto(value: com.olap.prototype.wire.governance.ScenarioVersionRecord): com.olap.prototype.governance.contract.ScenarioVersionRecord = com.olap.prototype.governance.contract.ScenarioVersionRecord(
    id = value.getId(),
    scenarioId = value.getScenarioId(),
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

internal fun ScenarioMetricRefCreateRequestToProto(value: com.olap.prototype.governance.contract.ScenarioMetricRefCreateRequest): com.olap.prototype.wire.governance.ScenarioMetricRefCreateRequest = com.olap.prototype.wire.governance.ScenarioMetricRefCreateRequest.newBuilder().apply {
    setScenarioId(value.scenarioId)
    setScenarioVersion(value.scenarioVersion)
    setAdoptionId(value.adoptionId)
    setAdoptionVersion(value.adoptionVersion)
    value.requiredByDefault?.let { setRequiredByDefault(it) }
    value.sortOrder?.let { setSortOrder(it) }
}.build()
internal fun ScenarioMetricRefCreateRequestFromProto(value: com.olap.prototype.wire.governance.ScenarioMetricRefCreateRequest): com.olap.prototype.governance.contract.ScenarioMetricRefCreateRequest = com.olap.prototype.governance.contract.ScenarioMetricRefCreateRequest(
    scenarioId = value.getScenarioId(),
    scenarioVersion = value.getScenarioVersion(),
    adoptionId = value.getAdoptionId(),
    adoptionVersion = value.getAdoptionVersion(),
    requiredByDefault = if (value.hasRequiredByDefault()) value.getRequiredByDefault() else null,
    sortOrder = if (value.hasSortOrder()) value.getSortOrder() else null
)

internal fun ScenarioMetricRefCreateResultToProto(value: com.olap.prototype.governance.contract.ScenarioMetricRefCreateResult): com.olap.prototype.wire.governance.ScenarioMetricRefCreateResult = com.olap.prototype.wire.governance.ScenarioMetricRefCreateResult.newBuilder().apply {
    setRef(ScenarioMetricRefRecordToProto(value.ref))
}.build()
internal fun ScenarioMetricRefCreateResultFromProto(value: com.olap.prototype.wire.governance.ScenarioMetricRefCreateResult): com.olap.prototype.governance.contract.ScenarioMetricRefCreateResult = com.olap.prototype.governance.contract.ScenarioMetricRefCreateResult(
    ref = required(value.hasRef(), "ScenarioMetricRefCreateResult.ref") { ScenarioMetricRefRecordFromProto(value.getRef()) }
)

internal fun ScenarioMetricRefRecordToProto(value: com.olap.prototype.governance.contract.ScenarioMetricRefRecord): com.olap.prototype.wire.governance.ScenarioMetricRefRecord = com.olap.prototype.wire.governance.ScenarioMetricRefRecord.newBuilder().apply {
    setId(value.id)
    setScenarioId(value.scenarioId)
    setScenarioVersion(value.scenarioVersion)
    setAdoptionId(value.adoptionId)
    setAdoptionVersion(value.adoptionVersion)
    setRequiredByDefault(value.requiredByDefault)
    setSortOrder(value.sortOrder)
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun ScenarioMetricRefRecordFromProto(value: com.olap.prototype.wire.governance.ScenarioMetricRefRecord): com.olap.prototype.governance.contract.ScenarioMetricRefRecord = com.olap.prototype.governance.contract.ScenarioMetricRefRecord(
    id = value.getId(),
    scenarioId = value.getScenarioId(),
    scenarioVersion = value.getScenarioVersion(),
    adoptionId = value.getAdoptionId(),
    adoptionVersion = value.getAdoptionVersion(),
    requiredByDefault = value.getRequiredByDefault(),
    sortOrder = value.getSortOrder(),
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)

internal fun ScenarioMemberSourceRefCreateRequestToProto(value: com.olap.prototype.governance.contract.ScenarioMemberSourceRefCreateRequest): com.olap.prototype.wire.governance.ScenarioMemberSourceRefCreateRequest = com.olap.prototype.wire.governance.ScenarioMemberSourceRefCreateRequest.newBuilder().apply {
    setScenarioId(value.scenarioId)
    setScenarioVersion(value.scenarioVersion)
    setSourceId(value.sourceId)
    setSourceVersion(value.sourceVersion)
    setPermission(MemberQueryPermissionToProto(value.permission))
}.build()
internal fun ScenarioMemberSourceRefCreateRequestFromProto(value: com.olap.prototype.wire.governance.ScenarioMemberSourceRefCreateRequest): com.olap.prototype.governance.contract.ScenarioMemberSourceRefCreateRequest = com.olap.prototype.governance.contract.ScenarioMemberSourceRefCreateRequest(
    scenarioId = value.getScenarioId(),
    scenarioVersion = value.getScenarioVersion(),
    sourceId = value.getSourceId(),
    sourceVersion = value.getSourceVersion(),
    permission = required(value.hasPermission(), "ScenarioMemberSourceRefCreateRequest.permission") { MemberQueryPermissionFromProto(value.getPermission()) }
)

internal fun ScenarioMemberSourceRefRecordToProto(value: com.olap.prototype.governance.contract.ScenarioMemberSourceRefRecord): com.olap.prototype.wire.governance.ScenarioMemberSourceRefRecord = com.olap.prototype.wire.governance.ScenarioMemberSourceRefRecord.newBuilder().apply {
    setScenarioId(value.scenarioId)
    setScenarioVersion(value.scenarioVersion)
    setSourceId(value.sourceId)
    setSourceVersion(value.sourceVersion)
    setPermission(MemberQueryPermissionToProto(value.permission))
}.build()
internal fun ScenarioMemberSourceRefRecordFromProto(value: com.olap.prototype.wire.governance.ScenarioMemberSourceRefRecord): com.olap.prototype.governance.contract.ScenarioMemberSourceRefRecord = com.olap.prototype.governance.contract.ScenarioMemberSourceRefRecord(
    scenarioId = value.getScenarioId(),
    scenarioVersion = value.getScenarioVersion(),
    sourceId = value.getSourceId(),
    sourceVersion = value.getSourceVersion(),
    permission = required(value.hasPermission(), "ScenarioMemberSourceRefRecord.permission") { MemberQueryPermissionFromProto(value.getPermission()) }
)

internal fun ScenarioRelationFilterCreateRequestToProto(value: com.olap.prototype.governance.contract.ScenarioRelationFilterCreateRequest): com.olap.prototype.wire.governance.ScenarioRelationFilterCreateRequest = com.olap.prototype.wire.governance.ScenarioRelationFilterCreateRequest.newBuilder().apply {
    setScenarioId(value.scenarioId)
    setScenarioVersion(value.scenarioVersion)
    setCode(value.code)
    setDisplayName(value.displayName)
    setEventId(value.eventId)
    setEventVersion(value.eventVersion)
    setRelationshipRole(value.relationshipRole)
    setMemberSourceId(value.memberSourceId)
    setMemberSourceVersion(value.memberSourceVersion)
    setPermission(RelationFilterPermissionToProto(value.permission))
    value.requiredByDefault?.let { setRequiredByDefault(it) }
    value.sortOrder?.let { setSortOrder(it) }
}.build()
internal fun ScenarioRelationFilterCreateRequestFromProto(value: com.olap.prototype.wire.governance.ScenarioRelationFilterCreateRequest): com.olap.prototype.governance.contract.ScenarioRelationFilterCreateRequest = com.olap.prototype.governance.contract.ScenarioRelationFilterCreateRequest(
    scenarioId = value.getScenarioId(),
    scenarioVersion = value.getScenarioVersion(),
    code = value.getCode(),
    displayName = value.getDisplayName(),
    eventId = value.getEventId(),
    eventVersion = value.getEventVersion(),
    relationshipRole = value.getRelationshipRole(),
    memberSourceId = value.getMemberSourceId(),
    memberSourceVersion = value.getMemberSourceVersion(),
    permission = required(value.hasPermission(), "ScenarioRelationFilterCreateRequest.permission") { RelationFilterPermissionFromProto(value.getPermission()) },
    requiredByDefault = if (value.hasRequiredByDefault()) value.getRequiredByDefault() else null,
    sortOrder = if (value.hasSortOrder()) value.getSortOrder() else null
)

internal fun ScenarioRelationFilterRecordToProto(value: com.olap.prototype.governance.contract.ScenarioRelationFilterRecord): com.olap.prototype.wire.governance.ScenarioRelationFilterRecord = com.olap.prototype.wire.governance.ScenarioRelationFilterRecord.newBuilder().apply {
    setScenarioId(value.scenarioId)
    setScenarioVersion(value.scenarioVersion)
    setCode(value.code)
    setDefinition(PublishedRelationFilterToProto(value.definition))
}.build()
internal fun ScenarioRelationFilterRecordFromProto(value: com.olap.prototype.wire.governance.ScenarioRelationFilterRecord): com.olap.prototype.governance.contract.ScenarioRelationFilterRecord = com.olap.prototype.governance.contract.ScenarioRelationFilterRecord(
    scenarioId = value.getScenarioId(),
    scenarioVersion = value.getScenarioVersion(),
    code = value.getCode(),
    definition = required(value.hasDefinition(), "ScenarioRelationFilterRecord.definition") { PublishedRelationFilterFromProto(value.getDefinition()) }
)

internal fun ScenarioAnalysisDimensionRefCreateRequestToProto(value: com.olap.prototype.governance.contract.ScenarioAnalysisDimensionRefCreateRequest): com.olap.prototype.wire.governance.ScenarioAnalysisDimensionRefCreateRequest = com.olap.prototype.wire.governance.ScenarioAnalysisDimensionRefCreateRequest.newBuilder().apply {
    setScenarioId(value.scenarioId)
    setScenarioVersion(value.scenarioVersion)
    setAnalysisDimensionId(value.analysisDimensionId)
    setAnalysisDimensionVersion(value.analysisDimensionVersion)
    value.defaultLevelCode?.let { setDefaultLevelCode(it) }
    value.requiredByDefault?.let { setRequiredByDefault(it) }
    value.sortOrder?.let { setSortOrder(it) }
}.build()
internal fun ScenarioAnalysisDimensionRefCreateRequestFromProto(value: com.olap.prototype.wire.governance.ScenarioAnalysisDimensionRefCreateRequest): com.olap.prototype.governance.contract.ScenarioAnalysisDimensionRefCreateRequest = com.olap.prototype.governance.contract.ScenarioAnalysisDimensionRefCreateRequest(
    scenarioId = value.getScenarioId(),
    scenarioVersion = value.getScenarioVersion(),
    analysisDimensionId = value.getAnalysisDimensionId(),
    analysisDimensionVersion = value.getAnalysisDimensionVersion(),
    defaultLevelCode = if (value.hasDefaultLevelCode()) value.getDefaultLevelCode() else null,
    requiredByDefault = if (value.hasRequiredByDefault()) value.getRequiredByDefault() else null,
    sortOrder = if (value.hasSortOrder()) value.getSortOrder() else null
)

internal fun ScenarioAnalysisDimensionRefCreateResultToProto(value: com.olap.prototype.governance.contract.ScenarioAnalysisDimensionRefCreateResult): com.olap.prototype.wire.governance.ScenarioAnalysisDimensionRefCreateResult = com.olap.prototype.wire.governance.ScenarioAnalysisDimensionRefCreateResult.newBuilder().apply {
    setRef(ScenarioAnalysisDimensionRefRecordToProto(value.ref))
}.build()
internal fun ScenarioAnalysisDimensionRefCreateResultFromProto(value: com.olap.prototype.wire.governance.ScenarioAnalysisDimensionRefCreateResult): com.olap.prototype.governance.contract.ScenarioAnalysisDimensionRefCreateResult = com.olap.prototype.governance.contract.ScenarioAnalysisDimensionRefCreateResult(
    ref = required(value.hasRef(), "ScenarioAnalysisDimensionRefCreateResult.ref") { ScenarioAnalysisDimensionRefRecordFromProto(value.getRef()) }
)

internal fun ScenarioAnalysisDimensionRefRecordToProto(value: com.olap.prototype.governance.contract.ScenarioAnalysisDimensionRefRecord): com.olap.prototype.wire.governance.ScenarioAnalysisDimensionRefRecord = com.olap.prototype.wire.governance.ScenarioAnalysisDimensionRefRecord.newBuilder().apply {
    setId(value.id)
    setScenarioId(value.scenarioId)
    setScenarioVersion(value.scenarioVersion)
    setAnalysisDimensionId(value.analysisDimensionId)
    setAnalysisDimensionVersion(value.analysisDimensionVersion)
    value.defaultLevelCode?.let { setDefaultLevelCode(it) }
    setRequiredByDefault(value.requiredByDefault)
    setSortOrder(value.sortOrder)
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun ScenarioAnalysisDimensionRefRecordFromProto(value: com.olap.prototype.wire.governance.ScenarioAnalysisDimensionRefRecord): com.olap.prototype.governance.contract.ScenarioAnalysisDimensionRefRecord = com.olap.prototype.governance.contract.ScenarioAnalysisDimensionRefRecord(
    id = value.getId(),
    scenarioId = value.getScenarioId(),
    scenarioVersion = value.getScenarioVersion(),
    analysisDimensionId = value.getAnalysisDimensionId(),
    analysisDimensionVersion = value.getAnalysisDimensionVersion(),
    defaultLevelCode = if (value.hasDefaultLevelCode()) value.getDefaultLevelCode() else null,
    requiredByDefault = value.getRequiredByDefault(),
    sortOrder = value.getSortOrder(),
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)

internal fun ScenarioFilterRefCreateRequestToProto(value: com.olap.prototype.governance.contract.ScenarioFilterRefCreateRequest): com.olap.prototype.wire.governance.ScenarioFilterRefCreateRequest = com.olap.prototype.wire.governance.ScenarioFilterRefCreateRequest.newBuilder().apply {
    setScenarioId(value.scenarioId)
    setScenarioVersion(value.scenarioVersion)
    setFilterTargetType(value.filterTargetType)
    setTargetId(value.targetId)
    setTargetVersion(value.targetVersion)
    value.valueProviderId?.let { setValueProviderId(it) }
    addAllOperators(value.operators.map { it.name })
    value.requiredByDefault?.let { setRequiredByDefault(it) }
    value.sortOrder?.let { setSortOrder(it) }
}.build()
internal fun ScenarioFilterRefCreateRequestFromProto(value: com.olap.prototype.wire.governance.ScenarioFilterRefCreateRequest): com.olap.prototype.governance.contract.ScenarioFilterRefCreateRequest = com.olap.prototype.governance.contract.ScenarioFilterRefCreateRequest(
    scenarioId = value.getScenarioId(),
    scenarioVersion = value.getScenarioVersion(),
    filterTargetType = value.getFilterTargetType(),
    targetId = value.getTargetId(),
    targetVersion = value.getTargetVersion(),
    valueProviderId = if (value.hasValueProviderId()) value.getValueProviderId() else null,
    operators = value.getOperatorsList().map { com.olap.prototype.query.contract.FilterLeafOperator.valueOf(it) },
    requiredByDefault = if (value.hasRequiredByDefault()) value.getRequiredByDefault() else null,
    sortOrder = if (value.hasSortOrder()) value.getSortOrder() else null
)

internal fun ScenarioFilterRefCreateResultToProto(value: com.olap.prototype.governance.contract.ScenarioFilterRefCreateResult): com.olap.prototype.wire.governance.ScenarioFilterRefCreateResult = com.olap.prototype.wire.governance.ScenarioFilterRefCreateResult.newBuilder().apply {
    setRef(ScenarioFilterRefRecordToProto(value.ref))
}.build()
internal fun ScenarioFilterRefCreateResultFromProto(value: com.olap.prototype.wire.governance.ScenarioFilterRefCreateResult): com.olap.prototype.governance.contract.ScenarioFilterRefCreateResult = com.olap.prototype.governance.contract.ScenarioFilterRefCreateResult(
    ref = required(value.hasRef(), "ScenarioFilterRefCreateResult.ref") { ScenarioFilterRefRecordFromProto(value.getRef()) }
)

internal fun ScenarioFilterRefRecordToProto(value: com.olap.prototype.governance.contract.ScenarioFilterRefRecord): com.olap.prototype.wire.governance.ScenarioFilterRefRecord = com.olap.prototype.wire.governance.ScenarioFilterRefRecord.newBuilder().apply {
    setId(value.id)
    setScenarioId(value.scenarioId)
    setScenarioVersion(value.scenarioVersion)
    setFilterTargetType(value.filterTargetType)
    setTargetId(value.targetId)
    setTargetVersion(value.targetVersion)
    addAllOperators(value.operators.map { it })
    value.valueProviderId?.let { setValueProviderId(it) }
    setRequiredByDefault(value.requiredByDefault)
    setSortOrder(value.sortOrder)
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun ScenarioFilterRefRecordFromProto(value: com.olap.prototype.wire.governance.ScenarioFilterRefRecord): com.olap.prototype.governance.contract.ScenarioFilterRefRecord = com.olap.prototype.governance.contract.ScenarioFilterRefRecord(
    id = value.getId(),
    scenarioId = value.getScenarioId(),
    scenarioVersion = value.getScenarioVersion(),
    filterTargetType = value.getFilterTargetType(),
    targetId = value.getTargetId(),
    targetVersion = value.getTargetVersion(),
    operators = value.getOperatorsList().map { it },
    valueProviderId = if (value.hasValueProviderId()) value.getValueProviderId() else null,
    requiredByDefault = value.getRequiredByDefault(),
    sortOrder = value.getSortOrder(),
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)

internal fun ScenarioPipelineRuleCreateRequestToProto(value: com.olap.prototype.governance.contract.ScenarioPipelineRuleCreateRequest): com.olap.prototype.wire.governance.ScenarioPipelineRuleCreateRequest = com.olap.prototype.wire.governance.ScenarioPipelineRuleCreateRequest.newBuilder().apply {
    setScenarioId(value.scenarioId)
    setScenarioVersion(value.scenarioVersion)
    setRuleType(value.ruleType)
    value.sortOrder?.let { setSortOrder(it) }
    setAuthorization(StageAuthorizationToProto(value.authorization))
}.build()
internal fun ScenarioPipelineRuleCreateRequestFromProto(value: com.olap.prototype.wire.governance.ScenarioPipelineRuleCreateRequest): com.olap.prototype.governance.contract.ScenarioPipelineRuleCreateRequest = com.olap.prototype.governance.contract.ScenarioPipelineRuleCreateRequest(
    scenarioId = value.getScenarioId(),
    scenarioVersion = value.getScenarioVersion(),
    ruleType = value.getRuleType(),
    sortOrder = if (value.hasSortOrder()) value.getSortOrder() else null,
    authorization = required(value.hasAuthorization(), "ScenarioPipelineRuleCreateRequest.authorization") { StageAuthorizationFromProto(value.getAuthorization()) }
)

internal fun ScenarioPipelineRuleCreateResultToProto(value: com.olap.prototype.governance.contract.ScenarioPipelineRuleCreateResult): com.olap.prototype.wire.governance.ScenarioPipelineRuleCreateResult = com.olap.prototype.wire.governance.ScenarioPipelineRuleCreateResult.newBuilder().apply {
    setRule(ScenarioPipelineRuleRecordToProto(value.rule))
}.build()
internal fun ScenarioPipelineRuleCreateResultFromProto(value: com.olap.prototype.wire.governance.ScenarioPipelineRuleCreateResult): com.olap.prototype.governance.contract.ScenarioPipelineRuleCreateResult = com.olap.prototype.governance.contract.ScenarioPipelineRuleCreateResult(
    rule = required(value.hasRule(), "ScenarioPipelineRuleCreateResult.rule") { ScenarioPipelineRuleRecordFromProto(value.getRule()) }
)

internal fun ScenarioPipelineRuleRecordToProto(value: com.olap.prototype.governance.contract.ScenarioPipelineRuleRecord): com.olap.prototype.wire.governance.ScenarioPipelineRuleRecord = com.olap.prototype.wire.governance.ScenarioPipelineRuleRecord.newBuilder().apply {
    setId(value.id)
    setScenarioId(value.scenarioId)
    setScenarioVersion(value.scenarioVersion)
    setRuleType(value.ruleType)
    setDisplayName(value.displayName)
    setAuthorization(StageAuthorizationToProto(value.authorization))
    setStatus(value.status)
    setSortOrder(value.sortOrder)
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun ScenarioPipelineRuleRecordFromProto(value: com.olap.prototype.wire.governance.ScenarioPipelineRuleRecord): com.olap.prototype.governance.contract.ScenarioPipelineRuleRecord = com.olap.prototype.governance.contract.ScenarioPipelineRuleRecord(
    id = value.getId(),
    scenarioId = value.getScenarioId(),
    scenarioVersion = value.getScenarioVersion(),
    ruleType = value.getRuleType(),
    displayName = value.getDisplayName(),
    authorization = required(value.hasAuthorization(), "ScenarioPipelineRuleRecord.authorization") { StageAuthorizationFromProto(value.getAuthorization()) },
    status = value.getStatus(),
    sortOrder = value.getSortOrder(),
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)

internal fun ScenarioPipelineRuleContractRefreshRequestToProto(value: com.olap.prototype.governance.contract.ScenarioPipelineRuleContractRefreshRequest): com.olap.prototype.wire.governance.ScenarioPipelineRuleContractRefreshRequest = com.olap.prototype.wire.governance.ScenarioPipelineRuleContractRefreshRequest.newBuilder().apply {
    setScenarioId(value.scenarioId)
    setScenarioVersion(value.scenarioVersion)
    setRuleType(value.ruleType)
    setApprovedAuthorization(StageAuthorizationToProto(value.approvedAuthorization))
}.build()
internal fun ScenarioPipelineRuleContractRefreshRequestFromProto(value: com.olap.prototype.wire.governance.ScenarioPipelineRuleContractRefreshRequest): com.olap.prototype.governance.contract.ScenarioPipelineRuleContractRefreshRequest = com.olap.prototype.governance.contract.ScenarioPipelineRuleContractRefreshRequest(
    scenarioId = value.getScenarioId(),
    scenarioVersion = value.getScenarioVersion(),
    ruleType = value.getRuleType(),
    approvedAuthorization = required(value.hasApprovedAuthorization(), "ScenarioPipelineRuleContractRefreshRequest.approvedAuthorization") { StageAuthorizationFromProto(value.getApprovedAuthorization()) }
)
