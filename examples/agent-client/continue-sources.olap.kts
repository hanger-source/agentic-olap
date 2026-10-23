import com.olap.prototype.dsl.ScriptValue

check(originalParameters.text("label") == "first")
check(parameters.text("label") == "second")

// 前段的正式对象仍是原快照；函数重新读取当前来源。两个输出分别保持自身含义。
val currentSources = readCurrentSources()
olap.output(sourceSnapshot)
olap.output(currentSources)
olap.output(ScriptValue.Text("原参数快照、当前参数和跨片段函数均可使用"))
