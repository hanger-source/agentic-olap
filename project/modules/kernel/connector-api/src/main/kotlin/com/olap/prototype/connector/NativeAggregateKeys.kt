package com.olap.prototype.connector

import org.apache.calcite.rel.core.Aggregate
import org.apache.calcite.rel.core.Project
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.type.SqlTypeUtil
import org.apache.calcite.tools.RelBuilder

/** 核验实际聚合的精确分组身份，不从原生文本 collation 或字段名称推断等价。 */
object NativeAggregateKeys {
    fun preservesExactIdentity(owner: Aggregate, support: CompilationSupport): Boolean {
        val project = owner.input as? Project
        val builder by lazy { RelBuilder.proto().create(owner.cluster, null).apply { push(requireNotNull(project).input) } }
        return owner.groupSets.all { group ->
            group.asList().all { index ->
                val type = owner.input.rowType.fieldList[index].type
                when {
                    SqlTypeUtil.isExactNumeric(type) || type.sqlTypeName in setOf(
                        SqlTypeName.NULL, SqlTypeName.BOOLEAN, SqlTypeName.BINARY, SqlTypeName.VARBINARY) -> true
                    SqlTypeUtil.isCharacter(type) && project != null -> {
                        val value = project.projects[index]
                        val encoded = support.textIdentityKey(builder, value)
                        encoded != null && encoded.type.sqlTypeName in setOf(SqlTypeName.BINARY, SqlTypeName.VARBINARY) &&
                            encoded.type.isNullable == value.type.isNullable && group.asList().any { candidate ->
                                candidate != index && project.projects[candidate] == encoded
                            }
                    }
                    else -> false
                }
            }
        }
    }
}
