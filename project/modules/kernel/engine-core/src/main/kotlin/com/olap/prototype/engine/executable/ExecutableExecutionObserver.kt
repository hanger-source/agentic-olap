package com.olap.prototype.engine.executable

import com.olap.prototype.engine.compiler.CompiledQuery
import com.olap.prototype.query.QueryStageAnalysis

/** 报告已完成的真实阶段；不拥有应用QueryRun生命周期。 */
interface ExecutableExecutionObserver {
    fun bindingCompleted(input: ExecutableInputScope)
    fun stageAnalysisCompleted(analyses: List<QueryStageAnalysis.Analysis>)
    fun sourceResolutionCompleted(sources: List<CompiledQuery.SourceEvidence>)
    fun loweringCompleted(loweringResult: ExecutableLoweringResult)
}
