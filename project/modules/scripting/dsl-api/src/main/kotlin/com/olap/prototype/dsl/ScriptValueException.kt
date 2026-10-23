package com.olap.prototype.dsl

/** 普通参数与返回值的公开拒绝；只携带限制和计数，不携带原值。 */
class ScriptValueException internal constructor(
    val reason: Reason,
    val fieldPath: List<Any>,
    val expected: String,
    val actual: String,
) : IllegalArgumentException("普通脚本值违反 ${reason.name} 要求：期望 $expected，实际 $actual") {
    enum class Reason { NODE_COUNT, DEPTH, TEXT_LENGTH, OBJECT_KEY_BLANK, OBJECT_KEY_LENGTH, DECIMAL_SYNTAX, DECIMAL_PRECISION, DECIMAL_SCALE }
    val code: String get() = "SCRIPT_VALUE_${reason.name}"

    fun withPathPrefix(prefix: List<Any>): ScriptValueException =
        ScriptValueException(reason, prefix + fieldPath, expected, actual)
}
