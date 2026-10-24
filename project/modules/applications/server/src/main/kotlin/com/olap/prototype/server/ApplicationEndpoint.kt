package com.olap.prototype.server

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.protocol.CapabilityEffect
import com.olap.prototype.query.contract.QueryWorkerAcquisition

/** 本地应用方法直接返回正式结果；JSON 只在 HTTP 入口和交付边界存在。 */
interface ApplicationEndpoint {
    val authorizationPolicyHash: String
    fun operations(principal: ServerAuthorization.Principal): Map<String, OperationDescription>
    fun call(principal: ServerAuthorization.Principal, operation: String, payload: JsonNode, remainingMillis: Long): ApplicationResponse
}

data class OperationDescription(val readOnly: Boolean, val description: String, val payload: Any? = null)
data class ApplicationFailure(val stage: String, val code: String, val message: String, val details: Map<String, Any?> = emptyMap())
data class ApplicationResponse(val requestId: String, val success: Boolean, val value: Any? = null,
    val error: ApplicationFailure? = null, val effects: List<CapabilityEffect> = emptyList(),
    val timingNanos: Map<String, Long> = emptyMap(), val workers: List<QueryWorkerAcquisition> = emptyList())
