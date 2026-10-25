package com.olap.prototype.query

import com.olap.prototype.contract.InputContractException

/** 具名输入共享发布坐标，完成各自阶段后按精确语义键组合。 */
object ScopedInputContract {
    const val MAX_DEPTH = 16
    const val MAX_INPUTS = 64
    fun requireMode(mode: String): String = mode.also { require(it in setOf("KEY_ALIGNMENT", "RELATION", "ROOT")) { "input mode must be KEY_ALIGNMENT, RELATION or ROOT" } }
    data class DimensionRef(val id: String, val version: String, val levelCode: String) {
        init { require(id.isNotBlank() && version.matches(Regex("[1-9][0-9]*")) && levelCode.isNotBlank()) { "input grain requires exact dimension identity, version and level" } }
    }
    fun requireGrainSubset(primary: List<DimensionRef>, input: List<DimensionRef>) {
        require(primary.distinct().size == primary.size && input.distinct().size == input.size) { "input grain contains duplicate dimension refs" }
        require(primary.containsAll(input)) { "additional input grain must be an exact subset of primary dimensions and levels" }
    }
    fun requirePopulationGrain(population: RowPopulationContract.Selection, id: String, primary: List<DimensionRef>, input: List<DimensionRef>) {
        requireGrainSubset(primary, input)
        require(population.mode != "SCOPED_UNION" || id !in requireNotNull(population.inputIds) || primary.isNotEmpty() && primary.size == input.size) {
            "SCOPED_UNION contributing inputs require the exact full non-scalar primary grain"
        }
    }
    private val identifier = Regex("[a-z][a-z0-9_]{0,47}")
    fun identifierContract(vararg fields: String): Map<String, Any> = mapOf(
        "identifierFields" to fields.toList(),
        "identifier" to mapOf("pattern" to identifier.pattern, "minimumLength" to 1, "maximumLength" to 48,
            "case" to "LOWERCASE_ASCII", "forbiddenSubstrings" to listOf("__"), "violationCode" to InputContractException.Rule.IDENTIFIER.code))

    fun requireId(id: String?, fieldPath: List<Any> = listOf("identifier")): String {
        if (id != null && id.matches(identifier) && !id.contains("__")) return id
        val actual = when {
            id == null -> "NULL"
            id.length !in 1..48 -> "IDENTIFIER_LENGTH_OUT_OF_RANGE"
            id.contains("__") -> "RESERVED_DOUBLE_UNDERSCORE"
            else -> "INVALID_IDENTIFIER_SHAPE"
        }
        throw InputContractException.violation(InputContractException.Rule.IDENTIFIER, fieldPath,
            "${fieldPath.joinToString(".")} must match ${identifier.pattern} and must not contain double underscores",
            "LOWERCASE_ASCII_IDENTIFIER[1,48]_WITHOUT_DOUBLE_UNDERSCORE", actual)
    }
    fun requireSymbol(value: String?, field: String): String = value?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("$field is required")
    fun requireInputSymbol(value: Any?, field: String): String = requireSymbol(value as? String ?: throw IllegalArgumentException("$field is required"), field)
    fun requireInputSymbols(value: Any?, field: String): List<String> {
        require(value is List<*>) { "$field must be an array" }
        val result = linkedSetOf<String>()
        value.forEach { item -> val symbol = requireInputSymbol(item, field); require(result.add(symbol)) { "$field contains duplicate symbol: $symbol" } }
        return java.util.List.copyOf(result)
    }
    fun metricSymbol(inputId: String, publishedAlias: String): String = "${requireId(inputId)}__$publishedAlias"
    fun presenceSymbol(inputId: String): String = "${requireId(inputId)}__present"
}
