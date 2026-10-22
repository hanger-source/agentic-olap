package com.olap.prototype.expression

import java.util.Locale

/** 来源与完成关系共同的封闭标量函数语言；原生函数另由精确连接器授权。 */
enum class ScalarFunction(val minimumArguments: Int, val maximumArguments: Int?, val nullResult: String, val quantityMeaning: String) {
    ABS(1, 1, "NULL", "ABSOLUTE_PRESERVES_UNIT_REQUIRES_NUMERIC_CONTRACT"),
    COALESCE(2, null, "FIRST_NONNULL_OR_NULL", "ALTERNATIVES_REQUIRE_SAME_QUANTITY_MEANING"),
    CONCAT(2, null, "NULL_IF_ANY_ARGUMENT_NULL", "CATEGORICAL_TEXT_ONLY"),
    LOWER(1, 1, "NULL", "CATEGORICAL_TEXT_ONLY"),
    NULLIF(2, 2, "NULL_IF_EQUAL_ELSE_FIRST", "COMPARABLE_ARGUMENTS_PRESERVE_FIRST_QUANTITY"),
    SUBSTRING(2, 3, "NULL_IF_ANY_ARGUMENT_NULL", "CATEGORICAL_ARGUMENTS_ONLY"),
    UPPER(1, 1, "NULL", "CATEGORICAL_TEXT_ONLY"),
    TIMESTAMPDIFF(3, 3, "NULL_IF_ANY_ARGUMENT_NULL", "CATEGORICAL_TEMPORAL_ARGUMENTS_ONLY"),
    DATE_TRUNC(2, 2, "NULL_IF_ANY_ARGUMENT_NULL", "CATEGORICAL_TEMPORAL_ARGUMENTS_ONLY"),
    TIMESTAMPADD(3, 3, "NULL_IF_ANY_ARGUMENT_NULL", "CATEGORICAL_TEMPORAL_ARGUMENTS_ONLY");

    val temporal: Boolean get() = this == TIMESTAMPDIFF || this == TIMESTAMPADD || this == DATE_TRUNC

    fun requireArity(count: Int) {
        require(count >= minimumArguments && (maximumArguments == null || count <= maximumArguments)) {
            "$name requires $minimumArguments..${maximumArguments ?: "unbounded"} arguments"
        }
    }

    fun contract(): Map<String, Any?> = mapOf("function" to name, "minimumArguments" to minimumArguments,
        "maximumArguments" to maximumArguments, "nullResult" to nullResult, "quantityMeaning" to quantityMeaning)

    companion object {
        fun parse(value: String): ScalarFunction = valueOf(value.uppercase(Locale.ROOT))
    }
}
