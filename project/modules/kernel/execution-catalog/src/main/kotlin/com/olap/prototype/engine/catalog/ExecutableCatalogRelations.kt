package com.olap.prototype.engine.catalog

import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.SemanticExpressionInspector
import com.olap.prototype.member.MemberSource
import com.olap.prototype.member.MemberSourceDefinition
import com.olap.prototype.query.SourceJoinContract
import com.olap.prototype.query.TemporalJoinContract

/** 完整计算目录的出现位置、连续路径及时间粒度，不读取治理或公开权限。 */
class ExecutableCatalogRelations internal constructor(private val catalog: ExecutableCatalog) {
    private val bindings = catalog.physicalExecutionBindings.values.groupBy { "${it.logicalTableId}@${it.logicalTableVersion}" }
    fun binding(id: String, version: String): ExecutablePhysicalExecutionBinding {
        val matches = bindings["$id@$version"] ?: emptyList()
        require(matches.size == 1) { "relationship position requires one exact logical-table binding: $id@$version" }
        return matches.single()
    }
    fun joinPath(topology: ExecutableTopology, id: String, version: String): ExecutableJoinPath {
        val edge = requireNotNull(catalog.joinPaths["$id@$version"]) { "exact role JoinPath is missing" }
        require(edge.topologyId == topology.topologyId && edge.topologyVersion == topology.topologyVersion && belongsTo(topology, id, version)) { "role path is outside the frozen topology" }
        SourceJoinContract.require(edge.joinType, edge.cardinality, edge.nullPolicy)
        return edge
    }
    fun path(topology: ExecutableTopology, start: ExecutablePhysicalExecutionBinding, path: RelationAccessPath): List<ExecutableJoinPath> {
        var id = start.logicalTableId; var version = start.logicalTableVersion
        return path.joinPathRefs.map { reference ->
            joinPath(topology, reference.joinPathId, reference.joinPathVersion.toString()).also { edge ->
                require(edge.sourceLogicalTableId == id && edge.sourceLogicalTableVersion == version) { "frozen role path must be continuous from its exact source" }
                id = edge.targetLogicalTableId; version = edge.targetLogicalTableVersion
            }
        }
    }
    internal fun requireComplete() {
        validateTopologyJoinPaths()
        catalog.joinPaths.values.forEach(::requireTemporalConstraint)
        validateRoleBindings()
        catalog.memberSources.values.filterIsInstance<MemberSource.Projection>().forEach { projection ->
            val ref = projection.definition.event
            val event = requireNotNull(catalog.events["${ref.eventId}@${ref.eventVersion}"]) { "member event is missing" }
            projection.definition.result.outputs.forEach { (slot, output) ->
                if (output is MemberSourceDefinition.DimensionOutput) catalog.memberDimensionMappings(projection, slot)
                else requireRoleExpression(event, MemberSourceDefinition.propertyPath(output), emptyMap(), listOf(requireNotNull(projection.outputExpressions[slot])))
            }
        }
    }
    private fun requireTemporalConstraint(path: ExecutableJoinPath) {
        path.temporalConstraint?.intervals?.forEach { interval ->
            val clock = interval.sourceTime
            if (clock is TemporalJoinContract.LogicUnitTime) requireLogicUnit(clock.logicUnitId, clock.logicUnitVersion, "temporal source")
            requireTemporalLogicUnit(interval.targetValidFromLogicUnitId, interval.targetValidFromLogicUnitVersion, path.targetLogicalTableId, path.targetLogicalTableVersion, "temporal target valid from")
            requireTemporalLogicUnit(interval.targetValidToLogicUnitId, interval.targetValidToLogicUnitVersion, path.targetLogicalTableId, path.targetLogicalTableVersion, "temporal target valid to")
            if (clock is TemporalJoinContract.QueryClock) {
                val endpoints = listOf(requireLogicUnit(interval.targetValidFromLogicUnitId, interval.targetValidFromLogicUnitVersion, "temporal.from"),
                    requireLogicUnit(interval.targetValidToLogicUnitId, interval.targetValidToLogicUnitVersion, "temporal.to"))
                require(endpoints.all { it.valueType?.name == clock.valueType.name }) { "query clock type differs from interval endpoint type" }
            }
        }
    }
    private fun validateRoleBindings() {
        val root = RelationAccessPath(emptyList())
        catalog.events.values.forEach { requireRoleExpression(it, root, it.roles, listOfNotNull(it.baseFilter)) }
        catalog.dimensionBindings.values.forEach { binding ->
            val event = requireNotNull(catalog.events["${binding.eventId}@${binding.eventVersion}"]) { "dimension event is missing" }
            require(binding.topologyId == event.topologyId && binding.topologyVersion == event.topologyVersion) { "dimension binding differs from its exact event topology" }
            requireRoleExpression(event, binding.accessPath, binding.roles, listOf(binding.expression))
        }
        catalog.metrics.values.forEach { metric -> (metric.definition as? MetricDefinition.FactMeasure)?.let { fact ->
            val event = requireNotNull(catalog.events["${fact.event.eventId}@${fact.event.eventVersion}"]) { "fact event is missing" }
            requireRoleExpression(event, root, fact.roles, listOfNotNull(fact.input, fact.privatePredicate) + (fact.snapshotRule?.expressions() ?: emptyList()))
        } }
    }
    private fun requireRoleExpression(event: ExecutableEvent, local: RelationAccessPath, roles: Map<String, RelationAccessPath>, expressions: List<SemanticExpression>) {
        roleBinding(event, local)
        expressions.forEach { expression ->
            SemanticExpressionInspector.quantifiers(expression).forEach { roleBinding(event, SemanticExpressionInspector.localPath(it.parents + it.expression, local, roles)) }
            SemanticExpressionInspector.accesses(expression).forEach { access ->
                val ref = access.reference
                require(ref.referenceKind != SemanticExpression.ReferenceKind.PHYSICAL_COLUMN) { "input expression cannot bypass LogicUnit ownership" }
                val owner = roleBinding(event, SemanticExpressionInspector.path(access, local, roles))
                require("${ref.id}@${ref.version}" in owner.logicUnitBindings) { "frozen LogicUnit is outside its explicitly declared relationship position" }
            }
        }
    }
    private fun roleBinding(event: ExecutableEvent, path: RelationAccessPath): ExecutablePhysicalExecutionBinding {
        val topology = requireNotNull(catalog.topologies["${event.topologyId}@${event.topologyVersion}"]) { "frozen event topology is missing" }
        var current = binding(topology.backboneLogicalTableId, topology.backboneLogicalTableVersion)
        this.path(topology, current, path).forEach { current = binding(it.targetLogicalTableId, it.targetLogicalTableVersion) }
        return current
    }
    private fun requireTemporalLogicUnit(id: String, version: Long, tableId: String, tableVersion: String, field: String) {
        val unit = requireLogicUnit(id, version, field)
        val owner = catalog.physicalExecutionBindings["${unit.physicalExecutionBindingId}@${unit.physicalExecutionBindingVersion}"]
        require(owner != null && owner.logicalTableId == tableId && owner.logicalTableVersion == tableVersion) { "$field must belong to logical table $tableId@$tableVersion" }
    }
    private fun validateTopologyJoinPaths() {
        catalog.topologies.values.forEach { topology -> topology.joinPathRefs.forEach { reference ->
            val path = requireNotNull(catalog.joinPaths["${reference.joinPathId}@${reference.joinPathVersion}"]) { "topology references missing exact join path" }
            require(topology.topologyId == path.topologyId && topology.topologyVersion == path.topologyVersion) { "join path belongs to another exact topology" }
            path.temporalConstraint?.intervals?.forEach { interval ->
                (interval.sourceTime as? TemporalJoinContract.LogicUnitTime)?.let { requireTemporalSource(topology, path, it) }
            }
        } }
    }
    private fun requireTemporalSource(topology: ExecutableTopology, path: ExecutableJoinPath, clock: TemporalJoinContract.LogicUnitTime) {
        val backbone = clock is TemporalJoinContract.BackboneLogicUnitClock
        var tableId = if (backbone) topology.backboneLogicalTableId else path.sourceLogicalTableId
        var tableVersion = if (backbone) topology.backboneLogicalTableVersion else path.sourceLogicalTableVersion
        val visited = hashSetOf(tableId)
        clock.joinPathRefs.forEach { reference ->
            val anchor = catalog.joinPaths["${reference.joinPathId}@${reference.joinPathVersion}"]
            require(anchor == null || !backbone || anchor.cardinality in setOf("ONE_TO_ONE", "MANY_TO_ONE")) { "temporal backbone clock path expands the fact grain" }
            require(anchor != null && belongsTo(topology, reference.joinPathId, reference.joinPathVersion.toString()) && anchor.sourceLogicalTableId == tableId &&
                anchor.sourceLogicalTableVersion == tableVersion && anchor.targetLogicalTableId != path.targetLogicalTableId && visited.add(anchor.targetLogicalTableId)) { "temporal source time path is missing, discontinuous or target dependent" }
            tableId = anchor.targetLogicalTableId; tableVersion = anchor.targetLogicalTableVersion
        }
        requireTemporalLogicUnit(clock.logicUnitId, clock.logicUnitVersion, tableId, tableVersion, "temporal source time")
    }
    private fun requireLogicUnit(id: String, version: Long, field: String): ExecutableLogicUnit = requireNotNull(catalog.logicUnits["$id@$version"]) { "$field is missing" }
    private fun belongsTo(topology: ExecutableTopology, id: String, version: String): Boolean = topology.joinPathRefs.any { it.joinPathId == id && it.joinPathVersion == version }
}
