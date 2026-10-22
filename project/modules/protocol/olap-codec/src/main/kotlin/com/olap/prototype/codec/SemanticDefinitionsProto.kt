package com.olap.prototype.codec

import com.olap.prototype.wire.semantic.*
import com.olap.prototype.calculation.MetricDefinition as D
import com.olap.prototype.calculation.MetricCalculation as C
import com.olap.prototype.calculation.MetricInstance as I
import com.olap.prototype.metric.MetricSnapshotRule as S
import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.metric.MetricValueContract.Rounding
import com.olap.prototype.member.MemberSourceDefinition as M
import com.olap.prototype.member.MemberSource as U
import com.olap.prototype.member.MemberResult as R
import com.olap.prototype.member.MemberShape
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.query.ScalarValueType

internal object SemanticDefinitionsProto {
    fun metric(v: D): MetricDefinition = MetricDefinition.newBuilder().apply { when (v) {
        is D.FactMeasure -> setFactMeasure(FactMeasure.newBuilder().setEvent(SemanticProto.toProto(v.event)).setInput(SemanticExpressionsProto.expression(v.input)).setAggregation(v.aggregation.name).setDistinct(v.distinct)
            .putAllRoles(v.roles.mapValues { SemanticTypesProto.path(it.value) }).setValueContract(SemanticTypesProto.value(v.valueContract)).setAggregationRounding(v.aggregationRounding.name)
            .apply { v.privatePredicate?.let { setPrivatePredicate(SemanticExpressionsProto.expression(it)) }; v.snapshotRule?.let { setSnapshotRule(snapshot(it)) } })
        is C -> setCalculation(calculation(v))
    } }.build()
    fun metric(v: MetricDefinition): D = when (v.kindCase) {
        MetricDefinition.KindCase.FACT_MEASURE -> v.factMeasure.let { D.FactMeasure(SemanticProto.fromProto(it.event), SemanticExpressionsProto.expression(it.input), MetricAggregation.valueOf(it.aggregation), it.distinct,
            it.privatePredicate.takeIf { _ -> it.hasPrivatePredicate() }?.let(SemanticExpressionsProto::expression), it.rolesMap.mapValues { entry -> SemanticTypesProto.path(entry.value) }, it.snapshotRule.takeIf { _ -> it.hasSnapshotRule() }?.let(::snapshot), SemanticTypesProto.value(it.valueContract), Rounding.valueOf(it.aggregationRounding)) }
        MetricDefinition.KindCase.CALCULATION -> calculation(v.calculation)
        else -> error("metric definition kind is required")
    }
    fun calculation(v: C): MetricCalculation = MetricCalculation.newBuilder().setCalculation(SemanticCalculationProto.calculation(v.calculation)).setMeasureOutput(v.measureOutput)
        .addAllKeyOutputs(v.keyOutputs).setValueContract(SemanticTypesProto.value(v.valueContract)).build()
    fun calculation(v: MetricCalculation): C = C(SemanticCalculationProto.calculation(v.calculation), v.measureOutput, v.keyOutputsList, SemanticTypesProto.value(v.valueContract))
    fun instance(v: I): MetricInstance = MetricInstance.newBuilder().setDisplayName(v.displayName).setDefinition(SemanticProto.toProto(v.definition)).setBindings(SemanticCalculationProto.bindings(v.bindings))
        .putAllFixedArguments(v.fixedArguments.mapValues { SemanticCalculationProto.binding(it.value) }).build()
    fun instance(v: MetricInstance): I = I(SemanticProto.fromProto(v.definition), SemanticCalculationProto.bindings(v.bindings), v.fixedArgumentsMap.mapValues { SemanticCalculationProto.value(it.value) }, v.displayName)
    fun snapshot(v: S): MetricSnapshotRule = MetricSnapshotRule.newBuilder().setReduction(v.reduction.name).setEntityRole(v.entityRole).addAllEntityKeys(v.entityKeys.map(::key)).setTimeKey(key(v.timeKey))
        .addAllTieBreakKeys(v.tieBreakKeys.map(::key)).setNullPolicy(v.nullPolicy.name).setMeanRounding(v.meanRounding.name).apply { v.meanValueContract?.let { setMeanValueContract(SemanticTypesProto.value(it)) } }.build()
    fun snapshot(v: MetricSnapshotRule): S = S(S.Reduction.valueOf(v.reduction), v.entityRole, v.entityKeysList.map(::key), key(v.timeKey), v.tieBreakKeysList.map(::key), S.NullPolicy.valueOf(v.nullPolicy), v.meanValueContract.takeIf { v.hasMeanValueContract() }?.let(SemanticTypesProto::value), Rounding.valueOf(v.meanRounding))
    private fun key(v: S.LogicUnitKey): LogicUnitRef = LogicUnitRef.newBuilder().setId(v.id).setVersion(v.version).build()
    private fun key(v: LogicUnitRef): S.LogicUnitKey = S.LogicUnitKey(v.id, v.version)
    fun memberDefinition(v: M): MemberSourceDefinition = MemberSourceDefinition.newBuilder().apply { when (v) { is M.Projection -> setProjection(projection(v)); is M.Computed -> setComputed(computed(v)) } }.build()
    fun memberDefinition(v: MemberSourceDefinition): M = when (v.kindCase) { MemberSourceDefinition.KindCase.PROJECTION -> projection(v.projection); MemberSourceDefinition.KindCase.COMPUTED -> computed(v.computed); else -> error("member definition kind is required") }
    private fun projection(v: M.Projection): MemberProjectionDefinition = MemberProjectionDefinition.newBuilder().setEvent(SemanticProto.toProto(v.event)).setResult(result(v.result)).build()
    private fun projection(v: MemberProjectionDefinition): M.Projection = M.Projection(SemanticProto.fromProto(v.event), result(v.result))
    private fun computed(v: M.Computed): MemberComputedDefinition = MemberComputedDefinition.newBuilder().setCalculation(SemanticCalculationProto.calculation(v.calculation)).setResult(result(v.result)).build()
    private fun computed(v: MemberComputedDefinition): M.Computed = M.Computed(SemanticCalculationProto.calculation(v.calculation), result(v.result))
    fun member(v: U): MemberSource = MemberSource.newBuilder().apply { when (v) {
        is U.Projection -> setProjection(ProjectedMemberSource.newBuilder().setId(v.id).setVersion(v.version).setDefinition(projection(v.definition)).putAllOutputExpressions(v.outputExpressions.mapValues { SemanticExpressionsProto.expression(it.value) }))
        is U.Computed -> setComputed(ComputedMemberSource.newBuilder().setId(v.id).setVersion(v.version).setDefinition(computed(v.definition)).setBindings(SemanticCalculationProto.bindings(v.bindings)).putAllFixedArguments(v.fixedArguments.mapValues { SemanticCalculationProto.binding(it.value) }))
    } }.build()
    fun member(v: MemberSource): U = when (v.kindCase) {
        MemberSource.KindCase.PROJECTION -> v.projection.let { U.Projection(it.id, it.version, projection(it.definition), it.outputExpressionsMap.mapValues { entry -> SemanticExpressionsProto.expression(entry.value) }) }
        MemberSource.KindCase.COMPUTED -> v.computed.let { U.Computed(it.id, it.version, computed(it.definition), SemanticCalculationProto.bindings(it.bindings), it.fixedArgumentsMap.mapValues { entry -> SemanticCalculationProto.value(entry.value) }) }
        else -> error("member source kind is required")
    }
    private fun result(v: R): MemberResult = MemberResult.newBuilder().setEntity(v.entity).setRole(v.role).setShape(v.shape.name).addAllIdentitySlots(v.identitySlots)
        .putAllOutputs(v.outputs.mapValues { output(it.value) }).putAllFilterOperators(v.filterOperators.mapValues { StringList.newBuilder().addAllValues(it.value.map { operator -> operator.name }).build() })
        .putAllClockSlots(v.clockSlots).apply { v.timeSlot?.let(::setTimeSlot); v.description?.let(::setDescription) }.build()
    private fun result(v: MemberResult): R = R(v.entity, v.role, MemberShape.valueOf(v.shape), v.identitySlotsList, v.outputsMap.mapValues { output(it.value) },
        v.filterOperatorsMap.mapValues { it.value.valuesList.map(MemberCondition.Operator::valueOf) }, v.timeSlot.takeIf { v.hasTimeSlot() }, v.clockSlotsMap, v.description.takeIf { v.hasDescription() })
    private fun output(v: M.Output): MemberSourceOutput = MemberSourceOutput.newBuilder().apply { when (v) {
        is M.DimensionOutput -> setDimension(SourceDimensionOutput.newBuilder().setDisplayName(v.displayName).setRole(v.role).setValueType(v.valueType.name).setDimensionId(v.dimensionId).setDimensionVersion(v.dimensionVersion).setBindingId(v.bindingId).setBindingVersion(v.bindingVersion).apply { v.valueContract?.let { setValueContract(SemanticTypesProto.value(it)) } })
        is M.PropertyOutput -> setProperty(SourcePropertyOutput.newBuilder().setDisplayName(v.displayName).setRole(v.role).setValueType(v.valueType.name).setLogicUnitId(v.logicUnitId).setLogicUnitVersion(v.logicUnitVersion).setAccessPath(SemanticTypesProto.path(v.accessPath)).apply { v.valueContract?.let { setValueContract(SemanticTypesProto.value(it)) } })
        is M.ComputedDimensionOutput -> setComputedDimension(SourceComputedDimensionOutput.newBuilder().setDisplayName(v.displayName).setRole(v.role).setValueType(v.valueType.name).setDimensionSlot(v.dimensionSlot).setSymbol(v.symbol).apply { v.valueContract?.let { setValueContract(SemanticTypesProto.value(it)) } })
        is M.ComputedPropertyOutput -> setComputedProperty(SourceComputedPropertyOutput.newBuilder().setDisplayName(v.displayName).setRole(v.role).setValueType(v.valueType.name).setSymbol(v.symbol).apply { v.valueContract?.let { setValueContract(SemanticTypesProto.value(it)) } })
        is M.ContainerPropertyOutput -> setContainerProperty(SourceContainerOutput.newBuilder().setDisplayName(v.displayName).setRole(v.role).setShape(SemanticTypesProto.shape(v.shape)).setLogicUnitId(v.logicUnitId).setLogicUnitVersion(v.logicUnitVersion).setAccessPath(SemanticTypesProto.path(v.accessPath)))
        is M.ComputedContainerPropertyOutput -> setComputedContainerProperty(SourceComputedContainerOutput.newBuilder().setDisplayName(v.displayName).setRole(v.role).setShape(SemanticTypesProto.shape(v.shape)).setSymbol(v.symbol))
    } }.build()
    private fun output(v: MemberSourceOutput): M.Output = when (v.kindCase) {
        MemberSourceOutput.KindCase.DIMENSION -> v.dimension.let { M.DimensionOutput(it.displayName, it.role, ScalarValueType.valueOf(it.valueType), it.dimensionId, it.dimensionVersion, it.bindingId, it.bindingVersion, it.valueContract.takeIf { _ -> it.hasValueContract() }?.let(SemanticTypesProto::value)) }
        MemberSourceOutput.KindCase.PROPERTY -> v.property.let { M.PropertyOutput(it.displayName, it.role, ScalarValueType.valueOf(it.valueType), it.logicUnitId, it.logicUnitVersion, SemanticTypesProto.path(it.accessPath), it.valueContract.takeIf { _ -> it.hasValueContract() }?.let(SemanticTypesProto::value)) }
        MemberSourceOutput.KindCase.COMPUTED_DIMENSION -> v.computedDimension.let { M.ComputedDimensionOutput(it.displayName, it.role, ScalarValueType.valueOf(it.valueType), it.dimensionSlot, it.symbol, it.valueContract.takeIf { _ -> it.hasValueContract() }?.let(SemanticTypesProto::value)) }
        MemberSourceOutput.KindCase.COMPUTED_PROPERTY -> v.computedProperty.let { M.ComputedPropertyOutput(it.displayName, it.role, ScalarValueType.valueOf(it.valueType), it.symbol, it.valueContract.takeIf { _ -> it.hasValueContract() }?.let(SemanticTypesProto::value)) }
        MemberSourceOutput.KindCase.CONTAINER_PROPERTY -> v.containerProperty.let { M.ContainerPropertyOutput(it.displayName, it.role, SemanticTypesProto.shape(it.shape), it.logicUnitId, it.logicUnitVersion, SemanticTypesProto.path(it.accessPath)) }
        MemberSourceOutput.KindCase.COMPUTED_CONTAINER_PROPERTY -> v.computedContainerProperty.let { M.ComputedContainerPropertyOutput(it.displayName, it.role, SemanticTypesProto.shape(it.shape), it.symbol) }
        else -> error("member output kind is required")
    }
}
