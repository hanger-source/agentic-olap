package com.olap.prototype.runtime

import com.olap.prototype.codec.CapabilityProtos
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.codec.QueryProto
import com.olap.prototype.codec.ScriptOutputRepresentationException
import com.olap.prototype.dsl.Caller
import com.olap.prototype.dsl.OlapClient
import com.olap.prototype.dsl.OlapClients
import com.olap.prototype.dsl.OlapEnvironment
import com.olap.prototype.dsl.ScriptArguments
import com.olap.prototype.protocol.ScriptRequest
import java.nio.file.Path

/** 客户端属于会话；顶层属性只在当前求值线程读取本次参数及正式调用绑定。 */
internal class OlapSessionEnvironment(private val peer: ScriptProtocolPeer, codec: CapabilityProtos,
    sessionDirectory: Path) : OlapEnvironment {
    private data class Execution(val requestId: String, val caller: Caller, val parameters: ScriptArguments)
    private var current: Execution? = null
    private var sessionCaller: Caller? = null
    override val olap: OlapClient = OlapClients.connect(peer.transport, codec, { execution().caller }, sessionDirectory) { value ->
        peer.requireEvaluation()
        // 编码在动作发生时完成；后续片段不能改变已经发出的正式快照。
        val snapshot = try { ProtoBytes.pack(QueryProto.scriptValue(value)) }
        catch (failure: ScriptOutputRepresentationException) { throw failure }
        catch (failure: Exception) { throw ScriptEmissionEncodingException(failure) }
        peer.emit(snapshot)
    }
    override val parameters: ScriptArguments get() = execution().parameters

    fun bind(request: ScriptRequest, parameters: ScriptArguments) {
        check(current == null) { "a session execution is already bound" }
        val caller = Caller(request.callerSubject)
        require(sessionCaller == null || sessionCaller == caller) { "session caller cannot change" }
        sessionCaller = caller
        current = Execution(request.requestId, caller, parameters)
    }

    fun release() { current = null }

    private fun execution(): Execution {
        val requestId = peer.requireEvaluation()
        return checkNotNull(current) { "no active session execution is bound" }.also {
            check(it.requestId == requestId) { "session environment differs from its active execution" }
        }
    }
}

internal class ScriptEmissionEncodingException(cause: Throwable) :
    IllegalArgumentException("explicit output could not be encoded using its formal result contract", cause)
