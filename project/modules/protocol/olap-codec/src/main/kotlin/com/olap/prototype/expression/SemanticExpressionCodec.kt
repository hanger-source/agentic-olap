package com.olap.prototype.expression

import com.olap.prototype.codec.SemanticProto
import com.olap.prototype.codec.ProtoBytes

/** 表达式存储字节采用统一正式模型格式；不接受作者 JSON 或自由 SQL。 */
object SemanticExpressionCodec {
    fun parse(encoded: String, label: String): SemanticExpression {
        require(encoded.isNotBlank()) { "$label 必须有正式模型编码" }
        return SemanticProto.fromProto(ProtoBytes.decode(ProtoBytes.stored(encoded), com.olap.prototype.wire.semantic.SemanticExpression.getDefaultInstance()))
    }
    fun write(expression: SemanticExpression): String = ProtoBytes.storage(SemanticProto.toProto(expression))
    fun logicUnit(id: String, version: Long): SemanticExpression.Reference = SemanticExpression.Reference(SemanticExpression.ReferenceKind.LOGIC_UNIT, id, version)
    fun and(expressions: List<SemanticExpression>): SemanticExpression {
        require(expressions.isNotEmpty()) { "AND expressions 不能为空" }
        return expressions.singleOrNull() ?: SemanticExpression.Operation(SemanticExpression.Operator.AND, expressions)
    }
}
