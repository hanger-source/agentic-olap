package com.olap.prototype.connector

import com.olap.prototype.api.ResultFrame
import com.olap.prototype.warehouse.contract.SourceFailureEvidence
import com.olap.prototype.warehouse.contract.SourceReadBasis
import com.olap.prototype.warehouse.contract.SourceRequestEvidence
import com.olap.prototype.warehouse.contract.SourceExecutionState
import com.olap.prototype.warehouse.contract.SourceReadEvidence

data class SourceExecutionResult(val frame: ResultFrame, val readBasis: SourceReadBasis, val request: SourceRequestEvidence) {
    init { require(request.execution == SourceExecutionState.SUCCEEDED) { "a completed source result requires its actual successful source outcome" } }
}

class SourceExecutionException(val failure: SourceFailureEvidence, cause: Throwable?, val readEvidence: SourceReadEvidence?) : RuntimeException(failure.safeMessage, cause) {
    init {
        require(readEvidence == null || readEvidence.binding == failure.binding && readEvidence.request == failure.request) {
            "source read and failure evidence must describe the same execution"
        }
    }
}
