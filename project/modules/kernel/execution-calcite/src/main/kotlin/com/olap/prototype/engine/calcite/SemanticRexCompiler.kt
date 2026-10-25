package com.olap.prototype.engine.calcite

import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.contract.NativeTableDependency
import com.olap.prototype.engine.catalog.ExecutablePhysicalExecutionBinding
import com.olap.prototype.engine.catalog.ExecutableRelationPosition
import com.olap.prototype.expression.*
import com.olap.prototype.warehouse.contract.SourceBindingRef
import org.apache.calcite.rel.core.CorrelationId
import org.apache.calcite.rex.RexNode
import org.apache.calcite.tools.RelBuilder

/** 字段在明确关系位置解析，LogicUnit内部字段始终留在该位置的采用owner内。 */
class SemanticRexCompiler(builder: RelBuilder, private val root: ExecutableRelationPosition.Id,
    positions: Map<ExecutableRelationPosition.Id, ExecutablePhysicalExecutionBinding>,
    private val physicalColumns: (ExecutableRelationPosition.Id, String) -> RexNode,
    parameters: MutableList<JdbcParameter>,
    private val quantifiers: (SemanticExpression.Exists, ExecutableRelationPosition.Expression) -> RexNode,
    private val correlations: MutableSet<CorrelationId>) {
    private val positions = positions.toMap()
    private val expressions: SemanticCalciteRexCompiler
    private data class Dependency(val position: ExecutableRelationPosition.Id, val unit: String)

    init {
        val local = requireNotNull(this.positions[root]) { "explicit local root position is required" }
        val system = builder.typeFactory.typeSystem as? SemanticCalciteTypeSystem ?: throw IllegalArgumentException("source expression requires native compilation types")
        val tables = linkedMapOf<String, NativeTableDependency>()
        this.positions.values.forEach { binding -> binding.nativeTableDependencies.forEach { (key, dependency) ->
            dependency.requireSource(local.adoption.binding, local.connectorRef, local.dialect)
            val prior = tables.putIfAbsent(key, dependency)
            require(prior == null || prior == dependency) { "native table has conflicting frozen dependencies" }
        } }
        val native = NativeExpressionScope(local.connectorRef, system.nativeSupport) { tables[it.key()] }
        expressions = SemanticCalciteRexCompiler(builder, parameters) { call, operands -> native.compile(builder, call, operands) }
    }

    fun compile(expression: SemanticExpression): RexNode = compile(expression, ExecutableRelationPosition.Expression.local(root))
    fun compile(expression: SemanticExpression, context: ExecutableRelationPosition.Expression): RexNode = compile(expression, context, false, mutableSetOf())
    fun drainCorrelations(): Set<CorrelationId> = correlations.toSet().also { correlations.clear() }

    private fun compile(expression: SemanticExpression, context: ExecutableRelationPosition.Expression, inside: Boolean, resolving: MutableSet<Dependency>): RexNode =
        expressions.compile(expression, { reference(it, context, inside, resolving) }, {
            require(!inside) { "generic LogicUnit cannot own relationship quantifiers" }
            quantifiers(it, context)
        })

    private fun reference(ref: SemanticExpression.Reference, context: ExecutableRelationPosition.Expression, inside: Boolean, resolving: MutableSet<Dependency>): RexNode {
        val at = if (ref.referenceKind == SemanticExpression.ReferenceKind.ROLE_LOGIC_UNIT) {
            require(!inside) { "relationship roles belong to the using input, not a generic LogicUnit" }
            requireNotNull(context.roles[ref.role]) { "reference role is not bound to a relationship position: ${ref.role}" }
        } else context.local
        val binding = requireNotNull(positions[at]) { "reference position is missing" }
        val resolved = resolveReference(ref, inside, binding)
        resolved.column?.let { return physicalColumns(at, it) }
        val dependency = Dependency(at, resolved.key)
        require(resolving.add(dependency)) { "cyclic positioned LogicUnit dependency: $dependency" }
        return try { compile(requireNotNull(resolved.definition), ExecutableRelationPosition.Expression.local(at), true, resolving) }
        finally { resolving.remove(dependency) }
    }

    data class PhysicalDependencies(val columns: Set<String>, val tables: Set<SemanticExpression.TableArgument>)
    private data class ResolvedReference(val key: String, val definition: SemanticExpression?, val column: String?)

    companion object {
        fun physicalDependencies(expressions: List<SemanticExpression>, bindings: Collection<ExecutablePhysicalExecutionBinding>): PhysicalDependencies {
            val owners = linkedMapOf<String, ExecutablePhysicalExecutionBinding>()
            bindings.forEach { binding -> binding.logicUnitBindings.forEach { (key, definition) ->
                val prior = owners.putIfAbsent(key, binding)
                require(prior == null || prior.bindingId == binding.bindingId && prior.bindingVersion == binding.bindingVersion && prior.logicUnitBindings[key] == definition) {
                    "one exact LogicUnit has conflicting frozen owners: $key"
                }
            } }
            val columns = linkedSetOf<String>()
            val tables = linkedSetOf<SemanticExpression.TableArgument>()
            expressions.forEach { collect(it, owners, null, columns, tables, mutableSetOf()) }
            return PhysicalDependencies(columns.toSet(), tables.toSet())
        }

        private fun resolveReference(reference: SemanticExpression.Reference, inside: Boolean, binding: ExecutablePhysicalExecutionBinding): ResolvedReference {
            val key = "${reference.id}@${reference.version}"
            require(!inside || reference.referenceKind != SemanticExpression.ReferenceKind.ROLE_LOGIC_UNIT) { "relationship roles belong to the using input, not a generic LogicUnit" }
            return if (reference.referenceKind == SemanticExpression.ReferenceKind.PHYSICAL_COLUMN) {
                require(inside) { "physical fields are only allowed inside their LogicUnit owner" }
                ResolvedReference(key, null, requireNotNull(binding.physicalColumnBindings[key]) { "exact physical field is outside the position owner" })
            } else ResolvedReference(key, requireNotNull(binding.logicUnitBindings[key]) { "LogicUnit is outside its explicit relationship position: $key" }, null)
        }

        private fun collect(expression: SemanticExpression, owners: Map<String, ExecutablePhysicalExecutionBinding>, owner: ExecutablePhysicalExecutionBinding?,
            columns: MutableSet<String>, tables: MutableSet<SemanticExpression.TableArgument>, resolving: MutableSet<String>) {
            val inside = owner != null
            require(!inside || SemanticExpressionInspector.quantifiers(expression).isEmpty()) { "generic LogicUnit cannot own relationship quantifiers" }
            tables.addAll(SemanticExpressionInspector.physicalTables(expression))
            SemanticExpressionInspector.references(expression).forEach { reference ->
                val key = "${reference.id}@${reference.version}"
                val binding = owner ?: requireNotNull(owners[key]) { "frozen exact LogicUnit is missing: $key" }
                val resolved = resolveReference(reference, inside, binding)
                if (resolved.column != null) columns.add(resolved.key) else {
                    require(resolving.add(resolved.key)) { "cyclic LogicUnit definition: ${resolved.key}" }
                    try { collect(requireNotNull(resolved.definition), owners, binding, columns, tables, resolving) }
                    finally { resolving.remove(resolved.key) }
                }
            }
        }
    }
}
