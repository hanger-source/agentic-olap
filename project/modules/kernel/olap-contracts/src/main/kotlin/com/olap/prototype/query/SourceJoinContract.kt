package com.olap.prototype.query

/** 源方向、键匹配与不同输入的人口；基数标签不产生唯一性证据。 */
object SourceJoinContract {
    enum class JoinType { INNER, LEFT, RIGHT, FULL; fun requiresBackboneMatch(): Boolean = this == INNER || this == RIGHT }
    enum class Cardinality { ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE, MANY_TO_MANY }
    enum class NullPolicy { SQL_EQUALS_NULL_NEVER_MATCHES }
    fun require(joinType: String, cardinality: String, nullPolicy: String) {
        type(joinType); value<Cardinality>(cardinality, "source cardinality"); value<NullPolicy>(nullPolicy, "source NULL-key policy")
    }
    fun type(value: String): JoinType = value(value, "source join type")
    private inline fun <reified T : Enum<T>> value(value: String, field: String): T = try { enumValueOf<T>(value) }
        catch (failure: IllegalArgumentException) { throw IllegalArgumentException("$field is not declared by the source relationship contract: $value", failure) }
    fun contract(): Map<String, Any> = mapOf("formatVersion" to "olap-source-join-contract/v1", "joinTypes" to JoinType.entries.map { it.name },
        "cardinalities" to Cardinality.entries.map { it.name }, "nullPolicies" to NullPolicy.entries.map { it.name },
        "factPopulation" to "EXACT_EVENT_BACKBONE_ROWS_ONLY_RIGHT_TARGET_ORPHANS_ARE_NOT_FACTS",
        "factMatching" to mapOf("INNER" to "REQUIRE_MATCH", "RIGHT" to "REQUIRE_MATCH", "LEFT" to "KEEP_BACKBONE", "FULL" to "KEEP_BACKBONE"),
        "memberPopulation" to "DECLARED_RELATION_BAG_WITH_IDENTITY_AND_ATTRIBUTE_PROOFS", "existsPopulation" to "ACTUALLY_ASSOCIATED_BACKBONE_ROWS_ONLY",
        "keyMatching" to "ALL_EQUALITY_KEYS_AND_ALL_TEMPORAL_INTERVALS_CONJOINED_NULL_KEYS_DO_NOT_MATCH",
        "guarantees" to "ONLY_ADOPTED_NATIVE_KEYS_OR_COMPLETE_FROZEN_NONOVERLAP_CARDINALITY_LABELS_ARE_NOT_PROOF")
}
