package com.olap.prototype.codec

/** 正式Warehouse消息的显式字段转换。 */
internal fun JdbcTableConstraintsToProto(value: com.olap.prototype.warehouse.contract.JdbcTableConstraints): com.olap.prototype.wire.governance.JdbcTableConstraints = com.olap.prototype.wire.governance.JdbcTableConstraints.newBuilder().apply {
    addAllPrimaryKeyColumns(value.primaryKeyColumns.map { it })
    addAllUniqueKeys(value.uniqueKeys.map { JdbcTableConstraintsUniqueKeyToProto(it) })
    addAllImportedKeys(value.importedKeys.map { JdbcTableConstraintsForeignKeyColumnToProto(it) })
    addAllTemporalKeys(value.temporalKeys.map { JdbcTableConstraintsTemporalKeyToProto(it) })
}.build()
internal fun JdbcTableConstraintsFromProto(value: com.olap.prototype.wire.governance.JdbcTableConstraints): com.olap.prototype.warehouse.contract.JdbcTableConstraints = com.olap.prototype.warehouse.contract.JdbcTableConstraints(
    primaryKeyColumns = value.getPrimaryKeyColumnsList().map { it },
    uniqueKeys = value.getUniqueKeysList().map { JdbcTableConstraintsUniqueKeyFromProto(it) },
    importedKeys = value.getImportedKeysList().map { JdbcTableConstraintsForeignKeyColumnFromProto(it) },
    temporalKeys = value.getTemporalKeysList().map { JdbcTableConstraintsTemporalKeyFromProto(it) }
)

internal fun JdbcTableConstraintsUniqueKeyToProto(value: com.olap.prototype.warehouse.contract.JdbcTableConstraints.UniqueKey): com.olap.prototype.wire.governance.JdbcTableConstraintsUniqueKey = com.olap.prototype.wire.governance.JdbcTableConstraintsUniqueKey.newBuilder().apply {
    setName(value.name)
    addAllColumns(value.columns.map { it })
    value.filterCondition?.let { setFilterCondition(it) }
    value.expressions?.let { setExpressions(it) }
}.build()
internal fun JdbcTableConstraintsUniqueKeyFromProto(value: com.olap.prototype.wire.governance.JdbcTableConstraintsUniqueKey): com.olap.prototype.warehouse.contract.JdbcTableConstraints.UniqueKey = com.olap.prototype.warehouse.contract.JdbcTableConstraints.UniqueKey(
    name = value.getName(),
    columns = value.getColumnsList().map { it },
    filterCondition = if (value.hasFilterCondition()) value.getFilterCondition() else null,
    expressions = if (value.hasExpressions()) value.getExpressions() else null
)

internal fun JdbcTableConstraintsForeignKeyColumnToProto(value: com.olap.prototype.warehouse.contract.JdbcTableConstraints.ForeignKeyColumn): com.olap.prototype.wire.governance.JdbcTableConstraintsForeignKeyColumn = com.olap.prototype.wire.governance.JdbcTableConstraintsForeignKeyColumn.newBuilder().apply {
    value.name?.let { setName(it) }
    setColumn(value.column)
    setReferencedTable(JdbcTableConstraintsTableToProto(value.referencedTable))
    setReferencedColumn(value.referencedColumn)
    setUpdateRule(value.updateRule.toInt())
    setDeleteRule(value.deleteRule.toInt())
    setKeySequence(value.keySequence)
}.build()
internal fun JdbcTableConstraintsForeignKeyColumnFromProto(value: com.olap.prototype.wire.governance.JdbcTableConstraintsForeignKeyColumn): com.olap.prototype.warehouse.contract.JdbcTableConstraints.ForeignKeyColumn = com.olap.prototype.warehouse.contract.JdbcTableConstraints.ForeignKeyColumn(
    name = if (value.hasName()) value.getName() else null,
    column = value.getColumn(),
    referencedTable = required(value.hasReferencedTable(), "JdbcTableConstraintsForeignKeyColumn.referencedTable") { JdbcTableConstraintsTableFromProto(value.getReferencedTable()) },
    referencedColumn = value.getReferencedColumn(),
    updateRule = value.getUpdateRule().toShort(),
    deleteRule = value.getDeleteRule().toShort(),
    keySequence = value.getKeySequence()
)

internal fun JdbcTableConstraintsTableToProto(value: com.olap.prototype.warehouse.contract.JdbcTableConstraints.Table): com.olap.prototype.wire.governance.JdbcTableConstraintsTable = com.olap.prototype.wire.governance.JdbcTableConstraintsTable.newBuilder().apply {
    value.catalog?.let { setCatalog(it) }
    value.schema?.let { setSchema(it) }
    setTable(value.table)
}.build()
internal fun JdbcTableConstraintsTableFromProto(value: com.olap.prototype.wire.governance.JdbcTableConstraintsTable): com.olap.prototype.warehouse.contract.JdbcTableConstraints.Table = com.olap.prototype.warehouse.contract.JdbcTableConstraints.Table(
    catalog = if (value.hasCatalog()) value.getCatalog() else null,
    schema = if (value.hasSchema()) value.getSchema() else null,
    table = value.getTable()
)

internal fun JdbcTableConstraintsTemporalKeyToProto(value: com.olap.prototype.warehouse.contract.JdbcTableConstraints.TemporalKey): com.olap.prototype.wire.governance.JdbcTableConstraintsTemporalKey = com.olap.prototype.wire.governance.JdbcTableConstraintsTemporalKey.newBuilder().apply {
    setRule(JdbcTableConstraintsTemporalRuleToProto(value.rule))
    setAnchor(JdbcTableConstraintsDataAnchorToProto(value.anchor))
}.build()
internal fun JdbcTableConstraintsTemporalKeyFromProto(value: com.olap.prototype.wire.governance.JdbcTableConstraintsTemporalKey): com.olap.prototype.warehouse.contract.JdbcTableConstraints.TemporalKey = com.olap.prototype.warehouse.contract.JdbcTableConstraints.TemporalKey(
    rule = required(value.hasRule(), "JdbcTableConstraintsTemporalKey.rule") { JdbcTableConstraintsTemporalRuleFromProto(value.getRule()) },
    anchor = required(value.hasAnchor(), "JdbcTableConstraintsTemporalKey.anchor") { JdbcTableConstraintsDataAnchorFromProto(value.getAnchor()) }
)

internal fun JdbcTableConstraintsTemporalRuleToProto(value: com.olap.prototype.warehouse.contract.JdbcTableConstraints.TemporalRule): com.olap.prototype.wire.governance.JdbcTableConstraintsTemporalRule = com.olap.prototype.wire.governance.JdbcTableConstraintsTemporalRule.newBuilder().apply {
    setName(value.name)
    addAllEqualityColumns(value.equalityColumns.map { it })
    addAllIntervals(value.intervals.map { JdbcTableConstraintsTemporalIntervalToProto(it) })
}.build()
internal fun JdbcTableConstraintsTemporalRuleFromProto(value: com.olap.prototype.wire.governance.JdbcTableConstraintsTemporalRule): com.olap.prototype.warehouse.contract.JdbcTableConstraints.TemporalRule = com.olap.prototype.warehouse.contract.JdbcTableConstraints.TemporalRule(
    name = value.getName(),
    equalityColumns = value.getEqualityColumnsList().map { it },
    intervals = value.getIntervalsList().map { JdbcTableConstraintsTemporalIntervalFromProto(it) }
)

internal fun JdbcTableConstraintsTemporalIntervalToProto(value: com.olap.prototype.warehouse.contract.JdbcTableConstraints.TemporalInterval): com.olap.prototype.wire.governance.JdbcTableConstraintsTemporalInterval = com.olap.prototype.wire.governance.JdbcTableConstraintsTemporalInterval.newBuilder().apply {
    setValidFromColumn(value.validFromColumn)
    setValidToColumn(value.validToColumn)
    setValidFromInclusive(value.validFromInclusive)
    setValidToInclusive(value.validToInclusive)
    setOpenEndedValidTo(value.openEndedValidTo)
}.build()
internal fun JdbcTableConstraintsTemporalIntervalFromProto(value: com.olap.prototype.wire.governance.JdbcTableConstraintsTemporalInterval): com.olap.prototype.warehouse.contract.JdbcTableConstraints.TemporalInterval = com.olap.prototype.warehouse.contract.JdbcTableConstraints.TemporalInterval(
    validFromColumn = value.getValidFromColumn(),
    validToColumn = value.getValidToColumn(),
    validFromInclusive = value.getValidFromInclusive(),
    validToInclusive = value.getValidToInclusive(),
    openEndedValidTo = value.getOpenEndedValidTo()
)

internal fun JdbcTableConstraintsDataAnchorToProto(value: com.olap.prototype.warehouse.contract.JdbcTableConstraints.DataAnchor): com.olap.prototype.wire.governance.JdbcTableConstraintsDataAnchor = com.olap.prototype.wire.governance.JdbcTableConstraintsDataAnchor.newBuilder().apply {
    setSha256(value.sha256)
}.build()
internal fun JdbcTableConstraintsDataAnchorFromProto(value: com.olap.prototype.wire.governance.JdbcTableConstraintsDataAnchor): com.olap.prototype.warehouse.contract.JdbcTableConstraints.DataAnchor = com.olap.prototype.warehouse.contract.JdbcTableConstraints.DataAnchor(
    sha256 = value.getSha256()
)

internal fun SourceCollectionRecordToProto(value: com.olap.prototype.warehouse.contract.SourceCollectionRecord): com.olap.prototype.wire.governance.SourceCollectionRecord = com.olap.prototype.wire.governance.SourceCollectionRecord.newBuilder().apply {
    setId(value.id)
    setDataSourceId(value.dataSourceId)
    setSourceIdentityHash(value.sourceIdentityHash)
    setDeclarationSha256(value.declarationSha256)
    setSourceAuthority(value.sourceAuthority)
    setRuleDocumentSha256(value.ruleDocumentSha256)
    setAnchor(JdbcTableConstraintsDataAnchorToProto(value.anchor))
    setScope(SealedSourceDatasetScopeToProto(value.scope))
    setCollection(SealedSourceDatasetCollectionToProto(value.collection))
}.build()
internal fun SourceCollectionRecordFromProto(value: com.olap.prototype.wire.governance.SourceCollectionRecord): com.olap.prototype.warehouse.contract.SourceCollectionRecord = com.olap.prototype.warehouse.contract.SourceCollectionRecord(
    id = value.getId(),
    dataSourceId = value.getDataSourceId(),
    sourceIdentityHash = value.getSourceIdentityHash(),
    declarationSha256 = value.getDeclarationSha256(),
    sourceAuthority = value.getSourceAuthority(),
    ruleDocumentSha256 = value.getRuleDocumentSha256(),
    anchor = required(value.hasAnchor(), "SourceCollectionRecord.anchor") { JdbcTableConstraintsDataAnchorFromProto(value.getAnchor()) },
    scope = required(value.hasScope(), "SourceCollectionRecord.scope") { SealedSourceDatasetScopeFromProto(value.getScope()) },
    collection = required(value.hasCollection(), "SourceCollectionRecord.collection") { SealedSourceDatasetCollectionFromProto(value.getCollection()) }
)

internal fun SealedSourceDatasetScopeToProto(value: com.olap.prototype.warehouse.contract.SealedSourceDataset.Scope): com.olap.prototype.wire.governance.SealedSourceDatasetScope = com.olap.prototype.wire.governance.SealedSourceDatasetScope.newBuilder().apply {
    setTimezone(value.timezone)
    setReportingStartInclusive(value.reportingStartInclusive.toString())
    setReportingEndExclusive(value.reportingEndExclusive.toString())
    setObservedThrough(value.observedThrough.toString())
}.build()
internal fun SealedSourceDatasetScopeFromProto(value: com.olap.prototype.wire.governance.SealedSourceDatasetScope): com.olap.prototype.warehouse.contract.SealedSourceDataset.Scope = com.olap.prototype.warehouse.contract.SealedSourceDataset.Scope(
    timezone = value.getTimezone(),
    reportingStartInclusive = java.time.LocalDate.parse(value.getReportingStartInclusive()),
    reportingEndExclusive = java.time.LocalDate.parse(value.getReportingEndExclusive()),
    observedThrough = java.time.LocalDateTime.parse(value.getObservedThrough())
)

internal fun SealedSourceDatasetCollectionToProto(value: com.olap.prototype.warehouse.contract.SealedSourceDataset.Collection): com.olap.prototype.wire.governance.SealedSourceDatasetCollection = com.olap.prototype.wire.governance.SealedSourceDatasetCollection.newBuilder().apply {
    setCode(value.code)
    setTable(JdbcTableConstraintsTableToProto(value.table))
    addAllRuleIds(value.ruleIds.map { it })
}.build()
internal fun SealedSourceDatasetCollectionFromProto(value: com.olap.prototype.wire.governance.SealedSourceDatasetCollection): com.olap.prototype.warehouse.contract.SealedSourceDataset.Collection = com.olap.prototype.warehouse.contract.SealedSourceDataset.Collection(
    code = value.getCode(),
    table = required(value.hasTable(), "SealedSourceDatasetCollection.table") { JdbcTableConstraintsTableFromProto(value.getTable()) },
    ruleIds = value.getRuleIdsList().map { it }
)

internal fun StructureProvenanceToProto(value: com.olap.prototype.warehouse.contract.StructureProvenance): com.olap.prototype.wire.governance.StructureProvenance = com.olap.prototype.wire.governance.StructureProvenance.newBuilder().apply {
    setKind(value.kind.name)
    setAuthority(value.authority)
    setRevision(value.revision)
}.build()
internal fun StructureProvenanceFromProto(value: com.olap.prototype.wire.governance.StructureProvenance): com.olap.prototype.warehouse.contract.StructureProvenance = com.olap.prototype.warehouse.contract.StructureProvenance(
    kind = com.olap.prototype.warehouse.contract.StructureProvenance.Kind.valueOf(value.getKind()),
    authority = value.getAuthority(),
    revision = value.getRevision()
)

internal fun SnapshotUniqueKeyToProto(value: com.olap.prototype.warehouse.contract.SnapshotUniqueKey): com.olap.prototype.wire.governance.SnapshotUniqueKey = com.olap.prototype.wire.governance.SnapshotUniqueKey.newBuilder().apply {
    setRule(SnapshotUniqueKeyRuleToProto(value.rule))
    setAnchor(JdbcTableConstraintsDataAnchorToProto(value.anchor))
}.build()
internal fun SnapshotUniqueKeyFromProto(value: com.olap.prototype.wire.governance.SnapshotUniqueKey): com.olap.prototype.warehouse.contract.SnapshotUniqueKey = com.olap.prototype.warehouse.contract.SnapshotUniqueKey(
    rule = required(value.hasRule(), "SnapshotUniqueKey.rule") { SnapshotUniqueKeyRuleFromProto(value.getRule()) },
    anchor = required(value.hasAnchor(), "SnapshotUniqueKey.anchor") { JdbcTableConstraintsDataAnchorFromProto(value.getAnchor()) }
)

internal fun SnapshotUniqueKeyRuleToProto(value: com.olap.prototype.warehouse.contract.SnapshotUniqueKey.Rule): com.olap.prototype.wire.governance.SnapshotUniqueKeyRule = com.olap.prototype.wire.governance.SnapshotUniqueKeyRule.newBuilder().apply {
    setName(value.name)
    addAllColumns(value.columns.map { it })
}.build()
internal fun SnapshotUniqueKeyRuleFromProto(value: com.olap.prototype.wire.governance.SnapshotUniqueKeyRule): com.olap.prototype.warehouse.contract.SnapshotUniqueKey.Rule = com.olap.prototype.warehouse.contract.SnapshotUniqueKey.Rule(
    name = value.getName(),
    columns = value.getColumnsList().map { it }
)

internal fun SourceStructureRegistrationRequestToProto(value: com.olap.prototype.warehouse.onboarding.SourceStructureRegistrationRequest): com.olap.prototype.wire.governance.SourceStructureRegistrationRequest = com.olap.prototype.wire.governance.SourceStructureRegistrationRequest.newBuilder().apply {
    setDataSourceId(value.dataSourceId)
    setSourceIdentityHash(value.sourceIdentityHash)
    value.catalog?.let { setCatalog(it) }
    setSchemaName(value.schemaName)
    setAuthority(value.authority)
    setRevision(value.revision)
    addAllTables(value.tables.map { SourceStructureRegistrationRequestTableToProto(it) })
    setRequestedBy(value.requestedBy)
}.build()
internal fun SourceStructureRegistrationRequestFromProto(value: com.olap.prototype.wire.governance.SourceStructureRegistrationRequest): com.olap.prototype.warehouse.onboarding.SourceStructureRegistrationRequest = com.olap.prototype.warehouse.onboarding.SourceStructureRegistrationRequest(
    dataSourceId = value.getDataSourceId(),
    sourceIdentityHash = value.getSourceIdentityHash(),
    catalog = if (value.hasCatalog()) value.getCatalog() else null,
    schemaName = value.getSchemaName(),
    authority = value.getAuthority(),
    revision = value.getRevision(),
    tables = value.getTablesList().map { SourceStructureRegistrationRequestTableFromProto(it) },
    requestedBy = value.getRequestedBy()
)

internal fun SourceStructureRegistrationRequestTableToProto(value: com.olap.prototype.warehouse.onboarding.SourceStructureRegistrationRequest.Table): com.olap.prototype.wire.governance.SourceStructureRegistrationRequestTable = com.olap.prototype.wire.governance.SourceStructureRegistrationRequestTable.newBuilder().apply {
    setTable(WarehouseTableSummaryToProto(value.table))
    addAllColumns(value.columns.map { WarehouseColumnMetadataToProto(it) })
    setConstraints(JdbcTableConstraintsToProto(value.constraints))
}.build()
internal fun SourceStructureRegistrationRequestTableFromProto(value: com.olap.prototype.wire.governance.SourceStructureRegistrationRequestTable): com.olap.prototype.warehouse.onboarding.SourceStructureRegistrationRequest.Table = com.olap.prototype.warehouse.onboarding.SourceStructureRegistrationRequest.Table(
    table = required(value.hasTable(), "SourceStructureRegistrationRequestTable.table") { WarehouseTableSummaryFromProto(value.getTable()) },
    columns = value.getColumnsList().map { WarehouseColumnMetadataFromProto(it) },
    constraints = required(value.hasConstraints(), "SourceStructureRegistrationRequestTable.constraints") { JdbcTableConstraintsFromProto(value.getConstraints()) }
)

internal fun WarehouseTableSummaryToProto(value: com.olap.prototype.warehouse.inspection.WarehouseTableSummary): com.olap.prototype.wire.governance.WarehouseTableSummary = com.olap.prototype.wire.governance.WarehouseTableSummary.newBuilder().apply {
    setRef(WarehouseTableRefToProto(value.ref))
    value.tableType?.let { setTableType(it) }
    value.remarks?.let { setRemarks(it) }
}.build()
internal fun WarehouseTableSummaryFromProto(value: com.olap.prototype.wire.governance.WarehouseTableSummary): com.olap.prototype.warehouse.inspection.WarehouseTableSummary = com.olap.prototype.warehouse.inspection.WarehouseTableSummary(
    ref = required(value.hasRef(), "WarehouseTableSummary.ref") { WarehouseTableRefFromProto(value.getRef()) },
    tableType = if (value.hasTableType()) value.getTableType() else null,
    remarks = if (value.hasRemarks()) value.getRemarks() else null
)

internal fun WarehouseTableRefToProto(value: com.olap.prototype.warehouse.inspection.WarehouseTableRef): com.olap.prototype.wire.governance.WarehouseTableRef = com.olap.prototype.wire.governance.WarehouseTableRef.newBuilder().apply {
    value.catalog?.let { setCatalog(it) }
    value.schema?.let { setSchema(it) }
    setTable(value.table)
}.build()
internal fun WarehouseTableRefFromProto(value: com.olap.prototype.wire.governance.WarehouseTableRef): com.olap.prototype.warehouse.inspection.WarehouseTableRef = com.olap.prototype.warehouse.inspection.WarehouseTableRef(
    catalog = if (value.hasCatalog()) value.getCatalog() else null,
    schema = if (value.hasSchema()) value.getSchema() else null,
    table = value.getTable()
)

internal fun WarehouseColumnMetadataToProto(value: com.olap.prototype.warehouse.inspection.WarehouseColumnMetadata): com.olap.prototype.wire.governance.WarehouseColumnMetadata = com.olap.prototype.wire.governance.WarehouseColumnMetadata.newBuilder().apply {
    setName(value.name)
    value.jdbcType?.let { setJdbcType(it) }
    value.typeName?.let { setTypeName(it) }
    value.ordinalPosition?.let { setOrdinalPosition(it) }
    value.nullable?.let { setNullable(it) }
    value.precision?.let { setPrecision(it) }
    value.scale?.let { setScale(it) }
    value.autoIncrement?.let { setAutoIncrement(it) }
    value.generated?.let { setGenerated(it) }
    value.defaultValue?.let { setDefaultValue(it) }
    value.remarks?.let { setRemarks(it) }
}.build()
internal fun WarehouseColumnMetadataFromProto(value: com.olap.prototype.wire.governance.WarehouseColumnMetadata): com.olap.prototype.warehouse.inspection.WarehouseColumnMetadata = com.olap.prototype.warehouse.inspection.WarehouseColumnMetadata(
    name = value.getName(),
    jdbcType = if (value.hasJdbcType()) value.getJdbcType() else null,
    typeName = if (value.hasTypeName()) value.getTypeName() else null,
    ordinalPosition = if (value.hasOrdinalPosition()) value.getOrdinalPosition() else null,
    nullable = if (value.hasNullable()) value.getNullable() else null,
    precision = if (value.hasPrecision()) value.getPrecision() else null,
    scale = if (value.hasScale()) value.getScale() else null,
    autoIncrement = if (value.hasAutoIncrement()) value.getAutoIncrement() else null,
    generated = if (value.hasGenerated()) value.getGenerated() else null,
    defaultValue = if (value.hasDefaultValue()) value.getDefaultValue() else null,
    remarks = if (value.hasRemarks()) value.getRemarks() else null
)

internal fun SourceDiscoveryReceiptToProto(value: com.olap.prototype.warehouse.onboarding.SourceDiscoveryReceipt): com.olap.prototype.wire.governance.SourceDiscoveryReceipt = com.olap.prototype.wire.governance.SourceDiscoveryReceipt.newBuilder().apply {
    setSnapshotId(value.snapshotId)
    setDataSourceId(value.dataSourceId)
    setSourceIdentityHash(value.sourceIdentityHash)
    setStatus(value.status)
    setTruncated(value.truncated)
    setTableCount(value.tableCount)
    setColumnCount(value.columnCount)
}.build()
internal fun SourceDiscoveryReceiptFromProto(value: com.olap.prototype.wire.governance.SourceDiscoveryReceipt): com.olap.prototype.warehouse.onboarding.SourceDiscoveryReceipt = com.olap.prototype.warehouse.onboarding.SourceDiscoveryReceipt(
    snapshotId = value.getSnapshotId(),
    dataSourceId = value.getDataSourceId(),
    sourceIdentityHash = value.getSourceIdentityHash(),
    status = value.getStatus(),
    truncated = value.getTruncated(),
    tableCount = value.getTableCount(),
    columnCount = value.getColumnCount()
)

internal fun WarehouseDiscoveryCommandToProto(value: com.olap.prototype.warehouse.onboarding.WarehouseDiscoveryCommand): com.olap.prototype.wire.governance.WarehouseDiscoveryCommand = com.olap.prototype.wire.governance.WarehouseDiscoveryCommand.newBuilder().apply {
    setDataSourceId(value.dataSourceId)
    setSourceIdentityHash(value.sourceIdentityHash)
    value.catalog?.let { setCatalog(it) }
    setSchemaName(value.schemaName)
    value.tablePattern?.let { setTablePattern(it) }
    addAllTableTypes(value.tableTypes.map { it })
    value.limit?.let { setLimit(it) }
    addAllTemporalKeys(value.temporalKeys.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.WarehouseDiscoveryCommandTemporalKeysEntry.newBuilder().setKey(entry.key).apply { setValue(com.olap.prototype.wire.governance.ListJdbcTableConstraintsTemporalRule.newBuilder().addAllValues(entry.value.map { JdbcTableConstraintsTemporalRuleToProto(it) }).build()) }.build() })
    addAllSnapshotUniqueKeys(value.snapshotUniqueKeys.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.WarehouseDiscoveryCommandSnapshotUniqueKeysEntry.newBuilder().setKey(entry.key).apply { setValue(com.olap.prototype.wire.governance.ListSnapshotUniqueKeyRule.newBuilder().addAllValues(entry.value.map { SnapshotUniqueKeyRuleToProto(it) }).build()) }.build() })
    setRequestedBy(value.requestedBy)
}.build()
internal fun WarehouseDiscoveryCommandFromProto(value: com.olap.prototype.wire.governance.WarehouseDiscoveryCommand): com.olap.prototype.warehouse.onboarding.WarehouseDiscoveryCommand = com.olap.prototype.warehouse.onboarding.WarehouseDiscoveryCommand(
    dataSourceId = value.getDataSourceId(),
    sourceIdentityHash = value.getSourceIdentityHash(),
    catalog = if (value.hasCatalog()) value.getCatalog() else null,
    schemaName = value.getSchemaName(),
    tablePattern = if (value.hasTablePattern()) value.getTablePattern() else null,
    tableTypes = value.getTableTypesList().map { it },
    limit = if (value.hasLimit()) value.getLimit() else null,
    temporalKeys = strictMap(value.getTemporalKeysList().map { it.getKey() to it.getValue().getValuesList().map { JdbcTableConstraintsTemporalRuleFromProto(it) } }),
    snapshotUniqueKeys = strictMap(value.getSnapshotUniqueKeysList().map { it.getKey() to it.getValue().getValuesList().map { SnapshotUniqueKeyRuleFromProto(it) } }),
    requestedBy = value.getRequestedBy()
)

internal fun WarehouseDiscoverySnapshotRecordToProto(value: com.olap.prototype.management.warehouse.WarehouseDiscoverySnapshotRecord): com.olap.prototype.wire.governance.WarehouseDiscoverySnapshotRecord = com.olap.prototype.wire.governance.WarehouseDiscoverySnapshotRecord.newBuilder().apply {
    setId(value.id)
    setDataSource(value.dataSource)
    setDialectId(value.dialectId)
    setSourceIdentityHash(value.sourceIdentityHash)
    setCatalogName(value.catalogName)
    setSchemaName(value.schemaName)
    value.databaseProductName?.let { setDatabaseProductName(it) }
    value.databaseProductVersion?.let { setDatabaseProductVersion(it) }
    value.driverName?.let { setDriverName(it) }
    value.driverVersion?.let { setDriverVersion(it) }
    value.readOnly?.let { setReadOnly(it) }
    setStatus(value.status)
    setTableCount(value.tableCount)
    value.errorMessage?.let { setErrorMessage(it) }
    setRequestedBy(value.requestedBy)
    setRequestedAt(value.requestedAt.toString())
    setDiscoveredAt(value.discoveredAt.toString())
    value.structureProvenance?.let { setStructureProvenance(StructureProvenanceToProto(it)) }
}.build()
internal fun WarehouseDiscoverySnapshotRecordFromProto(value: com.olap.prototype.wire.governance.WarehouseDiscoverySnapshotRecord): com.olap.prototype.management.warehouse.WarehouseDiscoverySnapshotRecord = com.olap.prototype.management.warehouse.WarehouseDiscoverySnapshotRecord(
    id = value.getId(),
    dataSource = value.getDataSource(),
    dialectId = value.getDialectId(),
    sourceIdentityHash = value.getSourceIdentityHash(),
    catalogName = value.getCatalogName(),
    schemaName = value.getSchemaName(),
    databaseProductName = if (value.hasDatabaseProductName()) value.getDatabaseProductName() else null,
    databaseProductVersion = if (value.hasDatabaseProductVersion()) value.getDatabaseProductVersion() else null,
    driverName = if (value.hasDriverName()) value.getDriverName() else null,
    driverVersion = if (value.hasDriverVersion()) value.getDriverVersion() else null,
    readOnly = if (value.hasReadOnly()) value.getReadOnly() else null,
    status = value.getStatus(),
    tableCount = value.getTableCount(),
    errorMessage = if (value.hasErrorMessage()) value.getErrorMessage() else null,
    requestedBy = value.getRequestedBy(),
    requestedAt = java.time.Instant.parse(value.getRequestedAt()),
    discoveredAt = java.time.Instant.parse(value.getDiscoveredAt()),
    structureProvenance = if (value.hasStructureProvenance()) StructureProvenanceFromProto(value.getStructureProvenance()) else null
)

internal fun WarehouseDiscoveredColumnRecordToProto(value: com.olap.prototype.management.warehouse.WarehouseDiscoveredColumnRecord): com.olap.prototype.wire.governance.WarehouseDiscoveredColumnRecord = com.olap.prototype.wire.governance.WarehouseDiscoveredColumnRecord.newBuilder().apply {
    setId(value.id)
    setDiscoveredTableId(value.discoveredTableId)
    setColumnName(value.columnName)
    setJdbcType(value.jdbcType)
    setTypeName(value.typeName)
    value.precision?.let { setPrecision(it) }
    value.scale?.let { setScale(it) }
    value.autoIncrement?.let { setAutoIncrement(it) }
    value.generated?.let { setGenerated(it) }
    value.defaultValue?.let { setDefaultValue(it) }
    value.columnComment?.let { setColumnComment(it) }
    value.nullable?.let { setNullable(it) }
    value.ordinalPosition?.let { setOrdinalPosition(it) }
    setCreatedAt(value.createdAt.toString())
}.build()
internal fun WarehouseDiscoveredColumnRecordFromProto(value: com.olap.prototype.wire.governance.WarehouseDiscoveredColumnRecord): com.olap.prototype.management.warehouse.WarehouseDiscoveredColumnRecord = com.olap.prototype.management.warehouse.WarehouseDiscoveredColumnRecord(
    id = value.getId(),
    discoveredTableId = value.getDiscoveredTableId(),
    columnName = value.getColumnName(),
    jdbcType = value.getJdbcType(),
    typeName = value.getTypeName(),
    precision = if (value.hasPrecision()) value.getPrecision() else null,
    scale = if (value.hasScale()) value.getScale() else null,
    autoIncrement = if (value.hasAutoIncrement()) value.getAutoIncrement() else null,
    generated = if (value.hasGenerated()) value.getGenerated() else null,
    defaultValue = if (value.hasDefaultValue()) value.getDefaultValue() else null,
    columnComment = if (value.hasColumnComment()) value.getColumnComment() else null,
    nullable = if (value.hasNullable()) value.getNullable() else null,
    ordinalPosition = if (value.hasOrdinalPosition()) value.getOrdinalPosition() else null,
    createdAt = java.time.Instant.parse(value.getCreatedAt())
)

internal fun WarehouseDiscoveredUniqueKeyRecordToProto(value: com.olap.prototype.management.warehouse.WarehouseDiscoveredUniqueKeyRecord): com.olap.prototype.wire.governance.WarehouseDiscoveredUniqueKeyRecord = com.olap.prototype.wire.governance.WarehouseDiscoveredUniqueKeyRecord.newBuilder().apply {
    setId(value.id)
    setDiscoveredTableId(value.discoveredTableId)
    setKey(SnapshotUniqueKeyToProto(value.key))
}.build()
internal fun WarehouseDiscoveredUniqueKeyRecordFromProto(value: com.olap.prototype.wire.governance.WarehouseDiscoveredUniqueKeyRecord): com.olap.prototype.management.warehouse.WarehouseDiscoveredUniqueKeyRecord = com.olap.prototype.management.warehouse.WarehouseDiscoveredUniqueKeyRecord(
    id = value.getId(),
    discoveredTableId = value.getDiscoveredTableId(),
    key = required(value.hasKey(), "WarehouseDiscoveredUniqueKeyRecord.key") { SnapshotUniqueKeyFromProto(value.getKey()) }
)

internal fun SourceConfigurationParametersToProto(value: com.olap.prototype.warehouse.contract.SourceConfigurationParameters): com.olap.prototype.wire.governance.SourceConfigurationParameters = com.olap.prototype.wire.governance.SourceConfigurationParameters.newBuilder().apply {
    addAllIdentityNames(value.identityNames.map { it }.sorted())
    addAllRuntimeNames(value.runtimeNames.map { it }.sorted())
}.build()
internal fun SourceConfigurationParametersFromProto(value: com.olap.prototype.wire.governance.SourceConfigurationParameters): com.olap.prototype.warehouse.contract.SourceConfigurationParameters = com.olap.prototype.warehouse.contract.SourceConfigurationParameters(
    identityNames = strictSet(value.getIdentityNamesList().map { it }),
    runtimeNames = strictSet(value.getRuntimeNamesList().map { it })
)

internal fun WarehouseRelationshipProfileRequestInputToProto(value: com.olap.prototype.warehouse.inspection.WarehouseRelationshipProfileRequest.Input): com.olap.prototype.wire.governance.WarehouseRelationshipProfileRequestInput = com.olap.prototype.wire.governance.WarehouseRelationshipProfileRequestInput.newBuilder().apply {
    setTable(WarehouseTableRefToProto(value.table))
    addAllColumns(value.columns.map { WarehouseRelationshipProfileRequestColumnToProto(it) })
    addAllKeyExpressions(value.keyExpressions.map { SemanticProto.toProto(it) })
}.build()
internal fun WarehouseRelationshipProfileRequestInputFromProto(value: com.olap.prototype.wire.governance.WarehouseRelationshipProfileRequestInput): com.olap.prototype.warehouse.inspection.WarehouseRelationshipProfileRequest.Input = com.olap.prototype.warehouse.inspection.WarehouseRelationshipProfileRequest.Input(
    table = required(value.hasTable(), "WarehouseRelationshipProfileRequestInput.table") { WarehouseTableRefFromProto(value.getTable()) },
    columns = value.getColumnsList().map { WarehouseRelationshipProfileRequestColumnFromProto(it) },
    keyExpressions = value.getKeyExpressionsList().map { SemanticProto.fromProto(it) as com.olap.prototype.expression.SemanticExpression }
)

internal fun WarehouseRelationshipProfileRequestColumnToProto(value: com.olap.prototype.warehouse.inspection.WarehouseRelationshipProfileRequest.Column): com.olap.prototype.wire.governance.WarehouseRelationshipProfileRequestColumn = com.olap.prototype.wire.governance.WarehouseRelationshipProfileRequestColumn.newBuilder().apply {
    setPhysicalColumnId(value.physicalColumnId)
    setPhysicalColumnVersion(value.physicalColumnVersion)
    setColumn(value.column)
}.build()
internal fun WarehouseRelationshipProfileRequestColumnFromProto(value: com.olap.prototype.wire.governance.WarehouseRelationshipProfileRequestColumn): com.olap.prototype.warehouse.inspection.WarehouseRelationshipProfileRequest.Column = com.olap.prototype.warehouse.inspection.WarehouseRelationshipProfileRequest.Column(
    physicalColumnId = value.getPhysicalColumnId(),
    physicalColumnVersion = value.getPhysicalColumnVersion(),
    column = value.getColumn()
)

internal fun WarehouseDiscoveryResultToProto(value: com.olap.prototype.warehouse.inspection.WarehouseDiscoveryResult): com.olap.prototype.wire.governance.WarehouseDiscoveryResult = com.olap.prototype.wire.governance.WarehouseDiscoveryResult.newBuilder().apply {
    setSource(WarehouseSourceMetadataToProto(value.source))
    setReturnedTableCount(value.returnedTableCount)
    setTruncated(value.truncated)
    addAllTables(value.tables.map { WarehouseTableSummaryToProto(it) })
}.build()
internal fun WarehouseDiscoveryResultFromProto(value: com.olap.prototype.wire.governance.WarehouseDiscoveryResult): com.olap.prototype.warehouse.inspection.WarehouseDiscoveryResult = com.olap.prototype.warehouse.inspection.WarehouseDiscoveryResult(
    source = required(value.hasSource(), "WarehouseDiscoveryResult.source") { WarehouseSourceMetadataFromProto(value.getSource()) },
    returnedTableCount = value.getReturnedTableCount(),
    truncated = value.getTruncated(),
    tables = value.getTablesList().map { WarehouseTableSummaryFromProto(it) }
)

internal fun WarehouseSourceMetadataToProto(value: com.olap.prototype.warehouse.inspection.WarehouseSourceMetadata): com.olap.prototype.wire.governance.WarehouseSourceMetadata = com.olap.prototype.wire.governance.WarehouseSourceMetadata.newBuilder().apply {
    setDataSourceId(value.dataSourceId)
    setDialectId(value.dialectId)
    setSourceIdentityHash(value.sourceIdentityHash)
    value.databaseProductName?.let { setDatabaseProductName(it) }
    value.databaseProductVersion?.let { setDatabaseProductVersion(it) }
    value.driverName?.let { setDriverName(it) }
    value.driverVersion?.let { setDriverVersion(it) }
    value.userName?.let { setUserName(it) }
    value.readOnly?.let { setReadOnly(it) }
    setStructureProvenance(StructureProvenanceToProto(value.structureProvenance))
}.build()
internal fun WarehouseSourceMetadataFromProto(value: com.olap.prototype.wire.governance.WarehouseSourceMetadata): com.olap.prototype.warehouse.inspection.WarehouseSourceMetadata = com.olap.prototype.warehouse.inspection.WarehouseSourceMetadata(
    dataSourceId = value.getDataSourceId(),
    dialectId = value.getDialectId(),
    sourceIdentityHash = value.getSourceIdentityHash(),
    databaseProductName = if (value.hasDatabaseProductName()) value.getDatabaseProductName() else null,
    databaseProductVersion = if (value.hasDatabaseProductVersion()) value.getDatabaseProductVersion() else null,
    driverName = if (value.hasDriverName()) value.getDriverName() else null,
    driverVersion = if (value.hasDriverVersion()) value.getDriverVersion() else null,
    userName = if (value.hasUserName()) value.getUserName() else null,
    readOnly = if (value.hasReadOnly()) value.getReadOnly() else null,
    structureProvenance = required(value.hasStructureProvenance(), "WarehouseSourceMetadata.structureProvenance") { StructureProvenanceFromProto(value.getStructureProvenance()) }
)

internal fun WarehouseTableMetadataToProto(value: com.olap.prototype.warehouse.inspection.WarehouseTableMetadata): com.olap.prototype.wire.governance.WarehouseTableMetadata = com.olap.prototype.wire.governance.WarehouseTableMetadata.newBuilder().apply {
    setSource(WarehouseSourceMetadataToProto(value.source))
    setTable(WarehouseTableSummaryToProto(value.table))
    addAllColumns(value.columns.map { WarehouseColumnMetadataToProto(it) })
    setConstraints(JdbcTableConstraintsToProto(value.constraints))
}.build()
internal fun WarehouseTableMetadataFromProto(value: com.olap.prototype.wire.governance.WarehouseTableMetadata): com.olap.prototype.warehouse.inspection.WarehouseTableMetadata = com.olap.prototype.warehouse.inspection.WarehouseTableMetadata(
    source = required(value.hasSource(), "WarehouseTableMetadata.source") { WarehouseSourceMetadataFromProto(value.getSource()) },
    table = required(value.hasTable(), "WarehouseTableMetadata.table") { WarehouseTableSummaryFromProto(value.getTable()) },
    columns = value.getColumnsList().map { WarehouseColumnMetadataFromProto(it) },
    constraints = required(value.hasConstraints(), "WarehouseTableMetadata.constraints") { JdbcTableConstraintsFromProto(value.getConstraints()) }
)

internal fun WarehouseSchemaSnapshotToProto(value: com.olap.prototype.warehouse.inspection.WarehouseSchemaSnapshot): com.olap.prototype.wire.governance.WarehouseSchemaSnapshot = com.olap.prototype.wire.governance.WarehouseSchemaSnapshot.newBuilder().apply {
    setSource(WarehouseSourceMetadataToProto(value.source))
    setTruncated(value.truncated)
    addAllTables(value.tables.map { WarehouseTableMetadataToProto(it) })
}.build()
internal fun WarehouseSchemaSnapshotFromProto(value: com.olap.prototype.wire.governance.WarehouseSchemaSnapshot): com.olap.prototype.warehouse.inspection.WarehouseSchemaSnapshot = com.olap.prototype.warehouse.inspection.WarehouseSchemaSnapshot(
    source = required(value.hasSource(), "WarehouseSchemaSnapshot.source") { WarehouseSourceMetadataFromProto(value.getSource()) },
    truncated = value.getTruncated(),
    tables = value.getTablesList().map { WarehouseTableMetadataFromProto(it) }
)

internal fun WarehousePreviewToProto(value: com.olap.prototype.warehouse.inspection.WarehousePreview): com.olap.prototype.wire.governance.WarehousePreview = com.olap.prototype.wire.governance.WarehousePreview.newBuilder().apply {
    setSource(WarehouseSourceMetadataToProto(value.source))
    setTable(WarehouseTableSummaryToProto(value.table))
    addAllColumns(value.columns.map { WarehouseColumnMetadataToProto(it) })
    setRowLimit(value.rowLimit)
    addAllRows(value.rows.map { WarehouseRowToProto(it) })
}.build()
internal fun WarehousePreviewFromProto(value: com.olap.prototype.wire.governance.WarehousePreview): com.olap.prototype.warehouse.inspection.WarehousePreview = com.olap.prototype.warehouse.inspection.WarehousePreview(
    source = required(value.hasSource(), "WarehousePreview.source") { WarehouseSourceMetadataFromProto(value.getSource()) },
    table = required(value.hasTable(), "WarehousePreview.table") { WarehouseTableSummaryFromProto(value.getTable()) },
    columns = value.getColumnsList().map { WarehouseColumnMetadataFromProto(it) },
    rowLimit = value.getRowLimit(),
    rows = value.getRowsList().map { WarehouseRowFromProto(it) }
)

internal fun WarehouseRowToProto(value: com.olap.prototype.warehouse.inspection.WarehouseRow): com.olap.prototype.wire.governance.WarehouseRow = com.olap.prototype.wire.governance.WarehouseRow.newBuilder().apply {
    addAllCells(value.cells.map { WarehouseCellToProto(it) })
}.build()
internal fun WarehouseRowFromProto(value: com.olap.prototype.wire.governance.WarehouseRow): com.olap.prototype.warehouse.inspection.WarehouseRow = com.olap.prototype.warehouse.inspection.WarehouseRow(
    cells = value.getCellsList().map { WarehouseCellFromProto(it) }
)

internal fun WarehouseCellToProto(value: com.olap.prototype.warehouse.inspection.WarehouseCell): com.olap.prototype.wire.governance.WarehouseCell = com.olap.prototype.wire.governance.WarehouseCell.newBuilder().apply {
    setColumnName(value.columnName)
    setJdbcType(value.jdbcType)
    value.typeName?.let { setTypeName(it) }
    setValue(WarehouseScalarToProto(value.value))
}.build()
internal fun WarehouseCellFromProto(value: com.olap.prototype.wire.governance.WarehouseCell): com.olap.prototype.warehouse.inspection.WarehouseCell = com.olap.prototype.warehouse.inspection.WarehouseCell(
    columnName = value.getColumnName(),
    jdbcType = value.getJdbcType(),
    typeName = if (value.hasTypeName()) value.getTypeName() else null,
    value = required(value.hasValue(), "WarehouseCell.value") { WarehouseScalarFromProto(value.getValue()) }
)

internal fun WarehouseScalarToProto(value: com.olap.prototype.warehouse.inspection.WarehouseScalar): com.olap.prototype.wire.governance.WarehouseScalar = com.olap.prototype.wire.governance.WarehouseScalar.newBuilder().apply {
    setKind(value.kind.name)
    value.value?.let { setValue(it) }
    setTruncated(value.truncated)
}.build()
internal fun WarehouseScalarFromProto(value: com.olap.prototype.wire.governance.WarehouseScalar): com.olap.prototype.warehouse.inspection.WarehouseScalar = com.olap.prototype.warehouse.inspection.WarehouseScalar(
    kind = com.olap.prototype.warehouse.inspection.WarehouseScalar.Kind.valueOf(value.getKind()),
    value = if (value.hasValue()) value.getValue() else null,
    truncated = value.getTruncated()
)

internal fun WarehouseProfileToProto(value: com.olap.prototype.warehouse.inspection.WarehouseProfile): com.olap.prototype.wire.governance.WarehouseProfile = com.olap.prototype.wire.governance.WarehouseProfile.newBuilder().apply {
    setSource(WarehouseSourceMetadataToProto(value.source))
    setTable(WarehouseTableSummaryToProto(value.table))
    setStartedAt(value.startedAt.toString())
    setCompletedAt(value.completedAt.toString())
    setRowCount(value.rowCount)
    addAllColumns(value.columns.map { WarehouseProfileColumnCountToProto(it) })
}.build()
internal fun WarehouseProfileFromProto(value: com.olap.prototype.wire.governance.WarehouseProfile): com.olap.prototype.warehouse.inspection.WarehouseProfile = com.olap.prototype.warehouse.inspection.WarehouseProfile(
    source = required(value.hasSource(), "WarehouseProfile.source") { WarehouseSourceMetadataFromProto(value.getSource()) },
    table = required(value.hasTable(), "WarehouseProfile.table") { WarehouseTableSummaryFromProto(value.getTable()) },
    startedAt = java.time.Instant.parse(value.getStartedAt()),
    completedAt = java.time.Instant.parse(value.getCompletedAt()),
    rowCount = value.getRowCount(),
    columns = value.getColumnsList().map { WarehouseProfileColumnCountFromProto(it) }
)

internal fun WarehouseProfileColumnCountToProto(value: com.olap.prototype.warehouse.inspection.WarehouseProfile.ColumnCount): com.olap.prototype.wire.governance.WarehouseProfileColumnCount = com.olap.prototype.wire.governance.WarehouseProfileColumnCount.newBuilder().apply {
    setColumn(value.column)
    setNonNullCount(value.nonNullCount)
    setNullCount(value.nullCount)
}.build()
internal fun WarehouseProfileColumnCountFromProto(value: com.olap.prototype.wire.governance.WarehouseProfileColumnCount): com.olap.prototype.warehouse.inspection.WarehouseProfile.ColumnCount = com.olap.prototype.warehouse.inspection.WarehouseProfile.ColumnCount(
    column = value.getColumn(),
    nonNullCount = value.getNonNullCount(),
    nullCount = value.getNullCount()
)

internal fun WarehouseRelationshipProfileToProto(value: com.olap.prototype.warehouse.inspection.WarehouseRelationshipProfile): com.olap.prototype.wire.governance.WarehouseRelationshipProfile = com.olap.prototype.wire.governance.WarehouseRelationshipProfile.newBuilder().apply {
    setSource(WarehouseSourceMetadataToProto(value.source))
    setSourceInput(WarehouseRelationshipProfileRequestInputToProto(value.sourceInput))
    setTargetInput(WarehouseRelationshipProfileRequestInputToProto(value.targetInput))
    setStartedAt(value.startedAt.toString())
    setCompletedAt(value.completedAt.toString())
    setEqualitySemantics(value.equalitySemantics)
    setSourceToTarget(WarehouseRelationshipProfileDirectionToProto(value.sourceToTarget))
    setTargetToSource(WarehouseRelationshipProfileDirectionToProto(value.targetToSource))
}.build()
internal fun WarehouseRelationshipProfileFromProto(value: com.olap.prototype.wire.governance.WarehouseRelationshipProfile): com.olap.prototype.warehouse.inspection.WarehouseRelationshipProfile = com.olap.prototype.warehouse.inspection.WarehouseRelationshipProfile(
    source = required(value.hasSource(), "WarehouseRelationshipProfile.source") { WarehouseSourceMetadataFromProto(value.getSource()) },
    sourceInput = required(value.hasSourceInput(), "WarehouseRelationshipProfile.sourceInput") { WarehouseRelationshipProfileRequestInputFromProto(value.getSourceInput()) },
    targetInput = required(value.hasTargetInput(), "WarehouseRelationshipProfile.targetInput") { WarehouseRelationshipProfileRequestInputFromProto(value.getTargetInput()) },
    startedAt = java.time.Instant.parse(value.getStartedAt()),
    completedAt = java.time.Instant.parse(value.getCompletedAt()),
    equalitySemantics = value.getEqualitySemantics(),
    sourceToTarget = required(value.hasSourceToTarget(), "WarehouseRelationshipProfile.sourceToTarget") { WarehouseRelationshipProfileDirectionFromProto(value.getSourceToTarget()) },
    targetToSource = required(value.hasTargetToSource(), "WarehouseRelationshipProfile.targetToSource") { WarehouseRelationshipProfileDirectionFromProto(value.getTargetToSource()) }
)

internal fun WarehouseRelationshipProfileDirectionToProto(value: com.olap.prototype.warehouse.inspection.WarehouseRelationshipProfile.Direction): com.olap.prototype.wire.governance.WarehouseRelationshipProfileDirection = com.olap.prototype.wire.governance.WarehouseRelationshipProfileDirection.newBuilder().apply {
    setRowCount(value.rowCount)
    setNullKeyRowCount(value.nullKeyRowCount)
    setNonNullKeyGroupCount(value.nonNullKeyGroupCount)
    setDuplicateNonNullKeyGroupCount(value.duplicateNonNullKeyGroupCount)
    setRowsWithDuplicatedNonNullKeys(value.rowsWithDuplicatedNonNullKeys)
    setUnmatchedRowCount(value.unmatchedRowCount)
    setSingleMatchRowCount(value.singleMatchRowCount)
    setMultipleMatchRowCount(value.multipleMatchRowCount)
    setMatchedPairCount(value.matchedPairCount)
    setMaximumMatchesPerRow(value.maximumMatchesPerRow)
}.build()
internal fun WarehouseRelationshipProfileDirectionFromProto(value: com.olap.prototype.wire.governance.WarehouseRelationshipProfileDirection): com.olap.prototype.warehouse.inspection.WarehouseRelationshipProfile.Direction = com.olap.prototype.warehouse.inspection.WarehouseRelationshipProfile.Direction(
    rowCount = value.getRowCount(),
    nullKeyRowCount = value.getNullKeyRowCount(),
    nonNullKeyGroupCount = value.getNonNullKeyGroupCount(),
    duplicateNonNullKeyGroupCount = value.getDuplicateNonNullKeyGroupCount(),
    rowsWithDuplicatedNonNullKeys = value.getRowsWithDuplicatedNonNullKeys(),
    unmatchedRowCount = value.getUnmatchedRowCount(),
    singleMatchRowCount = value.getSingleMatchRowCount(),
    multipleMatchRowCount = value.getMultipleMatchRowCount(),
    matchedPairCount = value.getMatchedPairCount(),
    maximumMatchesPerRow = value.getMaximumMatchesPerRow()
)

internal fun SourceDatasetRegistrationRequestToProto(value: com.olap.prototype.warehouse.onboarding.SourceDatasetRegistrationRequest): com.olap.prototype.wire.governance.SourceDatasetRegistrationRequest = com.olap.prototype.wire.governance.SourceDatasetRegistrationRequest.newBuilder().apply {
    setDataSourceId(value.dataSourceId)
    setSourceIdentityHash(value.sourceIdentityHash)
    setDeclaration(SealedSourceDatasetToProto(value.declaration))
}.build()
internal fun SourceDatasetRegistrationRequestFromProto(value: com.olap.prototype.wire.governance.SourceDatasetRegistrationRequest): com.olap.prototype.warehouse.onboarding.SourceDatasetRegistrationRequest = com.olap.prototype.warehouse.onboarding.SourceDatasetRegistrationRequest(
    dataSourceId = value.getDataSourceId(),
    sourceIdentityHash = value.getSourceIdentityHash(),
    declaration = required(value.hasDeclaration(), "SourceDatasetRegistrationRequest.declaration") { SealedSourceDatasetFromProto(value.getDeclaration()) }
)

internal fun SealedSourceDatasetToProto(value: com.olap.prototype.warehouse.contract.SealedSourceDataset): com.olap.prototype.wire.governance.SealedSourceDataset = com.olap.prototype.wire.governance.SealedSourceDataset.newBuilder().apply {
    setFormatVersion(value.formatVersion)
    setSourceAuthority(value.sourceAuthority)
    setRuleDocumentSha256(value.ruleDocumentSha256)
    setAnchor(JdbcTableConstraintsDataAnchorToProto(value.anchor))
    setScope(SealedSourceDatasetScopeToProto(value.scope))
    addAllCollections(value.collections.map { SealedSourceDatasetCollectionToProto(it) })
}.build()
internal fun SealedSourceDatasetFromProto(value: com.olap.prototype.wire.governance.SealedSourceDataset): com.olap.prototype.warehouse.contract.SealedSourceDataset = com.olap.prototype.warehouse.contract.SealedSourceDataset(
    formatVersion = value.getFormatVersion(),
    sourceAuthority = value.getSourceAuthority(),
    ruleDocumentSha256 = value.getRuleDocumentSha256(),
    anchor = required(value.hasAnchor(), "SealedSourceDataset.anchor") { JdbcTableConstraintsDataAnchorFromProto(value.getAnchor()) },
    scope = required(value.hasScope(), "SealedSourceDataset.scope") { SealedSourceDatasetScopeFromProto(value.getScope()) },
    collections = value.getCollectionsList().map { SealedSourceDatasetCollectionFromProto(it) }
)
