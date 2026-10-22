package com.olap.connectors.h2

import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.connector.StandardJdbcTypes
import com.olap.prototype.contract.*
import com.olap.prototype.expression.NativeScalarFunction
import com.olap.prototype.metric.MetricValueContract
import org.apache.calcite.rel.type.*
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.*
import org.apache.calcite.sql.`fun`.*
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.type.*
import org.apache.calcite.tools.RelBuilder

internal class H2Compilation : CompilationSupport {
    override val nativeScalarFunctions: List<NativeScalarFunction> = emptyList()
    override fun nativeScalarCall(function: String, arguments: List<CompilationSupport.NativeArgument>): SqlNode = throw IllegalArgumentException("H2 connector does not declare native scalar function: $function")
    override fun nativeAggregate(kind: SqlKind): SqlAggFunction = when (kind) {
        SqlKind.SUM -> SqlStdOperatorTable.SUM
        SqlKind.AVG -> SqlStdOperatorTable.AVG
        SqlKind.STDDEV_SAMP -> SqlBasicAggFunction.create(SqlKind.STDDEV_SAMP, ReturnTypes.DOUBLE_NULLABLE, OperandTypes.NUMERIC)
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
    override val decimalAverageRounding = MetricValueContract.Rounding.HALF_DOWN
    override val division: SqlOperator = SqlStdOperatorTable.DIVIDE
    override fun sortCollection(builder: RelBuilder, value: RexNode, descending: Boolean, nullsFirst: Boolean): RexNode = throw UnsupportedOperationException("H2 connector has no verified collection ordering mapping")
    override fun collectionText(builder: RelBuilder, value: RexNode, delimiter: String, nullReplacement: String?): RexNode = throw UnsupportedOperationException("H2 connector has no verified collection text mapping")
    override fun jdbcType(factory: RelDataTypeFactory, column: JdbcColumnType): RelDataType = StandardJdbcTypes.resolve(factory, column)
    override fun textIdentityEquals(builder: RelBuilder, left: RexNode, right: RexNode): RexNode = builder.equals(codeBytes(builder, left), codeBytes(builder, right))
    override fun expandCollection(collection: SqlNode, outputType: RelDataType, tableAlias: String): CompilationSupport.CollectionExpansion =
        throw UnsupportedOperationException("H2 connector has no verified typed collection expansion mapping")
    private fun codeBytes(builder: RelBuilder, value: RexNode): RexNode {
        val text = builder.typeFactory.createTypeWithNullability(builder.typeFactory.createSqlType(SqlTypeName.VARCHAR), value.type.isNullable)
        val hex = character(builder, "RAWTOHEX", value, text)
        val binary = builder.typeFactory.createTypeWithNullability(builder.typeFactory.createSqlType(SqlTypeName.VARBINARY), hex.type.isNullable)
        return character(builder, "STRINGTOUTF8", hex, binary)
    }
    private fun character(builder: RelBuilder, name: String, value: RexNode, type: RelDataType): RexNode = builder.rexBuilder.makeCall(type,
        SqlFunction(name, SqlKind.OTHER_FUNCTION, ReturnTypes.explicit(type), null, OperandTypes.CHARACTER, SqlFunctionCategory.STRING), listOf(value))
    override fun numericFailure(value: SqlNode, type: RelDataType, position: SqlParserPos): SqlNode = SqlStdOperatorTable.CONCAT.createCall(position,
        SqlLiteral.createCharString("numeric contract violation", position), SqlStdOperatorTable.CAST.createCall(position, value, SqlDataTypeSpec(SqlBasicTypeNameSpec(SqlTypeName.VARCHAR, 64, position), position)))
    override fun parameter(type: RelDataType, value: Any?): JdbcParameter = JdbcParameter.of(type.sqlTypeName.jdbcOrdinal, type.sqlTypeName.name, type.precision, type.scale,
        requireNotNull(value) { "SQL NULL must remain a typed literal, not a bound parameter" })
    override fun requireNativeCast(target: RelDataType) = Unit
    override fun tablePath(catalog: String?, schema: String?, table: String): List<String> {
        require(!schema.isNullOrBlank() && table.isNotBlank()) { "exact JDBC coordinate is required" }
        return listOf(schema, table)
    }
}
