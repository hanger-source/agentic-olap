package com.olap.prototype.engine.executable

import com.olap.prototype.query.PostOperationContract

/** 单作用域私有装配；完成后只交出只读输入。 */
internal class ScopeAssembly(private val mode: String?) {
    val inputs = linkedMapOf<String, ExecutableInputScope>()
    private var completed = false
    fun complete(root: ExecutableInputRoot, stages: List<PostOperationContract.ValidatedOperation>): ExecutableInputScope {
        check(!completed) { "scope binding has already completed" }
        completed = true
        return ExecutableInputScope(mode, root, emptyList(), inputs.toMap(), stages.toList())
    }
    fun callerInputs(): (String) -> ExecutableInputScope = { requireNotNull(inputs[it]) { "external local member input is not declared in the caller scope" } }
}
