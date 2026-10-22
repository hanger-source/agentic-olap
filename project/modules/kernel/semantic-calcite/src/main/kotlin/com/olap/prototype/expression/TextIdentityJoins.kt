package com.olap.prototype.expression

import com.olap.prototype.query.ResultExpression
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.type.SqlTypeUtil
import org.apache.calcite.tools.RelBuilder
import org.apache.calcite.util.ImmutableBitSet

/** 右侧键已证明唯一时，将精确文本相等蕴含的同型原生相等交给 Calcite 的保键核验。 */
internal object TextIdentityJoins {
    fun retainedKeyEqualities(builder: RelBuilder, condition: ResultExpression, left: RelNode, right: RelNode): List<RexNode> {
        val equalities = linkedMapOf<Int, RexNode>()
        collect(builder, condition, left.rowType.fieldCount, equalities)
        val columns = ImmutableBitSet.of(equalities.keys)
        if (right.cluster.metadataQuery.areColumnsUnique(right, columns, false) != true) return emptyList()
        return columns.asList().filter { SqlTypeUtil.inCharFamily(right.rowType.fieldList[it].type) }.map(equalities::getValue)
    }

    private fun collect(builder: RelBuilder, expression: ResultExpression, leftWidth: Int, equalities: MutableMap<Int, RexNode>) {
        val operation = expression as? ResultExpression.ResultOperation ?: return
        if (operation.operator == ResultExpression.ResultOperator.AND) operation.operands.forEach { collect(builder, it, leftWidth, equalities) }
        else if (operation.operator == ResultExpression.ResultOperator.EQ) {
            val first = operation.operands[0] as? ResultExpression.ResultSymbol ?: return
            val second = operation.operands[1] as? ResultExpression.ResultSymbol ?: return
            val a = builder.field(first.symbol)
            val b = builder.field(second.symbol)
            if ((a.index < leftWidth) == (b.index < leftWidth) || !SqlTypeUtil.equalSansNullability(builder.typeFactory, a.type, b.type)) return
            val index = (if (a.index < leftWidth) b.index else a.index) - leftWidth
            equalities.putIfAbsent(index, builder.equals(a, b))
        }
    }
}
