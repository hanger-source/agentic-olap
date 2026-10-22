package com.olap.prototype.contract

import java.math.BigDecimal
import java.math.BigInteger
import java.sql.PreparedStatement
import java.sql.Types
import java.time.*

/** 编译类型与无损值同行，不依赖驱动猜测类型。 */
data class JdbcParameter(val jdbcType: Int, val typeName: String, val precision: Int, val scale: Int, val encoding: Encoding, val value: String) {
    enum class Encoding { INTEGER, DECIMAL, FLOAT, DOUBLE, BOOLEAN, TEXT, DATE, LOCAL_TIME, OFFSET_TIME, LOCAL_TIMESTAMP, OFFSET_TIMESTAMP }
    init {
        require(typeName.isNotBlank()) { "SQL parameter type name is required" }
        val decoded = decode(encoding, value)
        val matches = when (encoding) {
            Encoding.INTEGER -> jdbcType in setOf(Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT) || jdbcType == Types.OTHER && typeName == "HUGEINT" && precision == 39 && scale == 0
            Encoding.DECIMAL -> jdbcType in setOf(Types.DECIMAL, Types.NUMERIC)
            Encoding.FLOAT -> jdbcType == Types.REAL
            Encoding.DOUBLE -> jdbcType in setOf(Types.FLOAT, Types.DOUBLE)
            Encoding.BOOLEAN -> jdbcType in setOf(Types.BOOLEAN, Types.BIT)
            Encoding.TEXT -> jdbcType in setOf(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR)
            Encoding.DATE -> jdbcType == Types.DATE
            Encoding.LOCAL_TIME -> jdbcType == Types.TIME
            Encoding.OFFSET_TIME -> jdbcType == Types.TIME_WITH_TIMEZONE
            Encoding.LOCAL_TIMESTAMP -> jdbcType == Types.TIMESTAMP
            Encoding.OFFSET_TIMESTAMP -> jdbcType == Types.TIMESTAMP_WITH_TIMEZONE
        }
        require(matches) { "SQL parameter encoding differs from compiled JDBC type" }
        when (encoding) {
            Encoding.INTEGER -> {
                val bits = when (jdbcType) { Types.TINYINT -> 8; Types.SMALLINT -> 16; Types.INTEGER -> 32; Types.OTHER -> 128; else -> 64 }
                require((decoded as BigInteger).bitLength() < bits) { "integer parameter exceeds compiled capacity" }
            }
            Encoding.DECIMAL -> {
                val decimal = decoded as BigDecimal
                require(precision > 0 && scale in 0..precision && decimal.scale() <= scale && maxOf(0, decimal.precision() - decimal.scale()) <= precision - scale) {
                    "decimal parameter exceeds compiled precision or scale"
                }
            }
            Encoding.TEXT -> require(precision < 0 || value.codePointCount(0, value.length) <= precision) { "text parameter exceeds compiled length" }
            else -> Unit
        }
        val nanos = when (decoded) { is LocalTime -> decoded.nano; is OffsetTime -> decoded.nano; is LocalDateTime -> decoded.nano; is OffsetDateTime -> decoded.nano; else -> 0 }
        require(nanos == 0 || precision in 0..9 && nanos % Math.pow(10.0, (9 - precision).toDouble()).toInt() == 0) { "temporal parameter exceeds compiled fractional precision" }
    }

    fun jdbcValue(): Any = decode(encoding, value)
    fun bind(statement: PreparedStatement, position: Int) {
        val decoded = jdbcValue()
        when (encoding) {
            Encoding.INTEGER -> if (typeName == "HUGEINT") statement.setObject(position, decoded) else statement.setObject(position, (decoded as BigInteger).longValueExact(), jdbcType)
            Encoding.DECIMAL -> statement.setBigDecimal(position, decoded as BigDecimal)
            Encoding.FLOAT -> statement.setFloat(position, decoded as Float)
            Encoding.DOUBLE -> statement.setDouble(position, decoded as Double)
            Encoding.BOOLEAN -> statement.setBoolean(position, decoded as Boolean)
            Encoding.TEXT -> statement.setString(position, decoded as String)
            Encoding.DATE -> statement.setDate(position, java.sql.Date.valueOf(decoded as LocalDate))
            Encoding.LOCAL_TIMESTAMP -> if (typeName in setOf("TIMESTAMP_NS", "TIMESTAMP_MS", "TIMESTAMP_S")) statement.setString(position, value) else statement.setObject(position, decoded, jdbcType)
            else -> statement.setObject(position, decoded, jdbcType)
        }
    }

    companion object {
        fun of(jdbcType: Int, typeName: String, precision: Int, scale: Int, value: Any): JdbcParameter {
            val encoding = when (value) {
                is BigDecimal -> Encoding.DECIMAL
                is BigInteger, is Byte, is Short, is Int, is Long -> Encoding.INTEGER
                is Float -> Encoding.FLOAT
                is Double -> Encoding.DOUBLE
                is Boolean -> Encoding.BOOLEAN
                is String -> Encoding.TEXT
                is LocalDate -> Encoding.DATE
                is LocalTime -> Encoding.LOCAL_TIME
                is OffsetTime -> Encoding.OFFSET_TIME
                is LocalDateTime -> Encoding.LOCAL_TIMESTAMP
                is OffsetDateTime -> Encoding.OFFSET_TIMESTAMP
                else -> throw IllegalArgumentException("unsupported typed SQL parameter value: ${value.javaClass.name}")
            }
            return JdbcParameter(jdbcType, typeName, precision, scale, encoding, if (value is BigDecimal) value.toPlainString() else value.toString())
        }

        private fun decode(encoding: Encoding, value: String): Any = when (encoding) {
            Encoding.INTEGER -> BigInteger(value)
            Encoding.DECIMAL -> BigDecimal(value)
            Encoding.FLOAT -> value.toFloat().also { require(it.isFinite()) { "non-finite SQL parameter" } }
            Encoding.DOUBLE -> value.toDouble().also { require(it.isFinite()) { "non-finite SQL parameter" } }
            Encoding.BOOLEAN -> { require(value == "true" || value == "false") { "invalid boolean SQL parameter" }; value.toBoolean() }
            Encoding.TEXT -> value
            Encoding.DATE -> LocalDate.parse(value)
            Encoding.LOCAL_TIME -> LocalTime.parse(value)
            Encoding.OFFSET_TIME -> OffsetTime.parse(value)
            Encoding.LOCAL_TIMESTAMP -> LocalDateTime.parse(value)
            Encoding.OFFSET_TIMESTAMP -> OffsetDateTime.parse(value)
        }
    }
}
