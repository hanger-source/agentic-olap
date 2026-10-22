package com.olap.prototype.engine.executable

import com.olap.prototype.api.SqlResultValues
import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.expression.SemanticNativeTypes
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.sql.type.SqlTypeName
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.sql.Types
import java.time.*

/** 只核验编译容量与实际值，不从样本重推类型。 */
internal object ExecutableResultTypes {
    fun requireJdbc(expected: RelDataType, observed: JdbcColumnType, support: CompilationSupport) {
        val accepted = when (expected.sqlTypeName) {
            SqlTypeName.NULL -> true
            SqlTypeName.ARRAY, SqlTypeName.MULTISET -> observed.jdbcType == Types.ARRAY
            SqlTypeName.ROW -> observed.jdbcType in setOf(Types.STRUCT, Types.JAVA_OBJECT)
            else -> support.matchesNativeResult(SemanticNativeTypes.describe(expected), observed)
        }
        check(accepted) { "JDBC result type cannot carry compiled type: ${expected.fullTypeString} -> $observed" }
    }
    fun requireValue(expected: RelDataType, value: Any?) {
        if (value == null) { check(expected.isNullable || expected.sqlTypeName == SqlTypeName.NULL) { "NULL result violates compiled non-null type: $expected" }; return }
        expected.componentType?.let { element ->
            val array = value as? SqlResultValues.ArrayValue ?: throw mismatch(expected, value)
            array.elements.forEach { requireValue(element, it) }; return
        }
        if (expected.isStruct) {
            val row = value as? SqlResultValues.RowValue ?: throw mismatch(expected, value)
            if (row.fields.size != expected.fieldCount) throw mismatch(expected, value)
            row.fields.forEachIndexed { index, field -> requireValue(expected.fieldList[index].type, field) }; return
        }
        when (expected.sqlTypeName) {
            SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER, SqlTypeName.BIGINT -> {
                if (!integral(value)) throw mismatch(expected, value)
                val integer = BigInteger(value.toString())
                val bits = when (expected.sqlTypeName) { SqlTypeName.TINYINT -> 8; SqlTypeName.SMALLINT -> 16; SqlTypeName.INTEGER -> 32; else -> 64 }
                val capacity = BigInteger.ONE.shiftLeft(bits - 1)
                check(integer >= capacity.negate() && integer <= capacity.subtract(BigInteger.ONE)) { "integer result exceeds compiled capacity: $expected" }
            }
            SqlTypeName.DECIMAL -> {
                val decimal = if (value is BigDecimal) value else if (integral(value)) BigDecimal(value.toString()) else throw mismatch(expected, value)
                try { if (decimal.setScale(expected.scale, RoundingMode.UNNECESSARY).precision() > expected.precision) throw ArithmeticException("precision exceeds contract") }
                catch (failure: ArithmeticException) { throw IllegalStateException("decimal result exceeds compiled precision or scale: $expected", failure) }
            }
            SqlTypeName.FLOAT, SqlTypeName.REAL, SqlTypeName.DOUBLE -> if ((value !is Double && value !is Float) || !(value as Number).toDouble().isFinite()) throw mismatch(expected, value)
            SqlTypeName.CHAR, SqlTypeName.VARCHAR -> if (value !is String || expected.precision >= 0 && value.codePointCount(0, value.length) > expected.precision) throw mismatch(expected, value)
            SqlTypeName.BOOLEAN -> if (value !is Boolean) throw mismatch(expected, value)
            SqlTypeName.DATE -> if (value !is LocalDate) throw mismatch(expected, value)
            SqlTypeName.TIME -> requireFraction(expected, (value as? LocalTime ?: throw mismatch(expected, value)).nano)
            SqlTypeName.TIME_TZ -> requireFraction(expected, (value as? OffsetTime ?: throw mismatch(expected, value)).nano)
            SqlTypeName.TIMESTAMP -> requireFraction(expected, (value as? LocalDateTime ?: throw mismatch(expected, value)).nano)
            SqlTypeName.TIMESTAMP_TZ -> requireFraction(expected, (value as? OffsetDateTime ?: throw mismatch(expected, value)).nano)
            else -> throw mismatch(expected, value)
        }
    }
    private fun requireFraction(expected: RelDataType, nanos: Int) {
        val precision = expected.precision
        check(precision !in 0..8 || nanos % Math.pow(10.0, (9 - precision).toDouble()).toInt() == 0) { "temporal result exceeds compiled fractional precision: $expected" }
    }
    private fun integral(value: Any): Boolean = value is BigInteger || value is Long || value is Int || value is Short || value is Byte
    private fun mismatch(expected: RelDataType, value: Any) = IllegalStateException("result value class differs from compiled type: $expected -> ${value.javaClass.name}")
}
