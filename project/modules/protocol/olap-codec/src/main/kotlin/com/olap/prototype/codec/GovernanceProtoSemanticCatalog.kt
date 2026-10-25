package com.olap.prototype.codec

/** 正式领域消息的显式字段转换。 */
internal fun PublishedSemanticCatalogToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog): com.olap.prototype.wire.governance.PublishedSemanticCatalog = com.olap.prototype.wire.governance.PublishedSemanticCatalog.newBuilder().apply {
    setFormatVersion(value.formatVersion)
    setSha256(value.sha256)
    setCatalog(PublishedSemanticCatalogCatalogToProto(value.catalog))
}.build()
internal fun PublishedSemanticCatalogFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalog): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog(
    formatVersion = value.getFormatVersion(),
    sha256 = value.getSha256(),
    catalog = required(value.hasCatalog(), "PublishedSemanticCatalog.catalog") { PublishedSemanticCatalogCatalogFromProto(value.getCatalog()) }
)

internal fun PublishedSemanticCatalogCatalogToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Catalog): com.olap.prototype.wire.governance.PublishedSemanticCatalogCatalog = com.olap.prototype.wire.governance.PublishedSemanticCatalogCatalog.newBuilder().apply {
    setCoordinate(PublishedSemanticCatalogCoordinateToProto(value.coordinate))
    setDisplayName(value.displayName)
    setSourceJoinContract(PublishedSemanticCatalogSourceJoinMeaningToProto(value.sourceJoinContract))
    addAllMetrics(value.metrics.map { PublishedSemanticCatalogMetricToProto(it) })
    addAllMemberSources(value.memberSources.map { PublishedSemanticCatalogMemberToProto(it) })
    addAllRelationFilters(value.relationFilters.map { PublishedSemanticCatalogRelationFilterToProto(it) })
    addAllDimensions(value.dimensions.map { PublishedSemanticCatalogDimensionToProto(it) })
    addAllTimeDimensions(value.timeDimensions.map { PublishedSemanticCatalogTimeDimensionToProto(it) })
    addAllCombinations(value.combinations.map { PublishedSemanticCatalogCombinationToProto(it) })
    addAllFilters(value.filters.map { PublishedSemanticCatalogFilterToProto(it) })
    addAllSemanticDependencies(value.semanticDependencies.map { PublishedSemanticCatalogSemanticDependencyToProto(it) })
}.build()
internal fun PublishedSemanticCatalogCatalogFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogCatalog): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Catalog = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Catalog(
    coordinate = required(value.hasCoordinate(), "PublishedSemanticCatalogCatalog.coordinate") { PublishedSemanticCatalogCoordinateFromProto(value.getCoordinate()) },
    displayName = value.getDisplayName(),
    sourceJoinContract = required(value.hasSourceJoinContract(), "PublishedSemanticCatalogCatalog.sourceJoinContract") { PublishedSemanticCatalogSourceJoinMeaningFromProto(value.getSourceJoinContract()) },
    metrics = value.getMetricsList().map { PublishedSemanticCatalogMetricFromProto(it) },
    memberSources = value.getMemberSourcesList().map { PublishedSemanticCatalogMemberFromProto(it) },
    relationFilters = value.getRelationFiltersList().map { PublishedSemanticCatalogRelationFilterFromProto(it) },
    dimensions = value.getDimensionsList().map { PublishedSemanticCatalogDimensionFromProto(it) },
    timeDimensions = value.getTimeDimensionsList().map { PublishedSemanticCatalogTimeDimensionFromProto(it) },
    combinations = value.getCombinationsList().map { PublishedSemanticCatalogCombinationFromProto(it) },
    filters = value.getFiltersList().map { PublishedSemanticCatalogFilterFromProto(it) },
    semanticDependencies = value.getSemanticDependenciesList().map { PublishedSemanticCatalogSemanticDependencyFromProto(it) }
)

internal fun PublishedSemanticCatalogCoordinateToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Coordinate): com.olap.prototype.wire.governance.PublishedSemanticCatalogCoordinate = com.olap.prototype.wire.governance.PublishedSemanticCatalogCoordinate.newBuilder().apply {
    setScenarioId(value.scenarioId)
    setScenarioVersion(value.scenarioVersion)
    setReleaseId(value.releaseId)
    setReleaseVersion(value.releaseVersion)
}.build()
internal fun PublishedSemanticCatalogCoordinateFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogCoordinate): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Coordinate = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Coordinate(
    scenarioId = value.getScenarioId(),
    scenarioVersion = value.getScenarioVersion(),
    releaseId = value.getReleaseId(),
    releaseVersion = value.getReleaseVersion()
)

internal fun PublishedSemanticCatalogSourceJoinMeaningToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.SourceJoinMeaning): com.olap.prototype.wire.governance.PublishedSemanticCatalogSourceJoinMeaning = com.olap.prototype.wire.governance.PublishedSemanticCatalogSourceJoinMeaning.newBuilder().apply {
    setFormatVersion(value.formatVersion)
    addAllJoinTypes(value.joinTypes.map { it.name })
    addAllCardinalities(value.cardinalities.map { it.name })
    addAllNullPolicies(value.nullPolicies.map { it.name })
    setFactPopulation(value.factPopulation)
    addAllFactMatching(value.factMatching.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogSourceJoinMeaningFactMatchingEntry.newBuilder().setKey(entry.key.name).apply { setValue(entry.value) }.build() })
    setMemberPopulation(value.memberPopulation)
    setExistsPopulation(value.existsPopulation)
    setKeyMatching(value.keyMatching)
    setGuarantees(value.guarantees)
}.build()
internal fun PublishedSemanticCatalogSourceJoinMeaningFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogSourceJoinMeaning): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.SourceJoinMeaning = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.SourceJoinMeaning(
    formatVersion = value.getFormatVersion(),
    joinTypes = value.getJoinTypesList().map { com.olap.prototype.query.SourceJoinContract.JoinType.valueOf(it) },
    cardinalities = value.getCardinalitiesList().map { com.olap.prototype.query.SourceJoinContract.Cardinality.valueOf(it) },
    nullPolicies = value.getNullPoliciesList().map { com.olap.prototype.query.SourceJoinContract.NullPolicy.valueOf(it) },
    factPopulation = value.getFactPopulation(),
    factMatching = strictMap(value.getFactMatchingList().map { com.olap.prototype.query.SourceJoinContract.JoinType.valueOf(it.getKey()) to it.getValue() }),
    memberPopulation = value.getMemberPopulation(),
    existsPopulation = value.getExistsPopulation(),
    keyMatching = value.getKeyMatching(),
    guarantees = value.getGuarantees()
)

internal fun PublishedSemanticCatalogMetricToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Metric): com.olap.prototype.wire.governance.PublishedSemanticCatalogMetric = com.olap.prototype.wire.governance.PublishedSemanticCatalogMetric.newBuilder().apply {
    setAdoptionId(value.adoptionId)
    setAdoptionVersion(value.adoptionVersion)
    setSubjectAreaId(value.subjectAreaId)
    setSubjectAreaVersion(value.subjectAreaVersion)
    setCode(value.code)
    setDisplayName(value.displayName)
    setBusinessMeaning(SemanticProto.toProto(value.businessMeaning))
    setDefinition(SemanticProto.toProto(value.definition))
    setInstance(SemanticProto.toProto(value.instance))
    setValueContract(SemanticProto.toProto(value.valueContract))
    value.snapshotMeaning?.let { setSnapshotMeaning(MetricSnapshotRuleMeaningToProto(it)) }
    addAllSources(value.sources.map { MetricSourceToProto(it) })
    setDefinitionKind(value.definitionKind.name)
    addAllFixedArguments(value.fixedArguments.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogMetricFixedArgumentsEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    addAllParameters(value.parameters.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogMetricParametersEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    addAllGroupingCandidates(value.groupingCandidates.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogMetricGroupingCandidatesEntry.newBuilder().setKey(entry.key).apply { setValue(com.olap.prototype.wire.governance.ListScopedInputContractDimensionRef.newBuilder().addAllValues(entry.value.map { SemanticProto.toProto(it) }).build()) }.build() })
    addAllMemberParameters(value.memberParameters.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogMetricMemberParametersEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    addAllRelationParameters(value.relationParameters.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogMetricRelationParametersEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    addAllSourceScopes(value.sourceScopes.map { PublishedSemanticCatalogSourceExplanationToProto(it) })
    setAggregation(CatalogOptionalPublishedSemanticCatalogAggregationToProto(value.aggregation))
}.build()
internal fun PublishedSemanticCatalogMetricFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogMetric): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Metric = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Metric(
    adoptionId = value.getAdoptionId(),
    adoptionVersion = value.getAdoptionVersion(),
    subjectAreaId = value.getSubjectAreaId(),
    subjectAreaVersion = value.getSubjectAreaVersion(),
    code = value.getCode(),
    displayName = value.getDisplayName(),
    businessMeaning = required(value.hasBusinessMeaning(), "PublishedSemanticCatalogMetric.businessMeaning") { SemanticProto.fromProto(value.getBusinessMeaning()) as com.olap.prototype.metric.MetricBusinessMeaning },
    definition = required(value.hasDefinition(), "PublishedSemanticCatalogMetric.definition") { SemanticProto.fromProto(value.getDefinition()) as com.olap.prototype.calculation.MetricRef },
    instance = required(value.hasInstance(), "PublishedSemanticCatalogMetric.instance") { SemanticProto.fromProto(value.getInstance()) as com.olap.prototype.calculation.MetricInstanceRef },
    valueContract = required(value.hasValueContract(), "PublishedSemanticCatalogMetric.valueContract") { SemanticProto.fromProto(value.getValueContract()) as com.olap.prototype.metric.MetricValueContract },
    snapshotMeaning = if (value.hasSnapshotMeaning()) MetricSnapshotRuleMeaningFromProto(value.getSnapshotMeaning()) else null,
    sources = value.getSourcesList().map { MetricSourceFromProto(it) },
    definitionKind = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.MetricKind.valueOf(value.getDefinitionKind()),
    fixedArguments = strictMap(value.getFixedArgumentsList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.Value }),
    parameters = strictMap(value.getParametersList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.Parameter }),
    groupingCandidates = strictMap(value.getGroupingCandidatesList().map { it.getKey() to it.getValue().getValuesList().map { SemanticProto.fromProto(it) as com.olap.prototype.query.ScopedInputContract.DimensionRef } }),
    memberParameters = strictMap(value.getMemberParametersList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.MemberSlot }),
    relationParameters = strictMap(value.getRelationParametersList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.RelationSlot }),
    sourceScopes = value.getSourceScopesList().map { PublishedSemanticCatalogSourceExplanationFromProto(it) },
    aggregation = required(value.hasAggregation(), "PublishedSemanticCatalogMetric.aggregation") { CatalogOptionalPublishedSemanticCatalogAggregationFromProto(value.getAggregation()) }
)

internal fun PublishedSemanticCatalogSourceExplanationToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.SourceExplanation): com.olap.prototype.wire.governance.PublishedSemanticCatalogSourceExplanation = com.olap.prototype.wire.governance.PublishedSemanticCatalogSourceExplanation.newBuilder().apply {
    addAllCallPath(value.callPath.map { it })
    setScope(PublishedSemanticCatalogSourceScopeToProto(value.scope))
}.build()
internal fun PublishedSemanticCatalogSourceExplanationFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogSourceExplanation): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.SourceExplanation = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.SourceExplanation(
    callPath = value.getCallPathList().map { it },
    scope = required(value.hasScope(), "PublishedSemanticCatalogSourceExplanation.scope") { PublishedSemanticCatalogSourceScopeFromProto(value.getScope()) }
)

internal fun PublishedSemanticCatalogSourceScopeToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.SourceScope): com.olap.prototype.wire.governance.PublishedSemanticCatalogSourceScope = com.olap.prototype.wire.governance.PublishedSemanticCatalogSourceScope.newBuilder().apply { when (value) {
    is com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.FactScope -> setFactScope(PublishedSemanticCatalogFactScopeToProto(value))
    is com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.MemberDefinition -> setMemberDefinition(PublishedSemanticCatalogMemberDefinitionToProto(value))
} }.build()
internal fun PublishedSemanticCatalogSourceScopeFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogSourceScope): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.SourceScope = when (value.kindCase) {
    com.olap.prototype.wire.governance.PublishedSemanticCatalogSourceScope.KindCase.FACT_SCOPE -> PublishedSemanticCatalogFactScopeFromProto(value.getFactScope())
    com.olap.prototype.wire.governance.PublishedSemanticCatalogSourceScope.KindCase.MEMBER_DEFINITION -> PublishedSemanticCatalogMemberDefinitionFromProto(value.getMemberDefinition())
    com.olap.prototype.wire.governance.PublishedSemanticCatalogSourceScope.KindCase.KIND_NOT_SET -> error("PublishedSemanticCatalogSourceScope variant is required")
}

internal fun PublishedSemanticCatalogFactScopeToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.FactScope): com.olap.prototype.wire.governance.PublishedSemanticCatalogFactScope = com.olap.prototype.wire.governance.PublishedSemanticCatalogFactScope.newBuilder().apply {
    setInterpretation(value.interpretation.name)
    value.eventPredicate?.let { setEventPredicate(PublicExpressionToProto(it)) }
    addAllEventRoles(value.eventRoles.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogFactScopeEventRolesEntry.newBuilder().setKey(entry.key).apply { setValue(com.olap.prototype.wire.governance.ListSemanticJoin.newBuilder().addAllValues(entry.value.map { SemanticJoinToProto(it) }).build()) }.build() })
    value.snapshotRule?.let { setSnapshotRule(SemanticProto.toProto(it)) }
    setInputExpression(PublicExpressionToProto(value.inputExpression))
    value.privatePredicate?.let { setPrivatePredicate(PublicExpressionToProto(it)) }
    addAllMetricRoles(value.metricRoles.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogFactScopeMetricRolesEntry.newBuilder().setKey(entry.key).apply { setValue(com.olap.prototype.wire.governance.ListSemanticJoin.newBuilder().addAllValues(entry.value.map { SemanticJoinToProto(it) }).build()) }.build() })
    addAllLogicUnits(value.logicUnits.map { PublishedSemanticCatalogLogicUnitMeaningToProto(it) })
    setSealedSourceCollections(CatalogOptionalListSealedSourceCollectionMeaningToProto(value.sealedSourceCollections))
}.build()
internal fun PublishedSemanticCatalogFactScopeFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogFactScope): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.FactScope = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.FactScope(
    interpretation = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.FactInterpretation.valueOf(value.getInterpretation()),
    eventPredicate = if (value.hasEventPredicate()) PublicExpressionFromProto(value.getEventPredicate()) else null,
    eventRoles = strictMap(value.getEventRolesList().map { it.getKey() to it.getValue().getValuesList().map { SemanticJoinFromProto(it) } }),
    snapshotRule = if (value.hasSnapshotRule()) SemanticProto.fromProto(value.getSnapshotRule()) as com.olap.prototype.metric.MetricSnapshotRule else null,
    inputExpression = required(value.hasInputExpression(), "PublishedSemanticCatalogFactScope.inputExpression") { PublicExpressionFromProto(value.getInputExpression()) },
    privatePredicate = if (value.hasPrivatePredicate()) PublicExpressionFromProto(value.getPrivatePredicate()) else null,
    metricRoles = strictMap(value.getMetricRolesList().map { it.getKey() to it.getValue().getValuesList().map { SemanticJoinFromProto(it) } }),
    logicUnits = value.getLogicUnitsList().map { PublishedSemanticCatalogLogicUnitMeaningFromProto(it) },
    sealedSourceCollections = required(value.hasSealedSourceCollections(), "PublishedSemanticCatalogFactScope.sealedSourceCollections") { CatalogOptionalListSealedSourceCollectionMeaningFromProto(value.getSealedSourceCollections()) }
)

internal fun PublicExpressionToProto(value: com.olap.prototype.query.contract.catalog.PublicExpression): com.olap.prototype.wire.governance.PublicExpression = com.olap.prototype.wire.governance.PublicExpression.newBuilder().apply { when (value) {
    is com.olap.prototype.query.contract.catalog.PublicExpression.Reference -> setReference(PublicExpressionReferenceToProto(value))
    is com.olap.prototype.query.contract.catalog.PublicExpression.TextLiteral -> setTextLiteral(PublicExpressionTextLiteralToProto(value))
    is com.olap.prototype.query.contract.catalog.PublicExpression.BooleanLiteral -> setBooleanLiteral(PublicExpressionBooleanLiteralToProto(value))
    is com.olap.prototype.query.contract.catalog.PublicExpression.ExactNumber -> setExactNumber(PublicExpressionExactNumberToProto(value))
    is com.olap.prototype.query.contract.catalog.PublicExpression.Call -> setCall(PublicExpressionCallToProto(value))
    is com.olap.prototype.query.contract.catalog.PublicExpression.NativeCall -> setNativeCall(PublicExpressionNativeCallToProto(value))
    is com.olap.prototype.query.contract.catalog.PublicExpression.Operation -> setOperation(PublicExpressionOperationToProto(value))
    is com.olap.prototype.query.contract.catalog.PublicExpression.CaseWhen -> setCaseWhen(PublicExpressionCaseWhenToProto(value))
    is com.olap.prototype.query.contract.catalog.PublicExpression.Round -> setRound(PublicExpressionRoundToProto(value))
    is com.olap.prototype.query.contract.catalog.PublicExpression.Cast -> setCast(PublicExpressionCastToProto(value))
    is com.olap.prototype.query.contract.catalog.PublicExpression.Exists -> setExists(PublicExpressionExistsToProto(value))
} }.build()
internal fun PublicExpressionFromProto(value: com.olap.prototype.wire.governance.PublicExpression): com.olap.prototype.query.contract.catalog.PublicExpression = when (value.kindCase) {
    com.olap.prototype.wire.governance.PublicExpression.KindCase.REFERENCE -> PublicExpressionReferenceFromProto(value.getReference())
    com.olap.prototype.wire.governance.PublicExpression.KindCase.TEXT_LITERAL -> PublicExpressionTextLiteralFromProto(value.getTextLiteral())
    com.olap.prototype.wire.governance.PublicExpression.KindCase.BOOLEAN_LITERAL -> PublicExpressionBooleanLiteralFromProto(value.getBooleanLiteral())
    com.olap.prototype.wire.governance.PublicExpression.KindCase.EXACT_NUMBER -> PublicExpressionExactNumberFromProto(value.getExactNumber())
    com.olap.prototype.wire.governance.PublicExpression.KindCase.CALL -> PublicExpressionCallFromProto(value.getCall())
    com.olap.prototype.wire.governance.PublicExpression.KindCase.NATIVE_CALL -> PublicExpressionNativeCallFromProto(value.getNativeCall())
    com.olap.prototype.wire.governance.PublicExpression.KindCase.OPERATION -> PublicExpressionOperationFromProto(value.getOperation())
    com.olap.prototype.wire.governance.PublicExpression.KindCase.CASE_WHEN -> PublicExpressionCaseWhenFromProto(value.getCaseWhen())
    com.olap.prototype.wire.governance.PublicExpression.KindCase.ROUND -> PublicExpressionRoundFromProto(value.getRound())
    com.olap.prototype.wire.governance.PublicExpression.KindCase.CAST -> PublicExpressionCastFromProto(value.getCast())
    com.olap.prototype.wire.governance.PublicExpression.KindCase.EXISTS -> PublicExpressionExistsFromProto(value.getExists())
    com.olap.prototype.wire.governance.PublicExpression.KindCase.KIND_NOT_SET -> error("PublicExpression variant is required")
}

internal fun PublicExpressionReferenceToProto(value: com.olap.prototype.query.contract.catalog.PublicExpression.Reference): com.olap.prototype.wire.governance.PublicExpressionReference = com.olap.prototype.wire.governance.PublicExpressionReference.newBuilder().apply {
    setId(value.id)
    setVersion(value.version)
    value.role?.let { setRole(it) }
}.build()
internal fun PublicExpressionReferenceFromProto(value: com.olap.prototype.wire.governance.PublicExpressionReference): com.olap.prototype.query.contract.catalog.PublicExpression.Reference = com.olap.prototype.query.contract.catalog.PublicExpression.Reference(
    id = value.getId(),
    version = value.getVersion(),
    role = if (value.hasRole()) value.getRole() else null
)

internal fun PublicExpressionTextLiteralToProto(value: com.olap.prototype.query.contract.catalog.PublicExpression.TextLiteral): com.olap.prototype.wire.governance.PublicExpressionTextLiteral = com.olap.prototype.wire.governance.PublicExpressionTextLiteral.newBuilder().apply {
    setValue(value.value)
}.build()
internal fun PublicExpressionTextLiteralFromProto(value: com.olap.prototype.wire.governance.PublicExpressionTextLiteral): com.olap.prototype.query.contract.catalog.PublicExpression.TextLiteral = com.olap.prototype.query.contract.catalog.PublicExpression.TextLiteral(
    value = value.getValue()
)

internal fun PublicExpressionBooleanLiteralToProto(value: com.olap.prototype.query.contract.catalog.PublicExpression.BooleanLiteral): com.olap.prototype.wire.governance.PublicExpressionBooleanLiteral = com.olap.prototype.wire.governance.PublicExpressionBooleanLiteral.newBuilder().apply {
    setValue(value.value)
}.build()
internal fun PublicExpressionBooleanLiteralFromProto(value: com.olap.prototype.wire.governance.PublicExpressionBooleanLiteral): com.olap.prototype.query.contract.catalog.PublicExpression.BooleanLiteral = com.olap.prototype.query.contract.catalog.PublicExpression.BooleanLiteral(
    value = value.getValue()
)

internal fun PublicExpressionExactNumberToProto(value: com.olap.prototype.query.contract.catalog.PublicExpression.ExactNumber): com.olap.prototype.wire.governance.PublicExpressionExactNumber = com.olap.prototype.wire.governance.PublicExpressionExactNumber.newBuilder().apply {
    setValue(value.value)
}.build()
internal fun PublicExpressionExactNumberFromProto(value: com.olap.prototype.wire.governance.PublicExpressionExactNumber): com.olap.prototype.query.contract.catalog.PublicExpression.ExactNumber = com.olap.prototype.query.contract.catalog.PublicExpression.ExactNumber(
    value = value.getValue()
)

internal fun PublicExpressionCallToProto(value: com.olap.prototype.query.contract.catalog.PublicExpression.Call): com.olap.prototype.wire.governance.PublicExpressionCall = com.olap.prototype.wire.governance.PublicExpressionCall.newBuilder().apply {
    setFunction(value.function)
    addAllArguments(value.arguments.map { PublicExpressionToProto(it) })
}.build()
internal fun PublicExpressionCallFromProto(value: com.olap.prototype.wire.governance.PublicExpressionCall): com.olap.prototype.query.contract.catalog.PublicExpression.Call = com.olap.prototype.query.contract.catalog.PublicExpression.Call(
    function = value.getFunction(),
    arguments = value.getArgumentsList().map { PublicExpressionFromProto(it) }
)

internal fun PublicExpressionNativeCallToProto(value: com.olap.prototype.query.contract.catalog.PublicExpression.NativeCall): com.olap.prototype.wire.governance.PublicExpressionNativeCall = com.olap.prototype.wire.governance.PublicExpressionNativeCall.newBuilder().apply {
    setConnectorRef(SemanticProto.toProto(value.connectorRef))
    setFunction(value.function)
    addAllArguments(value.arguments.map { PublicExpressionNativeArgumentToProto(it) })
}.build()
internal fun PublicExpressionNativeCallFromProto(value: com.olap.prototype.wire.governance.PublicExpressionNativeCall): com.olap.prototype.query.contract.catalog.PublicExpression.NativeCall = com.olap.prototype.query.contract.catalog.PublicExpression.NativeCall(
    connectorRef = required(value.hasConnectorRef(), "PublicExpressionNativeCall.connectorRef") { SemanticProto.fromProto(value.getConnectorRef()) as com.olap.prototype.connector.ConnectorRef },
    function = value.getFunction(),
    arguments = value.getArgumentsList().map { PublicExpressionNativeArgumentFromProto(it) }
)

internal fun PublicExpressionNativeArgumentToProto(value: com.olap.prototype.query.contract.catalog.PublicExpression.NativeArgument): com.olap.prototype.wire.governance.PublicExpressionNativeArgument = com.olap.prototype.wire.governance.PublicExpressionNativeArgument.newBuilder().apply { when (value) {
    is com.olap.prototype.query.contract.catalog.PublicExpression.ScalarArgument -> setScalarArgument(PublicExpressionScalarArgumentToProto(value))
    is com.olap.prototype.query.contract.catalog.PublicExpression.TableArgument -> setTableArgument(PublicExpressionTableArgumentToProto(value))
} }.build()
internal fun PublicExpressionNativeArgumentFromProto(value: com.olap.prototype.wire.governance.PublicExpressionNativeArgument): com.olap.prototype.query.contract.catalog.PublicExpression.NativeArgument = when (value.kindCase) {
    com.olap.prototype.wire.governance.PublicExpressionNativeArgument.KindCase.SCALAR_ARGUMENT -> PublicExpressionScalarArgumentFromProto(value.getScalarArgument())
    com.olap.prototype.wire.governance.PublicExpressionNativeArgument.KindCase.TABLE_ARGUMENT -> PublicExpressionTableArgumentFromProto(value.getTableArgument())
    com.olap.prototype.wire.governance.PublicExpressionNativeArgument.KindCase.KIND_NOT_SET -> error("PublicExpressionNativeArgument variant is required")
}

internal fun PublicExpressionScalarArgumentToProto(value: com.olap.prototype.query.contract.catalog.PublicExpression.ScalarArgument): com.olap.prototype.wire.governance.PublicExpressionScalarArgument = com.olap.prototype.wire.governance.PublicExpressionScalarArgument.newBuilder().apply {
    setExpression(PublicExpressionToProto(value.expression))
}.build()
internal fun PublicExpressionScalarArgumentFromProto(value: com.olap.prototype.wire.governance.PublicExpressionScalarArgument): com.olap.prototype.query.contract.catalog.PublicExpression.ScalarArgument = com.olap.prototype.query.contract.catalog.PublicExpression.ScalarArgument(
    expression = required(value.hasExpression(), "PublicExpressionScalarArgument.expression") { PublicExpressionFromProto(value.getExpression()) }
)

internal fun PublicExpressionTableArgumentToProto(value: com.olap.prototype.query.contract.catalog.PublicExpression.TableArgument): com.olap.prototype.wire.governance.PublicExpressionTableArgument = com.olap.prototype.wire.governance.PublicExpressionTableArgument.newBuilder().apply {
    setPhysicalTableId(value.physicalTableId)
    setPhysicalTableVersion(value.physicalTableVersion)
}.build()
internal fun PublicExpressionTableArgumentFromProto(value: com.olap.prototype.wire.governance.PublicExpressionTableArgument): com.olap.prototype.query.contract.catalog.PublicExpression.TableArgument = com.olap.prototype.query.contract.catalog.PublicExpression.TableArgument(
    physicalTableId = value.getPhysicalTableId(),
    physicalTableVersion = value.getPhysicalTableVersion()
)

internal fun PublicExpressionOperationToProto(value: com.olap.prototype.query.contract.catalog.PublicExpression.Operation): com.olap.prototype.wire.governance.PublicExpressionOperation = com.olap.prototype.wire.governance.PublicExpressionOperation.newBuilder().apply {
    setOperator(value.operator.name)
    addAllOperands(value.operands.map { PublicExpressionToProto(it) })
}.build()
internal fun PublicExpressionOperationFromProto(value: com.olap.prototype.wire.governance.PublicExpressionOperation): com.olap.prototype.query.contract.catalog.PublicExpression.Operation = com.olap.prototype.query.contract.catalog.PublicExpression.Operation(
    operator = com.olap.prototype.expression.SemanticExpression.Operator.valueOf(value.getOperator()),
    operands = value.getOperandsList().map { PublicExpressionFromProto(it) }
)

internal fun PublicExpressionCaseWhenToProto(value: com.olap.prototype.query.contract.catalog.PublicExpression.CaseWhen): com.olap.prototype.wire.governance.PublicExpressionCaseWhen = com.olap.prototype.wire.governance.PublicExpressionCaseWhen.newBuilder().apply {
    addAllBranches(value.branches.map { PublicExpressionBranchToProto(it) })
    setOtherwise(CatalogOptionalPublicExpressionToProto(value.otherwise))
}.build()
internal fun PublicExpressionCaseWhenFromProto(value: com.olap.prototype.wire.governance.PublicExpressionCaseWhen): com.olap.prototype.query.contract.catalog.PublicExpression.CaseWhen = com.olap.prototype.query.contract.catalog.PublicExpression.CaseWhen(
    branches = value.getBranchesList().map { PublicExpressionBranchFromProto(it) },
    otherwise = required(value.hasOtherwise(), "PublicExpressionCaseWhen.otherwise") { CatalogOptionalPublicExpressionFromProto(value.getOtherwise()) }
)

internal fun PublicExpressionBranchToProto(value: com.olap.prototype.query.contract.catalog.PublicExpression.Branch): com.olap.prototype.wire.governance.PublicExpressionBranch = com.olap.prototype.wire.governance.PublicExpressionBranch.newBuilder().apply {
    setWhen(PublicExpressionToProto(value.`when`))
    setThen(PublicExpressionToProto(value.then))
}.build()
internal fun PublicExpressionBranchFromProto(value: com.olap.prototype.wire.governance.PublicExpressionBranch): com.olap.prototype.query.contract.catalog.PublicExpression.Branch = com.olap.prototype.query.contract.catalog.PublicExpression.Branch(
    `when` = required(value.hasWhen(), "PublicExpressionBranch.when") { PublicExpressionFromProto(value.getWhen()) },
    then = required(value.hasThen(), "PublicExpressionBranch.then") { PublicExpressionFromProto(value.getThen()) }
)

internal fun CatalogOptionalPublicExpressionToProto(value: com.olap.prototype.query.contract.catalog.CatalogOptional<com.olap.prototype.query.contract.catalog.PublicExpression>): com.olap.prototype.wire.governance.CatalogOptionalPublicExpression = com.olap.prototype.wire.governance.CatalogOptionalPublicExpression.newBuilder().apply {
    setPresent(value.present)
    value.value?.let { setValue(PublicExpressionToProto(it)) }
}.build()
internal fun CatalogOptionalPublicExpressionFromProto(value: com.olap.prototype.wire.governance.CatalogOptionalPublicExpression): com.olap.prototype.query.contract.catalog.CatalogOptional<com.olap.prototype.query.contract.catalog.PublicExpression> = com.olap.prototype.query.contract.catalog.CatalogOptional<com.olap.prototype.query.contract.catalog.PublicExpression>(
    present = value.getPresent(),
    value = if (value.hasValue()) PublicExpressionFromProto(value.getValue()) else null
)

internal fun PublicExpressionRoundToProto(value: com.olap.prototype.query.contract.catalog.PublicExpression.Round): com.olap.prototype.wire.governance.PublicExpressionRound = com.olap.prototype.wire.governance.PublicExpressionRound.newBuilder().apply {
    setExpression(PublicExpressionToProto(value.expression))
    setScale(value.scale)
    setRounding(value.rounding.name)
}.build()
internal fun PublicExpressionRoundFromProto(value: com.olap.prototype.wire.governance.PublicExpressionRound): com.olap.prototype.query.contract.catalog.PublicExpression.Round = com.olap.prototype.query.contract.catalog.PublicExpression.Round(
    expression = required(value.hasExpression(), "PublicExpressionRound.expression") { PublicExpressionFromProto(value.getExpression()) },
    scale = value.getScale(),
    rounding = com.olap.prototype.metric.MetricValueContract.Rounding.valueOf(value.getRounding())
)

internal fun PublicExpressionCastToProto(value: com.olap.prototype.query.contract.catalog.PublicExpression.Cast): com.olap.prototype.wire.governance.PublicExpressionCast = com.olap.prototype.wire.governance.PublicExpressionCast.newBuilder().apply {
    setExpression(PublicExpressionToProto(value.expression))
    setTargetType(value.targetType.name)
    setPrecision(CatalogOptionalIntToProto(value.precision))
    setScale(CatalogOptionalIntToProto(value.scale))
    setRounding(value.rounding.name)
}.build()
internal fun PublicExpressionCastFromProto(value: com.olap.prototype.wire.governance.PublicExpressionCast): com.olap.prototype.query.contract.catalog.PublicExpression.Cast = com.olap.prototype.query.contract.catalog.PublicExpression.Cast(
    expression = required(value.hasExpression(), "PublicExpressionCast.expression") { PublicExpressionFromProto(value.getExpression()) },
    targetType = com.olap.prototype.expression.SemanticExpression.ValueType.valueOf(value.getTargetType()),
    precision = required(value.hasPrecision(), "PublicExpressionCast.precision") { CatalogOptionalIntFromProto(value.getPrecision()) },
    scale = required(value.hasScale(), "PublicExpressionCast.scale") { CatalogOptionalIntFromProto(value.getScale()) },
    rounding = com.olap.prototype.metric.MetricValueContract.Rounding.valueOf(value.getRounding())
)

internal fun CatalogOptionalIntToProto(value: com.olap.prototype.query.contract.catalog.CatalogOptional<Int>): com.olap.prototype.wire.governance.CatalogOptionalInt = com.olap.prototype.wire.governance.CatalogOptionalInt.newBuilder().apply {
    setPresent(value.present)
    value.value?.let { setValue(it) }
}.build()
internal fun CatalogOptionalIntFromProto(value: com.olap.prototype.wire.governance.CatalogOptionalInt): com.olap.prototype.query.contract.catalog.CatalogOptional<Int> = com.olap.prototype.query.contract.catalog.CatalogOptional<Int>(
    present = value.getPresent(),
    value = if (value.hasValue()) value.getValue() else null
)

internal fun PublicExpressionExistsToProto(value: com.olap.prototype.query.contract.catalog.PublicExpression.Exists): com.olap.prototype.wire.governance.PublicExpressionExists = com.olap.prototype.wire.governance.PublicExpressionExists.newBuilder().apply {
    setInstanceId(value.instanceId)
    setFrom(SemanticProto.toProto(value.from))
    addAllAccess(value.access.map { SemanticJoinToProto(it) })
    setPredicate(PublicExpressionToProto(value.predicate))
}.build()
internal fun PublicExpressionExistsFromProto(value: com.olap.prototype.wire.governance.PublicExpressionExists): com.olap.prototype.query.contract.catalog.PublicExpression.Exists = com.olap.prototype.query.contract.catalog.PublicExpression.Exists(
    instanceId = value.getInstanceId(),
    from = required(value.hasFrom(), "PublicExpressionExists.from") { SemanticProto.fromProto(value.getFrom()) as com.olap.prototype.expression.SemanticExpression.RelationParent },
    access = value.getAccessList().map { SemanticJoinFromProto(it) },
    predicate = required(value.hasPredicate(), "PublicExpressionExists.predicate") { PublicExpressionFromProto(value.getPredicate()) }
)

internal fun SemanticJoinToProto(value: com.olap.prototype.query.contract.catalog.SemanticJoin): com.olap.prototype.wire.governance.SemanticJoin = com.olap.prototype.wire.governance.SemanticJoin.newBuilder().apply {
    setJoinType(value.joinType.name)
    setCardinality(value.cardinality.name)
    setNullPolicy(value.nullPolicy.name)
    addAllKeys(value.keys.map { SemanticJoinKeyPairToProto(it) })
    addAllIntervals(value.intervals.map { SemanticJoinIntervalToProto(it) })
    setSourceSealedSourceCollections(CatalogOptionalListSealedSourceCollectionMeaningToProto(value.sourceSealedSourceCollections))
    setTargetSealedSourceCollections(CatalogOptionalListSealedSourceCollectionMeaningToProto(value.targetSealedSourceCollections))
}.build()
internal fun SemanticJoinFromProto(value: com.olap.prototype.wire.governance.SemanticJoin): com.olap.prototype.query.contract.catalog.SemanticJoin = com.olap.prototype.query.contract.catalog.SemanticJoin(
    joinType = com.olap.prototype.query.SourceJoinContract.JoinType.valueOf(value.getJoinType()),
    cardinality = com.olap.prototype.query.SourceJoinContract.Cardinality.valueOf(value.getCardinality()),
    nullPolicy = com.olap.prototype.query.SourceJoinContract.NullPolicy.valueOf(value.getNullPolicy()),
    keys = value.getKeysList().map { SemanticJoinKeyPairFromProto(it) },
    intervals = value.getIntervalsList().map { SemanticJoinIntervalFromProto(it) },
    sourceSealedSourceCollections = required(value.hasSourceSealedSourceCollections(), "SemanticJoin.sourceSealedSourceCollections") { CatalogOptionalListSealedSourceCollectionMeaningFromProto(value.getSourceSealedSourceCollections()) },
    targetSealedSourceCollections = required(value.hasTargetSealedSourceCollections(), "SemanticJoin.targetSealedSourceCollections") { CatalogOptionalListSealedSourceCollectionMeaningFromProto(value.getTargetSealedSourceCollections()) }
)

internal fun SemanticJoinKeyPairToProto(value: com.olap.prototype.query.contract.catalog.SemanticJoin.KeyPair): com.olap.prototype.wire.governance.SemanticJoinKeyPair = com.olap.prototype.wire.governance.SemanticJoinKeyPair.newBuilder().apply {
    setSource(PublicExpressionToProto(value.source))
    setTarget(PublicExpressionToProto(value.target))
}.build()
internal fun SemanticJoinKeyPairFromProto(value: com.olap.prototype.wire.governance.SemanticJoinKeyPair): com.olap.prototype.query.contract.catalog.SemanticJoin.KeyPair = com.olap.prototype.query.contract.catalog.SemanticJoin.KeyPair(
    source = required(value.hasSource(), "SemanticJoinKeyPair.source") { PublicExpressionFromProto(value.getSource()) },
    target = required(value.hasTarget(), "SemanticJoinKeyPair.target") { PublicExpressionFromProto(value.getTarget()) }
)

internal fun SemanticJoinIntervalToProto(value: com.olap.prototype.query.contract.catalog.SemanticJoin.Interval): com.olap.prototype.wire.governance.SemanticJoinInterval = com.olap.prototype.wire.governance.SemanticJoinInterval.newBuilder().apply {
    setClock(SemanticJoinClockToProto(value.clock))
    setClockScope(value.clockScope.name)
    addAllClockAccess(value.clockAccess.map { SemanticJoinToProto(it) })
    setFrom(PublicExpressionToProto(value.from))
    setTo(PublicExpressionToProto(value.to))
    setFromInclusive(value.fromInclusive)
    setToInclusive(value.toInclusive)
    setOpenEndedTo(value.openEndedTo)
}.build()
internal fun SemanticJoinIntervalFromProto(value: com.olap.prototype.wire.governance.SemanticJoinInterval): com.olap.prototype.query.contract.catalog.SemanticJoin.Interval = com.olap.prototype.query.contract.catalog.SemanticJoin.Interval(
    clock = required(value.hasClock(), "SemanticJoinInterval.clock") { SemanticJoinClockFromProto(value.getClock()) },
    clockScope = com.olap.prototype.query.contract.catalog.SemanticJoin.ClockScope.valueOf(value.getClockScope()),
    clockAccess = value.getClockAccessList().map { SemanticJoinFromProto(it) },
    from = required(value.hasFrom(), "SemanticJoinInterval.from") { PublicExpressionFromProto(value.getFrom()) },
    to = required(value.hasTo(), "SemanticJoinInterval.to") { PublicExpressionFromProto(value.getTo()) },
    fromInclusive = value.getFromInclusive(),
    toInclusive = value.getToInclusive(),
    openEndedTo = value.getOpenEndedTo()
)

internal fun SemanticJoinClockToProto(value: com.olap.prototype.query.contract.catalog.SemanticJoin.Clock): com.olap.prototype.wire.governance.SemanticJoinClock = com.olap.prototype.wire.governance.SemanticJoinClock.newBuilder().apply { when (value) {
    is com.olap.prototype.query.contract.catalog.SemanticJoin.LogicClock -> setLogicClock(SemanticJoinLogicClockToProto(value))
    is com.olap.prototype.query.contract.catalog.SemanticJoin.QueryClock -> setQueryClock(SemanticJoinQueryClockToProto(value))
} }.build()
internal fun SemanticJoinClockFromProto(value: com.olap.prototype.wire.governance.SemanticJoinClock): com.olap.prototype.query.contract.catalog.SemanticJoin.Clock = when (value.kindCase) {
    com.olap.prototype.wire.governance.SemanticJoinClock.KindCase.LOGIC_CLOCK -> SemanticJoinLogicClockFromProto(value.getLogicClock())
    com.olap.prototype.wire.governance.SemanticJoinClock.KindCase.QUERY_CLOCK -> SemanticJoinQueryClockFromProto(value.getQueryClock())
    com.olap.prototype.wire.governance.SemanticJoinClock.KindCase.KIND_NOT_SET -> error("SemanticJoinClock variant is required")
}

internal fun SemanticJoinLogicClockToProto(value: com.olap.prototype.query.contract.catalog.SemanticJoin.LogicClock): com.olap.prototype.wire.governance.SemanticJoinLogicClock = com.olap.prototype.wire.governance.SemanticJoinLogicClock.newBuilder().apply {
    setLogicUnitId(value.logicUnitId)
    setLogicUnitVersion(value.logicUnitVersion)
}.build()
internal fun SemanticJoinLogicClockFromProto(value: com.olap.prototype.wire.governance.SemanticJoinLogicClock): com.olap.prototype.query.contract.catalog.SemanticJoin.LogicClock = com.olap.prototype.query.contract.catalog.SemanticJoin.LogicClock(
    logicUnitId = value.getLogicUnitId(),
    logicUnitVersion = value.getLogicUnitVersion()
)

internal fun SemanticJoinQueryClockToProto(value: com.olap.prototype.query.contract.catalog.SemanticJoin.QueryClock): com.olap.prototype.wire.governance.SemanticJoinQueryClock = com.olap.prototype.wire.governance.SemanticJoinQueryClock.newBuilder().apply {
    setClockRef(value.clockRef)
    setValueType(value.valueType.name)
    setDescription(value.description)
}.build()
internal fun SemanticJoinQueryClockFromProto(value: com.olap.prototype.wire.governance.SemanticJoinQueryClock): com.olap.prototype.query.contract.catalog.SemanticJoin.QueryClock = com.olap.prototype.query.contract.catalog.SemanticJoin.QueryClock(
    clockRef = value.getClockRef(),
    valueType = com.olap.prototype.query.ScalarValueType.valueOf(value.getValueType()),
    description = value.getDescription()
)

internal fun CatalogOptionalListSealedSourceCollectionMeaningToProto(value: com.olap.prototype.query.contract.catalog.CatalogOptional<List<com.olap.prototype.query.contract.SealedSourceCollectionMeaning>>): com.olap.prototype.wire.governance.CatalogOptionalListSealedSourceCollectionMeaning = com.olap.prototype.wire.governance.CatalogOptionalListSealedSourceCollectionMeaning.newBuilder().apply {
    setPresent(value.present)
    value.value?.let { setValue(com.olap.prototype.wire.governance.CatalogOptionalListSealedSourceCollectionMeaningValueValues.newBuilder().addAllValues(it.map { SealedSourceCollectionMeaningToProto(it) }).build()) }
}.build()
internal fun CatalogOptionalListSealedSourceCollectionMeaningFromProto(value: com.olap.prototype.wire.governance.CatalogOptionalListSealedSourceCollectionMeaning): com.olap.prototype.query.contract.catalog.CatalogOptional<List<com.olap.prototype.query.contract.SealedSourceCollectionMeaning>> = com.olap.prototype.query.contract.catalog.CatalogOptional<List<com.olap.prototype.query.contract.SealedSourceCollectionMeaning>>(
    present = value.getPresent(),
    value = if (value.hasValue()) value.getValue().getValuesList().map { SealedSourceCollectionMeaningFromProto(it) } else null
)

internal fun PublishedSemanticCatalogLogicUnitMeaningToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.LogicUnitMeaning): com.olap.prototype.wire.governance.PublishedSemanticCatalogLogicUnitMeaning = com.olap.prototype.wire.governance.PublishedSemanticCatalogLogicUnitMeaning.newBuilder().apply {
    setId(value.id)
    setVersion(value.version)
    setDisplayName(value.displayName)
    value.valueType?.let { setValueType(it.name) }
    value.valueShape?.let { setValueShape(SemanticProto.toProto(it)) }
}.build()
internal fun PublishedSemanticCatalogLogicUnitMeaningFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogLogicUnitMeaning): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.LogicUnitMeaning = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.LogicUnitMeaning(
    id = value.getId(),
    version = value.getVersion(),
    displayName = value.getDisplayName(),
    valueType = if (value.hasValueType()) com.olap.prototype.query.ScalarValueType.valueOf(value.getValueType()) else null,
    valueShape = if (value.hasValueShape()) com.olap.prototype.query.SetOutputContract.requireSourceContainer(SemanticProto.fromProto(value.getValueShape()), listOf("valueShape")) else null
)

internal fun PublishedSemanticCatalogMemberDefinitionToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.MemberDefinition): com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberDefinition = com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberDefinition.newBuilder().apply { when (value) {
    is com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.ComputedMeaning -> setComputedMeaning(PublishedSemanticCatalogComputedMeaningToProto(value))
    is com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.ProjectionMeaning -> setProjectionMeaning(PublishedSemanticCatalogProjectionMeaningToProto(value))
} }.build()
internal fun PublishedSemanticCatalogMemberDefinitionFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberDefinition): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.MemberDefinition = when (value.kindCase) {
    com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberDefinition.KindCase.COMPUTED_MEANING -> PublishedSemanticCatalogComputedMeaningFromProto(value.getComputedMeaning())
    com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberDefinition.KindCase.PROJECTION_MEANING -> PublishedSemanticCatalogProjectionMeaningFromProto(value.getProjectionMeaning())
    com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberDefinition.KindCase.KIND_NOT_SET -> error("PublishedSemanticCatalogMemberDefinition variant is required")
}

internal fun PublishedSemanticCatalogComputedMeaningToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.ComputedMeaning): com.olap.prototype.wire.governance.PublishedSemanticCatalogComputedMeaning = com.olap.prototype.wire.governance.PublishedSemanticCatalogComputedMeaning.newBuilder().apply {
    setCalculation(SemanticProto.toProto(value.calculation))
    setBindings(SemanticProto.toProto(value.bindings))
    addAllSlots(value.slots.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogComputedMeaningSlotsEntry.newBuilder().setKey(entry.key).apply { setValue(PublishedSemanticCatalogMemberSlotMeaningToProto(entry.value)) }.build() })
    addAllSourceScopes(value.sourceScopes.map { PublishedSemanticCatalogSourceExplanationToProto(it) })
    addAllParameters(value.parameters.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogComputedMeaningParametersEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    addAllFixedArguments(value.fixedArguments.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogComputedMeaningFixedArgumentsEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    addAllGroupingCandidates(value.groupingCandidates.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogComputedMeaningGroupingCandidatesEntry.newBuilder().setKey(entry.key).apply { setValue(com.olap.prototype.wire.governance.ListScopedInputContractDimensionRef.newBuilder().addAllValues(entry.value.map { SemanticProto.toProto(it) }).build()) }.build() })
    addAllMemberParameters(value.memberParameters.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogComputedMeaningMemberParametersEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    addAllRelationParameters(value.relationParameters.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogComputedMeaningRelationParametersEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    setDescription(CatalogOptionalStringToProto(value.description))
}.build()
internal fun PublishedSemanticCatalogComputedMeaningFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogComputedMeaning): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.ComputedMeaning = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.ComputedMeaning(
    calculation = required(value.hasCalculation(), "PublishedSemanticCatalogComputedMeaning.calculation") { SemanticProto.fromProto(value.getCalculation()) as com.olap.prototype.calculation.Calculation },
    bindings = required(value.hasBindings(), "PublishedSemanticCatalogComputedMeaning.bindings") { SemanticProto.fromProto(value.getBindings()) as com.olap.prototype.calculation.CalculationBindings },
    slots = strictMap(value.getSlotsList().map { it.getKey() to PublishedSemanticCatalogMemberSlotMeaningFromProto(it.getValue()) }),
    sourceScopes = value.getSourceScopesList().map { PublishedSemanticCatalogSourceExplanationFromProto(it) },
    parameters = strictMap(value.getParametersList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.Parameter }),
    fixedArguments = strictMap(value.getFixedArgumentsList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.Value }),
    groupingCandidates = strictMap(value.getGroupingCandidatesList().map { it.getKey() to it.getValue().getValuesList().map { SemanticProto.fromProto(it) as com.olap.prototype.query.ScopedInputContract.DimensionRef } }),
    memberParameters = strictMap(value.getMemberParametersList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.MemberSlot }),
    relationParameters = strictMap(value.getRelationParametersList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.RelationSlot }),
    description = required(value.hasDescription(), "PublishedSemanticCatalogComputedMeaning.description") { CatalogOptionalStringFromProto(value.getDescription()) }
)

internal fun PublishedSemanticCatalogMemberSlotMeaningToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.MemberSlotMeaning): com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberSlotMeaning = com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberSlotMeaning.newBuilder().apply { when (value) {
    is com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.ScalarSlotMeaning -> setScalarSlotMeaning(PublishedSemanticCatalogScalarSlotMeaningToProto(value))
    is com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.ContainerSlotMeaning -> setContainerSlotMeaning(PublishedSemanticCatalogContainerSlotMeaningToProto(value))
    is com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.ComputedContainerSlotMeaning -> setComputedContainerSlotMeaning(PublishedSemanticCatalogComputedContainerSlotMeaningToProto(value))
} }.build()
internal fun PublishedSemanticCatalogMemberSlotMeaningFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberSlotMeaning): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.MemberSlotMeaning = when (value.kindCase) {
    com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberSlotMeaning.KindCase.SCALAR_SLOT_MEANING -> PublishedSemanticCatalogScalarSlotMeaningFromProto(value.getScalarSlotMeaning())
    com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberSlotMeaning.KindCase.CONTAINER_SLOT_MEANING -> PublishedSemanticCatalogContainerSlotMeaningFromProto(value.getContainerSlotMeaning())
    com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberSlotMeaning.KindCase.COMPUTED_CONTAINER_SLOT_MEANING -> PublishedSemanticCatalogComputedContainerSlotMeaningFromProto(value.getComputedContainerSlotMeaning())
    com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberSlotMeaning.KindCase.KIND_NOT_SET -> error("PublishedSemanticCatalogMemberSlotMeaning variant is required")
}

internal fun PublishedSemanticCatalogScalarSlotMeaningToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.ScalarSlotMeaning): com.olap.prototype.wire.governance.PublishedSemanticCatalogScalarSlotMeaning = com.olap.prototype.wire.governance.PublishedSemanticCatalogScalarSlotMeaning.newBuilder().apply { when (value) {
    is com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.ComputedSlotMeaning -> setComputedSlotMeaning(PublishedSemanticCatalogComputedSlotMeaningToProto(value))
    is com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.SlotMeaning -> setSlotMeaning(PublishedSemanticCatalogSlotMeaningToProto(value))
} }.build()
internal fun PublishedSemanticCatalogScalarSlotMeaningFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogScalarSlotMeaning): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.ScalarSlotMeaning = when (value.kindCase) {
    com.olap.prototype.wire.governance.PublishedSemanticCatalogScalarSlotMeaning.KindCase.COMPUTED_SLOT_MEANING -> PublishedSemanticCatalogComputedSlotMeaningFromProto(value.getComputedSlotMeaning())
    com.olap.prototype.wire.governance.PublishedSemanticCatalogScalarSlotMeaning.KindCase.SLOT_MEANING -> PublishedSemanticCatalogSlotMeaningFromProto(value.getSlotMeaning())
    com.olap.prototype.wire.governance.PublishedSemanticCatalogScalarSlotMeaning.KindCase.KIND_NOT_SET -> error("PublishedSemanticCatalogScalarSlotMeaning variant is required")
}

internal fun PublishedSemanticCatalogComputedSlotMeaningToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.ComputedSlotMeaning): com.olap.prototype.wire.governance.PublishedSemanticCatalogComputedSlotMeaning = com.olap.prototype.wire.governance.PublishedSemanticCatalogComputedSlotMeaning.newBuilder().apply {
    setDisplayName(value.displayName)
    setRole(value.role)
    setValueType(value.valueType.name)
    value.valueContract?.let { setValueContract(SemanticProto.toProto(it)) }
    value.dimensionSlot?.let { setDimensionSlot(it) }
    setSymbol(value.symbol)
}.build()
internal fun PublishedSemanticCatalogComputedSlotMeaningFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogComputedSlotMeaning): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.ComputedSlotMeaning = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.ComputedSlotMeaning(
    displayName = value.getDisplayName(),
    role = value.getRole(),
    valueType = com.olap.prototype.query.ScalarValueType.valueOf(value.getValueType()),
    valueContract = if (value.hasValueContract()) SemanticProto.fromProto(value.getValueContract()) as com.olap.prototype.metric.MetricValueContract else null,
    dimensionSlot = if (value.hasDimensionSlot()) value.getDimensionSlot() else null,
    symbol = value.getSymbol()
)

internal fun PublishedSemanticCatalogSlotMeaningToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.SlotMeaning): com.olap.prototype.wire.governance.PublishedSemanticCatalogSlotMeaning = com.olap.prototype.wire.governance.PublishedSemanticCatalogSlotMeaning.newBuilder().apply {
    setDisplayName(value.displayName)
    setRole(value.role)
    setValueType(value.valueType.name)
    value.valueContract?.let { setValueContract(SemanticProto.toProto(it)) }
    setExpression(PublicExpressionToProto(value.expression))
    addAllAccess(value.access.map { SemanticJoinToProto(it) })
    addAllRoles(value.roles.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogSlotMeaningRolesEntry.newBuilder().setKey(entry.key).apply { setValue(com.olap.prototype.wire.governance.ListSemanticJoin.newBuilder().addAllValues(entry.value.map { SemanticJoinToProto(it) }).build()) }.build() })
}.build()
internal fun PublishedSemanticCatalogSlotMeaningFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogSlotMeaning): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.SlotMeaning = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.SlotMeaning(
    displayName = value.getDisplayName(),
    role = value.getRole(),
    valueType = com.olap.prototype.query.ScalarValueType.valueOf(value.getValueType()),
    valueContract = if (value.hasValueContract()) SemanticProto.fromProto(value.getValueContract()) as com.olap.prototype.metric.MetricValueContract else null,
    expression = required(value.hasExpression(), "PublishedSemanticCatalogSlotMeaning.expression") { PublicExpressionFromProto(value.getExpression()) },
    access = value.getAccessList().map { SemanticJoinFromProto(it) },
    roles = strictMap(value.getRolesList().map { it.getKey() to it.getValue().getValuesList().map { SemanticJoinFromProto(it) } })
)

internal fun PublishedSemanticCatalogContainerSlotMeaningToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.ContainerSlotMeaning): com.olap.prototype.wire.governance.PublishedSemanticCatalogContainerSlotMeaning = com.olap.prototype.wire.governance.PublishedSemanticCatalogContainerSlotMeaning.newBuilder().apply {
    setDisplayName(value.displayName)
    setRole(value.role)
    setShape(SemanticProto.toProto(value.shape))
    setExpression(PublicExpressionToProto(value.expression))
    addAllAccess(value.access.map { SemanticJoinToProto(it) })
    addAllRoles(value.roles.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogContainerSlotMeaningRolesEntry.newBuilder().setKey(entry.key).apply { setValue(com.olap.prototype.wire.governance.ListSemanticJoin.newBuilder().addAllValues(entry.value.map { SemanticJoinToProto(it) }).build()) }.build() })
}.build()
internal fun PublishedSemanticCatalogContainerSlotMeaningFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogContainerSlotMeaning): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.ContainerSlotMeaning = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.ContainerSlotMeaning(
    displayName = value.getDisplayName(),
    role = value.getRole(),
    shape = required(value.hasShape(), "PublishedSemanticCatalogContainerSlotMeaning.shape") { SemanticProto.fromProto(value.getShape()) as com.olap.prototype.query.SetOutputContract.Shape },
    expression = required(value.hasExpression(), "PublishedSemanticCatalogContainerSlotMeaning.expression") { PublicExpressionFromProto(value.getExpression()) },
    access = value.getAccessList().map { SemanticJoinFromProto(it) },
    roles = strictMap(value.getRolesList().map { it.getKey() to it.getValue().getValuesList().map { SemanticJoinFromProto(it) } })
)

internal fun PublishedSemanticCatalogComputedContainerSlotMeaningToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.ComputedContainerSlotMeaning): com.olap.prototype.wire.governance.PublishedSemanticCatalogComputedContainerSlotMeaning = com.olap.prototype.wire.governance.PublishedSemanticCatalogComputedContainerSlotMeaning.newBuilder().apply {
    setDisplayName(value.displayName)
    setRole(value.role)
    setShape(SemanticProto.toProto(value.shape))
    setSymbol(value.symbol)
}.build()
internal fun PublishedSemanticCatalogComputedContainerSlotMeaningFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogComputedContainerSlotMeaning): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.ComputedContainerSlotMeaning = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.ComputedContainerSlotMeaning(
    displayName = value.getDisplayName(),
    role = value.getRole(),
    shape = required(value.hasShape(), "PublishedSemanticCatalogComputedContainerSlotMeaning.shape") { SemanticProto.fromProto(value.getShape()) as com.olap.prototype.query.SetOutputContract.Shape },
    symbol = value.getSymbol()
)

internal fun CatalogOptionalStringToProto(value: com.olap.prototype.query.contract.catalog.CatalogOptional<String>): com.olap.prototype.wire.governance.CatalogOptionalString = com.olap.prototype.wire.governance.CatalogOptionalString.newBuilder().apply {
    setPresent(value.present)
    value.value?.let { setValue(it) }
}.build()
internal fun CatalogOptionalStringFromProto(value: com.olap.prototype.wire.governance.CatalogOptionalString): com.olap.prototype.query.contract.catalog.CatalogOptional<String> = com.olap.prototype.query.contract.catalog.CatalogOptional<String>(
    present = value.getPresent(),
    value = if (value.hasValue()) value.getValue() else null
)

internal fun PublishedSemanticCatalogProjectionMeaningToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.ProjectionMeaning): com.olap.prototype.wire.governance.PublishedSemanticCatalogProjectionMeaning = com.olap.prototype.wire.governance.PublishedSemanticCatalogProjectionMeaning.newBuilder().apply {
    setEvent(SemanticProto.toProto(value.event))
    addAllEventRoles(value.eventRoles.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogProjectionMeaningEventRolesEntry.newBuilder().setKey(entry.key).apply { setValue(com.olap.prototype.wire.governance.ListSemanticJoin.newBuilder().addAllValues(entry.value.map { SemanticJoinToProto(it) }).build()) }.build() })
    value.eventPredicate?.let { setEventPredicate(PublicExpressionToProto(it)) }
    addAllSlots(value.slots.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogProjectionMeaningSlotsEntry.newBuilder().setKey(entry.key).apply { setValue(PublishedSemanticCatalogMemberSlotMeaningToProto(entry.value)) }.build() })
    addAllLogicUnits(value.logicUnits.map { PublishedSemanticCatalogLogicUnitMeaningToProto(it) })
    addAllSealedSourceCollections(value.sealedSourceCollections.map { SealedSourceCollectionMeaningToProto(it) })
    setDescription(CatalogOptionalStringToProto(value.description))
}.build()
internal fun PublishedSemanticCatalogProjectionMeaningFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogProjectionMeaning): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.ProjectionMeaning = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.ProjectionMeaning(
    event = required(value.hasEvent(), "PublishedSemanticCatalogProjectionMeaning.event") { SemanticProto.fromProto(value.getEvent()) as com.olap.prototype.event.EventRef },
    eventRoles = strictMap(value.getEventRolesList().map { it.getKey() to it.getValue().getValuesList().map { SemanticJoinFromProto(it) } }),
    eventPredicate = if (value.hasEventPredicate()) PublicExpressionFromProto(value.getEventPredicate()) else null,
    slots = strictMap(value.getSlotsList().map { it.getKey() to PublishedSemanticCatalogMemberSlotMeaningFromProto(it.getValue()) }),
    logicUnits = value.getLogicUnitsList().map { PublishedSemanticCatalogLogicUnitMeaningFromProto(it) },
    sealedSourceCollections = value.getSealedSourceCollectionsList().map { SealedSourceCollectionMeaningFromProto(it) },
    description = required(value.hasDescription(), "PublishedSemanticCatalogProjectionMeaning.description") { CatalogOptionalStringFromProto(value.getDescription()) }
)

internal fun CatalogOptionalPublishedSemanticCatalogAggregationToProto(value: com.olap.prototype.query.contract.catalog.CatalogOptional<com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Aggregation>): com.olap.prototype.wire.governance.CatalogOptionalPublishedSemanticCatalogAggregation = com.olap.prototype.wire.governance.CatalogOptionalPublishedSemanticCatalogAggregation.newBuilder().apply {
    setPresent(value.present)
    value.value?.let { setValue(PublishedSemanticCatalogAggregationToProto(it)) }
}.build()
internal fun CatalogOptionalPublishedSemanticCatalogAggregationFromProto(value: com.olap.prototype.wire.governance.CatalogOptionalPublishedSemanticCatalogAggregation): com.olap.prototype.query.contract.catalog.CatalogOptional<com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Aggregation> = com.olap.prototype.query.contract.catalog.CatalogOptional<com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Aggregation>(
    present = value.getPresent(),
    value = if (value.hasValue()) PublishedSemanticCatalogAggregationFromProto(value.getValue()) else null
)

internal fun PublishedSemanticCatalogAggregationToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Aggregation): com.olap.prototype.wire.governance.PublishedSemanticCatalogAggregation = com.olap.prototype.wire.governance.PublishedSemanticCatalogAggregation.newBuilder().apply {
    setFunction(value.function.name)
    setDistinct(value.distinct)
    value.meanTarget?.let { setMeanTarget(it.name) }
}.build()
internal fun PublishedSemanticCatalogAggregationFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogAggregation): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Aggregation = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Aggregation(
    function = com.olap.prototype.metric.MetricAggregation.valueOf(value.getFunction()),
    distinct = value.getDistinct(),
    meanTarget = if (value.hasMeanTarget()) com.olap.prototype.metric.MeanTarget.valueOf(value.getMeanTarget()) else null
)

internal fun PublishedSemanticCatalogMemberToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Member): com.olap.prototype.wire.governance.PublishedSemanticCatalogMember = com.olap.prototype.wire.governance.PublishedSemanticCatalogMember.newBuilder().apply {
    setSourceId(value.sourceId)
    setSourceVersion(value.sourceVersion)
    setDisplayName(value.displayName)
    setEntity(value.entity)
    setRole(value.role)
    setShape(value.shape.name)
    addAllIdentitySlots(value.identitySlots.map { it })
    addAllOutputs(value.outputs.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberOutputsEntry.newBuilder().setKey(entry.key).apply { setValue(MemberCandidateOutputToProto(entry.value)) }.build() })
    setPermission(MemberQueryPermissionToProto(value.permission))
    addAllFilterTypes(value.filterTypes.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberFilterTypesEntry.newBuilder().setKey(entry.key).apply { setValue(entry.value.name) }.build() })
    addAllFilterValueContracts(value.filterValueContracts.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberFilterValueContractsEntry.newBuilder().setKey(entry.key).apply { entry.value?.let { setValue(SemanticProto.toProto(it)) } }.build() })
    addAllUnitDimensions(value.unitDimensions.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberUnitDimensionsEntry.newBuilder().setKey(entry.key).apply { setValue(com.olap.prototype.wire.governance.ListScopedInputContractDimensionRef.newBuilder().addAllValues(entry.value.map { SemanticProto.toProto(it) }).build()) }.build() })
    value.timeRange?.let { setTimeRange(MemberCandidateTimeRangeCapabilityToProto(it)) }
    addAllClockSlots(value.clockSlots.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberClockSlotsEntry.newBuilder().setKey(entry.key).apply { setValue(entry.value) }.build() })
    addAllParameters(value.parameters.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberParametersEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    addAllFixedArguments(value.fixedArguments.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberFixedArgumentsEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    addAllGroupingCandidates(value.groupingCandidates.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberGroupingCandidatesEntry.newBuilder().setKey(entry.key).apply { setValue(com.olap.prototype.wire.governance.ListScopedInputContractDimensionRef.newBuilder().addAllValues(entry.value.map { SemanticProto.toProto(it) }).build()) }.build() })
    addAllMemberParameters(value.memberParameters.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberMemberParametersEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
    addAllRelationParameters(value.relationParameters.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberRelationParametersEntry.newBuilder().setKey(entry.key).apply { setValue(SemanticProto.toProto(entry.value)) }.build() })
}.build()
internal fun PublishedSemanticCatalogMemberFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogMember): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Member = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Member(
    sourceId = value.getSourceId(),
    sourceVersion = value.getSourceVersion(),
    displayName = value.getDisplayName(),
    entity = value.getEntity(),
    role = value.getRole(),
    shape = com.olap.prototype.member.MemberShape.valueOf(value.getShape()),
    identitySlots = value.getIdentitySlotsList().map { it },
    outputs = strictMap(value.getOutputsList().map { it.getKey() to MemberCandidateOutputFromProto(it.getValue()) }),
    permission = required(value.hasPermission(), "PublishedSemanticCatalogMember.permission") { MemberQueryPermissionFromProto(value.getPermission()) },
    filterTypes = strictMap(value.getFilterTypesList().map { it.getKey() to com.olap.prototype.query.ScalarValueType.valueOf(it.getValue()) }),
    filterValueContracts = strictMap(value.getFilterValueContractsList().map { it.getKey() to if (it.hasValue()) SemanticProto.fromProto(it.getValue()) as com.olap.prototype.metric.MetricValueContract else null }),
    unitDimensions = strictMap(value.getUnitDimensionsList().map { it.getKey() to it.getValue().getValuesList().map { SemanticProto.fromProto(it) as com.olap.prototype.query.ScopedInputContract.DimensionRef } }),
    timeRange = if (value.hasTimeRange()) MemberCandidateTimeRangeCapabilityFromProto(value.getTimeRange()) else null,
    clockSlots = strictMap(value.getClockSlotsList().map { it.getKey() to it.getValue() }),
    parameters = strictMap(value.getParametersList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.Parameter }),
    fixedArguments = strictMap(value.getFixedArgumentsList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.Value }),
    groupingCandidates = strictMap(value.getGroupingCandidatesList().map { it.getKey() to it.getValue().getValuesList().map { SemanticProto.fromProto(it) as com.olap.prototype.query.ScopedInputContract.DimensionRef } }),
    memberParameters = strictMap(value.getMemberParametersList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.MemberSlot }),
    relationParameters = strictMap(value.getRelationParametersList().map { it.getKey() to SemanticProto.fromProto(it.getValue()) as com.olap.prototype.calculation.Calculation.RelationSlot })
)

internal fun PublishedSemanticCatalogRelationFilterToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.RelationFilter): com.olap.prototype.wire.governance.PublishedSemanticCatalogRelationFilter = com.olap.prototype.wire.governance.PublishedSemanticCatalogRelationFilter.newBuilder().apply {
    setId(value.id)
    setVersion(value.version)
    setDisplayName(value.displayName)
    setParentEvent(SemanticProto.toProto(value.parentEvent))
    setEntity(value.entity)
    setRole(value.role)
    setPermission(RelationFilterPermissionToProto(value.permission))
    addAllFilterTypes(value.filterTypes.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogRelationFilterFilterTypesEntry.newBuilder().setKey(entry.key).apply { setValue(entry.value.name) }.build() })
    addAllFilterValueContracts(value.filterValueContracts.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogRelationFilterFilterValueContractsEntry.newBuilder().setKey(entry.key).apply { entry.value?.let { setValue(SemanticProto.toProto(it)) } }.build() })
    value.timeRange?.let { setTimeRange(MemberCandidateTimeRangeCapabilityToProto(it)) }
    addAllClockSlots(value.clockSlots.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogRelationFilterClockSlotsEntry.newBuilder().setKey(entry.key).apply { setValue(entry.value) }.build() })
    setRequiredByDefault(value.requiredByDefault)
    addAllAssociation(value.association.map { SemanticJoinToProto(it) })
    setSourceDefinition(PublishedSemanticCatalogProjectionMeaningToProto(value.sourceDefinition))
}.build()
internal fun PublishedSemanticCatalogRelationFilterFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogRelationFilter): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.RelationFilter = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.RelationFilter(
    id = value.getId(),
    version = value.getVersion(),
    displayName = value.getDisplayName(),
    parentEvent = required(value.hasParentEvent(), "PublishedSemanticCatalogRelationFilter.parentEvent") { SemanticProto.fromProto(value.getParentEvent()) as com.olap.prototype.event.EventRef },
    entity = value.getEntity(),
    role = value.getRole(),
    permission = required(value.hasPermission(), "PublishedSemanticCatalogRelationFilter.permission") { RelationFilterPermissionFromProto(value.getPermission()) },
    filterTypes = strictMap(value.getFilterTypesList().map { it.getKey() to com.olap.prototype.query.ScalarValueType.valueOf(it.getValue()) }),
    filterValueContracts = strictMap(value.getFilterValueContractsList().map { it.getKey() to if (it.hasValue()) SemanticProto.fromProto(it.getValue()) as com.olap.prototype.metric.MetricValueContract else null }),
    timeRange = if (value.hasTimeRange()) MemberCandidateTimeRangeCapabilityFromProto(value.getTimeRange()) else null,
    clockSlots = strictMap(value.getClockSlotsList().map { it.getKey() to it.getValue() }),
    requiredByDefault = value.getRequiredByDefault(),
    association = value.getAssociationList().map { SemanticJoinFromProto(it) },
    sourceDefinition = required(value.hasSourceDefinition(), "PublishedSemanticCatalogRelationFilter.sourceDefinition") { PublishedSemanticCatalogProjectionMeaningFromProto(value.getSourceDefinition()) }
)

internal fun PublishedSemanticCatalogDimensionToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Dimension): com.olap.prototype.wire.governance.PublishedSemanticCatalogDimension = com.olap.prototype.wire.governance.PublishedSemanticCatalogDimension.newBuilder().apply {
    setId(value.id)
    setVersion(value.version)
    setDisplayName(value.displayName)
    addAllLevels(value.levels.map { it })
    addAllValueTypes(value.valueTypes.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogDimensionValueTypesEntry.newBuilder().setKey(entry.key).apply { setValue(entry.value.name) }.build() })
    setSourceDefinition(PublishedSemanticCatalogDimensionDefinitionToProto(value.sourceDefinition))
    addAllEventMappings(value.eventMappings.map { PublishedSemanticCatalogEventMappingToProto(it) })
}.build()
internal fun PublishedSemanticCatalogDimensionFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogDimension): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Dimension = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Dimension(
    id = value.getId(),
    version = value.getVersion(),
    displayName = value.getDisplayName(),
    levels = value.getLevelsList().map { it },
    valueTypes = strictMap(value.getValueTypesList().map { it.getKey() to com.olap.prototype.query.ScalarValueType.valueOf(it.getValue()) }),
    sourceDefinition = required(value.hasSourceDefinition(), "PublishedSemanticCatalogDimension.sourceDefinition") { PublishedSemanticCatalogDimensionDefinitionFromProto(value.getSourceDefinition()) },
    eventMappings = value.getEventMappingsList().map { PublishedSemanticCatalogEventMappingFromProto(it) }
)

internal fun PublishedSemanticCatalogDimensionDefinitionToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.DimensionDefinition): com.olap.prototype.wire.governance.PublishedSemanticCatalogDimensionDefinition = com.olap.prototype.wire.governance.PublishedSemanticCatalogDimensionDefinition.newBuilder().apply {
    setInterpretation(value.interpretation.name)
    value.description?.let { setDescription(it) }
    addAllMappings(value.mappings.map { PublishedSemanticCatalogDefinitionMappingToProto(it) })
    addAllLogicUnits(value.logicUnits.map { PublishedSemanticCatalogLogicUnitMeaningToProto(it) })
}.build()
internal fun PublishedSemanticCatalogDimensionDefinitionFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogDimensionDefinition): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.DimensionDefinition = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.DimensionDefinition(
    interpretation = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.DimensionInterpretation.valueOf(value.getInterpretation()),
    description = if (value.hasDescription()) value.getDescription() else null,
    mappings = value.getMappingsList().map { PublishedSemanticCatalogDefinitionMappingFromProto(it) },
    logicUnits = value.getLogicUnitsList().map { PublishedSemanticCatalogLogicUnitMeaningFromProto(it) }
)

internal fun PublishedSemanticCatalogDefinitionMappingToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.DefinitionMapping): com.olap.prototype.wire.governance.PublishedSemanticCatalogDefinitionMapping = com.olap.prototype.wire.governance.PublishedSemanticCatalogDefinitionMapping.newBuilder().apply {
    setLevelCode(value.levelCode)
    setEventId(value.eventId)
    setEventVersion(value.eventVersion)
    setExpression(PublicExpressionToProto(value.expression))
    addAllAccess(value.access.map { SemanticJoinToProto(it) })
    addAllRoles(value.roles.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogDefinitionMappingRolesEntry.newBuilder().setKey(entry.key).apply { setValue(com.olap.prototype.wire.governance.ListSemanticJoin.newBuilder().addAllValues(entry.value.map { SemanticJoinToProto(it) }).build()) }.build() })
    setSealedSourceCollections(CatalogOptionalListSealedSourceCollectionMeaningToProto(value.sealedSourceCollections))
}.build()
internal fun PublishedSemanticCatalogDefinitionMappingFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogDefinitionMapping): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.DefinitionMapping = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.DefinitionMapping(
    levelCode = value.getLevelCode(),
    eventId = value.getEventId(),
    eventVersion = value.getEventVersion(),
    expression = required(value.hasExpression(), "PublishedSemanticCatalogDefinitionMapping.expression") { PublicExpressionFromProto(value.getExpression()) },
    access = value.getAccessList().map { SemanticJoinFromProto(it) },
    roles = strictMap(value.getRolesList().map { it.getKey() to it.getValue().getValuesList().map { SemanticJoinFromProto(it) } }),
    sealedSourceCollections = required(value.hasSealedSourceCollections(), "PublishedSemanticCatalogDefinitionMapping.sealedSourceCollections") { CatalogOptionalListSealedSourceCollectionMeaningFromProto(value.getSealedSourceCollections()) }
)

internal fun PublishedSemanticCatalogEventMappingToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.EventMapping): com.olap.prototype.wire.governance.PublishedSemanticCatalogEventMapping = com.olap.prototype.wire.governance.PublishedSemanticCatalogEventMapping.newBuilder().apply {
    setMappingId(value.mappingId)
    setLevelCode(value.levelCode)
    setLevelDisplayName(value.levelDisplayName)
    setEventId(value.eventId)
    setEventVersion(value.eventVersion)
    setEventDisplayName(value.eventDisplayName)
    value.valueAlignmentKey?.let { setValueAlignmentKey(it) }
    setValueAlignmentStatus(value.valueAlignmentStatus)
}.build()
internal fun PublishedSemanticCatalogEventMappingFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogEventMapping): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.EventMapping = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.EventMapping(
    mappingId = value.getMappingId(),
    levelCode = value.getLevelCode(),
    levelDisplayName = value.getLevelDisplayName(),
    eventId = value.getEventId(),
    eventVersion = value.getEventVersion(),
    eventDisplayName = value.getEventDisplayName(),
    valueAlignmentKey = if (value.hasValueAlignmentKey()) value.getValueAlignmentKey() else null,
    valueAlignmentStatus = value.getValueAlignmentStatus()
)

internal fun PublishedSemanticCatalogTimeDimensionToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.TimeDimension): com.olap.prototype.wire.governance.PublishedSemanticCatalogTimeDimension = com.olap.prototype.wire.governance.PublishedSemanticCatalogTimeDimension.newBuilder().apply {
    setId(value.id)
    setVersion(value.version)
    setDisplayName(value.displayName)
    addAllLevels(value.levels.map { it })
    addAllValueTypes(value.valueTypes.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogTimeDimensionValueTypesEntry.newBuilder().setKey(entry.key).apply { setValue(entry.value.name) }.build() })
    setSourceDefinition(PublishedSemanticCatalogDimensionDefinitionToProto(value.sourceDefinition))
    addAllAnchors(value.anchors.map { PublishedSemanticCatalogAnchorToProto(it) })
}.build()
internal fun PublishedSemanticCatalogTimeDimensionFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogTimeDimension): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.TimeDimension = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.TimeDimension(
    id = value.getId(),
    version = value.getVersion(),
    displayName = value.getDisplayName(),
    levels = value.getLevelsList().map { it },
    valueTypes = strictMap(value.getValueTypesList().map { it.getKey() to com.olap.prototype.query.ScalarValueType.valueOf(it.getValue()) }),
    sourceDefinition = required(value.hasSourceDefinition(), "PublishedSemanticCatalogTimeDimension.sourceDefinition") { PublishedSemanticCatalogDimensionDefinitionFromProto(value.getSourceDefinition()) },
    anchors = value.getAnchorsList().map { PublishedSemanticCatalogAnchorFromProto(it) }
)

internal fun PublishedSemanticCatalogAnchorToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Anchor): com.olap.prototype.wire.governance.PublishedSemanticCatalogAnchor = com.olap.prototype.wire.governance.PublishedSemanticCatalogAnchor.newBuilder().apply {
    setMappingId(value.mappingId)
    setLevelCode(value.levelCode)
    setEventId(value.eventId)
    setEventVersion(value.eventVersion)
    setEventDisplayName(value.eventDisplayName)
    setTimeRole(value.timeRole)
}.build()
internal fun PublishedSemanticCatalogAnchorFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogAnchor): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Anchor = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Anchor(
    mappingId = value.getMappingId(),
    levelCode = value.getLevelCode(),
    eventId = value.getEventId(),
    eventVersion = value.getEventVersion(),
    eventDisplayName = value.getEventDisplayName(),
    timeRole = value.getTimeRole()
)

internal fun PublishedSemanticCatalogCombinationToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Combination): com.olap.prototype.wire.governance.PublishedSemanticCatalogCombination = com.olap.prototype.wire.governance.PublishedSemanticCatalogCombination.newBuilder().apply {
    setInstance(SemanticProto.toProto(value.instance))
    setDimensionId(value.dimensionId)
    setDimensionVersion(value.dimensionVersion)
    setLevelCode(value.levelCode)
    setAvailable(value.available)
    value.reasonCode?.let { setReasonCode(it) }
    value.reason?.let { setReason(it) }
}.build()
internal fun PublishedSemanticCatalogCombinationFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogCombination): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Combination = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Combination(
    instance = required(value.hasInstance(), "PublishedSemanticCatalogCombination.instance") { SemanticProto.fromProto(value.getInstance()) as com.olap.prototype.calculation.MetricInstanceRef },
    dimensionId = value.getDimensionId(),
    dimensionVersion = value.getDimensionVersion(),
    levelCode = value.getLevelCode(),
    available = value.getAvailable(),
    reasonCode = if (value.hasReasonCode()) value.getReasonCode() else null,
    reason = if (value.hasReason()) value.getReason() else null
)

internal fun PublishedSemanticCatalogFilterToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Filter): com.olap.prototype.wire.governance.PublishedSemanticCatalogFilter = com.olap.prototype.wire.governance.PublishedSemanticCatalogFilter.newBuilder().apply {
    setId(value.id)
    setVersion(value.version)
    setDisplayName(value.displayName)
    setDimensionRef(PublishedSemanticCatalogAssetReferenceToProto(value.dimensionRef))
    addAllOperators(value.operators.map { it })
    addAllValueTypes(value.valueTypes.entries.sortedBy { it.key.toString() }.map { entry -> com.olap.prototype.wire.governance.PublishedSemanticCatalogFilterValueTypesEntry.newBuilder().setKey(entry.key).apply { setValue(entry.value.name) }.build() })
    setRequiredByDefault(value.requiredByDefault)
    addAllEventMappings(value.eventMappings.map { PublishedSemanticCatalogFilterMappingToProto(it) })
}.build()
internal fun PublishedSemanticCatalogFilterFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogFilter): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Filter = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.Filter(
    id = value.getId(),
    version = value.getVersion(),
    displayName = value.getDisplayName(),
    dimensionRef = required(value.hasDimensionRef(), "PublishedSemanticCatalogFilter.dimensionRef") { PublishedSemanticCatalogAssetReferenceFromProto(value.getDimensionRef()) },
    operators = value.getOperatorsList().map { it },
    valueTypes = strictMap(value.getValueTypesList().map { it.getKey() to com.olap.prototype.query.ScalarValueType.valueOf(it.getValue()) }),
    requiredByDefault = value.getRequiredByDefault(),
    eventMappings = value.getEventMappingsList().map { PublishedSemanticCatalogFilterMappingFromProto(it) }
)

internal fun PublishedSemanticCatalogAssetReferenceToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.AssetReference): com.olap.prototype.wire.governance.PublishedSemanticCatalogAssetReference = com.olap.prototype.wire.governance.PublishedSemanticCatalogAssetReference.newBuilder().apply {
    setId(value.id)
    setVersion(value.version)
}.build()
internal fun PublishedSemanticCatalogAssetReferenceFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogAssetReference): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.AssetReference = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.AssetReference(
    id = value.getId(),
    version = value.getVersion()
)

internal fun PublishedSemanticCatalogFilterMappingToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.FilterMapping): com.olap.prototype.wire.governance.PublishedSemanticCatalogFilterMapping = com.olap.prototype.wire.governance.PublishedSemanticCatalogFilterMapping.newBuilder().apply {
    setLevelCode(value.levelCode)
    setEventId(value.eventId)
    setEventVersion(value.eventVersion)
}.build()
internal fun PublishedSemanticCatalogFilterMappingFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogFilterMapping): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.FilterMapping = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.FilterMapping(
    levelCode = value.getLevelCode(),
    eventId = value.getEventId(),
    eventVersion = value.getEventVersion()
)

internal fun PublishedSemanticCatalogSemanticDependencyToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.SemanticDependency): com.olap.prototype.wire.governance.PublishedSemanticCatalogSemanticDependency = com.olap.prototype.wire.governance.PublishedSemanticCatalogSemanticDependency.newBuilder().apply { when (value) {
    is com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.CalculationDependency -> setCalculationDependency(PublishedSemanticCatalogCalculationDependencyToProto(value))
    is com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.FactDependency -> setFactDependency(PublishedSemanticCatalogFactDependencyToProto(value))
    is com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.InstanceDependency -> setInstanceDependency(PublishedSemanticCatalogInstanceDependencyToProto(value))
    is com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.MemberDependency -> setMemberDependency(PublishedSemanticCatalogMemberDependencyToProto(value))
} }.build()
internal fun PublishedSemanticCatalogSemanticDependencyFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogSemanticDependency): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.SemanticDependency = when (value.kindCase) {
    com.olap.prototype.wire.governance.PublishedSemanticCatalogSemanticDependency.KindCase.CALCULATION_DEPENDENCY -> PublishedSemanticCatalogCalculationDependencyFromProto(value.getCalculationDependency())
    com.olap.prototype.wire.governance.PublishedSemanticCatalogSemanticDependency.KindCase.FACT_DEPENDENCY -> PublishedSemanticCatalogFactDependencyFromProto(value.getFactDependency())
    com.olap.prototype.wire.governance.PublishedSemanticCatalogSemanticDependency.KindCase.INSTANCE_DEPENDENCY -> PublishedSemanticCatalogInstanceDependencyFromProto(value.getInstanceDependency())
    com.olap.prototype.wire.governance.PublishedSemanticCatalogSemanticDependency.KindCase.MEMBER_DEPENDENCY -> PublishedSemanticCatalogMemberDependencyFromProto(value.getMemberDependency())
    com.olap.prototype.wire.governance.PublishedSemanticCatalogSemanticDependency.KindCase.KIND_NOT_SET -> error("PublishedSemanticCatalogSemanticDependency variant is required")
}

internal fun PublishedSemanticCatalogCalculationDependencyToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.CalculationDependency): com.olap.prototype.wire.governance.PublishedSemanticCatalogCalculationDependency = com.olap.prototype.wire.governance.PublishedSemanticCatalogCalculationDependency.newBuilder().apply {
    setReference(SemanticProto.toProto(value.reference))
    setDefinition(SemanticProto.toProto(value.definition))
}.build()
internal fun PublishedSemanticCatalogCalculationDependencyFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogCalculationDependency): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.CalculationDependency = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.CalculationDependency(
    reference = required(value.hasReference(), "PublishedSemanticCatalogCalculationDependency.reference") { SemanticProto.fromProto(value.getReference()) as com.olap.prototype.calculation.MetricRef },
    definition = required(value.hasDefinition(), "PublishedSemanticCatalogCalculationDependency.definition") { SemanticProto.fromProto(value.getDefinition()) as com.olap.prototype.calculation.MetricCalculation }
)

internal fun PublishedSemanticCatalogFactDependencyToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.FactDependency): com.olap.prototype.wire.governance.PublishedSemanticCatalogFactDependency = com.olap.prototype.wire.governance.PublishedSemanticCatalogFactDependency.newBuilder().apply {
    setReference(SemanticProto.toProto(value.reference))
    setEvent(SemanticProto.toProto(value.event))
    setAggregation(PublishedSemanticCatalogAggregationToProto(value.aggregation))
    setValueContract(SemanticProto.toProto(value.valueContract))
    setSourceScope(PublishedSemanticCatalogFactScopeToProto(value.sourceScope))
}.build()
internal fun PublishedSemanticCatalogFactDependencyFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogFactDependency): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.FactDependency = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.FactDependency(
    reference = required(value.hasReference(), "PublishedSemanticCatalogFactDependency.reference") { SemanticProto.fromProto(value.getReference()) as com.olap.prototype.calculation.MetricRef },
    event = required(value.hasEvent(), "PublishedSemanticCatalogFactDependency.event") { SemanticProto.fromProto(value.getEvent()) as com.olap.prototype.event.EventRef },
    aggregation = required(value.hasAggregation(), "PublishedSemanticCatalogFactDependency.aggregation") { PublishedSemanticCatalogAggregationFromProto(value.getAggregation()) },
    valueContract = required(value.hasValueContract(), "PublishedSemanticCatalogFactDependency.valueContract") { SemanticProto.fromProto(value.getValueContract()) as com.olap.prototype.metric.MetricValueContract },
    sourceScope = required(value.hasSourceScope(), "PublishedSemanticCatalogFactDependency.sourceScope") { PublishedSemanticCatalogFactScopeFromProto(value.getSourceScope()) }
)

internal fun PublishedSemanticCatalogInstanceDependencyToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.InstanceDependency): com.olap.prototype.wire.governance.PublishedSemanticCatalogInstanceDependency = com.olap.prototype.wire.governance.PublishedSemanticCatalogInstanceDependency.newBuilder().apply {
    setReference(SemanticProto.toProto(value.reference))
    setInstance(SemanticProto.toProto(value.instance))
}.build()
internal fun PublishedSemanticCatalogInstanceDependencyFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogInstanceDependency): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.InstanceDependency = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.InstanceDependency(
    reference = required(value.hasReference(), "PublishedSemanticCatalogInstanceDependency.reference") { SemanticProto.fromProto(value.getReference()) as com.olap.prototype.calculation.MetricInstanceRef },
    instance = required(value.hasInstance(), "PublishedSemanticCatalogInstanceDependency.instance") { SemanticProto.fromProto(value.getInstance()) as com.olap.prototype.calculation.MetricInstance }
)

internal fun PublishedSemanticCatalogMemberDependencyToProto(value: com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.MemberDependency): com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberDependency = com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberDependency.newBuilder().apply {
    setReference(SemanticProto.toProto(value.reference))
    setEntity(value.entity)
    setRole(value.role)
    setShape(value.shape.name)
    addAllIdentitySlots(value.identitySlots.map { it })
    setDefinition(PublishedSemanticCatalogMemberDefinitionToProto(value.definition))
}.build()
internal fun PublishedSemanticCatalogMemberDependencyFromProto(value: com.olap.prototype.wire.governance.PublishedSemanticCatalogMemberDependency): com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.MemberDependency = com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.MemberDependency(
    reference = required(value.hasReference(), "PublishedSemanticCatalogMemberDependency.reference") { SemanticProto.fromProto(value.getReference()) as com.olap.prototype.member.MemberRef },
    entity = value.getEntity(),
    role = value.getRole(),
    shape = com.olap.prototype.member.MemberShape.valueOf(value.getShape()),
    identitySlots = value.getIdentitySlotsList().map { it },
    definition = required(value.hasDefinition(), "PublishedSemanticCatalogMemberDependency.definition") { PublishedSemanticCatalogMemberDefinitionFromProto(value.getDefinition()) }
)

internal fun SemanticCatalogReadsSearchRequestToProto(value: com.olap.prototype.query.contract.catalog.SemanticCatalogReads.SearchRequest): com.olap.prototype.wire.governance.SemanticCatalogReadsSearchRequest = com.olap.prototype.wire.governance.SemanticCatalogReadsSearchRequest.newBuilder().apply {
    setPublication(QueryProto.toProto(value.publication))
    setQuery(value.query)
    value.kind?.let { setKind(it.name) }
    setOffset(value.offset)
    setLimit(value.limit)
}.build()
internal fun SemanticCatalogReadsSearchRequestFromProto(value: com.olap.prototype.wire.governance.SemanticCatalogReadsSearchRequest): com.olap.prototype.query.contract.catalog.SemanticCatalogReads.SearchRequest = com.olap.prototype.query.contract.catalog.SemanticCatalogReads.SearchRequest(
    publication = required(value.hasPublication(), "SemanticCatalogReadsSearchRequest.publication") { QueryProto.fromProto(value.getPublication()) as com.olap.prototype.query.contract.QueryCapabilityRequest },
    query = value.getQuery(),
    kind = if (value.hasKind()) com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Kind.valueOf(value.getKind()) else null,
    offset = value.getOffset(),
    limit = value.getLimit()
)

internal fun SemanticCatalogReadsSearchPageToProto(value: com.olap.prototype.query.contract.catalog.SemanticCatalogReads.SearchPage): com.olap.prototype.wire.governance.SemanticCatalogReadsSearchPage = com.olap.prototype.wire.governance.SemanticCatalogReadsSearchPage.newBuilder().apply {
    addAllItems(value.items.map { SemanticCatalogReadsSearchItemToProto(it) })
    setTotal(value.total)
    setOffset(value.offset)
    value.nextOffset?.let { setNextOffset(it) }
}.build()
internal fun SemanticCatalogReadsSearchPageFromProto(value: com.olap.prototype.wire.governance.SemanticCatalogReadsSearchPage): com.olap.prototype.query.contract.catalog.SemanticCatalogReads.SearchPage = com.olap.prototype.query.contract.catalog.SemanticCatalogReads.SearchPage(
    items = value.getItemsList().map { SemanticCatalogReadsSearchItemFromProto(it) },
    total = value.getTotal(),
    offset = value.getOffset(),
    nextOffset = if (value.hasNextOffset()) value.getNextOffset() else null
)

internal fun SemanticCatalogReadsSearchItemToProto(value: com.olap.prototype.query.contract.catalog.SemanticCatalogReads.SearchItem): com.olap.prototype.wire.governance.SemanticCatalogReadsSearchItem = com.olap.prototype.wire.governance.SemanticCatalogReadsSearchItem.newBuilder().apply {
    setReference(SemanticCatalogReadsAssetReferenceToProto(value.reference))
    setDisplayName(value.displayName)
    setBusinessSummary(value.businessSummary)
}.build()
internal fun SemanticCatalogReadsSearchItemFromProto(value: com.olap.prototype.wire.governance.SemanticCatalogReadsSearchItem): com.olap.prototype.query.contract.catalog.SemanticCatalogReads.SearchItem = com.olap.prototype.query.contract.catalog.SemanticCatalogReads.SearchItem(
    reference = required(value.hasReference(), "SemanticCatalogReadsSearchItem.reference") { SemanticCatalogReadsAssetReferenceFromProto(value.getReference()) },
    displayName = value.getDisplayName(),
    businessSummary = value.getBusinessSummary()
)

internal fun SemanticCatalogReadsAssetReferenceToProto(value: com.olap.prototype.query.contract.catalog.SemanticCatalogReads.AssetReference): com.olap.prototype.wire.governance.SemanticCatalogReadsAssetReference = com.olap.prototype.wire.governance.SemanticCatalogReadsAssetReference.newBuilder().apply {
    setCatalog(SemanticCatalogReadsCatalogReferenceToProto(value.catalog))
    setKind(value.kind.name)
    setId(value.id)
    setVersion(value.version)
}.build()
internal fun SemanticCatalogReadsAssetReferenceFromProto(value: com.olap.prototype.wire.governance.SemanticCatalogReadsAssetReference): com.olap.prototype.query.contract.catalog.SemanticCatalogReads.AssetReference = com.olap.prototype.query.contract.catalog.SemanticCatalogReads.AssetReference(
    catalog = required(value.hasCatalog(), "SemanticCatalogReadsAssetReference.catalog") { SemanticCatalogReadsCatalogReferenceFromProto(value.getCatalog()) },
    kind = com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Kind.valueOf(value.getKind()),
    id = value.getId(),
    version = value.getVersion()
)

internal fun SemanticCatalogReadsCatalogReferenceToProto(value: com.olap.prototype.query.contract.catalog.SemanticCatalogReads.CatalogReference): com.olap.prototype.wire.governance.SemanticCatalogReadsCatalogReference = com.olap.prototype.wire.governance.SemanticCatalogReadsCatalogReference.newBuilder().apply {
    setCoordinate(PublishedSemanticCatalogCoordinateToProto(value.coordinate))
    setSha256(value.sha256)
}.build()
internal fun SemanticCatalogReadsCatalogReferenceFromProto(value: com.olap.prototype.wire.governance.SemanticCatalogReadsCatalogReference): com.olap.prototype.query.contract.catalog.SemanticCatalogReads.CatalogReference = com.olap.prototype.query.contract.catalog.SemanticCatalogReads.CatalogReference(
    coordinate = required(value.hasCoordinate(), "SemanticCatalogReadsCatalogReference.coordinate") { PublishedSemanticCatalogCoordinateFromProto(value.getCoordinate()) },
    sha256 = value.getSha256()
)

internal fun SemanticCatalogReadsCatalogRequestToProto(value: com.olap.prototype.query.contract.catalog.SemanticCatalogReads.CatalogRequest): com.olap.prototype.wire.governance.SemanticCatalogReadsCatalogRequest = com.olap.prototype.wire.governance.SemanticCatalogReadsCatalogRequest.newBuilder().apply {
    setReference(SemanticCatalogReadsCatalogReferenceToProto(value.reference))
}.build()
internal fun SemanticCatalogReadsCatalogRequestFromProto(value: com.olap.prototype.wire.governance.SemanticCatalogReadsCatalogRequest): com.olap.prototype.query.contract.catalog.SemanticCatalogReads.CatalogRequest = com.olap.prototype.query.contract.catalog.SemanticCatalogReads.CatalogRequest(
    reference = required(value.hasReference(), "SemanticCatalogReadsCatalogRequest.reference") { SemanticCatalogReadsCatalogReferenceFromProto(value.getReference()) }
)

internal fun SemanticCatalogReadsAssetRequestToProto(value: com.olap.prototype.query.contract.catalog.SemanticCatalogReads.AssetRequest): com.olap.prototype.wire.governance.SemanticCatalogReadsAssetRequest = com.olap.prototype.wire.governance.SemanticCatalogReadsAssetRequest.newBuilder().apply {
    setReference(SemanticCatalogReadsAssetReferenceToProto(value.reference))
}.build()
internal fun SemanticCatalogReadsAssetRequestFromProto(value: com.olap.prototype.wire.governance.SemanticCatalogReadsAssetRequest): com.olap.prototype.query.contract.catalog.SemanticCatalogReads.AssetRequest = com.olap.prototype.query.contract.catalog.SemanticCatalogReads.AssetRequest(
    reference = required(value.hasReference(), "SemanticCatalogReadsAssetRequest.reference") { SemanticCatalogReadsAssetReferenceFromProto(value.getReference()) }
)

internal fun SemanticCatalogReadsAssetResultToProto(value: com.olap.prototype.query.contract.catalog.SemanticCatalogReads.AssetResult): com.olap.prototype.wire.governance.SemanticCatalogReadsAssetResult = com.olap.prototype.wire.governance.SemanticCatalogReadsAssetResult.newBuilder().apply {
    setReference(SemanticCatalogReadsAssetReferenceToProto(value.reference))
    setAsset(SemanticCatalogReadsAssetToProto(value.asset))
}.build()
internal fun SemanticCatalogReadsAssetResultFromProto(value: com.olap.prototype.wire.governance.SemanticCatalogReadsAssetResult): com.olap.prototype.query.contract.catalog.SemanticCatalogReads.AssetResult = com.olap.prototype.query.contract.catalog.SemanticCatalogReads.AssetResult(
    reference = required(value.hasReference(), "SemanticCatalogReadsAssetResult.reference") { SemanticCatalogReadsAssetReferenceFromProto(value.getReference()) },
    asset = required(value.hasAsset(), "SemanticCatalogReadsAssetResult.asset") { SemanticCatalogReadsAssetFromProto(value.getAsset()) }
)

internal fun SemanticCatalogReadsAssetToProto(value: com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset): com.olap.prototype.wire.governance.SemanticCatalogReadsAsset = com.olap.prototype.wire.governance.SemanticCatalogReadsAsset.newBuilder().apply { when (value) {
    is com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.ScenarioAsset -> setScenarioAsset(SemanticCatalogReadsAssetScenarioAssetToProto(value))
    is com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.MetricAsset -> setMetricAsset(SemanticCatalogReadsAssetMetricAssetToProto(value))
    is com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.MemberAsset -> setMemberAsset(SemanticCatalogReadsAssetMemberAssetToProto(value))
    is com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.RelationFilterAsset -> setRelationFilterAsset(SemanticCatalogReadsAssetRelationFilterAssetToProto(value))
    is com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.DimensionAsset -> setDimensionAsset(SemanticCatalogReadsAssetDimensionAssetToProto(value))
    is com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.TimeDimensionAsset -> setTimeDimensionAsset(SemanticCatalogReadsAssetTimeDimensionAssetToProto(value))
    is com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.FilterAsset -> setFilterAsset(SemanticCatalogReadsAssetFilterAssetToProto(value))
} }.build()
internal fun SemanticCatalogReadsAssetFromProto(value: com.olap.prototype.wire.governance.SemanticCatalogReadsAsset): com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset = when (value.kindCase) {
    com.olap.prototype.wire.governance.SemanticCatalogReadsAsset.KindCase.SCENARIO_ASSET -> SemanticCatalogReadsAssetScenarioAssetFromProto(value.getScenarioAsset())
    com.olap.prototype.wire.governance.SemanticCatalogReadsAsset.KindCase.METRIC_ASSET -> SemanticCatalogReadsAssetMetricAssetFromProto(value.getMetricAsset())
    com.olap.prototype.wire.governance.SemanticCatalogReadsAsset.KindCase.MEMBER_ASSET -> SemanticCatalogReadsAssetMemberAssetFromProto(value.getMemberAsset())
    com.olap.prototype.wire.governance.SemanticCatalogReadsAsset.KindCase.RELATION_FILTER_ASSET -> SemanticCatalogReadsAssetRelationFilterAssetFromProto(value.getRelationFilterAsset())
    com.olap.prototype.wire.governance.SemanticCatalogReadsAsset.KindCase.DIMENSION_ASSET -> SemanticCatalogReadsAssetDimensionAssetFromProto(value.getDimensionAsset())
    com.olap.prototype.wire.governance.SemanticCatalogReadsAsset.KindCase.TIME_DIMENSION_ASSET -> SemanticCatalogReadsAssetTimeDimensionAssetFromProto(value.getTimeDimensionAsset())
    com.olap.prototype.wire.governance.SemanticCatalogReadsAsset.KindCase.FILTER_ASSET -> SemanticCatalogReadsAssetFilterAssetFromProto(value.getFilterAsset())
    com.olap.prototype.wire.governance.SemanticCatalogReadsAsset.KindCase.KIND_NOT_SET -> error("SemanticCatalogReadsAsset variant is required")
}

internal fun SemanticCatalogReadsAssetScenarioAssetToProto(value: com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.ScenarioAsset): com.olap.prototype.wire.governance.SemanticCatalogReadsAssetScenarioAsset = com.olap.prototype.wire.governance.SemanticCatalogReadsAssetScenarioAsset.newBuilder().apply {
    setValue(SemanticCatalogReadsScenarioToProto(value.value))
}.build()
internal fun SemanticCatalogReadsAssetScenarioAssetFromProto(value: com.olap.prototype.wire.governance.SemanticCatalogReadsAssetScenarioAsset): com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.ScenarioAsset = com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.ScenarioAsset(
    value = required(value.hasValue(), "SemanticCatalogReadsAssetScenarioAsset.value") { SemanticCatalogReadsScenarioFromProto(value.getValue()) }
)

internal fun SemanticCatalogReadsScenarioToProto(value: com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Scenario): com.olap.prototype.wire.governance.SemanticCatalogReadsScenario = com.olap.prototype.wire.governance.SemanticCatalogReadsScenario.newBuilder().apply {
    setId(value.id)
    setVersion(value.version)
    setDisplayName(value.displayName)
    setSourceJoinContract(PublishedSemanticCatalogSourceJoinMeaningToProto(value.sourceJoinContract))
    setMetrics(value.metrics)
    setMembers(value.members)
    setRelationFilters(value.relationFilters)
    setDimensions(value.dimensions)
    setTimeDimensions(value.timeDimensions)
    setFilters(value.filters)
}.build()
internal fun SemanticCatalogReadsScenarioFromProto(value: com.olap.prototype.wire.governance.SemanticCatalogReadsScenario): com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Scenario = com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Scenario(
    id = value.getId(),
    version = value.getVersion(),
    displayName = value.getDisplayName(),
    sourceJoinContract = required(value.hasSourceJoinContract(), "SemanticCatalogReadsScenario.sourceJoinContract") { PublishedSemanticCatalogSourceJoinMeaningFromProto(value.getSourceJoinContract()) },
    metrics = value.getMetrics(),
    members = value.getMembers(),
    relationFilters = value.getRelationFilters(),
    dimensions = value.getDimensions(),
    timeDimensions = value.getTimeDimensions(),
    filters = value.getFilters()
)

internal fun SemanticCatalogReadsAssetMetricAssetToProto(value: com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.MetricAsset): com.olap.prototype.wire.governance.SemanticCatalogReadsAssetMetricAsset = com.olap.prototype.wire.governance.SemanticCatalogReadsAssetMetricAsset.newBuilder().apply {
    setValue(PublishedSemanticCatalogMetricToProto(value.value))
}.build()
internal fun SemanticCatalogReadsAssetMetricAssetFromProto(value: com.olap.prototype.wire.governance.SemanticCatalogReadsAssetMetricAsset): com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.MetricAsset = com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.MetricAsset(
    value = required(value.hasValue(), "SemanticCatalogReadsAssetMetricAsset.value") { PublishedSemanticCatalogMetricFromProto(value.getValue()) }
)

internal fun SemanticCatalogReadsAssetMemberAssetToProto(value: com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.MemberAsset): com.olap.prototype.wire.governance.SemanticCatalogReadsAssetMemberAsset = com.olap.prototype.wire.governance.SemanticCatalogReadsAssetMemberAsset.newBuilder().apply {
    setValue(PublishedSemanticCatalogMemberToProto(value.value))
}.build()
internal fun SemanticCatalogReadsAssetMemberAssetFromProto(value: com.olap.prototype.wire.governance.SemanticCatalogReadsAssetMemberAsset): com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.MemberAsset = com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.MemberAsset(
    value = required(value.hasValue(), "SemanticCatalogReadsAssetMemberAsset.value") { PublishedSemanticCatalogMemberFromProto(value.getValue()) }
)

internal fun SemanticCatalogReadsAssetRelationFilterAssetToProto(value: com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.RelationFilterAsset): com.olap.prototype.wire.governance.SemanticCatalogReadsAssetRelationFilterAsset = com.olap.prototype.wire.governance.SemanticCatalogReadsAssetRelationFilterAsset.newBuilder().apply {
    setValue(PublishedSemanticCatalogRelationFilterToProto(value.value))
}.build()
internal fun SemanticCatalogReadsAssetRelationFilterAssetFromProto(value: com.olap.prototype.wire.governance.SemanticCatalogReadsAssetRelationFilterAsset): com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.RelationFilterAsset = com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.RelationFilterAsset(
    value = required(value.hasValue(), "SemanticCatalogReadsAssetRelationFilterAsset.value") { PublishedSemanticCatalogRelationFilterFromProto(value.getValue()) }
)

internal fun SemanticCatalogReadsAssetDimensionAssetToProto(value: com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.DimensionAsset): com.olap.prototype.wire.governance.SemanticCatalogReadsAssetDimensionAsset = com.olap.prototype.wire.governance.SemanticCatalogReadsAssetDimensionAsset.newBuilder().apply {
    setValue(PublishedSemanticCatalogDimensionToProto(value.value))
}.build()
internal fun SemanticCatalogReadsAssetDimensionAssetFromProto(value: com.olap.prototype.wire.governance.SemanticCatalogReadsAssetDimensionAsset): com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.DimensionAsset = com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.DimensionAsset(
    value = required(value.hasValue(), "SemanticCatalogReadsAssetDimensionAsset.value") { PublishedSemanticCatalogDimensionFromProto(value.getValue()) }
)

internal fun SemanticCatalogReadsAssetTimeDimensionAssetToProto(value: com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.TimeDimensionAsset): com.olap.prototype.wire.governance.SemanticCatalogReadsAssetTimeDimensionAsset = com.olap.prototype.wire.governance.SemanticCatalogReadsAssetTimeDimensionAsset.newBuilder().apply {
    setValue(PublishedSemanticCatalogTimeDimensionToProto(value.value))
}.build()
internal fun SemanticCatalogReadsAssetTimeDimensionAssetFromProto(value: com.olap.prototype.wire.governance.SemanticCatalogReadsAssetTimeDimensionAsset): com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.TimeDimensionAsset = com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.TimeDimensionAsset(
    value = required(value.hasValue(), "SemanticCatalogReadsAssetTimeDimensionAsset.value") { PublishedSemanticCatalogTimeDimensionFromProto(value.getValue()) }
)

internal fun SemanticCatalogReadsAssetFilterAssetToProto(value: com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.FilterAsset): com.olap.prototype.wire.governance.SemanticCatalogReadsAssetFilterAsset = com.olap.prototype.wire.governance.SemanticCatalogReadsAssetFilterAsset.newBuilder().apply {
    setValue(PublishedSemanticCatalogFilterToProto(value.value))
}.build()
internal fun SemanticCatalogReadsAssetFilterAssetFromProto(value: com.olap.prototype.wire.governance.SemanticCatalogReadsAssetFilterAsset): com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.FilterAsset = com.olap.prototype.query.contract.catalog.SemanticCatalogReads.Asset.FilterAsset(
    value = required(value.hasValue(), "SemanticCatalogReadsAssetFilterAsset.value") { PublishedSemanticCatalogFilterFromProto(value.getValue()) }
)

internal fun SemanticCatalogReadsDefinitionRequestToProto(value: com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionRequest): com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionRequest = com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionRequest.newBuilder().apply {
    setCatalog(SemanticCatalogReadsCatalogReferenceToProto(value.catalog))
    setReference(SemanticCatalogReadsDefinitionReferenceToProto(value.reference))
}.build()
internal fun SemanticCatalogReadsDefinitionRequestFromProto(value: com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionRequest): com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionRequest = com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionRequest(
    catalog = required(value.hasCatalog(), "SemanticCatalogReadsDefinitionRequest.catalog") { SemanticCatalogReadsCatalogReferenceFromProto(value.getCatalog()) },
    reference = required(value.hasReference(), "SemanticCatalogReadsDefinitionRequest.reference") { SemanticCatalogReadsDefinitionReferenceFromProto(value.getReference()) }
)

internal fun SemanticCatalogReadsDefinitionReferenceToProto(value: com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionReference): com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionReference = com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionReference.newBuilder().apply { when (value) {
    is com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionReference.Calculation -> setCalculation(SemanticCatalogReadsDefinitionReferenceCalculationToProto(value))
    is com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionReference.Fact -> setFact(SemanticCatalogReadsDefinitionReferenceFactToProto(value))
    is com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionReference.Instance -> setInstance(SemanticCatalogReadsDefinitionReferenceInstanceToProto(value))
    is com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionReference.Member -> setMember(SemanticCatalogReadsDefinitionReferenceMemberToProto(value))
} }.build()
internal fun SemanticCatalogReadsDefinitionReferenceFromProto(value: com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionReference): com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionReference = when (value.kindCase) {
    com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionReference.KindCase.CALCULATION -> SemanticCatalogReadsDefinitionReferenceCalculationFromProto(value.getCalculation())
    com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionReference.KindCase.FACT -> SemanticCatalogReadsDefinitionReferenceFactFromProto(value.getFact())
    com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionReference.KindCase.INSTANCE -> SemanticCatalogReadsDefinitionReferenceInstanceFromProto(value.getInstance())
    com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionReference.KindCase.MEMBER -> SemanticCatalogReadsDefinitionReferenceMemberFromProto(value.getMember())
    com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionReference.KindCase.KIND_NOT_SET -> error("SemanticCatalogReadsDefinitionReference variant is required")
}

internal fun SemanticCatalogReadsDefinitionReferenceCalculationToProto(value: com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionReference.Calculation): com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionReferenceCalculation = com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionReferenceCalculation.newBuilder().apply {
    setReference(SemanticProto.toProto(value.reference))
}.build()
internal fun SemanticCatalogReadsDefinitionReferenceCalculationFromProto(value: com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionReferenceCalculation): com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionReference.Calculation = com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionReference.Calculation(
    reference = required(value.hasReference(), "SemanticCatalogReadsDefinitionReferenceCalculation.reference") { SemanticProto.fromProto(value.getReference()) as com.olap.prototype.calculation.MetricRef }
)

internal fun SemanticCatalogReadsDefinitionReferenceFactToProto(value: com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionReference.Fact): com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionReferenceFact = com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionReferenceFact.newBuilder().apply {
    setReference(SemanticProto.toProto(value.reference))
}.build()
internal fun SemanticCatalogReadsDefinitionReferenceFactFromProto(value: com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionReferenceFact): com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionReference.Fact = com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionReference.Fact(
    reference = required(value.hasReference(), "SemanticCatalogReadsDefinitionReferenceFact.reference") { SemanticProto.fromProto(value.getReference()) as com.olap.prototype.calculation.MetricRef }
)

internal fun SemanticCatalogReadsDefinitionReferenceInstanceToProto(value: com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionReference.Instance): com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionReferenceInstance = com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionReferenceInstance.newBuilder().apply {
    setReference(SemanticProto.toProto(value.reference))
}.build()
internal fun SemanticCatalogReadsDefinitionReferenceInstanceFromProto(value: com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionReferenceInstance): com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionReference.Instance = com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionReference.Instance(
    reference = required(value.hasReference(), "SemanticCatalogReadsDefinitionReferenceInstance.reference") { SemanticProto.fromProto(value.getReference()) as com.olap.prototype.calculation.MetricInstanceRef }
)

internal fun SemanticCatalogReadsDefinitionReferenceMemberToProto(value: com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionReference.Member): com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionReferenceMember = com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionReferenceMember.newBuilder().apply {
    setReference(SemanticProto.toProto(value.reference))
}.build()
internal fun SemanticCatalogReadsDefinitionReferenceMemberFromProto(value: com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionReferenceMember): com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionReference.Member = com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionReference.Member(
    reference = required(value.hasReference(), "SemanticCatalogReadsDefinitionReferenceMember.reference") { SemanticProto.fromProto(value.getReference()) as com.olap.prototype.member.MemberRef }
)

internal fun SemanticCatalogReadsDefinitionResultToProto(value: com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionResult): com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionResult = com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionResult.newBuilder().apply {
    setCatalog(SemanticCatalogReadsCatalogReferenceToProto(value.catalog))
    setDefinition(PublishedSemanticCatalogSemanticDependencyToProto(value.definition))
}.build()
internal fun SemanticCatalogReadsDefinitionResultFromProto(value: com.olap.prototype.wire.governance.SemanticCatalogReadsDefinitionResult): com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionResult = com.olap.prototype.query.contract.catalog.SemanticCatalogReads.DefinitionResult(
    catalog = required(value.hasCatalog(), "SemanticCatalogReadsDefinitionResult.catalog") { SemanticCatalogReadsCatalogReferenceFromProto(value.getCatalog()) },
    definition = required(value.hasDefinition(), "SemanticCatalogReadsDefinitionResult.definition") { PublishedSemanticCatalogSemanticDependencyFromProto(value.getDefinition()) }
)
