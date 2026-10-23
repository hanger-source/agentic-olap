package com.olap.prototype.dsl.transport

import java.lang.reflect.Type
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import kotlin.reflect.jvm.kotlinFunction
import kotlin.reflect.full.valueParameters

/** 进程边界专用端口；主体、权限和期限由宿主父请求持有。 */
fun interface CapabilityTransport {
    fun call(operationId: String, input: ByteArray): ByteArray
}

/** 统一模型编码的注入点，不进入公开业务模型构造接口。 */
interface CapabilityCodec {
    fun encodeRequest(operationId: String, request: Any): ByteArray
    fun decodeResult(operationId: String, bytes: ByteArray): Any
}

/** 宿主已经脱敏的正式失败；脚本异常不会吞掉调用位置和领域诊断。 */
data class CapabilityFailure(val operationId: String, val callId: String, val code: String, val stage: String,
    val message: String, val fieldPath: List<String>, val diagnosticId: String?)
class CapabilityFailureException(val failure: CapabilityFailure) : RuntimeException(failure.message)

internal class CapabilityCalls(private val transport: CapabilityTransport, private val codec: CapabilityCodec,
    private val emit: (com.olap.prototype.dsl.ScriptValue) -> Unit) {
    private data class Returned(val operationId: String, val bytes: ByteArray)
    private class Identity(value: Any, queue: ReferenceQueue<Any>? = null) : WeakReference<Any>(value, queue) {
        private val hash = System.identityHashCode(value)
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean = this === other || other is Identity && get()?.let { it === other.get() } == true
    }
    private val collected = ReferenceQueue<Any>()
    private val responses = HashMap<Identity, Returned>()
    private fun reclaim() {
        while (true) responses.remove((collected.poll() ?: return) as Identity)
    }
    fun <T : Any> call(owner: Class<*>, method: String, input: Any): T {
        val operation = CapabilityRegistry.method(owner, method)
        val output = transport.call(operation.operationId, codec.encodeRequest(operation.operationId, input))
        @Suppress("UNCHECKED_CAST")
        val decoded = codec.decodeResult(operation.operationId, output) as T
        reclaim()
        responses[Identity(decoded, collected)] = Returned(operation.operationId, output.copyOf())
        return decoded
    }
    fun output(value: Any) {
        reclaim()
        val operation = responses[Identity(value)] ?: throw IllegalArgumentException("only an actual typed capability response can be emitted as a registered model")
        // 真实返回模型不统一假定深不可变；在对象仍可达时保留原正式回执字节，不重新解释已被调用方修改的对象。
        emit(com.olap.prototype.dsl.ScriptValue.RegisteredModel(operation.operationId, operation.bytes))
    }
    fun outputValue(value: com.olap.prototype.dsl.ScriptValue) = emit(value)
}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Capability(val operationId: String, val permission: String, val effect: String)

data class CapabilityContract(val operationId: String, val owner: String, val method: String, val inputType: Type, val outputType: Type,
    val permission: String, val effect: String, val kotlinInput: String, val kotlinOutput: String)

/** 实际公开函数就是发现与通信的合同来源。 */
object CapabilityRegistry {
    private val owners = listOf(com.olap.prototype.dsl.GovernanceClient::class.java, com.olap.prototype.dsl.QueryClient::class.java,
        com.olap.prototype.dsl.SemanticClient::class.java, com.olap.prototype.dsl.ResultClient::class.java, com.olap.prototype.dsl.SourceClient::class.java)
    private val contracts by lazy {
        owners.flatMap { owner -> owner.methods.mapNotNull { method ->
            method.getAnnotation(Capability::class.java)?.let { annotation ->
                require(method.parameterCount == 1) { "capability requires exactly one complete typed input" }
                val function = requireNotNull(method.kotlinFunction) { "capability must have a public Kotlin signature" }
                CapabilityContract(annotation.operationId, owner.simpleName, method.name, method.genericParameterTypes.single(),
                    method.genericReturnType, annotation.permission, annotation.effect,
                    function.valueParameters.single().type.toString(), function.returnType.toString())
            }
        } }.also { values -> check(values.map { it.operationId }.distinct().size == values.size) { "duplicate capability identity" } }
    }
    fun all(): List<CapabilityContract> = contracts.sortedBy { it.operationId }
    fun require(operationId: String): CapabilityContract = contracts.singleOrNull { it.operationId == operationId }
        ?: throw IllegalArgumentException("unregistered capability: $operationId")
    internal fun method(owner: Class<*>, method: String): CapabilityContract = contracts.single { it.owner == owner.simpleName && it.method == method }
}
