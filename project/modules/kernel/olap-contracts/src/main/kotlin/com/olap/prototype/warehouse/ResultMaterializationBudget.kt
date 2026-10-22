package com.olap.prototype.warehouse

import com.olap.prototype.api.ResultFrame
import com.olap.prototype.api.SqlResultValues
import com.olap.prototype.runtime.RuntimeResourceLimitException

/** 一次实际结果共用计数器，数组与关系行不另获完整预算。 */
class ResultMaterializationBudget(val limits: ResultMaterializationLimits) {
    private var rows = 0L
    private var values = 0L
    private var bytes = 0L
    fun row() {
        if (rows == limits.rows) throw RuntimeResourceLimitException("RESULT_ROWS_EXCEEDED")
        rows++
    }
    fun value(depth: Int) {
        if (depth > limits.depth) throw RuntimeResourceLimitException("RESULT_DEPTH_EXCEEDED")
        if (values == limits.values) throw RuntimeResourceLimitException("RESULT_VALUES_EXCEEDED")
        values++
    }
    fun requireMinimumValues(count: Long) {
        if (count > limits.values - values) throw RuntimeResourceLimitException("RESULT_VALUES_EXCEEDED")
    }
    fun scalar(value: Any?) {
        val length: Long = when (value) {
            null -> 0
            is ByteArray -> value.size.toLong()
            is CharSequence -> utf8Length(value)
            else -> value.toString().toByteArray(Charsets.UTF_8).size.toLong()
        }
        if (length > limits.bytes - bytes) throw RuntimeResourceLimitException("RESULT_BYTES_EXCEEDED")
        bytes += length
    }

    /** 逐字符计数，不为预算核验复制同样大的 UTF-8 数组。 */
    private fun utf8Length(text: CharSequence): Long {
        var length = 0L
        var index = 0
        while (index < text.length) {
            val character = text[index]
            length += when {
                character.code < 128 -> 1
                character.code < 2048 -> 2
                character.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> { index++; 4 }
                else -> 3
            }
            if (length > limits.bytes - bytes) throw RuntimeResourceLimitException("RESULT_BYTES_EXCEEDED")
            index++
        }
        return length
    }

    fun frame(frame: ResultFrame) {
        frame.rows.forEach { row -> this.row(); row.cells.forEach { resultValue(it.value, 1) } }
    }
    private fun resultValue(value: Any?, depth: Int) {
        this.value(depth)
        when (value) {
            is SqlResultValues.ArrayValue -> value.elements.forEach { resultValue(it, depth + 1) }
            is SqlResultValues.RowValue -> value.fields.forEach { resultValue(it, depth + 1) }
            else -> scalar(value)
        }
    }
}
