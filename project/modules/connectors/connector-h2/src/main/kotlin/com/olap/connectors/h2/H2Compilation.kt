package com.olap.connectors.h2

import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.connector.FiniteDecimalTypes
import com.olap.prototype.connector.NativeAggregateKeys
import com.olap.prototype.connector.StandardJdbcTypes
import com.olap.prototype.contract.*
import com.olap.prototype.expression.NativeScalarFunction
import com.olap.prototype.expression.NativeAggregateBehavior
import com.olap.prototype.metric.MetricValueContract
import org.apache.calcite.plan.Strong
import org.apache.calcite.rel.type.*
import org.apache.calcite.rex.RexNode
import org.apache.calcite.rex.RexOver
import org.apache.calcite.rex.RexBuilder
import org.apache.calcite.rex.RexCall
import org.apache.calcite.rel.core.Aggregate
import org.apache.calcite.rel.core.AggregateCall
import org.apache.calcite.jdbc.JavaTypeFactoryImpl
import org.apache.calcite.sql.*
import org.apache.calcite.sql.`fun`.*
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.type.*
import org.apache.calcite.tools.RelBuilder
import java.sql.Types
import java.util.function.Supplier

internal class H2Compilation : CompilationSupport {
    override val nativeScalarFunctions: List<NativeScalarFunction> = emptyList()
    override fun nativeScalarExpression(builder: RexBuilder, function: NativeScalarFunction, call: RexCall): RexNode = call
    override fun nativeScalarCall(function: String, arguments: List<CompilationSupport.NativeArgument>): SqlNode = throw IllegalArgumentException("H2 connector does not declare native scalar function: $function")
    override fun nativeAggregate(kind: SqlKind): SqlAggFunction = when (kind) {
        SqlKind.SUM -> SqlStdOperatorTable.SUM
        SqlKind.AVG -> SqlStdOperatorTable.AVG
        SqlKind.STDDEV_SAMP -> SqlBasicAggFunction.create(SqlKind.STDDEV_SAMP, { binding ->
            require(!FiniteDecimalTypes.isFinite(binding.getOperandType(0))) { "H2 finite decimal deviation has no verified native mapping" }
            ReturnTypes.DOUBLE_NULLABLE.inferReturnType(binding)
        }, OperandTypes.NUMERIC)
        SqlKind.MIN -> SqlStdOperatorTable.MIN
        SqlKind.MAX -> SqlStdOperatorTable.MAX
        SqlKind.COUNT -> SqlStdOperatorTable.COUNT
        SqlKind.ARRAY_AGG -> SqlLibraryOperators.ARRAY_AGG
        else -> throw IllegalArgumentException("unsupported H2 native aggregate: $kind")
    }
    // H2 2.2.224 在参数化 CTE 经派生表多次引用时可能丢失绑定值；正式 SQL 使用类型化字面量。
    override val parameterSubmission = CompilationSupport.ParameterSubmission.TYPED_LITERALS
    override fun matchesNativeResult(expected: NativeValueType, observed: JdbcColumnType): Boolean = StandardJdbcTypes.matchesResult(expected, observed)
    override val typeSystem: RelDataTypeSystem = H2NativeTypes()
    override val dialect: SqlDialect get() = H2Dialect.create()
    override val maximumDeclaredDecimalPrecision: Int = 100000
    override fun supports(capability: CompilationSupport.Capability): Boolean = capability in setOf(CompilationSupport.Capability.COLLECTION_RESULTS,
        CompilationSupport.Capability.NUMERIC_FAILURE, CompilationSupport.Capability.NUMERIC_ROUNDING, CompilationSupport.Capability.SAMPLE_STDDEV)
    override fun aggregateSql(owner: Aggregate, call: AggregateCall, arguments: List<SqlNode>, expression: SqlNode): SqlNode = expression
    override fun aggregateBehavior(owner: Aggregate, call: AggregateCall): NativeAggregateBehavior? {
        val kind = call.aggregation.kind
        if (kind !in setOf(SqlKind.SUM, SqlKind.AVG)) return null
        require(call.aggregation == nativeAggregate(kind) && call.argList.size == 1 && !call.isDistinct) { "H2 requires its actual scalar aggregate mapping" }
        val input = owner.input.rowType.fieldList[call.argList.single()].type
        if (FiniteDecimalTypes.isFinite(input)) return null
        if (kind == SqlKind.AVG) return meanBehavior(input, call.type)
        // VARCHAR 经数据库 CompareMode 比较；仅在实际分组同时保留精确二进制身份时准入文本键。
        if (!NativeAggregateKeys.preservesExactIdentity(owner, this)) return null
        val factory = owner.cluster.typeFactory
        require(SqlTypeUtil.equalSansNullability(factory, typeSystem.deriveSumType(factory, input), call.type)) { "H2 SUM result differs from its actual native type" }
        // H2 2.2.224 AggregateDataDefault SUM 使用 ValueBigint.add 的溢出检查，
        // 或 ValueNumeric.add 无 MathContext 的 BigDecimal 累加；最后 convertTo 仍核实际返回容量。
        // AggregateDataCount 的 long 自增没有溢出检查，因此 COUNT 保持未声明。
        return if (SqlTypeUtil.isExactNumeric(input) && SqlTypeUtil.isExactNumeric(call.type)) NativeAggregateBehavior.ExactOrFail
            else if (SqlTypeUtil.isApproximateNumeric(call.type)) NativeAggregateBehavior.NativeFloating else null
    }
    override fun aggregateBehavior(over: RexOver): NativeAggregateBehavior? {
        if (over.aggOperator.kind != SqlKind.AVG) return null
        require(over.aggOperator == nativeAggregate(SqlKind.AVG) && over.operands.size == 1 && !over.isDistinct) { "H2 requires its actual scalar window AVG mapping" }
        if (FiniteDecimalTypes.isFinite(over.operands.single().type)) return null
        return meanBehavior(over.operands.single().type, over.type)
    }
    private fun meanBehavior(input: RelDataType, result: RelDataType): NativeAggregateBehavior {
        val factory = JavaTypeFactoryImpl(typeSystem)
        val expected = typeSystem.deriveAvgAggType(factory, input)
        require(SqlTypeUtil.equalSansNullability(factory, expected, result)) { "H2 AVG result differs from its actual native type" }
        // H2 2.2.224 的普通、分区及有序窗口共用 AggregateDataAvg。
        // NUMERIC 以 BigDecimal 无中间 MathContext 累加，计数后仅向原生 scale 做一次 HALF_DOWN 除法。
        return if (result.sqlTypeName == SqlTypeName.DECIMAL && input.sqlTypeName in setOf(SqlTypeName.BIGINT, SqlTypeName.DECIMAL))
            NativeAggregateBehavior.QuantizedMean(MetricValueContract.Rounding.HALF_DOWN)
        else if (SqlTypeUtil.isApproximateNumeric(result)) NativeAggregateBehavior.NativeFloating
        else throw UnsupportedOperationException("H2 AVG numerical behavior is not mapped for the actual types")
    }
    override val division: SqlOperator = SqlStdOperatorTable.DIVIDE
    override fun sortCollection(builder: RelBuilder, value: RexNode, descending: Boolean, nullsFirst: Boolean): RexNode = throw UnsupportedOperationException("H2 connector has no verified collection ordering mapping")
    override fun collectionText(builder: RelBuilder, value: RexNode, delimiter: String, nullReplacement: String?): RexNode = throw UnsupportedOperationException("H2 connector has no verified collection text mapping")
    override fun jdbcType(factory: RelDataTypeFactory, column: JdbcColumnType): RelDataType {
        if (column.typeName.equals("DECFLOAT", ignoreCase = true)) {
            require(column.jdbcType in setOf(Types.NUMERIC, Types.DECIMAL)) { "H2 DECFLOAT requires its actual numeric JDBC type" }
            return FiniteDecimalTypes.create(requireNotNull(column.nullable) { "source nullability must be investigated before Calcite binding" })
        }
        return StandardJdbcTypes.resolve(factory, column)
    }
    override fun textIdentityEquals(builder: RelBuilder, left: RexNode, right: RexNode): RexNode = builder.equals(codeBytes(builder, left), codeBytes(builder, right))
    override fun textIdentityKey(builder: RelBuilder, value: RexNode): RexNode = codeBytes(builder, value)
    override fun expandCollection(outputType: RelDataType, tableAlias: String): CompilationSupport.CollectionExpansion =
        throw UnsupportedOperationException("H2 connector has no verified typed collection expansion mapping")
    private fun codeBytes(builder: RelBuilder, value: RexNode): RexNode {
        val text = builder.typeFactory.createTypeWithNullability(builder.typeFactory.createSqlType(SqlTypeName.VARCHAR), value.type.isNullable)
        val hex = character(builder, "RAWTOHEX", value, text)
        val binary = builder.typeFactory.createTypeWithNullability(builder.typeFactory.createSqlType(SqlTypeName.VARBINARY), hex.type.isNullable)
        return character(builder, "STRINGTOUTF8", hex, binary)
    }
    private fun character(builder: RelBuilder, name: String, value: RexNode, type: RelDataType): RexNode = builder.rexBuilder.makeCall(type,
        object : SqlFunction(name, SqlKind.OTHER_FUNCTION, ReturnTypes.explicit(type), null, OperandTypes.CHARACTER, SqlFunctionCategory.STRING) {
            override fun getStrongPolicyInference(): Supplier<Strong.Policy> = Supplier { Strong.Policy.ANY }
        }, listOf(value))
    override fun numericFailure(value: SqlNode, type: RelDataType, position: SqlParserPos): SqlNode = SqlStdOperatorTable.CONCAT.createCall(position,
        SqlLiteral.createCharString("numeric contract violation", position), SqlStdOperatorTable.CAST.createCall(position, value, SqlDataTypeSpec(SqlBasicTypeNameSpec(SqlTypeName.VARCHAR, 64, position), position)))
    override fun parameter(type: RelDataType, value: Any?): JdbcParameter {
        require(!FiniteDecimalTypes.isFinite(type)) { "H2 finite decimal parameters have no verified native mapping" }
        return JdbcParameter.of(type.sqlTypeName.jdbcOrdinal, type.sqlTypeName.name, type.precision, type.scale,
            requireNotNull(value) { "SQL NULL must remain a typed literal, not a bound parameter" })
    }
    override fun requireNativeCast(target: RelDataType) {
        require(!FiniteDecimalTypes.isFinite(target)) { "H2 finite decimal CAST has no verified exact native mapping" }
    }
    override fun tablePath(catalog: String?, schema: String?, table: String): List<String> {
        require(!schema.isNullOrBlank() && table.isNotBlank()) { "exact JDBC coordinate is required" }
        return listOf(schema, table)
    }
}
