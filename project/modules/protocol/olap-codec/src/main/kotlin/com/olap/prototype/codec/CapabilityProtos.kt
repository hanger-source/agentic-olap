package com.olap.prototype.codec

import com.google.protobuf.Any as ProtoAny
import com.olap.prototype.dsl.transport.CapabilityCodec

/** 每次调用只接受操作注册的正式消息；此处只路由，不解释领域规则。 */
class CapabilityProtos(private val maximumRequestBytes: Int, private val maximumInputDepth: Int) : CapabilityCodec {
    init { require(maximumRequestBytes > 0 && maximumInputDepth > 0) }
    override fun encodeRequest(operationId: String, request: Any): ByteArray = when {
        GovernanceCapabilityCodec.supports(operationId) -> GovernanceCapabilityCodec.encodeRequest(operationId, request, maximumRequestBytes)
        QueryCapabilityCodec.supports(operationId) -> QueryCapabilityCodec.encodeRequest(operationId, request, maximumRequestBytes)
        else -> throw IllegalArgumentException("unregistered capability: $operationId")
    }
    override fun decodeResult(operationId: String, bytes: ByteArray): Any = when {
        GovernanceCapabilityCodec.supports(operationId) -> GovernanceCapabilityCodec.decodeResult(operationId, bytes)
        QueryCapabilityCodec.supports(operationId) -> QueryCapabilityCodec.decodeResult(operationId, bytes)
        else -> throw IllegalArgumentException("unregistered capability: $operationId")
    }

    fun decodeRequest(operation: String, message: ProtoAny): Any = when {
        GovernanceCapabilityCodec.supports(operation) -> GovernanceCapabilityCodec.decodeRequest(operation, message, maximumRequestBytes, maximumInputDepth)
        QueryCapabilityCodec.supports(operation) -> QueryCapabilityCodec.decodeRequest(operation, message, maximumRequestBytes, maximumInputDepth)
        else -> throw IllegalArgumentException("unregistered capability: $operation")
    }

    fun encodeResult(operation: String, result: Any): ProtoAny = when {
        GovernanceCapabilityCodec.supports(operation) -> GovernanceCapabilityCodec.encodeResult(operation, result)
        QueryCapabilityCodec.supports(operation) -> QueryCapabilityCodec.encodeResult(operation, result)
        else -> throw IllegalArgumentException("unregistered capability: $operation")
    }
}
