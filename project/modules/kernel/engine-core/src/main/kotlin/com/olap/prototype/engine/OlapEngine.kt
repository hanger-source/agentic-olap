package com.olap.prototype.engine

import com.olap.prototype.api.OlapResult
import com.olap.prototype.connector.*
import com.olap.prototype.engine.catalog.ExecutableCatalog
import com.olap.prototype.engine.compiler.CompiledQuery
import com.olap.prototype.engine.compiler.OlapSemanticCompiler
import com.olap.prototype.engine.context.OlapQueryContext
import com.olap.prototype.engine.executable.*
import com.olap.prototype.runtime.RuntimeResourceLimitException
import com.olap.prototype.warehouse.SourceGuaranteeException
import com.olap.prototype.warehouse.WarehouseExecutionControl
import com.olap.prototype.warehouse.ResultMaterializationLimits
import com.olap.prototype.warehouse.contract.SourceReadEvidence
import com.olap.prototype.contract.JdbcSourceRequirement
import java.util.concurrent.CancellationException

class OlapEngine(private val semanticCompiler: OlapSemanticCompiler, private val sqlExecutor: ExecutableSqlExecutor,
    private val resultLimits: ResultMaterializationLimits) : ExecutableEngineExecutionPort {
    private val log = org.slf4j.LoggerFactory.getLogger(OlapEngine::class.java)

    override fun execute(catalog: ExecutableCatalog, request: ExecutableQueryRequest, observer: ExecutableExecutionObserver, executionControl: WarehouseExecutionControl): ExecutableExecutionResult {
        val started = System.nanoTime()
        val context = OlapQueryContext(request.requestId)
        var compiled: CompiledQuery? = null
        var lowering: ExecutableLoweringResult? = null
        var sourceReadEvidence: SourceReadEvidence? = null
        var sourceExecutionStarted = false
        var activeStage = "COMPILE_TOTAL"
        try {
            val semanticStarted = System.nanoTime()
            executionControl.checkCancellation()
            val query = try { semanticCompiler.compile(catalog, request, context, observer) }
            finally { context.recordStageTime("COMPILE_TOTAL", semanticStarted) }
            compiled = query
            context.addAuditLog("OlapEngine: executable 主链路已完成 业务输入绑定、阶段分析、源解析和原生关系编译")
            val sql = requireNotNull(query.executableLoweringResult) { "compiled SQL contract is required" }
            lowering = sql
            observer.loweringCompleted(sql)
            executionControl.checkCancellation()
            sourceExecutionStarted = true
            activeStage = "EXECUTE_SQL"
            val sourceResult = sqlExecutor.execute(sql, context, executionControl)
            val actualReadEvidence = SourceReadEvidence(JdbcSourceRequirement.requireSingleBinding(sql.sourceRequirements), sourceResult.readBasis, sourceResult.request)
            sourceReadEvidence = actualReadEvidence
            activeStage = "REPORT_SOURCE_COMPLETION"
            observer.sourceCompleted(actualReadEvidence)
            val frame = sourceResult.frame
            executionControl.checkCancellation()
            activeStage = if (query.coreFinalizationPlan == null) "MATERIALIZE_RESULT" else "CORE_FINALIZATION"
            val projectionStarted = System.nanoTime()
            val business = try {
                val finalization = query.coreFinalizationPlan
                (if (finalization == null) ExecutableBusinessResults.produce(frame, sql.outputContract, sql.compilationSupport)
                    else finalization.execute(frame, sql.compilationSupport, executionControl, resultLimits)).also { executionControl.checkCancellation() }
            } catch (failure: CancellationException) { throw failure }
            catch (failure: RuntimeResourceLimitException) { throw failure }
            catch (failure: EngineExecutionException) { throw failure }
            catch (failure: RuntimeException) { throw EngineExecutionException(activeStage,
                if (query.coreFinalizationPlan == null) "EXECUTABLE_OUTPUT_CONTRACT_FAILED" else "CORE_FINALIZATION_FAILED",
                if (query.coreFinalizationPlan == null) "Result differs from compiled output contract" else "Core finalization failed its declared result contract",
                failure, executionEvidence(compiled, lowering, context), compiled,
                sourceReadEvidence = sourceReadEvidence, sourceExecutionStarted = sourceExecutionStarted, stageNanos = context.stageTimes) }
            finally { context.recordStageTime(if (query.coreFinalizationPlan == null) "BUSINESS_PROJECTION" else "CORE_FINALIZATION", projectionStarted) }
            context.recordStageTime("ENGINE_TOTAL", started)
            return ExecutableExecutionResult(OlapResult(query.requestId, sql.sql, sql.aliasMapping, frame, context.auditReport, context.stageTimes), query, business, actualReadEvidence)
        } catch (failure: CancellationException) {
            if (sourceReadEvidence == null) throw failure
            context.recordStageTime("ENGINE_TOTAL", started)
            throw EngineExecutionException(activeStage, "CANCELLED", "execution cancelled after the source returned a result", failure,
                executionEvidence(compiled, lowering, context), compiled, sourceReadEvidence = sourceReadEvidence,
                sourceExecutionStarted = sourceExecutionStarted, stageNanos = context.stageTimes)
        }
        catch (failure: EngineExecutionException) {
            context.recordStageTime("ENGINE_TOTAL", started)
            throw EngineExecutionException(failure.stage, failure.code, failure.message, failure.cause,
                executionEvidence(compiled, lowering, context) + failure.evidenceContext + mapOf("stageNanos" to context.stageTimes, "sourceExecutionStarted" to sourceExecutionStarted), failure.compiledQuery ?: compiled,
                failure.sourceFailure, sourceReadEvidence ?: failure.sourceReadEvidence, sourceExecutionStarted, context.stageTimes)
        } catch (failure: Exception) {
            context.recordStageTime("ENGINE_TOTAL", started)
            val evidence = executionEvidence(compiled, lowering, context).toMutableMap()
            evidence["sourceExecutionStarted"] = sourceExecutionStarted
            val (stage, code, message) = when (failure) {
                is SourceExecutionException -> Triple(failure.failure.stage, failure.failure.code, failure.failure.safeMessage)
                is RuntimeResourceLimitException -> Triple(if (activeStage == "CORE_FINALIZATION") activeStage else "EXECUTION_RESOURCE", failure.code, "Execution resource budget exceeded")
                is SourceBindingUnavailableException -> {
                    evidence["dataSourceId"] = failure.binding.dataSourceId; evidence["sourceIdentityHash"] = failure.binding.sourceIdentityHash
                    Triple("SOURCE_ACCESS", "SOURCE_BINDING_NOT_DEPLOYED", failure.message)
                }
                is SourceCapabilityException -> {
                    evidence["dataSourceId"] = failure.dataSourceId; evidence["operation"] = failure.operation; evidence["capability"] = failure.capability.name
                    Triple("SOURCE_ACCESS", failure.code, failure.message)
                }
                is SourceResultMaterializationException -> Triple("MATERIALIZE_RESULT", "EXECUTABLE_SOURCE_RESULT_FAILED", "source result cannot be materialized")
                is SourceGuaranteeException -> {
                    evidence["sourceSqlState"] = failure.sqlState; evidence["sourceFailureType"] = failure.failureType
                    Triple("VERIFY_SOURCE", failure.code, "compiled source guarantee failed")
                }
                else -> {
                    log.error("OlapEngine [Fatal]: executable 执行链崩溃", failure)
                    if (activeStage == "REPORT_SOURCE_COMPLETION") Triple(activeStage, "SOURCE_COMPLETION_REPORT_FAILED", "source completed but its completion evidence could not be reported")
                    else Triple(activeStage, "EXECUTABLE_SQL_EXECUTION_FAILED", "Engine executable execution failed")
                }
            }
            throw EngineExecutionException(stage, code, message, failure, evidence, compiled, (failure as? SourceExecutionException)?.failure,
                sourceReadEvidence ?: (failure as? SourceExecutionException)?.readEvidence, sourceExecutionStarted, context.stageTimes)
        }
    }
    private fun executionEvidence(query: CompiledQuery?, lowering: ExecutableLoweringResult?, context: OlapQueryContext): Map<String, Any?> = buildMap {
        put("stageNanos", context.stageTimes)
        query?.let { put("requestId", it.requestId); put("catalogId", it.catalogId); put("stageAnalyses", it.stageAnalyses); put("resolvedSources", it.resolvedSources) }
        lowering?.let { put("sql", it.sql); put("dataSourceId", it.dataSourceId); put("dialect", it.dialect); put("aliasMapping", it.aliasMapping);
            put("aliasDisplayNames", it.aliasDisplayNames); put("sourceRequirements", it.sourceRequirements); put("relationChecks", it.relationChecks) }
    }
}
