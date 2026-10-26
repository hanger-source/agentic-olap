package com.olap.prototype.engine.catalog

import com.olap.prototype.calculation.*
import com.olap.prototype.event.EventRef
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.event.RelationFilter
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.SemanticExpressionInspector
import com.olap.prototype.member.MemberRef
import com.olap.prototype.member.MemberSource
import com.olap.prototype.member.MemberSourceDefinition
import com.olap.prototype.query.RelationClockDiscovery
import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.query.SetOutputContract
import com.olap.prototype.query.SourceJoinContract
import java.util.Collections

/** 可直接计算的完整目录；不解释发布、场景、生命周期或批准范围。 */
class ExecutableCatalog(val manifest: ExecutableCatalogManifest,
    metrics: Map<String, ExecutableMetric> = emptyMap(), metricInstances: Map<String, MetricInstance> = emptyMap(),
    memberSources: Map<String, MemberSource> = emptyMap(), relationFilters: Map<String, RelationFilter> = emptyMap(),
    analysisDimensions: Map<String, ExecutableAnalysisDimension> = emptyMap(), events: Map<String, ExecutableEvent> = emptyMap(),
    topologies: Map<String, ExecutableTopology> = emptyMap(), dimensions: Map<String, ExecutableDimension> = emptyMap(),
    dimensionBindings: Map<String, ExecutableDimensionBinding> = emptyMap(), logicUnits: Map<String, ExecutableLogicUnit> = emptyMap(),
    timeAnchors: Map<String, ExecutableTimeAnchor> = emptyMap(), joinPaths: Map<String, ExecutableJoinPath> = emptyMap(),
    physicalExecutionBindings: Map<String, ExecutablePhysicalExecutionBinding> = emptyMap()) {
    val metrics: Map<String, ExecutableMetric> = Collections.unmodifiableMap(LinkedHashMap(metrics))
    val metricInstances: Map<String, MetricInstance> = Collections.unmodifiableMap(LinkedHashMap(metricInstances))
    val memberSources: Map<String, MemberSource> = Collections.unmodifiableMap(LinkedHashMap(memberSources))
    val relationFilters: Map<String, RelationFilter> = Collections.unmodifiableMap(LinkedHashMap(relationFilters))
    val analysisDimensions: Map<String, ExecutableAnalysisDimension> = Collections.unmodifiableMap(LinkedHashMap(analysisDimensions))
    val events: Map<String, ExecutableEvent> = Collections.unmodifiableMap(LinkedHashMap(events))
    val topologies: Map<String, ExecutableTopology> = Collections.unmodifiableMap(LinkedHashMap(topologies))
    val dimensions: Map<String, ExecutableDimension> = Collections.unmodifiableMap(LinkedHashMap(dimensions))
    val dimensionBindings: Map<String, ExecutableDimensionBinding> = Collections.unmodifiableMap(LinkedHashMap(dimensionBindings))
    val logicUnits: Map<String, ExecutableLogicUnit> = Collections.unmodifiableMap(LinkedHashMap(logicUnits))
    val timeAnchors: Map<String, ExecutableTimeAnchor> = Collections.unmodifiableMap(LinkedHashMap(timeAnchors))
    val joinPaths: Map<String, ExecutableJoinPath> = Collections.unmodifiableMap(LinkedHashMap(joinPaths))
    val physicalExecutionBindings: Map<String, ExecutablePhysicalExecutionBinding> = Collections.unmodifiableMap(LinkedHashMap(physicalExecutionBindings))

    override fun equals(other: Any?): Boolean = other is ExecutableCatalog && manifest == other.manifest &&
        metrics == other.metrics && metricInstances == other.metricInstances && memberSources == other.memberSources &&
        relationFilters == other.relationFilters && analysisDimensions == other.analysisDimensions && events == other.events &&
        topologies == other.topologies && dimensions == other.dimensions && dimensionBindings == other.dimensionBindings &&
        logicUnits == other.logicUnits && timeAnchors == other.timeAnchors && joinPaths == other.joinPaths &&
        physicalExecutionBindings == other.physicalExecutionBindings

    override fun hashCode(): Int = listOf(manifest, metrics, metricInstances, memberSources, relationFilters,
        analysisDimensions, events, topologies, dimensions, dimensionBindings, logicUnits, timeAnchors, joinPaths,
        physicalExecutionBindings).hashCode()

    fun validate() {
        ExecutableCatalogManifest.requireSupportedSchema(manifest.schemaVersion)
        MetricDefinition.text(manifest.catalogId, "catalogId")
        physicalExecutionBindings.forEach { (key, binding) -> requireKey(key, binding.bindingId, binding.bindingVersion); binding.requireComplete() }
        val physicalSources = linkedMapOf<String, com.olap.prototype.contract.NativeTableDependency>()
        physicalExecutionBindings.values.forEach { binding ->
            (binding.nativeTableDependencies.values + com.olap.prototype.contract.NativeTableDependency(binding.dataContract, binding.adoption)).forEach { source ->
                val prior = physicalSources.putIfAbsent(source.key(), source)
                require(prior == null || prior == source) { "one physical data contract has conflicting selected source adoptions: ${source.key()}" }
            }
        }
        requireRelationReferences()
        requireLogicUnits()
        requireEvents()
        requireDimensions()
        requireMembers()
        requireMetricGraph()
        requireRelationFilters()
        relations().requireComplete()
    }
    fun relations(): ExecutableCatalogRelations = ExecutableCatalogRelations(this)

    private fun requireRelationReferences() {
        joinPaths.forEach { (key, path) ->
            requireKey(key, path.joinPathId, path.joinPathVersion)
            requireNotNull(topologies["${path.topologyId}@${path.topologyVersion}"]) { "join topology is missing" }
            SourceJoinContract.require(path.joinType, path.cardinality, path.nullPolicy)
            path.keys.forEach { requireLogicUnit(it.sourceLogicUnitId, it.sourceLogicUnitVersion); requireLogicUnit(it.targetLogicUnitId, it.targetLogicUnitVersion) }
        }
        topologies.forEach { (key, topology) ->
            requireKey(key, topology.topologyId, topology.topologyVersion)
            topology.joinPathRefs.forEach { requireNotNull(joinPaths["${it.joinPathId}@${it.joinPathVersion}"]) { "topology join path is missing" } }
            topology.eventRefs.forEach { requireEvent(it.eventId, it.eventVersion) }
        }
    }
    private fun requireLogicUnits() {
        logicUnits.forEach { (key, unit) ->
            requireKey(key, unit.logicUnitId, unit.logicUnitVersion)
            require((unit.valueType == null) != (unit.valueShape == null)) { "logic unit requires one scalar or container type" }
            unit.valueShape?.let { SetOutputContract.requireSourceContainer(it, listOf("valueShape")) }
            val binding = requirePhysicalBinding(unit.physicalExecutionBindingId, unit.physicalExecutionBindingVersion)
            requireNotNull(binding.logicUnitBindings[key]) { "logic unit source expression is missing" }
        }
    }
    private fun requireEvents() {
        events.forEach { (key, event) ->
            requireKey(key, event.eventId, event.eventVersion)
            val topology = requireNotNull(topologies["${event.topologyId}@${event.topologyVersion}"]) { "event topology is missing" }
            event.identity.recordKey.forEach { ref ->
                val unit = requireLogicUnit(ref.logicUnitId, ref.logicUnitVersion.toString())
                val binding = requirePhysicalBinding(unit.physicalExecutionBindingId, unit.physicalExecutionBindingVersion)
                require(binding.logicalTableId == topology.backboneLogicalTableId && binding.logicalTableVersion == topology.backboneLogicalTableVersion) {
                    "event record key must reference its exact backbone position"
                }
            }
            event.identity.entityRoles.values.flatten().forEach { requireLogicUnit(it.logicUnitId, it.logicUnitVersion.toString()) }
            event.roles.values.forEach(::requirePath)
            event.timeLogicUnitId?.let { requireLogicUnit(it, requireNotNull(event.timeLogicUnitVersion)) }
            event.baseFilter?.let(::requireExpression)
        }
    }
    private fun requireDimensions() {
        dimensionBindings.forEach { (key, binding) ->
            requireKey(key, binding.bindingId, binding.bindingVersion)
            requireEvent(binding.eventId, binding.eventVersion)
            requireNotNull(dimensions["${binding.dimensionId}@${binding.dimensionVersion}"]) { "semantic dimension is missing" }
            requireLogicUnit(binding.logicUnitId, binding.logicUnitVersion)
            requirePhysicalBinding(binding.physicalExecutionBindingId, binding.physicalExecutionBindingVersion)
            requirePath(binding.accessPath); binding.roles.values.forEach(::requirePath); requireExpression(binding.expression)
        }
        val mappingIds = hashSetOf<String>()
        analysisDimensions.forEach { (key, dimension) ->
            requireKey(key, dimension.analysisDimensionId, dimension.analysisDimensionVersion)
            val positions = hashSetOf<List<String>>()
            dimension.eventMappings.forEach { mapping ->
                MetricDefinition.text(mapping.mappingId, "analysis dimension mapping id")
                require(mappingIds.add(mapping.mappingId)) { "analysis dimension mapping identity is duplicated" }
                require(positions.add(listOf(mapping.levelCode, mapping.eventId, mapping.eventVersion))) { "analysis dimension requires one exact mapping per event and level" }
                require(mapping.analysisDimensionId == dimension.analysisDimensionId && mapping.analysisDimensionVersion == dimension.analysisDimensionVersion) { "analysis dimension mapping belongs to another exact definition" }
                requireEvent(mapping.eventId, mapping.eventVersion)
                val binding = requireNotNull(dimensionBindings["${mapping.dimensionBindingId}@${mapping.dimensionBindingVersion}"]) { "analysis dimension binding is missing" }
                require(mapping.eventId == binding.eventId && mapping.eventVersion == binding.eventVersion && mapping.dimensionId == binding.dimensionId && mapping.dimensionVersion == binding.dimensionVersion) {
                    "analysis dimension mapping differs from its exact event and dimension binding"
                }
            }
        }
    }
    private fun requireMembers() {
        memberSources.forEach { (key, source) ->
            requireKey(key, source.id, source.version.toString())
            when (source) {
                is MemberSource.Projection -> { requireEvent(source.definition.event.eventId, source.definition.event.eventVersion.toString()); source.outputExpressions.values.forEach(::requireExpression) }
                is MemberSource.Computed -> { requireBindings(source.definition.calculation, source.bindings); source.remainingParameters() }
            }
            source.definition.result.outputs.forEach { (slot, output) -> when (output) {
                is MemberSourceDefinition.DimensionOutput -> memberDimensionMappings(source, slot)
                is MemberSourceDefinition.PropertyOutput -> {
                    val unit = requireLogicUnit(output.logicUnitId, output.logicUnitVersion.toString())
                    output.requireValueType(requireNotNull(unit.valueType) { "member scalar property requires an exact scalar LogicUnit type" }); requirePath(output.accessPath)
                }
                is MemberSourceDefinition.ContainerPropertyOutput -> {
                    val unit = requireLogicUnit(output.logicUnitId, output.logicUnitVersion.toString())
                    require(output.shape == unit.valueShape) { "member container differs from its exact LogicUnit shape" }
                    requirePath(output.accessPath)
                }
                else -> Unit
            } }
        }
    }
    private fun requireMetricGraph() {
        metrics.forEach { (key, metric) -> require(key == metric.reference.key()) { "metric definition index differs from its exact reference" }; requireMetricDefinition(metric) }
        MetricSource.definitions(metrics.values.map { it.reference }, { requireMetric(it).definition }, ::requireMetricInstance, ::requireMemberSource)
        metricInstanceClosure(metricInstances.keys.map(::MetricInstanceRef))
        metricInstances.forEach { (id, instance) ->
            MetricInstanceRef(id)
            val metric = requireMetric(instance.definition)
            requireBindings(metric.definition, instance.bindings)
            instance.remainingParameters(metric.definition)
            MetricSource.resolve(instance.definition, instance.bindings, { requireMetric(it).definition }, ::requireMetricInstance,
                { sourceId, version -> requireNotNull(memberSources["$sourceId@$version"]) { "source member is missing" } })
        }
        memberSources.values.forEach { source -> MetricSource.closure(emptyList(), listOf(MemberRef(source.id, source.version)),
            { requireMetric(it).definition }, ::requireMetricInstance, ::requireMemberSource) }
    }
    private fun requireRelationFilters() {
        relationFilters.forEach { (key, relation) ->
            requireKey(key, relation.id, relation.version.toString())
            val parent = requireEvent(relation.parent.eventId, relation.parent.eventVersion.toString())
            val source = requireNotNull(memberSources["${relation.memberSourceId}@${relation.memberSourceVersion}"]) { "related member source is missing" }
            require(source is MemberSource.Projection) { "event relationship filters require an actual projected member source" }
            val discovery = RelationClockDiscovery { ref -> requireNotNull(joinPaths["${ref.joinPathId}@${ref.joinPathVersion}"]) { "relation path is missing" }.temporalConstraint }
            discovery.path("relation:${relation.id}", emptyList(), requireNotNull(parent.roles[relation.relationshipRole]) { "relation parent role is missing" })
            val clocks = RelationClockDiscovery.relationSlots(source.definition.result.clockSlots, discovery.slots().keys)
            require(relation.clockSlots.all { (name, position) -> clocks[name] == position }) { "relation clock differs from its exact source position" }
        }
    }

    fun requireBindings(definition: MetricDefinition, bindings: CalculationBindings) = bindings.require(definition, ::requireDimensionBinding,
        ::requireMemberSource, ::memberDimensionMapping, { requireMetric(it).definition }, ::requireMetricInstance)
    fun requireBindings(calculation: Calculation, bindings: CalculationBindings, localInputs: Calculation.Scope = calculation.computation) = bindings.require(calculation, ::requireDimensionBinding,
        ::requireMemberSource, ::memberDimensionMapping, { requireMetric(it).definition }, ::requireMetricInstance, localInputs)
    fun requireBoundBindings(definition: MetricDefinition, bindings: CalculationBindings) = bindings.requireBound(definition, ::requireDimensionBinding,
        ::requireMemberSource, ::memberDimensionMapping, { requireMetric(it).definition }, ::requireMetricInstance)
    fun requireBoundBindings(calculation: Calculation, bindings: CalculationBindings) = bindings.requireBound(calculation, ::requireDimensionBinding,
        ::requireMemberSource, ::memberDimensionMapping, { requireMetric(it).definition }, ::requireMetricInstance)
    private fun requireDimensionBinding(dimension: CalculationBindings.Dimension, constraints: List<Calculation.DimensionSlot>) {
        dimensionMappings(dimension).forEach { dimension.requireMeaning(it.dimensionId, it.dimensionVersion, constraints) }
    }

    /** 明确采用选集，不让目录中其他可用映射扩展实例。 */
    fun dimensionMappings(binding: CalculationBindings.Dimension): List<ExecutableAnalysisDimensionEventMapping> {
        val reference = binding.reference
        requireDimensionReference(reference)
        val available = linkedMapOf<String, ExecutableAnalysisDimensionEventMapping>()
        analysisDimensions.getValue("${reference.id}@${reference.version}").eventMappings.forEach {
            MetricDefinition.text(it.mappingId, "analysis dimension mapping id")
            require(available.putIfAbsent(it.mappingId, it) == null) { "analysis dimension mapping identity is duplicated" }
        }
        return binding.mappingIds.map { id ->
            requireNotNull(available[id]) { "bound exact dimension mapping is missing" }.also {
                require(reference.levelCode == it.levelCode && reference.id == it.analysisDimensionId && reference.version == it.analysisDimensionVersion) { "bound dimension mapping differs from its exact semantic reference" }
            }
        }
    }
    fun memberDimensionMapping(source: MemberSource, dimension: CalculationBindings.Dimension, output: MemberSourceDefinition.Output): Boolean {
        if (source is MemberSource.Computed && output is MemberSourceDefinition.ComputedDimensionOutput) {
            val actual = requireNotNull(source.bindings.dimensions[output.dimensionSlot]) { "computed member dimension is not bound" }
            return actual.reference == dimension.reference && actual.role == dimension.role
        }
        return source is MemberSource.Projection && output is MemberSourceDefinition.DimensionOutput && dimensionMappings(dimension).any { matchesMemberMapping(source.definition, output, it) }
    }
    fun requireDimensionEvent(dimension: CalculationBindings.Dimension, event: EventRef) {
        require(dimensionMappings(dimension).any { it.eventId == event.eventId && it.eventVersion == event.eventVersion.toString() }) { "bound dimension has no selected mapping for the actual fact event" }
    }
    fun memberDimensionMappings(source: MemberSource, outputSlot: String): List<ScopedInputContract.DimensionRef> {
        val output = requireNotNull(source.definition.result.outputs[outputSlot]) { "member output slot is missing" }
        if (source is MemberSource.Computed && output is MemberSourceDefinition.ComputedDimensionOutput)
            return listOf(requireNotNull(source.bindings.dimensions[output.dimensionSlot]) { "computed member dimension is not bound" }.reference)
        if (output !is MemberSourceDefinition.DimensionOutput) return emptyList()
        val projection = source as MemberSource.Projection
        val binding = requireNotNull(dimensionBindings["${output.bindingId}@${output.bindingVersion}"]) { "member dimension binding is missing" }
        val event = projection.definition.event
        require(binding.eventId == event.eventId && binding.eventVersion == event.eventVersion.toString() && binding.dimensionId == output.dimensionId &&
            binding.dimensionVersion == output.dimensionVersion.toString() && binding.expression == projection.outputExpressions[outputSlot]) { "member output differs from its exact semantic dimension expression binding" }
        val result = linkedSetOf<ScopedInputContract.DimensionRef>()
        analysisDimensions.values.forEach { analysis -> analysis.eventMappings.forEach { mapping ->
            if (matchesMemberMapping(projection.definition, output, mapping)) require(result.add(ScopedInputContract.DimensionRef(analysis.analysisDimensionId, analysis.analysisDimensionVersion, mapping.levelCode))) { "member dimension requires one exact mapping per semantic reference" }
        } }
        return result.toList()
    }
    private fun matchesMemberMapping(source: MemberSourceDefinition.Projection, output: MemberSourceDefinition.DimensionOutput, mapping: ExecutableAnalysisDimensionEventMapping): Boolean =
        mapping.eventId == source.event.eventId && mapping.eventVersion == source.event.eventVersion.toString() && mapping.dimensionBindingId == output.bindingId && mapping.dimensionBindingVersion == output.bindingVersion.toString()

    private fun requireMetricDefinition(metric: ExecutableMetric) {
        val fact = metric.definition as? MetricDefinition.FactMeasure ?: return
        val event = requireEvent(fact.event.eventId, fact.event.eventVersion.toString())
        require(event.identity.recordKey.isNotEmpty()) { "direct fact measures require an explicitly declared event record identity" }
        requireExpression(fact.input); fact.privatePredicate?.let(::requireExpression)
        fact.snapshotRule?.expressions()?.forEach(::requireExpression); fact.roles.values.forEach(::requirePath)
    }
    fun requireMetric(reference: MetricRef): ExecutableMetric = requireNotNull(metrics[reference.key()]) { "exact metric definition is missing" }.also {
        require(reference == it.reference) { "metric definition differs from its exact reference" }
    }
    fun requireMetricInstance(reference: MetricInstanceRef): MetricInstance = requireNotNull(metricInstances[reference.id]) { "exact metric instance is missing" }
    fun requireMemberSource(reference: MemberRef): MemberSource = requireNotNull(memberSources["${reference.id}@${reference.version}"]) { "exact member source is missing" }.also {
        require(MemberRef(it.id, it.version) == reference) { "member source differs from its exact reference" }
    }
    fun metricInstanceClosure(roots: Collection<MetricInstanceRef>): Map<String, MetricInstance> {
        val result = linkedMapOf<String, MetricInstance>()
        MetricSource.closure(roots, emptyList(), { requireMetric(it).definition }, ::requireMetricInstance, ::requireMemberSource).dependencies.forEach {
            if (it is MetricSource.InstanceDependency) result[it.reference.id] = requireMetricInstance(it.reference)
        }
        return Collections.unmodifiableMap(result)
    }
    private fun requireExpression(expression: SemanticExpression) {
        SemanticExpressionInspector.references(expression).forEach {
            require(it.referenceKind != SemanticExpression.ReferenceKind.PHYSICAL_COLUMN) { "semantic definitions require logical references" }
            requireLogicUnit(it.id, it.version.toString())
        }
        SemanticExpressionInspector.quantifiers(expression).forEach { requirePath(it.expression.accessPath) }
    }
    private fun requirePath(path: RelationAccessPath) { path.joinPathRefs.forEach { requireNotNull(joinPaths["${it.joinPathId}@${it.joinPathVersion}"]) { "exact relation path is missing" } } }
    private fun requireEvent(id: String, version: String): ExecutableEvent = requireNotNull(events["$id@$version"]) { "exact event is missing" }
    private fun requireDimensionReference(reference: ScopedInputContract.DimensionRef) {
        val dimension = requireNotNull(analysisDimensions["${reference.id}@${reference.version}"]) { "bound semantic dimension is missing" }
        require(dimension.levels.any { it.levelCode == reference.levelCode }) { "bound semantic dimension level is missing" }
    }
    private fun requireLogicUnit(id: String, version: String): ExecutableLogicUnit = requireNotNull(logicUnits["$id@$version"]) { "exact logic unit is missing" }
    private fun requirePhysicalBinding(id: String, version: String): ExecutablePhysicalExecutionBinding = requireNotNull(physicalExecutionBindings["$id@$version"]) { "exact physical execution binding is missing" }
    private fun requireKey(key: String, id: String, version: String) {
        MetricDefinition.text(key, "catalog index"); MetricDefinition.text(id, "definition id"); MetricDefinition.text(version, "definition version")
        require(key == "$id@$version") { "catalog index differs from its exact definition identity" }
    }
}
