package com.olap.prototype.engine.executable

import com.olap.prototype.engine.catalog.*
import com.olap.prototype.engine.catalog.ExecutableRelationPosition.Expression
import com.olap.prototype.engine.catalog.ExecutableRelationPosition.Id
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.SemanticExpressionInspector
import com.olap.prototype.member.MemberSourceDefinition
import com.olap.prototype.query.*
import java.util.IdentityHashMap

/** 由精确入口及显式访问路径产生位置，不按资产是否已出现推断角色。 */
class ExecutableSourceResolver {
    fun resolve(catalog: ExecutableCatalog, graph: ExecutableInputGraph): ResolvedInputSources {
        val count = intArrayOf(0)
        val relations = catalog.relations()
        val resolved = IdentityHashMap<ExecutableEventInput, ResolvedEventInput>()
        graph.sourceOrder.forEach { resolveScope(catalog, relations, it, count, resolved) }
        return ResolvedInputSources(resolved).also { SourceBindingConflictException.requireSingleBinding(ExecutableSourceDependencies.inputs(graph, it)) }
    }
    private fun resolveScope(catalog: ExecutableCatalog, relations: ExecutableCatalogRelations, scope: ExecutableInputScope,
        count: IntArray, resolved: MutableMap<ExecutableEventInput, ResolvedEventInput>) {
        val clocks = linkedSetOf<String>()
        scope.eventInputs.forEach { input ->
            check(input !in resolved) { "event input has already been resolved" }
            val graph = Positions.root(catalog, relations, input, count)
            resolved[input] = resolveInput(catalog, input, graph)
            if (input is ExecutableMetricInput) input.relationConditions.values.forEach { relation ->
                val child = relation.input
                val qualifiers = listOf("relation", relation.instanceId)
                val association = Positions(catalog, relations, input, count, graph.positions, graph.root, qualifiers, child.clocks())
                val path = requireNotNull(input.event.roles[relation.definition.relationshipRole]) { "frozen relationship role is missing" }
                val relatedRoot = association.path("relation:${relation.definition.id}", graph.root, path, true)
                val binding = association.positions.getValue(relatedRoot)
                require(binding.logicalTableId == child.topology.backboneLogicalTableId && binding.logicalTableVersion == child.topology.backboneLogicalTableVersion) { "relationship must end at the exact related event backbone" }
                val related = Positions(catalog, relations, child, count, association.positions, relatedRoot, qualifiers, child.clocks())
                related.joins.putAll(association.joins); related.clocks.putAll(association.clocks)
                resolved[child] = resolveInput(catalog, child, related)
                require(related.clocks.keys == child.clocks().keys) { "relation clocks must bind all and only its selected positions" }
            }
            clocks.addAll(graph.clocks.keys)
        }
        when (val root = scope.root) {
            is ExecutableInputRoot.Measures -> require(clocks == root.clocks.keys) { "source clocks must bind all and only selected relationship positions" }
            is ExecutableInputRoot.Members -> (root.input as? ExecutableMemberInput.Projection)?.let {
                require(clocks == it.clocks().keys) { "member clocks must bind all and only selected relationship positions" }
            }
            else -> Unit
        }
    }
    private fun resolveInput(catalog: ExecutableCatalog, input: ExecutableEventInput, graph: Positions): ResolvedEventInput {
        val event = graph.expression("event", graph.root, input.event.roles, listOfNotNull(input.event.baseFilter))
        val metric = (input as? ExecutableMetricInput)?.let { fact ->
            val definition = fact.metric.factMeasure()
            graph.expression("metric:${fact.metric.reference.key()}", graph.root, definition.roles,
                listOfNotNull(definition.input, definition.privatePredicate) + (definition.snapshotRule?.expressions() ?: emptyList()))
        }
        val dimensions = linkedMapOf<String, Expression>()
        input.analysisDimensions.forEach { dimensions["${it.binding.bindingId}@${it.binding.bindingVersion}"] = graph.dimension(it.binding) }
        val members = linkedMapOf<String, Expression>()
        if (input is ExecutableMemberInput.Projection) input.usedSlots.forEach { slot ->
            val source = input.memberSource
            val output = source.definition.result.outputs.getValue(slot)
            members[slot] = if (output is MemberSourceDefinition.DimensionOutput) {
                val binding = requireNotNull(catalog.dimensionBindings["${output.bindingId}@${output.bindingVersion}"]) { "member dimension binding is missing" }
                graph.dimension(binding).also { dimensions["${binding.bindingId}@${binding.bindingVersion}"] = it }
            } else {
                val local = graph.path("member:${source.id}@${source.version}:$slot", graph.root, MemberSourceDefinition.propertyPath(output))
                graph.expression("member:$slot", local, emptyMap(), listOf(source.outputExpressions.getValue(slot)))
            }
        }
        val range = if (input.window() == null) null else when (input) {
            is ExecutableMemberInput.Projection -> requireNotNull(members[input.memberSource.definition.result.timeSlot]) { "member time position is missing" }
            is ExecutableMetricInput -> {
                val selected = requireNotNull(input.rangeDimension) { "fact time range requires its actual bound dimension mapping" }
                graph.dimension(selected.binding).also { dimensions["${selected.binding.bindingId}@${selected.binding.bindingVersion}"] = it }
            }
        }
        return ResolvedEventInput(graph.root, graph.positions.toMap(), event, metric, dimensions, members, range,
            graph.joins.values.toList(), graph.clocks.toMap(), graph.quantifiers.toMap())
    }

    private class Positions(private val catalog: ExecutableCatalog, private val relations: ExecutableCatalogRelations,
        private val input: ExecutableEventInput, private val budget: IntArray, outer: Map<Id, ExecutablePhysicalExecutionBinding>,
        val root: Id, private val scope: List<String>, private val values: Map<String, TemporalClockBindings.Value>) {
        val positions = LinkedHashMap(outer)
        val joins = linkedMapOf<Id, ExecutablePhysicalJoin>()
        val quantifiers = linkedMapOf<List<String>, ResolvedQuantifier>()
        val clocks = linkedMapOf<String, TemporalClockBindings.Value>()
        private data class StaticStep(val source: Id, val join: RelationAccessPath.JoinRef)
        private val staticPositions = linkedMapOf<StaticStep, Id>()
        private val clockDependencies = linkedSetOf<String>()
        companion object {
            fun root(catalog: ExecutableCatalog, relations: ExecutableCatalogRelations, input: ExecutableEventInput, budget: IntArray): Positions =
                Positions(catalog, relations, input, budget, emptyMap(), Id.backbone(), emptyList(), input.clocks()).apply {
                    put(root, relations.binding(input.topology.backboneLogicalTableId, input.topology.backboneLogicalTableVersion))
                }
        }
        private fun put(id: Id, binding: ExecutablePhysicalExecutionBinding) {
            val prior = positions.putIfAbsent(id, binding)
            require(prior == null || prior == binding) { "one relation position has conflicting assets" }
            require(prior != null || ++budget[0] <= ScopedInputContract.MAX_INPUTS) { "query exceeds relationship position budget" }
        }
        fun expression(owner: String, local: Id, declared: Map<String, RelationAccessPath>, expressions: List<SemanticExpression>): Expression {
            val used = sortedSetOf<String>()
            expressions.forEach { expression ->
                SemanticExpressionInspector.references(expression).filter { it.referenceKind == SemanticExpression.ReferenceKind.ROLE_LOGIC_UNIT }.forEach { used.add(requireNotNull(it.role)) }
                SemanticExpressionInspector.quantifiers(expression).forEach { (it.expression.from as? SemanticExpression.RoleParent)?.let { from -> used.add(from.role) } }
            }
            val roles = used.associateWith { path("$owner:$it", root, requireNotNull(declared[it]) { "expression role is not declared" }) }
            val context = Expression(local, roles, scope + owner, owner)
            expressions.forEach { resolveExpression(it, context) }
            return context
        }
        private fun resolveExpression(expression: SemanticExpression, context: Expression) {
            SemanticExpressionInspector.accesses(expression).filter { it.scopes.isEmpty() }.forEach { access ->
                val ref = access.reference
                require(ref.referenceKind != SemanticExpression.ReferenceKind.PHYSICAL_COLUMN) { "input expression cannot bypass a LogicUnit owner" }
                val at = if (ref.referenceKind == SemanticExpression.ReferenceKind.ROLE_LOGIC_UNIT) requireNotNull(context.roles[ref.role]) { "expression role is missing" } else context.local
                requireUnit(at, ref.id, ref.version)
            }
            SemanticExpressionInspector.quantifiers(expression).filter { it.parents.isEmpty() }.forEach { declaration ->
                val exists = declaration.expression
                val from = (exists.from as? SemanticExpression.RoleParent)?.let { requireNotNull(context.roles[it.role]) { "quantifier parent role is missing" } } ?: context.local
                val scope = context.quantifier(exists.instanceId)
                val child = Positions(catalog, relations, input, budget, positions, root, scope, values)
                val owner = "${context.clockOwner}/${exists.instanceId}"
                val local = child.path(owner, from, exists.accessPath, true)
                val predicate = Expression(local, context.roles, scope, owner)
                child.resolveExpression(exists.predicate, predicate)
                val value = ResolvedQuantifier(from, predicate, child.positions.toMap(), child.joins.values.toList(), child.clocks.toMap(), child.quantifiers.toMap())
                val prior = quantifiers.putIfAbsent(scope, value)
                require(prior == null || prior == value) { "one quantified occurrence has conflicting definitions" }
                clocks.putAll(child.clocks)
            }
        }
        fun dimension(binding: ExecutableDimensionBinding): Expression {
            val owner = "dimension:${binding.bindingId}@${binding.bindingVersion}"
            return expression(owner, path(owner, root, binding.accessPath), binding.roles, listOf(binding.expression))
        }
        private fun requireUnit(at: Id, id: String, version: Long) {
            val binding = requireNotNull(positions[at]) { "expression position is missing" }
            require("$id@$version" in binding.logicUnitBindings) { "LogicUnit must be resolved within its explicit relationship position: $id@$version at $at" }
        }
        fun path(role: String, start: Id, access: RelationAccessPath, requiredMatch: Boolean = false): Id {
            var current = start
            val prefix = (if (start == root) emptyList() else start.path).toMutableList()
            relations.path(input.topology, positions.getValue(start), access).forEach { edge ->
                val ref = RelationAccessPath.JoinRef(edge.joinPathId, edge.joinPathVersion.toLong())
                prefix.add(ref)
                val target = if (!requiredMatch && edge.temporalConstraint == null) staticPositions.getOrPut(StaticStep(current, ref)) { Id(scope, role, prefix) } else Id(scope, role, prefix)
                put(target, relations.binding(edge.targetLogicalTableId, edge.targetLogicalTableVersion))
                val prior = joins[target]
                if (prior == null) add(edge, current, target, requiredMatch)
                else require(prior.source == current && prior.joinPath == edge) { "one role position has conflicting access paths" }
                current = target
            }
            return current
        }
        private fun add(edge: ExecutableJoinPath, source: Id, target: Id, requiredMatch: Boolean) {
            val dependency = "${edge.joinPathId}@${edge.joinPathVersion}"
            require(clockDependencies.add(dependency)) { "cyclic temporal clock path dependency: $clockDependencies" }
            try {
                val times = linkedMapOf<Int, Id>(); val slots = linkedMapOf<Int, String>()
                edge.temporalConstraint?.intervals?.forEachIndexed { index, interval ->
                    requireUnit(target, interval.targetValidFromLogicUnitId, interval.targetValidFromLogicUnitVersion)
                    requireUnit(target, interval.targetValidToLogicUnitId, interval.targetValidToLogicUnitVersion)
                    when (val clock = interval.sourceTime) {
                        is TemporalJoinContract.LogicUnitTime -> {
                            val local = if (clock is TemporalJoinContract.BackboneLogicUnitClock) root else source
                            val path = RelationAccessPath(clock.joinPathRefs.map { RelationAccessPath.JoinRef(it.joinPathId, it.joinPathVersion) })
                            val at = path("${target.role}:clock:$index", local, path)
                            requireUnit(at, clock.logicUnitId, clock.logicUnitVersion); times[index] = at
                        }
                        is TemporalJoinContract.QueryClock -> {
                            val slot = TemporalClockBindings.slot(target.role, target.path, index)
                            val value = requireNotNull(values[slot]) { "positioned query clock is missing: $slot" }
                            require(value.valueType == clock.valueType) { "positioned clock has a different temporal type" }
                            clocks[slot] = value; slots[index] = slot
                        }
                    }
                }
                joins[target] = ExecutablePhysicalJoin(edge, source, target, times, slots, requiredMatch)
            } finally { clockDependencies.remove(dependency) }
        }
    }
}
