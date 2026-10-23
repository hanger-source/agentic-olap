package com.olap.prototype.expression

import org.apache.calcite.rel.RelNode
import org.apache.calcite.tools.RelBuilder
import org.apache.calcite.util.ImmutableBitSet
import org.apache.calcite.plan.Strong
import com.olap.prototype.contract.JdbcRelationCheck
import com.olap.prototype.query.QueryStageAnalysis.ScopeId

object RelationalIdentity {
    fun requireUnique(relation: RelNode, keys: List<String>, meaning: String) {
        val fields = relation.rowType.fieldNames
        require(fields.containsAll(keys) && relation.cluster.metadataQuery.areColumnsUnique(
            relation, ImmutableBitSet.of(keys.map(fields::indexOf)), false) == true) {
            "$meaning identity is not proved by the actual native relation"
        }
    }

    fun checkKnown(builder: RelBuilder, relation: RelNode, keys: List<String>, code: String, checks: MutableList<RelationalCheck>, scope: ScopeId? = null) {
        if (keys.isEmpty()) return
        val fields = relation.rowType.fieldList
        val predicates = relation.cluster.metadataQuery.getPulledUpPredicates(relation)
        val unknown = keys.filter { key ->
            val index = fields.indexOfFirst { it.name == key }
            require(index >= 0) { "identity column is absent from the completed relation: $key" }
            val nonNull = predicates.isEffectivelyNotNull(relation.cluster.rexBuilder.makeInputRef(relation, index)) ||
                predicates.pulledUpPredicates.any { Strong.isNotTrue(it, ImmutableBitSet.of(index)) }
            fields[index].type.isNullable && !nonNull
        }
        if (unknown.isEmpty()) return
        builder.push(relation)
        builder.filter(builder.or(unknown.map { builder.isNull(builder.field(it)) }))
        builder.project(listOf(builder.literal(1)), listOf("violation"), true).limit(0, 1)
        checks.add(RelationalCheck(code, builder.build(), JdbcRelationCheck.Origin(scope, unknown, "IDENTITY_NON_NULL_NOT_PROVED")))
    }
}
