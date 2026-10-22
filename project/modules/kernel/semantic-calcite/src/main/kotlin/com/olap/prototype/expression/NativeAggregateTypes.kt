package com.olap.prototype.expression

import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.connector.NativeAggregateMappingException
import com.olap.prototype.contract.NativeValueType
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.ResultAggregation
import org.apache.calcite.jdbc.JavaTypeFactoryImpl
import org.apache.calcite.rel.core.Aggregate
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.sql.SqlAggFunction
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.type.SqlTypeUtil
import org.slf4j.LoggerFactory

/** 使用实际编译映射读取原生类型；不执行 SQL，也不证明业务数量合同或来源可用性。 */
object NativeAggregateTypes {
    private val log = LoggerFactory.getLogger(NativeAggregateTypes::class.java)

    sealed interface Mapping { val status: String }
    data class Mapped(val resultType: NativeValueType, val aggregationRounding: MetricValueContract.Rounding) : Mapping {
        override val status: String = "MAPPED"
    }
    data object Unavailable : Mapping {
        override val status: String = "UNAVAILABLE"
        val code: String = "NATIVE_AGGREGATE_MAPPING_UNAVAILABLE"
        val message: String = "connector does not map this aggregate for the declared input type"
    }

    fun describe(support: CompilationSupport, aggregation: ResultAggregation.Kind, input: NativeValueType, grouped: Boolean): Mapping {
        val factory = JavaTypeFactoryImpl(SemanticCalciteTypeSystem(support))
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
            if (aggregation == ResultAggregation.Kind.AVG) averageType(factory, type)
            val result = infer(factory, RelationalAggregates.aggregateFunction(aggregation, support), type, grouped)
            requireDetermined(result)
            Mapped(SemanticNativeTypes.describe(result),
                if (aggregation == ResultAggregation.Kind.AVG) averageRounding(result, support) else MetricValueContract.Rounding.NONE)
        } catch (failure: IllegalArgumentException) {
            unavailable(aggregation, type, failure)
        } catch (failure: UnsupportedOperationException) {
            unavailable(aggregation, type, failure)
        }
    }

    /** AVG 的预检与实际聚合函数必须承诺同一容量；中立语义检查不冒充原生映射。 */
    fun averageType(factory: RelDataTypeFactory, input: RelDataType): RelDataType = try {
        val type = factory.typeSystem.deriveAvgAggType(factory, input)
        require(SqlTypeUtil.isNumeric(type)) { "native AVG requires a numeric result" }
        requireDetermined(type)
        val system = factory.typeSystem as SemanticCalciteTypeSystem
        if (!system.semantic) {
            val actual = infer(factory, RelationalAggregates.aggregateFunction(ResultAggregation.Kind.AVG, system.nativeSupport), input, false)
            require(SqlTypeUtil.equalSansNullability(factory, type, actual)) { "AVG type system and native aggregate disagree" }
        }
        type
    } catch (failure: IllegalArgumentException) {
        throw NativeAggregateMappingException(MetricAggregation.AVG, input.fullTypeString, failure)
    } catch (failure: UnsupportedOperationException) {
        throw NativeAggregateMappingException(MetricAggregation.AVG, input.fullTypeString, failure)
    }

    fun averageRounding(result: RelDataType, support: CompilationSupport): MetricValueContract.Rounding =
        if (SqlTypeUtil.isApproximateNumeric(result)) MetricValueContract.Rounding.NONE else support.decimalAverageRounding

    private fun infer(factory: RelDataTypeFactory, function: SqlAggFunction, input: RelDataType, grouped: Boolean): RelDataType =
        requireNotNull(function.inferReturnType(Aggregate.AggCallBinding(factory, function, listOf(input), if (grouped) 1 else 0, false))) {
            "native aggregate must determine its result type"
        }

    private fun requireDetermined(type: RelDataType) {
        SemanticNativeTypes.describe(type)
        require(type.sqlTypeName != SqlTypeName.DECIMAL || type.precision > 0 && type.scale >= 0) { "native decimal aggregate requires precision and scale" }
        type.componentType?.let(::requireDetermined)
        if (type.isStruct) type.fieldList.forEach { requireDetermined(it.type) }
    }

    private fun unavailable(aggregation: ResultAggregation.Kind, input: RelDataType, failure: Exception): Mapping {
        log.debug("原生聚合映射未提供：aggregate={}, inputType={}", aggregation, input.fullTypeString, failure)
        return Unavailable
    }
}
