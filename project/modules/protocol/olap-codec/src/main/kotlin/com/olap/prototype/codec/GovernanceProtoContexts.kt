package com.olap.prototype.codec

/** 正式领域消息的显式字段转换。 */
internal fun BusinessLineCreateRequestToProto(value: com.olap.prototype.governance.contract.BusinessLineCreateRequest): com.olap.prototype.wire.governance.BusinessLineCreateRequest = com.olap.prototype.wire.governance.BusinessLineCreateRequest.newBuilder().apply {
    setCode(value.code)
    setDisplayName(value.displayName)
    setOwner(value.owner)
    value.description?.let { setDescription(it) }
    setCreatedBy(value.createdBy)
}.build()
internal fun BusinessLineCreateRequestFromProto(value: com.olap.prototype.wire.governance.BusinessLineCreateRequest): com.olap.prototype.governance.contract.BusinessLineCreateRequest = com.olap.prototype.governance.contract.BusinessLineCreateRequest(
    code = value.getCode(),
    displayName = value.getDisplayName(),
    owner = value.getOwner(),
    description = if (value.hasDescription()) value.getDescription() else null,
    createdBy = value.getCreatedBy()
)

internal fun BusinessLineCreateResultToProto(value: com.olap.prototype.governance.contract.BusinessLineCreateResult): com.olap.prototype.wire.governance.BusinessLineCreateResult = com.olap.prototype.wire.governance.BusinessLineCreateResult.newBuilder().apply {
    setBusinessLine(BusinessLineRecordToProto(value.businessLine))
    setBusinessLineVersion(BusinessLineVersionRecordToProto(value.businessLineVersion))
}.build()
internal fun BusinessLineCreateResultFromProto(value: com.olap.prototype.wire.governance.BusinessLineCreateResult): com.olap.prototype.governance.contract.BusinessLineCreateResult = com.olap.prototype.governance.contract.BusinessLineCreateResult(
    businessLine = required(value.hasBusinessLine(), "BusinessLineCreateResult.businessLine") { BusinessLineRecordFromProto(value.getBusinessLine()) },
    businessLineVersion = required(value.hasBusinessLineVersion(), "BusinessLineCreateResult.businessLineVersion") { BusinessLineVersionRecordFromProto(value.getBusinessLineVersion()) }
)

internal fun BusinessLineRecordToProto(value: com.olap.prototype.governance.contract.BusinessLineRecord): com.olap.prototype.wire.governance.BusinessLineRecord = com.olap.prototype.wire.governance.BusinessLineRecord.newBuilder().apply {
    setId(value.id)
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
internal fun BusinessLineRecordFromProto(value: com.olap.prototype.wire.governance.BusinessLineRecord): com.olap.prototype.governance.contract.BusinessLineRecord = com.olap.prototype.governance.contract.BusinessLineRecord(
    id = value.getId(),
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

internal fun BusinessLineVersionRecordToProto(value: com.olap.prototype.governance.contract.BusinessLineVersionRecord): com.olap.prototype.wire.governance.BusinessLineVersionRecord = com.olap.prototype.wire.governance.BusinessLineVersionRecord.newBuilder().apply {
    setId(value.id)
    setBusinessLineId(value.businessLineId)
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
internal fun BusinessLineVersionRecordFromProto(value: com.olap.prototype.wire.governance.BusinessLineVersionRecord): com.olap.prototype.governance.contract.BusinessLineVersionRecord = com.olap.prototype.governance.contract.BusinessLineVersionRecord(
    id = value.getId(),
    businessLineId = value.getBusinessLineId(),
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

internal fun SubjectAreaCreateRequestToProto(value: com.olap.prototype.governance.contract.SubjectAreaCreateRequest): com.olap.prototype.wire.governance.SubjectAreaCreateRequest = com.olap.prototype.wire.governance.SubjectAreaCreateRequest.newBuilder().apply {
    setBusinessLineId(value.businessLineId)
    setBusinessLineVersion(value.businessLineVersion)
    setCode(value.code)
    setDisplayName(value.displayName)
    setOwner(value.owner)
    value.description?.let { setDescription(it) }
    setCreatedBy(value.createdBy)
}.build()
internal fun SubjectAreaCreateRequestFromProto(value: com.olap.prototype.wire.governance.SubjectAreaCreateRequest): com.olap.prototype.governance.contract.SubjectAreaCreateRequest = com.olap.prototype.governance.contract.SubjectAreaCreateRequest(
    businessLineId = value.getBusinessLineId(),
    businessLineVersion = value.getBusinessLineVersion(),
    code = value.getCode(),
    displayName = value.getDisplayName(),
    owner = value.getOwner(),
    description = if (value.hasDescription()) value.getDescription() else null,
    createdBy = value.getCreatedBy()
)

internal fun SubjectAreaCreateResultToProto(value: com.olap.prototype.governance.contract.SubjectAreaCreateResult): com.olap.prototype.wire.governance.SubjectAreaCreateResult = com.olap.prototype.wire.governance.SubjectAreaCreateResult.newBuilder().apply {
    setSubjectArea(SubjectAreaRecordToProto(value.subjectArea))
    setSubjectAreaVersion(SubjectAreaVersionRecordToProto(value.subjectAreaVersion))
}.build()
internal fun SubjectAreaCreateResultFromProto(value: com.olap.prototype.wire.governance.SubjectAreaCreateResult): com.olap.prototype.governance.contract.SubjectAreaCreateResult = com.olap.prototype.governance.contract.SubjectAreaCreateResult(
    subjectArea = required(value.hasSubjectArea(), "SubjectAreaCreateResult.subjectArea") { SubjectAreaRecordFromProto(value.getSubjectArea()) },
    subjectAreaVersion = required(value.hasSubjectAreaVersion(), "SubjectAreaCreateResult.subjectAreaVersion") { SubjectAreaVersionRecordFromProto(value.getSubjectAreaVersion()) }
)

internal fun SubjectAreaRecordToProto(value: com.olap.prototype.governance.contract.SubjectAreaRecord): com.olap.prototype.wire.governance.SubjectAreaRecord = com.olap.prototype.wire.governance.SubjectAreaRecord.newBuilder().apply {
    setId(value.id)
    setCode(value.code)
    setDisplayName(value.displayName)
    setBusinessLineId(value.businessLineId)
    setBusinessLineVersion(value.businessLineVersion)
    value.description?.let { setDescription(it) }
    setOwner(value.owner)
    setStatus(value.status)
    setCurrentVersion(value.currentVersion)
    setCreatedBy(value.createdBy)
    setCreatedAt(value.createdAt.toString())
    value.updatedBy?.let { setUpdatedBy(it) }
    value.updatedAt?.let { setUpdatedAt(it.toString()) }
}.build()
internal fun SubjectAreaRecordFromProto(value: com.olap.prototype.wire.governance.SubjectAreaRecord): com.olap.prototype.governance.contract.SubjectAreaRecord = com.olap.prototype.governance.contract.SubjectAreaRecord(
    id = value.getId(),
    code = value.getCode(),
    displayName = value.getDisplayName(),
    businessLineId = value.getBusinessLineId(),
    businessLineVersion = value.getBusinessLineVersion(),
    description = if (value.hasDescription()) value.getDescription() else null,
    owner = value.getOwner(),
    status = value.getStatus(),
    currentVersion = value.getCurrentVersion(),
    createdBy = value.getCreatedBy(),
    createdAt = java.time.Instant.parse(value.getCreatedAt()),
    updatedBy = if (value.hasUpdatedBy()) value.getUpdatedBy() else null,
    updatedAt = if (value.hasUpdatedAt()) java.time.Instant.parse(value.getUpdatedAt()) else null
)

internal fun SubjectAreaVersionRecordToProto(value: com.olap.prototype.governance.contract.SubjectAreaVersionRecord): com.olap.prototype.wire.governance.SubjectAreaVersionRecord = com.olap.prototype.wire.governance.SubjectAreaVersionRecord.newBuilder().apply {
    setId(value.id)
    setSubjectAreaId(value.subjectAreaId)
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
internal fun SubjectAreaVersionRecordFromProto(value: com.olap.prototype.wire.governance.SubjectAreaVersionRecord): com.olap.prototype.governance.contract.SubjectAreaVersionRecord = com.olap.prototype.governance.contract.SubjectAreaVersionRecord(
    id = value.getId(),
    subjectAreaId = value.getSubjectAreaId(),
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

internal fun SubjectAreaMetricRefCreateRequestToProto(value: com.olap.prototype.governance.contract.SubjectAreaMetricRefCreateRequest): com.olap.prototype.wire.governance.SubjectAreaMetricRefCreateRequest = com.olap.prototype.wire.governance.SubjectAreaMetricRefCreateRequest.newBuilder().apply {
    setSubjectAreaId(value.subjectAreaId)
    setSubjectAreaVersion(value.subjectAreaVersion)
    setCode(value.code)
    setDisplayName(value.displayName)
    setBusinessMeaning(SemanticProto.toProto(value.businessMeaning))
    setMetricId(value.metricId)
    setMetricVersion(value.metricVersion)
    setBindings(SemanticProto.toProto(value.bindings))
    addAllFixedArguments(value.fixedArguments.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.SubjectAreaMetricRefCreateRequestFixedArgumentsEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    setSelectedBy(value.selectedBy)
}.build()
internal fun SubjectAreaMetricRefCreateRequestFromProto(value: com.olap.prototype.wire.governance.SubjectAreaMetricRefCreateRequest): com.olap.prototype.governance.contract.SubjectAreaMetricRefCreateRequest = com.olap.prototype.governance.contract.SubjectAreaMetricRefCreateRequest(
    subjectAreaId = value.getSubjectAreaId(),
    subjectAreaVersion = value.getSubjectAreaVersion(),
    code = value.getCode(),
    displayName = value.getDisplayName(),
    businessMeaning = required(value.hasBusinessMeaning(), "SubjectAreaMetricRefCreateRequest.businessMeaning") { SemanticProto.fromProto(value.getBusinessMeaning()) as com.olap.prototype.metric.MetricBusinessMeaning },
    metricId = value.getMetricId(),
    metricVersion = value.getMetricVersion(),
    bindings = required(value.hasBindings(), "SubjectAreaMetricRefCreateRequest.bindings") { SemanticProto.fromProto(value.getBindings()) as com.olap.prototype.calculation.CalculationBindings },
    fixedArguments = strictMap(value.getFixedArgumentsList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.Value }),
    selectedBy = value.getSelectedBy()
)

internal fun SubjectAreaMetricRefCreateResultToProto(value: com.olap.prototype.governance.contract.SubjectAreaMetricRefCreateResult): com.olap.prototype.wire.governance.SubjectAreaMetricRefCreateResult = com.olap.prototype.wire.governance.SubjectAreaMetricRefCreateResult.newBuilder().apply {
    setSubjectAreaMetricRef(SubjectAreaMetricRefRecordToProto(value.subjectAreaMetricRef))
}.build()
internal fun SubjectAreaMetricRefCreateResultFromProto(value: com.olap.prototype.wire.governance.SubjectAreaMetricRefCreateResult): com.olap.prototype.governance.contract.SubjectAreaMetricRefCreateResult = com.olap.prototype.governance.contract.SubjectAreaMetricRefCreateResult(
    subjectAreaMetricRef = required(value.hasSubjectAreaMetricRef(), "SubjectAreaMetricRefCreateResult.subjectAreaMetricRef") { SubjectAreaMetricRefRecordFromProto(value.getSubjectAreaMetricRef()) }
)

internal fun SubjectAreaMetricRefRecordToProto(value: com.olap.prototype.governance.contract.SubjectAreaMetricRefRecord): com.olap.prototype.wire.governance.SubjectAreaMetricRefRecord = com.olap.prototype.wire.governance.SubjectAreaMetricRefRecord.newBuilder().apply {
    setId(value.id)
    setSubjectAreaId(value.subjectAreaId)
    setSubjectAreaVersion(value.subjectAreaVersion)
    setCode(value.code)
    setDisplayName(value.displayName)
    setBusinessMeaning(SemanticProto.toProto(value.businessMeaning))
    setMetricId(value.metricId)
    setMetricVersion(value.metricVersion)
    setStatus(value.status)
    setSelectedBy(value.selectedBy)
    setSelectedAt(value.selectedAt.toString())
}.build()
internal fun SubjectAreaMetricRefRecordFromProto(value: com.olap.prototype.wire.governance.SubjectAreaMetricRefRecord): com.olap.prototype.governance.contract.SubjectAreaMetricRefRecord = com.olap.prototype.governance.contract.SubjectAreaMetricRefRecord(
    id = value.getId(),
    subjectAreaId = value.getSubjectAreaId(),
    subjectAreaVersion = value.getSubjectAreaVersion(),
    code = value.getCode(),
    displayName = value.getDisplayName(),
    businessMeaning = required(value.hasBusinessMeaning(), "SubjectAreaMetricRefRecord.businessMeaning") { SemanticProto.fromProto(value.getBusinessMeaning()) as com.olap.prototype.metric.MetricBusinessMeaning },
    metricId = value.getMetricId(),
    metricVersion = value.getMetricVersion(),
    status = value.getStatus(),
    selectedBy = value.getSelectedBy(),
    selectedAt = java.time.Instant.parse(value.getSelectedAt())
)

internal fun SubjectAreaAnalysisDimensionRefRecordToProto(value: com.olap.prototype.governance.contract.SubjectAreaAnalysisDimensionRefRecord): com.olap.prototype.wire.governance.SubjectAreaAnalysisDimensionRefRecord = com.olap.prototype.wire.governance.SubjectAreaAnalysisDimensionRefRecord.newBuilder().apply {
    setId(value.id)
    setSubjectAreaId(value.subjectAreaId)
    setSubjectAreaVersion(value.subjectAreaVersion)
    setAnalysisDimensionId(value.analysisDimensionId)
    setAnalysisDimensionVersion(value.analysisDimensionVersion)
    setStatus(value.status)
    setSelectedBy(value.selectedBy)
    setSelectedAt(value.selectedAt.toString())
}.build()
internal fun SubjectAreaAnalysisDimensionRefRecordFromProto(value: com.olap.prototype.wire.governance.SubjectAreaAnalysisDimensionRefRecord): com.olap.prototype.governance.contract.SubjectAreaAnalysisDimensionRefRecord = com.olap.prototype.governance.contract.SubjectAreaAnalysisDimensionRefRecord(
    id = value.getId(),
    subjectAreaId = value.getSubjectAreaId(),
    subjectAreaVersion = value.getSubjectAreaVersion(),
    analysisDimensionId = value.getAnalysisDimensionId(),
    analysisDimensionVersion = value.getAnalysisDimensionVersion(),
    status = value.getStatus(),
    selectedBy = value.getSelectedBy(),
    selectedAt = java.time.Instant.parse(value.getSelectedAt())
)

internal fun SubjectAreaScenarioRefRecordToProto(value: com.olap.prototype.governance.contract.SubjectAreaScenarioRefRecord): com.olap.prototype.wire.governance.SubjectAreaScenarioRefRecord = com.olap.prototype.wire.governance.SubjectAreaScenarioRefRecord.newBuilder().apply {
    setId(value.id)
    setSubjectAreaId(value.subjectAreaId)
    setSubjectAreaVersion(value.subjectAreaVersion)
    setScenarioId(value.scenarioId)
    setScenarioVersion(value.scenarioVersion)
    setStatus(value.status)
    setSelectedBy(value.selectedBy)
    setSelectedAt(value.selectedAt.toString())
}.build()
internal fun SubjectAreaScenarioRefRecordFromProto(value: com.olap.prototype.wire.governance.SubjectAreaScenarioRefRecord): com.olap.prototype.governance.contract.SubjectAreaScenarioRefRecord = com.olap.prototype.governance.contract.SubjectAreaScenarioRefRecord(
    id = value.getId(),
    subjectAreaId = value.getSubjectAreaId(),
    subjectAreaVersion = value.getSubjectAreaVersion(),
    scenarioId = value.getScenarioId(),
    scenarioVersion = value.getScenarioVersion(),
    status = value.getStatus(),
    selectedBy = value.getSelectedBy(),
    selectedAt = java.time.Instant.parse(value.getSelectedAt())
)

internal fun SubjectContextRevisionRequestToProto(value: com.olap.prototype.governance.contract.SubjectContextRevisionRequest): com.olap.prototype.wire.governance.SubjectContextRevisionRequest = com.olap.prototype.wire.governance.SubjectContextRevisionRequest.newBuilder().apply {
    setObjectId(value.objectId)
    setSourceVersion(value.sourceVersion)
    setDefinition(SubjectAreaCreateRequestToProto(value.definition))
    setCarry(SubjectContextSelectionToProto(value.carry))
}.build()
internal fun SubjectContextRevisionRequestFromProto(value: com.olap.prototype.wire.governance.SubjectContextRevisionRequest): com.olap.prototype.governance.contract.SubjectContextRevisionRequest = com.olap.prototype.governance.contract.SubjectContextRevisionRequest(
    objectId = value.getObjectId(),
    sourceVersion = value.getSourceVersion(),
    definition = required(value.hasDefinition(), "SubjectContextRevisionRequest.definition") { SubjectAreaCreateRequestFromProto(value.getDefinition()) },
    carry = required(value.hasCarry(), "SubjectContextRevisionRequest.carry") { SubjectContextSelectionFromProto(value.getCarry()) }
)

internal fun SubjectContextSelectionToProto(value: com.olap.prototype.governance.contract.SubjectContextSelection): com.olap.prototype.wire.governance.SubjectContextSelection = com.olap.prototype.wire.governance.SubjectContextSelection.newBuilder().apply {
    addAllMetrics(value.metrics.map { it })
    addAllMembers(value.members.map { it })
    addAllDimensions(value.dimensions.map { it })
    addAllScenarios(value.scenarios.map { it })
}.build()
internal fun SubjectContextSelectionFromProto(value: com.olap.prototype.wire.governance.SubjectContextSelection): com.olap.prototype.governance.contract.SubjectContextSelection = com.olap.prototype.governance.contract.SubjectContextSelection(
    metrics = value.getMetricsList().map { it },
    members = value.getMembersList().map { it },
    dimensions = value.getDimensionsList().map { it },
    scenarios = value.getScenariosList().map { it }
)

internal fun SubjectContextRevisionResultToProto(value: com.olap.prototype.governance.contract.SubjectContextRevisionResult): com.olap.prototype.wire.governance.SubjectContextRevisionResult = com.olap.prototype.wire.governance.SubjectContextRevisionResult.newBuilder().apply {
    setSubjectArea(SubjectAreaCreateResultToProto(value.subjectArea))
    addAllCarried(value.carried.map { ContextRevisionReferenceToProto(it) })
}.build()
internal fun SubjectContextRevisionResultFromProto(value: com.olap.prototype.wire.governance.SubjectContextRevisionResult): com.olap.prototype.governance.contract.SubjectContextRevisionResult = com.olap.prototype.governance.contract.SubjectContextRevisionResult(
    subjectArea = required(value.hasSubjectArea(), "SubjectContextRevisionResult.subjectArea") { SubjectAreaCreateResultFromProto(value.getSubjectArea()) },
    carried = value.getCarriedList().map { ContextRevisionReferenceFromProto(it) }
)

internal fun SubjectAreaReleaseBuildRequestToProto(value: com.olap.prototype.governance.contract.SubjectAreaReleaseBuildRequest): com.olap.prototype.wire.governance.SubjectAreaReleaseBuildRequest = com.olap.prototype.wire.governance.SubjectAreaReleaseBuildRequest.newBuilder().apply {
    setReleaseId(value.releaseId)
    setReleaseVersion(value.releaseVersion)
    setReleaseDisplayName(value.releaseDisplayName)
    setSubjectAreaId(value.subjectAreaId)
    setSubjectAreaVersion(value.subjectAreaVersion)
    setCreatedBy(value.createdBy)
    addAllSourceAdoptions(value.sourceAdoptions.map(::SourceAdoptionRefToProto))
}.build()
internal fun SubjectAreaReleaseBuildRequestFromProto(value: com.olap.prototype.wire.governance.SubjectAreaReleaseBuildRequest): com.olap.prototype.governance.contract.SubjectAreaReleaseBuildRequest = com.olap.prototype.governance.contract.SubjectAreaReleaseBuildRequest(
    releaseId = value.getReleaseId(),
    releaseVersion = value.getReleaseVersion(),
    releaseDisplayName = value.getReleaseDisplayName(),
    subjectAreaId = value.getSubjectAreaId(),
    subjectAreaVersion = value.getSubjectAreaVersion(),
    createdBy = value.getCreatedBy(),
    sourceAdoptions = value.getSourceAdoptionsList().map(::SourceAdoptionRefFromProto)
)

internal fun SubjectAreaReleaseBuildResultToProto(value: com.olap.prototype.governance.contract.SubjectAreaReleaseBuildResult): com.olap.prototype.wire.governance.SubjectAreaReleaseBuildResult = com.olap.prototype.wire.governance.SubjectAreaReleaseBuildResult.newBuilder().apply {
    setReleaseId(value.releaseId)
    setReleaseVersion(value.releaseVersion)
    setSubjectAreaId(value.subjectAreaId)
    setSubjectAreaVersion(value.subjectAreaVersion)
    setBuildStatus(value.buildStatus)
    setReleasesWritten(value.releasesWritten)
    setReleaseObjectRefsWritten(value.releaseObjectRefsWritten)
    setMetricsFrozen(value.metricsFrozen)
    setAnalysisDimensionsFrozen(value.analysisDimensionsFrozen)
    setScenariosFrozen(value.scenariosFrozen)
    setEventsFrozen(value.eventsFrozen)
    setTopologiesFrozen(value.topologiesFrozen)
    setJoinPathsFrozen(value.joinPathsFrozen)
    setLogicUnitsFrozen(value.logicUnitsFrozen)
    setDimensionsFrozen(value.dimensionsFrozen)
    setDimensionEventBindingsFrozen(value.dimensionEventBindingsFrozen)
    setMetricDimensionCapabilitiesWritten(value.metricDimensionCapabilitiesWritten)
    setUnavailableReasonsWritten(value.unavailableReasonsWritten)
}.build()
internal fun SubjectAreaReleaseBuildResultFromProto(value: com.olap.prototype.wire.governance.SubjectAreaReleaseBuildResult): com.olap.prototype.governance.contract.SubjectAreaReleaseBuildResult = com.olap.prototype.governance.contract.SubjectAreaReleaseBuildResult(
    releaseId = value.getReleaseId(),
    releaseVersion = value.getReleaseVersion(),
    subjectAreaId = value.getSubjectAreaId(),
    subjectAreaVersion = value.getSubjectAreaVersion(),
    buildStatus = value.getBuildStatus(),
    releasesWritten = value.getReleasesWritten(),
    releaseObjectRefsWritten = value.getReleaseObjectRefsWritten(),
    metricsFrozen = value.getMetricsFrozen(),
    analysisDimensionsFrozen = value.getAnalysisDimensionsFrozen(),
    scenariosFrozen = value.getScenariosFrozen(),
    eventsFrozen = value.getEventsFrozen(),
    topologiesFrozen = value.getTopologiesFrozen(),
    joinPathsFrozen = value.getJoinPathsFrozen(),
    logicUnitsFrozen = value.getLogicUnitsFrozen(),
    dimensionsFrozen = value.getDimensionsFrozen(),
    dimensionEventBindingsFrozen = value.getDimensionEventBindingsFrozen(),
    metricDimensionCapabilitiesWritten = value.getMetricDimensionCapabilitiesWritten(),
    unavailableReasonsWritten = value.getUnavailableReasonsWritten()
)

internal fun SubjectContextSelectionRequestToProto(value: com.olap.prototype.governance.contract.SubjectContextSelectionRequest): com.olap.prototype.wire.governance.SubjectContextSelectionRequest = com.olap.prototype.wire.governance.SubjectContextSelectionRequest.newBuilder().apply {
    setObjectId(value.objectId)
    setSourceVersion(value.sourceVersion)
}.build()
internal fun SubjectContextSelectionRequestFromProto(value: com.olap.prototype.wire.governance.SubjectContextSelectionRequest): com.olap.prototype.governance.contract.SubjectContextSelectionRequest = com.olap.prototype.governance.contract.SubjectContextSelectionRequest(
    objectId = value.getObjectId(),
    sourceVersion = value.getSourceVersion()
)

internal fun SubjectContextSelectionViewToProto(value: com.olap.prototype.governance.contract.SubjectContextSelectionView): com.olap.prototype.wire.governance.SubjectContextSelectionView = com.olap.prototype.wire.governance.SubjectContextSelectionView.newBuilder().apply {
    setObjectId(value.objectId)
    setSourceVersion(value.sourceVersion)
    setSelection(SubjectContextSelectionToProto(value.selection))
}.build()
internal fun SubjectContextSelectionViewFromProto(value: com.olap.prototype.wire.governance.SubjectContextSelectionView): com.olap.prototype.governance.contract.SubjectContextSelectionView = com.olap.prototype.governance.contract.SubjectContextSelectionView(
    objectId = value.getObjectId(),
    sourceVersion = value.getSourceVersion(),
    selection = required(value.hasSelection(), "SubjectContextSelectionView.selection") { SubjectContextSelectionFromProto(value.getSelection()) }
)
