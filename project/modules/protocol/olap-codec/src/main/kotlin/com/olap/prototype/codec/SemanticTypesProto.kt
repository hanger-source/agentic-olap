package com.olap.prototype.codec

import com.olap.prototype.wire.semantic.*
import com.olap.prototype.calculation.Calculation as DC
import com.olap.prototype.query.SetOutputContract as DS
import com.olap.prototype.metric.MetricValueContract as DV
import com.olap.prototype.query.TemporalJoinContract as DT
import com.olap.prototype.time.TemporalWindow as DW
import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.TemporalClockBindings
import com.olap.prototype.event.EventIdentity as DI
import com.olap.prototype.event.RelationAccessPath as DP
import com.olap.prototype.metric.MetricBusinessMeaning as DB
import com.olap.prototype.expression.NativeAggregateBehavior as DA

internal object SemanticTypesProto {
    fun dimension(v: ScopedInputContract.DimensionRef): DimensionRef = DimensionRef.newBuilder().setId(v.id).setVersion(v.version).setLevelCode(v.levelCode).build()
    fun dimension(v: DimensionRef): ScopedInputContract.DimensionRef = ScopedInputContract.DimensionRef(v.id, v.version, v.levelCode)
    fun path(v: DP): RelationAccessPath = RelationAccessPath.newBuilder().addAllJoinPathRefs(v.joinPathRefs.map(SemanticProto::toProto)).build()
    fun path(v: RelationAccessPath): DP = DP(v.joinPathRefsList.map(SemanticProto::fromProto))
    fun identity(v: DI): EventIdentity = EventIdentity.newBuilder().addAllRecordKey(v.recordKey.map { LogicUnitRef.newBuilder().setId(it.logicUnitId).setVersion(it.logicUnitVersion).build() })
        .putAllEntityRoles(v.entityRoles.mapValues { (_, refs) -> LogicUnitRefs.newBuilder().addAllValues(refs.map { LogicUnitRef.newBuilder().setId(it.logicUnitId).setVersion(it.logicUnitVersion).build() }).build() }).build()
    fun identity(v: EventIdentity): DI = DI(v.recordKeyList.map { DI.LogicUnitRef(it.id, it.version) }, v.entityRolesMap.mapValues { (_, refs) -> refs.valuesList.map { DI.LogicUnitRef(it.id, it.version) } })
    fun window(v: DW): TemporalWindow = TemporalWindow.newBuilder().setValueType(v.valueType.name).setBoundary(v.boundary).apply { v.start?.let(::setStart); v.end?.let(::setEnd) }.build()
    fun window(v: TemporalWindow): DW = DW(DW.ValueType.valueOf(v.valueType), v.boundary, v.start.takeIf { v.hasStart() }, v.end.takeIf { v.hasEnd() })
    fun clock(v: TemporalClockBindings.Value): TemporalClock = TemporalClock.newBuilder().setValueType(v.valueType.name).setValue(v.value).build()
    fun clock(v: TemporalClock): TemporalClockBindings.Value = TemporalClockBindings.Value(DW.ValueType.valueOf(v.valueType), v.value)
    fun temporal(v: DT.Constraint): TemporalConstraint = TemporalConstraint.newBuilder().addAllIntervals(v.intervals.map { interval -> TemporalInterval.newBuilder()
        .setSourceTime(temporalClock(interval.sourceTime)).setTargetValidFromLogicUnitId(interval.targetValidFromLogicUnitId).setTargetValidFromLogicUnitVersion(interval.targetValidFromLogicUnitVersion)
        .setTargetValidToLogicUnitId(interval.targetValidToLogicUnitId).setTargetValidToLogicUnitVersion(interval.targetValidToLogicUnitVersion).setValidFromInclusive(interval.validFromInclusive)
        .setValidToInclusive(interval.validToInclusive).setOpenEndedValidTo(interval.openEndedValidTo).build() }).build()
    fun temporal(v: TemporalConstraint): DT.Constraint = DT.Constraint(v.intervalsList.map { DT.Interval(temporalClock(it.sourceTime), it.targetValidFromLogicUnitId,
        it.targetValidFromLogicUnitVersion, it.targetValidToLogicUnitId, it.targetValidToLogicUnitVersion, it.validFromInclusive, it.validToInclusive, it.openEndedValidTo) })
    private fun temporalClock(v: DT.Clock): TemporalSourceClock = TemporalSourceClock.newBuilder().apply { when (v) {
        is DT.LogicUnitClock -> setLogicUnit(logicClock(v))
        is DT.BackboneLogicUnitClock -> setBackboneLogicUnit(logicClock(v))
        is DT.QueryClock -> setQueryClock(TemporalQueryClock.newBuilder().setValueType(v.valueType.name).setDescription(v.description))
    } }.build()
    private fun logicClock(v: DT.LogicUnitTime): TemporalLogicClock = TemporalLogicClock.newBuilder().setLogicUnitId(v.logicUnitId).setLogicUnitVersion(v.logicUnitVersion)
        .addAllJoinPathRefs(v.joinPathRefs.map { JoinRef.newBuilder().setJoinPathId(it.joinPathId).setJoinPathVersion(it.joinPathVersion).build() }).build()
    private fun temporalClock(v: TemporalSourceClock): DT.Clock = when (v.kindCase) {
        TemporalSourceClock.KindCase.LOGIC_UNIT -> v.logicUnit.let { DT.LogicUnitClock(it.logicUnitId, it.logicUnitVersion, it.joinPathRefsList.map { ref -> DT.SourceTimeJoinPathRef(ref.joinPathId, ref.joinPathVersion) }) }
        TemporalSourceClock.KindCase.BACKBONE_LOGIC_UNIT -> v.backboneLogicUnit.let { DT.BackboneLogicUnitClock(it.logicUnitId, it.logicUnitVersion, it.joinPathRefsList.map { ref -> DT.SourceTimeJoinPathRef(ref.joinPathId, ref.joinPathVersion) }) }
        TemporalSourceClock.KindCase.QUERY_CLOCK -> DT.QueryClock(DW.ValueType.valueOf(v.queryClock.valueType), v.queryClock.description)
        else -> error("temporal source clock kind is required")
    }
    fun unitIdentity(v: DV.UnitIdentity): UnitIdentity = UnitIdentity.newBuilder().setAuthority(v.authority).setId(v.id).setVersion(v.version).build()
    fun unitIdentity(v: UnitIdentity): DV.UnitIdentity = DV.UnitIdentity(v.authority, v.id, v.version)
    fun value(v: DV): MetricValueContract = MetricValueContract.newBuilder().setUnit(unit(v.unit)).setArithmetic(arithmetic(v.arithmetic)).setResult(result(v.result))
        .putAllUnitEquations(v.unitEquations.mapValues { equation(it.value) }).setOverflow(v.overflow.name).build()
    fun value(v: MetricValueContract): DV = DV(unit(v.unit), arithmetic(v.arithmetic), result(v.result), v.unitEquationsMap.mapValues { equation(it.value) }, DV.Overflow.valueOf(v.overflow))
    fun aggregateBehavior(v: DA): NativeAggregateBehavior = NativeAggregateBehavior.newBuilder().apply { when (v) {
        DA.ExactOrFail -> setExactOrFail(Empty.getDefaultInstance())
        is DA.QuantizedMean -> setQuantizedMeanRounding(v.rounding.name)
        DA.NativeFloating -> setNativeFloating(Empty.getDefaultInstance())
    } }.build()
    fun aggregateBehavior(v: NativeAggregateBehavior): DA = when (v.kindCase) {
        NativeAggregateBehavior.KindCase.EXACT_OR_FAIL -> DA.ExactOrFail
        NativeAggregateBehavior.KindCase.QUANTIZED_MEAN_ROUNDING -> DA.QuantizedMean(DV.Rounding.valueOf(v.quantizedMeanRounding))
        NativeAggregateBehavior.KindCase.NATIVE_FLOATING -> DA.NativeFloating
        else -> error("native aggregate behavior kind is required")
    }
    fun axis(v: DV.UnitAxis): UnitAxis = UnitAxis.newBuilder().setDimensionId(v.dimensionId).setDimensionVersion(v.dimensionVersion).setRole(v.role).setMemberType(v.memberType.name)
        .addAllMembers(v.members.map { UnitMember.newBuilder().setMember(SemanticCalculationProto.scalar(it.member)).setUnit(unitIdentity(it.unit)).build() }).build()
    fun axis(v: UnitAxis): DV.UnitAxis = DV.UnitAxis(v.dimensionId, v.dimensionVersion, v.role, ScalarValueType.valueOf(v.memberType), v.membersList.map { DV.UnitMember(SemanticCalculationProto.scalar(it.member), unitIdentity(it.unit)) })
    private fun unit(v: DV.Unit): ValueUnit = ValueUnit.newBuilder().apply { when (v) {
        is DV.Fixed -> setFixed(unitIdentity(v.identity))
        is DV.EventDimension -> setEventDimension(EventDimensionUnit.newBuilder().setBindingId(v.bindingId).setBindingVersion(v.bindingVersion).setAxis(axis(v.axis)))
        is DV.SlotDimension -> setSlotDimension(SlotDimensionUnit.newBuilder().setSlot(v.slot).setAxis(axis(v.axis)))
        is DV.AnalysisDimension -> setAnalysisDimension(AnalysisDimensionUnit.newBuilder().setDimension(dimension(v.dimension)).setAxis(axis(v.axis)))
    } }.build()
    private fun unit(v: ValueUnit): DV.Unit = when (v.kindCase) {
        ValueUnit.KindCase.FIXED -> DV.Fixed(unitIdentity(v.fixed))
        ValueUnit.KindCase.EVENT_DIMENSION -> v.eventDimension.let { DV.EventDimension(it.bindingId, it.bindingVersion, axis(it.axis)) }
        ValueUnit.KindCase.SLOT_DIMENSION -> v.slotDimension.let { DV.SlotDimension(it.slot, axis(it.axis)) }
        ValueUnit.KindCase.ANALYSIS_DIMENSION -> v.analysisDimension.let { DV.AnalysisDimension(dimension(it.dimension), axis(it.axis)) }
        else -> error("quantity unit kind is required")
    }
    fun arithmetic(v: DV.Arithmetic): Arithmetic = Arithmetic.newBuilder().apply { when (v) {
        is DV.Exact -> setExact(Empty.getDefaultInstance())
        is DV.NativeFloating -> setNativeFloating(Empty.getDefaultInstance())
        is DV.Approximate -> setApproximate(Approximation.newBuilder().setMinimum(v.minimum).setMaximum(v.maximum).setAbsoluteTolerance(v.absoluteTolerance).setRelativeTolerance(v.relativeTolerance))
    } }.build()
    fun arithmetic(v: Arithmetic): DV.Arithmetic = when (v.kindCase) {
        Arithmetic.KindCase.EXACT -> DV.Exact()
        Arithmetic.KindCase.NATIVE_FLOATING -> DV.NativeFloating()
        Arithmetic.KindCase.APPROXIMATE -> v.approximate.let { DV.Approximate(it.minimum, it.maximum, it.absoluteTolerance, it.relativeTolerance) }
        else -> error("quantity arithmetic kind is required")
    }
    private fun result(v: DV.Result): NumericResult = NumericResult.newBuilder().apply { when (v) {
        is DV.Decimal -> setDecimal(DecimalResult.newBuilder().setPrecision(v.precision).setScale(v.scale).setRounding(v.rounding.name))
        is DV.Integer -> setIntegerBits(v.bits)
        is DV.BinaryFloat -> setBinaryFloatBits(v.bits)
        DV.FiniteDecimal -> setFiniteDecimal(Empty.getDefaultInstance())
    } }.build()
    private fun result(v: NumericResult): DV.Result = when (v.kindCase) {
        NumericResult.KindCase.DECIMAL -> v.decimal.let { DV.Decimal(it.precision, it.scale, DV.Rounding.valueOf(it.rounding)) }
        NumericResult.KindCase.INTEGER_BITS -> DV.Integer(v.integerBits)
        NumericResult.KindCase.BINARY_FLOAT_BITS -> DV.BinaryFloat(v.binaryFloatBits)
        NumericResult.KindCase.FINITE_DECIMAL -> DV.FiniteDecimal
        else -> error("quantity result kind is required")
    }
    private fun equation(v: DV.UnitEquation): UnitEquation = UnitEquation.newBuilder().apply { when (v) {
        is DV.PreserveUnit -> setPreserveUnit(PreserveUnit.newBuilder().setOperator(v.operator.name).setOperand(v.operand.name))
        is DV.FixedBinary -> setFixedBinary(FixedBinaryUnit.newBuilder().setOperator(v.operator.name).setLeft(unitIdentity(v.left)).setRight(unitIdentity(v.right)).setResult(unitIdentity(v.result)))
        is DV.SameUnitRatio -> setSameUnitRatio(Empty.getDefaultInstance())
        is DV.Scale -> setScale(ScaleUnit.newBuilder().setOperator(v.operator.name).setFrom(unitIdentity(v.from)).setTo(unitIdentity(v.to)).setFactor(v.factor))
        is DV.PreserveUnitScale -> setPreserveUnitScale(PreserveUnitScale.newBuilder().setOperator(v.operator.name).setFactor(v.factor))
    } }.build()
    private fun equation(v: UnitEquation): DV.UnitEquation = when (v.kindCase) {
        UnitEquation.KindCase.PRESERVE_UNIT -> v.preserveUnit.let { DV.PreserveUnit(DV.BinaryOperator.valueOf(it.operator), DV.Operand.valueOf(it.operand)) }
        UnitEquation.KindCase.FIXED_BINARY -> v.fixedBinary.let { DV.FixedBinary(DV.BinaryOperator.valueOf(it.operator), unitIdentity(it.left), unitIdentity(it.right), unitIdentity(it.result)) }
        UnitEquation.KindCase.SAME_UNIT_RATIO -> DV.SameUnitRatio()
        UnitEquation.KindCase.SCALE -> v.scale.let { DV.Scale(DV.BinaryOperator.valueOf(it.operator), unitIdentity(it.from), unitIdentity(it.to), it.factor) }
        UnitEquation.KindCase.PRESERVE_UNIT_SCALE -> v.preserveUnitScale.let { DV.PreserveUnitScale(DV.BinaryOperator.valueOf(it.operator), it.factor) }
        else -> error("unit equation kind is required")
    }
    fun shape(v: DS.Shape): SetOutputShape = SetOutputShape.newBuilder().apply { when (v) {
        is DS.Dimension -> setDimension(dimension(v.dimension))
        is DS.DimensionSlot -> setDimensionSlot(v.slot)
        is DS.MemberDimension -> setMemberDimension(MemberDimensionShape.newBuilder().setSlot(v.slot).setDimensionId(v.dimensionId).setDimensionVersion(v.dimensionVersion).setRole(v.role))
        is DS.Property -> setProperty(PropertyShape.newBuilder().setRole(v.role).setValueType(v.valueType.name))
        is DS.Scalar -> setScalar(v.valueType.name)
        is DS.Quantity -> setQuantity(value(v.valueContract))
        is DS.Row -> setRow(RowShape.newBuilder().addAllFields(v.fields.map(::shape)))
        is DS.Array -> setArray(shape(v.element))
    } }.build()
    fun shape(v: SetOutputShape): DS.Shape = when (v.kindCase) {
        SetOutputShape.KindCase.DIMENSION -> DS.Dimension(dimension(v.dimension))
        SetOutputShape.KindCase.DIMENSION_SLOT -> DS.DimensionSlot(v.dimensionSlot)
        SetOutputShape.KindCase.MEMBER_DIMENSION -> v.memberDimension.let { DS.MemberDimension(it.slot, it.dimensionId, it.dimensionVersion, it.role) }
        SetOutputShape.KindCase.PROPERTY -> DS.Property(v.property.role, ScalarValueType.valueOf(v.property.valueType))
        SetOutputShape.KindCase.SCALAR -> DS.Scalar(DS.ScalarType.valueOf(v.scalar))
        SetOutputShape.KindCase.QUANTITY -> DS.Quantity(value(v.quantity))
        SetOutputShape.KindCase.ROW -> DS.Row(v.row.fieldsList.map(::shape))
        SetOutputShape.KindCase.ARRAY -> DS.Array(shape(v.array))
        else -> error("set output shape is required")
    }
    fun output(v: DS): SetOutput = SetOutput.newBuilder().setName(v.name).setShape(shape(v.shape)).build()
    fun output(v: SetOutput): DS = DS(v.name, shape(v.shape))
    fun meaning(v: DB): MetricBusinessMeaning = MetricBusinessMeaning.newBuilder().setDefinition(v.definition).setUnit(v.unit).setGrain(v.grain).addAllLimitations(v.limitations).build()
    fun meaning(v: MetricBusinessMeaning): DB = DB(v.definition, v.unit, v.grain, v.limitationsList)
}
