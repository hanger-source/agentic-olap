package com.olap.prototype.codec

import com.olap.prototype.query.QueryExplanationView as View
import com.olap.prototype.query.QueryExplanationContent as Content
import com.olap.prototype.query.QueryStageAnalysis
import com.olap.prototype.calculation.Calculation
import com.olap.prototype.contract.JdbcSourceRequirement
import com.olap.prototype.wire.query.*

/** 解释只是已观测事实的正式投影；边界不会重新推导键、人口或贡献关系。 */
object QueryExplanationProto {
    fun toProto(v: View): QueryExplanationView = QueryExplanationView.newBuilder().setRequestId(v.requestId).setSummary(toProto(v.summary))
        .addAllScopes(v.scopes.map(::toProto)).addAllSources(v.sources.map(::toProto)).addAllPlan(v.plan.map(::toProto))
        .apply { v.compilationScale?.let { setCompilationScale(toProto(it)) }; v.executionRequirements?.let { setExecutionRequirements(toProto(it)) } }.build()
    fun fromProto(v: QueryExplanationView): View { require(v.hasSummary()); return View(v.requestId, fromProto(v.summary), v.scopesList.map(::fromProto),
        v.sourcesList.map(::fromProto), v.planList.map(::fromProto), if (v.hasCompilationScale()) fromProto(v.compilationScale) else null,
        if (v.hasExecutionRequirements()) fromProto(v.executionRequirements) else null) }
    fun toProto(v: Content): QueryExplanationContent = QueryExplanationContent.newBuilder().apply { when (v) {
        is Content.Summary -> summary = toProto(v.value)
        is Content.Scopes -> scopes = ExplanationScopes.newBuilder().addAllItems(v.items.map(::toProto)).setTotalItems(v.totalItems).apply { v.nextOffset?.let(::setNextOffset) }.build()
        is Content.Sources -> sources = ExplanationSources.newBuilder().addAllItems(v.items.map(::toProto)).setTotalItems(v.totalItems).apply { v.nextOffset?.let(::setNextOffset) }.build()
        is Content.Plan -> plan = ExplanationPlan.newBuilder().addAllItems(v.items.map(::toProto)).setTotalItems(v.totalItems).apply { v.nextOffset?.let(::setNextOffset) }.build()
        is Content.CompilationScale -> scale = ExplanationScale.newBuilder().apply { v.value?.let { setValue(toProto(it)) } }.build()
        is Content.ExecutionRequirements -> executionRequirements = ExplanationRequirementSection.newBuilder().apply { v.value?.let { setValue(toProto(it)) } }.build()
    } }.build()
    fun fromProto(v: QueryExplanationContent): Content = when (v.kindCase) {
        QueryExplanationContent.KindCase.SUMMARY -> Content.Summary(fromProto(v.summary))
        QueryExplanationContent.KindCase.SCOPES -> v.scopes.let { Content.Scopes(it.itemsList.map(::fromProto), it.totalItems, if (it.hasNextOffset()) it.nextOffset else null) }
        QueryExplanationContent.KindCase.SOURCES -> v.sources.let { Content.Sources(it.itemsList.map(::fromProto), it.totalItems, if (it.hasNextOffset()) it.nextOffset else null) }
        QueryExplanationContent.KindCase.PLAN -> v.plan.let { Content.Plan(it.itemsList.map(::fromProto), it.totalItems, if (it.hasNextOffset()) it.nextOffset else null) }
        QueryExplanationContent.KindCase.SCALE -> Content.CompilationScale(if (v.scale.hasValue()) fromProto(v.scale.value) else null)
        QueryExplanationContent.KindCase.EXECUTION_REQUIREMENTS -> Content.ExecutionRequirements(if (v.executionRequirements.hasValue()) fromProto(v.executionRequirements.value) else null)
        else -> error("query explanation content requires one explicit section")
    }
    fun toProto(v: View.ExecutionRequirements): ExplanationExecutionRequirements = ExplanationExecutionRequirements.newBuilder()
        .addAllCapabilities(v.capabilities).addAllConstraintKinds(v.constraintKinds).setSourceCapabilitiesChecked(v.sourceCapabilitiesChecked)
        .addAllSourceConstraints(v.sourceConstraints.map(::toProto))
        .addAllRelationChecks(v.relationChecks.map { check -> ExplanationRelationCheck.newBuilder().setCode(check.code).addAllKeys(check.keys)
            .apply { check.scope?.let { setScope(SemanticProto.toProto(it)) }; check.reason?.let(::setReason) }.build() }).build()
    fun fromProto(v: ExplanationExecutionRequirements) = View.ExecutionRequirements(v.capabilitiesList.toList(), v.relationChecksList.map { check ->
        View.RelationCheck(check.code, if (check.hasScope()) SemanticProto.fromProto(check.scope) else null, check.keysList.toList(), if (check.hasReason()) check.reason else null)
    }, v.constraintKindsList.toList(), v.sourceCapabilitiesChecked, v.sourceConstraintsList.map(::fromProto))
    fun toProto(v: View.SourceConstraints): ExplanationSourceConstraints = ExplanationSourceConstraints.newBuilder()
        .setBinding(QuerySourceProto.toProto(v.binding)).setConnectorRef(SemanticProto.toProto(v.connectorRef)).setDialect(v.dialect)
        .setPhysicalTableId(v.physicalTableId).setPhysicalTableVersion(v.physicalTableVersion).setTable(v.table)
        .addAllConstraints(v.constraints.map { constraint -> ExplanationSourceConstraint.newBuilder().setKind(constraint.kind.name)
            .putAllColumns(constraint.columns).setAdoptedFor(constraint.adoptedFor).apply { constraint.name?.let(::setName) }.build() })
        .apply { v.catalog?.let(::setCatalog); v.schema?.let(::setSchema) }.build()
    fun fromProto(v: ExplanationSourceConstraints): View.SourceConstraints {
        require(v.hasBinding() && v.hasConnectorRef()) { "source constraint explanation requires an exact source and connector" }
        return View.SourceConstraints(QuerySourceProto.fromProto(v.binding), SemanticProto.fromProto(v.connectorRef), v.dialect,
            v.physicalTableId, v.physicalTableVersion, if (v.hasCatalog()) v.catalog else null, if (v.hasSchema()) v.schema else null,
            v.table, v.constraintsList.map { constraint -> View.SourceConstraint(JdbcSourceRequirement.ConstraintKind.valueOf(constraint.kind),
                if (constraint.hasName()) constraint.name else null, constraint.columnsMap.toMap(), constraint.adoptedFor) })
    }
    fun toProto(v: View.PopulationContract): PopulationContract = PopulationContract.newBuilder().setFormatVersion(v.formatVersion).setRequired(v.required)
        .addAllModes(v.modes.map { PopulationMode.newBuilder().setMode(it.mode).addAllFields(it.fields).setGroups(it.groups).apply { it.inputs?.let(::setInputs) }.build() })
        .setScope(v.scope).setGrain(v.grain).setUnmatchedMetrics(v.unmatchedMetrics).setCalculationMetric(v.calculationMetric).setEmptyPopulation(v.emptyPopulation).setCoverage(v.coverage).build()
    fun fromProto(v: PopulationContract) = View.PopulationContract(v.formatVersion, v.required, v.modesList.map { View.PopulationMode(it.mode, it.fieldsList.toList(), it.groups, if (it.hasInputs()) it.inputs else null) },
        v.scope, v.grain, v.unmatchedMetrics, v.calculationMetric, v.emptyPopulation, v.coverage)
    fun toProto(v: View.Summary): ExplanationSummary = ExplanationSummary.newBuilder().setScopeCount(v.scopeCount).setSourceInputCount(v.sourceInputCount)
        .setExecutionBoundary(v.executionBoundary).setPopulationContract(toProto(v.populationContract)).setActualSourceStatistics(v.actualSourceStatistics).setRowNullAttribution(v.rowNullAttribution)
        .addAllSourceOutputScopes(v.sourceOutputScopes.map(::toProto)).build()
    fun fromProto(v: ExplanationSummary): View.Summary { require(v.hasPopulationContract() && v.sourceOutputScopesCount > 0); return View.Summary(v.scopeCount, v.sourceInputCount, v.executionBoundary, fromProto(v.populationContract), v.actualSourceStatistics, v.rowNullAttribution, v.sourceOutputScopesList.map(::fromProto)) }
    fun toProto(v: View.SourceOutput): ExplanationSourceOutput = ExplanationSourceOutput.newBuilder().setScope(SemanticProto.toProto(v.scope))
        .setRepresentation(ExplanationSourceRepresentation.valueOf(v.representation.name)).apply {
            v.reductionStage?.let(::setReductionStage)
            v.reductionReason?.let(::setReductionReason)
            v.attemptedReductionStage?.let(::setAttemptedReductionStage)
        }.build()
    fun fromProto(v: ExplanationSourceOutput): View.SourceOutput {
        require(v.hasScope()) { "source output explanation requires its actual scope" }
        return View.SourceOutput(SemanticProto.fromProto(v.scope), View.SourceRepresentation.valueOf(v.representation.name),
            if (v.hasReductionStage()) v.reductionStage else null, if (v.hasReductionReason()) v.reductionReason else null,
            if (v.hasAttemptedReductionStage()) v.attemptedReductionStage else null)
    }
    fun toProto(v: QueryStageAnalysis.Key): ExplanationKey = ExplanationKey.newBuilder().apply { when (v) {
        is QueryStageAnalysis.UniqueKey -> { provenUnique = true; addAllColumns(v.columns.sortedWith(compareBy({ it.scope.path.joinToString("\u0000") }, { it.position })).map(SemanticProto::toProto)) }
        QueryStageAnalysis.UnknownKey.INSTANCE -> provenUnique = false
    } }.build()
    fun fromProto(v: ExplanationKey): QueryStageAnalysis.Key = if (v.provenUnique) QueryStageAnalysis.UniqueKey(v.columnsList.map(SemanticProto::fromProto).toSet())
        else { require(v.columnsCount == 0); QueryStageAnalysis.UnknownKey.INSTANCE }
    fun toProto(v: View.RelationRequirement): RelationRequirement = RelationRequirement.newBuilder().putAllOutputSlots(v.outputSlots).addAllOutputs(v.outputs.map(SemanticProto::toProto))
        .apply { v.keys?.let { setKeys(com.olap.prototype.wire.semantic.StringList.newBuilder().addAllValues(it)) } }.build()
    fun fromProto(v: RelationRequirement) = View.RelationRequirement(v.outputSlotsMap.toMap(), v.outputsList.map(SemanticProto::fromProto), if (v.hasKeys()) v.keys.valuesList.toList() else null)
    fun toProto(v: View.Root): ExplanationRoot = ExplanationRoot.newBuilder().apply { when (v) {
        is View.Root.Measures -> measures = ExplanationMeasures.newBuilder().setRowPopulation(SemanticProto.toProto(v.rowPopulation)).addAllCalls(v.calls)
            .putAllClocks(v.clocks.mapValues { SemanticProto.toProto(it.value) }).setOutputCondition(SemanticProto.toProto(v.outputCondition)).apply { v.timeRange?.let { setTimeRange(SemanticProto.toProto(it)) } }.build()
        is View.Root.Members -> members = ExplanationMembers.newBuilder().setOrigin(toProto(v.origin)).putAllOutputs(v.outputs).setCondition(SemanticProto.toProto(v.condition))
            .apply { v.range?.let { setRange(SemanticProto.toProto(it)) } }.build()
        is View.Root.Input -> inputId = v.inputId
        is View.Root.Relation -> relation = ExplanationRelation.newBuilder().setInputScope(SemanticProto.toProto(v.inputScope)).putAllOutputs(v.outputs)
            .addAllRequirements(v.requirements.map(::toProto)).apply { v.keys?.let { setKeys(com.olap.prototype.wire.semantic.StringList.newBuilder().addAllValues(it)) } }.build()
        is View.Root.ObjectMeasure -> objectMeasure = ExplanationObjectMeasure.newBuilder().addAllRoles(v.roles.map(SemanticProto::toProto)).build()
        is View.Root.AlignMeasurements -> alignMeasurements = ExplanationAlignment.newBuilder().putAllKeys(v.keys.mapValues { StringMap.newBuilder().putAllValues(it.value).build() })
            .addAllObservationKeys(v.observationKeys).setPopulation(SemanticProto.toProto(v.population)).build()
        is View.Root.ObservationStatistics -> observationStatistics = ExplanationObservation.newBuilder().setUnit(SemanticProto.toProto(v.unit)).addAllOuterKeys(v.outerKeys).build()
        is View.Root.SetInputs -> setInputs = ExplanationSet.newBuilder().setOperation(v.operation.name).setAll(v.all).addAllInputs(v.inputs.map(SemanticProto::toProto)).build()
    } }.build()
    fun fromProto(v: ExplanationRoot): View.Root = when (v.kindCase) {
        ExplanationRoot.KindCase.MEASURES -> v.measures.let { require(it.hasRowPopulation() && it.hasOutputCondition()); View.Root.Measures(SemanticProto.fromProto(it.rowPopulation), it.callsList.toList(),
            if (it.hasTimeRange()) SemanticProto.fromProto(it.timeRange) else null, it.clocksMap.mapValues { e -> SemanticProto.fromProto(e.value) }, SemanticProto.fromProto(it.outputCondition)) }
        ExplanationRoot.KindCase.MEMBERS -> v.members.let { require(it.hasOrigin() && it.hasCondition()); View.Root.Members(fromProto(it.origin), it.outputsMap.toMap(), SemanticProto.fromProto(it.condition), if (it.hasRange()) SemanticProto.fromProto(it.range) else null) }
        ExplanationRoot.KindCase.INPUT_ID -> View.Root.Input(v.inputId)
        ExplanationRoot.KindCase.RELATION -> v.relation.let { require(it.hasInputScope()); View.Root.Relation(SemanticProto.fromProto(it.inputScope), it.outputsMap.toMap(), if (it.hasKeys()) it.keys.valuesList.toList() else null, it.requirementsList.map(::fromProto)) }
        ExplanationRoot.KindCase.OBJECT_MEASURE -> View.Root.ObjectMeasure(v.objectMeasure.rolesList.map(SemanticProto::fromProto))
        ExplanationRoot.KindCase.ALIGN_MEASUREMENTS -> v.alignMeasurements.let { require(it.hasPopulation()); View.Root.AlignMeasurements(it.keysMap.mapValues { e -> e.value.valuesMap.toMap() }, it.observationKeysList.toList(), SemanticProto.fromProto(it.population)) }
        ExplanationRoot.KindCase.OBSERVATION_STATISTICS -> v.observationStatistics.let { require(it.hasUnit()); View.Root.ObservationStatistics(SemanticProto.fromProto(it.unit), it.outerKeysList.toList()) }
        ExplanationRoot.KindCase.SET_INPUTS -> View.Root.SetInputs(Calculation.SetKind.valueOf(v.setInputs.operation), v.setInputs.all, v.setInputs.inputsList.map(SemanticProto::fromProto))
        else -> error("explanation root requires one explicit variant")
    }
    fun toProto(v: View.MemberOrigin): ExplanationMemberOrigin = ExplanationMemberOrigin.newBuilder().apply { when (v) {
        is View.MemberOrigin.Projection -> projection = ExplanationMemberRef.newBuilder().setId(v.id).setVersion(v.version).build()
        is View.MemberOrigin.Computed -> computed = ExplanationComputedMember.newBuilder().setId(v.id).setVersion(v.version).setComputationScope(SemanticProto.toProto(v.computationScope)).build()
        is View.MemberOrigin.Local -> local = SemanticProto.toProto(v.inputScope)
    } }.build()
    fun fromProto(v: ExplanationMemberOrigin): View.MemberOrigin = when (v.kindCase) {
        ExplanationMemberOrigin.KindCase.PROJECTION -> View.MemberOrigin.Projection(v.projection.id, v.projection.version)
        ExplanationMemberOrigin.KindCase.COMPUTED -> { require(v.computed.hasComputationScope()); View.MemberOrigin.Computed(v.computed.id, v.computed.version, SemanticProto.fromProto(v.computed.computationScope)) }
        ExplanationMemberOrigin.KindCase.LOCAL -> View.MemberOrigin.Local(SemanticProto.fromProto(v.local))
        else -> error("explanation member origin requires one explicit variant")
    }
    fun toProto(v: View.Qualification): ExplanationQualification = ExplanationQualification.newBuilder().setObjectsScope(SemanticProto.toProto(v.objectsScope))
        .addAllObjectKeys(v.objectKeys).setTarget(SemanticProto.toProto(v.target)).setEffect(v.effect).addAllRecordSlots(v.recordSlots)
        .putAllRecordMeanings(v.recordMeanings.mapValues { SemanticProto.toProto(it.value) }).build()
    fun fromProto(v: ExplanationQualification): View.Qualification { require(v.hasObjectsScope() && v.hasTarget()); return View.Qualification(SemanticProto.fromProto(v.objectsScope), v.objectKeysList.toList(), SemanticProto.fromProto(v.target), v.effect, v.recordSlotsList.toList(), v.recordMeaningsMap.mapValues { SemanticProto.fromProto(it.value) }) }
    fun toProto(v: View.Stage): ExplanationStage = ExplanationStage.newBuilder().setIndex(v.index).setOperation(v.operation).setContract(SemanticProto.toProto(v.contract))
        .addAllInputs(v.inputs.map(SemanticProto::toProto)).addAllImports(v.imports.map(SemanticProto::toProto)).addAllOutputs(v.outputs.map(SemanticProto::toProto)).setGrain(toProto(v.grain))
        .setExecutionLocation(when (v.executionLocation) {
            View.ExecutionLocation.SOURCE -> StageExecutionLocation.STAGE_EXECUTION_SOURCE
            View.ExecutionLocation.CORE -> StageExecutionLocation.STAGE_EXECUTION_CORE
        }).build()
    fun fromProto(v: ExplanationStage): View.Stage { require(v.hasContract() && v.hasGrain()); return View.Stage(v.index, v.operation, SemanticProto.fromProto(v.contract), v.inputsList.map(SemanticProto::fromProto), v.importsList.map(SemanticProto::fromProto), v.outputsList.map(SemanticProto::fromProto), fromProto(v.grain), when (v.executionLocation) {
        StageExecutionLocation.STAGE_EXECUTION_SOURCE -> View.ExecutionLocation.SOURCE
        StageExecutionLocation.STAGE_EXECUTION_CORE -> View.ExecutionLocation.CORE
        StageExecutionLocation.UNRECOGNIZED -> error("unknown stage execution location")
    }) }
    fun toProto(v: View.RelationMatch): RelationInputMatch = RelationInputMatch.newBuilder().setStatus(v.status).setInputScope(SemanticProto.toProto(v.inputScope))
        .addAllActualOutputs(v.actualOutputs.map(SemanticProto::toProto)).setActualGrain(toProto(v.actualGrain)).addAllRequirements(v.requirements.map(::toProto))
        .putAllProjection(v.projection).setPopulation(v.population).setEffect(v.effect).build()
    fun fromProto(v: RelationInputMatch): View.RelationMatch { require(v.hasInputScope() && v.hasActualGrain()); return View.RelationMatch(v.status, SemanticProto.fromProto(v.inputScope), v.actualOutputsList.map(SemanticProto::fromProto), fromProto(v.actualGrain), v.requirementsList.map(::fromProto), v.projectionMap.toMap(), v.population, v.effect) }
    fun toProto(v: View.Scope): ExplanationScope = ExplanationScope.newBuilder().setScope(SemanticProto.toProto(v.scope)).setRoot(toProto(v.root))
        .addAllQualifications(v.qualifications.map(::toProto)).addAllChildren(v.children.map { ExplanationChild.newBuilder().setKind(it.kind).setName(it.name).setScope(SemanticProto.toProto(it.scope)).build() })
        .setFinalGrain(toProto(v.finalGrain)).addAllOutputs(v.outputs.map(SemanticProto::toProto)).addAllSelectedObservations(v.selectedObservations.map(SemanticProto::toProto))
        .addAllStages(v.stages.map(::toProto)).setRootExecutionLocation(when (v.rootExecutionLocation) {
            View.ExecutionLocation.SOURCE -> StageExecutionLocation.STAGE_EXECUTION_SOURCE
            View.ExecutionLocation.CORE -> StageExecutionLocation.STAGE_EXECUTION_CORE
        }).apply { v.inputMode?.let(::setInputMode); v.relationInputMatch?.let { setRelationInputMatch(toProto(it)) } }.build()
    fun fromProto(v: ExplanationScope): View.Scope { require(v.hasScope() && v.hasRoot() && v.hasFinalGrain()); return View.Scope(SemanticProto.fromProto(v.scope), if (v.hasInputMode()) v.inputMode else null,
        fromProto(v.root), v.qualificationsList.map(::fromProto), v.childrenList.map { require(it.hasScope()); View.Child(it.kind, it.name, SemanticProto.fromProto(it.scope)) }, fromProto(v.finalGrain),
        if (v.hasRelationInputMatch()) fromProto(v.relationInputMatch) else null, v.outputsList.map(SemanticProto::fromProto), v.selectedObservationsList.map(SemanticProto::fromProto), v.stagesList.map(::fromProto), when (v.rootExecutionLocation) {
            StageExecutionLocation.STAGE_EXECUTION_SOURCE -> View.ExecutionLocation.SOURCE
            StageExecutionLocation.STAGE_EXECUTION_CORE -> View.ExecutionLocation.CORE
            StageExecutionLocation.UNRECOGNIZED -> error("unknown root execution location")
        }) }
    fun toProto(v: View.SourceIdentity): ExplanationSourceIdentity = ExplanationSourceIdentity.newBuilder().apply { when (v) {
        is View.SourceIdentity.Fact -> fact = ExplanationFactIdentity.newBuilder().setCallId(v.callId).setOwner(SemanticProto.toProto(v.owner)).setMetric(SemanticProto.toProto(v.metric))
            .putAllGrouping(v.grouping.mapValues { SemanticProto.toProto(it.value) }).setFilter(SemanticProto.toProto(v.filter)).build()
        is View.SourceIdentity.Member -> member = ExplanationMemberIdentity.newBuilder().setSourceId(v.sourceId).setSourceVersion(v.sourceVersion).putAllOutputs(v.outputs)
            .putAllMeanings(v.meanings.mapValues { SemanticProto.toProto(it.value) }).setCondition(SemanticProto.toProto(v.condition)).build()
    } }.build()
    fun fromProto(v: ExplanationSourceIdentity): View.SourceIdentity = when (v.kindCase) {
        ExplanationSourceIdentity.KindCase.FACT -> v.fact.let { require(it.hasOwner() && it.hasMetric() && it.hasFilter()); View.SourceIdentity.Fact(it.callId, SemanticProto.fromProto(it.owner), SemanticProto.fromProto(it.metric), it.groupingMap.mapValues { e -> SemanticProto.fromProto(e.value) }, SemanticProto.fromProto(it.filter)) }
        ExplanationSourceIdentity.KindCase.MEMBER -> v.member.let { require(it.hasCondition()); View.SourceIdentity.Member(it.sourceId, it.sourceVersion, it.outputsMap.toMap(), it.meaningsMap.mapValues { e -> SemanticProto.fromProto(e.value) }, SemanticProto.fromProto(it.condition)) }
        else -> error("explanation source identity requires one explicit variant")
    }
    fun toProto(v: View.RelationPosition): RelationPosition = RelationPosition.newBuilder().addAllScope(v.scope).setRole(v.role).addAllPath(v.path.map(SemanticProto::toProto)).build()
    fun fromProto(v: RelationPosition) = View.RelationPosition(v.scopeList.toList(), v.role, v.pathList.map(SemanticProto::fromProto))
    fun toProto(v: View.Position): ExplanationPosition = ExplanationPosition.newBuilder().setPosition(toProto(v.position)).setBindingId(v.bindingId).setBindingVersion(v.bindingVersion).build()
    fun fromProto(v: ExplanationPosition): View.Position { require(v.hasPosition()); return View.Position(fromProto(v.position), v.bindingId, v.bindingVersion) }
    fun toProto(v: View.Join): ExplanationJoin = ExplanationJoin.newBuilder().setJoinPathId(v.joinPathId).setJoinPathVersion(v.joinPathVersion)
        .setSourcePosition(toProto(v.sourcePosition)).setTargetPosition(toProto(v.targetPosition)).putAllSourceTimes(v.sourceTimes.mapValues { toProto(it.value) }).putAllClockSlots(v.clockSlots).setRequiredMatch(v.requiredMatch).build()
    fun fromProto(v: ExplanationJoin): View.Join { require(v.hasSourcePosition() && v.hasTargetPosition()); return View.Join(v.joinPathId, v.joinPathVersion, fromProto(v.sourcePosition), fromProto(v.targetPosition), v.sourceTimesMap.mapValues { fromProto(it.value) }, v.clockSlotsMap.toMap(), v.requiredMatch) }
    fun toProto(v: View.Quantified): ExplanationQuantified = ExplanationQuantified.newBuilder().addAllOccurrence(v.occurrence).setParentPosition(toProto(v.parentPosition))
        .addAllPositions(v.positions.map(::toProto)).addAllJoins(v.joins.map(::toProto)).putAllClocks(v.clocks.mapValues { SemanticProto.toProto(it.value) }).addAllQuantifiers(v.quantifiers.map(::toProto)).build()
    fun fromProto(v: ExplanationQuantified): View.Quantified { require(v.hasParentPosition()); return View.Quantified(v.occurrenceList.toList(), fromProto(v.parentPosition), v.positionsList.map(::fromProto), v.joinsList.map(::fromProto), v.clocksMap.mapValues { SemanticProto.fromProto(it.value) }, v.quantifiersList.map(::fromProto)) }
    fun toProto(v: View.Source): ExplanationSource = ExplanationSource.newBuilder().setScope(SemanticProto.toProto(v.scope)).setIdentity(toProto(v.identity)).setEventId(v.eventId)
        .setEventVersion(v.eventVersion).setRecordIdentity(SemanticProto.toProto(v.recordIdentity)).putAllClocks(v.clocks.mapValues { SemanticProto.toProto(it.value) })
        .addAllPositions(v.positions.map(::toProto)).addAllJoins(v.joins.map(::toProto)).addAllQuantifiers(v.quantifiers.map(::toProto))
        .addAllRelations(v.relations.map { ExplanationRelationSource.newBuilder().setInstanceId(it.instanceId).setFilterRefId(it.filterRefId).setSource(toProto(it.source)).build() })
        .apply { v.range?.let { setRange(SemanticProto.toProto(it)) } }.build()
    fun fromProto(v: ExplanationSource): View.Source { require(v.hasScope() && v.hasIdentity() && v.hasRecordIdentity()); return View.Source(SemanticProto.fromProto(v.scope), fromProto(v.identity), v.eventId, v.eventVersion,
        SemanticProto.fromProto(v.recordIdentity), if (v.hasRange()) SemanticProto.fromProto(v.range) else null, v.clocksMap.mapValues { SemanticProto.fromProto(it.value) },
        v.positionsList.map(::fromProto), v.joinsList.map(::fromProto), v.quantifiersList.map(::fromProto), v.relationsList.map { require(it.hasSource()); View.RelationSource(it.instanceId, it.filterRefId, fromProto(it.source)) }) }
    fun toProto(v: View.PlanNode): ExplanationPlanNode = ExplanationPlanNode.newBuilder().setId(v.id).setOperator(v.operator).addAllInputs(v.inputs).apply {
        v.scan?.let(::setScan); v.joinType?.let(::setJoinType); v.groupColumns?.let { setGroupColumns(IntList.newBuilder().addAllValues(it)) }
        v.aggregations?.let { setAggregations(com.olap.prototype.wire.semantic.StringList.newBuilder().addAllValues(it)) }
    }.build()
    fun fromProto(v: ExplanationPlanNode) = View.PlanNode(v.id, v.operator, v.inputsList.toList(), if (v.hasScan()) v.scan else null, if (v.hasJoinType()) v.joinType else null,
        if (v.hasGroupColumns()) v.groupColumns.valuesList.toList() else null, if (v.hasAggregations()) v.aggregations.valuesList.toList() else null)
    fun toProto(v: View.RelationSize): ExplanationRelationSize = ExplanationRelationSize.newBuilder().setNodes(v.nodes).setEdges(v.edges).setScalarCalls(v.scalarCalls).setNumericChecks(v.numericChecks).setRepeatedNumericChecks(v.repeatedNumericChecks).build()
    fun fromProto(v: ExplanationRelationSize) = View.RelationSize(v.nodes, v.edges, v.scalarCalls, v.numericChecks, v.repeatedNumericChecks)
    fun toProto(v: View.CompilationScale): ExplanationCompilationScale = ExplanationCompilationScale.newBuilder().setRaw(toProto(v.raw)).setOptimized(toProto(v.optimized)).setSqlRelation(toProto(v.sqlRelation))
        .setSqlBytes(v.sqlBytes).setSqlCaseExpressions(v.sqlCaseExpressions).setSqlNumericChecks(v.sqlNumericChecks).setNumericBindings(v.numericBindings).setReusedNumericChecks(v.reusedNumericChecks).build()
    fun fromProto(v: ExplanationCompilationScale): View.CompilationScale { require(v.hasRaw() && v.hasOptimized() && v.hasSqlRelation()); return View.CompilationScale(fromProto(v.raw), fromProto(v.optimized), fromProto(v.sqlRelation), v.sqlBytes, v.sqlCaseExpressions, v.sqlNumericChecks, v.numericBindings, v.reusedNumericChecks) }
}
