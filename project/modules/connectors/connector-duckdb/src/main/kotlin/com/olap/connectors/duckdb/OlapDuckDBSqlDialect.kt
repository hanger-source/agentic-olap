package com.olap.connectors.duckdb

import com.olap.prototype.connector.FiniteDecimalTypes
import org.apache.calcite.avatica.util.TimeUnit
import org.apache.calcite.rel.RelFieldCollation
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeSystem
import org.apache.calcite.sql.*
import org.apache.calcite.sql.dialect.DuckDBSqlDialect
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect
import org.apache.calcite.sql.`fun`.SqlCase
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.type.OperandTypes
import org.apache.calcite.sql.type.ReturnTypes
import org.apache.calcite.sql.type.SqlTypeFamily
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.validate.SqlValidator
import org.apache.calcite.util.Litmus

/** Native SQL AST mappings; Calcite retains parameter, quoting and relation boundaries. */
internal object OlapDuckDBSqlDialect : DuckDBSqlDialect(DuckDBSqlDialect.DEFAULT_CONTEXT.withDataTypeSystem(DuckdbNativeTypes())) {
    private val elapsedTime = SqlBasicFunction.create("DATE_SUB", ReturnTypes.BIGINT_NULLABLE,
        OperandTypes.family(SqlTypeFamily.CHARACTER, SqlTypeFamily.DATETIME, SqlTypeFamily.DATETIME), SqlFunctionCategory.TIMEDATE)
    override fun quoteStringLiteral(builder: StringBuilder, charsetName: String?, value: String) {
        // DuckDB 的 Unicode 字符串使用普通引号，不接受 Calcite 的 _charset introducer。
        super.quoteStringLiteral(builder, null, value)
    }
    override fun getCastSpec(type: RelDataType): SqlNode? = when {
        FiniteDecimalTypes.isFinite(type) -> throw UnsupportedOperationException("DuckDB finite decimal CAST has no verified exact native mapping")
        type.sqlTypeName == SqlTypeName.ROW -> SqlDataTypeSpec(StructType(type), SqlParserPos.ZERO)
        type.sqlTypeName == SqlTypeName.ARRAY -> SqlDataTypeSpec(ArrayType(type), SqlParserPos.ZERO)
        DuckdbNativeTypes.wideInteger(type) -> SqlDataTypeSpec(SqlUserDefinedTypeNameSpec("HUGEINT", SqlParserPos.ZERO), SqlParserPos.ZERO)
        else -> super.getCastSpec(type)
    }
    private class StructType(private val type: RelDataType) : SqlTypeNameSpec(SqlIdentifier("STRUCT", SqlParserPos.ZERO), SqlParserPos.ZERO) {
        override fun unparse(writer: SqlWriter, leftPrec: Int, rightPrec: Int) {
            writer.keyword("STRUCT")
            val frame = writer.startList("(", ")")
            type.fieldList.forEach { field -> writer.sep(","); SqlIdentifier(field.name, SqlParserPos.ZERO).unparse(writer, 0, 0); requireNotNull(getCastSpec(field.type)) { "DuckDB STRUCT field has no native SQL type: ${field.type}" }.unparse(writer, 0, 0) }
            writer.endList(frame)
        }
        override fun deriveType(validator: SqlValidator): RelDataType = type
        override fun equalsDeep(other: SqlTypeNameSpec, litmus: Litmus): Boolean = if (other is StructType && type == other.type) litmus.succeed() else litmus.fail("different native STRUCT type")
    }
    private class ArrayType(private val type: RelDataType) : SqlTypeNameSpec(SqlIdentifier("ARRAY", SqlParserPos.ZERO), SqlParserPos.ZERO) {
        override fun unparse(writer: SqlWriter, leftPrec: Int, rightPrec: Int) { requireNotNull(getCastSpec(requireNotNull(type.componentType))) { "DuckDB ARRAY element has no native SQL type" }.unparse(writer, 0, 0); writer.keyword("[]") }
        override fun deriveType(validator: SqlValidator): RelDataType = type
        override fun equalsDeep(other: SqlTypeNameSpec, litmus: Litmus): Boolean = if (other is ArrayType && type == other.type) litmus.succeed() else litmus.fail("different native ARRAY type")
    }
    override fun unparseSqlIntervalQualifier(writer: SqlWriter, qualifier: SqlIntervalQualifier, typeSystem: RelDataTypeSystem) =
        super.unparseSqlIntervalQualifier(writer, SqlIntervalQualifier(qualifier.startUnit, qualifier.endUnit, qualifier.parserPosition), typeSystem)
    override fun supportsNestedAggregations(): Boolean = false
    override fun rewriteSingleValueExpr(aggregate: SqlNode, type: RelDataType): SqlNode {
        val converted = PostgresqlSqlDialect.DEFAULT.rewriteSingleValueExpr(aggregate, type) as SqlCase
        val failure = SqlBasicFunction.create("ERROR", ReturnTypes.VARCHAR_2000, OperandTypes.CHARACTER, SqlFunctionCategory.SYSTEM)
            .createCall(aggregate.parserPosition, SqlLiteral.createCharString("SINGLE_VALUE cardinality violation", aggregate.parserPosition))
        return SqlCase(converted.parserPosition, converted.valueOperand, converted.whenOperands, converted.thenOperands, failure)
    }
    override fun supportsCharSet(): Boolean = false
    override fun defaultNullDirection(direction: RelFieldCollation.Direction): RelFieldCollation.NullDirection = RelFieldCollation.NullDirection.UNSPECIFIED
    override fun unparseCall(writer: SqlWriter, call: SqlCall, leftPrec: Int, rightPrec: Int) {
        if (call.operator == Ops.CARDINALITY) {
            SqlBasicFunction.create("ARRAY_LENGTH", ReturnTypes.BIGINT_NULLABLE, OperandTypes.ARRAY, SqlFunctionCategory.SYSTEM)
                .createCall(call.parserPosition, call.operand<SqlNode>(0)).unparse(writer, leftPrec, rightPrec)
            return
        }
        if (call.kind == SqlKind.TIMESTAMP_ADD) {
            val unit = requireNotNull(call.operand<SqlLiteral>(0).getValueAs(TimeUnit::class.java))
            val base = when (unit) {
                TimeUnit.YEAR, TimeUnit.QUARTER, TimeUnit.MONTH -> TimeUnit.MONTH
                TimeUnit.WEEK, TimeUnit.DAY -> TimeUnit.DAY
                TimeUnit.HOUR, TimeUnit.MINUTE, TimeUnit.SECOND, TimeUnit.MILLISECOND, TimeUnit.MICROSECOND -> TimeUnit.SECOND
                else -> throw IllegalStateException("DuckDB calendar shift unit is unsupported: $unit")
            }
            val pos = call.parserPosition
            val interval = SqlLiteral.createInterval(1, unit.multiplier.divide(base.multiplier).toPlainString(), SqlIntervalQualifier(base, null, pos), pos)
            val offset = Ops.MULTIPLY.createCall(pos, call.operand<SqlNode>(1), interval)
            super.unparseCall(writer, Ops.PLUS.createCall(pos, call.operand<SqlNode>(2), offset), leftPrec, rightPrec)
            return
        }
        if (call.kind != SqlKind.TIMESTAMP_DIFF) { super.unparseCall(writer, call, leftPrec, rightPrec); return }
        val unit = requireNotNull(call.operand<SqlLiteral>(0).getValueAs(TimeUnit::class.java))
        check(unit in setOf(TimeUnit.YEAR, TimeUnit.QUARTER, TimeUnit.MONTH, TimeUnit.WEEK, TimeUnit.DAY, TimeUnit.HOUR, TimeUnit.MINUTE, TimeUnit.SECOND, TimeUnit.MILLISECOND, TimeUnit.MICROSECOND)) { "DuckDB elapsed time unit is unsupported: $unit" }
        super.unparseCall(writer, elapsedTime.createCall(call.parserPosition, SqlLiteral.createCharString(unit.name, call.parserPosition), call.operand<SqlNode>(1), call.operand<SqlNode>(2)), leftPrec, rightPrec)
    }
}
