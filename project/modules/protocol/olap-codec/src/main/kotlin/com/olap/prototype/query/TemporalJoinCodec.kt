package com.olap.prototype.query

import com.olap.prototype.codec.SemanticProto
import com.olap.prototype.codec.ProtoBytes
import com.olap.prototype.wire.semantic.TemporalConstraintValue

/** 非时态关联显式编码为 NULL；时态区间仍由正式构造器核验。 */
object TemporalJoinCodec {
    fun encode(value: TemporalJoinContract.Constraint?): String = ProtoBytes.storage(TemporalConstraintValue.newBuilder().apply {
        if (value == null) setNonTemporal(com.olap.prototype.wire.semantic.Empty.getDefaultInstance()) else setValue(SemanticProto.toProto(value))
    }.build())
    fun decode(encoded: String, field: String): TemporalJoinContract.Constraint? {
        require(encoded.isNotBlank()) { "$field 必须有正式模型编码" }
        val value = ProtoBytes.decode(ProtoBytes.stored(encoded), TemporalConstraintValue.getDefaultInstance())
        return when (value.kindCase) {
            TemporalConstraintValue.KindCase.VALUE -> SemanticProto.fromProto(value.value)
            TemporalConstraintValue.KindCase.NON_TEMPORAL -> null
            else -> error("temporal constraint choice is required")
        }
    }
}
