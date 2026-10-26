package com.olap.prototype.query.contract

import com.olap.prototype.calculation.*
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.InputContractException
import com.olap.prototype.engine.catalog.ExecutableCatalog
import com.olap.prototype.member.MemberCondition
import com.olap.prototype.member.MemberSource
import com.olap.prototype.query.*
import com.olap.prototype.time.TemporalWindow

/** 完整查询核验与引擎共用的精确发布批准；不改变定义内部的私有依赖政策。 */
object PublishedQueryContract {
    const val FORMAT_VERSION = QueryIntent.FORMAT_VERSION
    fun authorize(intent: QueryIntent, capability: QueryCapabilityView) = authorize(intent.formatVersion, intent.publication, intent.computation, capability)
    fun authorize(formatVersion: String, coordinate: QueryCapabilityRequest, computation: Calculation.Scope, capability: QueryCapabilityView) {
        requirePublication(formatVersion, coordinate, capability)
        Authorization(capability).authorizeScope(computation, 0)
    }
    fun coordinate(intent: QueryIntent): QueryCapabilityRequest = intent.publication
    private fun requirePublication(formatVersion: String, coordinate: QueryCapabilityRequest, capability: QueryCapabilityView) {
        require((capability.capabilityHash?.matches(Regex("[a-f0-9]{64}")) == true)) { "exact capability hash is required" }
        if (capability.subjectAreaCapabilityContext.subjectAreaScenarioRefStatus != "ACTIVE") fail("published scenario adoption is not active")
        if (formatVersion != FORMAT_VERSION) fail("query intent formatVersion must be $FORMAT_VERSION")
        if (coordinate != PublishedQueryEntry.key(capability)) fail("query coordinate differs from publication")
    }
    private fun requireRootInput(scope: Calculation.Scope, id: String, path: List<Any>) {
        if (scope.inputs[id]?.mode != "ROOT") throw InputContractException.violation(InputContractException.Rule.ROOT_INPUT_REQUIRED,
            path + listOf("inputs", id, "mode"), "root reference requires an explicit ROOT input: $id", "ROOT", scope.inputs[id]?.mode ?: "MISSING")
    }
    private fun key(id: String, version: Any): String = "$id@$version"
    private fun fail(message: String): Nothing = throw ProducerContractException(message)
    fun authorizeMemberValues(call: Calculation.Members, capability: QueryCapabilityView) { Authorization(capability).authorizeMemberValues(call) }

    /** 此次授权的索引、预算和惰性编译目录，绝不跨请求缓存已授权状态。 */
    private class Authorization(private val capability: QueryCapabilityView) {
        private val count = intArrayOf(0)
        private val metrics = capability.metricCandidates.associateBy { it.instance }
        private val members = capability.memberCandidates.associateBy { key(it.sourceId, it.sourceVersion) }
        private val dimensions = linkedMapOf<ScopedInputContract.DimensionRef, AnalysisDimensionCandidate>()
        private val filters = linkedMapOf<String, FilterCandidate>()
        private val relationFilters = linkedMapOf<String, RelationFilterCandidate>()
        private val timeDimensions = linkedMapOf<String, TimeAnalysisDimensionCandidate>()
        private val clocks = linkedMapOf<String, MutableSet<TemporalWindow.ValueType>>()
        private val matrix = linkedMapOf<MetricInstanceRef, MutableList<MetricAnalysisDimensionCapability>>()
        private val stageRules = linkedMapOf<String, MutableList<PostProcessCapability>>()

        private val compilationCatalog: ExecutableCatalog by lazy(LazyThreadSafetyMode.NONE) {
            capability.nativeDefinitions.catalog
        }
        init {
            capability.analysisDimensionCandidates.forEach { dimension -> dimension.availableLevelCodes.forEach { level ->
                dimensions.putIfAbsent(ScopedInputContract.DimensionRef(dimension.analysisDimensionId, dimension.analysisDimensionVersion.toString(), level), dimension)
            } }
            capability.filterCandidates.forEach { filters.putIfAbsent(it.filterRefId, it) }
            capability.relationFilterCandidates.forEach { relationFilters.putIfAbsent(it.definition.relation.id, it) }
            capability.timeAnalysisDimensionCandidates.forEach { timeDimensions.putIfAbsent(key(it.analysisDimensionId, it.analysisDimensionVersion), it) }
            capability.temporalClockCandidates.forEach { clocks.getOrPut(it.clockRef) { linkedSetOf() }.add(it.valueType) }
            capability.metricAnalysisDimensionMatrix.forEach { matrix.getOrPut(it.instance) { mutableListOf() }.add(it) }
            capability.postProcessCapabilities.filter { it.available }.forEach { stageRules.getOrPut(it.ruleType) { mutableListOf() }.add(it) }
        }
        fun authorizeScope(scope: Calculation.Scope, depth: Int, path: List<Any> = listOf("computation")) {
            authorizePublicScope(scope, null, CalculationBindings.empty(), emptyMap(), depth, path)
        }

        private fun authorizePublicScope(scope: Calculation.Scope, owner: Calculation?, bindings: CalculationBindings,
            arguments: Map<String, Calculation.Value>, depth: Int, path: List<Any>) {
            chargeScope(depth)
            when (val root = scope.root) {
                is Calculation.Measures -> authorizeMeasures(root, bindings, arguments, depth)
                is Calculation.Members -> authorizeMembers(root, owner, bindings, arguments, depth, false)
                is Calculation.Compute -> authorizeCompute(root, scope, owner, bindings, arguments, depth, path)
                is Calculation.Relation -> if (owner == null) fail("public relation roots require a declared calculation formal")
                is Calculation.SetCombination -> count[0] += root.inputs.size
                is Calculation.Input, is Calculation.ObjectMeasure, is Calculation.AlignMeasurements, is Calculation.ObservationStatistics -> kotlin.Unit
            }
            Calculation.rootInputs(scope.root).forEach { requireRootInput(scope, it, path) }
            val stages = if (owner == null) scope.stages else CalculationInstantiation.stages(scope.stages, arguments, owner, bindings, ::dimensionAlias)
            stages.forEach { stage ->
                if (stage.operationType == PostOperationContract.JOIN_INPUT) count[0]++
                if (stage is PostOperationContract.ObserveWindow) stage.calendar?.let { calendar ->
                    if (scope.inputs[calendar.inputId]?.mode != "RELATION") fail("public query calendar stages require an explicitly selected RELATION input")
                }
                authorizeStage(stage)
            }
            if (count[0] > ScopedInputContract.MAX_INPUTS) fail("query exceeds scope/instance/occurrence budget")
            scope.children().forEach { (id, input) -> authorizePublicScope(input.computation, owner, bindings, arguments, depth + 1, path + listOf("inputs", id, "computation")) }
        }

        private fun chargeScope(depth: Int) {
            if (depth > ScopedInputContract.MAX_DEPTH || ++count[0] > ScopedInputContract.MAX_INPUTS)
                fail("query exceeds scope/instance/occurrence budget")
        }

        private fun authorizeCompute(root: Calculation.Compute, callerScope: Calculation.Scope, parent: Calculation?, parentBindings: CalculationBindings,
            parentArguments: Map<String, Calculation.Value>, depth: Int, path: List<Any>) {
            val calculation = root.calculation
            val catalog = compilationCatalog
            (root.bindings as? Calculation.ExplicitBindings)?.let { catalog.requireBindings(calculation, it.bindings, callerScope) }
            val application = CalculationInstantiation.compute(root, parent, parentBindings, parentArguments)
            val bindings = application.bindings
            bindings.measurements.values.forEach { if (metric(it).adoptionStatus != "ACTIVE") fail("metric adoption is not active") }
            bindings.dimensions.values.forEach { authorizeDimensionBinding(it, catalog) }
            bindings.groupingCandidates.values.flatten().forEach { authorizeDimensionBinding(it, catalog) }
            authorizeMemberBindings(bindings.members, calculation.memberSlots.associateBy { it.name }, depth)
            authorizeRelationBindings(bindings.relations, calculation.relationSlots.associateBy { it.name })
            catalog.requireBoundBindings(calculation, bindings)
            authorizePublicScope(calculation.computation, calculation, bindings, application.arguments, depth + 1, path + listOf("root", "calculation", "computation"))
        }

        private fun dimensionAlias(reference: ScopedInputContract.DimensionRef): String =
            requireNotNull(compilationCatalog.analysisDimensions[key(reference.id, reference.version)]) { "grouping dimension is not frozen" }.outputAlias

        private fun dimensionReference(reference: Calculation.DimensionReference, bindings: CalculationBindings): ScopedInputContract.DimensionRef = when (reference) {
            is Calculation.PublishedDimension -> reference.reference
            is Calculation.DimensionParameter -> requireNotNull(bindings.dimensions[reference.slot]) { "public dimension parameters require a calculation binding" }.reference
        }

        /** 冻结依赖只沿真实调用寻找条件消费者，不重新套用公开阶段或指标候选政策。 */
        private fun authorizeFrozenScopeConditions(scope: Calculation.Scope, owner: Calculation, bindings: CalculationBindings,
            arguments: Map<String, Calculation.Value>, catalog: ExecutableCatalog, depth: Int) {
            chargeScope(depth)
            when (val root = scope.root) {
                is Calculation.Measures -> root.calls.forEach { call ->
                    val child = CalculationInstantiation.child(call, owner, bindings, arguments, catalog::requireMetricInstance) { catalog.requireMetric(it).definition }
                    val definition = catalog.requireMetric(child.metric).definition
                    val condition = sourceCondition(call.sourceCondition, bindings, arguments)
                    when (definition) {
                        is MetricDefinition.FactMeasure -> authorizeFactConditions(listOf(sourceCondition(root.sourceCondition, bindings, arguments), condition), key(definition.event.eventId, definition.event.eventVersion))
                        is MetricCalculation -> {
                            requireAlgorithmCondition(condition)
                            authorizeFrozenCalculationConditions(definition.calculation, child.bindings, child.fixedArguments, child.arguments, emptyMap(), emptyMap(), depth + 1)
                        }
                    }
                }
                is Calculation.Members -> {
                    when (val target = root.target) {
                        is Calculation.MemberParameter -> {
                            val call = bindings.members[target.slot] as? CalculationBindings.SourceCall
                            if (call != null) authorizeFrozenMemberConditions(catalog.requireMemberSource(call.source),
                                call.arguments.mapValues { it.value as Calculation.Value }, call.members, call.relations, depth + 1)
                        }
                        is Calculation.MemberSource -> authorizeFrozenMemberConditions(catalog.requireMemberSource(target.source),
                            root.arguments.mapValues { CalculationInstantiation.resolve(it.value, bindings, arguments) },
                            CalculationInstantiation.resolveMembers(root.members, bindings, arguments), root.relations, depth + 1)
                    }
                }
                is Calculation.Compute -> {
                    val application = CalculationInstantiation.compute(root, owner, bindings, arguments)
                    authorizeFrozenScopeConditions(root.calculation.computation, root.calculation, application.bindings, application.arguments, catalog, depth + 1)
                }
                is Calculation.Input, is Calculation.Relation, is Calculation.ObjectMeasure, is Calculation.AlignMeasurements,
                is Calculation.SetCombination, is Calculation.ObservationStatistics -> kotlin.Unit
            }
            scope.children().values.forEach { authorizeFrozenScopeConditions(it.computation, owner, bindings, arguments, catalog, depth + 1) }
        }

        private fun sourceCondition(binding: Calculation.Binding, bindings: CalculationBindings, arguments: Map<String, Calculation.Value>): DimensionCondition =
            (CalculationInstantiation.resolve(binding, bindings, arguments) as? Calculation.Condition)?.condition
                ?: fail("fact source conditions require a declared CONDITION argument")

        private fun requireAlgorithmCondition(condition: DimensionCondition) {
            if (condition !is DimensionCondition.Unrestricted) fail("algorithm source conditions must be supplied through declared CONDITION arguments")
        }

        private fun authorizeFrozenCalculationConditions(calculation: Calculation, initial: CalculationBindings, fixed: Map<String, Calculation.Value>,
            supplied: Map<String, Calculation.Value>, suppliedMembers: Map<String, CalculationBindings.Member>, suppliedRelations: Map<String, CalculationBindings.Relation>, depth: Int) {
            val arguments = CalculationInstantiation.arguments(calculation.parameters, fixed, supplied, initial)
            val resolved = CalculationBindings(initial.dimensions, CalculationInstantiation.resolveMembers(initial.members, initial, arguments), initial.groupingCandidates, initial.measurements, initial.relations)
            val bindings = CalculationInstantiation.relationArguments(calculation,
                CalculationInstantiation.memberArguments(calculation, resolved, suppliedMembers), suppliedRelations)
            authorizeFrozenScopeConditions(calculation.computation, calculation, bindings, arguments, compilationCatalog, depth)
        }

        private fun authorizeFrozenMemberConditions(source: MemberSource, arguments: Map<String, Calculation.Value>, suppliedMembers: Map<String, CalculationBindings.Member>,
            suppliedRelations: Map<String, CalculationBindings.Relation>, depth: Int) {
            when (source) {
                is MemberSource.Computed -> authorizeFrozenCalculationConditions(source.definition.calculation, source.bindings, source.fixedArguments, arguments, suppliedMembers, suppliedRelations, depth)
                is MemberSource.Projection -> if (suppliedMembers.isNotEmpty() || suppliedRelations.isNotEmpty()) fail("projection members do not declare member or relation parameters")
            }
        }

        private fun authorizeDimensionBinding(binding: CalculationBindings.Dimension, catalog: ExecutableCatalog) {
            val reference = binding.reference
            catalog.dimensionMappings(binding).forEach { mapping ->
                val approved = dimensions[reference]?.eventMappings.orEmpty().any { value ->
                    value.mappingId == mapping.mappingId && value.levelCode == reference.levelCode && value.eventId == mapping.eventId && value.eventVersion.toString() == mapping.eventVersion &&
                        value.dimensionId == mapping.dimensionId && value.dimensionVersion.toString() == mapping.dimensionVersion && value.dimensionBindingId == mapping.dimensionBindingId &&
                        value.dimensionBindingVersion.toString() == mapping.dimensionBindingVersion && value.logicUnitId == mapping.logicUnitId && value.logicUnitVersion.toString() == mapping.logicUnitVersion
                } || timeDimensions[key(reference.id, reference.version)]?.takeIf { reference.levelCode in it.availableLevelCodes }?.eventAnchors.orEmpty().any { value ->
                    value.mappingId == mapping.mappingId && value.levelCode == reference.levelCode && value.eventId == mapping.eventId && value.eventVersion.toString() == mapping.eventVersion &&
                        value.effectiveTimeLogicUnitId == mapping.logicUnitId && value.effectiveTimeLogicUnitVersion.toString() == mapping.logicUnitVersion
                }
                if (!approved) fail("inline dimension mapping is outside the published exact mappings")
            }
        }

        private fun authorizeMeasures(root: Calculation.Measures, bindings: CalculationBindings, arguments: Map<String, Calculation.Value>, depth: Int) {
            val events = linkedSetOf<String>()
            val facts = mutableListOf<MetricCandidate>()
            val factConditions = mutableListOf<Pair<MetricCandidate, DimensionCondition>>()
            val grouping = CalculationInstantiation.groupingReferences(root, bindings, arguments, ::dimensionAlias).values.toList()
            val rootCondition = sourceCondition(root.sourceCondition, bindings, arguments)
            val sourceClocks = root.clocks.mapValues { (CalculationInstantiation.resolve(it.value, bindings, arguments) as Calculation.Clock).clock }
            val timeDimension = root.timeRange?.let {
                require(CalculationInstantiation.resolve(it.window, bindings, arguments) is Calculation.Range) { "source time range requires a RANGE binding" }
                dimensionReference(it.dimension, bindings)
            }
            root.calls.forEach { call ->
                count[0]++
                val instanceRef = when (call) {
                    is Calculation.InstanceCall -> call.instance
                    is Calculation.MeasurementCall -> requireNotNull(bindings.measurements[call.slot]) { "public measurement parameters require a calculation binding" }
                    is Calculation.DefinitionCall -> fail("public calculations must select approved measurement instances")
                }
                val candidate = metric(instanceRef)
                if (candidate.adoptionStatus != "ACTIVE") fail("metric adoption is not active")
                val instance = compilationCatalog.requireMetricInstance(instanceRef)
                val definition = compilationCatalog.requireMetric(instance.definition).definition
                val supplied = call.arguments.mapValues { CalculationInstantiation.resolve(it.value, bindings, arguments) }
                val members = CalculationInstantiation.resolveMembers(CalculationInstantiation.memberBindings(call, bindings.members), bindings, arguments)
                val relations = CalculationInstantiation.relationBindings(call, bindings.relations, definition)
                val condition = sourceCondition(call.sourceCondition, bindings, arguments)
                authorizeMemberBindings(members, candidate.memberParameters, depth)
                authorizeRelationBindings(relations, candidate.relationParameters)
                authorizeArguments(supplied, candidate.parameters, candidate.fixedArguments, candidate.groupingCandidates)
                if (candidate.definitionKind == "FACT_MEASURE") {
                    facts.add(candidate)
                    factConditions.add(candidate to condition)
                    candidate.sources.forEach { events.add(key(it.event.eventId, it.event.eventVersion)) }
                } else {
                    requireAlgorithmCondition(condition)
                    authorizeFrozenCalculationConditions((definition as MetricCalculation).calculation, instance.bindings, instance.fixedArguments, supplied, members, relations, depth + 1)
                }
            }
            grouping.forEach { dimension(it) }
            if (facts.isEmpty() && (timeDimension != null || sourceClocks.isNotEmpty() || rootCondition !is DimensionCondition.Unrestricted || root.sourceEventConditions.isNotEmpty()))
                fail("source conditions require direct fact invocations; algorithm inputs use declared metric arguments")
            facts.forEach { fact -> grouping.forEach { reference ->
                val expected = fact.sources.map { key(it.event.eventId, it.event.eventVersion) }.toSet()
                val matches = matrix[fact.instance].orEmpty().filter { it.analysisDimensionId == reference.id && it.analysisDimensionVersion.toString() == reference.version && it.levelCode == reference.levelCode }
                if (matches.any { !it.available } || matches.map { key(it.eventId, it.eventVersion) }.toSet() != expected) fail("fact grouping is not available for its complete exact event input: ${fact.instance}")
            } }
            grouping.forEach { alignment(it, events) }
            dimensionCondition(rootCondition, events)
            val scoped = linkedSetOf<String>()
            root.sourceEventConditions.forEach { condition ->
                val event = key(condition.eventId, condition.eventVersion)
                if (event !in events || !scoped.add(event)) fail("event condition must select one direct fact event once")
                dimensionCondition(condition.condition, setOf(event))
            }
            factConditions.forEach { (fact, condition) -> fact.sources.forEach { source ->
                val event = key(source.event.eventId, source.event.eventVersion)
                authorizeFactConditions(listOf(rootCondition, condition) +
                    root.sourceEventConditions.filter { key(it.eventId, it.eventVersion) == event }.map { it.condition }, event)
            } }
            sourceClocks.forEach { (reference, value) -> requireClock(reference, value) }
            outputCondition(root.outputCondition, grouping)
            timeDimension?.let { ref ->
                val candidate = timeDimensions[key(ref.id, ref.version)] ?: fail("source time dimension is not published")
                if (ref.levelCode !in candidate.availableLevelCodes) fail("source time level is not published")
                val anchors = candidate.eventAnchors.filter { it.levelCode == ref.levelCode }.map { key(it.eventId, it.eventVersion) }.toSet()
                if (!anchors.containsAll(events)) fail("source time dimension does not cover all direct fact events")
                facts.forEach { fact -> if (candidate.metricEventCapabilities.any { it.instance == fact.instance && it.levelCode == ref.levelCode && !it.available }) fail("source time dimension is unavailable for selected fact") }
            }
        }

        private fun authorizeFactConditions(conditions: List<DimensionCondition>, event: String) {
            val instances = hashSetOf<String>()
            val grants = hashSetOf<String>()
            conditions.forEach { condition ->
                dimensionCondition(condition, setOf(event))
                DimensionCondition.existences(condition).forEach { exists ->
                    if (!instances.add(exists.instanceId) || instances.size > 64) fail("relation occurrence ids must be unique and bounded within each source input")
                    grants.add(exists.filterRefId)
                }
            }
            relationFilters.values.forEach { candidate ->
                val grant = candidate.definition
                if (grant.requiredByDefault && key(grant.relation.parent.eventId, grant.relation.parent.eventVersion) == event && grant.relation.id !in grants) fail("required source relation filter is absent")
            }
        }

        private fun dimensionCondition(condition: DimensionCondition, events: Set<String>) {
            when (condition) {
                is DimensionCondition.Unrestricted -> kotlin.Unit
                is DimensionCondition.Exists -> {
                    val candidate = relationFilters[condition.filterRefId] ?: fail("relation filter entry is not published")
                    val definition = candidate.definition
                    if (events != setOf(key(definition.relation.parent.eventId, definition.relation.parent.eventVersion))) fail("relation filter must select its exact parent event; use an event condition for multi-event inputs")
                    condition.range?.let { if (candidate.timeRange?.valueType?.name != it.valueType.name) fail("relation range is not approved with this temporal type") }
                    condition.clocks.forEach { (name, value) ->
                        if (name !in definition.permission.clocks) fail("relation clock is not approved")
                        requireClock(requireNotNull(candidate.clockSlots[name]) { "relation clock reference is missing" }, value)
                    }
                    memberCondition(condition.predicate, definition.permission.filters, candidate.filterTypes)
                    MemberCondition.requireQuantityUnits(condition.predicate, candidate.filterValueContracts)
                }
                is DimensionCondition.Predicate -> {
                    val reference = condition.dimension
                    val candidate = filters[condition.filterRefId] ?: fail("filter entry is not published")
                    if (candidate.filterTargetObjectId != reference.id || candidate.filterTargetObjectVersion.toString() != reference.version || condition.operator.name !in candidate.operators) fail("filter target or operator differs from publication")
                    val type = ScalarValueType.valueOf(candidate.filterValueType(reference.levelCode))
                    if (condition.values.any { it.scalarType != type }) fail("filter values differ from the exact published scalar type")
                    val covered = candidate.eventMappings.filter { it.levelCode == reference.levelCode }.map { key(it.eventId, it.eventVersion) }.toSet()
                    if (!covered.containsAll(events)) fail("source filter lacks an exact mapping for a selected fact event")
                    alignment(reference, events)
                }
                is DimensionCondition.All -> condition.operands.forEach { dimensionCondition(it, events) }
                is DimensionCondition.Any -> condition.operands.forEach { dimensionCondition(it, events) }
                is DimensionCondition.Not -> dimensionCondition(condition.operand, events)
            }
        }
        private fun outputCondition(condition: DimensionCondition, grouping: List<ScopedInputContract.DimensionRef>) {
            dimensionCondition(condition, emptySet())
            when (condition) {
                is DimensionCondition.Unrestricted -> kotlin.Unit
                is DimensionCondition.Exists -> fail("relationship existence belongs to source rows, not completed grouped outputs")
                is DimensionCondition.Predicate -> if (condition.dimension !in grouping) fail("output dimension condition requires a retained exact result dimension")
                is DimensionCondition.All -> condition.operands.forEach { outputCondition(it, grouping) }
                is DimensionCondition.Any -> condition.operands.forEach { outputCondition(it, grouping) }
                is DimensionCondition.Not -> outputCondition(condition.operand, grouping)
            }
        }

        private fun authorizeMembers(root: Calculation.Members, owner: Calculation?, bindings: CalculationBindings,
            arguments: Map<String, Calculation.Value>, depth: Int, candidateValues: Boolean) {
            val clocks = root.clocks.mapValues { (CalculationInstantiation.resolve(it.value, bindings, arguments) as Calculation.Clock).clock }
            when (val target = root.target) {
                is Calculation.MemberSource -> {
                    val candidate = member(target.source.id, target.source.version)
                    val source = compilationCatalog.requireMemberSource(target.source)
                    val bound = MemberInvocationBinding.sourceSelection(root, source, owner, bindings, arguments,
                        { slot -> (candidate.outputs[slot] as? MemberCandidate.Dimension)?.mappings.orEmpty() }, compilationCatalog::memberDimensionMapping)
                    authorizeMemberCall(target.source, root.arguments.mapValues { CalculationInstantiation.resolve(it.value, bindings, arguments) },
                        bound.outputs.map { (alias, slot) -> slot to (bound.meanings[alias] as? QueryStageAnalysis.MappedMemberDimension)?.dimension },
                        bound.range, clocks, bound.condition, CalculationInstantiation.resolveMembers(root.members, bindings, arguments), root.relations, depth, candidateValues)
                }
                is Calculation.MemberParameter -> {
                    val calculation = owner ?: fail("public member parameters require a calculation declaration")
                    when (val call = requireNotNull(bindings.members[target.slot]) { "member parameter is not bound" }) {
                        is CalculationBindings.SourceCall -> {
                            val source = compilationCatalog.requireMemberSource(call.source)
                            val bound = MemberInvocationBinding.bind(calculation, root, bindings, arguments, source, compilationCatalog::memberDimensionMapping)
                            authorizeMemberCall(call.source, call.arguments.mapValues { CalculationInstantiation.resolve(it.value, bindings, arguments) },
                                bound.outputs.map { (alias, slot) -> slot to (bound.meanings[alias] as? QueryStageAnalysis.MappedMemberDimension)?.dimension },
                                bound.range, clocks, bound.condition, call.members, call.relations, depth, candidateValues)
                        }
                        is CalculationBindings.LocalRef -> kotlin.Unit
                        is CalculationBindings.InputParameter -> fail("public member parameters require an exact source call or completed local input")
                    }
                }
            }
        }

        private fun authorizeMemberCall(source: com.olap.prototype.member.MemberRef, arguments: Map<String, Calculation.Value>,
            outputs: List<Pair<String, ScopedInputContract.DimensionRef?>>, range: TemporalWindow?, clocks: Map<String, TemporalClockBindings.Value>, condition: MemberCondition,
            members: Map<String, CalculationBindings.Member>, relations: Map<String, CalculationBindings.Relation>, depth: Int, candidateValues: Boolean) {
            val candidate = member(source.id, source.version)
            authorizeArguments(arguments, candidate.parameters, candidate.fixedArguments, candidate.groupingCandidates)
            authorizeMemberBindings(members, candidate.memberParameters, depth)
            authorizeRelationBindings(relations, candidate.relationParameters)
            outputs.forEach { (slot, dimension) ->
                if (slot !in (if (candidateValues) candidate.permission.values else candidate.permission.outputs)) fail("member output slot is outside this approved operation")
                val output = candidate.outputs[slot] ?: fail("member output slot is not approved")
                if (dimension != null && (output !is MemberCandidate.Dimension || dimension !in output.mappings)) fail("member dimension mapping is not approved for this output slot")
            }
            range?.let { if (candidate.timeRange?.valueType?.name != it.valueType.name) fail("member range is not authorized with this temporal type") }
            clocks.forEach { (name, value) ->
                if (name !in candidate.permission.clocks) fail("member clock is not approved")
                requireClock(requireNotNull(candidate.clockSlots[name]) { "member clock reference is missing" }, value)
            }
            memberCondition(condition, candidate.permission.filters, candidate.filterTypes)
            MemberCondition.requireQuantityUnits(condition, candidate.filterValueContracts)
            authorizeFrozenMemberConditions(compilationCatalog.requireMemberSource(source), arguments, members, relations, depth + 1)
        }
        fun authorizeMemberValues(call: Calculation.Members) {
            requirePublication(FORMAT_VERSION, PublishedQueryEntry.key(capability), capability)
            if (call.target !is Calculation.MemberSource || call.outputs.size != 1 || call.outputs.first().dimension != null) fail("member candidate values require one direct approved source slot")
            authorizeMembers(call, null, CalculationBindings.empty(), emptyMap(), 0, true)
        }
        private fun authorizeArguments(supplied: Map<String, Calculation.Value>, parameters: Map<String, Calculation.Parameter>,
            fixed: Map<String, Calculation.Value>, groupingCandidates: Map<String, List<ScopedInputContract.DimensionRef>>) {
            if (supplied.keys.intersect(fixed.keys).isNotEmpty() || !parameters.keys.containsAll(supplied.keys)) fail("call cannot override fixed arguments or supply undeclared arguments")
            parameters.forEach { (name, declaration) ->
                val value = supplied[name] ?: declaration.defaultValue ?: fail("call argument is missing or differs from its declared type: $name")
                if (!declaration.hasType(value.parameterKind, value.valueType)) fail("call argument is missing or differs from its declared type: $name")
                declaration.valueContract?.requireScalar(value as Calculation.Scalar)
                if (value is Calculation.Grouping && !requireNotNull(groupingCandidates[name]) { "published grouping candidates are missing" }.containsAll(value.dimensions)) fail("call grouping is outside its frozen candidates: $name")
            }
        }
        private fun authorizeMemberBindings(supplied: Map<String, CalculationBindings.Member>, parameters: Map<String, Calculation.MemberSlot>, depth: Int) {
            if (supplied.keys != parameters.keys) fail("member arguments must fill exactly the published member parameters")
            supplied.values.forEach { authorizeMemberBinding(it, depth) }
        }
        private fun authorizeRelationBindings(supplied: Map<String, CalculationBindings.Relation>, parameters: Map<String, Calculation.RelationSlot>) {
            if (supplied.keys != parameters.keys) fail("relation arguments must fill exactly the published relation parameters")
            supplied.forEach { (name, binding) ->
                if (binding !is CalculationBindings.LocalRelation) fail("public relation parameters require a completed caller ROOT input")
                if (binding.outputSlots.keys != parameters.getValue(name).outputs.map { it.name }.toSet()) fail("relation arguments must map all and only declared output slots")
            }
        }
        private fun authorizeMemberBinding(binding: CalculationBindings.Member, depth: Int) {
            if (depth >= ScopedInputContract.MAX_DEPTH || ++count[0] > ScopedInputContract.MAX_INPUTS) fail("member calls exceed the scope/instance budget")
            when (binding) {
                is CalculationBindings.SourceCall -> {
                    val candidate = member(binding.source.id, binding.source.version)
                    val values = binding.arguments.mapValues { it.value as? Calculation.Value ?: fail("public member calls require concrete arguments") }
                    authorizeArguments(values, candidate.parameters, candidate.fixedArguments, candidate.groupingCandidates)
                    if (!candidate.permission.outputs.containsAll(binding.outputSlots.values)) fail("member call maps an output outside its approved result permission")
                    authorizeMemberBindings(binding.members, candidate.memberParameters, depth + 1)
                    authorizeRelationBindings(binding.relations, candidate.relationParameters)
                }
                is CalculationBindings.LocalRef -> kotlin.Unit
                is CalculationBindings.InputParameter -> fail("public member parameters require an exact source call or completed local input")
            }
        }
        private fun memberCondition(condition: MemberCondition, filters: Map<String, List<MemberCondition.Operator>>, types: Map<String, ScalarValueType>) {
            when (condition) {
                is MemberCondition.Unrestricted -> kotlin.Unit
                is MemberCondition.Predicate -> {
                    if (filters[condition.slot]?.contains(condition.operator) != true) fail("member filter slot or operator is not approved")
                    val type = requireNotNull(types[condition.slot]) { "member filter type is missing" }
                    if (condition.values.any { it !is Calculation.Scalar || it.scalarType != type }) fail("member filter requires exact typed scalar values")
                }
                is MemberCondition.All -> condition.operands.forEach { memberCondition(it, filters, types) }
                is MemberCondition.Any -> condition.operands.forEach { memberCondition(it, filters, types) }
                is MemberCondition.Not -> memberCondition(condition.operand, filters, types)
            }
        }
        private fun authorizeStage(stage: PostOperationContract.ValidatedOperation) {
            val rules = stageRules[stage.operationType].orEmpty()
            if (rules.size != 1) fail("query stage requires one exact approved rule: ${stage.operationType}")
            try { PublishedStagePolicy.require(stage, rules.single().authorization, "query.stages") }
            catch (failure: Exception) { throw ProducerContractException("query stage differs from frozen permission: ${failure.message}", failure) }
        }
        private fun metric(instance: MetricInstanceRef): MetricCandidate = metrics[instance] ?: fail("metric instance is not published: $instance")
        private fun member(id: String, version: Long): MemberCandidate = members[key(id, version)] ?: fail("member source is not published: $id@$version")
        private fun dimension(ref: ScopedInputContract.DimensionRef): AnalysisDimensionCandidate = dimensions[ref] ?: fail("dimension level is not published: $ref")
        private fun alignment(ref: ScopedInputContract.DimensionRef, events: Set<String>) {
            if (events.size < 2) return
            val mappings = dimensions[ref]?.eventMappings.orEmpty().filter { it.levelCode == ref.levelCode && key(it.eventId, it.eventVersion) in events }
                .map { ValueAlignmentContract.AlignmentRef(key(it.eventId, it.eventVersion), it.valueAlignmentKey, it.valueAlignmentStatus) }
            if (mappings.map { it.eventRef }.distinct().size != events.size) fail("dimension lacks a complete exact event alignment")
            ValueAlignmentContract.requireCrossEventAlignment(mappings, "query dimension alignment")
        }
        private fun requireClock(ref: String, value: TemporalClockBindings.Value) {
            if (value.valueType !in clocks[ref].orEmpty()) fail("clock is not published with this type")
        }
    }
}
