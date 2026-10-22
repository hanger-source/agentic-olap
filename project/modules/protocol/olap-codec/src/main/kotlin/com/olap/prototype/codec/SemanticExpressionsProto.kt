package com.olap.prototype.codec

import com.olap.prototype.wire.semantic.*
import com.olap.prototype.expression.SemanticExpression as DE
import com.olap.prototype.expression.ScalarFunction
import com.olap.prototype.query.ResultExpression as DR
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.metric.MetricValueContract.Rounding
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

internal object SemanticExpressionsProto {
    fun parent(v: DE.RelationParent): RelationParent = RelationParent.newBuilder().apply { when (v) { is DE.LocalParent -> setLocal(Empty.getDefaultInstance()); is DE.RoleParent -> setRole(v.role) } }.build()
    fun parent(v: RelationParent): DE.RelationParent = when (v.kindCase) { RelationParent.KindCase.LOCAL -> DE.LocalParent(); RelationParent.KindCase.ROLE -> DE.RoleParent(v.role); else -> error("quantified relation parent kind is required") }
    fun literal(v: Any?): ScalarLiteral = ScalarLiteral.newBuilder().apply { when (v) {
        null -> setNullValue(Empty.getDefaultInstance())
        is String -> setText(v)
        is Boolean -> setBoolean(v)
        is BigDecimal -> setDecimal(v.toString())
        is BigInteger -> setBigInteger(v.toString())
        is Long -> setInteger64(v)
        is Int -> setInteger32(v)
        is LocalDate -> setDate(v.toString())
        is LocalDateTime -> setLocalTimestamp(v.toString())
        is OffsetDateTime -> setOffsetTimestamp(v.toString())
        else -> throw IllegalArgumentException("unsupported formal scalar literal type")
    } }.build()
    fun literal(v: ScalarLiteral): Any? = when (v.kindCase) {
        ScalarLiteral.KindCase.NULL_VALUE -> null
        ScalarLiteral.KindCase.TEXT -> v.text
        ScalarLiteral.KindCase.BOOLEAN -> v.boolean
        ScalarLiteral.KindCase.DECIMAL -> BigDecimal(v.decimal)
        ScalarLiteral.KindCase.BIG_INTEGER -> BigInteger(v.bigInteger)
        ScalarLiteral.KindCase.INTEGER64 -> v.integer64
        ScalarLiteral.KindCase.INTEGER32 -> v.integer32
        ScalarLiteral.KindCase.DATE -> LocalDate.parse(v.date)
        ScalarLiteral.KindCase.LOCAL_TIMESTAMP -> LocalDateTime.parse(v.localTimestamp)
        ScalarLiteral.KindCase.OFFSET_TIMESTAMP -> OffsetDateTime.parse(v.offsetTimestamp)
        else -> error("formal scalar literal kind is required")
    }
    fun expression(v: DE): SemanticExpression = SemanticExpression.newBuilder().apply { when (v) {
        is DE.Reference -> setReference(ExpressionReference.newBuilder().setReferenceKind(v.referenceKind.name).setId(v.id).setVersion(v.version).apply { v.role?.let(::setRole) })
        is DE.Literal -> setLiteral(literal(v.value))
        is DE.Call -> setCall(ExpressionCall.newBuilder().setFunction(v.function.name).addAllArguments(v.arguments.map(::expression)))
        is DE.NativeCall -> setNativeCall(NativeCall.newBuilder().setConnectorRef(SemanticProto.toProto(v.connectorRef)).setFunction(v.function).addAllArguments(v.arguments.map { argument ->
            NativeArgument.newBuilder().apply { when (argument) {
                is DE.ScalarArgument -> setScalar(expression(argument.expression))
                is DE.TableArgument -> setPhysicalTable(NativeTableArgument.newBuilder().setPhysicalTableId(argument.physicalTableId).setPhysicalTableVersion(argument.physicalTableVersion))
            } }.build()
        }))
        is DE.Operation -> setOperation(ExpressionOperation.newBuilder().setOperator(v.operator.name).addAllOperands(v.operands.map(::expression)))
        is DE.CaseWhen -> setCaseWhen(ExpressionCase.newBuilder().addAllBranches(v.branches.map { ExpressionBranch.newBuilder().setWhen(expression(it.`when`)).setThen(expression(it.then)).build() }).apply { v.otherwise?.let { setOtherwise(expression(it)) } })
        is DE.Exists -> setExists(ExpressionExists.newBuilder().setInstanceId(v.instanceId).setFrom(parent(v.from)).setAccessPath(SemanticTypesProto.path(v.accessPath)).setPredicate(expression(v.predicate)))
        is DE.Cast -> setCast(ExpressionCast.newBuilder().setExpression(expression(v.expression)).setTargetType(v.targetType.name).setRounding(v.rounding.name).apply { v.precision?.let(::setPrecision); v.scale?.let(::setScale) })
        is DE.Round -> setRound(ExpressionRound.newBuilder().setExpression(expression(v.expression)).setScale(v.scale).setRounding(v.rounding.name))
    } }.build()
    fun expression(v: SemanticExpression): DE = when (v.kindCase) {
        SemanticExpression.KindCase.REFERENCE -> v.reference.let { DE.Reference(DE.ReferenceKind.valueOf(it.referenceKind), it.id, it.version, it.role.takeIf { _ -> it.hasRole() }) }
        SemanticExpression.KindCase.LITERAL -> DE.Literal(requireNotNull(literal(v.literal)))
        SemanticExpression.KindCase.CALL -> DE.Call(ScalarFunction.valueOf(v.call.function), v.call.argumentsList.map(::expression))
        SemanticExpression.KindCase.NATIVE_CALL -> v.nativeCall.let { DE.NativeCall(SemanticProto.fromProto(it.connectorRef), it.function, it.argumentsList.map { argument -> when (argument.kindCase) {
            NativeArgument.KindCase.SCALAR -> DE.ScalarArgument(expression(argument.scalar))
            NativeArgument.KindCase.PHYSICAL_TABLE -> DE.TableArgument(argument.physicalTable.physicalTableId, argument.physicalTable.physicalTableVersion)
            else -> error("native argument kind is required")
        } }) }
        SemanticExpression.KindCase.OPERATION -> DE.Operation(DE.Operator.valueOf(v.operation.operator), v.operation.operandsList.map(::expression))
        SemanticExpression.KindCase.CASE_WHEN -> DE.CaseWhen(v.caseWhen.branchesList.map { DE.Branch(expression(it.`when`), expression(it.then)) }, v.caseWhen.otherwise.takeIf { v.caseWhen.hasOtherwise() }?.let(::expression))
        SemanticExpression.KindCase.EXISTS -> v.exists.let { DE.Exists(it.instanceId, parent(it.from), SemanticTypesProto.path(it.accessPath), expression(it.predicate)) }
        SemanticExpression.KindCase.CAST -> v.cast.let { DE.Cast(expression(it.expression), DE.ValueType.valueOf(it.targetType), it.precision.takeIf { _ -> it.hasPrecision() }, it.scale.takeIf { _ -> it.hasScale() }, Rounding.valueOf(it.rounding)) }
        SemanticExpression.KindCase.ROUND -> DE.Round(expression(v.round.expression), v.round.scale, Rounding.valueOf(v.round.rounding))
        else -> error("semantic expression kind is required")
    }
    fun result(v: DR): ResultExpression = ResultExpression.newBuilder().apply { when (v) {
        is DR.ResultCall -> setCall(ResultCall.newBuilder().setFunction(v.function.name).addAllArguments(v.arguments.map(::result)))
        is DR.ResultArgument -> setArgument(ResultArgument.newBuilder().setName(v.name).setValueType(v.valueType.name))
        is DR.ResultSymbol -> setSymbol(v.symbol)
        is DR.ResultLiteral -> setLiteral(literal(v.value))
        is DR.ResultTypedLiteral -> setTypedLiteral(TypedLiteral.newBuilder().setValueType(v.valueType.name).setValue(literal(v.value)))
        is DR.ResultQuantityLiteral -> setQuantityLiteral(QuantityLiteral.newBuilder().setValue(v.value.toString()).setValueContract(SemanticTypesProto.value(v.valueContract)))
        is DR.ResultOperation -> setOperation(ResultOperation.newBuilder().setOperator(v.operator.name).addAllOperands(v.operands.map(::result)))
        is DR.ResultRow -> setRow(ResultExpressions.newBuilder().addAllValues(v.operands.map(::result)))
        is DR.ResultCaseWhen -> setCaseWhen(ResultCase.newBuilder().addAllBranches(v.branches.map { ResultBranch.newBuilder().setWhen(result(it.`when`)).setThen(result(it.then)).build() }).setOtherwise(result(v.otherwise)))
        is DR.ResultCast -> setCast(ResultCast.newBuilder().setExpression(result(v.expression)).setTargetType(v.targetType.name).setRounding(v.rounding.name).apply { v.precision?.let(::setPrecision); v.scale?.let(::setScale) })
        is DR.ResultRound -> setRound(ResultRound.newBuilder().setExpression(result(v.expression)).setScale(v.scale).setRounding(v.rounding.name))
        is DR.ResultNumeric -> setNumeric(ResultNumeric.newBuilder().setExpression(result(v.expression)).setValueContract(SemanticTypesProto.value(v.valueContract)).apply { v.unitEquation?.let(::setUnitEquation) })
        is DR.ResultCollectionSort -> setCollectionSort(CollectionSort.newBuilder().setExpression(result(v.expression)).setDirection(v.direction).setNulls(v.nulls))
        is DR.ResultCollectionText -> setCollectionText(CollectionText.newBuilder().setExpression(result(v.expression)).setDelimiter(v.delimiter).setNulls(v.nulls).apply { v.nullText?.let(::setNullText) })
        is DR.ResultRowField -> setRowField(RowField.newBuilder().setExpression(result(v.expression)).setIndex(v.index))
        is DR.ResultWeightedContribution -> setWeightedContribution(WeightedContribution.newBuilder().setValue(result(v.value)).setWeight(result(v.weight)).setObservationUnit(SemanticCalculationProto.observation(v.observationUnit))
            .setComponent(v.component.name).setValueContract(SemanticTypesProto.value(v.valueContract)).apply { v.unitEquation?.let(::setUnitEquation) })
        is DR.ResultUniformAllocation -> setUniformAllocation(UniformAllocation.newBuilder().setValue(result(v.value)).addAllSourceKeys(v.sourceKeys).addAllMemberKeys(v.memberKeys)
            .setValueContract(SemanticTypesProto.value(v.valueContract)).apply { v.unitEquation?.let(::setUnitEquation) })
    } }.build()
    fun result(v: ResultExpression): DR = when (v.kindCase) {
        ResultExpression.KindCase.CALL -> DR.ResultCall(ScalarFunction.valueOf(v.call.function), v.call.argumentsList.map(::result))
        ResultExpression.KindCase.ARGUMENT -> DR.ResultArgument(v.argument.name, ScalarValueType.valueOf(v.argument.valueType))
        ResultExpression.KindCase.SYMBOL -> DR.ResultSymbol(v.symbol)
        ResultExpression.KindCase.LITERAL -> DR.ResultLiteral(literal(v.literal))
        ResultExpression.KindCase.TYPED_LITERAL -> DR.ResultTypedLiteral(ScalarValueType.valueOf(v.typedLiteral.valueType), literal(v.typedLiteral.value))
        ResultExpression.KindCase.QUANTITY_LITERAL -> DR.ResultQuantityLiteral(BigDecimal(v.quantityLiteral.value), SemanticTypesProto.value(v.quantityLiteral.valueContract))
        ResultExpression.KindCase.OPERATION -> DR.ResultOperation(DR.ResultOperator.valueOf(v.operation.operator), v.operation.operandsList.map(::result))
        ResultExpression.KindCase.ROW -> DR.ResultRow(v.row.valuesList.map(::result))
        ResultExpression.KindCase.CASE_WHEN -> DR.ResultCaseWhen(v.caseWhen.branchesList.map { DR.ResultCaseBranch(result(it.`when`), result(it.then)) }, result(v.caseWhen.otherwise))
        ResultExpression.KindCase.CAST -> v.cast.let { DR.ResultCast(result(it.expression), DE.ValueType.valueOf(it.targetType), it.precision.takeIf { _ -> it.hasPrecision() }, it.scale.takeIf { _ -> it.hasScale() }, Rounding.valueOf(it.rounding)) }
        ResultExpression.KindCase.ROUND -> DR.ResultRound(result(v.round.expression), v.round.scale, Rounding.valueOf(v.round.rounding))
        ResultExpression.KindCase.NUMERIC -> v.numeric.let { DR.ResultNumeric(result(it.expression), SemanticTypesProto.value(it.valueContract), it.unitEquation.takeIf { _ -> it.hasUnitEquation() }) }
        ResultExpression.KindCase.COLLECTION_SORT -> v.collectionSort.let { DR.ResultCollectionSort(result(it.expression), it.direction, it.nulls) }
        ResultExpression.KindCase.COLLECTION_TEXT -> v.collectionText.let { DR.ResultCollectionText(result(it.expression), it.delimiter, it.nulls, it.nullText.takeIf { _ -> it.hasNullText() }) }
        ResultExpression.KindCase.ROW_FIELD -> DR.ResultRowField(result(v.rowField.expression), v.rowField.index)
        ResultExpression.KindCase.WEIGHTED_CONTRIBUTION -> v.weightedContribution.let { DR.ResultWeightedContribution(result(it.value), result(it.weight), SemanticCalculationProto.observation(it.observationUnit), DR.WeightedComponent.valueOf(it.component), SemanticTypesProto.value(it.valueContract), it.unitEquation.takeIf { _ -> it.hasUnitEquation() }) }
        ResultExpression.KindCase.UNIFORM_ALLOCATION -> v.uniformAllocation.let { DR.ResultUniformAllocation(result(it.value), it.sourceKeysList, it.memberKeysList, SemanticTypesProto.value(it.valueContract), it.unitEquation.takeIf { _ -> it.hasUnitEquation() }) }
        else -> error("result expression kind is required")
    }
}
