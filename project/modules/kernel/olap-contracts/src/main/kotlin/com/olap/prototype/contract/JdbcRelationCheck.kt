package com.olap.prototype.contract

/** 主查询前在同一连接、事务和期限执行；出现任意一行即拒绝该业务关系。 */
class JdbcRelationCheck(val code: String, val sql: String, parameters: List<JdbcParameter>) {
    val parameters: List<JdbcParameter> = java.util.List.copyOf(parameters)
    init {
        requireCode(code)
        require(sql.isNotBlank()) { "relation check requires an exact failure code and compiled SQL" }
    }
    override fun equals(other: Any?): Boolean = other is JdbcRelationCheck && code == other.code && sql == other.sql && parameters == other.parameters
    override fun hashCode(): Int = listOf(code, sql, parameters).hashCode()
    companion object {
        fun requireCode(code: String) { require(code.matches(Regex("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*"))) { "relation check requires an exact failure code" } }
    }
}
