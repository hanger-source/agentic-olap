package com.olap.prototype.engine.executable

import com.olap.prototype.engine.executable.ExecutableBusinessResult.ValueKind
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.type.SqlTypeUtil
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime

/** 完成值的等值身份；数值按规范有理数，容器保留有序层次和实际 NULL。 */
internal object CoreValueKeys {
    sealed interface Key
    private data object NullKey : Key
    private data class NumberKey(val numerator: BigInteger, val denominator: BigInteger) : Key
    private data class BooleanKey(val value: Boolean) : Key
    private data class TextKey(val value: String) : Key
    private data class DateKey(val value: LocalDate) : Key
    private data class TimeKey(val value: LocalTime) : Key
    private data class TimestampKey(val value: LocalDateTime) : Key
    private data class InstantKey(val value: Instant) : Key
    private data class ArrayKey(val values: List<Key>) : Key
    private data class RowKey(val values: List<Key>) : Key

    fun requireSupported(type: CoreScalarType, path: List<Any>) {
        when (type.kind) {
            CoreScalarType.Kind.NUMBER -> type.storage?.let { storage ->
                if (!SqlTypeUtil.isExactNumeric(storage))
                    CoreFinalizationExpressions.unsupported(path, "core numeric identity requires an exact integer or decimal representation")
            }
            CoreScalarType.Kind.BOOLEAN, CoreScalarType.Kind.NULL -> Unit
            CoreScalarType.Kind.PRESERVED -> requireSupported(requireNotNull(type.storage), path)
        }
    }

    private fun requireSupported(type: RelDataType, path: List<Any>) {
        when {
            SqlTypeUtil.isExactNumeric(type) -> Unit
            type.sqlTypeName == SqlTypeName.ROW -> type.fieldList.forEachIndexed { index, field ->
                requireSupported(field.type, path + listOf("fields", index))
            }
            type.sqlTypeName == SqlTypeName.ARRAY -> requireSupported(requireNotNull(type.componentType), path + "element")
            type.sqlTypeName in setOf(SqlTypeName.NULL, SqlTypeName.BOOLEAN, SqlTypeName.CHAR, SqlTypeName.VARCHAR,
                SqlTypeName.DATE, SqlTypeName.TIME, SqlTypeName.TIMESTAMP, SqlTypeName.TIMESTAMP_TZ) -> Unit
            else -> CoreFinalizationExpressions.unsupported(path,
                "core value identity is not defined for this actual type: ${type.sqlTypeName}")
        }
    }

    fun key(value: CoreValue, type: CoreScalarType): Key = when (value) {
        CoreValue.Missing -> NullKey
        is CoreValue.Numeric -> number(value.value)
        is CoreValue.Logical -> BooleanKey(value.value)
        is CoreValue.Preserved -> preserved(value.value, requireNotNull(type.storage))
    }

    private fun number(value: CoreExactNumber): Key = NumberKey(value.numerator, value.denominator)

    private fun preserved(value: ExecutableBusinessResult.Value, type: RelDataType): Key {
        if (value is ExecutableBusinessResult.NullValue) return NullKey
        return when (type.sqlTypeName) {
            SqlTypeName.ROW -> {
                val row = value as? ExecutableBusinessResult.RowValue ?: error("core ROW identity requires an actual row")
                check(row.value.size == type.fieldCount) { "core ROW identity differs from its compiled field count" }
                RowKey(row.value.mapIndexed { index, field -> preserved(field, type.fieldList[index].type) })
            }
            SqlTypeName.ARRAY -> {
                val array = value as? ExecutableBusinessResult.ArrayValue ?: error("core ARRAY identity requires an actual array")
                ArrayKey(array.value.map { preserved(it, requireNotNull(type.componentType)) })
            }
            else -> {
                val scalar = value as? ExecutableBusinessResult.ScalarValue ?: error("core scalar identity requires an actual scalar")
                when (type.sqlTypeName) {
                    SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER, SqlTypeName.BIGINT, SqlTypeName.DECIMAL -> {
                        check(scalar.type in setOf(ValueKind.INTEGER, ValueKind.DECIMAL)) { "core exact numeric identity differs from its actual scalar" }
                        number(CoreExactNumber.decimal(BigDecimal(scalar.value)))
                    }
                    SqlTypeName.BOOLEAN -> {
                        check(scalar.type == ValueKind.BOOLEAN)
                        BooleanKey(scalar.value.toBooleanStrict())
                    }
                    SqlTypeName.CHAR, SqlTypeName.VARCHAR -> {
                        check(scalar.type == ValueKind.TEXT)
                        TextKey(scalar.value)
                    }
                    SqlTypeName.DATE -> {
                        check(scalar.type == ValueKind.DATE)
                        DateKey(LocalDate.parse(scalar.value))
                    }
                    SqlTypeName.TIME -> {
                        check(scalar.type == ValueKind.LOCAL_TIME)
                        TimeKey(LocalTime.parse(scalar.value))
                    }
                    SqlTypeName.TIMESTAMP -> {
                        check(scalar.type == ValueKind.LOCAL_TIMESTAMP)
                        TimestampKey(LocalDateTime.parse(scalar.value))
                    }
                    SqlTypeName.TIMESTAMP_TZ -> InstantKey(when (scalar.type) {
                        ValueKind.OFFSET_TIMESTAMP -> OffsetDateTime.parse(scalar.value).toInstant()
                        ValueKind.INSTANT -> Instant.parse(scalar.value)
                        else -> error("core offset timestamp identity requires an actual offset or instant")
                    })
                    else -> error("core value identity type was not admitted")
                }
            }
        }
    }
}
