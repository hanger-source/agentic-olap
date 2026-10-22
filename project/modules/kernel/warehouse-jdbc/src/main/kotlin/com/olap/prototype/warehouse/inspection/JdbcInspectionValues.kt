package com.olap.prototype.warehouse.inspection

import java.math.BigDecimal
import java.math.BigInteger
import java.sql.Blob
import java.sql.Clob
import java.sql.SQLXML
import java.time.temporal.TemporalAccessor
import java.util.Base64

/** Preview values are bounded with truncation facts; execution materialization stays lossless. */
internal object JdbcInspectionValues {
    private const val MAX_TEXT_LENGTH = 16384
    private const val MAX_BINARY_LENGTH = 4096
    fun scalar(value: Any?): WarehouseScalar = when (value) {
        null -> WarehouseScalar.nullValue()
        is Boolean -> exact(WarehouseScalar.Kind.BOOLEAN, value.toString())
        is Byte, is Short, is Int, is Long, is BigInteger -> exact(WarehouseScalar.Kind.INTEGER, value.toString())
        is BigDecimal -> exact(WarehouseScalar.Kind.DECIMAL, value.toPlainString())
        is Float, is Double -> exact(WarehouseScalar.Kind.DECIMAL, value.toString())
        is java.sql.Date, is java.time.LocalDate -> exact(WarehouseScalar.Kind.DATE, value.toString())
        is java.sql.Time, is java.time.LocalTime -> exact(WarehouseScalar.Kind.TIME, value.toString())
        is java.sql.Timestamp, is TemporalAccessor, is java.util.Date -> exact(WarehouseScalar.Kind.TIMESTAMP, value.toString())
        is ByteArray -> WarehouseScalar(WarehouseScalar.Kind.BINARY, Base64.getEncoder().encodeToString(value.copyOf(minOf(value.size, MAX_BINARY_LENGTH))), value.size > MAX_BINARY_LENGTH)
        is Blob -> try { val length = minOf(value.length(), MAX_BINARY_LENGTH.toLong()).toInt(); WarehouseScalar(WarehouseScalar.Kind.BINARY, Base64.getEncoder().encodeToString(value.getBytes(1, length)), value.length() > length) } finally { value.free() }
        is Clob -> try { val length = minOf(value.length(), MAX_TEXT_LENGTH.toLong()).toInt(); WarehouseScalar(WarehouseScalar.Kind.TEXT, value.getSubString(1, length), value.length() > length) } finally { value.free() }
        is SQLXML -> try {
            value.characterStream.use { reader ->
                val buffer = CharArray(MAX_TEXT_LENGTH + 1)
                var size = 0
                while (size < buffer.size) { val read = reader.read(buffer, size, buffer.size - size); if (read == -1) break; size += read }
                WarehouseScalar(WarehouseScalar.Kind.TEXT, String(buffer, 0, minOf(size, MAX_TEXT_LENGTH)), size > MAX_TEXT_LENGTH)
            }
        } finally { value.free() }
        is CharSequence, is Char -> bounded(WarehouseScalar.Kind.TEXT, value.toString())
        else -> bounded(WarehouseScalar.Kind.OTHER, value.toString())
    }
    private fun exact(kind: WarehouseScalar.Kind, value: String) = WarehouseScalar(kind, value, false)
    private fun bounded(kind: WarehouseScalar.Kind, value: String) = WarehouseScalar(kind, value.take(MAX_TEXT_LENGTH), value.length > MAX_TEXT_LENGTH)
}
