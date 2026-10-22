package com.olap.connectors.h2

import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.rel.type.RelDataTypeSystemImpl
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.type.SqlTypeUtil

internal class H2NativeTypes : RelDataTypeSystemImpl() {
    override fun getMaxPrecision(name: SqlTypeName): Int = when (name) {
        SqlTypeName.DECIMAL -> 100000
        SqlTypeName.TIMESTAMP, SqlTypeName.TIME, SqlTypeName.TIME_WITH_LOCAL_TIME_ZONE, SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE, SqlTypeName.TIME_TZ, SqlTypeName.TIMESTAMP_TZ -> 9
        else -> super.getMaxPrecision(name)
    }
    override fun getMaxScale(name: SqlTypeName): Int = if (name == SqlTypeName.DECIMAL) 100000 else super.getMaxScale(name)
    override fun deriveAvgAggType(factory: RelDataTypeFactory, argument: RelDataType): RelDataType {
        require(SqlTypeUtil.isNumeric(argument)) { "AVG requires a numeric input" }
        val result = when (argument.sqlTypeName) {
            SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER, SqlTypeName.REAL -> factory.createSqlType(SqlTypeName.DOUBLE)
            SqlTypeName.BIGINT -> factory.createSqlType(SqlTypeName.DECIMAL, 29, 10)
            SqlTypeName.DECIMAL -> {
                val extra = minOf(10, getMaxPrecision(SqlTypeName.DECIMAL) - argument.precision, getMaxScale(SqlTypeName.DECIMAL) - argument.scale)
                factory.createSqlType(SqlTypeName.DECIMAL, argument.precision + extra, argument.scale + extra)
            }
            else -> throw IllegalArgumentException("H2 AVG native result cannot be represented for input: $argument")
        }
        return factory.createTypeWithNullability(result, true)
    }
    override fun deriveSumType(factory: RelDataTypeFactory, argument: RelDataType): RelDataType {
        val result = when (argument.sqlTypeName) {
            SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER -> factory.createSqlType(SqlTypeName.BIGINT)
            SqlTypeName.BIGINT -> factory.createSqlType(SqlTypeName.DECIMAL, 29, 0)
            SqlTypeName.DECIMAL -> factory.createSqlType(SqlTypeName.DECIMAL, minOf(100000, argument.precision + 10), argument.scale)
            SqlTypeName.REAL -> factory.createSqlType(SqlTypeName.DOUBLE)
            else -> throw IllegalArgumentException("H2 SUM native result cannot be represented: $argument")
        }
        return factory.createTypeWithNullability(result, argument.isNullable)
    }
    override fun deriveDecimalDivideType(factory: RelDataTypeFactory, left: RelDataType, right: RelDataType): RelDataType? {
        if (left.sqlTypeName != SqlTypeName.DECIMAL && right.sqlTypeName != SqlTypeName.DECIMAL) return super.deriveDecimalDivideType(factory, left, right)
        val leftScale = maxOf(0, left.scale); val rightScale = maxOf(0, right.scale)
        var scale = maxOf(0L, minOf(100000L, leftScale.toLong() - rightScale + 2L * right.precision))
        var precision = left.precision.toLong() + rightScale - leftScale + scale
        if (precision > 100000) { val reduction = minOf(precision - 100000, scale); precision -= reduction; scale -= reduction }
        require(precision <= 100000) { "H2 division exceeds native decimal capacity" }
        return factory.createTypeWithNullability(factory.createSqlType(SqlTypeName.DECIMAL, precision.toInt(), scale.toInt()), left.isNullable || right.isNullable)
    }
}
