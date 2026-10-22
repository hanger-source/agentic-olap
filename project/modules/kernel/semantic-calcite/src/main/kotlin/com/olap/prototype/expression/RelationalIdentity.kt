package com.olap.prototype.expression

import org.apache.calcite.rel.RelNode
import org.apache.calcite.tools.RelBuilder
import org.apache.calcite.util.ImmutableBitSet

object RelationalIdentity {
    fun requireUnique(relation: RelNode, keys: List<String>, meaning: String) {
        val fields = relation.rowType.fieldNames
        require(fields.containsAll(keys) && relation.cluster.metadataQuery.areColumnsUnique(
            relation, ImmutableBitSet.of(keys.map(fields::indexOf)), false) == true) {
            "$meaning identity is not proved by the actual native relation"
        }
    }

    fun checkKnown(builder: RelBuilder, relation: RelNode, keys: List<String>, code: String, checks: MutableList<RelationalCheck>) {
        if (keys.isEmpty()) return
        builder.push(relation)
        builder.filter(builder.or(keys.map { builder.isNull(builder.field(it)) }))
        builder.project(listOf(builder.literal(1)), listOf("violation"), true).limit(0, 1)
        checks.add(RelationalCheck(code, builder.build()))
    }
}
