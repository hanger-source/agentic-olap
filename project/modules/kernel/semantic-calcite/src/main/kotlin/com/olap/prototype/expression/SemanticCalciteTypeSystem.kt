package com.olap.prototype.expression

import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.connector.NativeAggregateMappingException
import com.olap.prototype.connector.FiniteDecimalTypes
import com.olap.prototype.metric.MetricAggregation
import org.apache.calcite.rel.type.DelegatingTypeSystem
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.rel.type.RelDataTypeSystemImpl
import org.apache.calcite.sql.type.SqlTypeName

/** 中立语义容量或显式连接器原生类型；不依据后端名称选择实现。 */
class SemanticCalciteTypeSystem private constructor(private val support: CompilationSupport?, semantic: Boolean) :
    DelegatingTypeSystem(if (semantic) SemanticTypes else requireNotNull(support).typeSystem) {
    constructor() : this(null, true)
    constructor(support: CompilationSupport) : this(support, false)

    val semantic: Boolean get() = support == null
    val nativeSupport: CompilationSupport get() = requireNotNull(support) { "execution requires explicit connector compilation support" }
    val maximumDeclaredDecimalPrecision: Int get() = support?.maximumDeclaredDecimalPrecision ?: getMaxPrecision(SqlTypeName.DECIMAL)
    fun supports(capability: CompilationSupport.Capability): Boolean = support?.supports(capability) == true

    override fun deriveSumType(factory: RelDataTypeFactory, argument: RelDataType): RelDataType =
        if (semantic && FiniteDecimalTypes.isFinite(argument)) FiniteDecimalTypes.create(true) else super.deriveSumType(factory, argument)

    // Calcite 的 DelegatingTypeSystem 未委托这些接口默认方法；原生派生必须直接调用精确来源实现。
    override fun deriveDecimalPlusType(factory: RelDataTypeFactory, left: RelDataType, right: RelDataType): RelDataType? =
        finiteBinary(left, right) {
            if (semantic) super.deriveDecimalPlusType(factory, left, right)
            else nativeSupport.typeSystem.deriveDecimalPlusType(factory, left, right)
        }

    override fun deriveDecimalMultiplyType(factory: RelDataTypeFactory, left: RelDataType, right: RelDataType): RelDataType? =
        finiteBinary(left, right) {
            if (semantic) super.deriveDecimalMultiplyType(factory, left, right)
            else nativeSupport.typeSystem.deriveDecimalMultiplyType(factory, left, right)
        }

    override fun deriveDecimalDivideType(factory: RelDataTypeFactory, left: RelDataType, right: RelDataType): RelDataType? =
        finiteBinary(left, right) {
            if (semantic) super.deriveDecimalDivideType(factory, left, right)
            else nativeSupport.typeSystem.deriveDecimalDivideType(factory, left, right)
        }

    override fun deriveDecimalModType(factory: RelDataTypeFactory, left: RelDataType, right: RelDataType): RelDataType? =
        finiteBinary(left, right) {
            if (semantic) super.deriveDecimalModType(factory, left, right)
            else nativeSupport.typeSystem.deriveDecimalModType(factory, left, right)
        }

    private inline fun finiteBinary(left: RelDataType, right: RelDataType, native: () -> RelDataType?): RelDataType? {
        if (!FiniteDecimalTypes.isFinite(left) && !FiniteDecimalTypes.isFinite(right)) return native()
        if (semantic) return FiniteDecimalTypes.create(left.isNullable || right.isNullable)
        return native().also { result -> require(result != null && FiniteDecimalTypes.isFinite(result)) {
            "native finite decimal arithmetic requires an explicit value-preserving result mapping; default fixed precision/scale is not a mapping"
        } }
    }

    override fun deriveAvgAggType(factory: RelDataTypeFactory, argument: RelDataType): RelDataType = try {
        if (semantic && FiniteDecimalTypes.isFinite(argument)) FiniteDecimalTypes.create(true) else super.deriveAvgAggType(factory, argument)
    } catch (failure: NativeAggregateMappingException) {
        throw failure
    } catch (failure: IllegalArgumentException) {
        if (semantic) throw failure
        throw NativeAggregateMappingException(MetricAggregation.AVG, argument.fullTypeString, failure)
    } catch (failure: UnsupportedOperationException) {
        if (semantic) throw failure
        throw NativeAggregateMappingException(MetricAggregation.AVG, argument.fullTypeString, failure)
    }

    private object SemanticTypes : RelDataTypeSystemImpl() {
        override fun getMaxPrecision(name: SqlTypeName): Int = when (name) {
            SqlTypeName.DECIMAL -> 38
            SqlTypeName.TIMESTAMP, SqlTypeName.TIME, SqlTypeName.TIME_WITH_LOCAL_TIME_ZONE,
            SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE, SqlTypeName.TIME_TZ, SqlTypeName.TIMESTAMP_TZ -> 9
            else -> super.getMaxPrecision(name)
        }
        override fun getMaxScale(name: SqlTypeName): Int = if (name == SqlTypeName.DECIMAL) 38 else super.getMaxScale(name)
    }

    companion object {
        fun wideInteger(type: RelDataType): Boolean = type.sqlTypeName == SqlTypeName.DECIMAL && type.precision == 39 && type.scale == 0
    }
}
