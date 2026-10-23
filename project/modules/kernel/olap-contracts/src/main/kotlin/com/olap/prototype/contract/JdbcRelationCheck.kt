package com.olap.prototype.contract

/** 与主查询共享插件兑现的一致读取基础；出现任意一行即拒绝该业务关系。 */
class JdbcRelationCheck(val code: String, val sql: String, parameters: List<JdbcParameter>, val origin: Origin? = null) {
    data class Origin(val scope: com.olap.prototype.query.QueryStageAnalysis.ScopeId?, val keys: List<String>, val reason: String)
    val parameters: List<JdbcParameter> = java.util.List.copyOf(parameters)
    init {
        requireCode(code)
        require(sql.isNotBlank()) { "relation check requires an exact failure code and compiled SQL" }
    }
    override fun equals(other: Any?): Boolean = other is JdbcRelationCheck && code == other.code && sql == other.sql && parameters == other.parameters && origin == other.origin
    override fun hashCode(): Int = listOf(code, sql, parameters, origin).hashCode()
    companion object {
        fun requireCode(code: String) { require(code.matches(Regex("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*"))) { "relation check requires an exact failure code" } }
    }
}
