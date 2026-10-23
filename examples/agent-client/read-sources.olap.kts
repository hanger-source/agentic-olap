import com.olap.prototype.source.contract.EmptyRequest

val originalParameters = parameters
val sourceSnapshot = olap.sources.list(EmptyRequest)

// 后续片段调用这个函数时，正式客户端使用那次执行的身份、期限与调用归属。
fun readCurrentSources() = olap.sources.list(EmptyRequest)

olap.output(sourceSnapshot)
