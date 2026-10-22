package com.olap.prototype.query.contract

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.query.PostOperationContract

data class PostProcessCapability(val ruleId: String, val ruleType: String, val ruleDisplayName: String, val authorization: StageAuthorization,
    val sortOrder: Int, val reasonCode: String?, val reasonMessage: String?, val producerStage: String?, val targetRef: String?, val targetVersionRef: String?,
    val available: Boolean, val blocking: Boolean, val pageBlocking: Boolean, val executableBlocking: Boolean) {
    init {
        ProducerContracts.requireText(ruleId, "ruleId"); PostOperationContract.requireType(ruleType, "ruleType")
        ProducerContracts.requireText(ruleDisplayName, "ruleDisplayName")
        require(authorization.operationType == ruleType) { "stage authorization differs from rule identity" }
        if (!available) {
            ProducerContracts.requireText(reasonCode, "reasonCode"); ProducerContracts.requireText(reasonMessage, "reasonMessage")
            ProducerContracts.requireText(producerStage, "producerStage"); ProducerContracts.requireText(targetRef, "targetRef"); ProducerContracts.requireText(targetVersionRef, "targetVersionRef")
        }
    }
}
