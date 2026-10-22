package com.olap.prototype.server

import com.fasterxml.jackson.annotation.JsonProperty
import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.contract.NativeValueType
import com.olap.prototype.query.ResultAggregation

/** 空请求列出获准声明；聚合查询必须指明一个精确插件及完整输入类型。 */
internal data class ConnectorCapabilitiesRequest(val aggregate: Aggregate? = null) {
    data class Aggregate(val connectorRef: ConnectorRef, val aggregation: ResultAggregation.Kind,
        val inputType: NativeValueType, @param:JsonProperty(required = true) val grouped: Boolean)
}
