package com.olap.prototype.engine.executable

import com.olap.prototype.api.OlapResult
import com.olap.prototype.engine.compiler.CompiledQuery
import com.olap.prototype.warehouse.contract.SourceReadEvidence

data class ExecutableExecutionResult(val result: OlapResult, val compiledQuery: CompiledQuery, val businessResult: ExecutableBusinessResult,
    val sourceReadEvidence: SourceReadEvidence)
