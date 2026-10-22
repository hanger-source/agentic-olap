package com.olap.prototype.query.contract.catalog

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
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
import com.olap.prototype.util.OlapJsonUtils
import java.util.Collections

/** 冻结语义的唯一结构，描述定义闭包而不授予物理访问或重新解释计算。 */
@JsonDeserialize(using = PublishedSemanticCatalog.Deserializer::class)
data class PublishedSemanticCatalog(
    @param:JsonProperty(required = true) val formatVersion: String,
    @param:JsonProperty(required = true) val sha256: String,
    @param:JsonProperty(required = true) val catalog: Catalog,
) {
    init {
        require(formatVersion == FORMAT_VERSION && sha256.matches(Regex("[0-9a-f]{64}"))) { "exact published semantic format and hash required" }
        require(sha256 == QueryIntentHash.compute(OlapJsonUtils.mapper().valueToTree(catalog))) { "published semantic catalog hash mismatch" }
    }
    class Deserializer : JsonDeserializer<PublishedSemanticCatalog>() {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): PublishedSemanticCatalog {
            val artifact = parser.codec.readTree<JsonNode>(parser)
            CatalogJson.fields(artifact, PublishedSemanticCatalog::class.java, emptySet())
            require(artifact.path("sha256").isTextual && artifact.path("sha256").textValue() == QueryIntentHash.compute(artifact.required("catalog"))) { "original published semantic content hash mismatch" }
            return PublishedSemanticCatalog(CatalogJson.required(artifact, "formatVersion", String::class.java), CatalogJson.required(artifact, "sha256", String::class.java), CatalogJson.required(artifact, "catalog", Catalog::class.java))
        }
    }

    data class Coordinate(
        @param:JsonProperty(required = true) val scenarioId: String,
        @param:JsonProperty(required = true) val scenarioVersion: Long,
        @param:JsonProperty(required = true) val releaseId: String,
        @param:JsonProperty(required = true) val releaseVersion: String,
    ) {
        init { CatalogJson.text(scenarioId); CatalogJson.version(scenarioVersion); CatalogJson.text(releaseId); CatalogJson.text(releaseVersion) }
        fun request(): QueryCapabilityRequest = QueryCapabilityRequest(scenarioId, scenarioVersion, releaseId, releaseVersion)
    }

    class Catalog(
        @param:JsonProperty(required = true) val coordinate: Coordinate,
        @param:JsonProperty(required = true) val displayName: String,
        @param:JsonProperty(required = true) val sourceJoinContract: SourceJoinMeaning,
        @param:JsonProperty(required = true) metrics: List<Metric>,
        @param:JsonProperty(required = true) memberSources: List<Member>,
        @param:JsonProperty(required = true) relationFilters: List<RelationFilter>,
        @param:JsonProperty(required = true) dimensions: List<Dimension>,
        @param:JsonProperty(required = true) timeDimensions: List<TimeDimension>,
        @param:JsonProperty(required = true) combinations: List<Combination>,
        @param:JsonProperty(required = true) filters: List<Filter>,
        @param:JsonProperty(required = true) semanticDependencies: List<SemanticDependency>,
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
            CatalogJson.text(displayName)
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

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = CalculationDependency::class, name = "CALCULATION"), JsonSubTypes.Type(value = FactDependency::class, name = "FACT"), JsonSubTypes.Type(value = InstanceDependency::class, name = "INSTANCE"), JsonSubTypes.Type(value = MemberDependency::class, name = "MEMBER"))
    sealed interface SemanticDependency
    data class CalculationDependency(@param:JsonProperty(required = true) val reference: MetricRef, @param:JsonProperty(required = true) val definition: MetricCalculation) : SemanticDependency
    data class FactDependency(@param:JsonProperty(required = true) val reference: MetricRef, @param:JsonProperty(required = true) val event: EventRef,
        @param:JsonProperty(required = true) val aggregation: Aggregation, @param:JsonProperty(required = true) val valueContract: MetricValueContract,
        @param:JsonProperty(required = true) val sourceScope: FactScope) : SemanticDependency
    data class InstanceDependency(@param:JsonProperty(required = true) val reference: MetricInstanceRef, @param:JsonProperty(required = true) val instance: MetricInstance) : SemanticDependency
    class MemberDependency(@param:JsonProperty(required = true) val reference: MemberRef, @param:JsonProperty(required = true) val entity: String,
        @param:JsonProperty(required = true) val role: String, @param:JsonProperty(required = true) val shape: MemberShape,
        @param:JsonProperty(required = true) identitySlots: List<String>, @param:JsonProperty(required = true) val definition: MemberDefinition) : SemanticDependency {
        val identitySlots: List<String> = java.util.List.copyOf(identitySlots)
        init {
            CatalogJson.text(entity); CatalogJson.text(role); unique(this.identitySlots)
            require((shape == MemberShape.RAW_BAG) == this.identitySlots.isEmpty() && definition.slots.keys.containsAll(this.identitySlots)) { "semantic member identity differs from its declared shape or outputs" }
        }
        override fun equals(other: Any?): Boolean = other is MemberDependency && reference == other.reference && entity == other.entity && role == other.role && shape == other.shape && identitySlots == other.identitySlots && definition == other.definition
        override fun hashCode(): Int = listOf(reference, entity, role, shape, identitySlots, definition).hashCode()
    }

    class SourceJoinMeaning(@param:JsonProperty(required = true) val formatVersion: String,
        @param:JsonProperty(required = true) joinTypes: List<SourceJoinContract.JoinType>, @param:JsonProperty(required = true) cardinalities: List<SourceJoinContract.Cardinality>,
        @param:JsonProperty(required = true) nullPolicies: List<SourceJoinContract.NullPolicy>, @param:JsonProperty(required = true) val factPopulation: String,
        @param:JsonProperty(required = true) factMatching: Map<SourceJoinContract.JoinType, String>, @param:JsonProperty(required = true) val memberPopulation: String,
        @param:JsonProperty(required = true) val existsPopulation: String, @param:JsonProperty(required = true) val keyMatching: String, @param:JsonProperty(required = true) val guarantees: String) {
        val joinTypes: List<SourceJoinContract.JoinType> = CatalogJson.nonempty(joinTypes)
        val cardinalities: List<SourceJoinContract.Cardinality> = CatalogJson.nonempty(cardinalities)
        val nullPolicies: List<SourceJoinContract.NullPolicy> = CatalogJson.nonempty(nullPolicies)
        val factMatching: Map<SourceJoinContract.JoinType, String> = Collections.unmodifiableMap(LinkedHashMap(factMatching))
        init {
            CatalogJson.text(formatVersion); unique(this.joinTypes); unique(this.cardinalities); unique(this.nullPolicies)
            listOf(factPopulation, memberPopulation, existsPopulation, keyMatching, guarantees).forEach { CatalogJson.text(it) }
            require(this.factMatching.keys == this.joinTypes.toSet()) { "source matching must cover its join types" }
            this.factMatching.values.forEach { CatalogJson.text(it) }
        }
        override fun equals(other: Any?): Boolean = other is SourceJoinMeaning && formatVersion == other.formatVersion && joinTypes == other.joinTypes && cardinalities == other.cardinalities && nullPolicies == other.nullPolicies && factPopulation == other.factPopulation && factMatching == other.factMatching && memberPopulation == other.memberPopulation && existsPopulation == other.existsPopulation && keyMatching == other.keyMatching && guarantees == other.guarantees
        override fun hashCode(): Int = listOf(formatVersion, joinTypes, cardinalities, nullPolicies, factPopulation, factMatching, memberPopulation, existsPopulation, keyMatching, guarantees).hashCode()
        companion object { fun current(): SourceJoinMeaning = CatalogJson.read(OlapJsonUtils.mapper().valueToTree(SourceJoinContract.contract()), SourceJoinMeaning::class.java) }
    }
    enum class MetricKind { FACT_MEASURE, CALCULATION }
    data class Aggregation(@param:JsonProperty(required = true) val function: MetricAggregation,
        @param:JsonProperty(required = true) val distinct: Boolean, @param:JsonProperty(required = true) val rounding: MetricValueContract.Rounding)
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = FactScope::class, name = "FACT"), JsonSubTypes.Type(value = ProjectionMeaning::class, name = "PROJECTION"), JsonSubTypes.Type(value = ComputedMeaning::class, name = "COMPUTED"))
    sealed interface SourceScope
    class SourceExplanation(@param:JsonProperty(required = true) callPath: List<String>, @param:JsonProperty(required = true) val scope: SourceScope) {
        val callPath: List<String> = java.util.List.copyOf(callPath)
        init { this.callPath.forEach { CatalogJson.text(it) } }
        override fun equals(other: Any?): Boolean = other is SourceExplanation && callPath == other.callPath && scope == other.scope
        override fun hashCode(): Int = listOf(callPath, scope).hashCode()
    }

    @JsonDeserialize(using = MetricDeserializer::class)
    class Metric(
        @param:JsonProperty(required = true) val adoptionId: String,
        @param:JsonProperty(required = true) val adoptionVersion: Long,
        @param:JsonProperty(required = true) val subjectAreaId: String,
        @param:JsonProperty(required = true) val subjectAreaVersion: Long,
        @param:JsonProperty(required = true) val code: String,
        @param:JsonProperty(required = true) val displayName: String,
        @param:JsonProperty(required = true) val businessMeaning: MetricBusinessMeaning,
        @param:JsonProperty(required = true) val definition: MetricRef,
        @param:JsonProperty(required = true) val instance: MetricInstanceRef,
        @param:JsonProperty(required = true) val valueContract: MetricValueContract,
        @param:JsonProperty(required = true) val snapshotMeaning: MetricSnapshotRule.Meaning?,
        @param:JsonProperty(required = true) sources: List<MetricSource>,
        @param:JsonProperty(required = true) val definitionKind: MetricKind,
        @param:JsonProperty(required = true) fixedArguments: Map<String, Calculation.Value>,
        @param:JsonProperty(required = true) parameters: Map<String, Calculation.Parameter>,
        @param:JsonProperty(required = true) groupingCandidates: Map<String, List<ScopedInputContract.DimensionRef>>,
        @param:JsonProperty(required = true) memberParameters: Map<String, Calculation.MemberSlot>,
        @param:JsonProperty(required = true) relationParameters: Map<String, Calculation.RelationSlot>,
        @param:JsonProperty(required = true) sourceScopes: List<SourceExplanation>,
        @get:JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = CatalogOptional.Missing::class)
        val aggregation: CatalogOptional<Aggregation> = CatalogOptional.absent(),
    ) {
        val sources: List<MetricSource> = java.util.List.copyOf(sources)
        val fixedArguments: Map<String, Calculation.Value> = CatalogJson.map(fixedArguments)
        val parameters: Map<String, Calculation.Parameter> = CatalogJson.map(parameters)
        val groupingCandidates: Map<String, List<ScopedInputContract.DimensionRef>> = listMap(groupingCandidates)
        val memberParameters: Map<String, Calculation.MemberSlot> = CatalogJson.map(memberParameters)
        val relationParameters: Map<String, Calculation.RelationSlot> = CatalogJson.map(relationParameters)
        val sourceScopes: List<SourceExplanation> = java.util.List.copyOf(sourceScopes)
        init {
            CatalogJson.text(adoptionId); CatalogJson.version(adoptionVersion); CatalogJson.text(subjectAreaId); CatalogJson.version(subjectAreaVersion)
            CatalogJson.text(code); CatalogJson.text(displayName)
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
        @param:JsonProperty(required = true) val interpretation: FactInterpretation,
        @param:JsonProperty(required = true) val eventPredicate: PublicExpression?,
        @param:JsonProperty(required = true) eventRoles: Map<String, List<SemanticJoin>>,
        @param:JsonProperty(required = true) val snapshotRule: MetricSnapshotRule?,
        @param:JsonProperty(required = true) val inputExpression: PublicExpression,
        @param:JsonProperty(required = true) val privatePredicate: PublicExpression?,
        @param:JsonProperty(required = true) metricRoles: Map<String, List<SemanticJoin>>,
        @param:JsonProperty(required = true) logicUnits: List<LogicUnitMeaning>,
        sealedSourceCollections: CatalogOptional<List<SealedSourceCollectionMeaning>> = CatalogOptional.absent(),
    ) : SourceScope {
        val eventRoles: Map<String, List<SemanticJoin>> = roles(eventRoles)
        val metricRoles: Map<String, List<SemanticJoin>> = roles(metricRoles)
        val logicUnits: List<LogicUnitMeaning> = java.util.List.copyOf(logicUnits)
        @get:JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = CatalogOptional.Missing::class)
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
        @param:JsonProperty(required = true) val id: String,
        @param:JsonProperty(required = true) val version: Long,
        @param:JsonProperty(required = true) val displayName: String,
        @param:JsonProperty(required = true) val valueType: ScalarValueType?,
        @param:JsonProperty(required = true) val valueShape: SetOutputContract.Shape?,
    ) {
        init {
            CatalogJson.text(id); CatalogJson.version(version); CatalogJson.text(displayName)
            require((valueType == null) != (valueShape == null)) { "LogicUnit meaning requires one scalar or container type" }
            valueShape?.let { SetOutputContract.requireSourceContainer(it) }
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = SlotMeaning::class, name = "SCALAR"), JsonSubTypes.Type(value = ComputedSlotMeaning::class, name = "COMPUTED_SCALAR"), JsonSubTypes.Type(value = ContainerSlotMeaning::class, name = "CONTAINER"), JsonSubTypes.Type(value = ComputedContainerSlotMeaning::class, name = "COMPUTED_CONTAINER"))
    sealed interface MemberSlotMeaning { val displayName: String; val role: String }
    sealed interface ScalarSlotMeaning : MemberSlotMeaning { val valueType: ScalarValueType; val valueContract: MetricValueContract? }
    class ContainerSlotMeaning(override val displayName: String, override val role: String, val shape: SetOutputContract.Shape,
        val expression: PublicExpression, access: List<SemanticJoin>, roles: Map<String, List<SemanticJoin>>) : MemberSlotMeaning {
        val access: List<SemanticJoin> = java.util.List.copyOf(access)
        val roles: Map<String, List<SemanticJoin>> = PublishedSemanticCatalog.roles(roles)
        init { CatalogJson.text(displayName); CatalogJson.text(role); SetOutputContract.requireSourceContainer(shape) }
        fun references(references: MutableSet<String>) {
            access.forEach { it.references(references) }; roleReferences(roles, references); expression.references(roles.keys, references)
        }
        override fun equals(other: Any?): Boolean = other is ContainerSlotMeaning && displayName == other.displayName && role == other.role && shape == other.shape && expression == other.expression && access == other.access && roles == other.roles
        override fun hashCode(): Int = listOf(displayName, role, shape, expression, access, roles).hashCode()
    }
    data class ComputedContainerSlotMeaning(override val displayName: String, override val role: String,
        val shape: SetOutputContract.Shape, val symbol: String) : MemberSlotMeaning {
        init { CatalogJson.text(displayName); CatalogJson.text(role); SetOutputContract.requireSourceContainer(shape); CatalogJson.text(symbol) }
    }
    data class ComputedSlotMeaning(
        @param:JsonProperty(required = true) override val displayName: String,
        @param:JsonProperty(required = true) override val role: String,
        @param:JsonProperty(required = true) override val valueType: ScalarValueType,
        @param:JsonProperty(required = true) override val valueContract: MetricValueContract?,
        @param:JsonProperty(required = true) val dimensionSlot: String?,
        @param:JsonProperty(required = true) val symbol: String,
    ) : ScalarSlotMeaning {
        init { CatalogJson.text(displayName); CatalogJson.text(role); CatalogJson.text(symbol); dimensionSlot?.let { CatalogJson.text(it) } }
    }
    class SlotMeaning(
        @param:JsonProperty(required = true) override val displayName: String,
        @param:JsonProperty(required = true) override val role: String,
        @param:JsonProperty(required = true) override val valueType: ScalarValueType,
        @param:JsonProperty(required = true) override val valueContract: MetricValueContract?,
        @param:JsonProperty(required = true) val expression: PublicExpression,
        @param:JsonProperty(required = true) access: List<SemanticJoin>,
        @param:JsonProperty(required = true) roles: Map<String, List<SemanticJoin>>,
    ) : ScalarSlotMeaning {
        val access: List<SemanticJoin> = java.util.List.copyOf(access)
        val roles: Map<String, List<SemanticJoin>> = PublishedSemanticCatalog.roles(roles)
        init { CatalogJson.text(displayName); CatalogJson.text(role) }
        fun references(references: MutableSet<String>) {
            access.forEach { it.references(references) }; roleReferences(roles, references); expression.references(roles.keys, references)
        }
        override fun equals(other: Any?): Boolean = other is SlotMeaning && displayName == other.displayName && role == other.role && valueType == other.valueType && valueContract == other.valueContract && expression == other.expression && access == other.access && roles == other.roles
        override fun hashCode(): Int = listOf(displayName, role, valueType, valueContract, expression, access, roles).hashCode()
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = ProjectionMeaning::class, name = "PROJECTION"), JsonSubTypes.Type(value = ComputedMeaning::class, name = "COMPUTED"))
    sealed interface MemberDefinition : SourceScope {
        val slots: Map<String, MemberSlotMeaning>
        val description: CatalogOptional<String>
    }
    class ComputedMeaning(
        @param:JsonProperty(required = true) val calculation: Calculation,
        @param:JsonProperty(required = true) val bindings: CalculationBindings,
        @param:JsonProperty(required = true) slots: Map<String, MemberSlotMeaning>,
        @param:JsonProperty(required = true) sourceScopes: List<SourceExplanation>,
        @param:JsonProperty(required = true) parameters: Map<String, Calculation.Parameter>,
        @param:JsonProperty(required = true) fixedArguments: Map<String, Calculation.Value>,
        @param:JsonProperty(required = true) groupingCandidates: Map<String, List<ScopedInputContract.DimensionRef>>,
        @param:JsonProperty(required = true) memberParameters: Map<String, Calculation.MemberSlot>,
        @param:JsonProperty(required = true) relationParameters: Map<String, Calculation.RelationSlot>,
        @get:JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = CatalogOptional.Missing::class)
        override val description: CatalogOptional<String> = CatalogOptional.absent(),
    ) : MemberDefinition {
        override val slots: Map<String, MemberSlotMeaning> = CatalogJson.map(slots)
        val sourceScopes: List<SourceExplanation> = java.util.List.copyOf(sourceScopes)
        val parameters: Map<String, Calculation.Parameter> = CatalogJson.map(parameters)
        val fixedArguments: Map<String, Calculation.Value> = CatalogJson.map(fixedArguments)
        val groupingCandidates: Map<String, List<ScopedInputContract.DimensionRef>> = listMap(groupingCandidates)
        val memberParameters: Map<String, Calculation.MemberSlot> = CatalogJson.map(memberParameters)
        val relationParameters: Map<String, Calculation.RelationSlot> = CatalogJson.map(relationParameters)
        init {
            require(this.parameters.keys.intersect(this.fixedArguments.keys).isEmpty()) { "fixed computed arguments overlap invocation parameters" }
            description.value?.let { CatalogJson.text(it) }
        }
        override fun equals(other: Any?): Boolean = other is ComputedMeaning && calculation == other.calculation && bindings == other.bindings && slots == other.slots && sourceScopes == other.sourceScopes && parameters == other.parameters && fixedArguments == other.fixedArguments && groupingCandidates == other.groupingCandidates && memberParameters == other.memberParameters && relationParameters == other.relationParameters && description == other.description
        override fun hashCode(): Int = listOf(calculation, bindings, slots, sourceScopes, parameters, fixedArguments, groupingCandidates, memberParameters, relationParameters, description).hashCode()
    }
    class ProjectionMeaning(
        @param:JsonProperty(required = true) val event: EventRef,
        @param:JsonProperty(required = true) eventRoles: Map<String, List<SemanticJoin>>,
        @param:JsonProperty(required = true) val eventPredicate: PublicExpression?,
        @param:JsonProperty(required = true) slots: Map<String, MemberSlotMeaning>,
        @param:JsonProperty(required = true) logicUnits: List<LogicUnitMeaning>,
        @param:JsonProperty(required = true) sealedSourceCollections: List<SealedSourceCollectionMeaning>,
        @get:JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = CatalogOptional.Missing::class)
        override val description: CatalogOptional<String> = CatalogOptional.absent(),
    ) : MemberDefinition {
        val eventRoles: Map<String, List<SemanticJoin>> = roles(eventRoles)
        override val slots: Map<String, MemberSlotMeaning> = CatalogJson.map(slots)
        val logicUnits: List<LogicUnitMeaning> = java.util.List.copyOf(logicUnits)
        val sealedSourceCollections: List<SealedSourceCollectionMeaning> = requireNotNull(SemanticJoin.collections(CatalogOptional.of(sealedSourceCollections)).value)
        init {
            this.slots.keys.forEach { CatalogJson.text(it) }
            description.value?.let { CatalogJson.text(it) }
            require(declared(this.logicUnits).containsAll(memberReferences(this.eventRoles, eventPredicate, this.slots))) { "member meaning has an undeclared LogicUnit" }
        }
        fun requireClosure(additional: Set<String>) { closure(logicUnits, memberReferences(eventRoles, eventPredicate, slots) + additional) }
        override fun equals(other: Any?): Boolean = other is ProjectionMeaning && event == other.event && eventRoles == other.eventRoles && eventPredicate == other.eventPredicate && slots == other.slots && logicUnits == other.logicUnits && sealedSourceCollections == other.sealedSourceCollections && description == other.description
        override fun hashCode(): Int = listOf(event, eventRoles, eventPredicate, slots, logicUnits, sealedSourceCollections, description).hashCode()
    }

    class Member(
        @param:JsonProperty(required = true) val sourceId: String,
        @param:JsonProperty(required = true) val sourceVersion: Long,
        @param:JsonProperty(required = true) val displayName: String,
        @param:JsonProperty(required = true) val entity: String,
        @param:JsonProperty(required = true) val role: String,
        @param:JsonProperty(required = true) val shape: MemberShape,
        @param:JsonProperty(required = true) identitySlots: List<String>,
        @param:JsonProperty(required = true) outputs: Map<String, MemberCandidate.Output>,
        @param:JsonProperty(required = true) val permission: MemberQueryPermission,
        @param:JsonProperty(required = true) filterTypes: Map<String, ScalarValueType>,
        @param:JsonProperty(required = true) filterValueContracts: Map<String, MetricValueContract?>,
        @param:JsonProperty(required = true) unitDimensions: Map<String, List<ScopedInputContract.DimensionRef>>,
        @param:JsonProperty(required = true) val timeRange: MemberCandidate.TimeRangeCapability?,
        @param:JsonProperty(required = true) clockSlots: Map<String, String>,
        @param:JsonProperty(required = true) parameters: Map<String, Calculation.Parameter>,
        @param:JsonProperty(required = true) fixedArguments: Map<String, Calculation.Value>,
        @param:JsonProperty(required = true) groupingCandidates: Map<String, List<ScopedInputContract.DimensionRef>>,
        @param:JsonProperty(required = true) memberParameters: Map<String, Calculation.MemberSlot>,
        @param:JsonProperty(required = true) relationParameters: Map<String, Calculation.RelationSlot>,
    ) {
        val identitySlots: List<String> = java.util.List.copyOf(identitySlots)
        val outputs: Map<String, MemberCandidate.Output> = CatalogJson.map(outputs)
        val filterTypes: Map<String, ScalarValueType> = CatalogJson.map(filterTypes)
        val filterValueContracts: Map<String, MetricValueContract?> = CatalogJson.map(filterValueContracts)
        val unitDimensions: Map<String, List<ScopedInputContract.DimensionRef>> = listMap(unitDimensions)
        val clockSlots: Map<String, String> = CatalogJson.map(clockSlots)
        val parameters: Map<String, Calculation.Parameter> = CatalogJson.map(parameters)
        val fixedArguments: Map<String, Calculation.Value> = CatalogJson.map(fixedArguments)
        val groupingCandidates: Map<String, List<ScopedInputContract.DimensionRef>> = listMap(groupingCandidates)
        val memberParameters: Map<String, Calculation.MemberSlot> = CatalogJson.map(memberParameters)
        val relationParameters: Map<String, Calculation.RelationSlot> = CatalogJson.map(relationParameters)
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
        @param:JsonProperty(required = true) val id: String,
        @param:JsonProperty(required = true) val version: Long,
        @param:JsonProperty(required = true) val displayName: String,
        @param:JsonProperty(required = true) val parentEvent: EventRef,
        @param:JsonProperty(required = true) val entity: String,
        @param:JsonProperty(required = true) val role: String,
        @param:JsonProperty(required = true) val permission: RelationFilterPermission,
        @param:JsonProperty(required = true) filterTypes: Map<String, ScalarValueType>,
        @param:JsonProperty(required = true) filterValueContracts: Map<String, MetricValueContract?>,
        @param:JsonProperty(required = true) val timeRange: MemberCandidate.TimeRangeCapability?,
        @param:JsonProperty(required = true) clockSlots: Map<String, String>,
        @param:JsonProperty(required = true) val requiredByDefault: Boolean,
        @param:JsonProperty(required = true) association: List<SemanticJoin>,
        @param:JsonProperty(required = true) val sourceDefinition: ProjectionMeaning,
    ) {
        val filterTypes: Map<String, ScalarValueType> = CatalogJson.map(filterTypes)
        val filterValueContracts: Map<String, MetricValueContract?> = CatalogJson.map(filterValueContracts)
        val clockSlots: Map<String, String> = CatalogJson.map(clockSlots)
        val association: List<SemanticJoin> = java.util.List.copyOf(association)
        init {
            CatalogJson.text(id); CatalogJson.version(version); CatalogJson.text(displayName); CatalogJson.text(entity); CatalogJson.text(role)
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
        @param:JsonProperty(required = true) val levelCode: String,
        @param:JsonProperty(required = true) val eventId: String,
        @param:JsonProperty(required = true) val eventVersion: Long,
        @param:JsonProperty(required = true) val expression: PublicExpression,
        @param:JsonProperty(required = true) access: List<SemanticJoin>,
        @param:JsonProperty(required = true) roles: Map<String, List<SemanticJoin>>,
        sealedSourceCollections: CatalogOptional<List<SealedSourceCollectionMeaning>> = CatalogOptional.absent(),
    ) {
        val access: List<SemanticJoin> = java.util.List.copyOf(access)
        val roles: Map<String, List<SemanticJoin>> = PublishedSemanticCatalog.roles(roles)
        @get:JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = CatalogOptional.Missing::class)
        val sealedSourceCollections: CatalogOptional<List<SealedSourceCollectionMeaning>> = SemanticJoin.collections(sealedSourceCollections)
        init { CatalogJson.text(levelCode); CatalogJson.text(eventId); CatalogJson.version(eventVersion) }
        fun key(): String = "$levelCode:$eventId@$eventVersion"
        fun references(references: MutableSet<String>) {
            access.forEach { it.references(references) }; roleReferences(roles, references); expression.references(roles.keys, references)
        }
        override fun equals(other: Any?): Boolean = other is DefinitionMapping && levelCode == other.levelCode && eventId == other.eventId && eventVersion == other.eventVersion && expression == other.expression && access == other.access && roles == other.roles && sealedSourceCollections == other.sealedSourceCollections
        override fun hashCode(): Int = listOf(levelCode, eventId, eventVersion, expression, access, roles, sealedSourceCollections).hashCode()
    }
    class DimensionDefinition(
        @param:JsonProperty(required = true) val interpretation: DimensionInterpretation,
        @param:JsonProperty(required = true) val description: String?,
        @param:JsonProperty(required = true) mappings: List<DefinitionMapping>,
        @param:JsonProperty(required = true) logicUnits: List<LogicUnitMeaning>,
    ) {
        val mappings: List<DefinitionMapping> = CatalogJson.nonempty(mappings)
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
    data class EventMapping(@param:JsonProperty(required = true) val mappingId: String, @param:JsonProperty(required = true) val levelCode: String,
        @param:JsonProperty(required = true) val levelDisplayName: String, @param:JsonProperty(required = true) val eventId: String,
        @param:JsonProperty(required = true) val eventVersion: Long, @param:JsonProperty(required = true) val eventDisplayName: String,
        @param:JsonProperty(required = true) val valueAlignmentKey: String?, @param:JsonProperty(required = true) val valueAlignmentStatus: String) {
        init {
            CatalogJson.text(mappingId); CatalogJson.text(levelCode); CatalogJson.text(levelDisplayName); CatalogJson.text(eventId); CatalogJson.version(eventVersion)
            CatalogJson.text(eventDisplayName); ValueAlignmentContract.requireStatus(valueAlignmentStatus, "published alignment")
        }
        fun key(): String = "$levelCode:$eventId@$eventVersion"
    }
    data class Anchor(@param:JsonProperty(required = true) val mappingId: String, @param:JsonProperty(required = true) val levelCode: String,
        @param:JsonProperty(required = true) val eventId: String, @param:JsonProperty(required = true) val eventVersion: Long,
        @param:JsonProperty(required = true) val eventDisplayName: String, @param:JsonProperty(required = true) val timeRole: String) {
        init { CatalogJson.text(mappingId); CatalogJson.text(levelCode); CatalogJson.text(eventId); CatalogJson.version(eventVersion); CatalogJson.text(eventDisplayName); CatalogJson.text(timeRole) }
        fun key(): String = "$levelCode:$eventId@$eventVersion"
    }
    class Dimension(@param:JsonProperty(required = true) val id: String, @param:JsonProperty(required = true) val version: Long,
        @param:JsonProperty(required = true) val displayName: String, @param:JsonProperty(required = true) levels: List<String>,
        @param:JsonProperty(required = true) valueTypes: Map<String, ScalarValueType>, @param:JsonProperty(required = true) val sourceDefinition: DimensionDefinition,
        @param:JsonProperty(required = true) eventMappings: List<EventMapping>) {
        val levels: List<String> = CatalogJson.nonempty(levels)
        val valueTypes: Map<String, ScalarValueType> = CatalogJson.map(valueTypes)
        val eventMappings: List<EventMapping> = java.util.List.copyOf(eventMappings)
        init {
            CatalogJson.text(id); CatalogJson.version(version); CatalogJson.text(displayName)
            sourceDefinition.requireMappings(this.eventMappings.map { it.key() }.toSet()); levelTypes(this.levels, this.valueTypes)
        }
        override fun equals(other: Any?): Boolean = other is Dimension && id == other.id && version == other.version && displayName == other.displayName && levels == other.levels && valueTypes == other.valueTypes && sourceDefinition == other.sourceDefinition && eventMappings == other.eventMappings
        override fun hashCode(): Int = listOf(id, version, displayName, levels, valueTypes, sourceDefinition, eventMappings).hashCode()
    }
    class TimeDimension(@param:JsonProperty(required = true) val id: String, @param:JsonProperty(required = true) val version: Long,
        @param:JsonProperty(required = true) val displayName: String, @param:JsonProperty(required = true) levels: List<String>,
        @param:JsonProperty(required = true) valueTypes: Map<String, ScalarValueType>, @param:JsonProperty(required = true) val sourceDefinition: DimensionDefinition,
        @param:JsonProperty(required = true) anchors: List<Anchor>) {
        val levels: List<String> = CatalogJson.nonempty(levels)
        val valueTypes: Map<String, ScalarValueType> = CatalogJson.map(valueTypes)
        val anchors: List<Anchor> = java.util.List.copyOf(anchors)
        init {
            CatalogJson.text(id); CatalogJson.version(version); CatalogJson.text(displayName)
            sourceDefinition.requireMappings(this.anchors.map { it.key() }.toSet()); levelTypes(this.levels, this.valueTypes)
            require(this.valueTypes.values.all { it in setOf(ScalarValueType.DATE, ScalarValueType.LOCAL_TIMESTAMP, ScalarValueType.OFFSET_TIMESTAMP) }) { "time level requires temporal types" }
        }
        override fun equals(other: Any?): Boolean = other is TimeDimension && id == other.id && version == other.version && displayName == other.displayName && levels == other.levels && valueTypes == other.valueTypes && sourceDefinition == other.sourceDefinition && anchors == other.anchors
        override fun hashCode(): Int = listOf(id, version, displayName, levels, valueTypes, sourceDefinition, anchors).hashCode()
    }
    data class Combination(@param:JsonProperty(required = true) val instance: MetricInstanceRef, @param:JsonProperty(required = true) val dimensionId: String,
        @param:JsonProperty(required = true) val dimensionVersion: Long, @param:JsonProperty(required = true) val levelCode: String,
        @param:JsonProperty(required = true) val available: Boolean, @param:JsonProperty(required = true) val reasonCode: String?, @param:JsonProperty(required = true) val reason: String?) {
        init { CatalogJson.text(dimensionId); CatalogJson.version(dimensionVersion); CatalogJson.text(levelCode) }
    }
    data class AssetReference(@param:JsonProperty(required = true) val id: String, @param:JsonProperty(required = true) val version: Long) {
        init { CatalogJson.text(id); CatalogJson.version(version) }
    }
    data class FilterMapping(@param:JsonProperty(required = true) val levelCode: String, @param:JsonProperty(required = true) val eventId: String,
        @param:JsonProperty(required = true) val eventVersion: Long) {
        init { CatalogJson.text(levelCode); CatalogJson.text(eventId); CatalogJson.version(eventVersion) }
    }
    class Filter(@param:JsonProperty(required = true) val id: String, @param:JsonProperty(required = true) val version: Long,
        @param:JsonProperty(required = true) val displayName: String, @param:JsonProperty(required = true) val dimensionRef: AssetReference,
        @param:JsonProperty(required = true) operators: List<String>, @param:JsonProperty(required = true) valueTypes: Map<String, ScalarValueType>,
        @param:JsonProperty(required = true) val requiredByDefault: Boolean, @param:JsonProperty(required = true) eventMappings: List<FilterMapping>) {
        val operators: List<String> = CatalogJson.nonempty(operators)
        val valueTypes: Map<String, ScalarValueType> = CatalogJson.map(valueTypes)
        val eventMappings: List<FilterMapping> = java.util.List.copyOf(eventMappings)
        init { CatalogJson.text(id); CatalogJson.version(version); CatalogJson.text(displayName) }
        override fun equals(other: Any?): Boolean = other is Filter && id == other.id && version == other.version && displayName == other.displayName && dimensionRef == other.dimensionRef && operators == other.operators && valueTypes == other.valueTypes && requiredByDefault == other.requiredByDefault && eventMappings == other.eventMappings
        override fun hashCode(): Int = listOf(id, version, displayName, dimensionRef, operators, valueTypes, requiredByDefault, eventMappings).hashCode()
    }

    class MetricDeserializer : JsonDeserializer<Metric>() {
        override fun deserialize(input: JsonParser, context: DeserializationContext): Metric {
            val node = input.codec.readTree<JsonNode>(input)
            CatalogJson.fields(node, Metric::class.java, emptySet())
            fun <T> dictionary(name: String, type: Class<T>): Map<String, T> {
                val values = node.path(name)
                require(values.isObject) { "metric $name dictionary required" }
                return values.fields().asSequence().associateTo(linkedMapOf()) { it.key to CatalogJson.read(it.value, type) }
            }
            val groups = node.path("groupingCandidates")
            require(groups.isObject) { "metric parameter dictionaries required" }
            return Metric(
                CatalogJson.required(node, "adoptionId", String::class.java), CatalogJson.required(node, "adoptionVersion", Long::class.javaObjectType),
                CatalogJson.required(node, "subjectAreaId", String::class.java), CatalogJson.required(node, "subjectAreaVersion", Long::class.javaObjectType),
                CatalogJson.required(node, "code", String::class.java), CatalogJson.required(node, "displayName", String::class.java),
                CatalogJson.required(node, "businessMeaning", MetricBusinessMeaning::class.java), CatalogJson.required(node, "definition", MetricRef::class.java),
                CatalogJson.required(node, "instance", MetricInstanceRef::class.java), CatalogJson.required(node, "valueContract", MetricValueContract::class.java),
                CatalogJson.required(node, "snapshotMeaning", MetricSnapshotRule.Meaning::class.java), CatalogJson.list(node["sources"], MetricSource::class.java),
                CatalogJson.required(node, "definitionKind", MetricKind::class.java), dictionary("fixedArguments", Calculation.Value::class.java),
                dictionary("parameters", Calculation.Parameter::class.java),
                groups.fields().asSequence().associateTo(linkedMapOf()) { it.key to CatalogJson.list(it.value, ScopedInputContract.DimensionRef::class.java) },
                dictionary("memberParameters", Calculation.MemberSlot::class.java), dictionary("relationParameters", Calculation.RelationSlot::class.java), CatalogJson.list(node["sourceScopes"], SourceExplanation::class.java),
                if (node.has("aggregation")) CatalogOptional.of(CatalogJson.read(node["aggregation"], Aggregation::class.java)) else CatalogOptional.absent(),
            )
        }
    }

    companion object {
        const val FORMAT_VERSION = "olap-published-semantic-catalog/v3"
        fun create(catalog: Catalog): PublishedSemanticCatalog = PublishedSemanticCatalog(FORMAT_VERSION, QueryIntentHash.compute(OlapJsonUtils.mapper().valueToTree(catalog)), catalog)
        fun read(artifact: JsonNode): PublishedSemanticCatalog = CatalogJson.read(artifact, PublishedSemanticCatalog::class.java)
        fun scalar(meaning: MemberSlotMeaning): ScalarSlotMeaning = meaning as? ScalarSlotMeaning ?: throw IllegalArgumentException("semantic operation requires a scalar member slot")
        fun rootValueContract(meaning: MemberSlotMeaning): MetricValueContract? = (meaning as? ScalarSlotMeaning)?.valueContract
        private fun levelTypes(levels: List<String>, types: Map<String, ScalarValueType>) {
            unique(levels); levels.forEach { CatalogJson.text(it) }
            require(types.keys == levels.toSet()) { "value types must cover published levels" }
        }
        private fun <T> exact(values: List<T>): T { require(values.size == 1) { "exact published definition is unavailable" }; return values.single() }
        private fun unique(values: List<*>) { require(values.distinct().size == values.size) { "duplicate published identity" } }
        private fun <T> listMap(values: Map<String, List<T>>): Map<String, List<T>> = Collections.unmodifiableMap(values.mapValuesTo(linkedMapOf()) { (key, value) -> CatalogJson.text(key); java.util.List.copyOf(value) })
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
