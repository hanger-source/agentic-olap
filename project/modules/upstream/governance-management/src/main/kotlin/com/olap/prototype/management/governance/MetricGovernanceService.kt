package com.olap.prototype.management.governance

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.calculation.MetricCalculation
import com.olap.prototype.calculation.MetricDefinition
import com.olap.prototype.calculation.MetricRef
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.event.EventRef
import com.olap.prototype.event.RelationAccessPath
import com.olap.prototype.expression.*
import com.olap.prototype.governance.contract.*
import com.olap.prototype.metric.*
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.query.SetOutputContract
import com.olap.prototype.storage.PlatformDatabase
import java.time.Instant
import java.util.UUID

class MetricGovernanceService(private val database: PlatformDatabase, private val logicalModel: LogicalModelGovernanceService,
    private val topologies: TopologyGovernanceService, private val events: EventGovernanceService,
    private val dimensionBindings: DimensionBindingReferenceResolver, private val nativeExpressions: NativeExpressionBindings) {
    private val repository = MetricGovernanceRepository(database)
    private val snapshots = GovernanceSnapshotReader(database)
    private val memberSources = SubjectAreaAdoptionRepository(database)
    fun metricSnapshot(id: String, version: Long): MetricRecord = snapshots.metric(id, version)
    fun metricExecutionDefinition(id: String, version: Long): MetricDefinition = snapshots.metricExecutionDefinition(id, version)
    fun dimensionSnapshot(id: String, version: Long): DimensionRecord = snapshots.dimension(id, version)
    fun dimensionEventBindingSnapshot(id: String, version: Long): DimensionEventBindingRecord = snapshots.dimensionEventBinding(id, version)
    fun subjectAreaSnapshot(id: String, version: Long): SubjectAreaRecord = snapshots.subjectArea(id, version)
    fun analysisDimensionSnapshot(id: String, version: Long): AnalysisDimensionRecord = snapshots.analysisDimension(id, version)
    fun createMetric(request: MetricCreateRequest): MetricCreateResult = database.inTransaction { createFact(request, null) }
    fun reviseMetric(request: GovernanceRevisionRequest<MetricCreateRequest>): MetricCreateResult = database.inTransaction { createFact(request.definition, request) }
    fun createMetricCalculation(request: MetricCalculationCreateRequest): MetricCalculationCreateResult = database.inTransaction { createCalculation(request, null) }
    fun reviseMetricCalculation(request: GovernanceRevisionRequest<MetricCalculationCreateRequest>): MetricCalculationCreateResult = database.inTransaction { createCalculation(request.definition, request) }

    fun validateCalculation(calculation: Calculation, own: MetricRef?): Map<String, Calculation.Call> {
        val calls = calculation.callSites().associateTo(linkedMapOf()) { "root/${it.path.joinToString("/")}" to it.call }
        calls.forEach { (position, call) ->
            when (val target = call.target) {
                is Calculation.Measurement -> {
                    val slot = calculation.measurementSlots.firstOrNull { it.name == target.slot } ?: throw ProducerContractException("undeclared measurement slot: ${target.slot}")
                    validateArguments(calculation, call, slot.parameters, position)
                }
                is Calculation.Fixed -> {
                    if (own != null && target.metric == own) throw ProducerContractException("metric calculation cannot call itself: $position")
                    val child = metricSnapshot(target.metric.id, target.metric.version.toLong()).definition
                    if (child is MetricCalculation) validateNested(calculation, call, child.calculation, position)
                    else if (call.dimensionSlots.isNotEmpty() || call.memberSlots.isNotEmpty() || call.measurementSlots.isNotEmpty() || call.relationSlots.isNotEmpty() || call.arguments.isNotEmpty())
                        throw ProducerContractException("fact measure has no calculation slots or parameters: $position")
                }
            }
        }
        return calls
    }
    private fun validateNested(parent: Calculation, call: Calculation.Call, child: Calculation, position: String) {
        if (call.measurementSlots.keys != child.measurementSlots.mapTo(hashSetOf()) { it.name }) throw ProducerContractException("nested fixed call must map all measurement slots: $position")
        call.measurementSlots.values.forEach { name -> if (parent.measurementSlots.none { it.name == name }) throw ProducerContractException("nested measurement maps an unknown parent slot: $name") }
        if (call.dimensionSlots.keys != child.dimensionSlots.mapTo(hashSetOf()) { it.name } || call.memberSlots.keys != child.memberSlots.mapTo(hashSetOf()) { it.name })
            throw ProducerContractException("nested metric call must map every child slot and parameter exactly: $position")
        child.dimensionSlots.forEach { slot ->
            val actual = parent.dimensionSlots.firstOrNull { it.name == call.dimensionSlots[slot.name] } ?: throw ProducerContractException("unknown parent dimension slot")
            if (actual.dimensionId != slot.dimensionId || actual.dimensionVersion != slot.dimensionVersion || actual.role != slot.role) throw ProducerContractException("nested dimension identity or role differs: $position.${slot.name}")
        }
        child.memberSlots.forEach { slot ->
            val actual = parent.memberSlots.firstOrNull { it.name == call.memberSlots[slot.name] } ?: throw ProducerContractException("unknown parent member slot")
            if (actual.entity != slot.entity || actual.role != slot.role || actual.shape != slot.shape || !actual.outputs.keys.containsAll(slot.outputs.keys)) throw ProducerContractException("nested member shape or role differs: $position.${slot.name}")
            slot.outputs.forEach { (name, output) ->
                val mapped = actual.outputs[name]
                if (output is Calculation.MemberDimension) {
                    if (mapped !is Calculation.MemberDimension || call.dimensionSlots[output.dimensionSlot] != mapped.dimensionSlot) throw ProducerContractException("nested member output must map the exact parent dimension slot")
                } else if (output != mapped) throw ProducerContractException("nested member property meaning differs")
            }
        }
        validateRelations(parent, call, child, position)
        validateArguments(parent, call, child.parameters, position)
    }
    private fun validateRelations(parent: Calculation, call: Calculation.Call, child: Calculation, position: String) {
        if (call.relationSlots.keys != child.relationSlots.mapTo(hashSetOf()) { it.name })
            throw ProducerContractException("nested call must map all and only relation slots: $position")
        child.relationSlots.forEach { expected ->
            val actual = parent.relationSlots.firstOrNull { it.name == call.relationSlots[expected.name] }
                ?: throw ProducerContractException("nested relation maps an unknown parent slot: $position.${expected.name}")
            expected.keys?.let { keys ->
                if (actual.keys?.toSet() != keys.toSet())
                    throw ProducerContractException("nested relation requires its complete declared observation key: $position.${expected.name}")
            }
            val outputs = actual.outputs.associateBy { it.name }
            expected.outputs.forEach { output ->
                val supplied = outputs[output.name] ?: throw ProducerContractException("nested relation output is missing: $position.${expected.name}.${output.name}")
                try { requireRelationShape(output.shape, supplied.shape, call.dimensionSlots) }
                catch (failure: IllegalArgumentException) {
                    throw ProducerContractException("nested relation contract differs: $position.${expected.name}.${output.name}: ${failure.message}", failure)
                }
            }
        }
    }
    private fun requireRelationShape(expected: SetOutputContract.Shape, actual: SetOutputContract.Shape, dimensions: Map<String, String>) {
        when {
            expected is SetOutputContract.DimensionSlot && actual is SetOutputContract.DimensionSlot ->
                require(dimensions[expected.slot] == actual.slot) { "nested relation dimension must map the exact parent semantic slot" }
            expected is SetOutputContract.Quantity && actual is SetOutputContract.Quantity -> {
                val value = expected.valueContract
                val unit = (value.unit as? MetricValueContract.SlotDimension)?.let {
                    MetricValueContract.SlotDimension(requireNotNull(dimensions[it.slot]) { "nested relation quantity dimension is not mapped" }, it.axis)
                } ?: value.unit
                val adopted = MetricValueContract(unit, value.arithmetic, value.result, value.unitEquations, value.overflow)
                require(adopted.unit == actual.valueContract.unit) { "nested relation quantity must retain its exact unit meaning" }
                MetricValueContract.requireMeasurementValue(adopted, actual.valueContract)
                require(MetricValueContract.resultFits(actual.valueContract.result, adopted.result)) { "nested relation input cannot narrow the parent quantity capacity" }
            }
            expected is SetOutputContract.Row && actual is SetOutputContract.Row && expected.fields.size == actual.fields.size ->
                expected.fields.zip(actual.fields).forEach { (left, right) -> requireRelationShape(left, right, dimensions) }
            expected is SetOutputContract.Array && actual is SetOutputContract.Array -> requireRelationShape(expected.element, actual.element, dimensions)
            else -> require(expected == actual) { "nested relation changes an input's actual categorical, quantity or collection meaning" }
        }
    }
    private fun validateArguments(parent: Calculation, call: Calculation.Call, parameters: Map<String, Calculation.Parameter>, position: String) {
        if (!parameters.keys.containsAll(call.arguments.keys)) throw ProducerContractException("nested call contains undeclared parameters: $position")
        parameters.forEach { (name, expected) ->
            when (val binding = call.arguments[name]) {
                null -> if (expected.defaultValue == null) throw ProducerContractException("nested call lacks required parameter: $position.$name")
                is Calculation.Argument -> {
                    val actual = parent.parameters[binding.name]
                    if (actual == null || !expected.hasType(actual.kind, actual.valueType) || actual.valueContract != expected.valueContract) throw ProducerContractException("nested argument type differs: $position.$name")
                    if (expected.kind == Calculation.ParameterKind.GROUPING && !expected.allowsDimensions(actual.allowedDimensions)) throw ProducerContractException("parent grouping parameter exceeds the child's semantic dimension/version/role candidates")
                }
                is Calculation.GroupingSlots -> {
                    if (expected.kind != Calculation.ParameterKind.GROUPING) throw ProducerContractException("grouping slots only bind child grouping parameters")
                    binding.slots.forEach { slotName ->
                        val dimension = parent.dimensionSlots.firstOrNull { it.name == slotName } ?: throw ProducerContractException("grouping maps an unknown parent internal dimension slot")
                        if (!expected.allowsDimension(dimension)) throw ProducerContractException("parent internal grouping slot exceeds the child's semantic dimension/version/role candidates")
                    }
                }
                is Calculation.Value -> if (!expected.hasType(binding.parameterKind, binding.valueType)) throw ProducerContractException("nested value type differs: $position.$name")
            }
        }
    }
    private fun createCalculation(request: MetricCalculationCreateRequest, revision: GovernanceRevisionRequest<MetricCalculationCreateRequest>?): MetricCalculationCreateResult {
        val code = code(request.code)
        val id = revision?.objectId ?: "metric-${UUID.randomUUID()}"
        val actor = text(request.createdBy, "metric.created_by")
        val draft = GovernanceDraftVersions(database).start(GovernanceDraftVersions.Asset.METRIC, id, revision, actor)
        val calls = validateCalculation(request.definition.calculation, MetricRef(id, draft.version.toString()))
        val now = Instant.now()
        val metric = MetricRecord(id, code, text(request.displayName, "metric.display_name"), request.definition, request.description, text(request.owner, "metric.owner"), "DRAFT", draft.version, actor, now, actor, now)
        val version = version(metric, request.businessMeaning, draft.sourceVersion)
        val dependencies = calculationDependencies(metric, calls)
        persist(metric, version, dependencies)
        return MetricCalculationCreateResult(metric, version, dependencies)
    }
    private fun calculationDependencies(metric: MetricRecord, calls: Map<String, Calculation.Call>): List<MetricDependencyRecord> = buildList {
        calls.forEach { (position, call) ->
            val target = (call.target as? Calculation.Fixed)?.metric
            if (target != null) add(dependency(metric, "call:$position", position, "CHILD_METRIC", target.id, target.version.toLong(), call.callId))
        }
        val definition = metric.definition as MetricCalculation
        definition.calculation.memberSlots.forEach { slot -> slot.fixedSource?.let { add(dependency(metric, "member:${slot.name}", "member.${slot.name}", "MEMBER_SOURCE", it.id, it.version, slot.name)) } }
        definition.calculation.declaredDimensions().forEach { (position, slot) -> add(dependency(metric, position, position, "DIMENSION", slot.dimensionId, slot.dimensionVersion, slot.role)) }
    }
    private fun createFact(request: MetricCreateRequest, revision: GovernanceRevisionRequest<MetricCreateRequest>?): MetricCreateResult {
        val definition = request.definition
        val event = events.requireEvent(definition.event.eventId, definition.event.eventVersion)
        val code = code(request.code)
        val id = revision?.objectId ?: "metric-${UUID.randomUUID()}"
        val draft = GovernanceDraftVersions(database).start(GovernanceDraftVersions.Asset.METRIC, id, revision, request.createdBy)
        val actor = text(request.createdBy, "metric.created_by")
        val now = Instant.now()
        validateInput(event, definition.input, definition.roles)
        validateSnapshot(event, definition.snapshotRule, definition.distinct, definition.aggregation.name)
        val metric = MetricRecord(id, code, text(request.displayName, "metric.display_name"), definition, request.description, text(request.owner, "metric.owner"), "DRAFT", draft.version, actor, now, actor, now)
        val version = version(metric, request.businessMeaning, draft.sourceVersion)
        persist(metric, version, factDependencies(metric))
        return MetricCreateResult(metric, version)
    }
    private fun version(metric: MetricRecord, meaning: MetricBusinessMeaning, sourceVersion: Long?) = MetricVersionRecord("${metric.id}:v${metric.currentVersion}", metric.id, metric.currentVersion, "DRAFT",
        encodeDefinition(MetricSnapshots.metricVersionContent(metric, meaning)), "{}", sourceVersion, metric.createdBy, metric.createdAt, null, null, null, null, null, null)
    private fun dependency(metric: MetricRecord, suffix: String, scope: String, type: String, id: String, version: Long?, symbol: String?) =
        MetricDependencyRecord("${metric.id}:v${metric.currentVersion}:$suffix", metric.id, metric.currentVersion, scope, type, id, version, symbol, "BOUND", null, metric.createdAt)

    private fun factDependencies(metric: MetricRecord): List<MetricDependencyRecord> = buildList {
        val fact = metric.factMeasure()
        val event = events.requireEvent(fact.event.eventId, fact.event.eventVersion)
        fact.snapshotRule?.keys()?.forEach { add(dependency(metric, "SNAPSHOT:${it.id}@${it.version}", "SNAPSHOT", "LOGIC_UNIT", it.id, it.version, it.id)) }
        addAll(expressionDependencies(metric, "INPUT", fact.input))
        val unit = fact.valueContract.unit
        if (unit is MetricValueContract.EventDimension) {
            val binding = dimensionBindings.dimensionEventBindingSnapshot(unit.bindingId, unit.bindingVersion)
            if (binding.dimensionId != unit.axis.dimensionId || binding.dimensionVersion != unit.axis.dimensionVersion || binding.eventId != event.id || binding.eventVersion != fact.event.eventVersion)
                throw ProducerContractException("metric unit axis differs from its exact event dimension binding")
            val expression = requireNotNull(binding.expression) { "metric unit axis expression is required" }
            val memberType = SemanticExpressionValueType.resolve(expression, { ScalarValueType.fromLogicType(requireNotNull(snapshots.logicUnit(it.id, it.version).logicType)) }, nativeExpressions::function)
            if (memberType != unit.axis.memberType) throw ProducerContractException("metric unit member type differs from its exact semantic expression")
            add(dependency(metric, "UNIT_DIMENSION", "UNIT_DIMENSION", "DIMENSION_BINDING", unit.bindingId, unit.bindingVersion, unit.axis.role))
        }
        fact.privatePredicate?.let {
            events.requirePositionedExpression(event.id, fact.event.eventVersion, RelationAccessPath(emptyList()), fact.roles, it, "FILTERABLE", "metric private predicate")
            addAll(expressionDependencies(metric, "PRIVATE_FILTER", it))
        }
    }
    private fun expressionDependencies(metric: MetricRecord, field: String, expression: SemanticExpression): List<MetricDependencyRecord> =
        SemanticExpressionInspector.accesses(expression).distinct().map { access ->
            val ref = access.reference
            if (ref.referenceKind == SemanticExpression.ReferenceKind.PHYSICAL_COLUMN) throw ProducerContractException("metric expression cannot bypass LogicUnits")
            val scope = field + access.scopes.joinToString("") { "/${it.instanceId}" }
            dependency(metric, "$scope:${ref.id}@${ref.version}:${ref.role}", scope, ref.referenceKind.name, ref.id, ref.version, ref.role ?: ref.id)
        }
    private fun validateInput(event: EventRecord, expression: SemanticExpression, roles: Map<String, RelationAccessPath>) {
        if (SemanticExpressionInspector.references(expression).isEmpty() && SemanticExpressionInspector.quantifiers(expression).isEmpty()) throw ProducerContractException("metric input requires an explicit measure or relational condition")
        java.util.Map.copyOf(roles).forEach { (role, path) -> ScopedInputContract.requireId(role); topologies.requireAccessPath(event.topologyId, event.topologyVersion, path, "metric input role $role") }
        events.requirePositionedExpression(event.id, event.currentVersion, RelationAccessPath(emptyList()), roles, expression, "MEASURE", "metric input")
    }
    private fun validateSnapshot(event: EventRecord, rule: MetricSnapshotRule?, distinct: Boolean, aggregation: String) {
        if (rule == null) return
        rule.keys().forEach {
            topologies.requireReachableLogicUnit(event.topologyId, event.topologyVersion, it.id, it.version, "metric.snapshotRule")
            events.requireLogicUnitRef(event.id, event.currentVersion, it.id, it.version, "metric.snapshotRule")
        }
        if (rule.timeKey.id != event.eventTimeLogicUnitId || rule.timeKey.version != event.eventTimeLogicUnitVersion) throw ProducerContractException("snapshotRule.timeKey must be the exact event time LogicUnit")
        val identity = events.eventIdentity(event.id, event.currentVersion)
        val entity = identity.entityRoles[rule.entityRole]
        if (entity == null || rule.entityKeys != entity.map { MetricSnapshotRule.LogicUnitKey(it.logicUnitId, it.logicUnitVersion) }) throw ProducerContractException("snapshotRule.entityKeys must exactly match its declared event entity role")
        if (!rule.keys().containsAll(identity.recordKey.map { MetricSnapshotRule.LogicUnitKey(it.logicUnitId, it.logicUnitVersion) })) throw ProducerContractException("snapshotRule entity/time/tie keys must cover the declared event grain")
        if (rule.reduction == MetricSnapshotRule.Reduction.MEAN && (distinct || aggregation == MetricAggregation.COUNT.name)) throw ProducerContractException("MEAN reduces observed values per entity; COUNT/distinct need a separate population metric")
    }

    private fun persist(metric: MetricRecord, version: MetricVersionRecord, dependencies: List<MetricDependencyRecord>) {
        dependencies.forEach { validateDependency(metric, it) }
        saveMetric(metric); saveMetricVersion(version); dependencies.forEach(repository::saveMetricDependency)
    }
    private fun validateDependency(metric: MetricRecord, record: MetricDependencyRecord) {
        when (record.dependencyType) {
            "LOGIC_UNIT", "ROLE_LOGIC_UNIT" -> validateExpressionDependency(metric.factMeasure(), record)
            "DIMENSION_BINDING" -> {
                val version = positive(record.dependencyVersion, "metric_expression_dependency.dependency_version")
                val binding = dimensionBindings.dimensionEventBindingSnapshot(record.dependencyId, version)
                val fact = metric.factMeasure()
                if (binding.eventId != fact.event.eventId) throw ProducerContractException("metric_expression_dependency.dimension_binding.event_id must match metric.source_event_id")
                if (binding.eventVersion != fact.event.eventVersion) throw ProducerContractException("metric_expression_dependency.dimension_binding.event_version must match metric.source_event_version")
                if (record.dependencyScope == "UNIT_DIMENSION") {
                    val unit = fact.valueContract.unit
                    if (unit !is MetricValueContract.EventDimension || unit.bindingId != record.dependencyId || unit.bindingVersion != version || unit.axis.role != record.referencedSymbol)
                        throw ProducerContractException("unit dependency index differs from the metric's value contract")
                }
            }
            "CHILD_METRIC" -> requireMetricVersion(record.dependencyId, positive(record.dependencyVersion, "metric_expression_dependency.dependency_version"), "metric_expression_dependency.child_metric_version")
            "MEMBER_SOURCE" -> memberSources.memberSource(record.dependencyId, positive(record.dependencyVersion, "metric_expression_dependency.dependency_version"))
            "DIMENSION" -> snapshots.dimension(record.dependencyId, positive(record.dependencyVersion, "metric_expression_dependency.dependency_version"))
            else -> throw ProducerContractException("unknown metric dependency type: ${record.dependencyType}")
        }
    }
    private fun validateExpressionDependency(fact: MetricDefinition.FactMeasure, record: MetricDependencyRecord) {
        val event = events.requireEvent(fact.event.eventId, fact.event.eventVersion)
        val version = positive(record.dependencyVersion, "metric_expression_dependency.dependency_version")
        val parts = record.dependencyScope.split('/')
        val quantifiers = parts.drop(1)
        val expressions = when (parts.first()) {
            "INPUT" -> listOf(fact.input)
            "PRIVATE_FILTER" -> listOf(requireNotNull(fact.privatePredicate))
            "SNAPSHOT" -> requireNotNull(fact.snapshotRule).expressions()
            else -> throw ProducerContractException("metric dependency has an unknown expression scope")
        }
        val matching = expressions.flatMap { SemanticExpressionInspector.accesses(it) }.filter { access ->
            val ref = access.reference
            ref.id == record.dependencyId && ref.version == version && ref.referenceKind.name == record.dependencyType && (ref.role ?: ref.id) == record.referencedSymbol && access.scopes.map { it.instanceId } == quantifiers
        }.distinct()
        if (matching.size != 1) throw ProducerContractException("metric dependency does not identify one exact scoped reference")
        val found = matching.single()
        topologies.requirePositionedLogicUnit(event.topologyId, event.topologyVersion, SemanticExpressionInspector.path(found, RelationAccessPath(emptyList()), fact.roles), record.dependencyId, version, "metric dependency position")
        val role = when { found.scopes.isNotEmpty() || parts.first() == "PRIVATE_FILTER" -> "FILTERABLE"; parts.first() == "INPUT" -> "MEASURE"; else -> null }
        if (role == null) events.requireLogicUnitRef(event.id, event.currentVersion, record.dependencyId, version, "metric dependency")
        else events.requireLogicUnitRole(event.id, event.currentVersion, record.dependencyId, version, role, "metric dependency")
    }
    internal fun saveMetric(record: MetricRecord) {
        (record.definition as? MetricDefinition.FactMeasure)?.let { events.requireEvent(it.event.eventId, it.event.eventVersion); requireFactRecordKey(it.event) }
        repository.saveMetric(record)
    }
    internal fun saveMetricVersion(record: MetricVersionRecord) {
        if (!repository.metricIdentityExists(record.metricId)) throw ProducerContractException("metric_version.metric_id not found: ${record.metricId}")
        repository.saveMetricVersion(record)
    }
    internal fun saveMetricDependency(record: MetricDependencyRecord) {
        val metric = metricSnapshot(record.metricId, record.metricVersion)
        requireMetricVersion(record.metricId, record.metricVersion, "metric_expression_dependency.metric_version")
        validateDependency(metric, record); repository.saveMetricDependency(record)
    }
    fun requireFactRecordKey(event: EventRef) { if (events.eventIdentity(event.eventId, event.eventVersion).recordKey.isEmpty()) throw ProducerContractException("fact measure requires a declared exact event record key: $event") }
    fun findMetric(id: String): MetricRecord? = repository.findMetric(id)
    fun listMetrics(): List<MetricRecord> = repository.listMetrics()
    fun listMetricsByEvent(id: String, version: Long): List<MetricRecord> = repository.listMetricsByEvent(id, version)
    fun listMetricDependencies(id: String, version: Long): List<MetricDependencyRecord> { requireMetricVersion(id, version, "metric_expression_dependency.metric_version"); return repository.listMetricDependencies(id, version) }
    fun requireMetric(id: String, field: String): MetricRecord = findMetric(id) ?: throw ProducerContractException("$field not found: $id")
    fun requireMetricVersion(id: String, version: Long?, field: String) { metricSnapshot(text(id, "$field.metric_id"), positive(version, field)) }
    fun metricBusinessMeaning(id: String, version: Long): MetricBusinessMeaning = snapshots.metricBusinessMeaning(id, version)
    fun metricSnapshotRule(id: String, version: Long): MetricSnapshotRule? = (metricSnapshot(id, version).definition as? MetricDefinition.FactMeasure)?.snapshotRule
    fun metricBindingContext(eventId: String, eventVersion: Long): MetricBindingContextRecord {
        val event = events.requireEvent(eventId, eventVersion)
        val tables = topologies.topologyBindingContext(event.topologyId, event.topologyVersion).logicalTables.associateBy { it.id }
        val candidates = events.listEventLogicUnitRefs(eventId, eventVersion).filter { it.refRole == "MEASURE" }.map { ref ->
            logicalModel.requireLogicUnitVersion(ref.logicUnitId, ref.logicUnitVersion, "metric_binding_context.measure_logic_unit_version")
            topologies.requireReachableLogicUnit(event.topologyId, event.topologyVersion, ref.logicUnitId, ref.logicUnitVersion, "metric_binding_context.measure_logic_unit")
            val unit = logicalModel.logicUnitSnapshot(ref.logicUnitId, ref.logicUnitVersion)
            val tableId = text(unit.logicalTableId, "metric_binding_context.logical_table_id")
            val table = tables[tableId] ?: throw ProducerContractException("metric_binding_context.logical_table not found: $tableId")
            MetricMeasureLogicUnitCandidateRecord(ref.logicUnitId, ref.logicUnitVersion, tableId, text(table.displayName, "metric_binding_context.logical_table_display_name"),
                text(unit.code, "metric_binding_context.logic_unit_code"), text(unit.displayName, "metric_binding_context.logic_unit_display_name"), text(unit.sourceKind, "metric_binding_context.logic_unit_source_kind"),
                unit.logicType, unit.valueShape, unit.logicType?.let { GovernanceContract.requireLogicType(it, "metric_binding_context.logic_unit_type").displayName }, unit.aggregatable, unit.filterable, unit.isTime, unit.isPrimaryKey, unit.isForeignKey)
        }
        return MetricBindingContextRecord(event, candidates)
    }
    private fun text(value: String?, field: String): String = value?.trim()?.takeUnless { it.isEmpty() } ?: throw ProducerContractException("$field is required")
    private fun positive(value: Long?, field: String): Long = value?.takeIf { it > 0 } ?: throw ProducerContractException("$field is required and must be a positive version")
    private fun code(value: String): String = text(value, "metric.code").also { if (!it.matches(Regex("[a-z][a-z0-9_]*"))) throw ProducerContractException("metric.code must be lower snake case and start with a letter") }
    private fun encodeDefinition(value: GovernanceDefinition): String = com.olap.prototype.codec.ProtoBytes.storage(com.olap.prototype.codec.GovernanceProto.definition(value))
}
