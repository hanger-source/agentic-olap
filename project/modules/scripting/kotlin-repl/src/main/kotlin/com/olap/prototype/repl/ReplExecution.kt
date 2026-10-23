package com.olap.prototype.repl

/** [sourceName] 是作者显示名称；REPL 另为每次提交分配唯一的编译源码身份。 */
data class ReplSource(val source: String, val sourceName: String) {
    init {
        require(sourceName.isNotBlank()) { "a snippet display name is required" }
    }
}

enum class ReplStatus { COMPLETED, COMPILE_FAILED, RUNTIME_FAILED, NOT_EVALUATED }

/**
 * AVAILABLE 只表示可以继续提交代码；不保证失败片段中的声明已经初始化，
 * 也不表示任何先前副作用被撤销。RESET_REQUIRED 时不再编译或求值后续代码。
 */
enum class ReplContinuation { AVAILABLE, RESET_REQUIRED }

enum class ReplPhase { COMPILE, EVALUATE }
enum class ReplSeverity { DEBUG, INFO, WARNING, ERROR, FATAL }

/** 包含唯一片段身份，故后续片段调用历史函数时仍能定位到原始提交。 */
data class ReplLocation(
    val snippetId: String,
    val sourceName: String,
    val line: Int? = null,
    val column: Int? = null,
    val endLine: Int? = null,
    val endColumn: Int? = null,
)

data class ReplDiagnostic(
    val code: String,
    val phase: ReplPhase,
    val severity: ReplSeverity,
    val message: String,
    val location: ReplLocation,
    val exception: Throwable? = null,
)

/**
 * 不返回、展开或序列化末表达式。语言求值历史只留在 REPL 会话中；
 * 宿主的显式输出、授权与正式操作结果由宿主自己管理。
 */
data class ReplExecutionResult(
    val snippetId: String,
    val sourceName: String,
    val status: ReplStatus,
    val diagnostics: List<ReplDiagnostic>,
    val failure: Throwable?,
    val compilationNanos: Long,
    val evaluationNanos: Long,
    val continuation: ReplContinuation,
)
