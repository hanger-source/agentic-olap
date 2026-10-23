package com.olap.prototype.expression

import com.olap.prototype.contract.JdbcRelationCheck
import org.apache.calcite.rel.RelNode

/** 已编译的失败见证；不保存第二套查询语言。 */
data class RelationalCheck(val code: String, val violations: RelNode, val origin: JdbcRelationCheck.Origin? = null) {
    init { JdbcRelationCheck.requireCode(code) }
}
