package com.olap.prototype.engine

import com.olap.prototype.engine.compiler.CompiledQuery
import java.util.Collections
import com.olap.prototype.warehouse.contract.SourceFailureEvidence
import com.olap.prototype.warehouse.contract.SourceReadEvidence

/** 阶段、代码及原生证据明确区分绑定、编译、源核验、执行和交付失败。 */
class EngineExecutionException(val stage: String, val code: String, override val message: String, cause: Throwable?,
    evidenceContext: Map<String, Any?> = emptyMap(), val compiledQuery: CompiledQuery? = null,
    val sourceFailure: SourceFailureEvidence? = null, val sourceReadEvidence: SourceReadEvidence? = null) : RuntimeException(message, cause) {
    val evidenceContext: Map<String, Any?> = Collections.unmodifiableMap(LinkedHashMap(evidenceContext))
    init { require(stage.isNotBlank() && code.isNotBlank()) { "engine failure stage and code are required" } }
}
