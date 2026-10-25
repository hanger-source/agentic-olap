package com.olap.prototype.codec

/** 正式领域消息的显式字段转换。 */
internal fun PhysicalTableCreateRequestToProto(value: com.olap.prototype.governance.contract.PhysicalTableCreateRequest): com.olap.prototype.wire.governance.PhysicalTableCreateRequest = com.olap.prototype.wire.governance.PhysicalTableCreateRequest.newBuilder().apply {
    setCode(value.code)
    setTableName(value.tableName)
    setDisplayName(value.displayName)
    value.description?.let { setDescription(it) }
    value.dataDomain?.let { setDataDomain(it) }
    setOwner(value.owner)
    value.sensitivityLevel?.let { setSensitivityLevel(it) }
    value.sourceSystem?.let { setSourceSystem(it) }
    setDataSource(value.dataSource)
    value.catalog?.let { setCatalog(it) }
    value.schemaName?.let { setSchemaName(it) }
    setInitialAdoption(PhysicalTableCreateRequestSourceAdoptionInputToProto(value.initialAdoption))
    setCreatedBy(value.createdBy)
    addAllColumns(value.columns.map { PhysicalTableCreateRequestColumnInputToProto(it) })
}.build()
internal fun PhysicalTableCreateRequestFromProto(value: com.olap.prototype.wire.governance.PhysicalTableCreateRequest): com.olap.prototype.governance.contract.PhysicalTableCreateRequest = com.olap.prototype.governance.contract.PhysicalTableCreateRequest(
    code = value.getCode(),
    tableName = value.getTableName(),
    displayName = value.getDisplayName(),
    description = if (value.hasDescription()) value.getDescription() else null,
    dataDomain = if (value.hasDataDomain()) value.getDataDomain() else null,
    owner = value.getOwner(),
    sensitivityLevel = if (value.hasSensitivityLevel()) value.getSensitivityLevel() else null,
    sourceSystem = if (value.hasSourceSystem()) value.getSourceSystem() else null,
    dataSource = value.getDataSource(),
    catalog = if (value.hasCatalog()) value.getCatalog() else null,
    schemaName = if (value.hasSchemaName()) value.getSchemaName() else null,
    initialAdoption = required(value.hasInitialAdoption(), "PhysicalTableCreateRequest.initialAdoption") { PhysicalTableCreateRequestSourceAdoptionInputFromProto(value.getInitialAdoption()) },
    createdBy = value.getCreatedBy(),
    columns = value.getColumnsList().map { PhysicalTableCreateRequestColumnInputFromProto(it) }
)

internal fun PhysicalTableCreateRequestSourceAdoptionInputToProto(value: com.olap.prototype.governance.contract.PhysicalTableCreateRequest.SourceAdoptionInput): com.olap.prototype.wire.governance.PhysicalTableCreateRequestSourceAdoptionInput = com.olap.prototype.wire.governance.PhysicalTableCreateRequestSourceAdoptionInput.newBuilder().apply {
    setDiscoveredTableId(value.discoveredTableId)
    addAllSourceCollectionIds(value.sourceCollectionIds.map { it })
}.build()
internal fun PhysicalTableCreateRequestSourceAdoptionInputFromProto(value: com.olap.prototype.wire.governance.PhysicalTableCreateRequestSourceAdoptionInput): com.olap.prototype.governance.contract.PhysicalTableCreateRequest.SourceAdoptionInput = com.olap.prototype.governance.contract.PhysicalTableCreateRequest.SourceAdoptionInput(
    discoveredTableId = value.getDiscoveredTableId(),
    sourceCollectionIds = value.getSourceCollectionIdsList().map { it }
)

internal fun PhysicalTableCreateRequestColumnInputToProto(value: com.olap.prototype.governance.contract.PhysicalTableCreateRequest.ColumnInput): com.olap.prototype.wire.governance.PhysicalTableCreateRequestColumnInput = com.olap.prototype.wire.governance.PhysicalTableCreateRequestColumnInput.newBuilder().apply {
    setColumnName(value.columnName)
    setDisplayName(value.displayName)
    setColumnType(value.columnType)
    value.sensitivityLevel?.let { setSensitivityLevel(it) }
    setNullable(value.nullable)
    value.description?.let { setDescription(it) }
    setJdbcColumnType(JdbcColumnTypeToProto(value.jdbcColumnType))
}.build()
internal fun PhysicalTableCreateRequestColumnInputFromProto(value: com.olap.prototype.wire.governance.PhysicalTableCreateRequestColumnInput): com.olap.prototype.governance.contract.PhysicalTableCreateRequest.ColumnInput = com.olap.prototype.governance.contract.PhysicalTableCreateRequest.ColumnInput(
    columnName = value.getColumnName(),
    displayName = value.getDisplayName(),
    columnType = value.getColumnType(),
    sensitivityLevel = if (value.hasSensitivityLevel()) value.getSensitivityLevel() else null,
    nullable = value.getNullable(),
    description = if (value.hasDescription()) value.getDescription() else null,
    jdbcColumnType = required(value.hasJdbcColumnType(), "PhysicalTableCreateRequestColumnInput.jdbcColumnType") { JdbcColumnTypeFromProto(value.getJdbcColumnType()) }
)

internal fun PhysicalTableCreateResultToProto(value: com.olap.prototype.governance.contract.PhysicalTableCreateResult): com.olap.prototype.wire.governance.PhysicalTableCreateResult = com.olap.prototype.wire.governance.PhysicalTableCreateResult.newBuilder().apply {
    setPhysicalTable(PhysicalTableRecordToProto(value.physicalTable))
    setPhysicalTableVersion(PhysicalTableVersionRecordToProto(value.physicalTableVersion))
    addAllPhysicalColumns(value.physicalColumns.map { PhysicalColumnRecordToProto(it) })
    addAllPhysicalColumnVersions(value.physicalColumnVersions.map { PhysicalColumnVersionRecordToProto(it) })
    setSourceAdoptions(SourceAdoptionsToProto(value.sourceAdoptions))
}.build()
internal fun PhysicalTableCreateResultFromProto(value: com.olap.prototype.wire.governance.PhysicalTableCreateResult): com.olap.prototype.governance.contract.PhysicalTableCreateResult = com.olap.prototype.governance.contract.PhysicalTableCreateResult(
    physicalTable = required(value.hasPhysicalTable(), "PhysicalTableCreateResult.physicalTable") { PhysicalTableRecordFromProto(value.getPhysicalTable()) },
    physicalTableVersion = required(value.hasPhysicalTableVersion(), "PhysicalTableCreateResult.physicalTableVersion") { PhysicalTableVersionRecordFromProto(value.getPhysicalTableVersion()) },
    physicalColumns = value.getPhysicalColumnsList().map { PhysicalColumnRecordFromProto(it) },
    physicalColumnVersions = value.getPhysicalColumnVersionsList().map { PhysicalColumnVersionRecordFromProto(it) },
    sourceAdoptions = required(value.hasSourceAdoptions(), "PhysicalTableCreateResult.sourceAdoptions") { SourceAdoptionsFromProto(value.getSourceAdoptions()) }
)

internal fun PhysicalTableRecordToProto(value: com.olap.prototype.governance.contract.PhysicalTableRecord): com.olap.prototype.wire.governance.PhysicalTableRecord = com.olap.prototype.wire.governance.PhysicalTableRecord.newBuilder().apply {
    setId(value.id)
    setCode(value.code)
    setDisplayName(value.displayName)
    setDataSource(value.dataSource)
    value.catalogName?.let { setCatalogName(it) }
    value.schemaName?.let { setSchemaName(it) }
    setTableName(value.tableName)
    value.description?.let { setDescription(it) }
    setOwner(value.owner)
    setLoadStatus(value.loadStatus)
    value.rowCount?.let { setRowCount(it) }
    setStatus(value.status)
    setCurrentVersion(value.currentVersion)
    setCreatedBy(value.createdBy)
    setCreatedAt(value.createdAt.toString())
    value.updatedBy?.let { setUpdatedBy(it) }
    value.updatedAt?.let { setUpdatedAt(it.toString()) }
}.build()
internal fun PhysicalTableRecordFromProto(value: com.olap.prototype.wire.governance.PhysicalTableRecord): com.olap.prototype.governance.contract.PhysicalTableRecord = com.olap.prototype.governance.contract.PhysicalTableRecord(
    id = value.getId(),
    code = value.getCode(),
    displayName = value.getDisplayName(),
    dataSource = value.getDataSource(),
    catalogName = if (value.hasCatalogName()) value.getCatalogName() else null,
    schemaName = if (value.hasSchemaName()) value.getSchemaName() else null,
    tableName = value.getTableName(),
    description = if (value.hasDescription()) value.getDescription() else null,
    owner = value.getOwner(),
    loadStatus = value.getLoadStatus(),
    rowCount = if (value.hasRowCount()) value.getRowCount() else null,
    status = value.getStatus(),
    currentVersion = value.getCurrentVersion(),
    createdBy = value.getCreatedBy(),
    createdAt = java.time.Instant.parse(value.getCreatedAt()),
    updatedBy = if (value.hasUpdatedBy()) value.getUpdatedBy() else null,
    updatedAt = if (value.hasUpdatedAt()) java.time.Instant.parse(value.getUpdatedAt()) else null
)

internal fun PhysicalTableVersionRecordToProto(value: com.olap.prototype.governance.contract.PhysicalTableVersionRecord): com.olap.prototype.wire.governance.PhysicalTableVersionRecord = com.olap.prototype.wire.governance.PhysicalTableVersionRecord.newBuilder().apply {
    setId(value.id)
    setPhysicalTableId(value.physicalTableId)
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
internal fun PhysicalTableVersionRecordFromProto(value: com.olap.prototype.wire.governance.PhysicalTableVersionRecord): com.olap.prototype.governance.contract.PhysicalTableVersionRecord = com.olap.prototype.governance.contract.PhysicalTableVersionRecord(
    id = value.getId(),
    physicalTableId = value.getPhysicalTableId(),
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

internal fun PhysicalColumnRecordToProto(value: com.olap.prototype.governance.contract.PhysicalColumnRecord): com.olap.prototype.wire.governance.PhysicalColumnRecord = com.olap.prototype.wire.governance.PhysicalColumnRecord.newBuilder().apply {
    setId(value.id)
    setPhysicalTableId(value.physicalTableId)
    setColumnName(value.columnName)
    setDisplayName(value.displayName)
    setDataType(value.dataType)
    value.description?.let { setDescription(it) }
    setNullable(value.nullable)
    setOrdinalPosition(value.ordinalPosition)
    setEnabled(value.enabled)
    setStatus(value.status)
    setCurrentVersion(value.currentVersion)
    setCreatedBy(value.createdBy)
    setCreatedAt(value.createdAt.toString())
    value.updatedAt?.let { setUpdatedAt(it.toString()) }
    setJdbcColumnType(JdbcColumnTypeToProto(value.jdbcColumnType))
}.build()
internal fun PhysicalColumnRecordFromProto(value: com.olap.prototype.wire.governance.PhysicalColumnRecord): com.olap.prototype.governance.contract.PhysicalColumnRecord = com.olap.prototype.governance.contract.PhysicalColumnRecord(
    id = value.getId(),
    physicalTableId = value.getPhysicalTableId(),
    columnName = value.getColumnName(),
    displayName = value.getDisplayName(),
    dataType = value.getDataType(),
    description = if (value.hasDescription()) value.getDescription() else null,
    nullable = value.getNullable(),
    ordinalPosition = value.getOrdinalPosition(),
    enabled = value.getEnabled(),
    status = value.getStatus(),
    currentVersion = value.getCurrentVersion(),
    createdBy = value.getCreatedBy(),
    createdAt = java.time.Instant.parse(value.getCreatedAt()),
    updatedAt = if (value.hasUpdatedAt()) java.time.Instant.parse(value.getUpdatedAt()) else null,
    jdbcColumnType = required(value.hasJdbcColumnType(), "PhysicalColumnRecord.jdbcColumnType") { JdbcColumnTypeFromProto(value.getJdbcColumnType()) }
)

internal fun PhysicalColumnVersionRecordToProto(value: com.olap.prototype.governance.contract.PhysicalColumnVersionRecord): com.olap.prototype.wire.governance.PhysicalColumnVersionRecord = com.olap.prototype.wire.governance.PhysicalColumnVersionRecord.newBuilder().apply {
    setId(value.id)
    setPhysicalColumnId(value.physicalColumnId)
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
internal fun PhysicalColumnVersionRecordFromProto(value: com.olap.prototype.wire.governance.PhysicalColumnVersionRecord): com.olap.prototype.governance.contract.PhysicalColumnVersionRecord = com.olap.prototype.governance.contract.PhysicalColumnVersionRecord(
    id = value.getId(),
    physicalColumnId = value.getPhysicalColumnId(),
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

internal fun PhysicalLogicalTableRefRecordToProto(value: com.olap.prototype.governance.contract.PhysicalLogicalTableRefRecord): com.olap.prototype.wire.governance.PhysicalLogicalTableRefRecord = com.olap.prototype.wire.governance.PhysicalLogicalTableRefRecord.newBuilder().apply {
    setId(value.id)
    setPhysicalTableId(value.physicalTableId)
    setPhysicalTableVersion(value.physicalTableVersion)
    setLogicalTableId(value.logicalTableId)
    setLogicalTableVersion(value.logicalTableVersion)
    setRefRole(value.refRole)
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun PhysicalLogicalTableRefRecordFromProto(value: com.olap.prototype.wire.governance.PhysicalLogicalTableRefRecord): com.olap.prototype.governance.contract.PhysicalLogicalTableRefRecord = com.olap.prototype.governance.contract.PhysicalLogicalTableRefRecord(
    id = value.getId(),
    physicalTableId = value.getPhysicalTableId(),
    physicalTableVersion = value.getPhysicalTableVersion(),
    logicalTableId = value.getLogicalTableId(),
    logicalTableVersion = value.getLogicalTableVersion(),
    refRole = value.getRefRole(),
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)

internal fun PhysicalTableMetadataUpdateRequestToProto(value: com.olap.prototype.governance.contract.PhysicalTableMetadataUpdateRequest): com.olap.prototype.wire.governance.PhysicalTableMetadataUpdateRequest = com.olap.prototype.wire.governance.PhysicalTableMetadataUpdateRequest.newBuilder().apply {
    setPhysicalTableId(value.physicalTableId)
    setExpectedVersion(value.expectedVersion)
    setDisplayName(value.displayName)
    value.description?.let { setDescription(it) }
    setOwner(value.owner)
    setUpdatedBy(value.updatedBy)
}.build()
internal fun PhysicalTableMetadataUpdateRequestFromProto(value: com.olap.prototype.wire.governance.PhysicalTableMetadataUpdateRequest): com.olap.prototype.governance.contract.PhysicalTableMetadataUpdateRequest = com.olap.prototype.governance.contract.PhysicalTableMetadataUpdateRequest(
    physicalTableId = value.getPhysicalTableId(),
    expectedVersion = value.getExpectedVersion(),
    displayName = value.getDisplayName(),
    description = if (value.hasDescription()) value.getDescription() else null,
    owner = value.getOwner(),
    updatedBy = value.getUpdatedBy()
)

internal fun PhysicalTableMetadataUpdateResultToProto(value: com.olap.prototype.governance.contract.PhysicalTableMetadataUpdateResult): com.olap.prototype.wire.governance.PhysicalTableMetadataUpdateResult = com.olap.prototype.wire.governance.PhysicalTableMetadataUpdateResult.newBuilder().apply {
    setPhysicalTable(PhysicalTableRecordToProto(value.physicalTable))
    setPhysicalTableVersion(PhysicalTableVersionRecordToProto(value.physicalTableVersion))
}.build()
internal fun PhysicalTableMetadataUpdateResultFromProto(value: com.olap.prototype.wire.governance.PhysicalTableMetadataUpdateResult): com.olap.prototype.governance.contract.PhysicalTableMetadataUpdateResult = com.olap.prototype.governance.contract.PhysicalTableMetadataUpdateResult(
    physicalTable = required(value.hasPhysicalTable(), "PhysicalTableMetadataUpdateResult.physicalTable") { PhysicalTableRecordFromProto(value.getPhysicalTable()) },
    physicalTableVersion = required(value.hasPhysicalTableVersion(), "PhysicalTableMetadataUpdateResult.physicalTableVersion") { PhysicalTableVersionRecordFromProto(value.getPhysicalTableVersion()) }
)

internal fun PhysicalTableColumnsUpdateRequestToProto(value: com.olap.prototype.governance.contract.PhysicalTableColumnsUpdateRequest): com.olap.prototype.wire.governance.PhysicalTableColumnsUpdateRequest = com.olap.prototype.wire.governance.PhysicalTableColumnsUpdateRequest.newBuilder().apply {
    setPhysicalTableId(value.physicalTableId)
    setExpectedVersion(value.expectedVersion)
    setUpdatedBy(value.updatedBy)
    addAllColumns(value.columns.map { PhysicalTableColumnsUpdateRequestColumnInputToProto(it) })
}.build()
internal fun PhysicalTableColumnsUpdateRequestFromProto(value: com.olap.prototype.wire.governance.PhysicalTableColumnsUpdateRequest): com.olap.prototype.governance.contract.PhysicalTableColumnsUpdateRequest = com.olap.prototype.governance.contract.PhysicalTableColumnsUpdateRequest(
    physicalTableId = value.getPhysicalTableId(),
    expectedVersion = value.getExpectedVersion(),
    updatedBy = value.getUpdatedBy(),
    columns = value.getColumnsList().map { PhysicalTableColumnsUpdateRequestColumnInputFromProto(it) }
)

internal fun PhysicalTableColumnsUpdateRequestColumnInputToProto(value: com.olap.prototype.governance.contract.PhysicalTableColumnsUpdateRequest.ColumnInput): com.olap.prototype.wire.governance.PhysicalTableColumnsUpdateRequestColumnInput = com.olap.prototype.wire.governance.PhysicalTableColumnsUpdateRequestColumnInput.newBuilder().apply {
    setColumnName(value.columnName)
    setDisplayName(value.displayName)
    setColumnType(value.columnType)
    value.description?.let { setDescription(it) }
    setNullable(value.nullable)
    setJdbcColumnType(JdbcColumnTypeToProto(value.jdbcColumnType))
}.build()
internal fun PhysicalTableColumnsUpdateRequestColumnInputFromProto(value: com.olap.prototype.wire.governance.PhysicalTableColumnsUpdateRequestColumnInput): com.olap.prototype.governance.contract.PhysicalTableColumnsUpdateRequest.ColumnInput = com.olap.prototype.governance.contract.PhysicalTableColumnsUpdateRequest.ColumnInput(
    columnName = value.getColumnName(),
    displayName = value.getDisplayName(),
    columnType = value.getColumnType(),
    description = if (value.hasDescription()) value.getDescription() else null,
    nullable = value.getNullable(),
    jdbcColumnType = required(value.hasJdbcColumnType(), "PhysicalTableColumnsUpdateRequestColumnInput.jdbcColumnType") { JdbcColumnTypeFromProto(value.getJdbcColumnType()) }
)

internal fun PhysicalTableColumnsUpdateResultToProto(value: com.olap.prototype.governance.contract.PhysicalTableColumnsUpdateResult): com.olap.prototype.wire.governance.PhysicalTableColumnsUpdateResult = com.olap.prototype.wire.governance.PhysicalTableColumnsUpdateResult.newBuilder().apply {
    setPhysicalTable(PhysicalTableRecordToProto(value.physicalTable))
    setPhysicalTableVersion(PhysicalTableVersionRecordToProto(value.physicalTableVersion))
    addAllPhysicalColumns(value.physicalColumns.map { PhysicalColumnRecordToProto(it) })
    addAllPhysicalColumnVersions(value.physicalColumnVersions.map { PhysicalColumnVersionRecordToProto(it) })
}.build()
internal fun PhysicalTableColumnsUpdateResultFromProto(value: com.olap.prototype.wire.governance.PhysicalTableColumnsUpdateResult): com.olap.prototype.governance.contract.PhysicalTableColumnsUpdateResult = com.olap.prototype.governance.contract.PhysicalTableColumnsUpdateResult(
    physicalTable = required(value.hasPhysicalTable(), "PhysicalTableColumnsUpdateResult.physicalTable") { PhysicalTableRecordFromProto(value.getPhysicalTable()) },
    physicalTableVersion = required(value.hasPhysicalTableVersion(), "PhysicalTableColumnsUpdateResult.physicalTableVersion") { PhysicalTableVersionRecordFromProto(value.getPhysicalTableVersion()) },
    physicalColumns = value.getPhysicalColumnsList().map { PhysicalColumnRecordFromProto(it) },
    physicalColumnVersions = value.getPhysicalColumnVersionsList().map { PhysicalColumnVersionRecordFromProto(it) }
)
