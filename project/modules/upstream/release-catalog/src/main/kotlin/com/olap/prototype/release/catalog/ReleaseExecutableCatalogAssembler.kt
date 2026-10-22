package com.olap.prototype.release.catalog

import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.calculation.MetricInstance
import com.olap.prototype.calculation.MetricRef
import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.contract.JdbcColumnType
import com.olap.prototype.contract.NativeTableDependency
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.engine.catalog.*
import com.olap.prototype.event.PublishedDimensionBinding
import com.olap.prototype.event.RelationFilter
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.SemanticExpressionCodec
import com.olap.prototype.member.MemberSource
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.TemporalJoinContract
import com.olap.prototype.query.contract.*
import com.olap.prototype.release.contract.*
import com.olap.prototype.warehouse.contract.JdbcTableConstraints
import com.olap.prototype.warehouse.contract.SnapshotUniqueKey
import com.olap.prototype.warehouse.contract.SourceBindingRef
import java.time.Instant

class ReleaseExecutableCatalogAssembler(private val connectors: ConnectorRegistry) : ReleaseExecutableCatalogCompiler {
    override fun assemble(release: ReleaseCatalogView, assets: ExecutableCatalog, capability: QueryCapabilityView): PublishedExecutableCatalog {
        val adoptions = ReleaseObjectIndex(release.objectVersionRefs).adoptions(capability.metricCandidates.map { it.instance })
        val references = release.objectVersionRefs.map { PublishedObjectVersionRef(it.objectType, it.objectId, it.objectVersion.toString(), it.objectCode, it.objectDisplayName, it.refRole) }
        return PublishedExecutableCatalog(PublishedExecutableCatalog.FORMAT_VERSION,
            QueryCapabilityRequest(release.scenarioId, release.scenarioVersion, release.releaseId, release.releaseVersion), capability, assets,
            adoptions, references, Instant.now(), null).also { it.validate() }
    }
    override fun compileAssets(releaseCatalog: ReleaseCatalogView, metricReferences: List<ScenarioMetricReference>): ExecutableCatalog = AssetAssembly(releaseCatalog, metricReferences).complete()

    /** 只有本次装配持有可变索引；完整目录仅在规则全部收齐后构造并校验。 */
    private inner class AssetAssembly(private val release: ReleaseCatalogView, metricReferences: List<ScenarioMetricReference>) {
        private val metricReferences = java.util.List.copyOf(metricReferences)
        private val snapshots = ReleaseObjectIndex(release.objectVersionRefs)
        private val metrics = linkedMapOf<String, ExecutableMetric>()
        private val members = linkedMapOf<String, MemberSource>()
        private val relations = linkedMapOf<String, RelationFilter>()
        private val analysisDimensions = linkedMapOf<String, ExecutableAnalysisDimension>()
        private val events = linkedMapOf<String, ExecutableEvent>()
        private val topologies = linkedMapOf<String, ExecutableTopology>()
        private val dimensions = linkedMapOf<String, ExecutableDimension>()
        private val dimensionBindings = linkedMapOf<String, ExecutableDimensionBinding>()
        private val logicUnits = linkedMapOf<String, ExecutableLogicUnit>()
        private val timeAnchors = linkedMapOf<String, ExecutableTimeAnchor>()
        private val joinPaths = linkedMapOf<String, ExecutableJoinPath>()
        private val physicalBindings = linkedMapOf<String, ExecutablePhysicalExecutionBinding>()
        init {
            ProducerContracts.requireText(release.releaseId, "releaseId"); ProducerContracts.requireText(release.releaseVersion, "releaseVersion")
        }
        fun complete(): ExecutableCatalog {
            release.objectVersionRefs.filter { it.objectType == "LOGIC_UNIT" }.forEach(::addLogicUnit)
            attachNativeTables()
            attachSourceProofs()
            release.objectVersionRefs.filter { it.objectType != "LOGIC_UNIT" }.forEach(::addObject)
            release.objectVersionRefs.filter { it.objectType == "DIMENSION_EVENT_BINDING" }.forEach(::addDimensionBinding)
            release.dimensionMappings.forEach(::addAnalysisDimensionMapping)
            release.timeAnchorRule.forEach(::addTimeAnchor)
            release.objectVersionRefs.filter { it.objectType == "MEMBER_SOURCE" }.forEach { reference ->
                val member = read(reference, PublishedMemberSource::class.java, "invalid frozen member source")
                if (member.source.id != reference.objectId || member.source.version != reference.objectVersion) throw ProducerContractException("member snapshot coordinate differs from frozen reference")
                members[versionKey(member.source.id, member.source.version)] = member.source
            }
            val instances = completeInstances()
            val catalog = ExecutableCatalog(ExecutableCatalogManifest("${release.releaseId}@${release.releaseVersion}#${release.scenarioId}@${release.scenarioVersion}", ExecutableCatalogManifest.SCHEMA_VERSION),
                metrics, instances, members, relations, analysisDimensions, events, topologies, dimensions, dimensionBindings, logicUnits, timeAnchors, joinPaths, physicalBindings)
            try { catalog.validate() }
            catch (failure: IllegalArgumentException) { throw ProducerContractException("invalid frozen executable assets: ${failure.message}", failure) }
            return catalog
        }
        private fun completeInstances(): Map<String, MetricInstance> {
            val roots = metricReferences.map { snapshots.scenarioAdoption(it).instance }
            val instances = linkedMapOf<String, MetricInstance>()
            snapshots.adoptions(roots).values.forEach { adoption ->
                if (instances.putIfAbsent(adoption.instance.id, adoption.computation) != null) throw ProducerContractException("root metric binding is repeated or ambiguous")
            }
            return instances
        }
        private fun attachSourceProofs() {
            release.objectVersionRefs.filter { it.objectType == "SOURCE_UNIQUE_KEY" }.forEach { reference ->
                val proof = read(reference, SourceUniqueKeySnapshot::class.java, "invalid frozen source unique proof")
                if (proof.proofId != reference.objectId || reference.objectVersion != 1L) throw ProducerContractException("source proof differs from frozen identity")
                val owners = physicalBindings.filterValues { it.physicalTableId == proof.physicalTableId && it.physicalTableVersion == proof.physicalTableVersion.toString() }
                if (owners.isEmpty()) throw ProducerContractException("source proof has no exact frozen physical owner")
                owners.forEach { (key, binding) ->
                    val evidence = binding.sourceConstraints
                    if (evidence.discoverySnapshotId != proof.discoverySnapshotId || evidence.schemaHash != proof.schemaHash || evidence.sourceIdentityHash != proof.sourceIdentityHash ||
                        !evidence.columnVersionRefs.keys.containsAll(proof.key.rule.columns)) throw ProducerContractException("source proof differs from its adopted investigation")
                    if (binding.snapshotUniqueKeys.any { it.rule.name == proof.key.rule.name }) throw ProducerContractException("duplicate frozen source proof name")
                    physicalBindings[key] = replaceBinding(binding, snapshotUniqueKeys = binding.snapshotUniqueKeys + proof.key)
                }
            }
        }
        private fun attachNativeTables() {
            val tables = linkedMapOf<String, NativeTableDependency>()
            release.objectVersionRefs.filter { it.objectType == "PHYSICAL_TABLE" }.forEach { reference ->
                val dependency = read(reference, NativeTableDependency::class.java, "invalid frozen native physical table dependency")
                val key = versionKey(reference.objectId, reference.objectVersion)
                requireNativeIdentity(key, dependency)
                merge(tables, mapOf(key to dependency), "NativeTableDependency", "release")
            }
            physicalBindings.values.forEach { binding ->
                tables[versionKey(binding.physicalTableId, binding.physicalTableVersion)]?.let { owner ->
                    val frozen = NativeTableDependency(binding.physicalTableId, binding.physicalTableVersion.toLong(), binding.dataSourceId, binding.dialect,
                        JdbcTableConstraints.Table(binding.catalogName, binding.schemaName, binding.tableName), binding.sourceConstraints)
                    if (owner != frozen) throw ProducerContractException("native physical table dependency conflicts with its frozen physical execution owner: ${owner.key()}")
                }
                binding.nativeTableDependencies.forEach { (key, dependency) ->
                    requireNativeIdentity(key, dependency)
                    if (dependency != tables[key]) throw ProducerContractException("LogicUnit native table dependency differs from its exact PHYSICAL_TABLE release reference: $key")
                }
            }
            tables.values.forEach { dependency ->
                val owners = physicalBindings.filterValues { dependency.matchesSource(SourceBindingRef(it.dataSourceId, it.sourceConstraints.sourceIdentityHash), it.sourceConstraints.connectorRef, it.dialect) }
                if (owners.isEmpty()) throw ProducerContractException("native physical table dependency has no exact source binding in this release: ${dependency.key()}")
                owners.forEach { (key, binding) ->
                    val dependencies = LinkedHashMap(binding.nativeTableDependencies)
                    merge(dependencies, mapOf(dependency.key() to dependency), "NativeTableDependency", key)
                    physicalBindings[key] = replaceBinding(binding, nativeTables = dependencies)
                }
            }
        }
        private fun addObject(reference: ReleaseObjectRef) {
            val key = versionKey(reference.objectId, reference.objectVersion)
            when (reference.objectType) {
                "RELATION_FILTER" -> {
                    val grant = read(reference, PublishedRelationFilter::class.java, "invalid frozen relation grant")
                    if (grant.relation.id != reference.objectId || grant.relation.version != reference.objectVersion) throw ProducerContractException("relation grant snapshot coordinate differs")
                    relations[key] = grant.relation
                }
                "METRIC_ADOPTION" -> {
                    val adoption = read(reference, MetricAdoption::class.java, "invalid metric adoption snapshot")
                    if (adoption.adoptionId != reference.objectId || adoption.subjectAreaVersion != reference.objectVersion) throw ProducerContractException("metric adoption snapshot coordinate differs")
                }
                "METRIC" -> {
                    val snapshot = read(reference, MetricExecutionSnapshot::class.java, "invalid frozen metric snapshot")
                    val fact = snapshot.definition as? MetricDefinition.FactMeasure
                    if (fact != null) requireReleaseObject("EVENT", fact.event.eventId, fact.event.eventVersion, "fact.event")
                    val metric = ExecutableMetric(MetricRef(reference.objectId, reference.objectVersion.toString()), snapshot.definition)
                    metrics[metric.reference.key()] = metric
                }
                "EVENT" -> {
                    val snapshot = read(reference, EventExecutionSnapshot::class.java, "invalid frozen event snapshot")
                    snapshot.eventTimeLogicUnitId?.let { requireLogicUnit(it, requireNotNull(snapshot.eventTimeLogicUnitVersion), "event_snapshot.event_time_logic_unit") }
                    events[key] = ExecutableEvent(reference.objectId, reference.objectVersion.toString(), snapshot.topologyId, snapshot.topologyVersion.toString(),
                        reference.objectDisplayName, snapshot.identity, snapshot.roles, snapshot.baseFilter,
                        snapshot.eventTimeLogicUnitId, snapshot.eventTimeLogicUnitVersion?.toString())
                }
                "TOPOLOGY" -> addTopology(reference)
                "JOIN_PATH" -> addJoinPath(reference)
                "ANALYSIS_DIMENSION" -> analysisDimensions.putIfAbsent(key, ExecutableAnalysisDimension(reference.objectId, reference.objectVersion.toString(), reference.objectDisplayName,
                    reference.objectCode, reference.objectCode, null, emptyList(), emptyList()))
                else -> kotlin.Unit
            }
        }
        private fun addLogicUnit(reference: ReleaseObjectRef) {
            val snapshot = read(reference, LogicUnitExecutionSnapshot::class.java, "release_object_ref.LOGIC_UNIT snapshot_proto invalid")
            requireLogicUnitSnapshot(snapshot)
            if (reference.objectId != snapshot.logicUnitId || reference.objectVersion != snapshot.logicUnitVersion) throw ProducerContractException("release_object_ref.LOGIC_UNIT snapshot identity mismatch: ${reference.objectId}@${reference.objectVersion}")
            mergePhysicalBinding(snapshot)
            logicUnits[versionKey(reference.objectId, reference.objectVersion)] = ExecutableLogicUnit(reference.objectId, reference.objectVersion.toString(),
                snapshot.valueType?.let(ScalarValueType::valueOf), snapshot.valueShape, null, null, reference.objectCode, reference.objectDisplayName,
                snapshot.physicalExecutionBindingId, snapshot.physicalExecutionBindingVersion, listOf(snapshot.logicUnitId))
        }
        private fun requireLogicUnitSnapshot(snapshot: LogicUnitExecutionSnapshot) {
            ProducerContracts.requireText(snapshot.physicalExecutionBindingId, "logic_unit_snapshot.physicalExecutionBindingId")
            ProducerContracts.requireText(snapshot.physicalExecutionBindingVersion, "logic_unit_snapshot.physicalExecutionBindingVersion")
            ProducerContracts.requireText(snapshot.logicUnitId, "logic_unit_snapshot.logicUnitId"); ProducerContracts.requireVersion(snapshot.logicUnitVersion, "logic_unit_snapshot.logicUnitVersion")
            snapshot.valueType?.let { ScalarValueType.valueOf(ProducerContracts.requireText(it, "logic_unit_snapshot.valueType")) }
            if (snapshot.catalogName == null) throw ProducerContractException("logic_unit_snapshot.catalogName is required")
            ProducerContracts.requireText(snapshot.schemaName, "logic_unit_snapshot.schemaName"); ProducerContracts.requireText(snapshot.tableName, "logic_unit_snapshot.tableName")
            ProducerContracts.requireText(snapshot.dataSourceId, "logic_unit_snapshot.dataSourceId"); ProducerContracts.requireText(snapshot.dialect, "logic_unit_snapshot.dialect")
            snapshot.logicUnitBindings.forEach { (reference, _) ->
                if (!reference.matches(Regex(".+@[1-9][0-9]*"))) throw ProducerContractException("logic_unit_snapshot.logicUnitBindings key 必须是 exact LogicUnitRef: $reference")
            }
            snapshot.physicalColumnBindings.forEach { (reference, column) ->
                if (!reference.matches(Regex(".+@[1-9][0-9]*"))) throw ProducerContractException("logic_unit_snapshot.physicalColumnBindings key 必须是 exact PhysicalColumnRef: $reference")
                ProducerContracts.requireText(column, "logic_unit_snapshot.physicalColumnBindings.physicalColumn")
            }
            if (snapshot.physicalColumnTypes.keys != snapshot.physicalColumnBindings.keys) throw ProducerContractException("logic_unit_snapshot.physicalColumnTypes must describe every exact physical column ref")
            snapshot.nativeTableDependencies.forEach { (key, dependency) ->
                requireNativeIdentity(key, dependency)
                dependency.requireSource(SourceBindingRef(snapshot.dataSourceId, snapshot.sourceConstraints.sourceIdentityHash), snapshot.sourceConstraints.connectorRef, snapshot.dialect)
            }
        }
        private fun mergePhysicalBinding(snapshot: LogicUnitExecutionSnapshot) {
            val key = versionKey(snapshot.physicalExecutionBindingId, snapshot.physicalExecutionBindingVersion)
            val previous = physicalBindings[key]
            if (previous == null) {
                physicalBindings[key] = ExecutablePhysicalExecutionBinding(snapshot.physicalExecutionBindingId, snapshot.physicalExecutionBindingVersion, null, null,
                    snapshot.logicUnitId, snapshot.logicUnitVersion.toString(), snapshot.logicalTableId, snapshot.logicalTableVersion.toString(), snapshot.physicalTableId,
                    snapshot.physicalTableVersion.toString(), snapshot.dataSourceId, snapshot.sourceConstraints.connectorRef,
                    connectors.require(snapshot.sourceConstraints.connectorRef).compilation.tablePath(snapshot.catalogName, snapshot.schemaName, snapshot.tableName), snapshot.dialect,
                    snapshot.catalogName, snapshot.schemaName, snapshot.tableName, snapshot.sourceConstraints, emptyList(), snapshot.nativeTableDependencies,
                    parseLogicUnits(snapshot), snapshot.physicalColumnBindings, snapshot.physicalColumnTypes)
                return
            }
            if (previous.logicalTableId != snapshot.logicalTableId || previous.logicalTableVersion != snapshot.logicalTableVersion.toString() || previous.physicalTableId != snapshot.physicalTableId ||
                previous.physicalTableVersion != snapshot.physicalTableVersion.toString() || previous.catalogName != snapshot.catalogName || previous.schemaName != snapshot.schemaName ||
                previous.tableName != snapshot.tableName || previous.dataSourceId != snapshot.dataSourceId || previous.dialect != snapshot.dialect || previous.sourceConstraints != snapshot.sourceConstraints)
                throw ProducerContractException("physical execution binding snapshot conflict: $key")
            val units = LinkedHashMap(previous.logicUnitBindings).also { merge(it, parseLogicUnits(snapshot), "LogicUnit", key) }
            val columns = LinkedHashMap(previous.physicalColumnBindings).also { merge(it, snapshot.physicalColumnBindings, "PhysicalColumn", key) }
            val types = LinkedHashMap(previous.physicalColumnTypes).also { merge(it, snapshot.physicalColumnTypes, "PhysicalColumnType", key) }
            val tables = LinkedHashMap(previous.nativeTableDependencies).also { merge(it, snapshot.nativeTableDependencies, "NativeTableDependency", key) }
            physicalBindings[key] = replaceBinding(previous, nativeTables = tables, units = units, columns = columns, types = types)
        }
        private fun addTopology(reference: ReleaseObjectRef) {
            val snapshot = read(reference, TopologyExecutionSnapshot::class.java, "invalid frozen topology snapshot")
            if (reference.objectId != snapshot.topologyId || reference.objectVersion != snapshot.topologyVersion) throw ProducerContractException("release_object_ref.TOPOLOGY snapshot identity mismatch: ${reference.objectId}@${reference.objectVersion}")
            topologies[versionKey(reference.objectId, reference.objectVersion)] = ExecutableTopology(reference.objectId, reference.objectVersion.toString(),
                snapshot.topologyImplementationNamespaceCode, snapshot.topologyImplementationNamespaceDisplayName, snapshot.backboneLogicalTableId,
                snapshot.backboneLogicalTableVersion.toString(), emptyList(), snapshot.joinPathRefs.map { ExecutableJoinPathRef(it.joinPathId, it.joinPathVersion.toString()) })
        }
        private fun addJoinPath(reference: ReleaseObjectRef) {
            val snapshot = read(reference, ReleaseJoinPathSnapshot::class.java, "invalid frozen join path snapshot")
            if (reference.objectId != snapshot.joinPathId || reference.objectVersion != snapshot.joinPathVersion) throw ProducerContractException("release_object_ref.JOIN_PATH snapshot identity mismatch: ${reference.objectId}@${reference.objectVersion}")
            val keys = snapshot.keys.map { key ->
                requireLogicUnit(key.sourceLogicUnitId, key.sourceLogicUnitVersion, "join_path.source_logic_unit")
                requireLogicUnit(key.targetLogicUnitId, key.targetLogicUnitVersion, "join_path.target_logic_unit")
                ExecutableJoinKey(key.order, key.sourceLogicUnitId, key.sourceLogicUnitVersion.toString(), key.targetLogicUnitId, key.targetLogicUnitVersion.toString())
            }
            val temporal = snapshot.temporal
            joinPaths[versionKey(reference.objectId, reference.objectVersion)] = ExecutableJoinPath(reference.objectId, reference.objectVersion.toString(), snapshot.topologyId,
                snapshot.topologyVersion.toString(), snapshot.sourceLogicalTableId, snapshot.sourceLogicalTableVersion.toString(), snapshot.targetLogicalTableId,
                snapshot.targetLogicalTableVersion.toString(), snapshot.joinType, snapshot.cardinality, temporal, snapshot.nullPolicy, keys)
        }
        private fun addDimensionBinding(reference: ReleaseObjectRef) {
            val snapshot = read(reference, PublishedDimensionBinding::class.java, "invalid frozen dimension binding definition")
            if (snapshot.id != reference.objectId || snapshot.version != reference.objectVersion) throw ProducerContractException("frozen dimension binding coordinate differs")
            val unit = requireLogicUnit(snapshot.logicUnitId, snapshot.logicUnitVersion, "dimension binding source")
            val binding = ExecutableDimensionBinding(snapshot.id, snapshot.version.toString(), snapshot.dimensionId, snapshot.dimensionVersion.toString(), snapshot.event.eventId,
                snapshot.event.eventVersion.toString(), snapshot.topologyId, snapshot.topologyVersion.toString(), snapshot.logicUnitId, snapshot.logicUnitVersion.toString(),
                snapshot.expression, snapshot.accessPath, snapshot.roles, snapshot.accessPath.joinPathRefs.map { ExecutableJoinPathRef(it.joinPathId, it.joinPathVersion.toString()) },
                unit.physicalExecutionBindingId, unit.physicalExecutionBindingVersion)
            if (dimensionBindings.putIfAbsent(versionKey(snapshot.id, snapshot.version), binding) != null) throw ProducerContractException("duplicate frozen dimension binding")
        }
        private fun addAnalysisDimensionMapping(mapping: ReleaseDimensionMapping) {
            val reference = requireReleaseObject("ANALYSIS_DIMENSION", mapping.analysisDimensionId, mapping.analysisDimensionVersion, "release_dimension_mapping.analysis_dimension")
            if (reference.objectDisplayName != mapping.analysisDimensionDisplayName) throw ProducerContractException("release_dimension_mapping displayName differs from release_object_ref: ${mapping.analysisDimensionId}@${mapping.analysisDimensionVersion}")
            val key = versionKey(mapping.analysisDimensionId, mapping.analysisDimensionVersion)
            val previous = analysisDimensions[key]
            val levels = previous?.levels.orEmpty().toMutableList()
            if (levels.none { it.levelCode == mapping.levelCode }) levels.add(ExecutableAnalysisDimensionLevel(mapping.levelCode, mapping.levelDisplayName, null))
            val eventMapping = ExecutableAnalysisDimensionEventMapping(mapping.mappingId, mapping.analysisDimensionId, mapping.analysisDimensionVersion.toString(), mapping.levelCode,
                mapping.eventId, mapping.eventVersion.toString(), mapping.dimensionId, mapping.dimensionVersion.toString(), mapping.dimensionBindingId,
                mapping.dimensionBindingVersion.toString(), mapping.logicUnitId, mapping.logicUnitVersion.toString(), mapping.valueAlignmentKey, mapping.valueAlignmentStatus)
            analysisDimensions[key] = ExecutableAnalysisDimension(reference.objectId, reference.objectVersion.toString(), reference.objectDisplayName, reference.objectCode, reference.objectCode,
                if (previous == null) mapping.levelCode else previous.defaultLevelCode, levels, previous?.eventMappings.orEmpty() + eventMapping)
            dimensions.putIfAbsent(versionKey(mapping.dimensionId, mapping.dimensionVersion), ExecutableDimension(mapping.dimensionId, mapping.dimensionVersion.toString(), null,
                mapping.dimensionCodeNamespace, null, mapping.dimensionDisplayName))
            requireLogicUnit(mapping.logicUnitId, mapping.logicUnitVersion, "release_dimension_mapping.logic_unit")
            val binding = requireNotNull(dimensionBindings[versionKey(mapping.dimensionBindingId, mapping.dimensionBindingVersion)]) { "frozen dimension expression binding is missing" }
            val expected = binding.accessPath.joinPathRefs.map { ReleaseJoinPathRef(it.joinPathId, it.joinPathVersion) }
            if (expected != mapping.joinPathRefs || binding.expression != mapping.expression ||
                binding.eventId != mapping.eventId || binding.eventVersion != mapping.eventVersion.toString() || binding.dimensionId != mapping.dimensionId || binding.dimensionVersion != mapping.dimensionVersion.toString())
                throw ProducerContractException("dimension mapping differs from its exact frozen expression owner")
        }
        private fun addTimeAnchor(rule: ReleaseTimeAnchorRule) {
            val event = events[versionKey(rule.eventId, rule.eventVersion)] ?: throw ProducerContractException("release_time_anchor_rule missing release_object_ref.EVENT: ${rule.eventId}@${rule.eventVersion}")
            if (event.topologyId != rule.topologyId || event.topologyVersion != rule.topologyVersion.toString()) throw ProducerContractException("release_time_anchor_rule topology differs from event snapshot: ${rule.eventId}@${rule.eventVersion}")
            val unitKey = versionKey(rule.effectiveTimeLogicUnitId, rule.effectiveTimeLogicUnitVersion)
            val time = requireLogicUnit(rule.effectiveTimeLogicUnitId, rule.effectiveTimeLogicUnitVersion, "release_time_anchor_rule.effective_time_logic_unit")
            val key = ExecutableTimeAnchor.key(rule.eventId, rule.eventVersion.toString(), rule.analysisDimensionId, rule.analysisDimensionVersion.toString(), rule.levelCode)
            timeAnchors[key] = ExecutableTimeAnchor(rule.eventId, rule.eventVersion.toString(), rule.analysisDimensionId, rule.analysisDimensionVersion.toString(), rule.levelCode,
                rule.topologyId, rule.topologyVersion.toString(), rule.topologyImplementationNamespaceCode, rule.topologyImplementationNamespaceDisplayName,
                rule.effectiveTimeLogicUnitId, rule.effectiveTimeLogicUnitVersion.toString(), rule.effectiveTimeLogicUnitDisplayName,
                time.physicalExecutionBindingId, time.physicalExecutionBindingVersion, rule.timeRole)
            if (versionKey(rule.topologyId, rule.topologyVersion) !in topologies) throw ProducerContractException("release_time_anchor_rule missing release_object_ref.TOPOLOGY: ${rule.topologyId}@${rule.topologyVersion}")
            logicUnits[unitKey] = ExecutableLogicUnit(time.logicUnitId, time.logicUnitVersion, time.valueType, time.valueShape, rule.topologyId, rule.topologyVersion.toString(),
                time.logicUnitCode, time.displayName, time.physicalExecutionBindingId, time.physicalExecutionBindingVersion, time.outputSymbols)
        }

        private fun replaceBinding(binding: ExecutablePhysicalExecutionBinding, snapshotUniqueKeys: List<SnapshotUniqueKey> = binding.snapshotUniqueKeys,
            nativeTables: Map<String, NativeTableDependency> = binding.nativeTableDependencies, units: Map<String, SemanticExpression> = binding.logicUnitBindings,
            columns: Map<String, String> = binding.physicalColumnBindings, types: Map<String, JdbcColumnType> = binding.physicalColumnTypes): ExecutablePhysicalExecutionBinding =
            ExecutablePhysicalExecutionBinding(binding.bindingId, binding.bindingVersion, binding.eventId, binding.eventVersion, binding.logicUnitId, binding.logicUnitVersion,
                binding.logicalTableId, binding.logicalTableVersion, binding.physicalTableId, binding.physicalTableVersion, binding.dataSourceId, binding.connectorRef,
                binding.qualifiedTablePath, binding.dialect, binding.catalogName, binding.schemaName, binding.tableName, binding.sourceConstraints, snapshotUniqueKeys, nativeTables, units, columns, types)
        private fun requireNativeIdentity(key: String, dependency: NativeTableDependency) {
            if (key != dependency.key()) throw ProducerContractException("native physical table dependency requires its exact identity and version: $key")
        }
        private fun requireReleaseObject(type: String, id: String, version: Long, field: String): ReleaseObjectRef {
            ProducerContracts.requireText(id, "$field.objectId"); ProducerContracts.requireVersion(version, "$field.objectVersion")
            return snapshots.require(type, id, version)
        }
        private fun requireLogicUnit(id: String, version: Long, field: String): ExecutableLogicUnit {
            val unit = logicUnits[versionKey(id, version)] ?: throw ProducerContractException("$field missing release_object_ref.LOGIC_UNIT: $id@$version")
            ProducerContracts.requireText(unit.physicalExecutionBindingId, "$field.physicalExecutionBindingId")
            ProducerContracts.requireText(unit.physicalExecutionBindingVersion, "$field.physicalExecutionBindingVersion")
            return unit
        }
        private fun parseLogicUnits(snapshot: LogicUnitExecutionSnapshot): Map<String, SemanticExpression> = snapshot.logicUnitBindings
        private fun <T> merge(target: MutableMap<String, T>, source: Map<String, T>, kind: String, owner: String) {
            source.forEach { (key, value) ->
                val previous = target.putIfAbsent(key, value)
                if (previous != null && previous != value) throw ProducerContractException("$kind binding conflict: $key, physicalExecutionBinding=$owner")
            }
        }
        private fun <T : Any> read(reference: ReleaseObjectRef, type: Class<T>, message: String): T = try { snapshots.read(reference, type) }
        catch (failure: ProducerContractException) { throw failure }
        catch (failure: Exception) { throw ProducerContractException("$message: ${reference.objectId}@${reference.objectVersion}", failure) }
        private fun versionKey(id: String, version: Any): String = ProducerContracts.requireText(id, "versionKey.id") + "@" + ProducerContracts.requireText(version.toString(), "versionKey.version")
    }
}
