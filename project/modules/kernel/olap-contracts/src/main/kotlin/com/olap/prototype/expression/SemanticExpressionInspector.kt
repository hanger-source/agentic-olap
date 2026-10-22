package com.olap.prototype.expression

import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.expression.SemanticExpression.*

/** 引用保留量词位置，同一资产在不同量词下是不同访问。 */
object SemanticExpressionInspector {
    class Access(val reference: Reference, scopes: List<Exists>) {
        val scopes: List<Exists> = java.util.List.copyOf(scopes)
        override fun equals(other: Any?): Boolean = other is Access && reference == other.reference && scopes == other.scopes
        override fun hashCode(): Int = listOf(reference, scopes).hashCode()
    }
    class Quantifier(val expression: Exists, parents: List<Exists>) {
        val parents: List<Exists> = java.util.List.copyOf(parents)
        override fun equals(other: Any?): Boolean = other is Quantifier && expression == other.expression && parents == other.parents
        override fun hashCode(): Int = listOf(expression, parents).hashCode()
    }

    fun references(expression: SemanticExpression): List<Reference> = accesses(expression).map { it.reference }
    fun accesses(expression: SemanticExpression): List<Access> = inspect(expression).references
    fun nativeCalls(expression: SemanticExpression): List<NativeCall> = inspect(expression).nativeCalls
    fun physicalTables(expression: SemanticExpression): List<TableArgument> = nativeCalls(expression).flatMap { it.arguments }.filterIsInstance<TableArgument>().distinct()
    fun references(expression: SemanticExpression, kind: ReferenceKind, id: String, version: Long): Boolean =
        references(expression).any { it.referenceKind == kind && it.id == id && it.version == version }

    fun quantifiers(expression: SemanticExpression): List<Quantifier> {
        val quantifiers = inspect(expression).quantifiers
        val positions = hashSetOf<List<String>>()
        quantifiers.forEach { require(positions.add(it.parents.map { parent -> parent.instanceId } + it.expression.instanceId)) { "quantifier instanceId must be unique in its local scope" } }
        return quantifiers
    }

    fun localPath(scopes: List<Exists>, local: RelationAccessPath, roles: Map<String, RelationAccessPath>): RelationAccessPath =
        scopes.fold(local) { current, exists ->
            val parent = when (val from = exists.from) {
                is RoleParent -> requireNotNull(roles[from.role]) { "quantifier parent role is not declared" }
                is LocalParent -> current
            }
            RelationAccessPath(parent.joinPathRefs + exists.accessPath.joinPathRefs)
        }

    fun path(access: Access, local: RelationAccessPath, roles: Map<String, RelationAccessPath>): RelationAccessPath =
        if (access.reference.referenceKind == ReferenceKind.ROLE_LOGIC_UNIT)
            requireNotNull(roles[access.reference.role]) { "reference role is not declared" }
        else localPath(access.scopes, local, roles)

    private data class Inspection(val references: List<Access>, val quantifiers: List<Quantifier>, val nativeCalls: List<NativeCall>)
    private fun inspect(expression: SemanticExpression): Inspection {
        val references = mutableListOf<Access>()
        val quantifiers = mutableListOf<Quantifier>()
        val calls = mutableListOf<NativeCall>()
        fun walk(node: SemanticExpression, scopes: List<Exists>) {
            require(scopes.size <= 16) { "quantifier nesting exceeds the input scope budget" }
            if (node is Reference) references.add(Access(node, scopes))
            if (node is NativeCall) calls.add(node)
            val nested = if (node is Exists) { quantifiers.add(Quantifier(node, scopes)); scopes + node } else scopes
            SemanticExpressionTree.children(node).forEach { walk(it, nested) }
        }
        walk(expression, emptyList())
        return Inspection(java.util.List.copyOf(references), java.util.List.copyOf(quantifiers), java.util.List.copyOf(calls))
    }
}
