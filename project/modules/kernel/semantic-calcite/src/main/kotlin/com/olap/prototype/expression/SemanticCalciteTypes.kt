package com.olap.prototype.expression

import org.apache.calcite.jdbc.JavaTypeFactoryImpl
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.sql.SqlCollation
import org.apache.calcite.sql.parser.SqlParserUtil
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** 共同表达式使用 Unicode 文本；精确来源的数值规则与显式字符类型仍由其原生合同决定。 */
class SemanticCalciteTypes(typeSystem: SemanticCalciteTypeSystem) : JavaTypeFactoryImpl(typeSystem) {
    override fun getDefaultCharset(): Charset = StandardCharsets.UTF_8

    override fun createTypeWithCharsetAndCollation(type: RelDataType, charset: Charset, collation: SqlCollation): RelDataType {
        val actual = if (charset == defaultCharset) when {
            collation === SqlCollation.IMPLICIT -> implicit
            collation === SqlCollation.COERCIBLE -> coercible
            else -> collation
        } else collation
        return super.createTypeWithCharsetAndCollation(type, charset, actual)
    }

    override fun createJavaType(clazz: Class<*>): RelDataType {
        val type = super.createJavaType(clazz)
        return if (clazz == String::class.java) createTypeWithCharsetAndCollation(type, defaultCharset, implicit) else type
    }

    companion object {
        private val implicit = unicodeCollation(SqlCollation.IMPLICIT)
        private val coercible = unicodeCollation(SqlCollation.COERCIBLE)

        private fun unicodeCollation(template: SqlCollation): SqlCollation {
            val parsed = SqlParserUtil.parseCollation(template.collationName)
            return SqlCollation(template.coercibility, parsed.locale, StandardCharsets.UTF_8, parsed.strength)
        }

        /** 合成参数保留完整 Java String 容量；不声明来源的字符计数或列长度语义。 */
        fun textParameterCapacity(value: String): Int = maxOf(1, value.length)
    }
}
