package com.olap.prototype.connector

import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.contract.NativeValueType
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.sql.type.SqlTypeName
import java.sql.Types

/** 调查与执行共用的技术容量映射；不用名称或样本猜业务类型。 */
object StandardJdbcTypes {
    fun matchesResult(expected: NativeValueType, observed: JdbcColumnType): Boolean {
        val jdbc = observed.jdbcType
        val observedPrecision = observed.precision
        val observedScale = observed.scale
        val expectedPrecision = expected.precision
        val expectedScale = expected.scale
        if (expected.name == "FINITE_DECIMAL") return expectedPrecision == null && expectedScale == null && jdbc in setOf(Types.DECIMAL, Types.NUMERIC)
        return when (val type = SqlTypeName.valueOf(expected.name)) {
            SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER, SqlTypeName.BIGINT -> integerBits(jdbc) >= integerBits(type.jdbcOrdinal)
            SqlTypeName.DECIMAL -> jdbc in setOf(Types.DECIMAL, Types.NUMERIC) && observedPrecision != null && observedScale != null && expectedPrecision != null && expectedScale != null &&
                observedScale >= expectedScale && observedPrecision - observedScale >= expectedPrecision - expectedScale
            SqlTypeName.FLOAT, SqlTypeName.REAL, SqlTypeName.DOUBLE -> jdbc in setOf(Types.FLOAT, Types.REAL, Types.DOUBLE) && (expected.name != "DOUBLE" || jdbc == Types.DOUBLE)
            SqlTypeName.CHAR, SqlTypeName.VARCHAR -> jdbc in setOf(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR)
            SqlTypeName.BOOLEAN -> jdbc == Types.BOOLEAN || jdbc == Types.BIT
            SqlTypeName.DATE -> jdbc == Types.DATE
            SqlTypeName.TIME -> jdbc == Types.TIME
            SqlTypeName.TIME_TZ -> jdbc == Types.TIME_WITH_TIMEZONE
            SqlTypeName.TIMESTAMP -> jdbc == Types.TIMESTAMP
            SqlTypeName.TIMESTAMP_TZ -> jdbc == Types.TIMESTAMP_WITH_TIMEZONE
            else -> false
        }
    }

    private fun integerBits(jdbc: Int): Int = when (jdbc) {
        Types.TINYINT -> 8
        Types.SMALLINT -> 16
        Types.INTEGER -> 32
        Types.BIGINT -> 64
        else -> 0
    }

    fun resolve(factory: RelDataTypeFactory, column: JdbcColumnType): RelDataType {
        val nullable = requireNotNull(column.nullable) { "source nullability must be investigated before Calcite binding" }
        val type = when (column.jdbcType) {
            Types.TIMESTAMP_WITH_TIMEZONE -> SqlTypeName.TIMESTAMP_TZ
            Types.TIME_WITH_TIMEZONE -> SqlTypeName.TIME_TZ
            else -> SqlTypeName.getNameForJdbcType(column.jdbcType)
        }
        check(type != null && type !in setOf(SqlTypeName.ANY, SqlTypeName.OTHER, SqlTypeName.ARRAY, SqlTypeName.STRUCTURED)) {
            "JDBC type requires an explicit Calcite type extension: ${column.jdbcType} / ${column.typeName}"
        }
        val columnPrecision = column.precision
        val resolved = when {
            type == SqlTypeName.DECIMAL -> {
                val precision = column.precision
                val scale = column.scale
                check(precision != null && precision > 0 && scale != null && scale <= precision &&
                    precision <= factory.typeSystem.getMaxPrecision(type) && scale <= factory.typeSystem.getMaxScale(type)) {
                    "frozen DECIMAL precision/scale cannot be represented: $column"
                }
                factory.createSqlType(type, precision, scale)
            }
            type in setOf(SqlTypeName.TIMESTAMP, SqlTypeName.TIME, SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE,
                SqlTypeName.TIME_WITH_LOCAL_TIME_ZONE, SqlTypeName.TIMESTAMP_TZ, SqlTypeName.TIME_TZ) -> {
                val fractionalPrecision = column.scale
                check(fractionalPrecision != null && fractionalPrecision <= factory.typeSystem.getMaxPrecision(type)) { "frozen temporal fractional precision cannot be represented: $column" }
                factory.createSqlType(type, fractionalPrecision)
            }
            type.allowsPrec() && columnPrecision != null && columnPrecision > 0 -> factory.createSqlType(type, columnPrecision)
            else -> factory.createSqlType(type)
        }
        return factory.createTypeWithNullability(resolved, nullable)
    }
}
