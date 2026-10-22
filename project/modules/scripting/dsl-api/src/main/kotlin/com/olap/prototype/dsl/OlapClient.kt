package com.olap.prototype.dsl

import com.olap.prototype.dsl.transport.*

/** 仅持有正式能力端口，不向脚本暴露凭据、元数据库或 worker。 */
class OlapClient internal constructor(private val calls: CapabilityCalls, val caller: Caller, val temporaryDirectory: java.nio.file.Path) {
    init { require(temporaryDirectory.isAbsolute) { "script temporary directory must be absolute" } }
    val governance = GovernanceClient(calls)
    val queries = QueryClient(calls)
    val semantics = SemanticClient(calls)
    val results = ResultClient(calls)
    val sources = SourceClient(calls)
    /** 输出本次正式调用真实返回的回执；不能接受任意 JVM 对象。 */
    fun output(response: Any): ScriptValue.RegisteredModel = calls.returnedOutput(response)
    fun output(reference: com.olap.prototype.query.contract.QueryReference) = ScriptValue.QueryReferenceValue(reference)
    fun output(reference: com.olap.prototype.query.contract.QueryResultReference) = ScriptValue.ResultReferenceValue(reference)
    fun output(reference: com.olap.prototype.calculation.MetricRef) = ScriptValue.MetricReferenceValue(reference)
    fun output(reference: com.olap.prototype.calculation.MetricInstanceRef) = ScriptValue.MetricInstanceReferenceValue(reference)
    fun output(reference: com.olap.prototype.member.MemberRef) = ScriptValue.MemberReferenceValue(reference)
    fun output(reference: com.olap.prototype.event.EventRef) = ScriptValue.EventReferenceValue(reference)
    fun output(reference: com.olap.prototype.query.ScopedInputContract.DimensionRef) = ScriptValue.DimensionReferenceValue(reference)
    fun output(reference: com.olap.prototype.warehouse.contract.SourceBindingRef) = ScriptValue.SourceReferenceValue(reference)
    fun output(reference: com.olap.prototype.connector.ConnectorRef) = ScriptValue.ConnectorReferenceValue(reference)
}

object OlapClients {
    fun connect(transport: CapabilityTransport, codec: CapabilityCodec, caller: Caller, temporaryDirectory: java.nio.file.Path): OlapClient =
        OlapClient(CapabilityCalls(transport, codec), caller, temporaryDirectory)
}

/** 当前请求主体的只读信息；权限始终由服务持有的认证上下文决定。 */
data class Caller(val subject: String) {
    init { require(subject.isNotBlank()) { "authenticated caller subject is required" } }
}
