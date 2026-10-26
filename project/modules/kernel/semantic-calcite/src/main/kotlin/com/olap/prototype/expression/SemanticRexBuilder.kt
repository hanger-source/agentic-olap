package com.olap.prototype.expression

import com.olap.prototype.connector.FiniteDecimalTypes
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.rex.RexBuilder
import org.apache.calcite.rex.RexLiteral
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.type.SqlTypeName
import java.math.BigDecimal

/** 有限变尺度值不进入 Calcite 默认定点字面量舍入及 CAST 常量折叠。 */
internal class SemanticRexBuilder(types: RelDataTypeFactory) : RexBuilder(types) {
    override fun makeExactLiteral(value: BigDecimal?, type: RelDataType): RexLiteral =
        if (FiniteDecimalTypes.isFinite(type)) super.makeLiteral(value, type, SqlTypeName.DECIMAL) else super.makeExactLiteral(value, type)

    override fun makeLiteral(value: Any?, type: RelDataType, allowCast: Boolean, trim: Boolean): RexNode {
        if (!FiniteDecimalTypes.isFinite(type)) return super.makeLiteral(value, type, allowCast, trim)
        if (value == null) return makeNullLiteral(type)
        require(value is BigDecimal) { "finite decimal literal requires an exact BigDecimal" }
        return super.makeLiteral(value, type, SqlTypeName.DECIMAL)
    }

    override fun makeCast(pos: SqlParserPos, type: RelDataType, expression: RexNode,
        matchNullability: Boolean, safe: Boolean, format: RexLiteral?): RexNode {
        if (!FiniteDecimalTypes.isFinite(type) && !FiniteDecimalTypes.isFinite(expression.type))
            return super.makeCast(pos, type, expression, matchNullability, safe, format)
        val target = if (matchNullability) typeFactory.createTypeWithNullability(type, expression.type.isNullable) else type
        return if (target == expression.type) expression else makeAbstractCast(pos, target, expression, safe, format)
    }
}
