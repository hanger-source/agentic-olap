package com.olap.prototype.governance.contract

/** 本次冻结中同一精确位置出现不一致事实；路径只用于本次诊断。 */
class ReleaseClosureConflictException(
    val code: String,
    val coordinate: Map<String, Any>,
    paths: List<List<String>>,
) : IllegalArgumentException("release closure conflict: $code at $coordinate; paths=${paths.joinToString(" | ") { it.joinToString(" -> ") }}") {
    val paths: List<List<String>> = paths.map { java.util.List.copyOf(it) }
}
