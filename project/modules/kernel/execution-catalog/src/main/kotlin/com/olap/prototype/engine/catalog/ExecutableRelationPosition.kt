package com.olap.prototype.engine.catalog

import com.olap.prototype.event.RelationAccessPath
import java.util.Collections
import java.util.TreeMap

/** 同一资产的两个角色具有不同出现位置与字段ordinal。 */
object ExecutableRelationPosition {
    class Id(scope: List<String>, val role: String, path: List<RelationAccessPath.JoinRef>) {
        val scope: List<String> = java.util.List.copyOf(scope)
        val path: List<RelationAccessPath.JoinRef> = java.util.List.copyOf(path)
        init { require(role.isNotBlank()) { "relation position role is required" }; require(path.size <= 16) { "relation position must have a finite bounded path" } }
        companion object { fun backbone(): Id = Id(emptyList(), "backbone", emptyList()) }
        override fun equals(other: Any?): Boolean = other is Id && scope == other.scope && role == other.role && path == other.path
        override fun hashCode(): Int = listOf(scope, role, path).hashCode()
        override fun toString(): String = "Id(scope=$scope, role=$role, path=$path)"
    }
    class Expression(val local: Id, roles: Map<String, Id>, scope: List<String>, val clockOwner: String) {
        val roles: Map<String, Id> = Collections.unmodifiableMap(TreeMap(roles))
        val scope: List<String> = java.util.List.copyOf(scope)
        fun quantifier(instanceId: String): List<String> = scope + instanceId
        companion object { fun local(position: Id): Expression = Expression(position, emptyMap(), position.scope, "local") }
        override fun equals(other: Any?): Boolean = other is Expression && local == other.local && roles == other.roles && scope == other.scope && clockOwner == other.clockOwner
        override fun hashCode(): Int = listOf(local, roles, scope, clockOwner).hashCode()
    }
}
