package com.olap.connectors.duckdb

import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.connector.AggregateSql
import com.olap.prototype.connector.FiniteDecimalTypes
import com.olap.prototype.connector.StandardJdbcTypes
import com.olap.prototype.contract.*
import com.olap.prototype.expression.NativeScalarFunction
import com.olap.prototype.expression.NativeAggregateBehavior
import com.olap.prototype.metric.MetricValueContract
import org.apache.calcite.plan.Strong
import org.apache.calcite.rel.type.*
import org.apache.calcite.rex.RexLambdaRef
import org.apache.calcite.rex.RexNode
import org.apache.calcite.rex.RexOver
import org.apache.calcite.rex.RexBuilder
import org.apache.calcite.rex.RexCall
import org.apache.calcite.rel.core.Aggregate
import org.apache.calcite.rel.core.AggregateCall
import org.apache.calcite.sql.*
import org.apache.calcite.sql.`fun`.*
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.type.*
import org.apache.calcite.tools.RelBuilder
import java.math.BigDecimal
import java.math.BigInteger
import java.sql.Types
import java.util.Locale
import java.util.function.Supplier

internal class DuckdbCompilation : CompilationSupport {
    override val nativeScalarFunctions: List<NativeScalarFunction> = emptyList()
    override fun nativeScalarExpression(builder: RexBuilder, function: NativeScalarFunction, call: RexCall): RexNode = call
    override fun nativeScalarCall(function: String, arguments: List<CompilationSupport.NativeArgument>): SqlNode = throw IllegalArgumentException("DuckDB connector does not declare native scalar function: $function")
    override fun nativeAggregate(kind: SqlKind): SqlAggFunction = when (kind) {
        SqlKind.SUM -> SqlStdOperatorTable.SUM
        SqlKind.AVG -> SqlStdOperatorTable.AVG
        SqlKind.STDDEV_SAMP -> SqlBasicAggFunction.create(SqlKind.STDDEV_SAMP, { binding ->
            require(!FiniteDecimalTypes.isFinite(binding.getOperandType(0))) { "DuckDB finite decimal deviation has no verified native mapping" }
            ReturnTypes.DOUBLE_NULLABLE.inferReturnType(binding)
        }, OperandTypes.NUMERIC)
        SqlKind.MIN -> SqlStdOperatorTable.MIN
        SqlKind.MAX -> SqlStdOperatorTable.MAX
        SqlKind.COUNT -> SqlStdOperatorTable.COUNT
        SqlKind.ARRAY_AGG -> SqlLibraryOperators.ARRAY_AGG
        else -> throw IllegalArgumentException("unsupported Duckdb native aggregate: $kind")
    }
    override val parameterSubmission = CompilationSupport.ParameterSubmission.BIND_PARAMETERS
    override fun matchesNativeResult(expected: NativeValueType, observed: JdbcColumnType): Boolean =
        expected.name == "DECIMAL" && expected.precision == 39 && expected.scale == 0 && observed.typeName.equals("HUGEINT", ignoreCase = true) || StandardJdbcTypes.matchesResult(expected, observed)
    override val typeSystem: RelDataTypeSystem = DuckdbNativeTypes()
    override val dialect: SqlDialect = OlapDuckDBSqlDialect
    override val maximumDeclaredDecimalPrecision: Int = 38
    override fun supports(capability: CompilationSupport.Capability): Boolean = capability in setOf(
        CompilationSupport.Capability.COLLECTION_RESULTS, CompilationSupport.Capability.COLLECTION_EXPANSION, CompilationSupport.Capability.COLLECTION_SORT,
        CompilationSupport.Capability.COLLECTION_TEXT, CompilationSupport.Capability.NUMERIC_FAILURE, CompilationSupport.Capability.NUMERIC_ROUNDING,
        CompilationSupport.Capability.SAMPLE_STDDEV, CompilationSupport.Capability.RECURSIVE_ANCESTORS, CompilationSupport.Capability.ELAPSED_TIMESTAMPDIFF,
        CompilationSupport.Capability.WIDE_INTEGER, CompilationSupport.Capability.TIMESTAMP_PARAMETER_NATIVE_CAST, CompilationSupport.Capability.ROUND_KEEPS_INPUT_PRECISION,
        CompilationSupport.Capability.CORRELATED_DERIVED_PROJECTION)
    override fun aggregateSql(owner: Aggregate, call: AggregateCall, arguments: List<SqlNode>, expression: SqlNode): AggregateSql =
        AggregateSql.Direct(expression)
    override fun aggregateBehavior(owner: Aggregate, call: AggregateCall): NativeAggregateBehavior? {
        // v1.5.6 SumState<hugeint_t>::Combine 使用未检查溢出的 operator+=；
        // HUGEINT 结果类型本身不足以声明所有 SUM 形态的 ExactOrFail，COUNT 也不据 BIGINT 标签推保证。
        if (call.aggregation.kind != SqlKind.AVG) return null
        require(call.aggregation == nativeAggregate(SqlKind.AVG) && call.argList.size == 1 && !call.isDistinct) { "DuckDB requires its actual scalar AVG mapping" }
        if (FiniteDecimalTypes.isFinite(owner.input.rowType.fieldList[call.argList.single()].type)) return null
        return meanBehavior(owner.input.rowType.fieldList[call.argList.single()].type, call.type)
    }
    override fun aggregateBehavior(over: RexOver): NativeAggregateBehavior? {
        if (over.aggOperator.kind != SqlKind.AVG) return null
        require(over.aggOperator == nativeAggregate(SqlKind.AVG) && over.operands.size == 1 && !over.isDistinct) { "DuckDB requires its actual scalar window AVG mapping" }
        if (FiniteDecimalTypes.isFinite(over.operands.single().type)) return null
        return meanBehavior(over.operands.single().type, over.type)
    }
    private fun meanBehavior(input: RelDataType, result: RelDataType): NativeAggregateBehavior {
        require(SqlTypeUtil.isNumeric(input) && result.sqlTypeName == SqlTypeName.DOUBLE) { "DuckDB AVG requires its actual DOUBLE result mapping" }
        return NativeAggregateBehavior.NativeFloating
    }
    override fun sortCollection(builder: RelBuilder, value: RexNode, descending: Boolean, nullsFirst: Boolean): RexNode = collectionCall(builder, "LIST_SORT", value.type,
        listOf(value, builder.literal(if (descending) "DESC" else "ASC"), builder.literal(if (nullsFirst) "NULLS FIRST" else "NULLS LAST")))
    override fun collectionText(builder: RelBuilder, value: RexNode, delimiter: String, nullReplacement: String?): RexNode {
        val input = if (nullReplacement == null) value else {
            val variable = RexLambdaRef(0, "__olap_element", requireNotNull(value.type.componentType))
            val body = builder.call(SqlStdOperatorTable.COALESCE, variable, builder.literal(nullReplacement))
            collectionCall(builder, "LIST_TRANSFORM", value.type, listOf(value, builder.rexBuilder.makeLambdaCall(body, listOf(variable))))
        }
        val type = builder.typeFactory.createTypeWithNullability(builder.typeFactory.createSqlType(SqlTypeName.VARCHAR), input.type.isNullable)
        val text = collectionCall(builder, "ARRAY_TO_STRING", type, listOf(input, builder.literal(delimiter)))
        return builder.call(SqlStdOperatorTable.CASE, builder.isNull(input), builder.rexBuilder.makeNullLiteral(type), builder.call(SqlStdOperatorTable.COALESCE, text, builder.literal("")))
    }
    private fun collectionCall(builder: RelBuilder, name: String, type: RelDataType, arguments: List<RexNode>): RexNode = builder.rexBuilder.makeCall(type,
        SqlFunction(name, SqlKind.OTHER_FUNCTION, ReturnTypes.explicit(type), null, OperandTypes.VARIADIC, SqlFunctionCategory.SYSTEM), arguments)
    override val division: SqlOperator get() = SqlBinaryOperator("/", SqlKind.DIVIDE, 60, true, { binding ->
        require((0 until binding.operandCount).none { FiniteDecimalTypes.isFinite(binding.getOperandType(it)) }) {
            "DuckDB finite decimal division has no verified exact native mapping"
        }
        ReturnTypes.DOUBLE_NULLABLE.inferReturnType(binding)
    }, null, OperandTypes.NUMERIC_NUMERIC)
    override fun jdbcType(factory: RelDataTypeFactory, column: JdbcColumnType): RelDataType {
        if (DuckdbSourceTypes.container(column.typeName)) return DuckdbSourceTypes.resolve(factory, column.typeName, requireNotNull(column.nullable))
        if (column.typeName.equals("HUGEINT", ignoreCase = true)) return factory.createTypeWithNullability(factory.createSqlType(SqlTypeName.DECIMAL, 39, 0), requireNotNull(column.nullable))
        val name = column.typeName.uppercase(Locale.ROOT)
        val jdbc = if (name in setOf("TIMESTAMP_NS", "TIMESTAMP_MS", "TIMESTAMP_S")) Types.TIMESTAMP else column.jdbcType
        val resolved = if (jdbc in setOf(Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE, Types.TIME, Types.TIME_WITH_TIMEZONE)) {
            val precision = when (name) {
                "TIMESTAMP", "TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ", "TIME", "TIME WITH TIME ZONE", "TIMETZ" -> 6
                "TIMESTAMP_NS" -> 9
                "TIMESTAMP_MS" -> 3
                "TIMESTAMP_S" -> 0
                else -> throw IllegalArgumentException("unregistered DuckDB temporal type: ${column.typeName}")
            }
            JdbcColumnType(jdbc, column.typeName, column.precision, precision, column.nullable)
        } else column
        return StandardJdbcTypes.resolve(factory, resolved)
    }
    override fun textIdentityEquals(builder: RelBuilder, left: RexNode, right: RexNode): RexNode = builder.equals(codeBytes(builder, left), codeBytes(builder, right))
    override fun textIdentityKey(builder: RelBuilder, value: RexNode): RexNode = codeBytes(builder, value)
    override fun expandCollection(outputType: RelDataType, tableAlias: String): CompilationSupport.CollectionExpansion = CompilationSupport.CollectionExpansion.Relation { collection ->
        val position = SqlParserPos.ZERO
        val element = outputType.fieldList[0]
        val ordinal = outputType.fieldList[1]
        val alias = tableAlias
        val unnest = SqlStdOperatorTable.UNNEST_WITH_ORDINALITY.createCall(position, collection)
        val source = SqlStdOperatorTable.AS.createCall(position, unnest, SqlIdentifier(alias, position),
            SqlIdentifier(element.name, position), SqlIdentifier(ordinal.name, position))
        val number = SqlStdOperatorTable.CAST.createCall(position, SqlIdentifier(listOf(alias, ordinal.name), position), dialect.getCastSpec(ordinal.type))
        CompilationSupport.CollectionRows(source, SqlIdentifier(listOf(alias, element.name), position), number)
    }
    private fun codeBytes(builder: RelBuilder, value: RexNode): RexNode {
        val binary = builder.typeFactory.createTypeWithNullability(builder.typeFactory.createSqlType(SqlTypeName.VARBINARY), value.type.isNullable)
        val function = object : SqlFunction("ENCODE", SqlKind.OTHER_FUNCTION, ReturnTypes.explicit(binary), null, OperandTypes.CHARACTER, SqlFunctionCategory.STRING) {
            override fun getStrongPolicyInference(): Supplier<Strong.Policy> = Supplier { Strong.Policy.ANY }
        }
        return builder.rexBuilder.makeCall(binary, function, listOf(value))
    }
    override fun numericFailure(value: SqlNode, type: RelDataType, position: SqlParserPos): SqlNode =
        SqlFunction("ERROR", SqlKind.OTHER_FUNCTION, ReturnTypes.ARG0, null, OperandTypes.CHARACTER, SqlFunctionCategory.SYSTEM).createCall(position, SqlLiteral.createCharString("numeric contract violation", position))
    override fun parameter(type: RelDataType, value: Any?): JdbcParameter {
        require(!FiniteDecimalTypes.isFinite(type)) { "DuckDB finite decimal parameters have no verified native mapping" }
        if (DuckdbNativeTypes.wideInteger(type)) {
            val integer = when (value) {
                is BigDecimal -> value.toBigIntegerExact()
                is BigInteger -> value
                is Long, is Int, is Short, is Byte -> BigInteger(value.toString())
                else -> throw IllegalArgumentException("HUGEINT parameter requires an exact integer")
            }
            return JdbcParameter.of(Types.OTHER, "HUGEINT", 39, 0, integer)
        }
        val name = if (type.sqlTypeName == SqlTypeName.TIMESTAMP) when (type.precision) {
            0 -> "TIMESTAMP_S"; 3 -> "TIMESTAMP_MS"; 6 -> "TIMESTAMP"; 9 -> "TIMESTAMP_NS"
            else -> throw IllegalArgumentException("DuckDB timestamp parameter requires a native fractional precision")
        } else type.sqlTypeName.name
        return JdbcParameter.of(type.sqlTypeName.jdbcOrdinal, name, type.precision, type.scale,
            requireNotNull(value) { "SQL NULL must remain a typed literal, not a bound parameter" })
    }
    override fun requireNativeCast(target: RelDataType) {
        require(!FiniteDecimalTypes.isFinite(target)) { "DuckDB finite decimal CAST has no verified exact native mapping" }
        require(target.sqlTypeName != SqlTypeName.TIMESTAMP || target.precision in setOf(0, 3, 6, 9)) { "DuckDB timestamp cast requires a native fractional precision: 0, 3, 6 or 9" }
    }
    override fun tablePath(catalog: String?, schema: String?, table: String): List<String> {
        require(!schema.isNullOrBlank() && table.isNotBlank()) { "exact JDBC coordinate is required" }
        return buildList { if (!catalog.isNullOrEmpty()) add(catalog); add(schema); add(table) }
    }
}
