package com.olap.connectors.duckdb

import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.rel.type.RelDataTypeSystemImpl
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.type.SqlTypeUtil

internal class DuckdbNativeTypes : RelDataTypeSystemImpl() {
    override fun getMaxPrecision(name: SqlTypeName): Int = when (name) {
        SqlTypeName.DECIMAL -> 39
        SqlTypeName.TIMESTAMP -> 9
        SqlTypeName.TIME, SqlTypeName.TIME_WITH_LOCAL_TIME_ZONE, SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE, SqlTypeName.TIME_TZ, SqlTypeName.TIMESTAMP_TZ -> 6
        else -> super.getMaxPrecision(name)
    }
    override fun getMaxScale(name: SqlTypeName): Int = if (name == SqlTypeName.DECIMAL) 38 else super.getMaxScale(name)
    override fun deriveAvgAggType(factory: RelDataTypeFactory, argument: RelDataType): RelDataType {
        require(SqlTypeUtil.isNumeric(argument)) { "AVG requires a numeric input" }
        return factory.createTypeWithNullability(factory.createSqlType(SqlTypeName.DOUBLE), true)
    }
    override fun deriveSumType(factory: RelDataTypeFactory, argument: RelDataType): RelDataType {
        val result = when (argument.sqlTypeName) {
            SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER, SqlTypeName.BIGINT -> factory.createSqlType(SqlTypeName.DECIMAL, 39, 0)
            SqlTypeName.DECIMAL -> factory.createSqlType(SqlTypeName.DECIMAL, if (wideInteger(argument)) 39 else 38, argument.scale)
            SqlTypeName.REAL, SqlTypeName.FLOAT, SqlTypeName.DOUBLE -> factory.createSqlType(SqlTypeName.DOUBLE)
            else -> throw IllegalArgumentException("DuckDB SUM requires a native numeric input")
        }
        return factory.createTypeWithNullability(result, argument.isNullable)
    }
    override fun deriveDecimalPlusType(factory: RelDataTypeFactory, left: RelDataType, right: RelDataType): RelDataType? = decimal(factory, left, right, super.deriveDecimalPlusType(factory, left, right))
    override fun deriveDecimalMultiplyType(factory: RelDataTypeFactory, left: RelDataType, right: RelDataType): RelDataType? {
        require(left.sqlTypeName != SqlTypeName.DECIMAL || right.sqlTypeName != SqlTypeName.DECIMAL || left.scale + right.scale <= 38) { "DuckDB decimal multiplication exceeds native scale; declare rounding before multiplication" }
        return decimal(factory, left, right, super.deriveDecimalMultiplyType(factory, left, right))
    }
    private fun decimal(factory: RelDataTypeFactory, left: RelDataType, right: RelDataType, derived: RelDataType?): RelDataType? {
        if (wideInteger(left) || wideInteger(right)) {
            val other = if (wideInteger(left)) right else left
            require(other.sqlTypeName != SqlTypeName.DECIMAL || wideInteger(other)) { "DuckDB HUGEINT and DECIMAL arithmetic requires an explicit native type cast" }
            return factory.createTypeWithNullability(factory.createSqlType(SqlTypeName.DECIMAL, 39, 0), left.isNullable || right.isNullable)
        }
        if (derived == null) return null
        require(derived.scale <= 38) { "DuckDB decimal arithmetic exceeds its native scale without an explicit rounding stage" }
        return factory.createTypeWithNullability(factory.createSqlType(SqlTypeName.DECIMAL, minOf(38, derived.precision), derived.scale), derived.isNullable)
    }
    companion object { fun wideInteger(type: RelDataType): Boolean = type.sqlTypeName == SqlTypeName.DECIMAL && type.precision == 39 && type.scale == 0 }
}
