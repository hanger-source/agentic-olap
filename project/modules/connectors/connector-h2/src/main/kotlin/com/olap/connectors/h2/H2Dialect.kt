package com.olap.connectors.h2

import org.apache.calcite.avatica.util.TimeUnitRange
import org.apache.calcite.rel.RelFieldCollation
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.sql.*
import org.apache.calcite.sql.dialect.H2SqlDialect
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect
import org.apache.calcite.sql.`fun`.SqlCase
import org.apache.calcite.sql.`fun`.SqlFloorFunction
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.type.SqlTypeName

internal object H2Dialect {
    fun create(): SqlDialect = object : H2SqlDialect(H2SqlDialect.DEFAULT_CONTEXT.withDataTypeSystem(H2NativeTypes())) {
        override fun getCastSpec(type: RelDataType): SqlNode? {
            if (type.sqlTypeName != SqlTypeName.ROW) return super.getCastSpec(type)
            val pos = SqlParserPos.ZERO
            // H2 row fields reject the default explicit NULL modifiers.
            return SqlDataTypeSpec(SqlRowTypeNameSpec(pos, type.fieldList.map { SqlIdentifier(it.name, pos) }, type.fieldList.map { (getCastSpec(it.type) as SqlDataTypeSpec).withNullable(null) }), pos)
        }
        override fun supportsNestedAggregations(): Boolean = false
        override fun unparseCall(writer: SqlWriter, call: SqlCall, leftPrec: Int, rightPrec: Int) {
            if (call.kind == SqlKind.FLOOR && call.operandCount() == 2) {
                val literal = call.operand<SqlLiteral>(1)
                val unit = literal.getValueAs(TimeUnitRange::class.java)
                val native = SqlFloorFunction.replaceTimeUnitOperand(call, if (unit == TimeUnitRange.WEEK) "ISO_WEEK" else requireNotNull(unit).name, literal.parserPosition)
                SqlFloorFunction.unparseDatetimeFunction(writer, native, "DATE_TRUNC", false)
            } else super.unparseCall(writer, call, leftPrec, rightPrec)
        }
        override fun rewriteSingleValueExpr(aggregate: SqlNode, type: RelDataType): SqlNode {
            val converted = PostgresqlSqlDialect.DEFAULT.rewriteSingleValueExpr(aggregate, type) as SqlCase
            val pos = aggregate.parserPosition
            val count = Ops.COUNT.createCall(pos, SqlIdentifier.STAR)
            val text = Ops.CONCAT.createCall(pos, SqlLiteral.createCharString("SINGLE_VALUE cardinality violation:", pos), Ops.CAST.createCall(pos, count, SqlDataTypeSpec(SqlBasicTypeNameSpec(SqlTypeName.VARCHAR, 64, pos), pos)))
            val failure = Ops.CAST.createCall(pos, Ops.CAST.createCall(pos, text, SqlDataTypeSpec(SqlBasicTypeNameSpec(SqlTypeName.INTEGER, pos), pos)), getCastSpec(type))
            return SqlCase(pos, converted.valueOperand, converted.whenOperands, converted.thenOperands, failure)
        }
        override fun defaultNullDirection(direction: RelFieldCollation.Direction): RelFieldCollation.NullDirection = RelFieldCollation.NullDirection.UNSPECIFIED
    }
}
