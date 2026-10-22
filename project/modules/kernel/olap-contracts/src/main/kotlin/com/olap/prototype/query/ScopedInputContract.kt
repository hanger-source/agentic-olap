package com.olap.prototype.query

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
    fun requireId(id: String?): String {
        require(id != null && id.matches(Regex("[a-z][a-z0-9_]{0,47}")) && !id.contains("__")) { "additional input id must be a lowercase identifier without double underscores" }
        return id
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
