package com.olap.prototype.management.governance

import com.olap.prototype.calculation.*
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.event.EventRef
import com.olap.prototype.expression.SemanticExpression
import com.olap.prototype.expression.SemanticExpressionCodec
import com.olap.prototype.governance.contract.*
import com.olap.prototype.member.MemberRef
import com.olap.prototype.member.MemberSource
import com.olap.prototype.member.MemberSourceDefinition
import com.olap.prototype.query.RelationClockDiscovery
import com.olap.prototype.query.ValueAlignmentContract
import com.olap.prototype.query.contract.MetricAdoption
import com.olap.prototype.query.contract.PublishedRelationFilter
import com.olap.prototype.storage.PlatformDatabase
import java.time.Instant

/** Exact subject versions own adoptions and their parameter bindings, never copies of shared definitions. */
class SubjectAreaAdoptionService(private val database: PlatformDatabase, private val subjectAreas: SubjectAreaGovernanceService,
    private val analysisDimensions: AnalysisDimensionGovernanceService, private val metrics: MetricGovernanceService,
    private val memberDefinitions: MemberDefinitionGovernanceService, private val events: EventGovernanceService, private val dimensions: DimensionGovernanceService) {
    private val repository = SubjectAreaAdoptionRepository(database)
    fun createSubjectAreaMetricRef(request: SubjectAreaMetricRefCreateRequest): SubjectAreaMetricRefCreateResult = database.inTransaction {
        GovernanceDraftVersions(database).requireEditable(GovernanceDraftVersions.Asset.SUBJECT_AREA, request.subjectAreaId, request.subjectAreaVersion)
        val subject = text(request.subjectAreaId, "subject_area_metric_ref.subject_area_id")
        val version = positive(request.subjectAreaVersion, "subject_area_metric_ref.subject_area_version")
        val metric = text(request.metricId, "subject_area_metric_ref.metric_id")
        val metricVersion = positive(request.metricVersion, "subject_area_metric_ref.metric_version")
        val actor = text(request.selectedBy, "subject_area_metric_ref.selected_by")
        val code = text(request.code, "subject_area_metric_ref.code")
        if (!code.matches(Regex("[a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)*"))) throw ProducerContractException("subject_area_metric_ref.code must use lower-case letters, numbers, underscore, dash, or dot")
        val ref = SubjectAreaMetricRefRecord(MetricAdoption.identity(subject, code), subject, version, code, text(request.displayName, "subject_area_metric_ref.display_name"), request.businessMeaning,
            metric, metricVersion, "ACTIVE", actor, Instant.now())
        validateMetricAdoption(ref, request.bindings, request.fixedArguments)
        saveSubjectAreaMetricRef(ref)
        repository.replaceCalculationBindings(ref.id, version, request.bindings, request.fixedArguments)
        SubjectAreaMetricRefCreateResult(ref)
    }
    internal fun saveSubjectAreaMetricRef(record: SubjectAreaMetricRefRecord) {
        subjectAreas.requireSubjectAreaVersion(record.subjectAreaId, record.subjectAreaVersion, "subject_area_metric_ref.subject_area_version")
        metrics.requireMetricVersion(record.metricId, record.metricVersion, "subject_area_metric_ref.metric_version")
        repository.saveSubjectAreaMetricRef(record)
    }
    fun metricAdoptionBindings(id: String, version: Long): CalculationBindings = repository.metricAdoptionBindings(id, version)
    fun metricAdoption(id: String, version: Long): MetricAdoption = repository.metricAdoption(id, version)
    fun metricInstance(reference: MetricInstanceRef): MetricAdoption = repository.metricInstance(reference)
    fun metricInstanceReference(reference: MetricInstanceRef): SubjectAreaMetricRefRecord = repository.metricInstanceReference(reference)
    fun validateMetricAdoption(ref: SubjectAreaMetricRefRecord, bindings: CalculationBindings, fixedArguments: Map<String, Calculation.Value>) {
        val proposed = MetricInstance(MetricRef(ref.metricId, ref.metricVersion.toString()), bindings, fixedArguments, ref.displayName)
        validateDependencies(ref.subjectAreaId, ref.subjectAreaVersion, listOf(ref.instance), emptyList(),
            { if (it == ref.instance) proposed else metricInstance(it).computation }, ::memberComputation, true)
    }

    /** One closure session owns proposed roots, exact context checks and completed child validations. */
    private fun validateDependencies(subject: String, version: Long, metricRoots: List<MetricInstanceRef>, memberRoots: List<MemberRef>,
        instances: (MetricInstanceRef) -> MetricInstance, members: (MemberRef) -> MemberSource, approvedProjection: Boolean) {
        val definitions: (MetricRef) -> MetricDefinition = { reference ->
            metrics.metricSnapshot(reference.id, reference.version.toLong()).also {
                GovernanceContract.requireReferenceableVersion(it.status, "METRIC", reference.id, reference.version.toLong())
            }.definition
        }
        val selectedMembers: (MemberRef) -> MemberSource = { reference ->
            val source = members(reference)
            if (reference !in memberRoots) {
                val owned = memberSource(reference.id, reference.version)
                if (owned.subjectAreaId != subject || owned.subjectAreaVersion != version) throw ProducerContractException("member dependency belongs to another subject-area version")
            }
            source
        }
        val selectedInstances: (MetricInstanceRef) -> MetricInstance = { reference ->
            if (reference !in metricRoots) {
                val owned = metricInstance(reference)
                if (owned.subjectAreaId != subject || owned.subjectAreaVersion != version) throw ProducerContractException("measurement dependency belongs to another subject-area version")
            }
            instances(reference)
        }
        try {
            val closure = MetricSource.closure(metricRoots, memberRoots, definitions, selectedInstances, selectedMembers)
            closure.dependencies.forEach { dependency ->
                when (dependency) {
                    is MetricSource.InstanceDependency -> {
                        val computation = selectedInstances(dependency.reference)
                        val definition = definitions(computation.definition)
                        computation.remainingParameters(definition)
                        computation.bindings.require(definition, { value, constraints -> validateDimension(subject, version, value, constraints) }, selectedMembers,
                            ::hasMemberDimensionMapping, definitions, selectedInstances)
                    }
                    is MetricSource.MemberDependency -> when (val source = selectedMembers(dependency.reference)) {
                        is MemberSource.Projection -> memberDefinitions.validateProjection(source.definition, approvedProjection)
                        is MemberSource.Computed -> {
                            source.remainingParameters()
                            source.bindings.require(source.definition.calculation, { value, constraints -> validateDimension(subject, version, value, constraints) }, selectedMembers,
                                ::hasMemberDimensionMapping, definitions, selectedInstances)
                        }
                    }
                    is MetricSource.MetricDependency -> Unit
                }
            }
        } catch (failure: ProducerContractException) { throw failure }
          catch (failure: IllegalArgumentException) { throw ProducerContractException(requireNotNull(failure.message), failure) }
    }
    private fun hasMemberDimensionMapping(source: MemberSource, adopted: CalculationBindings.Dimension, output: MemberSourceDefinition.Output): Boolean = when (source) {
        is MemberSource.Projection -> output is MemberSourceDefinition.DimensionOutput && analysisDimensions.listAnalysisDimensionEventMappings(adopted.reference.id, adopted.reference.version.toLong()).any {
            it.id in adopted.mappingIds && it.levelCode == adopted.reference.levelCode && it.eventId == source.definition.event.eventId && it.eventVersion == source.definition.event.eventVersion && it.bindingId == output.bindingId && it.bindingVersion == output.bindingVersion
        }
        is MemberSource.Computed -> {
            if (output !is MemberSourceDefinition.ComputedDimensionOutput) false else {
                val actual = requireNotNull(source.bindings.dimensions[output.dimensionSlot]) { "computed dimension slot is not bound" }
                actual.reference == adopted.reference && actual.role == adopted.role && adopted.mappingIds.containsAll(actual.mappingIds)
            }
        }
    }
    private fun validateDimension(subject: String, version: Long, value: CalculationBindings.Dimension, constraints: List<Calculation.DimensionSlot>) {
        val target = value.reference
        val dimension = analysisDimensions.analysisDimensionSnapshot(target.id, target.version.toLong())
        if (dimension.subjectAreaId != subject || dimension.subjectAreaVersion != version) throw ProducerContractException("adopted dimension belongs to another subject-area version")
        val available = analysisDimensions.listAnalysisDimensionEventMappings(target.id, target.version.toLong()).filter { it.levelCode == target.levelCode }.associateBy { it.id }
        val selected = value.mappingIds.map { id ->
            val mapping = available[id] ?: throw ProducerContractException("adopted mapping is absent from its exact analysis dimension and level: $id")
            value.requireMeaning(mapping.dimensionId, mapping.dimensionVersion.toString(), constraints)
            analysisDimensions.validateAnalysisDimensionAlignmentRef(mapping)
            mapping
        }
        if (selected.map { "${it.eventId}@${it.eventVersion}" }.distinct().size > 1 && selected.any { it.alignmentStatus !in ValueAlignmentContract.crossEventStatuses() })
            throw ProducerContractException("cross-input dimension adoption requires confirmed value alignment evidence")
    }
    fun relationClockSlots(id: String, parent: EventRef, relationshipRole: String, memberId: String, memberVersion: Long): Map<String, String> {
        val source = memberSource(memberId, memberVersion)
        projection(source.definition, "event-role relation filter")
        return try { RelationClockDiscovery.relationSlots(source.definition.result.clockSlots, events.relationClocks(id, parent, relationshipRole).keys) }
        catch (invalid: IllegalArgumentException) { throw ProducerContractException(requireNotNull(invalid.message), invalid) }
    }
    fun requireRelationFilter(subject: String, version: Long, filter: PublishedRelationFilter) {
        val relation = filter.relation
        val source = memberSource(relation.memberSourceId, relation.memberSourceVersion)
        if (source.subjectAreaId != subject || source.subjectAreaVersion != version) throw ProducerContractException("related member source belongs to another subject-area version")
        val definition = projection(source.definition, "event-role relation filter")
        events.requireRelatedEventRole(relation.parent.eventId, relation.parent.eventVersion, relation.relationshipRole, definition.event.eventId, definition.event.eventVersion)
        val permission = filter.permission
        val associationClocks = events.relationClocks(relation.id, relation.parent, relation.relationshipRole).keys
        val clocks = try { RelationClockDiscovery.relationSlots(definition.result.clockSlots, associationClocks) }
            catch (invalid: IllegalArgumentException) { throw ProducerContractException(requireNotNull(invalid.message), invalid) }
        if (relation.clockSlots != clocks) throw ProducerContractException("relation clocks differ from their exact source and role")
        if (!definition.result.filterOperators.keys.containsAll(permission.filters.keys) || !clocks.keys.containsAll(permission.clocks) || permission.timeRange && definition.result.timeSlot == null)
            throw ProducerContractException("relation filter exceeds its related source permissions")
        permission.filters.forEach { (slot, operators) -> if (!requireNotNull(definition.result.filterOperators[slot]).containsAll(operators)) throw ProducerContractException("relation operators exceed related source permissions") }
        val required = associationClocks + events.baseClocks(definition.event.eventId, definition.event.eventVersion).keys
        val granted = permission.clocks.mapTo(hashSetOf()) { if (it in associationClocks) it else definition.result.clockSlots[it] }
        if (!granted.containsAll(required)) throw ProducerContractException("relation permission omits a mandatory association or related event clock")
    }
    private fun projection(definition: MemberSourceDefinition, operation: String): MemberSourceDefinition.Projection = definition as? MemberSourceDefinition.Projection
        ?: throw ProducerContractException("$operation requires an event projection; computed sources use explicit member invocations and local relations")

    fun createMemberSource(request: MemberSourceCreateRequest): MemberSourceRecord = database.inTransaction {
        GovernanceDraftVersions(database).requireEditable(GovernanceDraftVersions.Asset.SUBJECT_AREA, request.subjectAreaId, request.subjectAreaVersion)
        val code = text(request.code, "member source code")
        val source = MemberSourceRecord("${request.subjectAreaId}:member:$code", request.subjectAreaId, request.subjectAreaVersion, code, text(request.displayName, "member source displayName"),
            MemberDefinitionOrigin.Local(), request.definition, request.bindings, request.fixedArguments)
        validateMemberSource(source, false)
        repository.saveMemberSource(source)
        source
    }
    fun adoptMemberDefinition(request: MemberDefinitionAdoptRequest): MemberSourceRecord = database.inTransaction {
        GovernanceDraftVersions(database).requireEditable(GovernanceDraftVersions.Asset.SUBJECT_AREA, request.subjectAreaId, request.subjectAreaVersion)
        val reference = request.definition
        val definition = memberDefinitions.read(reference.id, reference.version)
        GovernanceContract.requireReferenceableVersion(definition.status, "MEMBER_DEFINITION", reference.id, reference.version)
        val code = text(request.code, "member source code")
        val source = MemberSourceRecord("${request.subjectAreaId}:member:$code", request.subjectAreaId, request.subjectAreaVersion, code, text(request.displayName, "member source displayName"),
            MemberDefinitionOrigin.Shared(reference), definition.definition, request.bindings, request.fixedArguments)
        validateMemberSource(source, false)
        repository.saveMemberSource(source)
        source
    }
    fun reviseMemberAdoption(request: MemberAdoptionRevisionRequest): MemberSourceRecord = database.inTransaction {
        val initial = memberSource(request.source.id, request.source.version)
        GovernanceDraftVersions(database).requireEditable(GovernanceDraftVersions.Asset.SUBJECT_AREA, initial.subjectAreaId, initial.subjectAreaVersion)
        val current = memberSource(request.source.id, request.source.version)
        val origin = current.origin as? MemberDefinitionOrigin.Shared
            ?: throw ProducerContractException("member adoption revision requires an existing shared definition adoption")
        if (origin.reference != request.expectedDefinition) throw MemberAdoptionConflictException(request.source, request.expectedDefinition, origin.reference)
        text(request.actor, "member adoption revision actor")
        val definition = memberDefinitions.read(request.definition.id, request.definition.version)
        GovernanceContract.requireReferenceableVersion(definition.status, "MEMBER_DEFINITION", request.definition.id, request.definition.version)
        val revised = MemberSourceRecord(current.id, current.subjectAreaId, current.subjectAreaVersion, current.code,
            text(request.displayName, "member source displayName"), MemberDefinitionOrigin.Shared(request.definition), definition.definition,
            request.bindings, request.fixedArguments)
        validateMemberSource(revised, false)
        repository.replaceMemberAdoption(revised)
        // 采用身份保持不变；所有既有计算消费者必须仍能兑现它们的精确槽位合同。
        validateDependencies(current.subjectAreaId, current.subjectAreaVersion,
            listSubjectAreaMetricRefs(current.subjectAreaId, current.subjectAreaVersion).map { it.instance },
            memberSources(current.subjectAreaId, current.subjectAreaVersion).map { MemberRef(it.id, it.subjectAreaVersion) },
            { metricInstance(it).computation }, ::memberComputation, false)
        revised
    }
    fun validateMemberSource(source: MemberSourceRecord) { validateMemberSource(source, true) }
    private fun validateMemberSource(source: MemberSourceRecord, approved: Boolean) {
        val origin = source.origin
        if (origin is MemberDefinitionOrigin.Shared) {
            val reference = origin.reference
            val definition = memberDefinitions.read(reference.id, reference.version)
            GovernanceContract.requireReferenceableVersion(definition.status, "MEMBER_DEFINITION", reference.id, reference.version)
            if (source.definition != definition.definition) throw ProducerContractException("member adoption differs from its exact shared definition")
        }
        val reference = MemberRef(source.id, source.subjectAreaVersion)
        val proposed = memberComputation(source)
        validateDependencies(source.subjectAreaId, source.subjectAreaVersion, emptyList(), listOf(reference), { metricInstance(it).computation }, { if (it == reference) proposed else memberComputation(it) }, approved)
    }
    fun memberComputation(reference: MemberRef): MemberSource = memberComputation(memberSource(reference.id, reference.version))
    private fun memberComputation(source: MemberSourceRecord): MemberSource = when (val definition = source.definition) {
        is MemberSourceDefinition.Computed -> MemberSource.Computed(source.id, source.subjectAreaVersion, definition, source.bindings, source.fixedArguments)
        is MemberSourceDefinition.Projection -> {
            val expressions = definition.result.outputs.mapValuesTo(linkedMapOf()) { (_, output) ->
                when (output) {
                    is MemberSourceDefinition.DimensionOutput -> requireNotNull(dimensions.dimensionEventBindingSnapshot(output.bindingId, output.bindingVersion).expression) { "member dimension expression is required" }
                    is MemberSourceDefinition.PropertyOutput -> SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, output.logicUnitId, output.logicUnitVersion)
                    is MemberSourceDefinition.ContainerPropertyOutput -> MemberSourceDefinition.propertyReference(output)
                    else -> throw ProducerContractException("event projection contains a computed output")
                }
            }
            MemberSource.Projection(source.id, source.subjectAreaVersion, definition, expressions)
        }
    }
    fun memberSources(id: String, version: Long): List<MemberSourceRecord> = repository.memberSources(id, version)
    fun memberSource(id: String, version: Long): MemberSourceRecord = repository.memberSource(id, version)
    fun listSubjectAreaMetricRefs(id: String, version: Long): List<SubjectAreaMetricRefRecord> = repository.listSubjectAreaMetricRefs(id, version)
    private fun text(value: String?, field: String): String = value?.trim()?.takeUnless { it.isEmpty() } ?: throw ProducerContractException("$field is required")
    private fun positive(value: Long?, field: String): Long = value?.takeIf { it > 0 } ?: throw ProducerContractException("$field is required and must be a positive version")
}
