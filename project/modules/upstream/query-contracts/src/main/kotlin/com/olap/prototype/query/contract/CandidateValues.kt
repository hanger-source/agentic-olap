package com.olap.prototype.query.contract

import com.olap.prototype.calculation.Calculation
import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.query.QueryComputation
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.warehouse.contract.SourceBindingRef
import java.time.Instant

/** 候选来自发布定义；分页和目标均显式，不接受来源SQL或连接。 */
data class CandidateRequest(val publication: QueryCapabilityRequest, val capabilityHash: String,
    val target: CandidateTarget, val offset: Int, val limit: Int) {
    init { require(capabilityHash.matches(Regex("[0-9a-f]{64}")) && offset in 0..1_000_000 && limit in 1..200) { "exact candidate capability and bounded page are required" } }
}
sealed interface CandidateTarget {
    data class DimensionProvider(val filterRefId: String, val levelCode: String) : CandidateTarget
    data class MemberOutput(val call: QueryComputation.Members) : CandidateTarget
}
data class CandidateSource(val binding: SourceBindingRef, val connectorRef: ConnectorRef)
data class CandidateResolution(val coordinate: QueryCapabilityRequest, val catalogHash: String, val capabilityHash: String,
    val queryHash: String, val sources: List<CandidateSource>, val valueType: ScalarValueType)
data class CandidateValues(val coordinate: QueryCapabilityRequest, val catalogHash: String, val capabilityHash: String,
    val queryHash: String, val target: CandidateTarget, val valueType: ScalarValueType, val values: List<Calculation.Scalar>,
    val offset: Int, val nextOffset: Int?, val observedAt: Instant, val scope: String)
