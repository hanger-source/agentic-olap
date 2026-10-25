package com.olap.prototype.governance.application

import com.olap.prototype.calculation.*
import com.olap.prototype.contract.NativeTableDependency
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.event.EventRef
import com.olap.prototype.event.PublishedDimensionBinding
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.SemanticExpressionCodec
import com.olap.prototype.expression.SemanticExpressionInspector
import com.olap.prototype.governance.contract.*
import com.olap.prototype.management.governance.*
import com.olap.prototype.management.governance.CanonicalReleaseRepository.*
import com.olap.prototype.member.MemberRef
import com.olap.prototype.member.MemberSource
import com.olap.prototype.member.MemberSourceDefinition
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.contract.*
import com.olap.prototype.release.contract.*
import com.olap.prototype.util.OlapJsonUtils
import com.olap.prototype.warehouse.contract.*
import java.time.Instant

/** 解析本次发布的精确依赖；缓存和待封存行都只属于这一次调用。 */
class ReleaseFreezer(private val physicalAssets: PhysicalAssetGovernanceService, private val nativeExpressions: NativeExpressionBindings,
    private val logicalModel: LogicalModelGovernanceService, private val topologies: TopologyGovernanceService, private val events: EventGovernanceService,
    private val dimensions: DimensionGovernanceService, private val metrics: MetricGovernanceService, private val subjectAreas: SubjectAreaGovernanceService,
    private val adoptions: SubjectAreaAdoptionService, private val analysisDimensions: AnalysisDimensionGovernanceService,
    private val memberDefinitions: MemberDefinitionGovernanceService, private val scenarios: ScenarioGovernanceService, private val diagnostics: ReleaseCatalogRepository) {
    fun freeze(request: SubjectAreaReleaseBuildRequest): ReleaseFreeze = Freezing(request).freeze()

    private inner class Freezing(request: SubjectAreaReleaseBuildRequest) {
        private val releaseId = required(request.releaseId, "subject_area_release.release_id")
        private val releaseVersion = required(request.releaseVersion, "subject_area_release.release_version")
        private val releaseDisplayName = required(request.releaseDisplayName, "subject_area_release.release_display_name")
        private val subjectAreaId = required(request.subjectAreaId, "subject_area_release.subject_area_id")
        private val subjectAreaVersion = ProducerContracts.requireVersion(request.subjectAreaVersion, "subject_area_release.subject_area_version")
        private val createdBy = required(request.createdBy, "subject_area_release.created_by")
        private val createdAt = Instant.now()
        private val sourceAdoptionRefs = request.sourceAdoptions
        private val sourceAdoptionCandidates = sourceAdoptionRefs.distinct().map(physicalAssets::sourceAdoption).groupBy { it.physicalTable }
        private val frozenSourceAdoptions = linkedMapOf<PhysicalTableRef, PhysicalSourceAdoption>()
        private val release = SubjectAreaReleaseRecord(releaseObjectId("sar", releaseId, releaseVersion), releaseId, releaseDisplayName, subjectAreaId, releaseVersion, subjectAreaVersion, "BUILT", createdBy, createdAt)
        private val subject = subjectAreas.subjectAreaSnapshot(subjectAreaId, subjectAreaVersion)
        private val subjectMetrics = adoptions.listSubjectAreaMetricRefs(subjectAreaId, subjectAreaVersion)
        private val subjectDimensions = analysisDimensions.listSubjectAreaAnalysisDimensionRefs(subjectAreaId, subjectAreaVersion)
        private val subjectScenarios = subjectAreas.listSubjectAreaScenarioRefs(subjectAreaId, subjectAreaVersion)

        private val frozenMetrics = linkedMapOf<VersionedId, MetricRecord>()
        private val frozenInstances = linkedMapOf<MetricInstanceRef, MetricInstance>()
        private val frozenEvents = linkedMapOf<VersionedId, EventRecord>()
        private val frozenTopologies = linkedMapOf<VersionedId, TopologyRecord>()
        private val frozenUnits = linkedMapOf<VersionedId, LogicUnitRecord>()
        private val frozenMembers = linkedMapOf<VersionedId, MemberSource>()
        private val scenarioDiagnostics = linkedMapOf<QueryScenarioRef, List<ReleaseDiagnosticIssue>>()
        private val objects = mutableListOf<ReleaseObjectRefRecord>()
        private val capabilities = mutableListOf<ReleaseMetricDimensionCapabilityRecord>()
        private val unavailable = mutableListOf<ReleaseUnavailableReasonRecord>()
        private val mappings = mutableListOf<ReleaseDimensionMappingRecord>()
        private val timeAnchors = mutableListOf<ReleaseTimeAnchorRuleRecord>()
        private val dependencyPaths = linkedMapOf<ObjectRef, MutableSet<List<String>>>()
        private var currentPaths = listOf(listOf("SUBJECT_AREA:$subjectAreaId@$subjectAreaVersion"))
        private val references = FrozenReferences()
        private val mappingSelections = MappingSelections()
        private val dimensionMappings = ReleaseDimensionMappings(analysisDimensions, dimensions, events, topologies, logicalModel)

        init {
            if (subject.status != "ACTIVE") throw ProducerContractException("publication requires active authorization context: SUBJECT_AREA#$subjectAreaId#$subjectAreaVersion status=${subject.status}")
            requireNotEmpty(subjectScenarios, "subject_area_release.scenarios")
        }
        fun freeze(): ReleaseFreeze {
            references.freezeObject("SUBJECT_AREA", subject.id, subjectAreaVersion, subject.code, subject.displayName, "SUBJECT_AREA")
            subjectDependencies()
            scenarioCapabilities()
            return ReleaseFreeze(release, objects, capabilities, unavailable, mappings, timeAnchors, scenarioDiagnostics)
        }
        private fun subjectDependencies() {
            val closure = MetricSource.closure(subjectMetrics.map { it.instance },
                adoptions.memberSources(subjectAreaId, subjectAreaVersion).map { MemberRef(it.id, it.subjectAreaVersion) },
                { metrics.metricSnapshot(it.id, it.version.toLong()).definition }, { adoptions.metricInstance(it).computation }, adoptions::memberComputation,
                onDependency = { dependency, path ->
                    val key = when (dependency) {
                        is MetricSource.MetricDependency -> ObjectRef(ReleaseObjectKind.METRIC, dependency.reference.id, dependency.reference.version.toLong())
                        is MetricSource.MemberDependency -> ObjectRef(ReleaseObjectKind.MEMBER_SOURCE, dependency.reference.id, dependency.reference.version)
                        is MetricSource.InstanceDependency -> adoptions.metricInstanceReference(dependency.reference).let { ObjectRef(ReleaseObjectKind.METRIC_ADOPTION, it.id, it.subjectAreaVersion) }
                    }
                    dependencyPaths.getOrPut(key) { linkedSetOf() }.add(currentPaths.single() + path)
                })
            closure.dependencies.forEach { dependency -> when (dependency) {
                is MetricSource.MetricDependency -> freezeMetric(dependency.reference.id, dependency.reference.version.toLong())
                is MetricSource.MemberDependency -> freezeMember(dependency.reference)
                is MetricSource.InstanceDependency -> {
                    val adoption = adoptions.metricInstance(dependency.reference)
                    if (adoption.subjectAreaId != subjectAreaId || adoption.subjectAreaVersion != subjectAreaVersion)
                        throw ProducerContractException("measurement dependency belongs to another subject-area version")
                    freezeAdoption(adoption)
                }
            } }
            subjectDimensions.forEach { dimension ->
                freezeAnalysisDimension(dimension.analysisDimensionId, dimension.analysisDimensionVersion)
                analysisDimensions.listAnalysisDimensionEventMappings(dimension.analysisDimensionId, dimension.analysisDimensionVersion).forEach {
                    freezeMappingObjects(it); references.freezeMapping(it)
                }
            }
        }
        private fun scenarioCapabilities() {
            val frozenObjects = objects.toList()
            val freezer = ReleaseScenarioFreezer(release, scenarios, analysisDimensions, diagnostics)
            subjectScenarios.forEach { reference ->
                val frozen = freezer.freeze(reference, subjectMetrics, subjectDimensions, frozenObjects)
                freezeScenario(frozen)
                buildCapabilities(frozen.selection)
            }
        }
        private fun freezeAdoption(adoption: MetricAdoption) = tracing("METRIC_ADOPTION", adoption.adoptionId, adoption.subjectAreaVersion) { freezeAdoptionBody(adoption) }
        private fun freezeAdoptionBody(adoption: MetricAdoption) {
            if (adoption.instance in frozenInstances) return
            adoptions.validateMetricAdoption(adoptions.metricInstanceReference(adoption.instance), adoption.bindings, adoption.fixedArguments)
            freezeBindingDimensions(adoption.bindings)
            references.freezeObject("METRIC_ADOPTION", adoption.adoptionId, adoption.subjectAreaVersion, adoption.code, adoption.displayName, "METRIC_ADOPTION", encodeSnapshot(adoption, "metric adoption snapshot"))
            frozenInstances[adoption.instance] = adoption.computation
        }
        private fun freezeMember(reference: MemberRef) = tracing("MEMBER_SOURCE", reference.id, reference.version) { freezeMemberBody(reference) }
        private fun freezeMemberBody(reference: MemberRef) {
            val key = VersionedId(reference.id, reference.version)
            if (key in frozenMembers) return
            val member = adoptions.memberSource(reference.id, reference.version)
            if (member.subjectAreaId != subjectAreaId || member.subjectAreaVersion != subjectAreaVersion)
                throw ProducerContractException("member dependency belongs to another subject-area version")
            adoptions.validateMemberSource(member)
            (member.origin as? MemberDefinitionOrigin.Shared)?.reference?.let { ref ->
                val definition = memberDefinitions.read(ref.id, ref.version)
                references.freezeObject("MEMBER_DEFINITION", ref.id, ref.version, definition.code, definition.displayName, "MEMBER_DEFINITION", encodeSnapshot(definition, "member definition snapshot"))
            }
            val source = adoptions.memberComputation(reference)
            when (source) {
                is MemberSource.Projection -> {
                    source.outputExpressions.values.forEach(::freezeNativeTables)
                    val event = freezeEvent(source.definition.event.eventId, source.definition.event.eventVersion)
                    freezeTopology(event.topologyId, event.topologyVersion)
                    source.definition.result.outputs.values.filterIsInstance<MemberSourceDefinition.DimensionOutput>().forEach { freezeDimensionBinding(it.bindingId, it.bindingVersion) }
                    source.outputExpressions.values.forEach { expression -> freezeSemanticUnits(expression, "member output must use exact semantic LogicUnits") }
                }
                is MemberSource.Computed -> freezeBindingDimensions(source.bindings)
            }
            frozenMembers[key] = source
            references.freezeObject("MEMBER_SOURCE", member.id, member.subjectAreaVersion, member.code, member.displayName, "MEMBER_SOURCE",
                encodeSnapshot(PublishedMemberSource(source, subjectAreaId, member.code, member.displayName), "member source snapshot"))
        }
        private fun freezeBindingDimensions(bindings: CalculationBindings) {
            val values = bindings.dimensions.values + bindings.groupingCandidates.values.flatten()
            values.forEach { value ->
                val ref = value.reference
                val version = ref.version.toLong()
                freezeAnalysisDimension(ref.id, version)
                val selected = analysisDimensions.listAnalysisDimensionEventMappings(ref.id, version).filter { it.levelCode == ref.levelCode && it.id in value.mappingIds }
                if (selected.size != value.mappingIds.size) throw ProducerContractException("bound analysis dimension mapping is absent from its exact source definition")
                selected.forEach { freezeMappingObjects(it); references.freezeMapping(it) }
            }
        }
        private fun buildCapabilities(selection: ReleaseScenarioSnapshot) {
            val levels = selection.dimensions.associateWith { grant ->
                analysisDimensions.listAnalysisDimensionLevels(grant.id, grant.version).also { requireNotEmpty(it, "analysis_dimension_level") }
            }
            selection.metrics.forEach { grant ->
                val instance = MetricAdoption.instance(grant.adoptionId, grant.adoptionVersion)
                val computation = requireInstance(instance)
                val metric = requireMetric(computation.definition)
                val sources = MetricSource.resolve(computation.definition, computation.bindings, { requireMetric(it).definition }, ::requireInstance,
                    { id, version -> requireNotNull(frozenMembers[VersionedId(id, version)]) { "adopted member source was not frozen" } })
                sources.map { it.event }.distinct().forEach { ref ->
                    val event = freezeEvent(ref.eventId, ref.eventVersion)
                    freezeTopology(event.topologyId, event.topologyVersion)
                    selection.dimensions.forEach { dimension -> levels.getValue(dimension).forEach { level ->
                        if (mappingSelections.usesSource(instance, metric.definition, dimension, level, ref)) buildCapability(selection.scenario, grant, event, dimension, level)
                    } }
                }
            }
        }
        private fun buildCapability(scenario: QueryScenarioRef, adoption: ScenarioMetricReference, event: EventRecord,
            dimension: ReleaseScenarioSnapshot.DimensionGrant, level: AnalysisDimensionLevelRecord) {
            val instance = MetricAdoption.instance(adoption.adoptionId, adoption.adoptionVersion)
            val definition = requireInstance(instance).definition
            val metric = requireMetric(definition)
            val metricVersion = definition.version.toLong()
            val selected = mappingSelections.findMappings(dimension.id, dimension.version, level.levelCode, event.id, event.currentVersion, instance)
            val available = selected.isNotEmpty()
            val reasonCode = if (available) null else "ANALYSIS_DIMENSION_EVENT_MAPPING_MISSING"
            val reason = if (available) null else "精确采用在此来源事件和层级下没有获选的维度映射，无法进入可执行目录"
            val target = "adoption:${adoption.adoptionId};analysis-dimension:${dimension.id}"
            val targetVersion = "adoption:${adoption.adoptionId}@${adoption.adoptionVersion};metric:${metric.id}@v$metricVersion|event:${event.id}@v${event.currentVersion}|analysisDimension:${dimension.id}@v${dimension.version}|level:${level.levelCode}"
            val identity = arrayOf<Any>(releaseId, releaseVersion, scenario.scenarioId, scenario.scenarioVersion, adoption.adoptionId, adoption.adoptionVersion,
                dimension.id, dimension.version, level.levelCode, event.id, event.currentVersion)
            capabilities.add(ReleaseMetricDimensionCapabilityRecord(releaseObjectId("rmdc", *identity), releaseId, releaseVersion, subjectAreaId, scenario.scenarioId,
                scenario.scenarioVersion, adoption.adoptionId, adoption.adoptionVersion, metric.id, metricVersion, event.id, event.currentVersion,
                dimension.id, dimension.version, level.levelCode, available, reasonCode, reason, PRODUCER_STAGE, target, targetVersion, !available, !available, !available, createdAt))
            if (!available) unavailable.add(ReleaseUnavailableReasonRecord(releaseObjectId("rur", *identity), releaseId, releaseVersion, "CAPABILITY", subjectAreaId,
                scenario.scenarioId, scenario.scenarioVersion, metric.id, metricVersion, dimension.id, dimension.version, level.levelCode,
                requireNotNull(reasonCode), requireNotNull(reason), PRODUCER_STAGE, target, targetVersion, true, true, true, createdAt))
            else selected.forEach(::freezeMappingObjects)
        }
        private fun requireInstance(ref: MetricInstanceRef): MetricInstance = requireNotNull(frozenInstances[ref]) { "adopted measurement instance was not frozen" }
        private fun requireMetric(ref: MetricRef): MetricRecord = requireNotNull(frozenMetrics[VersionedId(ref.id, ref.version.toLong())]) { "metric is outside the frozen subject-area dependency closure" }
        private fun freezeMetric(id: String, version: Long): MetricRecord = tracing("METRIC", id, version) { freezeMetricBody(id, version) }
        private fun freezeMetricBody(id: String, version: Long): MetricRecord {
            val key = VersionedId(id, version)
            frozenMetrics[key]?.let { return it }
            val metric = metrics.metricSnapshot(id, version)
            referenceable(metric.status, "METRIC", id, version)
            when (val definition = metric.definition) {
                is MetricDefinition.FactMeasure -> {
                    metrics.requireFactRecordKey(definition.event)
                    val event = freezeEvent(definition.event.eventId, definition.event.eventVersion)
                    freezeTopology(event.topologyId, event.topologyVersion)
                    freezeExpressionDependencies(definition.input)
                    definition.privatePredicate?.let(::freezeExpressionDependencies)
                    definition.snapshotRule?.expressions()?.forEach(::freezeExpressionDependencies)
                    (definition.valueContract.unit as? MetricValueContract.EventDimension)?.let { freezeDimensionBinding(it.bindingId, it.bindingVersion) }
                }
                is MetricCalculation -> definition.calculation.declaredDimensions().values.forEach { slot ->
                    val dimension = dimensions.dimensionSnapshot(slot.dimensionId, slot.dimensionVersion)
                    referenceable(dimension.status, "DIMENSION", dimension.id, slot.dimensionVersion)
                    references.freezeObject("DIMENSION", dimension.id, slot.dimensionVersion, dimension.code, dimension.displayName, "DIMENSION")
                }
            }
            val execution = MetricExecutionSnapshot(metric.definition, metrics.metricBusinessMeaning(id, version))
            references.freezeObject("METRIC", metric.id, version, metric.code, metric.displayName, "METRIC", encodeSnapshot(execution, "metric.snapshot_proto"))
            frozenMetrics[key] = metric
            return metric
        }
        private fun freezeExpressionDependencies(expression: SemanticExpression) {
            freezeNativeTables(expression)
            freezeSemanticUnits(expression, "published input cannot bypass semantic LogicUnits")
        }
        private fun freezeSemanticUnits(expression: SemanticExpression, message: String) {
            SemanticExpressionInspector.references(expression).forEach { reference ->
                if (reference.referenceKind == SemanticExpression.ReferenceKind.PHYSICAL_COLUMN) throw ProducerContractException(message)
                freezeLogicUnit(reference.id, reference.version)
            }
        }
        private fun freezeAnalysisDimension(id: String, version: Long): AnalysisDimensionRecord = tracing("ANALYSIS_DIMENSION", id, version) { freezeAnalysisDimensionBody(id, version) }
        private fun freezeAnalysisDimensionBody(id: String, version: Long): AnalysisDimensionRecord {
            val dimension = analysisDimensions.analysisDimensionSnapshot(required(id, "analysis_dimension.id"), version)
            referenceable(dimension.status, "ANALYSIS_DIMENSION", dimension.id, version)
            val meaning = FrozenAnalysisDimensionMeaning(dimension.description, analysisDimensions.listAnalysisDimensionValueAlignments(dimension.id, version))
            references.freezeObject("ANALYSIS_DIMENSION", dimension.id, version, dimension.code, dimension.displayName, "ANALYSIS_DIMENSION", encodeSnapshot(meaning, "analysis dimension meaning and exact alignment evidence"))
            return dimension
        }
        private fun freezeScenario(frozen: ReleaseScenarioFreezer.FrozenScenario) = tracing("SCENARIO", frozen.definition.id, frozen.definition.currentVersion) { freezeScenarioBody(frozen) }
        private fun freezeScenarioBody(frozen: ReleaseScenarioFreezer.FrozenScenario) {
            val scenario = frozen.definition
            references.freezeObject("SCENARIO", scenario.id, scenario.currentVersion, scenario.code, scenario.displayName, "SCENARIO", encodeSnapshot(frozen.selection, "complete scenario selection"))
            frozen.relationFilters.forEach { reference ->
                val filter = reference.definition
                val event = freezeEvent(filter.relation.parent.eventId, filter.relation.parent.eventVersion)
                freezeTopology(event.topologyId, event.topologyVersion)
                references.freezeObject("RELATION_FILTER", filter.relation.id, filter.relation.version, reference.code, filter.displayName, "RELATION_FILTER", encodeSnapshot(filter, "relation filter snapshot"))
            }
            scenarioDiagnostics[frozen.selection.scenario] = frozen.diagnostics
        }
        private fun freezeEvent(id: String, version: Long): EventRecord = tracing("EVENT", id, version) { freezeEventBody(id, version) }
        private fun freezeEventBody(id: String, version: Long): EventRecord {
            val key = VersionedId(id, version)
            frozenEvents[key]?.let { return it }
            val event = events.eventSnapshot(required(id, "event.id"), version)
            referenceable(event.status, "EVENT", event.id, version)
            val units = events.listEventLogicUnitRefs(event.id, version)
            requireNotEmpty(units, "event_logic_unit_ref")
            units.forEach { freezeLogicUnit(it.logicUnitId, it.logicUnitVersion) }
            val predicates = events.listEventSliceFilters(event.id, version).map { it.predicate }
            predicates.forEach(::freezeNativeTables)
            val execution = EventExecutionSnapshot(required(event.topologyId, "event.snapshot.topology_id"), event.topologyVersion, event.eventTimeLogicUnitId,
                event.eventTimeLogicUnitVersion, events.eventIdentity(id, version), events.eventRoles(id, version),
                predicates.takeIf { it.isNotEmpty() }?.let { SemanticExpressionCodec.and(it) })
            references.freezeObject("EVENT", event.id, version, event.code, event.displayName, "EVENT", encodeSnapshot(execution, "event.snapshot_proto"))
            frozenEvents[key] = event
            return event
        }
        private fun freezeTopology(id: String, version: Long): TopologyRecord = tracing("TOPOLOGY", id, version) { freezeTopologyBody(id, version) }
        private fun freezeTopologyBody(id: String, version: Long): TopologyRecord {
            val key = VersionedId(id, version)
            frozenTopologies[key]?.let { return it }
            val topology = topologies.topologySnapshot(required(id, "topology.id"), version)
            referenceable(topology.status, "TOPOLOGY", topology.id, version)
            val backbones = topologies.listTopologyLogicalTableRefs(topology.id, version).filter { it.usageRole == "BACKBONE_FACT" }
            if (backbones.size != 1) throw ProducerContractException("topology release snapshot requires exactly one BACKBONE_FACT: ${topology.id}@$version, matches=${backbones.size}")
            val paths = topologies.listTopologyJoinPathRefs(topology.id, version).map {
                freezeJoinPath(it.joinPathId, it.joinPathVersion, topology.id, version); ReleaseJoinPathRef(it.joinPathId, it.joinPathVersion)
            }
            val backbone = backbones.single()
            val execution = TopologyExecutionSnapshot(topology.id, version, topology.topologyImplementationNamespaceCode, topology.topologyImplementationNamespaceDisplayName,
                backbone.logicalTableId, backbone.logicalTableVersion, paths)
            references.freezeObject("TOPOLOGY", topology.id, version, topology.code, topology.displayName, "TOPOLOGY", encodeSnapshot(execution, "topology executable snapshot"))
            frozenTopologies[key] = topology
            return topology
        }
        private fun freezeJoinPath(id: String, version: Long, topologyId: String, topologyVersion: Long) = tracing("JOIN_PATH", id, version) { freezeJoinPathBody(id, version, topologyId, topologyVersion) }
        private fun freezeJoinPathBody(id: String, version: Long, topologyId: String, topologyVersion: Long) {
            val path = topologies.joinPathSnapshot(id, version)
            referenceable(path.status, "JOIN_PATH", path.id, version)
            if (path.topologyId != topologyId) throw ProducerContractException("join_path.topology_id must match topology.id")
            if (path.topologyVersion != topologyVersion) throw ProducerContractException("join_path.topology_version must match topology.version")
            val refs = topologies.listJoinPathKeyRefs(id, version)
            requireNotEmpty(refs, "join_path_key_ref")
            val keys = refs.map {
                freezeLogicUnit(it.sourceLogicUnitId, it.sourceLogicUnitVersion); freezeLogicUnit(it.targetLogicUnitId, it.targetLogicUnitVersion)
                ReleaseJoinPathSnapshot.Key(it.keyOrder, it.sourceLogicUnitId, it.sourceLogicUnitVersion, it.targetLogicUnitId, it.targetLogicUnitVersion)
            }
            val snapshot = ReleaseJoinPathSnapshot(path.id, version, path.topologyId, path.topologyVersion, path.sourceLogicalTableId, path.sourceLogicalTableVersion,
                path.targetLogicalTableId, path.targetLogicalTableVersion, path.joinType, path.cardinality, path.temporal, required(path.nullPolicy, "join_path.null_policy"), keys)
            val source = logicalModel.logicalTableSnapshot(path.sourceLogicalTableId, path.sourceLogicalTableVersion)
            val target = logicalModel.logicalTableSnapshot(path.targetLogicalTableId, path.targetLogicalTableVersion)
            referenceable(source.status, "LOGICAL_TABLE", source.id, path.sourceLogicalTableVersion)
            referenceable(target.status, "LOGICAL_TABLE", target.id, path.targetLogicalTableVersion)
            references.freezeObject("JOIN_PATH", path.id, version, path.id, "${source.displayName} -> ${target.displayName}", "JOIN_PATH", encodeSnapshot(snapshot, "join path executable snapshot"))
        }
        private fun freezeLogicUnit(id: String, version: Long): LogicUnitRecord = tracing("LOGIC_UNIT", id, version) { freezeLogicUnitBody(id, version) }
        private fun freezeLogicUnitBody(id: String, version: Long): LogicUnitRecord {
            val key = VersionedId(id, version)
            frozenUnits[key]?.let { return it }
            val unit = logicalModel.logicUnitSnapshot(required(id, "logic_unit.id"), version)
            referenceable(unit.status, "LOGIC_UNIT", unit.id, version)
            val expanded = logicalModel.expandLogicUnit(unit.id, version)
            expanded.units.values.filter { it.id != unit.id || it.currentVersion != version }.forEach { freezeLogicUnit(it.id, it.currentVersion) }
            val execution = logicUnitExecution(expanded)
            execution.nativeTableDependencies.values.forEach(::freezeNativeTable)
            references.freezeObject("LOGIC_UNIT", unit.id, version, unit.code, unit.displayName, "LOGIC_UNIT", encodeSnapshot(execution, "logic_unit.snapshot_proto"))
            frozenUnits[key] = unit
            return unit
        }
        private fun freezeMappingObjects(mapping: AnalysisDimensionEventMappingRecord) {
            val binding = freezeDimensionBinding(mapping.bindingId, mapping.bindingVersion)
            if (binding.dimensionId != mapping.dimensionId || binding.dimensionVersion != mapping.dimensionVersion || binding.eventId != mapping.eventId || binding.eventVersion != mapping.eventVersion)
                throw ProducerContractException("analysis dimension mapping differs from frozen binding identity")
        }
        private fun freezeDimensionBinding(id: String, version: Long): DimensionEventBindingRecord = tracing("DIMENSION_EVENT_BINDING", id, version) { freezeDimensionBindingBody(id, version) }
        private fun freezeDimensionBindingBody(id: String, version: Long): DimensionEventBindingRecord {
            val binding = dimensions.dimensionEventBindingSnapshot(id, version)
            referenceable(binding.status, "DIMENSION_EVENT_BINDING", id, version)
            val dimension = dimensions.dimensionSnapshot(binding.dimensionId, binding.dimensionVersion)
            referenceable(dimension.status, "DIMENSION", dimension.id, binding.dimensionVersion)
            references.freezeObject("DIMENSION", dimension.id, binding.dimensionVersion, dimension.code, dimension.displayName, "DIMENSION")
            val event = freezeEvent(binding.eventId, binding.eventVersion)
            freezeTopology(binding.topologyId, binding.topologyVersion)
            val expression = requireNotNull(binding.expression) { "dimension binding expression is required" }
            freezeNativeTables(expression); freezeSemanticUnits(expression, "dimension expression cannot expose physical fields")
            val unitId = required(binding.logicUnitId, "dimension binding logic unit")
            val unitVersion = requireNotNull(binding.logicUnitVersion) { "dimension binding logic unit version is required" }
            freezeLogicUnit(unitId, unitVersion)
            val snapshot = PublishedDimensionBinding(id, version, binding.dimensionId, binding.dimensionVersion, EventRef(binding.eventId, binding.eventVersion),
                binding.topologyId, binding.topologyVersion, unitId, unitVersion, expression, dimensions.bindingAccessPath(id, version), dimensions.bindingRoles(id, version))
            references.freezeObject("DIMENSION_EVENT_BINDING", id, version, "${dimension.code}:${event.code}", "${dimension.displayName} / ${event.displayName}", "DIMENSION_BINDING", encodeSnapshot(snapshot, "dimension binding snapshot"))
            return binding
        }
        private fun logicUnitExecution(expansion: LogicalModelGovernanceService.LogicUnitExpansion): LogicUnitExecutionSnapshot {
            val unit = expansion.root
            val ref = expansion.physical
            val logical = logicalModel.logicalTableSnapshot(unit.logicalTableId, expansion.logicalTableVersion)
            referenceable(logical.status, "LOGICAL_TABLE", logical.id, expansion.logicalTableVersion)
            val physical = physicalAssets.physicalTableSnapshot(ref.physicalTableId, ref.physicalTableVersion)
            referenceable(physical.status, "PHYSICAL_TABLE", physical.id, ref.physicalTableVersion)
            expansion.columns.values.forEach { referenceable(it.status, "PHYSICAL_COLUMN", it.id, it.currentVersion) }
            val contract = physicalAssets.readPhysicalDataContract(PhysicalTableRef(physical.id, ref.physicalTableVersion))
            val adoption = sourceAdoption(contract)
            val nativeTables = expansion.nativeTables.mapValues { (_, table) -> nativeTable(table) }
            nativeTables.values.forEach { dependency ->
                if (!dependency.matchesSource(adoption.binding, adoption.connectorRef, adoption.dialect))
                    throw ReleaseClosureConflictException("NATIVE_TABLE_SOURCE_ADOPTION_CONFLICT",
                        mapOf("physicalTableId" to contract.reference.id, "physicalTableVersion" to contract.reference.version,
                            "sourceAdoption" to adoption.reference.id, "nativePhysicalTableId" to dependency.physicalTableId,
                            "nativePhysicalTableVersion" to dependency.physicalTableVersion, "nativeSourceAdoption" to dependency.adoption.reference.id), currentPaths)
            }
            expansion.expressions.values.forEach { expression ->
                SemanticExpressionInspector.nativeCalls(expression).forEach { call ->
                    nativeExpressions.function(call)
                    if (call.connectorRef != adoption.connectorRef) throw ReleaseClosureConflictException("NATIVE_CALL_SOURCE_ADOPTION_CONFLICT",
                        mapOf("physicalTableId" to contract.reference.id, "physicalTableVersion" to contract.reference.version,
                            "sourceAdoption" to adoption.reference.id, "function" to call.function,
                            "declaredConnector" to call.connectorRef.toString(), "adoptedConnector" to adoption.connectorRef.toString()), currentPaths)
                }
            }
            return LogicUnitExecutionSnapshot(LogicUnitExecutionSnapshot.FORMAT_VERSION,
                releaseObjectId("peb", logical.id, expansion.logicalTableVersion, physical.id, ref.physicalTableVersion, adoption.reference.id), "${expansion.logicalTableVersion}.${ref.physicalTableVersion}",
                unit.id, unit.currentVersion, unit.logicType?.let { ScalarValueType.fromLogicType(it).name }, unit.valueShape, logical.id, expansion.logicalTableVersion,
                physical.id, ref.physicalTableVersion,
                expansion.expressions, expansion.columns.mapValues { it.value.columnName },
                expansion.columns.mapValues { it.value.jdbcColumnType }, nativeTables, contract, adoption)
        }
        private fun freezeNativeTables(expression: SemanticExpression) {
            SemanticExpressionInspector.nativeCalls(expression).forEach { call ->
                nativeExpressions.function(call)
                SemanticExpressionInspector.physicalTables(call).forEach { reference ->
                    val dependency = nativeTable(physicalAssets.nativeTable(reference))
                    if (dependency.adoption.connectorRef != call.connectorRef) throw ReleaseClosureConflictException("NATIVE_CALL_SOURCE_ADOPTION_CONFLICT",
                        mapOf("physicalTableId" to reference.physicalTableId, "physicalTableVersion" to reference.physicalTableVersion,
                            "sourceAdoption" to dependency.adoption.reference.id, "function" to call.function,
                            "declaredConnector" to call.connectorRef.toString(), "adoptedConnector" to dependency.adoption.connectorRef.toString()), currentPaths)
                    freezeNativeTable(dependency)
                }
            }
        }
        private fun sourceAdoption(contract: PhysicalDataContract): PhysicalSourceAdoption = frozenSourceAdoptions.getOrPut(contract.reference) {
            val candidates = sourceAdoptionCandidates[contract.reference].orEmpty()
            if (candidates.size != 1) throw ReleaseClosureConflictException(
                if (candidates.isEmpty()) "SOURCE_ADOPTION_MISSING" else "SOURCE_ADOPTION_AMBIGUOUS",
                mapOf("physicalTableId" to contract.reference.id, "physicalTableVersion" to contract.reference.version,
                    "sourceAdoptions" to candidates.map { it.reference.id }), currentPaths)
            candidates.single().also { adoption ->
                try { adoption.requireContract(contract) }
                catch (invalid: IllegalArgumentException) { throw ReleaseClosureConflictException("SOURCE_ADOPTION_CONTRACT_MISMATCH",
                    mapOf("physicalTableId" to contract.reference.id, "physicalTableVersion" to contract.reference.version,
                        "sourceAdoption" to adoption.reference.id, "reason" to requireNotNull(invalid.message)), currentPaths) }
            }
        }
        private fun nativeTable(contract: PhysicalDataContract): NativeTableDependency = NativeTableDependency(contract, sourceAdoption(contract))
        private fun freezeNativeTable(dependency: NativeTableDependency) {
            val table = physicalAssets.physicalTableSnapshot(dependency.physicalTableId, dependency.physicalTableVersion)
            references.freezeObject("PHYSICAL_TABLE", table.id, dependency.physicalTableVersion, table.code, table.displayName, "PHYSICAL_TABLE", encodeSnapshot(dependency, "native table dependency"))
        }
        private inner class MappingSelections {
            private val closures = mutableMapOf<MetricInstanceRef, MetricSource.Closure>()
            private val cache = mutableMapOf<VersionedId, List<AnalysisDimensionEventMappingRecord>>()
            private fun closure(instance: MetricInstanceRef): MetricSource.Closure = closures.getOrPut(instance) {
                MetricSource.closure(listOf(instance), emptyList(), { requireMetric(it).definition }, ::requireInstance,
                    { requireNotNull(frozenMembers[VersionedId(it.id, it.version)]) { "actual member is not frozen" } })
            }
            private fun adoptedDimensions(instance: MetricInstanceRef, id: String, version: Long, level: String): List<CalculationBindings.Dimension> {
                val values = buildList {
                    closure(instance).dependencies.forEach { dependency ->
                        val bound: Pair<CalculationBindings, Map<String, Calculation.Value>> = when (dependency) {
                            is MetricSource.InstanceDependency -> requireInstance(dependency.reference).let { it.bindings to it.fixedArguments }
                            is MetricSource.MemberDependency -> (frozenMembers[VersionedId(dependency.reference.id, dependency.reference.version)] as? MemberSource.Computed)
                                ?.let { it.bindings to it.fixedArguments } ?: return@forEach
                            is MetricSource.MetricDependency -> return@forEach
                        }
                        addAll(bound.first.dimensions.values)
                        bound.first.groupingCandidates.forEach { (name, candidates) ->
                            val fixed = bound.second[name]
                            addAll(if (fixed == null) candidates else candidates.filter { it.reference in (fixed as Calculation.Grouping).dimensions })
                        }
                    }
                }
                return values.filter { it.reference.id == id && it.reference.version == version.toString() && it.reference.levelCode == level }
            }
            private fun mappings(id: String, version: Long): List<AnalysisDimensionEventMappingRecord> = cache.getOrPut(VersionedId(id, version)) {
                analysisDimensions.listAnalysisDimensionEventMappings(id, version)
            }
            fun usesSource(instance: MetricInstanceRef, definition: MetricDefinition, dimension: ReleaseScenarioSnapshot.DimensionGrant,
                level: AnalysisDimensionLevelRecord, event: EventRef): Boolean {
                val selected = adoptedDimensions(instance, dimension.id, dimension.version, level.levelCode)
                if (selected.isEmpty()) return definition is MetricDefinition.FactMeasure && definition.event == event
                return mappings(dimension.id, dimension.version).any { mapping -> mapping.levelCode == level.levelCode && mapping.eventId == event.eventId &&
                    mapping.eventVersion == event.eventVersion && selected.any { mapping.id in it.mappingIds } }
            }
            fun findMappings(id: String, version: Long, level: String, eventId: String, eventVersion: Long, instance: MetricInstanceRef): List<AnalysisDimensionEventMappingRecord> {
                val candidates = mappings(id, version).filter { it.levelCode == level && it.eventId == eventId && it.eventVersion == eventVersion }
                val bindings = adoptedDimensions(instance, id, version, level)
                val selected = linkedMapOf<String, AnalysisDimensionEventMappingRecord>()
                fun select(matches: List<AnalysisDimensionEventMappingRecord>) {
                    if (matches.size > 1) {
                        val adoption = subjectMetrics.firstOrNull { it.instance == instance } ?: throw ProducerContractException("scenario measurement instance has no exact subject-area adoption")
                        throw ProducerContractException("analysis dimension has ambiguous exact mappings at one adopted binding: ${adoption.id}@${adoption.subjectAreaVersion} dimension=$id@$version/$level event=$eventId@$eventVersion")
                    }
                    matches.forEach { selected[it.id] = it }
                }
                if (bindings.isEmpty()) select(candidates) else bindings.forEach { binding -> select(candidates.filter { it.id in binding.mappingIds }) }
                return selected.values.toList()
            }
        }
        private fun <T> tracing(type: String, id: String, version: Long, action: () -> T): T {
            val before = currentPaths
            val key = ObjectRef(ReleaseObjectKind.require(type), id, version)
            currentPaths = (dependencyPaths[key].orEmpty() + before.map { it + "$type:$id@$version" }).distinct()
            return try { action() } finally { currentPaths = before }
        }
        private inner class FrozenReferences {
            private val identities = linkedMapOf<ObjectRef, ReleaseObjectRefRecord>()
            private val objectPaths = linkedMapOf<ObjectRef, List<List<String>>>()
            private val mappingIds = linkedMapOf<String, ReleaseDimensionMappingRecord>()
            private val mappingPositions = linkedMapOf<List<Any>, ReleaseDimensionMappingRecord>()
            private val mappingPaths = linkedMapOf<String, List<List<String>>>()
            fun freezeMapping(mapping: AnalysisDimensionEventMappingRecord) {
                val request = SubjectAreaReleaseBuildRequest(releaseId, releaseVersion, releaseDisplayName, subjectAreaId, subjectAreaVersion, createdBy, sourceAdoptionRefs)
                val frozen = dimensionMappings.freeze(request, mapping, createdAt)
                val paths = currentPaths.map { it + "ANALYSIS_DIMENSION:${mapping.analysisDimensionId}@${mapping.analysisDimensionVersion}" + "ANALYSIS_DIMENSION_MAPPING:${mapping.id}" }
                val priorId = mappingIds.putIfAbsent(mapping.id, frozen)
                if (priorId != null) {
                    if (priorId != frozen) throw ReleaseClosureConflictException("MAPPING_IDENTITY_CONFLICT", mapOf("mappingId" to mapping.id), mappingPaths.getValue(mapping.id) + paths)
                    return
                }
                val position = listOf(frozen.analysisDimensionId, frozen.analysisDimensionVersion, frozen.levelCode, frozen.eventId, frozen.eventVersion)
                val priorPosition = mappingPositions.putIfAbsent(position, frozen)
                if (priorPosition != null) throw ReleaseClosureConflictException("MAPPING_POSITION_CONFLICT", mapOf("analysisDimensionId" to frozen.analysisDimensionId,
                    "analysisDimensionVersion" to frozen.analysisDimensionVersion, "levelCode" to frozen.levelCode, "eventId" to frozen.eventId, "eventVersion" to frozen.eventVersion,
                    "mappingIds" to listOf(priorPosition.mappingId, frozen.mappingId)), mappingPaths.getValue(priorPosition.mappingId) + paths)
                mappingPaths[mapping.id] = paths
                mappings.add(frozen)
                val unit = frozenUnits[VersionedId(frozen.logicUnitId, frozen.logicUnitVersion)] ?: throw ProducerContractException("mapping LogicUnit was not frozen")
                if (unit.isTime == true) timeAnchors.add(dimensionMappings.timeAnchor(frozen))
            }
            fun freezeObject(type: String, id: String, version: Long, code: String?, display: String?, role: String, snapshot: String = "{}") {
                val key = ObjectRef(ReleaseObjectKind.require(type), id, version)
                val frozen = ReleaseObjectRefRecord(releaseObjectId("ror", releaseId, releaseVersion, type, id, version), releaseId, releaseVersion, type, id, version,
                    required(code, "release_object_ref.object_code"), required(display, "release_object_ref.object_display_name"), role,
                    required(snapshot, "release_object_ref.snapshot_proto"), createdAt)
                val paths = currentPaths.map { if (it.last() == "$type:$id@$version") it else it + "$type:$id@$version" }
                val prior = identities.putIfAbsent(key, frozen)
                if (prior != null) {
                    if (prior != frozen) throw ReleaseClosureConflictException("EXACT_OBJECT_CONFLICT", mapOf("objectType" to type, "objectId" to id, "objectVersion" to version), objectPaths.getValue(key) + paths)
                    objectPaths[key] = (objectPaths.getValue(key) + paths).distinct()
                    return
                }
                objectPaths[key] = paths
                objects.add(frozen)
            }
        }
    }
    private data class VersionedId(val id: String, val version: Long) {
        init { ProducerContracts.requireText(id, "release.snapshot.id"); ProducerContracts.requireVersion(version, "release.snapshot.version") }
    }
    private data class ObjectRef(val type: ReleaseObjectKind, val id: String, val version: Long)
    private fun referenceable(status: String, type: String, id: String, version: Long) = GovernanceContract.requireReferenceableVersion(status, type, id, version)
    private fun required(value: String?, field: String): String = ProducerContracts.requireText(value, field).trim()
    private fun requireNotEmpty(values: List<*>, field: String) { if (values.isEmpty()) throw ProducerContractException("$field is required for release build") }
    private fun encodeSnapshot(value: com.olap.prototype.query.contract.MetricAdoption, label: String): String = com.olap.prototype.codec.ProtoBytes.storage(com.olap.prototype.codec.GovernanceProto.toProto(value))
    private fun encodeSnapshot(value: com.olap.prototype.governance.contract.MemberDefinitionRecord, label: String): String = com.olap.prototype.codec.ProtoBytes.storage(com.olap.prototype.codec.GovernanceProto.toProto(value))
    private fun encodeSnapshot(value: com.olap.prototype.query.contract.PublishedMemberSource, label: String): String = com.olap.prototype.codec.ProtoBytes.storage(com.olap.prototype.codec.GovernanceProto.toProto(value))
    private fun encodeSnapshot(value: com.olap.prototype.release.contract.MetricExecutionSnapshot, label: String): String = com.olap.prototype.codec.ProtoBytes.storage(com.olap.prototype.codec.GovernanceProto.toProto(value))
    private fun encodeSnapshot(value: com.olap.prototype.release.contract.FrozenAnalysisDimensionMeaning, label: String): String = com.olap.prototype.codec.ProtoBytes.storage(com.olap.prototype.codec.GovernanceProto.toProto(value))
    private fun encodeSnapshot(value: com.olap.prototype.release.contract.ReleaseScenarioSnapshot, label: String): String = com.olap.prototype.codec.ProtoBytes.storage(com.olap.prototype.codec.GovernanceProto.toProto(value))
    private fun encodeSnapshot(value: com.olap.prototype.query.contract.PublishedRelationFilter, label: String): String = com.olap.prototype.codec.ProtoBytes.storage(com.olap.prototype.codec.GovernanceProto.toProto(value))
    private fun encodeSnapshot(value: com.olap.prototype.release.contract.EventExecutionSnapshot, label: String): String = com.olap.prototype.codec.ProtoBytes.storage(com.olap.prototype.codec.GovernanceProto.toProto(value))
    private fun encodeSnapshot(value: com.olap.prototype.release.contract.TopologyExecutionSnapshot, label: String): String = com.olap.prototype.codec.ProtoBytes.storage(com.olap.prototype.codec.GovernanceProto.toProto(value))
    private fun encodeSnapshot(value: com.olap.prototype.release.contract.ReleaseJoinPathSnapshot, label: String): String = com.olap.prototype.codec.ProtoBytes.storage(com.olap.prototype.codec.GovernanceProto.toProto(value))
    private fun encodeSnapshot(value: com.olap.prototype.release.contract.LogicUnitExecutionSnapshot, label: String): String = com.olap.prototype.codec.ProtoBytes.storage(com.olap.prototype.codec.GovernanceProto.toProto(value))
    private fun encodeSnapshot(value: com.olap.prototype.event.PublishedDimensionBinding, label: String): String = com.olap.prototype.codec.ProtoBytes.storage(com.olap.prototype.codec.GovernanceProto.toProto(value))
    private fun encodeSnapshot(value: com.olap.prototype.contract.NativeTableDependency, label: String): String = com.olap.prototype.codec.ProtoBytes.storage(com.olap.prototype.codec.GovernanceProto.toProto(value))
    companion object {
        private const val PRODUCER_STAGE = "RELEASE_BUILD"
        internal fun releaseObjectId(prefix: String, vararg parts: Any?): String {
            val identity = OlapJsonUtils.mapper().writeValueAsBytes(parts.map { it?.toString() })
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(identity).joinToString("") { "%02x".format(it) }
            return "${prefix}__$hash"
        }
    }
}
