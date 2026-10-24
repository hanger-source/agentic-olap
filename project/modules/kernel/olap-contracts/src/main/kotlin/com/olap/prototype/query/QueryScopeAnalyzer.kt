package com.olap.prototype.query

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.expression.NativeScalarFunction
import com.olap.prototype.query.QueryStageAnalysis.Analysis
import com.olap.prototype.query.QueryStageAnalysis.Child
import com.olap.prototype.query.QueryStageAnalysis.GroupingOrigin
import com.olap.prototype.query.QueryStageAnalysis.InputPresenceOrigin
import com.olap.prototype.query.QueryStageAnalysis.IntrinsicPopulation
import com.olap.prototype.query.QueryStageAnalysis.Key
import com.olap.prototype.query.QueryStageAnalysis.MetricPopulation
import com.olap.prototype.query.QueryStageAnalysis.OmittedDimensionOrigin
import com.olap.prototype.query.QueryStageAnalysis.OperationOrigin
import com.olap.prototype.query.QueryStageAnalysis.Origin
import com.olap.prototype.query.QueryStageAnalysis.Population
import com.olap.prototype.query.QueryStageAnalysis.ScopeId
import com.olap.prototype.query.QueryStageAnalysis.SelectedObservation
import com.olap.prototype.query.QueryStageAnalysis.SourceColumn
import com.olap.prototype.query.QueryStageAnalysis.SourceKey
import com.olap.prototype.query.QueryStageAnalysis.Stage
import com.olap.prototype.query.QueryStageAnalysis.Symbol
import com.olap.prototype.query.QueryStageAnalysis.SymbolId
import com.olap.prototype.query.QueryStageAnalysis.UniqueKey
import com.olap.prototype.query.QueryStageAnalysis.UniqueSourceKey
import com.olap.prototype.query.QueryStageAnalysis.UnknownKey
import com.olap.prototype.query.QueryStageAnalysis.dimension
import java.math.BigDecimal

/** 一次词法作用域的符号、人口和阶段转换，不保存跨请求状态或SQL。 */
internal class QueryScopeAnalyzer(
    private val scope: ScopeId,
    private val children: Map<String, Child>,
    private val population: Population,
    private val field: String,
    private val nativeFunctions: Map<ResultExpression.ResultNativeCall, NativeScalarFunction>,
    private val observationReduction: QueryStageAnalysis.ObservationReduction?,
) {
    private var symbols = linkedMapOf<String, Symbol>()
    private var nextPosition = 0
    private var key: Key = UnknownKey.INSTANCE
    private val selectedObservations = mutableListOf<SelectedObservation>()
    private val relations = linkedSetOf<String>()
    private val alignedIds = linkedSetOf<String>()
    private val rootIds = linkedSetOf<String>()
    private val consumedRelations = linkedSetOf<String>()
    private val occurrences = linkedSetOf<String>()

    fun analyze(sourceColumns: List<SourceColumn>, sourceKey: SourceKey,
        operations: List<PostOperationContract.ValidatedOperation>, sourceObservations: List<SelectedObservation>): Analysis {
        observationReduction?.let { check(operations.getOrNull(it.stageIndex) is PostOperationContract.Regroup) {
            "bound object observation reduction must identify its actual statistical REGROUP"
        } }
        initializeSources(sourceColumns, sourceKey, sourceObservations)
        requireInitialGroupingLevels()
        bindChildren()
        requireRootConsumption(operations)
        QueryValue.requireUnitsRetained(symbols.values)
        val inputs = symbols.values.toList()
        val stages = operations.mapIndexed { index, operation -> Transition(index, operation).apply() }
        require(consumedRelations == relations) { "$field must consume all and only declared RELATION inputs" }
        return Analysis(scope, inputs, stages, symbols.values.toList(), key, selectedObservations)
    }

    private fun initializeSources(columns: List<SourceColumn>, sourceKey: SourceKey, observations: List<SelectedObservation>) {
        val ids = linkedMapOf<String, SymbolId>()
        columns.forEach { require(ids.putIfAbsent(it.name, SymbolId(scope, nextPosition++)) == null) { "source columns have duplicate names" } }
        val coordinates = linkedMapOf<ValuePosition, ValuePosition>()
        columns.forEach { column ->
            val imported = column.value as? QueryValue.Imported ?: return@forEach
            val target = ValuePosition.column(ids.getValue(column.name))
            coordinates[ValuePosition.column(imported.owner)] = target
            QueryValue.coordinates(imported.value, target, coordinates)
        }
        columns.forEach { column ->
            val id = ids.getValue(column.name)
            add(symbols, Symbol(id, column.name, column.origin, column.dependencies, column.value.bind(id, ids, coordinates)))
        }
        observations.forEach { selectedObservations.add(SelectedObservation(it.timeAxes.map { axis -> coordinates[axis] ?: axis }, it.asOf)) }
        key = if (sourceKey is UniqueSourceKey) QueryKeys.key(sourceKey.columns, symbols) else UnknownKey.INSTANCE
    }

    private fun requireInitialGroupingLevels() {
        val rootSymbols = symbols
        val unique = key as? UniqueKey ?: return
        rootSymbols.values.forEach { symbol ->
            val grouping = symbol.origin as? GroupingOrigin ?: return@forEach
            if (symbol.id in unique.columns) grouping.definition.levels.forEach { level ->
                rootSymbols.values.filter { it.value is QueryValue.Measure }.forEach { value ->
                    QueryAggregationSemantics.aggregate(ResultAggregation.Minimum(ResultAggregation.NullPolicy.IGNORE), value.value, level.groupBy.map { requireSymbol(rootSymbols, it) },
                        QueryKeys.singleObservation(key, rootSymbols, level.groupBy))
                }
            }
        }
    }

    private fun bindChildren() {
        val dimensions = linkedMapOf<String, ScopedInputContract.DimensionRef>()
        symbols.values.forEach { symbol -> dimension(symbol.origin)?.let { dimensions[symbol.name] = it } }
        children.forEach { (name, child) ->
            val id = ScopedInputContract.requireId(name)
            require(child.analysis.scope == scope.child(id)) { "child analysis belongs to another lexical scope" }
            when (child.mode) {
                "ROOT" -> rootIds.add(id)
                "RELATION" -> relations.add(id)
                else -> alignChild(id, child.analysis, dimensions)
            }
        }
    }

    private fun alignChild(id: String, child: Analysis, dimensions: Map<String, ScopedInputContract.DimensionRef>) {
        val unique = key as? UniqueKey
        require(unique == null || symbols.values.none { it.id in unique.columns && it.origin is GroupingOrigin }) {
            "mixed grouping levels require an explicit level selection or a RELATION join with a level predicate before key alignment"
        }
        alignedIds.add(id)
        val grain = child.alignmentGrain()
        ScopedInputContract.requireGrainSubset(dimensions.values.toList(), grain.values.toList())
        if (population is MetricPopulation) ScopedInputContract.requirePopulationGrain(population.selection, id, dimensions.values.toList(), grain.values.toList())
        val correspondence = alignmentCorrespondence(child, grain)
        importColumns(child.outputs.filter { it.name !in grain }, { ScopedInputContract.metricSymbol(id, it.name) }, correspondence).forEach { add(symbols, it) }
        add(symbols, symbol(ScopedInputContract.presenceSymbol(id), InputPresenceOrigin(child.scope), emptyList()))
    }

    private fun alignmentCorrespondence(child: Analysis, grain: Map<String, ScopedInputContract.DimensionRef>): Map<SymbolId, SymbolId> {
        val result = linkedMapOf<SymbolId, SymbolId>()
        grain.forEach { (name, unit) ->
            val source = child.outputs.first { it.name == name }
            val matches = symbols.values.filter { dimension(it.origin) == unit }
            require(matches.size == 1) { "aligned unit key requires one exact parent semantic dimension" }
            val target = matches.single().id
            result[source.id] = target
            (source.value as? QueryValue.Plain)?.positions?.filter { it.path.isEmpty() }?.forEach { result[it.owner] = target }
        }
        return result
    }

    private fun requireRootConsumption(operations: List<PostOperationContract.ValidatedOperation>) {
        val stageRoots = linkedSetOf<String>()
        operations.filterIsInstance<PostOperationContract.ObserveWindow>().forEach { window ->
            window.calendar?.let { calendar ->
                val declared = requireNotNull(children[calendar.inputId]) { "calendar input is not declared" }
                if (declared.mode == "ROOT") stageRoots.add(calendar.inputId)
            }
        }
        when (population) {
            is MetricPopulation -> {
                RowPopulationContract.requireInputs(population.selection, alignedIds)
                require(rootIds == population.rootInputs + stageRoots) { "measure root cannot leave declared ROOT inputs unused" }
            }
            is IntrinsicPopulation -> require(rootIds == population.rootInputs + stageRoots) { "root must consume all and only its explicitly declared ROOT inputs" }
        }
    }

    /** 阶段拥有输入快照与可见导入；作用域状态仅在阶段完成后向后传递。 */
    private inner class Transition(private val index: Int, private val operation: PostOperationContract.ValidatedOperation) {
        private fun stagePath(): List<Any> = listOf<Any>("input") + scope.path + listOf("postOps", index)
        private fun expressionPath(name: String): List<Any> = stagePath() + name
        private val before = symbols.values.toList()
        private val imported = mutableListOf<Symbol>()
        private val available = LinkedHashMap(symbols)
        private val dependencies = PostOperationTraversal.dependencies(operation)

        fun apply(): Stage {
            prepareImports()
            requireExpressionIdentities()
            when (operation) {
                is PostOperationContract.JoinInput -> joinInput(operation)
                is PostOperationContract.SelectVersions -> selectVersions(operation)
                is PostOperationContract.AncestorClosure -> closeAncestors(operation)
                is PostOperationContract.Regroup -> regroup(operation)
                is PostOperationContract.ExpandCollection -> expandCollection(operation)
                is PostOperationContract.Project -> project(operation)
                is PostOperationContract.PostFilter -> filter(operation)
                is PostOperationContract.OutputOperation -> appendOutput(operation)
                is PostOperationContract.Sort, is PostOperationContract.TopN -> kotlin.Unit
            }
            QueryValue.requireUnitsRetained(symbols.values)
            return Stage(index, operation, before, imported, symbols.values.toList(), key)
        }

        private fun prepareImports() {
            if (operation is PostOperationContract.ObserveWindow) operation.calendar?.let { calendar ->
                val declared = requireNotNull(children[calendar.inputId]) { "calendar input is not declared" }
                requireSymbol(declared.analysis.outputs.associateBy { it.name }, calendar.dateSymbol)
                if (declared.mode == "RELATION") consumedRelations.add(calendar.inputId)
            }
            if (operation is PostOperationContract.JoinInput) {
                require(operation.inputId in relations) { "$field requires an explicitly declared RELATION input: ${operation.inputId}" }
                require(occurrences.add(operation.occurrenceId)) { "$field repeats a relation occurrence: ${operation.occurrenceId}" }
                consumedRelations.add(operation.inputId)
                imported.addAll(importColumns(children.getValue(operation.inputId).analysis.outputs,
                    { ScopedInputContract.metricSymbol(operation.occurrenceId, it.name) }, emptyMap()))
                imported.forEach { add(available, it) }
            }
        }

        private fun requireExpressionIdentities() {
            dependencies.forEach { requireSymbol(available, it) }
            val expressionKey = if (operation is PostOperationContract.JoinInput &&
                !QueryKeys.rightSingleMatch(children.getValue(operation.inputId).analysis, operation, symbols.keys)) UnknownKey.INSTANCE else key
            PostOperationTraversal.expressions(operation) { name, expression ->
                ResultExpressionTree.visit(expression, expressionPath(name)) { node, path ->
                    when (node) {
                        is ResultExpression.ResultWeightedContribution -> {
                            val objects = node.observationUnit as? Calculation.ObjectsBy
                            if (objects != null) {
                                val identity = objects.keys.map { requireSymbol(available, it).id }.toSet()
                                require(expressionKey is UniqueKey && identity == expressionKey.columns && objects.keys.all { requireSymbol(available, it).value is QueryValue.Plain }) {
                                    "weighted objects require the complete actual unique identity"
                                }
                            }
                        }
                        is ResultExpression.ResultUniformAllocation -> {
                            require(operation !is PostOperationContract.JoinInput) { "uniform allocation requires a completed association population, not a JOIN predicate" }
                            val keys = (node.sourceKeys + node.memberKeys).toSet()
                            val actual = keys.map { requireSymbol(available, it).id }.toSet()
                            require(expressionKey is UniqueKey && actual.containsAll(expressionKey.columns) && keys.all { requireSymbol(available, it).value is QueryValue.Plain }) {
                                "uniform allocation requires the complete actual source/member association identity"
                            }
                            QueryValueSemantics.uniformAllocation(QueryValueSemantics.expression(node.value, available, nativeFunctions, path + "value"),
                                node.sourceKeys.map { requireSymbol(available, it) }, node.memberKeys.map { requireSymbol(available, it) })
                        }
                        else -> kotlin.Unit
                    }
                }
            }
        }

        private fun joinInput(join: PostOperationContract.JoinInput) {
            QueryValueSemantics.expression(join.condition, available, nativeFunctions, expressionPath("condition"))
            if (join.joinType in setOf("SEMI", "ANTI")) return
            val child = children.getValue(join.inputId).analysis
            val retainedPopulation = QueryKeys.rightSingleMatch(child, join, symbols.keys)
            val retainedImportedPopulation = QueryKeys.leftSingleMatch(key, symbols, child, join)
            if (!retainedPopulation) {
                val rewritten = linkedMapOf<SymbolId, SymbolId>()
                symbols.replaceAll { _, value ->
                    val copied = QueryValueSemantics.copied(value.value)
                    if (copied == value.value) value else {
                        val id = SymbolId(scope, nextPosition++)
                        rewritten[value.id] = id
                        Symbol(id, value.name, value.origin, listOf(value.id), copied)
                    }
                }
                val unique = key as? UniqueKey
                if (unique != null) key = UniqueKey(unique.columns.map { rewritten[it] ?: it }.toSet())
            }
            if (!retainedImportedPopulation) imported.replaceAll { copiedImport(it) }
            key = QueryKeys.joinedKey(key, child, join, symbols.keys, imported)
            imported.forEach { add(symbols, it) }
        }

        private fun selectVersions(selection: PostOperationContract.SelectVersions) {
            if (selection.ties == PostOperationContract.VersionTies.FAIL) key = QueryKeys.key(selection.partitionBy, symbols)
            val snapshot = selection.snapshot ?: return
            val time = requireSymbol(symbols, snapshot.timeSymbol)
            require(time.value is QueryValue.Plain) { "snapshot selection requires an actual temporal observation axis" }
            val observed = SelectedObservation(listOf(ValuePosition.column(time.id)), snapshot.asOf as Calculation.Scalar?)
            selectedObservations.add(observed)
            val single = selection.ties == PostOperationContract.VersionTies.FAIL || QueryKeys.singleObservation(key, symbols, selection.partitionBy)
            symbols.replaceAll { _, value ->
                var actual = QueryAggregationSemantics.selectedSnapshotAt(value.value, observed.timeAxes, observed.asOf)
                if (!single) actual = QueryValueSemantics.copied(actual)
                if (actual == value.value) value else Symbol(SymbolId(scope, nextPosition++), value.name, value.origin, listOf(value.id), actual)
            }
        }

        private fun closeAncestors(closure: PostOperationContract.AncestorClosure) {
            val closed = linkedMapOf<String, Symbol>()
            val dependencies = (closure.sourceKeys + closure.parentKeys).map { requireSymbol(available, it).id }.toSet().toList()
            closure.sourceKeys.indices.forEach { component ->
                require(requireSymbol(available, closure.sourceKeys[component]).value is QueryValue.Plain &&
                    requireSymbol(available, closure.parentKeys[component]).value is QueryValue.Plain) { "ancestor identity components must be categorical scalar values" }
                listOf(closure.descendantAliases[component], closure.ancestorAliases[component]).forEach { alias ->
                    add(closed, symbol(alias, OperationOrigin(scope, index, closed.size, operation.operationType), dependencies))
                }
            }
            symbols = linkedMapOf<String, Symbol>().also { ordered ->
                (closure.descendantAliases + closure.ancestorAliases).forEach { add(ordered, closed.getValue(it)) }
            }
            key = QueryKeys.key(symbols.keys.toList(), symbols)
        }

        private fun regroup(regroup: PostOperationContract.Regroup) {
            val grouping = regroup.grouping
            val observations = observationReduction?.takeIf { it.stageIndex == index }
            observations?.let { declared ->
                val identity = declared.keys.map { requireSymbol(available, it).id }.toSet()
                check(key is UniqueKey && identity == (key as UniqueKey).columns && declared.keys.all { requireSymbol(available, it).value is QueryValue.Plain }) {
                    "bound statistical REGROUP must retain its proven complete object observation identity"
                }
            }
            grouping.aggregates.forEach { require(it.outputAlias !in available) { "$field aggregate output conflicts with an existing result symbol: ${it.outputAlias}" } }
            val levels = grouping.groupingLevels
            require(levels == null || levels.outputAlias !in available) { "$field grouping level output conflicts with an existing result symbol" }
            val grouped = linkedMapOf<String, Symbol>()
            grouping.groupBy.forEach { add(grouped, requireSymbol(available, it)) }
            if (levels != null) add(grouped, symbol(levels.outputAlias, GroupingOrigin(scope, index, levels), grouping.groupBy.map { requireSymbol(available, it).id }))
            grouping.aggregates.forEachIndexed { ordinal, aggregate ->
                val input = requireSymbol(available, aggregate.input)
                val dependencies = (grouping.groupBy.map { requireSymbol(available, it).id } + input.id).toSet().toList()
                val path = expressionPath("grouping") + listOf("aggregates", ordinal, "aggregation")
                fun reduce(groupBy: List<String>): QueryValue {
                    val retained = groupBy.map { requireSymbol(available, it) }
                    val single = QueryKeys.singleObservation(key, available, groupBy)
                    return if (observations != null) QueryAggregationSemantics.observationAggregate(aggregate.aggregation, input.value, retained, single, path)
                        else QueryAggregationSemantics.aggregate(aggregate.aggregation, input.value, retained, single, path)
                }
                levels?.levels?.forEach { level -> reduce(level.groupBy) }
                val actual = reduce(grouping.groupBy)
                add(grouped, symbol(aggregate.outputAlias, OperationOrigin(scope, index, ordinal, operation.operationType), dependencies, selectedStock(actual), false))
            }
            symbols = grouped
            key = QueryKeys.key(grouping.groupBy + listOfNotNull(levels?.outputAlias), symbols)
        }

        private fun expandCollection(expand: PostOperationContract.ExpandCollection) {
            val source = requireSymbol(available, expand.input)
            val array = source.value as? QueryValue.Array ?: throw IllegalArgumentException("EXPAND_COLLECTION requires a typed array symbol")
            val replacements = linkedMapOf<SymbolId, SymbolId>()
            before.forEach { input ->
                val copied = QueryValueSemantics.copied(input.value)
                if (copied != input.value) {
                    val replacement = Symbol(SymbolId(scope, nextPosition++), input.name, input.origin, listOf(input.id), copied)
                    symbols[input.name] = replacement
                    replacements[input.id] = replacement.id
                }
            }
            val element = symbol(expand.elementAlias, OperationOrigin(scope, index, 0, operation.operationType), listOf(source.id), array.element)
            val ordinal = symbol(expand.ordinalAlias, OperationOrigin(scope, index, 1, operation.operationType), listOf(source.id))
            add(symbols, element); add(symbols, ordinal)
            val unique = key as? UniqueKey
            key = if (expand.mode == "INNER" && unique != null) UniqueKey(unique.columns.map { replacements[it] ?: it }.toSet() + ordinal.id) else UnknownKey.INSTANCE
        }

        private fun project(project: PostOperationContract.Project) {
            symbols = linkedMapOf<String, Symbol>().also { projected -> project.symbols.forEach { add(projected, requireSymbol(available, it)) } }
            val unique = key as? UniqueKey
            if (unique != null && !symbols.values.map { it.id }.toSet().containsAll(unique.columns)) key = UnknownKey.INSTANCE
        }
        private fun filter(filter: PostOperationContract.PostFilter) {
            QueryValueSemantics.expression(filter.expression, available, nativeFunctions, expressionPath("expression"))
            key = selectedGroupingKey(filter.expression, key)
            val rewritten = refineUnits(filter.expression, index)
            val unique = key as? UniqueKey
            if (unique != null) key = UniqueKey(unique.columns.map { rewritten[it] ?: it }.toSet())
        }
        private fun appendOutput(output: PostOperationContract.OutputOperation) {
            add(symbols, symbol(output.outputAlias, OperationOrigin(scope, index, 0, operation.operationType),
                dependencies.map { requireSymbol(available, it).id }.toSet().toList(), selectedStock(outputValue(output, available, key, nativeFunctions, stagePath())), sameObservation(output, available)))
            if (output is PostOperationContract.ObserveWindow && output.domain == PostOperationContract.ObservationDomain.ROWS)
                key = QueryKeys.key((output.partitionBy + output.orderKeys.map { it.symbol }).distinct(), symbols)
        }
    }

    private fun selectedStock(value: QueryValue): QueryValue = selectedObservations.fold(value) { actual, observed ->
        QueryAggregationSemantics.selectedSnapshotAt(actual, observed.timeAxes, observed.asOf)
    }

    private fun selectedGroupingKey(expression: ResultExpression, current: Key): Key {
        if (expression !is ResultExpression.ResultOperation) return current
        if (expression.operator == ResultExpression.ResultOperator.AND) return expression.operands.fold(current) { actual, operand -> selectedGroupingKey(operand, actual) }
        if (expression.operator != ResultExpression.ResultOperator.EQ || current !is UniqueKey) return current
        val left = expression.operands[0]
        val right = expression.operands[1]
        val reference = (left as? ResultExpression.ResultSymbol) ?: (right as? ResultExpression.ResultSymbol) ?: return current
        val literal = (left as? ResultExpression.ResultLiteral) ?: (right as? ResultExpression.ResultLiteral) ?: return current
        val code = literal.value as? BigDecimal ?: return current
        val symbol = symbols[reference.symbol] ?: return current
        val grouping = symbol.origin as? GroupingOrigin ?: return current
        if (symbol.id !in current.columns) return current
        val level = grouping.definition.levels.firstOrNull { it.code == code.longValueExact() }
            ?: throw IllegalArgumentException("grouping filter selects an undeclared level code")
        val removed = hashSetOf(symbol.id)
        grouping.definition.levels.flatMap { it.groupBy }.distinct().filter { it !in level.groupBy }.forEach { name ->
            val original = symbols[name] ?: return@forEach
            removed.add(original.id)
            dimension(original.origin)?.let { dimension ->
                val id = SymbolId(scope, nextPosition++)
                symbols[name] = Symbol(id, name, OmittedDimensionOrigin(scope, original.id, symbol.id, dimension, level.id, level.code), listOf(original.id, symbol.id), QueryValue.plain(id))
            }
        }
        return UniqueKey(current.columns - removed)
    }

    private data class UnitSelection(val key: Symbol, val value: Any)
    private fun unitSelections(expression: ResultExpression): List<UnitSelection> {
        if (expression !is ResultExpression.ResultOperation) return emptyList()
        if (expression.operator == ResultExpression.ResultOperator.AND) return expression.operands.flatMap(::unitSelections)
        if (expression.operator != ResultExpression.ResultOperator.EQ) return emptyList()
        val left = expression.operands[0]
        val right = expression.operands[1]
        val reference = (left as? ResultExpression.ResultSymbol) ?: (right as? ResultExpression.ResultSymbol) ?: return emptyList()
        val constant = if (left === reference) right else left
        val value = when {
            constant is ResultExpression.ResultTypedLiteral && constant.valueType == ScalarValueType.TEXT -> constant.value
            constant is ResultExpression.ResultTypedLiteral && constant.valueType == ScalarValueType.NUMBER -> constant.valueType.parameter(constant.value)
            constant is ResultExpression.ResultLiteral && constant.value is BigDecimal -> constant.value
            else -> null
        } ?: return emptyList()
        return listOf(UnitSelection(requireSymbol(symbols, reference.symbol), value))
    }

    private fun refineUnits(expression: ResultExpression, index: Int): Map<SymbolId, SymbolId> {
        val rewritten = linkedMapOf<SymbolId, SymbolId>()
        val selections = unitSelections(expression)
        if (selections.isEmpty()) return rewritten
        symbols.values.toList().forEach { symbol ->
            val actual = refineUnit(symbol.value, selections, ValuePosition.column(symbol.id), index)
            if (actual != symbol.value) {
                val id = SymbolId(scope, nextPosition++)
                rewritten[symbol.id] = id
                symbols[symbol.name] = Symbol(id, symbol.name, symbol.origin, (listOf(symbol.id) + selections.map { it.key.id }).distinct(), actual)
            }
        }
        return rewritten
    }

    private fun refineUnit(value: QueryValue, selections: List<UnitSelection>, source: ValuePosition, index: Int): QueryValue {
        when (value) {
            is QueryValue.Row -> return QueryValue.Row(value.fields.mapIndexed { ordinal, child -> refineUnit(child, selections, source.field(ordinal), index) })
            is QueryValue.Array -> return QueryValue.Array(refineUnit(value.element, selections, source.element(), index))
            else -> kotlin.Unit
        }
        if (value !is QueryValue.Measure) return value
        val unit = value.quantity.unit as? QueryQuantity.Dimension ?: return value
        val proofs = mutableListOf<QueryQuantity.SelectedMember>()
        selections.forEach { selection -> unit.keys.forEach { key ->
            val plain = selection.key.value as? QueryValue.Plain
            val same = key.position == ValuePosition.column(selection.key.id) || plain?.positions?.contains(key.position) == true
            if (same && (unit.axis.memberType == ScalarValueType.NUMBER) == (selection.value is BigDecimal)) {
                val member = Calculation.Scalar(unit.axis.memberType, selection.value.toString())
                unit.axis.members.filter { it.member.sameValue(member) }.forEach { candidate ->
                    proofs.add(QueryQuantity.SelectedMember(key.reference, unit.axis, candidate.member, candidate.unit,
                        QueryQuantity.Selection(source, QueryQuantity.ResultFilter(scope, index, selection.key.id, candidate.member))))
                }
            }
        } }
        if (proofs.isEmpty()) return value
        require(proofs.map { it.identity }.distinct().size == 1) { "result filters identify contradictory measurement units" }
        return QueryValue.Measure(QueryQuantity(value.quantity.declaration, proofs.first(), value.quantity.provenance))
    }

    private fun importColumns(outputs: List<Symbol>, names: (Symbol) -> String, equality: Map<SymbolId, SymbolId>): List<Symbol> {
        val ids = linkedMapOf<SymbolId, SymbolId>()
        val references = linkedMapOf<ValuePosition, ValuePosition>()
        equality.forEach { (source, target) -> references[ValuePosition.column(source)] = ValuePosition.column(target) }
        outputs.forEach { output ->
            val id = SymbolId(scope, nextPosition++)
            ids[output.id] = id
            references.putIfAbsent(ValuePosition.column(output.id), ValuePosition.column(id))
            QueryValue.coordinates(output.value, ValuePosition.column(id), references)
        }
        return outputs.map { Symbol(ids.getValue(it.id), names(it), it.origin, listOf(it.id), QueryValue.remap(it.value, references)) }
    }

    private fun symbol(name: String, origin: Origin, dependencies: List<SymbolId>, value: QueryValue = QueryValue.anonymous(), sameObservation: Boolean = true): Symbol {
        val id = SymbolId(scope, nextPosition++)
        return Symbol(id, name, origin, dependencies, if (origin is GroupingOrigin) QueryValue.plain(id) else QueryValue.relocate(value, ValuePosition.column(id), sameObservation))
    }

    companion object {
        private fun copiedImport(symbol: Symbol): Symbol = Symbol(symbol.id, symbol.name, symbol.origin, symbol.dependencies, QueryValueSemantics.copied(symbol.value))
        private fun requireSymbol(symbols: Map<String, Symbol>, name: String): Symbol = requireNotNull(symbols[name]) { "symbol is outside the current lexical scope: $name" }
        private fun add(symbols: MutableMap<String, Symbol>, value: Symbol) { require(symbols.putIfAbsent(value.name, value) == null) { "conflicting output symbol: ${value.name}" } }

        private fun outputValue(operation: PostOperationContract.OutputOperation, symbols: Map<String, Symbol>, key: Key,
            nativeFunctions: Map<ResultExpression.ResultNativeCall, NativeScalarFunction>, fieldPath: List<Any>): QueryValue = when (operation) {
            is PostOperationContract.Derive -> QueryValueSemantics.expression(operation.expression, symbols, nativeFunctions, fieldPath + "expression")
            is PostOperationContract.Rank, is PostOperationContract.OrderedRank -> QueryAggregationSemantics.count()
            is PostOperationContract.Cumulative -> QueryAggregationSemantics.window(operation.aggregation, requireSymbol(symbols, operation.input).value,
                operation.partitionBy.map { requireSymbol(symbols, it) }, orders(symbols, listOf(PostOperationContract.OrderKey(operation.orderBy, operation.direction, if (operation.direction == "ASC") "LAST" else "FIRST"))),
                null, true, QueryKeys.totalOrder(key, symbols, operation.partitionBy, listOf(operation.orderBy)), QueryKeys.singleObservation(key, symbols, operation.partitionBy), fieldPath + "aggregation")
            is PostOperationContract.AggregateOutput -> {
                val window = operation as? PostOperationContract.WindowAggregate
                QueryAggregationSemantics.window(operation.aggregation, requireSymbol(symbols, operation.input).value, operation.partitionBy.map { requireSymbol(symbols, it) },
                    window?.let { orders(symbols, it.orderKeys) } ?: emptyList(), window?.windowFrame, false,
                    window != null && QueryKeys.totalOrder(key, symbols, window.partitionBy, window.orderKeys.map { it.symbol }), QueryKeys.singleObservation(key, symbols, operation.partitionBy), fieldPath + "aggregation")
            }
            is PostOperationContract.ObserveWindow -> {
                val input = if (operation.aggregation is ResultAggregation.Count && operation.aggregation.distinct) QueryValue.Row(operation.inputKeys.map { requireSymbol(symbols, it).value }) else requireSymbol(symbols, operation.inputKeys.first()).value
                QueryAggregationSemantics.window(operation.aggregation, input, operation.partitionBy.map { requireSymbol(symbols, it) }, orders(symbols, operation.orderKeys), operation.windowFrame, false,
                    operation.domain == PostOperationContract.ObservationDomain.ROWS || QueryKeys.totalOrder(key, symbols, operation.partitionBy, operation.orderKeys.map { it.symbol }), QueryKeys.singleObservation(key, symbols, operation.partitionBy), fieldPath + "aggregation")
            }
            is PostOperationContract.OffsetValue -> QueryAggregationSemantics.offset(requireSymbol(symbols, operation.input).value, operation.partitionBy.map { requireSymbol(symbols, it) }, orders(symbols, operation.orderKeys),
                operation.function, operation.distance, QueryKeys.totalOrder(key, symbols, operation.partitionBy, operation.orderKeys.map { it.symbol }))
        }
        private fun orders(symbols: Map<String, Symbol>, keys: List<PostOperationContract.OrderKey>): List<QueryQuantity.Order> = keys.map {
            QueryQuantity.Order(ValuePosition.column(requireSymbol(symbols, it.symbol).id), it.direction, it.nulls)
        }
        private fun sameObservation(operation: PostOperationContract.OutputOperation, symbols: Map<String, Symbol>): Boolean {
            if (operation is PostOperationContract.Derive) return true
            if (operation is PostOperationContract.OffsetValue) {
                val plain = requireSymbol(symbols, operation.input).value as? QueryValue.Plain ?: return false
                val partition = hashSetOf<ValuePosition>()
                operation.partitionBy.forEach { QueryValue.scalarReferences(requireSymbol(symbols, it).value, partition) }
                return plain.positions.any { it in partition }
            }
            return false
        }
    }
}
