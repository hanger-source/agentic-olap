package com.olap.prototype.codec

/** 正式领域消息的显式字段转换。 */
internal fun MetricCreateRequestToProto(value: com.olap.prototype.governance.contract.MetricCreateRequest): com.olap.prototype.wire.governance.MetricCreateRequest = com.olap.prototype.wire.governance.MetricCreateRequest.newBuilder().apply {
    setCode(value.code)
    setDisplayName(value.displayName)
    setOwner(value.owner)
    setDefinition(SemanticProto.toProto(value.definition))
    value.description?.let { setDescription(it) }
    setBusinessMeaning(SemanticProto.toProto(value.businessMeaning))
    setCreatedBy(value.createdBy)
}.build()
internal fun MetricCreateRequestFromProto(value: com.olap.prototype.wire.governance.MetricCreateRequest): com.olap.prototype.governance.contract.MetricCreateRequest = com.olap.prototype.governance.contract.MetricCreateRequest(
    code = value.getCode(),
    displayName = value.getDisplayName(),
    owner = value.getOwner(),
    definition = required(value.hasDefinition(), "MetricCreateRequest.definition") { SemanticProto.fromProto(value.getDefinition()) as com.olap.prototype.calculation.MetricDefinition.FactMeasure },
    description = if (value.hasDescription()) value.getDescription() else null,
    businessMeaning = required(value.hasBusinessMeaning(), "MetricCreateRequest.businessMeaning") { SemanticProto.fromProto(value.getBusinessMeaning()) as com.olap.prototype.metric.MetricBusinessMeaning },
    createdBy = value.getCreatedBy()
)

internal fun MetricCreateResultToProto(value: com.olap.prototype.governance.contract.MetricCreateResult): com.olap.prototype.wire.governance.MetricCreateResult = com.olap.prototype.wire.governance.MetricCreateResult.newBuilder().apply {
    setMetric(MetricRecordToProto(value.metric))
    setMetricVersion(MetricVersionRecordToProto(value.metricVersion))
}.build()
internal fun MetricCreateResultFromProto(value: com.olap.prototype.wire.governance.MetricCreateResult): com.olap.prototype.governance.contract.MetricCreateResult = com.olap.prototype.governance.contract.MetricCreateResult(
    metric = required(value.hasMetric(), "MetricCreateResult.metric") { MetricRecordFromProto(value.getMetric()) },
    metricVersion = required(value.hasMetricVersion(), "MetricCreateResult.metricVersion") { MetricVersionRecordFromProto(value.getMetricVersion()) }
)

internal fun MetricRecordToProto(value: com.olap.prototype.governance.contract.MetricRecord): com.olap.prototype.wire.governance.MetricRecord = com.olap.prototype.wire.governance.MetricRecord.newBuilder().apply {
    setId(value.id)
    setCode(value.code)
    setDisplayName(value.displayName)
    setDefinition(SemanticProto.toProto(value.definition))
    value.description?.let { setDescription(it) }
    setOwner(value.owner)
    setStatus(value.status)
    setCurrentVersion(value.currentVersion)
    setCreatedBy(value.createdBy)
    setCreatedAt(value.createdAt.toString())
    value.updatedBy?.let { setUpdatedBy(it) }
    value.updatedAt?.let { setUpdatedAt(it.toString()) }
}.build()
internal fun MetricRecordFromProto(value: com.olap.prototype.wire.governance.MetricRecord): com.olap.prototype.governance.contract.MetricRecord = com.olap.prototype.governance.contract.MetricRecord(
    id = value.getId(),
    code = value.getCode(),
    displayName = value.getDisplayName(),
    definition = required(value.hasDefinition(), "MetricRecord.definition") { SemanticProto.fromProto(value.getDefinition()) as com.olap.prototype.calculation.MetricDefinition },
    description = if (value.hasDescription()) value.getDescription() else null,
    owner = value.getOwner(),
    status = value.getStatus(),
    currentVersion = value.getCurrentVersion(),
    createdBy = value.getCreatedBy(),
    createdAt = java.time.Instant.parse(value.getCreatedAt()),
    updatedBy = if (value.hasUpdatedBy()) value.getUpdatedBy() else null,
    updatedAt = if (value.hasUpdatedAt()) java.time.Instant.parse(value.getUpdatedAt()) else null
)

internal fun MetricVersionRecordToProto(value: com.olap.prototype.governance.contract.MetricVersionRecord): com.olap.prototype.wire.governance.MetricVersionRecord = com.olap.prototype.wire.governance.MetricVersionRecord.newBuilder().apply {
    setId(value.id)
    setMetricId(value.metricId)
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
internal fun MetricVersionRecordFromProto(value: com.olap.prototype.wire.governance.MetricVersionRecord): com.olap.prototype.governance.contract.MetricVersionRecord = com.olap.prototype.governance.contract.MetricVersionRecord(
    id = value.getId(),
    metricId = value.getMetricId(),
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

internal fun MetricCalculationCreateRequestToProto(value: com.olap.prototype.governance.contract.MetricCalculationCreateRequest): com.olap.prototype.wire.governance.MetricCalculationCreateRequest = com.olap.prototype.wire.governance.MetricCalculationCreateRequest.newBuilder().apply {
    setCode(value.code)
    setDisplayName(value.displayName)
    setOwner(value.owner)
    setDefinition(SemanticProto.toProto(value.definition))
    value.description?.let { setDescription(it) }
    setBusinessMeaning(SemanticProto.toProto(value.businessMeaning))
    setCreatedBy(value.createdBy)
}.build()
internal fun MetricCalculationCreateRequestFromProto(value: com.olap.prototype.wire.governance.MetricCalculationCreateRequest): com.olap.prototype.governance.contract.MetricCalculationCreateRequest = com.olap.prototype.governance.contract.MetricCalculationCreateRequest(
    code = value.getCode(),
    displayName = value.getDisplayName(),
    owner = value.getOwner(),
    definition = required(value.hasDefinition(), "MetricCalculationCreateRequest.definition") { SemanticProto.fromProto(value.getDefinition()) as com.olap.prototype.calculation.MetricCalculation },
    description = if (value.hasDescription()) value.getDescription() else null,
    businessMeaning = required(value.hasBusinessMeaning(), "MetricCalculationCreateRequest.businessMeaning") { SemanticProto.fromProto(value.getBusinessMeaning()) as com.olap.prototype.metric.MetricBusinessMeaning },
    createdBy = value.getCreatedBy()
)

internal fun MetricCalculationCreateResultToProto(value: com.olap.prototype.governance.contract.MetricCalculationCreateResult): com.olap.prototype.wire.governance.MetricCalculationCreateResult = com.olap.prototype.wire.governance.MetricCalculationCreateResult.newBuilder().apply {
    setMetric(MetricRecordToProto(value.metric))
    setMetricVersion(MetricVersionRecordToProto(value.metricVersion))
    addAllDependencies(value.dependencies.map { MetricDependencyRecordToProto(it) })
}.build()
internal fun MetricCalculationCreateResultFromProto(value: com.olap.prototype.wire.governance.MetricCalculationCreateResult): com.olap.prototype.governance.contract.MetricCalculationCreateResult = com.olap.prototype.governance.contract.MetricCalculationCreateResult(
    metric = required(value.hasMetric(), "MetricCalculationCreateResult.metric") { MetricRecordFromProto(value.getMetric()) },
    metricVersion = required(value.hasMetricVersion(), "MetricCalculationCreateResult.metricVersion") { MetricVersionRecordFromProto(value.getMetricVersion()) },
    dependencies = value.getDependenciesList().map { MetricDependencyRecordFromProto(it) }
)

internal fun MetricDependencyRecordToProto(value: com.olap.prototype.governance.contract.MetricDependencyRecord): com.olap.prototype.wire.governance.MetricDependencyRecord = com.olap.prototype.wire.governance.MetricDependencyRecord.newBuilder().apply {
    setId(value.id)
    setMetricId(value.metricId)
    setMetricVersion(value.metricVersion)
    setDependencyScope(value.dependencyScope)
    setDependencyType(value.dependencyType)
    setDependencyId(value.dependencyId)
    value.dependencyVersion?.let { setDependencyVersion(it) }
    value.referencedSymbol?.let { setReferencedSymbol(it) }
    setBindingStatus(value.bindingStatus)
    value.diagnosticCode?.let { setDiagnosticCode(it) }
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun MetricDependencyRecordFromProto(value: com.olap.prototype.wire.governance.MetricDependencyRecord): com.olap.prototype.governance.contract.MetricDependencyRecord = com.olap.prototype.governance.contract.MetricDependencyRecord(
    id = value.getId(),
    metricId = value.getMetricId(),
    metricVersion = value.getMetricVersion(),
    dependencyScope = value.getDependencyScope(),
    dependencyType = value.getDependencyType(),
    dependencyId = value.getDependencyId(),
    dependencyVersion = if (value.hasDependencyVersion()) value.getDependencyVersion() else null,
    referencedSymbol = if (value.hasReferencedSymbol()) value.getReferencedSymbol() else null,
    bindingStatus = value.getBindingStatus(),
    diagnosticCode = if (value.hasDiagnosticCode()) value.getDiagnosticCode() else null,
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)
