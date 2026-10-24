package com.olap.prototype.governance.contract

import com.olap.prototype.contract.ProducerContractException
import com.olap.prototype.query.contract.StageAuthorization

/** 场景刷新核对明确批准的部署合同，不把一次查询所需节点推导成授权政策。 */
class StageAuthorizationApprovalException(val scenarioId: String, val scenarioVersion: Long, val ruleType: String,
    val approved: StageAuthorization, val expected: StageAuthorization) : ProducerContractException(
    "approvedAuthorization must equal StageAuthorization.current(ruleType) in full; a subset or superset is not accepted") {
    val differences: Map<String, Any> = buildMap {
        if (approved.operationType != expected.operationType) put("operationType", mapOf("expected" to expected.operationType, "actual" to approved.operationType))
        fun <T : Enum<T>> sets(field: String, required: Set<T>, actual: Set<T>) {
            if (required != actual) put(field, mapOf("missing" to (required - actual).map { it.name }.sorted(),
                "unexpected" to (actual - required).map { it.name }.sorted()))
        }
        sets("joinTypes", expected.joinTypes, approved.joinTypes)
        sets("aggregations", expected.aggregations, approved.aggregations)
        sets("expressions.kinds", expected.expressions.kinds, approved.expressions.kinds)
        sets("expressions.operators", expected.expressions.operators, approved.expressions.operators)
        sets("expressions.functions", expected.expressions.functions, approved.expressions.functions)
        sets("expressions.castTypes", expected.expressions.castTypes, approved.expressions.castTypes)
        sets("expressions.literalTypes", expected.expressions.literalTypes, approved.expressions.literalTypes)
    }
}

class ScenarioPipelineRuleRefreshTargetException(val scenarioId: String, val scenarioVersion: Long, val ruleType: String,
    val existingRuleCount: Int) : ProducerContractException("contract refresh requires exactly one existing scenario rule of ruleType")

class GovernanceDraftRequiredException(val assetType: String, val objectId: String, val version: Long,
    val actualStatus: String?) : ProducerContractException("the exact governance version must exist and be DRAFT before accepting changes")
