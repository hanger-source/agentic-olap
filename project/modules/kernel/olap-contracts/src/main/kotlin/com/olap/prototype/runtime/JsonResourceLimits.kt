package com.olap.prototype.runtime

/** 部署明确给出的 JSON 预算；节点含容器与标量，字节不含输运封套。 */
data class JsonResourceLimits(val requestBytes: Int, val responseBytes: Int, val inputDepth: Int, val inputNodes: Int, val inputFileBytes: Int, val inputFiles: Int) {
    init { require(listOf(requestBytes, responseBytes, inputDepth, inputNodes, inputFileBytes, inputFiles).all { it > 0 }) { "explicit positive JSON resource limits are required" } }
}

/** 预算失败不改变业务人口，也不截断为成功结果。 */
class RuntimeResourceLimitException(val code: String) : RuntimeException(code)
