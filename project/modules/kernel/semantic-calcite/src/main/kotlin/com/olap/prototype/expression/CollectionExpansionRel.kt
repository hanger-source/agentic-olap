package com.olap.prototype.expression

import org.apache.calcite.plan.Convention
import org.apache.calcite.plan.RelTraitSet
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.RelWriter
import org.apache.calcite.rel.SingleRel
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.sql.type.SqlTypeName

/** 每条输入记录展开自己的数组；语义关联不预先固定为相关派生表 SQL。 */
class CollectionExpansionRel private constructor(input: RelNode, traits: RelTraitSet, val column: Int,
    val elementAlias: String, val ordinalAlias: String, val outer: Boolean) : SingleRel(input.cluster, traits, input) {
    constructor(input: RelNode, column: Int, elementAlias: String, ordinalAlias: String, outer: Boolean) :
        this(input, input.cluster.traitSetOf(Convention.NONE), column, elementAlias, ordinalAlias, outer)

    override fun deriveRowType(): RelDataType {
        val types = cluster.typeFactory
        val collection = input.rowType.fieldList[column].type
        require(collection.sqlTypeName == SqlTypeName.ARRAY) { "collection expansion requires an ARRAY input column" }
        val element = requireNotNull(collection.componentType) { "collection expansion requires a complete element type" }
        return types.builder().apply {
            input.rowType.fieldList.forEach { add(it.name, it.type) }
            add(elementAlias, types.createTypeWithNullability(element, outer || element.isNullable))
            add(ordinalAlias, types.createTypeWithNullability(types.createSqlType(SqlTypeName.INTEGER), outer))
        }.build()
    }

    override fun copy(traitSet: RelTraitSet, inputs: List<RelNode>): RelNode {
        require(inputs.size == 1) { "collection expansion owns one input population" }
        return CollectionExpansionRel(inputs.single(), traitSet, column, elementAlias, ordinalAlias, outer)
    }

    override fun explainTerms(writer: RelWriter): RelWriter = super.explainTerms(writer)
        .item("collection", input.rowType.fieldNames[column]).item("element", elementAlias)
        .item("ordinal", ordinalAlias).item("mode", if (outer) "OUTER" else "INNER")
}
