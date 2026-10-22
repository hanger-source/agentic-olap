package com.olap.prototype.warehouse

/** 完整结果的行、值节点、标量载荷字节与嵌套深度预算。 */
data class ResultMaterializationLimits(val rows: Long, val values: Long, val bytes: Long, val depth: Int) {
    init { require(rows in 1..Int.MAX_VALUE.toLong() && values > 0 && bytes > 0 && depth > 0) { "explicit positive result materialization limits are required" } }
}
