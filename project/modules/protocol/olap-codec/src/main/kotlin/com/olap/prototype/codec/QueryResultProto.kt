package com.olap.prototype.codec

import com.olap.prototype.api.ResultValueType
import com.olap.prototype.engine.executable.ExecutableBusinessResult
import com.olap.prototype.engine.executable.ExecutableBusinessResult.Value
import com.olap.prototype.wire.query.ResultValueType as WType
import com.olap.prototype.wire.query.ResultScalarType as WScalarType
import com.olap.prototype.wire.query.ResultArrayType as WArrayType
import com.olap.prototype.wire.query.ResultRowType as WRowType
import com.olap.prototype.wire.query.ResultField as WField
import com.olap.prototype.wire.query.BusinessResult as WResult
import com.olap.prototype.wire.query.BusinessColumn as WColumn
import com.olap.prototype.wire.query.BusinessColumns as WColumns
import com.olap.prototype.wire.query.BusinessValue as WValue
import com.olap.prototype.wire.query.BusinessValues as WValues
import com.olap.prototype.wire.query.BusinessScalar as WScalar

/** 已完成结果按正式列类型与无损值传输；不会再次从JDBC类型或列名推导语义。 */
object QueryResultProto {
    fun toProto(v: ResultValueType): WType = WType.newBuilder().apply { when (v) {
        is ResultValueType.Scalar -> scalar = WScalarType.newBuilder().setName(v.name).setNullable(v.nullable)
            .apply { v.precision?.let(::setPrecision); v.scale?.let(::setScale) }.build()
        is ResultValueType.Array -> array = WArrayType.newBuilder().setElement(toProto(v.element)).setNullable(v.nullable).build()
        is ResultValueType.Row -> row = WRowType.newBuilder().setNullable(v.nullable).addAllFields(v.fields.map {
            WField.newBuilder().setName(it.name).setType(toProto(it.type)).build()
        }).build()
    } }.build()
    fun fromProto(v: WType): ResultValueType = when (v.kindCase) {
        WType.KindCase.SCALAR -> v.scalar.let { ResultValueType.Scalar(it.name, if (it.hasPrecision()) it.precision else null, if (it.hasScale()) it.scale else null, it.nullable) }
        WType.KindCase.ARRAY -> v.array.let { require(it.hasElement()); ResultValueType.Array(fromProto(it.element), it.nullable) }
        WType.KindCase.ROW -> ResultValueType.Row(v.row.fieldsList.map { require(it.hasType()); ResultValueType.Field(it.name, fromProto(it.type)) }, v.row.nullable)
        else -> error("result type requires one explicit variant")
    }
    fun toProto(v: ExecutableBusinessResult.Column): WColumn = WColumn.newBuilder().setColumnId(v.columnId).setSymbol(SemanticProto.toProto(v.symbol))
        .setType(toProto(v.type)).apply { v.displayName?.let(::setDisplayName) }.build()
    fun fromProto(v: WColumn): ExecutableBusinessResult.Column { require(v.hasSymbol() && v.hasType()); return ExecutableBusinessResult.Column(v.columnId,
        SemanticProto.fromProto(v.symbol), fromProto(v.type), if (v.hasDisplayName()) v.displayName else null) }
    fun toProto(v: Value): WValue = WValue.newBuilder().apply { when (v) {
        is ExecutableBusinessResult.NullValue -> nullValue = com.olap.prototype.wire.semantic.Empty.getDefaultInstance()
        is ExecutableBusinessResult.ScalarValue -> scalar = WScalar.newBuilder().setType(v.type.name).setValue(v.value).build()
        is ExecutableBusinessResult.ArrayValue -> array = values(v.value)
        is ExecutableBusinessResult.RowValue -> row = values(v.value)
    } }.build()
    fun fromProto(v: WValue): Value = when (v.kindCase) {
        WValue.KindCase.NULL_VALUE -> ExecutableBusinessResult.NullValue()
        WValue.KindCase.SCALAR -> ExecutableBusinessResult.ScalarValue(ExecutableBusinessResult.ValueKind.valueOf(v.scalar.type), v.scalar.value)
        WValue.KindCase.ARRAY -> ExecutableBusinessResult.ArrayValue(v.array.valuesList.map(::fromProto))
        WValue.KindCase.ROW -> ExecutableBusinessResult.RowValue(v.row.valuesList.map(::fromProto))
        else -> error("business result value requires one explicit variant")
    }
    private fun values(v: List<Value>): WValues = WValues.newBuilder().addAllValues(v.map(::toProto)).build()
    fun toProto(v: ExecutableBusinessResult): WResult = WResult.newBuilder().addAllInputColumns(v.inputColumns.map(::toProto))
        .putAllScopeInputColumns(v.scopeInputColumns.mapValues { WColumns.newBuilder().addAllValues(it.value.map(::toProto)).build() })
        .addAllColumns(v.columns.map(::toProto)).addAllSourceSymbols(v.sourceSymbols.map(SemanticProto::toProto)).addAllRows(v.rows.map(::values)).build()
    fun fromProto(v: WResult) = ExecutableBusinessResult(v.inputColumnsList.map(::fromProto), v.scopeInputColumnsMap.mapValues { it.value.valuesList.map(::fromProto) },
        v.columnsList.map(::fromProto), v.sourceSymbolsList.map(SemanticProto::fromProto), v.rowsList.map { it.valuesList.map(::fromProto) })
}
