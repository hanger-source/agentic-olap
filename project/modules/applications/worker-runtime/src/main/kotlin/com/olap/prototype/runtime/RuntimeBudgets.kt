package com.olap.prototype.runtime

import com.fasterxml.jackson.databind.JsonNode
import com.olap.prototype.warehouse.ResultMaterializationLimits

class RuntimeBudgets(val configuration: JsonNode) {
    private fun integer(name: String): Int {
        val value = configuration.required(name)
        require(value.isIntegralNumber && value.canConvertToInt() && value.intValue() > 0) { "positive exact $name is required" }
        return value.intValue()
    }
    private fun long(name: String): Long {
        val value = configuration.required(name)
        require(value.isIntegralNumber && value.canConvertToLong() && value.longValue() > 0) { "positive exact $name is required" }
        return value.longValue()
    }
    val io = JsonResourceLimits(integer("requestBytes"), integer("responseBytes"),
        integer("inputDepth"), integer("inputNodes"), integer("inputFileBytes"), integer("inputFiles"))
    val frameBytes = integer("pendingOutputBytes")
    val catalogEntries = integer("catalogEntriesPerWorker")
    val catalogBytes = long("catalogBytes")
    val sqlBytes = integer("sqlBytes")
    val parameters = integer("parameters")
    val heapMiB = integer("workerJvmHeapMiB")
    val nativeMemoryBytes = long("nativeMemoryBytes")
    val requestMillis: Long = configuration.required("requestSeconds").decimalValue().movePointRight(3).longValueExact().also {
        require(it > 0) { "positive exact request deadline is required" }
    }
    val materialization = ResultMaterializationLimits(long("resultRows"), long("resultValues"), long("resultBytes"), integer("resultDepth"))

    fun requireWorkerHeap() {
        require(Runtime.getRuntime().maxMemory() <= heapMiB.toLong() * 1024 * 1024) { "worker JVM heap exceeds declared budget" }
    }
}
