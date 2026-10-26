package com.olap.prototype.expression

import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.connector.NativeAggregateMappingException
import com.olap.prototype.connector.FiniteDecimalTypes
import com.olap.prototype.contract.NativeValueType
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.metric.MeanTarget
import com.olap.prototype.query.ResultAggregation
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.Aggregate
import org.apache.calcite.rel.core.AggregateCall
import org.apache.calcite.rel.core.Project
import org.apache.calcite.rel.core.Calc
import org.apache.calcite.rel.logical.LogicalAggregate
import org.apache.calcite.rel.logical.LogicalValues
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rex.*
import org.apache.calcite.sql.SqlAggFunction
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.type.SqlTypeUtil
import org.apache.calcite.tools.RelBuilder
import org.apache.calcite.util.ImmutableBitSet
import org.slf4j.LoggerFactory

/** 使用实际编译映射读取原生类型；不执行 SQL，也不证明业务数量合同或来源可用性。 */
object NativeAggregateTypes {
    private val log = LoggerFactory.getLogger(NativeAggregateTypes::class.java)

    fun describe(support: CompilationSupport, aggregation: ResultAggregation.Kind, input: NativeValueType, grouped: Boolean): NativeAggregateMapping {
        val builder = CalciteCompilation.builder(support)
        val factory = builder.typeFactory
        val type = try {
            SemanticNativeTypes.resolve(factory, input).also {
                require(SemanticNativeTypes.describe(it) == input) { "input type must retain its exact capacity" }
            }
        } catch (failure: IllegalArgumentException) {
            throw ProducerContractException("aggregate.inputType must specify an exactly representable native type and capacity", failure)
        }
        return try {
            when (aggregation) {
                ResultAggregation.Kind.SUM, ResultAggregation.Kind.AVG, ResultAggregation.Kind.STDDEV_SAMP ->
                    require(SqlTypeUtil.isNumeric(type)) { "numeric aggregate requires a numeric input" }
                else -> Unit
            }
            if (aggregation == ResultAggregation.Kind.STDDEV_SAMP)
                require(support.supports(CompilationSupport.Capability.SAMPLE_STDDEV)) { "native sample deviation is not declared" }
            if (aggregation in setOf(ResultAggregation.Kind.ARRAY_AGG, ResultAggregation.Kind.ARRAY_AGG_DISTINCT))
                require(support.supports(CompilationSupport.Capability.COLLECTION_RESULTS)) { "native collection results are not declared" }
            val inputType = factory.builder().apply { if (grouped) add("group_key", SqlTypeName.INTEGER); add("input", type) }.build()
            val inputRelation = LogicalValues.createEmpty(builder.cluster, inputType)
            val keys = if (grouped) ImmutableBitSet.of(0) else ImmutableBitSet.of()
            val function = RelationalAggregates.aggregateFunction(aggregation, support)
            val call = AggregateCall.create(function, aggregation in setOf(ResultAggregation.Kind.COUNT_DISTINCT, ResultAggregation.Kind.ARRAY_AGG_DISTINCT),
                listOf(if (grouped) 1 else 0), keys.cardinality(), inputRelation, null, "value")
            val owner = LogicalAggregate.create(inputRelation, emptyList(), keys, listOf(keys), listOf(call))
            val result = call.type
            requireDetermined(result)
            NativeAggregateMapping.Mapped(SemanticNativeTypes.describe(result),
                if (aggregation in setOf(ResultAggregation.Kind.SUM, ResultAggregation.Kind.COUNT, ResultAggregation.Kind.COUNT_DISTINCT, ResultAggregation.Kind.AVG))
                    behavior(support, owner, call) else support.aggregateBehavior(owner, call))
        } catch (failure: IllegalArgumentException) {
            unavailable(aggregation, type, failure)
        } catch (failure: UnsupportedOperationException) {
            unavailable(aggregation, type, failure)
        }
    }

    /** 追本次已发射输出的真实聚合；透明列投影不重新推导另一种聚合形态。 */
    fun requireAverage(builder: RelBuilder, alias: String, target: MeanTarget, contract: MetricValueContract,
        inputGuarantees: List<MetricValueContract.Arithmetic>) {
        val system = builder.typeFactory.typeSystem as SemanticCalciteTypeSystem
        if (system.semantic) return
        val (owner, call) = actualAggregate(builder, alias)
        val inputType = owner.input.rowType.fieldList[call.argList.single()].type
        requireTarget(inputType, call.type, behavior(system.nativeSupport, owner, call), target, contract, inputGuarantees)
    }

    /** 可变尺度来源 SUM 必须兑现真实累计行为；表示标签不能替代算术声明。 */
    fun requireFiniteSum(builder: RelBuilder, alias: String) {
        if ((builder.typeFactory.typeSystem as SemanticCalciteTypeSystem).semantic) return
        val (owner, call) = actualAggregate(builder, alias)
        if (FiniteDecimalTypes.isFinite(inputType(owner, call)) || FiniteDecimalTypes.isFinite(call.type)) requireExactWork(owner, call)
    }

    private fun actualAggregate(builder: RelBuilder, alias: String): Pair<Aggregate, AggregateCall> {
        var node: RelNode = builder.peek()
        var index = node.rowType.fieldNames.indexOf(alias)
        require(index >= 0) { "aggregate output is outside its actual relation" }
        val outputType = node.rowType.fieldList[index].type
        while (node is Project || node is Calc) {
            val expression = when (val current = node) {
                is Project -> current.projects[index]
                is Calc -> current.program.expandLocalRef(current.program.projectList[index])
                else -> error("transparent aggregate output")
            }
            val reference = expression as? RexInputRef ?: reject(outputType, NativeAggregateMappingException.Reason.NATIVE_BEHAVIOR_UNAVAILABLE)
            index = reference.index
            node = node.getInput(0)
        }
        val owner = node as? Aggregate ?: reject(outputType, NativeAggregateMappingException.Reason.NATIVE_BEHAVIOR_UNAVAILABLE)
        val call = owner.aggCallList.getOrNull(index - owner.groupCount)
            ?: reject(outputType, NativeAggregateMappingException.Reason.NATIVE_BEHAVIOR_UNAVAILABLE)
        if (!SqlTypeUtil.equalSansNullability(builder.typeFactory, outputType, call.type))
            reject(outputType, NativeAggregateMappingException.Reason.NATIVE_RESULT_TYPE_MISMATCH)
        return owner to call
    }

    fun requireFiniteSum(builder: RelBuilder, value: RexNode) {
        val system = builder.typeFactory.typeSystem as SemanticCalciteTypeSystem
        if (system.semantic) return
        value.accept(object : RexVisitorImpl<Void?>(true) {
            override fun visitOver(over: RexOver): Void? {
                if (over.aggOperator.kind != SqlKind.SUM || !FiniteDecimalTypes.isFinite(over.type) && over.operands.none { FiniteDecimalTypes.isFinite(it.type) }) return null
                val input = over.operands.single().type
                val inferred = over.aggOperator.inferReturnType(RexCallBinding(builder.typeFactory, over.aggOperator, over.operands, emptyList()))
                if (inferred == null || !SqlTypeUtil.equalSansNullability(builder.typeFactory, over.type, inferred) ||
                    system.nativeSupport.aggregateBehavior(over) != NativeAggregateBehavior.ExactOrFail)
                    throw NativeAggregateMappingException(MetricAggregation.SUM, input.fullTypeString,
                        IllegalArgumentException("finite decimal window SUM needs its actual ExactOrFail mapping"), NativeAggregateMappingException.Reason.NATIVE_BEHAVIOR_UNAVAILABLE)
                return null
            }
        })
    }

    /** toRex 已形成真实窗口，NULL 政策与最终量化尚未包裹它。 */
    fun requireAverage(builder: RelBuilder, value: RexNode, function: SqlAggFunction, target: MeanTarget, contract: MetricValueContract,
        inputGuarantees: List<MetricValueContract.Arithmetic>) {
        val system = builder.typeFactory.typeSystem as SemanticCalciteTypeSystem
        if (system.semantic) return
        val windows = mutableListOf<RexOver>()
        value.accept(object : RexVisitorImpl<Void?>(true) {
            override fun visitOver(over: RexOver): Void? { if (over.aggOperator === function) windows.add(over); return null }
        })
        val over = windows.singleOrNull() ?: reject(value.type, NativeAggregateMappingException.Reason.NATIVE_BEHAVIOR_UNAVAILABLE)
        val inputType = over.operands.single().type
        val behavior = try { system.nativeSupport.aggregateBehavior(over) }
        catch (failure: NativeAggregateMappingException) { throw failure }
        catch (failure: IllegalArgumentException) { throw NativeAggregateMappingException(MetricAggregation.AVG, inputType.fullTypeString, failure) }
        catch (failure: UnsupportedOperationException) { throw NativeAggregateMappingException(MetricAggregation.AVG, inputType.fullTypeString, failure) }
        requireTarget(inputType, over.type, behavior, target, contract, inputGuarantees)
    }

    /** 核验本次真实 SUM／COUNT 的工作保证；调用者继续核验所需的工作容量和业务输入保证。 */
    fun requireExactWork(owner: Aggregate, call: AggregateCall) {
        require(owner.aggCallList.contains(call)) { "exact work requires a call from its actual aggregate" }
        require(call.aggregation.kind in setOf(SqlKind.SUM, SqlKind.COUNT)) { "exact work requires an actual SUM or COUNT" }
        val support = (owner.cluster.typeFactory.typeSystem as SemanticCalciteTypeSystem).nativeSupport
        val input = inputType(owner, call)
        if (!SqlTypeUtil.isExactNumeric(call.type) || call.aggregation.kind == SqlKind.SUM &&
            (call.argList.size != 1 || !SqlTypeUtil.isExactNumeric(input)))
            reject(call, input, NativeAggregateMappingException.Reason.NATIVE_RESULT_TYPE_MISMATCH)
        if (behavior(support, owner, call) != NativeAggregateBehavior.ExactOrFail)
            reject(call, input, NativeAggregateMappingException.Reason.NATIVE_BEHAVIOR_UNAVAILABLE)
    }

    private fun inputType(owner: Aggregate, call: AggregateCall): RelDataType =
        call.argList.singleOrNull()?.let { owner.input.rowType.fieldList[it].type } ?: call.type

    private fun behavior(support: CompilationSupport, owner: Aggregate, call: AggregateCall): NativeAggregateBehavior? {
        val input = inputType(owner, call)
        val inferred = try {
            requireDetermined(call.type)
            call.aggregation.inferReturnType(call.createBinding(owner))
        } catch (failure: NativeAggregateMappingException) { throw failure }
        catch (failure: IllegalArgumentException) {
            throw NativeAggregateMappingException(aggregation(call), input.fullTypeString, failure, NativeAggregateMappingException.Reason.NATIVE_RESULT_TYPE_MISMATCH)
        } catch (failure: UnsupportedOperationException) {
            throw NativeAggregateMappingException(aggregation(call), input.fullTypeString, failure, NativeAggregateMappingException.Reason.NATIVE_RESULT_TYPE_MISMATCH)
        }
        if (inferred == null || !SqlTypeUtil.equalSansNullability(owner.cluster.typeFactory, call.type, inferred))
            reject(call, input, NativeAggregateMappingException.Reason.NATIVE_RESULT_TYPE_MISMATCH)
        return try { support.aggregateBehavior(owner, call).also { actual ->
            if (actual is NativeAggregateBehavior.QuantizedMean &&
                (call.type.sqlTypeName != SqlTypeName.DECIMAL || FiniteDecimalTypes.isFinite(call.type)))
                reject(call, input, NativeAggregateMappingException.Reason.NATIVE_RESULT_TYPE_MISMATCH)
        } }
        catch (failure: NativeAggregateMappingException) { throw failure }
        catch (failure: IllegalArgumentException) { throw NativeAggregateMappingException(aggregation(call), input.fullTypeString, failure) }
        catch (failure: UnsupportedOperationException) { throw NativeAggregateMappingException(aggregation(call), input.fullTypeString, failure) }
    }

    private fun requireTarget(input: RelDataType, result: RelDataType, behavior: NativeAggregateBehavior?, target: MeanTarget,
        contract: MetricValueContract, inputGuarantees: List<MetricValueContract.Arithmetic>) {
        requireDetermined(result)
        if (!SqlTypeUtil.isNumeric(result)) reject(input, NativeAggregateMappingException.Reason.NATIVE_RESULT_TYPE_MISMATCH)
        require(inputGuarantees.isNotEmpty()) { "AVG requires the actual input arithmetic guarantees" }
        inputGuarantees.forEach { guarantee ->
            try { MetricValueContract.requireArithmeticInput(contract.arithmetic, guarantee) }
            catch (_: IllegalArgumentException) { reject(input, NativeAggregateMappingException.Reason.INPUT_GUARANTEE_UPGRADE) }
        }
        if (contract.arithmetic is MetricValueContract.Approximate && inputGuarantees.any {
            it is MetricValueContract.Approximate && (java.math.BigDecimal(it.absoluteTolerance).signum() != 0 || java.math.BigDecimal(it.relativeTolerance).signum() != 0)
        })
            reject(input, NativeAggregateMappingException.Reason.APPROXIMATION_PROPAGATION_UNAVAILABLE)
        when (behavior) {
            is NativeAggregateBehavior.QuantizedMean -> {
                if (result.sqlTypeName != SqlTypeName.DECIMAL || FiniteDecimalTypes.isFinite(result))
                    reject(input, NativeAggregateMappingException.Reason.NATIVE_RESULT_TYPE_MISMATCH)
                if (target == MeanTarget.MATHEMATICAL && behavior.rounding != MetricValueContract.Rounding.NONE) {
                    val output = contract.result as? MetricValueContract.Decimal
                    if (output == null || output.scale != result.scale || output.rounding != behavior.rounding)
                        reject(input, NativeAggregateMappingException.Reason.MATHEMATICAL_QUANTIZATION_UNPROVED)
                }
            }
            NativeAggregateBehavior.NativeFloating -> {
                if (!SqlTypeUtil.isApproximateNumeric(result)) reject(input, NativeAggregateMappingException.Reason.NATIVE_RESULT_TYPE_MISMATCH)
                if (target != MeanTarget.SOURCE_NATIVE) reject(input, NativeAggregateMappingException.Reason.MATHEMATICAL_QUANTIZATION_UNPROVED)
                if (contract.arithmetic !is MetricValueContract.NativeFloating) reject(input, NativeAggregateMappingException.Reason.INPUT_GUARANTEE_UPGRADE)
            }
            NativeAggregateBehavior.ExactOrFail, null -> reject(input, NativeAggregateMappingException.Reason.NATIVE_BEHAVIOR_UNAVAILABLE)
        }
    }

    private fun aggregation(call: AggregateCall): MetricAggregation = when (call.aggregation.kind) {
        SqlKind.SUM -> MetricAggregation.SUM
        SqlKind.COUNT -> MetricAggregation.COUNT
        SqlKind.AVG -> MetricAggregation.AVG
        else -> error("aggregate behavior requires an actual SUM, COUNT or AVG")
    }

    private fun reject(call: AggregateCall, input: RelDataType, reason: NativeAggregateMappingException.Reason): Nothing =
        throw NativeAggregateMappingException(aggregation(call), input.fullTypeString, null, reason)

    private fun reject(input: RelDataType, reason: NativeAggregateMappingException.Reason): Nothing =
        throw NativeAggregateMappingException(MetricAggregation.AVG, input.fullTypeString, null, reason)

    private fun requireDetermined(type: RelDataType) {
        if (FiniteDecimalTypes.isFinite(type)) return
        SemanticNativeTypes.describe(type)
        require(type.sqlTypeName != SqlTypeName.DECIMAL || type.precision > 0 && type.scale >= 0) { "native decimal aggregate requires precision and scale" }
        type.componentType?.let(::requireDetermined)
        if (type.isStruct) type.fieldList.forEach { requireDetermined(it.type) }
    }

    private fun unavailable(aggregation: ResultAggregation.Kind, input: RelDataType, failure: Exception): NativeAggregateMapping {
        log.debug("原生聚合映射未提供：aggregate={}, inputType={}", aggregation, input.fullTypeString, failure)
        return NativeAggregateMapping.Unavailable("NATIVE_AGGREGATE_MAPPING_UNAVAILABLE", "connector does not map this aggregate for the declared input type")
    }
}
