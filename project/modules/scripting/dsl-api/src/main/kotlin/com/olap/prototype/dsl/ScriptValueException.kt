package com.olap.prototype.dsl

/** 普通参数与返回值无法精确表示的公开拒绝，不携带原值。 */
class ScriptValueException internal constructor(
    val reason: Reason,
    val fieldPath: List<Any>,
    val expected: String,
    val actual: String,
) : IllegalArgumentException("普通脚本值无法按 ${reason.name} 表示：期望 $expected，实际 $actual") {
    enum class Reason { DECIMAL_REPRESENTATION }
    val code: String get() = "SCRIPT_VALUE_${reason.name}"

    fun withPathPrefix(prefix: List<Any>): ScriptValueException =
        ScriptValueException(reason, prefix + fieldPath, expected, actual)
}
