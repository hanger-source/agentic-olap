package com.olap.prototype.query.contract.catalog

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.calculation.CalculationBindings
import com.olap.prototype.calculation.MetricCalculation
import com.olap.prototype.calculation.MetricInstance
import com.olap.prototype.calculation.MetricInstanceRef
import com.olap.prototype.calculation.MetricRef
import com.olap.prototype.calculation.MetricSource
import com.olap.prototype.event.EventRef
import com.olap.prototype.member.MemberRef
import com.olap.prototype.member.MemberShape
import com.olap.prototype.metric.MetricAggregation
import com.olap.prototype.metric.MetricBusinessMeaning
import com.olap.prototype.metric.MetricSnapshotRule
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.ScopedInputContract
import com.olap.prototype.query.SetOutputContract
import com.olap.prototype.query.SourceJoinContract
import com.olap.prototype.query.ValueAlignmentContract
import com.olap.prototype.query.contract.*
import java.util.Collections

/** 冻结语义的唯一结构，描述定义闭包而不授予物理访问或重新解释计算。 */
data class PublishedSemanticCatalog(
    val formatVersion: String,
    val sha256: String,
    val catalog: Catalog,
) {
    init {
        require(formatVersion == FORMAT_VERSION && sha256.matches(Regex("[0-9a-f]{64}"))) { "exact published semantic format and hash required" }
    }


    data class Coordinate(
        val scenarioId: String,
        val scenarioVersion: Long,
        val releaseId: String,
        val releaseVersion: String,
    ) {
        init { CatalogValues.text(scenarioId); CatalogValues.version(scenarioVersion); CatalogValues.text(releaseId); CatalogValues.text(releaseVersion) }
        fun request(): QueryCapabilityRequest = QueryCapabilityRequest(scenarioId, scenarioVersion, releaseId, releaseVersion)
    }

    class Catalog(
        val coordinate: Coordinate,
        val displayName: String,
        val sourceJoinContract: SourceJoinMeaning,
        metrics: List<Metric>,
        memberSources: List<Member>,
        relationFilters: List<RelationFilter>,
        dimensions: List<Dimension>,
        timeDimensions: List<TimeDimension>,
        combinations: List<Combination>,
        filters: List<Filter>,
        semanticDependencies: List<SemanticDependency>,
    ) {
        val metrics: List<Metric> = java.util.List.copyOf(metrics)
        val memberSources: List<Member> = java.util.List.copyOf(memberSources)
        val relationFilters: List<RelationFilter> = java.util.List.copyOf(relationFilters)
        val dimensions: List<Dimension> = java.util.List.copyOf(dimensions)
        val timeDimensions: List<TimeDimension> = java.util.List.copyOf(timeDimensions)
        val combinations: List<Combination> = java.util.List.copyOf(combinations)
        val filters: List<Filter> = java.util.List.copyOf(filters)
        val semanticDependencies: List<SemanticDependency> = java.util.List.copyOf(semanticDependencies)
        init {
            CatalogValues.text(displayName)
            requireSemanticDependencies()
            unique(this.metrics.map { it.instance.id })
            unique(this.memberSources.map { "${it.sourceId}@${it.sourceVersion}" })
            unique(this.relationFilters.map { "${it.id}@${it.version}" })
            unique(this.dimensions.map { "${it.id}@${it.version}" })
            unique(this.timeDimensions.map { "${it.id}@${it.version}" })
            unique(this.filters.map { "${it.id}@${it.version}" })
        }
        private fun requireSemanticDependencies() {
            val definitions = linkedMapOf<MetricRef, SemanticDependency>()
            val instances = linkedMapOf<MetricInstanceRef, MetricInstance>()
            val members = linkedMapOf<MemberRef, MemberDependency>()
            semanticDependencies.forEach { dependency ->
                when (dependency) {
                    is CalculationDependency -> require(definitions.putIfAbsent(dependency.reference, dependency) == null) { "duplicate semantic metric definition" }
                    is FactDependency -> require(definitions.putIfAbsent(dependency.reference, dependency) == null) { "duplicate semantic metric definition" }
                    is InstanceDependency -> require(instances.putIfAbsent(dependency.reference, dependency.instance) == null) { "duplicate semantic metric instance" }
                    is MemberDependency -> require(members.putIfAbsent(dependency.reference, dependency) == null) { "duplicate semantic member definition" }
                }
            }
            instances.values.forEach { require(it.definition in definitions) { "semantic instance definition is unavailable" } }
            metrics.forEach { metric ->
                val instance = requireNotNull(instances[metric.instance]) { "ROOT semantic instance is unavailable" }
                val definition = requireNotNull(definitions[metric.definition]) { "ROOT semantic definition is unavailable" }
                require(instance.definition == metric.definition && instance.displayName == metric.displayName && instance.fixedArguments == metric.fixedArguments &&
                    (definition is CalculationDependency) == (metric.definitionKind == MetricKind.CALCULATION)) { "ROOT metric differs from its exact semantic dependency" }
            }
            memberSources.forEach { member ->
                val dependency = requireNotNull(members[MemberRef(member.sourceId, member.sourceVersion)]) { "ROOT semantic member is unavailable" }
                require(dependency.entity == member.entity && dependency.role == member.role && dependency.shape == member.shape && dependency.identitySlots == member.identitySlots) { "ROOT member differs from its exact semantic dependency" }
                member.requireDefinition(dependency.definition)
            }
        }
        fun metric(instance: MetricInstanceRef): Metric = exact(metrics.filter { it.instance == instance })
        fun calculation(reference: MetricRef): MetricCalculation = exact(semanticDependencies.filterIsInstance<CalculationDependency>().filter { it.reference == reference }.map { it.definition })
        fun metricInstance(reference: MetricInstanceRef): MetricInstance = exact(semanticDependencies.filterIsInstance<InstanceDependency>().filter { it.reference == reference }.map { it.instance })
        fun memberDefinition(reference: MemberRef): MemberDefinition = exact(semanticDependencies.filterIsInstance<MemberDependency>().filter { it.reference == reference }.map { it.definition })
        fun member(id: String, version: Long): Member = exact(memberSources.filter { it.sourceId == id && it.sourceVersion == version })
        fun relation(id: String, version: Long): RelationFilter = exact(relationFilters.filter { it.id == id && it.version == version })
        fun dimension(id: String, version: Long): Dimension = exact(dimensions.filter { it.id == id && it.version == version })
        fun timeDimension(id: String, version: Long): TimeDimension = exact(timeDimensions.filter { it.id == id && it.version == version })
        override fun equals(other: Any?): Boolean = other is Catalog && coordinate == other.coordinate && displayName == other.displayName && sourceJoinContract == other.sourceJoinContract && metrics == other.metrics && memberSources == other.memberSources && relationFilters == other.relationFilters && dimensions == other.dimensions && timeDimensions == other.timeDimensions && combinations == other.combinations && filters == other.filters && semanticDependencies == other.semanticDependencies
        override fun hashCode(): Int = listOf(coordinate, displayName, sourceJoinContract, metrics, memberSources, relationFilters, dimensions, timeDimensions, combinations, filters, semanticDependencies).hashCode()
    }

    sealed interface SemanticDependency
    data class CalculationDependency(val reference: MetricRef, val definition: MetricCalculation) : SemanticDependency
    data class FactDependency(val reference: MetricRef, val event: EventRef,
        val aggregation: Aggregation, val valueContract: MetricValueContract,
        val sourceScope: FactScope) : SemanticDependency
    data class InstanceDependency(val reference: MetricInstanceRef, val instance: MetricInstance) : SemanticDependency
    class MemberDependency(val reference: MemberRef, val entity: String,
        val role: String, val shape: MemberShape,
        identitySlots: List<String>, val definition: MemberDefinition) : SemanticDependency {
        val identitySlots: List<String> = java.util.List.copyOf(identitySlots)
        init {
            CatalogValues.text(entity); CatalogValues.text(role); unique(this.identitySlots)
            require((shape == MemberShape.RAW_BAG) == this.identitySlots.isEmpty() && definition.slots.keys.containsAll(this.identitySlots)) { "semantic member identity differs from its declared shape or outputs" }
        }
        override fun equals(other: Any?): Boolean = other is MemberDependency && reference == other.reference && entity == other.entity && role == other.role && shape == other.shape && identitySlots == other.identitySlots && definition == other.definition
        override fun hashCode(): Int = listOf(reference, entity, role, shape, identitySlots, definition).hashCode()
    }

    class SourceJoinMeaning(val formatVersion: String,
        joinTypes: List<SourceJoinContract.JoinType>, cardinalities: List<SourceJoinContract.Cardinality>,
        nullPolicies: List<SourceJoinContract.NullPolicy>, val factPopulation: String,
        factMatching: Map<SourceJoinContract.JoinType, String>, val memberPopulation: String,
        val existsPopulation: String, val keyMatching: String, val guarantees: String) {
        val joinTypes: List<SourceJoinContract.JoinType> = CatalogValues.nonempty(joinTypes)
        val cardinalities: List<SourceJoinContract.Cardinality> = CatalogValues.nonempty(cardinalities)
        val nullPolicies: List<SourceJoinContract.NullPolicy> = CatalogValues.nonempty(nullPolicies)
        val factMatching: Map<SourceJoinContract.JoinType, String> = Collections.unmodifiableMap(LinkedHashMap(factMatching))
        init {
            CatalogValues.text(formatVersion); unique(this.joinTypes); unique(this.cardinalities); unique(this.nullPolicies)
            listOf(factPopulation, memberPopulation, existsPopulation, keyMatching, guarantees).forEach { CatalogValues.text(it) }
            require(this.factMatching.keys == this.joinTypes.toSet()) { "source matching must cover its join types" }
            this.factMatching.values.forEach { CatalogValues.text(it) }
        }
        override fun equals(other: Any?): Boolean = other is SourceJoinMeaning && formatVersion == other.formatVersion && joinTypes == other.joinTypes && cardinalities == other.cardinalities && nullPolicies == other.nullPolicies && factPopulation == other.factPopulation && factMatching == other.factMatching && memberPopulation == other.memberPopulation && existsPopulation == other.existsPopulation && keyMatching == other.keyMatching && guarantees == other.guarantees
        override fun hashCode(): Int = listOf(formatVersion, joinTypes, cardinalities, nullPolicies, factPopulation, factMatching, memberPopulation, existsPopulation, keyMatching, guarantees).hashCode()
        companion object { fun current(): SourceJoinMeaning = SourceJoinMeaning("olap-source-join-contract/v1", SourceJoinContract.JoinType.entries, SourceJoinContract.Cardinality.entries, SourceJoinContract.NullPolicy.entries, "EXACT_EVENT_BACKBONE_ROWS_ONLY_RIGHT_TARGET_ORPHANS_ARE_NOT_FACTS", mapOf(SourceJoinContract.JoinType.INNER to "REQUIRE_MATCH", SourceJoinContract.JoinType.RIGHT to "REQUIRE_MATCH", SourceJoinContract.JoinType.LEFT to "KEEP_BACKBONE", SourceJoinContract.JoinType.FULL to "KEEP_BACKBONE"), "DECLARED_RELATION_BAG_WITH_IDENTITY_AND_ATTRIBUTE_PROOFS", "ACTUALLY_ASSOCIATED_BACKBONE_ROWS_ONLY", "ALL_EQUALITY_KEYS_AND_ALL_TEMPORAL_INTERVALS_CONJOINED_NULL_KEYS_DO_NOT_MATCH", "ONLY_ADOPTED_NATIVE_KEYS_OR_COMPLETE_FROZEN_NONOVERLAP_CARDINALITY_LABELS_ARE_NOT_PROOF") }
    }
    enum class MetricKind { FACT_MEASURE, CALCULATION }
    data class Aggregation(val function: MetricAggregation,
        val distinct: Boolean, val rounding: MetricValueContract.Rounding)

    sealed interface SourceScope
    class SourceExplanation(callPath: List<String>, val scope: SourceScope) {
        val callPath: List<String> = java.util.List.copyOf(callPath)
        init { this.callPath.forEach { CatalogValues.text(it) } }
        override fun equals(other: Any?): Boolean = other is SourceExplanation && callPath == other.callPath && scope == other.scope
        override fun hashCode(): Int = listOf(callPath, scope).hashCode()
    }

        class Metric(
        val adoptionId: String,
        val adoptionVersion: Long,
        val subjectAreaId: String,
        val subjectAreaVersion: Long,
        val code: String,
        val displayName: String,
        val businessMeaning: MetricBusinessMeaning,
        val definition: MetricRef,
        val instance: MetricInstanceRef,
        val valueContract: MetricValueContract,
        val snapshotMeaning: MetricSnapshotRule.Meaning?,
        sources: List<MetricSource>,
        val definitionKind: MetricKind,
        fixedArguments: Map<String, Calculation.Value>,
        parameters: Map<String, Calculation.Parameter>,
        groupingCandidates: Map<String, List<ScopedInputContract.DimensionRef>>,
        memberParameters: Map<String, Calculation.MemberSlot>,
        relationParameters: Map<String, Calculation.RelationSlot>,
        sourceScopes: List<SourceExplanation>,
        val aggregation: CatalogOptional<Aggregation> = CatalogOptional.absent(),
    ) {
        val sources: List<MetricSource> = java.util.List.copyOf(sources)
        val fixedArguments: Map<String, Calculation.Value> = CatalogValues.map(fixedArguments)
        val parameters: Map<String, Calculation.Parameter> = CatalogValues.map(parameters)
        val groupingCandidates: Map<String, List<ScopedInputContract.DimensionRef>> = listMap(groupingCandidates)
        val memberParameters: Map<String, Calculation.MemberSlot> = CatalogValues.map(memberParameters)
        val relationParameters: Map<String, Calculation.RelationSlot> = CatalogValues.map(relationParameters)
        val sourceScopes: List<SourceExplanation> = java.util.List.copyOf(sourceScopes)
        init {
            CatalogValues.text(adoptionId); CatalogValues.version(adoptionVersion); CatalogValues.text(subjectAreaId); CatalogValues.version(subjectAreaVersion)
            CatalogValues.text(code); CatalogValues.text(displayName)
            require(adoptionVersion == subjectAreaVersion) { "adoption version differs from its subject-area version" }
            require(this.fixedArguments.keys.intersect(this.parameters.keys).isEmpty()) { "fixed and query arguments overlap" }
            require((definitionKind == MetricKind.FACT_MEASURE) == aggregation.present && (!aggregation.present || aggregation.value != null)) { "aggregation belongs to fact metrics only" }
            require(definitionKind != MetricKind.FACT_MEASURE || this.parameters.isEmpty() && this.fixedArguments.isEmpty() && this.groupingCandidates.isEmpty() && this.memberParameters.isEmpty() && this.relationParameters.isEmpty()) { "fact has calculation parameters" }
            val origins = linkedMapOf<List<String>, MetricSource>()
            this.sources.forEach { require(origins.putIfAbsent(it.callPath, it) == null) { "duplicate source call position" } }
            val seen = hashSetOf<List<String>>()
            this.sourceScopes.forEach { explanation ->
                val source = origins[explanation.callPath]
                require(source != null && seen.add(explanation.callPath)) { "source explanation has no exact adopted position" }
                if (source.origin is MetricSource.Fact) require(explanation.scope is FactScope) { "fact position requires fact scope" }
                else {
                    val meaning = explanation.scope as? ProjectionMeaning ?: throw IllegalArgumentException("member position requires member meaning")
                    val member = source.origin as MetricSource.Member
                    require(meaning.event == source.event && meaning.slots.keys == member.slots.toSet()) { "member scope differs from adopted input" }
                    meaning.requireClosure(emptySet())
                }
            }
            require(seen == origins.keys) { "incomplete source explanations" }
        }
        override fun equals(other: Any?): Boolean = other is Metric && adoptionId == other.adoptionId && adoptionVersion == other.adoptionVersion && subjectAreaId == other.subjectAreaId && subjectAreaVersion == other.subjectAreaVersion && code == other.code && displayName == other.displayName && businessMeaning == other.businessMeaning && definition == other.definition && instance == other.instance && valueContract == other.valueContract && snapshotMeaning == other.snapshotMeaning && sources == other.sources && definitionKind == other.definitionKind && fixedArguments == other.fixedArguments && parameters == other.parameters && groupingCandidates == other.groupingCandidates && memberParameters == other.memberParameters && relationParameters == other.relationParameters && sourceScopes == other.sourceScopes && aggregation == other.aggregation
        override fun hashCode(): Int = listOf(adoptionId, adoptionVersion, subjectAreaId, subjectAreaVersion, code, displayName, businessMeaning, definition, instance, valueContract, snapshotMeaning, sources, definitionKind, fixedArguments, parameters, groupingCandidates, memberParameters, relationParameters, sourceScopes, aggregation).hashCode()
    }

    enum class FactInterpretation { FROZEN_DEFINITION_NOT_CALLER_FILTER_OR_SOURCE_COMPLETENESS_PROOF }
    class FactScope(
        val interpretation: FactInterpretation,
        val eventPredicate: PublicExpression?,
        eventRoles: Map<String, List<SemanticJoin>>,
        val snapshotRule: MetricSnapshotRule?,
        val inputExpression: PublicExpression,
        val privatePredicate: PublicExpression?,
        metricRoles: Map<String, List<SemanticJoin>>,
        logicUnits: List<LogicUnitMeaning>,
        sealedSourceCollections: CatalogOptional<List<SealedSourceCollectionMeaning>> = CatalogOptional.absent(),
    ) : SourceScope {
        val eventRoles: Map<String, List<SemanticJoin>> = roles(eventRoles)
        val metricRoles: Map<String, List<SemanticJoin>> = roles(metricRoles)
        val logicUnits: List<LogicUnitMeaning> = java.util.List.copyOf(logicUnits)
        val sealedSourceCollections: CatalogOptional<List<SealedSourceCollectionMeaning>> = SemanticJoin.collections(sealedSourceCollections)
        init {
            val references = linkedSetOf<String>()
            roleReferences(this.eventRoles, references); roleReferences(this.metricRoles, references)
            eventPredicate?.references(this.eventRoles.keys, references)
            inputExpression.references(this.metricRoles.keys, references)
            privatePredicate?.references(this.metricRoles.keys, references)
            snapshotRule?.keys()?.forEach { references.add("${it.id}@${it.version}") }
            closure(this.logicUnits, references)
        }
        override fun equals(other: Any?): Boolean = other is FactScope && interpretation == other.interpretation && eventPredicate == other.eventPredicate && eventRoles == other.eventRoles && snapshotRule == other.snapshotRule && inputExpression == other.inputExpression && privatePredicate == other.privatePredicate && metricRoles == other.metricRoles && logicUnits == other.logicUnits && sealedSourceCollections == other.sealedSourceCollections
        override fun hashCode(): Int = listOf(interpretation, eventPredicate, eventRoles, snapshotRule, inputExpression, privatePredicate, metricRoles, logicUnits, sealedSourceCollections).hashCode()
    }
    data class LogicUnitMeaning(
        val id: String,
        val version: Long,
        val displayName: String,
        val valueType: ScalarValueType?,
        val valueShape: SetOutputContract.Shape?,
    ) {
        init {
            CatalogValues.text(id); CatalogValues.version(version); CatalogValues.text(displayName)
            require((valueType == null) != (valueShape == null)) { "LogicUnit meaning requires one scalar or container type" }
            valueShape?.let { SetOutputContract.requireSourceContainer(it) }
        }
    }

    sealed interface MemberSlotMeaning { val displayName: String; val role: String }
    sealed interface ScalarSlotMeaning : MemberSlotMeaning { val valueType: ScalarValueType; val valueContract: MetricValueContract? }
    class ContainerSlotMeaning(override val displayName: String, override val role: String, val shape: SetOutputContract.Shape,
        val expression: PublicExpression, access: List<SemanticJoin>, roles: Map<String, List<SemanticJoin>>) : MemberSlotMeaning {
        val access: List<SemanticJoin> = java.util.List.copyOf(access)
        val roles: Map<String, List<SemanticJoin>> = PublishedSemanticCatalog.roles(roles)
        init { CatalogValues.text(displayName); CatalogValues.text(role); SetOutputContract.requireSourceContainer(shape) }
        fun references(references: MutableSet<String>) {
            access.forEach { it.references(references) }; roleReferences(roles, references); expression.references(roles.keys, references)
        }
        override fun equals(other: Any?): Boolean = other is ContainerSlotMeaning && displayName == other.displayName && role == other.role && shape == other.shape && expression == other.expression && access == other.access && roles == other.roles
        override fun hashCode(): Int = listOf(displayName, role, shape, expression, access, roles).hashCode()
    }
    data class ComputedContainerSlotMeaning(override val displayName: String, override val role: String,
        val shape: SetOutputContract.Shape, val symbol: String) : MemberSlotMeaning {
        init { CatalogValues.text(displayName); CatalogValues.text(role); SetOutputContract.requireSourceContainer(shape); CatalogValues.text(symbol) }
    }
    data class ComputedSlotMeaning(
        override val displayName: String,
        override val role: String,
        override val valueType: ScalarValueType,
        override val valueContract: MetricValueContract?,
        val dimensionSlot: String?,
        val symbol: String,
    ) : ScalarSlotMeaning {
        init { CatalogValues.text(displayName); CatalogValues.text(role); CatalogValues.text(symbol); dimensionSlot?.let { CatalogValues.text(it) } }
    }
    class SlotMeaning(
        override val displayName: String,
        override val role: String,
        override val valueType: ScalarValueType,
        override val valueContract: MetricValueContract?,
        val expression: PublicExpression,
        access: List<SemanticJoin>,
        roles: Map<String, List<SemanticJoin>>,
    ) : ScalarSlotMeaning {
        val access: List<SemanticJoin> = java.util.List.copyOf(access)
        val roles: Map<String, List<SemanticJoin>> = PublishedSemanticCatalog.roles(roles)
        init { CatalogValues.text(displayName); CatalogValues.text(role) }
        fun references(references: MutableSet<String>) {
            access.forEach { it.references(references) }; roleReferences(roles, references); expression.references(roles.keys, references)
        }
        override fun equals(other: Any?): Boolean = other is SlotMeaning && displayName == other.displayName && role == other.role && valueType == other.valueType && valueContract == other.valueContract && expression == other.expression && access == other.access && roles == other.roles
        override fun hashCode(): Int = listOf(displayName, role, valueType, valueContract, expression, access, roles).hashCode()
    }

    sealed interface MemberDefinition : SourceScope {
        val slots: Map<String, MemberSlotMeaning>
        val description: CatalogOptional<String>
    }
    class ComputedMeaning(
        val calculation: Calculation,
        val bindings: CalculationBindings,
        slots: Map<String, MemberSlotMeaning>,
        sourceScopes: List<SourceExplanation>,
        parameters: Map<String, Calculation.Parameter>,
        fixedArguments: Map<String, Calculation.Value>,
        groupingCandidates: Map<String, List<ScopedInputContract.DimensionRef>>,
        memberParameters: Map<String, Calculation.MemberSlot>,
        relationParameters: Map<String, Calculation.RelationSlot>,
        override val description: CatalogOptional<String> = CatalogOptional.absent(),
    ) : MemberDefinition {
        override val slots: Map<String, MemberSlotMeaning> = CatalogValues.map(slots)
        val sourceScopes: List<SourceExplanation> = java.util.List.copyOf(sourceScopes)
        val parameters: Map<String, Calculation.Parameter> = CatalogValues.map(parameters)
        val fixedArguments: Map<String, Calculation.Value> = CatalogValues.map(fixedArguments)
        val groupingCandidates: Map<String, List<ScopedInputContract.DimensionRef>> = listMap(groupingCandidates)
        val memberParameters: Map<String, Calculation.MemberSlot> = CatalogValues.map(memberParameters)
        val relationParameters: Map<String, Calculation.RelationSlot> = CatalogValues.map(relationParameters)
        init {
            require(this.parameters.keys.intersect(this.fixedArguments.keys).isEmpty()) { "fixed computed arguments overlap invocation parameters" }
            description.value?.let { CatalogValues.text(it) }
        }
        override fun equals(other: Any?): Boolean = other is ComputedMeaning && calculation == other.calculation && bindings == other.bindings && slots == other.slots && sourceScopes == other.sourceScopes && parameters == other.parameters && fixedArguments == other.fixedArguments && groupingCandidates == other.groupingCandidates && memberParameters == other.memberParameters && relationParameters == other.relationParameters && description == other.description
        override fun hashCode(): Int = listOf(calculation, bindings, slots, sourceScopes, parameters, fixedArguments, groupingCandidates, memberParameters, relationParameters, description).hashCode()
    }
    class ProjectionMeaning(
        val event: EventRef,
        eventRoles: Map<String, List<SemanticJoin>>,
        val eventPredicate: PublicExpression?,
        slots: Map<String, MemberSlotMeaning>,
        logicUnits: List<LogicUnitMeaning>,
        sealedSourceCollections: List<SealedSourceCollectionMeaning>,
        override val description: CatalogOptional<String> = CatalogOptional.absent(),
    ) : MemberDefinition {
        val eventRoles: Map<String, List<SemanticJoin>> = roles(eventRoles)
        override val slots: Map<String, MemberSlotMeaning> = CatalogValues.map(slots)
        val logicUnits: List<LogicUnitMeaning> = java.util.List.copyOf(logicUnits)
        val sealedSourceCollections: List<SealedSourceCollectionMeaning> = requireNotNull(SemanticJoin.collections(CatalogOptional.of(sealedSourceCollections)).value)
        init {
            this.slots.keys.forEach { CatalogValues.text(it) }
            description.value?.let { CatalogValues.text(it) }
            require(declared(this.logicUnits).containsAll(memberReferences(this.eventRoles, eventPredicate, this.slots))) { "member meaning has an undeclared LogicUnit" }
        }
        fun requireClosure(additional: Set<String>) { closure(logicUnits, memberReferences(eventRoles, eventPredicate, slots) + additional) }
        override fun equals(other: Any?): Boolean = other is ProjectionMeaning && event == other.event && eventRoles == other.eventRoles && eventPredicate == other.eventPredicate && slots == other.slots && logicUnits == other.logicUnits && sealedSourceCollections == other.sealedSourceCollections && description == other.description
        override fun hashCode(): Int = listOf(event, eventRoles, eventPredicate, slots, logicUnits, sealedSourceCollections, description).hashCode()
    }

    class Member(
        val sourceId: String,
        val sourceVersion: Long,
        val displayName: String,
        val entity: String,
        val role: String,
        val shape: MemberShape,
        identitySlots: List<String>,
        outputs: Map<String, MemberCandidate.Output>,
        val permission: MemberQueryPermission,
        filterTypes: Map<String, ScalarValueType>,
        filterValueContracts: Map<String, MetricValueContract?>,
        unitDimensions: Map<String, List<ScopedInputContract.DimensionRef>>,
        val timeRange: MemberCandidate.TimeRangeCapability?,
        clockSlots: Map<String, String>,
        parameters: Map<String, Calculation.Parameter>,
        fixedArguments: Map<String, Calculation.Value>,
        groupingCandidates: Map<String, List<ScopedInputContract.DimensionRef>>,
        memberParameters: Map<String, Calculation.MemberSlot>,
        relationParameters: Map<String, Calculation.RelationSlot>,
    ) {
        val identitySlots: List<String> = java.util.List.copyOf(identitySlots)
        val outputs: Map<String, MemberCandidate.Output> = CatalogValues.map(outputs)
        val filterTypes: Map<String, ScalarValueType> = CatalogValues.map(filterTypes)
        val filterValueContracts: Map<String, MetricValueContract?> = CatalogValues.map(filterValueContracts)
        val unitDimensions: Map<String, List<ScopedInputContract.DimensionRef>> = listMap(unitDimensions)
        val clockSlots: Map<String, String> = CatalogValues.map(clockSlots)
        val parameters: Map<String, Calculation.Parameter> = CatalogValues.map(parameters)
        val fixedArguments: Map<String, Calculation.Value> = CatalogValues.map(fixedArguments)
        val groupingCandidates: Map<String, List<ScopedInputContract.DimensionRef>> = listMap(groupingCandidates)
        val memberParameters: Map<String, Calculation.MemberSlot> = CatalogValues.map(memberParameters)
        val relationParameters: Map<String, Calculation.RelationSlot> = CatalogValues.map(relationParameters)
        init { candidate() }
        internal fun requireDefinition(definition: MemberDefinition) {
            val slots = (identitySlots + permission.outputs + permission.values + permission.filters.keys).toMutableSet()
            if (permission.timeRange) slots.add(requireNotNull(timeRange).slot)
            require(definition.slots.keys.containsAll(slots)) { "member definition differs from readable slots" }
            if (definition is ProjectionMeaning) definition.requireClosure(emptySet())
        }
        fun candidate(): MemberCandidate = MemberCandidate(sourceId, sourceVersion, displayName, entity, role, shape, identitySlots,
            outputs, permission, filterTypes, filterValueContracts, unitDimensions, timeRange, clockSlots, parameters, fixedArguments, groupingCandidates, memberParameters, relationParameters)
        override fun equals(other: Any?): Boolean = other is Member && sourceId == other.sourceId && sourceVersion == other.sourceVersion && displayName == other.displayName && entity == other.entity && role == other.role && shape == other.shape && identitySlots == other.identitySlots && outputs == other.outputs && permission == other.permission && filterTypes == other.filterTypes && filterValueContracts == other.filterValueContracts && unitDimensions == other.unitDimensions && timeRange == other.timeRange && clockSlots == other.clockSlots && parameters == other.parameters && fixedArguments == other.fixedArguments && groupingCandidates == other.groupingCandidates && memberParameters == other.memberParameters && relationParameters == other.relationParameters
        override fun hashCode(): Int = listOf(sourceId, sourceVersion, displayName, entity, role, shape, identitySlots, outputs, permission, filterTypes, filterValueContracts, unitDimensions, timeRange, clockSlots, parameters, fixedArguments, groupingCandidates, memberParameters, relationParameters).hashCode()
    }

    class RelationFilter(
        val id: String,
        val version: Long,
        val displayName: String,
        val parentEvent: EventRef,
        val entity: String,
        val role: String,
        val permission: RelationFilterPermission,
        filterTypes: Map<String, ScalarValueType>,
        filterValueContracts: Map<String, MetricValueContract?>,
        val timeRange: MemberCandidate.TimeRangeCapability?,
        clockSlots: Map<String, String>,
        val requiredByDefault: Boolean,
        association: List<SemanticJoin>,
        val sourceDefinition: ProjectionMeaning,
    ) {
        val filterTypes: Map<String, ScalarValueType> = CatalogValues.map(filterTypes)
        val filterValueContracts: Map<String, MetricValueContract?> = CatalogValues.map(filterValueContracts)
        val clockSlots: Map<String, String> = CatalogValues.map(clockSlots)
        val association: List<SemanticJoin> = java.util.List.copyOf(association)
        init {
            CatalogValues.text(id); CatalogValues.version(version); CatalogValues.text(displayName); CatalogValues.text(entity); CatalogValues.text(role)
            val slots = permission.filters.keys.toMutableSet()
            if (permission.timeRange) slots.add(requireNotNull(timeRange).slot) else require(timeRange == null) { "unapproved relation range" }
            require(slots == sourceDefinition.slots.keys && this.filterTypes.keys == permission.filters.keys && this.filterValueContracts.keys == this.filterTypes.keys && this.clockSlots.keys == permission.clocks.toSet()) { "relation meaning differs from granted slots" }
            val references = linkedSetOf<String>()
            this.association.forEach { it.references(references) }
            sourceDefinition.requireClosure(references)
            this.filterTypes.forEach { (slot, type) -> require(scalar(sourceDefinition.slots.getValue(slot)).valueType == type) { "relation slot type mismatch" } }
        }
        override fun equals(other: Any?): Boolean = other is RelationFilter && id == other.id && version == other.version && displayName == other.displayName && parentEvent == other.parentEvent && entity == other.entity && role == other.role && permission == other.permission && filterTypes == other.filterTypes && filterValueContracts == other.filterValueContracts && timeRange == other.timeRange && clockSlots == other.clockSlots && requiredByDefault == other.requiredByDefault && association == other.association && sourceDefinition == other.sourceDefinition
        override fun hashCode(): Int = listOf(id, version, displayName, parentEvent, entity, role, permission, filterTypes, filterValueContracts, timeRange, clockSlots, requiredByDefault, association, sourceDefinition).hashCode()
    }

    enum class DimensionInterpretation { FROZEN_SEMANTIC_DEFINITION_NOT_SOURCE_COMPLETENESS_PROOF }
    class DefinitionMapping(
        val levelCode: String,
        val eventId: String,
        val eventVersion: Long,
        val expression: PublicExpression,
        access: List<SemanticJoin>,
        roles: Map<String, List<SemanticJoin>>,
        sealedSourceCollections: CatalogOptional<List<SealedSourceCollectionMeaning>> = CatalogOptional.absent(),
    ) {
        val access: List<SemanticJoin> = java.util.List.copyOf(access)
        val roles: Map<String, List<SemanticJoin>> = PublishedSemanticCatalog.roles(roles)
        val sealedSourceCollections: CatalogOptional<List<SealedSourceCollectionMeaning>> = SemanticJoin.collections(sealedSourceCollections)
        init { CatalogValues.text(levelCode); CatalogValues.text(eventId); CatalogValues.version(eventVersion) }
        fun key(): String = "$levelCode:$eventId@$eventVersion"
        fun references(references: MutableSet<String>) {
            access.forEach { it.references(references) }; roleReferences(roles, references); expression.references(roles.keys, references)
        }
        override fun equals(other: Any?): Boolean = other is DefinitionMapping && levelCode == other.levelCode && eventId == other.eventId && eventVersion == other.eventVersion && expression == other.expression && access == other.access && roles == other.roles && sealedSourceCollections == other.sealedSourceCollections
        override fun hashCode(): Int = listOf(levelCode, eventId, eventVersion, expression, access, roles, sealedSourceCollections).hashCode()
    }
    class DimensionDefinition(
        val interpretation: DimensionInterpretation,
        val description: String?,
        mappings: List<DefinitionMapping>,
        logicUnits: List<LogicUnitMeaning>,
    ) {
        val mappings: List<DefinitionMapping> = CatalogValues.nonempty(mappings)
        val logicUnits: List<LogicUnitMeaning> = java.util.List.copyOf(logicUnits)
        init {
            unique(this.mappings.map { it.key() })
            val references = linkedSetOf<String>()
            this.mappings.forEach { it.references(references) }; closure(this.logicUnits, references)
        }
        fun requireMappings(expected: Set<String>) { require(mappings.map { it.key() }.toSet() == expected) { "dimension definition differs from approved mappings" } }
        override fun equals(other: Any?): Boolean = other is DimensionDefinition && interpretation == other.interpretation && description == other.description && mappings == other.mappings && logicUnits == other.logicUnits
        override fun hashCode(): Int = listOf(interpretation, description, mappings, logicUnits).hashCode()
    }
    data class EventMapping(val mappingId: String, val levelCode: String,
        val levelDisplayName: String, val eventId: String,
        val eventVersion: Long, val eventDisplayName: String,
        val valueAlignmentKey: String?, val valueAlignmentStatus: String) {
        init {
            CatalogValues.text(mappingId); CatalogValues.text(levelCode); CatalogValues.text(levelDisplayName); CatalogValues.text(eventId); CatalogValues.version(eventVersion)
            CatalogValues.text(eventDisplayName); ValueAlignmentContract.requireStatus(valueAlignmentStatus, "published alignment")
        }
        fun key(): String = "$levelCode:$eventId@$eventVersion"
    }
    data class Anchor(val mappingId: String, val levelCode: String,
        val eventId: String, val eventVersion: Long,
        val eventDisplayName: String, val timeRole: String) {
        init { CatalogValues.text(mappingId); CatalogValues.text(levelCode); CatalogValues.text(eventId); CatalogValues.version(eventVersion); CatalogValues.text(eventDisplayName); CatalogValues.text(timeRole) }
        fun key(): String = "$levelCode:$eventId@$eventVersion"
    }
    class Dimension(val id: String, val version: Long,
        val displayName: String, levels: List<String>,
        valueTypes: Map<String, ScalarValueType>, val sourceDefinition: DimensionDefinition,
        eventMappings: List<EventMapping>) {
        val levels: List<String> = CatalogValues.nonempty(levels)
        val valueTypes: Map<String, ScalarValueType> = CatalogValues.map(valueTypes)
        val eventMappings: List<EventMapping> = java.util.List.copyOf(eventMappings)
        init {
            CatalogValues.text(id); CatalogValues.version(version); CatalogValues.text(displayName)
            sourceDefinition.requireMappings(this.eventMappings.map { it.key() }.toSet()); levelTypes(this.levels, this.valueTypes)
        }
        override fun equals(other: Any?): Boolean = other is Dimension && id == other.id && version == other.version && displayName == other.displayName && levels == other.levels && valueTypes == other.valueTypes && sourceDefinition == other.sourceDefinition && eventMappings == other.eventMappings
        override fun hashCode(): Int = listOf(id, version, displayName, levels, valueTypes, sourceDefinition, eventMappings).hashCode()
    }
    class TimeDimension(val id: String, val version: Long,
        val displayName: String, levels: List<String>,
        valueTypes: Map<String, ScalarValueType>, val sourceDefinition: DimensionDefinition,
        anchors: List<Anchor>) {
        val levels: List<String> = CatalogValues.nonempty(levels)
        val valueTypes: Map<String, ScalarValueType> = CatalogValues.map(valueTypes)
        val anchors: List<Anchor> = java.util.List.copyOf(anchors)
        init {
            CatalogValues.text(id); CatalogValues.version(version); CatalogValues.text(displayName)
            sourceDefinition.requireMappings(this.anchors.map { it.key() }.toSet()); levelTypes(this.levels, this.valueTypes)
            require(this.valueTypes.values.all { it in setOf(ScalarValueType.DATE, ScalarValueType.LOCAL_TIMESTAMP, ScalarValueType.OFFSET_TIMESTAMP) }) { "time level requires temporal types" }
        }
        override fun equals(other: Any?): Boolean = other is TimeDimension && id == other.id && version == other.version && displayName == other.displayName && levels == other.levels && valueTypes == other.valueTypes && sourceDefinition == other.sourceDefinition && anchors == other.anchors
        override fun hashCode(): Int = listOf(id, version, displayName, levels, valueTypes, sourceDefinition, anchors).hashCode()
    }
    data class Combination(val instance: MetricInstanceRef, val dimensionId: String,
        val dimensionVersion: Long, val levelCode: String,
        val available: Boolean, val reasonCode: String?, val reason: String?) {
        init { CatalogValues.text(dimensionId); CatalogValues.version(dimensionVersion); CatalogValues.text(levelCode) }
    }
    data class AssetReference(val id: String, val version: Long) {
        init { CatalogValues.text(id); CatalogValues.version(version) }
    }
    data class FilterMapping(val levelCode: String, val eventId: String,
        val eventVersion: Long) {
        init { CatalogValues.text(levelCode); CatalogValues.text(eventId); CatalogValues.version(eventVersion) }
    }
    class Filter(val id: String, val version: Long,
        val displayName: String, val dimensionRef: AssetReference,
        operators: List<String>, valueTypes: Map<String, ScalarValueType>,
        val requiredByDefault: Boolean, eventMappings: List<FilterMapping>) {
        val operators: List<String> = CatalogValues.nonempty(operators)
        val valueTypes: Map<String, ScalarValueType> = CatalogValues.map(valueTypes)
        val eventMappings: List<FilterMapping> = java.util.List.copyOf(eventMappings)
        init { CatalogValues.text(id); CatalogValues.version(version); CatalogValues.text(displayName) }
        override fun equals(other: Any?): Boolean = other is Filter && id == other.id && version == other.version && displayName == other.displayName && dimensionRef == other.dimensionRef && operators == other.operators && valueTypes == other.valueTypes && requiredByDefault == other.requiredByDefault && eventMappings == other.eventMappings
        override fun hashCode(): Int = listOf(id, version, displayName, dimensionRef, operators, valueTypes, requiredByDefault, eventMappings).hashCode()
    }



    companion object {
        const val FORMAT_VERSION = "olap-published-semantic-catalog/v3"
        fun scalar(meaning: MemberSlotMeaning): ScalarSlotMeaning = meaning as? ScalarSlotMeaning ?: throw IllegalArgumentException("semantic operation requires a scalar member slot")
        fun rootValueContract(meaning: MemberSlotMeaning): MetricValueContract? = (meaning as? ScalarSlotMeaning)?.valueContract
        private fun levelTypes(levels: List<String>, types: Map<String, ScalarValueType>) {
            unique(levels); levels.forEach { CatalogValues.text(it) }
            require(types.keys == levels.toSet()) { "value types must cover published levels" }
        }
        private fun <T> exact(values: List<T>): T { require(values.size == 1) { "exact published definition is unavailable" }; return values.single() }
        private fun unique(values: List<*>) { require(values.distinct().size == values.size) { "duplicate published identity" } }
        private fun <T> listMap(values: Map<String, List<T>>): Map<String, List<T>> = Collections.unmodifiableMap(values.mapValuesTo(linkedMapOf()) { (key, value) -> CatalogValues.text(key); java.util.List.copyOf(value) })
        private fun roles(values: Map<String, List<SemanticJoin>>): Map<String, List<SemanticJoin>> = listMap(values).also { it.keys.forEach { name -> ScopedInputContract.requireId(name) } }
        private fun roleReferences(roles: Map<String, List<SemanticJoin>>, references: MutableSet<String>) { roles.values.forEach { path -> path.forEach { it.references(references) } } }
        private fun memberReferences(roles: Map<String, List<SemanticJoin>>, predicate: PublicExpression?, slots: Map<String, MemberSlotMeaning>): MutableSet<String> {
            val references = linkedSetOf<String>()
            roleReferences(roles, references); predicate?.references(roles.keys, references)
            slots.values.forEach { slot -> when (slot) {
                is SlotMeaning -> slot.references(references)
                is ContainerSlotMeaning -> slot.references(references)
                else -> throw IllegalArgumentException("projection member has a computed slot")
            } }
            return references
        }
        private fun declared(units: List<LogicUnitMeaning>): Set<String> = units.map { "${it.id}@${it.version}" }.also(::unique).toSet()
        private fun closure(units: List<LogicUnitMeaning>, references: Set<String>) { require(declared(units) == references) { "published LogicUnit closure mismatch" } }
    }
}
