package com.olap.prototype.engine.compiler

import com.olap.prototype.connector.SourceCapabilityException
import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.engine.catalog.ExecutableCatalog
import com.olap.prototype.engine.catalog.ExecutablePhysicalExecutionBinding
import com.olap.prototype.engine.catalog.SourceBindingConflictException
import com.olap.prototype.engine.context.OlapQueryContext
import com.olap.prototype.engine.executable.*
import com.olap.prototype.query.QueryExecutionMode
import com.olap.prototype.query.QueryStageAnalysis.Analysis
import com.olap.prototype.query.QueryStageAnalysis.ScopeId
import org.apache.calcite.plan.RelOptUtil
import com.olap.prototype.engine.calcite.CalcitePlanEvidence

/** 绑定→阶段分析→精确源解析→原生编译→证据；每阶段只交出已完成的真实事实。 */
class OlapSemanticCompiler(private val compiler: CalciteRelCompiler) {
    private val binder = ExecutableSemanticBinder(compiler.connectors)
    private val sourceResolver = ExecutableSourceResolver()

    private data class Prepared(val graph: ExecutableInputGraph,
        val analyses: Map<ScopeId, Analysis>,
        val sources: ResolvedInputSources, val query: CompiledQuery)

    /** 来源授权只需要完整依赖绑定，不生成关系计划或 SQL；实际执行仍在已授权 worker 内完整编译。 */
    fun resolveSource(catalog: ExecutableCatalog, request: ExecutableQueryRequest, context: OlapQueryContext,
        observer: ExecutableExecutionObserver): ExecutablePhysicalExecutionBinding {
        val prepared = prepare(catalog, request, context, observer)
        return phase("RESOLVE_SOURCES", "EXECUTABLE_SOURCE_RESOLUTION_FAILED", "Source selection failed", prepared.query) {
            requireNotNull(ExecutableSourceDependencies.collect(prepared.graph, prepared.sources).bindings.values.firstOrNull()) {
                "calculation has no frozen executable source"
            }
        }
    }

    fun compile(catalog: ExecutableCatalog, request: ExecutableQueryRequest, context: OlapQueryContext, observer: ExecutableExecutionObserver): CompiledQuery {
        val (graph, analyses, resolved, query) = prepare(catalog, request, context, observer)
        val corePath = if (request.executionMode != QueryExecutionMode.CORE_FINALIZATION) null else {
            phase("COMPILE_CORE_FINALIZATION", "CORE_FINALIZATION_PLAN_FAILED", "Core finalization root selection failed", query) {
                CoreFinalizationPath.select(request, graph, analyses)
            }
        }
        if (corePath != null) timed(context, "COMPILE_CORE_PREFLIGHT") {
            phase("COMPILE_CORE_FINALIZATION", "CORE_FINALIZATION_PLAN_FAILED", "Core finalization stage selection failed", query) {
                CoreFinalizationPlan.requireSupportedStages(analyses, corePath)
            }
        }
        val sourceAnalyses = if (corePath == null) analyses else timed(context, "COMPILE_SOURCE_PREFIX") {
            phase("ANALYZE_SOURCE_PREFIX", "EXECUTABLE_SOURCE_PREFIX_ANALYSIS_FAILED", "Source completion boundary analysis failed", query) {
                ExecutableStageAnalysis.sourcePrefix(graph, analyses, corePath.sourceScope, compiler.connectors)
            }
        }
        val relational = try { compiler.compile(graph, sourceAnalyses, resolved, context, corePath?.sourceScope ?: ScopeId.root()) }
        catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
        catch (failure: EngineExecutionException) { throw EngineExecutionException(failure.stage, failure.code, failure.message, failure,
            observedEvidence(query) + failure.evidenceContext, query, failure.sourceFailure, failure.sourceReadEvidence, failure.sourceExecutionStarted, failure.stageNanos) }
        catch (failure: SourceCapabilityException) {
            throw EngineExecutionException("SOURCE_ACCESS", failure.code, failure.message, failure,
                observedEvidence(query) + mapOf("dataSourceId" to failure.dataSourceId, "operation" to failure.operation, "capability" to failure.capability.name), query)
        } catch (failure: RuntimeException) {
            throw EngineExecutionException("COMPILE_REL", "EXECUTABLE_REL_COMPILATION_FAILED", "Native relational compilation failed: ${failure.message}", failure, observedEvidence(query), query)
        }
        var observed = query.copy(executableLoweringResult = relational.lowering)
        if (corePath != null) {
            val core = timed(context, "COMPILE_CORE_FINALIZATION") {
                phase("COMPILE_CORE_FINALIZATION", "CORE_FINALIZATION_PLAN_FAILED", "Core finalization plan compilation failed", observed) {
                    CoreFinalizationPlan.compile(relational.lowering.outputContract, analyses, corePath)
                }
            }
            observed = observed.copy(coreFinalizationPlan = core)
        }
        return timed(context, "COMPILE_PLAN_EVIDENCE") {
            phase("CAPTURE_PLAN", "EXECUTABLE_PLAN_CAPTURE_FAILED", "Native relational plan capture failed", observed) {
                observed.copy(rawRelationalPlan = RelOptUtil.toString(relational.raw),
                    optimizedRelationalPlan = RelOptUtil.toString(relational.optimized), rawRelationalPlanJson = CalcitePlanEvidence.write(relational.raw),
                    optimizedRelationalPlanJson = CalcitePlanEvidence.write(relational.optimized),
                    sqlRelationalPlan = RelOptUtil.toString(relational.sqlRelation), sqlRelationalPlanJson = CalcitePlanEvidence.write(relational.sqlRelation),
                    compilationScale = relational.scale, sqlPlan = CalcitePlanEvidence.describe(relational.sqlRelation))
            }
        }
    }

    private fun prepare(catalog: ExecutableCatalog, request: ExecutableQueryRequest, context: OlapQueryContext,
        observer: ExecutableExecutionObserver): Prepared {
        val input = timed(context, "COMPILE_BIND") {
            phase("BIND_INPUT", "EXECUTABLE_INPUT_BIND_FAILED", "Business input binding failed") { binder.bind(catalog, request) }
        }
        observer.bindingCompleted(input)
        val (graph, analyses) = timed(context, "COMPILE_ANALYZE") {
            phase("ANALYZE_STAGES", "EXECUTABLE_STAGE_ANALYSIS_FAILED", "Stage analysis failed") {
                val graph = ExecutableInputGraph.index(input)
                graph to ExecutableStageAnalysis.analyze(graph, compiler.connectors)
            }
        }
        observer.stageAnalysisCompleted(analyses.values.toList())
        var observed = CompiledQuery(request.requestId, catalog.manifest.catalogId, analyses.values.toList(),
            scopes = CompiledScopeExplanation.collect(graph), executionMode = request.executionMode)
        val resolved = timed(context, "COMPILE_RESOLVE_SOURCE") {
            phase("RESOLVE_SOURCES", "EXECUTABLE_SOURCE_RESOLUTION_FAILED", "Source resolution failed", observed) { sourceResolver.resolve(catalog, graph) }
        }
        val evidence = CompilationEvidence.collect(graph, resolved)
        observed = observed.copy(resolvedSources = evidence.sources, metricInvocations = evidence.invocations)
        observer.sourceResolutionCompleted(observed.resolvedSources)
        context.addAuditLog("OlapSemanticCompiler: inputScopes=${analyses.size}, resolvedEventInputs=${evidence.sources.size}")
        return Prepared(graph, analyses, resolved, observed)
    }

    private fun observedEvidence(query: CompiledQuery): Map<String, Any?> = mapOf("requestId" to query.requestId,
        "stageAnalyses" to query.stageAnalyses, "resolvedSources" to query.resolvedSources)
    private inline fun <T> phase(stage: String, code: String, description: String, observed: CompiledQuery? = null, body: () -> T): T = try { body() }
    catch (failure: java.util.concurrent.CancellationException) { throw failure }
    catch (failure: EngineExecutionException) { throw failure }
    catch (failure: SourceBindingConflictException) { throw EngineExecutionException("RESOLVE_SOURCES", failure.code, requireNotNull(failure.message), failure,
        (observed?.let(::observedEvidence) ?: emptyMap()) + mapOf("inputs" to failure.inputs), observed) }
    catch (failure: RuntimeException) { throw EngineExecutionException(stage, code, "$description: ${failure.message}", failure,
        observed?.let(::observedEvidence) ?: emptyMap(), observed) }
    private inline fun <T> timed(context: OlapQueryContext, stage: String, body: () -> T): T {
        val start = System.nanoTime()
        return try { body() } finally { context.recordStageTime(stage, start) }
    }
}
