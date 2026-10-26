package com.olap.prototype.engine.executable

import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.contract.JdbcRelationCheck
import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.engine.calcite.CalciteQueryCompiler
import com.olap.prototype.engine.calcite.CandidateValueCompiler
import com.olap.prototype.engine.catalog.ExecutablePhysicalExecutionBinding
import com.olap.prototype.engine.context.OlapQueryContext
import com.olap.prototype.engine.compiler.CompilationScale
import com.olap.prototype.expression.*
import com.olap.prototype.query.*
import com.olap.prototype.query.QueryStageAnalysis.Analysis
import com.olap.prototype.query.QueryStageAnalysis.ScopeId
import org.apache.calcite.plan.RelOptUtil
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rex.RexNode
import org.apache.calcite.tools.RelBuilder

/** 已分析Scope→原生关系→优化→SQL；各阶段交出完整事实。 */
class CalciteRelCompiler private constructor(val connectors: ConnectorRegistry, private val candidatePage: CandidatePage?) {
    constructor(connectors: ConnectorRegistry) : this(connectors, null)
    private data class CandidatePage(val offset: Int, val limit: Long) { init { require(offset >= 0 && limit > 0) { "candidate page bounds are invalid" } } }
    data class Compilation(val lowering: ExecutableLoweringResult, val raw: RelNode, val optimized: RelNode,
        val sqlRelation: RelNode, val scale: CompilationScale)
    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(CalciteRelCompiler::class.java)
        fun memberCandidateValues(connectors: ConnectorRegistry, offset: Int, limit: Long): CalciteRelCompiler = CalciteRelCompiler(connectors, CandidatePage(offset, limit))
    }

    fun compile(graph: ExecutableInputGraph, analyses: Map<ScopeId, Analysis>, resolvedSources: ResolvedInputSources, context: OlapQueryContext,
        outputScope: ScopeId = ScopeId.root(), observationIdentity: List<String>? = null): Compilation =
        lower(graph, analyses, resolvedSources, context) { relations, builder ->
            val outputInput = requireNotNull(graph.scope(outputScope)) { "native output scope is outside the bound input graph" }
            val completed = relations.compile(outputInput, outputScope)
            requireObservation(builder, completed.relation, observationIdentity, outputScope, relations.relationChecks)
            SourceOutput(completed.relation, completed.displayNames) { type -> ExecutableSourceLayout.single(relations.contract(outputScope, type)) }
        }

    fun compileCore(graph: ExecutableInputGraph, analyses: Map<ScopeId, Analysis>, resolvedSources: ResolvedInputSources, context: OlapQueryContext,
        sources: List<CoreFinalizationGraph.SourceInput>): Compilation {
        require(sources.isNotEmpty() && sources.map { it.cut.scope }.distinct().size == sources.size) { "core source cuts must select one explicit boundary per scope" }
        sources.forEach { source ->
            require(analyses.getValue(source.cut.scope).stages.size == source.cut.stageCount) { "native source analysis differs from its selected prefix boundary" }
        }
        return lower(graph, analyses, resolvedSources, context) { relations, builder ->
            val completed = sources.associateWith { source ->
                val input = requireNotNull(graph.scope(source.cut.scope)) { "core source cut is outside the bound input graph" }
                relations.compile(input, source.cut.scope)
            }
            val contracts = completed.mapValues { (source, output) -> relations.contract(source.cut.scope, output.relation.rowType) }
            val reductions = completed.mapValues { (source, output) ->
                CoreSourceReduction.compile(builder, source, output.relation, relations.parameters, relations.native)
            }
            val payloads = completed.mapValues { (source, output) -> reductions[source]?.relation ?: output.relation }
            if (sources.size == 1) {
                val source = sources.single()
                val output = completed.getValue(source)
                val reduction = reductions[source]?.reduction
                val payload = reduction?.fields ?: contracts.getValue(source).columns.map { ExecutableSourceLayout.Field(it.symbol.name, it.nativeType) }
                SourceOutput(payloads.getValue(source), payload.associate { it.name to (output.displayNames[it.name] ?: it.name) }) { type -> ExecutableSourceLayout(
                    type.fieldList.map { ExecutableSourceLayout.Field(it.name, it.type) },
                    mapOf(source.cut.scope to ExecutableSourceLayout.Scope(contracts.getValue(source), payload.associate { it.name to it.name }, null, payload, reduction))) }
            } else {
                val occupied = payloads.values.flatMapTo(linkedSetOf()) { it.rowType.fieldNames }
                val scopes = linkedMapOf<ScopeId, ExecutableSourceLayout.Scope>()
                val display = linkedMapOf<String, String>()
                sources.forEach { source ->
                    val output = completed.getValue(source)
                    val contract = contracts.getValue(source)
                    val reduction = reductions[source]?.reduction
                    val payload = reduction?.fields ?: contract.columns.map { ExecutableSourceLayout.Field(it.symbol.name, it.nativeType) }
                    val columns = linkedMapOf<String, String>()
                    payload.forEach { field ->
                        val physical = SemanticNativeTypes.internalAlias(occupied, "__core_value_")
                        columns[field.name] = physical
                        display[physical] = output.displayNames[field.name] ?: field.name
                    }
                    val presence = SemanticNativeTypes.internalAlias(occupied, "__core_presence_")
                    display[presence] = "core source row present"
                    scopes[source.cut.scope] = ExecutableSourceLayout.Scope(contract, columns, presence, payload, reduction)
                }
                val branches = sources.map { source ->
                    builder.push(payloads.getValue(source))
                    val expressions = mutableListOf<RexNode>()
                    val names = mutableListOf<String>()
                    scopes.forEach { (scope, output) ->
                        val present = scope == source.cut.scope
                        output.payload.forEach { field ->
                            val type = builder.typeFactory.createTypeWithNullability(field.nativeType, true)
                            expressions += if (present) builder.rexBuilder.makeCast(type, builder.field(field.name))
                                else builder.rexBuilder.makeNullLiteral(type)
                            names += output.columns.getValue(field.name)
                        }
                        expressions += builder.literal(present)
                        names += requireNotNull(output.presenceColumn)
                    }
                    builder.project(expressions, names, true).build()
                }
                // 每个原袋独立交付；UNION ALL只运输，不按值去重或推断跨cut身份。
                branches.forEach(builder::push)
                builder.union(true, branches.size)
                val transported = builder.build()
                SourceOutput(transported, display) { type ->
                    ExecutableSourceLayout(type.fieldList.map { ExecutableSourceLayout.Field(it.name, it.type) }, scopes)
                }
            }
        }
    }

    private data class SourceOutput(val relation: RelNode, val displayNames: Map<String, String>, val layout: (RelDataType) -> ExecutableSourceLayout)

    private fun requireObservation(builder: RelBuilder, completed: RelNode, keys: List<String>?, scope: ScopeId, checks: MutableList<RelationalCheck>) {
        keys?.let {
            RelationalIdentity.requireUnique(completed, it, "completed observation")
            RelationalIdentity.checkKnown(builder, completed, it, "OBSERVATION_OBJECT_IDENTITY_NULL", checks, scope)
        }
    }

    private fun lower(graph: ExecutableInputGraph, analyses: Map<ScopeId, Analysis>, resolvedSources: ResolvedInputSources, context: OlapQueryContext,
        build: (Relations, RelBuilder) -> SourceOutput): Compilation {
        val frozen = timed(context, "COMPILE_SCHEMA") { frozenInputs(graph, resolvedSources) }
        val builder = frozen.nativeInputs.builder
        val support = connectors.require(frozen.source.connectorRef).compilation
        val native = NativeExpressionScope(frozen.source.connectorRef, support) {
            throw IllegalArgumentException("completed native scalar calls cannot introduce physical tables; use SemanticExpression.NativeCall in an adopted source expression")
        }
        val relations = Relations(graph, builder, analyses, resolvedSources, native)
        val output = timed(context, "COMPILE_BUILD_REL") { build(relations, builder) }
        val raw = output.relation
        val parameters = relations.parameters
        val optimized = timed(context, "COMPILE_OPTIMIZE_REL") {
            phase("OPTIMIZE_REL", "EXECUTABLE_REL_OPTIMIZATION_FAILED", "Native relational optimization failed") {
                val optimized = CalciteQueryCompiler.optimize(raw, parameters)
                candidatePage?.let { CalciteQueryCompiler.optimize(CandidateValueCompiler.page(optimized, it.offset, it.limit), parameters) } ?: optimized
            }
        }
        if (log.isDebugEnabled) log.debug("原生关系优化：raw={} optimized={}", RelOptUtil.toString(raw), RelOptUtil.toString(optimized))
        val (rendered, nativeChecks) = timed(context, "COMPILE_RENDER_SQL") {
            phase("RENDER_SQL", "EXECUTABLE_SQL_RENDERING_FAILED", "Native SQL conversion failed") {
                val sql = CalciteSqlRendering.compile(optimized, support, parameters)
                val checks = relations.relationChecks.mapNotNull { check ->
                    val checked = CalciteQueryCompiler.optimize(check.violations, parameters)
                    if (checked.cluster.metadataQuery.getMaxRowCount(checked) == 0.0) return@mapNotNull null
                    val statement = CalciteSqlRendering.render(checked, support, parameters)
                    JdbcRelationCheck(check.code, statement.sql, CalciteSqlRendering.orderedParameters(statement, parameters), check.origin)
                }
                sql to checks
            }
        }
        val sql = rendered.sql
        val aliases = optimized.rowType.fieldNames.associateWith { it }
        val display = output.displayNames.filterKeys(aliases::containsKey)
        val layout = output.layout(optimized.rowType)
        context.addAuditLog("Calcite: frozen scans -> Rex/Rel -> HepPlanner -> RelToSqlConverter; inputScopes=${graph.inputs.size}, outputScopes=${layout.scopes.keys.map { it.path }}, dataSource=${frozen.source.dataSourceId}")
        return Compilation(ExecutableLoweringResult(sql.sql, frozen.source.dataSourceId, frozen.source.connectorRef, support, frozen.source.dialect,
            CalciteSqlRendering.orderedParameters(sql, parameters), aliases, display, frozen.nativeInputs.sourceRequirements(), nativeChecks, layout), raw, optimized,
            rendered.relation, CompilationScale.capture(raw, optimized, rendered))
    }

    private data class FrozenInputs(val source: ExecutablePhysicalExecutionBinding, val nativeInputs: CalciteQueryCompiler.Inputs)
    private fun frozenInputs(graph: ExecutableInputGraph, resolved: ResolvedInputSources): FrozenInputs {
        val selected = ExecutableSourceDependencies.collect(graph, resolved)
        val source = selected.bindings.values.firstOrNull() ?: throw IllegalArgumentException("calculation has no frozen executable source")
        return FrozenInputs(source, CalciteQueryCompiler.inputs(selected.bindings.values, selected.dependencies.columns, selected.dependencies.tables, connectors))
    }
    private data class ScopeOutput(val input: ExecutableInputScope, val relation: RelNode, val displayNames: Map<String, String>, val inputType: RelDataType)
    private data class ImportedInputs(val alignedRoot: RelNode, val relations: Map<String, RelNode>)

    /** 一次lowering共享参数/检查，scope结果只按实际输入和分析作用域复用。 */
    private inner class Relations(private val graph: ExecutableInputGraph, private val builder: RelBuilder,
        private val analyses: Map<ScopeId, Analysis>, resolved: ResolvedInputSources, val native: NativeExpressionScope) {
        val parameters = mutableListOf<JdbcParameter>()
        val relationChecks = mutableListOf<RelationalCheck>()
        private val facts = FactRelationCompiler(SourceRelationCompiler(resolved))
        private val roots = InputRootCompiler(builder, analyses, parameters, relationChecks, facts,
            facts.sharedFacts(builder, graph.inputs, parameters)) { scope, display -> relation(requireNotNull(graph.scope(scope)), scope, display) }
        val outputs = linkedMapOf<ScopeId, ScopeOutput>()
        fun inputTypes(): Map<ScopeId, RelDataType> = outputs.mapValues { it.value.inputType }
        fun contract(scope: ScopeId, type: RelDataType): ExecutableOutputContract =
            ExecutableOutputContract.compiled(analyses.filterKeys { it in outputs }, type, inputTypes(), scope)
        private fun relation(input: ExecutableInputScope, scope: ScopeId, display: MutableMap<String, String>): RelNode = compile(input, scope).let { display.putAll(it.displayNames); it.relation }
        fun compile(input: ExecutableInputScope, scope: ScopeId): ScopeOutput {
            outputs[scope]?.let { check(it.input === input) { "one analyzed scope has different completed input identities" }; return it }
            val display = linkedMapOf<String, String>()
            val root = roots.compile(input, scope, display)
            val imported = importInputs(input, scope, root, display)
            builder.push(imported.alignedRoot)
            (input.root as? ExecutableInputRoot.Measures)?.let(roots::filterOutputs)
            val inputType = builder.peek().rowType
            CompletedRelationCompiler.compile(builder, analyses.getValue(scope), display, parameters, imported.relations, relationChecks, native)
            return ScopeOutput(input, builder.build(), display.toMap(), inputType).also { outputs[scope] = it }
        }
        private fun importInputs(input: ExecutableInputScope, scope: ScopeId, root: RelNode, display: MutableMap<String, String>): ImportedInputs {
            val rootDimensions = linkedMapOf<String, ScopedInputContract.DimensionRef>()
            analyses.getValue(scope).inputs.forEach { column -> QueryStageAnalysis.dimension(column.origin)?.let { rootDimensions[column.name] = it } }
            val primaryGrain = rootDimensions.values.toList()
            val measures = input.root as? ExecutableInputRoot.Measures
            if (measures != null) RowPopulationContract.requireInputs(measures.population, input.additionalInputs.filterValues { it.inputMode == "KEY_ALIGNMENT" }.keys)
            val completed = linkedMapOf<String, RelNode>()
            val relations = linkedMapOf<String, RelNode>()
            val grains = linkedMapOf<String, Map<String, ScopedInputContract.DimensionRef>>()
            val occupied = root.rowType.fieldNames.toCollection(linkedSetOf())
            val calendars = analyses.getValue(scope).stages.map { it.operation }.filterIsInstance<PostOperationContract.ObserveWindow>().mapNotNull { it.calendar?.inputId }.toSet()
            input.additionalInputs.forEach { (id, child) ->
                ScopedInputContract.requireId(id)
                val mode = ScopedInputContract.requireMode(requireNotNull(child.inputMode))
                when {
                    mode == "ROOT" && id !in calendars -> Unit
                    mode == "RELATION" || id in calendars -> relations[id] = compile(child, scope.child(id)).relation
                    else -> {
                        val grain = alignmentGrain(input, scope, id, primaryGrain)
                        completed[id] = alignedInput(child, scope.child(id), id, grain, occupied, display); grains[id] = grain
                    }
                }
            }
            return ImportedInputs(CompletedRelations.alignInputs(builder, root, rootDimensions, completed, grains, measures?.population), relations.toMap())
        }
        private fun alignmentGrain(input: ExecutableInputScope, scope: ScopeId, id: String, primary: List<ScopedInputContract.DimensionRef>): Map<String, ScopedInputContract.DimensionRef> {
            val child = input.additionalInputs.getValue(id)
            val grain = analyses.getValue(scope.child(id)).alignmentGrain()
            ScopedInputContract.requireGrainSubset(primary, grain.values.toList())
            (input.root as? ExecutableInputRoot.Measures)?.let { ScopedInputContract.requirePopulationGrain(it.population, id, primary, grain.values.toList()) }
            facts.requireValueAlignment(input.metrics + child.metrics, grain.values.toSet())
            return grain
        }
        private fun alignedInput(input: ExecutableInputScope, scope: ScopeId, id: String, grain: Map<String, ScopedInputContract.DimensionRef>,
            occupied: MutableSet<String>, display: MutableMap<String, String>): RelNode {
            val localDisplay = linkedMapOf<String, String>()
            builder.push(relation(input, scope, localDisplay))
            val values = builder.fields().toMutableList<RexNode>()
            val names = builder.peek().rowType.fieldNames.toMutableList()
            names.indices.forEach { index ->
                val name = names[index]
                if (name !in grain) {
                    val alias = ScopedInputContract.metricSymbol(id, name)
                    require(occupied.add(alias)) { "conflicting scoped symbol: $alias" }
                    display[alias] = "$id: ${localDisplay[name]}"; names[index] = alias
                }
            }
            val presence = ScopedInputContract.presenceSymbol(id)
            require(presence !in names && occupied.add(presence)) { "conflicting input presence symbol: $presence" }
            values.add(builder.literal(true)); names.add(presence)
            builder.project(values, names, true); display[presence] = "$id: input group present"
            return builder.build()
        }
    }
    private inline fun <T> timed(context: OlapQueryContext, stage: String, body: () -> T): T { val started = System.nanoTime(); return try { body() } finally { context.recordStageTime(stage, started) } }
    private inline fun <T> phase(stage: String, code: String, meaning: String, body: () -> T): T = try { body() }
    catch (failure: java.util.concurrent.CancellationException) { throw failure }
    catch (failure: RuntimeException) { throw EngineExecutionException(stage, code, "$meaning: ${failure.message}", failure) }
}
