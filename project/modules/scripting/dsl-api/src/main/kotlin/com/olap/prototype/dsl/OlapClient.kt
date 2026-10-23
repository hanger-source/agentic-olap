package com.olap.prototype.dsl

import com.olap.prototype.dsl.transport.*

/** 仅持有正式能力端口，不向脚本暴露凭据、元数据库或 worker。 */
class OlapClient internal constructor(private val calls: CapabilityCalls, private val currentCaller: () -> Caller,
    val temporaryDirectory: java.nio.file.Path) {
    init { require(temporaryDirectory.isAbsolute) { "script temporary directory must be absolute" } }
    val caller: Caller get() = currentCaller()
    val governance = GovernanceClient(calls)
    val queries = QueryClient(calls)
    val semantics = SemanticClient(calls)
    val results = ResultClient(calls)
    val sources = SourceClient(calls)
    /** 立即输出真实注册回执的快照；末表达式不承担输出，也不能输出任意 JVM 对象。 */
    fun output(response: Any) { currentCaller(); calls.output(response) }
    fun output(value: ScriptValue) { currentCaller(); calls.outputValue(value) }
    fun output(reference: com.olap.prototype.query.contract.QueryReference): Unit = output(ScriptValue.QueryReferenceValue(reference))
    fun output(reference: com.olap.prototype.query.contract.QueryResultReference): Unit = output(ScriptValue.ResultReferenceValue(reference))
    fun output(reference: com.olap.prototype.calculation.MetricRef): Unit = output(ScriptValue.MetricReferenceValue(reference))
    fun output(reference: com.olap.prototype.calculation.MetricInstanceRef): Unit = output(ScriptValue.MetricInstanceReferenceValue(reference))
    fun output(reference: com.olap.prototype.member.MemberRef): Unit = output(ScriptValue.MemberReferenceValue(reference))
    fun output(reference: com.olap.prototype.event.EventRef): Unit = output(ScriptValue.EventReferenceValue(reference))
    fun output(reference: com.olap.prototype.query.ScopedInputContract.DimensionRef): Unit = output(ScriptValue.DimensionReferenceValue(reference))
    fun output(reference: com.olap.prototype.warehouse.contract.SourceBindingRef): Unit = output(ScriptValue.SourceReferenceValue(reference))
    fun output(reference: com.olap.prototype.connector.ConnectorRef): Unit = output(ScriptValue.ConnectorReferenceValue(reference))
}

object OlapClients {
    private val evaluationEnvironment = ThreadLocal<OlapEnvironment>()

    fun connect(transport: CapabilityTransport, codec: CapabilityCodec, caller: () -> Caller,
        temporaryDirectory: java.nio.file.Path, output: (ScriptValue) -> Unit): OlapClient =
        OlapClient(CapabilityCalls(transport, codec, output), caller, temporaryDirectory)

    /** 应用只在真正求值期间进入作用域；后台线程不继承本次环境或调用权限。 */
    fun bindEvaluation(environment: OlapEnvironment): AutoCloseable {
        check(evaluationEnvironment.get() == null) { "an OLAP evaluation environment is already bound to this thread" }
        val owner = Thread.currentThread()
        evaluationEnvironment.set(environment)
        return AutoCloseable {
            check(Thread.currentThread() === owner) { "the OLAP evaluation environment must be released by its owning thread" }
            check(evaluationEnvironment.get() === environment) { "the OLAP evaluation environment differs from its owning scope" }
            evaluationEnvironment.remove()
        }
    }

    internal fun currentEnvironment(): OlapEnvironment = checkNotNull(evaluationEnvironment.get()) {
        "OLAP properties are available only on the current active evaluation thread"
    }
}

/** 正式顶层属性由应用在本次求值线程绑定；客户端仍属于同一个会话。 */
val olap: OlapClient get() = OlapClients.currentEnvironment().olap

/** 每次访问读取当前执行的不可变快照；保存得到的对象继续保留原快照。 */
val parameters: ScriptArguments get() = OlapClients.currentEnvironment().parameters

/** 应用提供会话客户端与当前执行参数；语言模块不依赖这个装配接口。 */
interface OlapEnvironment {
    val olap: OlapClient
    val parameters: ScriptArguments
}

/** 当前请求主体的只读信息；权限始终由服务持有的认证上下文决定。 */
data class Caller(val subject: String) {
    init { require(subject.isNotBlank()) { "authenticated caller subject is required" } }
}
