package com.olap.prototype.release.catalog

import com.olap.prototype.calculation.MetricCalculation
import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.calculation.MetricRef
import com.olap.prototype.calculation.MetricSource
import com.olap.prototype.engine.catalog.ExecutableCatalog
import com.olap.prototype.engine.catalog.ExecutableEvent
import com.olap.prototype.event.PublishedDimensionBinding
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.SemanticExpressionCodec
import com.olap.prototype.expression.SemanticExpressionInspector
import com.olap.prototype.member.MemberRef
import com.olap.prototype.member.MemberSource
import com.olap.prototype.member.MemberSourceDefinition
import com.olap.prototype.query.*
import com.olap.prototype.query.contract.*
import com.olap.prototype.query.contract.catalog.*
import com.olap.prototype.query.contract.catalog.PublishedSemanticCatalog.*
import com.olap.prototype.release.contract.*

/** 只从同一不可变发布构造公开语义，不读取草稿或暴露物理表达式。 */
class PublishedSemanticCatalogProducer : ReleaseSemanticCatalogCompiler {
    override fun produce(capability: QueryCapabilityView, release: ReleaseCatalogView, executable: PublishedExecutableCatalog): PublishedSemanticCatalog =
        Projection(release, executable.catalog).produce(capability, executable)

    /** 精确读取索引属于本次投影，角色、scope和含义收集仍属于各自位置。 */
    private class Projection(private val release: ReleaseCatalogView, private val executable: ExecutableCatalog) {
        private data class DimensionScope(val levelCode: String, val eventId: String, val eventVersion: Long)
        private val snapshots = ReleaseObjectIndex(release.objectVersionRefs)
        private val valueTypes = PublishedValueTypes(executable)
        private val physicalByLogical = executable.physicalExecutionBindings.values.groupBy { "${it.logicalTableId}@${it.logicalTableVersion}" }

        fun produce(capability: QueryCapabilityView, published: PublishedExecutableCatalog): PublishedSemanticCatalog {
            val closure = published.validate()
            QueryCapabilityHash.requireValid(capability)
            val coordinate = Coordinate(release.scenarioId, release.scenarioVersion, release.releaseId, release.releaseVersion)
            require(coordinate.request() == PublishedQueryEntry.key(capability)) { "published semantic sources have different coordinates" }
            require(capability == published.queryCapability) { "compiled semantic source has a different frozen query capability" }
            val dimensions = capability.analysisDimensionCandidates.map { dimension ->
                val scopes = dimension.eventMappings.map { DimensionScope(it.levelCode, it.eventId, it.eventVersion) }.toSet()
                Dimension(dimension.analysisDimensionId, dimension.analysisDimensionVersion, dimension.analysisDimensionDisplayName, dimension.availableLevelCodes,
                    dimensionValueTypes(capability.executionTypes, dimension.analysisDimensionId, dimension.analysisDimensionVersion, scopes),
                    dimensionDefinition(dimension.analysisDimensionId, dimension.analysisDimensionVersion, scopes), dimension.eventMappings.map {
                        EventMapping(it.mappingId, it.levelCode, it.levelDisplayName, it.eventId, it.eventVersion, it.eventDisplayName, it.valueAlignmentKey, it.valueAlignmentStatus)
                    })
            }
            val times = capability.timeAnalysisDimensionCandidates.map { dimension ->
                val scopes = dimension.eventAnchors.map { DimensionScope(it.levelCode, it.eventId, it.eventVersion) }.toSet()
                TimeDimension(dimension.analysisDimensionId, dimension.analysisDimensionVersion, dimension.analysisDimensionDisplayName, dimension.availableLevelCodes,
                    timeValueTypes(dimension.analysisDimensionId, dimension.analysisDimensionVersion, scopes), dimensionDefinition(dimension.analysisDimensionId, dimension.analysisDimensionVersion, scopes),
                    dimension.eventAnchors.map { Anchor(it.mappingId, it.levelCode, it.eventId, it.eventVersion, it.eventDisplayName, it.timeRole) })
            }
            val combinations = capability.metricAnalysisDimensionMatrix.map { Combination(it.instance, it.analysisDimensionId, it.analysisDimensionVersion, requireNotNull(it.levelCode), it.available, it.reasonCode, it.reasonMessage) }
            val filters = capability.filterCandidates.map { Filter(it.filterRefId, release.scenarioVersion, it.filterTargetDisplayName,
                AssetReference(it.filterTargetObjectId, it.filterTargetObjectVersion), it.operators, scalarTypes(it.filterValueTypes), it.requiredByDefault,
                it.eventMappings.map { mapping -> FilterMapping(mapping.levelCode, mapping.eventId, mapping.eventVersion) }) }
            return com.olap.prototype.codec.PublicationProto.semanticCatalog(Catalog(coordinate, release.scenarioDisplayName, SourceJoinMeaning.current(), capability.metricCandidates.map(::metric),
                capability.memberCandidates.map(::memberSource), capability.relationFilterCandidates.map(::relationFilter), dimensions, times, combinations, filters, semanticDependencies(closure)))
        }
        private fun semanticDependencies(closure: MetricSource.Closure): List<SemanticDependency> = closure.dependencies.map { dependency -> when (dependency) {
            is MetricSource.MetricDependency -> {
                when (val definition = executable.requireMetric(dependency.reference).definition) {
                    is MetricCalculation -> CalculationDependency(dependency.reference, definition)
                    is MetricDefinition.FactMeasure -> FactDependency(dependency.reference, definition.event,
                        Aggregation(definition.aggregation, definition.distinct, definition.aggregationRounding), definition.valueContract,
                        factScope(dependency.reference, definition.event.eventId, definition.event.eventVersion))
                }
            }
            is MetricSource.InstanceDependency -> InstanceDependency(dependency.reference, executable.metricInstances.getValue(dependency.reference.id))
            is MetricSource.MemberDependency -> {
                val source = executable.memberSources.getValue("${dependency.reference.id}@${dependency.reference.version}")
                val result = source.definition.result
                MemberDependency(dependency.reference, result.entity, result.role, result.shape, result.identitySlots, memberDefinition(source, result.outputs.keys.toSortedSet()))
            }
        } }
        private fun metric(candidate: MetricCandidate): Metric {
            val definition = executable.requireMetric(candidate.definition).definition
            val aggregation: CatalogOptional<Aggregation> = if (definition is MetricDefinition.FactMeasure) CatalogOptional.of(Aggregation(definition.aggregation, definition.distinct, definition.aggregationRounding)) else CatalogOptional.absent()
            return Metric(candidate.adoptionId, candidate.adoptionVersion, candidate.subjectAreaId, candidate.subjectAreaVersion, candidate.code, candidate.displayName,
                candidate.businessMeaning, candidate.definition, candidate.instance, candidate.valueContract, candidate.snapshotMeaning, candidate.sources,
                MetricKind.valueOf(candidate.definitionKind), candidate.fixedArguments, candidate.parameters, candidate.groupingCandidates, candidate.memberParameters, candidate.relationParameters,
                candidate.sources.map { SourceExplanation(it.callPath, sourceScope(it)) }, aggregation)
        }
        private fun dimensionDefinition(id: String, version: Long, scopes: Set<DimensionScope>): DimensionDefinition {
            val snapshot = snapshots.read(snapshots.require("ANALYSIS_DIMENSION", id, version), FrozenAnalysisDimensionMeaning::class.java)
            val description = snapshot.description
            val units = linkedMapOf<String, LogicUnitMeaning>()
            val mappings = linkedMapOf<String, DefinitionMapping>()
            release.dimensionMappings.filter { it.analysisDimensionId == id && it.analysisDimensionVersion == version && DimensionScope(it.levelCode, it.eventId, it.eventVersion) in scopes }.forEach { rule ->
                val binding = frozenDimensionBinding(rule.dimensionBindingId, rule.dimensionBindingVersion)
                val owner = "dimension:${binding.id}@${binding.version}"
                val expression = requireNotNull(definition(rule.expression, units, owner, binding.accessPath.joinPathRefs, binding.roles))
                val access = rolePath(owner, emptyList(), binding.accessPath, units, linkedSetOf())
                val roles = roleMeanings(owner, binding.roles, listOf(binding.expression), units)
                val mapping = DefinitionMapping(rule.levelCode, rule.eventId, rule.eventVersion, expression, access, roles, optionalCollections(eventCollections(rule.eventId, rule.eventVersion)))
                val previous = mappings.putIfAbsent(mapping.key(), mapping)
                require(previous == null || previous == mapping) { "frozen dimension has different definitions for the same event and level" }
            }
            require(mappings.isNotEmpty()) { "frozen dimension definitions are required" }
            return DimensionDefinition(DimensionInterpretation.FROZEN_SEMANTIC_DEFINITION_NOT_SOURCE_COMPLETENESS_PROOF, description, mappings.values.toList(), units.values.toList())
        }
        private fun frozenDimensionBinding(id: String, version: Long): PublishedDimensionBinding = snapshots.read(snapshots.require("DIMENSION_EVENT_BINDING", id, version), PublishedDimensionBinding::class.java)

        private fun rolePath(role: String, prefix: List<RelationAccessPath.JoinRef>, path: RelationAccessPath,
            units: MutableMap<String, LogicUnitMeaning>, active: MutableSet<String>): List<SemanticJoin> {
            val steps = mutableListOf<SemanticJoin>()
            val position = prefix.toMutableList()
            path.joinPathRefs.forEach { reference ->
                val key = "${reference.joinPathId}@${reference.joinPathVersion}"
                require(active.add(key)) { "cyclic semantic clock path explanation" }
                try {
                    val join = snapshots.read(snapshots.require("JOIN_PATH", reference.joinPathId, reference.joinPathVersion), ReleaseJoinPathSnapshot::class.java)
                    require(join.joinPathId == reference.joinPathId && join.joinPathVersion == reference.joinPathVersion) { "frozen role step differs from exact join identity" }
                    val parent = position.toList()
                    position.add(reference)
                    val temporal = join.temporal
                    val intervals = temporal?.intervals.orEmpty().mapIndexed { index, interval ->
                        val clock: SemanticJoin.Clock
                        val scope: SemanticJoin.ClockScope
                        val access: List<SemanticJoin>
                        when (val source = interval.sourceTime) {
                            is TemporalJoinContract.LogicUnitTime -> {
                                val backbone = source is TemporalJoinContract.BackboneLogicUnitClock
                                val clockPath = RelationAccessPath(source.joinPathRefs.map { RelationAccessPath.JoinRef(it.joinPathId, it.joinPathVersion) })
                                access = rolePath("$role:clock:$index", if (backbone) emptyList() else parent, clockPath, units, active)
                                semanticReference(source.logicUnitId, source.logicUnitVersion, units)
                                clock = SemanticJoin.LogicClock(source.logicUnitId, source.logicUnitVersion)
                                scope = if (backbone) SemanticJoin.ClockScope.TOPOLOGY_BACKBONE else SemanticJoin.ClockScope.JOIN_SOURCE
                            }
                            is TemporalJoinContract.QueryClock -> {
                                access = emptyList()
                                clock = SemanticJoin.QueryClock(TemporalClockBindings.slot(role, position, index), ScalarValueType.valueOf(source.valueType.name), source.description)
                                scope = SemanticJoin.ClockScope.QUERY
                            }
                        }
                        SemanticJoin.Interval(clock, scope, access, semanticReference(interval.targetValidFromLogicUnitId, interval.targetValidFromLogicUnitVersion, units),
                            semanticReference(interval.targetValidToLogicUnitId, interval.targetValidToLogicUnitVersion, units), interval.validFromInclusive, interval.validToInclusive, interval.openEndedValidTo)
                    }
                    val keys = join.keys.map { SemanticJoin.KeyPair(semanticReference(it.sourceLogicUnitId, it.sourceLogicUnitVersion, units), semanticReference(it.targetLogicUnitId, it.targetLogicUnitVersion, units)) }
                    steps.add(SemanticJoin(SourceJoinContract.JoinType.valueOf(join.joinType), SourceJoinContract.Cardinality.valueOf(join.cardinality), SourceJoinContract.NullPolicy.valueOf(join.nullPolicy),
                        keys, intervals, optionalCollections(sourceCollections(join.sourceLogicalTableId, join.sourceLogicalTableVersion)), optionalCollections(sourceCollections(join.targetLogicalTableId, join.targetLogicalTableVersion))))
                } catch (failure: java.io.IOException) { throw IllegalArgumentException("invalid frozen role explanation", failure) }
                finally { active.remove(key) }
            }
            return java.util.List.copyOf(steps)
        }
        private fun roleMeanings(owner: String, declared: Map<String, RelationAccessPath>, expressions: List<SemanticExpression>, units: MutableMap<String, LogicUnitMeaning>): Map<String, List<SemanticJoin>> {
            val used = sortedSetOf<String>()
            expressions.forEach { expression ->
                SemanticExpressionInspector.references(expression).filter { it.referenceKind == SemanticExpression.ReferenceKind.ROLE_LOGIC_UNIT }.forEach { used.add(requireNotNull(it.role)) }
                SemanticExpressionInspector.quantifiers(expression).forEach { (it.expression.from as? SemanticExpression.RoleParent)?.let { parent -> used.add(parent.role) } }
            }
            return used.associateWithTo(linkedMapOf()) { role -> rolePath("$owner:$role", emptyList(), requireNotNull(declared[role]) { "semantic role is not declared" }, units, linkedSetOf()) }
        }
        private fun semanticReference(id: String, version: Long, units: MutableMap<String, LogicUnitMeaning>): PublicExpression.Reference {
            addUnit(id, version, units)
            return PublicExpression.Reference(id, version, null)
        }
        private fun sourceScope(source: MetricSource): SourceScope = when (val origin = source.origin) {
            is MetricSource.Fact -> factScope(origin.metric, source.event.eventId, source.event.eventVersion)
            is MetricSource.Member -> {
                val member = requireNotNull(executable.memberSources["${origin.sourceId}@${origin.sourceVersion}"]) { "metric member source is missing" }
                val event = requireNotNull(executable.events["${source.event.eventId}@${source.event.eventVersion}"]) { "metric member event is missing" }
                memberMeaning(event, requireProjection(member), origin.slots.toSortedSet(), linkedMapOf())
            }
        }
        private fun factScope(reference: MetricRef, eventId: String, eventVersion: Long): FactScope {
            val units = linkedMapOf<String, LogicUnitMeaning>()
            val eventScope = requireNotNull(executable.events["$eventId@$eventVersion"]) { "exact executable event is required" }
            val eventRoles = roleMeanings("event", eventScope.roles, listOfNotNull(eventScope.baseFilter), units)
            val event = snapshots.read(snapshots.require("EVENT", eventId, eventVersion), EventExecutionSnapshot::class.java)
            val eventPredicate = definition(event.baseFilter, units, "event", emptyList(), eventScope.roles)
            val fact = executable.requireMetric(reference).definition as MetricDefinition.FactMeasure
            fact.snapshotRule?.keys()?.forEach { semanticReference(it.id, it.version, units) }
            val owner = "metric:${reference.key()}"
            val input = requireNotNull(definition(fact.input, units, owner, emptyList(), fact.roles))
            val predicate = definition(fact.privatePredicate, units, owner, emptyList(), fact.roles)
            val metricRoles = roleMeanings(owner, fact.roles, listOf(fact.input) + listOfNotNull(fact.privatePredicate), units)
            return FactScope(FactInterpretation.FROZEN_DEFINITION_NOT_CALLER_FILTER_OR_SOURCE_COMPLETENESS_PROOF, eventPredicate, eventRoles,
                fact.snapshotRule, input, predicate, metricRoles, units.values.toList(), optionalCollections(eventCollections(eventId, eventVersion)))
        }
        private fun relationFilter(candidate: RelationFilterCandidate): RelationFilter {
            val grant = candidate.definition
            val relation = grant.relation
            val units = linkedMapOf<String, LogicUnitMeaning>()
            val parent = requireNotNull(executable.events["${relation.parent.eventId}@${relation.parent.eventVersion}"]) { "relation parent event is missing" }
            val member = requireProjection(requireNotNull(executable.memberSources["${relation.memberSourceId}@${relation.memberSourceVersion}"]) { "related source is missing" })
            val event = requireNotNull(executable.events["${member.definition.event.eventId}@${member.definition.event.eventVersion}"]) { "related event is missing" }
            val association = rolePath("relation:${relation.id}", emptyList(), requireNotNull(parent.roles[relation.relationshipRole]), units, linkedSetOf())
            val slots = grant.permission.filters.keys.toSortedSet()
            if (grant.permission.timeRange) slots.add(requireNotNull(member.definition.result.timeSlot))
            return RelationFilter(relation.id, relation.version, grant.displayName, relation.parent, candidate.entity, candidate.role, grant.permission,
                candidate.filterTypes, candidate.filterValueContracts, candidate.timeRange, candidate.clockSlots, grant.requiredByDefault, association, memberMeaning(event, member, slots, units))
        }
        private fun memberSource(candidate: MemberCandidate): Member = Member(candidate.sourceId, candidate.sourceVersion, candidate.displayName, candidate.entity,
            candidate.role, candidate.shape, candidate.identitySlots, candidate.outputs, candidate.permission, candidate.filterTypes, candidate.filterValueContracts,
            candidate.unitDimensions, candidate.timeRange, candidate.clockSlots, candidate.parameters, candidate.fixedArguments, candidate.groupingCandidates, candidate.memberParameters, candidate.relationParameters)
        private fun memberDefinition(member: MemberSource, slots: Set<String>): MemberDefinition {
            if (member is MemberSource.Projection) {
                val event = member.definition.event
                return memberMeaning(requireNotNull(executable.events["${event.eventId}@${event.eventVersion}"]) { "member event is missing" }, member, slots, linkedMapOf())
            }
            member as MemberSource.Computed
            val meanings = slots.associateWithTo(linkedMapOf<String, MemberSlotMeaning>()) { slot ->
                when (val output = member.definition.result.outputs.getValue(slot)) {
                    is MemberSourceDefinition.ComputedDimensionOutput -> ComputedSlotMeaning(output.displayName, output.role, output.valueType, output.valueContract, output.dimensionSlot, output.symbol)
                    is MemberSourceDefinition.ComputedPropertyOutput -> ComputedSlotMeaning(output.displayName, output.role, output.valueType, output.valueContract, null, output.symbol)
                    else -> ComputedContainerSlotMeaning(output.displayName, output.role, MemberSourceDefinition.containerShape(output), MemberSourceDefinition.completedSymbol(output))
                }
            }
            val sources = MetricSource.resolve(MemberRef(member.id, member.version), { executable.requireMetric(it).definition },
                { executable.metricInstances.getValue(it.id) }, { executable.memberSources.getValue("${it.id}@${it.version}") })
            return ComputedMeaning(member.definition.calculation, member.bindings, meanings, sources.map { SourceExplanation(it.callPath, sourceScope(it)) },
                member.remainingParameters(), member.fixedArguments, member.remainingGroupingCandidates(), member.remainingMemberParameters(), member.remainingRelationParameters(),
                member.definition.result.description?.let { CatalogOptional.of(it) } ?: CatalogOptional.absent())
        }
        private fun requireProjection(source: MemberSource): MemberSource.Projection = source as? MemberSource.Projection ?: throw IllegalArgumentException("event leaf requires an exact projection source")
        private fun memberMeaning(event: ExecutableEvent, source: MemberSource.Projection, slots: Set<String>, units: MutableMap<String, LogicUnitMeaning>): ProjectionMeaning {
            val eventRoles = roleMeanings("event", event.roles, listOfNotNull(event.baseFilter), units)
            val predicate = definition(event.baseFilter, units, "event", emptyList(), event.roles)
            val outputs = slots.associateWithTo(linkedMapOf<String, MemberSlotMeaning>()) { slot ->
                val output = source.definition.result.outputs.getValue(slot)
                val owner: String
                val access: RelationAccessPath
                val roles: Map<String, RelationAccessPath>
                if (output is MemberSourceDefinition.DimensionOutput) {
                    val binding = frozenDimensionBinding(output.bindingId, output.bindingVersion)
                    owner = "dimension:${binding.id}@${binding.version}"; access = binding.accessPath; roles = binding.roles
                } else {
                    owner = "member:${source.id}@${source.version}:$slot"; access = MemberSourceDefinition.propertyPath(output); roles = emptyMap()
                }
                val expression = source.outputExpressions.getValue(slot)
                val meaning = requireNotNull(definition(expression, units, owner, access.joinPathRefs, roles))
                val path = rolePath(owner, emptyList(), access, units, linkedSetOf())
                val declaredRoles = roleMeanings(owner, roles, listOf(expression), units)
                if (output is MemberSourceDefinition.ScalarOutput) SlotMeaning(output.displayName, output.role, output.valueType, output.valueContract, meaning, path, declaredRoles)
                else ContainerSlotMeaning(output.displayName, output.role, MemberSourceDefinition.containerShape(output), meaning, path, declaredRoles)
            }
            return ProjectionMeaning(source.definition.event, eventRoles, predicate, outputs, units.values.toList(), eventCollections(source.definition.event.eventId, source.definition.event.eventVersion),
                source.definition.result.description?.let { CatalogOptional.of(it) } ?: CatalogOptional.absent())
        }
        private fun eventCollections(id: String, version: Long): List<SealedSourceCollectionMeaning> {
            val event = snapshots.read(snapshots.require("EVENT", id, version), EventExecutionSnapshot::class.java)
            val topology = snapshots.read(snapshots.require("TOPOLOGY", event.topologyId, event.topologyVersion), TopologyExecutionSnapshot::class.java)
            return sourceCollections(topology.backboneLogicalTableId, topology.backboneLogicalTableVersion)
        }
        private fun sourceCollections(id: String, version: Long): List<SealedSourceCollectionMeaning> {
            val bindings = physicalByLogical["$id@$version"].orEmpty()
            require(bindings.isNotEmpty()) { "frozen source collection position has no physical binding" }
            val source = bindings.first()
            require(bindings.all { it.bindingId == source.bindingId && it.bindingVersion == source.bindingVersion && it.physicalTableId == source.physicalTableId &&
                it.physicalTableVersion == source.physicalTableVersion && it.sourceConstraints == source.sourceConstraints }) { "frozen source collection position has inconsistent physical bindings" }
            return source.sourceConstraints.sourceCollections.map { record ->
                require(record.dataSourceId == source.dataSourceId && record.sourceIdentityHash == source.sourceConstraints.sourceIdentityHash &&
                    record.collection.table.catalog == source.catalogName?.takeIf { it.isNotEmpty() } && record.collection.table.schema == source.schemaName && record.collection.table.table == source.tableName) { "frozen source collection differs from its physical position" }
                SealedSourceCollectionMeaning(record.id, record.sourceAuthority, record.scope, record.collection.ruleIds, SealedSourceCollectionMeaning.MEANING)
            }
        }
        private fun optionalCollections(collections: List<SealedSourceCollectionMeaning>): CatalogOptional<List<SealedSourceCollectionMeaning>> = if (collections.isEmpty()) CatalogOptional.absent() else CatalogOptional.of(collections)
        private fun definition(expression: SemanticExpression?, units: MutableMap<String, LogicUnitMeaning>, owner: String, local: List<RelationAccessPath.JoinRef>, roles: Map<String, RelationAccessPath>): PublicExpression? {
            if (expression == null) return null
            SemanticExpressionInspector.references(expression).forEach { reference ->
                require(reference.referenceKind != SemanticExpression.ReferenceKind.PHYSICAL_COLUMN) { "semantic definitions cannot expose physical references" }
                addUnit(reference.id, reference.version, units)
            }
            return this.expression(expression, listOf(owner), local, roles, units)
        }
        private fun expression(expression: SemanticExpression, scope: List<String>, local: List<RelationAccessPath.JoinRef>, roles: Map<String, RelationAccessPath>, units: MutableMap<String, LogicUnitMeaning>): PublicExpression =
            PublicExpression.project(expression) { exists ->
                val child = scope + exists.instanceId
                val parent = (exists.from as? SemanticExpression.RoleParent)?.let { requireNotNull(roles[it.role]) { "quantifier parent role is absent" }.joinPathRefs } ?: local
                val access = rolePath(child.joinToString("/"), parent, exists.accessPath, units, linkedSetOf())
                PublicExpression.Exists(exists.instanceId, exists.from, access, this.expression(exists.predicate, child, parent + exists.accessPath.joinPathRefs, roles, units))
            }
        private fun addUnit(id: String, version: Long, units: MutableMap<String, LogicUnitMeaning>) {
            val reference = snapshots.require("LOGIC_UNIT", id, version)
            val shape = valueTypes.shape(id, version)
            units.putIfAbsent("$id@$version", LogicUnitMeaning(id, version, reference.objectDisplayName, if (shape == null) valueTypes.logicType(id, version) else null, shape))
        }
        private fun dimensionValueTypes(types: QueryExecutionTypes, id: String, version: Long, scopes: Set<DimensionScope>): Map<String, ScalarValueType> = scalarTypes(valueTypes.dimensions(
            release.dimensionMappings.filter { it.analysisDimensionId == id && it.analysisDimensionVersion == version && DimensionScope(it.levelCode, it.eventId, it.eventVersion) in scopes }, types))
        private fun scalarTypes(values: Map<String, String>): Map<String, ScalarValueType> = values.mapValues { ScalarValueType.valueOf(it.value) }
        private fun timeValueTypes(id: String, version: Long, scopes: Set<DimensionScope>): Map<String, ScalarValueType> {
            val result = linkedMapOf<String, ScalarValueType>()
            release.timeAnchorRule.filter { it.analysisDimensionId == id && it.analysisDimensionVersion == version && DimensionScope(it.levelCode, it.eventId, it.eventVersion) in scopes }.forEach { rule ->
                val type = valueTypes.logicType(rule.effectiveTimeLogicUnitId, rule.effectiveTimeLogicUnitVersion)
                val previous = result.putIfAbsent(rule.levelCode, type)
                require(previous == null || previous == type) { "published time level has incompatible event value types" }
            }
            require(result.isNotEmpty()) { "published time dimension has no typed anchors" }
            return result
        }
    }
}
