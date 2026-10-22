package com.olap.prototype.engine.executable

import com.olap.prototype.engine.calcite.CalciteQueryCompiler
import com.olap.prototype.expression.CalciteCompilation
import com.olap.prototype.expression.CalciteSqlRendering
import com.olap.prototype.expression.CompletedRelationCompiler
import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.query.PostOperationContract
import com.olap.prototype.query.QueryStageAnalysis
import com.olap.prototype.query.QueryValue
import com.olap.prototype.query.ResultExpression
import org.apache.calcite.rex.RexNode
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.schema.Statistics
import org.apache.calcite.schema.impl.AbstractTable
import org.apache.calcite.sql.*
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable as Ops
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.type.OperandTypes
import org.apache.calcite.tools.Frameworks
import org.apache.calcite.util.ImmutableBitSet
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** 在真实原生结果中观察集合NULL、重复、记录字段与序号。 */
class CollectionExecutionTest {
    @Test fun expandsRecordColumnsThroughNativePositionsAndPreservesWholeNullRows() {
        val delegate = TestConnectors.registry.require(TestConnectors.ref("duckdb")).compilation
        val support = object : CompilationSupport by delegate {
            override fun expandCollection(collection: SqlNode, outputType: RelDataType, tableAlias: String): CompilationSupport.CollectionExpansion {
                // 与直接 UNNEST 元素不同的原生映射：只展开位置，元素在外层按下标读取。
                val position = SqlParserPos.ZERO
                fun call(name: String, vararg operands: SqlNode): SqlNode = SqlFunction(name, SqlKind.OTHER_FUNCTION, null, null,
                    OperandTypes.VARIADIC, SqlFunctionCategory.SYSTEM).createCall(position, *operands)
                val one = SqlLiteral.createExactNumeric("1", position)
                val positions = call("RANGE", one, Ops.PLUS.createCall(position, call("ARRAY_LENGTH", collection), one))
                val relation = Ops.AS.createCall(position, Ops.UNNEST.createCall(position, positions),
                    SqlIdentifier(tableAlias, position), SqlIdentifier("i", position))
                val index = SqlIdentifier(listOf(tableAlias, "i"), position)
                val element = Ops.CAST.createCall(position, Ops.ITEM.createCall(position, collection, index),
                    delegate.dialect.getCastSpec(outputType.fieldList[0].type))
                val ordinal = Ops.CAST.createCall(position, index, delegate.dialect.getCastSpec(outputType.fieldList[1].type))
                return CompilationSupport.CollectionExpansion(relation, element, ordinal)
            }
        }
        TestConnectors.open("duckdb", "jdbc:duckdb:").use { connection -> connection.createStatement().use { statement ->
            statement.execute("CREATE TABLE records(parent INTEGER PRIMARY KEY, unused VARCHAR, items STRUCT(code VARCHAR, number INTEGER)[])")
            statement.execute("INSERT INTO records VALUES (1,'padded',[{'code':'a','number':NULL},{'code':'b','number':2},{'code':'a','number':NULL},NULL,{'code':NULL,'number':NULL}]),(2,'padded',[]),(3,'padded',NULL),(4,'padded',[{'code':'z','number':9}])")
            for (mode in listOf("INNER", "OUTER")) {
                val schema = Frameworks.createRootSchema(false)
                schema.add("records", object : AbstractTable() {
                    override fun getRowType(types: RelDataTypeFactory): RelDataType {
                        val text = types.createTypeWithNullability(types.createSqlType(SqlTypeName.VARCHAR, 32), true)
                        val number = types.createTypeWithNullability(types.createSqlType(SqlTypeName.INTEGER), true)
                        val row = types.createTypeWithNullability(types.builder().add("code", text).add("number", number).build(), true)
                        val array = types.createTypeWithNullability(types.createArrayType(row, -1), true)
                        return types.builder().add("parent", types.createSqlType(SqlTypeName.INTEGER)).add("unused", text).add("items", array).build()
                    }
                    override fun getStatistic() = Statistics.of(4.0, listOf(ImmutableBitSet.of(0)))
                })
                val builder = CalciteCompilation.builder(support, schema).scan("records")
                val scope = QueryStageAnalysis.ScopeId.root()
                val origin = QueryStageAnalysis.OperationOrigin(scope, 0, 0, PostOperationContract.PROJECT)
                val analysis = QueryStageAnalysis.analyze(scope, listOf(QueryStageAnalysis.SourceColumn.categorical("parent", origin),
                    QueryStageAnalysis.SourceColumn.categorical("unused", origin),
                    QueryStageAnalysis.SourceColumn("items", origin, emptyList(), QueryValue.Imported(QueryStageAnalysis.SymbolId(scope, 100),
                        QueryValue.Array(QueryValue.Row(listOf(QueryValue.anonymous(), QueryValue.anonymous())))))),
                    QueryStageAnalysis.UniqueSourceKey(listOf("parent")), emptyMap(), listOf(
                        PostOperationContract.ExpandCollection("items", "record", "ordinal", mode),
                        PostOperationContract.Derive(ResultExpression.ResultRowField(ResultExpression.ResultSymbol("record"), 0), "code", "业务编码"),
                        PostOperationContract.Derive(ResultExpression.ResultRowField(ResultExpression.ResultSymbol("record"), 1), "number", "数值属性"),
                        PostOperationContract.Project(listOf("parent", "code", "number", "ordinal", "record")),
                        PostOperationContract.Sort(listOf(PostOperationContract.OrderKey("parent", "ASC", "FIRST"), PostOperationContract.OrderKey("ordinal", "ASC", "FIRST")))),
                    QueryStageAnalysis.IntrinsicPopulation(emptySet()), "record collection")
                CompletedRelationCompiler.compile(builder, analysis, linkedMapOf(), mutableListOf(), emptyMap(), mutableListOf())
                val plan = CalciteQueryCompiler.optimize(builder.build(), emptyList())
                val sql = CalciteSqlRendering.render(plan, support).sql
                println("[collection-sql indexed $mode] $sql")
                statement.executeQuery(sql).use { rows ->
                    val actual = buildList { while (rows.next()) add(listOf(rows.getInt(1), rows.getString(2), rows.getObject(3), rows.getObject(4), rows.getObject(5) == null)) }
                    val expected = listOf(listOf(1,"a",null,1,false), listOf(1,"b",2,2,false), listOf(1,"a",null,3,false),
                        listOf(1,null,null,4,true), listOf(1,null,null,5,false)) +
                        (if (mode == "OUTER") listOf(listOf(2,null,null,null,true), listOf(3,null,null,null,true)) else emptyList()) + listOf(listOf(4,"z",9,1,false))
                    assertEquals(expected, actual)
                }
            }
        } }
    }

    @Test fun expandsAndDisplaysActualArrayOccurrencesWithExplicitNullAndOrderRules() {
        val support = TestConnectors.registry.require(TestConnectors.ref("duckdb")).compilation
        for (mode in listOf("INNER", "OUTER")) {
            val builder = CalciteCompilation.builder(support)
            builder.values(arrayOf("parent"), 1, 2, 3, 4)
            val parent = builder.field("parent")
            val factory = builder.typeFactory
            val text = factory.createTypeWithNullability(factory.createSqlType(SqlTypeName.VARCHAR, 32), true)
            val arrayType = factory.createTypeWithNullability(factory.createArrayType(text, -1), true)
            val nullText = builder.rexBuilder.makeNullLiteral(text)
            fun array(vararg values: RexNode) = builder.rexBuilder.makeCall(arrayType, Ops.ARRAY_VALUE_CONSTRUCTOR, values.toList())
            val populated = array(builder.literal("b"), nullText, builder.literal("a"), builder.literal("a"))
            val items = builder.rexBuilder.makeCall(arrayType, Ops.CASE, listOf(
                builder.equals(parent, builder.literal(1)), populated,
                builder.equals(parent, builder.literal(2)), array(),
                builder.equals(parent, builder.literal(3)), builder.rexBuilder.makeNullLiteral(arrayType), array(nullText)))
            builder.project(listOf(parent, items), listOf("parent", "items"))
            val scope = QueryStageAnalysis.ScopeId.root()
            val origin = QueryStageAnalysis.OperationOrigin(scope, 0, 0, PostOperationContract.PROJECT)
            val operations = listOf(
                PostOperationContract.Derive(ResultExpression.ResultCollectionSort(ResultExpression.ResultSymbol("items"), "ASC", "LAST"), "sorted", "排序集合"),
                PostOperationContract.Derive(ResultExpression.ResultCollectionText(ResultExpression.ResultSymbol("sorted"), "|", "SKIP", null), "skip", "跳过未知元素"),
                PostOperationContract.Derive(ResultExpression.ResultCollectionText(ResultExpression.ResultSymbol("sorted"), "|", "REPLACE", "<n>"), "replace", "显示未知元素"),
                PostOperationContract.ExpandCollection("sorted", "element", "ordinal", mode),
                PostOperationContract.Project(listOf("parent", "element", "ordinal", "skip", "replace")),
                PostOperationContract.Sort(listOf(PostOperationContract.OrderKey("parent", "ASC", "FIRST"), PostOperationContract.OrderKey("ordinal", "ASC", "FIRST"))))
            val analysis = QueryStageAnalysis.analyze(scope, listOf(QueryStageAnalysis.SourceColumn.categorical("parent", origin),
                QueryStageAnalysis.SourceColumn("items", origin, emptyList(), QueryValue.Imported(QueryStageAnalysis.SymbolId(scope, 100), QueryValue.Array(QueryValue.anonymous())))),
                QueryStageAnalysis.UniqueSourceKey(listOf("parent")), emptyMap(), operations, QueryStageAnalysis.IntrinsicPopulation(emptySet()), "collections")
            CompletedRelationCompiler.compile(builder, analysis, linkedMapOf(), mutableListOf(), emptyMap(), mutableListOf())
            val sql = CalciteSqlRendering.render(builder.build(), support).sql
            println("[collection-sql $mode] $sql")
            TestConnectors.open("duckdb", "jdbc:duckdb:").use { connection -> connection.createStatement().use { statement -> statement.executeQuery(sql).use { rows ->
                var count = 0
                while (rows.next()) {
                    count++
                    when (val id = rows.getInt(1)) {
                        1 -> {
                            val ordinal = rows.getInt(3)
                            assertEquals(when { ordinal <= 2 -> "a"; ordinal == 3 -> "b"; else -> null }, rows.getString(2))
                            assertEquals("a|a|b", rows.getString(4)); assertEquals("a|a|b|<n>", rows.getString(5))
                        }
                        4 -> {
                            assertNull(rows.getString(2)); assertEquals(1, rows.getInt(3))
                            assertEquals("", rows.getString(4)); assertEquals("<n>", rows.getString(5))
                        }
                        else -> {
                            assertEquals("OUTER", mode); assertNull(rows.getObject(2)); assertNull(rows.getObject(3))
                            assertEquals(if (id == 2) "" else null, rows.getString(4)); assertEquals(if (id == 2) "" else null, rows.getString(5))
                        }
                    }
                }
                assertEquals(if (mode == "INNER") 5 else 7, count)
            } } }
        }
    }
}
