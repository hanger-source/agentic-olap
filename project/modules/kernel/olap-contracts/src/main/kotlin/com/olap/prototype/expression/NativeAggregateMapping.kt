package com.olap.prototype.expression

import com.olap.prototype.contract.NativeValueType
import com.olap.prototype.metric.MetricValueContract

/** 连接器针对精确输入类型的原生映射声明；不证明数据源可用或本次业务查询可执行。 */
sealed interface NativeAggregateMapping {
    data class Mapped(val resultType: NativeValueType, val aggregationRounding: MetricValueContract.Rounding) : NativeAggregateMapping
    data class Unavailable(val code: String, val message: String) : NativeAggregateMapping
}
