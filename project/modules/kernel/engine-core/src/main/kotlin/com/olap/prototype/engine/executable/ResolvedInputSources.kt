package com.olap.prototype.engine.executable

import java.util.Collections
import java.util.IdentityHashMap

/** 键是实际事件出现身份，不按定义或值相等合并。 */
class ResolvedInputSources(inputs: Map<ExecutableEventInput, ResolvedEventInput>) {
    private val inputs: Map<ExecutableEventInput, ResolvedEventInput> = Collections.unmodifiableMap(IdentityHashMap(inputs))
    fun require(input: ExecutableEventInput): ResolvedEventInput = requireNotNull(inputs[input]) { "event input is outside the completed source resolution" }
}
