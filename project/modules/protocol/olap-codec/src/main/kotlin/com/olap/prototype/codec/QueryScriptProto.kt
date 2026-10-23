package com.olap.prototype.codec

import com.google.protobuf.Any
import com.olap.prototype.dsl.ScriptValue
import com.olap.prototype.dsl.transport.CapabilityRegistry
import com.olap.prototype.wire.query.ScriptValue as WValue
import com.olap.prototype.wire.query.ScriptValues as WValues
import com.olap.prototype.wire.query.ScriptObject as WObject
import com.olap.prototype.wire.query.RegisteredReply
import com.olap.prototype.wire.query.ScriptReference

/** 普通值只编码为正式输出消息；不会把任意对象图解释为语义模型。 */
object QueryScriptProto {
    fun toProto(v: ScriptValue): WValue = encode(v, emptyList())

    private fun encode(v: ScriptValue, path: List<String>): WValue {
        return WValue.newBuilder().apply { when (v) {
        ScriptValue.Null -> nullValue = com.olap.prototype.wire.semantic.Empty.getDefaultInstance()
        is ScriptValue.BooleanValue -> boolean = v.value
        is ScriptValue.Text -> text = unicode(v.value, path)
        is ScriptValue.DecimalValue -> decimal = v.value.toString()
        is ScriptValue.ArrayValue -> array = WValues.newBuilder().addAllValues(v.values.mapIndexed { index, child -> encode(child, path + index.toString()) }).build()
        is ScriptValue.ObjectValue -> `object` = WObject.newBuilder().apply {
            v.values.forEach { (key, child) ->
                // 非法键本身不能再次进入 Protobuf 诊断路径。
                putValues(unicode(key, path), encode(child, path + key))
            }
        }.build()
        is ScriptValue.ReferenceValue -> reference = reference(v)
        is ScriptValue.RegisteredModel -> {
            CapabilityRegistry.require(v.operationId)
            // 本次实际客户端返回值已完成正式类型解码；此处只读取其不可变官方封套。
            val actual = ProtoBytes.decodeResponse(v.content, Any.getDefaultInstance())
            registeredReply = RegisteredReply.newBuilder().setOperationId(unicode(v.operationId, path)).setContent(actual).build()
        }
        } }.build()
    }

    private fun unicode(text: String, path: List<String>): String = try { ProtoUnicode.requireText(text, path) }
        catch (failure: ProtoStringRepresentationException) { throw ScriptOutputRepresentationException(failure.fieldPath) }
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

class ScriptOutputRepresentationException(val fieldPath: List<String>) :
    IllegalArgumentException("ordinary script output contains an unpaired UTF-16 surrogate and cannot round-trip through UTF-8")
