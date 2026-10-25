package com.olap.prototype.expression

import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.connector.NativeAggregateMappingException
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

    override fun deriveAvgAggType(factory: RelDataTypeFactory, argument: RelDataType): RelDataType = try {
        super.deriveAvgAggType(factory, argument)
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
