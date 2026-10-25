package com.olap.prototype.expression

import com.olap.prototype.api.ResultValueType
import com.olap.prototype.calculation.Calculation
import com.olap.prototype.contract.NativeValueType
import com.olap.prototype.metric.MetricValueContract
import com.olap.prototype.query.ScalarValueType
import com.olap.prototype.query.SetOutputContract
import com.olap.prototype.time.TemporalWindow
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.type.SqlTypeUtil
import org.apache.calcite.tools.RelBuilder
import java.math.BigDecimal

/** 原生类型的描述、容量及值形状，不决定来源或planner生命周期。 */
object SemanticNativeTypes {
    fun scalarParameterType(builder: RelBuilder, value: Calculation.Scalar): RelDataType = when (value.scalarType) {
        ScalarValueType.NUMBER -> builder.rexBuilder.makeExactLiteral(value.jdbcValue() as BigDecimal).type
        ScalarValueType.TEXT, ScalarValueType.ENUM -> builder.typeFactory.createSqlType(SqlTypeName.VARCHAR, SemanticCalciteTypes.textParameterCapacity(value.value))
        ScalarValueType.BOOLEAN -> builder.typeFactory.createSqlType(SqlTypeName.BOOLEAN)
        ScalarValueType.DATE -> builder.typeFactory.createSqlType(SqlTypeName.DATE)
        ScalarValueType.LOCAL_TIMESTAMP -> builder.typeFactory.createSqlType(SqlTypeName.TIMESTAMP, 9)
        ScalarValueType.OFFSET_TIMESTAMP -> builder.typeFactory.createSqlType(SqlTypeName.TIMESTAMP_TZ, builder.typeFactory.typeSystem.getMaxPrecision(SqlTypeName.TIMESTAMP_TZ))
    }

    private fun requireDetermined(name: SqlTypeName) {
        require(name !in setOf(SqlTypeName.ANY, SqlTypeName.OTHER, SqlTypeName.NULL)) { "native source type must be determined before publication" }
    }

    fun describe(type: RelDataType): NativeValueType {
        val name = type.sqlTypeName
        requireDetermined(name)
        return NativeValueType(name.name, type.precision.takeIf { name.allowsPrec() && it >= 0 },
            type.scale.takeIf { name.allowsScale() && it >= 0 }, type.isNullable,
            type.componentType?.let(::describe), if (type.isStruct) type.fieldList.map { describe(it.type) } else null)
    }

    fun resolve(factory: RelDataTypeFactory, type: NativeValueType): RelDataType {
        val name = SqlTypeName.valueOf(type.name)
        val precision = type.precision
        val scale = type.scale
        requireDetermined(name)
        val value = when (name) {
            SqlTypeName.ARRAY -> factory.createArrayType(resolve(factory, requireNotNull(type.elementType)), -1)
            SqlTypeName.MULTISET -> factory.createMultisetType(resolve(factory, requireNotNull(type.elementType)), -1)
            SqlTypeName.ROW -> factory.builder().apply {
                requireNotNull(type.fields).forEachIndexed { index, field -> add("field$index", resolve(factory, field)) }
            }.build()
            else -> when {
                scale != null -> factory.createSqlType(name, requireNotNull(precision), scale)
                precision != null -> factory.createSqlType(name, precision)
                else -> factory.createSqlType(name)
            }
        }
        return factory.createTypeWithNullability(value, type.nullable)
    }

    /** 原生类型合并必须容纳所有已有值；后端精度上限不能悄悄截短公共类型。 */
    fun commonType(factory: RelDataTypeFactory, inputs: List<RelDataType>, context: String): RelDataType {
        val common = factory.leastRestrictive(inputs)
        require(common != null && inputs.filter { it.sqlTypeName != SqlTypeName.NULL }.all { preservesCapacity(it, common) }) {
            "$context has no lossless common native type: $inputs"
        }
        return common
    }

    /** CASE/COALESCE 的分类标量遵循原生定型；数值叶子不得因共同定型缩减容量。 */
    fun commonAlternativeType(factory: RelDataTypeFactory, inputs: List<RelDataType>, context: String): RelDataType {
        val common = factory.leastRestrictive(inputs)
        require(common != null && inputs.all { preservesAlternativeCapacity(it, common) }) {
            "$context has no lossless common numeric representation: $inputs"
        }
        return common
    }

    private fun preservesAlternativeCapacity(input: RelDataType, output: RelDataType): Boolean = when {
        input.sqlTypeName == SqlTypeName.NULL -> true
        SqlTypeUtil.isNumeric(input) -> preservesCapacity(input, output)
        input.isStruct -> output.isStruct && input.fieldCount == output.fieldCount &&
            input.fieldList.indices.all { preservesAlternativeCapacity(input.fieldList[it].type, output.fieldList[it].type) }
        input.componentType != null -> output.componentType?.let { preservesAlternativeCapacity(requireNotNull(input.componentType), it) } == true
        else -> true
    }

    fun preservesCapacity(input: RelDataType, output: RelDataType): Boolean {
        if (SqlTypeUtil.isExactNumeric(input) && SqlTypeUtil.isExactNumeric(output))
            return output.scale >= input.scale && output.precision - output.scale >= input.precision - input.scale
        if (SqlTypeUtil.isApproximateNumeric(input) && SqlTypeUtil.isApproximateNumeric(output))
            return output.precision >= input.precision
        if (input.sqlTypeName != output.sqlTypeName) return false
        if (SqlTypeUtil.isCharacter(input) || SqlTypeUtil.isBinary(input))
            return output.precision < 0 || input.precision >= 0 && output.precision >= input.precision
        if (input.sqlTypeName in setOf(SqlTypeName.ARRAY, SqlTypeName.MULTISET)) {
            val first = input.componentType ?: return false
            val second = output.componentType ?: return false
            return preservesCapacity(first, second)
        }
        if (input.isStruct && (!output.isStruct || input.fieldCount != output.fieldCount ||
                input.fieldList.indices.any { !preservesCapacity(input.fieldList[it].type, output.fieldList[it].type) })) return false
        return input.precision <= output.precision && input.scale <= output.scale
    }

    fun internalAlias(reserved: MutableSet<String>, prefix: String): String {
        var index = 0
        while (true) { val alias = "$prefix${index++}"; if (reserved.add(alias)) return alias }
    }

    fun requireTemporalType(type: RelDataType, declaration: TemporalWindow.ValueType) {
        val expected = when (declaration) {
            TemporalWindow.ValueType.DATE -> SqlTypeName.DATE
            TemporalWindow.ValueType.LOCAL_TIMESTAMP -> SqlTypeName.TIMESTAMP
            TemporalWindow.ValueType.OFFSET_TIMESTAMP -> SqlTypeName.TIMESTAMP_TZ
        }
        require(type.sqlTypeName == expected) { "source range differs from its declared temporal slot" }
    }

    fun requireSetType(type: RelDataType, shape: SetOutputContract.Shape) {
        when (shape) {
            is SetOutputContract.Array -> requireSetType(requireNotNull(type.componentType) { "set array slot requires an array native value" }, shape.element)
            is SetOutputContract.Row -> {
                require(type.isStruct && type.fieldCount == shape.fields.size) { "set row slot requires its exact ordinal width" }
                shape.fields.forEachIndexed { index, field -> requireSetType(type.fieldList[index].type, field) }
            }
            else -> {
                require(!type.isStruct && type.componentType == null) { "scalar set slot cannot hide a container" }
                when (shape) {
                    is SetOutputContract.Quantity -> require(MetricValueContract.resultFits(numericResult(type), shape.valueContract.result)) {
                        "set native value exceeds its declared common output capacity"
                    }
                    is SetOutputContract.Property -> requireScalarType(type, shape.valueType)
                    is SetOutputContract.Scalar -> requireScalarType(type, ScalarValueType.valueOf(shape.valueType.name))
                    is SetOutputContract.Dimension, is SetOutputContract.DimensionSlot, is SetOutputContract.MemberDimension -> Unit
                    is SetOutputContract.Array, is SetOutputContract.Row -> error("container set shapes must be checked recursively")
                }
            }
        }
    }

    private fun numericResult(type: RelDataType): MetricValueContract.Result = when (type.sqlTypeName) {
        SqlTypeName.TINYINT -> MetricValueContract.Integer(8)
        SqlTypeName.SMALLINT -> MetricValueContract.Integer(16)
        SqlTypeName.INTEGER -> MetricValueContract.Integer(32)
        SqlTypeName.BIGINT -> MetricValueContract.Integer(64)
        SqlTypeName.DECIMAL -> if (SemanticCalciteTypeSystem.wideInteger(type)) MetricValueContract.Integer(128)
            else MetricValueContract.Decimal(type.precision, type.scale, MetricValueContract.Rounding.NONE)
        SqlTypeName.REAL -> MetricValueContract.BinaryFloat(32)
        SqlTypeName.FLOAT, SqlTypeName.DOUBLE -> MetricValueContract.BinaryFloat(64)
        else -> throw IllegalArgumentException("set quantity slot requires a numeric native type")
    }

    fun requireScalarType(type: RelDataType, valueType: ScalarValueType) {
        require(!type.isStruct && type.componentType == null) { "scalar output cannot hide a container" }
        val matches = when (valueType) {
            ScalarValueType.NUMBER -> SqlTypeUtil.isNumeric(type)
            ScalarValueType.TEXT, ScalarValueType.ENUM -> SqlTypeUtil.inCharFamily(type)
            ScalarValueType.BOOLEAN -> type.sqlTypeName == SqlTypeName.BOOLEAN
            ScalarValueType.DATE -> type.sqlTypeName == SqlTypeName.DATE
            ScalarValueType.LOCAL_TIMESTAMP -> type.sqlTypeName == SqlTypeName.TIMESTAMP
            ScalarValueType.OFFSET_TIMESTAMP -> type.sqlTypeName == SqlTypeName.TIMESTAMP_TZ
        }
        require(matches) { "declared scalar has a different native type" }
    }

    fun describe(type: ResultValueType.Scalar): NativeValueType = NativeValueType(type.name, type.precision, type.scale, type.nullable)

    fun resultType(type: RelDataType): ResultValueType {
        type.componentType?.let { return ResultValueType.Array(resultType(it), type.isNullable) }
        if (type.isStruct) return ResultValueType.Row(type.fieldList.map { ResultValueType.Field(it.name, resultType(it.type)) }, type.isNullable)
        require(type.keyType == null) { "MAP output requires an explicit published value contract" }
        return ResultValueType.Scalar(type.sqlTypeName.name,
            type.precision.takeUnless { it == RelDataType.PRECISION_NOT_SPECIFIED },
            type.scale.takeUnless { it == RelDataType.SCALE_NOT_SPECIFIED }, type.isNullable)
    }
}
