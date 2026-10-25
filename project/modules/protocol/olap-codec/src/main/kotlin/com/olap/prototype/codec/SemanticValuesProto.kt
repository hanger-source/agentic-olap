package com.olap.prototype.codec

import com.olap.prototype.wire.semantic.*
import com.olap.prototype.query.QueryValue as V
import com.olap.prototype.query.QueryQuantity as Q
import com.olap.prototype.query.QueryStageAnalysis as A
import com.olap.prototype.query.ValuePosition as P
import com.olap.prototype.calculation.Calculation as C
import com.olap.prototype.calculation.CalculationOwner as O
import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.metric.MetricSnapshotRule
import com.olap.prototype.metric.MeanTarget
import com.olap.prototype.metric.MetricValueContract.Rounding
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.GroupingLevels

internal object SemanticValuesProto {
    fun scope(v: A.ScopeId): ScopeId = ScopeId.newBuilder().addAllPath(v.path).build()
    fun scope(v: ScopeId): A.ScopeId = A.ScopeId(v.pathList)
    fun id(v: A.SymbolId): SymbolId = SymbolId.newBuilder().setScope(scope(v.scope)).setPosition(v.position).build()
    fun id(v: SymbolId): A.SymbolId = A.SymbolId(scope(v.scope), v.position)
    fun position(v: P): ValuePosition = ValuePosition.newBuilder().setOwner(id(v.owner)).addAllPath(v.path).build()
    fun position(v: ValuePosition): P = P(id(v.owner), v.pathList)
    fun symbol(v: A.Symbol): Symbol = Symbol.newBuilder().setId(id(v.id)).setName(v.name).setOrigin(origin(v.origin)).addAllDependencies(v.dependencies.map(::id)).setValue(value(v.value)).build()
    fun symbol(v: Symbol): A.Symbol = A.Symbol(id(v.id), v.name, origin(v.origin), v.dependenciesList.map(::id), value(v.value))
    fun observation(v: A.SelectedObservation): SelectedObservation = SelectedObservation.newBuilder().addAllTimeAxes(v.timeAxes.map(::position)).apply { v.asOf?.let { setAsOf(SemanticCalculationProto.scalar(it)) } }.build()
    fun observation(v: SelectedObservation): A.SelectedObservation = A.SelectedObservation(v.timeAxesList.map(::position), v.asOf.takeIf { v.hasAsOf() }?.let(SemanticCalculationProto::scalar))
    fun owner(v: O): CalculationOwner = CalculationOwner.newBuilder().apply { when (v) { is O.Metric -> setMetric(SemanticProto.toProto(v.instance)); is O.Member -> setMember(SemanticProto.toProto(v.source)); is O.Query -> setQuery(StringList.newBuilder().addAllValues(v.path)) } }.build()
    fun owner(v: CalculationOwner): O = when (v.kindCase) { CalculationOwner.KindCase.METRIC -> O.Metric(SemanticProto.fromProto(v.metric)); CalculationOwner.KindCase.MEMBER -> O.Member(SemanticProto.fromProto(v.member)); CalculationOwner.KindCase.QUERY -> O.Query(v.query.valuesList); else -> error("calculation owner kind is required") }
    private fun origin(v: A.Origin): Origin = Origin.newBuilder().apply { when (v) {
        is A.MetricOrigin -> setMetric(MetricOrigin.newBuilder().setScope(scope(v.scope)).setCallId(v.callId).setOwner(owner(v.owner)).setMetric(SemanticProto.toProto(v.metric)))
        is A.DimensionOrigin -> setDimension(DimensionOrigin.newBuilder().setScope(scope(v.scope)).setDimension(SemanticTypesProto.dimension(v.dimension)))
        is A.MemberOrigin -> setMember(MemberOrigin.newBuilder().setScope(scope(v.scope)).setSourceId(v.sourceId).setSourceVersion(v.sourceVersion).setSlot(v.slot).setMeaning(meaning(v.meaning)))
        is A.OperationOrigin -> setOperation(OperationOrigin.newBuilder().setScope(scope(v.scope)).setOperationIndex(v.operationIndex).setOutputIndex(v.outputIndex).setOperationType(v.operationType))
        is A.InputPresenceOrigin -> setInputPresence(scope(v.scope))
        is A.GroupingOrigin -> setGrouping(GroupingOrigin.newBuilder().setScope(scope(v.scope)).setOperationIndex(v.operationIndex).setDefinition(levels(v.definition)))
        is A.OmittedDimensionOrigin -> setOmittedDimension(OmittedDimensionOrigin.newBuilder().setScope(scope(v.scope)).setSource(id(v.source)).setGrouping(id(v.grouping)).setDimension(SemanticTypesProto.dimension(v.dimension)).setLevelId(v.levelId).setLevelCode(v.levelCode))
        is A.SetOrigin -> setSet(SetOrigin.newBuilder().setScope(scope(v.scope)).setOperation(v.operation.name).setAll(v.all).setOutputIndex(v.outputIndex).setShape(SemanticTypesProto.shape(v.shape)))
    } }.build()
    private fun origin(v: Origin): A.Origin = when (v.kindCase) {
        Origin.KindCase.METRIC -> v.metric.let { A.MetricOrigin(scope(it.scope), it.callId, owner(it.owner), SemanticProto.fromProto(it.metric)) }
        Origin.KindCase.DIMENSION -> A.DimensionOrigin(scope(v.dimension.scope), SemanticTypesProto.dimension(v.dimension.dimension))
        Origin.KindCase.MEMBER -> v.member.let { A.MemberOrigin(scope(it.scope), it.sourceId, it.sourceVersion, it.slot, meaning(it.meaning)) }
        Origin.KindCase.OPERATION -> v.operation.let { A.OperationOrigin(scope(it.scope), it.operationIndex, it.outputIndex, it.operationType) }
        Origin.KindCase.INPUT_PRESENCE -> A.InputPresenceOrigin(scope(v.inputPresence))
        Origin.KindCase.GROUPING -> A.GroupingOrigin(scope(v.grouping.scope), v.grouping.operationIndex, levels(v.grouping.definition))
        Origin.KindCase.OMITTED_DIMENSION -> v.omittedDimension.let { A.OmittedDimensionOrigin(scope(it.scope), id(it.source), id(it.grouping), SemanticTypesProto.dimension(it.dimension), it.levelId, it.levelCode) }
        Origin.KindCase.SET -> v.set.let { A.SetOrigin(scope(it.scope), C.SetKind.valueOf(it.operation), it.all, it.outputIndex, SemanticTypesProto.shape(it.shape)) }
        else -> error("symbol origin kind is required")
    }
    private fun levels(v: GroupingLevels.Bound): BoundGroupingLevels = BoundGroupingLevels.newBuilder().setOutputAlias(v.outputAlias).addAllLevels(v.levels.map { BoundGroupingLevel.newBuilder().setId(it.id).setCode(it.code).addAllGroupBy(it.groupBy).build() }).build()
    private fun levels(v: BoundGroupingLevels): GroupingLevels.Bound = GroupingLevels.Bound(v.outputAlias, v.levelsList.map { GroupingLevels.BoundLevel(it.id, it.code, it.groupByList) })
    fun meaning(v: A.MemberMeaning): MemberMeaning = MemberMeaning.newBuilder().apply { when (v) {
        is A.MappedMemberDimension -> setMappedDimension(MappedMemberDimensionMeaning.newBuilder().setDimension(SemanticTypesProto.dimension(v.dimension)).setRole(v.role))
        is A.MemberDimension -> setDimension(MemberDimensionMeaning.newBuilder().setDimensionId(v.dimensionId).setDimensionVersion(v.dimensionVersion).setRole(v.role))
        is A.MemberProperty -> setProperty(PropertyShape.newBuilder().setRole(v.role).setValueType(v.valueType.name))
        is A.MemberContainer -> setContainer(MemberContainerOutput.newBuilder().setRole(v.role).setShape(SemanticTypesProto.shape(v.shape)))
    } }.build()
    fun meaning(v: MemberMeaning): A.MemberMeaning = when (v.kindCase) {
        MemberMeaning.KindCase.MAPPED_DIMENSION -> A.MappedMemberDimension(SemanticTypesProto.dimension(v.mappedDimension.dimension), v.mappedDimension.role)
        MemberMeaning.KindCase.DIMENSION -> A.MemberDimension(v.dimension.dimensionId, v.dimension.dimensionVersion, v.dimension.role)
        MemberMeaning.KindCase.PROPERTY -> A.MemberProperty(ScalarValueType.valueOf(v.property.valueType), v.property.role)
        MemberMeaning.KindCase.CONTAINER -> A.MemberContainer(SemanticTypesProto.shape(v.container.shape), v.container.role)
        else -> error("member meaning kind is required")
    }
    fun value(v: V): QueryValue = QueryValue.newBuilder().apply { when (v) {
        is V.Plain -> setPlain(PlainValue.newBuilder().addAllPositions(v.positions.map(::position)).apply { v.source?.let { setSource(position(it)) } })
        is V.Measure -> setMeasure(quantity(v.quantity))
        is V.Row -> setRow(QueryValues.newBuilder().addAllFields(v.fields.map(::value)))
        is V.Array -> setArray(value(v.element))
    } }.build()
    fun value(v: QueryValue): V = when (v.kindCase) {
        QueryValue.KindCase.PLAIN -> V.Plain(v.plain.source.takeIf { v.plain.hasSource() }?.let(::position), v.plain.positionsList.map(::position))
        QueryValue.KindCase.MEASURE -> V.Measure(quantity(v.measure))
        QueryValue.KindCase.ROW -> V.Row(v.row.fieldsList.map(::value))
        QueryValue.KindCase.ARRAY -> V.Array(value(v.array))
        else -> error("query value kind is required")
    }
    fun quantity(v: Q): QueryQuantity = QueryQuantity.newBuilder().setDeclaration(SemanticTypesProto.value(v.declaration)).setUnit(unit(v.unit)).setProvenance(provenance(v.provenance)).build()
    fun quantity(v: QueryQuantity): Q = Q(SemanticTypesProto.value(v.declaration), unit(v.unit), provenance(v.provenance))
    private fun unit(v: Q.Unit): QuantityUnit = QuantityUnit.newBuilder().apply { when (v) {
        is Q.Fixed -> setFixed(SemanticTypesProto.unitIdentity(v.identity))
        is Q.Dimension -> setDimension(DimensionQuantityUnit.newBuilder().setAxis(SemanticTypesProto.axis(v.axis)).addAllKeys(v.keys.map { QuantityKey.newBuilder().setReference(reference(it.reference)).setPosition(position(it.position)).build() }))
        is Q.SelectedMember -> setSelectedMember(SelectedQuantityUnit.newBuilder().setReference(reference(v.reference)).setAxis(SemanticTypesProto.axis(v.axis)).setMember(SemanticCalculationProto.scalar(v.member)).setIdentity(SemanticTypesProto.unitIdentity(v.identity))
            .setSelection(UnitSelection.newBuilder().setSource(position(v.selection.source)).setTarget(target(v.selection.target))))
    } }.build()
    private fun unit(v: QuantityUnit): Q.Unit = when (v.kindCase) {
        QuantityUnit.KindCase.FIXED -> Q.Fixed(SemanticTypesProto.unitIdentity(v.fixed))
        QuantityUnit.KindCase.DIMENSION -> Q.Dimension(SemanticTypesProto.axis(v.dimension.axis), v.dimension.keysList.map { Q.Key(reference(it.reference), position(it.position)) })
        QuantityUnit.KindCase.SELECTED_MEMBER -> v.selectedMember.let { Q.SelectedMember(reference(it.reference), SemanticTypesProto.axis(it.axis), SemanticCalculationProto.scalar(it.member), SemanticTypesProto.unitIdentity(it.identity), Q.Selection(position(it.selection.source), target(it.selection.target))) }
        else -> error("quantity unit kind is required")
    }
    private fun reference(v: Q.UnitReference): UnitReference = UnitReference.newBuilder().apply { when (v) {
        is Q.SemanticDimensionRef -> setSemanticDimension(SemanticDimensionReference.newBuilder().setDimensionId(v.dimensionId).setDimensionVersion(v.dimensionVersion).setRole(v.role))
        is Q.AnalysisDimensionRef -> setAnalysisDimension(SemanticTypesProto.dimension(v.dimension))
        is Q.MemberSlotRef -> setMemberSlot(memberSlot(v))
    } }.build()
    private fun reference(v: UnitReference): Q.UnitReference = when (v.kindCase) {
        UnitReference.KindCase.SEMANTIC_DIMENSION -> Q.SemanticDimensionRef(v.semanticDimension.dimensionId, v.semanticDimension.dimensionVersion, v.semanticDimension.role)
        UnitReference.KindCase.ANALYSIS_DIMENSION -> Q.AnalysisDimensionRef(SemanticTypesProto.dimension(v.analysisDimension))
        UnitReference.KindCase.MEMBER_SLOT -> memberSlot(v.memberSlot)
        else -> error("unit reference kind is required")
    }
    private fun memberSlot(v: Q.MemberSlotRef): MemberSlotReference = MemberSlotReference.newBuilder().setSourceId(v.sourceId).setSourceVersion(v.sourceVersion).setSlot(v.slot).build()
    private fun memberSlot(v: MemberSlotReference): Q.MemberSlotRef = Q.MemberSlotRef(v.sourceId, v.sourceVersion, v.slot)
    private fun target(v: Q.SelectionTarget): SelectionTarget = SelectionTarget.newBuilder().apply { when (v) {
        is Q.DimensionFilter -> setDimensionFilter(v.filterRefId)
        is Q.MemberFilter -> setMemberFilter(memberSlot(v.source))
        is Q.ResultFilter -> setResultFilter(ResultUnitFilter.newBuilder().setScope(scope(v.scope)).setOperationIndex(v.operationIndex).setKey(id(v.key)).setMember(SemanticCalculationProto.scalar(v.member)))
    } }.build()
    private fun target(v: SelectionTarget): Q.SelectionTarget = when (v.kindCase) {
        SelectionTarget.KindCase.DIMENSION_FILTER -> Q.DimensionFilter(v.dimensionFilter)
        SelectionTarget.KindCase.MEMBER_FILTER -> Q.MemberFilter(memberSlot(v.memberFilter))
        SelectionTarget.KindCase.RESULT_FILTER -> v.resultFilter.let { Q.ResultFilter(scope(it.scope), it.operationIndex, id(it.key), SemanticCalculationProto.scalar(it.member)) }
        else -> error("unit selection target kind is required")
    }
    private fun snapshot(v: Q.Snapshot): QuantitySnapshot = QuantitySnapshot.newBuilder().setReduction(v.reduction.name).setNullPolicy(v.nullPolicy.name)
        .addAllTimeAxes(v.timeAxes.map(::position)).putAllBoundClocks(v.boundClocks.mapValues { SemanticTypesProto.clock(it.value) })
        .apply { v.meanTarget?.let { setMeanTarget(it.name) }; v.meanValueContract?.let { setMeanValueContract(SemanticTypesProto.value(it)) } }.build()
    private fun snapshot(v: QuantitySnapshot): Q.Snapshot = Q.Snapshot(MetricSnapshotRule.Reduction.valueOf(v.reduction), MetricSnapshotRule.NullPolicy.valueOf(v.nullPolicy),
        v.timeAxesList.map(::position), v.boundClocksMap.mapValues { SemanticTypesProto.clock(it.value) },
        v.meanTarget.takeIf { v.hasMeanTarget() }?.let(MeanTarget::valueOf), v.meanValueContract.takeIf { v.hasMeanValueContract() }?.let(SemanticTypesProto::value))
    private fun order(v: Q.Order): QuantityOrder = QuantityOrder.newBuilder().setPosition(position(v.position)).setDirection(v.direction).setNulls(v.nulls).build()
    private fun order(v: QuantityOrder): Q.Order = Q.Order(position(v.position), v.direction, v.nulls)
    private fun window(v: Q.Window): WindowQuantity = WindowQuantity.newBuilder().setAggregate(SemanticStagesProto.aggregation(v.aggregation))
        .setInput(provenance(v.input)).addAllObservations(v.observations.map(::position)).addAllPartition(v.partition.map(::position)).addAllOrder(v.order.map(::order))
        .setCumulative(v.cumulative).setTotalOrder(v.totalOrder)
        .apply { v.frame?.let { setFrame(SemanticStagesProto.frame(it)) }; v.inputDeclaration?.let { setInputDeclaration(SemanticTypesProto.value(it)) } }.build()
    private fun window(v: WindowQuantity): Q.Window = Q.Window(required(v.hasAggregate(), "WindowQuantity.aggregate") { SemanticStagesProto.aggregation(v.aggregate) },
        provenance(v.input), v.observationsList.map(::position), v.partitionList.map(::position), v.orderList.map(::order), v.frame.takeIf { v.hasFrame() }?.let(SemanticStagesProto::frame),
        v.cumulative, v.totalOrder, v.inputDeclaration.takeIf { v.hasInputDeclaration() }?.let(SemanticTypesProto::value))
    private fun provenance(v: Q.Provenance): QuantityProvenance = QuantityProvenance.newBuilder().apply { when (v) {
        is Q.Raw -> setRaw(RawQuantity.newBuilder().addAllObservations(v.observations.map(::position)).apply { v.source?.let { setSource(position(it)) } })
        is Q.Constant -> setConstant(v.value)
        is Q.Fact -> setFact(FactQuantity.newBuilder().setDefinition(SemanticProto.toProto(v.definition)).setAggregation(v.aggregation.name).setDistinct(v.distinct)
            .setSource(position(v.source)).addAllObservations(v.observations.map(::position)).setValueContract(SemanticTypesProto.value(v.valueContract))
            .apply { v.snapshot?.let { setSnapshot(snapshot(it)) }; v.meanTarget?.let { setMeanTarget(it.name) } })
        is Q.SelectedSnapshot -> setSelectedSnapshot(SelectedSnapshotQuantity.newBuilder().setInput(provenance(v.input)).addAllTimeAxes(v.timeAxes.map(::position)).apply { v.asOf?.let { setAsOf(SemanticCalculationProto.scalar(it)) } })
        is Q.Reduction -> setReduction(ReducedQuantity.newBuilder().setAggregate(SemanticStagesProto.aggregation(v.aggregation)).setInput(provenance(v.input))
            .addAllObservations(v.observations.map(::position)).apply { v.inputDeclaration?.let { setInputDeclaration(SemanticTypesProto.value(it)) } })
        is Q.Declared -> setDeclared(DeclaredQuantity.newBuilder().setInput(provenance(v.input))
            .setInputDeclaration(SemanticTypesProto.value(v.inputDeclaration)).setDeclaration(SemanticTypesProto.value(v.declaration)))
        is Q.Quantized -> setQuantized(QuantizedQuantity.newBuilder().setInput(provenance(v.input)).setInputDeclaration(SemanticTypesProto.value(v.inputDeclaration))
            .setScale(v.scale).setRounding(v.rounding.name))
        is Q.Cast -> setCast(CastQuantity.newBuilder().setInput(provenance(v.input)).setInputDeclaration(SemanticTypesProto.value(v.inputDeclaration))
            .setTargetType(v.targetType.name).setRounding(v.rounding.name).apply { v.precision?.let(::setPrecision); v.scale?.let(::setScale) })
        is Q.UniformAllocation -> setUniformAllocation(AllocatedQuantity.newBuilder().setInput(provenance(v.input)).addAllSourceKeys(v.sourceKeys.map(::position)).addAllMemberKeys(v.memberKeys.map(::position)))
        is Q.Linear -> setLinear(QuantityTerms.newBuilder().addAllValues(v.terms.map(::provenance)))
        is Q.Observed -> setObserved(ObservedQuantity.newBuilder().setNature(v.nature.name).addAllComponents(v.components.map(::provenance)).addAllObservations(v.observations.map(::position)).setKnownObservation(v.knownObservation))
        is Q.Window -> setWindow(window(v))
        is Q.Offset -> setOffset(OffsetQuantity.newBuilder().setInput(provenance(v.input)).addAllPartition(v.partition.map(::position)).addAllOrder(v.order.map(::order)).setFunction(v.function).setDistance(v.distance).setTotalOrder(v.totalOrder))
        is Q.Copied -> setCopied(provenance(v.input))
        is Q.Delta -> setDelta(window(v.cumulative))
        is Q.Alternatives -> setAlternatives(QuantityTerms.newBuilder().addAllValues(v.branches.map(::provenance)))
    } }.build()
    private fun provenance(v: QuantityProvenance): Q.Provenance = when (v.kindCase) {
        QuantityProvenance.KindCase.RAW -> Q.Raw(v.raw.source.takeIf { v.raw.hasSource() }?.let(::position), v.raw.observationsList.map(::position))
        QuantityProvenance.KindCase.CONSTANT -> Q.Constant(v.constant)
        QuantityProvenance.KindCase.FACT -> v.fact.let { Q.Fact(SemanticProto.fromProto(it.definition), MetricAggregation.valueOf(it.aggregation), it.distinct, position(it.source),
            it.observationsList.map(::position), it.snapshot.takeIf { _ -> it.hasSnapshot() }?.let(::snapshot), it.meanTarget.takeIf { _ -> it.hasMeanTarget() }?.let(MeanTarget::valueOf),
            required(it.hasValueContract(), "FactQuantity.valueContract") { SemanticTypesProto.value(it.valueContract) }) }
        QuantityProvenance.KindCase.SELECTED_SNAPSHOT -> Q.SelectedSnapshot(provenance(v.selectedSnapshot.input), v.selectedSnapshot.timeAxesList.map(::position), v.selectedSnapshot.asOf.takeIf { v.selectedSnapshot.hasAsOf() }?.let(SemanticCalculationProto::scalar))
        QuantityProvenance.KindCase.REDUCTION -> v.reduction.let { Q.Reduction(required(it.hasAggregate(), "ReducedQuantity.aggregate") { SemanticStagesProto.aggregation(it.aggregate) },
            provenance(it.input), it.observationsList.map(::position), it.inputDeclaration.takeIf { _ -> it.hasInputDeclaration() }?.let(SemanticTypesProto::value)) }
        QuantityProvenance.KindCase.DECLARED -> v.declared.let { Q.Declared(provenance(it.input),
            required(it.hasInputDeclaration(), "DeclaredQuantity.inputDeclaration") { SemanticTypesProto.value(it.inputDeclaration) },
            required(it.hasDeclaration(), "DeclaredQuantity.declaration") { SemanticTypesProto.value(it.declaration) }) }
        QuantityProvenance.KindCase.QUANTIZED -> v.quantized.let { Q.Quantized(provenance(it.input),
            required(it.hasInputDeclaration(), "QuantizedQuantity.inputDeclaration") { SemanticTypesProto.value(it.inputDeclaration) }, it.scale, Rounding.valueOf(it.rounding)) }
        QuantityProvenance.KindCase.CAST -> v.cast.let { Q.Cast(provenance(it.input),
            required(it.hasInputDeclaration(), "CastQuantity.inputDeclaration") { SemanticTypesProto.value(it.inputDeclaration) },
            com.olap.prototype.expression.SemanticExpression.ValueType.valueOf(it.targetType), it.precision.takeIf { _ -> it.hasPrecision() }, it.scale.takeIf { _ -> it.hasScale() }, Rounding.valueOf(it.rounding)) }
        QuantityProvenance.KindCase.UNIFORM_ALLOCATION -> Q.UniformAllocation(provenance(v.uniformAllocation.input), v.uniformAllocation.sourceKeysList.map(::position), v.uniformAllocation.memberKeysList.map(::position))
        QuantityProvenance.KindCase.LINEAR -> Q.Linear(v.linear.valuesList.map(::provenance))
        QuantityProvenance.KindCase.OBSERVED -> Q.Observed(Q.Nature.valueOf(v.observed.nature), v.observed.componentsList.map(::provenance), v.observed.observationsList.map(::position), v.observed.knownObservation)
        QuantityProvenance.KindCase.WINDOW -> window(v.window)
        QuantityProvenance.KindCase.OFFSET -> v.offset.let { Q.Offset(provenance(it.input), it.partitionList.map(::position), it.orderList.map(::order), it.function, it.distance, it.totalOrder) }
        QuantityProvenance.KindCase.COPIED -> Q.Copied(provenance(v.copied))
        QuantityProvenance.KindCase.DELTA -> Q.Delta(window(v.delta))
        QuantityProvenance.KindCase.ALTERNATIVES -> Q.Alternatives(v.alternatives.valuesList.map(::provenance))
        else -> error("quantity provenance kind is required")
    }
}
