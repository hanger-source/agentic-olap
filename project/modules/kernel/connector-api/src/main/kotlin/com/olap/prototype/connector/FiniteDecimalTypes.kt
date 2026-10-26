package com.olap.prototype.connector

import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.sql.type.AbstractSqlType
import org.apache.calcite.sql.type.SqlTypeName

/** 有限十进制的逐值表示；没有预定共同容量，也不声明来源运算的算术保证。 */
object FiniteDecimalTypes {
    private val required = FiniteDecimalType(false)
    private val optional = FiniteDecimalType(true)

    fun create(nullable: Boolean): RelDataType = if (nullable) optional else required
    fun isFinite(type: RelDataType): Boolean = type is FiniteDecimalType

    private class FiniteDecimalType(nullable: Boolean) : AbstractSqlType(SqlTypeName.DECIMAL, nullable, null) {
        init { computeDigest() }
        override fun getPrecision(): Int = RelDataType.PRECISION_NOT_SPECIFIED
        override fun getScale(): Int = RelDataType.SCALE_NOT_SPECIFIED
        override fun generateTypeString(builder: StringBuilder, withDetail: Boolean) { builder.append("FINITE_DECIMAL") }
    }
}
