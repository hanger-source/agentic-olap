package com.olap.prototype.codec

import com.olap.prototype.wire.governance.DiagnosticValue
import com.olap.prototype.wire.governance.DiagnosticValues
import com.olap.prototype.wire.semantic.Empty
import com.olap.prototype.connector.ConnectorRef
import com.olap.prototype.contract.NativeValueType
import com.olap.prototype.warehouse.contract.SourceBindingRef
import com.olap.prototype.engine.catalog.SourceBindingConflictException

/** 诊断中的受控异构字段；不编码语义计算或任意 JVM 对象。 */
internal fun diagnosticToProto(value: Any?): DiagnosticValue = DiagnosticValue.newBuilder().apply {
    when (value) {
        null -> setNullValue(Empty.getDefaultInstance())
        is String -> setText(value)
        is Enum<*> -> setText(value.name)
        is Int -> setInteger(value.toLong())
        is Short -> setInteger(value.toLong())
        is Long -> setInteger(value)
        is Boolean -> setBoolean(value)
        is ConnectorRef -> setConnector(SemanticProto.toProto(value))
        is SourceBindingRef -> setBinding(QuerySourceProto.toProto(value))
        is NativeValueType -> setNativeType(NativeValueTypeToProto(value))
        is SourceBindingConflictException.Input -> setConflictingInput(SourceBindingConflictExceptionInputToProto(value))
        is List<*> -> setValues(DiagnosticValues.newBuilder().addAllValues(value.map(::diagnosticToProto)))
        else -> error("diagnostic field has no declared protobuf representation: ${value.javaClass.name}")
    }
}.build()

internal fun diagnosticFromProto(value: DiagnosticValue): Any? = when (value.kindCase) {
    DiagnosticValue.KindCase.NULL_VALUE -> null
    DiagnosticValue.KindCase.TEXT -> value.text
    DiagnosticValue.KindCase.INTEGER -> value.integer
    DiagnosticValue.KindCase.BOOLEAN -> value.boolean
    DiagnosticValue.KindCase.CONNECTOR -> SemanticProto.fromProto(value.connector)
    DiagnosticValue.KindCase.BINDING -> QuerySourceProto.fromProto(value.binding)
    DiagnosticValue.KindCase.NATIVE_TYPE -> NativeValueTypeFromProto(value.nativeType)
    DiagnosticValue.KindCase.CONFLICTING_INPUT -> SourceBindingConflictExceptionInputFromProto(value.conflictingInput)
    DiagnosticValue.KindCase.VALUES -> value.values.valuesList.map(::diagnosticFromProto)
    DiagnosticValue.KindCase.KIND_NOT_SET -> error("diagnostic value variant is required")
}
