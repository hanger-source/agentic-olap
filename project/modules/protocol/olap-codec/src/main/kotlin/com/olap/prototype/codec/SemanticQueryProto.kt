package com.olap.prototype.codec

import com.olap.prototype.wire.semantic.*
import com.olap.prototype.query.QueryComputation as Q
import com.olap.prototype.query.RowPopulationContract
import com.olap.prototype.calculation.Calculation as C

internal object SemanticQueryProto {
    fun timeRange(v: Q.TimeRange): QueryTimeRange = QueryTimeRange.newBuilder().setDimension(SemanticTypesProto.dimension(v.dimension)).setWindow(SemanticTypesProto.window(v.window)).build()
    fun timeRange(v: QueryTimeRange): Q.TimeRange = Q.TimeRange(SemanticTypesProto.dimension(v.dimension), SemanticTypesProto.window(v.window))
    fun population(v: RowPopulationContract.Selection): RowPopulation = RowPopulation.newBuilder().setMode(v.mode).addAllCallIds(v.callIds)
        .apply { v.inputIds?.let { setInputIds(StringList.newBuilder().addAllValues(it)) } }.build()
    fun population(v: RowPopulation): RowPopulationContract.Selection = RowPopulationContract.Selection(v.mode, v.callIdsList, v.inputIds.valuesList.takeIf { v.hasInputIds() })
    fun scope(v: Q.Scope): QueryScope = QueryScope.newBuilder().setRoot(root(v.root)).putAllInputs(v.inputs.mapValues { QueryInput.newBuilder().setMode(it.value.mode).setComputation(scope(it.value.computation)).build() })
        .addAllStages(v.stages.map(SemanticStagesProto::stage)).build()
    fun scope(v: QueryScope): Q.Scope = Q.Scope(root(v.root), v.inputsMap.mapValues { Q.Input(it.value.mode, scope(it.value.computation)) }, v.stagesList.map(SemanticStagesProto::stage))
    private fun root(v: Q.Root): QueryRoot = QueryRoot.newBuilder().apply { when (v) {
        is Q.Compute -> setCompute(QueryCompute.newBuilder().setCalculation(SemanticCalculationProto.calculation(v.calculation)).setBindings(SemanticCalculationProto.bindings(v.bindings))
            .putAllArguments(v.arguments.mapValues { SemanticCalculationProto.binding(it.value) }).putAllMembers(v.members.mapValues { SemanticCalculationProto.member(it.value) }).putAllRelations(v.relations.mapValues { SemanticCalculationProto.relation(it.value) }))
        is Q.Reuse -> setInput(v.inputId)
        is Q.SetInputs -> setSet(SemanticCalculationProto.set(v.operation, v.all, v.inputs, v.outputSlots))
        is Q.Measures -> setMeasures(QueryMeasures.newBuilder().addAllCalls(v.calls.map(::invocation)).addAllGrouping(v.grouping.map(SemanticTypesProto::dimension))
            .putAllSourceClocks(v.sourceClocks.mapValues { SemanticTypesProto.clock(it.value) }).setSourceCondition(SemanticCalculationProto.condition(v.sourceCondition)).setOutputCondition(SemanticCalculationProto.condition(v.outputCondition))
            .addAllSourceEventConditions(v.sourceEventConditions.map { EventCondition.newBuilder().setEventId(it.eventId).setEventVersion(it.eventVersion).setCondition(SemanticCalculationProto.condition(it.condition)).build() })
            .setPopulation(RowPopulation.newBuilder().setMode(v.population.mode).addAllCallIds(v.population.callIds).apply { v.population.inputIds?.let { setInputIds(StringList.newBuilder().addAllValues(it)) } })
            .apply { v.sourceTimeRange?.let { setSourceTimeRange(timeRange(it)) }
                v.groupingLevels?.let { setGroupingLevels(QueryGroupingLevels.newBuilder().setOutputAlias(it.outputAlias).addAllLevels(it.levels.map { level -> QueryGroupingLevel.newBuilder().setId(level.id).addAllDimensions(level.dimensions.map(SemanticTypesProto::dimension)).build() })) } })
        is Q.Members -> setMembers(members(v))
    } }.build()
    private fun root(v: QueryRoot): Q.Root = when (v.kindCase) {
        QueryRoot.KindCase.COMPUTE -> v.compute.let { Q.Compute(SemanticCalculationProto.calculation(it.calculation), SemanticCalculationProto.bindings(it.bindings),
            it.argumentsMap.mapValues { entry -> SemanticCalculationProto.value(entry.value) }, it.membersMap.mapValues { entry -> SemanticCalculationProto.member(entry.value) }, it.relationsMap.mapValues { entry -> SemanticCalculationProto.relation(entry.value) }) }
        QueryRoot.KindCase.INPUT -> Q.Reuse(v.input)
        QueryRoot.KindCase.SET -> Q.SetInputs(C.SetKind.valueOf(v.set.operation), v.set.all, v.set.inputsList.map(SemanticCalculationProto::projection), v.set.outputSlotsList.map(SemanticTypesProto::output))
        QueryRoot.KindCase.MEASURES -> measures(v.measures)
        QueryRoot.KindCase.MEMBERS -> members(v.members)
        else -> error("query root kind is required")
    }
    private fun measures(v: QueryMeasures): Q.Measures {
        val levels = if (v.hasGroupingLevels()) Q.Levels(v.groupingLevels.outputAlias, v.groupingLevels.levelsList.map { level ->
            Q.DimensionLevel(level.id, level.dimensionsList.map(SemanticTypesProto::dimension))
        }) else null
        return Q.Measures(v.callsList.map(::invocation), v.groupingList.map(SemanticTypesProto::dimension),
            v.sourceTimeRange.takeIf { v.hasSourceTimeRange() }?.let(::timeRange), v.sourceClocksMap.mapValues { SemanticTypesProto.clock(it.value) },
            SemanticCalculationProto.condition(v.sourceCondition), SemanticCalculationProto.condition(v.outputCondition),
            v.sourceEventConditionsList.map { Q.EventCondition(it.eventId, it.eventVersion, SemanticCalculationProto.condition(it.condition)) }, population(v.population), levels)
    }
    fun members(v: Q.Members): QueryMembers = QueryMembers.newBuilder().setCallId(v.callId).setSource(SemanticProto.toProto(v.source)).putAllArguments(v.arguments.mapValues { SemanticCalculationProto.binding(it.value) })
        .addAllOutputs(v.outputs.map { MemberProjection.newBuilder().setOutputAlias(it.outputAlias).setSlot(it.slot).apply { if (it is Q.MappedDimension) setDimension(SemanticTypesProto.dimension(it.dimension)) }.build() })
        .putAllClocks(v.clocks.mapValues { SemanticTypesProto.clock(it.value) }).setCondition(SemanticCalculationProto.memberCondition(v.condition))
        .putAllMembers(v.members.mapValues { SemanticCalculationProto.member(it.value) }).putAllRelations(v.relations.mapValues { SemanticCalculationProto.relation(it.value) })
        .apply { v.range?.let { setRange(SemanticTypesProto.window(it)) } }.build()
    fun members(v: QueryMembers): Q.Members = Q.Members(v.callId, SemanticProto.fromProto(v.source), v.argumentsMap.mapValues { SemanticCalculationProto.value(it.value) },
        v.outputsList.map { output -> if (output.hasDimension()) Q.MappedDimension(output.outputAlias, output.slot, SemanticTypesProto.dimension(output.dimension)) else Q.Slot(output.outputAlias, output.slot) },
        v.range.takeIf { v.hasRange() }?.let(SemanticTypesProto::window), v.clocksMap.mapValues { SemanticTypesProto.clock(it.value) }, SemanticCalculationProto.memberCondition(v.condition),
        v.membersMap.mapValues { SemanticCalculationProto.member(it.value) }, v.relationsMap.mapValues { SemanticCalculationProto.relation(it.value) })
    private fun invocation(v: Q.Invocation): QueryInvocation = QueryInvocation.newBuilder().setCallId(v.callId).setInstance(SemanticProto.toProto(v.instance)).setOutputAlias(v.outputAlias)
        .putAllArguments(v.arguments.mapValues { SemanticCalculationProto.binding(it.value) }).putAllMembers(v.members.mapValues { SemanticCalculationProto.member(it.value) })
        .putAllRelations(v.relations.mapValues { SemanticCalculationProto.relation(it.value) }).setSourceCondition(SemanticCalculationProto.condition(v.sourceCondition)).build()
    private fun invocation(v: QueryInvocation): Q.Invocation = Q.Invocation(v.callId, SemanticProto.fromProto(v.instance), v.outputAlias, v.argumentsMap.mapValues { SemanticCalculationProto.value(it.value) },
        v.membersMap.mapValues { SemanticCalculationProto.member(it.value) }, v.relationsMap.mapValues { SemanticCalculationProto.relation(it.value) }, SemanticCalculationProto.condition(v.sourceCondition))
}
