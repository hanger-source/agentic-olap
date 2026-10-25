package com.olap.prototype.expression

import com.olap.prototype.metric.MetricValueContract

/** 实际 AVG 节点的原生数值行为；原生结果容量取自 AggregateCall 或 RexOver 的真实类型。 */
sealed interface NativeAverageBehavior {
    /**
     * 对本节点参与值精确累加和计数，除法没有其他信息损失，
     * 仅最后按 rounding 量化到本节点原生 DECIMAL 类型的 scale。
     * 不证明输入数量没有既有误差，也不承诺任意第二次量化与一次最终量化等价。
     */
    data class QuantizedMean(val rounding: MetricValueContract.Rounding) : NativeAverageBehavior

    /** 原生浮点求均值；没有业务误差上界，结果表示不能提升其算术保证。 */
    data object NativeFloating : NativeAverageBehavior
}
