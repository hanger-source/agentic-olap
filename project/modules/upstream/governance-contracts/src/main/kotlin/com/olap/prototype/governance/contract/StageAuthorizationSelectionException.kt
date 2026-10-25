package com.olap.prototype.governance.contract

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.query.contract.StageAuthorization

/** 创建和刷新只拒绝身份不符或超出当前支持域的选择，不补齐未选择的能力。 */
class StageAuthorizationSelectionException(val scenarioId: String, val scenarioVersion: Long, val ruleType: String,
    val field: String, val selected: StageAuthorization, val supported: StageAuthorization) : ProducerContractException(
    "stage authorization must select the target operation and only capabilities supported for that operation") {
    val unsupported: Map<String, List<String>> = selected.unsupportedBy(supported)
}

class ScenarioPipelineRuleRefreshTargetException(val scenarioId: String, val scenarioVersion: Long, val ruleType: String,
    val existingRuleCount: Int) : ProducerContractException("contract refresh requires exactly one existing scenario rule of ruleType")

class GovernanceDraftRequiredException(val assetType: String, val objectId: String, val version: Long,
    val actualStatus: String?) : ProducerContractException("the exact governance version must exist and be DRAFT before accepting changes")
