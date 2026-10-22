package com.olap.prototype.engine.executable

import com.olap.prototype.calculation.*
import com.olap.prototype.member.*
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.*
import com.olap.prototype.query.QueryStageAnalysis.Analysis
import com.olap.prototype.query.QueryStageAnalysis.ScopeId
import com.olap.prototype.query.QueryStageAnalysis.Child
import com.olap.prototype.query.QueryStageAnalysis.SourceColumn
import com.olap.prototype.query.QueryStageAnalysis.SourceKey
import com.olap.prototype.query.QueryStageAnalysis.Population
import com.olap.prototype.query.QueryStageAnalysis.SelectedObservation
import com.olap.prototype.query.QueryStageAnalysis.UniqueSourceKey
import com.olap.prototype.query.QueryStageAnalysis.UnknownSourceKey
import com.olap.prototype.query.QueryStageAnalysis.MetricPopulation
import com.olap.prototype.query.QueryStageAnalysis.IntrinsicPopulation
import com.olap.prototype.query.QueryStageAnalysis.MetricOrigin
import com.olap.prototype.query.QueryStageAnalysis.DimensionOrigin
import com.olap.prototype.query.QueryStageAnalysis.GroupingOrigin
import com.olap.prototype.query.QueryStageAnalysis.MemberOrigin
import com.olap.prototype.query.QueryStageAnalysis.MemberMeaning
import com.olap.prototype.query.QueryStageAnalysis.MemberContainer
import com.olap.prototype.query.QueryStageAnalysis.MemberProperty
import com.olap.prototype.query.QueryStageAnalysis.MappedMemberDimension
import com.olap.prototype.query.QueryStageAnalysis.SetOrigin
import com.olap.prototype.query.QueryStageAnalysis.UniqueKey
import com.olap.prototype.query.QueryStageAnalysis.Symbol
import com.olap.prototype.query.QueryStageAnalysis.SymbolId
import java.util.Collections
import java.util.IdentityHashMap

/** 一个绑定根的来源符号、键、数量和人口；不调度或保存作用域完成状态。 */
internal class ExecutableInputSymbols(private val graph: ExecutableInputGraph,
    private val result: Map<ScopeId, Analysis>, private val input: ExecutableInputScope,
    private val scope: ScopeId, private val children: Map<String, Child>) {
    data class Facts(val columns: List<SourceColumn>, val key: SourceKey, val population: Population,
        val operations: List<PostOperationContract.ValidatedOperation>, val observations: List<SelectedObservation>)

    fun facts(): Facts = when (val root = input.root) {
        is ExecutableInputRoot.Measures -> measures(root)
        is ExecutableInputRoot.Members -> members(root)
        is ExecutableInputRoot.ObjectMeasure -> qualified(root)
        is ExecutableInputRoot.AlignMeasurements -> aligned(root)
        is ExecutableInputRoot.ObservationStatistics -> statistics(root)
        is ExecutableInputRoot.Input -> reuse(root)
        is ExecutableInputRoot.Relation -> relation(root.input)
        is ExecutableInputRoot.SetInputs -> set(root)
    }
    private fun measures(root: ExecutableInputRoot.Measures): Facts {
        val columns = linkedMapOf<String, SourceColumn>()
        val dimensions = linkedSetOf<String>()
        root.grouping.forEach { (alias, ref) -> columns[alias] = groupingColumn(alias, ref, scope, input, result); dimensions += alias }
        root.groupingLevels?.let { levels ->
            columns[levels.outputAlias] = SourceColumn.categorical(levels.outputAlias, GroupingOrigin(scope, -1, levels)); dimensions += levels.outputAlias
        }
        input.metricCalls.forEach { call ->
            val column = measureColumn(call, root, dimensions, columns.values)
            require(columns.putIfAbsent(column.name, column) == null) { "selected output alias conflicts with another output" }
        }
        val key = UniqueSourceKey(dimensions.toList())
        val population = MetricPopulation(root.population, consumedRootInputs(input, scope))
        val observations = input.metricCalls.filter { it.invocation.callId in root.population.callIds }.flatMap { call ->
            call.calculationScopes(scope).keys.flatMap { result.getValue(it).selectedObservations }
        }
        return Facts(columns.values.toList(), key, population, input.postOps.toList(), observations)
    }
    private fun measureColumn(call: ExecutableMetricCall, root: ExecutableInputRoot.Measures, dimensions: Set<String>, columns: Collection<SourceColumn>): SourceColumn {
        val invocation = call.invocation
        val alias = invocation.outputAlias
        val dependencies = call.calculationScopes(scope).flatMap { (position, calculation) ->
            result.getValue(position).outputs.filter { it.name == calculation.measureOutput }.map { it.id }
        }
        val column = SourceColumn.quantity(alias, MetricOrigin(scope, invocation.callId, invocation.owner, invocation.metric.reference),
            dependencies, quantity(call, root, result, scope))
        if (call is ExecutableMetricCall.Fact) invocation.measurementSlot?.let { requirement ->
            val branchColumns = columns.filter { it.origin is DimensionOrigin || it.origin is GroupingOrigin } + column
            val branch = QueryStageAnalysis.analyze(scope, branchColumns, UniqueSourceKey(dimensions.toList()), emptyMap(), emptyList(),
                MetricPopulation(RowPopulationContract.calls(listOf(invocation.callId)), emptySet()), "measurement[${invocation.callId}]")
            requireMeasurement(requirement, branch, branch.outputs.first { it.name == alias }.quantity)
        }
        return column
    }
    private fun members(root: ExecutableInputRoot.Members): Facts = when (val member = root.input) {
        is ExecutableMemberInput.Projection -> projection(member)
        else -> completedMember(member)
    }
    private fun projection(member: ExecutableMemberInput.Projection): Facts {
        val columns = linkedMapOf<String, SourceColumn>()
        val definition = member.memberSource.definition.result
        val identities = definition.identitySlots.flatMap { identity -> member.outputs.filterValues { it == identity }.keys }
        member.outputs.forEach { (alias, slot) -> columns[alias] = projectionColumn(member, alias, slot, identities) }
        val keys = definition.identitySlots.mapNotNull { slot -> member.outputs.entries.firstOrNull { it.value == slot }?.key }
        val key = if (definition.shape != MemberShape.RAW_BAG && keys.size == definition.identitySlots.size) UniqueSourceKey(keys) else UnknownSourceKey.INSTANCE
        val population = IntrinsicPopulation(consumedRootInputs(input, scope))
        return Facts(columns.values.toList(), key, population, input.postOps.toList(), emptyList())
    }
    private fun projectionColumn(member: ExecutableMemberInput.Projection, alias: String, slot: String, identities: List<String>): SourceColumn {
        val source = member.memberSource
        val origin = MemberOrigin(scope, source.id, source.version.toString(), slot, member.meanings.getValue(alias))
        val meaning = origin.meaning
        if (meaning is MemberContainer) return SourceColumn(alias, origin, emptyList(), QueryValue.Structured(meaning.shape, emptyList()))
        val declaration = member.valueContracts[alias] ?: return SourceColumn.categorical(alias, origin)
        return SourceColumn.quantity(alias, origin, emptyList(), QueryQuantity.memberSource(declaration,
            requireNotNull(MemberSourceDefinition.rootValueContract(source.definition.result.outputs.getValue(slot))),
            MemberRef(source.id, source.version), member.selection, QueryQuantity.RawSource(identities)))
    }
    private fun completedMember(member: ExecutableMemberInput): Facts {
        val columns = linkedMapOf<String, SourceColumn>()
        val symbols = linkedMapOf<String, String>()
        val actual = when (member) {
            is ExecutableMemberInput.Computed -> result.getValue(member.computationScope).also { analysis ->
                val source = member.source
                source.definition.result.outputs.forEach { (slot, output) -> symbols[slot] = MemberSourceDefinition.completedSymbol(output) }
                requireComputed(member, analysis, symbols, result, graph)
                member.outputs.forEach { (alias, slot) ->
                    val symbol = requireSymbol(analysis, symbols.getValue(slot))
                    columns[alias] = SourceColumn(alias, MemberOrigin(scope, source.id, source.version.toString(), slot, member.meanings.getValue(alias)),
                        listOf(symbol.id), QueryValue.Imported(symbol.id, symbol.value))
                }
            }
            is ExecutableMemberInput.Local -> requireNotNull(result[member.inputScope]) { "local member input must already be completed" }.also { analysis ->
                requireLocal(member, analysis, result, graph)
                member.outputs.forEach { (alias, name) -> columns[alias] = SourceColumn.imported(alias, requireSymbol(analysis, name)) }
            }
            is ExecutableMemberInput.Projection -> error("projection is not a completed input")
        }
        val unique = actual.key as? UniqueKey
        val outputKey = unique?.columns?.mapNotNull { id ->
            val original = actual.outputs.first { it.id == id }
            member.outputs.entries.firstOrNull { (if (member is ExecutableMemberInput.Computed) symbols[it.value] else it.value) == original.name }?.key
        } ?: emptyList()
        val raw = when (member) {
            is ExecutableMemberInput.Computed -> member.source.definition.result.shape == MemberShape.RAW_BAG
            is ExecutableMemberInput.Local -> member.formal.shape == MemberShape.RAW_BAG
            is ExecutableMemberInput.Projection -> false
        }
        val key = if (!raw && unique != null && outputKey.size == unique.columns.size) UniqueSourceKey(outputKey) else UnknownSourceKey.INSTANCE
        val observations = actual.selectedObservations.toList()
        val population = IntrinsicPopulation(consumedRootInputs(input, scope))
        return Facts(columns.values.toList(), key, population, input.postOps.toList(), observations)
    }
    private fun qualified(root: ExecutableInputRoot.ObjectMeasure): Facts {
        val measured = requireRootChild(children, "measurement")
        val objects = requireRootChild(children, "objects")
        root.roles.forEach { role -> role.objectKeys.forEach { name -> require(requireSymbol(objects, name).value is QueryValue.Plain) { "object qualification keys must retain actual identity meaning" } } }
        requireRecordObjectMeanings(input.additionalInputs.getValue("measurement"), scope.child("objects"), objects, graph, identitySet())
        val columns = importColumns(measured)
        val key = sourceKey(measured)
        val observations = measured.selectedObservations.toList()
        val population = IntrinsicPopulation(consumedRootInputs(input, scope) + setOf("measurement", "objects"))
        return Facts(columns, key, population, input.postOps.toList(), observations)
    }
    private fun aligned(root: ExecutableInputRoot.AlignMeasurements): Facts {
        val columns = linkedMapOf<String, SourceColumn>()
        val first = root.population.inputIds.first()
        val base = requireRootChild(children, first)
        root.keys.forEach { (id, mapping) ->
            val child = requireRootChild(children, id)
            requireCompleteKey(child, mapping.values)
            root.observationKeys.forEach { name ->
                require(keyMeaning(requireSymbol(child, mapping.getValue(name)), graph) == keyMeaning(requireSymbol(base, root.keys.getValue(first).getValue(name)), graph)) {
                    "aligned observation identity differs in actual meaning, reference or role: $name"
                }
            }
        }
        val observations = root.population.inputIds.flatMap { requireRootChild(children, it).selectedObservations }
        root.observationKeys.forEach { name -> columns[name] = SourceColumn.imported(name, requireSymbol(base, root.keys.getValue(first).getValue(name))) }
        root.values.forEach { (id, values) ->
            val child = requireRootChild(children, id)
            values.forEach { value ->
                val actual = requireSymbol(child, value.symbol)
                require(value.noMatch != Calculation.NoMatch.ZERO_WHEN_ABSENT || actual.value is QueryValue.Measure) { "known zero for an absent measurement requires an actual scalar quantity" }
                columns[value.outputAlias] = SourceColumn.imported(value.outputAlias, actual)
            }
        }
        val key = UniqueSourceKey(root.observationKeys)
        val population = IntrinsicPopulation(consumedRootInputs(input, scope) + root.keys.keys)
        return Facts(columns.values.toList(), key, population, input.postOps.toList(), observations)
    }
    private fun statistics(root: ExecutableInputRoot.ObservationStatistics): Facts {
        val child = requireRootChild(children, "observations")
        (root.unit as? Calculation.ObjectsBy)?.let { requireCompleteKey(child, it.keys) }
        val columns = importColumns(child)
        val key = sourceKey(child)
        val observations = child.selectedObservations.toList()
        val population = IntrinsicPopulation(consumedRootInputs(input, scope) + "observations")
        val operations = ObservationStatisticsLowering.lower(root, child.outputNames())
        return Facts(columns, key, population, operations + input.postOps, observations)
    }
    private fun reuse(root: ExecutableInputRoot.Input): Facts {
        val child = requireRootChild(children, root.inputId)
        val columns = importColumns(child)
        val key = sourceKey(child)
        val observations = child.selectedObservations.toList()
        val population = IntrinsicPopulation(consumedRootInputs(input, scope) + root.inputId)
        return Facts(columns, key, population, input.postOps.toList(), observations)
    }
    private fun relation(binding: ExecutableRelationInput): Facts {
        val actual = result.getValue(binding.inputScope)
        val symbols = QueryStageAnalysis.symbols(result.values)
        val unique = actual.key as? UniqueKey
        try {
            binding.requirements.forEach { requirement ->
                requirement.outputs.forEach { output ->
                    SetOutputContract.requireCompletedMeaning(output.shape, requireSymbol(actual, requirement.outputSlots.getValue(output.name)), symbols)
                }
                requirement.keys?.let { names ->
                    require(unique != null && names.map { requireSymbol(actual, requirement.outputSlots.getValue(it)).id }.toSet() == unique.columns) {
                        "relation input must retain its declared complete actual unique key"
                    }
                }
            }
        } catch (failure: IllegalArgumentException) {
            throw com.olap.prototype.engine.EngineExecutionException("ANALYZE_STAGES", "RELATION_INPUT_CONTRACT_MISMATCH",
                "completed relation does not satisfy its formal input: ${failure.message}", failure,
                mapOf("scope" to scope, "inputScope" to binding.inputScope, "requirements" to binding.requirements,
                    "actualKey" to actual.key, "actualOutputs" to actual.outputs))
        }
        val projection = binding.projection
        val columns = projection.outputs.map { output -> SourceColumn.imported(output.name, requireSymbol(actual, projection.outputSlots.getValue(output.name))) }
        val retained = unique?.columns?.mapNotNull { id ->
            val original = actual.outputs.first { it.id == id }.name
            projection.outputSlots.entries.firstOrNull { it.value == original }?.key
        }
        val key = if (unique != null && retained?.size == unique.columns.size) UniqueSourceKey(projection.keys ?: requireNotNull(retained)) else UnknownSourceKey.INSTANCE
        return Facts(columns, key, IntrinsicPopulation(consumedRootInputs(input, scope)), input.postOps.toList(), actual.selectedObservations.toList())
    }
    private fun importColumns(child: Analysis): List<SourceColumn> {
        val columns = linkedMapOf<String, SourceColumn>()
        child.outputs.forEach { columns[it.name] = SourceColumn.imported(it.name, it) }
        return columns.values.toList()
    }
    private fun set(root: ExecutableInputRoot.SetInputs): Facts {
        val columns = linkedMapOf<String, SourceColumn>()
        val observations = mutableListOf<SelectedObservation>()
        val used = linkedSetOf<String>()
        root.inputs.forEach { projection ->
            val child = requireRootChild(children, projection.inputId)
            observations += child.selectedObservations; used += projection.inputId
            require(child.outputNames().containsAll(projection.symbols) && projection.symbols.size == root.outputSlots.size) { "set input projection references unavailable output symbols or differs in width" }
        }
        SetOutputContract.columns(scope, root.operation, root.all, root.inputs, root.outputSlots, children, QueryStageAnalysis.symbols(result.values)).forEach { columns[it.name] = it }
        val key = if (root.all) UnknownSourceKey.INSTANCE else UniqueSourceKey(root.outputSlots.map { it.name })
        val population = IntrinsicPopulation(used)
        return Facts(columns.values.toList(), key, population, input.postOps.toList(), observations.toList())
    }

    companion object {
        private fun <T> identitySet(): MutableSet<T> = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
        private fun requireSymbol(analysis: Analysis, name: String): Symbol = analysis.outputs.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("member output references an unavailable completed symbol: $name")
        private fun requireRecordObjectMeanings(measured: ExecutableInputScope, objects: ScopeId, actual: Analysis, graph: ExecutableInputGraph, visited: MutableSet<ExecutableInputScope>) {
            if (!visited.add(measured)) return
            measured.objectRestrictions.forEach { restriction ->
                val records = restriction.target as? Calculation.RecordOutputs ?: return@forEach
                if (restriction.objectsScope != objects) return@forEach
                records.keys.forEachIndexed { index, key ->
                    val expected = requireNotNull(restriction.recordMeanings[key]) { "qualified record object key has no exact bound member meaning" }
                    val symbol = requireSymbol(actual, restriction.objectKeys[index])
                    val meaning = (symbol.origin as? MemberOrigin)?.meaning ?: run {
                        val dimension = keyMeaning(symbol, graph) as? DimensionKey ?: throw IllegalArgumentException("object qualification requires exact identity meaning")
                        MappedMemberDimension(dimension.reference, dimension.role)
                    }
                    require(expected == meaning) { "object qualification differs in actual member identity, semantic dimension or role" }
                }
            }
            measured.metricCalls.flatMap { it.calculations() }.forEach { requireRecordObjectMeanings(it.input, objects, actual, graph, visited) }
            measured.additionalInputs.values.forEach { requireRecordObjectMeanings(it, objects, actual, graph, visited) }
        }
        private fun consumedRootInputs(owner: ExecutableInputScope, position: ScopeId): Set<String> {
            val consumed = linkedSetOf<String>()
            collectRootInputs(owner, owner.additionalInputs.keys.associateBy(position::child), consumed, identitySet())
            return consumed.toSet()
        }
        private fun collectRootInputs(input: ExecutableInputScope, declared: Map<ScopeId, String>, consumed: MutableSet<String>, visited: MutableSet<ExecutableInputScope>) {
            if (!visited.add(input)) return
            val local = (input.root as? ExecutableInputRoot.Members)?.input as? ExecutableMemberInput.Local
            local?.let { declared[it.inputScope]?.let(consumed::add) }
            (input.root as? ExecutableInputRoot.Relation)?.let { declared[it.input.inputScope]?.let(consumed::add) }
            (input.objectRestrictions + input.metrics.flatMap { it.objectRestrictions }).forEach { declared[it.objectsScope]?.let(consumed::add) }
            input.memberComputations().values.forEach { collectRootInputs(it, declared, consumed, visited) }
            input.metricCalls.flatMap { it.calculations() }.forEach { collectRootInputs(it.input, declared, consumed, visited) }
            input.additionalInputs.values.forEach { collectRootInputs(it, declared, consumed, visited) }
        }
        private fun requireCompleteKey(actual: Analysis, names: Collection<String>) {
            val unique = actual.key as? UniqueKey ?: throw IllegalArgumentException("observation objects require an actual unique identity")
            require(names.map { requireSymbol(actual, it).id }.toSet() == unique.columns) { "observation objects must retain the complete actual identity" }
            names.forEach { require(requireSymbol(actual, it).value is QueryValue.Plain) { "observation identity cannot be a completed quantity or collection" } }
        }
        private sealed interface AlignmentKey
        private data class DimensionKey(val reference: ScopedInputContract.DimensionRef, val role: String) : AlignmentKey
        private data class MemberKey(val source: MemberRef, val slot: String, val meaning: MemberMeaning) : AlignmentKey
        private fun keyMeaning(symbol: Symbol, graph: ExecutableInputGraph): AlignmentKey = when (val origin = symbol.origin) {
            is MemberOrigin -> when (val meaning = origin.meaning) {
                is MappedMemberDimension -> DimensionKey(meaning.dimension, meaning.role)
                else -> MemberKey(MemberRef(origin.sourceId, origin.sourceVersion.toLong()), origin.slot, meaning)
            }
            is DimensionOrigin -> {
                val root = graph.scope(origin.scope)?.root as? ExecutableInputRoot.Measures ?: throw IllegalArgumentException("aligned key has no actual bound semantic dimension owner")
                val roles = root.groupingBindings.values.filter { it.reference == origin.dimension }.map { it.role }.distinct()
                require(roles.size == 1) { "aligned keys require one actual semantic role" }
                DimensionKey(origin.dimension, roles.single())
            }
            else -> throw IllegalArgumentException("aligned identity must retain exact semantic or member identity meaning")
        }
        private fun requireComputed(input: ExecutableMemberInput.Computed, actual: Analysis, symbols: Map<String, String>, analyses: Map<ScopeId, Analysis>, graph: ExecutableInputGraph) {
            val definition = input.source.definition.result
            definition.outputs.forEach { (slot, output) ->
                val symbol = requireSymbol(actual, symbols.getValue(slot))
                if (output is MemberSourceDefinition.ComputedDimensionOutput) {
                    val binding = input.source.bindings.dimensions.getValue(output.dimensionSlot)
                    require(QueryStageAnalysis.dimension(symbol.origin) == binding.reference && binding.role == actualDimensionRole(symbol, graph, analyses, hashSetOf())) {
                        "computed member dimension differs from its actual completed semantic dimension"
                    }
                }
                if (output is MemberSourceDefinition.ComputedContainerPropertyOutput) SetOutputContract.requireValueMeaning(output.shape, symbol)
                val declaration = MemberSourceDefinition.rootValueContract(output)
                val quantity = symbol.quantity
                require((declaration == null) == (quantity == null)) { "computed member output differs from the completed quantity/categorical meaning" }
                if (declaration != null) {
                    QueryValueSemantics.requireDeclaration(declaration, requireNotNull(quantity))
                    require(MetricValueContract.resultFits(quantity.declaration.result, declaration.result)) { "computed member quantity output cannot narrow its actual value capacity" }
                }
            }
            if (definition.shape != MemberShape.RAW_BAG) {
                val declared = definition.identitySlots.map { requireSymbol(actual, symbols.getValue(it)).id }.toSet()
                require((actual.key as? UniqueKey)?.let { declared.containsAll(it.columns) } == true) { "computed member identity is not proved by its completed relation" }
            }
        }
        private fun actualDimensionRole(symbol: Symbol, graph: ExecutableInputGraph, analyses: Map<ScopeId, Analysis>, active: MutableSet<SymbolId>): String? {
            require(active.add(symbol.id)) { "dimension provenance contains a cycle" }
            try {
                (symbol.origin as? MemberOrigin)?.let { return it.meaning.role }
                (symbol.origin as? DimensionOrigin)?.let { dimension ->
                    val root = graph.scope(dimension.scope)?.root as? ExecutableInputRoot.Measures
                    if (root != null) analyses.getValue(dimension.scope).inputs.forEach { original ->
                        val originalValue = original.value
                        val selectedValue = symbol.value
                        if (original.origin == symbol.origin && originalValue is QueryValue.Plain && selectedValue is QueryValue.Plain && originalValue.source == selectedValue.source)
                            root.groupingBindings[original.name]?.let { return it.role }
                    }
                }
                val all = QueryStageAnalysis.symbols(analyses.values)
                val roles = linkedSetOf<String>()
                symbol.dependencies.forEach { id ->
                    val dependency = requireNotNull(all[id]) { "dimension dependency is missing" }
                    if (QueryStageAnalysis.dimension(dependency.origin) == QueryStageAnalysis.dimension(symbol.origin))
                        roles += actualDimensionRole(dependency, graph, analyses, active) ?: return null
                }
                return roles.singleOrNull()
            } finally { active.remove(symbol.id) }
        }
        private fun requireLocal(local: ExecutableMemberInput.Local, actual: Analysis, analyses: Map<ScopeId, Analysis>, graph: ExecutableInputGraph) {
            if (local.formal.shape != MemberShape.RAW_BAG) {
                val retained = local.outputs.values.map { requireSymbol(actual, it).id }.toSet()
                require((actual.key as? UniqueKey)?.let { retained.containsAll(it.columns) } == true) { "local identified member projection must retain the actual complete unique key" }
            }
            local.outputs.forEach { (alias, name) ->
                val symbol = requireSymbol(actual, name)
                when (val meaning = local.meanings[alias]) {
                    is MappedMemberDimension -> require(QueryStageAnalysis.dimension(symbol.origin) == meaning.dimension && meaning.role == actualDimensionRole(symbol, graph, analyses, hashSetOf())) { "local member dimension differs from its actual completed semantic identity" }
                    is MemberProperty -> {
                        val origin = symbol.origin
                        val matches = origin is MemberOrigin && origin.meaning == meaning || origin is SetOrigin && (origin.shape as? SetOutputContract.Property)?.let { it.role == meaning.role && it.valueType == meaning.valueType } == true
                        require(matches) { "local member property requires its actual completed source role and type" }
                    }
                    is MemberContainer -> SetOutputContract.requireValueMeaning(meaning.shape, symbol)
                    else -> Unit
                }
                val declaration = local.valueContracts[alias]
                val quantity = symbol.quantity
                require((declaration == null) == (quantity == null)) { "local member quantity contract differs from its actual completed value" }
                if (declaration != null) QueryValueSemantics.requireDeclaration(declaration, requireNotNull(quantity))
            }
        }
        private fun groupingColumn(alias: String, reference: ScopedInputContract.DimensionRef, scope: ScopeId, input: ExecutableInputScope, analyses: Map<ScopeId, Analysis>): SourceColumn {
            val origin = DimensionOrigin(scope, reference)
            val dependencies = linkedSetOf<SymbolId>()
            val coordinates = linkedSetOf<ValuePosition>()
            input.metricCalls.forEach { call -> call.calculationScopes(scope).keys.forEach { position ->
                val completed = analyses.getValue(position)
                val unique = completed.key as? UniqueKey ?: throw IllegalArgumentException("completed measurement must retain its actual unique identity")
                completed.outputs.filter { it.id in unique.columns && QueryStageAnalysis.dimension(it.origin) == reference }.forEach { symbol ->
                    val plain = symbol.value as? QueryValue.Plain ?: throw IllegalArgumentException("completed observation key must be categorical")
                    dependencies += symbol.id; coordinates += ValuePosition.column(symbol.id); coordinates += plain.positions
                }
            } }
            if (dependencies.isEmpty()) return SourceColumn.categorical(alias, origin)
            val source = dependencies.first()
            return SourceColumn(alias, origin, dependencies.toList(), QueryValue.Imported(source, QueryValue.Plain(ValuePosition.column(source), coordinates.toList())))
        }
        fun requireMeasurement(requirement: ExecutableMetricCall.MeasurementRequirement, analysis: Analysis, quantity: QueryQuantity?) {
            val grain = analysis.alignmentGrain().values
            val expected = requirement.observations.dimensions()
            require(grain.size == expected.size && grain.toSet() == expected.keys) { "measurement output differs from its declared exact observation grain" }
            QueryValueSemantics.requireDeclaration(requirement.valueContract, requireNotNull(quantity) { "measurement output must be a scalar quantity" })
        }
        private fun quantity(call: ExecutableMetricCall, root: ExecutableInputRoot.Measures, analyses: Map<ScopeId, Analysis>, scope: ScopeId): QueryQuantity.Source {
            val declaration = call.invocation.metric.definition.valueContract.adopted(call.invocation.bindings)
            val grouping = root.grouping.entries.associate { it.value to it.key }
            if (call is ExecutableMetricCall.Fact) {
                val unit = declaration.unit as? MetricValueContract.EventDimension
                val candidates = if (unit == null) emptyList() else call.input.analysisDimensions.filter { it.binding.bindingId == unit.bindingId && it.binding.bindingVersion == unit.bindingVersion.toString() }
                    .map { ScopedInputContract.DimensionRef(it.analysisDimension.analysisDimensionId, it.analysisDimension.analysisDimensionVersion, it.levelCode) }
                return QueryQuantity.source(declaration, candidates, grouping, call.input.filterExpression, factProvenance(call, root))
            }
            val quantities = call.calculationScopes(scope).map { (position, calculation) ->
                requireNotNull(analyses.getValue(position).outputs.first { it.name == calculation.measureOutput }.quantity) { "calculation measure must expose scalar quantity semantics" }
                    .also { QueryValueSemantics.requireDeclaration(declaration, it) }
            }
            return QueryQuantity.calculationSource(declaration, quantities, grouping)
        }
        private fun factProvenance(fact: ExecutableMetricCall.Fact, root: ExecutableInputRoot.Measures): QueryQuantity.FactSource {
            val definition = fact.input.metric.factMeasure()
            val identities = root.grouping.keys.toMutableList().apply { root.groupingLevels?.let { add(it.outputAlias) } }
            val snapshot = definition.snapshotRule?.let { rule ->
                val time = rule.timeKey.expression()
                val timeColumns = fact.input.analysisDimensions.filter { !it.filterOnly && time == it.binding.expression }.map { it.outputAlias }.filter { it in identities }.distinct()
                QueryQuantity.SnapshotSource(rule.reduction, timeColumns, emptyMap())
            }
            return QueryQuantity.FactSource(fact.input.metric.reference, definition.aggregation, definition.distinct, identities, snapshot)
        }
        private fun requireRootChild(children: Map<String, Child>, id: String): Analysis {
            val child = requireNotNull(children[id]) { "root input is not declared" }
            require(child.mode == "ROOT") { "completed root must explicitly reference a ROOT input" }
            return child.analysis
        }
        private fun sourceKey(analysis: Analysis): SourceKey = (analysis.key as? UniqueKey)?.let { unique ->
            UniqueSourceKey(analysis.outputs.filter { it.id in unique.columns }.map { it.name })
        } ?: UnknownSourceKey.INSTANCE
    }
}
