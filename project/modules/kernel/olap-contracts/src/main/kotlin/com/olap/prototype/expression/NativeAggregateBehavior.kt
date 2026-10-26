package com.olap.prototype.expression

import com.olap.prototype.metric.MetricValueContract

/** 实际聚合节点的原生数值行为；结果容量取自 AggregateCall 或 RexOver 的真实类型。 */
sealed interface NativeAggregateBehavior {
    /**
     * 针对实际 Aggregate owner／call 精确执行 SUM／COUNT，不做中间量化；
     * 容量不足必须失败，不能静默溢出或回绕。同时保留 owner 分组键的精确值身份、
     * NULL 及袋人口，原生文本比较不得折叠不同身份。插件必须核验本次实际键的适用域。
     */
    data object ExactOrFail : NativeAggregateBehavior
    /**
     * 对本节点参与值精确累加和计数，除法没有其他信息损失，
     * 仅最后按 rounding 量化到本节点原生 DECIMAL 类型的 scale。
     * 不证明输入数量没有既有误差，也不承诺任意第二次量化与一次最终量化等价。
     */
    data class QuantizedMean(val rounding: MetricValueContract.Rounding) : NativeAggregateBehavior

    /** 原生浮点聚合；没有业务误差上界，结果表示不能提升其算术保证。 */
    data object NativeFloating : NativeAggregateBehavior
}
