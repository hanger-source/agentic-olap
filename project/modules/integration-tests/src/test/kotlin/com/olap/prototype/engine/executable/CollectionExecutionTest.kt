package com.olap.prototype.engine.executable

import com.olap.prototype.engine.calcite.CalciteQueryCompiler
import com.olap.prototype.expression.CalciteCompilation
import com.olap.prototype.expression.CalciteSqlRendering
import com.olap.prototype.expression.CompletedRelationCompiler
import com.olap.prototype.expression.NativeExpressionScope
import com.olap.prototype.expression.SemanticCalciteTypeSystem
import com.olap.prototype.expression.CollectionExpansionRel
import com.olap.prototype.connector.CompilationSupport
import com.olap.prototype.connector.NativeCompilationCapabilityException
import com.olap.prototype.contract.JdbcParameter
import com.olap.prototype.integration.TestConnectors
import com.olap.prototype.query.GroupingLevels
import com.olap.prototype.query.PostOperationContract
import com.olap.prototype.query.QueryStageAnalysis
import com.olap.prototype.query.QueryValue
import com.olap.prototype.query.ResultAggregation
import com.olap.prototype.query.ResultExpression
import com.olap.prototype.query.ScalarValueType
import org.apache.calcite.rel.RelNode
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
import org.apache.calcite.tools.RelBuilder
import org.apache.calcite.util.ImmutableBitSet
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** 在真实原生结果中观察集合NULL、重复、记录字段与序号。 */
class CollectionExecutionTest {
    @Test fun collectsDistinctWholeRowsFromPhysicalObjectPopulationBeforeLeftAlignment() {
        val support = TestConnectors.registry.require(TestConnectors.ref("duckdb")).compilation
        TestConnectors.open("duckdb", "jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE activities(actor VARCHAR, object_id VARCHAR, period VARCHAR)")
                statement.execute("INSERT INTO activities VALUES ('A','o1','current'),('A','o1','current'),('A','o2','current'),('B','o3','current'),('C',NULL,'current'),('excluded','o8','old')")
                statement.execute("CREATE TABLE options(object_id VARCHAR, category VARCHAR, option_id VARCHAR, label VARCHAR, edition VARCHAR)")
                statement.execute("INSERT INTO options VALUES ('o1','first','x1','same','current'),('o1','first','x1','same','current'),('o2','first','x2','same','current'),('o1','first','x3',NULL,'current'),('o1','second','y1','','current'),('o2','second','y2',NULL,'current'),('o3','first','b1','solo','current'),('o9','first','outside','unrelated','current'),('o1','first','old','obsolete','old')")
            }
            val schema = Frameworks.createRootSchema(false)
            schema.add("activities", physicalTextTable("actor", "object_id", "period"))
            schema.add("options", physicalTextTable("object_id", "category", "option_id", "label", "edition"))
            val builder = CalciteCompilation.builder(support, schema)
            val parameters = mutableListOf<JdbcParameter>()
            val root = QueryStageAnalysis.ScopeId.root()
            fun symbol(name: String) = ResultExpression.ResultSymbol(name)
            fun equal(name: String, value: String) = ResultExpression.ResultOperation(ResultExpression.ResultOperator.EQ,
                listOf(symbol(name), ResultExpression.ResultTypedLiteral(ScalarValueType.TEXT, value)))
            fun joinEqual(left: String, right: String) = ResultExpression.ResultOperation(ResultExpression.ResultOperator.EQ,
                listOf(symbol(left), symbol(right)))
            val active = PostOperationContract.PostFilter(equal("period", "current"))
            fun objectPopulation(scope: QueryStageAnalysis.ScopeId): Pair<QueryStageAnalysis.Analysis, RelNode> {
                builder.scan("activities")
                return completePhysicalCollection(builder, scope, listOf(active,
                    PostOperationContract.PostFilter(ResultExpression.ResultOperation(ResultExpression.ResultOperator.IS_NOT_NULL, listOf(symbol("object_id")))),
                    PostOperationContract.Regroup(PostOperationContract.ResultGrouping(listOf("actor", "object_id"), listOf(
                        PostOperationContract.ResultAggregate("object_id", ResultAggregation.Minimum(ResultAggregation.NullPolicy.IGNORE), "retained_object", "对象身份")), null)),
                    PostOperationContract.Project(listOf("actor", "object_id"))), parameters)
            }
            val objects = objectPopulation(root.child("objects"))
            assertEquals(listOf(listOf("A", "o1"), listOf("A", "o2"), listOf("B", "o3")).groupingBy { it }.eachCount(),
                physicalCollectionRows(connection, objects.second, support, parameters).groupingBy { it }.eachCount())
            fun optionCollection(category: String): Pair<QueryStageAnalysis.Analysis, RelNode> {
                val scope = root.child(category)
                val selectedObjects = objectPopulation(scope.child("objects"))
                builder.scan("options")
                return completePhysicalCollection(builder, scope, listOf(
                    PostOperationContract.PostFilter(ResultExpression.ResultOperation(ResultExpression.ResultOperator.AND,
                        listOf(equal("edition", "current"), equal("category", category)))),
                    PostOperationContract.JoinInput("objects", "selected", "INNER", joinEqual("object_id", "selected__object_id")),
                    PostOperationContract.Derive(symbol("selected__actor"), "actor", "主体"),
                    PostOperationContract.Derive(ResultExpression.ResultRow(listOf(symbol("category"), symbol("option_id"), symbol("label"))), "option_row", "完整选项身份与显示"),
                    PostOperationContract.Regroup(PostOperationContract.ResultGrouping(listOf("actor"), listOf(
                        PostOperationContract.ResultAggregate("option_row", ResultAggregation.Collect(true), "tuples", "去重完整选项集合")), null))),
                    parameters, mapOf("objects" to selectedObjects))
            }
            val first = optionCollection("first")
            val second = optionCollection("second")
            builder.scan("activities")
            val completed = completePhysicalCollection(builder, root, listOf(active,
                PostOperationContract.Regroup(PostOperationContract.ResultGrouping(listOf("actor"), listOf(
                    PostOperationContract.ResultAggregate("object_id", ResultAggregation.Count(true, ResultAggregation.NullPolicy.IGNORE), "object_count", "不同对象数"),
                    PostOperationContract.ResultAggregate("actor", ResultAggregation.Count(false, ResultAggregation.NullPolicy.IGNORE), "activity_count", "原活动数")), null)),
                PostOperationContract.JoinInput("first", "first", "LEFT", joinEqual("actor", "first__actor")),
                PostOperationContract.JoinInput("second", "second", "LEFT", joinEqual("actor", "second__actor")),
                PostOperationContract.Derive(ResultExpression.ResultOperation(ResultExpression.ResultOperator.IS_NOT_NULL, listOf(symbol("first__tuples"))), "has_first", "存在第一类集合"),
                PostOperationContract.Derive(ResultExpression.ResultOperation(ResultExpression.ResultOperator.IS_NOT_NULL, listOf(symbol("second__tuples"))), "has_second", "存在第二类集合"),
                PostOperationContract.Project(listOf("actor", "object_count", "activity_count", "first__tuples", "second__tuples", "has_first", "has_second")),
                PostOperationContract.Sort(listOf(PostOperationContract.OrderKey("actor", "ASC", "LAST")))),
                parameters, mapOf("first" to first, "second" to second)).second
            for (name in listOf("first__tuples", "second__tuples")) {
                val type = requireNotNull(completed.rowType.getField(name, true, false)).type
                assertEquals(SqlTypeName.ARRAY, type.sqlTypeName)
                val element = requireNotNull(type.componentType)
                assertEquals(SqlTypeName.ROW, element.sqlTypeName)
                assertEquals(listOf(SqlTypeName.VARCHAR, SqlTypeName.VARCHAR, SqlTypeName.VARCHAR), element.fieldList.map { it.type.sqlTypeName })
                assertTrue(element.fieldList[2].type.isNullable)
            }
            val rows = physicalCollectionRows(connection, completed, support, parameters)
            assertEquals(listOf("A", "B", "C"), rows.map { it[0] })
            assertEquals(listOf(2L, 1L, 0L), rows.map { (it[1] as Number).toLong() })
            assertEquals(listOf(3L, 1L, 1L), rows.map { (it[2] as Number).toLong() })
            val firstTuples = rows[0][3] as List<*>
            val secondTuples = rows[0][4] as List<*>
            // 比较多重集，既保留同显示的不同身份，也能发现未去除的重复完整ROW。
            assertEquals(listOf(listOf("first", "x1", "same"), listOf("first", "x2", "same"), listOf("first", "x3", null)).groupingBy { it }.eachCount(), firstTuples.groupingBy { it }.eachCount())
            assertEquals(listOf(listOf("second", "y1", ""), listOf("second", "y2", null)).groupingBy { it }.eachCount(), secondTuples.groupingBy { it }.eachCount())
            assertEquals(listOf(true, true), rows[0].takeLast(2))
            assertEquals(listOf(listOf("first", "b1", "solo")), rows[1][3]); assertNull(rows[1][4])
            assertEquals(listOf(true, false), rows[1].takeLast(2))
            assertNull(rows[2][3]); assertNull(rows[2][4]); assertEquals(listOf(false, false), rows[2].takeLast(2))
        }
    }

    @Test fun preservesEmptyGroupingAndPropagatingScalarAlongsideCollections() {
        val support = TestConnectors.registry.require(TestConnectors.ref("duckdb")).compilation
        TestConnectors.open("duckdb", "jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE observations(actor VARCHAR, item_id VARCHAR, label VARCHAR)")
                statement.execute("INSERT INTO observations VALUES ('A','a1','x'),('A','a2',NULL),('B','b1','y'),('B','b2','z')")
            }
            val schema = Frameworks.createRootSchema(false)
            schema.add("observations", physicalTextTable("actor", "item_id", "label"))
            val builder = CalciteCompilation.builder(support, schema)
            val row = PostOperationContract.Derive(ResultExpression.ResultRow(listOf(
                ResultExpression.ResultSymbol("item_id"), ResultExpression.ResultSymbol("label"))), "item", "完整观察记录")
            val aggregates = listOf(
                PostOperationContract.ResultAggregate("item", ResultAggregation.Collect(false), "items", "观察记录集合"),
                PostOperationContract.ResultAggregate("label", ResultAggregation.Maximum(ResultAggregation.NullPolicy.PROPAGATE), "maximum", "未知传播的最大值"))
            fun execute(name: String, empty: Boolean, groupBy: List<String>, levels: GroupingLevels.Bound? = null): List<List<Any?>> {
                val parameters = mutableListOf<JdbcParameter>()
                builder.scan("observations")
                val operations: List<PostOperationContract.ValidatedOperation> = listOf(row) + (if (empty) listOf(PostOperationContract.PostFilter(ResultExpression.ResultLiteral(false))) else emptyList()) +
                    PostOperationContract.Regroup(PostOperationContract.ResultGrouping(groupBy, aggregates, levels)) +
                    (if (groupBy.isNotEmpty()) listOf(PostOperationContract.Sort(listOf(PostOperationContract.OrderKey("actor", "ASC", "LAST")))) else emptyList())
                val plan = completePhysicalCollection(builder, QueryStageAnalysis.ScopeId.root().child(name), operations, parameters).second
                return physicalCollectionRows(connection, plan, support, parameters)
            }
            val grouped = execute("grouped", false, listOf("actor"))
            assertEquals(listOf("A", "B"), grouped.map { it[0] })
            assertEquals(listOf(listOf("a1", "x"), listOf("a2", null)).groupingBy { it }.eachCount(), (grouped[0][1] as List<*>).groupingBy { it }.eachCount())
            assertEquals(listOf(listOf("b1", "y"), listOf("b2", "z")).groupingBy { it }.eachCount(), (grouped[1][1] as List<*>).groupingBy { it }.eachCount())
            assertNull(grouped[0][2]); assertEquals("z", grouped[1][2])
            assertEquals(emptyList<List<Any?>>(), execute("empty_grouped", true, listOf("actor")))
            assertEquals(listOf(listOf(null, null)), execute("empty_global", true, emptyList()))
            val levels = GroupingLevels("level", listOf(GroupingLevels.Level("actor", listOf("actor")), GroupingLevels.Level("total", emptyList()))).bind(listOf("actor"))
            val emptyLevels = execute("empty_levels", true, listOf("actor"), levels)
            assertEquals(1, emptyLevels.size)
            assertNull(emptyLevels[0][0]); assertEquals(1L, (emptyLevels[0][1] as Number).toLong())
            assertNull(emptyLevels[0][2]); assertNull(emptyLevels[0][3])
        }
    }

    private fun physicalTextTable(vararg columns: String) = object : AbstractTable() {
        override fun getRowType(types: RelDataTypeFactory): RelDataType = types.builder().also { row ->
            columns.forEach { row.add(it, types.createTypeWithNullability(types.createSqlType(SqlTypeName.VARCHAR, 32), true)) }
        }.build()
    }

    private fun completePhysicalCollection(builder: RelBuilder, scope: QueryStageAnalysis.ScopeId,
        operations: List<PostOperationContract.ValidatedOperation>, parameters: MutableList<JdbcParameter>,
        inputs: Map<String, Pair<QueryStageAnalysis.Analysis, RelNode>> = emptyMap()): Pair<QueryStageAnalysis.Analysis, RelNode> {
        val columns = builder.peek().rowType.fieldNames.mapIndexed { index, name -> QueryStageAnalysis.SourceColumn.categorical(name,
            QueryStageAnalysis.OperationOrigin(scope, 0, index, PostOperationContract.PROJECT)) }
        val analysis = QueryStageAnalysis.analyze(scope, columns, QueryStageAnalysis.UnknownSourceKey.INSTANCE,
            inputs.mapValues { QueryStageAnalysis.Child("RELATION", it.value.first) }, operations,
            QueryStageAnalysis.IntrinsicPopulation(emptySet()), "physical collections")
        val support = (builder.typeFactory.typeSystem as SemanticCalciteTypeSystem).nativeSupport
        val native = NativeExpressionScope(TestConnectors.ref("duckdb"), support) { null }
        CompletedRelationCompiler.compile(builder, analysis, linkedMapOf(), parameters, inputs.mapValues { it.value.second }, mutableListOf(), native)
        return analysis to builder.build()
    }

    private fun physicalCollectionRows(connection: java.sql.Connection, relation: RelNode, support: CompilationSupport,
        parameters: List<JdbcParameter>): List<List<Any?>> {
        val plan = CalciteQueryCompiler.optimize(relation, parameters)
        val sql = CalciteSqlRendering.render(plan, support, parameters)
        println("[collection-sql physical] ${sql.sql}")
        return connection.prepareStatement(sql.sql).use { statement ->
            CalciteSqlRendering.orderedParameters(sql, parameters).forEachIndexed { index, parameter -> parameter.bind(statement, index + 1) }
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add((1..rows.metaData.columnCount).map { index ->
                    when (val value = rows.getObject(index)) {
                        is java.sql.Array -> try { (value.array as Array<*>).map { element ->
                            assertTrue(element is java.sql.Struct, "集合元素必须由真实ROW返回")
                            (element as java.sql.Struct).attributes.toList()
                        } } finally { value.free() }
                        else -> value
                    }
                })
            } }
        }
    }

    private fun nativePositions(projection: Boolean, outer: Boolean): CompilationSupport {
        val delegate = TestConnectors.registry.require(TestConnectors.ref("duckdb")).compilation
        return object : CompilationSupport by delegate {
            override fun supports(capability: CompilationSupport.Capability): Boolean =
                if (capability == CompilationSupport.Capability.COLLECTION_PROJECTION_OUTER) projection && outer else delegate.supports(capability)
            override fun expandCollection(outputType: RelDataType, tableAlias: String): CompilationSupport.CollectionExpansion {
                val position = SqlParserPos.ZERO
                fun call(name: String, vararg operands: SqlNode): SqlNode = SqlFunction(name, SqlKind.OTHER_FUNCTION, null, null,
                    OperandTypes.VARIADIC, SqlFunctionCategory.SYSTEM).createCall(position, *operands)
                val one = SqlLiteral.createExactNumeric("1", position)
                // 投影映射用偏移后的原生位置，核验核心不会把生成值直接当作业务序号。
                val lower = SqlLiteral.createExactNumeric(if (projection) "7" else "1", position)
                fun positions(array: SqlNode): SqlNode = call("RANGE", lower, Ops.PLUS.createCall(position, call("ARRAY_LENGTH", array), lower))
                fun values(array: SqlNode, index: SqlNode): CompilationSupport.CollectionValues {
                    val ordinal = Ops.PLUS.createCall(position, Ops.MINUS.createCall(position, index, lower), one)
                    return CompilationSupport.CollectionValues(
                        Ops.CAST.createCall(position, Ops.ITEM.createCall(position, array, ordinal), delegate.dialect.getCastSpec(outputType.fieldList[0].type)),
                        Ops.CAST.createCall(position, ordinal, delegate.dialect.getCastSpec(outputType.fieldList[1].type)))
                }
                return if (projection) CompilationSupport.CollectionExpansion.InputProjection(
                    generate = { array -> Ops.UNNEST.createCall(position, positions(array)) }, values = ::values)
                else CompilationSupport.CollectionExpansion.Relation { array ->
                    val relation = Ops.AS.createCall(position, Ops.UNNEST.createCall(position, positions(array)),
                        SqlIdentifier(tableAlias, position), SqlIdentifier("i", position))
                    val selected = values(array, SqlIdentifier(listOf(tableAlias, "i"), position))
                    CompilationSupport.CollectionRows(relation, selected.element, selected.ordinal)
                }
            }
        }
    }

    @Test fun expandsRecordColumnsThroughNativePositionsAndPreservesWholeNullRows() {
        for (projection in listOf(false, true)) recordExpansion(projection)
    }

    private fun recordExpansion(projection: Boolean) {
        TestConnectors.open("duckdb", "jdbc:duckdb:").use { connection -> connection.createStatement().use { statement ->
            statement.execute("CREATE TABLE records(parent INTEGER PRIMARY KEY, unused VARCHAR, items STRUCT(code VARCHAR, number INTEGER)[])")
            statement.execute("INSERT INTO records VALUES (1,'padded',[{'code':'a','number':NULL},{'code':'b','number':2},{'code':'a','number':NULL},NULL,{'code':NULL,'number':NULL}]),(2,'padded',[]),(3,'padded',NULL),(4,'padded',[{'code':'z','number':9}])")
            for (aggregateInput in listOf(false, true)) for (mode in listOf("INNER", "OUTER")) {
                val support = nativePositions(projection, outer = mode == "OUTER")
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
                if (aggregateInput) {
                    // 完整 ARRAY 输入来自真实表上的聚合；每个完整主键的一份数组按原样保留。
                    builder.aggregate(builder.groupKey("parent", "unused"),
                        builder.aggregateCall(support.nativeAggregate(SqlKind.ARRAY_AGG), builder.field("items")).`as`("batches"))
                    builder.project(listOf(builder.field("parent"), builder.field("unused"),
                        builder.call(Ops.ITEM, builder.field("batches"), builder.literal(1))), listOf("parent", "unused", "items"))
                }
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
                CompletedRelationCompiler.compile(builder, analysis, linkedMapOf(), mutableListOf(), emptyMap(), mutableListOf(),
                    NativeExpressionScope(TestConnectors.ref("duckdb"), support) { null })
                val plan = CalciteQueryCompiler.optimize(builder.build(), emptyList())
                val sql = CalciteSqlRendering.render(plan, support).sql
                println("[collection-sql indexed projection=$projection aggregate=$aggregateInput $mode] $sql")
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

    @Test fun rejectsProjectionOuterBeforeSourceWhenSingletonMappingIsNotDeclared() {
        val support = nativePositions(projection = true, outer = false)
        val builder = CalciteCompilation.builder(support).values(arrayOf("parent"), 1)
        val array = builder.typeFactory.createArrayType(builder.typeFactory.createSqlType(SqlTypeName.INTEGER), -1)
        builder.project(listOf(builder.field("parent"), builder.rexBuilder.makeNullLiteral(array)), listOf("parent", "items"))
        val failure = assertThrows(NativeCompilationCapabilityException::class.java) {
            CalciteSqlRendering.render(CollectionExpansionRel(builder.build(), 1, "element", "ordinal", true), support)
        }
        assertEquals(CompilationSupport.Capability.COLLECTION_PROJECTION_OUTER, failure.capability)
        assertEquals("EXPAND_COLLECTION_OUTER", failure.operation)
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
            CompletedRelationCompiler.compile(builder, analysis, linkedMapOf(), mutableListOf(), emptyMap(), mutableListOf(),
                NativeExpressionScope(TestConnectors.ref("duckdb"), support) { null })
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
