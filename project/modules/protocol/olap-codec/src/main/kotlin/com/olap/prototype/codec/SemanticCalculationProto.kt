package com.olap.prototype.codec

import com.olap.prototype.wire.semantic.*
import com.olap.prototype.calculation.Calculation as DC
import com.olap.prototype.calculation.CalculationBindings as DB
import com.olap.prototype.query.DimensionCondition as DD
import com.olap.prototype.member.MemberCondition as DM
import com.olap.prototype.query.GroupingLevels as DG
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.RowPopulationContract

internal object SemanticCalculationProto {
    fun scalar(v: DC.Scalar): CalculationScalar = CalculationScalar.newBuilder().setScalarType(v.scalarType.name).setValue(v.value).build()
    fun scalar(v: CalculationScalar): DC.Scalar = DC.Scalar(ScalarValueType.valueOf(v.scalarType), v.value)
    fun binding(v: DC.Binding): CalculationBinding = CalculationBinding.newBuilder().apply { when (v) {
        is DC.Argument -> setArgument(v.name)
        is DC.GroupingSlots -> setGroupingSlots(StringList.newBuilder().addAllValues(v.slots))
        is DC.Scalar -> setScalar(scalar(v))
        is DC.Range -> setRange(SemanticTypesProto.window(v.window))
        is DC.Clock -> setClock(SemanticTypesProto.clock(v.clock))
        is DC.Grouping -> setGrouping(DimensionRefs.newBuilder().addAllValues(v.dimensions.map(SemanticTypesProto::dimension)))
        is DC.Condition -> setCondition(condition(v.condition))
    } }.build()
    fun binding(v: CalculationBinding): DC.Binding = when (v.kindCase) {
        CalculationBinding.KindCase.ARGUMENT -> DC.Argument(v.argument)
        CalculationBinding.KindCase.GROUPING_SLOTS -> DC.GroupingSlots(v.groupingSlots.valuesList)
        CalculationBinding.KindCase.SCALAR -> scalar(v.scalar)
        CalculationBinding.KindCase.RANGE -> DC.Range(SemanticTypesProto.window(v.range))
        CalculationBinding.KindCase.CLOCK -> DC.Clock(SemanticTypesProto.clock(v.clock))
        CalculationBinding.KindCase.GROUPING -> DC.Grouping(v.grouping.valuesList.map(SemanticTypesProto::dimension))
        CalculationBinding.KindCase.CONDITION -> DC.Condition(condition(v.condition))
        else -> error("calculation binding kind is required")
    }
    fun value(v: CalculationBinding): DC.Value = binding(v) as? DC.Value ?: error("concrete calculation value is required")
    fun condition(v: DD): DimensionCondition = DimensionCondition.newBuilder().apply { when (v) {
        is DD.Unrestricted -> setUnrestricted(Empty.getDefaultInstance())
        is DD.Predicate -> setPredicate(DimensionPredicate.newBuilder().setFilterRefId(v.filterRefId).setDimension(SemanticTypesProto.dimension(v.dimension)).setOperator(v.operator.name).addAllValues(v.values.map(::scalar)))
        is DD.Exists -> setExists(DimensionExists.newBuilder().setFilterRefId(v.filterRefId).setInstanceId(v.instanceId).setPredicate(memberCondition(v.predicate)).putAllClocks(v.clocks.mapValues { SemanticTypesProto.clock(it.value) }).apply { v.range?.let { setRange(SemanticTypesProto.window(it)) } })
        is DD.All -> setAll(DimensionConditions.newBuilder().addAllValues(v.operands.map(::condition)))
        is DD.Any -> setAny(DimensionConditions.newBuilder().addAllValues(v.operands.map(::condition)))
        is DD.Not -> setNot(condition(v.operand))
    } }.build()
    fun condition(v: DimensionCondition): DD = when (v.kindCase) {
        DimensionCondition.KindCase.UNRESTRICTED -> DD.Unrestricted()
        DimensionCondition.KindCase.PREDICATE -> v.predicate.let { DD.Predicate(it.filterRefId, SemanticTypesProto.dimension(it.dimension), DM.Operator.valueOf(it.operator), it.valuesList.map(::scalar)) }
        DimensionCondition.KindCase.EXISTS -> v.exists.let { DD.Exists(it.instanceId, it.filterRefId, memberCondition(it.predicate), it.range.takeIf { _ -> it.hasRange() }?.let(SemanticTypesProto::window), it.clocksMap.mapValues { entry -> SemanticTypesProto.clock(entry.value) }) }
        DimensionCondition.KindCase.ALL -> DD.All(v.all.valuesList.map(::condition))
        DimensionCondition.KindCase.ANY -> DD.Any(v.any.valuesList.map(::condition))
        DimensionCondition.KindCase.NOT -> DD.Not(condition(v.not))
        else -> error("dimension condition kind is required")
    }
    fun memberCondition(v: DM): MemberCondition = MemberCondition.newBuilder().apply { when (v) {
        is DM.Unrestricted -> setUnrestricted(Empty.getDefaultInstance())
        is DM.Predicate -> setPredicate(MemberPredicate.newBuilder().setSlot(v.slot).setOperator(v.operator.name).addAllValues(v.values.map(::binding)))
        is DM.All -> setAll(MemberConditions.newBuilder().addAllValues(v.operands.map(::memberCondition)))
        is DM.Any -> setAny(MemberConditions.newBuilder().addAllValues(v.operands.map(::memberCondition)))
        is DM.Not -> setNot(memberCondition(v.operand))
    } }.build()
    fun memberCondition(v: MemberCondition): DM = when (v.kindCase) {
        MemberCondition.KindCase.UNRESTRICTED -> DM.Unrestricted()
        MemberCondition.KindCase.PREDICATE -> DM.Predicate(v.predicate.slot, DM.Operator.valueOf(v.predicate.operator), v.predicate.valuesList.map(::binding))
        MemberCondition.KindCase.ALL -> DM.All(v.all.valuesList.map(::memberCondition))
        MemberCondition.KindCase.ANY -> DM.Any(v.any.valuesList.map(::memberCondition))
        MemberCondition.KindCase.NOT -> DM.Not(memberCondition(v.not))
        else -> error("member condition kind is required")
    }
    fun dimension(v: DB.Dimension): DimensionBinding = DimensionBinding.newBuilder().setReference(SemanticTypesProto.dimension(v.reference)).setRole(v.role).addAllMappingIds(v.mappingIds).build()
    fun dimension(v: DimensionBinding): DB.Dimension = DB.Dimension(SemanticTypesProto.dimension(v.reference), v.role, v.mappingIdsList)
    fun bindings(v: DB): CalculationBindings = CalculationBindings.newBuilder().putAllDimensions(v.dimensions.mapValues { dimension(it.value) })
        .putAllMembers(v.members.mapValues { member(it.value) }).putAllGroupingCandidates(v.groupingCandidates.mapValues { DimensionBindings.newBuilder().addAllValues(it.value.map(::dimension)).build() })
        .putAllMeasurements(v.measurements.mapValues { SemanticProto.toProto(it.value) }).putAllRelations(v.relations.mapValues { relation(it.value) }).build()
    fun bindings(v: CalculationBindings): DB = DB(v.dimensionsMap.mapValues { dimension(it.value) }, v.membersMap.mapValues { member(it.value) },
        v.groupingCandidatesMap.mapValues { it.value.valuesList.map(::dimension) }, v.measurementsMap.mapValues { SemanticProto.fromProto(it.value) }, v.relationsMap.mapValues { relation(it.value) })
    fun member(v: DB.Member): MemberBinding = MemberBinding.newBuilder().apply { when (v) {
        is DB.SourceCall -> setSourceCall(SourceCall.newBuilder().setSource(SemanticProto.toProto(v.source)).putAllArguments(v.arguments.mapValues { binding(it.value) })
            .putAllOutputSlots(v.outputSlots).putAllMembers(v.members.mapValues { member(it.value) }).putAllRelations(v.relations.mapValues { relation(it.value) }))
        is DB.LocalRef -> setLocal(LocalBinding.newBuilder().setInputId(v.inputId).putAllOutputSlots(v.outputSlots))
        is DB.InputParameter -> setParameter(Empty.getDefaultInstance())
    } }.build()
    fun member(v: MemberBinding): DB.Member = when (v.kindCase) {
        MemberBinding.KindCase.SOURCE_CALL -> v.sourceCall.let { DB.SourceCall(SemanticProto.fromProto(it.source), it.argumentsMap.mapValues { entry -> binding(entry.value) }, it.outputSlotsMap,
            it.membersMap.mapValues { entry -> member(entry.value) }, it.relationsMap.mapValues { entry -> relation(entry.value) }) }
        MemberBinding.KindCase.LOCAL -> DB.LocalRef(v.local.inputId, v.local.outputSlotsMap)
        MemberBinding.KindCase.PARAMETER -> DB.InputParameter()
        else -> error("member binding kind is required")
    }
    fun relation(v: DB.Relation): RelationBinding = RelationBinding.newBuilder().apply { when (v) {
        is DB.LocalRelation -> setLocal(LocalBinding.newBuilder().setInputId(v.inputId).putAllOutputSlots(v.outputSlots))
        is DB.RelationParameter -> setParameter(Empty.getDefaultInstance())
    } }.build()
    fun relation(v: RelationBinding): DB.Relation = when (v.kindCase) {
        RelationBinding.KindCase.LOCAL -> DB.LocalRelation(v.local.inputId, v.local.outputSlotsMap)
        RelationBinding.KindCase.PARAMETER -> DB.RelationParameter()
        else -> error("relation binding kind is required")
    }
    fun calculation(v: DC): Calculation = Calculation.newBuilder().addAllDimensionSlots(v.dimensionSlots.map(::slot)).addAllMemberSlots(v.memberSlots.map(::memberSlot))
        .addAllMeasurementSlots(v.measurementSlots.map { MeasurementSlot.newBuilder().setName(it.name).putAllParameters(it.parameters.mapValues { entry -> parameter(entry.value) })
            .addAllObservationSlots(it.observationSlots.map(::slot)).addAllGroupingArguments(it.groupingArguments).setValueContract(SemanticTypesProto.value(it.valueContract)).build() })
        .addAllRelationSlots(v.relationSlots.map { RelationSlot.newBuilder().setName(it.name).addAllOutputs(it.outputs.map(SemanticTypesProto::output)).apply { it.keys?.let { keys -> setKeys(StringList.newBuilder().addAllValues(keys)) } }.build() })
        .putAllParameters(v.parameters.mapValues { parameter(it.value) }).setComputation(scope(v.computation)).build()
    fun calculation(v: Calculation): DC = DC(v.dimensionSlotsList.map(::slot), v.memberSlotsList.map(::memberSlot),
        v.measurementSlotsList.map { DC.MeasurementSlot(it.name, it.parametersMap.mapValues { entry -> parameter(entry.value) }, it.observationSlotsList.map(::slot), it.groupingArgumentsList, SemanticTypesProto.value(it.valueContract)) },
        v.relationSlotsList.map { DC.RelationSlot(it.name, it.outputsList.map(SemanticTypesProto::output), it.keys.valuesList.takeIf { _ -> it.hasKeys() }) }, v.parametersMap.mapValues { parameter(it.value) }, scope(v.computation))
    fun slot(v: DC.DimensionSlot): DimensionSlot = DimensionSlot.newBuilder().setName(v.name).setDimensionId(v.dimensionId).setDimensionVersion(v.dimensionVersion).setRole(v.role).build()
    fun slot(v: DimensionSlot): DC.DimensionSlot = DC.DimensionSlot(v.name, v.dimensionId, v.dimensionVersion, v.role)
    fun parameter(v: DC.Parameter): CalculationParameter = CalculationParameter.newBuilder().setKind(v.kind.name).setValueType(v.valueType).addAllAllowedDimensions(v.allowedDimensions.map(::slot))
        .apply { v.defaultValue?.let { setDefaultValue(binding(it)) }; v.valueContract?.let { setValueContract(SemanticTypesProto.value(it)) } }.build()
    fun parameter(v: CalculationParameter): DC.Parameter = DC.Parameter(DC.ParameterKind.valueOf(v.kind), v.valueType, v.allowedDimensionsList.map(::slot),
        v.defaultValue.takeIf { v.hasDefaultValue() }?.let(::value), v.valueContract.takeIf { v.hasValueContract() }?.let(SemanticTypesProto::value))
    fun relationSlot(v: DC.RelationSlot): RelationSlot = RelationSlot.newBuilder().setName(v.name).addAllOutputs(v.outputs.map(SemanticTypesProto::output))
        .apply { v.keys?.let { setKeys(StringList.newBuilder().addAllValues(it)) } }.build()
    fun relationSlot(v: RelationSlot): DC.RelationSlot = DC.RelationSlot(v.name, v.outputsList.map(SemanticTypesProto::output), v.keys.valuesList.takeIf { v.hasKeys() })
    fun memberSlot(v: DC.MemberSlot): MemberSlot = MemberSlot.newBuilder().setName(v.name).setEntity(v.entity).setRole(v.role).setShape(v.shape.name)
        .putAllOutputs(v.outputs.mapValues { output(it.value) }).apply { v.fixedSource?.let { setFixedSource(SemanticProto.toProto(it)) } }.build()
    fun memberSlot(v: MemberSlot): DC.MemberSlot = DC.MemberSlot(v.name, v.entity, v.role, com.olap.prototype.member.MemberShape.valueOf(v.shape), v.outputsMap.mapValues { output(it.value) }, v.fixedSource.takeIf { v.hasFixedSource() }?.let(SemanticProto::fromProto))
    fun output(v: DC.MemberOutput): MemberOutput = MemberOutput.newBuilder().apply { when (v) {
        is DC.MemberDimension -> setDimension(MemberDimensionOutput.newBuilder().setDimensionSlot(v.dimensionSlot).apply { v.valueContract?.let { setValueContract(SemanticTypesProto.value(it)) } })
        is DC.MemberProperty -> setProperty(MemberPropertyOutput.newBuilder().setRole(v.role).setValueType(v.valueType.name).apply { v.valueContract?.let { setValueContract(SemanticTypesProto.value(it)) } })
        is DC.MemberContainerProperty -> setContainerProperty(MemberContainerOutput.newBuilder().setRole(v.role).setShape(SemanticTypesProto.shape(v.shape)))
    } }.build()
    fun output(v: MemberOutput): DC.MemberOutput = when (v.kindCase) {
        MemberOutput.KindCase.DIMENSION -> DC.MemberDimension(v.dimension.dimensionSlot, v.dimension.valueContract.takeIf { v.dimension.hasValueContract() }?.let(SemanticTypesProto::value))
        MemberOutput.KindCase.PROPERTY -> DC.MemberProperty(v.property.role, ScalarValueType.valueOf(v.property.valueType), v.property.valueContract.takeIf { v.property.hasValueContract() }?.let(SemanticTypesProto::value))
        MemberOutput.KindCase.CONTAINER_PROPERTY -> DC.MemberContainerProperty(v.containerProperty.role, SemanticTypesProto.shape(v.containerProperty.shape))
        else -> error("formal member output kind is required")
    }
    fun scope(v: DC.Scope): CalculationScope = CalculationScope.newBuilder().setRoot(root(v.root)).putAllInputs(v.inputs.mapValues { CalculationInput.newBuilder().setMode(it.value.mode).setComputation(scope(it.value.computation)).build() })
        .addAllStages(v.stages.map(SemanticStagesProto::stage)).build()
    fun scope(v: CalculationScope): DC.Scope = DC.Scope(root(v.root), v.inputsMap.mapValues { DC.InputScope(it.value.mode, scope(it.value.computation)) }, v.stagesList.map(SemanticStagesProto::stage))
    private fun root(v: DC.Root): CalculationRoot = CalculationRoot.newBuilder().apply { when (v) {
        is DC.Measures -> setMeasures(CalculationMeasures.newBuilder().addAllCalls(v.calls.map(::call)).addAllGrouping(v.grouping.map(::dimensionReference))
            .addAllGroupingArguments(v.groupingArguments).putAllClocks(v.clocks.mapValues { binding(it.value) })
            .setSourceCondition(binding(v.sourceCondition)).setOutputCondition(condition(v.outputCondition))
            .addAllSourceEventConditions(v.sourceEventConditions.map { EventCondition.newBuilder().setEventId(it.eventId).setEventVersion(it.eventVersion).setCondition(condition(it.condition)).build() })
            .setPopulation(population(v.population)).apply { v.timeRange?.let { setTimeRange(timeRange(it)) }; v.groupingLevels?.let { setGroupingLevels(levels(it)) } })
        is DC.Members -> setMembers(members(v))
        is DC.Compute -> setCompute(CalculationCompute.newBuilder().setCalculation(calculation(v.calculation)).setBindings(computeBindings(v.bindings))
            .putAllArguments(v.arguments.mapValues { binding(it.value) }))
        is DC.Relation -> setRelation(v.slot)
        is DC.Input -> setInput(v.inputId)
        is DC.SetCombination -> setSet(set(v.operation, v.all, v.inputs, v.outputSlots))
        is DC.ObjectMeasure -> setObjectMeasure(ObjectMeasure.newBuilder().setMeasurement(scope(v.measurement)).setObjects(scope(v.objects)).addAllRoles(v.roles.map(::objectRole)))
        is DC.AlignMeasurements -> setAlignMeasurements(AlignMeasurements.newBuilder().putAllInputs(v.inputs.mapValues { (_, input) -> AlignedInput.newBuilder().setComputation(scope(input.computation)).putAllKeys(input.keys)
            .addAllValues(input.values.map { AlignedValue.newBuilder().setSymbol(it.symbol).setOutputAlias(it.outputAlias).setNoMatch(it.noMatch.name).build() }).build() }).addAllObservationKeys(v.observationKeys)
            .setPopulation(AlignmentPopulation.newBuilder().setKind(v.population.kind.name).addAllInputIds(v.population.inputIds)))
        is DC.ObservationStatistics -> setObservationStatistics(ObservationStatistics.newBuilder().setObservations(scope(v.observations)).setUnit(observation(v.unit)).addAllOuterKeys(v.outerKeys)
            .addAllStatistics(v.statistics.map { statistic -> ObservationStatistic.newBuilder().apply { when (statistic) {
                is DC.Reduction -> setReduction(SemanticStagesProto.aggregate(statistic.aggregate))
                is DC.WeightedMean -> setWeightedMean(WeightedMean.newBuilder().setValue(statistic.value).setWeight(statistic.weight).setOutputAlias(statistic.outputAlias).setOutputDisplayName(statistic.outputDisplayName)
                    .setNumeratorContract(SemanticTypesProto.value(statistic.numeratorContract)).setWeightSumContract(SemanticTypesProto.value(statistic.weightSumContract)).setResultContract(SemanticTypesProto.value(statistic.resultContract))
                    .setNullPolicy(statistic.nullPolicy).apply { statistic.multiplyEquation?.let(::setMultiplyEquation); statistic.divideEquation?.let(::setDivideEquation) })
            } }.build() }))
    } }.build()
    private fun root(v: CalculationRoot): DC.Root = when (v.kindCase) {
        CalculationRoot.KindCase.MEASURES -> v.measures.let { DC.Measures(it.callsList.map(::call), it.groupingList.map(::dimensionReference), it.groupingArgumentsList,
            it.timeRange.takeIf { _ -> it.hasTimeRange() }?.let(::timeRange), it.clocksMap.mapValues { entry -> binding(entry.value) }, binding(it.sourceCondition), condition(it.outputCondition),
            it.sourceEventConditionsList.map { value -> DC.EventCondition(value.eventId, value.eventVersion, condition(value.condition)) }, population(it.population), it.groupingLevels.takeIf { _ -> it.hasGroupingLevels() }?.let(::levels)) }
        CalculationRoot.KindCase.MEMBERS -> members(v.members)
        CalculationRoot.KindCase.COMPUTE -> v.compute.let { DC.Compute(calculation(it.calculation), computeBindings(it.bindings), it.argumentsMap.mapValues { entry -> binding(entry.value) }) }
        CalculationRoot.KindCase.RELATION -> DC.Relation(v.relation)
        CalculationRoot.KindCase.INPUT -> DC.Input(v.input)
        CalculationRoot.KindCase.SET -> v.set.let { DC.SetCombination(DC.SetKind.valueOf(it.operation), it.all, it.inputsList.map(::projection), it.outputSlotsList.map(SemanticTypesProto::output)) }
        CalculationRoot.KindCase.OBJECT_MEASURE -> v.objectMeasure.let { DC.ObjectMeasure(scope(it.measurement), scope(it.objects), it.rolesList.map(::objectRole)) }
        CalculationRoot.KindCase.ALIGN_MEASUREMENTS -> v.alignMeasurements.let { DC.AlignMeasurements(it.inputsMap.mapValues { (_, input) -> DC.AlignedInput(scope(input.computation), input.keysMap, input.valuesList.map { value -> DC.AlignedValue(value.symbol, value.outputAlias, DC.NoMatch.valueOf(value.noMatch)) }) }, it.observationKeysList, DC.AlignmentPopulation(DC.PopulationKind.valueOf(it.population.kind), it.population.inputIdsList)) }
        CalculationRoot.KindCase.OBSERVATION_STATISTICS -> v.observationStatistics.let { DC.ObservationStatistics(scope(it.observations), observation(it.unit), it.outerKeysList, it.statisticsList.map { statistic -> when (statistic.kindCase) {
            ObservationStatistic.KindCase.REDUCTION -> DC.Reduction(SemanticStagesProto.aggregate(statistic.reduction))
            ObservationStatistic.KindCase.WEIGHTED_MEAN -> statistic.weightedMean.let { mean -> DC.WeightedMean(mean.value, mean.weight, mean.outputAlias, mean.outputDisplayName, SemanticTypesProto.value(mean.numeratorContract), SemanticTypesProto.value(mean.weightSumContract), SemanticTypesProto.value(mean.resultContract), mean.multiplyEquation.takeIf { mean.hasMultiplyEquation() }, mean.divideEquation.takeIf { mean.hasDivideEquation() }, mean.nullPolicy) }
            else -> error("observation statistic kind is required")
        } }) }
        else -> error("calculation root kind is required")
    }
    fun observation(v: DC.ObservationUnit): ObservationUnit = ObservationUnit.newBuilder().apply { when (v) { is DC.RawRows -> setRawRows(Empty.getDefaultInstance()); is DC.ObjectsBy -> setObjectsBy(StringList.newBuilder().addAllValues(v.keys)) } }.build()
    fun observation(v: ObservationUnit): DC.ObservationUnit = when (v.kindCase) { ObservationUnit.KindCase.RAW_ROWS -> DC.RawRows(); ObservationUnit.KindCase.OBJECTS_BY -> DC.ObjectsBy(v.objectsBy.valuesList); else -> error("observation unit kind is required") }
    fun set(operation: DC.SetKind, all: Boolean, inputs: List<DC.SetProjection>, outputs: List<com.olap.prototype.query.SetOutputContract>): SetCombination = SetCombination.newBuilder().setOperation(operation.name).setAll(all).addAllInputs(inputs.map(::projection)).addAllOutputSlots(outputs.map(SemanticTypesProto::output)).build()
    fun projection(v: DC.SetProjection): SetProjection = SetProjection.newBuilder().setInputId(v.inputId).addAllSymbols(v.symbols).build()
    fun projection(v: SetProjection): DC.SetProjection = DC.SetProjection(v.inputId, v.symbolsList)
    fun objectTarget(v: DC.ObjectTarget): ObjectTarget = ObjectTarget.newBuilder().apply { when (v) {
        is DC.EventRole -> setEventRole(v.role)
        is DC.RecordOutputs -> setRecordOutputs(RecordOutputs.newBuilder().setMemberSlot(v.memberSlot).addAllKeys(v.keys))
        is DC.RecordCallOutputs -> setRecordCallOutputs(RecordCallOutputs.newBuilder().addAllInputPath(v.inputPath).setCallId(v.callId).addAllKeys(v.keys))
    } }.build()
    fun objectTarget(v: ObjectTarget): DC.ObjectTarget = when (v.kindCase) {
        ObjectTarget.KindCase.EVENT_ROLE -> DC.EventRole(v.eventRole)
        ObjectTarget.KindCase.RECORD_OUTPUTS -> DC.RecordOutputs(v.recordOutputs.memberSlot, v.recordOutputs.keysList)
        ObjectTarget.KindCase.RECORD_CALL_OUTPUTS -> DC.RecordCallOutputs(v.recordCallOutputs.inputPathList, v.recordCallOutputs.callId, v.recordCallOutputs.keysList)
        else -> error("object target kind is required")
    }
    fun objectRole(v: DC.ObjectRole): ObjectRole = ObjectRole.newBuilder().addAllObjectKeys(v.objectKeys).setTarget(objectTarget(v.target)).build()
    fun objectRole(v: ObjectRole): DC.ObjectRole = DC.ObjectRole(v.objectKeysList, objectTarget(v.target))
    fun alignmentPopulation(v: DC.AlignmentPopulation): AlignmentPopulation = AlignmentPopulation.newBuilder().setKind(v.kind.name).addAllInputIds(v.inputIds).build()
    fun alignmentPopulation(v: AlignmentPopulation): DC.AlignmentPopulation = DC.AlignmentPopulation(DC.PopulationKind.valueOf(v.kind), v.inputIdsList)
    fun levels(v: DG): GroupingLevels = GroupingLevels.newBuilder().setOutputAlias(v.outputAlias).addAllLevels(v.levels.map { GroupingLevel.newBuilder().setId(it.id).addAllGroupBy(it.groupBy).build() }).build()
    fun levels(v: GroupingLevels): DG = DG(v.outputAlias, v.levelsList.map { DG.Level(it.id, it.groupByList) })
    private fun call(v: DC.Call): CalculationCall = CalculationCall.newBuilder().setCallId(v.callId).setTarget(CallTarget.newBuilder().apply { when (val target = v.target) {
        is DC.Fixed -> setFixed(SemanticProto.toProto(target.metric)); is DC.Measurement -> setMeasurement(target.slot); is DC.Instance -> setInstance(SemanticProto.toProto(target.instance))
    } }).setOutputAlias(v.outputAlias).putAllDimensionSlots(v.dimensionSlots).putAllMemberSlots(v.memberSlots).putAllMeasurementSlots(v.measurementSlots).putAllRelationSlots(v.relationSlots)
        .putAllArguments(v.arguments.mapValues { binding(it.value) }).setSourceCondition(binding(v.sourceCondition))
        .putAllMembers(v.members.mapValues { member(it.value) }).putAllRelations(v.relations.mapValues { relation(it.value) }).build()
    private fun call(v: CalculationCall): DC.Call = DC.Call(v.callId, when (v.target.kindCase) {
        CallTarget.KindCase.FIXED -> DC.Fixed(SemanticProto.fromProto(v.target.fixed)); CallTarget.KindCase.MEASUREMENT -> DC.Measurement(v.target.measurement)
        CallTarget.KindCase.INSTANCE -> DC.Instance(SemanticProto.fromProto(v.target.instance)); else -> error("call target kind is required")
    }, v.outputAlias, v.dimensionSlotsMap, v.memberSlotsMap, v.measurementSlotsMap, v.relationSlotsMap, v.argumentsMap.mapValues { binding(it.value) }, binding(v.sourceCondition),
        v.membersMap.mapValues { member(it.value) }, v.relationsMap.mapValues { relation(it.value) })

    fun members(v: DC.Members): CalculationMembers = CalculationMembers.newBuilder().setCallId(v.callId).setTarget(MemberTarget.newBuilder().apply { when (val target = v.target) {
        is DC.MemberParameter -> setParameter(target.slot); is DC.MemberSource -> setSource(SemanticProto.toProto(target.source))
    } }).addAllOutputs(v.outputs.map { MemberProjection.newBuilder().setOutputAlias(it.outputAlias).setSlot(it.slot)
        .apply { it.dimension?.let { dimension -> setDimension(dimensionReference(dimension)) } }.build() })
        .putAllClocks(v.clocks.mapValues { binding(it.value) }).setCondition(memberCondition(v.condition))
        .putAllArguments(v.arguments.mapValues { binding(it.value) }).putAllMembers(v.members.mapValues { member(it.value) }).putAllRelations(v.relations.mapValues { relation(it.value) })
        .apply { v.range?.let { setRange(binding(it)) } }.build()
    fun members(v: CalculationMembers): DC.Members = DC.Members(v.callId, when (v.target.kindCase) {
        MemberTarget.KindCase.PARAMETER -> DC.MemberParameter(v.target.parameter); MemberTarget.KindCase.SOURCE -> DC.MemberSource(SemanticProto.fromProto(v.target.source))
        else -> error("member target kind is required")
    }, v.outputsList.map { DC.MemberProjection(it.outputAlias, it.slot, it.dimension.takeIf { _ -> it.hasDimension() }?.let(::dimensionReference)) },
        v.range.takeIf { v.hasRange() }?.let(::binding), v.clocksMap.mapValues { binding(it.value) }, memberCondition(v.condition),
        v.argumentsMap.mapValues { binding(it.value) }, v.membersMap.mapValues { member(it.value) }, v.relationsMap.mapValues { relation(it.value) })

    private fun dimensionReference(v: DC.DimensionReference): DimensionReference = DimensionReference.newBuilder().apply { when (v) {
        is DC.DimensionParameter -> setParameter(v.slot); is DC.PublishedDimension -> setPublished(SemanticTypesProto.dimension(v.reference))
    } }.build()
    private fun dimensionReference(v: DimensionReference): DC.DimensionReference = when (v.kindCase) {
        DimensionReference.KindCase.PARAMETER -> DC.DimensionParameter(v.parameter)
        DimensionReference.KindCase.PUBLISHED -> DC.PublishedDimension(SemanticTypesProto.dimension(v.published))
        else -> error("dimension reference kind is required")
    }
    fun timeRange(v: DC.TimeRange): CalculationTimeRange = CalculationTimeRange.newBuilder().setDimension(dimensionReference(v.dimension)).setWindow(binding(v.window)).build()
    fun timeRange(v: CalculationTimeRange): DC.TimeRange = DC.TimeRange(dimensionReference(v.dimension), binding(v.window))
    fun population(v: RowPopulationContract.Selection): RowPopulation = RowPopulation.newBuilder().setMode(v.mode).addAllCallIds(v.callIds)
        .apply { v.inputIds?.let { setInputIds(StringList.newBuilder().addAllValues(it)) } }.build()
    fun population(v: RowPopulation): RowPopulationContract.Selection = RowPopulationContract.Selection(v.mode, v.callIdsList, v.inputIds.valuesList.takeIf { v.hasInputIds() })

    private fun computeBindings(v: DC.ComputeBindings): ComputeBindings = ComputeBindings.newBuilder().apply { when (v) {
        is DC.ExplicitBindings -> setExplicit(ExplicitComputeBindings.newBuilder().setBindings(bindings(v.bindings))
            .putAllMembers(v.members.mapValues { member(it.value) }).putAllRelations(v.relations.mapValues { relation(it.value) }))
        is DC.ParentSlots -> setParentSlots(ParentSlotBindings.newBuilder().putAllDimensionSlots(v.dimensionSlots)
            .putAllMemberSlots(v.memberSlots).putAllMeasurementSlots(v.measurementSlots).putAllRelationSlots(v.relationSlots))
    } }.build()
    private fun computeBindings(v: ComputeBindings): DC.ComputeBindings = when (v.kindCase) {
        ComputeBindings.KindCase.EXPLICIT -> v.explicit.let { DC.ExplicitBindings(bindings(it.bindings),
            it.membersMap.mapValues { entry -> member(entry.value) }, it.relationsMap.mapValues { entry -> relation(entry.value) }) }
        ComputeBindings.KindCase.PARENT_SLOTS -> v.parentSlots.let { DC.ParentSlots(it.dimensionSlotsMap, it.memberSlotsMap, it.measurementSlotsMap, it.relationSlotsMap) }
        else -> error("compute bindings kind is required")
    }
}
