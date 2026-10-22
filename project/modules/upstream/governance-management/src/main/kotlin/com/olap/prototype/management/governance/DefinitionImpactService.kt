package com.olap.prototype.management.governance

import com.olap.prototype.calculation.MetricInstanceRef
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts.requireText
import com.olap.prototype.expression.SemanticExpressionCodec
import com.olap.prototype.governance.contract.*
import com.olap.prototype.governance.contract.DefinitionImpact.Dependent
import com.olap.prototype.governance.contract.DefinitionImpact.Reference
import com.olap.prototype.governance.contract.DefinitionImpact.Relation
import com.olap.prototype.governance.contract.DefinitionImpact.Step
import com.olap.prototype.storage.PlatformDatabase

/** 在一次一致读内构造精确反向图和最短路径；不保存第二套依赖状态。 */
class DefinitionImpactService(private val database: PlatformDatabase) {
    private val snapshots = GovernanceSnapshotReader(database)
    private val adoptions = SubjectAreaAdoptionRepository(database)
    private val members = MemberDefinitionRepository(database)
    private val refs = GovernanceVersionRefRepository(database)
    private val structural = GovernanceRepository(database)
    private val stepOrder = compareBy<Step>({ it.consumer.assetType }, { it.consumer.id }, { it.consumer.version }, { it.relation.name })

    fun read(request: DefinitionImpactRequest): DefinitionImpact = database.inReadSnapshot {
        val target = reference(request.assetType, request.id, request.version)
        ImpactGraph().also { it.requireTarget(target); it.connectDefinitions(); it.connectContexts(); it.connectReleases() }.impact(target)
    }
    private inner class ImpactGraph {
        private val graph = linkedMapOf<Reference, LinkedHashSet<Step>>()
        private val metrics = coordinates("SELECT metric_id,version FROM metric_version ORDER BY metric_id,version", "METRIC")
        private val memberDefinitions = coordinates("SELECT member_definition_id,version FROM member_definition_version ORDER BY member_definition_id,version", "MEMBER_DEFINITION")
        private val units = coordinates("SELECT logic_unit_id,version FROM logic_unit_version ORDER BY logic_unit_id,version", "LOGIC_UNIT")
        private val events = coordinates("SELECT event_id,version FROM event_version ORDER BY event_id,version", "EVENT")
        private val bindings = coordinates("SELECT binding_id,version FROM dimension_event_binding_version ORDER BY binding_id,version", "DIMENSION_EVENT_BINDING")
        private val adoptedMetrics = coordinates("SELECT id,subject_area_version FROM subject_area_metric_ref ORDER BY id,subject_area_version", "METRIC_ADOPTION")
        private val sources = coordinates("SELECT id,subject_area_version FROM subject_area_member_source ORDER BY id,subject_area_version", "MEMBER_SOURCE")
        private val instances = adoptedMetrics.associateBy { adoptions.metricAdoption(it.id, version(it)).instance }
        init {
            GovernanceDraftVersions.Asset.entries.forEach { asset ->
                coordinates("SELECT ${asset.column},version FROM ${asset.table}_version ORDER BY ${asset.column},version", asset.name).forEach { graph.putIfAbsent(it, linkedSetOf()) }
            }
            (adoptedMetrics + sources).forEach { graph.putIfAbsent(it, linkedSetOf()) }
        }
        fun requireTarget(target: Reference) { if (target !in graph) throw ProducerContractException("exact impact target does not exist: $target") }
        private fun instance(ref: MetricInstanceRef): Reference = requireNotNull(instances[ref]) { "exact adopted instance is missing: $ref" }
        fun connectDefinitions() {
            units.forEach { logicUnitReferences(it, graph, ::instance) }
            events.forEach { eventReferences(it, graph, ::instance) }
            bindings.forEach { binding ->
                bindingReferences(binding, graph, ::instance)
                val actual = snapshots.dimensionEventBinding(binding.id, version(binding))
                edge(graph, reference("DIMENSION", actual.dimensionId, actual.dimensionVersion), binding, Relation.DEFINITION_REFERENCE)
            }
            foreignEdges("SELECT source_logic_unit_id,source_logic_unit_version,join_path_id,join_path_version FROM join_path_key_ref", "LOGIC_UNIT", "JOIN_PATH", graph)
            foreignEdges("SELECT target_logic_unit_id,target_logic_unit_version,join_path_id,join_path_version FROM join_path_key_ref", "LOGIC_UNIT", "JOIN_PATH", graph)
            foreignEdges("SELECT join_path_id,join_path_version,topology_id,topology_version FROM topology_join_path_ref", "JOIN_PATH", "TOPOLOGY", graph)
            metrics.forEach { target -> visitor(graph, target, Relation.DEFINITION_REFERENCE, ::instance).metric(snapshots.metric(target.id, version(target)).definition) }
            memberDefinitions.forEach { target -> visitor(graph, target, Relation.DEFINITION_REFERENCE, ::instance).member(members.read(target.id, version(target)).definition) }
            adoptedMetrics.forEach { target ->
                val actual = adoptions.metricAdoption(target.id, version(target))
                visitor(graph, target, Relation.ADOPTION_BINDING, ::instance).computation(actual.computation)
                edge(graph, target, reference("SUBJECT_AREA", actual.subjectAreaId, actual.subjectAreaVersion), Relation.CONTEXT_MEMBER)
            }
            sources.forEach { target ->
                val actual = adoptions.memberSource(target.id, version(target))
                sourceReferences(actual, target, graph, ::instance)
                edge(graph, target, reference("SUBJECT_AREA", actual.subjectAreaId, actual.subjectAreaVersion), Relation.CONTEXT_MEMBER)
            }
        }
        fun connectReleases() {
            try { GovernanceJdbcSupport.query(database, "SELECT object_type,object_id,object_version,release_id,release_version FROM release_object_ref ORDER BY release_id,release_version,object_type,object_id,object_version") { rows ->
                edge(graph, reference(rows.getString(1), rows.getString(2), rows.getLong(3)), Reference("RELEASE", rows.getString(4), rows.getString(5)), Relation.FROZEN_SNAPSHOT)
            } } catch (failure: java.sql.SQLException) { throw IllegalStateException("cannot read frozen release references", failure) }
        }
        fun connectContexts() {
            coordinates("SELECT physical_table_id,version FROM physical_table_version", "PHYSICAL_TABLE").forEach { table ->
                snapshots.physicalTableColumns(table.id, version(table)).forEach { column -> edge(graph, reference("PHYSICAL_COLUMN", column.id, column.currentVersion), table, Relation.CONTEXT_MEMBER) }
            }
            foreignEdges("SELECT physical_table_id,physical_table_version,logical_table_id,logical_table_version FROM physical_logical_table_ref", "PHYSICAL_TABLE", "LOGICAL_TABLE", graph)
            foreignEdges("SELECT logic_unit_id,logic_unit_version,logical_table_id,logical_table_version FROM logical_table_logic_unit_ref", "LOGIC_UNIT", "LOGICAL_TABLE", graph)
            foreignEdges("SELECT logical_table_id,logical_table_version,topology_id,topology_version FROM topology_logical_table_ref", "LOGICAL_TABLE", "TOPOLOGY", graph)
            coordinates("SELECT subject_area_id,version FROM subject_area_version", "SUBJECT_AREA").forEach { target ->
                val subject = snapshots.subjectArea(target.id, version(target))
                edge(graph, reference("BUSINESS_LINE", subject.businessLineId, subject.businessLineVersion), target, Relation.CONTEXT_MEMBER)
            }
            foreignEdges("SELECT analysis_dimension_id,analysis_dimension_version,subject_area_id,subject_area_version FROM subject_area_analysis_dimension_ref", "ANALYSIS_DIMENSION", "SUBJECT_AREA", graph, Relation.CONTEXT_MEMBER)
            foreignEdges("SELECT scenario_id,scenario_version,subject_area_id,subject_area_version FROM subject_area_scenario_ref", "SCENARIO", "SUBJECT_AREA", graph, Relation.CONTEXT_MEMBER)
            coordinates("SELECT analysis_dimension_id,version FROM analysis_dimension_version", "ANALYSIS_DIMENSION").forEach { target ->
                val actual = snapshots.analysisDimension(target.id, version(target))
                edge(graph, reference("SUBJECT_AREA", actual.subjectAreaId, actual.subjectAreaVersion), target, Relation.CONTEXT_MEMBER)
            }
            GovernanceJdbcSupport.query(database, "SELECT * FROM analysis_dimension_event_mapping ORDER BY id") { row ->
                val mapping = reference("ANALYSIS_DIMENSION_MAPPING", row.getString("id"), row.getLong("analysis_dimension_version"))
                edge(graph, reference("EVENT", row.getString("event_id"), row.getLong("event_version")), mapping, Relation.DEFINITION_REFERENCE)
                edge(graph, reference("DIMENSION", row.getString("dimension_id"), row.getLong("dimension_version")), mapping, Relation.DEFINITION_REFERENCE)
                edge(graph, reference("DIMENSION_EVENT_BINDING", row.getString("binding_id"), row.getLong("binding_version")), mapping, Relation.DEFINITION_REFERENCE)
                edge(graph, mapping, reference("ANALYSIS_DIMENSION", row.getString("analysis_dimension_id"), row.getLong("analysis_dimension_version")), Relation.CONTEXT_MEMBER)
            }
            GovernanceJdbcSupport.query(database, "SELECT * FROM analysis_dimension_value_provider ORDER BY id") { row ->
                val provider = reference("VALUE_PROVIDER", row.getString("id"), row.getLong("analysis_dimension_version"))
                row.getString("source_table_id")?.let { edge(graph, reference("PHYSICAL_TABLE", it, row.getLong("source_table_version")), provider, Relation.DEFINITION_REFERENCE) }
                row.getString("source_logic_unit_id")?.let { edge(graph, reference("LOGIC_UNIT", it, row.getLong("source_logic_unit_version")), provider, Relation.DEFINITION_REFERENCE) }
                edge(graph, provider, reference("ANALYSIS_DIMENSION", row.getString("analysis_dimension_id"), row.getLong("analysis_dimension_version")), Relation.CONTEXT_MEMBER)
            }
            val scenarios = ScenarioGovernanceRepository(database)
            coordinates("SELECT scenario_id,version FROM scenario_version", "SCENARIO").forEach { target ->
                val actual = snapshots.scenario(target.id, version(target))
                edge(graph, reference("SUBJECT_AREA", actual.subjectAreaId, actual.subjectAreaVersion), target, Relation.CONTEXT_MEMBER)
                scenarios.listScenarioMetricRefs(target.id, version(target)).forEach { ref -> edge(graph, reference("METRIC_ADOPTION", ref.adoptionId, ref.adoptionVersion), target, Relation.SCENARIO_SELECTION) }
                scenarios.listScenarioAnalysisDimensionRefs(target.id, version(target)).forEach { ref -> edge(graph, reference("ANALYSIS_DIMENSION", ref.analysisDimensionId, ref.analysisDimensionVersion), target, Relation.SCENARIO_SELECTION) }
                scenarios.listScenarioFilterRefs(target.id, version(target)).forEach { ref ->
                    edge(graph, reference(ref.filterTargetType, ref.targetId, ref.targetVersion), target, Relation.AUTHORIZATION)
                    ref.valueProviderId?.let { edge(graph, reference("VALUE_PROVIDER", it, ref.targetVersion), target, Relation.AUTHORIZATION) }
                }
                scenarios.memberSourceRefs(target.id, version(target)).forEach { ref -> edge(graph, reference("MEMBER_SOURCE", ref.sourceId, ref.sourceVersion), target, Relation.AUTHORIZATION) }
                scenarios.relationFilters(target.id, version(target)).forEach { ref ->
                    val relation = ref.definition.relation
                    val grant = reference("RELATION_FILTER", relation.id, relation.version)
                    edge(graph, reference("EVENT", relation.parent.eventId, relation.parent.eventVersion), grant, Relation.DEFINITION_REFERENCE)
                    edge(graph, reference("MEMBER_SOURCE", relation.memberSourceId, relation.memberSourceVersion), grant, Relation.DEFINITION_REFERENCE)
                    edge(graph, grant, target, Relation.AUTHORIZATION)
                }
            }
        }
        fun dependencies(target: Reference): List<Reference> = graph.filterValues { steps -> steps.any { it.consumer == target } }.keys
            .sortedWith(compareBy(Reference::assetType, Reference::id, Reference::version))
        fun impact(target: Reference): DefinitionImpact {
            val paths = linkedMapOf<Reference, List<Step>>(target to emptyList())
            val queue = ArrayDeque<Reference>(); queue.addLast(target)
            while (queue.isNotEmpty()) {
                val dependency = queue.removeFirst()
                graph[dependency].orEmpty().sortedWith(stepOrder).forEach { step ->
                    if (step.consumer !in paths) {
                        paths[step.consumer] = paths.getValue(dependency) + step
                        queue.addLast(step.consumer)
                    }
                }
            }
            paths.remove(target)
            return DefinitionImpact(target, paths.map { (ref, path) -> Dependent(ref, path) })
        }
    }
    class Dependencies(val target: Reference, items: List<Reference>, val nextOffset: Int?) {
        val items: List<Reference> = java.util.List.copyOf(items)
        override fun equals(other: Any?): Boolean = other is Dependencies && target == other.target && items == other.items && nextOffset == other.nextOffset
        override fun hashCode(): Int = listOf(target, items, nextOffset).hashCode()
    }
    fun direct(request: DefinitionImpactRequest, offset: Int, limit: Int): Dependencies {
        require(offset >= 0 && limit in 1..1000) { "invalid dependency page" }
        return database.inReadSnapshot {
            val target = reference(request.assetType, request.id, request.version)
            val items = ImpactGraph().also { it.requireTarget(target); it.connectDefinitions(); it.connectContexts() }.dependencies(target)
            Dependencies(target, items.drop(offset).take(limit), if (offset.toLong() + limit < items.size) Math.addExact(offset, limit) else null)
        }
    }
    internal fun directReferences(request: DefinitionImpactRequest): List<Reference> = database.inReadSnapshot {
        val target = reference(request.assetType, request.id, request.version)
        ImpactGraph().also { it.requireTarget(target); it.connectDefinitions(); it.connectContexts() }.dependencies(target)
    }
    private fun sourceReferences(source: MemberSourceRecord, target: Reference, graph: MutableMap<Reference, LinkedHashSet<Step>>, instances: (MetricInstanceRef) -> Reference) {
        when (val origin = source.origin) {
            is MemberDefinitionOrigin.Shared -> edge(graph, reference("MEMBER_DEFINITION", origin.reference.id, origin.reference.version), target, Relation.DEFINITION_REFERENCE)
            is MemberDefinitionOrigin.Local -> visitor(graph, target, Relation.DEFINITION_REFERENCE, instances).member(source.definition)
        }
        visitor(graph, target, Relation.ADOPTION_BINDING, instances).bindings(source.bindings)
    }
    private fun logicUnitReferences(target: Reference, graph: MutableMap<Reference, LinkedHashSet<Step>>, instances: (MetricInstanceRef) -> Reference) {
        val unit = snapshots.logicUnit(target.id, version(target))
        if (unit.sourceKind == "EXPRESSION") visitor(graph, target, Relation.DEFINITION_REFERENCE, instances).expression(SemanticExpressionCodec.parse(requireText(unit.expressionJson, "exact LogicUnit expression"), "exact LogicUnit expression"))
        else refs.listLogicUnitSourceRefs(target.id, version(target)).forEach { edge(graph, reference("PHYSICAL_COLUMN", it.physicalColumnId, it.physicalColumnVersion), target, Relation.DEFINITION_REFERENCE) }
    }
    private fun eventReferences(target: Reference, graph: MutableMap<Reference, LinkedHashSet<Step>>, instances: (MetricInstanceRef) -> Reference) {
        val event = snapshots.event(target.id, version(target))
        edge(graph, reference("TOPOLOGY", event.topologyId, event.topologyVersion), target, Relation.DEFINITION_REFERENCE)
        refs.listEventLogicUnitRefs(target.id, version(target)).forEach { edge(graph, reference("LOGIC_UNIT", it.logicUnitId, it.logicUnitVersion), target, Relation.DEFINITION_REFERENCE) }
        event.eventTimeLogicUnitId?.let { edge(graph, reference("LOGIC_UNIT", it, requireNotNull(event.eventTimeLogicUnitVersion)), target, Relation.DEFINITION_REFERENCE) }
        structural.listEventSliceFilters(target.id, version(target)).forEach { visitor(graph, target, Relation.DEFINITION_REFERENCE, instances).expression(SemanticExpressionCodec.parse(it.filterJson, "exact event filter")) }
    }
    private fun bindingReferences(target: Reference, graph: MutableMap<Reference, LinkedHashSet<Step>>, instances: (MetricInstanceRef) -> Reference) {
        val binding = snapshots.dimensionEventBinding(target.id, version(target))
        edge(graph, reference("LOGIC_UNIT", requireNotNull(binding.logicUnitId), requireNotNull(binding.logicUnitVersion)), target, Relation.DEFINITION_REFERENCE)
        edge(graph, reference("EVENT", binding.eventId, binding.eventVersion), target, Relation.DEFINITION_REFERENCE)
        visitor(graph, target, Relation.DEFINITION_REFERENCE, instances).expression(SemanticExpressionCodec.parse(requireText(binding.expressionJson, "exact dimension binding expression"), "exact dimension binding"))
    }
    private fun visitor(graph: MutableMap<Reference, LinkedHashSet<Step>>, target: Reference, relation: Relation, instances: (MetricInstanceRef) -> Reference): DefinitionReferences = DefinitionReferences(instances) { edge(graph, it, target, relation) }
    private fun foreignEdges(sql: String, dependency: String, consumer: String, graph: MutableMap<Reference, LinkedHashSet<Step>>, relation: Relation = Relation.DEFINITION_REFERENCE) {
        try { GovernanceJdbcSupport.query(database, sql) { rows -> edge(graph, reference(dependency, rows.getString(1), rows.getLong(2)), reference(consumer, rows.getString(3), rows.getLong(4)), relation) } }
        catch (failure: java.sql.SQLException) { throw IllegalStateException("cannot read exact structural references", failure) }
    }
    private fun coordinates(sql: String, kind: String): List<Reference> = try { GovernanceJdbcSupport.query(database, sql) { rows -> reference(kind, rows.getString(1), rows.getLong(2)) } }
        catch (failure: java.sql.SQLException) { throw IllegalStateException("cannot enumerate exact semantic versions: $kind", failure) }
    private fun version(ref: Reference): Long = ref.version.toLong()
    private fun reference(type: String, id: String, version: Long): Reference = Reference(type, id, version.toString())
    private fun edge(graph: MutableMap<Reference, LinkedHashSet<Step>>, dependency: Reference, consumer: Reference, relation: Relation) { graph.getOrPut(dependency) { linkedSetOf() }.add(Step(consumer, relation)) }
}
