package com.olap.prototype.contract

/** SQL 技术容量；来源响应或显式合同均可提供，不要求 JDBC 游标。 */
data class JdbcColumnType(val jdbcType: Int, val typeName: String, val precision: Int?, val scale: Int?, val nullable: Boolean?) {
    init {
        if (typeName.isBlank()) throw ProducerContractException("JDBC column type requires jdbcType and typeName")
        if (precision != null && precision < 0 || scale != null && scale < 0)
            throw ProducerContractException("JDBC column precision and scale must not be negative")
    }
}
