package com.olap.prototype.codec

import com.google.protobuf.Any
import com.olap.prototype.dsl.ScriptValue
import com.olap.prototype.dsl.transport.CapabilityRegistry
import com.olap.prototype.wire.query.ScriptValue as WValue
import com.olap.prototype.wire.query.ScriptValues as WValues
import com.olap.prototype.wire.query.ScriptObject as WObject
import com.olap.prototype.wire.query.RegisteredReply
import com.olap.prototype.wire.query.ScriptReference

/** 普通值的有限输出合同；不会把任意对象图解释为语义模型。 */
object QueryScriptProto {
    fun toProto(v: ScriptValue, maximumBytes: Int, maximumDepth: Int): WValue = encode(v, maximumBytes, maximumDepth, 0)
    private fun encode(v: ScriptValue, maximumBytes: Int, maximumDepth: Int, depth: Int): WValue {
        require(depth <= maximumDepth) { "script output exceeds structural depth budget" }
        return WValue.newBuilder().apply { when (v) {
        ScriptValue.Null -> nullValue = com.olap.prototype.wire.semantic.Empty.getDefaultInstance()
        is ScriptValue.BooleanValue -> boolean = v.value
        is ScriptValue.Text -> text = v.value
        is ScriptValue.DecimalValue -> decimal = v.value.toString()
        is ScriptValue.ArrayValue -> array = WValues.newBuilder().addAllValues(v.values.map { encode(it, maximumBytes, maximumDepth, depth + 1) }).build()
        is ScriptValue.ObjectValue -> `object` = WObject.newBuilder().putAllValues(v.values.mapValues { encode(it.value, maximumBytes, maximumDepth, depth + 1) }).build()
        is ScriptValue.ReferenceValue -> reference = reference(v)
        is ScriptValue.RegisteredModel -> {
            CapabilityRegistry.require(v.operationId)
            val bytes = v.content
            CapabilityProtos(maximumBytes, maximumBytes, maximumDepth).decodeResult(v.operationId, bytes)
            val actual = ProtoBytes.decode(bytes, Any.getDefaultInstance(), maximumBytes, maximumDepth)
            registeredReply = RegisteredReply.newBuilder().setOperationId(v.operationId).setContent(actual).build()
        }
        } }.build()
    }
    private fun reference(v: ScriptValue.ReferenceValue): ScriptReference = ScriptReference.newBuilder().apply { when(v) {
        is ScriptValue.QueryReferenceValue -> setQuery(QueryProto.toProto(v.reference))
        is ScriptValue.ResultReferenceValue -> setResult(QueryProto.toProto(v.reference))
        is ScriptValue.MetricReferenceValue -> setMetric(SemanticProto.toProto(v.reference))
        is ScriptValue.MetricInstanceReferenceValue -> setMetricInstance(SemanticProto.toProto(v.reference))
        is ScriptValue.MemberReferenceValue -> setMember(SemanticProto.toProto(v.reference))
        is ScriptValue.EventReferenceValue -> setEvent(SemanticProto.toProto(v.reference))
        is ScriptValue.DimensionReferenceValue -> setDimension(SemanticProto.toProto(v.reference))
        is ScriptValue.SourceReferenceValue -> setSource(QuerySourceProto.toProto(v.reference))
        is ScriptValue.ConnectorReferenceValue -> setConnector(SemanticProto.toProto(v.reference))
    } }.build()
}
