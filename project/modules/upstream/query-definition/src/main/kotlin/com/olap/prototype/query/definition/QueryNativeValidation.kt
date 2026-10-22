package com.olap.prototype.query.definition

import com.olap.prototype.connector.ConnectorRegistry
import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.InputContractException
import com.olap.prototype.engine.EngineExecutionException
import com.olap.prototype.engine.catalog.ExecutableCatalog
import com.olap.prototype.engine.compiler.CompiledQuery
import com.olap.prototype.engine.compiler.OlapSemanticCompiler
import com.olap.prototype.engine.context.OlapQueryContext
import com.olap.prototype.engine.executable.*
import com.olap.prototype.query.QueryStageAnalysis
import com.olap.prototype.query.contract.*

/** 只使用去定位的冻结目录与正式编译器；校验不取得业务来源访问凭据。 */
object QueryNativeValidation {
    private val log = System.getLogger(QueryNativeValidation::class.java.name)

    fun validate(intent: QueryIntent, capability: QueryCapabilityView, connectors: ConnectorRegistry) {
        val computation = intent.computation
        try {
            val catalog = capability.nativeDefinitions.catalog
            try { PublishedInstanceContract.require(catalog, capability) }
            catch (failure: IllegalArgumentException) { throw ProducerContractException(requireNotNull(failure.message), failure) }
            val request = ExecutableQueryRequest(ExecutableQueryRequest.FORMAT_VERSION, "query-validation", catalog.manifest.catalogId, computation)
            val context = OlapQueryContext(request.requestId)
            val started = System.nanoTime()
            try {
                PublishedQueryContract.authorize(intent.formatVersion, intent.publication, computation, capability)
            } catch (failure: InputContractException) {
                throw failure
            } catch (failure: RuntimeException) {
                throw EngineExecutionException("AUTHORIZE_INPUT", "EXECUTABLE_QUERY_NOT_PUBLISHED", "Published query rejected: ${failure.message}", failure)
            } finally { context.recordStageTime("COMPILE_AUTHORIZE", started) }
            OlapSemanticCompiler(CalciteRelCompiler(connectors)).compile(catalog, request, context, object : ExecutableExecutionObserver {
                override fun bindingCompleted(input: ExecutableInputScope) {}
                override fun stageAnalysisCompleted(analyses: List<QueryStageAnalysis.Analysis>) {}
                override fun sourceResolutionCompleted(sources: List<CompiledQuery.SourceEvidence>) {}
                override fun loweringCompleted(loweringResult: ExecutableLoweringResult) {}
            })
        } catch (failure: EngineExecutionException) {
            throw ProducerContractException("query is invalid at ${failure.stage}: ${failure.message}", failure)
        } catch (failure: RuntimeException) {
            throw failure
        } catch (failure: Exception) {
            log.log(System.Logger.Level.ERROR, "Frozen publication compilation input decoding failed", failure)
            throw ProducerContractException("frozen publication cannot provide a valid native compilation input", failure)
        }
    }
}
