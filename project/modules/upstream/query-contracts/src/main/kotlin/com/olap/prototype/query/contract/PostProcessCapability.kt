package com.olap.prototype.query.contract

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.contract.ProducerContracts
import com.olap.prototype.query.PostOperationContract
import com.olap.prototype.util.OlapJsonUtils

data class PostProcessCapability(val ruleId: String, val ruleType: String, val ruleDisplayName: String, val ruleConfigJson: String,
    val sortOrder: Int, val reasonCode: String?, val reasonMessage: String?, val producerStage: String?, val targetRef: String?, val targetVersionRef: String?,
    val available: Boolean, val blocking: Boolean, val pageBlocking: Boolean, val executableBlocking: Boolean) {
    init {
        ProducerContracts.requireText(ruleId, "ruleId"); PostOperationContract.requireType(ruleType, "ruleType")
        ProducerContracts.requireText(ruleDisplayName, "ruleDisplayName"); ProducerContracts.requireText(ruleConfigJson, "ruleConfigJson")
        val root = try { OlapJsonUtils.mapper().readTree(ruleConfigJson) }
        catch (failure: Exception) { throw ProducerContractException("ruleConfigJson must be a JSON object", failure) }
        if (root == null || !root.isObject) throw ProducerContractException("ruleConfigJson must be a JSON object")
        if (!available) {
            ProducerContracts.requireText(reasonCode, "reasonCode"); ProducerContracts.requireText(reasonMessage, "reasonMessage")
            ProducerContracts.requireText(producerStage, "producerStage"); ProducerContracts.requireText(targetRef, "targetRef"); ProducerContracts.requireText(targetVersionRef, "targetVersionRef")
        }
    }
}
