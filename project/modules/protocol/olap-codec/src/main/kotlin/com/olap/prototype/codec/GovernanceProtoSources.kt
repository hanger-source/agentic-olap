package com.olap.prototype.codec

/** 正式领域消息的显式字段转换。 */
internal fun EmptyRequestToProto(value: com.olap.prototype.source.contract.EmptyRequest): com.olap.prototype.wire.governance.EmptyRequest = com.olap.prototype.wire.governance.EmptyRequest.getDefaultInstance()
internal fun EmptyRequestFromProto(value: com.olap.prototype.wire.governance.EmptyRequest): com.olap.prototype.source.contract.EmptyRequest = com.olap.prototype.source.contract.EmptyRequest

internal fun AvailableSourcesToProto(value: com.olap.prototype.source.contract.AvailableSources): com.olap.prototype.wire.governance.AvailableSources = com.olap.prototype.wire.governance.AvailableSources.newBuilder().apply {
    addAllItems(value.items.map { AvailableSourceToProto(it) })
}.build()
internal fun AvailableSourcesFromProto(value: com.olap.prototype.wire.governance.AvailableSources): com.olap.prototype.source.contract.AvailableSources = com.olap.prototype.source.contract.AvailableSources(
    items = value.getItemsList().map { AvailableSourceFromProto(it) }
)

internal fun AvailableSourceToProto(value: com.olap.prototype.source.contract.AvailableSource): com.olap.prototype.wire.governance.AvailableSource = com.olap.prototype.wire.governance.AvailableSource.newBuilder().apply {
    setBinding(QuerySourceProto.toProto(value.binding))
    setConnectorRef(SemanticProto.toProto(value.connectorRef))
    setDialectId(value.dialectId)
    addAllAccessCapabilities(value.accessCapabilities.map { it }.sorted())
}.build()
internal fun AvailableSourceFromProto(value: com.olap.prototype.wire.governance.AvailableSource): com.olap.prototype.source.contract.AvailableSource = com.olap.prototype.source.contract.AvailableSource(
    binding = required(value.hasBinding(), "AvailableSource.binding") { QuerySourceProto.fromProto(value.getBinding()) as com.olap.prototype.warehouse.contract.SourceBindingRef },
    connectorRef = required(value.hasConnectorRef(), "AvailableSource.connectorRef") { SemanticProto.fromProto(value.getConnectorRef()) as com.olap.prototype.connector.ConnectorRef },
    dialectId = value.getDialectId(),
    accessCapabilities = strictSet(value.getAccessCapabilitiesList().map { it })
)

internal fun ConnectorCapabilitiesRequestToProto(value: com.olap.prototype.source.contract.ConnectorCapabilitiesRequest): com.olap.prototype.wire.governance.ConnectorCapabilitiesRequest = com.olap.prototype.wire.governance.ConnectorCapabilitiesRequest.newBuilder().apply {
    value.aggregate?.let { setAggregate(ConnectorCapabilitiesRequestAggregateToProto(it)) }
}.build()
internal fun ConnectorCapabilitiesRequestFromProto(value: com.olap.prototype.wire.governance.ConnectorCapabilitiesRequest): com.olap.prototype.source.contract.ConnectorCapabilitiesRequest = com.olap.prototype.source.contract.ConnectorCapabilitiesRequest(
    aggregate = if (value.hasAggregate()) ConnectorCapabilitiesRequestAggregateFromProto(value.getAggregate()) else null
)

internal fun ConnectorCapabilitiesRequestAggregateToProto(value: com.olap.prototype.source.contract.ConnectorCapabilitiesRequest.Aggregate): com.olap.prototype.wire.governance.ConnectorCapabilitiesRequestAggregate = com.olap.prototype.wire.governance.ConnectorCapabilitiesRequestAggregate.newBuilder().apply {
    setConnectorRef(SemanticProto.toProto(value.connectorRef))
    setAggregation(value.aggregation.name)
    setInputType(NativeValueTypeToProto(value.inputType))
    setGrouped(value.grouped)
}.build()
internal fun ConnectorCapabilitiesRequestAggregateFromProto(value: com.olap.prototype.wire.governance.ConnectorCapabilitiesRequestAggregate): com.olap.prototype.source.contract.ConnectorCapabilitiesRequest.Aggregate = com.olap.prototype.source.contract.ConnectorCapabilitiesRequest.Aggregate(
    connectorRef = required(value.hasConnectorRef(), "ConnectorCapabilitiesRequestAggregate.connectorRef") { SemanticProto.fromProto(value.getConnectorRef()) as com.olap.prototype.connector.ConnectorRef },
    aggregation = com.olap.prototype.query.ResultAggregation.Kind.valueOf(value.getAggregation()),
    inputType = required(value.hasInputType(), "ConnectorCapabilitiesRequestAggregate.inputType") { NativeValueTypeFromProto(value.getInputType()) },
    grouped = value.getGrouped()
)

internal fun ConnectorCapabilitiesToProto(value: com.olap.prototype.source.contract.ConnectorCapabilities): com.olap.prototype.wire.governance.ConnectorCapabilities = com.olap.prototype.wire.governance.ConnectorCapabilities.newBuilder().apply {
    setApiVersion(value.apiVersion)
    addAllConnectors(value.connectors.map { ConnectorCapabilitiesConnectorToProto(it) })
    value.aggregate?.let { setAggregate(ConnectorCapabilitiesAggregateToProto(it)) }
}.build()
internal fun ConnectorCapabilitiesFromProto(value: com.olap.prototype.wire.governance.ConnectorCapabilities): com.olap.prototype.source.contract.ConnectorCapabilities = com.olap.prototype.source.contract.ConnectorCapabilities(
    apiVersion = value.getApiVersion(),
    connectors = value.getConnectorsList().map { ConnectorCapabilitiesConnectorFromProto(it) },
    aggregate = if (value.hasAggregate()) ConnectorCapabilitiesAggregateFromProto(value.getAggregate()) else null
)

internal fun ConnectorCapabilitiesConnectorToProto(value: com.olap.prototype.source.contract.ConnectorCapabilities.Connector): com.olap.prototype.wire.governance.ConnectorCapabilitiesConnector = com.olap.prototype.wire.governance.ConnectorCapabilitiesConnector.newBuilder().apply {
    setConnectorRef(SemanticProto.toProto(value.connectorRef))
    addAllNativeScalarFunctions(value.nativeScalarFunctions.map { NativeScalarFunctionToProto(it) })
    addAllCompilationCapabilities(value.compilationCapabilities.map { it }.sorted())
    setParameterSubmission(value.parameterSubmission)
    setConfigurationParameters(SourceConfigurationParametersToProto(value.configurationParameters))
}.build()
internal fun ConnectorCapabilitiesConnectorFromProto(value: com.olap.prototype.wire.governance.ConnectorCapabilitiesConnector): com.olap.prototype.source.contract.ConnectorCapabilities.Connector = com.olap.prototype.source.contract.ConnectorCapabilities.Connector(
    connectorRef = required(value.hasConnectorRef(), "ConnectorCapabilitiesConnector.connectorRef") { SemanticProto.fromProto(value.getConnectorRef()) as com.olap.prototype.connector.ConnectorRef },
    nativeScalarFunctions = value.getNativeScalarFunctionsList().map { NativeScalarFunctionFromProto(it) },
    compilationCapabilities = strictSet(value.getCompilationCapabilitiesList().map { it }),
    parameterSubmission = value.getParameterSubmission(),
    configurationParameters = required(value.hasConfigurationParameters(), "ConnectorCapabilitiesConnector.configurationParameters") { SourceConfigurationParametersFromProto(value.getConfigurationParameters()) }
)

internal fun ConnectorCapabilitiesAggregateToProto(value: com.olap.prototype.source.contract.ConnectorCapabilities.Aggregate): com.olap.prototype.wire.governance.ConnectorCapabilitiesAggregate = com.olap.prototype.wire.governance.ConnectorCapabilitiesAggregate.newBuilder().apply {
    setConnectorRef(SemanticProto.toProto(value.connectorRef))
    setAggregation(value.aggregation.name)
    setInputType(NativeValueTypeToProto(value.inputType))
    setGrouped(value.grouped)
    setMapping(NativeAggregateMappingToProto(value.mapping))
}.build()
internal fun ConnectorCapabilitiesAggregateFromProto(value: com.olap.prototype.wire.governance.ConnectorCapabilitiesAggregate): com.olap.prototype.source.contract.ConnectorCapabilities.Aggregate = com.olap.prototype.source.contract.ConnectorCapabilities.Aggregate(
    connectorRef = required(value.hasConnectorRef(), "ConnectorCapabilitiesAggregate.connectorRef") { SemanticProto.fromProto(value.getConnectorRef()) as com.olap.prototype.connector.ConnectorRef },
    aggregation = com.olap.prototype.query.ResultAggregation.Kind.valueOf(value.getAggregation()),
    inputType = required(value.hasInputType(), "ConnectorCapabilitiesAggregate.inputType") { NativeValueTypeFromProto(value.getInputType()) },
    grouped = value.getGrouped(),
    mapping = required(value.hasMapping(), "ConnectorCapabilitiesAggregate.mapping") { NativeAggregateMappingFromProto(value.getMapping()) }
)

internal fun SourceInspectionToProto(value: com.olap.prototype.source.contract.SourceInspection): com.olap.prototype.wire.governance.SourceInspection = com.olap.prototype.wire.governance.SourceInspection.newBuilder().apply { when (value) {
    is com.olap.prototype.source.contract.SourceInspection.Capabilities -> setCapabilities(SourceInspectionCapabilitiesToProto(value))
    is com.olap.prototype.source.contract.SourceInspection.Discover -> setDiscover(SourceInspectionDiscoverToProto(value))
    is com.olap.prototype.source.contract.SourceInspection.Describe -> setDescribe(SourceInspectionDescribeToProto(value))
    is com.olap.prototype.source.contract.SourceInspection.Snapshot -> setSnapshot(SourceInspectionSnapshotToProto(value))
    is com.olap.prototype.source.contract.SourceInspection.Preview -> setPreview(SourceInspectionPreviewToProto(value))
    is com.olap.prototype.source.contract.SourceInspection.Profile -> setProfile(SourceInspectionProfileToProto(value))
    is com.olap.prototype.source.contract.SourceInspection.Relationship -> setRelationship(SourceInspectionRelationshipToProto(value))
} }.build()
internal fun SourceInspectionFromProto(value: com.olap.prototype.wire.governance.SourceInspection): com.olap.prototype.source.contract.SourceInspection = when (value.kindCase) {
    com.olap.prototype.wire.governance.SourceInspection.KindCase.CAPABILITIES -> SourceInspectionCapabilitiesFromProto(value.getCapabilities())
    com.olap.prototype.wire.governance.SourceInspection.KindCase.DISCOVER -> SourceInspectionDiscoverFromProto(value.getDiscover())
    com.olap.prototype.wire.governance.SourceInspection.KindCase.DESCRIBE -> SourceInspectionDescribeFromProto(value.getDescribe())
    com.olap.prototype.wire.governance.SourceInspection.KindCase.SNAPSHOT -> SourceInspectionSnapshotFromProto(value.getSnapshot())
    com.olap.prototype.wire.governance.SourceInspection.KindCase.PREVIEW -> SourceInspectionPreviewFromProto(value.getPreview())
    com.olap.prototype.wire.governance.SourceInspection.KindCase.PROFILE -> SourceInspectionProfileFromProto(value.getProfile())
    com.olap.prototype.wire.governance.SourceInspection.KindCase.RELATIONSHIP -> SourceInspectionRelationshipFromProto(value.getRelationship())
    com.olap.prototype.wire.governance.SourceInspection.KindCase.KIND_NOT_SET -> error("SourceInspection variant is required")
}

internal fun SourceInspectionCapabilitiesToProto(value: com.olap.prototype.source.contract.SourceInspection.Capabilities): com.olap.prototype.wire.governance.SourceInspectionCapabilities = com.olap.prototype.wire.governance.SourceInspectionCapabilities.getDefaultInstance()
internal fun SourceInspectionCapabilitiesFromProto(value: com.olap.prototype.wire.governance.SourceInspectionCapabilities): com.olap.prototype.source.contract.SourceInspection.Capabilities = com.olap.prototype.source.contract.SourceInspection.Capabilities

internal fun SourceInspectionDiscoverToProto(value: com.olap.prototype.source.contract.SourceInspection.Discover): com.olap.prototype.wire.governance.SourceInspectionDiscover = com.olap.prototype.wire.governance.SourceInspectionDiscover.newBuilder().apply {
    setBinding(QuerySourceProto.toProto(value.binding))
    value.catalog?.let { setCatalog(it) }
    value.schemaPattern?.let { setSchemaPattern(it) }
    value.tablePattern?.let { setTablePattern(it) }
    addAllTableTypes(value.tableTypes.map { it })
    value.limit?.let { setLimit(it) }
}.build()
internal fun SourceInspectionDiscoverFromProto(value: com.olap.prototype.wire.governance.SourceInspectionDiscover): com.olap.prototype.source.contract.SourceInspection.Discover = com.olap.prototype.source.contract.SourceInspection.Discover(
    binding = required(value.hasBinding(), "SourceInspectionDiscover.binding") { QuerySourceProto.fromProto(value.getBinding()) as com.olap.prototype.warehouse.contract.SourceBindingRef },
    catalog = if (value.hasCatalog()) value.getCatalog() else null,
    schemaPattern = if (value.hasSchemaPattern()) value.getSchemaPattern() else null,
    tablePattern = if (value.hasTablePattern()) value.getTablePattern() else null,
    tableTypes = value.getTableTypesList().map { it },
    limit = if (value.hasLimit()) value.getLimit() else null
)

internal fun SourceInspectionDescribeToProto(value: com.olap.prototype.source.contract.SourceInspection.Describe): com.olap.prototype.wire.governance.SourceInspectionDescribe = com.olap.prototype.wire.governance.SourceInspectionDescribe.newBuilder().apply {
    setBinding(QuerySourceProto.toProto(value.binding))
    setTable(WarehouseTableRefToProto(value.table))
}.build()
internal fun SourceInspectionDescribeFromProto(value: com.olap.prototype.wire.governance.SourceInspectionDescribe): com.olap.prototype.source.contract.SourceInspection.Describe = com.olap.prototype.source.contract.SourceInspection.Describe(
    binding = required(value.hasBinding(), "SourceInspectionDescribe.binding") { QuerySourceProto.fromProto(value.getBinding()) as com.olap.prototype.warehouse.contract.SourceBindingRef },
    table = required(value.hasTable(), "SourceInspectionDescribe.table") { WarehouseTableRefFromProto(value.getTable()) }
)

internal fun SourceInspectionSnapshotToProto(value: com.olap.prototype.source.contract.SourceInspection.Snapshot): com.olap.prototype.wire.governance.SourceInspectionSnapshot = com.olap.prototype.wire.governance.SourceInspectionSnapshot.newBuilder().apply {
    setBinding(QuerySourceProto.toProto(value.binding))
    value.catalog?.let { setCatalog(it) }
    setSchemaName(value.schemaName)
    value.tablePattern?.let { setTablePattern(it) }
    addAllTableTypes(value.tableTypes.map { it })
    value.limit?.let { setLimit(it) }
}.build()
internal fun SourceInspectionSnapshotFromProto(value: com.olap.prototype.wire.governance.SourceInspectionSnapshot): com.olap.prototype.source.contract.SourceInspection.Snapshot = com.olap.prototype.source.contract.SourceInspection.Snapshot(
    binding = required(value.hasBinding(), "SourceInspectionSnapshot.binding") { QuerySourceProto.fromProto(value.getBinding()) as com.olap.prototype.warehouse.contract.SourceBindingRef },
    catalog = if (value.hasCatalog()) value.getCatalog() else null,
    schemaName = value.getSchemaName(),
    tablePattern = if (value.hasTablePattern()) value.getTablePattern() else null,
    tableTypes = value.getTableTypesList().map { it },
    limit = if (value.hasLimit()) value.getLimit() else null
)

internal fun SourceInspectionPreviewToProto(value: com.olap.prototype.source.contract.SourceInspection.Preview): com.olap.prototype.wire.governance.SourceInspectionPreview = com.olap.prototype.wire.governance.SourceInspectionPreview.newBuilder().apply {
    setBinding(QuerySourceProto.toProto(value.binding))
    setTable(WarehouseTableRefToProto(value.table))
    value.limit?.let { setLimit(it) }
}.build()
internal fun SourceInspectionPreviewFromProto(value: com.olap.prototype.wire.governance.SourceInspectionPreview): com.olap.prototype.source.contract.SourceInspection.Preview = com.olap.prototype.source.contract.SourceInspection.Preview(
    binding = required(value.hasBinding(), "SourceInspectionPreview.binding") { QuerySourceProto.fromProto(value.getBinding()) as com.olap.prototype.warehouse.contract.SourceBindingRef },
    table = required(value.hasTable(), "SourceInspectionPreview.table") { WarehouseTableRefFromProto(value.getTable()) },
    limit = if (value.hasLimit()) value.getLimit() else null
)

internal fun SourceInspectionProfileToProto(value: com.olap.prototype.source.contract.SourceInspection.Profile): com.olap.prototype.wire.governance.SourceInspectionProfile = com.olap.prototype.wire.governance.SourceInspectionProfile.newBuilder().apply {
    setBinding(QuerySourceProto.toProto(value.binding))
    setTable(WarehouseTableRefToProto(value.table))
    addAllColumns(value.columns.map { it })
    value.timeoutSeconds?.let { setTimeoutSeconds(it) }
}.build()
internal fun SourceInspectionProfileFromProto(value: com.olap.prototype.wire.governance.SourceInspectionProfile): com.olap.prototype.source.contract.SourceInspection.Profile = com.olap.prototype.source.contract.SourceInspection.Profile(
    binding = required(value.hasBinding(), "SourceInspectionProfile.binding") { QuerySourceProto.fromProto(value.getBinding()) as com.olap.prototype.warehouse.contract.SourceBindingRef },
    table = required(value.hasTable(), "SourceInspectionProfile.table") { WarehouseTableRefFromProto(value.getTable()) },
    columns = value.getColumnsList().map { it },
    timeoutSeconds = if (value.hasTimeoutSeconds()) value.getTimeoutSeconds() else null
)

internal fun SourceInspectionRelationshipToProto(value: com.olap.prototype.source.contract.SourceInspection.Relationship): com.olap.prototype.wire.governance.SourceInspectionRelationship = com.olap.prototype.wire.governance.SourceInspectionRelationship.newBuilder().apply {
    setBinding(QuerySourceProto.toProto(value.binding))
    setSourceInput(WarehouseRelationshipProfileRequestInputToProto(value.sourceInput))
    setTargetInput(WarehouseRelationshipProfileRequestInputToProto(value.targetInput))
    value.timeoutSeconds?.let { setTimeoutSeconds(it) }
}.build()
internal fun SourceInspectionRelationshipFromProto(value: com.olap.prototype.wire.governance.SourceInspectionRelationship): com.olap.prototype.source.contract.SourceInspection.Relationship = com.olap.prototype.source.contract.SourceInspection.Relationship(
    binding = required(value.hasBinding(), "SourceInspectionRelationship.binding") { QuerySourceProto.fromProto(value.getBinding()) as com.olap.prototype.warehouse.contract.SourceBindingRef },
    sourceInput = required(value.hasSourceInput(), "SourceInspectionRelationship.sourceInput") { WarehouseRelationshipProfileRequestInputFromProto(value.getSourceInput()) },
    targetInput = required(value.hasTargetInput(), "SourceInspectionRelationship.targetInput") { WarehouseRelationshipProfileRequestInputFromProto(value.getTargetInput()) },
    timeoutSeconds = if (value.hasTimeoutSeconds()) value.getTimeoutSeconds() else null
)

internal fun SourceInspectionResultToProto(value: com.olap.prototype.source.contract.SourceInspectionResult): com.olap.prototype.wire.governance.SourceInspectionResult = com.olap.prototype.wire.governance.SourceInspectionResult.newBuilder().apply { when (value) {
    is com.olap.prototype.source.contract.SourceInspectionResult.Capabilities -> setCapabilities(SourceInspectionResultCapabilitiesToProto(value))
    is com.olap.prototype.source.contract.SourceInspectionResult.Discovery -> setDiscovery(SourceInspectionResultDiscoveryToProto(value))
    is com.olap.prototype.source.contract.SourceInspectionResult.Description -> setDescription(SourceInspectionResultDescriptionToProto(value))
    is com.olap.prototype.source.contract.SourceInspectionResult.Snapshot -> setSnapshot(SourceInspectionResultSnapshotToProto(value))
    is com.olap.prototype.source.contract.SourceInspectionResult.Preview -> setPreview(SourceInspectionResultPreviewToProto(value))
    is com.olap.prototype.source.contract.SourceInspectionResult.Profile -> setProfile(SourceInspectionResultProfileToProto(value))
    is com.olap.prototype.source.contract.SourceInspectionResult.Relationship -> setRelationship(SourceInspectionResultRelationshipToProto(value))
} }.build()
internal fun SourceInspectionResultFromProto(value: com.olap.prototype.wire.governance.SourceInspectionResult): com.olap.prototype.source.contract.SourceInspectionResult = when (value.kindCase) {
    com.olap.prototype.wire.governance.SourceInspectionResult.KindCase.CAPABILITIES -> SourceInspectionResultCapabilitiesFromProto(value.getCapabilities())
    com.olap.prototype.wire.governance.SourceInspectionResult.KindCase.DISCOVERY -> SourceInspectionResultDiscoveryFromProto(value.getDiscovery())
    com.olap.prototype.wire.governance.SourceInspectionResult.KindCase.DESCRIPTION -> SourceInspectionResultDescriptionFromProto(value.getDescription())
    com.olap.prototype.wire.governance.SourceInspectionResult.KindCase.SNAPSHOT -> SourceInspectionResultSnapshotFromProto(value.getSnapshot())
    com.olap.prototype.wire.governance.SourceInspectionResult.KindCase.PREVIEW -> SourceInspectionResultPreviewFromProto(value.getPreview())
    com.olap.prototype.wire.governance.SourceInspectionResult.KindCase.PROFILE -> SourceInspectionResultProfileFromProto(value.getProfile())
    com.olap.prototype.wire.governance.SourceInspectionResult.KindCase.RELATIONSHIP -> SourceInspectionResultRelationshipFromProto(value.getRelationship())
    com.olap.prototype.wire.governance.SourceInspectionResult.KindCase.KIND_NOT_SET -> error("SourceInspectionResult variant is required")
}

internal fun SourceInspectionResultCapabilitiesToProto(value: com.olap.prototype.source.contract.SourceInspectionResult.Capabilities): com.olap.prototype.wire.governance.SourceInspectionResultCapabilities = com.olap.prototype.wire.governance.SourceInspectionResultCapabilities.newBuilder().apply {
    addAllOperations(value.operations.map { it }.sorted())
    setRelationshipExpressionType(value.relationshipExpressionType)
}.build()
internal fun SourceInspectionResultCapabilitiesFromProto(value: com.olap.prototype.wire.governance.SourceInspectionResultCapabilities): com.olap.prototype.source.contract.SourceInspectionResult.Capabilities = com.olap.prototype.source.contract.SourceInspectionResult.Capabilities(
    operations = strictSet(value.getOperationsList().map { it }),
    relationshipExpressionType = value.getRelationshipExpressionType()
)

internal fun SourceInspectionResultDiscoveryToProto(value: com.olap.prototype.source.contract.SourceInspectionResult.Discovery): com.olap.prototype.wire.governance.SourceInspectionResultDiscovery = com.olap.prototype.wire.governance.SourceInspectionResultDiscovery.newBuilder().apply {
    setValue(WarehouseDiscoveryResultToProto(value.value))
}.build()
internal fun SourceInspectionResultDiscoveryFromProto(value: com.olap.prototype.wire.governance.SourceInspectionResultDiscovery): com.olap.prototype.source.contract.SourceInspectionResult.Discovery = com.olap.prototype.source.contract.SourceInspectionResult.Discovery(
    value = required(value.hasValue(), "SourceInspectionResultDiscovery.value") { WarehouseDiscoveryResultFromProto(value.getValue()) }
)

internal fun SourceInspectionResultDescriptionToProto(value: com.olap.prototype.source.contract.SourceInspectionResult.Description): com.olap.prototype.wire.governance.SourceInspectionResultDescription = com.olap.prototype.wire.governance.SourceInspectionResultDescription.newBuilder().apply {
    setValue(WarehouseTableMetadataToProto(value.value))
}.build()
internal fun SourceInspectionResultDescriptionFromProto(value: com.olap.prototype.wire.governance.SourceInspectionResultDescription): com.olap.prototype.source.contract.SourceInspectionResult.Description = com.olap.prototype.source.contract.SourceInspectionResult.Description(
    value = required(value.hasValue(), "SourceInspectionResultDescription.value") { WarehouseTableMetadataFromProto(value.getValue()) }
)

internal fun SourceInspectionResultSnapshotToProto(value: com.olap.prototype.source.contract.SourceInspectionResult.Snapshot): com.olap.prototype.wire.governance.SourceInspectionResultSnapshot = com.olap.prototype.wire.governance.SourceInspectionResultSnapshot.newBuilder().apply {
    setValue(WarehouseSchemaSnapshotToProto(value.value))
}.build()
internal fun SourceInspectionResultSnapshotFromProto(value: com.olap.prototype.wire.governance.SourceInspectionResultSnapshot): com.olap.prototype.source.contract.SourceInspectionResult.Snapshot = com.olap.prototype.source.contract.SourceInspectionResult.Snapshot(
    value = required(value.hasValue(), "SourceInspectionResultSnapshot.value") { WarehouseSchemaSnapshotFromProto(value.getValue()) }
)

internal fun SourceInspectionResultPreviewToProto(value: com.olap.prototype.source.contract.SourceInspectionResult.Preview): com.olap.prototype.wire.governance.SourceInspectionResultPreview = com.olap.prototype.wire.governance.SourceInspectionResultPreview.newBuilder().apply {
    setValue(WarehousePreviewToProto(value.value))
}.build()
internal fun SourceInspectionResultPreviewFromProto(value: com.olap.prototype.wire.governance.SourceInspectionResultPreview): com.olap.prototype.source.contract.SourceInspectionResult.Preview = com.olap.prototype.source.contract.SourceInspectionResult.Preview(
    value = required(value.hasValue(), "SourceInspectionResultPreview.value") { WarehousePreviewFromProto(value.getValue()) }
)

internal fun SourceInspectionResultProfileToProto(value: com.olap.prototype.source.contract.SourceInspectionResult.Profile): com.olap.prototype.wire.governance.SourceInspectionResultProfile = com.olap.prototype.wire.governance.SourceInspectionResultProfile.newBuilder().apply {
    setValue(WarehouseProfileToProto(value.value))
}.build()
internal fun SourceInspectionResultProfileFromProto(value: com.olap.prototype.wire.governance.SourceInspectionResultProfile): com.olap.prototype.source.contract.SourceInspectionResult.Profile = com.olap.prototype.source.contract.SourceInspectionResult.Profile(
    value = required(value.hasValue(), "SourceInspectionResultProfile.value") { WarehouseProfileFromProto(value.getValue()) }
)

internal fun SourceInspectionResultRelationshipToProto(value: com.olap.prototype.source.contract.SourceInspectionResult.Relationship): com.olap.prototype.wire.governance.SourceInspectionResultRelationship = com.olap.prototype.wire.governance.SourceInspectionResultRelationship.newBuilder().apply {
    setValue(WarehouseRelationshipProfileToProto(value.value))
}.build()
internal fun SourceInspectionResultRelationshipFromProto(value: com.olap.prototype.wire.governance.SourceInspectionResultRelationship): com.olap.prototype.source.contract.SourceInspectionResult.Relationship = com.olap.prototype.source.contract.SourceInspectionResult.Relationship(
    value = required(value.hasValue(), "SourceInspectionResultRelationship.value") { WarehouseRelationshipProfileFromProto(value.getValue()) }
)

internal fun SourceCollectionsToProto(value: com.olap.prototype.dsl.SourceCollections): com.olap.prototype.wire.governance.SourceCollections = com.olap.prototype.wire.governance.SourceCollections.newBuilder().apply {
    addAllItems(value.items.map { SourceCollectionRecordToProto(it) })
}.build()
internal fun SourceCollectionsFromProto(value: com.olap.prototype.wire.governance.SourceCollections): com.olap.prototype.dsl.SourceCollections = com.olap.prototype.dsl.SourceCollections(
    items = value.getItemsList().map { SourceCollectionRecordFromProto(it) }
)

internal fun DeploymentAvailabilityRequestToProto(value: com.olap.prototype.governance.contract.DeploymentAvailabilityRequest): com.olap.prototype.wire.governance.DeploymentAvailabilityRequest = com.olap.prototype.wire.governance.DeploymentAvailabilityRequest.newBuilder().apply {
    addAllPublications(value.publications.map { QueryProto.toProto(it) })
    addAllQueries(value.queries.map { DeploymentAvailabilityRequestSavedQueryReferenceToProto(it) })
    addAllResults(value.results.map { DeploymentAvailabilityRequestResultReferenceToProto(it) })
}.build()
internal fun DeploymentAvailabilityRequestFromProto(value: com.olap.prototype.wire.governance.DeploymentAvailabilityRequest): com.olap.prototype.governance.contract.DeploymentAvailabilityRequest = com.olap.prototype.governance.contract.DeploymentAvailabilityRequest(
    publications = value.getPublicationsList().map { QueryProto.fromProto(it) as com.olap.prototype.query.contract.QueryCapabilityRequest },
    queries = value.getQueriesList().map { DeploymentAvailabilityRequestSavedQueryReferenceFromProto(it) },
    results = value.getResultsList().map { DeploymentAvailabilityRequestResultReferenceFromProto(it) }
)

internal fun DeploymentAvailabilityRequestSavedQueryReferenceToProto(value: com.olap.prototype.governance.contract.DeploymentAvailabilityRequest.SavedQueryReference): com.olap.prototype.wire.governance.DeploymentAvailabilityRequestSavedQueryReference = com.olap.prototype.wire.governance.DeploymentAvailabilityRequestSavedQueryReference.newBuilder().apply {
    setDefinitionId(value.definitionId)
    setDefinitionVersion(value.definitionVersion)
    setSha256(value.sha256)
}.build()
internal fun DeploymentAvailabilityRequestSavedQueryReferenceFromProto(value: com.olap.prototype.wire.governance.DeploymentAvailabilityRequestSavedQueryReference): com.olap.prototype.governance.contract.DeploymentAvailabilityRequest.SavedQueryReference = com.olap.prototype.governance.contract.DeploymentAvailabilityRequest.SavedQueryReference(
    definitionId = value.getDefinitionId(),
    definitionVersion = value.getDefinitionVersion(),
    sha256 = value.getSha256()
)

internal fun DeploymentAvailabilityRequestResultReferenceToProto(value: com.olap.prototype.governance.contract.DeploymentAvailabilityRequest.ResultReference): com.olap.prototype.wire.governance.DeploymentAvailabilityRequestResultReference = com.olap.prototype.wire.governance.DeploymentAvailabilityRequestResultReference.newBuilder().apply {
    setRunId(value.runId)
    setSha256(value.sha256)
}.build()
internal fun DeploymentAvailabilityRequestResultReferenceFromProto(value: com.olap.prototype.wire.governance.DeploymentAvailabilityRequestResultReference): com.olap.prototype.governance.contract.DeploymentAvailabilityRequest.ResultReference = com.olap.prototype.governance.contract.DeploymentAvailabilityRequest.ResultReference(
    runId = value.getRunId(),
    sha256 = value.getSha256()
)

internal fun DeploymentAvailabilityToProto(value: com.olap.prototype.governance.contract.DeploymentAvailability): com.olap.prototype.wire.governance.DeploymentAvailability = com.olap.prototype.wire.governance.DeploymentAvailability.newBuilder().apply {
    addAllSupported(value.supported.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.DeploymentAvailabilitySupportedEntry.newBuilder().setKey(entry.key).apply { setValue(entry.value) }.build() })
    addAllChecks(value.checks.map { DeploymentAvailabilityCheckToProto(it) })
}.build()
internal fun DeploymentAvailabilityFromProto(value: com.olap.prototype.wire.governance.DeploymentAvailability): com.olap.prototype.governance.contract.DeploymentAvailability = com.olap.prototype.governance.contract.DeploymentAvailability(
    supported = strictMap(value.getSupportedList().map { it.getKey() to it.getValue() }),
    checks = value.getChecksList().map { DeploymentAvailabilityCheckFromProto(it) }
)

internal fun DeploymentAvailabilityCheckToProto(value: com.olap.prototype.governance.contract.DeploymentAvailability.Check): com.olap.prototype.wire.governance.DeploymentAvailabilityCheck = com.olap.prototype.wire.governance.DeploymentAvailabilityCheck.newBuilder().apply {
    setKind(value.kind.name)
    addAllReference(value.reference.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.DeploymentAvailabilityCheckReferenceEntry.newBuilder().setKey(entry.key).apply { setValue(diagnosticToProto(entry.value)) }.build() })
    setStatus(value.status.name)
    addAllIssues(value.issues.map { DeploymentAvailabilityIssueToProto(it) })
}.build()
internal fun DeploymentAvailabilityCheckFromProto(value: com.olap.prototype.wire.governance.DeploymentAvailabilityCheck): com.olap.prototype.governance.contract.DeploymentAvailability.Check = com.olap.prototype.governance.contract.DeploymentAvailability.Check(
    kind = com.olap.prototype.governance.contract.DeploymentAvailability.Kind.valueOf(value.getKind()),
    reference = strictMap(value.getReferenceList().map { it.getKey() to requireNotNull(diagnosticFromProto(it.getValue())) }),
    status = com.olap.prototype.governance.contract.DeploymentAvailability.Status.valueOf(value.getStatus()),
    issues = value.getIssuesList().map { DeploymentAvailabilityIssueFromProto(it) }
)

internal fun DeploymentAvailabilityIssueToProto(value: com.olap.prototype.governance.contract.DeploymentAvailability.Issue): com.olap.prototype.wire.governance.DeploymentAvailabilityIssue = com.olap.prototype.wire.governance.DeploymentAvailabilityIssue.newBuilder().apply {
    setCode(value.code)
    setMessage(value.message)
    addAllDetails(value.details.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.DeploymentAvailabilityIssueDetailsEntry.newBuilder().setKey(entry.key).apply { entry.value?.let { setValue(diagnosticToProto(it)) } }.build() })
}.build()
internal fun DeploymentAvailabilityIssueFromProto(value: com.olap.prototype.wire.governance.DeploymentAvailabilityIssue): com.olap.prototype.governance.contract.DeploymentAvailability.Issue = com.olap.prototype.governance.contract.DeploymentAvailability.Issue(
    code = value.getCode(),
    message = value.getMessage(),
    details = strictMap(value.getDetailsList().map { it.getKey() to if (it.hasValue()) diagnosticFromProto(it.getValue()) else null })
)
